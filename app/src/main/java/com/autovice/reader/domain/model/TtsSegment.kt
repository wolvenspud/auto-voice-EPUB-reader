package com.autovice.reader.domain.model

data class TtsSegment(
    /** Globally unique ID matching the injected HTML span: "bookId_ch{N}_seg{M}". */
    val spanId: String,
    val bookId: String,
    val chapterIndex: Int,
    val segmentIndex: Int,
    val rawText: String,
    /**
     * Optional reading-corrected text fed to the TTS engine instead of [rawText], with
     * context-ambiguous kanji spelled in kana (e.g. お腹が空いた → お腹がすいた). Null when the
     * reading is unambiguous. [rawText] is always what's shown on screen.
     */
    val ttsTextOverride: String? = null,
    val speakerTag: SpeakerTag,
    val voiceProfileId: String,
    val attributionSource: AttributionSource,
    val attributionConfidence: Float,
    /** Millisecond offset within the chapter audio file. -1 = not yet synthesised. */
    val audioStartMs: Long = -1L,
    val audioDurationMs: Long = -1L,
    val needsResynthesis: Boolean = false,
)

enum class AttributionSource { HEURISTIC, LLM, USER }
