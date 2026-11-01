package me.rerere.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceFileCreationCompatibilityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root = "workspace"
    private val access = WorkspaceDeviceFileAccess()

    private fun manager(bindMounts: List<WorkspaceBindMount> = emptyList()) = WorkspaceManager(
        tmp.newFolder("workspaces"), bindMounts = bindMounts,
    ).also { it.ensureWorkspace(root) }

    @Test
    fun managerForwardsCreationInFilesOnlyAndPreservesWriteCompatibility() {
        val manager = manager()
        val empty = manager.createFile(root, "untitled")
        assertEquals("untitled", empty.path)
        assertEquals("", manager.readText(root, "untitled"))
        manager.mkdir(root, "nested")
        val entry = manager.createFile(root, "nested/notes.md", "中文😀")
        assertEquals("nested/notes.md", entry.path)
        assertEquals("中文😀", manager.readText(root, entry.path))
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            manager.writeText(root, entry.path, "clobber", overwrite = false)
        }
        assertEquals("中文😀", manager.readText(root, entry.path))
        manager.writeText(root, entry.path, "edit")
        assertEquals("edit", manager.readText(root, entry.path))
        assertFalse(File(manager.linuxDir(root), "untitled").exists())
        assertEquals(emptyList<String>(), manager.linuxDir(root).list()!!.toList())
    }

    @Test
    fun managerNeverResurrectsDeletedWorkspaceOrMissingParents() {
        val manager = manager()
        assertThrows(WorkspaceFileOperationException::class.java) {
            manager.createFile(root, "missing/note.md")
        }
        assertFalse(File(manager.filesDir(root), "missing").exists())
        assertTrue(manager.deleteWorkspace(root))
        val error = assertThrows(WorkspaceFileOperationException::class.java) {
            manager.createFile(root, "note.md")
        }
        assertEquals(WorkspaceFileOperationException.PARENT_NOT_FOUND, error.code)
        assertThrows(WorkspaceFileOperationException::class.java) {
            manager.writeText(root, "note.md", "", overwrite = false)
        }
        assertFalse(manager.workspaceDir(root).exists())
    }

    @Test
    fun deviceForwardsDefaultAndExplicitCharsetAndExclusiveWrite() {
        val mount = WorkspaceDeviceMount("m1", "docs", tmp.newFolder("device"))
        assertEquals(0L, access.createFile(mount, "empty").sizeBytes)
        File(mount.source, "nested").mkdir()
        val created = access.createFile(mount, "nested/notes.md", "é中文")
        assertEquals("nested/notes.md", created.path)
        assertArrayEquals("é中文".toByteArray(StandardCharsets.UTF_8), File(mount.source, created.path).readBytes())
        access.createFile(mount, "latin", "é", StandardCharsets.ISO_8859_1)
        assertArrayEquals(byteArrayOf(-23), File(mount.source, "latin").readBytes())
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            access.writeText(mount, created.path, "clobber", overwrite = false)
        }
        assertEquals("é中文", access.readText(mount, created.path))
        access.writeText(mount, created.path, "saved")
        assertEquals("saved", access.readText(mount, created.path))
    }

    @Test
    fun deviceExclusiveOperationsDoNotCreateParentsOrResurrectDeletedSource() {
        val mount = WorkspaceDeviceMount("m1", "docs", tmp.newFolder("device"))
        assertThrows(WorkspaceFileOperationException::class.java) {
            access.createFile(mount, "missing/note.md")
        }
        assertThrows(WorkspaceFileOperationException::class.java) {
            access.writeText(mount, "missing/note.md", "", overwrite = false)
        }
        assertFalse(File(mount.source, "missing").exists())
        assertTrue(mount.source.delete())
        assertThrows(IllegalArgumentException::class.java) { access.createFile(mount, "note.md") }
        assertThrows(IllegalArgumentException::class.java) {
            access.writeText(mount, "note.md", "", overwrite = false)
        }
        assertFalse(mount.source.exists())
    }

    @Test
    fun deviceSourceChangedToLinkOrRegularFileIsRejectedOnEveryCall() {
        val source = tmp.newFolder("device")
        val mount = WorkspaceDeviceMount("m1", "docs", source)
        val outside = tmp.newFolder("outside")
        assertTrue(source.delete())
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { access.createFile(mount, "note.md") }
        assertThrows(IllegalArgumentException::class.java) {
            access.writeText(mount, "note.md", "", overwrite = false)
        }
        assertFalse(File(outside, "note.md").exists())
        Files.delete(source.toPath())
        source.writeText("not a directory")
        assertThrows(IllegalArgumentException::class.java) { access.createFile(mount, "note.md") }
        assertEquals("not a directory", source.readText())
    }

    @Test
    fun deviceFilesystemWritePermissionRevocationBlocksBothExclusiveApis() {
        val source = tmp.newFolder("device")
        val mount = WorkspaceDeviceMount("m1", "docs", source)
        val originalPermissions = Files.getPosixFilePermissions(source.toPath())
        try {
            Files.setPosixFilePermissions(source.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
            ))
            // root/capability-enabled JVMs ignore mode bits; do not claim this tested
            // Android's permission gate (which lives in app, not this module).
            assumeFalse("JVM user can still write despite mode revocation", source.canWrite())
            assertThrows(IllegalArgumentException::class.java) { access.createFile(mount, "note.md") }
            assertThrows(IllegalArgumentException::class.java) {
                access.writeText(mount, "note.md", "", overwrite = false)
            }
            assertFalse(File(source, "note.md").exists())
        } finally {
            Files.setPosixFilePermissions(source.toPath(), originalPermissions)
        }
    }

    @Test
    fun rootfsCreationForFilesLinuxBindAndDeviceUsesSameExclusivePrimitive() {
        val bound = tmp.newFolder("bound")
        val manager = manager(listOf(WorkspaceBindMount(bound, "/skills")))
        val device = WorkspaceDeviceMount("m1", "docs", tmp.newFolder("device"))
        File(manager.linuxDir(root), "etc").mkdir()
        val mounts = manager.bindMounts + device.toBindMount()
        val paths = listOf(
            "/workspace/new.md" to File(manager.filesDir(root), "new.md"),
            "/etc/new.md" to File(manager.linuxDir(root), "etc/new.md"),
            "/skills/new.md" to File(bound, "new.md"),
            "/device/docs/new.md" to File(device.source, "new.md"),
        )
        paths.forEach { (guest, host) ->
            val entry = manager.writeRootfsText(root, guest, "original", overwrite = false, mounts = mounts)
            assertEquals(guest, entry.path)
            assertEquals("new.md", entry.name)
            assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
                manager.writeRootfsText(root, guest, "clobber", overwrite = false, mounts = mounts)
            }
            assertEquals("original", host.readText())
        }
    }

    @Test
    fun rootfsCreationDoesNotNormalizeInvalidLeafOrCreateParentChains() {
        val manager = manager()
        val invalid = listOf(
            " /workspace/note.md", "/workspace/note.md ", "/workspace/note.md/",
            "/workspace/./note.md", "/workspace/a/../note.md", "/workspace//note.md",
            "/workspace/a\\note.md", "/workspace/.l2s.hidden",
        )
        invalid.forEach { path ->
            assertThrows(path, WorkspaceFileOperationException::class.java) {
                manager.writeRootfsText(root, path, "", overwrite = false)
            }
        }
        val error = assertThrows(WorkspaceFileOperationException::class.java) {
            manager.writeRootfsText(root, "/workspace/missing/note.md", "", overwrite = false)
        }
        assertEquals(WorkspaceFileOperationException.PARENT_NOT_FOUND, error.code)
        assertEquals(0, manager.filesDir(root).list()!!.size)
    }

    @Test
    fun rootfsDanglingLeafDespiteEditorStatPreflightIsConflictAndParentsAreRejected() {
        val manager = manager()
        val files = manager.filesDir(root)
        val absent = File(files, "absent")
        val link = File(files, "dangling.md")
        Files.createSymbolicLink(link.toPath(), absent.toPath())
        // str_replace_editor create currently checks stat first. It is advisory;
        // the exclusive write still rejects the original dangling lexical leaf.
        assertFalse(manager.rootfsStat(root, "/workspace/dangling.md").exists)
        assertThrows(WorkspaceFileAlreadyExistsException::class.java) {
            manager.writeRootfsText(root, "/workspace/dangling.md", "x", overwrite = false)
        }
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertFalse(absent.exists())
        val actual = File(files, "actual").apply { mkdir() }
        Files.createSymbolicLink(File(files, "parent-link").toPath(), actual.toPath())
        val error = assertThrows(WorkspaceFileOperationException::class.java) {
            manager.writeRootfsText(root, "/workspace/parent-link/new.md", "x", overwrite = false)
        }
        assertEquals(WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED, error.code)
        assertFalse(File(actual, "new.md").exists())
    }

    @Test
    fun rootfsDeviceUnavailableNeverFallsBackToLinuxTrap() {
        val manager = manager()
        val source = tmp.newFolder("device")
        val device = WorkspaceDeviceMount("m1", "docs", source)
        val mounts = listOf(device.toBindMount())
        val trap = File(manager.linuxDir(root), "device/docs").apply { mkdirs() }
        assertTrue(source.delete())
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeRootfsText(root, "/device/docs/note.md", "x", overwrite = false, mounts = mounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeRootfsText(root, "/device/docs/note.md", "x", overwrite = false)
        }
        assertFalse(File(trap, "note.md").exists())
        assertFalse(source.exists())
    }

    @Test
    fun deviceAndRootfsContendersShareOneKernelClaimWithoutClobbering() {
        val manager = manager()
        val device = WorkspaceDeviceMount("m1", "docs", tmp.newFolder("device"))
        val mounts = listOf(device.toBindMount())
        val count = 24
        val start = CountDownLatch(1)
        val ready = CountDownLatch(count)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val futures = (0 until count).map { index ->
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    val payload = "client-$index"
                    val error = runCatching {
                        when (index % 3) {
                            0 -> access.createFile(device, "same.md", payload)
                            1 -> access.writeText(device, "same.md", payload, overwrite = false)
                            else -> manager.writeRootfsText(
                                root, "/device/docs/same.md", payload, overwrite = false, mounts = mounts,
                            )
                        }
                    }.exceptionOrNull()
                    index to error
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            val winners = results.filter { it.second == null }
            assertEquals(1, winners.size)
            assertEquals(count - 1, results.count { it.second is WorkspaceFileAlreadyExistsException })
            assertEquals("client-${winners.single().first}", File(device.source, "same.md").readText())
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
