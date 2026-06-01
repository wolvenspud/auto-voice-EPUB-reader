package com.autovice.synthesis

data class SynthesisPosition(val chapterIndex: Int, val segmentIndex: Int)

class SynthesisScheduler(
    private val windowChars: Int = 80_000,
    private val evictionTrailChars: Int = 10_000,
) {

    fun shouldSynthesiseNext(synthesisedCharsAhead: Int): Boolean {
        return synthesisedCharsAhead < windowChars
    }

    fun shouldEvict(charsBehindCurrent: Int): Boolean {
        return charsBehindCurrent > evictionTrailChars
    }

    fun countSynthesisedCharsAhead(
        allSegments: List<Pair<Boolean, Int>>,
        currentIndex: Int,
    ): Int {
        if (currentIndex >= allSegments.size) return 0
        var total = 0
        for (i in currentIndex until allSegments.size) {
            val (isSynthesised, charCount) = allSegments[i]
            if (!isSynthesised) break
            total += charCount
        }
        return total
    }
}
