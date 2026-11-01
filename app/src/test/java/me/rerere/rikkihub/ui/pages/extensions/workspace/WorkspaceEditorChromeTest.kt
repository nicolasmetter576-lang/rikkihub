package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceEditorChromeTest {
    @Test fun toolbarSizeIdsAreStableAndUnknownReadsAsMini() {
        assertEquals(listOf("standard", "compact", "mini"), WorkspaceEditorToolbarSize.entries.map { it.id })
        assertEquals(WorkspaceEditorToolbarSize.COMPACT, WorkspaceEditorToolbarSize.of("compact"))
        assertEquals(WorkspaceEditorToolbarSize.MINI, WorkspaceEditorToolbarSize.of("huge"))
        assertEquals(WorkspaceEditorToolbarSize.MINI, WorkspaceEditorToolbarSize.of(""))
        // Standard is the toolbar as it was before sizes: 48dp rows.
        assertEquals(48f, WorkspaceEditorToolbarSize.STANDARD.rowHeight.value)
    }
}
