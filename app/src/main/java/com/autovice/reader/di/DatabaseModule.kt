package com.autovice.reader.di

import android.content.Context
import androidx.room.Room
import com.autovice.reader.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "autovice_reader.db")
            .build()

    @Provides
    fun provideBookDao(db: AppDatabase) = db.bookDao()

    @Provides
    fun provideReadingProgressDao(db: AppDatabase) = db.readingProgressDao()

    @Provides
    fun provideTtsSegmentDao(db: AppDatabase) = db.ttsSegmentDao()

    @Provides
    fun provideCharacterVoiceDao(db: AppDatabase) = db.characterVoiceDao()
}
