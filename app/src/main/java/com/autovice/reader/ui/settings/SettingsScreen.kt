package com.autovice.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autovice.reader.data.preferences.LlmProvider
import com.autovice.reader.data.preferences.ReaderTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsState()
    val apiKey by viewModel.apiKeyConfig.collectAsState()
    val connTest by viewModel.connectionTest.collectAsState()

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

            SettingsSection(title = "AI speaker attribution") {
                SettingsRow(label = "Character attribution") {
                    Switch(
                        checked = prefs.characterAttributionEnabled,
                        onCheckedChange = viewModel::updateCharacterAttribution,
                    )
                }
                Text(
                    text = "When off, every line is read by the narrator voice. When on, dialogue is " +
                        "attributed to per-character voices using the API key below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SettingsRow(label = "Provider") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = apiKey.provider == LlmProvider.CLAUDE,
                            onClick = { viewModel.updateLlmProvider(LlmProvider.CLAUDE) },
                            label = { Text("Claude") },
                        )
                        FilterChip(
                            selected = apiKey.provider == LlmProvider.OPENAI,
                            onClick = { viewModel.updateLlmProvider(LlmProvider.OPENAI) },
                            label = { Text("OpenAI") },
                        )
                    }
                }
                ApiKeyField(
                    label = "Claude API key",
                    initialValue = apiKey.claudeKey,
                    onValueChange = viewModel::updateClaudeKey,
                )
                ApiKeyField(
                    label = "OpenAI API key",
                    initialValue = apiKey.openAiKey,
                    onValueChange = viewModel::updateOpenAiKey,
                )
                Text(
                    text = "Keys are stored encrypted on-device and used only to attribute dialogue speakers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SettingsSection(title = "VOICEVOX") {
                var url by remember { mutableStateOf(prefs.voicevoxBaseUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; viewModel.updateVoicevoxUrl(it) },
                    label = { Text("Engine URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Base URL of a running VOICEVOX engine. Default reaches the host machine " +
                        "from the emulator (10.0.2.2); use a LAN/remote address for a physical device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // Test VOICEVOX reachability + that the API key works, so failures are diagnosable.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.testConnections() },
                        enabled = !connTest.testing,
                    ) { Text("Test connections") }
                    if (connTest.testing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                connTest.voicevox?.let { ConnectionResultRow("VOICEVOX", it) }
                connTest.llm?.let { ConnectionResultRow("API key", it) }
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
private fun ApiKeyField(
    label: String,
    initialValue: String,
    onValueChange: (String) -> Unit,
) {
    // Seeded once from the stored value; edits commit straight to the encrypted store.
    var text by remember { mutableStateOf(initialValue) }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide key" else "Show key",
                )
            }
        },
    )
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

@Composable
private fun ConnectionResultRow(label: String, status: String) {
    val ok = status.startsWith("OK")
    Text(
        text = "$label: $status",
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}
