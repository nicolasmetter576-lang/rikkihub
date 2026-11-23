package me.rerere.rikkihub.data.book

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.book.transfer.BookImport
import me.rerere.rikkihub.data.book.transfer.BookImporter
import me.rerere.rikkihub.data.book.transfer.ExportFormat
import me.rerere.rikkihub.data.book.transfer.ExportOptions
import me.rerere.rikkihub.data.book.transfer.ImportChapter
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.workspace.LocalWorkspaceBackend
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
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

    @Before
    fun setUp() {
        base = Files.createTempDirectory("book-store-test").toFile()
        root = File(base, "w/files").apply { mkdirs() }
        val row = WorkspaceEntity(
            id = "w", name = "长夜", root = "w", shellStatus = WorkspaceShellStatus.DISABLED.name,
            createdAt = 1, updatedAt = 1, bookMode = true,
        )
        // 只用到 getById
        val dao = Proxy.newProxyInstance(WorkspaceDAO::class.java.classLoader, arrayOf(WorkspaceDAO::class.java)) { _, method, args ->
            if (method.name == "getById") row.takeIf { args[0] == "w" } else error("unexpected ${method.name}")
        } as WorkspaceDAO
        val unsafe = Class.forName("sun.misc.Unsafe").let { type -> type to type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) }
        val settings = unsafe.first.getMethod("allocateInstance", Class::class.java).invoke(unsafe.second, SettingsStore::class.java) as SettingsStore
        val manager = WorkspaceManager(base)
        repository = WorkspaceRepository(
            dao = dao,
            manager = LocalWorkspaceBackend(manager, RootfsInstaller(manager), File("/nonexistent-native"), null),
            settingsStore = settings,
            runtimeGate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L),
        )
        store = newStore()
    }

    private fun newStore() = BookStore(repository, File(base, "cache"))

    @After
    fun tearDown() {
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

    // ---- 查找替换 ----------------------------------------------------------------------------

    private fun replacer() = BookReplacer(store, File(base, "undo"))

    private fun query(vararg paths: String) = BookReplaceQuery(listOf(ReplacePair("阿青", "阿白")), false, paths.toList())

    private fun targets(preview: BookReplacePreview) = preview.files.map { BookReplaceTarget(it.path, it.revision, null) }

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
}
