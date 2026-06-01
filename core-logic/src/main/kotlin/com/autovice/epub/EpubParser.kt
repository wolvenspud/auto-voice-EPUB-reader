package com.autovice.epub

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class EpubParser {

    fun parse(epubFile: File, outputDir: File): ParsedEpub {
        val contentDir = File(outputDir, "content").also { it.mkdirs() }
        extractZip(epubFile, contentDir)

        val opfPath = findOpfPath(contentDir)
        val opfFile = File(contentDir, opfPath)
        val opfDir = opfFile.parentFile ?: contentDir

        val opfDoc = Jsoup.parse(opfFile.readText(), "", Parser.xmlParser())

        val title = opfDoc.selectFirst("dc|title, dc\\:title")?.text() ?: epubFile.nameWithoutExtension
        val author = opfDoc.selectFirst("dc|creator, dc\\:creator")?.text()
        val language = opfDoc.selectFirst("dc|language, dc\\:language")?.text()

        val manifest = buildManifest(opfDoc, opfDir)
        val coverImagePath = findCoverPath(opfDoc, manifest)
        val spineIds = buildSpineIds(opfDoc)
        val spineFiles = spineIds.mapNotNull { manifest[it] }

        val chapterTitles = extractChapterTitles(opfDoc, manifest, contentDir, opfDir, spineFiles)

        val chapters = spineFiles.mapIndexed { index, htmlFile ->
            ParsedChapter(
                index = index,
                title = chapterTitles.getOrNull(index),
                htmlFile = htmlFile,
                contentDir = htmlFile.parentFile ?: opfDir,
            )
        }

        return ParsedEpub(
            title = title,
            author = author,
            language = language,
            coverImagePath = coverImagePath,
            chapters = chapters,
            extractedDir = contentDir,
        )
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun findOpfPath(contentDir: File): String {
        val containerXml = File(contentDir, "META-INF/container.xml")
        val doc = Jsoup.parse(containerXml.readText(), "", Parser.xmlParser())
        return doc.selectFirst("rootfile")?.attr("full-path")
            ?: error("Could not find OPF path in container.xml")
    }

    private fun buildManifest(opfDoc: org.jsoup.nodes.Document, opfDir: File): Map<String, File> {
        val result = mutableMapOf<String, File>()
        opfDoc.select("manifest > item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                result[id] = File(opfDir, href).canonicalFile
            }
        }
        return result
    }

    private fun buildSpineIds(opfDoc: org.jsoup.nodes.Document): List<String> {
        return opfDoc.select("spine > itemref").map { it.attr("idref") }
    }

    private fun findCoverPath(opfDoc: org.jsoup.nodes.Document, manifest: Map<String, File>): String? {
        // EPUB 3: item with properties="cover-image"
        val epub3Cover = opfDoc.select("manifest > item[properties~=cover-image]").firstOrNull()
        if (epub3Cover != null) {
            return manifest[epub3Cover.attr("id")]?.absolutePath
        }
        // EPUB 2: meta name="cover" content="{id}"
        val metaCover = opfDoc.selectFirst("meta[name=cover]")
        if (metaCover != null) {
            val coverId = metaCover.attr("content")
            return manifest[coverId]?.absolutePath
        }
        return null
    }

    private fun extractChapterTitles(
        opfDoc: org.jsoup.nodes.Document,
        manifest: Map<String, File>,
        contentDir: File,
        opfDir: File,
        spineFiles: List<File>,
    ): List<String?> {
        // Try EPUB 3 nav document first
        val navItem = opfDoc.select("manifest > item[properties~=nav]").firstOrNull()
        if (navItem != null) {
            val navFile = manifest[navItem.attr("id")]
            if (navFile != null && navFile.exists()) {
                val titles = parseNavTitles(navFile, spineFiles, opfDir)
                if (titles.isNotEmpty()) return titles
            }
        }

        // Try EPUB 2 NCX
        val ncxId = opfDoc.selectFirst("spine")?.attr("toc")
        val ncxFile = if (ncxId != null) manifest[ncxId] else {
            manifest.values.firstOrNull { it.name.endsWith(".ncx") }
        }
        if (ncxFile != null && ncxFile.exists()) {
            val titles = parseNcxTitles(ncxFile, spineFiles, opfDir)
            if (titles.isNotEmpty()) return titles
        }

        // Fall back to <title> from each HTML file
        return spineFiles.map { htmlFile ->
            if (htmlFile.exists()) {
                val encoding = detectEncoding(htmlFile)
                val doc = Jsoup.parse(htmlFile.readText(Charsets.UTF_8))
                doc.selectFirst("title")?.text()?.takeIf { it.isNotBlank() }
            } else null
        }
    }

    private fun parseNavTitles(navFile: File, spineFiles: List<File>, opfDir: File): List<String?> {
        val doc = Jsoup.parse(navFile.readText(), "", Parser.xmlParser())
        val navMap = mutableMapOf<String, String>()
        doc.select("nav[*|type=toc] a, nav[epub\\:type=toc] a").forEach { a ->
            val href = a.attr("href").substringBefore("#")
            val resolved = File(navFile.parentFile ?: opfDir, href).canonicalFile
            navMap[resolved.absolutePath] = a.text()
        }
        return spineFiles.map { navMap[it.absolutePath] }
    }

    private fun parseNcxTitles(ncxFile: File, spineFiles: List<File>, opfDir: File): List<String?> {
        val doc = Jsoup.parse(ncxFile.readText(), "", Parser.xmlParser())
        val navMap = mutableMapOf<String, String>()
        doc.select("navPoint").forEach { navPoint ->
            val src = navPoint.selectFirst("content")?.attr("src")?.substringBefore("#") ?: return@forEach
            val label = navPoint.selectFirst("navLabel > text")?.text() ?: return@forEach
            val resolved = File(ncxFile.parentFile ?: opfDir, src).canonicalFile
            navMap[resolved.absolutePath] = label
        }
        return spineFiles.map { navMap[it.absolutePath] }
    }

    private fun detectEncoding(file: File): String {
        val bytes = file.readBytes().take(1024).toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        val charsetMatch = Regex("""charset=["']?([^"'\s;>]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.lowercase()
        return when {
            charsetMatch?.contains("shift") == true || charsetMatch?.contains("sjis") == true -> "Shift_JIS"
            charsetMatch?.contains("euc-jp") == true -> "EUC-JP"
            else -> "UTF-8"
        }
    }
}
