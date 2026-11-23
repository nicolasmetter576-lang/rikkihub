package me.rerere.rikkihub.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookManifestTest {
    private fun chapter(id: String) = BookChapter(id, "$id.md")
    private fun ids(manifest: BookManifest) = manifest.items.map { it.id }
    private val book = BookManifest(
        id = "b", title = "书",
        items = listOf(chapter("a"), BookVolume("v1", "卷一"), chapter("b"), chapter("c"), BookVolume("v2", "卷二"), chapter("d")),
    )

    @Test
    fun `encode and decode round trip keeps unknown fields`() {
        val text = """
            {"format":"rikkihub-book","version":1,"id":"b","title":"书","future":{"x":[1,2]},
             "items":[{"type":"chapter","id":"a","path":"a.md","status":"final","beats":7},{"type":"volume","id":"v","title":"卷","color":"red"}],
             "ignored":["notes.md"]}
        """.trimIndent()
        val first = BookManifest.decode(text)
        val second = BookManifest.decode(first.encode())
        assertEquals(first, second)
        assertTrue(second.encode().contains("\"future\""))
        assertTrue(second.encode().contains("\"beats\": 7"))
        assertTrue(second.encode().contains("\"color\": \"red\""))
        assertEquals(ChapterStatus.FINAL, second.chapters.single().status)
        assertEquals(setOf("notes.md"), second.ignored)
    }

    @Test
    fun `broken and newer manifests are told apart`() {
        fun reason(text: String) = (runCatching { BookManifest.decode(text) }.exceptionOrNull() as BookManifestException).reason
        assertEquals(BookManifestException.Reason.CORRUPT, reason("not json"))
        assertEquals(BookManifestException.Reason.CORRUPT, reason("[]"))
        assertEquals(BookManifestException.Reason.CORRUPT, reason("""{"format":"other","version":1,"items":[]}"""))
        assertEquals(BookManifestException.Reason.CORRUPT, reason("""{"format":"rikkihub-book","version":1}"""))
        assertEquals(BookManifestException.Reason.NEWER_VERSION, reason("""{"format":"rikkihub-book","version":2,"items":[]}"""))
    }

    @Test
    fun `hand edited duplicates get fresh ids and paths stay unique`() {
        val manifest = BookManifest.decode(
            """{"format":"rikkihub-book","version":1,"items":[
               {"type":"chapter","id":"x","path":"a.md"},{"type":"chapter","id":"x","path":"b.md"},
               {"type":"chapter","path":"c.md"},{"type":"chapter","id":"y","path":"a.md"},{"type":"mystery","id":"z"}]}""",
        )
        assertEquals(listOf("a.md", "b.md", "c.md"), manifest.chapters.map { it.path })
        assertEquals(3, manifest.chapters.map { it.id }.toSet().size)
    }

    @Test
    fun `moves keep relative order and survive an anchor inside the moved set`() {
        assertEquals(listOf("v1", "b", "c", "v2", "a", "d"), ids(book.move(setOf("a"), "v2")))
        assertEquals(listOf("b", "d", "a", "v1", "c", "v2"), ids(book.move(setOf("b", "d"), BookManifest.START)))
        assertEquals(listOf("a", "v1", "b", "c", "v2", "d"), ids(book.move(setOf("b", "c"), "c")))
        assertEquals(listOf("a", "v1", "b", "c", "v2", "d"), ids(book.move(setOf("a"), "a")))
    }

    @Test
    fun `moving to a volume lands at its end`() {
        assertEquals(listOf("v1", "b", "c", "a", "v2", "d"), ids(book.moveToVolume(setOf("a"), "v1")))
        assertEquals(listOf("v1", "b", "c", "v2", "d", "a"), ids(book.moveToVolume(setOf("a"), "v2")))
        assertEquals(listOf("a", "d", "v1", "b", "c", "v2"), ids(book.moveToVolume(setOf("d"), null)))
        // 卷里唯一的一章移到自己这一卷 = 不动
        assertEquals(ids(book), ids(book.moveToVolume(setOf("d"), "v2")))
    }

    @Test
    fun `reorder tolerates a list made before others were added`() {
        assertEquals(listOf("d", "a", "v1", "b", "c", "v2"), ids(book.reorder(listOf("d", "a"))))
        assertEquals(ids(book), ids(book.reorder(listOf("gone"))))
    }

    @Test
    fun `adding a file twice or an ignored file`() {
        val manifest = book.copy(ignored = setOf("e.md")).addChapters(listOf(chapter("e"), chapter("a")), "v1")
        assertEquals(listOf("a", "v1", "e", "b", "c", "v2", "d"), ids(manifest))
        assertTrue(manifest.ignored.isEmpty())
    }

    @Test
    fun `renamed files are followed only when there is no doubt`() {
        fun info(sha: String) = BookFileInfo(1, 1, sha, 1)
        val manifest = BookManifest("b", "", items = listOf(chapter("a"), chapter("b")))
        val remembered = mapOf("a.md" to info("A"), "b.md" to info("B"))
        assertEquals("new.md", followRenames(manifest, mapOf("new.md" to info("A"), "b.md" to info("B")), remembered).chapter("a")!!.path)
        // 两个内容相同的新文件：不猜
        assertEquals(manifest, followRenames(manifest, mapOf("n1.md" to info("A"), "n2.md" to info("A"), "b.md" to info("B")), remembered))
        // 两章曾有同样内容、都丢了：不猜
        val twins = mapOf("a.md" to info("A"), "b.md" to info("A"))
        assertEquals(manifest, followRenames(manifest, mapOf("new.md" to info("A")), twins))
        // 没见过旧文件：不猜
        assertEquals(manifest, followRenames(manifest, mapOf("new.md" to info("A"), "b.md" to info("B")), emptyMap()))
        // 原文件还在（复制）：不动
        assertEquals(manifest, followRenames(manifest, mapOf("a.md" to info("A"), "b.md" to info("B"), "copy.md" to info("A")), remembered))
    }

    @Test
    fun `first open takes chapter-looking files and makes folders volumes`() {
        fun info() = BookFileInfo(1, 1, "x", 1)
        val files = listOf("大纲.md", "第10章.md", "第2章.md", "设定/人物.md", "卷二/第12章 归来.txt", "卷二/第11章.md", "README.md", "序章.txt").associateWith { info() }
        val manifest = initialManifest("书", "zh", files)
        val shape = manifest.items.map { if (it is BookVolume) "#${it.title}" else (it as BookChapter).path }
        assertEquals(listOf("第2章.md", "第10章.md", "#卷二", "卷二/第11章.md", "卷二/第12章 归来.txt").toSet() + "序章.txt", shape.toSet())
        assertTrue(shape.indexOf("第2章.md") < shape.indexOf("第10章.md"))
        assertEquals(listOf("#卷二", "卷二/第11章.md", "卷二/第12章 归来.txt"), shape.takeLast(3))
        val assembled = assembleBook("w", manifest, files)
        assertEquals(setOf("大纲.md", "设定/人物.md", "README.md"), assembled.unlisted.toSet())
    }

    @Test
    fun `chapter looking names`() {
        listOf("第一章 归来.md", "第 3 回.txt", "012.md", "12、夜雨.txt", "Chapter 7.md", "ch03.txt", "ch03.5 rain.md", "楔子.md", "番外二.txt", "1-2 起.md").forEach {
            assertTrue(it, looksLikeChapter(it))
        }
        listOf("大纲.md", "人物设定.md", "README.md", "3d模型.md", "chat.md", "第一版大纲.md", "charts.md", "2024年计划.txt").forEach {
            assertTrue(it, !looksLikeChapter(it))
        }
    }

    @Test
    fun `chapter numbers`() {
        mapOf("十" to 10, "十二" to 12, "二十" to 20, "一百零五" to 105, "两百" to 200, "三千零一" to 3001, "一万二千" to 12000, "１２" to 12, "廿三" to 23, "二〇五" to 205, "一○四" to 104, "一一○" to 110, "007" to 7)
            .forEach { (text, value) -> assertEquals(text, value, ChapterNumbers.parse(text)) }
        assertNull(ChapterNumbers.parse("序"))
        for (value in 1..12_345) assertEquals(value, ChapterNumbers.parse(ChapterNumbers.toChinese(value)))
        assertEquals("十", ChapterNumbers.toChinese(10))
        assertEquals("一百一十", ChapterNumbers.toChinese(110))
        assertEquals("一千零一", ChapterNumbers.toChinese(1001))
    }

    @Test
    fun `next file name follows the last one`() {
        mapOf(
            "第9章 夜雨.md" to "第10章.md",
            "第九章 夜雨.md" to "第十章.md",
            "第 09 章.txt" to "第 10 章.txt",
            "012 起风.md" to "013.md",
            "012 第十二章 起风.md" to "013 第十三章.md",
            "Chapter 9 - Rain.md" to "Chapter 10.md",
            "ch09.txt" to "ch10.txt",
            "第１２章.md" to "第１３章.md",
            "正文 第三十九章.md" to "正文 第四十章.md",
        ).forEach { (from, to) -> assertEquals(from, to, ChapterNumbers.nextFileName(from)) }
        assertNull(ChapterNumbers.nextFileName("尾声.md"))
    }

    @Test
    fun `titles and word counts`() {
        assertEquals("夜雨", firstHeading("a.md", "\n\n## 夜雨 ##\n正文"))
        assertNull(firstHeading("a.txt", "# 夜雨"))
        assertNull(firstHeading("a.md", "正文没有标题"))
        // 中文按字符、英文按词
        assertEquals(6, bookWordCount("你好，世界。"))
        assertEquals(4, bookWordCount("The rain fell hard."))
        assertEquals(0, bookWordCount(""))
    }
}
