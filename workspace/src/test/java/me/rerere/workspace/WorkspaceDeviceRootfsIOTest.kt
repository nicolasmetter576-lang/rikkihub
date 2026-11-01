package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceDeviceRootfsIOTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `all ordinary rootfs file APIs use required device bind directly on original source`() {
        val manager = WorkspaceManager(temporary.newFolder("workspaces"))
        manager.ensureWorkspace("workspace")
        val source = temporary.newFolder("phone-documents")
        val mounts = listOf(WorkspaceDeviceMount("id", "docs", source).toBindMount())
        manager.writeRootfsText("workspace", "/device/docs/hello.txt", "original changed", true, mounts = mounts)
        assertEquals("original changed", File(source, "hello.txt").readText())
        assertEquals(16L, manager.rootfsFileSize("workspace", "/device/docs/hello.txt", mounts))
        val output = ByteArrayOutputStream()
        manager.exportRootfsFile("workspace", "/device/docs/hello.txt", output, mounts)
        assertEquals("original changed", output.toString("UTF-8"))
        assertEquals("/device/docs/hello.txt", manager.listRootfs("workspace", "/device/docs", mounts).single().path)
        assertEquals(true, manager.rootfsStat("workspace", "/device/docs/hello.txt", mounts).isFile)
    }

    @Test fun `device cwd instructions use original source and never disconnected rootfs trap`() {
        val manager = WorkspaceManager(temporary.newFolder("workspaces"))
        manager.ensureWorkspace("workspace")
        val source = temporary.newFolder("phone-documents")
        File(source, "project").mkdirs()
        File(source, "project/AGENTS.md").writeText("phone instructions")
        val mounts = listOf(WorkspaceDeviceMount("id", "docs", source).toBindMount())
        val guest = "/device/docs/project/AGENTS.md"
        val output = ByteArrayOutputStream()
        manager.exportRootfsFile("workspace", guest, output, mounts)
        assertEquals("phone instructions", output.toString("UTF-8"))
        File(manager.linuxDir("workspace"), "device/docs/project").mkdirs()
        File(manager.linuxDir("workspace"), "device/docs/project/AGENTS.md").writeText("trap")
        assertThrows(IllegalArgumentException::class.java) {
            manager.exportRootfsFile("workspace", guest, ByteArrayOutputStream())
        }
        source.deleteRecursively()
        assertThrows(IllegalArgumentException::class.java) {
            manager.exportRootfsFile("workspace", guest, ByteArrayOutputStream(), mounts)
        }
    }

    @Test fun `retargeted source alias cannot silently expose a different directory`() {
        val manager = WorkspaceManager(temporary.newFolder("workspaces"))
        manager.ensureWorkspace("workspace")
        val source = temporary.newFolder("phone-documents")
        val other = temporary.newFolder("not-connected")
        File(other, "secret.txt").writeText("private")
        val mount = WorkspaceDeviceMount("id", "docs", source)
        source.deleteRecursively()
        java.nio.file.Files.createSymbolicLink(source.toPath(), other.toPath())
        assertThrows(IllegalArgumentException::class.java) { WorkspaceDeviceFileAccess().readText(mount, "secret.txt") }
        assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize("workspace", "/device/docs/secret.txt", listOf(mount.toBindMount()))
        }
        assertEquals("private", File(other, "secret.txt").readText())
    }

    @Test fun `normalized device names cannot fall back to linux and cannot traverse aliases`() {
        val manager = WorkspaceManager(temporary.newFolder("workspaces"))
        manager.ensureWorkspace("workspace")
        File(manager.linuxDir("workspace"), "device/docs").mkdirs()
        File(manager.linuxDir("workspace"), "device/docs/trap.txt").writeText("not connected")
        for (path in listOf("/device/docs/trap.txt", "/./device/docs/trap.txt", "//device/docs/trap.txt")) {
            assertThrows(IllegalArgumentException::class.java) { manager.rootfsFileSize("workspace", path) }
        }
        val source = temporary.newFolder("phone-documents")
        val mounts = listOf(WorkspaceDeviceMount("id", "docs", source).toBindMount())
        assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize("workspace", "/device/docs/../other/file", mounts)
        }
        source.deleteRecursively()
        assertThrows(IllegalArgumentException::class.java) { manager.listRootfs("workspace", "/device/docs", mounts) }
    }
}
