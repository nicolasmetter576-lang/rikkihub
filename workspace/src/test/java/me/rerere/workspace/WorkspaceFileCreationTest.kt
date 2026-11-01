package me.rerere.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceFileCreationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fileSystem = WorkspaceFileSystem()

    @Test
    fun createsEmptyRootFileAndNonemptyUtf8SubdirectoryFile() {
        val root = tmp.newFolder("files")
        val empty = fileSystem.createFile(root, "untitled")
        assertEquals("untitled", empty.path)
        assertEquals("untitled", empty.name)
        assertEquals(0L, empty.sizeBytes)
        assertFalse(empty.isDirectory)
        assertTrue(empty.updatedAt > 0L)
        File(root, "目录").mkdir()
        val text = "# 中文😀\r\nCafé\n"
        val entry = fileSystem.createFile(root, "目录/笔记.md", text)
        assertEquals("目录/笔记.md", entry.path)
        assertEquals("笔记.md", entry.name)
        assertEquals(text.toByteArray(StandardCharsets.UTF_8).size.toLong(), entry.sizeBytes)
        assertArrayEquals(text.toByteArray(StandardCharsets.UTF_8), File(root, entry.path).readBytes())
    }

    @Test
    fun preservesDecomposedUnicodeAndExistingParentWhitespaceLiterally() {
        val root = tmp.newFolder("files")
        File(root, " parent ").mkdir()
        val path = " parent /e\u0301.md"
        val entry = fileSystem.createFile(root, path, "unchanged")
        assertEquals(path, entry.path)
        assertTrue(File(root, path).isFile)
        assertFalse(File(root, "parent/é.md").exists())
    }

    @Test
    fun existingFileConflictKeepsEveryOriginalByteForBothCreationApis() {
        val root = tmp.newFolder("files")
        val existing = File(root, "keep.bin")
        val original = byteArrayOf(0, 1, -1, 13, 10, 42)
        existing.writeBytes(original)
        val beforeTime = existing.lastModified()
        val error = assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            fileSystem.createFile(root, "keep.bin", "replacement")
        }
        assertEquals(WorkspaceFileOperationException.FILE_ALREADY_EXISTS, error.code)
        assertTrue(error is IllegalArgumentException)
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            fileSystem.writeText(root, "keep.bin", "replacement", overwrite = false)
        }
        assertArrayEquals(original, existing.readBytes())
        assertEquals(beforeTime, existing.lastModified())
    }

    @Test
    fun directoryAtLeafIsConflictAndItsContentsAreUntouched() {
        val root = tmp.newFolder("files")
        val directory = File(root, "notes.md").apply { mkdir() }
        File(directory, "keep").writeText("original")
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            fileSystem.createFile(root, "notes.md", "x")
        }
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            fileSystem.writeText(root, "notes.md", "x", overwrite = false)
        }
        assertEquals("original", File(directory, "keep").readText())
    }

    @Test
    fun lexicalTraversalAbsoluteAndMalformedPathsNeverCreateAnything() {
        val root = tmp.newFolder("files")
        File(root, "dir").mkdir()
        val invalid = listOf(
            "", ".", "..", "/a.txt", "../escape", "dir/../../escape", "dir/../a.txt",
            "dir/./a.txt", "dir//a.txt", "dir/", "dir\\a.txt", "dir/a\u0000.txt", "dir/a\u007f.txt",
            "dir/ leading", "dir/trailing ", "dir/.l2s.hidden",
        )
        invalid.forEach { path ->
            assertThrows(path, WorkspaceFileOperationException::class.java) {
                fileSystem.createFile(root, path, "x")
            }
        }
        assertEquals(listOf("dir"), root.list()!!.toList())
        assertEquals(0, File(root, "dir").list()!!.size)
        assertFalse(File(tmp.root, "escape").exists())
    }

    @Test
    fun absentRootAndMissingParentAreTypedAndNeverCreated() {
        val missingRoot = File(tmp.root, "gone")
        assertCode(WorkspaceFileOperationException.PARENT_NOT_FOUND) {
            fileSystem.createFile(missingRoot, "note.md")
        }
        assertFalse(missingRoot.exists())
        val root = tmp.newFolder("files")
        assertCode(WorkspaceFileOperationException.PARENT_NOT_FOUND) {
            fileSystem.createFile(root, "missing/nested/note.md")
        }
        assertCode(WorkspaceFileOperationException.PARENT_NOT_FOUND) {
            fileSystem.writeText(root, "missing/nested/note.md", "", overwrite = false)
        }
        assertFalse(File(root, "missing").exists())
        // Ordinary overwrite retains its previous mkdir-chains compatibility.
        fileSystem.writeText(root, "missing/nested/note.md", "ordinary")
        assertEquals("ordinary", File(root, "missing/nested/note.md").readText())
    }

    @Test
    fun fileInsteadOfParentOrRootHasTypedError() {
        val root = tmp.newFolder("files")
        val file = File(root, "not-dir").apply { writeText("keep") }
        assertCode(WorkspaceFileOperationException.PARENT_NOT_DIRECTORY) {
            fileSystem.createFile(root, "not-dir/note.md")
        }
        assertCode(WorkspaceFileOperationException.PARENT_NOT_DIRECTORY) {
            fileSystem.createFile(file, "note.md")
        }
        assertEquals("keep", file.readText())
    }

    @Test
    fun occupiedLeafLinksInsideOutsideAndDanglingAreConflictsNotTargets() {
        val root = tmp.newFolder("files")
        val inside = File(root, "real").apply { writeText("inside") }
        val outside = tmp.newFile("outside").apply { writeText("outside") }
        val missing = File(tmp.root, "absent-target")
        listOf(inside, outside, missing).forEachIndexed { index, target ->
            val link = File(root, "link-$index")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
                fileSystem.createFile(root, link.name, "new")
            }
            assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
                fileSystem.writeText(root, link.name, "new", overwrite = false)
            }
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals(target.toPath(), Files.readSymbolicLink(link.toPath()))
        }
        assertEquals("inside", inside.readText())
        assertEquals("outside", outside.readText())
        assertFalse(missing.exists())
    }

    @Test
    fun parentLinksInsideOutsideAndDanglingAreRejectedBeforeOpening() {
        val root = tmp.newFolder("files")
        val inside = File(root, "real").apply { mkdir() }
        val outside = tmp.newFolder("outside")
        val missing = File(tmp.root, "absent-parent")
        listOf(inside, outside, missing).forEachIndexed { index, target ->
            val link = File(root, "link-$index")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            assertCode(WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED) {
                fileSystem.createFile(root, "${link.name}/new.md", "new")
            }
            assertCode(WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED) {
                fileSystem.writeText(root, "${link.name}/new.md", "new", overwrite = false)
            }
            assertFalse(File(target, "new.md").exists())
            assertTrue(Files.isSymbolicLink(link.toPath()))
        }
        assertFalse(missing.exists())
    }

    @Test
    fun checksEachNestedParentAndAllowsCanonicalRootAliasOnly() {
        val root = tmp.newFolder("files")
        File(root, "nested").mkdir()
        val real = tmp.newFolder("outside")
        Files.createSymbolicLink(File(root, "nested/link").toPath(), real.toPath())
        assertCode(WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED) {
            fileSystem.createFile(root, "nested/link/new.md")
        }
        val alias = File(tmp.root, "root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertEquals("new.md", fileSystem.createFile(alias, "new.md").path)
        assertTrue(File(root, "new.md").isFile)
        assertFalse(File(real, "new.md").exists())
    }

    @Test
    fun enforcesContentByteLimitBeforeClaimAndHonorsExplicitCharset() {
        val root = tmp.newFolder("files")
        val limited = WorkspaceFileSystem(WorkspaceConfig(maxWriteBytes = 4))
        assertCode(WorkspaceFileOperationException.CONTENT_TOO_LARGE) {
            limited.createFile(root, "too-big", "中中")
        }
        assertCode(WorkspaceFileOperationException.CONTENT_TOO_LARGE) {
            limited.writeText(root, "too-big", "中中", overwrite = false)
        }
        assertFalse(File(root, "too-big").exists())
        limited.createFile(root, "exact", "éé")
        assertArrayEquals("éé".toByteArray(StandardCharsets.UTF_8), File(root, "exact").readBytes())
        limited.createFile(root, "latin", "éééé", StandardCharsets.ISO_8859_1)
        assertArrayEquals(byteArrayOf(-23, -23, -23, -23), File(root, "latin").readBytes())
        limited.writeText(root, "utf16", "ab", overwrite = false, charset = StandardCharsets.UTF_16BE)
        assertArrayEquals(byteArrayOf(0, 97, 0, 98), File(root, "utf16").readBytes())
    }

    @Test
    fun utf8FilenameLengthIsCheckedBeforeClaim() {
        val root = tmp.newFolder("files")
        val exact = "中".repeat(85)
        assertEquals(exact, fileSystem.createFile(root, exact).name)
        assertCode(WorkspaceFileOperationException.INVALID_FILE_NAME) {
            fileSystem.createFile(root, "中".repeat(86))
        }
        assertEquals(1, root.list()!!.size)
    }

    @Test
    fun thirtyTwoParallelMixedContendersHaveExactlyOneWinnerAndNoClobber() {
        val root = tmp.newFolder("files")
        val contenders = 32
        val ready = CountDownLatch(contenders)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(contenders)
        try {
            val futures = (0 until contenders).map { index ->
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    val payload = "writer-$index\n".repeat(256)
                    val error = runCatching {
                        // Distinct instances emulate clients, not a per-object lock.
                        val fs = WorkspaceFileSystem()
                        if (index % 2 == 0) fs.createFile(root, "shared.md", payload)
                        else fs.writeText(root, "shared.md", payload, overwrite = false)
                    }.exceptionOrNull()
                    index to error
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            val winners = results.filter { it.second == null }
            assertEquals(1, winners.size)
            assertEquals(contenders - 1, results.count { it.second is WorkspaceFileAlreadyExistsException })
            val original = "writer-${winners.single().first}\n".repeat(256)
            assertEquals(original, File(root, "shared.md").readText())
            assertEquals(listOf("shared.md"), root.list()!!.toList())
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun postClaimWriteFailureKeepsIncompleteFileAndClosesDescriptor() {
        val root = tmp.newFolder("files")
        val target = File(root, "partial").toPath()
        var closed = false
        assertThrows(IOException::class.java) {
            writeNewWorkspaceFile(target, "payload".toByteArray()) { path ->
                val actual = newChannel(path)
                object : SeekableByteChannel by actual {
                    var wrote = false
                    override fun write(src: ByteBuffer): Int {
                        if (wrote) throw IOException("injected write failure")
                        wrote = true
                        val oldLimit = src.limit()
                        src.limit(src.position() + 1)
                        return try { actual.write(src) } finally { src.limit(oldLimit) }
                    }
                    override fun close() { closed = true; actual.close() }
                }
            }
        }
        assertTrue(closed)
        assertArrayEquals("p".toByteArray(), Files.readAllBytes(target))
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            fileSystem.createFile(root, "partial", "retry")
        }
    }

    @Test
    fun writeFailureNeverDeletesAnotherWritersReplacement() {
        val root = tmp.newFolder("files")
        val target = File(root, "replaced").toPath()
        assertThrows(IOException::class.java) {
            writeNewWorkspaceFile(target, "payload".toByteArray()) { path ->
                val actual = newChannel(path)
                object : SeekableByteChannel by actual {
                    override fun write(src: ByteBuffer): Int {
                        Files.delete(path)
                        Files.write(path, "somebody else's file".toByteArray(), StandardOpenOption.CREATE_NEW)
                        throw IOException("injected after replacement")
                    }
                }
            }
        }
        assertEquals("somebody else's file", target.toFile().readText())
    }

    @Test
    fun closeFailureKeepsClaimedFileWithoutReportingSuccessfulWrite() {
        val target = tmp.newFolder("files").toPath().resolve("close-failure")
        assertThrows(IOException::class.java) {
            writeNewWorkspaceFile(target, "payload".toByteArray()) { path ->
                val actual = newChannel(path)
                object : SeekableByteChannel by actual {
                    override fun close() {
                        actual.close()
                        throw IOException("injected close failure")
                    }
                }
            }
        }
        assertEquals("payload", target.toFile().readText())
    }

    private fun newChannel(path: Path): SeekableByteChannel = Files.newByteChannel(
        path,
        setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
    )

    private fun assertCode(code: String, operation: () -> Unit) {
        val error = assertThrows(WorkspaceFileOperationException::class.java) { operation() }
        assertEquals(code, error.code)
    }
}
