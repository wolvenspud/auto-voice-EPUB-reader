package com.autovice.reader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autovice.reader.domain.model.AttributionSource
import com.autovice.reader.domain.model.SpeakerTag
import com.autovice.reader.domain.model.TtsSegment

@Entity(
    tableName = "tts_segments",
    indices = [Index(value = ["bookId", "chapterIndex", "segmentIndex"])]
)
data class TtsSegmentEntity(
    @PrimaryKey val spanId: String,
    val bookId: String,
    val chapterIndex: Int,
    val segmentIndex: Int,
    val rawText: String,
    val ttsTextOverride: String? = null,
    val speakerTag: String,
    val voiceProfileId: String,
    val attributionSource: String,
    val attributionConfidence: Float,
    val audioStartMs: Long = -1L,
    val audioDurationMs: Long = -1L,
    val needsResynthesis: Boolean = false,
) {
    fun toDomain() = TtsSegment(
        spanId = spanId,
        bookId = bookId,
        chapterIndex = chapterIndex,
        segmentIndex = segmentIndex,
        rawText = rawText,
        ttsTextOverride = ttsTextOverride,
        speakerTag = SpeakerTag.fromStorageString(speakerTag),
        voiceProfileId = voiceProfileId,
        attributionSource = AttributionSource.valueOf(attributionSource),
        attributionConfidence = attributionConfidence,
        audioStartMs = audioStartMs,
        audioDurationMs = audioDurationMs,
        needsResynthesis = needsResynthesis,
    )

    companion object {
        fun fromDomain(segment: TtsSegment) = TtsSegmentEntity(
            spanId = segment.spanId,
            bookId = segment.bookId,
            chapterIndex = segment.chapterIndex,
            segmentIndex = segment.segmentIndex,
            rawText = segment.rawText,
            ttsTextOverride = segment.ttsTextOverride,
            speakerTag = segment.speakerTag.toStorageString(),
            voiceProfileId = segment.voiceProfileId,
            attributionSource = segment.attributionSource.name,
            attributionConfidence = segment.attributionConfidence,
            audioStartMs = segment.audioStartMs,
            audioDurationMs = segment.audioDurationMs,
            needsResynthesis = segment.needsResynthesis,
        )
    }
}
