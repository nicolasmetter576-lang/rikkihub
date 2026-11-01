package me.rerere.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * 纯 JVM 测试: FakeNode 模拟 OpenDocumentTree 递归查询出的文档树,
 * 覆盖树结构还原 / 命名清洗 / 冲突重命名 / 循环与坏 provider 的显式报错.
 * Android 侧 DocumentsContract 适配 (WorkspaceFolderImporter) 无法在 JVM 测,
 * 行为由这里的接口契约约束.
 */
class WorkspaceTreeImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val importer = WorkspaceTreeImporter()

    private fun area(): File = tmp.newFolder("files")

    // ---------- 结构还原 ----------

    @Test
    fun `preserves nested directories and empty directories`() = runBlocking {
        val target = area()
        val tree = dir("r", "root",
            dir("a", "a",
                file("f1", "hello.txt", "hi"),
                dir("empty", "empty-dir"),
            ),
            file("f2", "top.md", "# title"),
        )

        val result = importer.import(target, "", tree)

        assertEquals("root", result.rootName)
        assertEquals(2, result.fileCount)
        assertEquals(3, result.directoryCount) // root + a + empty-dir
        assertEquals("hi", File(target, "root/a/hello.txt").readText())
        assertEquals("# title", File(target, "root/top.md").readText())
        assertTrue(File(target, "root/a/empty-dir").isDirectory)
    }

    @Test
    fun `imports into nested destination path`() = runBlocking {
        val target = area()
        val tree = dir("r", "folder", file("f", "a.txt", "x"))

        importer.import(target, "deep/nest", tree)

        assertEquals("x", File(target, "deep/nest/folder/a.txt").readText())
    }

    @Test
    fun `supports unicode and space names`() = runBlocking {
        val target = area()
        val tree = dir("r", "我的 文件夹",
            file("f1", "报告 2024.txt", "中文内容"),
            dir("d", "子 目录", file("f2", "空.txt", "")),
        )

        importer.import(target, "", tree)

        assertEquals("中文内容", File(target, "我的 文件夹/报告 2024.txt").readText())
        assertTrue(File(target, "我的 文件夹/子 目录/空.txt").isFile)
        assertEquals(0L, File(target, "我的 文件夹/子 目录/空.txt").length())
    }

    @Test
    fun `streams file content without loading whole file at once`() = runBlocking {
        val target = area()
        val big = ByteArray(300 * 1024) { (it % 251).toByte() }
        val tree = dir("r", "root", file("f", "big.bin", big))

        importer.import(target, "", tree)

        assertTrue(big.contentEquals(File(target, "root/big.bin").readBytes()))
    }

    // ---------- 冲突与重名策略 ----------

    @Test
    fun `conflicting root folder is renamed not merged`() = runBlocking {
        val target = area()
        File(target, "root").mkdirs()
        File(target, "root/keep.txt").writeText("keep")
        val tree = dir("r", "root", file("f", "new.txt", "new"))

        val result = importer.import(target, "", tree)

        assertEquals("root (1)", result.rootName)
        assertEquals("new", File(target, "root (1)/new.txt").readText())
        // 旧目录原样保留, 没有混入
        assertEquals("keep", File(target, "root/keep.txt").readText())
        assertFalse(File(target, "root/new.txt").exists())
    }

    @Test
    fun `existing files and directories are never overwritten or merged`() = runBlocking {
        val target = area()
        // 目标处的同名文件/目录都不在导入根内部, 不能被覆盖或混入
        File(target, "dir").mkdirs()
        File(target, "a.txt").writeText("old")
        val tree = dir("r", "root",
            file("f1", "a.txt", "new"),
            dir("d", "dir", file("f2", "inner.txt", "inner")),
        )

        importer.import(target, "", tree)

        assertEquals("old", File(target, "a.txt").readText())
        assertEquals("new", File(target, "root/a.txt").readText())
        assertTrue(File(target, "root/dir/inner.txt").isFile)
        assertTrue(File(target, "dir").list()!!.isEmpty())
    }

    @Test
    fun `sibling names colliding after sanitize are deduplicated`() = runBlocking {
        val target = area()
        // "a/b" 和 "a\b" 清洗后都是 "a_b"
        val tree = dir("r", "root",
            file("f1", "a/b", "first"),
            file("f2", "a\\b", "second"),
        )

        importer.import(target, "", tree)

        assertEquals("first", File(target, "root/a_b").readText())
        assertEquals("second", File(target, "root/a_b (1)").readText())
    }

    // ---------- 非法名称防御 ----------

    @Test
    fun `rejects reserved names that cannot be sanitized`() = runBlocking {
        val target = area()
        for (bad in listOf("..", ".", "   ", "...")) {
            val tree = dir("r", "root", file("f-$bad", bad, "x"))
            assertThrows("name=$bad", IllegalArgumentException::class.java) {
                runBlocking { importer.import(target, "", tree) }
            }
        }
        assertFalse(File(target, "root").exists())
    }

    @Test
    fun `sanitizes slashes and control characters in names`() = runBlocking {
        val target = area()
        val tree = dir("r", "root",
            file("f1", "a/b", "slash"),
            file("f2", "a\\b", "backslash"),
            file("f3", "a\u0001b", "ctrl"),
        )

        importer.import(target, "", tree)

        assertEquals("slash", File(target, "root/a_b").readText())
        assertEquals("backslash", File(target, "root/a_b (1)").readText())
        assertEquals("ctrl", File(target, "root/a_b (2)").readText())
        // 没有越出 root 目录
        assertEquals(3, File(target, "root").list()!!.size)
    }

    @Test
    fun `rejects null display name`() = runBlocking {
        val target = area()
        val tree = dir("r", "root", file("f", null, "x"))
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { importer.import(target, "", tree) }
        }
        assertTrue(error.message!!.contains("display name"))
    }

    @Test
    fun `conflict rename of a max-length name stays within the byte limit`() = runBlocking {
        val target = area()
        // 两个名字清洗后都变成 250 个 'a' + "_.txt" (255 字节, 正好在上限),
        // 第二个必须走 " (n)" 冲突重命名且截断回 255 字节以内, 否则 ENAMETOOLONG
        val tree = dir("r", "root",
            file("f1", "a".repeat(250) + "/.txt", "first"),
            file("f2", "a".repeat(250) + "\\.txt", "second"),
        )

        importer.import(target, "", tree)

        val created = File(target, "root").listFiles()!!
        assertEquals(2, created.size)
        assertTrue(created.all { it.name.toByteArray(Charsets.UTF_8).size <= 255 })
        // 截短的是基名, 扩展名保留
        assertTrue(created.all { it.name.endsWith(".txt") })
        assertEquals(1, created.count { it.name.endsWith(" (1).txt") })
        assertEquals(setOf("first", "second"), created.map { it.readText() }.toSet())
    }

    // ---------- 循环 / 坏 provider ----------

    @Test
    fun `rejects repeated document ids including cycles`() = runBlocking {
        val target = area()
        val subtree = dir("dup", "sub")
        val tree = dir("r", "root", subtree, dir("d", "other", subtree))

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { importer.import(target, "", tree) }
        }
        assertTrue(error.message!!.contains("repeated document id"))
        // 失败后本次新建的根目录被清掉
        assertFalse(File(target, "root").exists())
    }

    @Test
    fun `rejects provider that fails to list children`() = runBlocking {
        val target = area()
        val tree = dir("r", "root",
            file("ok", "ok.txt", "ok"),
            failingDir("bad", "bad-dir", IllegalStateException("cursor was null")),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { importer.import(target, "", tree) }
        }
        assertEquals("cursor was null", error.message)
        assertFalse(File(target, "root").exists())
    }

    @Test
    fun `rejects provider that returns null file stream`() = runBlocking {
        val target = area()
        val tree = dir("r", "root", fileNullStream("f", "ghost.txt"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { importer.import(target, "", tree) }
        }
        assertFalse(File(target, "root").exists())
    }

    @Test
    fun `rejects non-directory root and blank document id`(): Unit = runBlocking {
        val target = area()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { importer.import(target, "", file("f", "a.txt", "x")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { importer.import(target, "", dir("  ", "root")) }
        }
    }

    // ---------- 目标路径 ----------

    @Test
    fun `rejects destination escaping storage root`() = runBlocking {
        val target = area()
        val tree = dir("r", "root")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { importer.import(target, "../outside", tree) }
        }
        assertFalse(File(tmp.root, "outside").exists())
    }

    @Test
    fun `rejects destination that is an existing file`(): Unit = runBlocking {
        val target = area()
        File(target, "notdir").writeText("x")
        val tree = dir("r", "root")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { importer.import(target, "notdir", tree) }
        }
    }

    // ---------- 取消 ----------

    @Test
    fun `propagates cancellation`() {
        val target = area()
        val tree = dir("r", "root", file("f", "a.txt", "x"))
        val cancelled = Job().also { it.cancel() }

        assertThrows(CancellationException::class.java) {
            runBlocking(cancelled) { importer.import(target, "", tree) }
        }
        assertFalse(File(target, "root").exists())
    }

    // ---------- WorkspaceManager 集成: 两个存储区 ----------

    @Test
    fun `manager routes import to FILES and LINUX areas`() = runBlocking {
        val manager = WorkspaceManager(baseDir = tmp.newFolder("workspaces"))
        manager.ensureWorkspace("ws")
        val tree = dir("r", "folder", file("f", "a.txt", "x"))

        manager.importFolder("ws", "", WorkspaceStorageArea.FILES, tree)
        manager.importFolder("ws", "sub", WorkspaceStorageArea.LINUX, tree)

        assertEquals("x", File(manager.filesDir("ws"), "folder/a.txt").readText())
        assertEquals("x", File(manager.linuxDir("ws"), "sub/folder/a.txt").readText())
    }

    // ---------- fake provider ----------

    private class FakeNode(
        override val stableId: String,
        override val displayName: String?,
        private val directory: Boolean,
        private val content: ByteArray? = null,
        private val children: List<FakeNode> = emptyList(),
        private val childrenError: Throwable? = null,
    ) : WorkspaceImportNode {
        override val isDirectory: Boolean get() = directory

        override fun listChildren(): List<WorkspaceImportNode> {
            childrenError?.let { throw it }
            return children
        }

        override fun openInputStream(): InputStream? =
            content?.let(::ByteArrayInputStream)
    }

    private fun dir(id: String, name: String?, vararg children: FakeNode) =
        FakeNode(id, name, directory = true, children = children.toList())

    private fun failingDir(id: String, name: String?, error: Throwable) =
        FakeNode(id, name, directory = true, childrenError = error)

    private fun file(id: String, name: String?, text: String) =
        FakeNode(id, name, directory = false, content = text.toByteArray())

    private fun file(id: String, name: String?, bytes: ByteArray) =
        FakeNode(id, name, directory = false, content = bytes)

    private fun fileNullStream(id: String, name: String?) =
        FakeNode(id, name, directory = false, content = null)
}
