package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

class WorkspaceTextOverwriteTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * A child JVM overwrites a file under `ulimit -f`, so the write fails part-way with EFBIG, the same
     * error path as a full disk. The original must survive whole and no temporary file may stay behind.
     */
    @Test fun overwriteFailingPartWayKeepsTheOriginal() {
        val root = tmp.newFolder()
        val original = "o".repeat(100_000)
        val file = File(root, "note.md").apply { writeText(original) }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            "bash", "-c", "ulimit -f 128; exec \"$0\" -XX:-UsePerfData -Xshare:off -cp \"$1\" ${OverwriteChild::class.java.name} \"$2\"",
            java, System.getProperty("java.class.path"), root.path,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertNotEquals("child should fail: $output", 0, process.waitFor())
        assertTrue(output, output.contains("WorkspaceFileOperationException") || output.contains("IOException"))
        assertEquals(original.length.toLong(), file.length())
        assertEquals(original, file.readText())
        assertEquals(listOf("note.md"), root.list()!!.toList())
    }

    @Test fun overwriteKeepsPermissionsAndWritesThroughSymlinks() {
        val root = tmp.newFolder()
        val script = File(root, "run.sh").apply { writeText("old") }
        Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        val link = File(root, "link.sh")
        Files.createSymbolicLink(link.toPath(), script.toPath().fileName)
        val fs = WorkspaceFileSystem()

        fs.writeText(root, "link.sh", "new")

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("new", script.readText())
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(script.toPath())))
        assertEquals(listOf("link.sh", "run.sh"), root.list()!!.sorted())
    }

    @Test fun overwriteCleansStaleTempFilesInSameDirectoryOnly() {
        val now = System.currentTimeMillis()
        val hours25Ago = now - 25L * 60L * 60L * 1_000L
        val hours23Ago = now - 23L * 60L * 60L * 1_000L

        for (useCas in listOf(false, true)) {
            val root = tmp.newFolder()
            val target = File(root, "note.md").apply { writeText("old") }
            val stale = File(root, workspaceTempFileName()).apply {
                writeText("leftover")
                assertTrue(setLastModified(hours25Ago))
            }
            val userFiles = NOT_GENERATED_NAMES.map { name ->
                File(root, name).apply {
                    writeText("user file")
                    assertTrue(setLastModified(hours25Ago))
                }
            }
            val fresh = File(root, workspaceTempFileName()).apply { writeText("recent") }
            val young = File(root, workspaceTempFileName()).apply {
                writeText("almost old")
                assertTrue(setLastModified(hours23Ago))
            }
            val normalOld = File(root, "keep.txt").apply {
                writeText("normal")
                assertTrue(setLastModified(hours25Ago))
            }
            val oldDir = File(root, workspaceTempFileName()).apply {
                assertTrue(mkdir())
                assertTrue(setLastModified(hours25Ago))
            }
            val oldLink = File(root, workspaceTempFileName())
            Files.createSymbolicLink(oldLink.toPath(), normalOld.toPath().fileName)
            val subDir = File(root, "sub").apply { assertTrue(mkdir()) }
            val nestedStale = File(subDir, workspaceTempFileName()).apply {
                writeText("nested")
                assertTrue(setLastModified(hours25Ago))
            }

            val fs = WorkspaceFileSystem()
            if (useCas) {
                val snap = fs.readTextSnapshot(root, target.name)
                fs.writeTextWithRevision(root, target.name, "updated", snap.revision)
            } else {
                fs.writeText(root, target.name, "updated")
            }

            assertEquals("updated", target.readText())
            assertFalse("stale temp should be pruned (useCas=$useCas)", stale.exists())
            assertEquals(
                "files the app never generates must stay (useCas=$useCas)",
                emptyList<String>(),
                userFiles.filterNot { it.exists() }.map { it.name },
            )
            assertTrue(fresh.exists())
            assertTrue(young.exists())
            assertTrue(normalOld.exists())
            assertTrue(oldDir.isDirectory)
            assertTrue(Files.isSymbolicLink(oldLink.toPath()))
            assertEquals("normal", normalOld.readText())
            assertTrue(nestedStale.exists())
        }

        // Overwriting a target whose own name starts with the temp prefix keeps the target intact.
        val root = tmp.newFolder()
        val tempNamedTarget = File(root, workspaceTempFileName()).apply {
            writeText("old recovered")
            assertTrue(setLastModified(hours25Ago))
        }
        WorkspaceFileSystem().writeText(root, tempNamedTarget.name, "new recovered")
        assertTrue(tempNamedTarget.exists())
        assertEquals("new recovered", tempNamedTarget.readText())
    }

    @Test fun failedOverwriteAndNoopDoNotCleanStaleTempFiles() {
        val hours25Ago = System.currentTimeMillis() - 25L * 60L * 60L * 1_000L
        val root = tmp.newFolder()
        val target = File(root, "note.md").apply { writeText("original") }
        val stale = File(root, workspaceTempFileName()).apply {
            writeText("leftover")
            assertTrue(setLastModified(hours25Ago))
        }
        val fs = WorkspaceFileSystem()
        val snap = fs.readTextSnapshot(root, target.name)

        // No-op CAS save does not touch the directory.
        fs.writeTextWithRevision(root, target.name, snap.content, snap.revision)
        assertTrue(stale.exists())

        // Failed rename does not trigger stale temp cleanup.
        fs.textSaveIo = object : WorkspaceTextSaveIo() {
            override fun move(temp: Path, target: Path) { throw IOException("simulated failure") }
        }
        assertThrows(WorkspaceFileOperationException::class.java) {
            fs.writeText(root, target.name, "changed")
        }
        assertEquals("original", target.readText())
        assertTrue(stale.exists())
    }

    @Test fun cleanupFailureDoesNotFailOverwrite() {
        val root = tmp.newFolder()
        val dir = File(root, "locked").apply { assertTrue(mkdir()) }
        val target = File(dir, "note.md").apply { writeText("old") }
        val stale = File(dir, workspaceTempFileName()).apply {
            writeText("leftover")
            assertTrue(setLastModified(System.currentTimeMillis() - 25L * 60L * 60L * 1_000L))
        }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("-wx------"))
        try {
            assumeTrue(
                "directory must be unreadable for this test (e.g. non-root POSIX)",
                runCatching { Files.newDirectoryStream(dir.toPath()).use { } }.isFailure,
            )
            WorkspaceFileSystem().writeText(root, "locked/note.md", "new")
            assertEquals("new", target.readText())
            assertTrue(stale.exists())
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    private companion object {
        /** Names that share the temp prefix but that `workspaceTempFileName` cannot produce. */
        val NOT_GENERATED_NAMES = listOf(
            "${WORKSPACE_TEMP_FILE_PREFIX}notes.md",
            "${WORKSPACE_TEMP_FILE_PREFIX}b-stale",
            "${WORKSPACE_TEMP_FILE_PREFIX}0123456789ab4de",
            "${WORKSPACE_TEMP_FILE_PREFIX}0123456789ab4def0",
            "${WORKSPACE_TEMP_FILE_PREFIX}0123456789AB4DEF",
            "${WORKSPACE_TEMP_FILE_PREFIX}0123456789ab4def.md",
        )
    }
}

internal object OverwriteChild {
    @JvmStatic fun main(args: Array<String>) {
        try {
            WorkspaceFileSystem().writeText(File(args[0]), "note.md", "n".repeat(300_000))
        } catch (error: Exception) {
            System.err.println(error)
            exitProcess(1)
        }
        exitProcess(0)
    }
}
