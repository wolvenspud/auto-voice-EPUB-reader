package com.autovice.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onNavigateBack: () -> Unit,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { viewModel.toggleControls() },
    ) {
        EpubWebView(
            htmlFilePath = uiState.currentChapter?.htmlPath,
            highlightedSpanId = uiState.highlightedSpanId,
            bridge = bridge,
            onBridgeReady = {},
            modifier = Modifier.fillMaxSize(),
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
                        IconButton(onClick = { showChapterList = true }) {
                            Icon(Icons.Default.List, contentDescription = "Chapter list")
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
            TtsControlBar(
                playbackState = uiState.playbackState,
                playbackSpeed = uiState.playbackSpeed,
                onPlay = { viewModel.setPlaybackState(PlaybackState.PLAYING) },
                onPause = { viewModel.setPlaybackState(PlaybackState.PAUSED) },
                onSkipBack = { /* skip to previous sentence via service */ },
                onSkipForward = { /* skip to next sentence via service */ },
                onSpeedChange = { /* update speed via service */ },
                onChapterBack = { viewModel.navigateChapter(-1) },
                onChapterForward = { viewModel.navigateChapter(1) },
            )
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
