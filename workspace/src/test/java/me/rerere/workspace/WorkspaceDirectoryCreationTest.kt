package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceDirectoryCreationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fs = WorkspaceFileSystem()
    private val access = WorkspaceDeviceFileAccess()

    @Test
    fun createsEmptyRootAndNestedDirectoriesWithCorrectEntries() {
        val root = tmp.newFolder("files")
        val directoryName = WorkspaceFileNames.creationName("目录😀", "")
        val rootEntry = fs.createDirectory(root, directoryName)
        assertEquals(directoryName, rootEntry.path)
        assertEquals(directoryName, rootEntry.name)
        assertTrue(rootEntry.isDirectory)
        assertEquals(0L, rootEntry.sizeBytes)
        assertTrue(rootEntry.updatedAt > 0L)
        assertEquals(0, File(root, directoryName).list()!!.size)
        val nested = fs.createDirectory(root, "$directoryName/nested")
        assertEquals("$directoryName/nested", nested.path)
        assertEquals("nested", nested.name)
        assertTrue(nested.isDirectory)
        assertEquals(0, File(root, nested.path).list()!!.size)
    }

    @Test
    fun existingDirectoryIsNeverAdoptedMergedOrCleared() {
        val root = tmp.newFolder("files")
        val directory = File(root, "existing").apply { mkdir() }
        File(directory, "keep").writeText("original")
        val error = assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            fs.createDirectory(root, "existing")
        }
        assertEquals(WorkspaceFileOperationException.FILE_ALREADY_EXISTS, error.code)
        assertEquals(listOf("keep"), directory.list()!!.toList())
        assertEquals("original", File(directory, "keep").readText())
        fs.createDirectory(root, "empty")
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) { fs.createDirectory(root, "empty") }
        // Existing legacy mkdir intentionally continues to adopt directories.
        assertTrue(fs.mkdir(root, "existing").isDirectory)
    }

    @Test
    fun regularFileAtLeafIsConflictAndContentsRemainUnchanged() {
        val root = tmp.newFolder("files")
        val file = File(root, "existing").apply { writeText("original") }
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) { fs.createDirectory(root, file.name) }
        assertEquals("original", file.readText())
    }

    @Test
    fun invalidLexicalPathsAndNamesHaveNoSideEffects() {
        val root = tmp.newFolder("files")
        val invalid = listOf(
            "", ".", "..", "/directory", "../directory", "a/../directory", "a/./directory",
            "a//directory", "a/", "a\\directory", "a\u0000b", "a\u007fb",
            ".l2s.hidden", " directory", "directory ", "中".repeat(86),
        )
        invalid.forEach { path ->
            assertThrows(path, WorkspaceFileOperationException::class.java) { fs.createDirectory(root, path) }
        }
        assertEquals(0, root.list()!!.size)
        assertFalse(File(tmp.root, "directory").exists())
    }

    @Test
    fun missingRootAndParentsAreNotCreatedAndFileParentIsRejected() {
        val root = tmp.newFolder("files")
        assertCode(WorkspaceFileOperationException.PARENT_NOT_FOUND) {
            fs.createDirectory(root, "missing/nested/new")
        }
        assertFalse(File(root, "missing").exists())
        val missingRoot = File(tmp.root, "missing-root")
        assertCode(WorkspaceFileOperationException.PARENT_NOT_FOUND) { fs.createDirectory(missingRoot, "new") }
        assertFalse(missingRoot.exists())
        File(root, "file-parent").writeText("keep")
        assertCode(WorkspaceFileOperationException.PARENT_NOT_DIRECTORY) { fs.createDirectory(root, "file-parent/new") }
        assertEquals("keep", File(root, "file-parent").readText())
    }

    @Test
    fun occupiedLeafLinksInsideOutsideAndDanglingAreConflicts() {
        val root = tmp.newFolder("files")
        val inside = File(root, "real").apply { mkdir() }
        val outside = tmp.newFolder("outside")
        val missing = File(tmp.root, "absent")
        listOf(inside, outside, missing).forEachIndexed { index, target ->
            val leaf = File(root, "link-$index")
            Files.createSymbolicLink(leaf.toPath(), target.toPath())
            assertThrows(WorkspaceFileAlreadyExistsException::class.java) { fs.createDirectory(root, leaf.name) }
            assertTrue(Files.isSymbolicLink(leaf.toPath()))
            assertEquals(target.toPath(), Files.readSymbolicLink(leaf.toPath()))
        }
        assertEquals(0, inside.list()!!.size)
        assertEquals(0, outside.list()!!.size)
        assertFalse(missing.exists())
    }

    @Test
    fun parentLinksInsideOutsideAndDanglingAreRejectedAtEveryDepth() {
        val root = tmp.newFolder("files")
        val inside = File(root, "real").apply { mkdir() }
        val outside = tmp.newFolder("outside")
        val missing = File(tmp.root, "absent")
        val nested = File(root, "nested").apply { mkdir() }
        listOf(inside, outside, missing).forEachIndexed { index, target ->
            val parent = File(nested, "link-$index")
            Files.createSymbolicLink(parent.toPath(), target.toPath())
            assertCode(WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED) {
                fs.createDirectory(root, "nested/${parent.name}/new")
            }
            assertFalse(File(target, "new").exists())
            assertTrue(Files.isSymbolicLink(parent.toPath()))
        }
        assertFalse(missing.exists())
    }

    @Test
    fun rootCanonicalAliasIsAllowedWithoutFollowingRelativeLinks() {
        val root = tmp.newFolder("files")
        val alias = File(tmp.root, "root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertTrue(fs.createDirectory(alias, "new").isDirectory)
        assertTrue(File(root, "new").isDirectory)
    }

    @Test
    fun thirtyTwoIndependentDirectoryContendersHaveExactlyOneWinner() {
        val root = tmp.newFolder("files")
        val count = 32
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val futures = (0 until count).map {
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching { WorkspaceFileSystem().createDirectory(root, "same") }.exceptionOrNull()
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == null })
            assertEquals(count - 1, results.count { it is WorkspaceFileAlreadyExistsException })
            assertEquals(listOf("same"), root.list()!!.toList())
            assertTrue(File(root, "same").isDirectory)
            assertEquals(0, File(root, "same").list()!!.size)
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun managerAndDeviceForwardExclusiveDirectoryCreationAndKeepLegacyMkdir() {
        val manager = WorkspaceManager(tmp.newFolder("workspaces")).also { it.ensureWorkspace("root") }
        val entry = manager.createDirectory("root", "notes")
        assertTrue(entry.isDirectory)
        assertEquals("notes", entry.path)
        assertTrue(manager.createDirectory("root", "notes/nested").isDirectory)
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) { manager.createDirectory("root", "notes") }
        assertTrue(manager.mkdir("root", "notes").isDirectory)
        assertThrows(WorkspaceFileOperationException::class.java) { manager.createDirectory("root", "missing/new") }
        assertFalse(File(manager.filesDir("root"), "missing").exists())
        assertEquals(0, manager.linuxDir("root").list()!!.size)
        val device = WorkspaceDeviceMount("m1", "docs", tmp.newFolder("device"))
        assertTrue(access.createDirectory(device, "notes").isDirectory)
        assertEquals("notes/nested", access.createDirectory(device, "notes/nested").path)
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) { access.createDirectory(device, "notes") }
        assertThrows(WorkspaceFileOperationException::class.java) { access.createDirectory(device, "missing/new") }
        assertFalse(File(device.source, "missing").exists())
    }

    @Test
    fun managerAndDeviceDoNotResurrectDeletedRoots() {
        val manager = WorkspaceManager(tmp.newFolder("workspaces")).also { it.ensureWorkspace("root") }
        manager.deleteWorkspace("root")
        assertCode(WorkspaceFileOperationException.PARENT_NOT_FOUND) { manager.createDirectory("root", "new") }
        assertFalse(manager.workspaceDir("root").exists())
        val device = WorkspaceDeviceMount("m1", "docs", tmp.newFolder("device"))
        assertTrue(device.source.delete())
        assertThrows(IllegalArgumentException::class.java) { access.createDirectory(device, "new") }
        assertFalse(device.source.exists())
    }

    @Test
    fun deviceRejectsChangedSourceAndRelativeSymlinkParent() {
        val source = tmp.newFolder("device")
        val device = WorkspaceDeviceMount("m1", "docs", source)
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(source, "link").toPath(), outside.toPath())
        assertCode(WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED) { access.createDirectory(device, "link/new") }
        Files.delete(File(source, "link").toPath())
        assertTrue(source.delete())
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { access.createDirectory(device, "new") }
        assertFalse(File(outside, "new").exists())
    }

    @Test
    fun deviceWritePermissionRevocationBlocksExclusiveDirectoryCreation() {
        val source = tmp.newFolder("device")
        val device = WorkspaceDeviceMount("m1", "docs", source)
        val originalPermissions = Files.getPosixFilePermissions(source.toPath())
        try {
            Files.setPosixFilePermissions(source.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
            ))
            assumeFalse("JVM user can still write despite mode revocation", source.canWrite())
            assertThrows(IllegalArgumentException::class.java) { access.createDirectory(device, "new") }
            assertFalse(File(source, "new").exists())
        } finally {
            Files.setPosixFilePermissions(source.toPath(), originalPermissions)
        }
    }

    private fun assertCode(code: String, operation: () -> Unit) {
        val error = assertThrows(WorkspaceFileOperationException::class.java) { operation() }
        assertEquals(code, error.code)
    }
}
