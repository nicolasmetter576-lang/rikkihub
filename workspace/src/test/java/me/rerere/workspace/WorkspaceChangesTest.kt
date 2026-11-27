package me.rerere.workspace

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 每个会覆盖、删除、移走已有文件的入口都要先告诉观察者；覆盖写成功、移动完成之后再说一次。 */
class WorkspaceChangesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = WorkspaceFileSystem()
    private lateinit var root: File
    private val seen = mutableListOf<String>()

    @Before
    fun setUp() {
        root = tmp.newFolder("files").canonicalFile
        WorkspaceChanges.observer = { change ->
            seen += when (change) {
                // 写之前：文件里还是旧内容
                is WorkspaceChange.Overwrite -> "overwrite ${rel(change.file)} ${change.file.readText()} -> ${change.content().decodeToString()}"
                is WorkspaceChange.Delete -> "delete ${rel(change.file)} exists=${change.file.exists()}"
                is WorkspaceChange.Moved -> "moved ${rel(change.from)} -> ${rel(change.to)} arrived=${change.to.exists()}"
                // 写之后：文件里已经是新内容
                is WorkspaceChange.Written -> "written ${rel(change.file)} ${change.file.readText()}"
                is WorkspaceChange.Created -> "created ${rel(change.file)} ${change.file.readText()} = ${change.content().decodeToString()}"
                is WorkspaceChange.Moving -> "moving ${rel(change.from)} -> ${rel(change.to)} source=${change.from.exists()}"
            }
        }
    }

    @After
    fun tearDown() {
        WorkspaceChanges.observer = null
    }

    private fun rel(file: File) = file.relativeTo(root.parentFile).invariantSeparatorsPath

    private fun write(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun `overwrites report old and new content, creations report after writing, no-ops do not`() {
        write("a.md", "one")
        fs.writeText(root, "a.md", "two")
        val revision = fs.readTextSnapshot(root, "a.md").revision
        fs.writeTextWithRevision(root, "a.md", "three", revision)
        fs.writeTextWithRevision(root, "a.md", "three", fs.readTextSnapshot(root, "a.md").revision)
        fs.writeText(root, "new.md", "x")
        fs.createFile(root, "made.md", "y")
        assertEquals(
            listOf(
                "overwrite files/a.md one -> two", "written files/a.md two", "overwrite files/a.md two -> three", "written files/a.md three",
                "created files/new.md x = x", "created files/made.md y = y",
            ),
            seen,
        )
    }

    @Test
    fun `a creation that fails is not reported`() {
        write("taken.md", "old")
        assertTrue(runCatching { fs.createFile(root, "taken.md", "new") }.isFailure)
        assertTrue(runCatching { fs.writeText(root, "taken.md", "new", overwrite = false) }.isFailure)
        assertTrue(runCatching { fs.createFile(root, "missing/dir.md", "new") }.isFailure)
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `deletes report before deleting and accept a file the observer moved away`() {
        write("a.md", "A")
        write("dir/b.md", "B")
        val trash = tmp.newFolder("trash")
        WorkspaceChanges.observer = { change ->
            change as WorkspaceChange.Delete
            seen += "delete ${rel(change.file)}"
            if (change.file.isFile) change.file.renameTo(File(trash, change.file.name))
        }
        assertTrue(fs.delete(root, "a.md"))
        assertTrue(fs.delete(root, "dir", recursive = true))
        assertEquals(listOf("delete files/a.md", "delete files/dir"), seen)
        assertEquals("A", File(trash, "a.md").readText())
        assertFalse(File(root, "dir").exists())
    }

    @Test
    fun `refused directory delete does not report`() {
        write("dir/b.md", "B")
        runCatching { fs.delete(root, "dir") }
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `move asks first, then reports the overwritten target, then the move`() {
        write("a.md", "A")
        write("b.md", "B")
        fs.move(root, "a.md", "b.md", overwrite = true)
        fs.move(root, "b.md", "c/d.md")
        assertEquals(
            listOf(
                "moving files/a.md -> files/b.md source=true", "delete files/b.md exists=true", "moved files/a.md -> files/b.md arrived=true",
                "moving files/b.md -> files/c/d.md source=true", "moved files/b.md -> files/c/d.md arrived=true",
            ),
            seen,
        )
    }

    @Test
    fun `a refused move leaves both the source and the overwritten target alone`() {
        write("a.md", "A")
        write("b.md", "B")
        WorkspaceChanges.observer = { if (it is WorkspaceChange.Moving) throw WorkspaceLockedException("locked") }
        val error = runCatching { fs.move(root, "a.md", "b.md", overwrite = true) }.exceptionOrNull()
        assertEquals(WorkspaceFileOperationException.FILE_LOCKED, (error as WorkspaceFileOperationException).code)
        assertEquals("A", File(root, "a.md").readText())
        assertEquals("B", File(root, "b.md").readText())
    }

    @Test
    fun `a move onto an existing target without overwrite is refused before asking`() {
        write("a.md", "A")
        write("b.md", "B")
        assertTrue(runCatching { fs.move(root, "a.md", "b.md") }.isFailure)
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `batch delete reports each item and counts a moved-away file as deleted`() = runBlocking {
        write("a.md", "A")
        write("dir/b.md", "B")
        val trash = tmp.newFolder("trash")
        WorkspaceChanges.observer = { change ->
            change as WorkspaceChange.Delete
            seen += "delete ${rel(change.file)}"
            if (change.file.isFile) change.file.renameTo(File(trash, change.file.name))
        }
        val result = WorkspaceBatchDeleter(fs).deleteBatch(root, listOf("a.md", "dir"))
        assertEquals(listOf("a.md", "dir"), result.deletedPaths)
        assertEquals(listOf("delete files/a.md", "delete files/dir"), seen)
        assertFalse(File(root, "dir").exists())
    }

    @Test
    fun `transfer move reports after arriving, copy does not report`() = runBlocking {
        write("a.md", "A")
        write("b.md", "B")
        val other = tmp.newFolder("other").canonicalFile
        val transfer = WorkspaceFileTransfer(fs)
        transfer.transfer(root, "a.md", other, "", WorkspaceTransferMode.MOVE)
        transfer.transfer(root, "b.md", other, "", WorkspaceTransferMode.COPY)
        assertEquals(listOf("moving files/a.md -> other/a.md source=true", "moved files/a.md -> other/a.md arrived=true"), seen)
    }

    @Test
    fun `transfer move that falls back to copy and delete still reports`() = runBlocking {
        write("a.md", "A")
        val other = tmp.newFolder("other").canonicalFile
        val transfer = WorkspaceFileTransfer(fs).apply { mover = { _, _ -> throw java.io.IOException("cross device") } }
        transfer.transfer(root, "a.md", other, "", WorkspaceTransferMode.MOVE)
        assertEquals(listOf("moving files/a.md -> other/a.md source=true", "moved files/a.md -> other/a.md arrived=true"), seen)
    }

    @Test
    fun `a refused transfer move keeps the file and reports the lock code`() = runBlocking {
        write("a.md", "A")
        val other = tmp.newFolder("other").canonicalFile
        WorkspaceChanges.observer = { if (it is WorkspaceChange.Moving) throw WorkspaceLockedException("locked") }
        val result = WorkspaceFileTransfer(fs).transferBatch(root, listOf("a.md"), other, "", WorkspaceTransferMode.MOVE)
        assertEquals(WorkspaceFileOperationException.FILE_LOCKED, result.items.single().errorCode)
        assertEquals("A", File(root, "a.md").readText())
        assertFalse(File(other, "a.md").exists())
    }

    @Test
    fun `replaceWith reports only when replacing an existing file`() {
        write("out.md", "old")
        val staged = tmp.newFile("staged").apply { writeText("new") }
        fs.replaceWith(root, "out.md", staged)
        fs.replaceWith(root, "fresh.md", staged)
        assertEquals(listOf("overwrite files/out.md old -> new", "written files/out.md new"), seen)
        assertEquals("new", File(root, "out.md").readText())
        assertEquals("new", File(root, "fresh.md").readText())
    }

    @Test
    fun `an observer calling a locking file API fails fast instead of deadlocking`() {
        write("a.md", "A")
        var nested: Throwable? = null
        WorkspaceChanges.observer = { nested = runCatching { fs.readTextSnapshot(root, "a.md") }.exceptionOrNull() }
        fs.writeText(root, "a.md", "B")
        assertTrue(nested is IllegalStateException)
        assertEquals("B", File(root, "a.md").readText())
        // 观察者跑完就恢复：同一线程之后照常加锁
        WorkspaceChanges.observer = null
        assertEquals("B", fs.readTextSnapshot(root, "a.md").content)
    }

    @Test
    fun `an observer failure fails the write and leaves the file untouched`() {
        write("a.md", "A")
        WorkspaceChanges.observer = { error("boom") }
        assertTrue(runCatching { fs.writeText(root, "a.md", "B") }.isFailure)
        assertEquals("A", File(root, "a.md").readText())
    }

    @Test
    fun `a refused replaceWith leaves no temporary file behind`() {
        write("out.md", "old")
        val staged = tmp.newFile("staged").apply { writeText("new") }
        WorkspaceChanges.observer = { if (it is WorkspaceChange.Overwrite) throw WorkspaceLockedException("locked") }
        assertTrue(runCatching { fs.replaceWith(root, "out.md", staged) }.isFailure)
        assertEquals(listOf("out.md"), root.list()!!.toList())
        assertEquals("old", File(root, "out.md").readText())
    }

    @Test
    fun `written is not reported when the save fails after the observer agreed`() {
        write("a.md", "A")
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun beforeRename() = throw java.io.IOException("disk full")
        }
        assertTrue(runCatching { fs.writeText(root, "a.md", "B") }.isFailure)
        val revision = fs.readTextSnapshot(root, "a.md").revision
        assertTrue(runCatching { fs.writeTextWithRevision(root, "a.md", "C", revision) }.isFailure)
        assertEquals(listOf("overwrite files/a.md A -> B", "overwrite files/a.md A -> C"), seen)
        assertEquals("A", File(root, "a.md").readText())
    }

    @Test
    fun `an observer failing on written, created or moved does not fail the finished write`() {
        write("a.md", "A")
        WorkspaceChanges.observer = { if (it is WorkspaceChange.Written || it is WorkspaceChange.Moved || it is WorkspaceChange.Created) error("boom") }
        fs.writeText(root, "new.md", "N")
        fs.createFile(root, "made.md", "M")
        assertEquals("N", File(root, "new.md").readText())
        assertEquals("M", File(root, "made.md").readText())
        fs.writeText(root, "a.md", "B")
        fs.writeTextWithRevision(root, "a.md", "C", fs.readTextSnapshot(root, "a.md").revision)
        fs.move(root, "a.md", "b.md")
        assertEquals("C", File(root, "b.md").readText())
    }

    @Test
    fun `a locked refusal passes through the text API with its own code`() {
        write("a.md", "A")
        WorkspaceChanges.observer = { if (it is WorkspaceChange.Overwrite) throw WorkspaceLockedException("locked") }
        val revision = fs.readTextSnapshot(root, "a.md").revision
        val error = runCatching { fs.writeTextWithRevision(root, "a.md", "B", revision) }.exceptionOrNull()
        assertTrue(error is WorkspaceLockedException)
        assertEquals("A", File(root, "a.md").readText())
    }

    @Test
    fun `a refused item in a batch delete carries the lock code`() = runBlocking {
        write("a.md", "A")
        write("b.md", "B")
        WorkspaceChanges.observer = { change ->
            if (change is WorkspaceChange.Delete && change.file.name == "a.md") throw WorkspaceLockedException("locked")
        }
        val result = WorkspaceBatchDeleter(fs).deleteBatch(root, listOf("a.md", "b.md"))
        assertEquals(listOf("b.md"), result.deletedPaths)
        assertEquals(WorkspaceFileOperationException.FILE_LOCKED, result.failures.single().errorCode)
        assertTrue(File(root, "a.md").exists())
    }

    @Test
    fun `withRevision runs only at the expected revision and blocks nested locking calls`() {
        write("a.md", "A")
        val revision = fs.readTextSnapshot(root, "a.md").revision
        assertEquals("A", fs.withRevision(root, "a.md", revision) { it.decodeToString() })
        val nested = fs.withRevision(root, "a.md", revision) { runCatching { fs.readTextSnapshot(root, "a.md") }.exceptionOrNull() }
        assertTrue(nested is IllegalStateException)
        File(root, "a.md").writeText("changed")
        assertTrue(runCatching { fs.withRevision(root, "a.md", revision) { Unit } }.exceptionOrNull() is WorkspaceFileRevisionConflictException)
        // 回调结束后同一线程照常加锁
        assertEquals("changed", fs.readTextSnapshot(root, "a.md").content)
    }
}
