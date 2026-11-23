package me.rerere.rikkihub.data.ai.tools

import me.rerere.workspace.WORKSPACE_TEMP_FILE_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceWriteFileScriptTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class ShellSpec(val name: String, val commandPrefix: List<String>)

    private data class ScriptResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun availableShells(): List<ShellSpec> = buildList {
        val bash = findExecutable("bash")
        if (bash != null) add(ShellSpec("bash", listOf(bash, "-c")))
        val dash = findExecutable("dash")
        if (dash != null) add(ShellSpec("dash", listOf(dash, "-c")))
        val busybox = findExecutable("busybox")
        if (busybox != null) add(ShellSpec("busybox sh", listOf(busybox, "sh", "-c")))
    }.also {
        assumeTrue("At least one POSIX shell must be available", it.isNotEmpty())
    }

    private fun findExecutable(name: String): String? {
        val candidates = listOf("/usr/bin/$name", "/bin/$name")
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun runScript(
        shell: ShellSpec,
        script: String,
        stdin: ByteArray,
    ): ScriptResult {
        val process = ProcessBuilder(shell.commandPrefix + listOf("eval \"\$1\"", "rikkihub", script))
            .redirectErrorStream(false)
            .start()
        val writer = Thread {
            runCatching {
                process.outputStream.use { out ->
                    out.write(stdin)
                    out.flush()
                }
            }
        }.apply { start() }
        val stdoutFuture = Executors.newSingleThreadExecutor().let { exec ->
            exec.submit(Callable { process.inputStream.readBytes().toString(Charsets.UTF_8) }).also { exec.shutdown() }
        }
        val stderrFuture = Executors.newSingleThreadExecutor().let { exec ->
            exec.submit(Callable { process.errorStream.readBytes().toString(Charsets.UTF_8) }).also { exec.shutdown() }
        }
        assertTrue("${shell.name}: script timed out", process.waitFor(15, TimeUnit.SECONDS))
        writer.join(2_000)
        return ScriptResult(
            exitCode = process.exitValue(),
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS),
            stderr = stderrFuture.get(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `overwrite rejects truncated stdin EOF and leaves original file untouched`() {
        for (shell in availableShells()) {
            val dir = tempFolder.newFolder("trunc-${shell.name.replace(' ', '_')}")
            val target = File(dir, "target.txt").apply { writeText("original intact content") }
            val truncated = "partial-prefix".repeat(30).toByteArray(Charsets.UTF_8)
            val script = buildWriteTextInRootfsCommand(
                path = target.absolutePath,
                expectedBytes = truncated.size + 512,
                overwrite = true,
            )

            val result = runScript(shell, script, stdin = truncated)

            assertNotEquals("${shell.name}: truncated stdin must fail", 0, result.exitCode)
            assertTrue("${shell.name}: expected Incomplete write stderr, got: ${result.stderr}", result.stderr.contains("Incomplete write"))
            assertEquals("${shell.name}: original file must stay whole", "original intact content", target.readText())
            val leftovers = dir.list().orEmpty().filter { it.startsWith(WORKSPACE_TEMP_FILE_PREFIX) }
            assertEquals("${shell.name}: temp file must be cleaned up", emptyList<String>(), leftovers)

            // overwrite=false also rejects truncated stdin EOF rather than reporting success
            val fresh = File(dir, "fresh-trunc.txt")
            val freshRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(
                    path = fresh.absolutePath,
                    expectedBytes = truncated.size + 512,
                    overwrite = false,
                ),
                stdin = truncated,
            )
            assertNotEquals("${shell.name}: overwrite=false truncated stdin must fail", 0, freshRes.exitCode)
            assertTrue("${shell.name}: expected Incomplete write stderr, got: ${freshRes.stderr}", freshRes.stderr.contains("Incomplete write"))
        }
    }

    @Test
    fun `overwrite handles empty multibyte UTF-8 symlink and large payloads across shells`() {
        for (shell in availableShells()) {
            val dir = tempFolder.newFolder("edge-${shell.name.replace(' ', '_')}")
            // 1. Empty file (0 bytes)
            val emptyFile = File(dir, "empty.txt").apply { writeText("before") }
            val emptyRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(emptyFile.absolutePath, expectedBytes = 0, overwrite = true),
                stdin = ByteArray(0),
            )
            assertEquals("${shell.name}: empty write failed: ${emptyRes.stderr}", 0, emptyRes.exitCode)
            assertEquals("", emptyFile.readText())

            // 2. Multibyte UTF-8 + emoji + CRLF
            val utf8Text = "中文内容😀\r\n第二行🚀\n"
            val utf8Bytes = utf8Text.toByteArray(Charsets.UTF_8)
            assertNotEquals(utf8Text.length, utf8Bytes.size)
            val utf8File = File(dir, "utf8 file.txt").apply { writeText("old") }
            val utf8Res = runScript(
                shell,
                buildWriteTextInRootfsCommand(utf8File.absolutePath, expectedBytes = utf8Bytes.size, overwrite = true),
                stdin = utf8Bytes,
            )
            assertEquals("${shell.name}: utf8 write failed: ${utf8Res.stderr}", 0, utf8Res.exitCode)
            assertEquals(utf8Text, utf8File.readText())

            // 3. Symlink overwrite keeps link intact
            val real = File(dir, "real.txt").apply { writeText("old-real") }
            val link = File(dir, "link.txt")
            Files.createSymbolicLink(link.toPath(), Paths.get("real.txt"))
            val linkBytes = "new-through-link\n".toByteArray(Charsets.UTF_8)
            val linkRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(link.absolutePath, expectedBytes = linkBytes.size, overwrite = true),
                stdin = linkBytes,
            )
            assertEquals("${shell.name}: symlink overwrite failed: ${linkRes.stderr}", 0, linkRes.exitCode)
            assertTrue("${shell.name}: symlink must remain a symlink", Files.isSymbolicLink(link.toPath()))
            assertEquals("new-through-link\n", real.readText())

            // 4. Large 2 MiB payload
            val largeBytes = ByteArray(2 * 1024 * 1024) { ('a'.code + (it % 26)).toByte() }
            val largeFile = File(dir, "large.txt").apply { writeText("old") }
            val largeRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(largeFile.absolutePath, expectedBytes = largeBytes.size, overwrite = true),
                stdin = largeBytes,
            )
            assertEquals("${shell.name}: large write failed: ${largeRes.stderr}", 0, largeRes.exitCode)
            assertEquals(largeBytes.size.toLong(), largeFile.length())
        }
    }

    @Test
    fun `overwrite false rejects concurrent creation during slow write without clobbering`() {
        for (shell in availableShells()) {
            val dir = tempFolder.newFolder("race-slow-${shell.name.replace(' ', '_')}")
            val target = File(dir, "shared.txt")
            val payload = "slow-writer-content\n".toByteArray(Charsets.UTF_8)
            val script = buildWriteTextInRootfsCommand(
                path = target.absolutePath,
                expectedBytes = payload.size,
                overwrite = false,
            )
            val process = ProcessBuilder(shell.commandPrefix + listOf("eval \"\$1\"", "rikkihub", script))
                .redirectErrorStream(false)
                .start()

            // Give the shell time to pass its initial existence check and block on stdin.
            // With atomic `set -C`, the target is already claimed at open time; with the old
            // temp-file + `mv -f` path, the target does not exist yet and a concurrent creator
            // can win the path during the write window.
            Thread.sleep(150)
            val competitorCreated = !target.exists()
            if (competitorCreated) {
                target.writeText("concurrent-winner")
            }

            runCatching {
                process.outputStream.use { out ->
                    out.write(payload)
                    out.flush()
                }
            }
            assertTrue("${shell.name}: timed out", process.waitFor(15, TimeUnit.SECONDS))
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)

            if (competitorCreated) {
                assertNotEquals("${shell.name}: must not overwrite file created during write window", 0, process.exitValue())
                assertTrue("${shell.name}: expected File already exists, got: $stderr", stderr.contains("File already exists"))
                assertEquals("${shell.name}: concurrent file must stay intact", "concurrent-winner", target.readText())
            } else {
                assertEquals("${shell.name}: atomic claim should succeed: $stderr", 0, process.exitValue())
                assertEquals("slow-writer-content\n", target.readText())
            }
        }
    }

    @Test
    fun `overwrite false allows exactly one winner under 12-way concurrency`() {
        val workers = 12
        for (shell in availableShells()) {
            val dir = tempFolder.newFolder("race-12-${shell.name.replace(' ', '_')}")
            val target = File(dir, "contended.txt")
            val pool = Executors.newFixedThreadPool(workers)
            try {
                val ready = CountDownLatch(workers)
                val go = CountDownLatch(1)
                val futures = (0 until workers).map { idx ->
                    val content = "winner-$idx-" + "x".repeat(64 * 1024)
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    val script = buildWriteTextInRootfsCommand(
                        path = target.absolutePath,
                        expectedBytes = bytes.size,
                        overwrite = false,
                    )
                    pool.submit(Callable {
                        ready.countDown()
                        go.await(5, TimeUnit.SECONDS)
                        idx to runScript(shell, script, bytes)
                    })
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                val results = futures.map { it.get(30, TimeUnit.SECONDS) }
                val winners = results.filter { it.second.exitCode == 0 }
                val losers = results.filter { it.second.exitCode != 0 }

                assertEquals("${shell.name}: expected exactly 1 winner, got ${winners.map { it.first }}", 1, winners.size)
                assertEquals(workers - 1, losers.size)
                losers.forEach { (idx, res) ->
                    assertTrue(
                        "${shell.name}: loser $idx stderr should report File already exists, got: ${res.stderr}",
                        res.stderr.contains("File already exists"),
                    )
                }
                val winnerIdx = winners.single().first
                val expectedContent = "winner-$winnerIdx-" + "x".repeat(64 * 1024)
                assertEquals("${shell.name}: disk content must belong wholly to the sole winner", expectedContent, target.readText())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `overwrite false rejects dangling symlink directory and reports real EFBIG error`() {
        for (shell in availableShells()) {
            val dir = tempFolder.newFolder("edge-noclobber-${shell.name.replace(' ', '_')}")

            // 1. Dangling symlink must be rejected and preserved
            val dangling = File(dir, "dangling.txt")
            val danglingTarget = File(dir, "missing-target.txt")
            Files.createSymbolicLink(dangling.toPath(), danglingTarget.toPath())
            val danglingRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(dangling.absolutePath, expectedBytes = 2, overwrite = false),
                stdin = "hi".toByteArray(Charsets.UTF_8),
            )
            assertNotEquals("${shell.name}: dangling symlink must be rejected", 0, danglingRes.exitCode)
            assertTrue("${shell.name}: expected File already exists, got: ${danglingRes.stderr}", danglingRes.stderr.contains("File already exists"))
            assertTrue("${shell.name}: dangling symlink must remain intact", Files.isSymbolicLink(dangling.toPath()))
            assertFalse("${shell.name}: dangling target must not be created", danglingTarget.exists())

            // 2. Existing directory must be rejected
            val subdir = File(dir, "adir").apply { assertTrue(mkdir()) }
            val dirRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(subdir.absolutePath, expectedBytes = 2, overwrite = false),
                stdin = "hi".toByteArray(Charsets.UTF_8),
            )
            assertNotEquals("${shell.name}: existing directory must be rejected", 0, dirRes.exitCode)
            assertTrue(dirRes.stderr.contains("File already exists"))

            // 3. Auto-create deep parent dirs & symlink parent dir
            val deep = File(dir, "deep/a/b/new.txt")
            val deepBytes = "hello-deep".toByteArray(Charsets.UTF_8)
            val deepRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(deep.absolutePath, expectedBytes = deepBytes.size, overwrite = false),
                stdin = deepBytes,
            )
            assertEquals("${shell.name}: deep create failed: ${deepRes.stderr}", 0, deepRes.exitCode)
            assertEquals("hello-deep", deep.readText())

            val realParent = File(dir, "real-parent").apply { assertTrue(mkdir()) }
            val linkParent = File(dir, "link-parent")
            Files.createSymbolicLink(linkParent.toPath(), realParent.toPath())
            val viaLink = File(linkParent, "child.txt")
            val viaLinkBytes = "via-link".toByteArray(Charsets.UTF_8)
            val viaLinkRes = runScript(
                shell,
                buildWriteTextInRootfsCommand(viaLink.absolutePath, expectedBytes = viaLinkBytes.size, overwrite = false),
                stdin = viaLinkBytes,
            )
            assertEquals("${shell.name}: symlink parent create failed: ${viaLinkRes.stderr}", 0, viaLinkRes.exitCode)
            assertEquals("via-link", File(realParent, "child.txt").readText())

            // 4. Write failure (EFBIG via ulimit -f) must NOT be misreported as "File already exists"
            val big = File(dir, "big.txt")
            val bigBytes = ByteArray(128 * 1024) { 'z'.code.toByte() }
            val writeScript = buildWriteTextInRootfsCommand(big.absolutePath, expectedBytes = bigBytes.size, overwrite = false)
            val wrapped = "ulimit -f 64\n$writeScript"
            val efbigRes = runScript(shell, wrapped, stdin = bigBytes)
            assertNotEquals("${shell.name}: ulimit -f write must fail", 0, efbigRes.exitCode)
            assertFalse(
                "${shell.name}: EFBIG must not be misreported as File already exists, got: ${efbigRes.stderr}",
                efbigRes.stderr.contains("File already exists"),
            )
        }
    }
}
