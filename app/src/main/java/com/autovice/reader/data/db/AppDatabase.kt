package com.autovice.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        TtsSegmentEntity::class,
        CharacterVoiceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun ttsSegmentDao(): TtsSegmentDao
    abstract fun characterVoiceDao(): CharacterVoiceDao
}
