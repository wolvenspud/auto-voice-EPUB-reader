package com.autovice.reader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.data.repository.ProgressRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.reader.domain.model.Book
import com.autovice.reader.work.ImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = false,
    val importState: ImportState = ImportState.Idle,
)

sealed class ImportState {
    object Idle : ImportState()
    data class Importing(val progress: Float, val currentStep: String) : ImportState()
    data class Error(val message: String) : ImportState()
    data class Success(val bookId: String) : ImportState()
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val segmentRepository: SegmentRepository,
    private val voiceRepository: CharacterVoiceRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        libraryRepository.observeBooks()
            .onEach { books -> _uiState.update { it.copy(books = books) } }
            .launchIn(viewModelScope)
    }

    fun importEpub(uri: Uri) {
        val bookId = UUID.randomUUID().toString()
        val workRequest = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(
                workDataOf(
                    ImportWorker.KEY_BOOK_ID to bookId,
                    ImportWorker.KEY_EPUB_URI to uri.toString(),
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueue(workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getFloat(ImportWorker.KEY_PROGRESS_FRACTION, 0f)
                        val step = workInfo.progress.getString(ImportWorker.KEY_PROGRESS_STEP) ?: "Importing…"
                        _uiState.update { it.copy(importState = ImportState.Importing(progress, step)) }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val resultBookId = workInfo.outputData.getString(ImportWorker.KEY_RESULT_BOOK_ID) ?: bookId
                        _uiState.update { it.copy(importState = ImportState.Success(resultBookId)) }
                    }
                    WorkInfo.State.FAILED -> {
                        val message = workInfo.outputData.getString(ImportWorker.KEY_ERROR_MESSAGE) ?: "Import failed"
                        _uiState.update { it.copy(importState = ImportState.Error(message)) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            segmentRepository.deleteAllForBook(bookId)
            voiceRepository.deleteAllForBook(bookId)
            progressRepository.deleteProgress(bookId)
            libraryRepository.deleteBook(bookId)
        }
    }

    fun dismissImportState() {
        _uiState.update { it.copy(importState = ImportState.Idle) }
    }
}
