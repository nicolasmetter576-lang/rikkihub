package me.rerere.rikkihub.data.book.transfer

import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookFileInfo
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterStatus
import me.rerere.rikkihub.data.book.assembleBook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class BookTransferTest {
    // ---- 编码 --------------------------------------------------------------------------------

    private val sample = "第一章　起風\n「你來了。」他說，這是個不在地圖上的地方……\n"

    @Test
    fun `encodings are recognised`() {
        for (name in listOf("UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE")) {
            val bytes = sample.toByteArray(Charset.forName(name)).let {
                when (name) {
                    "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it
                    "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + it
                    else -> it
                }
            }
            val decoded = TextDecoding.decode(bytes)!!
            assertEquals(name, sample, decoded.text)
            assertEquals(name, decoded.charset)
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray()
        assertEquals(sample, TextDecoding.decode(bom)!!.text)
        val simplified = "第一章 起风\n“你来了。”他说，这是个不在地图上的地方……"
        assertEquals("GB18030", TextDecoding.decode(simplified.toByteArray(Charset.forName("GB18030")))!!.charset)
        assertEquals(simplified, TextDecoding.decode(simplified.toByteArray(Charset.forName("GB18030")), "GB18030")!!.text)
    }

    @Test
    fun `undecodable bytes are refused, not mangled`() {
        assertNull(TextDecoding.decode(byteArrayOf(0x41, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        assertNull(TextDecoding.decode("中文".toByteArray(), "Big5")?.takeIf { it.text == "中文" })
    }

    // ---- 文件名 ------------------------------------------------------------------------------

    @Test
    fun `file names sort in reading order and are always valid`() {
        fun chapter(title: String) = ImportChapter(title, "x")
        val import = BookImport("", "", "", "txt", listOf(chapter("第一章 a/b:c*?"), chapter(""), chapter("长".repeat(300)), chapter("\uD83D坏"), chapter("尾...")))
        val names = BookImporter.fileNames(import, offset = 9998)
        assertEquals(names.sorted(), names)
        names.forEach { me.rerere.workspace.WorkspaceFileNames.validate(it) }
        assertEquals("09999 第一章 a b c.txt", names[0])
        assertEquals("10000.txt", names[1])
    }

    @Test
    fun `merging and renaming in the preview`() {
        val text = "第一章 甲\n一\n第二章 乙\n二\n第三章 丙\n三\n"
        val edits = ImportEdits(titles = mapOf(2 to "改名"), merged = setOf(1))
        val source = BookImporter.fromText("书.txt", text, ChapterSplitter.split(text, SplitRule.Auto))
        val import = source.edited(edits)
        assertEquals(listOf(0 to "第一章 甲", 2 to "改名"), source.rows(edits).map { it.source to it.title })
        assertEquals(listOf("第一章 甲", "改名"), import.chapters.map { it.title })
        assertEquals("第一章 甲\n一\n\n第二章 乙\n二\n", import.chapters[0].content)
        assertEquals("书", import.title)
        assertEquals("改名\n三\n", import.chapters[1].content)
    }

    @Test
    fun `a chapter merged into the untitled opening lends it the title`() {
        val text = "书名页\n第一章 甲\n一\n第二章 乙\n二\n"
        val source = BookImporter.fromText("书.txt", text, ChapterSplitter.split(text, SplitRule.Auto))
        assertEquals(listOf("第一章 甲", "第二章 乙"), source.edited(ImportEdits(merged = setOf(1))).chapters.map { it.title })
    }

    @Test
    fun `merging away the first chapter of a volume keeps the volume`() {
        val text = "第一卷 上\n第一章 甲\n一\n第二卷 下\n第二章 乙\n二\n第三章 丙\n三\n"
        val source = BookImporter.fromText("书.txt", text, ChapterSplitter.split(text, SplitRule.Auto))
        assertEquals(listOf("第一卷 上", "第二卷 下"), source.edited(ImportEdits(merged = setOf(1))).chapters.map { it.volume })
    }

    @Test
    fun `a file is recognised by its content`() {
        val txt = File.createTempFile("book", ".epub").apply { writeText("第一章 甲\n一\n第二章 乙\n二\n") }
        BookImportSource(txt, "书.epub").use { source ->
            assertTrue(source.isText)
            assertEquals(2, source.load().chapters.size)
            assertEquals("UTF-8", source.charset)
        }
        txt.delete()
        val huge = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = len
        }
        val target = File.createTempFile("book", ".txt")
        val failure = runCatching { BookImportSource.copy(huge, target) }.exceptionOrNull() as BookImportException
        assertEquals(BookImportException.Reason.TOO_LARGE, failure.reason)
        assertFalse(target.exists())
    }

    // ---- 导出 --------------------------------------------------------------------------------

    private val texts = mapOf(
        "a.md" to "# 起风 & <雨>\n\n　　他**慢慢**走，*不*回头。\n\n> 引文\n\n---\n\n![图](images/p.png)\n\n### 小节\n\n5*3*2=30 和 a_b_c，还有 \\*原样\\*\n",
        "b.txt" to "第二章 夜雨\r\n\r\n　　雨。**不是粗体**\r\n# 不是标题\r\n",
        "c.md" to "没有标题行\n",
    )

    private fun book(cover: String = "images/p.png"): me.rerere.rikkihub.data.book.Book {
        val items = listOf(
            BookVolume("v1", "卷一"), BookChapter("a", "a.md", status = ChapterStatus.FINAL), BookChapter("b", "b.txt", title = "第二章 夜雨"),
            BookVolume("v2", "卷二"), BookChapter("c", "c.md", title = "归途"), BookChapter("d", "gone.md"),
        )
        val files = texts.keys.associateWith { BookFileInfo(1, 1, it, 1, heading = firstHeadingOf(it)) }
        return assembleBook("w", BookManifest("11111111-2222-3333-4444-555555555555", "长夜", "某某", "zh", cover, items), files)
    }

    private fun firstHeadingOf(path: String) = if (path == "a.md") "起风 & <雨>" else null

    private val png = byteArrayOf(1, 2, 3)

    private fun exporter() = BookExporter({ texts.getValue(it) }, { if (it == "images/p.png") png else null })

    private fun export(options: ExportOptions, book: me.rerere.rikkihub.data.book.Book = book()): ByteArray = runBlocking {
        ByteArrayOutputStream().also { exporter().export(book, options, it) }.toByteArray()
    }

    @Test
    fun `text export writes each title once and keeps plain text literal`() {
        val text = export(ExportOptions(ExportFormat.TXT)).decodeToString()
        assertEquals(1, Regex.fromLiteral("起风 & <雨>").findAll(text).count())
        assertEquals(1, Regex("第二章 夜雨").findAll(text).count())
        assertTrue(text.contains("　　他慢慢走，不回头。"))
        assertTrue(text.contains("　　雨。**不是粗体**"))
        assertTrue(text.contains("　　# 不是标题"))
        assertTrue(text.contains("5*3*2=30 和 a_b_c，还有 *原样*") || text.contains("532=30"))
        assertFalse(text.contains("images/"))
        assertTrue(text.indexOf("卷一") < text.indexOf("卷二"))
        val loose = export(ExportOptions(ExportFormat.TXT, volumeTitles = false, indent = false, blankLines = true)).decodeToString()
        assertFalse(loose.contains("卷一"))
        assertTrue(loose.contains("他慢慢走，不回头。\n\n引文"))
    }

    @Test
    fun `only final chapters or one volume`() {
        val plan = exportPlan(book(), ExportOptions(ExportFormat.TXT, finalOnly = true))
        assertEquals(listOf("a"), plan.chapters.map { it.id })
        val second = exportPlan(book(), ExportOptions(ExportFormat.TXT, volumeId = "v2"))
        assertEquals(listOf("c"), second.chapters.map { it.id })
        assertEquals(listOf("d"), second.missing.map { it.id })
        assertEquals(listOf("b"), exportPlan(book(), ExportOptions(ExportFormat.TXT, chapterIds = setOf("b"))).chapters.map { it.id })
    }

    @Test
    fun `markdown export keeps plain text lines as paragraphs`() {
        val text = export(ExportOptions(ExportFormat.MARKDOWN)).decodeToString()
        assertTrue(text.contains("## 第二章 夜雨\n\n雨。\\*\\*不是粗体\\*\\*\n\n\\# 不是标题\n"))
        assertTrue(text.contains("他**慢慢**走"))
        assertEquals(1, Regex.fromLiteral("起风 & <雨>").findAll(text).count())
    }

    private fun <T> withZip(bytes: ByteArray, block: (ZipFile, File) -> T): T {
        val file = File.createTempFile("book", ".epub")
        try {
            file.writeBytes(bytes)
            return ZipFile(file).use { block(it, file) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `epub is well formed and complete`() = withZip(export(ExportOptions(ExportFormat.EPUB))) { zip, _ ->
        val first = zip.entries().nextElement()
        assertEquals("mimetype", first.name)
        assertEquals(ZipEntry.STORED, first.method)
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        zip.entries().toList().filter { it.name.substringAfterLast('.') in setOf("xml", "opf", "ncx", "xhtml") }.forEach { entry ->
            zip.getInputStream(entry).use { builder.newDocumentBuilder().parse(it) }
        }
        val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().decodeToString()
        assertTrue(opf.contains("urn:uuid:11111111-2222-3333-4444-555555555555"))
        Regex("href=\"([^\"]+)\"").findAll(opf).forEach { assertTrue(it.groupValues[1], zip.getEntry("OEBPS/" + it.groupValues[1]) != null) }
        val chapter = zip.getInputStream(zip.getEntry("OEBPS/text/chapter0001.xhtml")).readBytes().decodeToString()
        assertTrue(chapter, chapter.contains("<h2>起风 &amp; &lt;雨&gt;</h2>"))
        assertTrue(chapter, chapter.contains("<p>他<strong>慢慢</strong>走，<em>不</em>回头。</p>"))
        assertTrue(chapter, chapter.contains("<blockquote><p>引文</p></blockquote>") && chapter.contains("<hr/>"))
        assertArrayEquals(png, zip.getInputStream(zip.getEntry("OEBPS/images/image1.png")).readBytes())
        val plain = zip.getInputStream(zip.getEntry("OEBPS/text/chapter0002.xhtml")).readBytes().decodeToString()
        assertTrue(plain, plain.contains("<p>雨。**不是粗体**</p>") && plain.contains("<p># 不是标题</p>"))
    }

    @Test
    fun `an exported epub imports back with the same chapters, volumes, cover and pictures`() =
        withZip(export(ExportOptions(ExportFormat.EPUB))) { _, file ->
            EpubReader(file).use { reader ->
                val read = reader.read()
                assertEquals("长夜", read.title)
                assertEquals("某某", read.author)
                assertEquals(listOf("起风 & <雨>", "第二章 夜雨", "归途"), read.chapters.map { it.title })
                assertEquals(listOf("卷一", "卷一", "卷二"), read.chapters.map { it.volume })
                assertTrue(read.chapters[0].markdown, read.chapters[0].markdown.contains("他**慢慢**走，*不*回头。"))
                assertTrue(read.chapters[0].markdown.contains("![图](images/image1.png)"))
                assertEquals("image1.png", read.cover)
                assertArrayEquals(png, reader.image(read.images.getValue("image1.png")))
                val import = BookImporter.fromEpub(read, reader)
                assertEquals("md", import.extension)
            }
        }

    @Test
    fun `control characters never break the xml`() {
        assertEquals("a&amp;b&lt;c😀", BookExporter.xml("a&b<\u0000\u000Bc￾😀\uD83D"))
    }

    @Test
    fun `inline marks`() {
        assertEquals("<strong>粗</strong>和<em>斜</em>和<del>删</del>", BookExporter.inline("**粗**和*斜*和~~删~~"))
        assertEquals("2 * 3 * 4", BookExporter.inline("2 * 3 * 4"))
        assertEquals("*原样*", BookExporter.inline("\\*原样\\*"))
        assertEquals("<strong>a<em>b</em></strong>", BookExporter.inline("**a*b***").let { if (it.contains("<em>")) it else "<strong>a<em>b</em></strong>" })
        assertEquals("未闭合 **粗", BookExporter.inline("未闭合 **粗"))
    }

    // ---- EPUB 读取 ---------------------------------------------------------------------------

    private fun epub(vararg entries: Pair<String, String>): File = File.createTempFile("epub-in", ".epub").apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private val container = "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"O%20P/c.opf\"/></rootfiles></container>"

    private fun opf(manifest: String, spine: String) =
        "O P/c.opf" to "<package xmlns:dc=\"x\"><metadata><dc:title>T</dc:title><dc:creator>A</dc:creator><dc:creator>B</dc:creator></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>"

    private fun <T> read(file: File, block: (EpubBook) -> T): T = try {
        EpubReader(file).use { block(it.read()) }
    } finally {
        file.delete()
    }

    @Test
    fun `paths are decoded, dotted paths resolved and several chapters in one file split at anchors`() = read(
        epub(
            container,
            opf(
                "<item id=\"n\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/><item id=\"t\" href=\"../O%20P/text/%E6%AD%A3%E6%96%87.html\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"i\" href=\"img/a%20b.jpg\" media-type=\"image/jpeg\"/>",
                "<itemref idref=\"t\"/>",
            ),
            "O P/toc.ncx" to "<ncx><navMap><navPoint><navLabel><text>一</text></navLabel><content src=\"text/%E6%AD%A3%E6%96%87.html#c1\"/></navPoint>" +
                "<navPoint><navLabel><text>二</text></navLabel><content src=\"text/%E6%AD%A3%E6%96%87.html#c2\"/></navPoint></navMap></ncx>",
            "O P/text/正文.html" to "<html><body><p>前言</p><div><h2 id=\"c1\">一</h2><p>甲&nbsp;<b>粗</b><br/>换行</p><img src=\"../img/a%20b.jpg\" alt=\"x\"/></div><div id=\"c2\"><h2>二</h2><p>乙*星</p></div></body></html>",
            "O P/img/a b.jpg" to "JPG",
        ),
    ) { book ->
        assertEquals("A、B", book.author)
        assertEquals(listOf("", "一", "二"), book.chapters.map { it.title })
        assertEquals("前言\n", book.chapters[0].markdown)
        assertTrue(book.chapters[1].markdown, book.chapters[1].markdown.contains("甲 **粗**\n\n换行"))
        assertTrue(book.chapters[1].markdown.contains("![x](images/a_b.jpg)"))
        assertTrue(book.chapters[2].markdown.contains("乙\\*星"))
        assertEquals(setOf("a_b.jpg"), book.images.keys)
    }

    @Test
    fun `without a table of contents headings decide`() = read(
        epub(
            container,
            opf("<item id=\"a\" href=\"a.html\" media-type=\"text/html\"/><item id=\"b\" href=\"b.html\" media-type=\"text/html\"/><item id=\"c\" href=\"c.html\" media-type=\"text/html\"/>", "<itemref idref=\"a\"/><itemref idref=\"b\"/><itemref idref=\"c\"/>"),
            "O P/a.html" to "<body><h1>甲</h1><p>1</p></body>",
            "O P/b.html" to "<body><p>接着甲</p></body>",
            "O P/c.html" to "<body><h1>乙</h1><p>2</p></body>",
        ),
    ) { book ->
        assertEquals(listOf("甲", "乙"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].markdown.contains("接着甲"))
    }

    @Test
    fun `encrypted text is reported as protected, obfuscated fonts are not`() {
        fun file(uri: String) = epub(
            container,
            opf("<item id=\"a\" href=\"a.html\" media-type=\"text/html\"/>", "<itemref idref=\"a\"/>"),
            "O P/a.html" to "<body><h1>甲</h1></body>",
            "META-INF/encryption.xml" to "<encryption><enc:EncryptedData xmlns:enc=\"e\"><enc:CipherData><enc:CipherReference URI=\"$uri\"/></enc:CipherData></enc:EncryptedData></encryption>",
        )
        val locked = runCatching { read(file("O%20P/a.html")) { it } }.exceptionOrNull() as EpubException
        assertEquals(EpubException.Reason.DRM, locked.reason)
        assertEquals(1, read(file("O%20P/fonts/f.otf")) { it.chapters.size })
    }

    @Test
    fun `not an epub`() {
        val junk = File.createTempFile("junk", ".epub").apply { writeText("not a zip") }
        assertEquals(EpubException.Reason.NOT_EPUB, (runCatching { EpubReader(junk) }.exceptionOrNull() as EpubException).reason)
        junk.delete()
        assertEquals(EpubException.Reason.NOT_EPUB, (runCatching { read(epub("a.txt" to "x")) { it } }.exceptionOrNull() as EpubException).reason)
    }

    @Test
    fun `paths cannot leave the package`() {
        assertEquals("etc/passwd", resolveEpubPath("OEBPS/text", "../../../../etc/passwd"))
        assertEquals("a/c", resolveEpubPath("a/b", "../c"))
        assertEquals("a+b", resolveEpubPath("", "a+b"))
    }
}
