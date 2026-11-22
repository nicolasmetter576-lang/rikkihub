package me.rerere.rikkihub.data.files

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceTransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipFile

class FileManagerServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val device = FileManagerRoot.Device("primary")

    private class Env(
        val service: FileManagerService,
        val library: File,
        val upload: File,
        val phone: File,
        var access: FileManagerDeviceAccess,
    )

    private fun env(
        displayNames: Map<String, String> = emptyMap(),
        libraryCopies: Set<String> = emptySet(),
        workspaces: List<Pair<String, File>> = emptyList(),
    ): Env {
        val base = temp.newFolder()
        val library = File(base, "library")
        val upload = File(base, "upload").apply { mkdirs() }
        val phone = File(base, "phone").apply { mkdirs() }
        lateinit var env: Env
        val service = FileManagerService(
            libraryRoot = library,
            libraryStaging = File(base, "library_staging"),
            volumes = { listOf(FileManagerVolume("primary", "Internal", true, phone)) },
            deviceAccess = { env.access },
            uploadDir = upload,
            uploadDisplayName = { displayNames[it] },
            chatUploadsFolder = { "Chat uploads" },
            isLibraryCopy = { it in libraryCopies },
            workspaces = { workspaces.map { (name, dir) -> FileManagerWorkspace(name, dir, 0) } },
            workspacesFolderName = { "Workspaces" },
            today = { LocalDate.of(2026, 11, 12) },
        )
        env = Env(service, library, upload, phone, FileManagerDeviceAccess.READY)
        return env
    }

    private fun Env.uploadFile(name: String, text: String) = File(upload, name).apply { writeText(text) }

    /** What sending does: mark, then collect the pending parts. */
    private suspend fun Env.send(parts: List<UIMessagePart>): List<UIMessagePart> {
        val (marked, pending) = service.markChatUploads(parts)
        service.collectChatUploads(pending)
        return marked
    }

    private val UIMessagePart.collected get() = metadata?.get(FileManagerService.COLLECTED_METADATA_KEY) != null

    @Test
    fun `sent attachments are copied under chat uploads by date with their original names`() = runBlocking {
        val env = env(displayNames = mapOf("u1.jpg" to "holiday.jpg"))
        val image = env.uploadFile("u1.jpg", "img")
        val doc = env.uploadFile("u2.txt", "doc")

        val sent = env.send(listOf(
            UIMessagePart.Text("hi"),
            UIMessagePart.Image(image.toURI().toString()),
            UIMessagePart.Document(doc.toURI().toString(), fileName = "notes.txt"),
        ))

        val day = File(env.library, "Chat uploads/2026-11-12")
        assertEquals(setOf("holiday.jpg", "notes.txt"), day.list()!!.toSet())
        assertEquals("img", File(day, "holiday.jpg").readText())
        assertEquals(listOf(false, true, true), sent.map { it.collected })
    }

    @Test
    fun `re-sending marked parts and files picked from the library collect nothing`() = runBlocking {
        val env = env(libraryCopies = setOf("fromLibrary.txt"))
        val upload = env.uploadFile("u1.txt", "a")
        val fromLibrary = env.uploadFile("fromLibrary.txt", "b")
        val outside = File(env.phone, "x.txt").apply { writeText("x") }

        val sent = env.send(listOf(
            UIMessagePart.Document(upload.toURI().toString(), fileName = "a.txt"),
            UIMessagePart.Document(fromLibrary.toURI().toString(), fileName = "b.txt"),
            UIMessagePart.Document(outside.toURI().toString(), fileName = "x.txt"),
        ))
        // Editing the message sends the stored (marked) parts again.
        env.send(sent)

        assertEquals(listOf("a.txt"), File(env.library, "Chat uploads/2026-11-12").list()!!.toList())
        assertTrue(sent[1].collected)
    }

    @Test
    fun `collect renames on clash instead of overwriting`() = runBlocking {
        val env = env()
        val first = env.uploadFile("u1.txt", "first")
        val second = env.uploadFile("u2.txt", "second")

        env.send(listOf(UIMessagePart.Document(first.toURI().toString(), fileName = "a.txt")))
        env.send(listOf(UIMessagePart.Document(second.toURI().toString(), fileName = "a.txt")))

        val day = File(env.library, "Chat uploads/2026-11-12")
        assertEquals("first", File(day, "a.txt").readText())
        assertEquals("second", File(day, "a (1).txt").readText())
    }

    @Test
    fun `text saves revise the file and refuse a stale revision`() = runBlocking {
        val env = env()
        File(env.library, "a.md").apply { parentFile!!.mkdirs(); writeText("original") }

        val snapshot = env.service.readText(FileManagerRoot.Library, "a.md")
        env.service.writeText(FileManagerRoot.Library, "a.md", "edited", snapshot.revision)
        assertEquals("edited", File(env.library, "a.md").readText())

        try {
            env.service.writeText(FileManagerRoot.Library, "a.md", "stale", snapshot.revision)
            fail("expected a revision conflict")
        } catch (error: WorkspaceFileOperationException) {
            assertEquals(WorkspaceFileOperationException.FILE_REVISION_CONFLICT, error.code)
        }
        assertEquals("edited", File(env.library, "a.md").readText())
    }

    @Test
    fun `move between library and phone deletes the source only after a full copy`() = runBlocking {
        val env = env()
        env.service.createFolder(FileManagerRoot.Library, "docs")
        File(env.library, "docs/a.txt").writeText("a")
        File(env.phone, "docs").mkdirs()

        val result = env.service.transfer(FileManagerRoot.Library, listOf("docs"), device, "", WorkspaceTransferMode.MOVE)

        assertEquals("docs (1)", result.items.single().target)
        assertEquals("a", File(env.phone, "docs (1)/a.txt").readText())
        assertFalse(File(env.library, "docs").exists())
    }

    @Test
    fun `phone operations fail with permission denied while access is missing`() = runBlocking {
        val env = env()
        env.access = FileManagerDeviceAccess.PERMISSION_REQUIRED
        try {
            env.service.list(device, "")
            fail("expected permission error")
        } catch (error: WorkspaceFileOperationException) {
            assertEquals(WorkspaceFileOperationException.FILE_PERMISSION_DENIED, error.code)
        }
        // The library keeps working.
        assertTrue(env.service.list(FileManagerRoot.Library, "").isEmpty())
    }

    @Test
    fun `rename refuses invalid names and existing targets`() = runBlocking {
        val env = env()
        File(env.phone, "a.txt").writeText("a")
        File(env.phone, "b.txt").writeText("b")

        for (bad in listOf("x/y", "..", "")) {
            try {
                env.service.rename(device, "a.txt", bad)
                fail("expected $bad to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            env.service.rename(device, "a.txt", "b.txt")
            fail("expected clash")
        } catch (_: Exception) {
        }
        assertEquals("b", File(env.phone, "b.txt").readText())

        env.service.rename(device, "a.txt", "c.txt")
        assertEquals("a", File(env.phone, "c.txt").readText())
    }

    @Test
    fun `zip lands as a new file and extracting on the phone leaves no staging folder`() = runBlocking {
        val env = env()
        File(env.phone, "pack").mkdirs()
        File(env.phone, "pack/a.txt").writeText("a")
        File(env.phone, "pack.zip").writeText("taken")

        val zip = env.service.zip(device, "", listOf("pack"), "pack")
        assertEquals("pack (1).zip", zip.name)
        ZipFile(File(env.phone, zip.path)).use { assertTrue(it.getEntry("pack/a.txt") != null) }

        val extracted = env.service.extract(device, zip.path)
        assertEquals("pack (1)", extracted.name)
        assertEquals("a", File(env.phone, "pack (1)/a.txt").readText())
        assertFalse(File(env.phone, FileManagerService.DEVICE_STAGING_NAME).exists())
    }

    @Test
    fun `import keeps an existing file and adds a numbered copy`() = runBlocking {
        val env = env()
        env.service.import(FileManagerRoot.Library, "", "a.txt", "one".byteInputStream())
        val second = env.service.import(FileManagerRoot.Library, "", "a.txt", "two".byteInputStream())

        assertEquals("a (1).txt", second.name)
        assertEquals("one", File(env.library, "a.txt").readText())
    }

    @Test
    fun `workspaces show up in the library as live folders named after them`() = runBlocking {
        val novel = File(temp.newFolder(), "files").apply { mkdirs() }
        File(novel, "ch1").mkdirs()
        File(novel, "ch1/1.md").writeText("one")
        val env = env(workspaces = listOf("Novel" to novel, "a/b" to temp.newFolder()))
        env.library.mkdirs()
        File(env.library, "mine.txt").writeText("x")

        assertEquals(setOf("mine.txt", "Workspaces"), env.service.list(FileManagerRoot.Library, "").map { it.name }.toSet())
        assertEquals(listOf("Novel", "a／b"), env.service.list(FileManagerRoot.Library, "Workspaces").map { it.name })
        val chapter = env.service.list(FileManagerRoot.Library, "Workspaces/Novel/ch1").single()
        assertEquals("Workspaces/Novel/ch1/1.md", chapter.path)
        assertEquals("one", env.service.readText(FileManagerRoot.Library, chapter.path).content)

        // Changes inside land in the workspace itself; nothing is copied into the library
        env.service.createFolder(FileManagerRoot.Library, "Workspaces/Novel/ch2")
        assertTrue(File(novel, "ch2").isDirectory)
        env.service.transfer(FileManagerRoot.Library, listOf("mine.txt"), FileManagerRoot.Library, "Workspaces/Novel", WorkspaceTransferMode.COPY)
        assertEquals("x", File(novel, "mine.txt").readText())
        assertFalse(File(env.library, "Workspaces").exists())
    }

    @Test
    fun `the workspaces folder and the workspace folders cannot be changed`() = runBlocking {
        val env = env(workspaces = listOf("Novel" to temp.newFolder()))
        listOf(
            suspend { env.service.delete(FileManagerRoot.Library, listOf("Workspaces/Novel")) },
            suspend { env.service.rename(FileManagerRoot.Library, "Workspaces/Novel", "Other") },
            suspend { env.service.createFolder(FileManagerRoot.Library, "Workspaces/New") },
            suspend { env.service.delete(FileManagerRoot.Library, listOf("Workspaces")) },
        ).forEachIndexed { index, action ->
            try {
                action()
                fail("action $index should be refused")
            } catch (e: WorkspaceFileOperationException) {
                assertEquals("action $index", FileManagerService.READ_ONLY_FOLDER, e.code)
            }
        }
    }

    @Test
    fun `a real library folder keeps its name and the workspaces folder steps aside`() = runBlocking {
        val env = env(workspaces = listOf("Novel" to temp.newFolder()))
        File(env.library, "Workspaces").mkdirs()
        val names = env.service.list(FileManagerRoot.Library, "").map { it.name }.toSet()
        assertEquals(setOf("Workspaces", "Workspaces (1)"), names)
        assertEquals(listOf("Novel"), env.service.list(FileManagerRoot.Library, "Workspaces (1)").map { it.name })
        assertEquals(emptyList<String>(), env.service.list(FileManagerRoot.Library, "Workspaces").map { it.name })
    }

    @Test
    fun `picked items resolve to the folder they really live in`() = runBlocking {
        val novel = temp.newFolder()
        File(novel, "a.md").writeText("a")
        val env = env(workspaces = listOf("Novel" to novel))
        env.library.mkdirs()
        val sources = env.service.sources(FileManagerRoot.Library, listOf("Workspaces/Novel/a.md"))
        assertEquals(listOf(FileManagerSource.Items(novel, listOf("a.md"))), sources)
        assertEquals(listOf(FileManagerSource.Items(env.library, listOf("x"))), env.service.sources(FileManagerRoot.Library, listOf("x")))
        // A whole workspace folder is its files area, copied under the workspace's name
        assertEquals(listOf(FileManagerSource.Folder(novel, "Novel")), env.service.sources(FileManagerRoot.Library, listOf("Workspaces/Novel")))
        assertReadOnly { env.service.sources(FileManagerRoot.Library, listOf("Workspaces")) }
    }

    @Test
    fun `only paths with a real folder behind them have a real file`() {
        val novel = temp.newFolder()
        val env = env(workspaces = listOf("Novel" to novel))
        assertEquals(null, env.service.realFile(FileManagerRoot.Library, "Workspaces"))
        assertEquals(novel.canonicalFile, env.service.realFile(FileManagerRoot.Library, "Workspaces/Novel")?.canonicalFile)
        assertEquals(File(novel, "a.md").canonicalFile, env.service.realFile(FileManagerRoot.Library, "Workspaces/Novel/a.md")?.canonicalFile)
    }

    @Test
    fun `a workspace folder copies out under its name and is never moved`() = runBlocking {
        val novel = temp.newFolder()
        File(novel, "ch1").mkdirs()
        File(novel, "ch1/1.md").writeText("one")
        File(novel, "notes.txt").writeText("n")
        val env = env(workspaces = listOf("Novel" to novel))
        env.library.mkdirs()

        val first = env.service.transfer(FileManagerRoot.Library, listOf("Workspaces/Novel"), FileManagerRoot.Library, "", WorkspaceTransferMode.COPY)
        assertEquals(listOf("Novel"), first.items.map { it.target })
        assertEquals("one", File(env.library, "Novel/ch1/1.md").readText())
        assertEquals("n", File(env.library, "Novel/notes.txt").readText())

        val second = env.service.transfer(FileManagerRoot.Library, listOf("Workspaces/Novel"), device, "", WorkspaceTransferMode.COPY)
        assertEquals(listOf("Novel"), second.items.map { it.target })
        val third = env.service.transfer(FileManagerRoot.Library, listOf("Workspaces/Novel"), device, "", WorkspaceTransferMode.COPY)
        assertEquals(listOf("Novel (1)"), third.items.map { it.target })
        assertTrue(third.items.single().renamed)
        assertEquals("one", File(env.phone, "Novel (1)/ch1/1.md").readText())

        // Copying into the workspace itself would never end
        val into = env.service.transfer(FileManagerRoot.Library, listOf("Workspaces/Novel"), FileManagerRoot.Library, "Workspaces/Novel/ch1", WorkspaceTransferMode.COPY)
        assertEquals(WorkspaceFileOperationException.TRANSFER_INTO_ITSELF, into.items.single().errorCode)

        assertReadOnly { env.service.transfer(FileManagerRoot.Library, listOf("Workspaces/Novel"), device, "", WorkspaceTransferMode.MOVE) }
        assertReadOnly { env.service.transfer(FileManagerRoot.Library, listOf("Workspaces"), device, "", WorkspaceTransferMode.COPY) }
        assertEquals("one", File(novel, "ch1/1.md").readText())
    }

    @Test
    fun `a workspace folder zips as one folder named after it`() = runBlocking {
        val novel = temp.newFolder()
        File(novel, "ch1").mkdirs()
        File(novel, "ch1/1.md").writeText("one")
        val env = env(workspaces = listOf("Novel" to novel, "Empty" to temp.newFolder()))
        env.library.mkdirs()

        val zip = env.service.zip(FileManagerRoot.Library, "", listOf("Workspaces/Novel"), "Novel")
        ZipFile(File(env.library, zip.path)).use { file ->
            assertEquals(listOf("Novel/", "Novel/ch1/", "Novel/ch1/1.md"), file.entries().toList().map { it.name })
        }
        val empty = env.service.zip(FileManagerRoot.Library, "", listOf("Workspaces/Empty"), "Empty")
        ZipFile(File(env.library, empty.path)).use { file -> assertEquals(listOf("Empty/"), file.entries().toList().map { it.name }) }
        assertReadOnly { env.service.zip(FileManagerRoot.Library, "", listOf("Workspaces"), "all") }
    }

    private inline fun assertReadOnly(action: () -> Unit) {
        try {
            action()
            fail("should be refused")
        } catch (e: WorkspaceFileOperationException) {
            assertEquals(FileManagerService.READ_ONLY_FOLDER, e.code)
        }
    }
}
