package com.autovice.reader.di

import android.content.Context
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.tts.AndroidTtsEngine
import com.autovice.reader.tts.VoiceEngine
import com.autovice.reader.tts.VoiceEngineRegistry
import com.autovice.reader.tts.VoicevoxEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {

    @Provides
    @Singleton
    fun provideVoiceEngines(
        @ApplicationContext ctx: Context,
        http: OkHttpClient,
        prefs: ReaderPreferencesRepository,
    ): Map<String, @JvmSuppressWildcards VoiceEngine> = mapOf(
        VoiceEngineId.ANDROID_TTS to AndroidTtsEngine(ctx),
        VoiceEngineId.VOICEVOX to VoicevoxEngine(http, prefs),
    )

    @Provides
    @Singleton
    fun provideVoiceEngineRegistry(
        engines: Map<String, @JvmSuppressWildcards VoiceEngine>,
    ): VoiceEngineRegistry = VoiceEngineRegistry(engines)
}
