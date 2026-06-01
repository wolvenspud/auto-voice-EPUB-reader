package com.autovice.reader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val segmentIndex: Int,
    /** Millisecond position within the chapter audio file for exact restore. */
    val audioPositionMs: Long,
    val lastReadAt: Long,
)
