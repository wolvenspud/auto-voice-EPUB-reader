package com.autovice.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autovice.reader.data.preferences.ApiKeyConfig
import com.autovice.reader.data.preferences.ApiKeyStore
import com.autovice.reader.data.preferences.LlmProvider
import com.autovice.reader.data.preferences.ReaderPreferences
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.preferences.ReaderTheme
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.llm.AttributionLine
import com.autovice.reader.llm.LlmAttributionClient
import com.autovice.reader.tts.VoiceEngineRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of the "Test connections" action: null fields = untested, otherwise a status line. */
data class ConnectionTest(
    val testing: Boolean = false,
    val voicevox: String? = null,
    val llm: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: ReaderPreferencesRepository,
    private val apiKeyStore: ApiKeyStore,
    private val engineRegistry: VoiceEngineRegistry,
    private val attributionClient: LlmAttributionClient,
) : ViewModel() {

    val preferences: StateFlow<ReaderPreferences> = prefs.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReaderPreferences(),
    )

    val apiKeyConfig: StateFlow<ApiKeyConfig> = apiKeyStore.config

    private val _connectionTest = MutableStateFlow(ConnectionTest())
    val connectionTest: StateFlow<ConnectionTest> = _connectionTest.asStateFlow()

    /** Checks VOICEVOX reachability and that the API key actually works, surfacing each result. */
    fun testConnections() {
        if (_connectionTest.value.testing) return
        viewModelScope.launch {
            _connectionTest.value = ConnectionTest(testing = true)
            val voicevox = runCatching { engineRegistry.engineFor(VoiceEngineId.VOICEVOX).listVoices() }
                .fold(
                    onSuccess = {
                        if (it.isEmpty()) "Unreachable — check the URL and that the server is running"
                        else "OK · ${it.size} voices"
                    },
                    onFailure = { "Unreachable: ${it.message ?: "no response"}" },
                )
            val config = apiKeyStore.current()
            val provider = config.provider.name.lowercase()
            val llm = if (!config.hasActiveKey) {
                "No $provider API key set"
            } else {
                runCatching {
                    attributionClient.attribute(
                        config.provider, config.activeKey, listOf(AttributionLine(0, "テスト")), emptyList(),
                    )
                }.fold(
                    onSuccess = { "OK · $provider key works" },
                    onFailure = { "Failed: ${it.message ?: "no response"}" },
                )
            }
            _connectionTest.value = ConnectionTest(testing = false, voicevox = voicevox, llm = llm)
        }
    }

    fun updateClaudeKey(key: String) = apiKeyStore.setClaudeKey(key)

    fun updateOpenAiKey(key: String) = apiKeyStore.setOpenAiKey(key)

    fun updateLlmProvider(provider: LlmProvider) = apiKeyStore.setProvider(provider)

    fun updateFontSize(size: Float) {
        viewModelScope.launch { prefs.updateFontSize(size) }
    }

    fun updateTheme(theme: ReaderTheme) {
        viewModelScope.launch { prefs.updateTheme(theme) }
    }

    fun updateLineHeight(height: Float) {
        viewModelScope.launch { prefs.updateLineHeight(height) }
    }

    fun updatePlaybackSpeed(speed: Float) {
        viewModelScope.launch { prefs.updatePlaybackSpeed(speed) }
    }

    fun updateAutoScroll(enabled: Boolean) {
        viewModelScope.launch { prefs.updateAutoScroll(enabled) }
    }

    fun updateAutoScrollResumeDelay(secs: Int) {
        viewModelScope.launch { prefs.updateAutoScrollResumeDelay(secs) }
    }

    fun updateCharacterAttribution(enabled: Boolean) {
        viewModelScope.launch { prefs.updateCharacterAttribution(enabled) }
    }

    fun updateVoicevoxUrl(url: String) {
        viewModelScope.launch { prefs.updateVoicevoxBaseUrl(url) }
    }
}
