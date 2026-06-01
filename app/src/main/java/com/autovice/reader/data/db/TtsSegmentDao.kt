package com.autovice.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TtsSegmentDao {

    @Query("""
        SELECT * FROM tts_segments
        WHERE bookId = :bookId AND chapterIndex = :chapterIndex
        ORDER BY segmentIndex ASC
    """)
    suspend fun getChapterSegments(bookId: String, chapterIndex: Int): List<TtsSegmentEntity>

    @Query("SELECT * FROM tts_segments WHERE spanId = :spanId")
    suspend fun getSegment(spanId: String): TtsSegmentEntity?

    /**
     * Returns the segment whose audio window contains [positionMs].
     * Used by ExoPlayer position polling to drive WebView highlight.
     */
    @Query("""
        SELECT * FROM tts_segments
        WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND audioStartMs <= :positionMs
        ORDER BY audioStartMs DESC
        LIMIT 1
    """)
    suspend fun getSegmentAtPosition(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
    ): TtsSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<TtsSegmentEntity>)

    @Query("""
        UPDATE tts_segments
        SET audioStartMs = :startMs, audioDurationMs = :durationMs, needsResynthesis = 0
        WHERE spanId = :spanId
    """)
    suspend fun updateAudioTimestamp(spanId: String, startMs: Long, durationMs: Long)

    /** Called when user corrects a speaker attribution via the character registry UI. */
    @Query("""
        UPDATE tts_segments
        SET speakerTag = :speakerTag, voiceProfileId = :voiceProfileId, attributionSource = 'USER', attributionConfidence = 1.0
        WHERE spanId = :spanId
    """)
    suspend fun updateSpeaker(spanId: String, speakerTag: String, voiceProfileId: String)

    /** Marks all segments using [profileId] as dirty after a voice profile change. */
    @Query("""
        UPDATE tts_segments
        SET needsResynthesis = 1
        WHERE bookId = :bookId AND voiceProfileId = :profileId
    """)
    suspend fun markResynthesisNeeded(bookId: String, profileId: String)

    @Query("SELECT * FROM tts_segments WHERE bookId = :bookId AND needsResynthesis = 1")
    suspend fun getSegmentsNeedingResynthesis(bookId: String): List<TtsSegmentEntity>

    @Query("DELETE FROM tts_segments WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    /**
     * Counts synthesised characters ahead of [segmentIndex] within the window
     * [windowStart]..[segmentIndex], used by the synthesis scheduler.
     */
    @Query("""
        SELECT COALESCE(SUM(LENGTH(rawText)), 0) FROM tts_segments
        WHERE bookId = :bookId
          AND (chapterIndex > :windowChapter OR (chapterIndex = :windowChapter AND segmentIndex >= :windowSegment))
          AND (chapterIndex < :currentChapter OR (chapterIndex = :currentChapter AND segmentIndex <= :currentSegment))
    """)
    suspend fun countCharsBetween(
        bookId: String,
        windowChapter: Int,
        windowSegment: Int,
        currentChapter: Int,
        currentSegment: Int,
    ): Int
}
