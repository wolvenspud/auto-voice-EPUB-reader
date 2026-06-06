package com.autovice.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.preferences.ReaderTheme
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.data.repository.ProgressRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.audio.WavProcessor
import com.autovice.reader.domain.model.Book
import com.autovice.reader.domain.model.Chapter
import com.autovice.reader.tts.SynthesisWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Float = 16f,
    val lineHeight: Float = 1.6f,
    /** Synthesised chapter audio to feed the player; null until the current chapter is synthesised. */
    val audioPath: String? = null,
    /** 0..1 while a SynthesisWorker is running for the current chapter. */
    val synthesisProgress: Float? = null,
    val error: String? = null,
)

enum class PlaybackState { IDLE, LOADING, PLAYING, PAUSED }

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val segmentRepository: SegmentRepository,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** One-shot target position (ms) for tap-to-seek; the UI consumes it then calls [consumeSeek]. */
    private val _seekToMs = MutableStateFlow<Long?>(null)
    val seekToMs: StateFlow<Long?> = _seekToMs.asStateFlow()

    private var autoScrollResumeJob: Job? = null
    private var synthesisObserveJob: Job? = null
    private var pendingSeekSegment: Int? = null

    /**
     * AAC-LC encoder priming delay. The MediaCodec AAC encoder prepends 2048 samples of look-ahead
     * silence to every encoded file, and MediaMuxer doesn't tag it for gapless trimming — so the
     * decoded audio plays ~85 ms (at 24 kHz) later than the WAV-concatenation timeline our segment
     * timestamps (audioStartMs) are built on. Without compensating, the highlight leads the audio by
     * that much — negligible on long narration but glaring on short, rapid dialogue lines. So we
     * subtract it when mapping a player position to a segment, and add it when seeking to a segment.
     */
    private val encoderDelayMs: Long = 2048L * 1000L / WavProcessor.CANONICAL_SAMPLE_RATE

    init {
        preferencesRepository.preferences.onEach { p ->
            _uiState.update { it.copy(theme = p.theme, fontSize = p.fontSize, lineHeight = p.lineHeight) }
        }.launchIn(viewModelScope)
    }

    fun loadBook(bookId: String) {
        // Idempotent: re-entering the reader (e.g. returning from Settings or Character voices)
        // re-runs this LaunchedEffect, but the ViewModel survives — so don't reset back to the
        // saved chapter and clobber where the reader currently is.
        if (_uiState.value.book?.id == bookId) return
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

            // Switching chapters stops playback; reuse already-synthesised audio if present.
            synthesisObserveJob?.cancel()
            pendingSeekSegment = null
            _seekToMs.value = null
            val existingAudio = chapterAudioFile(index).takeIf { it.exists() }?.absolutePath

            _uiState.update {
                it.copy(
                    currentChapter = chapter,
                    chapterHtml = html,
                    highlightedSpanId = null,
                    playbackState = PlaybackState.IDLE,
                    audioPath = existingAudio,
                    synthesisProgress = null,
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

    /** Tapping a sentence seeks playback to that segment (synthesising the chapter first if needed). */
    fun onSegmentTap(spanHtmlId: String) {
        _uiState.update { it.copy(highlightedSpanId = spanHtmlId) }
        val segIndex = parseSegmentIndex(spanHtmlId) ?: return
        val book = _uiState.value.book ?: return
        val chapterIndex = _uiState.value.currentChapter?.index ?: return
        viewModelScope.launch {
            val segment = segmentRepository.getChapterSegments(book.id, chapterIndex)
                .firstOrNull { it.segmentIndex == segIndex }
            val audioReady = _uiState.value.audioPath?.let { File(it).exists() } == true
            if (audioReady && segment != null && segment.audioStartMs >= 0) {
                _seekToMs.value = segment.audioStartMs + encoderDelayMs
                _uiState.update { it.copy(playbackState = PlaybackState.PLAYING) }
            } else {
                // Audio not ready yet: synthesise, then jump to the tapped sentence on completion.
                pendingSeekSegment = segIndex
                synthesiseThenPlay()
            }
        }
    }

    fun consumeSeek() {
        _seekToMs.value = null
    }

    private fun parseSegmentIndex(htmlId: String): Int? =
        Regex("""c\d+s(\d+)""").find(htmlId)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun navigateChapter(delta: Int) {
        val current = _uiState.value.currentChapter ?: return
        val newIndex = current.index + delta
        if (newIndex in _uiState.value.chapters.indices) {
            loadChapter(newIndex)
        }
    }

    /** Called when the WebView follows an internal link to another chapter file. */
    fun navigateToChapterUrl(url: String) {
        val index = Regex("""/ch_(\d+)\.html""").find(url)
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
        if (index != _uiState.value.currentChapter?.index) loadChapter(index)
    }

    /** Play/pause toggle. Synthesises the current chapter on first play if needed. */
    fun togglePlayback() {
        val state = _uiState.value
        when (state.playbackState) {
            PlaybackState.PLAYING -> _uiState.update { it.copy(playbackState = PlaybackState.PAUSED) }
            else -> {
                if (state.audioPath != null && File(state.audioPath).exists()) {
                    _uiState.update { it.copy(playbackState = PlaybackState.PLAYING) }
                } else {
                    synthesiseThenPlay()
                }
            }
        }
    }

    private fun synthesiseThenPlay() {
        val chapter = _uiState.value.currentChapter ?: return
        val book = _uiState.value.book ?: return
        val chapterIndex = chapter.index

        _uiState.update { it.copy(playbackState = PlaybackState.LOADING, synthesisProgress = 0f, error = null) }

        val request = OneTimeWorkRequestBuilder<SynthesisWorker>()
            .setInputData(
                workDataOf(
                    SynthesisWorker.KEY_BOOK_ID to book.id,
                    SynthesisWorker.KEY_CHAPTER_INDEX to chapterIndex,
                )
            )
            .build()
        val uniqueName = "synth_${book.id}_$chapterIndex"
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)

        synthesisObserveJob?.cancel()
        synthesisObserveJob = workManager.getWorkInfosForUniqueWorkFlow(uniqueName)
            .onEach { infos ->
                val info = infos.lastOrNull() ?: return@onEach
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = info.progress.getFloat(SynthesisWorker.KEY_PROGRESS, 0f)
                        val partialPath = info.progress.getString(SynthesisWorker.KEY_PARTIAL_AUDIO_PATH)
                        _uiState.update { state ->
                            // Adopt each new partial version until the full file arrives. Once a
                            // non-partial (full) path is set, stop swapping back to partials.
                            val onPartial = state.audioPath == null || state.audioPath.contains("_partial")
                            val takePartial = partialPath != null && partialPath != state.audioPath && onPartial
                            state.copy(
                                synthesisProgress = progress,
                                audioPath = if (takePartial) partialPath else state.audioPath,
                                // Start playback as soon as the first partial is available.
                                playbackState = if (takePartial && state.playbackState == PlaybackState.LOADING)
                                    PlaybackState.PLAYING else state.playbackState,
                            )
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val audio = chapterAudioFile(chapterIndex)
                        // Only auto-start if the user is still waiting on this synthesis.
                        val stillWaiting = _uiState.value.playbackState == PlaybackState.LOADING
                        _uiState.update {
                            it.copy(
                                audioPath = audio.takeIf { f -> f.exists() }?.absolutePath,
                                synthesisProgress = null,
                                playbackState = if (stillWaiting && audio.exists()) PlaybackState.PLAYING
                                else if (stillWaiting) PlaybackState.IDLE else it.playbackState,
                                error = if (audio.exists()) it.error else "Audio was not produced",
                            )
                        }
                        // If the user tapped a sentence to start, jump there once audio is ready.
                        val seekSeg = pendingSeekSegment
                        pendingSeekSegment = null
                        if (audio.exists() && seekSeg != null) {
                            val seg = segmentRepository.getChapterSegments(book.id, chapterIndex)
                                .firstOrNull { it.segmentIndex == seekSeg }
                            if (seg != null && seg.audioStartMs >= 0) _seekToMs.value = seg.audioStartMs + encoderDelayMs
                        }
                    }
                    WorkInfo.State.FAILED ->
                        _uiState.update {
                            it.copy(
                                playbackState = PlaybackState.IDLE,
                                synthesisProgress = null,
                                error = info.outputData.getString(SynthesisWorker.KEY_ERROR)
                                    ?: "Synthesis failed (is a Japanese TTS voice installed?)",
                            )
                        }
                    else -> {}
                }
            }
            .launchIn(viewModelScope)
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    /** Called when the player reports playback finished. */
    fun onPlaybackEnded() {
        val state = _uiState.value
        // If we only reached the end of a growing partial (synthesis still running), stay in
        // PLAYING so playback resumes automatically when the next, longer partial loads.
        val waitingForMoreAudio = state.synthesisProgress != null &&
            state.audioPath?.contains("_partial") == true
        if (waitingForMoreAudio) return
        _uiState.update { it.copy(playbackState = PlaybackState.PAUSED) }
    }

    /** Maps a playback position to the spoken segment and highlights its HTML span. */
    fun updateHighlightFromPosition(positionMs: Long) {
        val book = _uiState.value.book ?: return
        val chapterIndex = _uiState.value.currentChapter?.index ?: return
        viewModelScope.launch {
            // Map the player position back onto the WAV-concatenation timeline before looking up
            // the segment, so the highlight tracks the audio actually being heard (see encoderDelayMs).
            val contentMs = (positionMs - encoderDelayMs).coerceAtLeast(0)
            val segment = segmentRepository.getSegmentAtPosition(book.id, chapterIndex, contentMs) ?: return@launch
            val htmlId = "c${segment.chapterIndex}s${segment.segmentIndex}"
            if (htmlId != _uiState.value.highlightedSpanId) {
                _uiState.update { it.copy(highlightedSpanId = htmlId) }
            }
        }
    }

    /** Returns the audio start (ms) of the segment [delta] sentences from [positionMs], for skip controls. */
    suspend fun sentenceSeekTarget(positionMs: Long, delta: Int): Long? {
        val book = _uiState.value.book ?: return null
        val chapterIndex = _uiState.value.currentChapter?.index ?: return null
        val segments = segmentRepository.getChapterSegments(book.id, chapterIndex)
            .filter { it.audioStartMs >= 0 }
            .sortedBy { it.audioStartMs }
        if (segments.isEmpty()) return null
        val contentMs = (positionMs - encoderDelayMs).coerceAtLeast(0)
        val currentIdx = segments.indexOfLast { it.audioStartMs <= contentMs }.coerceAtLeast(0)
        val targetIdx = (currentIdx + delta).coerceIn(segments.indices)
        return segments[targetIdx].audioStartMs + encoderDelayMs
    }

    fun setHighlightedSpan(spanId: String?) {
        _uiState.update { it.copy(highlightedSpanId = spanId) }
    }

    private fun chapterAudioFile(index: Int): File {
        val bookId = _uiState.value.book?.id ?: ""
        return File(File(libraryRepository.generateBookDir(bookId), "processed"), "ch_$index.aac")
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
