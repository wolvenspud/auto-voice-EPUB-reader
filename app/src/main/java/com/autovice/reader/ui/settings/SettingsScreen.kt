package com.autovice.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autovice.reader.data.preferences.ReaderTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(title = "Display") {
                // Font size
                LabeledSlider(
                    label = "Font size",
                    value = prefs.fontSize,
                    valueLabel = "${prefs.fontSize.roundToInt()}sp",
                    valueRange = 12f..24f,
                    steps = 11,
                    onValueChange = viewModel::updateFontSize,
                )
                // Line height
                LabeledSlider(
                    label = "Line height",
                    value = prefs.lineHeight,
                    valueLabel = String.format("%.1f", prefs.lineHeight),
                    valueRange = 1.2f..2.5f,
                    steps = 12,
                    onValueChange = viewModel::updateLineHeight,
                )
                // Theme
                SettingsRow(label = "Theme") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = prefs.theme == theme,
                                onClick = { viewModel.updateTheme(theme) },
                                label = {
                                    Text(
                                        when (theme) {
                                            ReaderTheme.LIGHT -> "Light"
                                            ReaderTheme.SEPIA -> "Sepia"
                                            ReaderTheme.DARK -> "Dark"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            SettingsSection(title = "Playback") {
                // Speed
                LabeledSlider(
                    label = "Playback speed",
                    value = prefs.playbackSpeed,
                    valueLabel = "${prefs.playbackSpeed}×",
                    valueRange = 0.5f..3.0f,
                    steps = 24,
                    onValueChange = viewModel::updatePlaybackSpeed,
                )
                // Auto-scroll
                SettingsRow(label = "Auto-scroll") {
                    Switch(
                        checked = prefs.autoScrollEnabled,
                        onCheckedChange = viewModel::updateAutoScroll,
                    )
                }
                // Auto-scroll resume delay
                SettingsRow(label = "Resume after scroll") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 8).forEach { secs ->
                            FilterChip(
                                selected = prefs.autoScrollResumeDelaySecs == secs,
                                onClick = { viewModel.updateAutoScrollResumeDelay(secs) },
                                label = { Text("${secs}s") }
                            )
                        }
                    }
                }
            }

            SettingsSection(title = "Advanced") {
                SettingsInfoRow(
                    label = "Synthesis window",
                    value = "${prefs.synthesisWindowChars / 1000}k chars",
                )
                SettingsInfoRow(
                    label = "Eviction trail",
                    value = "${prefs.synthesisEvictionTrailChars / 1000}k chars",
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueLabel: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
