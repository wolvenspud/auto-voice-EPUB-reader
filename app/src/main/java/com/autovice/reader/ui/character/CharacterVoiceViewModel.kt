package com.autovice.reader.ui.character

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * The single "Refresh audio" action's state. One tap identifies speakers (LLM attribution), gives
 * any new characters a voice (LLM casting against the VOICEVOX catalog), and synthesises the
 * chapter — so the audio always reflects the current settings.
 */
sealed interface RefreshUiState {
    data object Idle : RefreshUiState
    /** [progress] is null while attributing/casting (indeterminate) and 0..1 while synthesising. */
    data class Running(val progress: Float?, val label: String) : RefreshUiState
    data class Done(val message: String) : RefreshUiState
    data class Error(val message: String) : RefreshUiState
}

data class CharacterVoiceUiState(
    val profiles: List<VoiceProfile> = emptyList(),
    val aiAvailable: Boolean = false,
    val refresh: RefreshUiState = RefreshUiState.Idle,
    /** VOICEVOX voices for the per-character picker (empty if the engine is unreachable). */
    val voiceCatalog: List<EngineVoice> = emptyList(),
)

@HiltViewModel
class CharacterVoiceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val voiceRepository: CharacterVoiceRepository,
    private val segmentRepository: SegmentRepository,
    private val apiKeyStore: ApiKeyStore,
    private val workManager: WorkManager,
    private val libraryRepository: LibraryRepository,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val engineRegistry: VoiceEngineRegistry,
    private val castingClient: LlmCastingClient,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val chapterIndex: Int = savedStateHandle.get<String>("chapterIndex")?.toIntOrNull() ?: 0

    private val refresh = MutableStateFlow<RefreshUiState>(RefreshUiState.Idle)
    private val voiceCatalog = MutableStateFlow<List<EngineVoice>>(emptyList())

    init {
        // Load the VOICEVOX catalog for the per-character picker.
        viewModelScope.launch { voiceCatalog.value = loadCatalog() }
    }

    val uiState: StateFlow<CharacterVoiceUiState> = combine(
        combine(
            voiceRepository.observeVoicesForBook(bookId),
            apiKeyStore.config,
            preferencesRepository.preferences,
        ) { profiles, config, prefs -> Triple(profiles, config, prefs) },
        refresh,
        voiceCatalog,
    ) { (profiles, config, prefs), ref, catalog ->
        CharacterVoiceUiState(
            // System voices (narrator, …) first, then characters by appearance frequency.
            profiles = profiles.sortedWith(
                compareByDescending<VoiceProfile> { it.tier == CharacterTier.SYSTEM }
                    .thenByDescending { it.appearanceCount },
            ),
            aiAvailable = config.hasActiveKey && prefs.characterAttributionEnabled,
            refresh = ref,
            voiceCatalog = catalog,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CharacterVoiceUiState(),
    )

    private suspend fun loadCatalog(): List<EngineVoice> =
        runCatching { engineRegistry.engineFor(VoiceEngineId.VOICEVOX).listVoices() }.getOrDefault(emptyList())

    /** Persists an edited profile, flags its segments for re-synthesis, and deletes cached audio. */
    fun saveProfile(profile: VoiceProfile) {
        viewModelScope.launch {
            voiceRepository.updateProfileAndInvalidate(bookId, profile)
            withContext(Dispatchers.IO) { deleteCachedAudio() }
        }
    }

    /**
     * One-tap "make the audio match the current settings". With character attribution on and an API
     * key set, this (1) identifies speakers via the LLM, (2) casts any not-yet-voiced characters to
     * VOICEVOX voices, then (3) re-synthesises the chapter. With attribution off (or no key) it just
     * re-synthesises in the narrator voice. Each step is skipped when it has nothing to do, so
     * tapping again is cheap and never wastes API calls.
     */
    fun refreshAudio() {
        if (refresh.value is RefreshUiState.Running) return
        viewModelScope.launch {
            try {
                val prefs = preferencesRepository.preferences.first()
                val config = apiKeyStore.current()
                val attributionOn = prefs.characterAttributionEnabled
                val hasKey = config.hasActiveKey
                val useLlm = attributionOn && hasKey

                val segments = segmentRepository.getChapterSegments(bookId, chapterIndex)
                if (segments.isEmpty()) {
                    refresh.value = RefreshUiState.Error("No text in this chapter to synthesise"); return@launch
                }

                if (useLlm) {
                    // 1. Identify speakers. Re-run if this chapter isn't LLM-attributed yet, or if the
                    //    book still has no characters at all (a prior run found none / failed).
                    val hasCharacters = voiceRepository.getVoicesForBook(bookId)
                        .any { it.tier != CharacterTier.SYSTEM }
                    val needsAttribution =
                        segments.none { it.attributionSource == AttributionSource.LLM } || !hasCharacters
                    if (needsAttribution) {
                        refresh.value = RefreshUiState.Running(null, "Identifying speakers…")
                        if (!awaitAttribution()) {
                            refresh.value = RefreshUiState.Error(
                                "Speaker attribution failed — check your API key in Settings",
                            )
                            return@launch
                        }
                    }
                    // 2. Give any not-yet-cast characters a VOICEVOX voice. Best-effort: if VOICEVOX
                    //    is unreachable or casting errors, characters keep their device-TTS fallback.
                    refresh.value = RefreshUiState.Running(null, "Assigning character voices…")
                    runCatching { castUncastCharacters() }
                        .onFailure { android.util.Log.w("CharacterVoice", "Casting skipped: ${it.message}") }
                }

                // 3. Synthesise the chapter with the resolved voices.
                refresh.value = RefreshUiState.Running(0f, "Synthesising audio…")
                val ok = awaitSynthesis()
                refresh.value = if (ok) RefreshUiState.Done(doneMessage(attributionOn, hasKey)) else
                    RefreshUiState.Error("Synthesis failed")
            } catch (e: Exception) {
                refresh.value = RefreshUiState.Error(e.message ?: "Refresh failed")
            }
        }
    }

    fun dismissRefreshStatus() { refresh.value = RefreshUiState.Idle }

    private suspend fun doneMessage(attributionOn: Boolean, hasKey: Boolean): String = when {
        !attributionOn -> "Audio refreshed — narrator voice (character attribution is off in Settings)"
        !hasKey -> "Audio refreshed — narrator only; add an API key in Settings for character voices"
        else -> {
            val characters = voiceRepository.getVoicesForBook(bookId).count { it.tier != CharacterTier.SYSTEM }
            if (characters > 0) "Audio refreshed — $characters character voice(s)"
            else "Audio refreshed — no dialogue speakers were found in this chapter"
        }
    }

    /**
     * Casts characters (and an uncast narrator) that don't yet have a VOICEVOX voice, preserving any
     * voice the user — or a previous run — already chose. No-op if VOICEVOX is unreachable.
     */
    private suspend fun castUncastCharacters() {
        val config = apiKeyStore.current()
        if (!config.hasActiveKey) return
        val voices = loadCatalog().also { voiceCatalog.value = it }
        if (voices.isEmpty()) return  // VOICEVOX unreachable — leave characters on device TTS.

        val profiles = voiceRepository.getVoicesForBook(bookId)
        val short = VoiceProfileIds.shortBookId(bookId)
        val narrator = profiles.firstOrNull { it.profileId == VoiceProfileIds.narrator(short) }
        val chars = profiles.filter { it.tier != CharacterTier.SYSTEM }

        val narratorNeedsCast = narrator != null && narrator.voiceEngineId != VoiceEngineId.VOICEVOX
        val uncast = chars.filter {
            it.voiceEngineId != VoiceEngineId.VOICEVOX || it.externalVoiceId.isNullOrBlank()
        }
        if (!narratorNeedsCast && uncast.isEmpty()) return

        val input = buildList {
            if (narratorNeedsCast) add(CastCharacter("NARRATOR", narrator!!.appearanceCount, null))
            addAll(uncast.map { CastCharacter(it.characterName, it.appearanceCount, it.estimatedGender) })
        }
        val byName = chars.associateBy { it.characterName }
        for (a in castingClient.cast(config.provider, config.activeKey, input, voices)) {
            val profile = if (a.character == "NARRATOR") narrator else byName[a.character]
            profile ?: continue
            voiceRepository.updateProfileAndInvalidate(
                bookId,
                profile.copy(voiceEngineId = VoiceEngineId.VOICEVOX, externalVoiceId = a.speakerId.toString()),
            )
        }
    }

    /** Enqueues attribution for the chapter and suspends until it finishes; true on success. */
    private suspend fun awaitAttribution(): Boolean {
        val request = OneTimeWorkRequestBuilder<AttributionWorker>()
            .setInputData(
                workDataOf(
                    AttributionWorker.KEY_BOOK_ID to bookId,
                    AttributionWorker.KEY_CHAPTER_INDEX to chapterIndex,
                )
            )
            .build()
        workManager.enqueue(request)
        return awaitWorkSuccess(request.id) {}
    }

    /** Re-synthesises the chapter (replacing any in-flight run) and suspends until done. */
    private suspend fun awaitSynthesis(): Boolean {
        val request = OneTimeWorkRequestBuilder<SynthesisWorker>()
            .setInputData(
                workDataOf(
                    SynthesisWorker.KEY_BOOK_ID to bookId,
                    SynthesisWorker.KEY_CHAPTER_INDEX to chapterIndex,
                )
            )
            .build()
        workManager.enqueueUniqueWork("synth_${bookId}_$chapterIndex", ExistingWorkPolicy.REPLACE, request)
        return awaitWorkSuccess(request.id) { p ->
            refresh.value = RefreshUiState.Running(p, "Synthesising audio…")
        }
    }

    /** Suspends until the given work reaches a terminal state, reporting progress; true if SUCCEEDED. */
    private suspend fun awaitWorkSuccess(id: UUID, onProgress: (Float) -> Unit): Boolean {
        val terminal = workManager.getWorkInfoByIdFlow(id)
            .onEach { info ->
                if (info?.state == WorkInfo.State.RUNNING) {
                    onProgress(info.progress.getFloat(SynthesisWorker.KEY_PROGRESS, 0f))
                }
            }
            .first { info -> info != null && info.state.isFinished }
        return terminal?.state == WorkInfo.State.SUCCEEDED
    }

    private fun deleteCachedAudio() {
        val processedDir = File(libraryRepository.generateBookDir(bookId), "processed")
        processedDir.listFiles()?.filter { it.extension == "aac" }?.forEach { it.delete() }
    }
}
