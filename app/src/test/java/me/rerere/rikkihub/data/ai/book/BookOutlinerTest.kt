package me.rerere.rikkihub.data.ai.book

import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatState
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookFileInfo
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterNotes
import me.rerere.rikkihub.data.book.ChapterOutline
import me.rerere.rikkihub.data.book.OutlineFile
import me.rerere.rikkihub.data.book.PlotPoint
import me.rerere.rikkihub.data.book.QuoteLocator
import me.rerere.rikkihub.data.book.assembleBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookOutlinerTest {
    // ---- 引文 --------------------------------------------------------------------------------

    private fun located(text: String, quote: String) = QuoteLocator.locate(text, quote)?.let { text.substring(it) }

    @Test
    fun `quotes are found with small differences and come back as the text has them`() {
        val text = "他说：「你来得**太晚**了，\n　　船已经走了。」然后转身。"
        assertEquals("船已经走了", located(text, "船已经走了"))
        assertEquals("你来得**太晚**了，\n　　船已经走了。", located(text, "“你来得太晚了，船已经走了。”")?.trimEnd('」')?.trim('「'))
        assertNull(located(text, "船还没有走"))
        assertNull(located(text, "太晚 了"))
        assertNull(located(text, ""))
        assertNull(located(text, "「」"))
    }

    // ---- 提取 --------------------------------------------------------------------------------

    @Test
    fun `broken lines are skipped and unfound quotes are left empty`() {
        val text = "她在码头等船。船没来，来的是信。"
        val output = """
            ```json
            {"synopsis":"等船。"}
            {"text":"她等船","quote":"她在码头等船"}
            - {"text":"信来了","quote":"来的是一封信"},
            {"quote":"没有 text"}
            {"text":"   "}
            {"text":"半截
        """.trimIndent()
        val outline = BookOutliner.parseOutline(output, text, "h")!!
        assertEquals("等船。", outline.synopsis)
        assertEquals(listOf("她等船" to "她在码头等船", "信来了" to ""), outline.points.map { it.text to it.quote })
        assertEquals(2, outline.points.map { it.id }.toSet().size)
    }

    @Test
    fun `no usable point means no outline`() {
        assertNull(BookOutliner.parseOutline("抱歉，我不能。", "x", "h"))
        assertNull(BookOutliner.parseOutline("""{"synopsis":"只有梗概"}""", "x", "h"))
    }

    // ---- 对照 --------------------------------------------------------------------------------

    private val notes = ChapterNotes(
        beats = listOf(Beat("b1", "等船"), Beat("b2", "来信"), Beat("b3", "北上")),
        outline = ChapterOutline("h", 1, "", listOf(PlotPoint("p1", "等船", ""), PlotPoint("p2", "信", ""))),
    )

    @Test
    fun `numbers that do not exist are dropped and unmentioned beats count as missing`() {
        val output = """
            {"beat":1,"state":"written","points":[1,1,9,"2"]}
            {"beat":2,"state":"changed","points":[7],"note":"x"}
            {"beat":8,"state":"written","points":[1]}
            {"beat":1,"state":"missing","points":[]}
            {"beat":2,"state":"unknown","points":[2]}
        """.trimIndent()
        val matches = BookOutliner.parseComparison(output, notes)
        assertEquals(listOf("b1", "b2", "b3"), matches.map { it.beatId })
        assertEquals(listOf(BeatState.WRITTEN, BeatState.MISSING, BeatState.MISSING), matches.map { it.state })
        assertEquals(listOf("p1", "p2"), matches[0].pointIds)
    }

    @Test
    fun `an unusable reply is not a comparison`() {
        assertEquals(emptyList<Any>(), BookOutliner.parseComparison("没有 JSON", notes))
        assertEquals(emptyList<Any>(), BookOutliner.parseComparison("""{"beat":9,"state":"written"}""", notes))
    }

    // ---- 大纲文件 ----------------------------------------------------------------------------

    private fun book(vararg titles: String) = assembleBook(
        "w",
        BookManifest("id", "书", items = listOf(BookVolume("v", "卷一")) + titles.mapIndexed { i, t -> BookChapter("c${i + 1}", "$t.md", title = t) }),
        titles.associate { "$it.md" to BookFileInfo(1, 1, it, 1) },
    )

    @Test
    fun `headings match by number first name, lists become beats`() {
        val outline = """
            # 全书大纲
            主题：离别
            ## 第一卷
            ### 第1章 起风
            - 她离开山门
            - [x] 遇见老人
            1. 夜宿破庙
            ### 第二章：改了名
            雨夜来信
            ### 归途
            * 回家
            ### 第九章 没有的
            - 以后再说
        """.trimIndent()
        val sections = OutlineFile.parse(outline, book("第一章 起风", "第二章 夜雨", "第三章 归途"))
        assertEquals(listOf("全书大纲", "第1章 起风", "第二章：改了名", "归途", "第九章 没有的"), sections.map { it.title })
        assertEquals(listOf(null, "c1", "c2", "c3", null), sections.map { it.chapterId })
        assertEquals(listOf("她离开山门", "遇见老人", "夜宿破庙"), sections[1].beats)
    }

    @Test
    fun `one line per chapter`() {
        val sections = OutlineFile.parse("第一章 起风：她离开山门\n第二章：来信\n她没有回头\n", book("起风", "夜雨"))
        assertEquals(listOf("c1", "c2"), sections.map { it.chapterId })
        assertEquals(listOf(listOf("她离开山门"), listOf("来信", "她没有回头")), sections.map { it.beats })
    }

    @Test
    fun `an ambiguous name is not guessed`() {
        val twice = book("归途", "归途")
        assertEquals(listOf<String?>(null), OutlineFile.parse("# 归途\n- 甲", twice).map { it.chapterId })
        val restarted = book("第一章 甲", "第一章 乙")
        assertEquals(listOf("c2"), OutlineFile.parse("# 第一章 乙\n- 甲", restarted).map { it.chapterId })
        assertEquals(listOf<String?>(null), OutlineFile.parse("# 第一章 丙\n- 甲", restarted).map { it.chapterId })
    }
}
