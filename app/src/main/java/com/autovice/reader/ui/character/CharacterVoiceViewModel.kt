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
import com.autovice.reader.domain.model.CharacterTier
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.domain.model.VoiceProfile
import com.autovice.reader.domain.model.VoiceProfileIds
import com.autovice.reader.llm.CastCharacter
import com.autovice.reader.llm.LlmCastingClient
import com.autovice.reader.tts.EngineVoice
import com.autovice.reader.tts.VoiceEngineRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.autovice.reader.work.AttributionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.autovice.reader.domain.model.AttributionSource
import com.autovice.reader.tts.SynthesisWorker
import java.util.UUID
import javax.inject.Inject

sealed interface AttributionUiState {
    data object Idle : AttributionUiState
    data class Running(val progress: Float) : AttributionUiState
    data class Done(val applied: Int) : AttributionUiState
    data class Error(val message: String) : AttributionUiState
}

sealed interface CastingUiState {
    data object Idle : CastingUiState
    data object Running : CastingUiState
    data class Done(val applied: Int) : CastingUiState
    data class Error(val message: String) : CastingUiState
}

sealed interface RefreshUiState {
    data object Idle : RefreshUiState
    /** [progress] is null while attributing (indeterminate) and 0..1 while synthesising. */
    data class Running(val progress: Float?, val label: String) : RefreshUiState
    data object Done : RefreshUiState
    data class Error(val message: String) : RefreshUiState
}

data class CharacterVoiceUiState(
    val profiles: List<VoiceProfile> = emptyList(),
    val aiAvailable: Boolean = false,
    val castAvailable: Boolean = false,
    val attribution: AttributionUiState = AttributionUiState.Idle,
    val casting: CastingUiState = CastingUiState.Idle,
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

    private val attribution = MutableStateFlow<AttributionUiState>(AttributionUiState.Idle)
    private val casting = MutableStateFlow<CastingUiState>(CastingUiState.Idle)
    private val refresh = MutableStateFlow<RefreshUiState>(RefreshUiState.Idle)
    private val voiceCatalog = MutableStateFlow<List<EngineVoice>>(emptyList())

    init {
        // Load the VOICEVOX catalog for the picker / cast availability.
        viewModelScope.launch { voiceCatalog.value = loadCatalog() }
    }

    val uiState: StateFlow<CharacterVoiceUiState> = combine(
        combine(
            voiceRepository.observeVoicesForBook(bookId),
            apiKeyStore.config,
            preferencesRepository.preferences,
        ) { profiles, config, prefs -> Triple(profiles, config, prefs) },
        attribution,
        combine(casting, refresh) { c, r -> c to r },
        voiceCatalog,
    ) { (profiles, config, prefs), attr, (cast, ref), catalog ->
        CharacterVoiceUiState(
            // System voices (narrator, …) first, then characters by appearance frequency.
            profiles = profiles.sortedWith(
                compareByDescending<VoiceProfile> { it.tier == CharacterTier.SYSTEM }
                    .thenByDescending { it.appearanceCount },
            ),
            aiAvailable = config.hasActiveKey && prefs.characterAttributionEnabled,
            castAvailable = config.hasActiveKey && catalog.isNotEmpty(),
            attribution = attr,
            casting = cast,
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

    /** LLM best-guess casting of every character (incl. narrator) to a VOICEVOX voice. */
    fun autoCast() {
        if (casting.value is CastingUiState.Running) return
        viewModelScope.launch {
            casting.value = CastingUiState.Running
            try {
                val config = apiKeyStore.current()
                if (!config.hasActiveKey) {
                    casting.value = CastingUiState.Error("Add an API key in Settings first"); return@launch
                }
                val voices = loadCatalog().also { voiceCatalog.value = it }
                if (voices.isEmpty()) {
                    casting.value = CastingUiState.Error("VOICEVOX unreachable — check the URL in Settings"); return@launch
                }
                val profiles = voiceRepository.getVoicesForBook(bookId)
                val short = VoiceProfileIds.shortBookId(bookId)
                val narrator = profiles.firstOrNull { it.profileId == VoiceProfileIds.narrator(short) }
                val chars = profiles.filter { it.tier != CharacterTier.SYSTEM }
                val input = buildList {
                    add(CastCharacter("NARRATOR", narrator?.appearanceCount ?: 0, null))
                    addAll(chars.map { CastCharacter(it.characterName, it.appearanceCount, it.estimatedGender) })
                }
                val byName = chars.associateBy { it.characterName }
                var applied = 0
                for (a in castingClient.cast(config.provider, config.activeKey, input, voices)) {
                    val profile = if (a.character == "NARRATOR") narrator else byName[a.character]
                    profile ?: continue
                    voiceRepository.updateProfileAndInvalidate(
                        bookId,
                        profile.copy(voiceEngineId = VoiceEngineId.VOICEVOX, externalVoiceId = a.speakerId.toString()),
                    )
                    applied++
                }
                withContext(Dispatchers.IO) { deleteCachedAudio() }
                casting.value = CastingUiState.Done(applied)
            } catch (e: Exception) {
                casting.value = CastingUiState.Error(e.message ?: "Casting failed")
            }
        }
    }

    fun dismissCastingStatus() { casting.value = CastingUiState.Idle }

    /**
     * One-tap "make the audio match the current settings". Re-runs LLM attribution only if it
     * hasn't been done for this chapter yet (attribution just turned on / never run), then
     * re-synthesises the chapter. Synthesis honours the attribution master switch: full
     * per-character voices when it's on and attributed, otherwise the narrator voice (device TTS)
     * for everything.
     */
    fun refreshAudio() {
        if (refresh.value is RefreshUiState.Running) return
        viewModelScope.launch {
            try {
                val prefs = preferencesRepository.preferences.first()
                val config = apiKeyStore.current()
                val useLlm = prefs.characterAttributionEnabled && config.hasActiveKey

                val segments = segmentRepository.getChapterSegments(bookId, chapterIndex)
                if (segments.isEmpty()) {
                    refresh.value = RefreshUiState.Error("No text in this chapter to synthesise"); return@launch
                }

                // Attribute only when enabled and not already LLM-attributed — avoids redundant API calls.
                if (useLlm && segments.none { it.attributionSource == AttributionSource.LLM }) {
                    refresh.value = RefreshUiState.Running(null, "Attributing speakers…")
                    if (!awaitAttribution()) {
                        refresh.value = RefreshUiState.Error("Attribution failed"); return@launch
                    }
                }

                refresh.value = RefreshUiState.Running(0f, "Synthesising audio…")
                val ok = awaitSynthesis()
                refresh.value = if (ok) RefreshUiState.Done else RefreshUiState.Error("Synthesis failed")
            } catch (e: Exception) {
                refresh.value = RefreshUiState.Error(e.message ?: "Refresh failed")
            }
        }
    }

    fun dismissRefreshStatus() { refresh.value = RefreshUiState.Idle }

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

    fun runAttribution() {
        if (uiState.value.attribution is AttributionUiState.Running) return
        val request = OneTimeWorkRequestBuilder<AttributionWorker>()
            .setInputData(
                workDataOf(
                    AttributionWorker.KEY_BOOK_ID to bookId,
                    AttributionWorker.KEY_CHAPTER_INDEX to chapterIndex,
                )
            )
            .build()
        attribution.value = AttributionUiState.Running(0f)
        workManager.enqueue(request)

        workManager.getWorkInfoByIdFlow(request.id)
            .onEach { info ->
                when (info?.state) {
                    WorkInfo.State.RUNNING ->
                        attribution.value = AttributionUiState.Running(
                            info.progress.getFloat(AttributionWorker.KEY_PROGRESS, 0f)
                        )
                    WorkInfo.State.SUCCEEDED ->
                        attribution.value = AttributionUiState.Done(
                            info.outputData.getInt(AttributionWorker.KEY_APPLIED, 0)
                        )
                    WorkInfo.State.FAILED ->
                        attribution.value = AttributionUiState.Error(
                            info.outputData.getString(AttributionWorker.KEY_ERROR) ?: "Attribution failed"
                        )
                    else -> {}
                }
            }
            .launchIn(viewModelScope)
    }

    fun dismissAttributionStatus() {
        attribution.value = AttributionUiState.Idle
    }
}
