package com.autovice.reader.domain.model

/**
 * Central source of truth for voice-profile IDs so that segment attribution
 * (which stores `voiceProfileId` on each [TtsSegment]) and the character
 * registry (which stores [VoiceProfile] rows) never drift apart.
 *
 * IDs are derived from a short, stable prefix of the book UUID plus the
 * speaker. Character names are truncated to keep IDs bounded; the same
 * truncation MUST be used everywhere a character ID is produced.
 */
object VoiceProfileIds {

    private const val NAME_KEY_LEN = 8

    fun shortBookId(bookId: String): String = bookId.replace("-", "").take(8)

    fun narrator(bookIdShort: String) = "${bookIdShort}_narrator"

    fun unknownDialogue(bookIdShort: String) = "${bookIdShort}_unknown_dialogue"

    fun group(bookIdShort: String) = "${bookIdShort}_group"

    fun character(bookIdShort: String, name: String) =
        "${bookIdShort}_char_${name.take(NAME_KEY_LEN)}"

    /** Resolves the profile ID that a segment carrying [tag] should reference. */
    fun forSpeaker(bookIdShort: String, tag: SpeakerTag): String = when (tag) {
        is SpeakerTag.Narrator -> narrator(bookIdShort)
        is SpeakerTag.InnerMonologue -> narrator(bookIdShort)
        is SpeakerTag.UnknownDialogue -> unknownDialogue(bookIdShort)
        is SpeakerTag.Group -> group(bookIdShort)
        is SpeakerTag.Character -> character(bookIdShort, tag.name)
    }
}
