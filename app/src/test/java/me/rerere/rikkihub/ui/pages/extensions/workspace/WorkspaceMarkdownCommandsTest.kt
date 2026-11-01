package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class WorkspaceMarkdownCommandsTest {
    private fun applied(source: String, edit: WorkspaceSourceEdit) = source.replaceRange(edit.start, edit.end, edit.replacement)
    @Test fun `inline commands wrap only selected raw UTF16 range`() {
        val source = "before 中文😀 after"
        val start = source.indexOf("中文")
        val end = start + "中文😀".length
        val expected = mapOf(WorkspaceMarkdownCommand.BOLD to "**中文😀**", WorkspaceMarkdownCommand.ITALIC to "*中文😀*",
            WorkspaceMarkdownCommand.CODE to "`中文😀`", WorkspaceMarkdownCommand.LINK to "[中文😀](https://)")
        expected.forEach { (command, middle) ->
            val edit = workspaceMarkdownCommand(source, end, start, command)
            assertEquals("before $middle after", applied(source, edit))
            assertEquals("中文😀", applied(source, edit).substring(edit.selectionStart, edit.selectionEnd))
        }
    }
    @Test fun `heading list quote task prefix selected lines preserves CRLF and terminal newline`() {
        val source = "one\r\ntwo\r\nthree\r\n"
        val prefixes = mapOf(WorkspaceMarkdownCommand.HEADING to "### ", WorkspaceMarkdownCommand.LIST to "- ",
            WorkspaceMarkdownCommand.QUOTE to "> ", WorkspaceMarkdownCommand.TASK to "- [ ] ")
        prefixes.forEach { (command, prefix) ->
            val edit = workspaceMarkdownCommand(source, 1, source.indexOf("three"), command)
            assertEquals("${prefix}one\r\n${prefix}two\r\nthree\r\n", applied(source, edit))
        }
    }
    @Test fun `multiline code uses document newline and source stays untouched outside range`() {
        val source = "before\r\na\r\nb\r\nafter"
        val edit = workspaceMarkdownCommand(source, source.indexOf("a\r\nb"), source.indexOf("b\r\n") + 1, WorkspaceMarkdownCommand.CODE)
        assertEquals("before\r\n```\r\na\r\nb\r\n```\r\nafter", applied(source, edit))
    }
    @Test fun `empty selection inserts balanced wrappers and caret inside`() {
        val edit = workspaceMarkdownCommand("", 0, 0, WorkspaceMarkdownCommand.BOLD)
        assertEquals("****", edit.replacement)
        assertEquals(2, edit.selectionStart)
        assertEquals(2, edit.selectionEnd)
    }
    @Test fun `fork public edit shares native undo redo without custom stack`() {
        // Public API behavioral regression: unlike earlier Compose, this fork's commitEdit
        // explicitly records programmatic source edits. Parent runs JVM; IME path needs device.
        val state = TextFieldState("plain")
        state.undoState.clearHistory()
        state.edit { append(" typed") }
        val command = workspaceMarkdownCommand(state.text.toString(), 0, 5, WorkspaceMarkdownCommand.BOLD)
        state.edit { replace(command.start, command.end, command.replacement); selection = TextRange(command.selectionStart, command.selectionEnd) }
        assertEquals("**plain** typed", state.text.toString())
        assertTrue(state.undoState.canUndo)
        state.undoState.undo()
        assertEquals("plain typed", state.text.toString())
        state.undoState.undo()
        assertEquals("plain", state.text.toString())
        state.undoState.redo()
        state.undoState.redo()
        assertEquals("**plain** typed", state.text.toString())
    }
}
