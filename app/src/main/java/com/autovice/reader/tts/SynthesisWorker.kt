package com.autovice.reader.tts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autovice.audio.WavProcessor
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.reader.domain.model.VoiceProfile
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class SynthesisWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val segmentRepository: SegmentRepository,
    private val voiceRepository: CharacterVoiceRepository,
    private val voiceEngine: VoiceEngine,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, -1)
        if (chapterIndex < 0) return@withContext Result.failure()

        try {
            val engineReady = withContext(Dispatchers.Main) { voiceEngine.initialise() }
            if (!engineReady) return@withContext Result.failure(
                workDataOf(KEY_ERROR to "TTS engine failed to initialise")
            )

            val segments = segmentRepository.getChapterSegments(bookId, chapterIndex)
            if (segments.isEmpty()) return@withContext Result.success()

            val profiles = voiceRepository.getVoicesForBook(bookId)
            val profileMap = profiles.associateBy { it.profileId }

            val processedDir = File(context.filesDir, "epubs/$bookId/processed").also { it.mkdirs() }
            val tempDir = File(processedDir, "tmp_synth").also { it.mkdirs() }

            val segmentWavFiles = mutableListOf<Pair<String, File>>()
            var cumulativeMs = 0L

            segments.forEachIndexed { i, segment ->
                setProgressAsync(workDataOf(
                    KEY_PROGRESS to i.toFloat() / segments.size,
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

                val profile = profileMap[segment.voiceProfileId] ?: defaultProfile()
                val tempWav = File(tempDir, "seg_${segment.segmentIndex}.wav")
                val duration = voiceEngine.synthesiseToFile(segment.rawText, profile, tempWav)
                if (duration < 0 || !tempWav.exists()) return@forEachIndexed

                val rawWavBytes = tempWav.readBytes()
                val rawInfo = WavProcessor.readInfo(tempWav)
                val pcmOnly = rawWavBytes.copyOfRange(rawWavBytes.size - rawInfo.dataBytes, rawWavBytes.size)
                val trimmedPcm = WavProcessor.trimSilence(pcmOnly)
                val info = WavProcessor.readInfo(tempWav)
                val header = WavProcessor.buildWavHeader(info.sampleRate, info.channels, info.bitsPerSample, trimmedPcm.size)
                tempWav.writeBytes(header + trimmedPcm)
                val trimmedInfo = WavProcessor.readInfo(tempWav)
                val trimmedDuration = WavProcessor.durationMs(trimmedInfo)

                segmentRepository.updateAudioTimestamp(segment.spanId, cumulativeMs, trimmedDuration)
                cumulativeMs += trimmedDuration
                segmentWavFiles.add(Pair(segment.spanId, tempWav))
            }

            val chapterWav = File(processedDir, "ch_$chapterIndex.wav")
            AudioConcatenator.concatenateWavs(segmentWavFiles.map { it.second }, chapterWav)

            val chapterAac = File(processedDir, "ch_$chapterIndex.aac")
            AacEncoder.encodeWavToAac(chapterWav, chapterAac)

            chapterWav.delete()
            tempDir.listFiles()?.forEach { it.delete() }

            Result.success(workDataOf(KEY_AUDIO_PATH to chapterAac.absolutePath))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Synthesis failed")))
        } finally {
            voiceEngine.release()
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
    }
}
