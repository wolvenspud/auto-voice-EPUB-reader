package com.autovice.reader.ui.character

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autovice.reader.data.preferences.ApiKeyStore
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.reader.domain.model.AttributionSource
import com.autovice.reader.domain.model.CharacterTier
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.domain.model.VoiceProfile
import com.autovice.reader.domain.model.VoiceProfileIds
import com.autovice.reader.llm.CastCharacter
import com.autovice.reader.llm.LlmCastingClient
import com.autovice.reader.tts.EngineVoice
import com.autovice.reader.tts.SynthesisWorker
import com.autovice.reader.tts.VoiceEngineRegistry
import com.autovice.reader.work.AttributionWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Result of the auto-casting step, so the Generate flow can alert on engine failures. */
private sealed interface CastOutcome {
    data object Ok : CastOutcome
    data object VoicevoxDown : CastOutcome
    data class Failed(val message: String) : CastOutcome
}

/**
 * Runs the "Generate / refresh audio" pipeline (attribution → cast → synthesis) on an
 * application-scoped coroutine, keyed per book+chapter, so it keeps running when the user leaves the
 * Character voices screen. State is shared, so a freshly-created ViewModel re-attaches to in-flight
 * (or finished) work. Synthesis itself is a WorkManager job and already survives process death; this
 * keeps the orchestration alive too, within the app process.
 */
@Singleton
class RefreshCoordinator @Inject constructor(
    private val segmentRepository: SegmentRepository,
    private val voiceRepository: CharacterVoiceRepository,
    private val apiKeyStore: ApiKeyStore,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val engineRegistry: VoiceEngineRegistry,
    private val castingClient: LlmCastingClient,
    private val libraryRepository: LibraryRepository,
    private val workManager: WorkManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _states = MutableStateFlow<Map<String, RefreshUiState>>(emptyMap())
    val states: StateFlow<Map<String, RefreshUiState>> = _states.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun keyOf(bookId: String, chapterIndex: Int) = "$bookId/$chapterIndex"

    fun stateOf(bookId: String, chapterIndex: Int): RefreshUiState =
        _states.value[keyOf(bookId, chapterIndex)] ?: RefreshUiState.Idle

    fun dismiss(bookId: String, chapterIndex: Int) {
        setState(keyOf(bookId, chapterIndex), RefreshUiState.Idle)
    }

    private fun setState(key: String, state: RefreshUiState) {
        _states.update { it + (key to state) }
    }

    /** Full pipeline; no-op if one is already running for this chapter. Survives navigation. */
    fun refreshAudio(bookId: String, chapterIndex: Int) {
        val key = keyOf(bookId, chapterIndex)
        if (_states.value[key] is RefreshUiState.Running) return
        jobs[key]?.cancel()
        jobs[key] = scope.launch {
            try {
                runRefresh(bookId, chapterIndex, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState(key, RefreshUiState.Error(e.message ?: "Refresh failed"))
            }
        }
    }

    /** Saves an edited profile then re-synthesises (synthesis only — manual choice is authoritative). */
    fun saveAndRecompile(bookId: String, chapterIndex: Int, profile: VoiceProfile) {
        val key = keyOf(bookId, chapterIndex)
        if (_states.value[key] is RefreshUiState.Running) return
        jobs[key]?.cancel()
        jobs[key] = scope.launch {
            try {
                voiceRepository.updateProfileAndInvalidate(bookId, profile)
                deleteCachedAudio(bookId)
                setState(key, RefreshUiState.Running(0f, "Recompiling audio…"))
                val synthError = awaitSynthesis(bookId, chapterIndex, key)
                setState(
                    key,
                    if (synthError == null) RefreshUiState.Done("Voice saved — audio recompiled")
                    else RefreshUiState.Error("Synthesis failed: $synthError"),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState(key, RefreshUiState.Error(e.message ?: "Recompile failed"))
            }
        }
    }

    private suspend fun runRefresh(bookId: String, chapterIndex: Int, key: String) {
        val prefs = preferencesRepository.preferences.first()
        val config = apiKeyStore.current()
        val attributionOn = prefs.characterAttributionEnabled
        val hasKey = config.hasActiveKey
        val useLlm = attributionOn && hasKey

        val segments = segmentRepository.getChapterSegments(bookId, chapterIndex)
        if (segments.isEmpty()) {
            setState(key, RefreshUiState.Error("No text in this chapter to synthesise")); return
        }

        var castOutcome: CastOutcome? = null
        if (useLlm) {
            val hasCharacters = voiceRepository.getVoicesForBook(bookId)
                .any { it.tier != CharacterTier.SYSTEM }
            val needsAttribution =
                segments.none { it.attributionSource == AttributionSource.LLM } || !hasCharacters
            if (needsAttribution) {
                setState(key, RefreshUiState.Running(null, "Identifying speakers…"))
                val attrError = awaitAttribution(bookId, chapterIndex)
                if (attrError != null) {
                    setState(
                        key,
                        RefreshUiState.Error(
                            "Couldn't identify speakers (${config.provider.name.lowercase()}): $attrError. " +
                                "Check your API key and connection in Settings.",
                        ),
                    )
                    return
                }
            }
            setState(key, RefreshUiState.Running(null, "Assigning character voices…"))
            castOutcome = castUncastCharacters(bookId)
        }

        setState(key, RefreshUiState.Running(0f, "Synthesising audio…"))
        val synthError = awaitSynthesis(bookId, chapterIndex, key)

        setState(
            key,
            when {
                synthError != null ->
                    RefreshUiState.Error("Synthesis failed: $synthError")
                attributionOn && !hasKey ->
                    RefreshUiState.Error(
                        "No API key set — read in the narrator voice. Add a Claude or OpenAI key " +
                            "in Settings to get per-character voices.",
                    )
                castOutcome is CastOutcome.VoicevoxDown ->
                    RefreshUiState.Error(
                        "VOICEVOX engine unreachable — characters used the device voice. Audio is " +
                            "ready, but check Settings → VOICEVOX URL and that the server is running.",
                    )
                castOutcome is CastOutcome.Failed ->
                    RefreshUiState.Error(
                        "Voice casting failed: ${(castOutcome as CastOutcome.Failed).message}. " +
                            "Audio uses existing/device voices.",
                    )
                else -> RefreshUiState.Done(doneMessage(bookId, attributionOn))
            },
        )
    }

    private suspend fun loadCatalog(): List<EngineVoice> =
        runCatching { engineRegistry.engineFor(VoiceEngineId.VOICEVOX).listVoices() }.getOrDefault(emptyList())

    private suspend fun castUncastCharacters(bookId: String): CastOutcome {
        val config = apiKeyStore.current()
        if (!config.hasActiveKey) return CastOutcome.Ok
        val voices = loadCatalog()
        if (voices.isEmpty()) return CastOutcome.VoicevoxDown

        val profiles = voiceRepository.getVoicesForBook(bookId)
        val short = VoiceProfileIds.shortBookId(bookId)
        val narrator = profiles.firstOrNull { it.profileId == VoiceProfileIds.narrator(short) }
        val chars = profiles.filter { it.tier != CharacterTier.SYSTEM }

        val narratorNeedsCast = narrator != null && narrator.voiceEngineId != VoiceEngineId.VOICEVOX
        val uncast = chars.filter {
            it.voiceEngineId != VoiceEngineId.VOICEVOX || it.externalVoiceId.isNullOrBlank()
        }
        if (!narratorNeedsCast && uncast.isEmpty()) return CastOutcome.Ok

        val input = buildList {
            if (narratorNeedsCast) add(CastCharacter("NARRATOR", narrator!!.appearanceCount, null))
            addAll(uncast.map { CastCharacter(it.characterName, it.appearanceCount, it.estimatedGender) })
        }
        val byName = chars.associateBy { it.characterName }
        return try {
            for (a in castingClient.cast(config.provider, config.activeKey, input, voices)) {
                val profile = if (a.character == "NARRATOR") narrator else byName[a.character]
                profile ?: continue
                voiceRepository.updateProfileAndInvalidate(
                    bookId,
                    profile.copy(voiceEngineId = VoiceEngineId.VOICEVOX, externalVoiceId = a.speakerId.toString()),
                )
            }
            CastOutcome.Ok
        } catch (e: Exception) {
            CastOutcome.Failed(e.message ?: "voice casting failed")
        }
    }

    private suspend fun doneMessage(bookId: String, attributionOn: Boolean): String = when {
        !attributionOn -> "Audio refreshed — narrator voice (character attribution is off in Settings)"
        else -> {
            val characters = voiceRepository.getVoicesForBook(bookId).count { it.tier != CharacterTier.SYSTEM }
            if (characters > 0) "Audio refreshed — $characters character voice(s)"
            else "Audio refreshed — no dialogue speakers were found in this chapter"
        }
    }

    private suspend fun awaitAttribution(bookId: String, chapterIndex: Int): String? {
        val request = OneTimeWorkRequestBuilder<AttributionWorker>()
            .setInputData(
                workDataOf(
                    AttributionWorker.KEY_BOOK_ID to bookId,
                    AttributionWorker.KEY_CHAPTER_INDEX to chapterIndex,
                )
            )
            .build()
        workManager.enqueue(request)
        val info = awaitWork(request.id) {}
        return when (info?.state) {
            WorkInfo.State.SUCCEEDED -> null
            else -> info?.outputData?.getString(AttributionWorker.KEY_ERROR) ?: "attribution didn't complete"
        }
    }

    private suspend fun awaitSynthesis(bookId: String, chapterIndex: Int, key: String): String? {
        val request = OneTimeWorkRequestBuilder<SynthesisWorker>()
            .setInputData(
                workDataOf(
                    SynthesisWorker.KEY_BOOK_ID to bookId,
                    SynthesisWorker.KEY_CHAPTER_INDEX to chapterIndex,
                )
            )
            .build()
        workManager.enqueueUniqueWork("synth_${bookId}_$chapterIndex", ExistingWorkPolicy.REPLACE, request)
        val info = awaitWork(request.id) { p ->
            setState(key, RefreshUiState.Running(p, "Synthesising audio…"))
        }
        return when (info?.state) {
            WorkInfo.State.SUCCEEDED -> null
            else -> info?.outputData?.getString(SynthesisWorker.KEY_ERROR) ?: "synthesis didn't complete"
        }
    }

    private suspend fun awaitWork(id: UUID, onProgress: (Float) -> Unit): WorkInfo? =
        workManager.getWorkInfoByIdFlow(id)
            .onEach { info ->
                if (info?.state == WorkInfo.State.RUNNING) {
                    onProgress(info.progress.getFloat(SynthesisWorker.KEY_PROGRESS, 0f))
                }
            }
            .first { info -> info != null && info.state.isFinished }

    private fun deleteCachedAudio(bookId: String) {
        val processedDir = File(libraryRepository.generateBookDir(bookId), "processed")
        processedDir.listFiles()?.filter { it.extension == "aac" }?.forEach { it.delete() }
    }
}
