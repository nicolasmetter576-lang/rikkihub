package me.rerere.rikkihub.ui.pages.extensions.workspace

import kotlinx.serialization.json.Json
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.applyDisplaySettingsPatch
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceEditorToolbarItem.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEditorToolbarTest {
    @Test fun emptyOrderIsTheDefaultOrder() {
        assertEquals(WorkspaceEditorToolbarItem.entries, workspaceEditorToolbarOrder(emptyList()))
        // Writing buttons first, then the Markdown format buttons, then Undo/Redo.
        assertEquals(listOf(TUIQIAO, ZAIZAO, QUBAGU, BEATS, FIND, FORMAT, HEADING, BOLD, ITALIC, LIST, QUOTE, TASK, LINK, CODE, TABLE, UNDO, REDO, HISTORY),
            WorkspaceEditorToolbarItem.entries.toList())
    }

    @Test fun storedOrderComesFirstAndMissingButtonsAreAppendedInDefaultOrder() {
        val order = workspaceEditorToolbarOrder(listOf("undo", "table", "bold"))
        assertEquals(listOf(UNDO, TABLE, BOLD, TUIQIAO, ZAIZAO, QUBAGU, BEATS, FIND, FORMAT, HEADING, ITALIC, LIST, QUOTE, TASK, LINK, CODE, REDO, HISTORY), order)
        // An order saved before the writing buttons existed keeps its buttons first and gains the new ones at the end.
        val saved = listOf("heading", "bold", "italic", "list", "quote", "task", "link", "code", "table", "undo", "redo")
        assertEquals(listOf(TUIQIAO, ZAIZAO, QUBAGU, BEATS, FIND, FORMAT, HISTORY), workspaceEditorToolbarOrder(saved).takeLast(7))
        // One saved before 八股, 节拍 and 历史 existed: they come last.
        val beforeQubagu = saved + listOf("tuiqiao", "zaizao", "find", "format")
        assertEquals(listOf(QUBAGU, BEATS, HISTORY), workspaceEditorToolbarOrder(beforeQubagu).takeLast(3))
        // One saved before 历史 existed: every stored button keeps its place, 历史 is added last.
        val beforeHistory = WorkspaceEditorToolbarItem.entries.filter { it != HISTORY }.reversed().map { it.id }
        assertEquals(beforeHistory + "history", workspaceEditorToolbarOrder(beforeHistory).map { it.id })
    }

    @Test fun switchedOffButtonsAreNotShownAndLaterButtonsStillAre() {
        // Hidden ids drop out; the rest keep the stored order.
        assertEquals(listOf(UNDO, BOLD), workspaceEditorToolbarShown(listOf("undo", "table", "bold"), listOf("table")).take(2))
        assertFalse(TABLE in workspaceEditorToolbarShown(emptyList(), listOf("table", "nope")))
        // The setting lists the hidden, not the shown: a button the stored order lacks (added later) still shows.
        val saved = listOf("heading", "bold", "italic", "list", "quote", "task", "link", "code", "table", "undo", "redo")
        assertEquals(listOf(TUIQIAO, ZAIZAO, QUBAGU, BEATS, FIND, FORMAT, HISTORY),
            workspaceEditorToolbarShown(saved, listOf("bold")).takeLast(7))
        // Everything switched off leaves an empty toolbar, laid out as one empty row.
        val none = workspaceEditorToolbarShown(emptyList(), WorkspaceEditorToolbarItem.entries.map { it.id })
        assertEquals(listOf(emptyList<WorkspaceEditorToolbarItem>()), workspaceEditorToolbarRows(none, multiRow = true, perRow = 8))
    }

    @Test fun unknownAndRepeatedIdsAreIgnored() {
        val order = workspaceEditorToolbarOrder(listOf("redo", "nope", "redo", "HEADING", "code"))
        assertEquals(REDO, order[0])
        assertEquals(CODE, order[1])
        assertEquals(WorkspaceEditorToolbarItem.entries.size, order.size)
        assertEquals(WorkspaceEditorToolbarItem.entries.toSet(), order.toSet())
    }

    @Test fun idsAreStable() {
        assertEquals(listOf("tuiqiao", "zaizao", "qubagu", "beats", "find", "format", "heading", "bold", "italic", "list", "quote", "task", "link", "code", "table", "undo", "redo", "history"),
            WorkspaceEditorToolbarItem.entries.map { it.id })
    }

    @Test fun singleRowKeepsEverythingOnOneRow() {
        val items = WorkspaceEditorToolbarItem.entries
        assertEquals(listOf(items), workspaceEditorToolbarRows(items, multiRow = false, perRow = 8))
    }

    @Test fun multiRowCutsInOrderByButtonsPerRow() {
        val items = WorkspaceEditorToolbarItem.entries
        // 18 buttons, 8 a row: two full rows and two left over.
        val rows = workspaceEditorToolbarRows(items, multiRow = true, perRow = 8)
        assertEquals(listOf(TUIQIAO, ZAIZAO, QUBAGU, BEATS, FIND, FORMAT, HEADING, BOLD), rows[0])
        assertEquals(listOf(ITALIC, LIST, QUOTE, TASK, LINK, CODE, TABLE, UNDO), rows[1])
        // 5 a row: three full rows and three left over.
        assertEquals(listOf(5, 5, 5, 3), workspaceEditorToolbarRows(items, multiRow = true, perRow = 5).map { it.size })
        // A txt file's 6 buttons fit one row of 8.
        assertEquals(listOf(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT, UNDO, REDO)),
            workspaceEditorToolbarRows(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT, UNDO, REDO), multiRow = true, perRow = 8))
    }

    @Test fun buttonsPerRowIsClampedToThreeThroughEighteen() {
        val items = WorkspaceEditorToolbarItem.entries
        assertEquals(listOf(3, 3, 3, 3, 3, 3), workspaceEditorToolbarRows(items, multiRow = true, perRow = 1).map { it.size })
        assertEquals(listOf(18), workspaceEditorToolbarRows(items, multiRow = true, perRow = 99).map { it.size })
        assertEquals(listOf(emptyList<WorkspaceEditorToolbarItem>()), workspaceEditorToolbarRows(emptyList(), multiRow = true, perRow = 8))
    }

    @Test fun buttonsFollowTheFileType() {
        val markdown = WorkspaceEditorToolbarActions(true, WorkspaceFormatKind.MARKDOWN, onTuiqiao = { _, _ -> }, onZaizao = { _, _ -> }, qubagu = qubagu(active = false), onBeats = {}, onHistory = {})
        assertEquals(WorkspaceEditorToolbarItem.entries, WorkspaceEditorToolbarItem.entries.filter { it.availableFor(markdown) })
        // A txt file: writing buttons, find, format, undo/redo — no Markdown formatting.
        val plain = WorkspaceEditorToolbarActions(false, WorkspaceFormatKind.PLAIN, onTuiqiao = { _, _ -> }, onZaizao = { _, _ -> }, qubagu = qubagu(active = false))
        assertEquals(listOf(TUIQIAO, ZAIZAO, QUBAGU, FIND, FORMAT, UNDO, REDO), WorkspaceEditorToolbarItem.entries.filter { it.availableFor(plain) })
        // 历史 only for a book's file (the editor passes onHistory then), whatever its type.
        assertTrue(HISTORY.availableFor(WorkspaceEditorToolbarActions(false, WorkspaceFormatKind.PLAIN, onHistory = {})))
        // 八股 needs an editable text file whose autosave works, like 推敲
        val noQubagu = WorkspaceEditorToolbarActions(false, WorkspaceFormatKind.PLAIN, onTuiqiao = { _, _ -> }, onZaizao = { _, _ -> })
        assertFalse(QUBAGU.availableFor(noQubagu))
        // Code, or 推敲 off for the type (and the device-directory editor): find and undo/redo only.
        val code = WorkspaceEditorToolbarActions(false, null)
        assertEquals(listOf(FIND, UNDO, REDO), WorkspaceEditorToolbarItem.entries.filter { it.availableFor(code) })
    }

    private fun qubagu(active: Boolean) = WorkspaceEditorQubagu(active = active, scanning = false, marks = emptyList(), onToggle = {}, onOpen = {})

    @Test fun shownMarksLockTheEditingButtons() {
        assertFalse(WorkspaceEditorToolbarActions(true, WorkspaceFormatKind.MARKDOWN, qubagu = qubagu(active = false)).locked)
        assertTrue(WorkspaceEditorToolbarActions(true, WorkspaceFormatKind.MARKDOWN, qubagu = qubagu(active = true)).locked)
        assertFalse(WorkspaceEditorToolbarActions(true, WorkspaceFormatKind.MARKDOWN).locked)
    }

    @Test fun displaySettingDefaultsAndLegacyJson() {
        val legacy = Json.decodeFromString<DisplaySetting>("{}")
        assertTrue(legacy.workspaceEditorAutoPair)
        assertTrue(legacy.webWorkspaceEditorAutoPair)
        assertFalse(legacy.workspaceEditorToolbarDoubleRow)
        assertEquals(8, legacy.workspaceEditorToolbarPerRow)
        assertFalse(legacy.workspaceEditorToolbarAtBottom)
        assertEquals(emptyList<String>(), legacy.workspaceEditorToolbarOrder)
        assertEquals(emptyList<String>(), legacy.workspaceEditorToolbarHidden)
        // Settings without a stored size get the mini toolbar (user decision).
        assertEquals("mini", legacy.workspaceEditorToolbarSize)
        val setting = DisplaySetting(workspaceEditorToolbarDoubleRow = true, workspaceEditorToolbarOrder = listOf("undo", "redo"),
            workspaceEditorToolbarSize = "mini", workspaceEditorToolbarPerRow = 5, workspaceEditorToolbarAtBottom = true,
            workspaceEditorToolbarHidden = listOf("task", "table"))
        assertEquals(setting, Json.decodeFromString<DisplaySetting>(Json.encodeToString(setting)))
    }

    @Test fun toolbarChangesArePatchedFieldByField() {
        val stale = DisplaySetting()
        val latest = stale.copy(workspaceMarkdownHeadingSizes = true)
        val next = applyDisplaySettingsPatch(latest, stale,
            stale.copy(workspaceEditorToolbarDoubleRow = true, workspaceEditorToolbarOrder = listOf("table"), workspaceEditorToolbarSize = "standard"))
        assertTrue(next.workspaceMarkdownHeadingSizes)
        assertEquals("standard", next.workspaceEditorToolbarSize)
        assertTrue(next.workspaceEditorToolbarDoubleRow)
        assertEquals(listOf("table"), next.workspaceEditorToolbarOrder)
        // Switching a button off on one screen keeps an order saved meanwhile on another.
        val hiddenNext = applyDisplaySettingsPatch(next, stale, stale.copy(workspaceEditorToolbarHidden = listOf("code")))
        assertEquals(listOf("code"), hiddenNext.workspaceEditorToolbarHidden)
        assertEquals(listOf("table"), hiddenNext.workspaceEditorToolbarOrder)
    }
}
