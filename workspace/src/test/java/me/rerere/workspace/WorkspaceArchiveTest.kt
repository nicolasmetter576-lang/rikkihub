package me.rerere.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class WorkspaceArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val exporter = WorkspaceArchiveExporter()

    private fun area(): File = tmp.newFolder("files")

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = if (entry.isDirectory) {
                    ByteArray(0)
                } else {
                    zip.readBytes()
                }
            }
        }
        return entries
    }

    // ---------- 结构与 entry 命名 ----------

    @Test
    fun `exports nested directories and empty directories`() = runBlocking {
        val root = area()
        File(root, "dir/sub").mkdirs()
        File(root, "dir/empty").mkdirs()
        File(root, "dir/a.txt").writeText("A")
        File(root, "dir/sub/b.txt").writeText("B")

        val out = ByteArrayOutputStream()
        val result = exporter.exportZip(root, listOf("dir"), out)

        val entries = unzip(out.toByteArray())
        // 以选中项自身为顶层
        assertEquals(
            setOf("dir/", "dir/a.txt", "dir/empty/", "dir/sub/", "dir/sub/b.txt"),
            entries.keys,
        )
        assertEquals("A", entries["dir/a.txt"]!!.toString(Charsets.UTF_8))
        assertEquals("B", entries["dir/sub/b.txt"]!!.toString(Charsets.UTF_8))
        assertEquals(2, result.fileCount)
        assertEquals(3, result.directoryCount)
    }

    @Test
    fun `multiple selections share their common parent as archive base`() = runBlocking {
        val root = area()
        File(root, "dir").mkdirs()
        File(root, "dir/x.txt").writeText("X")
        File(root, "dir/y").mkdirs()
        File(root, "dir/y/z.txt").writeText("Z")

        val out = ByteArrayOutputStream()
        exporter.exportZip(root, listOf("dir/x.txt", "dir/y"), out)

        assertEquals(
            setOf("x.txt", "y/", "y/z.txt"),
            unzip(out.toByteArray()).keys,
        )
    }

    @Test
    fun `ancestor selection dedupes descendant selections`() = runBlocking {
        val root = area()
        File(root, "dir/sub").mkdirs()
        File(root, "dir/sub/a.txt").writeText("A")
        File(root, "other.txt").writeText("O")

        val out = ByteArrayOutputStream()
        // 故意乱序传入: dir/sub 在 dir 之前
        exporter.exportZip(root, listOf("dir/sub", "other.txt", "dir"), out)

        val entries = unzip(out.toByteArray())
        assertEquals(
            setOf("dir/", "dir/sub/", "dir/sub/a.txt", "other.txt"),
            entries.keys,
        )
        assertEquals("A", entries["dir/sub/a.txt"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `duplicate identical selections are written once`() = runBlocking {
        val root = area()
        File(root, "a.txt").writeText("A")

        val out = ByteArrayOutputStream()
        exporter.exportZip(root, listOf("a.txt", "a.txt", "./a.txt"), out)

        assertEquals(setOf("a.txt"), unzip(out.toByteArray()).keys)
    }

    @Test
    fun `unicode and space names round-trip in entries`() = runBlocking {
        val root = area()
        File(root, "我的 文件夹").mkdirs()
        File(root, "我的 文件夹/报告 2024.txt").writeText("中文内容")
        File(root, "plain.txt").writeText("p")

        val out = ByteArrayOutputStream()
        exporter.exportZip(root, listOf("我的 文件夹", "plain.txt"), out)

        val entries = unzip(out.toByteArray())
        assertEquals(
            setOf("我的 文件夹/", "我的 文件夹/报告 2024.txt", "plain.txt"),
            entries.keys,
        )
        assertEquals("中文内容", entries["我的 文件夹/报告 2024.txt"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `single file selection exports just that file`() = runBlocking {
        val root = area()
        File(root, "deep/nested").mkdirs()
        File(root, "deep/nested/note.txt").writeText("hi")

        val out = ByteArrayOutputStream()
        exporter.exportZip(root, listOf("deep/nested/note.txt"), out)

        assertEquals(mapOf("note.txt" to "hi"), unzip(out.toByteArray())
            .mapValues { it.value.toString(Charsets.UTF_8) })
    }

    // ---------- 拒绝规则 ----------

    @Test
    fun `rejects empty selection`() {
        val root = area()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { exporter.exportZip(root, emptyList(), ByteArrayOutputStream()) }
        }
    }

    @Test
    fun `rejects archiving the storage root itself`() {
        val root = area()
        File(root, "a.txt").writeText("A")
        for (path in listOf("", ".", "/")) {
            assertThrows("path='$path'", IllegalArgumentException::class.java) {
                runBlocking { exporter.exportZip(root, listOf(path), ByteArrayOutputStream()) }
            }
        }
    }

    @Test
    fun `rejects paths escaping the storage root`() {
        val root = area()
        File(tmp.root, "secret.txt").writeText("secret")
        for (path in listOf("../secret.txt", "..", "dir/../../secret.txt")) {
            assertThrows("path=$path", IllegalArgumentException::class.java) {
                runBlocking { exporter.exportZip(root, listOf(path), ByteArrayOutputStream()) }
            }
        }
    }

    @Test
    fun `rejects missing paths`() {
        val root = area()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { exporter.exportZip(root, listOf("ghost.txt"), ByteArrayOutputStream()) }
        }
    }

    // ---------- 符号链接 ----------

    @Test
    fun `refuses symlink escape to outside the storage root`() {
        val root = area()
        File(tmp.root, "outside-secret.txt").writeText("secret")
        val link = File(root, "link.txt")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(tmp.root, "outside-secret.txt").toPath())
            }.isSuccess,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { exporter.exportZip(root, listOf("link.txt"), ByteArrayOutputStream()) }
        }
        assertTrue(error.message!!.contains("symbolic link") || error.message!!.contains("escapes"))
    }

    @Test
    fun `refuses symlinks found while walking a directory`() {
        val root = area()
        File(root, "dir").mkdirs()
        File(root, "dir/real.txt").writeText("real")
        val link = File(root, "dir/evil")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(tmp.root, "nope").toPath())
            }.isSuccess,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { exporter.exportZip(root, listOf("dir"), ByteArrayOutputStream()) }
        }
        assertTrue(error.message!!.contains("symbolic link"))
    }

    @Test
    fun `refuses a selected symlink even when it points inside the root`() {
        val root = area()
        File(root, "dir").mkdirs()
        File(root, "dir/real.txt").writeText("real")
        val link = File(root, "link")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(root, "dir").toPath())
            }.isSuccess,
        )

        // resolve() 会把内部链接 canonical 成 dir/, 必须拒绝而不是静默导出目标内容
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { exporter.exportZip(root, listOf("link"), ByteArrayOutputStream()) }
        }
        assertTrue(error.message!!.contains("symbolic link"))
    }

    @Test
    fun `refuses a path whose middle component is an internal symlink`() {
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

        // link/inside.txt 的末端不是链接, 但中间组件 link 是; 必须逐组件拒绝,
        // 不能 canonical 化成 real/inside.txt 后跟随导出
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { exporter.exportZip(root, listOf("link/inside.txt"), ByteArrayOutputStream()) }
        }
        assertTrue(error.message!!.contains("symbolic link"))
    }

    @Test
    fun `refuses internal symlink cycles instead of looping`() {
        val root = area()
        File(root, "dir").mkdirs()
        val link = File(root, "dir/self")
        assumeTrue(
            "symlink not supported on this platform",
            runCatching {
                Files.createSymbolicLink(link.toPath(), File(root, "dir").toPath())
            }.isSuccess,
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { exporter.exportZip(root, listOf("dir"), ByteArrayOutputStream()) }
        }
    }

    // ---------- 取消与流管理 ----------

    @Test
    fun `closes the output stream when invoked in an already cancelled coroutine`() {
        val root = area()
        File(root, "a.txt").writeText("A")
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                // 函数必须真的被调用: 先启动协程再取消, 调用点仍会执行 exportZip,
                // 由函数体第一个 ensureActive 抛出. 这与 "withContext 在调度前取消
                // 导致函数根本没执行" 的无效场景不同.
                coroutineContext.cancel()
                exporter.exportZip(root, listOf("a.txt"), out)
            }
        }
        assertTrue(closed)
    }

    @Test
    fun `propagates cancellation and closes the output stream`() {
        val root = area()
        // 足够大, 保证第一块会真实冲刷到底层流
        File(root, "big.bin").writeBytes(ByteArray(200 * 1024) { it.toByte() })
        val job = Job()
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                // 写到一半取消, 模拟传输途中被用户/系统取消
                job.cancel()
                super.write(b, off, len)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }

        assertThrows(CancellationException::class.java) {
            runBlocking(job) { exporter.exportZip(root, listOf("big.bin"), out) }
        }
        assertTrue(closed)
    }

    @Test
    fun `entry name validation rejects absolute and traversal names`() {
        for (bad in listOf("/abs", "a/../b", "..", ".", "", "a//b", "a\\b")) {
            assertThrows("name='$bad'", IllegalArgumentException::class.java) {
                WorkspaceArchiveExporter.entryName(bad, "")
            }
        }
        assertEquals("dir/a.txt", WorkspaceArchiveExporter.entryName("dir/a.txt", ""))
        assertEquals("a.txt", WorkspaceArchiveExporter.entryName("dir/a.txt", "dir"))
    }

    // ---------- WorkspaceManager 集成 ----------

    @Test
    fun `manager routes export to FILES and LINUX areas`() = runBlocking {
        val manager = WorkspaceManager(baseDir = tmp.newFolder("workspaces"))
        manager.ensureWorkspace("ws")
        File(manager.filesDir("ws"), "f.txt").writeText("files-area")
        File(manager.linuxDir("ws"), "etc").mkdirs()
        File(manager.linuxDir("ws"), "etc/hostname").writeText("linux-area")

        val filesOut = ByteArrayOutputStream()
        manager.exportArchive("ws", listOf("f.txt"), WorkspaceStorageArea.FILES, filesOut)
        assertEquals(setOf("f.txt"), unzip(filesOut.toByteArray()).keys)

        val linuxOut = ByteArrayOutputStream()
        manager.exportArchive("ws", listOf("etc"), WorkspaceStorageArea.LINUX, linuxOut)
        assertEquals(setOf("etc/", "etc/hostname"), unzip(linuxOut.toByteArray()).keys)
    }

    @Test
    fun `failing selection does not write partial entries`() = runBlocking {
        val root = area()
        File(root, "ok.txt").writeText("ok")

        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { exporter.exportZip(root, listOf("ok.txt", "../ghost"), out) }
        }
        // 校验发生在任何 entry 写出之前
        assertTrue(unzip(out.toByteArray()).isEmpty())
    }
}
