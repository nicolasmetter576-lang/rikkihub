package me.rerere.rikkihub.data.book.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceChanges
import me.rerere.workspace.WorkspaceFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPInputStream

/** 扫描（命令行、终端、外部 App）、主动留底、回收站、保留清理。 */
class BookHistoryScanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = WorkspaceFileSystem()
    private var now = System.currentTimeMillis()
    private val scope = CoroutineScope(Job())
    private val history = BookHistory(scope) { now }
    private lateinit var files: File

    private val turn1 = HistorySource.Ai("c", "m1", "t1")
    private val turn2 = HistorySource.Ai("c", "m2", "t2")

    @Before
    fun setUp() {
        files = File(tmp.newFolder("book"), "files").apply { mkdirs() }.canonicalFile
        history.setBooks(mapOf("w" to files))
        WorkspaceChanges.observer = history::onChange
    }

    @After
    fun tearDown() {
        WorkspaceChanges.observer = null
    }

    private fun disk(freeSpace: (File) -> Long = { Long.MAX_VALUE }) = BookHistoryStore(files, { now }, freeSpace)

    private fun entries(path: String) = disk().entries(path)

    private fun versions(path: String) = disk().let { store ->
        store.entries(path).filterIsInstance<HistoryEntry.Version>().map { store.read(it.sha)?.decodeToString() to it.source }
    }

    private fun seed(path: String, text: String) = File(files, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun <T> run(source: HistorySource? = null, block: suspend () -> T): T = runBlocking {
        if (source == null) block() else withContext(BookHistory.sourceElement(source)) { block() }
    }

    /** 模拟命令行：绕过 App 直接改盘；改过的文件修改时间往后挪，免得与扫描时看到的同一毫秒。 */
    private fun shell(block: () -> Unit) {
        now += 1_000
        block()
        files.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(it.lastModified() + 2_000) }
    }

    private fun objects() = File(files.parentFile, "history/objects").listFiles().orEmpty().filter { it.name.endsWith(".gz") }

    @Test
    fun `what the AI wrote survives a shell command overwriting it`() {
        seed("第1章.md", "v0")
        run(turn1) { fs.writeText(files, "第1章.md", "ai") }
        run(turn2) { history.watching("w") { shell { File(files, "第1章.md").writeText("sed") } } }
        assertEquals(listOf("v0" to HistorySource.Initial, "ai" to turn1), versions("第1章.md"))
        // 命令行改的那版算在这一轮上（不认是哪次工具调用），下次被覆盖时留下
        fs.writeText(files, "第1章.md", "hand")
        assertEquals("sed" to turn2.copy(toolCallId = null), versions("第1章.md").last())
    }

    @Test
    fun `outside a conversation, scanned changes count as external`() {
        seed("a.md", "v0")
        run { history.scan("w") }
        shell { File(files, "a.md").writeText("v1") }
        run { history.watching("w") {} }
        fs.writeText(files, "a.md", "v2")
        assertEquals(listOf("v0" to HistorySource.Initial, "v1" to HistorySource.External), versions("a.md"))
    }

    @Test
    fun `the first scan stores every file, later scans only read what changed`() {
        seed("a.md", "A")
        seed("卷一/b.txt", "B")
        seed("图.png", "png")
        run { history.scan("w") }
        assertEquals(2, objects().size)
        val stamp = objects().associate { it.name to it.lastModified() }
        run { history.scan("w") }
        assertEquals(stamp, objects().associate { it.name to it.lastModified() })
    }

    @Test
    fun `a file renamed by a command keeps its history`() {
        seed("a.md", "v0")
        run(turn1) { fs.writeText(files, "a.md", "v1") }
        run { history.watching("w") { shell { File(files, "a.md").renameTo(File(files, "b.md")) } } }
        assertEquals(listOf("v0"), versions("b.md").map { it.first })
        assertTrue(entries("b.md").last() is HistoryEntry.Renamed)
        assertTrue(entries("a.md").isEmpty())
    }

    @Test
    fun `a file deleted by a command lands in the trash with its last content`() {
        seed("a.md", "v0")
        run(turn1) { fs.writeText(files, "a.md", "ai") }
        run { history.watching("w") { shell { File(files, "a.md").delete() } } }
        val trashed = run { history.trashed("w") }.single()
        assertEquals("a.md", trashed.path)
        assertEquals(HistorySource.External, trashed.source)
        assertEquals("ai", File(files.parentFile, "history/trash/${trashed.key}/${trashed.name}").readText())
    }

    @Test
    fun `a file created by a command is tracked from then on`() {
        run { history.scan("w") }
        run(turn1) { history.watching("w") { shell { seed("新章.md", "new") } } }
        fs.writeText(files, "新章.md", "edited")
        assertEquals(listOf("new" to turn1.copy(toolCallId = null)), versions("新章.md"))
    }

    @Test
    fun `scanning never breaks the command it wraps`() {
        seed("a.md", "v0")
        File(files.parentFile, "history").writeText("not a directory")
        assertEquals(42, run { history.watching("w") { 42 } })
    }

    @Test
    fun `a checkpoint keeps the unsaved editor text and stops the next save from merging`() {
        seed("a.md", "saved")
        fs.writeText(files, "a.md", "h1") // 手写一段开始
        run { history.checkpoint("w", "a.md", "h1 + 没保存的字") }
        fs.writeText(files, "a.md", "推敲后")
        assertEquals(listOf("saved", "h1", "h1 + 没保存的字"), versions("a.md").map { it.first })
        fs.writeText(files, "a.md", "推敲后再手改")
        assertEquals("推敲后", versions("a.md").last().first)
    }

    @Test
    fun `an external app writing through the file picker is kept on both sides`() {
        seed("a.md", "v0")
        run(turn1) { fs.writeText(files, "a.md", "ai") }
        val file = File(files, "a.md")
        history.beforeExternalWrite(file)
        file.writeText("") // "wt" 打开即清空
        shell { file.writeText("from another app") }
        history.afterExternalWrite(file)
        runBlocking { scope.coroutineContext.job.children.forEach { it.join() } }
        assertEquals(listOf("v0", "ai"), versions("a.md").map { it.first })
        fs.writeText(files, "a.md", "hand")
        assertEquals("from another app" to HistorySource.ExternalApp, versions("a.md").last())
    }

    @Test
    fun `restoring from the trash puts the file back under its old history`() {
        seed("a.md", "v0")
        run(turn1) { fs.writeText(files, "a.md", "v1") }
        fs.delete(files, "a.md")
        seed("a.md", "someone else")
        val trashed = run { history.trashed("w") }.single()
        val path = run { history.restoreTrashed("w", trashed.key, trashed.name) }
        assertEquals("a (1).md", path)
        assertEquals("v1", File(files, "a (1).md").readText())
        assertEquals(listOf("v0" to HistorySource.Initial, "v1" to HistorySource.Restore), versions("a (1).md"))
        assertTrue(run { history.trashed("w") }.isEmpty())
        assertNull(run { history.restoreTrashed("w", trashed.key, trashed.name) })
    }

    @Test
    fun `deleting from the trash and emptying it`() {
        seed("a.md", "A")
        seed("b.md", "B")
        fs.delete(files, "a.md")
        fs.delete(files, "b.md")
        val items = run { history.trashed("w") }
        run { history.deleteTrashed("w", items[0].key, items[0].name) }
        assertEquals(1, run { history.trashed("w") }.size)
        run { history.emptyTrash("w") }
        assertTrue(run { history.trashed("w") }.isEmpty())
    }

    @Test
    fun `deleting a version and clearing history keep the trash, unused texts go after an hour`() {
        seed("a.md", "v0")
        run(turn1) { fs.writeText(files, "a.md", "v1") }
        run(turn2) { fs.writeText(files, "a.md", "v2") }
        seed("b.md", "B")
        fs.delete(files, "b.md")
        val first = entries("a.md").first() as HistoryEntry.Version
        run { history.deleteVersion("w", "a.md", first) }
        assertEquals(listOf("v1"), versions("a.md").map { it.first })
        // 一小时内的对象可能还没记进清单，不删（v0、v1，以及 AI 写成功时就存下的 v2）
        assertEquals(3, objects().size)
        now += 2 * 60 * 60 * 1000L
        objects().forEach { it.setLastModified(now - 2 * 60 * 60 * 1000L) }
        run { history.clear("w") }
        assertTrue(versions("a.md").isEmpty())
        assertEquals(1, run { history.trashed("w") }.size)
        // 清空历史连审阅记录一起清；只剩文件现在的样子（索引还指着它）
        assertEquals(listOf("v2"), objects().map { GZIPInputStream(it.inputStream()).use { s -> s.readBytes().decodeToString() } })
    }

    @Test
    fun `thinning keeps the latest twenty, everything from today, hourly this week and daily before`() {
        val day = 24 * 60 * 60 * 1000L
        val hour = 60 * 60 * 1000L
        val today = now
        // 30 天前起每 10 分钟一版，到现在
        val versions = (0 until 30 * 24 * 6).map { i ->
            HistoryEntry.Version(today - 30 * day + i * 10 * 60 * 1000L, "a.md", HistorySource.Manual, "s$i")
        }
        val kept = BookHistoryStore.thin(versions, today)
        val recent = versions.filter { today - it.at < day }
        assertTrue(kept.containsAll(recent))
        assertTrue(kept.containsAll(versions.takeLast(20)))
        val week = kept.filter { today - it.at in day until 7 * day }
        assertTrue(week.size in 6 * 24 - 1..6 * 24 + 1)
        val older = kept.filter { today - it.at >= 7 * day }
        assertTrue(older.size in 22..24)
        assertEquals(kept.size, recent.size + week.size + older.size)
        assertTrue(hour > 0)
    }

    @Test
    fun `twenty versions survive a clock that jumped forward`() {
        val versions = (0 until 30).map { HistoryEntry.Version(it.toLong(), "a.md", HistorySource.Manual, "s$it") }
        val kept = BookHistoryStore.thin(versions, now + 400L * 24 * 60 * 60 * 1000)
        assertTrue(kept.containsAll(versions.takeLast(20)))
    }

    @Test
    fun `over the space limit the oldest versions go, each file keeps its latest`() {
        val store = disk()
        val big = { tag: String -> (0 until 50_000).joinToString("") { "$tag$it " } }
        seed("a.md", big("a0"))
        seed("b.md", big("b0"))
        for (i in 1..3) {
            now += 1_000
            run(HistorySource.Ai("c", "m$i")) {
                fs.writeText(files, "a.md", big("a$i"))
                fs.writeText(files, "b.md", big("b$i"))
            }
        }
        assertEquals(3, versions("a.md").size)
        now += 2 * 60 * 60 * 1000L
        objects().forEach { it.setLastModified(now - 2 * 60 * 60 * 1000L) }
        val one = objects().maxOf { it.length() }
        store.cleanup(limitBytes = one * 2)
        assertEquals(1, versions("a.md").size)
        assertEquals(1, versions("b.md").size)
        // 审阅记录保护的版本超过上限一半时记录提前归档、不再保护；剩下每个文件现在的样子（AI 写成功时存下）和最后一版
        assertEquals(4, objects().size)
        assertTrue(store.turns().all { it.archivedAt != null })
    }

    @Test
    fun `low free space pauses history without blocking the write`() {
        WorkspaceChanges.observer = null
        val store = disk(freeSpace = { 0L })
        seed("a.md", "v0")
        store.overwrite(File(files, "a.md"), { "v1".toByteArray() }, turn1)
        assertTrue(store.paused)
        assertTrue(objects().isEmpty())
        assertFalse(store.entries("a.md").any { it is HistoryEntry.Version })
    }
}
