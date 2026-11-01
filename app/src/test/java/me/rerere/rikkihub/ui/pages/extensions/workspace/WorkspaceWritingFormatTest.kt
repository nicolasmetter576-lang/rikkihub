package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWritingFormatTest {
    private fun applied(source: String, edit: WorkspaceSourceEdit) = source.replaceRange(edit.start, edit.end, edit.replacement)

    /** The cases the web editor's port runs too (`web-ui/tests/writing.test.ts`). */
    @Test fun `shared format cases`() {
        val cases = Json.parseToJsonElement(javaClass.getResource("/writing/format-cases.json")!!.readText()).jsonArray
        assertTrue(cases.size >= 20)
        for (case in cases.map { it.jsonObject }) {
            val name = case.string("name")
            val source = case.string("source")
            val kind = if (case.string("kind") == "markdown") WorkspaceFormatKind.MARKDOWN else WorkspaceFormatKind.PLAIN
            val command = WorkspaceFormatCommand.entries.first { it.id == case.string("command") }
            val selection = case["selection"]?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(0, 0)
            val edit = WorkspaceWritingFormat.apply(source, selection[0], selection[1], command, kind)
            val expected = case.string("expected")
            if (expected == source) assertNull(name, edit) else assertEquals(name, expected, applied(source, edit!!))
        }
    }

    @Test fun `edit covers only the changed span and keeps the caret on its line`() {
        val source = "一\n二\n三"
        val caret = source.indexOf("二") + 1 // after 二
        val edit = WorkspaceWritingFormat.apply(source, caret, caret, WorkspaceFormatCommand.INDENT, WorkspaceFormatKind.PLAIN)!!
        val result = applied(source, edit)
        assertEquals("　　一\n　　二\n　　三", result)
        assertEquals(edit.selectionStart, edit.selectionEnd)
        assertEquals("　　二", result.substring(result.indexOf('\n') + 1, edit.selectionStart))
        // Only the span between the first and last change is replaced.
        assertEquals(0, edit.start)
        assertEquals(source.length - 1, edit.end)
    }

    @Test fun `caret on a removed blank line moves to the next paragraph`() {
        val source = "一\n\n二"
        val edit = WorkspaceWritingFormat.apply(source, 2, 2, WorkspaceFormatCommand.REMOVE_BLANK_LINES, WorkspaceFormatKind.PLAIN)!!
        val result = applied(source, edit)
        assertEquals("一\n二", result)
        assertEquals(result.indexOf("二"), edit.selectionStart)
    }

    @Test fun `a selection stays on the formatted lines`() {
        val source = "零\n一\n二\n三"
        val start = source.indexOf("一")
        val end = source.indexOf("二") + 1
        val edit = WorkspaceWritingFormat.apply(source, start, end, WorkspaceFormatCommand.INDENT, WorkspaceFormatKind.PLAIN)!!
        val result = applied(source, edit)
        assertEquals("　　一\n　　二", result.substring(edit.selectionStart, edit.selectionEnd))
    }

    @Test fun `one command is one undo step`() {
        val state = TextFieldState("一\n二\n三")
        state.undoState.clearHistory()
        val edit = WorkspaceWritingFormat.apply(state.text.toString(), 0, 0, WorkspaceFormatCommand.BLANK_LINE_BETWEEN, WorkspaceFormatKind.PLAIN)!!
        state.edit {
            replace(edit.start, edit.end, edit.replacement)
            selection = TextRange(edit.selectionStart, edit.selectionEnd)
        }
        assertEquals("一\n\n二\n\n三", state.text.toString())
        state.undoState.undo()
        assertEquals("一\n二\n三", state.text.toString())
        assertTrue(!state.undoState.canUndo)
    }

    @Test fun `format kinds by extension`() {
        assertEquals(WorkspaceFormatKind.MARKDOWN, workspaceFormatKind("a.MD"))
        assertEquals(WorkspaceFormatKind.MARKDOWN, workspaceFormatKind("a.markdown"))
        assertEquals(WorkspaceFormatKind.PLAIN, workspaceFormatKind("a.txt"))
        assertEquals(WorkspaceFormatKind.PLAIN, workspaceFormatKind("a.text"))
        assertNull(workspaceFormatKind("a.srt"))
        assertNull(workspaceFormatKind("a.kt"))
        assertNull(workspaceFormatKind("README"))
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
