package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.IOException
import java.io.ObjectOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import me.rerere.workspace.WorkspaceStorageArea

/**
 * Real-behavior unit tests for [exportWorkspaceFilesToDirectory]: an in-memory
 * [WorkspaceExportDirectory] fake exercises naming collisions, binary fidelity,
 * progress, stream closing, directory rejection, fail-closed queries,
 * documentId reuse rejection, partial-artifact cleanup and cancellation —
 * the same code path the SAF adapter drives in production.
 */
class WorkspaceDirectoryExporterTest {

    private class TrackedStream(val target: FakeDoc) : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        var closed = false
            private set

        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun close() {
            closed = true
            target.data = delegate.toByteArray()
        }
    }

    private class FakeDoc(val id: String, val name: String) {
        var data: ByteArray? = null
        var openAttempts = 0
        lateinit var stream: TrackedStream
    }

    private class FakeDirectory(
        existing: Map<String, String> = emptyMap(),
    ) : WorkspaceExportDirectory {
        /** Pre-existing documents, docId -> displayName. Never touched by export. */
        val preExisting = existing.toMutableMap()
        val created = LinkedHashMap<String, FakeDoc>()
        val deleted = mutableListOf<String>()
        var protectedIds: Set<String> = emptySet()
        override val protectedDocumentIds: Set<String> get() = protectedIds
        var childrenCalls = 0
        var childrenError: Throwable? = null
        var createError: Throwable? = null
        var createReturnsNull = false
        var createResultOverride: ((String, String) -> WorkspaceCreatedDocument?)? = null
        var openReturnsNull = false
        var deleteError: Throwable? = null
        private var nextId = 0

        private inner class FakeCreated(override val documentId: String) :
            WorkspaceCreatedDocument

        override fun children(): Map<String, String> {
            childrenCalls++
            childrenError?.let { throw it }
            return preExisting.toMap()
        }

        override fun create(displayName: String, mimeType: String): WorkspaceCreatedDocument? {
            createResultOverride?.let { return it(displayName, mimeType) }
            createError?.let { throw it }
            if (createReturnsNull) return null
            val id = "doc-${nextId++}"
            created[id] = FakeDoc(id, displayName)
            return FakeCreated(id)
        }

        override fun openOutput(document: WorkspaceCreatedDocument): OutputStream? {
            if (openReturnsNull) return null
            val doc = created[document.documentId] ?: return null
            doc.openAttempts++
            return TrackedStream(doc).also { doc.stream = it }
        }

        override fun delete(document: WorkspaceCreatedDocument) {
            deleteError?.let { throw it }
            deleted += document.documentId
        }
    }

    private fun file(path: String, isDirectory: Boolean = false) =
        WorkspaceDirectoryExportFile(
            path = path,
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
        )

    private fun export(
        files: List<WorkspaceDirectoryExportFile>,
        target: FakeDirectory,
        contents: Map<String, ByteArray> = emptyMap(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): WorkspaceDirectoryExportResult = runBlocking {
        exportWorkspaceFilesToDirectory(
            files = files,
            target = target,
            onProgress = onProgress,
        ) { f, out ->
            out.write(contents[f.path] ?: f.path.toByteArray())
        }
    }

    private fun FakeDirectory.byName(name: String): FakeDoc? =
        created.values.singleOrNull { it.name == name }

    @Test
    fun `happy path writes binary content and closes streams`() {
        val target = FakeDirectory()
        val payload = byteArrayOf(0, 1, 2, -1, -128, 42)
        val result = export(
            files = listOf(file("a.txt"), file("b.bin")),
            target = target,
            contents = mapOf("b.bin" to payload),
        )
        assertEquals(2, result.exportedCount)
        assertTrue(result.failures.isEmpty())
        assertArrayEquals("a.txt".toByteArray(), target.byName("a.txt")!!.data)
        assertArrayEquals(payload, target.byName("b.bin")!!.data)
        assertTrue(target.created.values.all { it.stream.closed })
        assertTrue(target.deleted.isEmpty())
    }

    @Test
    fun `existing same-name file is not overwritten and non-conflicting name keeps extension`() {
        val target = FakeDirectory(existing = mapOf("old-1" to "a.txt"))
        val result = export(files = listOf(file("a.txt")), target = target)
        assertEquals(1, result.exportedCount)
        assertTrue(result.failures.isEmpty())
        assertEquals("a.txt".toByteArray().toList(), target.byName("a (1).txt")!!.data!!.toList())
        assertNull(target.byName("a.txt"))
        assertEquals(mapOf("old-1" to "a.txt"), target.preExisting)
    }

    @Test
    fun `collision without extension appends counter to whole name`() {
        val target = FakeDirectory(existing = mapOf("old-1" to "Makefile"))
        export(files = listOf(file("dir/Makefile")), target = target)
        assertTrue(target.byName("Makefile (1)") != null)
    }

    @Test
    fun `hidden dotfile collision keeps whole name as stem`() {
        val target = FakeDirectory(existing = mapOf("old-1" to ".env"))
        export(files = listOf(file(".env")), target = target)
        assertTrue(target.byName(".env (1)") != null)
    }

    @Test
    fun `case difference still counts as collision`() {
        val target = FakeDirectory(existing = mapOf("old-1" to "a.txt"))
        export(files = listOf(file("A.TXT")), target = target)
        assertTrue(target.byName("A (1).TXT") != null)
    }

    @Test
    fun `two files with same name in one run get distinct targets`() {
        val target = FakeDirectory()
        val result = export(
            files = listOf(
                WorkspaceDirectoryExportFile(path = "x/dup.txt", name = "dup.txt"),
                WorkspaceDirectoryExportFile(path = "y/dup.txt", name = "dup.txt"),
            ),
            target = target,
        )
        assertEquals(2, result.exportedCount)
        assertTrue(target.byName("dup.txt") != null)
        assertTrue(target.byName("dup (1).txt") != null)
    }

    @Test
    fun `directory entries are rejected explicitly and never created`() {
        val target = FakeDirectory()
        val result = export(
            files = listOf(file("docs", isDirectory = true), file("a.txt")),
            target = target,
        )
        assertEquals(1, result.exportedCount)
        assertEquals(
            listOf(WorkspaceExportFailure.DIRECTORY_REJECTED),
            result.failures.map { it.reason },
        )
        assertEquals(1, target.created.size)
    }

    @Test
    fun `blank and dot-only names fail as unsafe without touching the provider`() {
        val target = FakeDirectory()
        val result = export(
            files = listOf(
                WorkspaceDirectoryExportFile(path = "x", name = ""),
                WorkspaceDirectoryExportFile(path = ".", name = "."),
                WorkspaceDirectoryExportFile(path = "..", name = ".."),
            ),
            target = target,
        )
        assertEquals(0, result.exportedCount)
        assertTrue(result.failures.all { it.reason == WorkspaceExportFailure.UNSAFE_NAME })
        assertTrue(target.created.isEmpty())
    }

    @Test
    fun `collision exhaustion reports explicit failure`() {
        val existing = buildMap {
            put("id-0", "a.txt")
            (1..100).forEach { put("id-$it", "a ($it).txt") }
        }
        val target = FakeDirectory(existing = existing)
        val result = export(files = listOf(file("a.txt")), target = target)
        assertEquals(0, result.exportedCount)
        assertEquals(WorkspaceExportFailure.NAME_COLLISION_EXHAUSTED, result.failures.single().reason)
    }

    @Test
    fun `children query failure aborts the whole batch before any create`() {
        val target = FakeDirectory().apply { childrenError = IOException("provider gone") }
        try {
            export(files = listOf(file("a.txt")), target = target)
            fail("expected fail-closed propagation")
        } catch (error: IOException) {
            // expected
        }
        assertEquals(1, target.childrenCalls)
        assertTrue(target.created.isEmpty())
    }

    @Test
    fun `create returning a pre-existing docId is rejected and left untouched`() {
        val target = FakeDirectory(existing = mapOf("victim" to "old.txt")).apply {
            createResultOverride = { _, _ -> object : WorkspaceCreatedDocument {
                override val documentId = "victim"
            } }
        }
        val result = export(files = listOf(file("a.txt")), target = target)
        assertEquals(0, result.exportedCount)
        assertEquals(WorkspaceExportFailure.TARGET_ID_REUSED, result.failures.single().reason)
        assertTrue(target.created.isEmpty())
        assertTrue(target.deleted.isEmpty())
    }

    @Test
    fun `create returning an id already used this run is rejected without deleting the earlier doc`() {
        var first = true
        val target = FakeDirectory().apply {
            createResultOverride = { _, _ ->
                if (first) {
                    first = false
                    created["doc-0"] = FakeDoc("doc-0", "a.txt")
                    object : WorkspaceCreatedDocument { override val documentId = "doc-0" }
                } else {
                    object : WorkspaceCreatedDocument { override val documentId = "doc-0" }
                }
            }
        }
        val result = export(files = listOf(file("a.txt"), file("b.txt")), target = target)
        assertEquals(1, result.exportedCount)
        assertEquals(WorkspaceExportFailure.TARGET_ID_REUSED, result.failures.single().reason)
        assertTrue(target.deleted.isEmpty())
    }

    @Test
    fun `create returning null is a file-level failure and export continues`() {
        val target = FakeDirectory().apply { createReturnsNull = true }
        val result = export(
            files = listOf(file("a.txt")),
            target = target,
        )
        assertEquals(0, result.exportedCount)
        assertEquals(WorkspaceExportFailure.WRITE_FAILED, result.failures.single().reason)
    }

    @Test
    fun `openOutput failure deletes the just-created document`() {
        val target = FakeDirectory().apply { openReturnsNull = true }
        val result = export(files = listOf(file("a.txt")), target = target)
        assertEquals(0, result.exportedCount)
        assertEquals(WorkspaceExportFailure.WRITE_FAILED, result.failures.single().reason)
        assertEquals(listOf("doc-0"), target.deleted)
    }

    @Test
    fun `write failure deletes the partial doc and later files still export`() {
        val target = FakeDirectory()
        val result = runBlocking {
            exportWorkspaceFilesToDirectory(
                files = listOf(file("bad.txt"), file("good.txt")),
                target = target,
            ) { f, out ->
                if (f.path == "bad.txt") throw IOException("disk full")
                out.write(7)
            }
        }
        assertEquals(1, result.exportedCount)
        assertEquals(WorkspaceExportFailure.WRITE_FAILED, result.failures.single().reason)
        assertEquals(listOf("doc-0"), target.deleted)
        assertTrue(target.byName("good.txt") != null)
    }

    @Test
    fun `cancellation propagates, deletes the in-flight partial and keeps committed files`() {
        val target = FakeDirectory()
        var calls = 0
        try {
            runBlocking {
                exportWorkspaceFilesToDirectory(
                    files = listOf(file("a.txt"), file("b.txt"), file("c.txt")),
                    target = target,
                ) { _, out ->
                    calls++
                    if (calls == 2) throw CancellationException("cancelled by test")
                    out.write(1)
                }
            }
            fail("expected cancellation to propagate")
        } catch (error: CancellationException) {
            // expected
        }
        // a.txt committed (doc-0 kept), b.txt partial deleted (doc-1), c.txt never attempted
        assertTrue(target.byName("a.txt") != null)
        assertEquals(listOf("doc-1"), target.deleted)
        assertEquals(2, target.created.size)
    }

    @Test
    fun `deletion of a partial artifact is best effort`() {
        val target = FakeDirectory().apply {
            openReturnsNull = true
            deleteError = RuntimeException("delete denied")
        }
        val result = export(files = listOf(file("a.txt")), target = target)
        assertEquals(0, result.exportedCount)
        assertEquals(WorkspaceExportFailure.WRITE_FAILED, result.failures.single().reason)
    }

    @Test
    fun `create throwing counts as write failure without delete`() {
        val target = FakeDirectory().apply { createError = SecurityException("denied") }
        val result = export(files = listOf(file("a.txt")), target = target)
        assertEquals(0, result.exportedCount)
        assertEquals(WorkspaceExportFailure.WRITE_FAILED, result.failures.single().reason)
        assertTrue(target.deleted.isEmpty())
    }

    @Test
    fun `progress reports once per input file in order`() {
        val target = FakeDirectory()
        val progress = mutableListOf<Pair<Int, Int>>()
        export(
            files = listOf(file("a.txt"), file("dir", isDirectory = true), file("c.txt")),
            target = target,
            onProgress = { done, total -> progress += done to total },
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun `resolveNonConflictingExportName prefers the original and keeps extension`() {
        val taken = setOf("a.txt", "a (1).txt")
        assertEquals("a (2).txt", resolveNonConflictingExportName("a.txt", taken))
        assertEquals("b.txt", resolveNonConflictingExportName("b.txt", taken))
        assertNull(resolveNonConflictingExportName("", taken))
        assertNull(resolveNonConflictingExportName("..", taken))
    }

    @Test
    fun `pending snapshot is serializable for rememberSaveable`() {
        val pending = PendingDirectoryExport(
            area = WorkspaceStorageArea.LINUX,
            files = arrayListOf(file("a.txt"), file("dir/b.bin")),
        )
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).writeObject(pending) }
        assertTrue(bytes.size() > 0)
        val restored = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            it.readObject() as PendingDirectoryExport
        }
        assertEquals(pending, restored)
    }

    @Test fun `separator and nul names never reach createDocument`() {
        val target = FakeDirectory()
        val result = export(
            listOf("../victim", "a/b.txt", "a\\b.txt", "a\u0000b").map {
                WorkspaceDirectoryExportFile("source", it)
            }, target,
        )
        assertEquals(4, result.failures.size)
        assertTrue(result.failures.all { it.reason == WorkspaceExportFailure.UNSAFE_NAME })
        assertTrue(target.created.isEmpty())
    }

    @Test fun `parent document id is protected and never opened or deleted`() {
        val target = FakeDirectory().apply {
            protectedIds = setOf("parent")
            createResultOverride = { _, _ -> object : WorkspaceCreatedDocument {
                override val documentId = "parent"
            } }
        }
        val result = export(listOf(file("a.txt")), target)
        assertEquals(WorkspaceExportFailure.TARGET_ID_REUSED, result.failures.single().reason)
        assertTrue(target.created.isEmpty())
        assertTrue(target.deleted.isEmpty())
    }

    @Test fun `failed creation id remains protected even if cleanup fails`() {
        val target = FakeDirectory().apply {
            deleteError = IOException("cleanup denied")
            createResultOverride = { name, _ ->
                if (!created.containsKey("same-id")) created["same-id"] = FakeDoc("same-id", name)
                object : WorkspaceCreatedDocument { override val documentId = "same-id" }
            }
        }
        val result = runBlocking {
            exportWorkspaceFilesToDirectory(listOf(file("bad.txt"), file("good.txt")), target) { _, _ ->
                throw IOException("write failed")
            }
        }
        assertEquals(
            listOf(WorkspaceExportFailure.WRITE_FAILED, WorkspaceExportFailure.TARGET_ID_REUSED),
            result.failures.map { it.reason },
        )
        assertEquals(1, target.created.getValue("same-id").openAttempts)
        assertTrue(target.deleted.isEmpty())
    }

    @Test fun `live query discovers provider-renamed targets between files`() {
        val target = FakeDirectory()
        val result = export(listOf(file("first.txt"), file("renamed.txt")), target, onProgress = { done, _ ->
            if (done == 1) target.preExisting["doc-0"] = "renamed.txt"
        })
        assertEquals(2, result.exportedCount)
        assertTrue(target.byName("renamed (1).txt") != null)
    }

    @Test fun `live query discovers another writer's newly added file`() {
        val target = FakeDirectory()
        val result = export(listOf(file("first.txt"), file("user.txt")), target, onProgress = { done, _ ->
            if (done == 1) target.preExisting["user-id"] = "user.txt"
        })
        assertEquals(2, result.exportedCount)
        assertTrue(target.byName("user (1).txt") != null)
        assertTrue(target.deleted.isEmpty())
    }

    @Test fun `failed requery aborts later exports and keeps committed files`() {
        val target = FakeDirectory()
        try {
            export(listOf(file("first.txt"), file("second.txt")), target, onProgress = { done, _ ->
                if (done == 1) target.childrenError = IOException("requery denied")
            })
            fail("query failure must abort safely")
        } catch (_: IOException) { }
        assertEquals(1, target.created.size)
        assertTrue(target.byName("first.txt")!!.stream.closed)
        assertTrue(target.deleted.isEmpty())
    }

    @Test fun `real cancellation during create cleans its new doc without opening output`() {
        val target = FakeDirectory()
        try {
            runBlocking {
                val job = currentCoroutineContext().job
                target.createResultOverride = { name, _ ->
                    target.created["partial"] = FakeDoc("partial", name)
                    job.cancel()
                    object : WorkspaceCreatedDocument { override val documentId = "partial" }
                }
                exportWorkspaceFilesToDirectory(listOf(file("a.txt")), target) { _, out -> out.write(1) }
            }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, target.created.getValue("partial").openAttempts)
        assertEquals(listOf("partial"), target.deleted)
    }

    @Test fun `empty batch does not query the provider`() {
        val target = FakeDirectory().apply { childrenError = IOException("must not query") }
        assertEquals(WorkspaceDirectoryExportResult(0, 0, emptyList()), export(emptyList(), target))
        assertEquals(0, target.childrenCalls)
    }

    @Test fun `new child membership is verified before claiming the returned uri`() {
        validateCreatedExportDocument("parent", "new", true, setOf("new", "existing"))
        for (case in listOf(
            Triple("outside", true, setOf("new")),
            Triple("parent", true, setOf("parent", "new")),
            Triple("new", false, setOf("new")),
            Triple("", true, setOf("")),
        )) {
            try {
                validateCreatedExportDocument("parent", case.first, case.second, case.third)
                fail("unverified document must not be claimed")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun `fatal provider errors are not disguised as ordinary write failures`() {
        val target = FakeDirectory().apply { createError = AssertionError("fatal") }
        try {
            export(listOf(file("a.txt")), target)
            fail("must propagate fatal error")
        } catch (error: AssertionError) {
            assertEquals("fatal", error.message)
        }
    }
}

