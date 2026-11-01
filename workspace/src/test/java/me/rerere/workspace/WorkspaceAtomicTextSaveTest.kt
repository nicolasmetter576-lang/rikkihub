package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

class WorkspaceAtomicTextSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun failedExclusiveTempOpenCannotTouchExistingFileOrUnownedTemp() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("original") }
        val unrelated = File(root, ".l2s.edit.unrelated").apply { writeText("other owner") }
        val fs = WorkspaceFileSystem()
        val snapshot = fs.readTextSnapshot(root, file.name)
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun openTemp(path: Path): FileChannel { throw IOException("private host path /secret") }
        }
        val error = assertThrows(WorkspaceFileOperationException::class.java) { fs.writeTextWithRevision(root, file.name, "new", snapshot.revision) }
        assertEquals("FILE_IO_ERROR", error.code)
        assertFalse(error.message!!.contains("/secret"))
        assertNull(error.cause)
        assertEquals("original", file.readText())
        assertEquals("other owner", unrelated.readText())
        assertEquals(2, root.listFiles()!!.size)
    }

    @Test fun failedTempWriteKeepsOldFileAndCleansOnlyClaimedTemp() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("original") }
        val fs = WorkspaceFileSystem()
        val snapshot = fs.readTextSnapshot(root, file.name)
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun openTemp(path: Path): FileChannel = super.openTemp(path).also { it.close() }
        }
        assertThrows(WorkspaceFileOperationException::class.java) { fs.writeTextWithRevision(root, file.name, "new", snapshot.revision) }
        assertEquals("original", file.readText())
        assertEquals(listOf("note.md"), root.list()!!.toList())
    }

    @Test fun unsupportedAtomicMoveNeverFallsBackToCopyOrTruncate() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("original\r\n") }
        val fs = WorkspaceFileSystem()
        val snapshot = fs.readTextSnapshot(root, file.name)
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun move(temp: Path, target: Path) { throw AtomicMoveNotSupportedException(temp.toString(), target.toString(), "unsupported") }
        }
        val error = assertThrows(WorkspaceFileOperationException::class.java) { fs.writeTextWithRevision(root, file.name, "new\n", snapshot.revision) }
        assertEquals("ATOMIC_SAVE_UNSUPPORTED", error.code)
        assertEquals("original\r\n", file.readText())
        assertEquals(listOf("note.md"), root.list()!!.toList())
    }

    @Test fun ordinaryMoveFailureKeepsOldFileAndDoesNotDeleteUnrelatedTemps() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("original") }
        val unrelated = File(root, ".l2s.edit.other").apply { writeText("keep") }
        val fs = WorkspaceFileSystem()
        val snapshot = fs.readTextSnapshot(root, file.name)
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun move(temp: Path, target: Path) { throw IOException("disk full") }
        }
        assertEquals("FILE_IO_ERROR", assertThrows(WorkspaceFileOperationException::class.java) { fs.writeTextWithRevision(root, file.name, "new", snapshot.revision) }.code)
        assertEquals("original", file.readText())
        assertEquals("keep", unrelated.readText())
        assertEquals(2, root.listFiles()!!.size)
    }

    @Test fun externalChangeAndDeletionBetweenTempWriteAndRenameAreRechecked() {
        for (delete in listOf(false, true)) {
            val root = tmp.newFolder()
            val file = File(root, "note.md").apply { writeText("original") }
            val fs = WorkspaceFileSystem()
            val snapshot = fs.readTextSnapshot(root, file.name)
            fs.textSaveIo = object : WorkspaceTextSaveIo() {
                override fun beforeRename() { if (delete) file.delete() else file.writeText("external") }
                override fun move(temp: Path, target: Path) { fail("CAS must fail before rename") }
            }
            assertThrows(WorkspaceFileRevisionConflictException::class.java) { fs.writeTextWithRevision(root, file.name, "new", snapshot.revision) }
            if (delete) assertFalse(file.exists()) else assertEquals("external", file.readText())
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".l2s.edit.") })
        }
    }

    @Test fun failedFsyncOrMetadataPreservationNeverRenamesOrTruncatesOriginal() {
        for (failure in listOf("fsync", "metadata")) {
            val root = tmp.newFolder()
            val file = File(root, "note.md").apply { writeText("original") }
            val fs = WorkspaceFileSystem()
            val snapshot = fs.readTextSnapshot(root, file.name)
            fs.textSaveIo = object : WorkspaceTextSaveIo() {
                override fun force(channel: FileChannel) {
                    if (failure == "fsync") throw IOException("force failure")
                    super.force(channel)
                }
                override fun preserveMetadata(target: Path, temp: Path) {
                    if (failure == "metadata") throw java.nio.file.AccessDeniedException("private path")
                    super.preserveMetadata(target, temp)
                }
                override fun move(temp: Path, target: Path) { fail("No rename after failure") }
            }
            val error = assertThrows(WorkspaceFileOperationException::class.java) { fs.writeTextWithRevision(root, file.name, "new", snapshot.revision) }
            assertEquals(if (failure == "metadata") "FILE_PERMISSION_DENIED" else "FILE_IO_ERROR", error.code)
            assertFalse(error.message!!.contains("private path"))
            assertEquals("original", file.readText())
            assertEquals(listOf("note.md"), root.list()!!.toList())
        }
    }

    @Test fun tempIsInSameDirectoryAndFsyncCompletesBeforeAtomicMove() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("original") }
        val fs = WorkspaceFileSystem()
        val snapshot = fs.readTextSnapshot(root, file.name)
        var forced = false
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun force(channel: FileChannel) { super.force(channel); forced = true }
            override fun move(temp: Path, target: Path) {
                assertTrue(forced)
                assertEquals(target.parent, temp.parent)
                assertTrue(temp.fileName.toString().startsWith(".l2s.edit."))
                super.move(temp, target)
            }
        }
        fs.writeTextWithRevision(root, file.name, "new", snapshot.revision)
        assertEquals("new", file.readText())
    }

    @Test fun tempPathReplacedByAnotherOwnerIsNeitherRenamedNorDeleted() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("original") }
        val fs = WorkspaceFileSystem()
        val snapshot = fs.readTextSnapshot(root, file.name)
        lateinit var temp: Path
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun openTemp(path: Path): FileChannel { temp = path; return super.openTemp(path) }
            override fun beforeRename() {
                val other = File(root, "other").toPath()
                Files.write(other, "somebody else".toByteArray(), CREATE_NEW, WRITE)
                Files.move(other, temp, REPLACE_EXISTING)
            }
            override fun move(temp: Path, target: Path) { fail("Must not move someone else's temp") }
        }
        assertThrows(WorkspaceFileOperationException::class.java) { fs.writeTextWithRevision(root, file.name, "new", snapshot.revision) }
        assertEquals("original", file.readText())
        assertEquals("somebody else", temp.toFile().readText())
    }
}
