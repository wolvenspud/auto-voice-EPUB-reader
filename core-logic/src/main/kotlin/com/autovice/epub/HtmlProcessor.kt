package com.autovice.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.text.BreakIterator
import java.util.Locale

data class ProcessedSegment(
    val spanId: String,
    val ttsText: String,
    val charCount: Int,
)

data class ProcessingResult(
    val html: String,
    val segments: List<ProcessedSegment>,
)

class HtmlProcessor {

    fun process(
        htmlContent: String,
        baseUrl: String,
        bookId: String,
        chapterIndex: Int,
        /** Maps original spine filenames (e.g. "chapter_0009.xhtml") to processed chapter indices. */
        chapterFileMap: Map<String, Int> = emptyMap(),
    ): ProcessingResult {
        val doc = Jsoup.parse(htmlContent)

        doc.select("script").remove()

        rewriteInternalLinks(doc, chapterFileMap)

        val segments = mutableListOf<ProcessedSegment>()
        var segmentIndex = 0

        val blockElements = doc.body()?.select("p, div, section, article, h1, h2, h3, h4, h5, h6, blockquote, li, td, th")
            ?: return ProcessingResult(doc.outerHtml(), emptyList())

        val processedNodes = mutableSetOf<org.jsoup.nodes.Node>()

        for (block in blockElements) {
            if (hasBlockAncestorAlreadyProcessed(block, blockElements)) continue

            val textNodes = collectDirectTextNodes(block)
            for (textNode in textNodes) {
                if (processedNodes.contains(textNode)) continue
                processedNodes.add(textNode)

                val rawText = textNode.text()
                if (rawText.isBlank()) continue
                if (isPurelyDecorative(rawText)) continue

                val sentences = splitSentences(rawText)
                if (sentences.isEmpty()) continue

                val replacementElements = mutableListOf<org.jsoup.nodes.Node>()
                for (sentence in sentences) {
                    if (sentence.trim().length < 2 || isPurelyDecorative(sentence)) {
                        replacementElements.add(TextNode(sentence))
                        continue
                    }
                    val htmlSpanId = "c${chapterIndex}s${segmentIndex}"
                    val dbSpanId = buildDbSpanId(bookId, chapterIndex, segmentIndex)
                    val span = Element("span")
                    span.attr("id", htmlSpanId)
                    span.attr("class", "tts-seg")
                    span.text(sentence)

                    replacementElements.add(span)
                    segments.add(ProcessedSegment(
                        spanId = dbSpanId,
                        ttsText = sentence.trim(),
                        charCount = sentence.trim().length,
                    ))
                    segmentIndex++
                }

                if (replacementElements.isNotEmpty()) {
                    var insertAfter: org.jsoup.nodes.Node = textNode
                    for (elem in replacementElements) {
                        insertAfter.after(elem)
                        insertAfter = elem
                    }
                    textNode.remove()
                }
            }

            // Handle ruby elements: replace tts text with rt content
            for (rubyEl in block.select("ruby")) {
                val rtText = rubyEl.select("rt").joinToString("") { it.text() }
                if (rtText.isBlank()) continue

                val rubyBase = rubyEl.ownText()
                if (rubyBase.isBlank()) continue

                val sentences = splitSentences(rtText)
                if (sentences.size == 1 && rtText.trim().length >= 2) {
                    val htmlSpanId = "c${chapterIndex}s${segmentIndex}"
                    val dbSpanId = buildDbSpanId(bookId, chapterIndex, segmentIndex)
                    val wrapper = Element("span")
                    wrapper.attr("id", htmlSpanId)
                    wrapper.attr("class", "tts-seg")
                    rubyEl.replaceWith(wrapper)
                    wrapper.appendChild(rubyEl)
                    segments.add(ProcessedSegment(
                        spanId = dbSpanId,
                        ttsText = rtText.trim(),
                        charCount = rtText.trim().length,
                    ))
                    segmentIndex++
                }
            }
        }

        injectHeadContent(doc, baseUrl)

        return ProcessingResult(
            html = doc.outerHtml(),
            segments = segments,
        )
    }

    private fun hasBlockAncestorAlreadyProcessed(
        element: Element,
        blockElements: org.jsoup.select.Elements,
    ): Boolean {
        var parent = element.parent()
        while (parent != null) {
            if (parent in blockElements) return true
            parent = parent.parent()
        }
        return false
    }

    private fun collectDirectTextNodes(element: Element): List<TextNode> {
        val result = mutableListOf<TextNode>()
        for (child in element.childNodes()) {
            if (child is TextNode) {
                result.add(child)
            }
        }
        return result
    }

    private fun splitSentences(text: String): List<String> {
        val locale = if (text.any { it.code in 0x3000..0x9FFF }) Locale.JAPANESE else Locale.getDefault()
        val bi = BreakIterator.getSentenceInstance(locale)
        bi.setText(text)
        val sentences = mutableListOf<String>()
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val s = text.substring(start, end)
            if (s.isNotEmpty()) sentences.add(s)
            start = end
            end = bi.next()
        }
        return sentences
    }

    private fun buildDbSpanId(bookId: String, chapterIndex: Int, segmentIndex: Int): String {
        val bookIdShort = bookId.replace("-", "").take(8)
        return "${bookIdShort}_c${chapterIndex}_s${segmentIndex}"
    }

    /**
     * Rewrites internal chapter links (to original spine files) so they point at the processed
     * `ch_{index}.html` files. The reader's WebView intercepts these and routes navigation through
     * the ViewModel, which loads the processed (TTS-tagged, theme-aware) chapter — without this,
     * links load the raw original xhtml: no playback spans and no dark-mode CSS.
     */
    private fun rewriteInternalLinks(doc: org.jsoup.nodes.Document, chapterFileMap: Map<String, Int>) {
        if (chapterFileMap.isEmpty()) return
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) continue
            val anchor = href.substringAfter('#', "")
            val fileName = href.substringBefore('#').substringAfterLast('/')
            val index = chapterFileMap[fileName] ?: continue
            a.attr("href", "ch_$index.html" + if (anchor.isNotEmpty()) "#$anchor" else "")
        }
    }

    /** Returns true for strings that contain no letters or digits — e.g. ◆◆◆, ─────, ＊＊＊. */
    private fun isPurelyDecorative(text: String): Boolean {
        val stripped = text.trim()
        return stripped.isNotEmpty() && stripped.none { c -> c.isLetterOrDigit() }
    }

    private fun injectHeadContent(doc: org.jsoup.nodes.Document, baseUrl: String) {
        val head = doc.head()

        head.prepend("""<base href="$baseUrl">""")
        head.prepend("""<meta name="viewport" content="width=device-width, initial-scale=1">""")

        head.append("""
<style id="av-vars">
:root {
  --font-size: 16px;
  --line-height: 1.6;
  --bg-color: #ffffff;
  --text-color: #1a1a1a;
}
body {
  font-size: var(--font-size);
  line-height: var(--line-height);
  background-color: var(--bg-color);
  color: var(--text-color);
  padding: 16px;
  max-width: 100%;
  word-wrap: break-word;
}
.tts-active {
  background-color: rgba(255, 220, 0, 0.45);
  border-radius: 2px;
}
</style>
""".trimIndent())

        head.append("""
<script id="av-bridge">
function highlightSegment(id) {
  var prev = document.querySelector('.tts-active');
  if (prev) prev.classList.remove('tts-active');
  var el = document.getElementById(id);
  if (el) { el.classList.add('tts-active'); el.scrollIntoView({behavior:'smooth',block:'center'}); }
}
function clearHighlight() {
  var el = document.querySelector('.tts-active');
  if (el) el.classList.remove('tts-active');
}
var _pScroll = false;
function _markProgScroll() { _pScroll = true; setTimeout(function(){ _pScroll=false; }, 700); }
document.addEventListener('scroll', function() {
  if (!_pScroll && window.AndroidBridge) window.AndroidBridge.onUserScroll();
}, {passive:true});
document.addEventListener('click', function(e) {
  var seg = e.target.closest('.tts-seg');
  if (!window.AndroidBridge) return;
  if (seg) window.AndroidBridge.onSegmentTap(seg.id);
  else window.AndroidBridge.onBackgroundTap();
});
</script>
""".trimIndent())
    }
}
