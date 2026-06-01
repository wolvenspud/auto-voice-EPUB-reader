package com.autovice.synthesis

import org.junit.Assert.*
import org.junit.Test

class SynthesisSchedulerTest {

    private val scheduler = SynthesisScheduler(windowChars = 1000, evictionTrailChars = 200)

    @Test
    fun `shouldSynthesiseNext true when below window`() {
        assertTrue(scheduler.shouldSynthesiseNext(500))
    }

    @Test
    fun `shouldSynthesiseNext false when at window`() {
        assertFalse(scheduler.shouldSynthesiseNext(1000))
    }

    @Test
    fun `shouldSynthesiseNext false when above window`() {
        assertFalse(scheduler.shouldSynthesiseNext(1200))
    }

    @Test
    fun `shouldEvict true when beyond trail`() {
        assertTrue(scheduler.shouldEvict(300))
    }

    @Test
    fun `shouldEvict false when within trail`() {
        assertFalse(scheduler.shouldEvict(100))
    }

    @Test
    fun `shouldEvict false when equal to trail`() {
        assertFalse(scheduler.shouldEvict(200))
    }

    @Test
    fun `countSynthesisedCharsAhead sums consecutive synthesised segments`() {
        val segments = listOf(
            Pair(true, 100),
            Pair(true, 150),
            Pair(false, 200),
            Pair(true, 50),
        )
        val count = scheduler.countSynthesisedCharsAhead(segments, 0)
        assertEquals(250, count)
    }

    @Test
    fun `countSynthesisedCharsAhead starts from currentIndex`() {
        val segments = listOf(
            Pair(true, 100),
            Pair(true, 150),
            Pair(true, 200),
        )
        val count = scheduler.countSynthesisedCharsAhead(segments, 1)
        assertEquals(350, count)
    }

    @Test
    fun `countSynthesisedCharsAhead returns 0 when current not synthesised`() {
        val segments = listOf(
            Pair(false, 100),
            Pair(true, 150),
        )
        val count = scheduler.countSynthesisedCharsAhead(segments, 0)
        assertEquals(0, count)
    }

    @Test
    fun `countSynthesisedCharsAhead returns 0 when index out of bounds`() {
        val segments = listOf(Pair(true, 100))
        val count = scheduler.countSynthesisedCharsAhead(segments, 5)
        assertEquals(0, count)
    }

    @Test
    fun `default scheduler uses 80k window`() {
        val defaultScheduler = SynthesisScheduler()
        assertTrue(defaultScheduler.shouldSynthesiseNext(79_999))
        assertFalse(defaultScheduler.shouldSynthesiseNext(80_000))
    }
}
