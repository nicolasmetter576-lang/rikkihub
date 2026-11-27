package me.rerere.rikkihub.data.book.history

import me.rerere.rikkihub.data.book.history.ChapterDiff.Block
import me.rerere.rikkihub.data.book.history.ChapterDiff.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterDiffTest {
    private fun changes(old: String, new: String) = ChapterDiff.of(old, new).blocks.filterIsInstance<Block.Changed>()

    @Test
    fun `an edited paragraph is highlighted word by word, line endings and trailing spaces do not count`() {
        val diff = ChapterDiff.of("甲。\r\n他走了。  \r\n丙。", "甲。\n他跑了。\n丙。")
        val change = diff.blocks.filterIsInstance<Block.Changed>().single()
        assertEquals(listOf("他走了。  "), change.old)
        assertEquals(
            listOf(ChapterDiff.Span("他", Kind.SAME), ChapterDiff.Span("走", Kind.REMOVED), ChapterDiff.Span("跑", Kind.ADDED), ChapterDiff.Span("了。", Kind.SAME)),
            change.spans!!.single().dropLastWhile { it.kind == Kind.REMOVED && it.text.isBlank() },
        )
        assertFalse(diff.rewritten)
    }

    @Test
    fun `inserted and deleted paragraphs get no word spans`() {
        val inserted = changes("a\nb", "a\nnew\nb").single()
        assertEquals(emptyList<String>(), inserted.old)
        assertEquals(listOf("new"), inserted.new)
        assertNull(inserted.spans)
        assertEquals(listOf("b"), changes("a\nb\nc", "a\nc").single().old)
    }

    @Test
    fun `huge paragraphs only get paragraph level`() {
        val long = "字".repeat(ChapterDiff.MAX_WORD_DIFF_CHARS + 1)
        assertNull(changes(long, long + "尾").single().spans)
    }

    @Test
    fun `an almost entirely new chapter is flagged as rewritten`() {
        val old = (1..30).joinToString("\n") { "旧$it" }
        val new = (1..30).joinToString("\n") { "新$it" }
        assertTrue(ChapterDiff.of(old, new).rewritten)
    }

    @Test
    fun `taking one block back leaves every other byte alone`() {
        val old = "开头\r\n被删的段\r\n中间\r\n原句。\r\n结尾"
        val current = "开头\r\n中间\r\n改过的句子。  \r\n新加的段\r\n结尾"
        val blocks = changes(old, current)
        assertEquals(2, blocks.size)
        assertEquals("开头\r\n被删的段\r\n中间\r\n改过的句子。  \r\n新加的段\r\n结尾", ChapterDiff.takeBack(old, current, blocks[0].index))
        assertEquals("开头\r\n中间\r\n原句。\r\n结尾", ChapterDiff.takeBack(old, current, blocks[1].index))
        assertNull(ChapterDiff.takeBack(old, current, 5))
    }

    @Test
    fun `repeated short paragraphs are matched as whole lines, not substrings`() {
        val old = "嗯。\n他说。\n嗯。\n她说。"
        val current = "嗯。\n他说。\n嗯嗯。\n她说。"
        val block = changes(old, current).single()
        assertEquals(listOf("嗯。"), block.old)
        assertEquals(old, ChapterDiff.takeBack(old, current, block.index))
    }

    @Test
    fun `paragraphs added one after another are separate blocks, each can be taken back alone`() {
        val old = "# 起风\n\n阿青下山。\n"
        val current = "# 起风\n\n阿青下山。\n\nAI 写的一段。\n\n手写加的一段。\n"
        val blocks = changes(old, current)
        assertEquals(2, blocks.size)
        assertEquals("# 起风\n\n阿青下山。\n\n手写加的一段。\n", ChapterDiff.takeBack(old, current, blocks.first { "AI 写的一段。" in it.new }.index))
        assertEquals("# 起风\n\n阿青下山。\n\nAI 写的一段。\n", ChapterDiff.takeBack(old, current, blocks.first { "手写加的一段。" in it.new }.index))
    }

    @Test
    fun `paragraphs deleted together come back one at a time`() {
        val old = "开头\n\n第一段。\n\n第二段。\n\n结尾"
        val current = "开头\n\n结尾"
        val blocks = changes(old, current)
        assertEquals(2, blocks.size)
        assertEquals("开头\n\n第二段。\n\n结尾", ChapterDiff.takeBack(old, current, blocks.first { "第二段。" in it.old }.index))
    }
}
