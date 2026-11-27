package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import me.rerere.rikkihub.data.book.history.BookLocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑器里锁定段落的位置：按行定位（CRLF、BOM 也对）、打字时平移。 */
class BookEditorLocksTest {
    private val locked = "画里的女人眉眼低垂，像是在看他。"

    @Test
    fun `a passage is found by its lines, with CRLF and a BOM the range still covers just its text`() {
        val text = "﻿开头一句。\r\n$locked\r\n结尾。"
        val passage = BookLocks.passageAt(BookLocks.Doc(text), 1..1, "p", 0, "sha", false)
        val range = BookLocks.rangesIn(text, listOf(passage)).getValue("p")
        assertEquals(locked, text.substring(range.first, range.last + 1))
    }

    @Test
    fun `typing before the passage shifts it, typing inside grows it, typing after leaves it`() {
        val old = "前文。\n$locked\n后文。"
        val range = old.indexOf(locked) until old.indexOf(locked) + locked.length
        val before = "前文多几个字。\n$locked\n后文。"
        assertEquals(locked, before.substring(rebase(range, old, before)))
        val inside = old.replace("眉眼", "眉目低低地")
        assertEquals(locked.replace("眉眼", "眉目低低地"), inside.substring(rebase(range, old, inside)))
        val after = old.replace("后文", "后面的文字")
        assertEquals(range, rebase(range, old, after))
    }

    @Test
    fun `deleting the whole passage leaves an empty range, so its bar goes away`() {
        val old = "前文。\n$locked\n后文。"
        val range = old.indexOf(locked) until old.indexOf(locked) + locked.length
        val now = old.replace("$locked\n", "")
        assertTrue(rebase(range, old, now).isEmpty())
    }

    @Test
    fun `editor offsets turn into offsets of the normalized text`() {
        val text = "﻿a\r\nb"
        assertEquals(2, normalizedOffset(text, 4))
    }

    private fun String.substring(range: IntRange) = substring(range.first, range.last + 1)
}
