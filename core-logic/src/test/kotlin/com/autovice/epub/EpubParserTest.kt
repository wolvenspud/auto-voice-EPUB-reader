package com.autovice.epub

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createEpub(
        title: String = "Test Book",
        author: String? = "Test Author",
        language: String? = "ja",
        chapters: List<Pair<String?, String>> = listOf(Pair("Chapter 1", "<html><body><p>Hello.</p></body></html>")),
        hasCover: Boolean = false,
    ): java.io.File {
        val buf = ByteArrayOutputStream()
        val zos = ZipOutputStream(buf)

        fun writeStored(name: String, bytes: ByteArray) {
            val entry = ZipEntry(name).also { it.method = ZipEntry.STORED }
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            val crc = CRC32(); crc.update(bytes); entry.crc = crc.value
            zos.putNextEntry(entry); zos.write(bytes); zos.closeEntry()
        }

        fun writeDeflated(name: String, content: String) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        writeStored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
        writeDeflated("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")

        val manifestItems = StringBuilder()
        if (hasCover) {
            manifestItems.append("""<item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>""")
        }
        val ncxEntry = """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        manifestItems.append(ncxEntry)
        chapters.forEachIndexed { idx, _ ->
            manifestItems.append("""<item id="ch$idx" href="chapter$idx.html" media-type="application/xhtml+xml"/>""")
        }

        val spineItems = chapters.indices.joinToString("") { """<itemref idref="ch$it"/>""" }

        val authorXml = if (author != null) "<dc:creator>$author</dc:creator>" else ""
        val langXml = if (language != null) "<dc:language>$language</dc:language>" else ""

        writeDeflated("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$title</dc:title>
    $authorXml
    $langXml
    <dc:identifier id="uid">test-id</dc:identifier>
  </metadata>
  <manifest>$manifestItems</manifest>
  <spine toc="ncx">$spineItems</spine>
</package>""")

        val ncxNavPoints = chapters.mapIndexed { idx, (chTitle, _) ->
            """<navPoint id="np$idx" playOrder="${idx + 1}"><navLabel><text>${chTitle ?: ""}</text></navLabel><content src="chapter$idx.html"/></navPoint>"""
        }.joinToString("")

        writeDeflated("OEBPS/toc.ncx", """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="test-id"/></head>
  <docTitle><text>$title</text></docTitle>
  $ncxNavPoints
</ncx>""")

        chapters.forEachIndexed { idx, (chTitle, html) ->
            writeDeflated("OEBPS/chapter$idx.html", "<html><head><title>${chTitle ?: ""}</title></head><body>$html</body></html>")
        }

        if (hasCover) {
            zos.putNextEntry(ZipEntry("OEBPS/cover.png"))
            zos.write(ByteArray(8) { 0 })
            zos.closeEntry()
        }

        zos.close()
        val file = tempFolder.newFile("test.epub")
        file.writeBytes(buf.toByteArray())
        return file
    }

    @Test
    fun `parse returns correct title and author`() {
        val epub = createEpub(title = "My Novel", author = "Jane Doe")
        val outputDir = tempFolder.newFolder("out1")
        val result = EpubParser().parse(epub, outputDir)
        assertEquals("My Novel", result.title)
        assertEquals("Jane Doe", result.author)
    }

    @Test
    fun `parse returns correct language`() {
        val epub = createEpub(language = "ja")
        val outputDir = tempFolder.newFolder("out2")
        val result = EpubParser().parse(epub, outputDir)
        assertEquals("ja", result.language)
    }

    @Test
    fun `parse returns correct number of chapters`() {
        val epub = createEpub(chapters = listOf(
            Pair("Ch1", "<p>Text 1.</p>"),
            Pair("Ch2", "<p>Text 2.</p>"),
            Pair("Ch3", "<p>Text 3.</p>"),
        ))
        val outputDir = tempFolder.newFolder("out3")
        val result = EpubParser().parse(epub, outputDir)
        assertEquals(3, result.chapters.size)
    }

    @Test
    fun `chapter HTML files exist after extraction`() {
        val epub = createEpub(chapters = listOf(
            Pair("Chapter One", "<p>Content here.</p>"),
        ))
        val outputDir = tempFolder.newFolder("out4")
        val result = EpubParser().parse(epub, outputDir)
        assertTrue("Chapter HTML should exist", result.chapters[0].htmlFile.exists())
    }

    @Test
    fun `handles missing author`() {
        val epub = createEpub(author = null, language = "en")
        val outputDir = tempFolder.newFolder("out5")
        val result = EpubParser().parse(epub, outputDir)
        assertNull(result.author)
    }

    @Test
    fun `handles missing language`() {
        val epub = createEpub(language = null)
        val outputDir = tempFolder.newFolder("out6")
        val result = EpubParser().parse(epub, outputDir)
        assertNull(result.language)
    }

    @Test
    fun `chapter titles are extracted from NCX`() {
        val epub = createEpub(chapters = listOf(
            Pair("First Chapter", "<p>A.</p>"),
            Pair("Second Chapter", "<p>B.</p>"),
        ))
        val outputDir = tempFolder.newFolder("out7")
        val result = EpubParser().parse(epub, outputDir)
        assertEquals("First Chapter", result.chapters[0].title)
        assertEquals("Second Chapter", result.chapters[1].title)
    }
}
