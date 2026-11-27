package me.rerere.rikkihub.data.book

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.buildWorkspaceTools
import me.rerere.rikkihub.data.book.history.BookHistory
import me.rerere.rikkihub.data.book.history.BookHistoryActions
import me.rerere.rikkihub.data.book.history.ChapterDiff
import me.rerere.rikkihub.data.book.history.BookHistoryStore
import me.rerere.rikkihub.data.book.history.HistoryEntry
import me.rerere.rikkihub.data.book.history.HistorySource
import me.rerere.rikkihub.data.book.transfer.BookImport
import me.rerere.rikkihub.data.book.transfer.BookImporter
import me.rerere.rikkihub.data.book.transfer.ExportFormat
import me.rerere.rikkihub.data.book.transfer.ExportOptions
import me.rerere.rikkihub.data.book.transfer.ImportChapter
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.workspace.LocalWorkspaceBackend
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceChanges
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class BookStoreTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var store: BookStore
    private lateinit var repository: WorkspaceRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val row = WorkspaceEntity(
        id = "w", name = "长夜", root = "w", shellStatus = WorkspaceShellStatus.DISABLED.name,
        createdAt = 1, updatedAt = 1, bookMode = true,
    )

    @Before
    fun setUp() {
        base = Files.createTempDirectory("book-store-test").toFile()
        root = File(base, "w/files").apply { mkdirs() }
        var deleted = false
        // 只用到 getById 与 deleteById
        val dao = Proxy.newProxyInstance(WorkspaceDAO::class.java.classLoader, arrayOf(WorkspaceDAO::class.java)) { _, method, args ->
            when (method.name) {
                "getById" -> row.takeIf { args[0] == "w" && !deleted }
                "deleteById" -> 1.also { deleted = true }
                else -> error("unexpected ${method.name}")
            }
        } as WorkspaceDAO
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(base, "settings.preferences_pb") })
        val settings = SettingsStore.forTesting(dataStore, MutationRuntime(File(base, "mutation"), File(base, "restore.marker")), scope)
        val manager = WorkspaceManager(base)
        repository = WorkspaceRepository(
            dao = dao,
            manager = LocalWorkspaceBackend(manager, RootfsInstaller(manager), File("/nonexistent-native"), null),
            settingsStore = settings,
            runtimeGate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L),
            onDeleted = { id -> store.forget(id); replacer().forget(id) },
        )
        store = newStore()
    }

    private fun newStore() = BookStore(repository, File(base, "cache"))

    @After
    fun tearDown() {
        scope.cancel()
        base.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs() }.writeText(text)

    private val manifestFile get() = File(root, ".book/book.json")

    private fun reasonOf(block: suspend () -> Unit) = runBlocking {
        (runCatching { block() }.exceptionOrNull() as BookManifestException).reason
    }

    @Test
    fun `first open builds the structure and later files wait outside`() = runBlocking {
        write("第1章.md", "# 起\n你好。")
        write("第2章.md", "再见。")
        write("设定.md", "x")
        val book = store.open("w")
        assertEquals("长夜", book.manifest.title)
        assertEquals(listOf("起", "第2章"), book.entries.map { it.title })
        assertEquals(listOf("设定.md"), book.unlisted)
        write("第3章.md", "新的")
        assertEquals(listOf("第3章.md", "设定.md").toSet(), store.open("w").unlisted.toSet())
        assertEquals(2, store.open("w").entries.size)
    }

    @Test
    fun `hidden folders and node_modules are not part of the book`() = runBlocking {
        write(".git/第1章.md", "x")
        write("node_modules/第2章.md", "x")
        write(".book/chapters/第3章.md", "x")
        val book = store.open("w")
        assertTrue(book.entries.isEmpty() && book.unlisted.isEmpty())
    }

    @Test
    fun `a file renamed outside keeps its chapter, even after a restart`() = runBlocking {
        write("第1章.md", "内容甲")
        write("第2章.md", "内容乙")
        val before = store.open("w")
        store.edit("w") { it.manifest.updateChapter(before.entries[0].id) { chapter -> chapter.copy(synopsis = "梗概") } }
        File(root, "第1章.md").renameTo(File(root, "第1章 归来.md"))
        val after = newStore().open("w")
        assertEquals("第1章 归来.md", after.entry(before.entries[0].id)!!.path)
        assertEquals("梗概", after.entry(before.entries[0].id)!!.chapter.synopsis)
        assertTrue(after.unlisted.isEmpty())
    }

    @Test
    fun `a deleted file leaves its metadata and can be relinked`() = runBlocking {
        write("第1章.md", "内容甲")
        val id = store.open("w").entries[0].id
        File(root, "第1章.md").delete()
        assertTrue(store.open("w").entries[0].missing)
        write("重写.md", "完全不同")
        val relinked = store.relink("w", id, "重写.md")
        assertEquals("重写.md", relinked.entry(id)!!.path)
        assertFalse(relinked.entry(id)!!.missing)
    }

    @Test
    fun `broken or newer structure is never overwritten, rebuilding keeps the bad file`() = runBlocking {
        write("第1章.md", "x")
        write(".book/book.json", "{ broken")
        assertEquals(BookManifestException.Reason.CORRUPT, reasonOf { store.open("w") })
        assertEquals(BookManifestException.Reason.CORRUPT, reasonOf { store.edit("w") { it.manifest } })
        assertEquals("{ broken", manifestFile.readText())
        val rebuilt = store.rebuild("w")
        assertEquals(1, rebuilt.entries.size)
        assertTrue(File(root, ".book").listFiles()!!.any { it.name.endsWith(".broken.json") && it.readText() == "{ broken" })
        val newer = """{"format":"rikkihub-book","version":9,"items":[]}"""
        manifestFile.writeText(newer)
        assertEquals(BookManifestException.Reason.NEWER_VERSION, reasonOf { store.edit("w") { it.manifest.copy(title = "x") } })
        assertEquals(newer, manifestFile.readText())
    }

    @Test
    fun `parallel edits all land`() = runBlocking {
        write("第1章.md", "x")
        store.open("w")
        withContext(Dispatchers.IO) {
            (1..20).map { index ->
                async { store.edit("w") { it.manifest.addVolume(BookVolume("v$index", "卷$index")) } }
            }.awaitAll()
        }
        assertEquals((1..20).map { "v$it" }.toSet(), store.open("w").manifest.items.filterIsInstance<BookVolume>().map { it.id }.toSet())
    }

    @Test
    fun `an edit that throws writes nothing`() = runBlocking {
        write("第1章.md", "x")
        store.open("w")
        val before = manifestFile.readText()
        assertTrue(runCatching { store.edit("w") { error("no") } }.isFailure)
        assertEquals(before, manifestFile.readText())
    }

    @Test
    fun `creating a chapter never replaces a file and lands after its anchor`() = runBlocking {
        write("第1章.md", "甲")
        write("第3章.md", "丙")
        val book = store.open("w")
        val created = store.createChapter("w", "第2章.md", "", book.entries[0].id)
        assertEquals(listOf("第1章.md", "第2章.md", "第3章.md"), created.entries.map { it.path })
        assertTrue(runCatching { store.createChapter("w", "第3章.md", "覆盖", null) }.isFailure)
        assertEquals("丙", File(root, "第3章.md").readText())
    }

    @Test
    fun `word counts follow the file`() = runBlocking {
        write("第1章.md", "你好")
        assertEquals(2, store.open("w").words)
        write("第1章.md", "你好世界")
        File(root, "第1章.md").setLastModified(System.currentTimeMillis() + 5_000)
        assertEquals(4, store.open("w").words)
    }

    @Test
    fun `deleting chapters removes both the files and the entries`() = runBlocking {
        write("第1章.md", "甲")
        write("第2章.md", "乙")
        val book = store.open("w")
        val after = store.deleteChapters("w", setOf(book.entries[0].id))
        assertEquals(listOf("第2章.md"), after.entries.map { it.path })
        assertFalse(File(root, "第1章.md").exists())
    }

    @Test
    fun `the model's file tools leave the book structure alone but can write parallel drafts`() = runBlocking {
        write("第1章.md", "甲")
        store.open("w")
        val manifest = manifestFile.readText()
        Files.createSymbolicLink(File(root, "link").toPath(), File(root, ".book").toPath())
        val tools = buildWorkspaceTools(
            workspace = row, workspaceRepository = repository, includeSubagent = false,
            loadWorkspace = { row }, bookStore = store,
        )
        val writeFile = tools.single { it.name == BuiltinToolIds.WORKSPACE_WRITE_FILE }
        val editFile = tools.single { it.name == BuiltinToolIds.WORKSPACE_EDIT_FILE }
        fun write(path: String) = buildJsonObject { put("path", path); put("text", "{}") }
        val refused = listOf(
            "/workspace/.book/book.json", "/workspace/./.book/book.json", "/workspace//.book/chapters/c1.json",
            "/workspace/link/book.json",
        ).associateWith { path -> runCatching { writeFile.execute(write(path)) }.exceptionOrNull()?.message }
        refused.forEach { (path, message) -> assertTrue("$path: $message", message.orEmpty().contains("book_update")) }
        val edit = buildJsonObject { put("path", "/workspace/.book/book.json"); put("old_text", "rikkihub-book"); put("new_text", "x") }
        assertTrue(runCatching { editFile.execute(edit) }.exceptionOrNull()?.message.orEmpty().contains("book_update"))
        assertEquals(manifest, manifestFile.readText())
        assertFalse(File(root, ".book/chapters/c1.json").exists())

        writeFile.execute(write("/workspace/.book/branches/c1/另一稿.md"))
        writeFile.execute(write("/workspace/.bookish/a.md"))
        assertEquals("{}", File(root, ".book/branches/c1/另一稿.md").readText())
        assertEquals("{}", File(root, ".bookish/a.md").readText())
    }

    // ---- 查找替换 ----------------------------------------------------------------------------

    private fun replacer() = BookReplacer(store, File(base, "undo"))

    private fun query(vararg paths: String) = BookReplaceQuery(listOf(ReplacePair("阿青", "阿白")), false, paths.toList())

    private fun targets(preview: BookReplacePreview) = preview.files.map { BookReplaceTarget(it.path, it.revision, null) }

    @Test
    fun `deleting the workspace forgets its index and replacement undo`() = runBlocking {
        write("第1章.md", "阿青")
        store.open("w")
        replacer().apply("w", query("第1章.md"), targets(replacer().preview("w", query("第1章.md"))))
        val index = File(base, "cache/w.json")
        val undo = File(base, "undo/w")
        assertTrue(index.exists() && undo.list().orEmpty().isNotEmpty())
        File(base, "undo/other").mkdirs()

        assertTrue(repository.delete("w"))
        assertFalse(index.exists())
        assertFalse(undo.exists())
        assertTrue(File(base, "undo/other").exists())
        assertFalse(File(base, "w").exists())
    }

    @Test
    fun `replacing across chapters can be undone`() = runBlocking {
        write("第1章.md", "阿青下山。\r\n阿青")
        write("第2章.md", "老人")
        write("第3章.md", "阿青回来")
        val replacer = replacer()
        val query = query("第1章.md", "第2章.md", "第3章.md", "没有.md")
        val preview = replacer.preview("w", query)
        assertEquals(listOf("第1章.md", "第3章.md"), preview.files.map { it.path })
        assertEquals(listOf("没有.md"), preview.unreadable)
        val result = replacer.apply("w", query, targets(preview))
        assertEquals(3, result.replaced)
        assertEquals("阿白下山。\r\n阿白", File(root, "第1章.md").readText())
        val undone = replacer.undo("w", result.undoId!!)
        assertEquals(2, undone.restored.size)
        assertEquals("阿青下山。\r\n阿青", File(root, "第1章.md").readText())
        assertTrue(replacer.history("w").isEmpty())
    }

    @Test
    fun `a file changed after the search is left alone`() = runBlocking {
        write("第1章.md", "阿青")
        write("第2章.md", "阿青")
        val replacer = replacer()
        val query = query("第1章.md", "第2章.md")
        val preview = replacer.preview("w", query)
        write("第1章.md", "阿青改过了")
        val result = replacer.apply("w", query, targets(preview))
        assertEquals(listOf("第1章.md"), result.conflicts)
        assertEquals(listOf("第2章.md"), result.changed)
        assertEquals("阿青改过了", File(root, "第1章.md").readText())
    }

    @Test
    fun `undo leaves files edited after the replacement and can be retried`() = runBlocking {
        write("第1章.md", "阿青")
        write("第2章.md", "阿青")
        val replacer = replacer()
        val query = query("第1章.md", "第2章.md")
        val result = replacer.apply("w", query, targets(replacer.preview("w", query)))
        write("第1章.md", "阿白接着写")
        val undone = replacer.undo("w", result.undoId!!)
        assertEquals(listOf("第2章.md"), undone.restored)
        assertEquals(listOf("第1章.md"), undone.changedSince)
        assertEquals("阿白接着写", File(root, "第1章.md").readText())
        assertEquals(listOf("第1章.md"), replacer.history("w").single().files.map { it.path })
        write("第1章.md", "阿白")
        assertEquals(listOf("第1章.md"), replacer.undo("w", result.undoId!!).restored)
    }

    @Test
    fun `only the last ten replacements can be undone and nothing is kept for an empty one`() = runBlocking {
        val replacer = replacer()
        write("第1章.md", "阿青")
        repeat(13) { index ->
            val from = if (index % 2 == 0) "阿青" else "阿白"
            val to = if (index % 2 == 0) "阿白" else "阿青"
            val query = BookReplaceQuery(listOf(ReplacePair(from, to)), false, listOf("第1章.md"))
            replacer.apply("w", query, targets(replacer.preview("w", query)))
            Thread.sleep(3)
        }
        assertEquals(10, replacer.history("w").size)
        val none = replacer.apply("w", query("第1章.md"), emptyList())
        assertEquals(null, none.undoId)
        assertEquals(10, File(base, "undo/w").listFiles()!!.size)
    }

    // ---- 导入导出 ----------------------------------------------------------------------------

    private fun import(vararg titles: String, images: Map<String, () -> ByteArray?> = emptyMap(), cover: String? = null) = BookImport(
        "书名", "作者", "zh", "md",
        titles.map { ImportChapter(it, "# $it\n\n![图](images/a.png)\n") }, images, cover,
    )

    private fun leftovers() = File(root, ".book").listFiles().orEmpty().filter { it.name.startsWith("import-") }

    @Test
    fun `importing into an empty book fills in its details`() = runBlocking {
        val book = BookImporter(store).import("w", import("起", "承", images = mapOf("a.png" to { byteArrayOf(7) }), cover = "a.png"))
        assertEquals(listOf("起", "承"), book.entries.map { it.title })
        assertEquals("书名", book.manifest.title)
        assertEquals("images/a.png", book.manifest.cover)
        assertEquals(listOf("0001 起.md", "0002 承.md", "images"), root.list()!!.filterNot { it.startsWith(".") }.sorted())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `importing into a written book appends and keeps the details and pictures there`() = runBlocking {
        write("第1章.md", "已有")
        write("images/a.png", "old")
        store.open("w")
        val book = BookImporter(store).import("w", import("起", images = mapOf("a.png" to { byteArrayOf(7) }), cover = "a.png"))
        assertEquals(listOf("第1章", "起"), book.entries.map { it.title })
        assertEquals("长夜", book.manifest.title)
        assertEquals("", book.manifest.cover)
        assertEquals("old", File(root, "images/a.png").readText())
        assertTrue(File(root, "0002 起.md").readText().contains("(images-2/a.png)"))
        assertTrue(File(root, "images-2/a.png").isFile)
    }

    @Test
    fun `a failed import leaves nothing behind`() = runBlocking {
        write("第1章.md", "已有")
        store.open("w")
        write("0003 承.md", "别动我")
        val before = manifestFile.readText()
        assertTrue(runCatching { BookImporter(store).import("w", import("起", "承")) }.isFailure)
        assertEquals("别动我", File(root, "0003 承.md").readText())
        assertFalse(File(root, "0002 起.md").exists())
        assertEquals(before, manifestFile.readText())
        // 图片读到一半失败也一样
        val broken = import("转", images = mapOf("b.png" to { error("broken") }))
        assertTrue(runCatching { BookImporter(store).import("w", broken) }.isFailure)
        assertEquals(setOf("第1章.md", "0003 承.md"), root.list()!!.filterNot { it.startsWith(".") }.toSet())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `an export into the book is not offered as a chapter and can be overwritten`() = runBlocking {
        write("第1章.md", "你好")
        val options = ExportOptions(ExportFormat.TXT)
        store.exportInto(store.open("w"), options, "导出/长夜.txt")
        write("第1章.md", "你好啊")
        store.exportInto(store.open("w"), options, "导出/长夜.txt")
        assertTrue(File(root, "导出/长夜.txt").readText().contains("你好啊"))
        assertTrue(store.open("w").unlisted.isEmpty())
        assertEquals(listOf("长夜.txt"), File(root, "导出").list()!!.toList())
    }

    // ---- 版本史（经运行闸门、BookStore 的真实写入路径） --------------------------------------------

    private fun withHistory(block: suspend (BookHistory) -> Unit) {
        val history = BookHistory(scope).apply { setBooks(mapOf("w" to root)) }
        WorkspaceChanges.observer = history::onChange
        try {
            runBlocking { block(history) }
        } finally {
            WorkspaceChanges.observer = null
        }
    }

    private fun historyVersions(path: String) = BookHistoryStore(root.canonicalFile, { 0 }).let { disk ->
        disk.entries(path).filterIsInstance<HistoryEntry.Version>().map { disk.read(it.sha)!!.decodeToString() to it.source }
    }

    @Test
    fun `the writer's source survives the runtime gate, replacements and exports`() = withHistory {
        write("第1章.md", "阿青来了")
        val ai = HistorySource.Ai("c", "m", "t")
        withContext(BookHistory.sourceElement(ai)) { repository.writeText("w", "第1章.md", "阿青走了", overwrite = true) }
        replacer().apply("w", query("第1章.md"), targets(replacer().preview("w", query("第1章.md"))))
        assertEquals("阿白走了", File(root, "第1章.md").readText())
        repository.writeText("w", "第1章.md", "手改", overwrite = true)
        assertEquals(
            listOf("阿青来了" to HistorySource.Initial, "阿青走了" to ai, "阿白走了" to HistorySource.Replace),
            historyVersions("第1章.md"),
        )
        val options = ExportOptions(ExportFormat.TXT)
        store.exportInto(store.open("w"), options, "导出/长夜.txt")
        val first = File(root, "导出/长夜.txt").readText()
        write("第1章.md", "又改")
        store.exportInto(store.open("w"), options, "导出/长夜.txt")
        assertEquals(listOf(first), historyVersions("导出/长夜.txt").map { it.first })
    }

    @Test
    fun `restoring a version and taking one paragraph back go through revision checks`() = withHistory { history ->
        write("第1章.md", "甲\n乙\n丙")
        val ai = HistorySource.Ai("c", "m")
        withContext(BookHistory.sourceElement(ai)) { repository.writeText("w", "第1章.md", "甲\n乙改\n丙\n丁", overwrite = true) }
        val actions = BookHistoryActions(store, history)
        val version = history.entries("w", "第1章.md").filterIsInstance<HistoryEntry.Version>().single()
        val comparison = actions.compare("w", "第1章.md", version.sha)
        val blocks = comparison.diff.blocks.filterIsInstance<ChapterDiff.Block.Changed>()
        assertEquals(2, blocks.size)
        // 稿子在比较之后变了：取回被版本校验挡住
        repository.writeText("w", "第1章.md", "甲\n乙改\n丙\n丁\n戊", overwrite = true)
        assertTrue(runCatching { actions.takeBack("w", "第1章.md", version.sha, blocks[0].index, comparison.current.revision) }.isFailure)
        val fresh = actions.compare("w", "第1章.md", version.sha)
        val first = fresh.diff.blocks.filterIsInstance<ChapterDiff.Block.Changed>().first()
        val revision = actions.takeBack("w", "第1章.md", version.sha, first.index, fresh.current.revision)
        assertEquals("甲\n乙\n丙\n丁\n戊", File(root, "第1章.md").readText())
        actions.restore("w", "第1章.md", version.sha, revision)
        assertEquals("甲\n乙\n丙", File(root, "第1章.md").readText())
        // 还原前的稿子自动留了一版
        val texts = history.entries("w", "第1章.md").filterIsInstance<HistoryEntry.Version>().map { history.read("w", it.sha) }
        assertTrue("甲\n乙\n丙\n丁\n戊" in texts)
    }

    @Test
    fun `a chapter put back from the trash is relinked when its entry is still waiting`() = withHistory { history ->
        write("第1章.md", "甲")
        write("第2章.md", "乙")
        val book = store.open("w")
        val id = book.entryByPath("第1章.md")!!.id
        // 文件被删了但目录项还在（显示“找不到”）
        repository.deleteFiles("w", WorkspaceStorageArea.FILES, listOf("第1章.md"))
        assertTrue(store.open("w").entry(id)!!.missing)
        write("第1章.md", "别人的新文件")
        val trashed = history.trashed("w").single()
        val actions = BookHistoryActions(store, history)
        val path = actions.restoreTrashed("w", trashed.key, trashed.name, trashed.path)
        assertEquals("第1章 (1).md", path)
        // 原路径上又有文件了，目录项不再“找不到”，放回的留在未编入里
        assertTrue("第1章 (1).md" in store.open("w").unlisted)
        // 章节删掉后放回：在未编入里
        store.deleteChapters("w", setOf(store.open("w").entryByPath("第2章.md")!!.id))
        val second = history.trashed("w").first { it.path == "第2章.md" }
        assertEquals("第2章.md", actions.restoreTrashed("w", second.key, second.name, second.path))
        assertTrue("第2章.md" in store.open("w").unlisted)
    }

    @Test
    fun `deleting chapters puts their files in the trash`() = withHistory {
        write("第1章.md", "甲")
        val id = store.open("w").entries.single().id
        store.deleteChapters("w", setOf(id))
        assertFalse(File(root, "第1章.md").exists())
        assertEquals(listOf("甲"), File(base, "w/history/trash").walkTopDown().filter { it.isFile }.map { it.readText() }.toList())
    }

    @Test
    fun `pictures outside the book are not read`() = runBlocking {
        File(base, "secret.png").writeText("x")
        write("images/a.png", "ok")
        assertEquals("ok", store.readBytes("w", "images/a.png")!!.decodeToString())
        assertEquals(null, store.readBytes("w", "../../secret.png"))
        Files.createSymbolicLink(File(root, "link.png").toPath(), File(base, "secret.png").toPath())
        assertEquals(null, store.readBytes("w", "link.png"))
    }

    // ---- 章节笔记 ----------------------------------------------------------------------------

    private val notesDir get() = File(root, ".book/chapters")

    @Test
    fun `notes survive a round trip with fields of later versions`() = runBlocking {
        store.open("w")
        val draft = BeatDraft("d1", "正文", "m", "短一点", 5, "basis")
        store.editNotes("w", "c1") {
            it.copy(
                beats = listOf(Beat("b1", "等船", 800, listOf(draft), "d1", true, "正文")),
                outline = ChapterOutline("h", 9, "梗概", listOf(PlotPoint("p1", "等船", "她等"))),
                comparison = OutlineComparison("x", 1, listOf(BeatMatch("b1", BeatState.CHANGED, listOf("p1"), "改了"))),
            )
        }
        val file = File(notesDir, "c1.json")
        file.writeText(file.readText().replace("\"text\": \"等船\",\n            \"words\"", "\"people\": [\"阿青\"],\n            \"text\": \"等船\",\n            \"words\"").replaceFirst("{", "{\"future\": 1,"))
        val read = store.notes("w", "c1")
        assertEquals(draft, read.beats.single().drafts.single())
        assertEquals("b1", read.comparison!!.matches.single().beatId)
        store.editNotes("w", "c1") { it.copy(beats = it.beats.map { beat -> beat.copy(text = "改了") }) }
        assertTrue(file.readText().contains("\"future\": 1"))
        assertEquals("改了", store.notes("w", "c1").beats.single().text)
    }

    @Test
    fun `a broken notes file is kept aside and a newer one is left alone`() = runBlocking {
        store.open("w")
        notesDir.mkdirs()
        File(notesDir, "c1.json").writeText("{oops")
        assertEquals(
            ChapterNotesException.Reason.CORRUPT,
            (runCatching { store.notes("w", "c1") }.exceptionOrNull() as ChapterNotesException).reason,
        )
        store.editNotes("w", "c1") { it.addBeats(listOf("甲")) }
        assertEquals(listOf("甲"), store.notes("w", "c1").beats.map { it.text })
        assertEquals("{oops", notesDir.listFiles()!!.single { it.name.endsWith(".broken.json") }.readText())
        File(notesDir, "c2.json").writeText("{\"version\":99}")
        val newer = runCatching { store.editNotes("w", "c2") { it.addBeats(listOf("甲")) } }.exceptionOrNull() as ChapterNotesException
        assertEquals(ChapterNotesException.Reason.NEWER_VERSION, newer.reason)
        assertEquals("{\"version\":99}", File(notesDir, "c2.json").readText())
    }

    @Test
    fun `a chapter id from a hand edited structure cannot leave the folder`() = runBlocking {
        store.open("w")
        store.editNotes("w", "../../../evil") { it.addBeats(listOf("甲")) }
        assertFalse(File(base, "evil.json").exists() || File(root, "evil.json").exists())
        assertEquals(1, notesDir.list()!!.size)
        assertEquals(listOf("甲"), store.notes("w", "../../../evil").beats.map { it.text })
    }

    @Test
    fun `concurrent note edits are all kept`() = runBlocking {
        store.open("w")
        withContext(Dispatchers.IO) {
            (1..20).map { n -> async { store.editNotes("w", "c1") { it.addBeats(listOf("节拍$n")) } } }.awaitAll()
        }
        assertEquals(20, store.notes("w", "c1").beats.size)
    }

    @Test
    fun `changing the plan makes a comparison stale`() {
        val notes = ChapterNotes(listOf(Beat("b1", "甲")), ChapterOutline("h", 1, "", listOf(PlotPoint("p1", "甲", ""))))
        val compared = notes.copy(comparison = OutlineComparison(notes.comparisonBasis, 1, emptyList()))
        assertFalse(compared.comparisonStale)
        assertFalse(compared.updateBeat("b1") { it.copy(words = 5, locked = true) }.comparisonStale)
        assertTrue(compared.updateBeat("b1") { it.copy(text = "乙") }.comparisonStale)
        assertTrue(compared.addBeats(listOf("丙")).comparisonStale)
    }

    // ---- 定稿锁与全书替换 ----------------------------------------------------------------------

    @Test
    fun `book-wide replace skips a locked chapter and marks hits in locked passages`() = withHistory { history ->
        write("第1章.md", "阿青下山。")
        write("第2章.md", "开头一句。\n阿青站在门口很久都没有说话。\n结尾。")
        store.open("w")
        history.lockChapter("w", "第1章.md", File(root, "第1章.md").readBytes(), web = false)
        history.lockPassage("w", "第2章.md", File(root, "第2章.md").readBytes(), 1..1, web = false)
        val replacer = BookReplacer(store, File(base, "undo"), history)
        val preview = replacer.preview("w", query("第1章.md", "第2章.md"))
        assertTrue(preview.files.first { it.path == "第1章.md" }.locked)
        assertTrue(preview.files.first { it.path == "第2章.md" }.hits.single().locked)

        // 勾上锁定段落里的命中（默认就是勾上的）：替换，锁跟着走，历史仍记全书替换
        val result = replacer.apply("w", query("第1章.md", "第2章.md"), targets(preview))
        assertEquals(listOf("第1章.md"), result.locked)
        assertEquals("阿青下山。", File(root, "第1章.md").readText())
        assertTrue(File(root, "第2章.md").readText().contains("阿白站在门口"))
        assertEquals(listOf("阿白站在门口很久都没有说话。"), history.locksOf("w", "第2章.md")!!.passages.single().lines)
        // 替换前的稿子留了底，现在的稿子记在全书替换名下（不是手写）
        assertTrue(historyVersions("第2章.md").any { it.first.contains("阿青站在门口") })
        assertEquals(HistorySource.Replace, history.currentWriter("w", "第2章.md"))
    }

    @Test
    fun `untick the locked hits and the passage stays as it was`() = withHistory { history ->
        write("第2章.md", "阿青开头。\n阿青站在门口很久都没有说话。\n结尾。")
        store.open("w")
        history.lockPassage("w", "第2章.md", File(root, "第2章.md").readBytes(), 1..1, web = false)
        val replacer = BookReplacer(store, File(base, "undo"), history)
        val file = replacer.preview("w", query("第2章.md")).files.single()
        val unlocked = file.hits.filterNot { it.locked }.map { it.start }.toSet()
        replacer.apply("w", query("第2章.md"), listOf(BookReplaceTarget(file.path, file.revision, unlocked)))
        assertEquals("阿白开头。\n阿青站在门口很久都没有说话。\n结尾。", File(root, "第2章.md").readText())
    }

    @Test
    fun `undoing a replace is held back by a chapter locked afterwards`() = withHistory { history ->
        write("第1章.md", "阿青下山。")
        store.open("w")
        val replacer = BookReplacer(store, File(base, "undo"), history)
        val result = replacer.apply("w", query("第1章.md"), targets(replacer.preview("w", query("第1章.md"))))
        history.lockChapter("w", "第1章.md", File(root, "第1章.md").readBytes(), web = false)
        val undone = replacer.undo("w", result.undoId!!)
        assertEquals(listOf("第1章.md"), undone.locked)
        assertEquals("阿白下山。", File(root, "第1章.md").readText())
    }

    // ---- 逐处审阅：读、完成、撤销（经 BookStore 的真实写入路径） ------------------------------------

    private val turn = HistorySource.Ai("c", "m1", "t1")

    /** AI 这一轮写了 [writes]（路径 → 全文；文件不在就新建），收尾后返回审阅页。 */
    private suspend fun aiTurn(history: BookHistory, actions: me.rerere.rikkihub.data.book.history.TurnReviewActions, writes: Map<String, String>): me.rerere.rikkihub.data.book.history.TurnReviewDto {
        withContext(BookHistory.sourceElement(turn)) {
            for ((path, text) in writes) repository.writeText("w", path, text, overwrite = File(root, path).exists())
        }
        val running = scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet()
        history.finishTurn("c", "m1")
        (scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet() - running).forEach { it.join() }
        return actions.load("w", "c", "m1")!!
    }

    private fun pick(file: me.rerere.rikkihub.data.book.history.TurnFileDto, reject: Set<Int> = emptySet(), whole: Boolean = false) =
        me.rerere.rikkihub.data.book.history.TurnFilePick(file.key, file.revision, file.before, file.after, reject, whole)

    private val p1 = "雨下了一整夜，镇上的石板路泛着冷光。"
    private val p2 = "他推开院门，院子里积着一洼洼的水。"
    private val p3 = "远处传来几声狗叫，又很快安静下来。"

    @Test
    fun `rejecting blocks writes them back, keeps a version first and marks the turn reviewed`() = withHistory { history ->
        val actions = me.rerere.rikkihub.data.book.history.TurnReviewActions(store, history)
        File(root, "第1章.md").writeBytes(("\uFEFF" + listOf(p1, p2, p3).joinToString("\r\n\r\n")).toByteArray())
        history.scanAll()
        val review = aiTurn(history, actions, mapOf("第1章.md" to "\uFEFF" + listOf("雨停了。", p2, "远处没有声音。").joinToString("\r\n\r\n")))
        val file = review.files.single()
        assertEquals(2, file.blocks.size)
        val outcome = actions.complete("w", "c", "m1", listOf(pick(file, setOf(0)))).single()
        assertEquals(me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.OK, outcome.outcome)
        // 只换回第一块；BOM 与 CRLF 由快照层照原文件保留
        assertEquals("\uFEFF" + listOf(p1, p2, "远处没有声音。").joinToString("\r\n\r\n"), File(root, "第1章.md").readText())
        assertTrue(historyVersions("第1章.md").any { it.first.contains("雨停了。") })
        val again = actions.load("w", "c", "m1")!!
        assertEquals(me.rerere.rikkihub.data.book.history.TurnStatus.REVIEWED, again.status)
        assertEquals(1, again.reverted)
        assertEquals(me.rerere.rikkihub.data.book.history.TurnBlockDto.BlockState.REVERTED, again.files.single().blocks[0].state)
        // 撤销：回到审阅前，记录回到待审
        actions.undo("w", "c", "m1")
        assertEquals("\uFEFF" + listOf("雨停了。", p2, "远处没有声音。").joinToString("\r\n\r\n"), File(root, "第1章.md").readText())
        assertEquals(me.rerere.rikkihub.data.book.history.TurnStatus.PENDING, actions.load("w", "c", "m1")!!.status)
    }

    @Test
    fun `a locked chapter, a changed draft and a stale record each fail on their own`() = withHistory { history ->
        val actions = me.rerere.rikkihub.data.book.history.TurnReviewActions(store, history)
        write("a.md", listOf(p1, p2).joinToString("\n\n"))
        write("b.md", listOf(p1, p2).joinToString("\n\n"))
        write("c.md", listOf(p1, p2).joinToString("\n\n"))
        history.scanAll()
        val review = aiTurn(history, actions, listOf("a.md", "b.md", "c.md").associateWith { listOf(p1, "他推开木门。").joinToString("\n\n") })
        val (a, b, c) = review.files
        history.lockChapter("w", "a.md", File(root, "a.md").readBytes(), web = false)
        write("b.md", listOf(p1, "他推开木门。", "你接着写的。").joinToString("\n\n"))
        val outcomes = actions.complete("w", "c", "m1", listOf(pick(a, setOf(0)), pick(b, setOf(0)), pick(c, setOf(0)).copy(after = "别的")))
        assertEquals(
            listOf(me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.LOCKED, me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.CONFLICT, me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.CONFLICT),
            outcomes.map { it.outcome },
        )
        assertEquals(me.rerere.rikkihub.data.book.history.TurnStatus.PENDING, actions.load("w", "c", "m1")!!.status)
        // 重新加载后再完成，只处理剩下的
        val fresh = actions.load("w", "c", "m1")!!.files
        val retry = actions.complete("w", "c", "m1", listOf(pick(fresh[1], setOf(0)), pick(fresh[2], setOf(0))))
        assertTrue(retry.all { it.outcome == me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.OK })
        assertEquals(listOf(p1, p2, "你接着写的。").joinToString("\n\n"), File(root, "b.md").readText())
    }

    @Test
    fun `rejecting a new chapter deletes it and undo puts it back in place with its details`() = withHistory { history ->
        val actions = me.rerere.rikkihub.data.book.history.TurnReviewActions(store, history)
        write("第1章.md", p1)
        write("第3章.md", p3)
        val book = store.open("w")
        history.scanAll()
        withContext(BookHistory.sourceElement(turn)) { store.createChapter("w", "第2章.md", p2, book.entries[0].id, title = "插进来的一章") }
        store.edit("w") { it.manifest.updateChapter(it.entryByPath("第2章.md")!!.id) { chapter -> chapter.copy(synopsis = "梗概", status = ChapterStatus.REVISING) } }
        val review = aiTurn(history, actions, emptyMap())
        val created = review.files.single()
        assertEquals(me.rerere.rikkihub.data.book.history.TurnFileDto.Kind.CREATED, created.kind)
        actions.complete("w", "c", "m1", listOf(pick(created, whole = true)))
        assertFalse(File(root, "第2章.md").exists())
        assertEquals(listOf("第1章.md", "第3章.md"), store.open("w").entries.map { it.path })
        actions.undo("w", "c", "m1")
        val back = store.open("w").entries
        assertEquals(listOf("第1章.md", "第2章.md", "第3章.md"), back.map { it.path })
        assertEquals("插进来的一章", back[1].chapter.title)
        assertEquals("梗概", back[1].chapter.synopsis)
        assertEquals(ChapterStatus.REVISING, back[1].chapter.status)
    }

    @Test
    fun `rejecting a deletion puts the file back, undo deletes it again only if unchanged`() = withHistory { history ->
        val actions = me.rerere.rikkihub.data.book.history.TurnReviewActions(store, history)
        write("设定.md", p1)
        history.scanAll()
        // 命令删掉了它
        withContext(BookHistory.sourceElement(turn)) { history.watching("w") { File(root, "设定.md").delete() } }
        val review = aiTurn(history, actions, emptyMap())
        val deleted = review.files.single()
        assertEquals(me.rerere.rikkihub.data.book.history.TurnFileDto.Kind.DELETED, deleted.kind)
        assertEquals(me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.OK, actions.complete("w", "c", "m1", listOf(pick(deleted, whole = true))).single().outcome)
        assertEquals(p1, File(root, "设定.md").readText())
        // 放回后你改过：撤销不动它
        write("设定.md", "你改过了")
        assertEquals(me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.CONFLICT, actions.undo("w", "c", "m1").single().outcome)
        assertEquals("你改过了", File(root, "设定.md").readText())
    }

    @Test
    fun `a version with a BOM compares cleanly and restores without a second BOM`() = withHistory { history ->
        val actions = BookHistoryActions(store, history)
        File(root, "第1章.md").writeBytes("\uFEFF第一段\r\n\r\n第二段".toByteArray())
        history.scanAll()
        val current = store.read("w", "第1章.md")
        store.write("w", "第1章.md", current.content.replace("第二段", "改过的第二段"), current.revision)
        // 写之前的稿子（带 BOM、CRLF 的原字节）留了一版
        val sha = history.entries("w", "第1章.md").filterIsInstance<HistoryEntry.Version>().first().sha
        assertTrue(history.read("w", sha)!!.startsWith("\uFEFF"))
        val comparison = actions.compare("w", "第1章.md", sha)
        // 只有真改了的那一段
        assertEquals(1, comparison.diff.blocks.count { it is ChapterDiff.Block.Changed })
        actions.restore("w", "第1章.md", sha, comparison.current.revision)
        assertEquals("\uFEFF第一段\r\n\r\n第二段", File(root, "第1章.md").readText())
    }

    @Test
    fun `restoring one whole file leaves the other files of the turn to review`() = withHistory { history ->
        val actions = me.rerere.rikkihub.data.book.history.TurnReviewActions(store, history)
        write("a.md", listOf(p1, p2).joinToString("\n\n"))
        write("b.md", listOf(p1, p2).joinToString("\n\n"))
        history.scanAll()
        val review = aiTurn(history, actions, listOf("a.md", "b.md").associateWith { listOf(p1, "他推开木门。").joinToString("\n\n") })
        val a = review.files.single { it.path == "a.md" }
        val outcome = actions.complete("w", "c", "m1", listOf(pick(a, whole = true))).single()
        assertEquals(me.rerere.rikkihub.data.book.history.TurnFileOutcome.Outcome.OK, outcome.outcome)
        assertEquals(listOf(p1, p2).joinToString("\n\n"), File(root, "a.md").readText())
        val after = actions.load("w", "c", "m1")!!
        assertEquals(me.rerere.rikkihub.data.book.history.TurnStatus.PENDING, after.status)
        assertFalse(after.files.single { it.path == "b.md" }.done)
    }
}
