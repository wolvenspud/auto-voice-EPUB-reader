package com.autovice.reader.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("reader_preferences")

@Singleton
class ReaderPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val preferences: Flow<ReaderPreferences> = context.dataStore.data.map { prefs ->
        ReaderPreferences(
            fontSize = prefs[Keys.FONT_SIZE] ?: 16f,
            fontFamily = prefs[Keys.FONT_FAMILY] ?: "default",
            theme = ReaderTheme.valueOf(prefs[Keys.THEME] ?: ReaderTheme.LIGHT.name),
            lineHeight = prefs[Keys.LINE_HEIGHT] ?: 1.6f,
            playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
            autoScrollEnabled = prefs[Keys.AUTO_SCROLL_ENABLED] ?: true,
            autoScrollResumeDelaySecs = prefs[Keys.AUTO_SCROLL_RESUME_DELAY] ?: 4,
            characterAttributionEnabled = prefs[Keys.CHARACTER_ATTRIBUTION_ENABLED] ?: false,
            synthesisWindowChars = prefs[Keys.SYNTHESIS_WINDOW_CHARS] ?: 80_000,
            synthesisEvictionTrailChars = prefs[Keys.SYNTHESIS_EVICTION_TRAIL_CHARS] ?: 10_000,
        )
    }

    suspend fun updateFontSize(size: Float) =
        context.dataStore.edit { it[Keys.FONT_SIZE] = size }

    suspend fun updateFontFamily(family: String) =
        context.dataStore.edit { it[Keys.FONT_FAMILY] = family }

    suspend fun updateTheme(theme: ReaderTheme) =
        context.dataStore.edit { it[Keys.THEME] = theme.name }

    suspend fun updateLineHeight(height: Float) =
        context.dataStore.edit { it[Keys.LINE_HEIGHT] = height }

    suspend fun updatePlaybackSpeed(speed: Float) =
        context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = speed }

    suspend fun updateAutoScroll(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_SCROLL_ENABLED] = enabled }

    suspend fun updateAutoScrollResumeDelay(secs: Int) =
        context.dataStore.edit { it[Keys.AUTO_SCROLL_RESUME_DELAY] = secs }

    suspend fun updateCharacterAttribution(enabled: Boolean) =
        context.dataStore.edit { it[Keys.CHARACTER_ATTRIBUTION_ENABLED] = enabled }

    suspend fun updateSynthesisWindowChars(chars: Int) =
        context.dataStore.edit { it[Keys.SYNTHESIS_WINDOW_CHARS] = chars }

    private object Keys {
        val FONT_SIZE = floatPreferencesKey("font_size")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val THEME = stringPreferencesKey("theme")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val AUTO_SCROLL_ENABLED = booleanPreferencesKey("auto_scroll_enabled")
        val AUTO_SCROLL_RESUME_DELAY = intPreferencesKey("auto_scroll_resume_delay_secs")
        val CHARACTER_ATTRIBUTION_ENABLED = booleanPreferencesKey("character_attribution_enabled")
        val SYNTHESIS_WINDOW_CHARS = intPreferencesKey("synthesis_window_chars")
        val SYNTHESIS_EVICTION_TRAIL_CHARS = intPreferencesKey("synthesis_eviction_trail_chars")
    }
}
