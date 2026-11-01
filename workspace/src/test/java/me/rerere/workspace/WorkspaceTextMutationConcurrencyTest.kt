package me.rerere.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class WorkspaceTextMutationConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun twoFilesystemInstancesSavingSameBaselineHaveExactlyOneWinner() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("old") }
        val revision = WorkspaceFileSystem().readTextSnapshot(root, file.name).revision
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { writer -> pool.submit(Callable {
                start.await()
                try { WorkspaceFileSystem().writeTextWithRevision(root, file.name, "writer $writer", revision); true }
                catch (_: WorkspaceFileRevisionConflictException) { false }
            }) }
            start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(file.readText() in listOf("writer 1", "writer 2"))
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".l2s.edit.") })
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun canonicalRootAliasesShareMutationLock() {
        val root = tmp.newFolder()
        val alias = File(tmp.root, "alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        val file = File(root, "note.md").apply { writeText("old") }
        val fs = WorkspaceFileSystem()
        val revision = fs.readTextSnapshot(root, file.name).revision
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        fs.textSaveIo = blockingIo(paused, resume)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit(Callable { fs.writeTextWithRevision(root, file.name, "CAS", revision) })
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            val started = CountDownLatch(1)
            val legacy = pool.submit(Callable { started.countDown(); WorkspaceFileSystem().writeText(alias, file.name, "legacy") })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { legacy.get(100, TimeUnit.MILLISECONDS) }
            assertEquals("old", file.readText())
            resume.countDown()
            first.get(5, TimeUnit.SECONDS)
            legacy.get(5, TimeUnit.SECONDS)
            assertEquals("legacy", file.readText())
        } finally { resume.countDown(); pool.shutdownNow() }
    }

    @Test fun legacyWriteCompletingBeforeCasCausesConflictRatherThanLostUpdate() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("old") }
        val revision = WorkspaceFileSystem().readTextSnapshot(root, file.name).revision
        WorkspaceFileSystem().writeText(root, file.name, "legacy")
        assertThrows(WorkspaceFileRevisionConflictException::class.java) {
            WorkspaceFileSystem().writeTextWithRevision(root, file.name, "CAS", revision)
        }
        assertEquals("legacy", file.readText())
    }

    @Test fun rootWideLockSerializesRecursiveDeleteMoveAndImportAgainstSave() {
        for (operation in listOf("delete", "move", "import")) {
            val root = tmp.newFolder()
            File(root, "dir").mkdir()
            val file = File(root, "dir/note.md").apply { writeText("old") }
            val fs = WorkspaceFileSystem()
            val revision = fs.readTextSnapshot(root, "dir/note.md").revision
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            fs.textSaveIo = blockingIo(paused, resume)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val save = pool.submit(Callable { fs.writeTextWithRevision(root, "dir/note.md", "CAS", revision) })
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                val started = CountDownLatch(1)
                val mutation = pool.submit(Callable {
                    started.countDown()
                    val other = WorkspaceFileSystem()
                    when (operation) {
                        "delete" -> other.delete(root, "dir", recursive = true)
                        "move" -> other.move(root, "dir", "moved")
                        else -> other.importBytes(root, "unrelated.txt", ByteArrayInputStream("import".toByteArray()))
                    }
                })
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { mutation.get(100, TimeUnit.MILLISECONDS) }
                assertEquals("old", file.readText())
                assertFalse(File(root, "unrelated.txt").exists())
                resume.countDown()
                save.get(5, TimeUnit.SECONDS)
                mutation.get(5, TimeUnit.SECONDS)
                when (operation) {
                    "delete" -> assertFalse(file.exists())
                    "move" -> assertEquals("CAS", File(root, "moved/note.md").readText())
                    else -> assertEquals("import", File(root, "unrelated.txt").readText())
                }
            } finally { resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun wholeFolderImportHoldsRootLockNotOnlyEachImportedLeaf() {
        val root = tmp.newFolder()
        val file = File(root, "note.md").apply { writeText("old") }
        val revision = WorkspaceFileSystem().readTextSnapshot(root, file.name).revision
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val node = object : WorkspaceImportNode {
            override val stableId = "folder"
            override val displayName = "folder"
            override val isDirectory = true
            override fun listChildren(): List<WorkspaceImportNode> {
                paused.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                return emptyList()
            }
            override fun openInputStream() = null
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val importing = pool.submit(Callable { runBlocking { WorkspaceTreeImporter().import(root, "", node) } })
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            val started = CountDownLatch(1)
            val save = pool.submit(Callable {
                started.countDown()
                WorkspaceFileSystem().writeTextWithRevision(root, file.name, "CAS", revision)
            })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { save.get(100, TimeUnit.MILLISECONDS) }
            assertEquals("old", file.readText())
            resume.countDown()
            importing.get(5, TimeUnit.SECONDS)
            save.get(5, TimeUnit.SECONDS)
            assertEquals("CAS", file.readText())
        } finally { resume.countDown(); pool.shutdownNow() }
    }

    @Test fun twoDeviceAccessInstancesAndDifferentMountIdsSameSourceHaveOneWinner() {
        val source = tmp.newFolder().canonicalFile
        val file = File(source, "note.md").apply { writeText("old") }
        val mounts = listOf(WorkspaceDeviceMount("one", "first", source), WorkspaceDeviceMount("two", "second", source))
        val accesses = listOf(WorkspaceDeviceFileAccess(), WorkspaceDeviceFileAccess())
        val revision = accesses[0].readTextSnapshot(mounts[0], file.name).revision
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val saves = accesses.mapIndexed { index, access -> pool.submit(Callable {
                start.await()
                try { access.writeTextWithRevision(mounts[index], file.name, "device $index", revision); true }
                catch (_: WorkspaceFileRevisionConflictException) { false }
            }) }
            start.countDown()
            assertEquals(1, saves.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(file.readText() in listOf("device 0", "device 1"))
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun differentCanonicalRootsDoNotBlockEachOther() {
        val firstRoot = tmp.newFolder()
        val secondRoot = tmp.newFolder()
        File(firstRoot, "note.md").writeText("old")
        File(secondRoot, "note.md").writeText("other")
        val fs = WorkspaceFileSystem()
        val revision = fs.readTextSnapshot(firstRoot, "note.md").revision
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        fs.textSaveIo = blockingIo(paused, resume)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val save = pool.submit(Callable { fs.writeTextWithRevision(firstRoot, "note.md", "new", revision) })
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            pool.submit(Callable { WorkspaceFileSystem().writeText(secondRoot, "note.md", "unrelated") }).get(5, TimeUnit.SECONDS)
            assertEquals("unrelated", File(secondRoot, "note.md").readText())
            resume.countDown()
            save.get(5, TimeUnit.SECONDS)
        } finally { resume.countDown(); pool.shutdownNow() }
    }

    private fun blockingIo(paused: CountDownLatch, resume: CountDownLatch) = object : WorkspaceTextSaveIo() {
        override fun beforeRename() { paused.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
    }
}
