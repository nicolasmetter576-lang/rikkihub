package me.rerere.rikkihub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeBlockWorkspaceSaveTest {
    @Test
    fun `markdown blocks are saved verbatim`() {
        assertEquals("# Title\n", codeBlockMarkdownContent("# Title", "markdown"))
        assertEquals("a\n", codeBlockMarkdownContent("a\n", "plaintext"))
    }

    @Test
    fun `code blocks are fenced with their language`() {
        assertEquals("```kotlin\nval a = 1\n```\n", codeBlockMarkdownContent("val a = 1", "kotlin"))
    }

    @Test
    fun `fence outgrows inner backtick runs`() {
        assertEquals("````js\n```x```\n````\n", codeBlockMarkdownContent("```x```", "js"))
    }

    @Test
    fun `file names stay at the workspace root`() {
        assertEquals("code_2026-10-16T14-18-14-123.md", codeBlockWorkspaceFileName("2026-10-16T14:18:14.123", 0))
        assertEquals("code_2026-10-16T14-18-14-123_2.md", codeBlockWorkspaceFileName("2026-10-16T14:18:14.123", 2))
    }
}
