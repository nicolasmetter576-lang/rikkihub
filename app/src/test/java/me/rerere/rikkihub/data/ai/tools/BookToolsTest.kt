package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookFileInfo
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterStatus
import me.rerere.rikkihub.data.book.assembleBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookToolsTest {
    private fun info(words: Int = 100) = BookFileInfo(1, 1, "x", words)

    private fun book(chapters: Int = 3, extra: List<String> = listOf("新章.md")): Book {
        val items = listOf(BookVolume("v", "卷一")) + (1..chapters).map { BookChapter("c$it", "第${it}章.md", synopsis = "梗概$it") }
        val files = (1..chapters).associate { "第${it}章.md" to info() } + extra.associateWith { info() }
        return assembleBook("w", BookManifest("b", "长夜", items = items), files)
    }

    private fun ops(json: String): List<JsonObject> = (Json.parseToJsonElement(json) as JsonArray).map { it.jsonObject }

    private fun apply(json: String, on: Book = book()) = applyBookOperations(on, ops(json))

    private fun failure(json: String): String = runCatching { apply(json) }.exceptionOrNull()!!.message!!

    @Test
    fun `chapter numbers always mean the numbers before the call`() {
        val result = apply("""[{"op":"move","chapter":1,"after":3},{"op":"set","chapter":1,"title":"原第一章"},{"op":"remove","chapter":2}]""")
        assertEquals(listOf("c3", "c1"), result.chapters.map { it.id })
        assertEquals("原第一章", result.chapter("c1")!!.title)
    }

    @Test
    fun `several adds at the same place keep their written order`() {
        val on = book(extra = listOf("甲.md", "乙.md"))
        val result = apply("""[{"op":"add","path":"/workspace/甲.md","after":1},{"op":"add","path":"乙.md","after":1,"status":"final"}]""", on)
        assertEquals(listOf("第1章.md", "甲.md", "乙.md", "第2章.md", "第3章.md"), result.chapters.map { it.path })
        assertEquals(ChapterStatus.FINAL, result.chapters[2].status)
        assertEquals("甲.md", apply("""[{"op":"add","path":"甲.md","after":0}]""", on).chapters.first().path)
        assertEquals("甲.md", apply("""[{"op":"add","path":"甲.md"}]""", on).chapters.last().path)
    }

    @Test
    fun `bad operations are refused with a message the model can act on`() {
        assertTrue(failure("""[{"op":"set","chapter":9,"title":"x"}]""").contains("no chapter 9"))
        assertTrue(failure("""[{"op":"add","path":"/workspace/没有.md"}]""").contains("No such text file"))
        assertTrue(failure("""[{"op":"add","path":"第1章.md"}]""").contains("Already in the contents"))
        assertTrue(failure("""[{"op":"add","path":"新章.md"},{"op":"add","path":"新章.md"}]""").contains("Already in the contents"))
        assertTrue(failure("""[{"op":"set","chapter":1,"status":"done"}]""").contains("draft, revising or final"))
        assertTrue(failure("""[{"op":"move","chapter":1}]""").contains("after"))
        assertTrue(failure("""[{"op":"rewrite","chapter":1}]""").contains("rewrite"))
    }

    @Test
    fun `set leaves the fields it does not mention`() {
        val result = apply("""[{"op":"set","chapter":2,"status":"revising"}]""").chapter("c2")!!
        assertEquals("梗概2", result.synopsis)
        assertEquals(ChapterStatus.REVISING, result.status)
    }

    @Test
    fun `contents list shows numbers, paths and unlisted files`() {
        val text = bookContentsText(book(), null, null, synopses = true)
        assertTrue(text, text.contains("## 卷一"))
        assertTrue(text, text.contains("2. 第2章 — 100 words, draft, /workspace/第2章.md\n   梗概2"))
        assertTrue(text, text.contains("- /workspace/新章.md"))
        assertFalse(bookContentsText(book(), null, null, synopses = false).contains("梗概"))
    }

    @Test
    fun `a long book is listed in parts`() {
        val text = bookContentsText(book(chapters = 450), null, null, synopses = false)
        assertTrue(text.contains("\n200. "))
        assertFalse(text.contains("\n201. "))
        assertTrue(text.contains("chapters 1-200 of 450"))
        val part = bookContentsText(book(chapters = 450), 201, 205, synopses = false)
        assertTrue(part.contains("\n205. ") && !part.contains("\n206. ") && part.contains("201. "))
    }

    @Test
    fun `slices say where they were cut and never split a surrogate pair`() {
        val text = "😀".repeat(10)
        val first = sliceProse(text, "all", 5, 0)
        assertTrue(first, first.startsWith("😀😀\n") && first.contains("offset=4"))
        val rest = sliceProse(text, "all", 100, 4)
        assertEquals("😀".repeat(8), rest)
        assertTrue(sliceProse(text, "end", 5, 0).endsWith("😀😀"))
        assertTrue(sliceProse(text, "start", 4, 0).startsWith("😀😀\n(the first 4 of 20"))
        assertEquals(text, sliceProse(text, "end", 100, 0))
        assertEquals("", sliceProse(text, "all", 10, 999))
    }

    @Test
    fun `excerpts keep whole characters`() {
        val line = "😀".repeat(30) + "命中" + "😀".repeat(30)
        val shown = excerpt(line, 60, 2, context = 5)
        assertTrue(shown.contains("命中") && shown.startsWith("…") && shown.endsWith("…"))
        assertFalse(shown.any { it.isSurrogate() } && shown.codePoints().anyMatch { it in 0xD800..0xDFFF })
    }
}
