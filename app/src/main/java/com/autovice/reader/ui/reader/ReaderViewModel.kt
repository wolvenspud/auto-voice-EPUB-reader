package com.autovice.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.data.repository.ProgressRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.reader.domain.model.Book
import com.autovice.reader.domain.model.Chapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ReaderUiState(
    val book: Book? = null,
    val currentChapter: Chapter? = null,
    val chapterHtml: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val controlsVisible: Boolean = true,
    val autoScrollPaused: Boolean = false,
    val highlightedSpanId: String? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val playbackSpeed: Float = 1.0f,
    val error: String? = null,
)

enum class PlaybackState { IDLE, LOADING, PLAYING, PAUSED }

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val segmentRepository: SegmentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var autoScrollResumeJob: Job? = null

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            val book = libraryRepository.getBook(bookId)
            if (book == null) {
                _uiState.update { it.copy(error = "Book not found") }
                return@launch
            }
            libraryRepository.updateLastOpened(bookId)

            val chapters = buildChapterList(book)
            val progress = progressRepository.getProgress(bookId)
            val initialChapterIndex = progress?.chapterIndex ?: 0

            _uiState.update {
                it.copy(
                    book = book,
                    chapters = chapters,
                )
            }
            loadChapter(initialChapterIndex)
        }
    }

    fun loadChapter(index: Int) {
        viewModelScope.launch {
            val book = _uiState.value.book ?: return@launch
            val chapters = _uiState.value.chapters
            val chapter = chapters.getOrNull(index) ?: return@launch

            val html = withContext(Dispatchers.IO) {
                try { File(chapter.htmlPath).readText(Charsets.UTF_8) } catch (_: Exception) { null }
            }

            _uiState.update {
                it.copy(
                    currentChapter = chapter,
                    chapterHtml = html,
                    highlightedSpanId = null,
                )
            }
        }
    }

    fun toggleControls() {
        _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun onUserScrolled() {
        _uiState.update { it.copy(autoScrollPaused = true) }
        autoScrollResumeJob?.cancel()
        autoScrollResumeJob = viewModelScope.launch {
            delay(4_000)
            _uiState.update { it.copy(autoScrollPaused = false) }
        }
    }

    fun onSegmentTap(spanHtmlId: String) {
        // Convert HTML span ID (c{chap}s{seg}) to DB span ID, then notify playback
        _uiState.update { it.copy(highlightedSpanId = spanHtmlId) }
        // Playback seek handled by TtsPlaybackService via MediaController
    }

    fun navigateChapter(delta: Int) {
        val current = _uiState.value.currentChapter ?: return
        val newIndex = current.index + delta
        if (newIndex in _uiState.value.chapters.indices) {
            loadChapter(newIndex)
        }
    }

    fun setPlaybackState(state: PlaybackState) {
        _uiState.update { it.copy(playbackState = state) }
    }

    fun setHighlightedSpan(spanId: String?) {
        _uiState.update { it.copy(highlightedSpanId = spanId) }
    }

    private fun buildChapterList(book: Book): List<Chapter> {
        val bookDir = libraryRepository.generateBookDir(book.id)
        val processedDir = File(bookDir, "processed")
        return (0 until book.totalChapters).map { index ->
            Chapter(
                index = index,
                title = "Chapter ${index + 1}",
                htmlPath = File(processedDir, "ch_$index.html").absolutePath,
                audioPath = File(processedDir, "ch_$index.aac").let { if (it.exists()) it.absolutePath else null },
                charCount = 0,
                segmentCount = 0,
            )
        }
    }
}
