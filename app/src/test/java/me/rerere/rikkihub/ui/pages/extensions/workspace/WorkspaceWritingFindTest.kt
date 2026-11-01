package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceWritingFindTest {
    @Test fun `matches are non-overlapping and case folding is optional`() {
        assertArrayEquals(intArrayOf(0, 2), workspaceFindMatches("aaaa", "aa", caseSensitive = false))
        assertArrayEquals(intArrayOf(0, 4), workspaceFindMatches("Cat cat", "cat", caseSensitive = false))
        assertArrayEquals(intArrayOf(4), workspaceFindMatches("Cat cat", "cat", caseSensitive = true))
        assertArrayEquals(intArrayOf(), workspaceFindMatches("abc", "", caseSensitive = false))
        assertEquals(3, workspaceFindMatches("x".repeat(10), "x", caseSensitive = true, limit = 3).size)
    }

    @Test fun `current match follows the previous one`() {
        val matches = intArrayOf(2, 8, 15)
        assertEquals(1, workspaceFindCurrent(matches, 8))
        assertEquals(2, workspaceFindCurrent(matches, 9))
        assertEquals(0, workspaceFindCurrent(matches, 99))
        assertEquals(-1, workspaceFindCurrent(intArrayOf(), 0))
    }

    @Test fun `replace all is one undo step and moving to a match records none`() {
        val state = TextFieldState("他他她他")
        state.undoState.clearHistory()
        state.edit { selection = TextRange(1, 2) } // jumping to a match is a selection change only
        assertFalse(state.undoState.canUndo)
        val matches = workspaceFindMatches(state.text, "他", caseSensitive = true)
        state.edit {
            for (start in matches.reversed()) replace(start, start + 1, "她们")
        }
        assertEquals("她们她们她她们", state.text.toString())
        assertEquals("她们她们她她们", workspaceReplaceAll("他他她他", matches, 1, "她们"))
        state.undoState.undo()
        assertEquals("他他她他", state.text.toString())
        assertFalse(state.undoState.canUndo)
    }
}
