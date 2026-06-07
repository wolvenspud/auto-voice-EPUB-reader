package com.autovice.reader.tts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autovice.audio.WavProcessor
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.reader.domain.model.AttributionSource
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.domain.model.VoiceProfile
import com.autovice.reader.domain.model.VoiceProfileIds
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class SynthesisWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val segmentRepository: SegmentRepository,
    private val voiceRepository: CharacterVoiceRepository,
    private val engineRegistry: VoiceEngineRegistry,
    private val preferencesRepository: ReaderPreferencesRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, -1)
        if (chapterIndex < 0) return@withContext Result.failure()

        val usedEngineIds = mutableSetOf(VoiceEngineId.ANDROID_TTS)
        try {
            val segments = segmentRepository.getChapterSegments(bookId, chapterIndex)
            if (segments.isEmpty()) return@withContext Result.success()

            val profiles = voiceRepository.getVoicesForBook(bookId)
            val profileMap = profiles.associateBy { it.profileId }
            val bookIdShort = VoiceProfileIds.shortBookId(bookId)
            val narratorProfile = profileMap[VoiceProfileIds.narrator(bookIdShort)] ?: defaultProfile()
            // Master switch: when character attribution is off, everything reads in the narrator
            // voice (typically device TTS), so synthesis ignores per-character assignments entirely.
            val attributionEnabled = preferencesRepository.preferences.first().characterAttributionEnabled

            // Initialise every engine the chapter's profiles reference (plus the always-present
            // device-TTS fallback). Each engine self-manages its threading inside initialise().
            usedEngineIds += profileMap.values.map { it.voiceEngineId }
            val readyEngineIds = mutableSetOf<String>()
            for (engineId in usedEngineIds) {
                if (engineRegistry.engineFor(engineId).initialise()) readyEngineIds.add(engineId)
                else android.util.Log.w("SynthesisWorker", "Engine '$engineId' failed to initialise")
            }
            if (VoiceEngineId.ANDROID_TTS !in readyEngineIds) return@withContext Result.failure(
                workDataOf(KEY_ERROR to "TTS engine failed to initialise")
            )

            val processedDir = File(context.filesDir, "epubs/$bookId/processed").also { it.mkdirs() }
            val tempDir = File(processedDir, "tmp_synth").also { it.mkdirs() }
            // Discard WAVs from a previous run and any stale partial audio file.
            tempDir.listFiles()?.forEach { it.delete() }
            // Clear any partial files (incl. versioned) from a previous run.
            processedDir.listFiles()?.filter {
                it.name.startsWith("ch_${chapterIndex}_partial")
            }?.forEach { it.delete() }

            val segmentWavFiles = mutableListOf<Pair<String, File>>()
            var cumulativeMs = 0L
            var partialPathEmitted: String? = null
            var partialVersion = 0
            var nextPartialThreshold = INITIAL_BATCH

            segments.forEachIndexed { i, segment ->
                // Always carry the partial path once known — WorkManager conflates progress updates,
                // so a one-off emission can be dropped before the observer ever sees it.
                setProgressAsync(workDataOf(
                    KEY_PROGRESS to i.toFloat() / segments.size,
                    KEY_PARTIAL_AUDIO_PATH to partialPathEmitted,
                ))

                if (segment.audioStartMs >= 0 && !segment.needsResynthesis) {
                    val existingFile = File(tempDir, "seg_${segment.segmentIndex}.wav")
                    if (existingFile.exists()) {
                        segmentWavFiles.add(Pair(segment.spanId, existingFile))
                        val info = WavProcessor.readInfo(existingFile)
                        cumulativeMs += WavProcessor.durationMs(info)
                        return@forEachIndexed
                    }
                }

                // Only use character voices when attribution is enabled AND has been verified by the
                // LLM (heuristic attribution frequently produces garbage speaker names). Otherwise the
                // narrator *profile* (not a hard-coded default) is used so its engine/voice applies.
                val profile = when {
                    !attributionEnabled -> narratorProfile
                    segment.attributionSource == AttributionSource.LLM ||
                        segment.attributionSource == AttributionSource.USER ->
                        profileMap[segment.voiceProfileId] ?: narratorProfile
                    else -> narratorProfile
                }
                // Dispatch to the profile's engine, falling back to device TTS if it didn't init.
                val engineId = profile.voiceEngineId.takeIf { it in readyEngineIds } ?: VoiceEngineId.ANDROID_TTS
                val engine = engineRegistry.engineFor(engineId)
                val tempWav = File(tempDir, "seg_${segment.segmentIndex}.wav")
                // Feed the engine the reading-corrected text when present (ambiguous kanji spelled in
                // kana); the on-screen text is unchanged.
                val ttsText = segment.ttsTextOverride?.takeIf { it.isNotBlank() } ?: segment.rawText
                var duration = engine.synthesiseToFile(ttsText, profile, tempWav)
                // A transient engine failure (e.g. a VOICEVOX network hiccup) must not silently drop
                // the sentence — that leaves a hole in the audio and desyncs the highlight. Retry on
                // device TTS so every segment still gets spoken and the timeline stays contiguous.
                if ((duration < 0 || !tempWav.exists()) && engineId != VoiceEngineId.ANDROID_TTS) {
                    android.util.Log.w("SynthesisWorker", "Engine '$engineId' failed seg ${segment.segmentIndex}; falling back to device TTS")
                    duration = engineRegistry.engineFor(VoiceEngineId.ANDROID_TTS)
                        .synthesiseToFile(ttsText, profile, tempWav)
                }
                if (duration < 0 || !tempWav.exists()) {
                    // Even the fallback failed: park a zero-length slot at the current offset so a
                    // stale timestamp from a previous run can't mis-highlight this segment.
                    segmentRepository.updateAudioTimestamp(segment.spanId, cumulativeMs, 0)
                    return@forEachIndexed
                }

                val rawWavBytes = tempWav.readBytes()
                val rawInfo = WavProcessor.readInfo(tempWav)
                val pcmOnly = rawWavBytes.copyOfRange(rawWavBytes.size - rawInfo.dataBytes, rawWavBytes.size)
                val trimmedPcm = WavProcessor.trimSilence(pcmOnly)
                // Normalise to a canonical format so segments from different TTS voices still concatenate,
                // then fade the edges to avoid clicks where trimmed segments abut.
                val normPcm = WavProcessor.applyEdgeFades(
                    WavProcessor.normalisePcm16(trimmedPcm, rawInfo.sampleRate, rawInfo.channels),
                    WavProcessor.CANONICAL_SAMPLE_RATE,
                )
                // Append a short silence after each segment so consecutive utterances don't
                // click when abutted and natural inter-sentence spacing is preserved.
                val finalPcm = WavProcessor.appendSilence(normPcm, WavProcessor.CANONICAL_SAMPLE_RATE, 40)
                val header = WavProcessor.buildWavHeader(
                    WavProcessor.CANONICAL_SAMPLE_RATE,
                    WavProcessor.CANONICAL_CHANNELS,
                    WavProcessor.CANONICAL_BITS,
                    finalPcm.size,
                )
                tempWav.writeBytes(header + finalPcm)
                val trimmedInfo = WavProcessor.readInfo(tempWav)
                val trimmedDuration = WavProcessor.durationMs(trimmedInfo)

                segmentRepository.updateAudioTimestamp(segment.spanId, cumulativeMs, trimmedDuration)
                cumulativeMs += trimmedDuration
                segmentWavFiles.add(Pair(segment.spanId, tempWav))

                // Periodically encode a growing partial chapter file so the reader starts playing
                // quickly and keeps playing as more audio is synthesised. Each version uses a new
                // filename so the player reloads it (seamlessly, position preserved) rather than
                // keeping the stale shorter file. Stops once we reach the final segment (full file
                // is written below).
                if (segmentWavFiles.size >= nextPartialThreshold && i < segments.size - 1) {
                    runCatching {
                        val version = ++partialVersion
                        val pWav = File(processedDir, "ch_${chapterIndex}_partial_v$version.wav")
                        val pAac = File(processedDir, "ch_${chapterIndex}_partial_v$version.aac")
                        AudioConcatenator.concatenateWavs(segmentWavFiles.map { it.second }, pWav)
                        AacEncoder.encodeWavToAac(pWav, pAac)
                        pWav.delete()
                        // Remove the previous version once the new one is ready.
                        partialPathEmitted?.let { old -> File(old).delete() }
                        partialPathEmitted = pAac.absolutePath
                        nextPartialThreshold = segmentWavFiles.size + PARTIAL_GROW_STEP
                        setProgressAsync(workDataOf(
                            KEY_PROGRESS to segmentWavFiles.size.toFloat() / segments.size,
                            KEY_PARTIAL_AUDIO_PATH to pAac.absolutePath,
                        ))
                    }.onFailure { e -> android.util.Log.w("SynthesisWorker", "Partial encode failed", e) }
                }
            }

            val chapterWav = File(processedDir, "ch_$chapterIndex.wav")
            AudioConcatenator.concatenateWavs(segmentWavFiles.map { it.second }, chapterWav)

            val chapterAac = File(processedDir, "ch_$chapterIndex.aac")
            AacEncoder.encodeWavToAac(chapterWav, chapterAac)

            chapterWav.delete()
            tempDir.listFiles()?.forEach { it.delete() }
            // The full file supersedes every partial; remove them.
            processedDir.listFiles()?.filter {
                it.name.startsWith("ch_${chapterIndex}_partial")
            }?.forEach { it.delete() }

            Result.success(workDataOf(KEY_AUDIO_PATH to chapterAac.absolutePath))
        } catch (e: Exception) {
            android.util.Log.e("SynthesisWorker", "Synthesis failed", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Synthesis failed")))
        } finally {
            usedEngineIds.forEach { runCatching { engineRegistry.engineFor(it).release() } }
        }
    }

    private fun defaultProfile() = VoiceProfile(
        profileId = "default",
        characterName = "Narrator",
        tier = com.autovice.reader.domain.model.CharacterTier.SYSTEM,
    )

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_CHAPTER_INDEX = "chapterIndex"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_AUDIO_PATH = "audioPath"
        const val KEY_PARTIAL_AUDIO_PATH = "partialAudioPath"
        /** Segments to synthesise before the first playable partial is emitted. */
        private const val INITIAL_BATCH = 8
        /** Additional segments between subsequent (growing) partial re-encodes. */
        private const val PARTIAL_GROW_STEP = 12
    }
}
