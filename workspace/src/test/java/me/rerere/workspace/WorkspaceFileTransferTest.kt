package me.rerere.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.random.Random

class WorkspaceFileTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val transfer = WorkspaceFileTransfer()

    private fun roots(): Pair<File, File> = tmp.newFolder("a", "files") to tmp.newFolder("b", "files")

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (error: WorkspaceFileOperationException) {
            assertEquals(code, error.code)
        }
    }

    // ---------- 跨工作区移动 ----------

    @Test
    fun `moves a file into another workspace folder`() = runBlocking {
        val (a, b) = roots()
        File(a, "notes").mkdirs()
        File(a, "notes/第一章.md").writeText("正文")
        File(b, "archive").mkdirs()

        val result = transfer.transferBatch(a, listOf("notes/第一章.md"), b, "archive", WorkspaceTransferMode.MOVE)

        val item = result.items.single()
        assertEquals("notes/第一章.md", item.source)
        assertEquals("archive/第一章.md", item.target)
        assertFalse(item.renamed)
        assertFalse(item.isDirectory)
        assertFalse(File(a, "notes/第一章.md").exists())
        assertEquals("正文", File(b, "archive/第一章.md").readText())
    }

    @Test
    fun `moves a folder tree including empty folders to the other root`() = runBlocking {
        val (a, b) = roots()
        File(a, "book/sub").mkdirs()
        File(a, "book/empty").mkdirs()
        File(a, "book/sub/x.txt").writeText("X")

        val item = transfer.transferBatch(a, listOf("book"), b, "", WorkspaceTransferMode.MOVE).items.single()

        assertEquals("book", item.target)
        assertTrue(item.isDirectory)
        assertFalse(File(a, "book").exists())
        assertEquals("X", File(b, "book/sub/x.txt").readText())
        assertTrue(File(b, "book/empty").isDirectory)
    }

    // ---------- 同名自动改名 ----------

    @Test
    fun `renames on a clash instead of overwriting`() = runBlocking {
        val (a, b) = roots()
        File(a, "a.txt").writeText("new")
        File(a, "dir").mkdirs()
        File(a, ".gitignore").writeText("g")
        File(b, "a.txt").writeText("old")
        File(b, "a (1).txt").writeText("old1")
        File(b, "dir").mkdirs()
        File(b, ".gitignore").writeText("old")

        val result = transfer.transferBatch(a, listOf("a.txt", "dir", ".gitignore"), b, "", WorkspaceTransferMode.MOVE)

        assertEquals(listOf("a (2).txt", "dir (1)", ".gitignore (1)"), result.items.map { it.target })
        assertTrue(result.items.all { it.renamed })
        assertEquals("old", File(b, "a.txt").readText())
        assertEquals("old1", File(b, "a (1).txt").readText())
        assertEquals("new", File(b, "a (2).txt").readText())
    }

    @Test
    fun `a dangling symlink occupies the name`() = runBlocking {
        val (a, b) = roots()
        File(a, "a.txt").writeText("A")
        Files.createSymbolicLink(File(b, "a.txt").toPath(), File(b, "missing").toPath())

        val item = transfer.transferBatch(a, listOf("a.txt"), b, "", WorkspaceTransferMode.MOVE).items.single()

        assertEquals("a (1).txt", item.target)
        assertTrue(Files.isSymbolicLink(File(b, "a.txt").toPath()))
    }

    @Test
    fun `a long name is trimmed to fit the counter`() = runBlocking {
        val (a, b) = roots()
        val name = "长".repeat(83) + ".txt" // 253 bytes: " (1)" would overflow 255
        File(a, name).writeText("A")
        File(b, name).writeText("B")

        val target = transfer.transferBatch(a, listOf(name), b, "", WorkspaceTransferMode.MOVE).items.single().target!!

        assertTrue(target.toByteArray().size <= 255)
        assertTrue(target, target.endsWith(" (1).txt"))
        assertTrue(target.startsWith("长长长"))
        assertEquals("A", File(b, target).readText())
    }

    // ---------- 同一工作区 ----------

    @Test
    fun `same workspace move into a sibling folder renames on a clash`() = runBlocking {
        val root = tmp.newFolder("files")
        File(root, "src").mkdirs()
        File(root, "dst").mkdirs()
        File(root, "src/a.txt").writeText("new")
        File(root, "dst/a.txt").writeText("old")

        val item = transfer.transferBatch(root, listOf("src/a.txt"), root, "dst", WorkspaceTransferMode.MOVE).items.single()

        assertEquals("dst/a (1).txt", item.target)
        assertEquals("old", File(root, "dst/a.txt").readText())
        assertFalse(File(root, "src/a.txt").exists())
    }

    @Test
    fun `moving into the folder it is already in is refused`() = runBlocking {
        val root = tmp.newFolder("files")
        File(root, "dir").mkdirs()
        File(root, "dir/a.txt").writeText("A")

        val item = transfer.transferBatch(root, listOf("dir/a.txt"), root, "dir", WorkspaceTransferMode.MOVE).items.single()

        assertNull(item.target)
        assertEquals(WorkspaceFileOperationException.TRANSFER_SAME_LOCATION, item.errorCode)
        assertTrue(File(root, "dir/a.txt").exists())
    }

    @Test
    fun `copying into the same folder makes a numbered duplicate`() = runBlocking {
        val root = tmp.newFolder("files")
        File(root, "dir/sub").mkdirs()
        File(root, "dir/sub/x.txt").writeText("X")

        val result = transfer.transferBatch(root, listOf("dir"), root, "", WorkspaceTransferMode.COPY)

        assertEquals("dir (1)", result.items.single().target)
        assertEquals("X", File(root, "dir/sub/x.txt").readText())
        assertEquals("X", File(root, "dir (1)/sub/x.txt").readText())
    }

    @Test
    fun `a folder cannot go into itself or its subfolder`() = runBlocking {
        val root = tmp.newFolder("files")
        File(root, "dir/sub").mkdirs()

        for (mode in WorkspaceTransferMode.entries) {
            for (dest in listOf("dir", "dir/sub")) {
                val item = transfer.transferBatch(root, listOf("dir"), root, dest, mode).items.single()
                assertEquals("$mode → $dest", WorkspaceFileOperationException.TRANSFER_INTO_ITSELF, item.errorCode)
            }
        }
        assertTrue(File(root, "dir/sub").isDirectory)
        assertFalse(File(root, "dir/sub/dir").exists())
    }

    // ---------- 复制 ----------

    @Test
    fun `copies a tree to another workspace and keeps the source and timestamps`() = runBlocking {
        val (a, b) = roots()
        File(a, "book/sub").mkdirs()
        File(a, "book/empty").mkdirs()
        val bytes = Random(1).nextBytes(200_000)
        File(a, "book/sub/data.bin").writeBytes(bytes)
        File(a, "book/sub/data.bin").setLastModified(1_600_000_000_000L)

        val item = transfer.transferBatch(a, listOf("book"), b, "", WorkspaceTransferMode.COPY).items.single()

        assertEquals("book", item.target)
        assertEquals(0, item.skippedLinks)
        assertArrayEquals(bytes, File(a, "book/sub/data.bin").readBytes())
        assertArrayEquals(bytes, File(b, "book/sub/data.bin").readBytes())
        assertTrue(File(b, "book/empty").isDirectory)
        assertEquals(1_600_000_000_000L, File(b, "book/sub/data.bin").lastModified())
    }

    @Test
    fun `copy skips symbolic links and never follows them`() = runBlocking {
        val (a, b) = roots()
        val secret = tmp.newFile("secret.txt").apply { writeText("SECRET") }
        val outsideDir = tmp.newFolder("outside").apply { File(this, "x.txt").writeText("OUT") }
        File(a, "dir").mkdirs()
        File(a, "dir/ok.txt").writeText("ok")
        Files.createSymbolicLink(File(a, "dir/file-link").toPath(), secret.toPath())
        Files.createSymbolicLink(File(a, "dir/dir-link").toPath(), outsideDir.toPath())

        val item = transfer.transferBatch(a, listOf("dir"), b, "", WorkspaceTransferMode.COPY).items.single()

        assertEquals(2, item.skippedLinks)
        assertEquals("ok", File(b, "dir/ok.txt").readText())
        assertFalse(Files.exists(File(b, "dir/file-link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(File(b, "dir/dir-link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `not enough space refuses before writing anything`() = runBlocking {
        val (a, b) = roots()
        File(a, "dir").mkdirs()
        File(a, "dir/a.bin").writeBytes(ByteArray(10_000))
        transfer.usableSpace = { WorkspaceFileTransfer.SPACE_RESERVE_BYTES + 9_999 }

        val item = transfer.transferBatch(a, listOf("dir"), b, "", WorkspaceTransferMode.COPY).items.single()

        assertEquals(WorkspaceFileOperationException.NOT_ENOUGH_SPACE, item.errorCode)
        assertFalse(File(b, "dir").exists())
    }

    @Test
    fun `cancelling a copy removes the partial target and keeps the source`() = runBlocking {
        val (a, b) = roots()
        File(a, "dir").mkdirs()
        File(a, "dir/first.bin").writeBytes(ByteArray(3 * 1024 * 1024))
        File(a, "dir/second.bin").writeBytes(ByteArray(3 * 1024 * 1024))
        var progressed = false

        val job = launch(Dispatchers.IO) {
            transfer.transferBatch(a, listOf("dir"), b, "", WorkspaceTransferMode.COPY) { progress ->
                if (progress.bytesDone > 0 && !progressed) {
                    progressed = true
                    coroutineContext.cancel()
                }
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(progressed)
        assertFalse(File(b, "dir").exists())
        assertEquals(3L * 1024 * 1024, File(a, "dir/first.bin").length())
        assertEquals(3L * 1024 * 1024, File(a, "dir/second.bin").length())
    }

    @Test
    fun `progress covers every item and ends at the total`() = runBlocking {
        val (a, b) = roots()
        File(a, "one.bin").writeBytes(ByteArray(2 * 1024 * 1024 + 5))
        File(a, "two.txt").writeText("2")
        val seen = mutableListOf<WorkspaceTransferProgress>()

        transfer.transferBatch(a, listOf("one.bin", "two.txt"), b, "", WorkspaceTransferMode.COPY) { seen += it }

        assertEquals(listOf(0, 1), seen.map { it.itemIndex }.distinct())
        assertTrue(seen.all { it.itemCount == 2 })
        val last = seen.last { it.itemIndex == 0 }
        assertEquals(2L * 1024 * 1024 + 5, last.bytesDone)
        assertEquals(last.bytesTotal, last.bytesDone)
    }

    // ---------- 移动退回拷贝 ----------

    @Test
    fun `move falls back to copy and delete when the rename fails`() = runBlocking {
        val (a, b) = roots()
        File(a, "dir/sub").mkdirs()
        File(a, "dir/sub/x.txt").writeText("X")
        Files.createSymbolicLink(File(a, "dir/link").toPath(), tmp.newFile("outside.txt").toPath())
        transfer.mover = { _, _ -> throw IOException("EXDEV") }

        val item = transfer.transferBatch(a, listOf("dir"), b, "", WorkspaceTransferMode.MOVE).items.single()

        assertEquals("dir", item.target)
        assertEquals(1, item.skippedLinks)
        assertFalse(File(a, "dir").exists())
        assertEquals("X", File(b, "dir/sub/x.txt").readText())
        assertTrue(tmp.root.resolve("outside.txt").exists())
    }

    @Test
    fun `a name taken outside the lock is reported, not overwritten`() = runBlocking {
        val (a, b) = roots()
        File(a, "a.txt").writeText("A")
        transfer.mover = { _, _ -> throw FileAlreadyExistsException("a.txt") }

        val item = transfer.transferBatch(a, listOf("a.txt"), b, "", WorkspaceTransferMode.MOVE).items.single()

        assertEquals(WorkspaceFileOperationException.FILE_ALREADY_EXISTS, item.errorCode)
        assertTrue(File(a, "a.txt").exists())
    }

    // ---------- 拒绝与部分失败 ----------

    @Test
    fun `one bad item does not stop the others`() = runBlocking {
        val (a, b) = roots()
        File(a, "ok1.txt").writeText("1")
        File(a, "ok2.txt").writeText("2")

        val result = transfer.transferBatch(a, listOf("ok1.txt", "gone.txt", "ok2.txt"), b, "", WorkspaceTransferMode.MOVE)

        assertEquals(listOf("ok1.txt", null, "ok2.txt"), result.items.map { it.target })
        assertEquals(WorkspaceFileOperationException.FILE_NOT_FOUND, result.failures.single().errorCode)
        assertEquals(2, result.succeeded.size)
    }

    @Test
    fun `a missing or non-folder destination fails the whole batch`() = runBlocking {
        val (a, b) = roots()
        File(a, "a.txt").writeText("A")
        File(b, "file.txt").writeText("F")

        expectCode(WorkspaceFileOperationException.PARENT_NOT_FOUND) {
            transfer.transferBatch(a, listOf("a.txt"), b, "nope", WorkspaceTransferMode.MOVE)
        }
        expectCode(WorkspaceFileOperationException.PARENT_NOT_DIRECTORY) {
            transfer.transferBatch(a, listOf("a.txt"), b, "file.txt", WorkspaceTransferMode.COPY)
        }
        expectCode(WorkspaceFileOperationException.INVALID_PATH) {
            transfer.transferBatch(a, listOf("a.txt"), b, "../../a/files", WorkspaceTransferMode.MOVE)
        }
        assertTrue(File(a, "a.txt").exists())
    }

    @Test
    fun `root, parent segments and escapes are refused`() = runBlocking {
        val (a, b) = roots()
        File(a, "dir").mkdirs()

        val result = transfer.transferBatch(a, listOf("", "/", "dir/..", "../b/files"), b, "", WorkspaceTransferMode.COPY)

        assertTrue(result.items.all { it.errorCode == WorkspaceFileOperationException.INVALID_PATH })
        assertTrue(File(a, "dir").isDirectory)
        assertEquals(0, b.listFiles()!!.size)
    }

    @Test
    fun `selected symbolic links are refused, including middle components`() = runBlocking {
        val (a, b) = roots()
        val outside = tmp.newFolder("outside").apply { File(this, "inside.txt").writeText("I") }
        Files.createSymbolicLink(File(a, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(a, "file-link").toPath(), File(outside, "inside.txt").toPath())

        val result = transfer.transferBatch(a, listOf("link", "link/inside.txt", "file-link"), b, "", WorkspaceTransferMode.MOVE)

        // "link/inside.txt" 被祖先 "link" 去重；剩下两项都拒绝
        assertEquals(listOf("link", "file-link"), result.items.map { it.source })
        assertTrue(result.items.all { it.errorCode == WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED })
        assertTrue(File(outside, "inside.txt").exists())

        val middle = transfer.transferBatch(a, listOf("link/inside.txt"), b, "", WorkspaceTransferMode.COPY).items.single()
        assertEquals(WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED, middle.errorCode)
    }

    @Test
    fun `selection is deduplicated against ancestors and repeats`() {
        assertEquals(
            listOf("dir", "b.txt"),
            WorkspaceFileTransfer.dedupeSelection(listOf("dir/sub", "/dir/", "b.txt", "dir/sub/x", "b.txt")),
        )
    }

    // ---------- 并发 ----------

    @Test
    fun `opposite transfers between two workspaces do not deadlock`() = runBlocking {
        val (a, b) = roots()
        repeat(40) { File(a, "a$it.txt").writeText("a") }
        repeat(40) { File(b, "b$it.txt").writeText("b") }

        withTimeout(30_000) {
            coroutineScope {
                (0 until 40).flatMap { i ->
                    listOf(
                        async(Dispatchers.IO) { transfer.transfer(a, "a$i.txt", b, "", WorkspaceTransferMode.MOVE) },
                        async(Dispatchers.IO) { transfer.transfer(b, "b$i.txt", a, "", WorkspaceTransferMode.MOVE) },
                    )
                }.awaitAll()
            }
        }

        assertEquals((0 until 40).map { "b$it.txt" }.toSet(), a.list()!!.toSet())
        assertEquals((0 until 40).map { "a$it.txt" }.toSet(), b.list()!!.toSet())
    }

    @Test
    fun `a running transfer holds off a blocking mutation of the same root`() = runBlocking {
        val (a, b) = roots()
        File(a, "big.bin").writeBytes(ByteArray(4 * 1024 * 1024))
        val fs = WorkspaceFileSystem()
        var deletedWhileCopying = false
        var probed = false

        coroutineScope {
            launch(Dispatchers.IO) {
                transfer.transferBatch(a, listOf("big.bin"), b, "", WorkspaceTransferMode.COPY) { progress ->
                    if (progress.bytesDone >= 1024L * 1024L && progress.bytesDone < progress.bytesTotal && !probed) {
                        probed = true
                        // 拷贝中另一线程尝试删目标根下的文件：要等本项结束才能拿到锁
                        val other = Thread { fs.delete(b, "big.bin") }
                        other.start()
                        Thread.sleep(200)
                        deletedWhileCopying = !File(b, "big.bin").exists()
                    }
                }
            }
        }

        assertTrue(probed)
        assertFalse(deletedWhileCopying)
    }

    @Test
    fun `cancellation before start leaves everything untouched`() {
        val (a, b) = roots()
        File(a, "a.txt").writeText("A")
        try {
            runBlocking {
                coroutineContext.cancel()
                transfer.transferBatch(a, listOf("a.txt"), b, "", WorkspaceTransferMode.MOVE)
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(File(a, "a.txt").exists())
        assertFalse(File(b, "a.txt").exists())
    }
}
