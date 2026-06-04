package com.autovice.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import android.net.Uri
import com.autovice.reader.data.preferences.ReaderTheme
import com.autovice.reader.tts.TtsPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onNavigateBack: () -> Unit,
    onNavigateToCharacters: (chapterIndex: Int) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showChapterList by remember { mutableStateOf(false) }
    val chapterSheetState = rememberModalBottomSheetState()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    val bridge = remember { AndroidBridge(viewModel) }
    val context = LocalContext.current

    // Connect to the MediaSessionService that owns the ExoPlayer.
    val controller by produceState<MediaController?>(initialValue = null) {
        val token = SessionToken(context, ComponentName(context, TtsPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ value = future.get() }, ContextCompat.getMainExecutor(context))
        awaitDispose {
            value?.release()
            MediaController.releaseFuture(future)
        }
    }

    // Notify when playback finishes so the UI returns to a paused state.
    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) viewModel.onPlaybackEnded()
            }
        }
        c?.addListener(listener)
        onDispose { c?.removeListener(listener) }
    }

    // Reconcile the player to the desired audio / play state / speed / seek target.
    val seekToMs by viewModel.seekToMs.collectAsState()
    var loadedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller, uiState.audioPath, uiState.playbackState, uiState.playbackSpeed, seekToMs) {
        val c = controller ?: return@LaunchedEffect
        val path = uiState.audioPath
        if (path != null && path != loadedPath) {
            // Preserve playback position when swapping partial→full chapter file.
            val posToRestore = if (loadedPath != null && c.currentPosition > 0) c.currentPosition else 0L
            c.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            c.prepare()
            if (posToRestore > 0) c.seekTo(posToRestore)
            loadedPath = path
        }
        c.setPlaybackSpeed(uiState.playbackSpeed)
        when (uiState.playbackState) {
            PlaybackState.PLAYING -> if (path != null) {
                // If the chapter previously played to the end, restart from the top.
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
            PlaybackState.PAUSED -> c.pause()
            PlaybackState.IDLE -> c.pause()
            PlaybackState.LOADING -> {}
        }
        // Tap-to-seek: jump to the tapped sentence (media item is loaded above).
        seekToMs?.let { ms ->
            if (path != null) {
                c.seekTo(ms)
                c.play()
            }
            viewModel.consumeSeek()
        }
    }

    // While playing, drive the WebView highlight from the player position.
    LaunchedEffect(controller, uiState.playbackState) {
        val c = controller ?: return@LaunchedEffect
        if (uiState.playbackState == PlaybackState.PLAYING) {
            while (isActive) {
                viewModel.updateHighlightFromPosition(c.currentPosition)
                delay(200)
            }
        }
    }

    val readerColors = readerColorsFor(uiState.theme)
    // Animate the top inset so content fills the screen when the app bar is hidden.
    val topPadding by animateDpAsState(
        targetValue = if (uiState.controlsVisible) 64.dp else 0.dp,
        label = "readerTopPadding",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(android.graphics.Color.parseColor(readerColors.bgHex))),
    ) {
        EpubWebView(
            htmlFilePath = uiState.currentChapter?.htmlPath,
            highlightedSpanId = uiState.highlightedSpanId,
            bridge = bridge,
            bgHex = readerColors.bgHex,
            textHex = readerColors.textHex,
            fontSize = uiState.fontSize,
            lineHeight = uiState.lineHeight,
            onBridgeReady = {},
            onInternalLink = { url -> viewModel.navigateToChapterUrl(url) },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = topPadding),
        )

        // Top app bar
        AnimatedVisibility(
            visible = uiState.controlsVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column {
                TopAppBar(
                    title = { Text(uiState.currentChapter?.title ?: uiState.book?.title ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            onNavigateToCharacters(uiState.currentChapter?.index ?: 0)
                        }) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = "Character voices")
                        }
                        IconButton(onClick = { showChapterList = true }) {
                            Icon(Icons.Default.List, contentDescription = "Chapter list")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
                val currentIdx = uiState.currentChapter?.index ?: 0
                val total = uiState.chapters.size.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { (currentIdx + 1).toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Bottom TTS control bar
        AnimatedVisibility(
            visible = uiState.controlsVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column {
                if (uiState.playbackState == PlaybackState.LOADING) {
                    val p = uiState.synthesisProgress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                TtsControlBar(
                    playbackState = uiState.playbackState,
                    playbackSpeed = uiState.playbackSpeed,
                    onPlay = { viewModel.togglePlayback() },
                    onPause = { viewModel.togglePlayback() },
                    onSkipBack = {
                        scope.launch {
                            val c = controller ?: return@launch
                            viewModel.sentenceSeekTarget(c.currentPosition, -1)?.let { c.seekTo(it) }
                        }
                    },
                    onSkipForward = {
                        scope.launch {
                            val c = controller ?: return@launch
                            viewModel.sentenceSeekTarget(c.currentPosition, 1)?.let { c.seekTo(it) }
                        }
                    },
                    onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                    onChapterBack = { viewModel.navigateChapter(-1) },
                    onChapterForward = { viewModel.navigateChapter(1) },
                )
            }
        }
    }

    if (showChapterList) {
        ModalBottomSheet(
            onDismissRequest = { showChapterList = false },
            sheetState = chapterSheetState,
        ) {
            LazyColumn(modifier = Modifier.padding(bottom = 32.dp)) {
                itemsIndexed(uiState.chapters) { index, chapter ->
                    ListItem(
                        headlineContent = { Text(chapter.title ?: "Chapter ${index + 1}") },
                        modifier = Modifier.clickable {
                            scope.launch { chapterSheetState.hide() }.invokeOnCompletion {
                                showChapterList = false
                                viewModel.loadChapter(index)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** Page background / text colours per reader theme, as CSS hex strings. */
data class ReaderColors(val bgHex: String, val textHex: String)

fun readerColorsFor(theme: ReaderTheme): ReaderColors = when (theme) {
    ReaderTheme.LIGHT -> ReaderColors("#ffffff", "#1a1a1a")
    ReaderTheme.SEPIA -> ReaderColors("#f4ecd8", "#5b4636")
    ReaderTheme.DARK -> ReaderColors("#121212", "#e0e0e0")
}
