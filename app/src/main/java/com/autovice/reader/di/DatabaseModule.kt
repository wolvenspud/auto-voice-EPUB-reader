package com.autovice.reader.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /** v2: adds tts_segments.ttsTextOverride (reading-corrected TTS text). */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tts_segments ADD COLUMN ttsTextOverride TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "autovice_reader.db")
            .addMigrations(MIGRATION_1_2)
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
