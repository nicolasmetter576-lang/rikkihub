package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import me.rerere.rikkihub.data.book.history.HistoryEntry
import me.rerere.rikkihub.data.book.history.HistorySource
import org.junit.Assert.assertEquals
import org.junit.Test

/** 与网页端 `beforeAiEdits`（web-ui/tests/book.test.ts）同一组用例。 */
class BookHistoryUiStateTest {
    private val ai = HistorySource.Ai("c", "m")
    private val entries = listOf(
        HistoryEntry.Version(4, "a.md", HistorySource.Manual, "d"),
        HistoryEntry.Renamed(3, "a.md", HistorySource.Manual, from = "old.md"),
        HistoryEntry.Version(2, "old.md", ai, "b"),
        HistoryEntry.Version(1, "old.md", HistorySource.Initial, "a"),
    )

    private fun shown(writer: HistorySource?) = BookHistoryUiState(entries = entries, onlyBeforeAi = true).shown(writer).map { it.at }

    @Test
    fun `before AI edits keeps versions an AI write replaced, judging the newest by the current writer`() {
        assertEquals(listOf(4L, 1L), shown(ai))
        assertEquals(listOf(1L), shown(HistorySource.Manual))
        assertEquals(listOf(1L), shown(null))
        assertEquals(entries, BookHistoryUiState(entries = entries).shown(ai))
    }
}
