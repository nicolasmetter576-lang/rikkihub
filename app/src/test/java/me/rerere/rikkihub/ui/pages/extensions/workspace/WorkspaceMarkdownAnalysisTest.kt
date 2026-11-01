package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.*
import org.junit.Test
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

class WorkspaceMarkdownAnalysisTest {
    private fun analyze(text: String) = WorkspaceMarkdownAnalyzer.analyze(text)
    private fun has(result: WorkspaceMarkdownAnalysis, style: WorkspaceMarkdownStyle, offset: Int) = result.spans.any { it.style == style && offset in it.start until it.end }

    @Test fun `nested emphasis keeps ancestor weight and child italic`() {
        val source = "**粗*斜😀*体** and ~~gone~~"
        val result = analyze(source)
        assertTrue(has(result, WorkspaceMarkdownStyle.BOLD, source.indexOf("斜")))
        assertTrue(has(result, WorkspaceMarkdownStyle.ITALIC, source.indexOf("斜")))
        assertTrue(has(result, WorkspaceMarkdownStyle.STRIKE, source.indexOf("gone")))
        assertTrue(has(result, WorkspaceMarkdownStyle.MARKER, 0))
        assertEquals(source, result.source)
    }
    @Test fun `unfinished and escaped emphasis do not invent bold`() {
        listOf("**unfinished", "\\*\\*literal\\*\\*", "ordinary text").forEach {
            assertFalse(analyze(it).spans.any { span -> span.style == WorkspaceMarkdownStyle.BOLD })
        }
    }
    @Test fun `atx and setext headings plus distinct raw markers`() {
        val source = "### 中文😀\n\nTitle\n=====\n\nOther\n-----\n"
        val result = analyze(source)
        assertTrue(has(result, WorkspaceMarkdownStyle.HEADING, source.indexOf("中文")))
        assertEquals(3, result.spans.first { it.style == WorkspaceMarkdownStyle.HEADING }.level)
        assertTrue(has(result, WorkspaceMarkdownStyle.MARKER, 0))
        assertTrue(has(result, WorkspaceMarkdownStyle.HEADING, source.indexOf("Title")))
        assertTrue(has(result, WorkspaceMarkdownStyle.MARKER, source.indexOf("=====")))
        assertTrue(has(result, WorkspaceMarkdownStyle.HEADING, source.indexOf("Other")))
    }
    @Test fun `fenced code is opaque and closing fence does not swallow next paragraph`() {
        val source = "```kt\n**literal**\n```\n\n**real**\n"
        val result = analyze(source)
        assertTrue(has(result, WorkspaceMarkdownStyle.CODE, source.indexOf("literal")))
        assertFalse(has(result, WorkspaceMarkdownStyle.BOLD, source.indexOf("literal")))
        assertFalse(has(result, WorkspaceMarkdownStyle.CODE, source.indexOf("real")))
        assertTrue(has(result, WorkspaceMarkdownStyle.BOLD, source.indexOf("real")))
        assertTrue(has(result, WorkspaceMarkdownStyle.MARKER, source.indexOf("```")))
    }
    @Test fun `unfinished fence and indented code stay opaque`() {
        listOf("```\n**literal**", "    **literal**\n").forEach { source ->
            val result = analyze(source)
            assertTrue(has(result, WorkspaceMarkdownStyle.CODE, source.indexOf("literal")))
            assertFalse(has(result, WorkspaceMarkdownStyle.BOLD, source.indexOf("literal")))
        }
    }
    @Test fun `inline code escaped delimiter does not produce emphasis`() {
        val source = "`**literal**` and \\*escaped\\*"
        val result = analyze(source)
        assertTrue(has(result, WorkspaceMarkdownStyle.CODE, source.indexOf("literal")))
        assertFalse(result.spans.any { it.style == WorkspaceMarkdownStyle.BOLD || it.style == WorkspaceMarkdownStyle.ITALIC })
    }
    @Test fun `ordered unordered task and quote nodes style source`() {
        val source = "> quote\n\n- [x] done\n- [ ] pending\n\n1. ordered\n"
        val result = analyze(source)
        assertTrue(has(result, WorkspaceMarkdownStyle.QUOTE, source.indexOf("quote")))
        assertTrue(has(result, WorkspaceMarkdownStyle.LIST, source.indexOf("done")))
        assertTrue(has(result, WorkspaceMarkdownStyle.LIST, source.indexOf("ordered")))
        assertTrue(has(result, WorkspaceMarkdownStyle.TASK, source.indexOf("[x]")))
        assertTrue(has(result, WorkspaceMarkdownStyle.MARKER, source.indexOf("[ ]")))
    }
    @Test fun `gfm table retains pipes separator and header weight`() {
        val source = "| a | b |\n|---|---|\n| c | d |\n"
        val result = analyze(source)
        assertTrue(has(result, WorkspaceMarkdownStyle.TABLE, source.indexOf("c")))
        assertTrue(has(result, WorkspaceMarkdownStyle.BOLD, source.indexOf("a")))
        assertTrue(has(result, WorkspaceMarkdownStyle.MARKER, source.indexOf("---")))
        assertEquals(source, result.source)
    }
    @Test fun `raw intellij offsets are UTF16 half open and preserve CJK emoji CRLF exact identity`() {
        val source = "中文😀\r\n\r\n**粗体😀**\r\n"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        assertEquals(0, root.startOffset)
        assertEquals(source.length, root.endOffset)
        val result = analyze(source)
        val strong = result.spans.first { it.style == WorkspaceMarkdownStyle.BOLD }
        assertEquals("**粗体😀**", source.substring(strong.start, strong.end))
        assertEquals(source.indexOf("**"), strong.start)
        assertEquals(source, result.source)
        assertTrue(result.spans.all { it.start >= 0 && it.end <= source.length })
        assertEquals(2, "😀".length)
    }
    @Test fun `oversized source and line budgets explicitly fall back without partial styling`() {
        for (source in listOf("x".repeat(WorkspaceMarkdownAnalyzer.MAX_SOURCE_CHARS + 1), "\n".repeat(WorkspaceMarkdownAnalyzer.MAX_LINES))) {
            val result = analyze(source)
            assertTrue(result.plainFallback)
            assertTrue(result.spans.isEmpty())
            assertEquals(source, result.source)
        }
    }
    @Test fun `span node and depth budgets never exceed bounded results`() {
        val result = analyze("*x* ".repeat(12_000))
        assertTrue(result.plainFallback)
        assertTrue(result.spans.isEmpty())
    }
    @Test fun `cancellation hook can interrupt parser and traversal`() {
        var checks = 0
        try {
            WorkspaceMarkdownAnalyzer.analyze("**x**\n".repeat(100)) { if (++checks > 5) throw InterruptedException("cancel") }
            fail("did not cancel")
        } catch (_: InterruptedException) { assertTrue(checks > 5) }
    }
    @Test fun `safe link preview excludes image links dangerous schemes html and userinfo`() {
        val source = "[ok](https://example.com/path) [bad](javascript:alert) ![img](https://example.com/image) <img src='https://example.com/a'>"
        val result = analyze(source)
        assertEquals(1, result.links.size)
        assertEquals("https://example.com/path", result.links.single().url)
        assertNull(safeWorkspaceMarkdownUrl("intent://evil"))
        assertNull(safeWorkspaceMarkdownUrl("file:///etc/passwd"))
        assertNull(safeWorkspaceMarkdownUrl("content://evil/a"))
        assertNull(safeWorkspaceMarkdownUrl("data:text/html,x"))
        assertNull(safeWorkspaceMarkdownUrl("https://user:password@example.com"))
        assertNull(safeWorkspaceMarkdownUrl("https://example.com/\nanything"))
        assertNull(safeWorkspaceMarkdownUrl("https://example.com\\@evil"))
        assertEquals("http://example.com", safeWorkspaceMarkdownUrl("http://example.com"))
    }

    private fun bold(start: Int, end: Int) = WorkspaceMarkdownSpan(start, end, WorkspaceMarkdownStyle.BOLD)

    @Test fun `typing inside a span extends it and typing after it does not`() {
        val spans = listOf(bold(0, 6)) // **ab**
        assertEquals(listOf(bold(0, 7)), spans.rebased("**ab** z", "**aXb** z"))
        assertEquals(listOf(bold(0, 6)), spans.rebased("**ab** z", "**ab**X z"))
    }

    @Test fun `spans after the edit shift and spans before it stay`() {
        val spans = listOf(bold(0, 2), bold(6, 8)) // "ab", "ef"
        assertEquals(listOf(bold(0, 2), bold(9, 11)), spans.rebased("ab cd ef", "ab XYZcd ef"))
        assertEquals(listOf(bold(0, 2), bold(3, 5)), spans.rebased("ab cd ef", "ab ef"))
    }

    @Test fun `a boundary inside deleted text snaps to the edit and empty spans vanish`() {
        // "34567" loses "3456": only "7" stays bold.
        assertEquals(listOf(bold(2, 3)), listOf(bold(3, 8)).rebased("0123456789", "01789"))
        assertEquals(emptyList<WorkspaceMarkdownSpan>(), listOf(bold(3, 5)).rebased("0123456789", "0156789"))
        assertEquals(emptyList<WorkspaceMarkdownSpan>(), listOf(bold(0, 4)).rebased("abcd", "wxyz"))
    }

    @Test fun `unchanged text keeps the very same spans`() {
        val spans = listOf(bold(0, 3))
        assertSame(spans, spans.rebased("abc", StringBuilder("abc")))
    }
}
