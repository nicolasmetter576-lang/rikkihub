package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceFileSelectionTest {

    private fun entry(path: String, isDirectory: Boolean = false) = WorkspaceFileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = isDirectory,
        sizeBytes = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `toggle adds an unselected path and removes a selected one`() {
        val empty = emptySet<String>()
        val once = toggleWorkspacePathSelection(empty, "docs/a.txt")
        assertEquals(setOf("docs/a.txt"), once)

        val twice = toggleWorkspacePathSelection(once, "docs/b.txt")
        assertEquals(setOf("docs/a.txt", "docs/b.txt"), twice)

        val back = toggleWorkspacePathSelection(twice, "docs/a.txt")
        assertEquals(setOf("docs/b.txt"), back)
    }

    @Test
    fun `prune removes vanished entries and keeps survivors`() {
        val selected = linkedSetOf("a.txt", "gone.txt", "dir/b.txt")
        val entries = listOf(entry("a.txt"), entry("dir/b.txt"))
        val pruned = pruneWorkspaceSelection(selected, entries)
        assertEquals(setOf("a.txt", "dir/b.txt"), pruned)
        // iteration order follows the original selection, not the listing
        assertEquals(listOf("a.txt", "dir/b.txt"), pruned.toList())
    }

    @Test
    fun `prune returns the same instance when nothing changed`() {
        val selected = setOf("a.txt", "b.txt")
        val entries = listOf(entry("a.txt"), entry("b.txt"), entry("c.txt"))
        assertSame(selected, pruneWorkspaceSelection(selected, entries))
    }

    @Test
    fun `prune handles empty selection and empty listing`() {
        val empty = emptySet<String>()
        assertSame(empty, pruneWorkspaceSelection(empty, listOf(entry("a.txt"))))
        assertTrue(pruneWorkspaceSelection(setOf("a.txt"), emptyList()).isEmpty())
    }

    @Test
    fun `archive name for a single file strips its extension`() {
        assertEquals(
            "notes.zip",
            defaultWorkspaceArchiveName("ws", "docs", listOf("docs/notes.md")),
        )
    }

    @Test
    fun `archive name for a single directory keeps the folder name`() {
        assertEquals(
            "pictures.zip",
            defaultWorkspaceArchiveName("ws", "", listOf("pictures")),
        )
    }

    @Test
    fun `archive name for multiple entries uses the current directory`() {
        assertEquals(
            "docs.zip",
            defaultWorkspaceArchiveName("ws", "docs", listOf("docs/a.txt", "docs/b.txt")),
        )
    }

    @Test
    fun `archive name at storage root falls back to the workspace name`() {
        assertEquals(
            "My-Workspace.zip",
            defaultWorkspaceArchiveName("My Workspace", "", listOf("a.txt", "b.txt")),
        )
    }

    @Test
    fun `archive name falls back to workspace when nothing else is usable`() {
        assertEquals(
            "workspace.zip",
            defaultWorkspaceArchiveName(null, "", listOf("a.txt", "b.txt")),
        )
        // workspace name sanitizes to nothing usable
        assertEquals(
            "workspace.zip",
            defaultWorkspaceArchiveName("...", "", listOf("a.txt", "b.txt")),
        )
    }

    @Test
    fun `archive name sanitizes odd characters`() {
        assertEquals(
            "we-ird.zip",
            defaultWorkspaceArchiveName("ws", "docs", listOf("docs/we ird?.png")),
        )
    }

    @Test
    fun `pending snapshots survive java serialization like rememberSaveable`() {
        val folderImport = PendingFolderImport(
            area = WorkspaceStorageArea.LINUX,
            destinationPath = "root/sub",
        )
        val archiveExport = PendingArchiveExport(
            area = WorkspaceStorageArea.FILES,
            paths = ArrayList(listOf("a.txt", "dir")),
        )
        val batchDelete = PendingBatchDelete(
            area = WorkspaceStorageArea.LINUX,
            paths = ArrayList(listOf("dir", "a.txt")),
            includesDirectories = true,
        )
        assertEquals(folderImport, roundTrip(folderImport))
        assertEquals(archiveExport, roundTrip(archiveExport))
        assertEquals(batchDelete, roundTrip(batchDelete))
    }

    @Test
    fun `pending batch delete snapshot keeps its own area and paths`() {
        // 快照必须独立于发起时的可变选择集: 确认回调读快照而不是读当前选择
        val snapshot = PendingBatchDelete(
            area = WorkspaceStorageArea.FILES,
            paths = ArrayList(listOf("x.txt")),
            includesDirectories = false,
        )
        assertEquals(WorkspaceStorageArea.FILES, snapshot.area)
        assertEquals(listOf("x.txt"), snapshot.paths)
        assertEquals(false, snapshot.includesDirectories)
    }

    private inline fun <reified T : java.io.Serializable> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { it.writeObject(value) }
            bos.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as T }
    }
}
