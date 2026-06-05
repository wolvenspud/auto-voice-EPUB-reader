package com.autovice.reader.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autovice.reader.data.preferences.ApiKeyStore
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.reader.domain.model.AttributionSource
import com.autovice.reader.domain.model.CharacterTier
import com.autovice.reader.domain.model.SpeakerTag
import com.autovice.reader.domain.model.TtsSegment
import com.autovice.reader.domain.model.VoiceProfile
import com.autovice.reader.domain.model.VoiceProfileIds
import com.autovice.reader.llm.AttributionLine
import com.autovice.reader.llm.AttributionResult
import com.autovice.reader.llm.LlmAttributionClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs LLM-based speaker attribution over one chapter, replacing heuristic guesses with
 * model-resolved speakers, creating any newly discovered character profiles, and flagging
 * changed segments for re-synthesis.
 */
@HiltWorker
class AttributionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val segmentRepository: SegmentRepository,
    private val voiceRepository: CharacterVoiceRepository,
    private val apiKeyStore: ApiKeyStore,
    private val client: LlmAttributionClient,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, -1)
        if (chapterIndex < 0) return@withContext Result.failure()

        val config = apiKeyStore.current()
        if (!config.hasActiveKey) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "No API key configured for ${config.provider.name}"))
        }

        try {
            val segments = segmentRepository.getChapterSegments(bookId, chapterIndex)
            if (segments.isEmpty()) return@withContext Result.success()

            val bookIdShort = VoiceProfileIds.shortBookId(bookId)
            // Rolling character roster, seeded from any existing character profiles.
            val roster = LinkedHashSet(
                voiceRepository.getVoicesForBook(bookId)
                    .filter { it.tier == CharacterTier.MAJOR || it.tier == CharacterTier.MINOR }
                    .map { it.characterName },
            )

            val segmentByIndex = segments.associateBy { it.segmentIndex }
            val chunks = chunkLines(segments)
            val newCharacterCounts = HashMap<String, Int>()
            var applied = 0
            var leadIn = emptyList<LlmAttributionClient.LeadInLine>()

            chunks.forEachIndexed { chunkIdx, chunk ->
                setProgressAsync(workDataOf(KEY_PROGRESS to (chunkIdx.toFloat() / chunks.size)))
                val results = client.attribute(config.provider, config.activeKey, chunk, roster.toList(), leadIn)
                val speakerByIndex = results.associate { it.segmentIndex to it.speaker }
                results.forEach { result ->
                    val segment = segmentByIndex[result.segmentIndex] ?: return@forEach
                    val (tag, profileId) = resolveSpeaker(bookIdShort, result.speaker)
                    if (tag is SpeakerTag.Character) {
                        newCharacterCounts[tag.name] = (newCharacterCounts[tag.name] ?: 0) + 1
                        roster.add(tag.name)
                    }
                    segmentRepository.updateAttribution(
                        spanId = segment.spanId,
                        speakerTag = tag,
                        voiceProfileId = profileId,
                        source = AttributionSource.LLM,
                        confidence = 0.8f,
                    )
                    applied++
                }
                // Carry the last few attributed lines forward so turn-taking stays coherent.
                leadIn = chunk.takeLast(LEAD_IN).mapNotNull { line ->
                    speakerByIndex[line.segmentIndex]?.let {
                        LlmAttributionClient.LeadInLine(line.segmentIndex, line.text, it)
                    }
                }
            }

            // Create profiles for any characters the model discovered, without clobbering tuned ones.
            val newProfiles = newCharacterCounts.map { (name, count) ->
                VoiceProfile(
                    profileId = VoiceProfileIds.character(bookIdShort, name),
                    characterName = name,
                    tier = CharacterTier.MINOR,
                    pitch = VoiceProfile.defaultPitchFor(name),
                    appearanceCount = count,
                )
            }
            if (newProfiles.isNotEmpty()) voiceRepository.addMissingProfiles(bookId, newProfiles)

            Result.success(workDataOf(KEY_APPLIED to applied))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Attribution failed")))
        }
    }

    private fun resolveSpeaker(bookIdShort: String, speaker: String): Pair<SpeakerTag, String> {
        val tag: SpeakerTag = when (speaker.uppercase()) {
            AttributionResult.NARRATOR -> SpeakerTag.Narrator
            AttributionResult.UNKNOWN -> SpeakerTag.UnknownDialogue
            AttributionResult.GROUP -> SpeakerTag.Group
            else -> SpeakerTag.Character(speaker)
        }
        return tag to VoiceProfileIds.forSpeaker(bookIdShort, tag)
    }

    /** Splits the chapter into prompt-sized windows, keeping absolute segment indices intact. */
    private fun chunkLines(segments: List<TtsSegment>): List<List<AttributionLine>> {
        val chunks = mutableListOf<List<AttributionLine>>()
        var current = mutableListOf<AttributionLine>()
        var budget = 0
        segments.forEach { seg ->
            current.add(AttributionLine(seg.segmentIndex, seg.rawText))
            budget += seg.rawText.length
            if (budget >= CHUNK_CHAR_BUDGET) {
                chunks.add(current)
                current = mutableListOf()
                budget = 0
            }
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_CHAPTER_INDEX = "chapterIndex"
        const val KEY_PROGRESS = "progress"
        const val KEY_APPLIED = "applied"
        const val KEY_ERROR = "error"

        private const val CHUNK_CHAR_BUDGET = 6000
        private const val LEAD_IN = 4
    }
}
