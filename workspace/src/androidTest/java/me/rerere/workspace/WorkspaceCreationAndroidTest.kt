package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs against Android's real libcore/filesystem, not the host JVM provider. */
@RunWith(AndroidJUnit4::class)
class WorkspaceCreationAndroidTest {
    private lateinit var root: File
    private val fs = WorkspaceFileSystem()

    @Before fun setup() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "implementation-create-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun defaultMdAndEmptyExtensionDirectoryUseActualAndroidProvider() {
        val leaf = WorkspaceFileNames.creationName("笔记😀", "md")
        val file = fs.createFile(root, leaf)
        assertEquals("笔记😀.md", file.name)
        assertFalse(file.isDirectory)
        assertEquals(0L, file.sizeBytes)
        fs.writeText(root, file.path, "### 中文\n**实时** _样式_ ~~删除~~\n")
        assertTrue(fs.readText(root, file.path).contains("实时"))
        val folder = fs.createDirectory(root, WorkspaceFileNames.creationName("笔记", ""))
        assertTrue(folder.isDirectory)
        assertEquals(0, File(root, folder.path).list()!!.size)
    }

    @Test fun fileAndDirectoryConflictsNeverModifyOriginal() {
        fs.createFile(root, "notes.md", "original")
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            fs.createFile(root, "notes.md", "clobber")
        }
        assertEquals("original", fs.readText(root, "notes.md"))
        fs.createDirectory(root, "folder")
        File(root, "folder/keep.txt").writeText("keep")
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) { fs.createDirectory(root, "folder") }
        assertEquals("keep", File(root, "folder/keep.txt").readText())
    }

    @Test fun noFollowRejectsDanglingLeafAndParentLinks() {
        Files.createSymbolicLink(File(root, "dangling.md").toPath(), File(root, "absent").toPath())
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) { fs.createFile(root, "dangling.md") }
        assertFalse(File(root, "absent").exists())
        fs.createDirectory(root, "real")
        Files.createSymbolicLink(File(root, "linked").toPath(), File(root, "real").toPath())
        assertThrows(WorkspaceFileOperationException::class.java) { fs.createFile(root, "linked/new.md") }
        assertThrows(WorkspaceFileOperationException::class.java) { fs.createDirectory(root, "linked/new-folder") }
        assertEquals(0, File(root, "real").list()!!.size)
    }

    @Test fun sixteenConcurrentCreatesHaveExactlyOneWinnerForBothKinds() {
        for (directory in listOf(false, true)) {
            val pool = Executors.newFixedThreadPool(16)
            val start = CountDownLatch(1)
            val leaf = if (directory) "race-dir" else "race.md"
            try {
                val tasks = (0 until 16).map { n ->
                    pool.submit(Callable {
                        start.await()
                        try {
                            if (directory) fs.createDirectory(root, leaf)
                            else fs.createFile(root, leaf, "winner-$n")
                            true
                        } catch (_: WorkspaceFileAlreadyExistsException) {
                            false
                        }
                    })
                }
                start.countDown()
                assertEquals(1, tasks.count { it.get(15, TimeUnit.SECONDS) })
                if (directory) assertEquals(0, File(root, leaf).list()!!.size)
                else assertTrue(fs.readText(root, leaf).startsWith("winner-"))
            } finally { pool.shutdownNow() }
        }
    }
}
