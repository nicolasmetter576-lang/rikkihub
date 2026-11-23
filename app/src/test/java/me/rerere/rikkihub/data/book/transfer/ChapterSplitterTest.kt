package me.rerere.rikkihub.data.book.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {
    private fun body(i: Int) = "　　这是第${i}段正文，他在第二天走了。\n　　第一次见面是在码头。\n"

    private fun titles(text: String, rule: SplitRule = SplitRule.Auto) = ChapterSplitter.split(text, rule).map { it.title }

    /** 拆完拼回去必须一字不差。 */
    private fun assertLossless(text: String, rule: SplitRule = SplitRule.Auto) {
        val parts = ChapterSplitter.split(text, rule)
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test
    fun `plain numbered chapters with a preface`() {
        val text = "《长夜》\n作者：某某\n\n第一章 起风\n${body(1)}\n第二章 夜雨\n${body(2)}\n第三章 归途\n${body(3)}"
        assertEquals(listOf("", "第一章 起风", "第二章 夜雨", "第三章 归途"), titles(text))
        assertLossless(text)
    }

    @Test
    fun `sentences that only look like titles stay in the text`() {
        val text = "第一章 起风\n第二节课下课铃响了\n第一回见到她是在码头\n第三章我们再说\n${body(1)}第二章 夜雨\n${body(2)}第二章，他翻开书。\n第三章 归途\n${body(3)}"
        assertEquals(listOf("第一章 起风", "第二章 夜雨", "第三章 归途"), titles(text))
    }

    @Test
    fun `titles without a separator count when the number follows`() {
        val text = "第一章起风了\n${body(1)}第二章夜雨\n${body(2)}第三章归途？\n${body(3)}第九章里他说过\n${body(4)}第四章旧屋\n${body(5)}"
        assertEquals(listOf("第一章起风了", "第二章夜雨", "第三章归途？", "第四章旧屋"), titles(text))
    }

    @Test
    fun `volumes open with their first chapter`() {
        val text = "第一卷 山下\n\n第一章 甲\n${body(1)}第二章 乙\n${body(2)}第二卷 旧屋\n第一章 丙\n${body(3)}尾声\n${body(4)}"
        val parts = ChapterSplitter.split(text, SplitRule.Auto)
        assertEquals(listOf("第一章 甲", "第二章 乙", "第一章 丙", "尾声"), parts.map { it.title })
        assertEquals(listOf("第一卷 山下", null, "第二卷 旧屋", null), parts.map { it.volume })
        assertLossless(text)
    }

    @Test
    fun `volume and chapter on one line`() {
        val text = "第一卷 山下 第一章 甲\n${body(1)}第一卷 山下 第二章 乙\n${body(2)}第二卷 旧屋 第三章 丙\n${body(3)}"
        val parts = ChapterSplitter.split(text, SplitRule.Auto)
        assertEquals(listOf("第一章 甲", "第二章 乙", "第三章 丙"), parts.map { it.title })
        assertEquals(listOf("第一卷 山下", null, "第二卷 旧屋"), parts.map { it.volume })
    }

    @Test
    fun `a copied table of contents is not a run of empty chapters`() {
        val list = (1..6).joinToString("\n") { "第${it}章 标题$it" }
        val text = "目录\n$list\n\n" + (1..6).joinToString("") { "第${it}章 标题$it\n${body(it)}\n" }
        val parts = ChapterSplitter.split(text, SplitRule.Auto)
        assertEquals(listOf("") + (1..6).map { "第${it}章 标题$it" }, parts.map { it.title })
        assertTrue(text.substring(parts[1].start, parts[1].end).contains("正文"))
        assertLossless(text)
    }

    @Test
    fun `a title written twice in a row is one chapter`() {
        val text = "第一章 甲\n\n第一章 甲\n${body(1)}第二章 乙\n${body(2)}"
        assertEquals(listOf("第一章 甲", "第二章 乙"), titles(text))
        assertLossless(text)
    }

    @Test
    fun `a circle is a zero`() {
        val text = "第九九回 甲\n${body(1)}第一○○回 乙\n${body(2)}第一○一回 丙\n${body(3)}第一一○回 丁\n${body(4)}"
        assertEquals(listOf(99, 100, 101, 110), ChapterSplitter.split(text, SplitRule.Auto).map { it.number })
    }

    @Test
    fun `english chapters and full width numbers`() {
        assertEquals(
            listOf("Chapter 1 Rain", "CHAPTER 2", "Chapter 3: Home"),
            titles("Chapter 1 Rain\nIt rained.\nCHAPTER 2\nIn chapter 4 of the book he read it.\nMore.\nChapter 3: Home\nEnd.\n"),
        )
        assertEquals(listOf("第１章　甲", "第２章　乙"), titles("第１章　甲\n${body(1)}第２章　乙\n${body(2)}"))
    }

    @Test
    fun `the wrong unit is ignored`() {
        val text = (1..4).joinToString("") { "第${it}章 甲$it\n${body(it)}第一节 小节\n${body(9)}" }
        assertEquals((1..4).map { "第${it}章 甲$it" }, titles(text))
    }

    @Test
    fun `manual rules`() {
        assertEquals(listOf("甲", "乙"), titles("# 甲\n正文\n### 小\n正文\n## 乙\n正文", SplitRule.Headings(2)))
        assertEquals(listOf("甲", "乙"), titles("甲\n正文\n\n正文\n\n\n\n乙\n正文", SplitRule.BlankLines(3)))
        assertEquals(listOf("※ 一", "※ 二"), titles("※ 一\n正文\n※ 二\n正文", SplitRule.LinePrefix("※")))
        val long = (1..30).joinToString("\n") { "第几段不重要".repeat(10) }
        assertEquals(4, ChapterSplitter.split(long, SplitRule.FixedSize(500)).size)
        assertLossless(long, SplitRule.FixedSize(500))
    }

    @Test
    fun `nothing to split gives the whole text once`() {
        assertEquals(listOf(""), titles("没有章节的一篇短文。"))
        assertTrue(ChapterSplitter.split("", SplitRule.Auto).isEmpty())
        assertTrue(ChapterSplitter.split(" \n\n", SplitRule.Auto).isEmpty())
    }

    @Test
    fun `issues point at gaps, repeats and odd sizes`() {
        fun part(n: Int?, volume: String? = null) = SplitPart("t", 0, 0, n, volume)
        val parts = listOf(part(1), part(2), part(4), part(4), part(5), part(1, "卷二"), part(2))
        val issues = splitIssues(parts, listOf(1000, 1000, 1000, 1000, 9000, 1000, 10))
        assertEquals(
            listOf(emptySet(), emptySet(), setOf(SplitIssue.NUMBER_GAP), setOf(SplitIssue.NUMBER_REPEAT), setOf(SplitIssue.TOO_LONG), emptySet(), setOf(SplitIssue.TOO_SHORT)),
            issues,
        )
    }
}
