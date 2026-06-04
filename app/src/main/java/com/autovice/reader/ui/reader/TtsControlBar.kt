package com.autovice.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TtsControlBar(
    playbackState: PlaybackState,
    playbackSpeed: Float,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onChapterBack: () -> Unit,
    onChapterForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onChapterBack, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FastRewind, contentDescription = "Previous chapter")
            }
            IconButton(onClick = onSkipBack, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous sentence")
            }
            IconButton(
                onClick = if (playbackState == PlaybackState.PLAYING) onPause else onPlay,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (playbackState == PlaybackState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState == PlaybackState.PLAYING) "Pause" else "Play",
                )
            }
            IconButton(onClick = onSkipForward, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next sentence")
            }
            IconButton(onClick = onChapterForward, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FastForward, contentDescription = "Next chapter")
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SpeedDropdown(speed = playbackSpeed, onSpeedChange = onSpeedChange)
            }
        }
    }
}

private val speedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

@Composable
private fun SpeedDropdown(speed: Float, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (speed == 1.0f) "1×" else "${speed}×"
    Box {
        SuggestionChip(
            onClick = { expanded = true },
            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            speedSteps.forEach { step ->
                DropdownMenuItem(
                    text = { Text(if (step == 1.0f) "1×" else "${step}×") },
                    onClick = { onSpeedChange(step); expanded = false },
                )
            }
        }
    }
}
