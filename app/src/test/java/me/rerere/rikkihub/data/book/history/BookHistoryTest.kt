package me.rerere.rikkihub.data.book.history

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceBatchDeleter
import me.rerere.workspace.WorkspaceChanges
import me.rerere.workspace.WorkspaceFileSystem
import me.rerere.workspace.WorkspaceFileTransfer
import me.rerere.workspace.WorkspaceTransferMode
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookHistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = WorkspaceFileSystem()
    private var now = 1_000_000L
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    private val history = BookHistory(scope) { now }
    private lateinit var files: File
    private lateinit var other: File

    private val turn1 = HistorySource.Ai("c", "m1", "t1")
    private val turn1Again = HistorySource.Ai("c", "m1", "t2")
    private val turn2 = HistorySource.Ai("c", "m2", "t3")

    @Before
    fun setUp() {
        files = File(tmp.newFolder("book"), "files").apply { mkdirs() }.canonicalFile
        other = File(tmp.newFolder("plain"), "files").apply { mkdirs() }.canonicalFile
        history.setBooks(mapOf("w" to files))
        WorkspaceChanges.observer = history::onChange
    }

    @After
    fun tearDown() {
        WorkspaceChanges.observer = null
    }

    /** 新开一个 store 读：只认盘上的，顺带验证都落盘了。 */
    private fun disk() = BookHistoryStore(files, { now })

    private fun entries(path: String) = disk().entries(path)

    private fun text(sha: String) = disk().read(sha)?.decodeToString()

    private fun versions(path: String) = entries(path).filterIsInstance<HistoryEntry.Version>().map { text(it.sha) to it.source }

    private fun seed(path: String, text: String) = File(files, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun <T> as_(source: HistorySource, block: suspend () -> T): T = runBlocking { withContext(BookHistory.sourceElement(source)) { block() } }

    private fun save(path: String, text: String) {
        val revision = fs.readTextSnapshot(files, path).revision
        fs.writeTextWithRevision(files, path, text, revision)
    }

    @Test
    fun `an AI write keeps what was there before, once per turn`() {
        seed("第1章.md", "v0")
        as_(turn1) { fs.writeText(files, "第1章.md", "v1") }
        as_(turn1Again) { fs.writeText(files, "第1章.md", "v2") }
        assertEquals(listOf("v0" to HistorySource.Initial), versions("第1章.md"))
        // 下一轮动手前，上一轮写成的样子留下来，记在上一轮名下
        as_(turn2) { fs.writeText(files, "第1章.md", "v3") }
        assertEquals(listOf("v0" to HistorySource.Initial, "v2" to turn1Again), versions("第1章.md"))
    }

    @Test
    fun `hand edits within ten minutes merge, and their end is kept before the AI writes`() {
        seed("a.md", "v0")
        save("a.md", "h1")
        now += 60_000
        save("a.md", "h2")
        now += 60_000
        save("a.md", "h3")
        assertEquals(listOf("v0" to HistorySource.Initial), versions("a.md"))
        now += 10 * 60_000
        save("a.md", "h4")
        assertEquals(listOf("v0" to HistorySource.Initial, "h3" to HistorySource.Manual), versions("a.md"))
        as_(turn1) { fs.writeText(files, "a.md", "ai") }
        assertEquals(listOf("v0", "h3", "h4"), versions("a.md").map { it.first })
    }

    @Test
    fun `a change made outside the app is kept as external`() {
        seed("a.md", "v0")
        as_(turn1) { fs.writeText(files, "a.md", "v1") }
        File(files, "a.md").writeText("shell")
        as_(turn2) { fs.writeText(files, "a.md", "v2") }
        assertEquals(listOf("v0" to HistorySource.Initial, "shell" to HistorySource.External), versions("a.md"))
    }

    @Test
    fun `the same content is stored once`() {
        seed("a.md", "same")
        seed("b.md", "same")
        as_(turn1) {
            fs.writeText(files, "a.md", "x")
            fs.writeText(files, "b.md", "y")
        }
        // 两个文件写前的“same”只存一份；AI 写下的 x、y 写成功时就各存一份（逐处审阅的“改后”）
        val objects = File(files.parentFile, "history/objects").listFiles()!!.map { it.name.removeSuffix(".gz") }
        assertEquals(setOf("same", "x", "y"), objects.map { text(it) }.toSet())
        assertEquals(3, objects.size)
    }

    @Test
    fun `only md and txt outside hidden folders are kept, plus parallel drafts`() {
        seed("a.json", "{}")
        seed(".book/book.json", "{}")
        seed(".git/x.md", "x")
        seed("node_modules/y.md", "y")
        seed(".book/branches/b1/第1章.md", "draft")
        seed("notes.TXT", "t")
        as_(turn1) {
            for (path in listOf("a.json", ".book/book.json", ".git/x.md", "node_modules/y.md", ".book/branches/b1/第1章.md", "notes.TXT")) {
                fs.writeText(files, path, "changed")
            }
        }
        assertEquals(listOf("draft"), versions(".book/branches/b1/第1章.md").map { it.first })
        assertEquals(listOf("t"), versions("notes.TXT").map { it.first })
        for (path in listOf("a.json", ".book/book.json", ".git/x.md", "node_modules/y.md")) assertTrue(entries(path).isEmpty())
    }

    @Test
    fun `a workspace that is not a book gets no history`() {
        File(other, "a.md").writeText("v0")
        as_(turn1) { fs.writeText(other, "a.md", "v1") }
        assertFalse(File(other.parentFile, "history").exists())
        // 关掉作品模式也不再记
        history.setBooks(emptyMap())
        seed("b.md", "v0")
        as_(turn1) { fs.writeText(files, "b.md", "v1") }
        assertTrue(entries("b.md").isEmpty())
    }

    @Test
    fun `deleted files go to the trash, other files are deleted as before`() {
        seed("第1章.md", "one")
        seed("卷一/第2章.md", "two")
        seed("卷一/封面.png", "png")
        seed("卷一/.book-ish/z.md", "hidden")
        fs.delete(files, "第1章.md")
        runBlocking { WorkspaceBatchDeleter(fs).deleteBatch(files, listOf("卷一")) }
        assertFalse(File(files, "第1章.md").exists() || File(files, "卷一").exists())
        val deleted = entries("第1章.md")
        assertTrue(deleted.isEmpty()) // 路径已不再指向这个文件
        val trash = File(files.parentFile, "history/trash")
        val kept = trash.walkTopDown().filter { it.isFile }.map { it.readText() }.toSet()
        assertEquals(setOf("one", "two"), kept)
        val lines = File(files.parentFile, "history/files").listFiles()!!.flatMap { it.readLines() }
        assertTrue(lines.all { "\"deleted\"" in it } && lines.size == 2)
    }

    @Test
    fun `renames keep the history and a moved-over target goes to the trash`() {
        seed("a.md", "v0")
        seed("b.md", "victim")
        as_(turn1) { fs.writeText(files, "a.md", "v1") }
        fs.move(files, "a.md", "b.md", overwrite = true)
        assertEquals(listOf("v0"), versions("b.md").map { it.first })
        assertTrue(entries("b.md").last() is HistoryEntry.Renamed)
        assertEquals(setOf("victim"), File(files.parentFile, "history/trash").walkTopDown().filter { it.isFile }.map { it.readText() }.toSet())
        // 改名后再改，接着记在同一个文件下；改名前写的 v1 记在 AI 名下
        as_(turn2) { fs.writeText(files, "b.md", "v2") }
        assertEquals(listOf("v0" to HistorySource.Initial, "v1" to turn1), versions("b.md"))
    }

    @Test
    fun `a folder move carries every chapter, moving out of the book drops it`() {
        seed("卷一/第1章.md", "one")
        as_(turn1) { fs.writeText(files, "卷一/第1章.md", "one!") }
        runBlocking {
            val transfer = WorkspaceFileTransfer(fs)
            fs.mkdir(files, "正文")
            transfer.transfer(files, "卷一", files, "正文", WorkspaceTransferMode.MOVE)
            assertEquals(listOf("one"), versions("正文/卷一/第1章.md").map { it.first })
            transfer.transfer(files, "正文/卷一", other, "", WorkspaceTransferMode.MOVE)
        }
        assertTrue(entries("正文/卷一/第1章.md").isEmpty())
        assertFalse(File(other.parentFile, "history").exists())
    }

    @Test
    fun `a torn last line loses only itself`() {
        seed("a.md", "v0")
        as_(turn1) { fs.writeText(files, "a.md", "v1") }
        val list = File(files.parentFile, "history/files").listFiles()!!.single()
        list.appendText("{\"type\":\"version\",\"at\":") // 写到一半被杀
        history.setBooks(emptyMap())
        val fresh = BookHistory(scope) { now }.apply { setBooks(mapOf("w" to files)) }
        WorkspaceChanges.observer = fresh::onChange
        as_(turn2) { fs.writeText(files, "a.md", "v2") }
        assertEquals(listOf("v0", "v1"), versions("a.md").map { it.first })
    }

    @Test
    fun `a lost index is rebuilt from the lists`() {
        seed("a.md", "v0")
        as_(turn1) { fs.writeText(files, "a.md", "v1") }
        fs.move(files, "a.md", "b.md")
        File(files.parentFile, "history/index.json").delete()
        assertEquals(listOf("v0"), versions("b.md").map { it.first })
    }

    @Test
    fun `a damaged version reads as missing`() {
        seed("a.md", "v0")
        as_(turn1) { fs.writeText(files, "a.md", "v1") }
        val version = entries("a.md").single() as HistoryEntry.Version
        File(files.parentFile, "history/objects/${version.sha}.gz").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(disk().read(version.sha))
    }

    @Test
    fun `history failures never block the write`() {
        seed("a.md", "v0")
        File(files.parentFile, "history").writeText("not a directory")
        as_(turn1) { fs.writeText(files, "a.md", "v1") }
        assertEquals("v1", File(files, "a.md").readText())
        seed("b.md", "b")
        assertTrue(fs.delete(files, "b.md"))
        assertFalse(File(files, "b.md").exists())
    }

    @Test
    fun `stored versions are byte exact`() {
        val bytes = "第一行\r\n第二行\n 尾".toByteArray()
        File(files, "a.md").writeBytes(bytes)
        as_(turn1) { fs.writeText(files, "a.md", "x") }
        val version = entries("a.md").single() as HistoryEntry.Version
        assertArrayEquals(bytes, disk().read(version.sha))
    }
}
