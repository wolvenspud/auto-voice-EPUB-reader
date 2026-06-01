package com.autovice.reader.di

import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.data.repository.ProgressRepository
import com.autovice.reader.data.repository.SegmentRepository
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    // All repositories use @Inject constructor with @Singleton annotation
    // so Hilt auto-provides them without explicit @Provides methods.
}
