package com.autovice.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autovice.reader.data.preferences.ApiKeyConfig
import com.autovice.reader.data.preferences.ApiKeyStore
import com.autovice.reader.data.preferences.LlmProvider
import com.autovice.reader.data.preferences.ReaderPreferences
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.preferences.ReaderTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: ReaderPreferencesRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {

    val preferences: StateFlow<ReaderPreferences> = prefs.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReaderPreferences(),
    )

    val apiKeyConfig: StateFlow<ApiKeyConfig> = apiKeyStore.config

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
