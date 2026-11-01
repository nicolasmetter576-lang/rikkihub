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
        assertEquals(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT, HEADING, BOLD, ITALIC, LIST, QUOTE, TASK, LINK, CODE, TABLE, UNDO, REDO),
            WorkspaceEditorToolbarItem.entries.toList())
    }

    @Test fun storedOrderComesFirstAndMissingButtonsAreAppendedInDefaultOrder() {
        val order = workspaceEditorToolbarOrder(listOf("undo", "table", "bold"))
        assertEquals(listOf(UNDO, TABLE, BOLD, TUIQIAO, ZAIZAO, FIND, FORMAT, HEADING, ITALIC, LIST, QUOTE, TASK, LINK, CODE, REDO), order)
        // An order saved before the writing buttons existed keeps its buttons first and gains the new ones at the end.
        val saved = listOf("heading", "bold", "italic", "list", "quote", "task", "link", "code", "table", "undo", "redo")
        assertEquals(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT), workspaceEditorToolbarOrder(saved).takeLast(4))
    }

    @Test fun unknownAndRepeatedIdsAreIgnored() {
        val order = workspaceEditorToolbarOrder(listOf("redo", "nope", "redo", "HEADING", "code"))
        assertEquals(REDO, order[0])
        assertEquals(CODE, order[1])
        assertEquals(WorkspaceEditorToolbarItem.entries.size, order.size)
        assertEquals(WorkspaceEditorToolbarItem.entries.toSet(), order.toSet())
    }

    @Test fun idsAreStable() {
        assertEquals(listOf("tuiqiao", "zaizao", "find", "format", "heading", "bold", "italic", "list", "quote", "task", "link", "code", "table", "undo", "redo"),
            WorkspaceEditorToolbarItem.entries.map { it.id })
    }

    @Test fun singleRowKeepsEverythingOnOneRow() {
        val items = WorkspaceEditorToolbarItem.entries
        assertEquals(listOf(items), workspaceEditorToolbarRows(items, multiRow = false, perRow = 8))
    }

    @Test fun multiRowCutsInOrderByButtonsPerRow() {
        val items = WorkspaceEditorToolbarItem.entries
        // 15 buttons, 8 a row: 8 + 7 (the old double-row split).
        val rows = workspaceEditorToolbarRows(items, multiRow = true, perRow = 8)
        assertEquals(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT, HEADING, BOLD, ITALIC, LIST), rows[0])
        assertEquals(listOf(QUOTE, TASK, LINK, CODE, TABLE, UNDO, REDO), rows[1])
        // 5 a row: three full rows.
        assertEquals(listOf(5, 5, 5), workspaceEditorToolbarRows(items, multiRow = true, perRow = 5).map { it.size })
        // A txt file's 6 buttons fit one row of 8.
        assertEquals(listOf(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT, UNDO, REDO)),
            workspaceEditorToolbarRows(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT, UNDO, REDO), multiRow = true, perRow = 8))
    }

    @Test fun buttonsPerRowIsClampedToThreeThroughFifteen() {
        val items = WorkspaceEditorToolbarItem.entries
        assertEquals(listOf(3, 3, 3, 3, 3), workspaceEditorToolbarRows(items, multiRow = true, perRow = 1).map { it.size })
        assertEquals(listOf(15), workspaceEditorToolbarRows(items, multiRow = true, perRow = 99).map { it.size })
        assertEquals(listOf(emptyList<WorkspaceEditorToolbarItem>()), workspaceEditorToolbarRows(emptyList(), multiRow = true, perRow = 8))
    }

    @Test fun buttonsFollowTheFileType() {
        val markdown = WorkspaceEditorToolbarActions(true, WorkspaceFormatKind.MARKDOWN, onTuiqiao = { _, _ -> }, onZaizao = { _, _ -> })
        assertEquals(WorkspaceEditorToolbarItem.entries, WorkspaceEditorToolbarItem.entries.filter { it.availableFor(markdown) })
        // A txt file: writing buttons, find, format, undo/redo — no Markdown formatting.
        val plain = WorkspaceEditorToolbarActions(false, WorkspaceFormatKind.PLAIN, onTuiqiao = { _, _ -> }, onZaizao = { _, _ -> })
        assertEquals(listOf(TUIQIAO, ZAIZAO, FIND, FORMAT, UNDO, REDO), WorkspaceEditorToolbarItem.entries.filter { it.availableFor(plain) })
        // Code, or 推敲 off for the type (and the device-directory editor): find and undo/redo only.
        val code = WorkspaceEditorToolbarActions(false, null)
        assertEquals(listOf(FIND, UNDO, REDO), WorkspaceEditorToolbarItem.entries.filter { it.availableFor(code) })
    }

    @Test fun displaySettingDefaultsAndLegacyJson() {
        val legacy = Json.decodeFromString<DisplaySetting>("{}")
        assertTrue(legacy.workspaceEditorAutoPair)
        assertTrue(legacy.webWorkspaceEditorAutoPair)
        assertFalse(legacy.workspaceEditorToolbarDoubleRow)
        assertEquals(8, legacy.workspaceEditorToolbarPerRow)
        assertFalse(legacy.workspaceEditorToolbarAtBottom)
        assertEquals(emptyList<String>(), legacy.workspaceEditorToolbarOrder)
        // Settings without a stored size get the mini toolbar (user decision).
        assertEquals("mini", legacy.workspaceEditorToolbarSize)
        val setting = DisplaySetting(workspaceEditorToolbarDoubleRow = true, workspaceEditorToolbarOrder = listOf("undo", "redo"),
            workspaceEditorToolbarSize = "mini", workspaceEditorToolbarPerRow = 5, workspaceEditorToolbarAtBottom = true)
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
    }
}
