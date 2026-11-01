package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceDeviceMountShellTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(runner: WorkspaceShellRunner = HostShellRunner()): Pair<WorkspaceManager, String> {
        val base = tmp.newFolder("ws")
        val mgr = WorkspaceManager(baseDir = base, shellRunner = runner)
        mgr.ensureWorkspace("w1")
        return mgr to "w1"
    }

    @Test
    fun `device mount maps to device alias guest path and required bind`() {
        val src = tmp.newFolder("src")
        val m = WorkspaceDeviceMount("id1", "docs", src)
        assertEquals("/device/docs", m.guestPath)
        val bind = m.toBindMount()
        assertEquals("/device/docs", bind.target)
        assertTrue(bind.required)
    }

    @Test
    fun `proot launcher fails on missing required source but skips optional`() {
        val files = tmp.newFolder("files")
        val linux = tmp.newFolder("linux")
        val proot = tmp.newFile("proot")
        val missing = File(tmp.root, "gone")

        val args = ProotLauncher.buildCommand(
            proot = proot, filesDir = files, linuxDir = linux,
            bindMounts = listOf(WorkspaceBindMount(missing, "/skills")),
        )
        assertTrue("/skills" !in args.joinToString(" "))

        assertThrows(IllegalStateException::class.java) {
            ProotLauncher.buildCommand(
                proot = proot, filesDir = files, linuxDir = linux,
                bindMounts = listOf(WorkspaceBindMount(missing, "/device/docs", required = true)),
            )
        }
    }

    @Test
    fun `resolveRootfsPath rejects unconfigured device namespace without fallback`() {
        val (mgr, root) = manager()
        assertThrows(IllegalArgumentException::class.java) {
            mgr.resolveRootfsPath(root, "/device/docs/a.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            mgr.resolveRootfsPath(root, "/device")
        }
    }

    @Test
    fun `resolveRootfsPath maps device alias and rejects unknown alias`() {
        val (mgr, root) = manager()
        val src = tmp.newFolder("src")
        val m = WorkspaceDeviceMount("id1", "docs", src)
        val loc = mgr.resolveRootfsPath(root, "/device/docs/sub/f.txt", deviceMounts = listOf(m))
        assertEquals(src.canonicalFile, loc.rootDir)
        assertEquals("sub/f.txt", loc.relativePath)
        assertThrows(IllegalStateException::class.java) {
            mgr.resolveRootfsPath(root, "/device/other/x", deviceMounts = listOf(m))
        }
    }

    @Test
    fun `resolveRootfsPath errors when device source disappears`() {
        val (mgr, root) = manager()
        val src = tmp.newFolder("src")
        val m = WorkspaceDeviceMount("id1", "docs", src)
        src.deleteRecursively()
        assertThrows(IllegalArgumentException::class.java) {
            mgr.resolveRootfsPath(root, "/device/docs/f.txt", deviceMounts = listOf(m))
        }
    }

    @Test
    fun `non-device rootfs resolution still falls back to linux dir`() {
        val (mgr, root) = manager()
        val loc = mgr.resolveRootfsPath(root, "/etc/hostname")
        assertEquals(mgr.linuxDir(root), loc.rootDir)
        assertEquals("etc/hostname", loc.relativePath)
    }

    @Test
    fun `host shell runner refuses device mounts instead of bare fallback`() {
        val (mgr, root) = manager()
        val m = WorkspaceDeviceMount("id1", "docs", tmp.newFolder("src"))
        assertThrows(IllegalArgumentException::class.java) {
            mgr.executeCommand(root, "ls", deviceMounts = listOf(m))
        }
        // 无设备挂载时旧行为不受影响
        val res = mgr.executeCommand(root, "echo hi")
        assertEquals(0, res.exitCode)
        assertEquals("hi", res.stdout.trim())
    }

    @Test
    fun `bind mount rejects colon and nul in source or target`() {
        val src = tmp.newFolder("src")
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBindMount(src, "not-absolute")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBindMount(src, "/x:y")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBindMount(File("/tmp/a\u0000b"), "/ok")
        }
    }
}
