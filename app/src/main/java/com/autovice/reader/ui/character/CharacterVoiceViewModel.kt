package com.autovice.reader.ui.character

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autovice.reader.data.preferences.ApiKeyStore
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.domain.model.CharacterTier
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.domain.model.VoiceProfile
import com.autovice.reader.tts.EngineVoice
import com.autovice.reader.tts.VoiceEngineRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The single "Refresh audio" action's state. One tap identifies speakers (LLM attribution), gives
 * any new characters a voice (LLM casting against the VOICEVOX catalog), and synthesises the
 * chapter — so the audio always reflects the current settings. The work runs in [RefreshCoordinator]
 * so it keeps going (and this state survives) when the user leaves the screen.
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
    private val apiKeyStore: ApiKeyStore,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val engineRegistry: VoiceEngineRegistry,
    private val libraryRepository: LibraryRepository,
    private val coordinator: RefreshCoordinator,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val chapterIndex: Int = savedStateHandle.get<String>("chapterIndex")?.toIntOrNull() ?: 0
    private val refreshKey = coordinator.keyOf(bookId, chapterIndex)

    private val voiceCatalog = MutableStateFlow<List<EngineVoice>>(emptyList())

    private var previewJob: Job? = null
    private var previewPlayer: android.media.MediaPlayer? = null

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
        // Observe the shared coordinator state so a re-created ViewModel re-attaches to in-flight work.
        coordinator.states.map { it[refreshKey] ?: RefreshUiState.Idle },
        voiceCatalog,
    ) { (profiles, config, prefs), refresh, catalog ->
        CharacterVoiceUiState(
            // System voices (narrator, …) first, then characters by appearance frequency.
            profiles = profiles.sortedWith(
                compareByDescending<VoiceProfile> { it.tier == CharacterTier.SYSTEM }
                    .thenByDescending { it.appearanceCount },
            ),
            aiAvailable = config.hasActiveKey && prefs.characterAttributionEnabled,
            refresh = refresh,
            voiceCatalog = catalog,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CharacterVoiceUiState(),
    )

    private suspend fun loadCatalog(): List<EngineVoice> =
        runCatching { engineRegistry.engineFor(VoiceEngineId.VOICEVOX).listVoices() }.getOrDefault(emptyList())

    /** Re-fetch the VOICEVOX voice catalog — used to retry after a transient connectivity failure. */
    fun reloadCatalog() {
        viewModelScope.launch { voiceCatalog.value = loadCatalog() }
    }

    /**
     * One-tap "make the audio match the current settings": identify speakers, cast their voices, and
     * synthesise — runs in the background coordinator, so leaving the screen doesn't stop it.
     */
    fun refreshAudio() = coordinator.refreshAudio(bookId, chapterIndex)

    /** Saves an edited profile and re-synthesises the chapter in the background. */
    fun saveProfileAndRecompile(profile: VoiceProfile) =
        coordinator.saveAndRecompile(bookId, chapterIndex, profile)

    fun dismissRefreshStatus() = coordinator.dismiss(bookId, chapterIndex)

    /** Synthesises a short sample with [voiceId] (VOICEVOX) and plays it, so the user can audition. */
    fun previewVoice(voiceId: String) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val out = File(libraryRepository.generateBookDir(bookId), "voice_preview.wav")
                val sample = VoiceProfile(
                    profileId = "preview",
                    characterName = "preview",
                    tier = CharacterTier.SYSTEM,
                    voiceEngineId = VoiceEngineId.VOICEVOX,
                    externalVoiceId = voiceId,
                )
                val duration = withContext(Dispatchers.IO) {
                    engineRegistry.engineFor(VoiceEngineId.VOICEVOX).synthesiseToFile(PREVIEW_TEXT, sample, out)
                }
                if (duration < 0 || !out.exists()) return@launch
                previewPlayer?.release()
                previewPlayer = android.media.MediaPlayer().apply {
                    setDataSource(out.absolutePath)
                    setOnCompletionListener { mp -> mp.release(); if (previewPlayer === mp) previewPlayer = null }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                android.util.Log.w("CharacterVoice", "Voice preview failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        previewJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
    }

    private companion object {
        /** Short sample line spoken when auditioning a voice. */
        const val PREVIEW_TEXT = "こんにちは。これはこの声のサンプルです。"
    }
}
