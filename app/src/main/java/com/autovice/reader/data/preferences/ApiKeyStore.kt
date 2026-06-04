package com.autovice.reader.data.preferences

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class LlmProvider { CLAUDE, OPENAI }

/**
 * Snapshot of the user's LLM credentials. Keys are sensitive — never log this object.
 */
data class ApiKeyConfig(
    val claudeKey: String = "",
    val openAiKey: String = "",
    val provider: LlmProvider = LlmProvider.CLAUDE,
) {
    val activeKey: String
        get() = when (provider) {
            LlmProvider.CLAUDE -> claudeKey
            LlmProvider.OPENAI -> openAiKey
        }

    /** True when the currently selected provider has a non-blank key configured. */
    val hasActiveKey: Boolean get() = activeKey.isNotBlank()
}

/**
 * Stores LLM API keys in [EncryptedSharedPreferences] (AES-256, hardware-backed master key
 * where available). Exposes a reactive [config] snapshot for the UI.
 */
@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<ApiKeyConfig> = _config.asStateFlow()

    private fun readConfig() = ApiKeyConfig(
        claudeKey = prefs.getString(KEY_CLAUDE, "") ?: "",
        openAiKey = prefs.getString(KEY_OPENAI, "") ?: "",
        provider = runCatching {
            LlmProvider.valueOf(prefs.getString(KEY_PROVIDER, LlmProvider.CLAUDE.name)!!)
        }.getOrDefault(LlmProvider.CLAUDE),
    )

    /** Read the current config directly (used off the main thread by workers). */
    fun current(): ApiKeyConfig = _config.value

    fun setClaudeKey(key: String) {
        prefs.edit().putString(KEY_CLAUDE, key.trim()).apply()
        _config.value = readConfig()
    }

    fun setOpenAiKey(key: String) {
        prefs.edit().putString(KEY_OPENAI, key.trim()).apply()
        _config.value = readConfig()
    }

    fun setProvider(provider: LlmProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        _config.value = readConfig()
    }

    private companion object {
        const val FILE_NAME = "secure_api_keys"
        const val KEY_CLAUDE = "claude_api_key"
        const val KEY_OPENAI = "openai_api_key"
        const val KEY_PROVIDER = "llm_provider"
    }
}
