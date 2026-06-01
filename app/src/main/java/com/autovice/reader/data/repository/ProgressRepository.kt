package com.autovice.reader.data.repository

import com.autovice.reader.data.db.ReadingProgressDao
import com.autovice.reader.data.db.ReadingProgressEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressRepository @Inject constructor(
    private val progressDao: ReadingProgressDao,
) {

    suspend fun getProgress(bookId: String): ReadingProgressEntity? =
        progressDao.getProgress(bookId)

    suspend fun saveProgress(
        bookId: String,
        chapterIndex: Int,
        segmentIndex: Int,
        audioPositionMs: Long,
    ) {
        progressDao.saveProgress(
            ReadingProgressEntity(
                bookId = bookId,
                chapterIndex = chapterIndex,
                segmentIndex = segmentIndex,
                audioPositionMs = audioPositionMs,
                lastReadAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteProgress(bookId: String) =
        progressDao.deleteProgress(bookId)
}
