package me.rerere.rikkihub.workspace

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceMountSelection
import me.rerere.workspace.WorkspacePtySession
import me.rerere.workspace.WorkspaceShellRequest
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The workspace runtime as production wires it (Koin [WorkspaceBackend] = LocalWorkspaceBackend):
 * files live in the app's own storage, PRoot shells run in the app process tree.
 *
 * The rootfs test downloads the official Ubuntu base image for the device ABI; it is
 * skipped when `-e rootfs false` is passed.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WorkspaceRuntimeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val backend get() = GlobalContext.get().get<WorkspaceBackend>()

    @Test(timeout = 60_000)
    fun a_fileOperationsLiveInAppStorage() {
        val root = "itest-files"
        backend.deleteWorkspace(root)
        assertFalse(backend.workspaceExists(root))
        backend.ensureWorkspace(root)
        backend.createFile(root, "hello.txt", "hi there")
        assertEquals("hi there", backend.readText(root, "hello.txt"))
        backend.copyFile(root, "hello.txt", "copy.txt")
        assertEquals("hi there", backend.readText(root, "copy.txt"))
        assertEquals("hi there",
            File(context.filesDir.canonicalFile, "workspaces/$root/files/hello.txt").readText())
        val out = ByteArrayOutputStream()
        backend.exportFile(root, "hello.txt", WorkspaceStorageArea.FILES, out)
        assertEquals("hi there", out.toString())
        assertTrue(backend.deleteWorkspace(root))
    }

    @Test(timeout = 15 * 60_000)
    fun b_rootfsCommandsMountsAndPty() = runBlocking {
        assumeTrue(args.getString("rootfs") != "false")
        val root = "itest-shell"
        backend.ensureWorkspace(root)
        if (!backend.hasRootfs(root)) {
            backend.installRootfs(root, defaultRootfsUrl())
        }
        assertTrue(backend.hasRootfs(root))

        val result = backend.executeCommand(root,
            "cat /proc/self/status | grep '^Uid'; echo ok > /workspace/out.txt",
            timeoutMillis = 60_000)
        assertEquals(result.stderr, 0, result.exitCode)
        // PRoot fakes root inside; the kernel-level uid is the app's own.
        assertTrue(result.stdout, result.stdout.contains("${Process.myUid()}"))
        assertEquals("ok\n", backend.readText(root, "out.txt"))

        // /skills is a direct bind of the app's skills tree: shell edits are visible to the app.
        val marker = File(context.filesDir.canonicalFile, "${FileFolders.SKILLS}/itest-bind.txt")
        marker.delete()
        val write = backend.executeCommand(root, "echo bound > /skills/itest-bind.txt",
            timeoutMillis = 30_000, mounts = WorkspaceMountSelection(exposedTargets = setOf("/skills")))
        assertEquals(write.stderr, 0, write.exitCode)
        assertEquals("bound\n", marker.readText())
        marker.delete()

        val pty = backend.openShell(root, WorkspaceShellRequest(rows = 24, columns = 80))
        try {
            assertTrue("terminals attach to a duplicate PTY master", pty is WorkspacePtySession)
            (pty as WorkspacePtySession).dupMaster().close()
            pty.write("echo pty-\$((40+2))\nexit 3\n".toByteArray())
            val seen = StringBuilder()
            val buffer = ByteArray(4096)
            withTimeout(60_000) {
                while (!seen.contains("pty-42")) {
                    val n = pty.read(buffer)
                    if (n <= 0) break
                    seen.append(String(buffer, 0, n))
                }
            }
            assertTrue(seen.toString(), seen.contains("pty-42"))
            assertEquals(3, withTimeout(30_000) { pty.awaitExit() })
        } finally {
            pty.close()
        }
    }

    @Test(timeout = 5 * 60_000)
    fun c_unavailableDeviceDirectoryIsANoteFileThatRejectsWrites() = runBlocking {
        assumeTrue(args.getString("rootfs") != "false")
        val root = "itest-shell" // rootfs installed by b_
        assumeTrue(backend.hasRootfs(root))
        val mounts = WorkspaceMountSelection(exposedTargets = emptySet(), unavailableDeviceAliases = listOf("gone"))

        // The shell still starts; the alias exists and explains itself instead of failing the whole workspace.
        val list = backend.executeCommand(root, "cat /device/gone", timeoutMillis = 30_000, mounts = mounts)
        assertEquals(list.stderr, 0, list.exitCode)
        assertTrue(list.stdout, list.stdout.contains("unavailable"))

        // Writes fail loudly rather than landing in the rootfs.
        val write = backend.executeCommand(root, "echo x > /device/gone/new.txt", timeoutMillis = 30_000, mounts = mounts)
        assertTrue("write below the placeholder must fail", write.exitCode != 0)
        val mkdir = backend.executeCommand(root, "mkdir -p /device/gone/sub", timeoutMillis = 30_000, mounts = mounts)
        assertTrue("mkdir below the placeholder must fail", mkdir.exitCode != 0)
        val leaked = backend.executeCommand(root, "ls /device/gone/ 2>/dev/null; ls -d /device/gone/sub 2>/dev/null; echo end",
            timeoutMillis = 30_000, mounts = mounts)
        assertFalse(leaked.stdout, leaked.stdout.contains("new.txt") || leaked.stdout.contains("sub"))
        // Without the placeholder binding nothing was created in the rootfs either.
        val bare = backend.executeCommand(root, "test -e /device/gone && echo leaked || echo clean",
            timeoutMillis = 30_000, mounts = WorkspaceMountSelection(exposedTargets = emptySet()))
        assertEquals("clean\n", bare.stdout)

        // App-side file tools are refused too.
        assertTrue(runCatching {
            backend.writeRootfsText(root, "/device/gone/tool.txt", "x", overwrite = true, mounts = mounts)
        }.isFailure)
    }
}
