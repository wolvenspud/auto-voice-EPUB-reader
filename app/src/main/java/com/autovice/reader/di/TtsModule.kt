package com.autovice.reader.di

import android.content.Context
import com.autovice.reader.tts.AndroidTtsEngine
import com.autovice.reader.tts.VoiceEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {

    @Provides
    @Singleton
    fun provideVoiceEngine(@ApplicationContext ctx: Context): VoiceEngine = AndroidTtsEngine(ctx)
}
