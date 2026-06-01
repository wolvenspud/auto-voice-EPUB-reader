package com.autovice.scraper

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBuilder {

    fun build(novel: ScrapedNovel, outputFile: File) {
        ZipOutputStream(outputFile.outputStream().buffered()).use { zos ->
            // mimetype MUST be first, STORED (uncompressed)
            val mimetypeEntry = ZipEntry("mimetype").also { it.method = ZipEntry.STORED }
            val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            mimetypeEntry.size = mimetypeBytes.size.toLong()
            mimetypeEntry.compressedSize = mimetypeBytes.size.toLong()
            mimetypeEntry.crc = computeCrc(mimetypeBytes)
            zos.putNextEntry(mimetypeEntry)
            zos.write(mimetypeBytes)
            zos.closeEntry()

            writeEntry(zos, "META-INF/container.xml", buildContainerXml())
            writeEntry(zos, "OEBPS/content.opf", buildOpf(novel))
            writeEntry(zos, "OEBPS/toc.ncx", buildNcx(novel))

            novel.chapters.forEachIndexed { idx, chapter ->
                writeEntry(zos, "OEBPS/chapter_${idx + 1}.html", buildChapterHtml(chapter))
            }
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildContainerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun buildOpf(novel: ScrapedNovel): String {
        val manifestItems = novel.chapters.mapIndexed { idx, _ ->
            """    <item id="chapter_${idx + 1}" href="chapter_${idx + 1}.html" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n")

        val spineItems = novel.chapters.mapIndexed { idx, _ ->
            """    <itemref idref="chapter_${idx + 1}"/>"""
        }.joinToString("\n")

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookId" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeXml(novel.title)}</dc:title>
    <dc:creator>${escapeXml(novel.author)}</dc:creator>
    <dc:language>ja</dc:language>
    <dc:identifier id="bookId">syosetu-${System.currentTimeMillis()}</dc:identifier>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
$manifestItems
  </manifest>
  <spine toc="ncx">
$spineItems
  </spine>
</package>"""
    }

    private fun buildNcx(novel: ScrapedNovel): String {
        val navPoints = novel.chapters.mapIndexed { idx, chapter ->
            """  <navPoint id="navPoint-${idx + 1}" playOrder="${idx + 1}">
    <navLabel><text>${escapeXml(chapter.title)}</text></navLabel>
    <content src="chapter_${idx + 1}.html"/>
  </navPoint>"""
        }.joinToString("\n")

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="syosetu"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escapeXml(novel.title)}</text></docTitle>
$navPoints
</ncx>"""
    }

    private fun buildChapterHtml(chapter: ScrapedChapter): String {
        val paragraphs = chapter.content
            .split("\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${escapeXml(it)}</p>" }

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta charset="UTF-8"/>
  <title>${escapeXml(chapter.title)}</title>
</head>
<body>
  <h1>${escapeXml(chapter.title)}</h1>
$paragraphs
</body>
</html>"""
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun computeCrc(data: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value
    }
}
