package com.autovice.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

data class ScrapedNovel(
    val title: String,
    val author: String,
    val description: String,
    val chapters: List<ScrapedChapter>,
)

data class ScrapedChapter(
    val index: Int,
    val title: String,
    val content: String,
)

class SyosetuScraper(private val httpClient: OkHttpClient = OkHttpClient()) {

    private val userAgent = "AutoVoice-Reader/1.0 (personal use; +https://github.com/wolvenspud/auto-voice-epub-reader)"

    fun scrapeNovel(ncodeUrl: String): ScrapedNovel {
        val chapterList = scrapeChapterList(ncodeUrl)
        val chapters = chapterList.map { (chapterNo, _) ->
            Thread.sleep(2000)
            scrapeChapter(ncodeUrl, chapterNo)
        }
        val indexHtml = fetchHtml(normalizeUrl(ncodeUrl))
        val indexDoc = Jsoup.parse(indexHtml)
        val title = indexDoc.selectFirst(".novel_title")?.text() ?: ""
        val author = indexDoc.selectFirst(".novel_writername")?.text()?.trim() ?: ""
        val description = indexDoc.selectFirst("#novel_ex")?.text() ?: ""
        return ScrapedNovel(title, author, description, chapters)
    }

    fun scrapeChapterList(ncodeUrl: String): List<Pair<Int, String>> {
        val url = normalizeUrl(ncodeUrl)
        val html = fetchHtml(url)
        val doc = Jsoup.parse(html)
        val result = mutableListOf<Pair<Int, String>>()
        doc.select(".index_box .chapter_title, .index_box dl.novel_sublist2 dt.subtitle a").forEach { a ->
            val href = a.attr("href")
            val chapterNoMatch = Regex("""/${extractNcode(ncodeUrl)}/(\d+)/""").find(href)
            val chapterNo = chapterNoMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
            result.add(Pair(chapterNo, a.text()))
        }
        // Alternative selector if above yields nothing
        if (result.isEmpty()) {
            doc.select("a[href*='/${extractNcode(ncodeUrl)}/']").forEach { a ->
                val href = a.attr("href")
                val chapterNoMatch = Regex("""/${extractNcode(ncodeUrl)}/(\d+)/""").find(href)
                val chapterNo = chapterNoMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
                if (chapterNo > 0) {
                    result.add(Pair(chapterNo, a.text()))
                }
            }
        }
        return result.distinctBy { it.first }.sortedBy { it.first }
    }

    fun scrapeChapter(ncodeUrl: String, chapterNo: Int): ScrapedChapter {
        val ncode = extractNcode(ncodeUrl)
        val url = "https://ncode.syosetu.com/$ncode/$chapterNo/"
        val html = fetchHtml(url)
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("#novel_subtitle")?.text() ?: "Chapter $chapterNo"
        val content = doc.selectFirst("#novel_honbun")?.text() ?: ""
        return ScrapedChapter(chapterNo, title, content)
    }

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string() ?: error("Empty response body for $url")
        }
    }

    private fun normalizeUrl(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }

    private fun extractNcode(url: String): String {
        val match = Regex("""ncode\.syosetu\.com/(n[0-9a-z]+)""").find(url)
        return match?.groupValues?.getOrNull(1) ?: error("Invalid Syosetu URL: $url")
    }
}
