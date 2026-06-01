package com.autovice.reader.di

import android.content.Context
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    fun provideReaderPreferencesRepository(
        @ApplicationContext context: Context,
    ): ReaderPreferencesRepository = ReaderPreferencesRepository(context)
}
