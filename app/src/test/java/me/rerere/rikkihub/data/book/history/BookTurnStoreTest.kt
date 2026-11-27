package me.rerere.rikkihub.data.book.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceChanges
import me.rerere.workspace.WorkspaceFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 逐处审阅的记录：各写入路径产生的记录、收尾的“改后”取法、命令重叠、保护名单、归档。 */
class BookTurnStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = WorkspaceFileSystem()
    private var now = 1_000_000_000L
    private var mono = 5_000L
    private var free = Long.MAX_VALUE
    private val scope = CoroutineScope(SupervisorJob())
    private var generating = false
    private val history = BookHistory(scope, freeSpace = { free }, monotonic = { mono }, clock = { now })
    private lateinit var files: File

    private val turn = HistorySource.Ai("c", "m1", "t1")
    private val other = HistorySource.Ai("c2", "m9", "t9")

    @Before
    fun setUp() {
        files = File(tmp.newFolder("book"), "files").apply { mkdirs() }.canonicalFile
        history.setBooks(mapOf("w" to files))
        history.isGenerating = { _, _ -> generating }
        WorkspaceChanges.observer = history::onChange
        history.writeBack = { _, path, compute ->
            val snapshot = fs.readTextSnapshot(files, path)
            val next = compute(snapshot.content)
            if (next == null) false else {
                withContext(BookHistory.sourceElement(HistorySource.Restore) + BookHistory.lockElement(BookLockContext(restore = true))) {
                    fs.writeTextWithRevision(files, path, next, snapshot.revision)
                }
                true
            }
        }
        runBlocking { history.scanAll() }
    }

    @After
    fun tearDown() {
        WorkspaceChanges.observer = null
    }

    private fun seed(path: String, text: String) = File(files, path).apply { parentFile.mkdirs(); writeText(text); setLastModified(now) }

    private fun <T> as_(source: HistorySource, block: suspend () -> T): T = runBlocking { withContext(BookHistory.sourceElement(source)) { block() } }

    private fun save(path: String, text: String) = fs.writeTextWithRevision(files, path, text, fs.readTextSnapshot(files, path).revision)

    /** 收尾并等它做完（只等收尾投递的任务，不等开着的终端）。 */
    private fun finish(source: HistorySource.Ai = turn) {
        val running = scope.coroutineContext.job.children.toSet()
        history.finishTurn(source.conversationId, source.messageId)
        runBlocking { (scope.coroutineContext.job.children.toSet() - running).joinAll() }
    }

    private fun record(source: HistorySource.Ai = turn) = runBlocking { history.turn("w", source.conversationId, source.messageId) }

    private fun text(sha: String?) = sha?.let { runBlocking { history.read("w", it) } }

    private fun file(path: String, source: HistorySource.Ai = turn) = record(source)!!.files.values.single { it.path == path }

    private fun command(source: HistorySource, change: () -> Unit): List<LockReport> = runBlocking {
        var reports = emptyList<LockReport>()
        withContext(BookHistory.sourceElement(source)) { history.watching("w", { reports = it }) { now += 1000; change() } }
        reports
    }

    @Test
    fun `a tool overwrite and a new file are recorded with before and after`() {
        seed("第1章.md", "原文")
        runBlocking { history.scanAll() }
        as_(turn) {
            fs.writeText(files, "第1章.md", "第一稿")
            fs.writeText(files, "第1章.md", "第二稿")
            fs.createFile(files, "第2章.md", "新的一章")
        }
        assertNull("收尾前界面看不到", record())
        finish()
        val changed = file("第1章.md")
        assertEquals("原文", text(changed.before))
        assertEquals("第二稿", text(changed.after))
        val created = file("第2章.md")
        assertTrue(created.created)
        assertNull(created.before)
        assertEquals("新的一章", text(created.after))
        assertEquals(TurnStatus.PENDING, record()!!.status)
    }

    @Test
    fun `a subagent writes into the parent message's turn`() {
        seed("a.md", "原文")
        as_(turn) { fs.writeText(files, "a.md", "主代理") }
        as_(turn.copy(toolCallId = "sub")) { fs.writeText(files, "a.md", "子代理") }
        finish()
        assertEquals("子代理", text(file("a.md").after))
        assertEquals("原文", text(file("a.md").before))
    }

    @Test
    fun `a command's change, new file, deletion and rename are recorded`() {
        seed("改.md", "改前")
        seed("删.md", "要删的")
        seed("旧名.md", "改名的")
        runBlocking { history.scanAll() }
        command(turn) {
            File(files, "改.md").writeText("改后")
            File(files, "新.md").writeText("新文件")
            File(files, "删.md").delete()
            File(files, "旧名.md").renameTo(File(files, "新名.md"))
        }
        finish()
        assertEquals("改后", text(file("改.md").after))
        assertTrue(file("新.md").created)
        assertNotNull(file("删.md").trashed)
        assertEquals("旧名.md", file("新名.md").renamedFrom)
    }

    @Test
    fun `a write refused by a chapter lock leaves no record`() {
        seed("a.md", "定稿")
        history.lockChapter("w", "a.md", File(files, "a.md").readBytes(), web = false)
        runCatching { as_(turn) { fs.writeText(files, "a.md", "改") } }
        finish()
        assertNull(record())
    }

    @Test
    fun `when a command's change to locked text is put back, the turn's after is what was put back`() {
        val text = "第一段，林默推开门，屋里一片漆黑。\n第二段，画里的女人眉眼低垂，像是在看他。\n第三段，他把灯放下，转身离开。"
        seed("a.md", text)
        runBlocking { history.scanAll() }
        history.lockPassage("w", "a.md", File(files, "a.md").readBytes(), 1..1, web = false)
        command(turn) { File(files, "a.md").writeText(text.replace("第二段", "第2段").replace("第三段，他", "第三段，林默")) }
        finish()
        val restored = File(files, "a.md").readText()
        assertTrue(restored.contains("第二段"))
        assertEquals(restored, text(file("a.md").after))
    }

    @Test
    fun `your edit after the AI's write does not change the turn's after, and marks it mixed`() {
        seed("a.md", "原文")
        as_(turn) { fs.writeText(files, "a.md", "AI 写的") }
        now += 1000
        save("a.md", "你又改了")
        finish()
        assertEquals("AI 写的", text(file("a.md").after))
        assertTrue(file("a.md").mixed)
    }

    @Test
    fun `writing again into the same message reopens the record and keeps its before`() {
        seed("a.md", "原文")
        as_(turn) { fs.writeText(files, "a.md", "第一次") }
        finish()
        runBlocking { history.updateTurn("w", "c", "m1") { r -> r.copy(files = r.files.mapValues { it.value.copy(done = true) }, reviewedAt = now) } }
        assertEquals(TurnStatus.REVIEWED, record()!!.status)
        now += 1000
        save("a.md", "你改的")
        now += 1000
        as_(turn) { fs.writeText(files, "a.md", "第二次") }
        assertNull("重开后收尾前看不到", record())
        finish()
        assertEquals(TurnStatus.PENDING, record()!!.status)
        assertEquals("原文", text(file("a.md").before))
        assertEquals("第二次", text(file("a.md").after))
    }

    @Test
    fun `a scan during a command only keeps copies and leaves the change to the command`() {
        seed("a.md", "改前")
        seed("删.md", "要删的")
        runBlocking { history.scanAll() }
        command(turn) {
            File(files, "a.md").writeText("命令改的")
            File(files, "新.md").writeText("命令新建的")
            File(files, "删.md").delete()
            // 命令进行中回到前台、打开终端前的扫描
            runBlocking { history.scan("w") }
        }
        finish()
        assertEquals("命令改的", text(file("a.md").after))
        assertTrue(file("新.md").created)
        assertNotNull(file("删.md").trashed)
        assertEquals(turn.copy(toolCallId = null), runBlocking { history.currentWriter("w", "a.md") })
    }

    @Test
    fun `two turns' commands at the same time attribute to neither`() {
        seed("a.md", "改前")
        seed("b.md", "改前")
        runBlocking { history.scanAll() }
        runBlocking {
            withContext(BookHistory.sourceElement(turn)) {
                history.watching("w") {
                    // 另一个对话的命令在中间跑完
                    withContext(BookHistory.sourceElement(other)) { history.watching("w") { File(files, "b.md").writeText("另一个对话改的") } }
                    File(files, "a.md").writeText("这一轮改的")
                }
            }
        }
        finish()
        finish(other)
        assertEquals(Unattributed.OVERLAP, record()!!.commandsUnattributed)
        assertTrue(record()!!.files.isEmpty())
        assertEquals(HistorySource.External, runBlocking { history.currentWriter("w", "a.md") })
        assertEquals(HistorySource.External, runBlocking { history.currentWriter("w", "b.md") })
    }

    @Test
    fun `a terminal open before or during a command makes its changes unattributed`() {
        seed("a.md", "改前")
        runBlocking { history.scanAll() }
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        history.scanAfter("w") { closed.await() }
        command(turn) { File(files, "a.md").writeText("改了") }
        finish()
        assertEquals(Unattributed.TERMINAL, record()!!.commandsUnattributed)
        closed.complete(Unit)
        runBlocking { scope.coroutineContext.job.children.toList().joinAll() }
        assertFalse(history.commandRunning("w"))
        // 终端关了以后照常归属
        command(turn.copy(messageId = "m2")) { File(files, "a.md").writeText("又改了") }
        finish(turn.copy(messageId = "m2"))
        assertEquals("又改了", text(file("a.md", turn.copy(messageId = "m2")).after))
        assertNull(record(turn.copy(messageId = "m2"))!!.commandsUnattributed)
    }

    @Test
    fun `two commands of the same turn interleaving still attribute to it`() {
        seed("a.md", "改前")
        seed("b.md", "改前")
        runBlocking { history.scanAll() }
        runBlocking {
            withContext(BookHistory.sourceElement(turn)) {
                history.watching("w") {
                    withContext(BookHistory.sourceElement(turn.copy(toolCallId = "sub"))) { history.watching("w") { File(files, "b.md").writeText("子代理改的") } }
                    File(files, "a.md").writeText("主代理改的")
                }
            }
        }
        finish()
        assertNull(record()!!.commandsUnattributed)
        assertEquals("主代理改的", text(file("a.md").after))
        assertEquals("子代理改的", text(file("b.md").after))
    }

    @Test
    fun `a command cancelled before it starts does not leave the book marked busy`() {
        seed("a.md", "改前")
        val job = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            withContext(BookHistory.sourceElement(turn)) { history.watching("w") { kotlinx.coroutines.awaitCancellation() } }
        }
        runBlocking { kotlinx.coroutines.delay(100); job.cancel(); job.join() }
        assertFalse(history.commandRunning("w"))
    }

    @Test
    fun `the AI's written version survives a terminal changing the file afterwards`() {
        seed("a.md", "原文")
        as_(turn) { fs.writeText(files, "a.md", "AI 写的") }
        File(files, "a.md").writeText("终端改的")
        runBlocking { history.scanAll() }
        finish()
        assertEquals("AI 写的", text(file("a.md").after))
        assertTrue(file("a.md").mixed)
    }

    @Test
    fun `your edit in the app while a command runs keeps the command's version in its turn`() {
        seed("a.md", "原文")
        runBlocking { history.scanAll() }
        command(turn) {
            File(files, "a.md").writeText("命令写的")
            now += 1000
            runBlocking { withContext(BookHistory.sourceElement(HistorySource.Manual)) { save("a.md", "你在 App 里改的") } }
        }
        finish()
        assertEquals("命令写的", text(file("a.md").after))
        assertTrue(file("a.md").mixed)
        assertEquals("原文", text(file("a.md").before))
    }

    @Test
    fun `a turn still generating is not finished, and an unfinished turn is finished later`() {
        seed("a.md", "原文")
        as_(turn) { fs.writeText(files, "a.md", "AI 写的") }
        generating = true
        finish()
        assertNull(record())
        // 补收尾也跳过正在生成的
        runBlocking { history.verify("w") }
        assertNull(record())
        generating = false
        // 进程重启后（内存里不记得碰过哪些书）由补收尾接上
        val fresh = BookHistory(scope, freeSpace = { free }, monotonic = { mono }, clock = { now })
        fresh.setBooks(mapOf("w" to files))
        runBlocking { fresh.verify("w") }
        assertEquals("AI 写的", runBlocking { fresh.turn("w", "c", "m1") }?.files?.values?.single()?.after?.let { runBlocking { fresh.read("w", it) } })
    }

    @Test
    fun `a file created and deleted in the same turn is dropped, an unchanged file too`() {
        seed("a.md", "原文")
        runBlocking { history.scanAll() }
        command(turn) {
            File(files, "临时.md").writeText("临时")
            File(files, "a.md").writeText("改了")
        }
        command(turn) {
            File(files, "临时.md").delete()
            // 扫描按大小 + 修改时间跳过没变的文件；“原文”与“改了”一样长，同一毫秒内写会被当成没变
            File(files, "a.md").apply { val before = lastModified(); writeText("原文"); setLastModified(before + 2000) }
        }
        finish()
        assertNull(record())
    }

    @Test
    fun `with little space the versions are still kept down to 64 MB and history stays paused`() {
        seed("a.md", "原文")
        runBlocking { history.scanAll() }
        free = 100L * 1024 * 1024
        save("a.md", "你改的")
        // 你改的那版在 AI 动手前要留底：普通留底因空间不足放弃（历史暂停），一轮的改前强制存下
        as_(turn) { fs.writeText(files, "a.md", "AI 写的") }
        finish()
        assertEquals("你改的", text(file("a.md").before))
        assertEquals("AI 写的", text(file("a.md").after))
        assertTrue(history.paused("w"))
        free = 32L * 1024 * 1024
        as_(turn.copy(messageId = "m2")) { fs.writeText(files, "a.md", "再写") }
        finish(turn.copy(messageId = "m2"))
        assertEquals(TurnMissing.AFTER, file("a.md", turn.copy(messageId = "m2")).missing)
    }

    @Test
    fun `versions a pending or reviewed turn needs survive cleanup, archived ones do not`() {
        seed("a.md", "原文")
        runBlocking { history.scanAll() }
        as_(turn) { fs.writeText(files, "a.md", "AI 写的") }
        finish()
        val before = file("a.md").before!!
        // 这一版在清单里很快就会被清掉（只留最近的），审阅记录要它
        repeat(30) { i ->
            now += 1000
            as_(HistorySource.Ai("c", "x$i")) { fs.writeText(files, "a.md", "第 $i 稿") }
        }
        now += 40L * 24 * 60 * 60 * 1000
        File(files.parentFile, "history/objects").listFiles()!!.forEach { it.setLastModified(now - 2 * 60 * 60 * 1000L) }
        val store = BookHistoryStore(files, { now })
        store.cleanup(Long.MAX_VALUE)
        assertNotNull(store.read(before))
        store.updateTurn(TurnKey("c", "m1")) { it.copy(archivedAt = now) }
        store.turns().filter { it.archivedAt == null }.forEach { r -> store.updateTurn(r.key) { it.copy(archivedAt = now) } }
        store.cleanup(Long.MAX_VALUE)
        assertNull(store.read(before))
    }

    @Test
    fun `deleting a version takes it out of the turn records too, and clearing history clears them`() {
        seed("a.md", "原文")
        runBlocking { history.scanAll() }
        as_(turn) { fs.writeText(files, "a.md", "AI 写的") }
        finish()
        val version = runBlocking { history.entries("w", "a.md") }.filterIsInstance<HistoryEntry.Version>().first { text(it.sha) == "原文" }
        runBlocking { history.deleteVersion("w", "a.md", version) }
        assertNull(file("a.md").before)
        assertEquals(TurnMissing.DELETED_VERSION, file("a.md").missing)
        runBlocking { history.clear("w") }
        assertNull(record())
    }

    @Test
    fun `records age by presence and time, and a clock jump does not expire them`() {
        seed("a.md", "原文")
        runBlocking { history.scanAll() }
        val presence = HashMap<String, MessagePresence>()
        history.presence = { _, ids -> ids.associateWith { presence[it] ?: MessagePresence.CURRENT } }
        for (id in listOf("gone", "branch", "hidden", "old")) {
            as_(HistorySource.Ai("c", id)) { fs.writeText(files, "a.md", "AI 写的 $id") }
            finish(HistorySource.Ai("c", id))
        }
        presence["gone"] = MessagePresence.GONE
        presence["branch"] = MessagePresence.OTHER_BRANCH
        presence["hidden"] = MessagePresence.HIDDEN
        fun cleanup() {
            mono += 25L * 60 * 60 * 1000
            runBlocking { history.scanAll() }
        }
        fun status(id: String) = record(HistorySource.Ai("c", id))?.status
        cleanup()
        assertEquals(TurnStatus.ARCHIVED, status("gone"))
        assertEquals(TurnStatus.PENDING, status("branch"))
        assertEquals(TurnStatus.PENDING, status("hidden"))
        // 墙钟往前跳一年（单调时钟只走了一天多）：这一次不按年龄处理
        now += 365L * 24 * 60 * 60 * 1000
        cleanup()
        assertEquals(TurnStatus.PENDING, status("old"))
        assertEquals(TurnStatus.ARCHIVED, status("gone"))
        // 真过了 8 天（两种时钟一起走）：待审没看过的归档，归档满 7 天的删掉
        now += 8L * 24 * 60 * 60 * 1000
        mono += 8L * 24 * 60 * 60 * 1000
        runBlocking { history.scanAll() }
        assertEquals(TurnStatus.ARCHIVED, status("old"))
        assertNull(status("gone"))
    }

    @Test
    fun `a damaged record is ignored and writing goes on`() {
        seed("a.md", "原文")
        val damaged = File(files.parentFile, "history/turns/c/m1.json").apply { parentFile.mkdirs(); writeText("{坏的") }
        assertTrue(damaged.isFile)
        as_(turn) { fs.writeText(files, "a.md", "AI 写的") }
        assertEquals("AI 写的", File(files, "a.md").readText())
        finish()
        assertEquals("AI 写的", text(file("a.md").after))
    }
}
