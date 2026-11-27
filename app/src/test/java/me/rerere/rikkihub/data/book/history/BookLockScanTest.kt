package me.rerere.rikkihub.data.book.history

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

/** 命令行、CLI 改了锁定内容：扫描之后放回（只在这一轮对话里）、提醒、全量核对。 */
class BookLockScanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = WorkspaceFileSystem()
    private var now = 1_000_000L
    private var free = Long.MAX_VALUE
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    private val history = BookHistory(scope, freeSpace = { free }, clock = { now })
    private lateinit var files: File

    private val turn = HistorySource.Ai("c", "m1", "t1")
    private val lt = "画里的女人眉眼低垂，像是在看他，又像是在看很远的地方。"
    private val tight = listOf("林默推开门，屋里一片漆黑。", "“谁？”", "“是我。”", lt, "“嗯。”", "他把灯放下，转身离开。").joinToString("\n")

    @Before
    fun setUp() {
        files = File(tmp.newFolder("book"), "files").apply { mkdirs() }.canonicalFile
        history.setBooks(mapOf("w" to files))
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

    private fun seed(path: String, text: String) = File(files, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun read(path: String) = File(files, path).readText()

    /** 这一轮对话里跑一条命令（直接改文件，钩子看不到），返回放回的说明。 */
    private fun command(change: () -> Unit): List<LockReport> = runBlocking {
        var reports = emptyList<LockReport>()
        withContext(BookHistory.sourceElement(turn)) { history.watching("w", { reports = it }) { now += 1000; change() } }
        reports
    }

    private fun notices() = runBlocking { history.notices("w") }

    @Test
    fun `a command changing a locked chapter is put back and the changed text is kept in history`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        val reports = command { File(files, "第1章.md").writeText("命令行改的") }
        assertEquals(tight, read("第1章.md"))
        assertTrue(reports.single().restored)
        val notice = notices().single()
        assertEquals(LockNotice.Kind.RESTORED, notice.kind)
        assertEquals("m1", notice.messageId)
        assertEquals("命令行改的", runBlocking { history.read("w", notice.changedSha!!) })
    }

    @Test
    fun `with no space to keep the changed text nothing is put back`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        free = 0
        val reports = command { File(files, "第1章.md").writeText("命令行改的") }
        assertEquals("命令行改的", read("第1章.md"))
        assertTrue(reports.single().noSpace)
        assertEquals(LockNotice.Kind.NO_SPACE, notices().single().kind)
    }

    @Test
    fun `a command deleting a locked chapter brings it back from the trash, still locked`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        command { File(files, "第1章.md").delete() }
        assertEquals(tight, read("第1章.md"))
        assertNotNull(runBlocking { history.locksOf("w", "第1章.md") }?.chapter)
    }

    @Test
    fun `a whole-chapter polish by a command puts back only the locked passage`() {
        seed("第1章.md", tight)
        val doc = BookLocks.Doc(tight)
        val row = doc.keys.indexOf(lt)
        history.lockPassage("w", "第1章.md", File(files, "第1章.md").readBytes(), row..row, web = false)
        val polished = listOf("林默推开房门，屋内漆黑一片。", "“谁在那儿？”", "“是我呀。”", "画中女子眉眼低垂，似在看他，又似望向远方。", "“嗯。”", "他放下灯，转身走了。").joinToString("\n")
        val reports = command { File(files, "第1章.md").writeText(polished) }
        assertEquals(polished.replace("画中女子眉眼低垂，似在看他，又似望向远方。", lt), read("第1章.md"))
        assertEquals(listOf(lt.take(20)), reports.single().passages)
    }

    @Test
    fun `a change found outside a turn is only reported, once per state`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        File(files, "第1章.md").writeText("终端里改的")
        runBlocking { history.scan("w") }
        assertEquals("终端里改的", read("第1章.md"))
        val notice = notices().single()
        assertEquals(LockNotice.Kind.CHANGED, notice.kind)
        // 看过、点了“知道了”：同一状态下次核对不再提醒
        runBlocking { history.dismissNotice("w", notice.id, notice.changedSha) }
        runBlocking { history.scanAll() }
        assertTrue(notices().isEmpty())
    }

    @Test
    fun `the full check restores a missing base when the file did not change`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        val sha = runBlocking { history.locksOf("w", "第1章.md") }!!.chapter!!.sha
        File(files.parentFile, "history/objects/$sha.gz").delete()
        runBlocking { history.scanAll() }
        assertEquals(tight, runBlocking { history.read("w", sha) })
        assertFalse(notices().any { it.kind == LockNotice.Kind.BASE_MISSING })
    }

    // ---- 提醒的处理与撤销 ----------------------------------------------------------------------

    @Test
    fun `put back from a notice restores the locked chapter and closes the notice`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        File(files, "第1章.md").writeText("终端里改的")
        runBlocking { history.scan("w") }
        val notice = notices().single()
        assertTrue(runBlocking { history.putBack("w", notice.id) })
        assertEquals(tight, read("第1章.md"))
        assertTrue(notices().isEmpty())
    }

    @Test
    fun `put back from a notice restores only the changed passage`() {
        seed("第1章.md", tight)
        val row = BookLocks.Doc(tight).keys.indexOf(lt)
        history.lockPassage("w", "第1章.md", File(files, "第1章.md").readBytes(), row..row, web = false)
        File(files, "第1章.md").writeText(tight.replace("眉眼", "眉目").replace("林默推开门", "林默推开房门"))
        runBlocking { history.scan("w") }
        assertTrue(runBlocking { history.putBack("w", notices().single().id) })
        assertEquals(tight.replace("林默推开门", "林默推开房门"), read("第1章.md"))
    }

    @Test
    fun `lock as it is now moves the passage lock onto the edited text and can be undone`() {
        seed("第1章.md", tight)
        val row = BookLocks.Doc(tight).keys.indexOf(lt)
        history.lockPassage("w", "第1章.md", File(files, "第1章.md").readBytes(), row..row, web = false)
        File(files, "第1章.md").writeText(tight.replace("眉眼", "眉目"))
        runBlocking { history.scan("w") }
        val (path, previous) = runBlocking { history.relock("w", notices().single().id) }!!
        assertEquals(listOf(lt.replace("眉眼", "眉目")), runBlocking { history.locksOf("w", path) }!!.passages.single().lines)
        assertTrue(notices().isEmpty())
        // 撤销：原来的锁对不上现在的稿子，不恢复一把已经失效的锁
        assertFalse(runBlocking { history.restoreLocks("w", path, previous) })
    }

    @Test
    fun `keep this change unlocks and goes back to the changed text`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        command { File(files, "第1章.md").writeText("命令行改的") }
        assertEquals(tight, read("第1章.md"))
        val (path, previous) = runBlocking { history.keepChange("w", notices().single().id) }!!
        assertEquals("命令行改的", read("第1章.md"))
        assertNull(runBlocking { history.locksOf("w", path) }?.chapter)
        // 撤销解锁：稿子已经不是加锁时的样子，不恢复
        assertFalse(runBlocking { history.restoreLocks("w", path, previous) })
    }

    @Test
    fun `keep this change after later edits swaps only the locked passage`() {
        seed("第1章.md", tight)
        val row = BookLocks.Doc(tight).keys.indexOf(lt)
        history.lockPassage("w", "第1章.md", File(files, "第1章.md").readBytes(), row..row, web = false)
        command { File(files, "第1章.md").writeText(tight.replace("眉眼低垂", "眉目低垂").replace("“嗯。”", "“嗯，好。”")) }
        // 放回了锁定段，锁外的改动留着；之后你又改了开头
        assertEquals(tight.replace("“嗯。”", "“嗯，好。”"), read("第1章.md"))
        val later = tight.replace("“嗯。”", "“嗯，好。”").replace("林默推开门", "林默轻轻推开门")
        // 过了一会儿你在编辑器里手写保存（来源 Manual）
        now += 60_000
        runBlocking { withContext(BookHistory.sourceElement(HistorySource.Manual)) { fs.writeTextWithRevision(files, "第1章.md", later, fs.readTextSnapshot(files, "第1章.md").revision) } }
        assertNotNull(runBlocking { history.keepChange("w", notices().single().id) })
        assertEquals(later.replace("眉眼低垂", "眉目低垂"), read("第1章.md"))
        assertTrue(runBlocking { history.locksOf("w", "第1章.md") }?.passages.isNullOrEmpty())
    }

    @Test
    fun `keep this change refuses instead of overwriting later edits when the passage can't be matched`() {
        seed("第1章.md", tight)
        val row = BookLocks.Doc(tight).keys.indexOf(lt)
        history.lockPassage("w", "第1章.md", File(files, "第1章.md").readBytes(), row..row, web = false)
        // 命令把锁定段整个删了（跟不过去），系统放回了它
        command { File(files, "第1章.md").writeText(tight.replace("$lt\n", "")) }
        assertEquals(tight, read("第1章.md"))
        val later = tight.replace("林默推开门", "林默轻轻推开门")
        // 过了一会儿你在编辑器里手写保存（来源 Manual）
        now += 60_000
        runBlocking { withContext(BookHistory.sourceElement(HistorySource.Manual)) { fs.writeTextWithRevision(files, "第1章.md", later, fs.readTextSnapshot(files, "第1章.md").revision) } }
        assertNull(runBlocking { history.keepChange("w", notices().single().id) })
        assertEquals(later, read("第1章.md"))
        assertEquals(1, runBlocking { history.locksOf("w", "第1章.md") }!!.passages.size)
    }

    @Test
    fun `undoing an unlock restores the same lock while the text still matches`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        val previous = runBlocking { history.unlock("w", "第1章.md", chapter = true, passageIds = emptySet()) }
        assertNull(runBlocking { history.locksOf("w", "第1章.md") })
        assertTrue(runBlocking { history.restoreLocks("w", "第1章.md", previous) })
        assertEquals(previous?.chapter, runBlocking { history.locksOf("w", "第1章.md") }?.chapter)
    }

    @Test
    fun `a chapter deleted from another app can be put back from its notice`() {
        seed("第1章.md", tight)
        history.lockChapter("w", "第1章.md", File(files, "第1章.md").readBytes(), web = false)
        BookHistory.withLockContext(BookLockContext(foreign = true)) { fs.delete(files, "第1章.md") }
        val notice = notices().single { it.kind == LockNotice.Kind.DELETED }
        assertTrue(runBlocking { history.putBack("w", notice.id) })
        assertEquals(tight, read("第1章.md"))
        assertNotNull(runBlocking { history.locksOf("w", "第1章.md") }?.chapter)
    }
}
