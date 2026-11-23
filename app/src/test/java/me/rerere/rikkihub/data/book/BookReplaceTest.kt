package me.rerere.rikkihub.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookReplaceTest {
    private fun pairs(vararg pair: Pair<String, String>) = pair.map { ReplacePair(it.first, it.second) }

    private fun replaceAll(text: String, vararg pair: Pair<String, String>, ignoreCase: Boolean = false) =
        BookReplace.replace(text, pairs(*pair), ignoreCase, null).first

    @Test
    fun `names can swap in one pass`() {
        assertEquals("乙打了甲，甲没还手", replaceAll("甲打了乙，乙没还手", "甲" to "乙", "乙" to "甲"))
    }

    @Test
    fun `the longest name at the same place wins`() {
        assertEquals("苏婉清、小苏、苏家", replaceAll("林婉清、小林、林家", "林" to "苏", "林婉清" to "苏婉清", "小林" to "小苏"))
    }

    @Test
    fun `replaced text is never matched again`() {
        assertEquals("aab", replaceAll("ab", "a" to "aa"))
        assertEquals("bb", replaceAll("aaa", "aa" to "b", "a" to "b"))
    }

    @Test
    fun `case is ignored only when asked`() {
        assertEquals("X b X", replaceAll("Abc b aBC", "abc" to "X", ignoreCase = true))
        assertEquals("Abc b X", replaceAll("Abc b aBC", "aBC" to "X"))
    }

    @Test
    fun `half an emoji is not a hit`() {
        val text = "😀😁"
        assertTrue(BookReplace.find(text, pairs("\uD83D" to "x"), false).isEmpty())
        assertTrue(BookReplace.find(text, pairs("\uDE00" to "x"), false).isEmpty())
        assertEquals("x😁", replaceAll(text, "😀" to "x"))
    }

    @Test
    fun `hits carry line, context and their pair`() {
        val text = "第一行\r\n前面二十个字以内阿青后面\r\n阿青"
        val hits = BookReplace.find(text, pairs("没有" to "", "阿青" to "阿白"), false)
        assertEquals(listOf(2, 3), hits.map { it.line })
        assertEquals("前面二十个字以内", hits[0].before)
        assertEquals("后面", hits[0].after)
        assertTrue(hits.all { it.pair == 1 && it.match == "阿青" })
    }

    @Test
    fun `context is cut on whole characters`() {
        val text = "😀".repeat(15) + "名" + "😀".repeat(15)
        val hit = BookReplace.find(text, pairs("名" to "x"), false).single()
        fun whole(s: String) = s.codePoints().noneMatch { it in 0xD800..0xDFFF }
        assertTrue(whole(hit.before) && whole(hit.after))
        assertTrue(hit.before.startsWith("…") && hit.after.endsWith("…"))
    }

    @Test
    fun `only the chosen hits change`() {
        val text = "阿青 阿青 阿青"
        val hits = BookReplace.find(text, pairs("阿青" to "阿白"), false)
        val (result, count) = BookReplace.replace(text, pairs("阿青" to "阿白"), false, setOf(hits[1].start))
        assertEquals("阿青 阿白 阿青", result)
        assertEquals(1, count)
    }

    @Test
    fun `an empty replacement deletes`() {
        assertEquals("他走了", replaceAll("他慢慢地走了", "慢慢地" to ""))
    }

    @Test
    fun `line breaks and everything else stay byte for byte`() {
        val text = "﻿甲\r\n\r\n　　乙\t甲\n"
        assertEquals("﻿丙\r\n\r\n　　乙\t丙\n", replaceAll(text, "甲" to "丙"))
    }
}
