package com.autovice.dialogue

import com.autovice.epub.ProcessedSegment
import org.junit.Assert.*
import org.junit.Test

class DialogueAnalyserTest {

    private val analyser = DialogueAnalyser()
    private val bookId = "testbook"

    private fun seg(text: String, idx: Int = 0) = ProcessedSegment(
        spanId = "testbook_c0_s$idx",
        ttsText = text,
        charCount = text.length,
    )

    private fun analyse(vararg texts: String): List<DialogueAnnotation> {
        val segments = texts.mapIndexed { i, t -> seg(t, i) }
        return analyser.analyse(segments, 0, bookId)
    }

    @Test
    fun `dialogue without attribution is tagged as unknown_dialogue`() {
        val result = analyse("「こんにちは」と言った。")
        val annotation = result.first()
        assertEquals("unknown_dialogue", annotation.speakerTag)
    }

    @Test
    fun `pre-speaker attribution tags correct character`() {
        val result = analyse("太郎は「よろしく」と言った。")
        val annotation = result.first()
        assertEquals("character:太郎", annotation.speakerTag)
    }

    @Test
    fun `post-speaker attribution tags correct character`() {
        val result = analyse("「ありがとう」と花子が言った。")
        val annotation = result.first()
        assertEquals("character:花子", annotation.speakerTag)
    }

    @Test
    fun `inner monologue is tagged correctly`() {
        val result = analyse("（これは夢なのか）")
        assertEquals("inner_monologue", result.first().speakerTag)
    }

    @Test
    fun `triple bracket is tagged as group`() {
        val result = analyse("「「「みんなで行こう！」」」")
        assertEquals("group", result.first().speakerTag)
    }

    @Test
    fun `plain narration is tagged as narrator`() {
        val result = analyse("空は青かった。")
        assertEquals("narrator", result.first().speakerTag)
    }

    @Test
    fun `multiple segments get correct tags`() {
        val result = analyse(
            "空が晴れていた。",
            "太郎は「行くぞ」と叫んだ。",
            "（嬉しいな）と思った。",
        )
        assertEquals("narrator", result[0].speakerTag)
        assertEquals("character:太郎", result[1].speakerTag)
        assertEquals("inner_monologue", result[2].speakerTag)
    }

    @Test
    fun `annotation has correct spanId`() {
        val result = analyse("テスト。")
        assertEquals("testbook_c0_s0", result.first().spanId)
    }

    @Test
    fun `narrator confidence is high`() {
        val result = analyse("普通の文章です。")
        assertTrue(result.first().confidence >= 0.8f)
    }

    @Test
    fun `unknown dialogue confidence is lower than narrator`() {
        val narrator = analyse("普通の文章です。").first()
        val dialogue = analyse("「何かが来た」").first()
        assertTrue(dialogue.confidence < narrator.confidence)
    }
}
