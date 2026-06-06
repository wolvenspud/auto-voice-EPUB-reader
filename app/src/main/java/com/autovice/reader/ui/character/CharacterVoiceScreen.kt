package com.autovice.reader.ui.character

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import com.autovice.reader.domain.model.CharacterTier
import com.autovice.reader.domain.model.Gender
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.domain.model.VoiceProfile
import com.autovice.reader.tts.EngineVoice
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterVoiceScreen(
    onNavigateBack: () -> Unit,
    viewModel: CharacterVoiceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<VoiceProfile?>(null) }
    val editorSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Surface terminal refresh-audio outcomes, then reset.
    LaunchedEffect(uiState.refresh) {
        when (val r = uiState.refresh) {
            is RefreshUiState.Done -> {
                snackbarHost.showSnackbar(r.message)
                viewModel.dismissRefreshStatus()
            }
            is RefreshUiState.Error -> {
                snackbarHost.showSnackbar("Refresh failed: ${r.message}")
                viewModel.dismissRefreshStatus()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Character voices") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // One action: identify speakers, cast their voices, and re-synthesise.
                    val refreshing = uiState.refresh is RefreshUiState.Running
                    IconButton(
                        onClick = { viewModel.refreshAudio() },
                        enabled = !refreshing,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Generate & refresh audio")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val ref = uiState.refresh
            if (ref is RefreshUiState.Running) {
                Text(
                    text = ref.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val p = ref.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(
                text = if (uiState.aiAvailable) {
                    "Tap ✨ to identify speakers, assign their voices, and generate the audio."
                } else {
                    "Turn on character attribution and add an API key in Settings for per-character " +
                        "voices. Tap ✨ to generate audio in the narrator voice meanwhile."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.profiles, key = { it.profileId }) { profile ->
                    CharacterCard(
                        profile = profile,
                        voiceLabel = voiceLabelFor(profile, uiState.voiceCatalog),
                        onClick = { editing = profile },
                    )
                }
            }
        }
    }

    editing?.let { profile ->
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = editorSheetState,
        ) {
            VoiceEditor(
                profile = profile,
                voiceCatalog = uiState.voiceCatalog,
                onSave = { updated ->
                    viewModel.saveProfile(updated)
                    scope.launch { editorSheetState.hide() }.invokeOnCompletion { editing = null }
                },
                onCancel = {
                    scope.launch { editorSheetState.hide() }.invokeOnCompletion { editing = null }
                },
            )
        }
    }
}

/** The human-readable voice a profile is bound to (VOICEVOX style label, or device-TTS pitch). */
private fun voiceLabelFor(profile: VoiceProfile, catalog: List<EngineVoice>): String =
    when (profile.voiceEngineId) {
        VoiceEngineId.VOICEVOX -> {
            val match = catalog.firstOrNull { it.id == profile.externalVoiceId }
            "VOICEVOX · " + (match?.label ?: profile.externalVoiceId?.let { "id $it" } ?: "default")
        }
        else -> "Device TTS · pitch ${String.format("%.2f", profile.pitch)}"
    }

@Composable
private fun CharacterCard(profile: VoiceProfile, voiceLabel: String, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.characterName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(tierLabel(profile.tier))
                        if (profile.appearanceCount > 0) append(" · ${profile.appearanceCount} lines")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = voiceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            profile.estimatedGender?.let {
                AssistChip(onClick = onClick, label = { Text(genderLabel(it)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceEditor(
    profile: VoiceProfile,
    voiceCatalog: List<EngineVoice>,
    onSave: (VoiceProfile) -> Unit,
    onCancel: () -> Unit,
) {
    var engineId by remember(profile.profileId) { mutableStateOf(profile.voiceEngineId) }
    var voiceId by remember(profile.profileId) { mutableStateOf(profile.externalVoiceId) }
    var pitch by remember(profile.profileId) { mutableFloatStateOf(profile.pitch) }
    var speed by remember(profile.profileId) { mutableFloatStateOf(profile.synthesisSpeed) }
    var gender by remember(profile.profileId) { mutableStateOf(profile.estimatedGender) }
    var tier by remember(profile.profileId) { mutableStateOf(profile.tier) }
    val editable = profile.tier != CharacterTier.SYSTEM
    val voicevoxAvailable = voiceCatalog.isNotEmpty()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(profile.characterName, style = MaterialTheme.typography.headlineSmall)

        // Engine
        Text("Engine", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = engineId != VoiceEngineId.VOICEVOX,
                onClick = { engineId = VoiceEngineId.ANDROID_TTS },
                label = { Text("Device TTS") },
            )
            FilterChip(
                selected = engineId == VoiceEngineId.VOICEVOX,
                onClick = { engineId = VoiceEngineId.VOICEVOX },
                enabled = voicevoxAvailable,
                label = { Text("VOICEVOX") },
            )
        }

        if (engineId == VoiceEngineId.VOICEVOX) {
            VoicePicker(catalog = voiceCatalog, selectedId = voiceId, onSelect = { voiceId = it })
        } else {
            EditorSlider(
                label = "Pitch",
                value = pitch,
                valueLabel = String.format("%.2f", pitch),
                valueRange = 0.5f..2.0f,
                steps = 14,
                onValueChange = { pitch = it },
            )
            EditorSlider(
                label = "Synthesis speed",
                value = speed,
                valueLabel = String.format("%.2f×", speed),
                valueRange = 0.5f..2.0f,
                steps = 14,
                onValueChange = { speed = it },
            )
        }

        if (editable) {
            Text("Gender", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = gender == Gender.MALE,
                    onClick = { gender = if (gender == Gender.MALE) null else Gender.MALE },
                    label = { Text("Male") },
                )
                FilterChip(
                    selected = gender == Gender.FEMALE,
                    onClick = { gender = if (gender == Gender.FEMALE) null else Gender.FEMALE },
                    label = { Text("Female") },
                )
            }

            Text("Tier", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CharacterTier.MAJOR, CharacterTier.MINOR, CharacterTier.ONE_OFF).forEach { t ->
                    FilterChip(
                        selected = tier == t,
                        onClick = { tier = t },
                        label = { Text(tierLabel(t)) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = {
                onSave(
                    profile.copy(
                        voiceEngineId = engineId,
                        externalVoiceId = if (engineId == VoiceEngineId.VOICEVOX) voiceId else null,
                        pitch = pitch,
                        synthesisSpeed = speed,
                        estimatedGender = gender,
                        tier = tier,
                    )
                )
            }) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(catalog: List<EngineVoice>, selectedId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = catalog.firstOrNull { it.id == selectedId }?.label ?: "Choose a voice"
    Column {
        Text("Voice", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                catalog.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) },
                        onClick = { onSelect(v.id); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorSlider(
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

private fun tierLabel(tier: CharacterTier): String = when (tier) {
    CharacterTier.MAJOR -> "Major"
    CharacterTier.MINOR -> "Minor"
    CharacterTier.ONE_OFF -> "One-off"
    CharacterTier.SYSTEM -> "System"
}

private fun genderLabel(gender: Gender): String = when (gender) {
    Gender.MALE -> "Male"
    Gender.FEMALE -> "Female"
}
