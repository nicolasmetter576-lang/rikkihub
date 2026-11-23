package me.rerere.rikkihub.workspace

import android.os.Environment
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.buildWriteTextInRootfsCommand
import me.rerere.rikkihub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import me.rerere.workspace.WORKSPACE_TEMP_FILE_PREFIX
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * `workspace_write_file` / `workspace_edit_file` through a real PRoot: an interrupted overwrite leaves the
 * old or the new file whole, never a prefix, and a kept-back temporary file is listed next to it.
 * Needs a real rootfs (skipped with `-e rootfs false`).
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceWriteFileInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val repo get() = GlobalContext.get().get<WorkspaceRepository>()
    private val devices get() = GlobalContext.get().get<WorkspaceDeviceDirectoryService>()

    private fun withWorkspace(block: suspend CoroutineScope.(id: String, files: File) -> Unit) = runBlocking {
        assumeTrue(args.getString("rootfs") != "false")
        val workspace = repo.create("itest-write-${System.currentTimeMillis()}")
        try {
            assertTrue(repo.installRootfs(workspace.id, defaultRootfsUrl()))
            block(workspace.id, File(context.filesDir.canonicalFile, "workspaces/${workspace.root}/files"))
        } finally {
            repo.delete(workspace.id)
        }
    }

    private suspend fun tool(workspaceId: String, id: String): Tool =
        createWorkspaceTools(workspaceId, repo).single { it.name == id }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                else -> error("unsupported $value")
            }
        }
    }

    private fun mode(file: File) = Os.stat(file.path).st_mode and "777".toInt(8)

    @Test(timeout = 20 * 60_000)
    fun interruptedOverwriteLeavesTheOldOrTheNewFileWhole() = withWorkspace { id, files ->
        val write = tool(id, BuiltinToolIds.WORKSPACE_WRITE_FILE)
        val target = File(files, "target.txt")
        val original = "original line\n".repeat(10_000)
        val replacement = "x".repeat(24 * 1024 * 1024)
        val input = args("path" to "/workspace/target.txt", "text" to replacement)
        val whole = setOf(original.length.toLong(), replacement.length.toLong())
        val damaged = mutableListOf<String>()
        // A sampler watches the target for the whole write: any other size is what a stop, timeout or
        // process death at that instant would leave behind. null = let the write finish.
        for (cancelAfter in listOf<Long?>(null, 0, 50, 100, 200, 400, 800)) {
            target.writeText(original)
            val seen = sortedSetOf<Long>()
            val sampler = launch(Dispatchers.IO) {
                while (isActive) target.length().takeIf { it !in whole }?.let { seen += it }
            }
            val job = launch(Dispatchers.IO) { runCatching { write.execute(input) } }
            if (cancelAfter == null) job.join() else { delay(cancelAfter); job.cancelAndJoin() }
            delay(500) // the killed PRoot may still hold the file for a moment
            sampler.cancelAndJoin()
            val final = target.length()
            if (final == original.length.toLong()) assertEquals(original, target.readText())
            if (seen.isNotEmpty() || final !in whole) {
                damaged += "cancel after ${cancelAfter ?: "never"}: sizes seen ${seen.take(5)}…, final $final"
            }
        }
        assertTrue(damaged.joinToString("\n"), damaged.isEmpty())

        val leftovers = files.list()!!.filter { it != "target.txt" }
        assertTrue(leftovers.toString(), leftovers.all { it.startsWith(".rikkihub-tmp-") })
        val listed = repo.listFiles(id, WorkspaceStorageArea.FILES, "").map { it.name }
        assertTrue("$listed should show $leftovers", listed.containsAll(leftovers))
    }

    @Test(timeout = 20 * 60_000)
    fun overwriteAndEditKeepModeLinksAndCreationRules() = withWorkspace { id, files ->
        val write = tool(id, BuiltinToolIds.WORKSPACE_WRITE_FILE)
        val edit = tool(id, BuiltinToolIds.WORKSPACE_EDIT_FILE)
        val script = File(files, "bin/run.sh").apply { parentFile!!.mkdirs(); writeText("echo old\n") }
        Os.chmod(script.path, "750".toInt(8))
        val link = File(files, "link.sh")
        Files.createSymbolicLink(link.toPath(), Paths.get("bin/run.sh"))

        write.execute(args("path" to "/workspace/link.sh", "text" to "echo new\n"))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("echo new\n", script.readText())
        assertEquals("750".toInt(8), mode(script))

        edit.execute(args("path" to "/workspace/bin/run.sh", "old_text" to "new", "new_text" to "edited"))
        assertEquals("echo edited\n", script.readText())
        assertEquals("750".toInt(8), mode(script))

        write.execute(args("path" to "/workspace/deep/a/b.txt", "text" to "hi"))
        assertEquals("hi", File(files, "deep/a/b.txt").readText())
        val refused = runCatching {
            write.execute(args("path" to "/workspace/deep/a/b.txt", "text" to "x", "overwrite" to false))
        }
        assertTrue(refused.exceptionOrNull()?.message.orEmpty(), refused.isFailure)
        assertEquals("hi", File(files, "deep/a/b.txt").readText())

        write.execute(args("path" to "/root/note.txt", "text" to "one"))
        write.execute(args("path" to "/root/note.txt", "text" to "two"))
        assertEquals("two", repo.executeCommand(id, "cat /root/note.txt").stdout)

        val leftovers = files.walkTopDown().filter { it.name.startsWith(".rikkihub-tmp-") }.toList()
        assertTrue(leftovers.toString(), leftovers.isEmpty())
    }

    /** `/device/<alias>` sits on shared storage (FUSE); rename and the mode check must work there too. */
    @Test(timeout = 20 * 60_000)
    fun overwriteWorksOnADeviceDirectory() = withWorkspace { id, _ ->
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation
                .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"),
        ).use { it.readBytes() }
        val relative = "Download/rikkihub-itest-write-${System.currentTimeMillis()}"
        val source = File(Environment.getExternalStorageDirectory(), relative).apply { mkdirs() }
        try {
            devices.create(id, "ext", relative)
            val write = tool(id, BuiltinToolIds.WORKSPACE_WRITE_FILE)
            val edit = tool(id, BuiltinToolIds.WORKSPACE_EDIT_FILE)
            write.execute(args("path" to "/device/ext/note.txt", "text" to "first\n"))
            write.execute(args("path" to "/device/ext/note.txt", "text" to "second\n"))
            edit.execute(args("path" to "/device/ext/note.txt", "old_text" to "second", "new_text" to "third"))
            assertEquals("third\n", File(source, "note.txt").readText())
            // In-process writer (str_replace_editor, device panel) on the same FUSE directory.
            repo.writeRootfsText(id, "/device/ext/note.txt", "fourth\n", overwrite = true)
            assertEquals("fourth\n", File(source, "note.txt").readText())
            assertEquals(listOf("note.txt"), source.list()!!.toList())
        } finally {
            source.deleteRecursively()
        }
    }

    @Test(timeout = 20 * 60_000)
    fun overwriteCleansStaleTempFilesInSameDirectoryOnly() = withWorkspace { id, files ->
        val write = tool(id, BuiltinToolIds.WORKSPACE_WRITE_FILE)
        val target = File(files, "target.txt").apply { writeText("old\n") }
        val stale = File(files, "${WORKSPACE_TEMP_FILE_PREFIX}1111111111114111").apply { writeText("old leftover") }
        // Names the app never generates belong to the user, whatever their age.
        val userNames = listOf(
            "notes.md", "b-stale", "0123456789ab4de", "0123456789ab4def0", "0123456789AB4DEF", "0123456789ab4def.md",
        ).map { WORKSPACE_TEMP_FILE_PREFIX + it }
        userNames.forEach { File(files, it).writeText("user file") }
        val fresh = File(files, "${WORKSPACE_TEMP_FILE_PREFIX}3333333333334333").apply { writeText("fresh leftover") }
        val normalOld = File(files, "keep.txt").apply { writeText("normal old") }
        val oldDir = File(files, "${WORKSPACE_TEMP_FILE_PREFIX}2222222222224222").apply { assertTrue(mkdir()) }
        val sub = File(files, "sub").apply { assertTrue(mkdir()) }
        val nestedStale = File(sub, "${WORKSPACE_TEMP_FILE_PREFIX}5555555555554555").apply { writeText("nested old") }
        val subTarget = File(sub, "inside.txt").apply { writeText("sub old") }
        val linkedDir = File(files, "linked").apply { assertTrue(mkdir()) }
        val linkedFile = File(linkedDir, "real.txt").apply { writeText("linked old") }
        val linkedStale = File(linkedDir, "${WORKSPACE_TEMP_FILE_PREFIX}6666666666664666").apply { writeText("linked stale") }

        val setup = repo.executeCommand(
            id,
            """
                ln -s /nonexistent/dangling /workspace/${WORKSPACE_TEMP_FILE_PREFIX}0000000000004000
                ln -s keep.txt /workspace/${WORKSPACE_TEMP_FILE_PREFIX}4444444444444444
                ln -s sub /workspace/dirlink
                ln -s linked/real.txt /workspace/filelink
                touch -d '25 hours ago' \
                  /workspace/${WORKSPACE_TEMP_FILE_PREFIX}1111111111114111 \
                  /workspace/keep.txt \
                  /workspace/${WORKSPACE_TEMP_FILE_PREFIX}2222222222224222 \
                  /workspace/sub/${WORKSPACE_TEMP_FILE_PREFIX}5555555555554555 \
                  /workspace/linked/${WORKSPACE_TEMP_FILE_PREFIX}6666666666664666
                touch -d '25 hours ago' ${userNames.joinToString(" ") { "/workspace/$it" }}
                touch -h -d '25 hours ago' \
                  /workspace/${WORKSPACE_TEMP_FILE_PREFIX}0000000000004000 \
                  /workspace/${WORKSPACE_TEMP_FILE_PREFIX}4444444444444444
            """.trimIndent(),
        )
        assertEquals(setup.stderr, 0, setup.exitCode)

        // 1. Direct overwrite cleans only same-dir regular stale temp (even with dangling symlink sorting first).
        write.execute(args("path" to "/workspace/target.txt", "text" to "new\n"))
        assertEquals("new\n", target.readText())
        assertFalse(stale.exists())
        assertEquals(emptyList<String>(), userNames.filterNot { File(files, it).exists() })
        assertTrue(fresh.exists())
        assertTrue(normalOld.exists())
        assertTrue(oldDir.isDirectory)
        assertTrue(Files.isSymbolicLink(File(files, "${WORKSPACE_TEMP_FILE_PREFIX}0000000000004000").toPath()))
        assertTrue(Files.isSymbolicLink(File(files, "${WORKSPACE_TEMP_FILE_PREFIX}4444444444444444").toPath()))
        assertTrue(nestedStale.exists())
        assertTrue(linkedStale.exists())

        // 2. Overwrite through a directory symlink follows the symlinked directory (`find -H`) and cleans inside it.
        write.execute(args("path" to "/workspace/dirlink/inside.txt", "text" to "sub new\n"))
        assertEquals("sub new\n", subTarget.readText())
        assertFalse(nestedStale.exists())

        // 3. Overwrite through a file symlink cleans stale temp next to the symlink target and keeps the link.
        write.execute(args("path" to "/workspace/filelink", "text" to "linked new\n"))
        assertTrue(Files.isSymbolicLink(File(files, "filelink").toPath()))
        assertEquals("linked new\n", linkedFile.readText())
        assertFalse(linkedStale.exists())

        // 4. The in-process writer sweeps by the same names.
        val restale = repo.executeCommand(id, "echo x > /workspace/${stale.name} && touch -d '25 hours ago' /workspace/${stale.name}")
        assertEquals(restale.stderr, 0, restale.exitCode)
        repo.writeRootfsText(id, "/workspace/target.txt", "in-process\n", overwrite = true)
        assertEquals("in-process\n", target.readText())
        assertFalse(stale.exists())
        assertEquals(emptyList<String>(), userNames.filterNot { File(files, it).exists() })
        assertTrue(fresh.exists())
    }

    @Test(timeout = 20 * 60_000)
    fun truncatedStdinFailsAndPreservesOriginalInProot() = withWorkspace { id, files ->
        val target = File(files, "target.txt").apply { writeText("original-in-proot") }
        val command = buildWriteTextInRootfsCommand(
            path = "/workspace/target.txt",
            expectedBytes = 4096,
            overwrite = true,
        )
        val result = repo.executeCommand(
            id = id,
            command = command,
            stdin = ByteArray(1024) { 'a'.code.toByte() },
        )
        assertNotEquals("truncated stdin must fail inside PRoot", 0, result.exitCode)
        assertTrue("expected Incomplete write stderr, got: ${result.stderr}", result.stderr.contains("Incomplete write"))
        assertEquals("original-in-proot", target.readText())
        val leftovers = files.list().orEmpty().filter { it.startsWith(WORKSPACE_TEMP_FILE_PREFIX) }
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test(timeout = 20 * 60_000)
    fun overwriteFalseIsExclusiveAcrossProotAndFuseAndRejectsDanglingSymlink() = withWorkspace { id, files ->
        val write = tool(id, BuiltinToolIds.WORKSPACE_WRITE_FILE)

        // 1. 8-way concurrent overwrite=false inside /workspace
        val wsOutcomes = (0 until 8).map { idx ->
            async(Dispatchers.IO) {
                val body = "ws-winner-$idx-" + "w".repeat(16 * 1024)
                idx to runCatching {
                    write.execute(args("path" to "/workspace/contended.txt", "text" to body, "overwrite" to false))
                }
            }
        }.awaitAll()
        val wsWinners = wsOutcomes.filter { it.second.isSuccess }
        val wsLosers = wsOutcomes.filter { it.second.isFailure }
        assertEquals("expected exactly 1 /workspace winner, got ${wsWinners.map { it.first }}", 1, wsWinners.size)
        assertEquals(7, wsLosers.size)
        wsLosers.forEach { (_, res) ->
            assertTrue(
                "expected File already exists, got: ${res.exceptionOrNull()?.message}",
                res.exceptionOrNull()?.message.orEmpty().contains("File already exists"),
            )
        }
        val expectedWs = "ws-winner-${wsWinners.single().first}-" + "w".repeat(16 * 1024)
        assertEquals(expectedWs, File(files, "contended.txt").readText())

        // 2. Dangling symlink rejected and kept intact
        val dangling = File(files, "dangling.txt")
        val danglingTarget = File(files, "nowhere.txt")
        Files.createSymbolicLink(dangling.toPath(), Paths.get("nowhere.txt"))
        val danglingRes = runCatching {
            write.execute(args("path" to "/workspace/dangling.txt", "text" to "hi", "overwrite" to false))
        }
        assertTrue(danglingRes.isFailure)
        assertTrue(danglingRes.exceptionOrNull()?.message.orEmpty().contains("File already exists"))
        assertTrue(Files.isSymbolicLink(dangling.toPath()))
        assertFalse(danglingTarget.exists())

        // 3. 8-way concurrent overwrite=false on Android shared storage FUSE (/device/ext)
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation
                .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"),
        ).use { it.readBytes() }
        val relative = "Download/rikkihub-itest-noclobber-${System.currentTimeMillis()}"
        val source = File(Environment.getExternalStorageDirectory(), relative).apply { mkdirs() }
        try {
            devices.create(id, "ext", relative)
            val fuseOutcomes = (0 until 8).map { idx ->
                async(Dispatchers.IO) {
                    val body = "fuse-winner-$idx-" + "f".repeat(16 * 1024)
                    idx to runCatching {
                        write.execute(args("path" to "/device/ext/contended.txt", "text" to body, "overwrite" to false))
                    }
                }
            }.awaitAll()
            val fuseWinners = fuseOutcomes.filter { it.second.isSuccess }
            val fuseLosers = fuseOutcomes.filter { it.second.isFailure }
            assertEquals("expected exactly 1 FUSE winner, got ${fuseWinners.map { it.first }}", 1, fuseWinners.size)
            assertEquals(7, fuseLosers.size)
            fuseLosers.forEach { (_, res) ->
                assertTrue(
                    "expected File already exists on FUSE, got: ${res.exceptionOrNull()?.message}",
                    res.exceptionOrNull()?.message.orEmpty().contains("File already exists"),
                )
            }
            val expectedFuse = "fuse-winner-${fuseWinners.single().first}-" + "f".repeat(16 * 1024)
            assertEquals(expectedFuse, File(source, "contended.txt").readText())
        } finally {
            source.deleteRecursively()
        }
    }
}
