package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceTransferItemResult
import me.rerere.workspace.WorkspaceTransferMode.COPY
import me.rerere.workspace.WorkspaceTransferMode.MOVE
import me.rerere.workspace.WorkspaceTransferProgress
import me.rerere.workspace.WorkspaceTransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileMoveTest {

    @Test
    fun `moves into another folder of the same workspace`() {
        assertTrue(workspaceTransferAllowed("a.md", sameWorkspace = true, "docs", MOVE))
        assertTrue(workspaceTransferAllowed("src/notes", sameWorkspace = true, "docs/sub", MOVE))
        assertTrue(workspaceTransferAllowed("docs/a.md", sameWorkspace = true, "", MOVE))
        // "src2" 只是名字以 "src" 开头，不在 src 里面
        assertTrue(workspaceTransferAllowed("src", sameWorkspace = true, "src2", MOVE))
    }

    @Test
    fun `moving into the folder it is already in is disabled, copying there makes a duplicate`() {
        assertFalse(workspaceTransferAllowed("a.md", sameWorkspace = true, "", MOVE))
        assertFalse(workspaceTransferAllowed("docs/a.md", sameWorkspace = true, "docs", MOVE))
        assertFalse(workspaceTransferAllowed("docs/a.md", sameWorkspace = true, "/docs/", MOVE))
        assertTrue(workspaceTransferAllowed("docs/a.md", sameWorkspace = true, "docs", COPY))
        assertTrue(workspaceTransferAllowed("a.md", sameWorkspace = true, "", COPY))
    }

    @Test
    fun `a folder never goes into itself, moving or copying`() {
        for (mode in listOf(MOVE, COPY)) {
            assertFalse(workspaceTransferAllowed("src", sameWorkspace = true, "src", mode))
            assertFalse(workspaceTransferAllowed("src", sameWorkspace = true, "src/inner", mode))
            assertFalse(workspaceTransferAllowed("a/src", sameWorkspace = true, "a/src/inner/deep", mode))
        }
    }

    @Test
    fun `another workspace accepts any folder, including the same path`() {
        assertTrue(workspaceTransferAllowed("docs/a.md", sameWorkspace = false, "docs", MOVE))
        assertTrue(workspaceTransferAllowed("src", sameWorkspace = false, "src/inner", MOVE))
        assertTrue(workspaceTransferAllowed("a.md", sameWorkspace = false, "", COPY))
    }

    @Test
    fun `the root itself is never a source`() {
        assertFalse(workspaceTransferAllowed("", sameWorkspace = true, "docs", MOVE))
        assertFalse(workspaceTransferAllowed("/", sameWorkspace = false, "docs", COPY))
    }

    @Test
    fun `conflict count looks at names already in the destination`() {
        assertEquals(2, workspaceTransferConflictCount(listOf("a.md", "b.md", "dir"), setOf("a.md", "dir", "z")))
        assertEquals(0, workspaceTransferConflictCount(listOf("a.md"), emptySet()))
    }

    @Test
    fun `progress fraction adds the current item's bytes`() {
        assertEquals(0f, workspaceTransferFraction(WorkspaceTransferProgress(0, 4, "a")), 0f)
        assertEquals(0.625f, workspaceTransferFraction(WorkspaceTransferProgress(2, 4, "c", bytesDone = 50, bytesTotal = 100)), 1e-6f)
        assertEquals(1f, workspaceTransferFraction(WorkspaceTransferProgress(3, 4, "d", bytesDone = 9, bytesTotal = 9)), 0f)
        assertEquals(0f, workspaceTransferFraction(WorkspaceTransferProgress(0, 0, "")), 0f)
    }

    @Test
    fun `summary counts renamed items, skipped links and failures`() {
        val summary = workspaceTransferSummary(
            WorkspaceTransferResult(
                listOf(
                    WorkspaceTransferItemResult("a.md", target = "x/a.md"),
                    WorkspaceTransferItemResult("b.md", target = "x/b (1).md", renamed = true, skippedLinks = 1),
                    WorkspaceTransferItemResult("dir", target = "x/dir", isDirectory = true, skippedLinks = 2),
                    WorkspaceTransferItemResult("gone.md", errorCode = "FILE_NOT_FOUND", error = "Does not exist"),
                ),
            ),
        )
        assertEquals(3, summary.succeeded)
        assertEquals(1, summary.renamed)
        assertEquals(3, summary.skippedLinks)
        assertEquals(listOf("gone.md"), summary.failures.map { it.source })
    }
}
