package me.rerere.rikkihub.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceMountSelection
import me.rerere.workspace.WorkspaceShellRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files

/**
 * 选中工作目录且开关开启时，真实 PRoot 里只看得到该目录。
 * `files/a/b` 是范围，`files/other/secret.txt` 与 `files/top-secret.txt` 必须探测不到。
 * 需要真实 rootfs（`-e rootfs false` 时跳过）。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceFilesScopeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val backend get() = GlobalContext.get().get<WorkspaceBackend>()
    private val repo get() = GlobalContext.get().get<WorkspaceRepository>()
    private val bash get() = GlobalContext.get().get<WorkspacePersistentBashSessionManager>()

    private val root = "itest-shell" // shared with WorkspaceRuntimeInstrumentedTest
    private val scoped = WorkspaceMountSelection(exposedTargets = emptySet(), filesScope = "a/b")

    private fun files() = File(context.filesDir.canonicalFile, "workspaces/$root/files")

    private fun prepare() {
        backend.ensureWorkspace(root)
        if (!backend.hasRootfs(root)) backend.installRootfs(root, defaultRootfsUrl())
        assertTrue(backend.hasRootfs(root))
        val files = files()
        File(files, "a/b").mkdirs()
        File(files, "a/b/in.txt").writeText("inside")
        File(files, "a/sibling-secret.txt").writeText("secret")
        File(files, "other").mkdirs()
        File(files, "other/secret.txt").writeText("secret")
        File(files, "top-secret.txt").writeText("secret")
        File(files, "a/b/made.txt").delete()
        File(files, "stray.txt").delete()
        File(files, "a/b/rel-escape").delete()
        File(files, "a/b/abs-escape").delete()
        Files.createSymbolicLink(File(files, "a/b/rel-escape").toPath(), File("../../other/secret.txt").toPath())
        Files.createSymbolicLink(File(files, "a/b/abs-escape").toPath(), File(files, "other/secret.txt").toPath())
    }

    private fun run(command: String) =
        backend.executeCommand(root, command, timeoutMillis = 120_000, mounts = scoped)

    @Test(timeout = 15 * 60_000)
    fun confinedShellCannotSeeOrReachOutsideFiles() {
        assumeTrue(args.getString("rootfs") != "false")
        prepare()

        assertEquals("/workspace/a/b", run("pwd").stdout.trim())
        assertEquals(listOf("abs-escape", "in.txt", "rel-escape"), run("ls -1A").stdout.trim().lines().sorted())
        assertEquals("a", run("ls -A /workspace").stdout.trim())
        assertEquals("b", run("cd .. && ls -A").stdout.trim())
        assertEquals("b", run("ls -A /workspace/a").stdout.trim())

        val probes = listOf(
            "cat /workspace/other/secret.txt",
            "cat /workspace/top-secret.txt",
            "cat /workspace/a/sibling-secret.txt",
            "cat rel-escape",
            "cat abs-escape",
            "cat ../../other/secret.txt",
        )
        for (probe in probes) {
            val result = run(probe)
            assertFalse("$probe -> ${result.stdout}", result.stdout.contains("secret"))
            assertTrue("$probe should fail", result.exitCode != 0)
        }
        val found = run(
            "find / \\( -path /proc -o -path /sys -o -path /dev \\) -prune -o -name '*secret*' -print 2>/dev/null"
        ).stdout
        assertFalse(found, found.contains("secret"))
        val grep = run("grep -rs secret /workspace 2>/dev/null; true").stdout
        assertFalse(grep, grep.contains("secret"))

        // Work inside the scope reaches FILES; writes elsewhere under /workspace do not.
        assertEquals(0, run("echo made > made.txt").exitCode)
        assertEquals("made\n", File(files(), "a/b/made.txt").readText())
        run("echo stray > /workspace/stray.txt")
        assertFalse(File(files(), "stray.txt").exists())

        // Outside cwd is rejected before a process starts.
        assertTrue(runCatching { backend.executeCommand(root, "ls", cwd = "other", mounts = scoped) }.isFailure)
    }

    @Test(timeout = 15 * 60_000)
    fun confinedInteractiveAndPersistentShells() = runBlocking {
        assumeTrue(args.getString("rootfs") != "false")
        prepare()
        val pty = backend.openShell(root, WorkspaceShellRequest(rows = 24, columns = 80, mounts = scoped))
        try {
            pty.write("pwd; ls -A /workspace; cat /workspace/other/secret.txt; echo done-\$((1+1))\nexit\n".toByteArray())
            val seen = StringBuilder()
            val buffer = ByteArray(4096)
            withTimeout(60_000) {
                while (!seen.contains("done-2")) {
                    val n = pty.read(buffer)
                    if (n <= 0) break
                    seen.append(String(buffer, 0, n))
                }
            }
            val out = seen.toString()
            assertTrue(out, out.contains("/workspace/a/b"))
            assertFalse(out, out.contains("secret\r\n") || out.lines().any { it.trim() == "secret" })
        } finally {
            pty.close()
        }

        // Model bash through the repository: one shell per scope, starting inside it.
        val workspace = repo.create("itest-scope-${System.currentTimeMillis()}")
        try {
            assertTrue(repo.installRootfs(workspace.id, defaultRootfsUrl()))
            val files = File(context.filesDir.canonicalFile, "workspaces/${workspace.root}/files")
            File(files, "p/q").mkdirs()
            File(files, "x").mkdirs()
            fun String.last() = trim().lines().last().trim()
            assertEquals("/workspace/p/q", bash.execute(workspace.id, "pwd", filesScope = "p/q").last())
            assertEquals("p", bash.execute(workspace.id, "ls -A --color=never /workspace", filesScope = "p/q").last())
            assertEquals("/workspace", bash.execute(workspace.id, "pwd").last())
            assertEquals(emptySet<String>(), repo.executionMountTargets(workspace.id, "p/q") - "/tool_outputs")
        } finally {
            bash.closeWorkspace(workspace.id)
            repo.delete(workspace.id)
        }
    }
}
