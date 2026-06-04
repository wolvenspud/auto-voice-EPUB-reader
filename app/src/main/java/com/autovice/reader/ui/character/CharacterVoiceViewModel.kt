package com.autovice.reader.ui.character

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autovice.reader.data.preferences.ApiKeyStore
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.domain.model.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.autovice.reader.work.AttributionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AttributionUiState {
    data object Idle : AttributionUiState
    data class Running(val progress: Float) : AttributionUiState
    data class Done(val applied: Int) : AttributionUiState
    data class Error(val message: String) : AttributionUiState
}

data class CharacterVoiceUiState(
    val profiles: List<VoiceProfile> = emptyList(),
    val aiAvailable: Boolean = false,
    val attribution: AttributionUiState = AttributionUiState.Idle,
)

@HiltViewModel
class CharacterVoiceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val voiceRepository: CharacterVoiceRepository,
    private val apiKeyStore: ApiKeyStore,
    private val workManager: WorkManager,
    private val libraryRepository: LibraryRepository,
    private val preferencesRepository: ReaderPreferencesRepository,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val chapterIndex: Int = savedStateHandle.get<String>("chapterIndex")?.toIntOrNull() ?: 0

    private val attribution = MutableStateFlow<AttributionUiState>(AttributionUiState.Idle)

    val uiState: StateFlow<CharacterVoiceUiState> = combine(
        voiceRepository.observeVoicesForBook(bookId),
        apiKeyStore.config,
        attribution,
        preferencesRepository.preferences,
    ) { profiles, config, attr, prefs ->
        CharacterVoiceUiState(
            profiles = profiles,
            // Attribution needs both a key and the master toggle enabled.
            aiAvailable = config.hasActiveKey && prefs.characterAttributionEnabled,
            attribution = attr,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CharacterVoiceUiState(),
    )

    /** Persists an edited profile, flags its segments for re-synthesis, and deletes cached audio. */
    fun saveProfile(profile: VoiceProfile) {
        viewModelScope.launch {
            voiceRepository.updateProfileAndInvalidate(bookId, profile)
            // Delete cached chapter audio so the player re-synthesises with the new voice settings.
            withContext(Dispatchers.IO) {
                val processedDir = File(libraryRepository.generateBookDir(bookId), "processed")
                processedDir.listFiles()?.filter { it.extension == "aac" }?.forEach { it.delete() }
            }
        }
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
