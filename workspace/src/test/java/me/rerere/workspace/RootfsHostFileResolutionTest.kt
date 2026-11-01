package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * `resolveRootfsFile` is what lets the app show an image the model wrote about by guest path. It must
 * see the workspace exactly as the model's file tools do and never hand back a file outside it.
 */
class RootfsHostFileResolutionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root = "ws"
    private lateinit var manager: WorkspaceManager
    private lateinit var files: File
    private lateinit var upload: File

    private fun setUp() {
        upload = tmp.newFolder("upload")
        manager = WorkspaceManager(
            baseDir = tmp.newFolder("workspaces"),
            bindMounts = listOf(WorkspaceBindMount(upload, "/upload")),
        ).also { it.ensureWorkspace(root) }
        files = manager.filesDir(root)
        File(files, "a/b").mkdirs()
        File(files, "a/b/in.png").writeText("inside")
        File(files, "a/sibling.png").writeText("sibling")
        File(files, "top.png").writeText("top")
        File(files, "my pic.png").writeText("spaced")
        File(upload, "photo.jpg").writeText("uploaded")
        File(manager.linuxDir(root), "tmp").mkdirs()
        File(manager.linuxDir(root), "tmp/plot.png").writeText("plot")
    }

    @Test
    fun `guest paths map to host files in every area the model can see`() {
        setUp()
        assertEquals("top", manager.resolveRootfsFile(root, "/workspace/top.png").readText())
        assertEquals("spaced", manager.resolveRootfsFile(root, "/workspace/my pic.png").readText())
        assertEquals("plot", manager.resolveRootfsFile(root, "/tmp/plot.png").readText())
        val mounts = listOf(WorkspaceBindMount(upload, "/upload"))
        assertEquals("uploaded", manager.resolveRootfsFile(root, "/upload/photo.jpg", mounts).readText())
    }

    @Test
    fun `a missing file resolves to a path that does not exist`() {
        setUp()
        assertFalse(manager.resolveRootfsFile(root, "/workspace/nope.png").exists())
    }

    @Test
    fun `parent traversal is refused`() {
        setUp()
        assertThrows(IllegalArgumentException::class.java) {
            manager.resolveRootfsFile(root, "/workspace/../linux/tmp/plot.png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.resolveRootfsFile(root, "/workspace/a/../../x.png")
        }
    }

    @Test
    fun `a confined conversation only reaches its own directory`() {
        setUp()
        assertEquals("inside", manager.resolveRootfsFile(root, "/workspace/a/b/in.png", filesScope = "a/b").readText())
        for (outside in listOf("/workspace/a/sibling.png", "/workspace/top.png", "/workspace/nope.png")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                manager.resolveRootfsFile(root, outside, filesScope = "a/b")
            }
            assertEquals("${WorkspaceFilesScope.OUTSIDE_MESSAGE} /workspace/a/b", error.message)
        }
    }

    @Test
    fun `a link that leaves the workspace is refused`() {
        setUp()
        val secret = File(tmp.root, "secret.png").apply { writeText("secret") }
        Files.createSymbolicLink(File(files, "leak.png").toPath(), secret.toPath())
        Files.createSymbolicLink(File(files, "a/b/leak.png").toPath(), secret.toPath())

        assertThrows(IllegalArgumentException::class.java) { manager.resolveRootfsFile(root, "/workspace/leak.png") }
        assertThrows(IllegalArgumentException::class.java) {
            manager.resolveRootfsFile(root, "/workspace/a/b/leak.png", filesScope = "a/b")
        }
    }

    @Test
    fun `a link that stays inside the workspace still works`() {
        setUp()
        Files.createSymbolicLink(File(files, "alias.png").toPath(), File(files, "top.png").toPath())
        val resolved = manager.resolveRootfsFile(root, "/workspace/alias.png")
        assertTrue(resolved.isFile)
        assertEquals("top", resolved.readText())
    }
}
