package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * 选中工作目录后模型只看得到该目录。文件工具一侧（resolveRootfsPath）是硬边界；
 * PRoot 一侧只验证参数拼装（真实进程里 `ls /workspace` 等需真机验证）。
 */
class WorkspaceFilesScopeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root = "ws"
    private lateinit var manager: WorkspaceManager
    private lateinit var files: File
    private var captured: WorkspaceShellContext? = null

    private fun setUp() {
        manager = WorkspaceManager(
            baseDir = tmp.newFolder("workspaces"),
            shellRunner = object : WorkspaceShellRunner {
                override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                    captured = context
                    return WorkspaceCommandResult(0, "", "")
                }
            },
            bindMounts = listOf(WorkspaceBindMount(tmp.newFolder("skills"), "/skills")),
        ).also { it.ensureWorkspace(root) }
        files = manager.filesDir(root)
        File(files, "a/b").mkdirs()
        File(files, "a/b/in.txt").writeText("inside")
        File(files, "a/sibling.txt").writeText("secret")
        File(files, "other").mkdirs()
        File(files, "other/secret.txt").writeText("secret")
        File(files, "top.txt").writeText("secret")
    }

    private fun outside(block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertEquals("${WorkspaceFilesScope.OUTSIDE_MESSAGE} /workspace/a/b", error.message)
    }

    @Test
    fun `normalize accepts guest and relative forms and rejects traversal`() {
        assertEquals("a/b", WorkspaceFilesScope.normalize("/workspace/a/b/"))
        assertEquals("a/b", WorkspaceFilesScope.normalize("a//./b"))
        assertNull(WorkspaceFilesScope.normalize("/workspace"))
        assertNull(WorkspaceFilesScope.normalize(""))
        assertNull(WorkspaceFilesScope.normalize(null))
        for (bad in listOf("/workspace/../etc", "a/../b", "/etc", "/workspacex/a", "a\\b", "a\u0000b")) {
            assertThrows(bad, IllegalArgumentException::class.java) { WorkspaceFilesScope.normalize(bad) }
        }
        assertTrue(WorkspaceFilesScope.contains("a/b", "a/b/c"))
        assertFalse(WorkspaceFilesScope.contains("a/b", "a/bc"))
    }

    @Test
    fun `scoped paths resolve only inside the selected directory`() {
        setUp()
        val inside = manager.resolveRootfsPath(root, "/workspace/a/b/in.txt", filesScope = "a/b")
        assertEquals(File(files, "a/b").canonicalFile, inside.rootDir)
        assertEquals("in.txt", inside.relativePath)
        assertEquals("", manager.resolveRootfsPath(root, "/workspace/a/b", filesScope = "a/b").relativePath)
        assertEquals(6L, manager.rootfsFileSize(root, "/workspace/a/b/in.txt", filesScope = "a/b"))

        // Existing and missing outside paths fail identically.
        for (path in listOf("/workspace", "/workspace/a", "/workspace/a/sibling.txt", "/workspace/other/secret.txt",
            "/workspace/top.txt", "/workspace/missing.txt", "/workspace/a/bc")) {
            outside { manager.resolveRootfsPath(root, path, filesScope = "a/b") }
        }
        outside { manager.listRootfs(root, "/workspace", filesScope = "a/b") }
        outside { manager.writeRootfsText(root, "/workspace/other/new.txt", "x", overwrite = true, filesScope = "a/b") }
        assertFalse(File(files, "other/new.txt").exists())

        // Rootfs system paths stay available; unscoped behavior is unchanged.
        assertEquals(manager.linuxDir(root), manager.resolveRootfsPath(root, "/etc/hosts", filesScope = "a/b").rootDir)
        assertEquals(files, manager.resolveRootfsPath(root, "/workspace/other/secret.txt").rootDir)
    }

    @Test
    fun `writes land in the selected directory`() {
        setUp()
        manager.writeRootfsText(root, "/workspace/a/b/new.txt", "hello", overwrite = true, filesScope = "a/b")
        assertEquals("hello", File(files, "a/b/new.txt").readText())
        val out = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/workspace/a/b/new.txt", out, filesScope = "a/b")
        assertEquals("hello", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `symlinks cannot escape the selected directory`() {
        setUp()
        Files.createSymbolicLink(File(files, "a/b/escape").toPath(), File(files, "other/secret.txt").toPath())
        assertThrows(Exception::class.java) { manager.rootfsFileSize(root, "/workspace/a/b/escape", filesScope = "a/b") }

        // A scope whose own path goes through a symlink is unavailable, not followed.
        Files.createSymbolicLink(File(files, "link").toPath(), File(files, "other").toPath())
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.resolveRootfsPath(root, "/workspace/link/secret.txt", filesScope = "link")
        }
        assertEquals(WorkspaceFilesScope.UNAVAILABLE_MESSAGE, error.message)
    }

    @Test
    fun `deleted scope fails closed instead of widening`() {
        setUp()
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.resolveRootfsPath(root, "/workspace/gone/x", filesScope = "gone")
        }
        assertEquals(WorkspaceFilesScope.UNAVAILABLE_MESSAGE, error.message)
        assertThrows(IllegalArgumentException::class.java) { manager.executeCommand(root, "ls", filesScope = "gone") }
    }

    @Test
    fun `commands start in the scope and reject an outside cwd`() {
        setUp()
        manager.executeCommand(root, "ls", filesScope = "a/b")
        assertEquals("a/b", captured!!.cwd)
        assertEquals("a/b", captured!!.filesScope)
        manager.executeCommand(root, "ls", cwd = "/workspace/a/b", filesScope = "a/b")
        assertEquals("a/b", captured!!.cwd)
        outside { manager.executeCommand(root, "ls", cwd = "other", filesScope = "a/b") }
        outside { manager.executeCommand(root, "ls", cwd = "a", filesScope = "a/b") }
        manager.executeCommand(root, "ls", cwd = "other")
        assertNull(captured!!.filesScope)
    }

    @Test
    fun `proot binds an empty shell at workspace and only the scope inside it`() {
        setUp()
        val linux = tmp.newFolder("linux")
        val proot = tmp.newFile("proot")
        val temp = manager.tempDir(root).apply { mkdirs() }
        val binds = WorkspaceFilesScope.workspaceBinds(files, temp, "a/b")
        val args = ProotLauncher.buildCommand(
            proot = proot, filesDir = files, linuxDir = linux, cwd = "/workspace/a/b", workspaceBinds = binds,
        )
        val joined = args.joinToString(" ")
        assertFalse(joined.contains("${files.absolutePath}:/workspace "))
        val shell = binds[0].source
        assertTrue(joined.contains("${shell.absolutePath}:/workspace"))
        assertTrue(joined.contains("${File(files, "a/b").canonicalPath}:/workspace/a/b"))
        assertEquals("/workspace/a/b", args[args.indexOf("-w") + 1])
        // The shell holds only the directory chain to the scope.
        assertEquals(listOf("a"), shell.list()!!.toList())
        assertEquals(listOf("b"), File(shell, "a").list()!!.toList())

        // Unscoped command line is unchanged.
        val legacy = ProotLauncher.buildCommand(proot = proot, filesDir = files, linuxDir = linux).joinToString(" ")
        assertTrue(legacy.contains("${files.absolutePath}:/workspace"))
    }

    @Test
    fun `bare host shell refuses a scoped command`() {
        val dir = tmp.newFolder("host")
        val context = WorkspaceShellContext(
            root = root, command = "ls", cwd = "", filesDir = dir, linuxDir = dir, tempDir = dir,
            workingDir = dir, timeoutMillis = 1_000, filesScope = "a",
        )
        assertThrows(IllegalArgumentException::class.java) { HostShellRunner().execute(context) }
    }
}
