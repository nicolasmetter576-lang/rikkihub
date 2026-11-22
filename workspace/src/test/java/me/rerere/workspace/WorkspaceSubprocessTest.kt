package me.rerere.workspace

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The launch script and staged shutdown, run against the host's own shells. */
class WorkspaceSubprocessTest {
    private lateinit var home: File

    @Before fun setup() {
        home = Files.createTempDirectory("subprocess-home").toFile()
        File(home, ".profile").writeText("echo profile-noise\nprintf 'no newline'\n")
        File(home, ".bash_profile").writeText(". \"\$HOME/.profile\"\necho bash-profile-noise\n")
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/fake-agent").apply {
            writeText(
                "#!/bin/sh\necho agent-stderr >&2\nprintf '[%s]' \"\$@\"; echo\n" +
                    "while IFS= read -r line; do echo \"got:\$line\"; done\necho bye\n",
            )
            setExecutable(true)
        }
    }

    @After fun cleanup() { home.deleteRecursively() }

    private fun launch(shell: String, vararg command: String): WorkspaceSubprocess {
        assumeTrue(File(shell).canExecute())
        val process = ProcessBuilder(WorkspaceSubprocess.launchCommand(shell, command.toList()))
            .redirectErrorStream(false)
            .apply {
                environment().clear()
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = "/usr/bin:/bin"
            }
            .start()
        return WorkspaceSubprocess(process)
    }

    @Test fun profileOutputGoesToStderrAndArgumentsArriveIntact() = runBlocking {
        for (shell in listOf("/bin/bash", "/bin/sh")) {
            val process = launch(shell, "fake-agent", "a b", "\$HOME", "it's \"quoted\"")
            process.stdin.write("{\"id\":1}\n".toByteArray())
            process.close()
            assertEquals(shell, 0, process.awaitExit())
            val stdout = process.stdout.bufferedReader().readText()
            val stderr = process.stderr.bufferedReader().readText()
            assertEquals(shell, "[a b][\$HOME][it's \"quoted\"]\ngot:{\"id\":1}\nbye\n", stdout)
            assertTrue("$shell: $stderr", stderr.contains("profile-noise") && stderr.contains("agent-stderr"))
        }
    }

    @Test fun missingProgramFailsWith127() = runBlocking {
        val process = launch("/bin/sh", "no-such-agent")
        assertEquals(127, process.awaitExit())
        assertEquals("", process.stdout.bufferedReader().readText())
    }

    @Test fun closeEscalatesWhenStdinEofAndSigtermAreIgnored() = runBlocking {
        File(home, ".local/bin/stubborn").apply {
            writeText("#!/bin/sh\ntrap '' TERM\nwhile :; do sleep 0.1; done\n")
            setExecutable(true)
        }
        val process = launch("/bin/sh", "stubborn")
        Thread.sleep(300)
        assertTrue(process.isAlive)
        val started = System.nanoTime()
        process.close()
        process.close()
        assertTrue("killed within the staged deadline", process.awaitClosed(WorkspaceSubprocess.KILL_AFTER_MS + 2_000))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("SIGKILL comes after the SIGTERM grace: ${elapsedMs}ms", elapsedMs >= WorkspaceSubprocess.KILL_AFTER_MS - 100)
    }

    @Test fun closeOnACooperativeProgramIsJustStdinEof() = runBlocking {
        val process = launch("/bin/sh", "fake-agent")
        val started = System.nanoTime()
        process.close()
        assertEquals(0, process.awaitExit())
        assertTrue((System.nanoTime() - started) / 1_000_000 < WorkspaceSubprocess.TERM_AFTER_MS)
    }
}
