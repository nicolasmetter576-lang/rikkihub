package me.rerere.rikkihub.data.book.history

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceBatchDeleter
import me.rerere.workspace.WorkspaceChange
import me.rerere.workspace.WorkspaceChanges
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceFileSystem
import me.rerere.workspace.WorkspaceFileTransfer
import me.rerere.workspace.WorkspaceTransferMode
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** 定稿锁接在写入钩子上：谁被拦、谁跟着走、删除与移动、永久版本、锁表损坏。 */
class BookLockHookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = WorkspaceFileSystem()
    private var now = 1_000_000L
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    private val history = BookHistory(scope) { now }
    private lateinit var files: File
    private lateinit var other: File

    private val ai = HistorySource.Ai("c", "m1", "t1")
    private val locked = "　　画里的女人眉眼低垂，像是在看他，又像是在看很远的地方。"
    private val chapter = "　　林默推开门，屋里一片漆黑。\n\n$locked\n\n　　他把灯放下，转身离开。"

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

    private fun seed(path: String, text: String) = File(files, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun read(path: String) = File(files, path).readText()

    private fun <T> under(context: CoroutineContext, block: suspend () -> T): T = runBlocking { withContext(context) { block() } }

    private fun as_(source: HistorySource?, lock: BookLockContext? = null, block: suspend () -> Unit) = under(
        (source?.let(BookHistory::sourceElement) ?: EmptyCoroutineContext) + (lock?.let(BookHistory::lockElement) ?: EmptyCoroutineContext),
        block,
    )

    private fun save(path: String, text: String) {
        val revision = fs.readTextSnapshot(files, path).revision
        fs.writeTextWithRevision(files, path, text, revision)
    }

    private fun lockChapter(path: String) = history.lockChapter("w", path, File(files, path).readBytes(), web = false)

    private fun lockPassage(path: String, line: String): PassageLock {
        val doc = BookLocks.Doc(read(path))
        val row = doc.keys.indexOf(BookLocks.key(line))
        return history.lockPassage("w", path, File(files, path).readBytes(), row..row, web = false).first
    }

    private fun locks(path: String) = runBlocking { history.locksOf("w", path) }

    private fun refusal(block: () -> Unit): BookLockedException? = runCatching(block).exceptionOrNull() as? BookLockedException

    // ---- 整章锁 ----

    @Test
    fun `a chapter lock refuses every write except the system putting it back`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        assertEquals(BookLockedException.Reason.CHAPTER, refusal { as_(ai) { fs.writeText(files, "第1章.md", "AI") } }?.reason)
        assertNotNull(refusal { as_(null) { fs.writeText(files, "第1章.md", "unlabeled") } })
        assertNotNull(refusal { as_(HistorySource.Manual) { save("第1章.md", "stale editor") } })
        assertEquals(chapter, read("第1章.md"))
        as_(HistorySource.Restore, BookLockContext(restore = true)) { save("第1章.md", "put back") }
        assertEquals("put back", read("第1章.md"))
    }

    @Test
    fun `the refusal keeps its code through the text API`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        val error = runCatching { as_(ai) { save("第1章.md", "x") } }.exceptionOrNull()
        assertEquals(WorkspaceFileOperationException.FILE_LOCKED, (error as WorkspaceFileOperationException).code)
    }

    // ---- 段落锁 ----

    @Test
    fun `the AI may change everything except the locked passage`() {
        seed("第1章.md", chapter)
        lockPassage("第1章.md", locked)
        as_(ai) { fs.writeText(files, "第1章.md", chapter.replace("屋里一片漆黑", "屋里黑得什么都看不见")) }
        val error = refusal { as_(ai) { fs.writeText(files, "第1章.md", read("第1章.md").replace("眉眼", "眉目")) } }
        assertEquals(BookLockedException.Reason.PASSAGES, error?.reason)
        assertEquals(BookLocks.IssueKind.CHANGED, error!!.issues.single().kind)
        assertTrue(read("第1章.md").contains(locked))
    }

    @Test
    fun `your own edit to a locked passage goes through and the lock follows it`() {
        seed("第1章.md", chapter)
        lockPassage("第1章.md", locked)
        as_(HistorySource.Manual) { save("第1章.md", chapter.replace("眉眼", "眉目")) }
        assertEquals(listOf(locked.replace("眉眼", "眉目")), locks("第1章.md")!!.passages.single().lines)
        // 之后 AI 改回原文也算碰到锁
        assertNotNull(refusal { as_(ai) { fs.writeText(files, "第1章.md", chapter) } })
    }

    @Test
    fun `the lock only follows after the write succeeded`() {
        seed("第1章.md", chapter)
        val passage = lockPassage("第1章.md", locked)
        val file = File(files, "第1章.md")
        val edited = chapter.replace("眉眼", "眉目").toByteArray()
        // 观察者同意了，写盘却失败：没有 Written
        as_(HistorySource.Manual) { history.onChange(WorkspaceChange.Overwrite(file) { edited }) }
        assertEquals(listOf(passage.lines), locks("第1章.md")!!.passages.map { it.lines })
    }

    @Test
    fun `a passage unlocked while its follow is pending does not come back`() {
        seed("第1章.md", chapter)
        val passage = lockPassage("第1章.md", locked)
        val file = File(files, "第1章.md")
        val edited = chapter.replace("眉眼", "眉目").toByteArray()
        as_(HistorySource.Manual) { history.onChange(WorkspaceChange.Overwrite(file) { edited }) }
        runBlocking { history.unlock("w", "第1章.md", chapter = false, passageIds = setOf(passage.id)) }
        file.writeBytes(edited)
        history.onChange(WorkspaceChange.Written(file) { edited })
        assertNull(locks("第1章.md"))
    }

    @Test
    fun `cutting a locked passage pauses it and pasting it back within half an hour wakes it`() {
        seed("第1章.md", chapter)
        lockPassage("第1章.md", locked)
        val cut = chapter.replace("$locked\n\n", "")
        as_(HistorySource.Manual) { save("第1章.md", cut) }
        assertNotNull(locks("第1章.md")!!.passages.single().dormantSince)
        // 暂停期间 AI 改这一章不受这处锁约束
        now += 10 * 60 * 1000
        as_(HistorySource.Manual) { save("第1章.md", "$cut\n\n$locked") }
        val woken = locks("第1章.md")!!.passages.single()
        assertNull(woken.dormantSince)
        assertNotNull(refusal { as_(ai) { fs.writeText(files, "第1章.md", cut) } })
    }

    @Test
    fun `a passage paused for more than half an hour is removed at the next save`() {
        seed("第1章.md", chapter)
        lockPassage("第1章.md", locked)
        val cut = chapter.replace("$locked\n\n", "")
        as_(HistorySource.Manual) { save("第1章.md", cut) }
        now += 31 * 60 * 1000
        as_(HistorySource.Manual) { save("第1章.md", "$cut\n\n新写的一段。") }
        assertNull(locks("第1章.md"))
    }

    @Test
    fun `a book-wide replace with the locked hits ticked follows them and is still recorded as a replace`() {
        seed("第1章.md", chapter)
        val passage = lockPassage("第1章.md", locked)
        as_(HistorySource.Replace, BookLockContext(passages = setOf(passage.id))) { save("第1章.md", chapter.replace("林默", "陈默").replace("女人", "女子")) }
        assertEquals(listOf(locked.replace("女人", "女子")), locks("第1章.md")!!.passages.single().lines)
        val sources = BookHistoryStore(files, { now }).entries("第1章.md").filterIsInstance<HistoryEntry.Version>().map { it.source }
        assertFalse(sources.contains(HistorySource.Manual))
        // 没勾的替换照样被拦
        assertNotNull(refusal { as_(HistorySource.Replace) { save("第1章.md", read("第1章.md").replace("眉眼", "眉目")) } })
    }

    // ---- 删除与移动 ----

    @Test
    fun `deleting a locked chapter takes the lock into the trash and putting it back restores it`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        fs.delete(files, "第1章.md")
        assertNull(locks("第1章.md"))
        val trashed = runBlocking { history.trashed("w") }.single()
        assertEquals("第1章.md", runBlocking { history.restoreTrashed("w", trashed.key, trashed.name) })
        assertNotNull(locks("第1章.md")!!.chapter)
        assertNotNull(refusal { as_(ai) { fs.writeText(files, "第1章.md", "AI") } })
    }

    @Test
    fun `emptying the trash forgets the locks of the files in it`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        fs.delete(files, "第1章.md")
        runBlocking { history.emptyTrash("w") }
        seed("第1章.md", "新的同名文件")
        as_(ai) { fs.writeText(files, "第1章.md", "AI") }
        assertEquals("AI", read("第1章.md"))
    }

    @Test
    fun `a locked file that cannot go into the trash is not deleted`() {
        seed("第1章.md", chapter)
        seed("第2章.md", "第二章")
        lockChapter("第1章.md")
        lockChapter("第2章.md")
        // 回收站目录被一个文件占了：搬不进去
        File(files.parentFile, "history/trash").apply { deleteRecursively(); writeText("x") }
        val result = runBlocking { WorkspaceBatchDeleter(fs).deleteBatch(files, listOf("第1章.md")) }
        assertEquals(WorkspaceFileOperationException.FILE_LOCKED, result.failures.single().errorCode)
        assertEquals(chapter, read("第1章.md"))
    }

    @Test
    fun `a delete from the web or another app leaves a notice`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        BookHistory.withLockContext(BookLockContext(foreign = true)) { fs.delete(files, "第1章.md") }
        assertEquals(LockNotice.Kind.DELETED, runBlocking { history.notices("w") }.single().kind)
    }

    @Test
    fun `renaming inside the book keeps the lock, moving out needs the app's permission`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        fs.move(files, "第1章.md", "卷一/第1章.md")
        assertNotNull(locks("卷一/第1章.md")!!.chapter)
        val refused = runBlocking { WorkspaceFileTransfer(fs).transferBatch(files, listOf("卷一/第1章.md"), other, "", WorkspaceTransferMode.MOVE) }
        assertEquals(WorkspaceFileOperationException.FILE_LOCKED, refused.items.single().errorCode)
        assertTrue(File(files, "卷一/第1章.md").exists())
        under(BookHistory.lockElement(BookLockContext(moveOut = true))) {
            WorkspaceFileTransfer(fs).transfer(files, "卷一/第1章.md", other, "", WorkspaceTransferMode.MOVE)
        }
        assertTrue(File(other, "第1章.md").exists())
        assertTrue(runBlocking { history.locks("w") }.isEmpty())
    }

    @Test
    fun `moving onto a locked target puts the target and its lock into the trash`() {
        seed("第1章.md", chapter)
        seed("草稿.md", "草稿")
        lockChapter("第1章.md")
        fs.move(files, "草稿.md", "第1章.md", overwrite = true)
        // 覆盖锁定的目标按删除处理：目标连锁进了回收站
        assertEquals("草稿", read("第1章.md"))
        assertEquals("第1章.md", runBlocking { history.trashed("w") }.single().path)
    }

    // ---- 永久版本与锁表 ----

    @Test
    fun `the locked version survives cleanup, clearing and cannot be deleted while locked`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        val sha = locks("第1章.md")!!.chapter!!.sha
        runBlocking { history.clear("w") }
        val store = BookHistoryStore(files, { now })
        store.cleanup(0)
        assertEquals(chapter, store.read(sha)?.decodeToString())
        val version = store.entries("第1章.md").filterIsInstance<HistoryEntry.Version>().first { it.sha == sha }
        assertTrue(runCatching { runBlocking { history.deleteVersion("w", "第1章.md", version) } }.exceptionOrNull() is LockRefusedException)
    }

    @Test
    fun `a broken lock table lets writes through, refuses lock changes and pauses cleanup`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        val dir = File(files.parentFile, "history")
        File(dir, "locks.json").writeText("{broken")
        File(dir, "locks.json.bak").writeText("{broken")
        val fresh = BookHistory(scope) { now }.apply { setBooks(mapOf("w" to files)) }
        WorkspaceChanges.observer = fresh::onChange
        as_(ai) { fs.writeText(files, "第1章.md", "AI") }
        assertEquals("AI", read("第1章.md"))
        assertTrue(fresh.locksBroken("w"))
        assertTrue(runCatching { fresh.lockChapter("w", "第1章.md", "AI".toByteArray(), false) }.exceptionOrNull() is LockTableBrokenException)
        val objects = File(dir, "objects").list()!!.toSet()
        now += 400L * 24 * 60 * 60 * 1000
        runBlocking { fresh.scanAll() }
        assertTrue(File(dir, "objects").list()!!.toSet().containsAll(objects))
    }

    @Test
    fun `a damaged lock table falls back to the backup`() {
        seed("第1章.md", chapter)
        lockChapter("第1章.md")
        seed("第2章.md", "第二章")
        lockChapter("第2章.md")
        File(files.parentFile, "history/locks.json").writeText("{broken")
        val fresh = BookHistory(scope) { now }.apply { setBooks(mapOf("w" to files)) }
        assertNotNull(runBlocking { fresh.locksOf("w", "第1章.md") }?.chapter)
        assertFalse(fresh.locksBroken("w"))
    }

    @Test
    fun `too short or overlapping passages are not locked`() {
        seed("第1章.md", "嗯。\n\n$locked")
        val bytes = File(files, "第1章.md").readBytes()
        assertEquals(LockRefusedException.Reason.TOO_SHORT,
            (runCatching { history.lockPassage("w", "第1章.md", bytes, 0..0, false) }.exceptionOrNull() as LockRefusedException).reason)
        history.lockPassage("w", "第1章.md", bytes, 2..2, false)
        assertEquals(LockRefusedException.Reason.OVERLAP,
            (runCatching { history.lockPassage("w", "第1章.md", bytes, 0..2, false) }.exceptionOrNull() as LockRefusedException).reason)
    }
}
