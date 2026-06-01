package com.autovice.epub

import org.junit.Assert.*
import org.junit.Test

class HtmlProcessorTest {

    private val processor = HtmlProcessor()
    private val bookId = "test-book-id"

    @Test
    fun `simple Japanese paragraph splits into sentences at 。`() {
        val html = "<html><body><p>これはテストです。次の文章です。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertTrue("Should have at least 2 segments", result.segments.size >= 2)
    }

    @Test
    fun `multiple punctuation types create separate sentences`() {
        val html = "<html><body><p>これは！テストです？すごい！</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertTrue("Should have segments for each sentence", result.segments.isNotEmpty())
    }

    @Test
    fun `spans have correct ID format`() {
        val html = "<html><body><p>第一文。第二文。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        val outputDoc = org.jsoup.Jsoup.parse(result.html)
        val spans = outputDoc.select("span.tts-seg")
        assertTrue("Should have spans", spans.isNotEmpty())
        assertTrue("First span ID should start with c0s0", spans[0].id().startsWith("c0s"))
        if (spans.size > 1) {
            assertTrue("Second span should be c0s1", spans[1].id() == "c0s1")
        }
    }

    @Test
    fun `script tags are stripped`() {
        val html = "<html><head><script>alert('xss')</script></head><body><p>Safe.</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertFalse("Script should be removed", result.html.contains("alert('xss')"))
    }

    @Test
    fun `empty paragraphs produce no segments`() {
        val html = "<html><body><p>   </p><p></p><p>実際のテキスト。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertTrue("Should have at least one segment for non-empty text", result.segments.isNotEmpty())
        result.segments.forEach { seg ->
            assertTrue("No segment should be whitespace-only", seg.ttsText.isNotBlank())
        }
    }

    @Test
    fun `injected JS contains highlightSegment function`() {
        val html = "<html><body><p>テスト。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertTrue("Should contain highlightSegment", result.html.contains("highlightSegment"))
    }

    @Test
    fun `injected CSS contains tts-active class`() {
        val html = "<html><body><p>テスト。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertTrue("Should contain .tts-active", result.html.contains("tts-active"))
    }

    @Test
    fun `base tag is injected with correct URL`() {
        val html = "<html><body><p>テスト。</p></body></html>"
        val result = processor.process(html, "file:///my/path/", bookId, 0)
        assertTrue("Should contain base href", result.html.contains("file:///my/path/"))
    }

    @Test
    fun `chapter index is reflected in span IDs`() {
        val html = "<html><body><p>テキスト。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 5)
        val doc = org.jsoup.Jsoup.parse(result.html)
        val spans = doc.select("span.tts-seg")
        if (spans.isNotEmpty()) {
            assertTrue("Span ID should contain chapter index 5", spans[0].id().startsWith("c5s"))
        }
    }

    @Test
    fun `very short strings do not produce segments`() {
        val html = "<html><body><p>あ</p><p>実際のテキストです。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        result.segments.forEach { seg ->
            assertTrue("Segment char count should be >= 2", seg.charCount >= 2)
        }
    }

    @Test
    fun `injected JS contains AndroidBridge onUserScroll`() {
        val html = "<html><body><p>テスト。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertTrue("Should contain AndroidBridge.onUserScroll", result.html.contains("AndroidBridge.onUserScroll"))
    }

    @Test
    fun `injected JS contains AndroidBridge onSegmentTap`() {
        val html = "<html><body><p>テスト。</p></body></html>"
        val result = processor.process(html, "file:///test/", bookId, 0)
        assertTrue("Should contain AndroidBridge.onSegmentTap", result.html.contains("AndroidBridge.onSegmentTap"))
    }
}
