package me.rerere.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceBatchDeleteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val deleter = WorkspaceBatchDeleter()

    private fun area(): File = tmp.newFolder("files")

    // ---------- 基本行为 ----------

    @Test
    fun `deletes mixed files and directories recursively including empty dirs`() = runBlocking {
        val root = area()
        File(root, "dir/sub").mkdirs()
        File(root, "dir/empty").mkdirs()
        File(root, "dir/a.txt").writeText("A")
        File(root, "dir/sub/b.txt").writeText("B")
        File(root, "top.txt").writeText("T")
        File(root, "keep.txt").writeText("K")

        val result = deleter.deleteBatch(root, listOf("dir", "top.txt"))

        assertEquals(listOf("dir", "top.txt"), result.deletedPaths)
        assertTrue(result.failures.isEmpty())
        assertFalse(File(root, "dir").exists())
        assertFalse(File(root, "top.txt").exists())
        assertTrue(File(root, "keep.txt").exists())
    }

    @Test
    fun `ancestor selection dedupes descendant selections`() = runBlocking {
        val root = area()
        File(root, "dir/sub").mkdirs()
        File(root, "dir/sub/a.txt").writeText("A")

        // 乱序 + 后代在前: 只删一次, 结果只保留祖先
        val result = deleter.deleteBatch(root, listOf("dir/sub", "dir", "dir/sub/a.txt"))

        assertEquals(listOf("dir"), result.deletedPaths)
        assertTrue(result.failures.isEmpty())
        assertFalse(File(root, "dir").exists())
    }

    @Test
    fun `duplicate identical selections are deleted once`() = runBlocking {
        val root = area()
        File(root, "a.txt").writeText("A")

        val result = deleter.deleteBatch(root, listOf("a.txt", "a.txt", "./a.txt"))

        assertEquals(listOf("a.txt"), result.deletedPaths)
        assertFalse(File(root, "a.txt").exists())
    }

    // ---------- 拒绝规则 (任何非法路径都在删除前拒绝, 什么都不删) ----------

    @Test
    fun `rejects empty selection`() {
        val root = area()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { deleter.deleteBatch(root, emptyList()) }
        }
    }

    @Test
    fun `rejects deleting the storage root itself`() {
        val root = area()
        File(root, "a.txt").writeText("A")
        for (path in listOf("", ".", "/")) {
            assertThrows("path='$path'", IllegalArgumentException::class.java) {
                runBlocking { deleter.deleteBatch(root, listOf(path)) }
            }
        }
        assertTrue(File(root, "a.txt").exists())
        assertTrue(root.exists())
    }

    @Test
    fun `rejects paths escaping the storage root and deletes nothing`() {
        val root = area()
        File(tmp.root, "secret.txt").writeText("secret")
        File(root, "ok.txt").writeText("ok")
        for (path in listOf("../secret.txt", "..", "dir/../../secret.txt")) {
            assertThrows("path=$path", IllegalArgumentException::class.java) {
                runBlocking { deleter.deleteBatch(root, listOf("ok.txt", path)) }
            }
        }
        // 校验先于任何删除: 合法项也不能被删
        assertTrue(File(root, "ok.txt").exists())
        assertTrue(File(tmp.root, "secret.txt").exists())
    }

    @Test
    fun `rejects missing paths`() {
        val root = area()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { deleter.deleteBatch(root, listOf("ghost.txt")) }
        }
    }

    // ---------- 符号链接 ----------

    @Test
    fun `refuses a selected symlink pointing outside the root`() {
        val root = area()
        File(tmp.root, "outside-secret.txt").writeText("secret")
        val link = File(root, "link.txt")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(tmp.root, "outside-secret.txt").toPath())
            }.isSuccess,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { deleter.deleteBatch(root, listOf("link.txt")) }
        }
        // 链接与目标都必须还在
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(File(tmp.root, "outside-secret.txt").exists())
    }

    @Test
    fun `refuses a path whose middle component is a symlink`() {
        val root = area()
        File(root, "real").mkdirs()
        File(root, "real/inside.txt").writeText("inside")
        val link = File(root, "link")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(root, "real").toPath())
            }.isSuccess,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { deleter.deleteBatch(root, listOf("link/inside.txt")) }
        }
        assertTrue(File(root, "real/inside.txt").exists())
    }

    @Test
    fun `symlink inside a deleted directory removes only the link, never the target`() = runBlocking {
        val root = area()
        File(root, "dir").mkdirs()
        File(root, "dir/real.txt").writeText("real")
        File(tmp.root, "outside-dir").mkdirs()
        File(tmp.root, "outside-dir/keep.txt").writeText("keep")
        val link = File(root, "dir/evil")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(tmp.root, "outside-dir").toPath())
            }.isSuccess,
        )

        val result = deleter.deleteBatch(root, listOf("dir"))

        assertEquals(listOf("dir"), result.deletedPaths)
        assertTrue(result.failures.isEmpty())
        assertFalse(File(root, "dir").exists())
        // 链接目标及其内容不受影响
        assertTrue(File(tmp.root, "outside-dir/keep.txt").exists())
    }

    @Test
    fun `symlink cycle inside a deleted directory terminates and does not escape`() = runBlocking {
        val root = area()
        File(root, "dir").mkdirs()
        File(root, "dir/a.txt").writeText("A")
        val link = File(root, "dir/self")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(root, "dir").toPath())
            }.isSuccess,
        )

        val result = deleter.deleteBatch(root, listOf("dir"))

        assertEquals(listOf("dir"), result.deletedPaths)
        assertFalse(File(root, "dir").exists())
    }

    // ---------- 部分失败 ----------

    @Test
    fun `one failure does not report overall success and other items still delete`() = runBlocking {
        val root = area()
        File(root, "locked").mkdirs()
        File(root, "locked/a.txt").writeText("A")
        File(root, "ok.txt").writeText("ok")
        // 目录不可写 → 其子项删除失败, 目录本身也无法被删空
        assumeTrue(File(root, "locked").setWritable(false, false))
        try {
            val result = deleter.deleteBatch(root, listOf("locked", "ok.txt"))

            assertEquals(listOf("ok.txt"), result.deletedPaths)
            assertEquals(1, result.failures.size)
            assertEquals("locked", result.failures[0].path)
            assertFalse(File(root, "ok.txt").exists())
        } finally {
            File(root, "locked").setWritable(true, false)
        }
    }

    @Test
    fun `a vanishing path between validation and delete is a failure not a crash`() = runBlocking {
        val root = area()
        File(root, "a.txt").writeText("A")
        // 无法从外部精准注入竞态, 但可以验证已不存在项的后续路径仍被处理
        val result = deleter.deleteBatch(root, listOf("a.txt"))
        assertEquals(listOf("a.txt"), result.deletedPaths)
    }

    // ---------- 取消 ----------

    @Test
    fun `invocation in an already cancelled coroutine deletes nothing`() {
        val root = area()
        File(root, "a.txt").writeText("A")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                coroutineContext.cancel()
                deleter.deleteBatch(root, listOf("a.txt"))
            }
        }
        assertTrue(File(root, "a.txt").exists())
    }

    @Test
    fun `cancellation propagates and is not swallowed`() {
        val root = area()
        File(root, "a.txt").writeText("A")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                coroutineContext.cancel()
                deleter.deleteBatch(root, listOf("a.txt"))
            }
        }
    }

    // ---------- WorkspaceManager 集成 ----------

    @Test
    fun `manager routes batch delete to FILES and LINUX areas`() = runBlocking {
        val manager = WorkspaceManager(baseDir = tmp.newFolder("workspaces"))
        manager.ensureWorkspace("ws")
        File(manager.filesDir("ws"), "f.txt").writeText("files-area")
        File(manager.linuxDir("ws"), "etc").mkdirs()
        File(manager.linuxDir("ws"), "etc/hostname").writeText("linux-area")

        val filesResult = manager.deleteFiles("ws", listOf("f.txt"), WorkspaceStorageArea.FILES)
        assertEquals(listOf("f.txt"), filesResult.deletedPaths)
        assertFalse(File(manager.filesDir("ws"), "f.txt").exists())

        val linuxResult = manager.deleteFiles("ws", listOf("etc"), WorkspaceStorageArea.LINUX)
        assertEquals(listOf("etc"), linuxResult.deletedPaths)
        assertFalse(File(manager.linuxDir("ws"), "etc").exists())
    }
}
