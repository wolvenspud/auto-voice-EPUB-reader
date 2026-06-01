package com.autovice.reader.data.repository

import com.autovice.reader.data.db.TtsSegmentDao
import com.autovice.reader.data.db.TtsSegmentEntity
import com.autovice.reader.domain.model.AttributionSource
import com.autovice.reader.domain.model.SpeakerTag
import com.autovice.reader.domain.model.TtsSegment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SegmentRepository @Inject constructor(
    private val segmentDao: TtsSegmentDao,
) {

    suspend fun getChapterSegments(bookId: String, chapterIndex: Int): List<TtsSegment> =
        segmentDao.getChapterSegments(bookId, chapterIndex).map { it.toDomain() }

    suspend fun getSegmentAtPosition(bookId: String, chapterIndex: Int, positionMs: Long): TtsSegment? =
        segmentDao.getSegmentAtPosition(bookId, chapterIndex, positionMs)?.toDomain()

    suspend fun insertAll(segments: List<TtsSegment>) =
        segmentDao.insertAll(segments.map { TtsSegmentEntity.fromDomain(it) })

    suspend fun updateAudioTimestamp(spanId: String, startMs: Long, durationMs: Long) =
        segmentDao.updateAudioTimestamp(spanId, startMs, durationMs)

    suspend fun updateSpeaker(spanId: String, speakerTag: SpeakerTag, voiceProfileId: String) =
        segmentDao.updateSpeaker(spanId, speakerTag.toStorageString(), voiceProfileId)

    suspend fun getSegmentsNeedingResynthesis(bookId: String): List<TtsSegment> =
        segmentDao.getSegmentsNeedingResynthesis(bookId).map { it.toDomain() }

    suspend fun deleteAllForBook(bookId: String) =
        segmentDao.deleteAllForBook(bookId)
}
