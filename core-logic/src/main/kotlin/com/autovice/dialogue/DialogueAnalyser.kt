package com.autovice.dialogue

import com.autovice.epub.ProcessedSegment

data class DialogueAnnotation(
    val spanId: String,
    val speakerTag: String,
    val confidence: Float,
    val source: String = "heuristic",
)

class DialogueAnalyser {

    fun analyse(
        segments: List<ProcessedSegment>,
        chapterIndex: Int,
        bookId: String,
    ): List<DialogueAnnotation> {
        return segments.mapIndexed { idx, segment ->
            val text = segment.ttsText
            val tag = when {
                TRIPLE_BRACKET_PATTERN.containsMatchIn(text) -> {
                    DialogueAnnotation(segment.spanId, "group", 0.9f)
                }
                INNER_MONOLOGUE_PATTERN.containsMatchIn(text) -> {
                    DialogueAnnotation(segment.spanId, "inner_monologue", 0.9f)
                }
                DIALOGUE_PATTERN.containsMatchIn(text) -> {
                    val speaker = findSpeaker(segments, idx)
                    if (speaker != null) {
                        DialogueAnnotation(segment.spanId, "character:$speaker", 0.7f)
                    } else {
                        DialogueAnnotation(segment.spanId, "unknown_dialogue", 0.5f)
                    }
                }
                else -> DialogueAnnotation(segment.spanId, "narrator", 0.9f)
            }
            tag
        }
    }

    private fun findSpeaker(segments: List<ProcessedSegment>, currentIdx: Int): String? {
        val current = segments[currentIdx].ttsText

        // PRE pattern: search in current segment first
        PRE_SPEAKER_PATTERN.find(current)?.let { return it.groupValues[1] }

        // PRE pattern: search in preceding segment
        if (currentIdx > 0) {
            PRE_SPEAKER_PATTERN.find(segments[currentIdx - 1].ttsText)?.let { return it.groupValues[1] }
        }

        // POST pattern: search in current segment
        POST_SPEAKER_PATTERN.find(current)?.let { return it.groupValues[1] }

        // POST pattern: search in following segment
        if (currentIdx < segments.size - 1) {
            POST_SPEAKER_PATTERN.find(segments[currentIdx + 1].ttsText)?.let { return it.groupValues[1] }
        }

        return null
    }

    companion object {
        private val TRIPLE_BRACKET_PATTERN = Regex("""「「「.*?」」」""")
        private val INNER_MONOLOGUE_PATTERN = Regex("""（[^）]+）""")
        private val DIALOGUE_PATTERN = Regex("""[「『].*?[」』]""")

        // Speaker before opening bracket: "太郎は「" or "花子が「"
        // Require at least 2 chars so single stray characters aren't treated as speaker names.
        private val PRE_SPEAKER_PATTERN = Regex("""([^\s「」『』、。！？]{2,10})[はがもの](?=[「『])""")

        // Speaker after closing bracket: "」と花子が言った"
        private val POST_SPEAKER_PATTERN = Regex("""[」』]と([^\s「」『』、。！？]{2,10})[はがも]""")
    }
}
