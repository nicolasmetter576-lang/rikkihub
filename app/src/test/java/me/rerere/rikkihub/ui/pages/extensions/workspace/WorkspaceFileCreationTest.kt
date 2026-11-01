package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.CancellationException
import me.rerere.workspace.WorkspaceFileAlreadyExistsException
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.*
import org.junit.Test

/** JVM-only completion/snapshot tests: no Android Context, NavController, or filesystem IO. */
class WorkspaceFileCreationTest {
    private val target = WorkspaceNewFileTarget("workspace-a", "existing/parent")

    @Test fun `defaults create untitled md not a directory`() {
        val request = target.request()
        assertEquals("untitled", request.name)
        assertEquals("md", request.extension)
        assertEquals("untitled.md", request.leafName)
        assertEquals("existing/parent/untitled.md", request.path)
        assertFalse(request.isDirectory)
    }

    @Test fun `empty extension means directory even if name has a dot`() {
        val directory = target.request("notes.md", "")
        assertTrue(directory.isDirectory)
        assertEquals("existing/parent/notes.md", directory.path)
    }

    @Test fun `compound extensions and unicode are preserved`() {
        val request = target.request("文档", "tar.gz")
        assertEquals("文档.tar.gz", request.leafName)
        assertEquals("café.md", target.request("café", "md").leafName)
        assertEquals("cafe\u0301.md", target.request("cafe\u0301", "md").leafName)
    }

    @Test fun `neither field is silently trimmed and invalid extensions are rejected`() {
        listOf(" name", "name ", "../name", ".l2s.private", "a/b", "a\\b", "\u0000").forEach { name ->
            assertRejected { target.request(name, "md") }
        }
        listOf(" md", "md ", ".md", "md.", "a/b", "\n", ".").forEach { extension ->
            assertRejected { target.request("name", extension) }
        }
        assertRejected { target.request("", "md") }
        assertRejected { target.request("a".repeat(254), "md") }
    }

    @Test fun `LINUX is rejected before submission`() {
        assertRejected { target.copy(area = WorkspaceStorageArea.LINUX).request() }
    }

    @Test fun `request freezes parent identity name and extension before later navigation`() {
        var location = target.copy(mountId = "mount-a")
        var name = "notes"
        var extension = "txt"
        val request = location.request(name, extension)
        location = location.copy(workspaceId = "workspace-b", mountId = "mount-b", parentPath = "elsewhere")
        name = "different"
        extension = ""
        assertEquals("workspace-a", request.target.workspaceId)
        assertEquals("mount-a", request.target.mountId)
        assertEquals("existing/parent/notes.txt", request.path)
        assertFalse(request.isDirectory)
        assertTrue(location.request(name, extension).isDirectory)
    }

    @Test fun `double click guard is synchronous before any coroutine launches`() {
        val creation = WorkspaceFileCreation()
        assertTrue(creation.begin(target.request()))
        assertTrue(creation.state.value.busy)
        assertFalse(creation.begin(target.request("another", "txt")))
        creation.failed(WorkspaceCreateError.ALREADY_EXISTS)
        creation.finish()
        assertFalse(creation.state.value.busy)
        assertTrue(creation.begin(target.request("another", "txt")))
        assertNull(creation.state.value.error)
    }

    @Test fun `invalid direct request cannot bypass shared validation`() {
        val creation = WorkspaceFileCreation()
        assertFalse(creation.begin(WorkspaceNewFileRequest(target, "name", " md")))
        assertEquals(WorkspaceCreateError.INVALID_NAME, creation.state.value.error)
        assertFalse(creation.state.value.busy)
        assertNull(creation.state.value.pendingOpen)
    }

    @Test fun `pending open survives finish and absence of foreground collectors`() {
        val creation = WorkspaceFileCreation()
        val request = target.request()
        assertTrue(creation.begin(request))
        creation.created(request)
        creation.finish()
        val event = requireNotNull(creation.state.value.pendingOpen)
        assertEquals(request, event.request)
        assertFalse(creation.state.value.busy)
        assertFalse(creation.begin(target.request("another", "md")))
        var navigationCount = 0
        creation.open(event) {
            // Event must not have been consumed before the navigation callback succeeds.
            assertEquals(event, creation.state.value.pendingOpen)
            navigationCount++
        }
        assertNull(creation.state.value.pendingOpen)
        creation.open(event) { navigationCount++ }
        assertEquals(1, navigationCount)
    }

    @Test fun `navigation failure is create success open failure and is not replayed`() {
        val creation = completedFile()
        val event = requireNotNull(creation.state.value.pendingOpen)
        creation.open(event) { throw IOException("/host/private/path") }
        assertEquals(WorkspaceCreateError.OPEN_FAILED, creation.state.value.error)
        assertNull(creation.state.value.pendingOpen)
        creation.open(event) { fail("Must not navigate twice") }
        creation.dismissError()
        assertNull(creation.state.value.error)
    }

    @Test fun `cancelled foreground delivery retains pending open for resumption`() {
        val creation = completedFile()
        val event = requireNotNull(creation.state.value.pendingOpen)
        try {
            creation.open(event) { throw CancellationException("backgrounded") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(event, creation.state.value.pendingOpen)
        assertNull(creation.state.value.error)
        creation.open(event) { }
        assertNull(creation.state.value.pendingOpen)
    }

    @Test fun `stale token cannot consume a later successful creation`() {
        val creation = completedFile()
        val old = requireNotNull(creation.state.value.pendingOpen)
        creation.open(old) { }
        val next = target.request("second", "txt")
        assertTrue(creation.begin(next))
        creation.created(next)
        creation.finish()
        val fresh = requireNotNull(creation.state.value.pendingOpen)
        assertNotEquals(old.token, fresh.token)
        creation.openFailed(old)
        creation.open(old) { fail("Stale event must not run") }
        assertEquals(fresh, creation.state.value.pendingOpen)
    }

    @Test fun `exclusive directory completion refreshes without an editor event`() {
        val creation = WorkspaceFileCreation()
        val request = target.request("new-folder", "")
        assertTrue(creation.begin(request))
        creation.created(request)
        creation.finish()
        assertNull(creation.state.value.pendingOpen)
        assertEquals("existing/parent/new-folder", creation.state.value.createdDirectoryPath)
        creation.dismissError()
        assertNull(creation.state.value.createdDirectoryPath)
    }

    @Test fun `typed errors never depend on raw host path messages`() {
        assertEquals(WorkspaceCreateError.ALREADY_EXISTS, workspaceCreateError(WorkspaceFileAlreadyExistsException()))
        assertEquals(WorkspaceCreateError.PERMISSION_DENIED, workspaceCreateError(AccessDeniedException("/host/private")))
        assertEquals(WorkspaceCreateError.PARENT_MISSING, workspaceCreateError(NoSuchFileException("/host/private")))
        assertEquals(WorkspaceCreateError.PARENT_MISSING, workspaceCreateError(WorkspaceFileOperationException(
            WorkspaceFileOperationException.PARENT_NOT_FOUND, "/host/private",
        )))
        assertEquals(WorkspaceCreateError.INVALID_NAME, workspaceCreateError(WorkspaceFileOperationException(
            WorkspaceFileOperationException.INVALID_FILE_NAME, "/host/private",
        )))
        assertEquals(WorkspaceCreateError.INVALID_PATH, workspaceCreateError(WorkspaceFileOperationException(
            WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED, "/host/private",
        )))
        assertEquals(WorkspaceCreateError.FAILED, workspaceCreateError(IOException("/host/private")))
    }

    private fun completedFile(): WorkspaceFileCreation = WorkspaceFileCreation().also {
        val request = target.request()
        assertTrue(it.begin(request))
        it.created(request)
        it.finish()
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected shared creation validation to reject the input")
        } catch (_: IllegalArgumentException) { }
    }
}
