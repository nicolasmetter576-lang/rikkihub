package me.rerere.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class WorkspaceDeviceFileAccessTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val access = WorkspaceDeviceFileAccess()

    private fun mount(): WorkspaceDeviceMount {
        val dir = tmp.newFolder("device-src")
        return WorkspaceDeviceMount("m1", "docs", dir)
    }

    @Test
    fun `writeText writes the original file directly`() {
        val m = mount()
        access.writeText(m, "a/b.txt", "hello")
        assertEquals("hello", File(m.source, "a/b.txt").readText())
        // entry path is relative to mount.source
        assertEquals("a/c.txt", access.writeText(m, "a/c.txt", "x").path)
        assertEquals("hello", access.readText(m, "a/b.txt"))
    }

    @Test
    fun `writes create parent dirs but never auto-create source`() {
        val missing = WorkspaceDeviceMount("m1", "gone", File(tmp.root, "missing"))
        assertThrows(IllegalArgumentException::class.java) {
            access.writeText(missing, "a.txt", "x")
        }
        assertFalse(missing.source.exists())
    }

    @Test
    fun `list stat mkdir move delete`() {
        runBlocking {
        val m = mount()
        access.mkdir(m, "dir")
        access.writeText(m, "dir/f.txt", "data")
        val moved = access.move(m, "dir/f.txt", "dir/g.txt")
        assertEquals("dir/g.txt", moved.path)
        val entries = access.list(m, "dir")
        assertEquals(listOf("g.txt"), entries.map { it.name })
        assertTrue(access.stat(m, "dir/g.txt").isFile)
        assertTrue(access.delete(m, "dir/g.txt"))
        assertThrows(IllegalArgumentException::class.java) { access.delete(m, "") }
        assertThrows(IllegalArgumentException::class.java) { access.delete(m, ".") }
        }
    }

    @Test
    fun `path escape via dotdot is rejected`() {
        val m = mount()
        assertThrows(IllegalArgumentException::class.java) {
            access.readText(m, "../../etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            access.writeText(m, "../evil.txt", "x")
        }
    }

    @Test
    fun `deleteBatch validates all paths before deleting`() {
        runBlocking {
        val m = mount()
        access.writeText(m, "keep.txt", "k")
        access.writeText(m, "del.txt", "d")
        val res = access.deleteBatch(m, listOf("del.txt"))
        assertEquals(listOf("del.txt"), res.deletedPaths)
        assertTrue(File(m.source, "keep.txt").exists())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { access.deleteBatch(m, listOf("keep.txt", "../escape")) }
        }
        assertTrue(File(m.source, "keep.txt").exists())
        }
    }

    @Test
    fun `batch delete and zip reject symlinked selection`() {
        runBlocking {
        val m = mount()
        val outside = tmp.newFolder("outside").apply { File(this, "secret.txt").writeText("s") }
        java.nio.file.Files.createSymbolicLink(
            File(m.source, "lnk").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { access.deleteBatch(m, listOf("lnk")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { access.exportZip(m, listOf("lnk"), ByteArrayOutputStream()) }
        }
        }
    }

    @Test
    fun `importFile importFolder exportFile exportZip`() {
        runBlocking {
        val m = mount()
        access.importFile(m, "", "in.bin", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertEquals(3, File(m.source, "in.bin").length())

        val out = ByteArrayOutputStream()
        access.exportFile(m, "in.bin", out)
        assertEquals(3, out.size())

        val zip = ByteArrayOutputStream()
        access.exportZip(m, listOf("in.bin"), zip)
        val names = mutableListOf<String>()
        ZipInputStream(zip.toByteArray().inputStream()).use { z ->
            while (true) names += z.nextEntry?.name ?: break
        }
        assertEquals(listOf("in.bin"), names)

        val tree = FakeNode("docs", true, listOf(FakeNode("a.txt", false)))
        val result = access.importFolder(m, "", tree)
        assertEquals("docs", result.rootName)
        assertTrue(File(m.source, "docs/a.txt").isFile)
        }
    }

    private class FakeNode(
        private val name: String,
        override val isDirectory: Boolean,
        private val children: List<FakeNode> = emptyList(),
    ) : WorkspaceImportNode {
        override val stableId: String get() = name
        override val displayName: String get() = name
        override fun listChildren() = children
        override fun openInputStream() = ByteArrayInputStream("x".toByteArray())
    }
}
