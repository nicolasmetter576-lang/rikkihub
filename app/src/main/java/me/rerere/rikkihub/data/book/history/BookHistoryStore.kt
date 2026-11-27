package me.rerere.rikkihub.data.book.history

import android.util.Log
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.uuid.Uuid

private const val TAG = "BookHistoryStore"

/**
 * 一本书的版本史，放在工作区的 `history/`（与 `files/` 同级，命令行、终端看不到）：
 *
 * - `objects/<sha256>.gz`：版本全文，按内容去重
 * - `files/<键>.jsonl`：每个文件的版本清单，只追加（清理时整份重写）；键是自己生成的，改名移动不换键
 * - `index.json`：当前路径 → 键，以及文件现在的内容是谁写的（[Head]）
 * - `trash/<键>/<时间>.<扩展名>`：删掉的文件整个移进来
 *
 * 写入顺序“对象 → 清单行 → 索引”，哪一步被杀都只会少记，不会记错；索引坏了按清单重建。
 * 方法都拿这本书自己的锁，调用方可能持有存储根的锁：顺序固定为“存储根锁 → 历史锁”，这里不能反过来调文件 API。
 */
internal class BookHistoryStore(
    private val filesDir: File,
    private val clock: () -> Long,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /** 锁表变了（加锁、解锁、跟随、提醒）：界面据此刷新。 */
    private val onLocksChanged: () -> Unit = {},
    /** 一轮的记录变了：界面据此刷新；[TurnKey] 告诉上层这一轮碰过这本书（收尾时只处理碰过的书）。 */
    private val onTurnChanged: (TurnKey) -> Unit = {},
) {
    private val dir = File(filesDir.parentFile, "history")
    private val objects = File(dir, "objects")
    private val lists = File(dir, "files")
    private val trash = File(dir, "trash")
    private val indexFile = File(dir, "index.json")
    private var index: Map<String, Tracked>? = null

    private val lockTable = BookLockTable(dir)

    private val turnTable = BookTurnTable(dir)

    /** 这本书上进行中的命令或 CLI 回合（[watching] 之间）：[turn] 为 null 是不属于任何一轮的命令（终端单条命令、CLI 的 `!命令`）。 */
    class Watch internal constructor(internal val turn: TurnKey?) {
        internal var overlapped = false
        internal var terminal = false
    }

    private val watches = ArrayList<Watch>()

    /** 开着的终端（含持久 bash）。 */
    private var shells = 0

    /** 覆盖写前算好的写后哈希，[written] 复用；按相对路径。 */
    private val writtenShas = HashMap<String, String>()

    /** 带放回许可的还原写下的哈希，等放回成功后记进这一轮（[turnRestored]）；按历史键。 */
    private val restoredShas = HashMap<String, String>()

    /** 手写跟随：写前算好、写成功（[written]）后才提交；按相对路径。 */
    private val pendingFollows = HashMap<String, PendingFollow>()

    private class PendingFollow(val sha: String, val key: String, val follows: Map<String, Pair<PassageLock, BookLocks.Follow>>)

    /** 每个键清单里最后一版的哈希（删掉后为 null）。 */
    private val lastShas = HashMap<String, String?>()

    /** 上次留底失败或空间不够：界面显示“版本历史已暂停”，正文照常写。 */
    @Volatile
    var paused: Boolean = false
        private set

    /**
     * 文件现在的内容 [sha] 是 [source] 在 [at] 写的；同一段手写或同一轮对话从 [since] 开始，
     * [since] 为 0 表示这版自成一段，后面的写入不并进来。[kept]：这版已经记进清单了。
     * [size]、[modifiedAt] 是扫描时看到的，进程内写入后不知道（-1），下次扫描再认一遍。
     */
    @Serializable
    private data class Head(
        val sha: String,
        val source: HistorySource,
        val at: Long,
        val since: Long,
        val size: Long = -1,
        val modifiedAt: Long = -1,
        val kept: Boolean = false,
    )

    @Serializable
    private data class Tracked(val key: String, val head: Head? = null)

    fun contains(file: File): Boolean = file.path.startsWith(filesDir.path + File.separator)

    /** 界面或接口给的相对路径；越界（含软链指出去）返回 null。 */
    fun resolve(path: String): File? = File(filesDir, path).canonicalFile.takeIf(::contains)

    fun relativePath(file: File): String? = relative(file)

    // ---- 进程内写入（钩子） ------------------------------------------------------------------

    /**
     * [file] 马上要被 [content] 换掉：留下写前全文（同一段手写、同一轮对话只留第一次之前的），记下写后是谁写的。
     * AI 的写入记进这一轮（写前的样子强制留底）；[restore]：这是带放回许可的还原（系统放回锁定内容）。
     */
    @Synchronized
    fun overwrite(file: File, content: () -> ByteArray, source: HistorySource, restore: Boolean = false) {
        val path = relative(file)?.takeIf(::tracks) ?: return
        val files = index()
        val tracked = files[path] ?: Tracked(newKey())
        val now = clock()
        val bytes = content()
        val sha = sha256(bytes)
        writtenShas[path] = sha
        if (source == HistorySource.Restore && restore && bytes.size <= MAX_BYTES && storeTurn(sha, bytes)) restoredShas[tracked.key] = sha
        val head = if (file.isFile && file.length() <= MAX_BYTES) {
            val old = file.readBytes()
            val oldSha = sha256(old)
            var current = currentHead(tracked, oldSha, file)
            // 命令进行中你在 App 里改了它刚改过的文件：命令写的那版记在那一轮名下
            val command = soleCommand().takeIf { source !is HistorySource.Ai && current.source == HistorySource.External }
            if (command != null) current = current.copy(source = HistorySource.Ai(command.conversationId, command.messageId))
            val continues = source.continues(current, now)
            if (!continues && !current.kept) keep(tracked.key, path, current, old)
            if (source is HistorySource.Ai) touchTurn(source.turn, tracked.key, path, before = oldSha, beforeBytes = old)
            if (command != null) touchTurn(command, tracked.key, path, before = tracked.head?.sha, created = tracked.head == null, mixed = true)
            // 刚存过一版（编辑器应用推敲之类之前）：这次写进来的也自成一段，接着的手写不会把它并掉
            val alone = current.kept && current.since == 0L
            Head(sha, source, now, since = if (continues) current.since else if (alone) 0 else now)
        } else {
            Head(sha, source, now, now)
        }
        save(files + (path to tracked.copy(head = head)))
    }

    /** 新文件 [file] 刚建好（进程内）：建索引，内容存成对象；AI 新建的记进这一轮。 */
    @Synchronized
    fun created(file: File, content: () -> ByteArray, source: HistorySource) {
        // 只为 AI 这一轮记（新建文件进审阅）；别的来源照 2.59.0，留给扫描记成“初始”
        if (source !is HistorySource.Ai) return
        val path = relative(file)?.takeIf(::tracks) ?: return
        val bytes = content()
        if (bytes.size > MAX_BYTES) return
        val files = index()
        val tracked = files[path] ?: Tracked(newKey())
        // 索引里还留着外面删掉、还没扫到的旧文件：先把它记着的那版留下
        tracked.head?.takeIf { !it.kept && objectOf(it.sha).exists() }?.let { keep(tracked.key, path, it, null) }
        val sha = sha256(bytes)
        storeTurn(sha, bytes)
        val now = clock()
        save(files + (path to tracked.copy(head = Head(sha, source, now, now))))
        touchTurn(source.turn, tracked.key, path, before = null, created = true)
    }

    /**
     * [target]（文件或目录）马上要被删：里面要记的文件整个移进回收站。锁定的文件连锁一起进去（锁休眠，放回后恢复），
     * 而且必须进得去：有一个进不去就把已经搬进去的锁定文件放回原处，拒绝这次删除。[foreign]：网页端或别的 App 删的，记一条提醒。
     */
    @Synchronized
    fun delete(target: File, source: HistorySource, foreign: Boolean = false) {
        val files = index().toMutableMap()
        val now = clock()
        val lockedKeys = activeLocks().keys
        val (locked, others) = trackedFiles(target).partition { files[it.second]?.key in lockedKeys }
        val moved = ArrayList<Triple<File, File, String>>()
        for ((file, path) in locked) {
            val slot = trashSlot(files.getValue(path).key, now, file.extension)
            if (!file.renameTo(slot)) {
                moved.asReversed().forEach { (original, trashed, _) -> trashed.renameTo(original) }
                throw BookLockedException(path, BookLockedException.Reason.TRASH)
            }
            moved += Triple(file, slot, path)
        }
        if (moved.isNotEmpty()) {
            var table = lockTable.read()
            val keys = moved.map { (_, _, path) -> path to files.getValue(path).key }
            for ((index, entry) in moved.withIndex()) {
                val (path, key) = keys[index]
                append(key, HistoryEntry.Deleted(now, path, source, entry.second.name), sync = true)
                table.locks[key]?.let { table = table.copy(locks = table.locks + (key to it.copy(path = path, trashed = entry.second.name))) }
                files -= path
            }
            saveLocks(table)
            if (foreign) keys.forEach { (path, key) -> notice(key, path, LockNotice.Kind.DELETED) }
        }
        for ((file, path) in others) {
            try {
                val key = files[path]?.key ?: newKey()
                val slot = trashSlot(key, now, file.extension)
                if (!file.renameTo(slot)) throw IOException("Cannot move a file into the trash")
                append(key, HistoryEntry.Deleted(now, path, source, slot.name), sync = true)
                files -= path
            } catch (error: IOException) {
                Log.w(TAG, "Deleted without keeping a copy", error)
            }
        }
        save(files)
    }

    /** [from] 刚被移到 [to]（文件或目录）；[to] 为 null 表示移出了这本书。 */
    @Synchronized
    fun moved(from: File, to: File?, source: HistorySource) {
        val fromPath = relative(from) ?: return
        val toPath = to?.let(::relative)
        val files = index()
        val moving = files.filterKeys { it == fromPath || it.startsWith("$fromPath/") }
        if (moving.isEmpty()) return
        val now = clock()
        val arrived = moving.mapNotNull { (path, tracked) ->
            val target = toPath?.let { it + path.removePrefix(fromPath) }?.takeIf(::tracks) ?: return@mapNotNull null
            append(tracked.key, HistoryEntry.Renamed(now, target, source, from = path), sync = false)
            target to tracked
        }
        save(files - moving.keys + arrived)
        // 锁跟着走；移出了这本书的（App 里确认过）解除
        if (!lockTable.broken) {
            val table = lockTable.read()
            val gone = moving.values.map { it.key }.toSet() - arrived.map { it.second.key }.toSet()
            val renamed = arrived.associate { (path, tracked) -> tracked.key to path }
            val locks = (table.locks - gone).mapValues { (key, locks) -> renamed[key]?.let { locks.copy(path = it) } ?: locks }
            saveLocks(table.copy(locks = locks))
        }
    }

    // ---- 定稿锁：写入钩子 ------------------------------------------------------------------------

    /**
     * [file] 马上要被 [content] 换掉：有整章锁就拒绝；段落锁按来源决定是拒绝（[BookLocks.issues]）还是跟着走
     * （你自己的写入：[source] 是手写或还原，或 [context] 放行了这几处）。跟随的结果等写成功（[written]）才提交。
     */
    @Synchronized
    fun checkOverwrite(file: File, content: () -> ByteArray, source: HistorySource?, context: BookLockContext?) {
        val path = relative(file) ?: return
        val key = index()[path]?.key ?: return
        val locks = activeLocks()[key] ?: return
        if (context?.restore == true) return
        if (locks.chapter != null) throw BookLockedException(path, BookLockedException.Reason.CHAPTER)
        if (locks.passages.isEmpty() || !file.isFile) return
        val bytes = content()
        val old = BookLocks.Doc(file.readText())
        val new = BookLocks.Doc(bytes.decodeToString())
        val manual = source == HistorySource.Manual || source == HistorySource.Restore
        val following = locks.passages.filter { manual || it.id in context?.passages.orEmpty() }
        val guarded = locks.passages - following.toSet()
        val issues = BookLocks.issues(old, new, guarded)
        if (issues.isNotEmpty()) throw BookLockedException(path, BookLockedException.Reason.PASSAGES, issues)
        BookLocks.lost(old, guarded).firstOrNull()?.let { notice(key, path, LockNotice.Kind.LOST) }
        pendingFollows.remove(path)
        if (following.isNotEmpty()) {
            pendingFollows[path] = PendingFollow(sha256(bytes), key, following.associate { it.id to (it to BookLocks.follow(old, new, it)) })
        }
    }

    /**
     * [file] 已经写成了 [content]：AI 写下的样子立即强制存成对象（之后被终端、外部程序改掉也丢不了）；
     * 提交写前算好的跟随，按段落 id 合进现在的锁表（这期间解掉的锁不会复活）。
     */
    @Synchronized
    fun written(file: File, content: () -> ByteArray, source: HistorySource = HistorySource.Manual) {
        val path = relative(file) ?: return
        val sha = writtenShas.remove(path)
        val pending = pendingFollows.remove(path)
        if (pending == null && (source !is HistorySource.Ai || !tracks(path))) return
        val bytes = content()
        val writtenSha = sha ?: sha256(bytes)
        if (source is HistorySource.Ai && tracks(path) && bytes.size <= MAX_BYTES) storeTurn(writtenSha, bytes)
        if (pending == null || writtenSha != pending.sha || lockTable.broken) return
        val table = lockTable.read()
        val locks = table.locks[pending.key] ?: return
        val now = clock()
        val doc = BookLocks.Doc(bytes.decodeToString())
        val passages = locks.passages.mapNotNull { passage ->
            val (seen, follow) = pending.follows[passage.id] ?: return@mapNotNull passage
            if (seen != passage) return@mapNotNull passage
            when (follow) {
                is BookLocks.Follow.Moved -> follow.passage
                BookLocks.Follow.Dormant -> passage.copy(dormantSince = passage.dormantSince ?: now)
                BookLocks.Follow.Unknown -> passage
            }
        }.mapNotNull { passage -> wakeOrExpire(passage, doc, now) }
        saveLocks(table.copy(locks = table.locks + (pending.key to locks.copy(passages = passages))))
    }

    /** 暂停的段落锁：原文又出现了就接回；暂停超过 [DORMANT_MS] 就删掉。 */
    private fun wakeOrExpire(passage: PassageLock, doc: BookLocks.Doc, now: Long): PassageLock? {
        val since = passage.dormantSince ?: return passage
        for (lines in listOf(passage.lines, passage.origin)) {
            val probe = passage.copy(lines = lines)
            BookLocks.locate(probe, doc)?.let { range ->
                return BookLocks.passageAt(doc, range, passage.id, passage.at, passage.sha, passage.web).copy(origin = passage.origin)
            }
        }
        return passage.takeIf { now - since < DORMANT_MS }
    }

    /** [from] 马上要移到 [to]：里面有锁定的文件、又要移出这本书（[inBook] 为 false）时，没有 [context] 的放行就拒绝。 */
    @Synchronized
    fun checkMoving(from: File, inBook: Boolean, context: BookLockContext?) {
        if (inBook || context?.moveOut == true) return
        val fromPath = relative(from) ?: return
        val locked = activeLocks().keys
        val hit = index().entries.firstOrNull { (path, tracked) -> (path == fromPath || path.startsWith("$fromPath/")) && tracked.key in locked }
        if (hit != null) throw BookLockedException(hit.key, BookLockedException.Reason.MOVE_OUT)
    }

    // ---- 定稿锁：加锁、解锁、查询 -------------------------------------------------------------------

    /** 有效的锁（不含随文件进了回收站的、空的），按历史键；锁表损坏时为空（放行）。 */
    private fun activeLocks(): Map<String, FileLocks> =
        if (lockTable.broken) emptyMap() else lockTable.read().locks.filterValues { it.trashed == null && !it.isEmpty }

    val locksBroken: Boolean get() = lockTable.broken

    /** 这本书有效的锁，按现在的路径。 */
    @Synchronized
    fun locks(): Map<String, FileLocks> {
        val paths = index().entries.associate { (path, tracked) -> tracked.key to path }
        return activeLocks().mapNotNull { (key, locks) -> paths[key]?.let { it to locks.copy(path = it) } }.toMap()
    }

    @Synchronized
    fun locksOf(path: String): FileLocks? = locks()[path]

    /**
     * 给 [file] 加整章锁：[bytes] 是磁盘上现在的内容（调用方在存储根锁里按版本号读出，所以中间不会被改）。
     * 全文存成永久版本（不受剩余空间门槛限制），存不下就不加锁。返回加锁前的锁（撤销用）。
     */
    @Synchronized
    fun lockChapter(file: File, bytes: ByteArray, web: Boolean): FileLocks? {
        val (path, key, sha) = pin(file, bytes)
        val table = lockTable.read()
        val previous = table.locks[key]
        val next = (previous ?: FileLocks(path)).copy(path = path, chapter = ChapterLock(clock(), sha, web), trashed = null)
        saveLocks(table.copy(locks = table.locks + (key to next)))
        return previous
    }

    /**
     * 给 [file] 的 [rows]（归一后的行号）加一处段落锁：扩到整段，少于 [BookLocks.MIN_CHARS] 个字或和已有的锁重叠时报错。
     */
    @Synchronized
    fun lockPassage(file: File, bytes: ByteArray, rows: IntRange, web: Boolean): Pair<PassageLock, FileLocks?> {
        val doc = BookLocks.Doc(bytes.decodeToString())
        val range = BookLocks.wholeParagraphs(doc, rows) ?: throw LockRefusedException(LockRefusedException.Reason.EMPTY)
        val lines = range.filter { doc.keys[it].isNotEmpty() }.map { doc.lines[it] }
        if (BookLocks.charCount(lines) < BookLocks.MIN_CHARS) throw LockRefusedException(LockRefusedException.Reason.TOO_SHORT)
        val (path, key, sha) = pin(file, bytes)
        val table = lockTable.read()
        val previous = table.locks[key]
        val existing = previous?.passages.orEmpty().filter { it.dormantSince == null }
        if (existing.any { other -> BookLocks.locate(other, doc)?.let { it.first <= range.last && range.first <= it.last } == true }) {
            throw LockRefusedException(LockRefusedException.Reason.OVERLAP)
        }
        val passage = BookLocks.passageAt(doc, range, newKey(), clock(), sha, web)
        val next = (previous ?: FileLocks(path)).copy(path = path, passages = previous?.passages.orEmpty() + passage, trashed = null)
        saveLocks(table.copy(locks = table.locks + (key to next)))
        return passage to previous
    }

    /** 解锁：整章锁（[chapter]）和/或这几处段落锁。返回解锁前的锁（撤销用）。 */
    @Synchronized
    fun unlock(path: String, chapter: Boolean, passageIds: Set<String>): FileLocks? {
        val key = index()[path]?.key ?: return null
        val table = lockTable.read()
        val previous = table.locks[key] ?: return null
        val next = previous.copy(chapter = previous.chapter.takeUnless { chapter }, passages = previous.passages.filter { it.id !in passageIds })
        saveLocks(table.copy(locks = if (next.isEmpty) table.locks - key else table.locks + (key to next)))
        return previous
    }

    /**
     * 按现在的稿子重新锁定（“可能是你自己改的”那类提醒）：整章锁换成 [now] 并存永久版本；
     * 段落锁按 [before] → [now] 找到改后的段落重新锁上，找不到的不动。返回之前的锁（撤销用）。
     */
    @Synchronized
    fun relock(file: File, before: ByteArray?, now: ByteArray): FileLocks? {
        val path = relative(file) ?: return null
        val key = index()[path]?.key ?: return null
        val previous = lockTable.read().locks[key] ?: return null
        val sha = pin(file, now).third
        val old = before?.let { BookLocks.Doc(it.decodeToString()) }
        val new = BookLocks.Doc(now.decodeToString())
        val passages = previous.passages.map { passage ->
            if (passage.dormantSince != null || old == null || BookLocks.locate(passage, new) != null) return@map passage
            when (val follow = BookLocks.follow(old, new, passage)) {
                is BookLocks.Follow.Moved -> follow.passage.copy(sha = sha)
                else -> passage
            }
        }
        val next = previous.copy(path = path, chapter = previous.chapter?.copy(at = clock(), sha = sha), passages = passages)
        val table = lockTable.read()
        saveLocks(table.copy(locks = table.locks + (key to next)))
        return previous
    }

    /** 永久版本（加锁时存的）的哈希。 */
    @Synchronized
    fun pinned(): Set<String> = lockTable.read().pinned

    /** 随文件进了回收站的锁在回收站里的名字（“放回”被删的锁定章用）。 */
    @Synchronized
    fun trashedSlot(key: String): String? = lockTable.read().locks[key]?.trashed

    /** 撤销：把 [path] 的锁换回 [previous]（原范围、原锚点、原永久版本）。 */
    @Synchronized
    fun restoreLocks(path: String, previous: FileLocks?) {
        val key = index()[path]?.key ?: return
        val table = lockTable.read()
        val locks = if (previous == null || previous.isEmpty) table.locks - key else table.locks + (key to previous.copy(path = path))
        saveLocks(table.copy(locks = locks, pinned = table.pinned + listOfNotNull(previous?.chapter?.sha) + previous?.passages.orEmpty().map { it.sha }))
    }

    /** 把这一章现在的样子存成永久版本，返回（路径，历史键，哈希）。 */
    private fun pin(file: File, bytes: ByteArray): Triple<String, String, String> {
        val path = relative(file)?.takeIf(::tracks) ?: throw LockRefusedException(LockRefusedException.Reason.NOT_TRACKED)
        if (bytes.size > MAX_BYTES) throw LockRefusedException(LockRefusedException.Reason.TOO_LARGE)
        val files = index()
        val sha = sha256(bytes)
        val tracked = files[path] ?: Tracked(newKey())
        if (!store(sha, bytes, force = true)) throw IOException("Cannot keep the locked version")
        // 文件现在的样子和记着的不一样（之前被外面改过）：先把记着的那版留下
        tracked.head?.takeIf { it.sha != sha && !it.kept && objectOf(it.sha).exists() }?.let { keep(tracked.key, path, it, null) }
        val head = currentHead(tracked, sha, file)
        keep(tracked.key, path, head, null)
        save(files + (path to tracked.copy(head = head.copy(since = 0, kept = true))))
        val table = lockTable.read()
        saveLocks(table.copy(pinned = table.pinned + sha))
        return Triple(path, tracked.key, sha)
    }

    private fun saveLocks(data: LockTableData) {
        if (data == lockTable.read()) return
        lockTable.write(data)
        onLocksChanged()
    }

    /** 记一条提醒：同一文件同一种只留最新一条（记下更早的次数），最多 [MAX_NOTICES] 条；已处理过的同一状态不再记。 */
    private fun notice(
        key: String,
        path: String,
        kind: LockNotice.Kind,
        beforeSha: String? = null,
        changedSha: String? = null,
        source: HistorySource? = null,
        currentSha: String? = null,
    ) {
        if (lockTable.broken) return
        val table = lockTable.read()
        if (currentSha != null && HandledLock(key, currentSha) in table.handled) return
        val earlier = table.notices.firstOrNull { it.key == key && it.kind == kind }
        val ai = source as? HistorySource.Ai
        val notice = LockNotice(
            newKey(), key, path, kind, clock(), ai?.conversationId, ai?.messageId, beforeSha, changedSha,
            earlier = earlier?.let { it.earlier + 1 } ?: 0,
        )
        val notices = (table.notices.filterNot { it.key == key && it.kind == kind } + notice).takeLast(MAX_NOTICES)
        saveLocks(table.copy(notices = notices))
    }

    @Synchronized
    fun notices(): List<LockNotice> {
        if (lockTable.broken) return emptyList()
        val paths = index().entries.associate { (path, tracked) -> tracked.key to path }
        return lockTable.read().notices.map { notice -> paths[notice.key]?.let { notice.copy(path = it) } ?: notice }
    }

    /** 关掉一条提醒；[currentSha] 不为 null 时记成已处理（这个文件是这个样子时不再提醒）。 */
    @Synchronized
    fun dismiss(id: String, currentSha: String?) {
        val table = lockTable.read()
        val notice = table.notices.firstOrNull { it.id == id } ?: return
        val handled = if (currentSha == null) table.handled else (table.handled + HandledLock(notice.key, currentSha)).takeLast(MAX_NOTICES * 4)
        saveLocks(table.copy(notices = table.notices - notice, handled = handled))
    }

    @Synchronized
    fun markNotices(ids: Set<String>, seen: Boolean = false, delivered: Boolean = false) {
        val table = lockTable.read()
        saveLocks(table.copy(notices = table.notices.map {
            if (it.id in ids) it.copy(seen = it.seen || seen, delivered = it.delivered || delivered) else it
        }))
    }

    /** 已放回、之后核对完好的提醒，打开过比较或放了 7 天就归档。 */
    private fun archiveNotices(now: Long) {
        val table = lockTable.read()
        val kept = table.notices.filterNot { !it.needsDecision && (it.seen || now - it.at > ARCHIVE_MS) }
        if (kept.size != table.notices.size) saveLocks(table.copy(notices = kept))
    }

    /** 这一章是否已经提示过“已锁定”；没有的话记下来并返回 false。 */
    @Synchronized
    fun tipShown(path: String): Boolean {
        val key = index()[path]?.key ?: return true
        val table = lockTable.read()
        if (key in table.tips || lockTable.broken) return true
        saveLocks(table.copy(tips = table.tips + key))
        return false
    }

    @Synchronized
    fun reloadLocks() {
        lockTable.reload()
        onLocksChanged()
    }

    // ---- 主动留底与扫描 ------------------------------------------------------------------------

    /**
     * 现在就存一版，不受手写合并限制：[content] 为 null 时存文件现在的内容（记在写它的人名下），
     * 否则存 [content]（编辑器里还没保存的稿子，记在 [source] 名下）。之后的下一次写入不会并进这一版之前的那段。
     */
    @Synchronized
    fun checkpoint(file: File, content: ByteArray?, source: HistorySource) {
        val path = relative(file)?.takeIf(::tracks) ?: return
        if (!file.isFile || file.length() > MAX_BYTES) return
        val files = index()
        val tracked = files[path] ?: Tracked(newKey())
        val bytes = file.readBytes()
        val current = currentHead(tracked, sha256(bytes), file)
        keep(tracked.key, path, current, bytes)
        if (content != null && content.size <= MAX_BYTES) keep(tracked.key, path, Head(sha256(content), source, clock(), 0), content)
        save(files + (path to tracked.copy(head = current.copy(since = 0, kept = true))))
    }

    /**
     * 命令行、CLI、终端、外部 App 可能改过文件：变了的把上一版留下、记成 [source] 改的；不见了的按内容认改名，
     * 认不出就把最后一版放进回收站。结束时每个文件现在的内容都已存成对象，下一次外部改动也丢不了。
     * [only] 只看这一个文件（外部 App 写完时）。
     */
    @Synchronized
    fun scan(source: HistorySource, only: File? = null, attribute: Boolean? = null): List<ScanChange> {
        // 有命令在进行时，别的扫描只留底、不归属：改动留给命令收尾的扫描（命令自己的扫描显式传 [attribute]）
        val attributing = attribute ?: watches.isEmpty()
        val turn = (source as? HistorySource.Ai)?.turn
        val changes = ArrayList<ScanChange>()
        val firstScan = !indexFile.exists() && !lists.exists()
        val files = index().toMutableMap()
        val present = if (only != null) {
            listOfNotNull(relative(only)?.takeIf { tracks(it) && Files.isRegularFile(only.toPath(), LinkOption.NOFOLLOW_LINKS) }?.let { only to it })
        } else {
            trackedFiles(filesDir)
        }
        val now = clock()
        val appeared = ArrayList<Triple<String, Head, ByteArray>>()
        for ((file, path) in present) {
            val size = file.length()
            val modifiedAt = file.lastModified()
            if (size > MAX_BYTES) continue
            val tracked = files[path]
            val head = tracked?.head
            if (head != null && head.size == size && head.modifiedAt == modifiedAt && objectOf(head.sha).exists()) continue
            val bytes = file.readBytes()
            val sha = sha256(bytes)
            when {
                tracked == null -> if (attributing) appeared += Triple(path, Head(sha, HistorySource.Initial, modifiedAt, modifiedAt, size, modifiedAt), bytes)
                head != null && head.sha == sha -> {
                    store(sha, bytes)
                    files[path] = tracked.copy(head = head.copy(size = size, modifiedAt = modifiedAt))
                }
                attributing -> {
                    if (head != null && !head.kept && objectOf(head.sha).exists()) keep(tracked.key, path, head, null)
                    if (turn != null) storeTurn(sha, bytes) else store(sha, bytes)
                    files[path] = tracked.copy(head = Head(sha, source, now, now, size, modifiedAt))
                    changes += ScanChange(tracked.key, path, head?.sha, sha, trashed = null)
                    if (turn != null) touchTurn(turn, tracked.key, path, before = head?.sha)
                }
            }
        }
        if (only == null && attributing) {
            val gone = files.keys - present.map { it.second }.toSet()
            for (path in gone) {
                val tracked = files.getValue(path)
                val head = tracked.head
                val renamedTo = head?.let { appeared.firstOrNull { it.second.sha == head.sha } }
                if (head != null && renamedTo != null) {
                    appeared -= renamedTo
                    append(tracked.key, HistoryEntry.Renamed(now, renamedTo.first, source, from = path), sync = false)
                    files[renamedTo.first] = tracked.copy(head = head.copy(size = renamedTo.second.size, modifiedAt = renamedTo.second.modifiedAt))
                    relocateLock(tracked.key) { it.copy(path = renamedTo.first) }
                    if (turn != null) touchTurn(turn, tracked.key, renamedTo.first, before = head.sha, renamedFrom = path)
                } else {
                    val slot = trashLastVersion(tracked, path, source, now)
                    if (slot != null) relocateLock(tracked.key) { it.copy(path = path, trashed = slot) }
                    changes += ScanChange(tracked.key, path, head?.sha, null, trashed = slot)
                    if (turn != null) touchTurn(turn, tracked.key, path, before = head?.sha, trashed = slot, deleted = slot == null)
                }
                files -= path
            }
        }
        for ((path, head, bytes) in appeared) {
            val key = newKey()
            if (turn != null) storeTurn(head.sha, bytes) else store(head.sha, bytes)
            files[path] = Tracked(key, if (firstScan) head else head.copy(source = source.takeUnless { it == HistorySource.External } ?: HistorySource.Initial))
            if (!firstScan) changes += ScanChange(key, path, null, head.sha, trashed = null)
            if (turn != null) touchTurn(turn, key, path, before = null, created = true)
        }
        save(files)
        return changes
    }

    // ---- 定稿锁：扫描后的核对 ----------------------------------------------------------------------

    /**
     * 扫描发现的改动里碰到锁的：整章锁全文不是底稿了、段落锁找不到了、锁定的文件被删了。
     * [restorable]：改前和改后的内容都已经是能读出的全文（放回前的硬检查；不满足只提醒，不放回）。
     */
    @Synchronized
    fun lockFindings(changes: List<ScanChange>): List<LockFinding> {
        if (lockTable.broken) return emptyList()
        val table = lockTable.read()
        return changes.mapNotNull { change ->
            val locks = table.locks[change.key]?.takeIf { !it.isEmpty } ?: return@mapNotNull null
            if (change.trashed != null) {
                return@mapNotNull LockFinding(change.key, change.path, locks, locks.passages, change.beforeSha, null, change.trashed, restorable = true)
            }
            if (locks.trashed != null) return@mapNotNull null
            val after = change.afterSha?.let(::read)
            val before = change.beforeSha?.let(::read)
            val chapterBroken = locks.chapter != null && change.afterSha != locks.chapter.sha
            val doc = after?.let { BookLocks.Doc(it.decodeToString()) }
            val passages = locks.passages.filter { it.dormantSince == null && (doc == null || BookLocks.locate(it, doc) == null) }
            if (!chapterBroken && passages.isEmpty()) return@mapNotNull null
            LockFinding(change.key, change.path, locks, passages, change.beforeSha, change.afterSha, null, restorable = before != null && after != null)
        }
    }

    /** 外部 App 改了有段落锁的文件（视同你自己打字）：锁跟着走，从 [beforeSha] 跟到 [afterSha]。 */
    @Synchronized
    fun followExternal(key: String, beforeSha: String, afterSha: String) {
        val before = read(beforeSha) ?: return
        val after = read(afterSha) ?: return
        val table = lockTable.read()
        val locks = table.locks[key] ?: return
        val old = BookLocks.Doc(before.decodeToString())
        val new = BookLocks.Doc(after.decodeToString())
        val now = clock()
        val passages = locks.passages.map { passage ->
            when (val follow = BookLocks.follow(old, new, passage)) {
                is BookLocks.Follow.Moved -> follow.passage
                BookLocks.Follow.Dormant -> passage.copy(dormantSince = passage.dormantSince ?: now)
                BookLocks.Follow.Unknown -> passage
            }
        }
        saveLocks(table.copy(locks = table.locks + (key to locks.copy(passages = passages))))
    }

    /** 记一条扫描后的提醒（来源决定能不能对上某条回复）。 */
    @Synchronized
    fun noticeFinding(finding: LockFinding, kind: LockNotice.Kind, source: HistorySource?) {
        notice(finding.key, finding.path, kind, finding.beforeSha, finding.afterSha, source, currentSha = finding.afterSha.takeIf { kind == LockNotice.Kind.CHANGED })
    }

    /** 有锁的文件（全量核对用）：路径、历史键、锁；读文件和算哈希由调用方在锁外做。 */
    @Synchronized
    fun lockTargets(): List<Triple<String, String, FileLocks>> {
        val paths = index().entries.associate { (path, tracked) -> tracked.key to path }
        return activeLocks().mapNotNull { (key, locks) -> paths[key]?.let { Triple(it, key, locks) } }
    }

    /** 全量核对的结果：整章锁的底稿丢了但文件没变就补存；变了的记提醒（已处理过的同一状态不再记）。 */
    @Synchronized
    fun verified(key: String, path: String, locks: FileLocks, bytes: ByteArray) {
        // 命令进行中：它收尾时会扫描、放回，这时记的提醒会和放回后的状态重复
        if (watches.isNotEmpty()) return
        val sha = sha256(bytes)
        val chapter = locks.chapter
        if (chapter != null) {
            val baseMissing = read(chapter.sha) == null
            when {
                sha == chapter.sha && baseMissing -> store(sha, bytes, force = true)
                sha != chapter.sha && baseMissing -> notice(key, path, LockNotice.Kind.BASE_MISSING, changedSha = sha.takeIf { store(it, bytes) }, currentSha = sha)
                sha != chapter.sha -> notice(key, path, LockNotice.Kind.CHANGED, chapter.sha, sha.takeIf { store(it, bytes) }, currentSha = sha)
            }
            return
        }
        val doc = BookLocks.Doc(bytes.decodeToString())
        if (locks.passages.any { it.dormantSince == null && BookLocks.locate(it, doc) == null }) {
            notice(key, path, LockNotice.Kind.CHANGED, locks.passages.firstOrNull()?.sha, sha.takeIf { store(it, bytes) }, currentSha = sha)
        }
    }

    /** 外面删掉的文件：把最后一版放进回收站，返回回收站里的文件名。 */
    private fun trashLastVersion(tracked: Tracked, path: String, source: HistorySource, now: Long): String? {
        val bytes = tracked.head?.let { read(it.sha) } ?: return null
        return try {
            val slot = trashSlot(tracked.key, now, path.substringAfterLast('.', "txt"))
            slot.writeBytes(bytes)
            append(tracked.key, HistoryEntry.Deleted(now, path, source, slot.name), sync = true)
            slot.name
        } catch (error: IOException) {
            Log.w(TAG, "Lost track of a file deleted outside the app", error)
            null
        }
    }

    private fun relocateLock(key: String, change: (FileLocks) -> FileLocks) {
        if (lockTable.broken) return
        val table = lockTable.read()
        val locks = table.locks[key] ?: return
        saveLocks(table.copy(locks = table.locks + (key to change(locks))))
    }

    // ---- 读 ------------------------------------------------------------------------------------

    @Synchronized
    fun currentWriter(path: String): HistorySource? = index()[path]?.head?.source

    @Synchronized
    fun entries(path: String): List<HistoryEntry> = index()[path]?.let { readList(it.key) }.orEmpty()

    /** 一版的全文；对象丢了或坏了返回 null。 */
    @Synchronized
    fun read(sha: String): ByteArray? = runCatching {
        GZIPInputStream(objectOf(sha).inputStream()).use { it.readBytes() }.takeIf { sha256(it) == sha }
    }.getOrNull()

    /** 回收站里还在的文件，新的在前。 */
    @Synchronized
    fun trashed(): List<TrashedFile> = lists.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.flatMap { list ->
        val key = list.name.removeSuffix(".jsonl")
        readList(key).filterIsInstance<HistoryEntry.Deleted>().mapNotNull { entry ->
            File(trash, "$key/${entry.trash}").takeIf { it.isFile }?.let { TrashedFile(key, entry.trash, entry.path, entry.at, entry.source, it.length()) }
        }
    }.sortedByDescending { it.at }

    /** 历史占用的空间（字节）。 */
    @Synchronized
    fun usage(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- 回收站与清理（只在用户操作或后台空闲时） -------------------------------------------------

    /** 放回原路径（重名时 “名字 (n)”），返回放回的路径；回收站里已经没有就返回 null。 */
    @Synchronized
    fun restore(key: String, name: String): String? {
        val entry = readList(key).filterIsInstance<HistoryEntry.Deleted>().lastOrNull { it.trash == name } ?: return null
        val source = File(trash, "$key/$name").takeIf { it.isFile } ?: return null
        val folder = entry.path.substringBeforeLast('/', "")
        val stem = entry.path.substringAfterLast('/').substringBeforeLast('.')
        val extension = entry.path.substringAfterLast('.')
        File(filesDir, folder).mkdirs()
        var path = entry.path
        var n = 0
        while (true) {
            try {
                Files.move(source.toPath(), File(filesDir, path).toPath())
                break
            } catch (_: FileAlreadyExistsException) {
                path = (if (folder.isEmpty()) "" else "$folder/") + "$stem (${++n}).$extension"
            }
        }
        val file = File(filesDir, path)
        val bytes = file.readBytes()
        val now = clock()
        val head = Head(sha256(bytes), HistorySource.Restore, now, 0, file.length(), file.lastModified(), kept = bytes.size <= MAX_BYTES)
        if (bytes.size <= MAX_BYTES) keep(key, path, head, bytes)
        if (bytes.size <= MAX_BYTES && storeTurn(head.sha, bytes)) restoredShas[key] = head.sha
        save(index() + (path to Tracked(key, head)))
        // 随文件进了回收站的锁恢复
        relocateLock(key) { locks -> if (locks.trashed == name) locks.copy(path = path, trashed = null) else locks }
        return path
    }

    @Synchronized
    fun deleteTrashed(key: String, name: String) {
        File(trash, "$key/$name").delete()
        if (!lockTable.broken) {
            val table = lockTable.read()
            if (table.locks[key]?.trashed == name) saveLocks(table.copy(locks = table.locks - key))
        }
    }

    @Synchronized
    fun emptyTrash() {
        trash.deleteRecursively()
        if (!lockTable.broken) {
            val table = lockTable.read()
            saveLocks(table.copy(locks = table.locks.filterValues { it.trashed == null }))
        }
    }

    /** 删掉某个文件的一版（隐私）；全文等下次清理时没人引用了才删。锁还在用的永久版本不能删。 */
    @Synchronized
    fun deleteVersion(path: String, version: HistoryEntry.Version) {
        if (lockTable.broken) throw LockTableBrokenException()
        val key = index()[path]?.key ?: return
        val table = lockTable.read()
        if (version.sha in lockShas(table)) throw LockRefusedException(LockRefusedException.Reason.VERSION_IN_USE)
        if (version.sha in table.pinned) saveLocks(table.copy(pinned = table.pinned - version.sha))
        rewriteList(key, readList(key) - version)
        // 审阅记录也不能再留着它（否则隐私删除被保护名单架空）；别的版本行、文件现在的样子还在用它时照旧保留
        val stillUsed = index().values.any { it.head?.sha == version.sha } || lists.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.any { list ->
            readList(list.name.removeSuffix(".jsonl")).any { it is HistoryEntry.Version && it.sha == version.sha }
        }
        if (!stillUsed) forgetTurnSha(version.sha)
        collectGarbage()
    }

    /** 清空历史：回收站（删除记录要跟着它）和永久版本保留。 */
    @Synchronized
    fun clear() {
        if (lockTable.broken) throw LockTableBrokenException()
        val pinned = lockTable.read().pinned
        lists.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.forEach { list ->
            val key = list.name.removeSuffix(".jsonl")
            rewriteList(key, readList(key).filter { it is HistoryEntry.Deleted || it is HistoryEntry.Version && it.sha in pinned })
        }
        turnTable.clear()
        collectGarbage()
    }

    /**
     * 按 [thin] 稀疏化；超过 [limitBytes] 再从最老的删起（每个文件最后一版不删）；最后删没人引用的全文。
     */
    @Synchronized
    fun cleanup(limitBytes: Long) {
        // 锁表读不出来时不知道哪些是永久版本：先不清理
        if (lockTable.broken) return
        val now = clock()
        archiveNotices(now)
        // 审阅记录保护的版本超过上限的一半：最老的待审记录提前归档
        while (turnShas().sumOf { objectOf(it).length() } > limitBytes / 2) {
            val oldest = turnTable.all().filter { it.status == TurnStatus.PENDING }.minByOrNull { it.finishedAt ?: it.startedAt } ?: break
            putTurn(oldest.copy(archivedAt = now))
        }
        val pinned = lockTable.read().pinned + turnShas()
        val all = lists.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.associate { list ->
            val key = list.name.removeSuffix(".jsonl")
            key to readList(key)
        }.toMutableMap()
        for ((key, entries) in all) {
            val versions = entries.filterIsInstance<HistoryEntry.Version>()
            val kept = thin(versions, now, pinned)
            if (kept.size < versions.size) {
                val next = entries.filter { it !is HistoryEntry.Version || it in kept }
                rewriteList(key, next)
                all[key] = next
            }
        }
        val heads = index().values.mapNotNull { it.head?.sha } + pinned
        val refs = HashMap<String, Int>()
        fun count(sha: String, delta: Int) = refs.merge(sha, delta, Int::plus)
        heads.forEach { count(it, 1) }
        all.values.flatten().filterIsInstance<HistoryEntry.Version>().forEach { count(it.sha, 1) }
        var total = refs.keys.sumOf { objectOf(it).length() }
        if (total > limitBytes) {
            val candidates = all.flatMap { (key, entries) ->
                entries.filterIsInstance<HistoryEntry.Version>().dropLast(1).filter { it.sha !in pinned }.map { key to it }
            }.sortedBy { it.second.at }
            val dropped = HashMap<String, MutableSet<HistoryEntry.Version>>()
            for ((key, version) in candidates) {
                if (total <= limitBytes) break
                dropped.getOrPut(key) { HashSet() } += version
                count(version.sha, -1)
                if (refs[version.sha] == 0) total -= objectOf(version.sha).length()
            }
            for ((key, versions) in dropped) rewriteList(key, all.getValue(key).filterNot { it in versions })
        }
        collectGarbage()
    }

    /** 全文对象：清单和索引都不引用、且放了一小时以上的删掉（刚写的对象可能还没来得及记进清单）。 */
    private fun collectGarbage() {
        if (lockTable.broken) return
        val referenced = HashSet<String>()
        index().values.mapNotNullTo(referenced) { it.head?.sha }
        val table = lockTable.read()
        referenced += table.pinned
        referenced += lockShas(table)
        referenced += turnShas()
        lists.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.forEach { list ->
            readList(list.name.removeSuffix(".jsonl")).filterIsInstance<HistoryEntry.Version>().mapTo(referenced) { it.sha }
        }
        val cutoff = clock() - GRACE_MS
        objects.listFiles().orEmpty().forEach { file ->
            val sha = file.name.removeSuffix(".gz")
            if (file.lastModified() < cutoff && (file.name.startsWith(".") || sha !in referenced)) file.delete()
        }
    }

    // ---- 逐处审阅：一轮的记录 -------------------------------------------------------------------------

    /**
     * 这一轮碰到了 [key]（现在在 [path]）：第一次碰到时记下改前（[before]，[beforeBytes] 给出时强制留底）；
     * 已收尾、已审阅、已归档的记录重新打开（各文件回到待审，撤销信息保留）。
     */
    private fun touchTurn(
        turn: TurnKey,
        key: String,
        path: String,
        before: String?,
        beforeBytes: ByteArray? = null,
        created: Boolean = false,
        mixed: Boolean = false,
        renamedFrom: String? = null,
        trashed: String? = null,
        deleted: Boolean = false,
    ) {
        if (!BookTurnTable.accepts(turn)) return
        val now = clock()
        val record = turnTable.get(turn) ?: TurnRecord(turn.conversationId, turn.messageId, startedAt = now)
        val existing = record.files[key]
        val file = if (existing == null) {
            val kept = before == null || if (beforeBytes != null) storeTurn(before, beforeBytes) else objectOf(before).exists()
            TurnFile(
                path = path, before = before, created = created, mixed = mixed, renamedFrom = renamedFrom, trashed = trashed, deleted = deleted,
                since = now, missing = TurnMissing.BEFORE.takeUnless { kept },
            )
        } else {
            existing.copy(
                path = path,
                mixed = existing.mixed || mixed,
                renamedFrom = existing.renamedFrom ?: renamedFrom,
                trashed = trashed ?: existing.trashed,
                deleted = existing.deleted || deleted,
            )
        }
        val reopened = record.copy(
            finishedAt = null,
            archivedAt = null,
            files = record.files.mapValues { (_, it) -> it.copy(done = false) } + (key to file.copy(done = false)),
        )
        putTurn(reopened)
    }

    private fun putTurn(record: TurnRecord) {
        if (turnTable.get(record.key) == record) return
        turnTable.put(record)
        onTurnChanged(record.key)
    }

    private fun removeTurn(key: TurnKey) {
        if (turnTable.get(key) == null) return
        turnTable.remove(key)
        onTurnChanged(key)
    }

    /** 一轮的版本强制留底：不受历史暂停（< 1 GB）限制，只在剩余空间不到 [TURN_MIN_FREE_BYTES] 时放弃；成功也不解除暂停。 */
    private fun storeTurn(sha: String, bytes: ByteArray): Boolean {
        val target = objectOf(sha)
        if (target.exists()) return true
        objects.mkdirs()
        if (freeSpace(objects) < TURN_MIN_FREE_BYTES) return false
        return try {
            val temp = File(objects, ".$sha.tmp")
            fastGzip(temp.outputStream()).use { it.write(bytes) }
            temp.renameTo(target)
        } catch (error: IOException) {
            Log.w(TAG, "Could not keep a version for review", error)
            false
        }
    }

    /** 审阅记录保护的版本（未归档的）：清理与回收不删。 */
    private fun turnShas(): Set<String> = turnTable.all().filter { it.archivedAt == null }.flatMapTo(HashSet()) { record ->
        record.files.values.flatMap { listOfNotNull(it.before, it.after, it.restoredSha, it.undo?.beforeSha, it.undo?.writtenSha) }
    }

    /** 删掉的一版从所有记录里摘掉。 */
    private fun forgetTurnSha(sha: String) {
        for (record in turnTable.all().toList()) {
            if (record.files.values.none { sha in listOfNotNull(it.before, it.after, it.restoredSha, it.undo?.beforeSha, it.undo?.writtenSha) }) continue
            putTurn(record.copy(files = record.files.mapValues { (_, file) ->
                val hit = file.before == sha || file.after == sha || file.restoredSha == sha
                file.copy(
                    before = file.before.takeUnless { it == sha },
                    after = file.after.takeUnless { it == sha },
                    restoredSha = file.restoredSha.takeUnless { it == sha },
                    undo = file.undo.takeUnless { it?.beforeSha == sha || it?.writtenSha == sha },
                    missing = if (hit) TurnMissing.DELETED_VERSION else file.missing,
                )
            }))
        }
    }

    /** 这本书上只有一个属于某一轮、没有重叠、没开终端的命令在进行：它是哪一轮。 */
    private fun soleCommand(): TurnKey? = watches.singleOrNull()?.takeIf { !it.overlapped && !it.terminal }?.turn

    /** 一个命令或 CLI 回合开始：和这本书上别的轮次的命令同时进行就都标重叠；开着终端标 [Watch.terminal]。 */
    @Synchronized
    fun beginWatch(turn: TurnKey?): Watch {
        val watch = Watch(turn)
        for (other in watches) {
            if (turn == null || other.turn == null || other.turn != turn) {
                other.overlapped = true
                watch.overlapped = true
            }
        }
        if (shells > 0) watch.terminal = true
        watches += watch
        return watch
    }

    /** 命令收尾时它的改动能不能归到这一轮（不能时的原因）。 */
    @Synchronized
    fun unattributed(watch: Watch): Unattributed? = when {
        watch.overlapped -> Unattributed.OVERLAP
        watch.terminal -> Unattributed.TERMINAL
        else -> null
    }

    @Synchronized
    fun endWatch(watch: Watch): Unattributed? = unattributed(watch).also { watches.remove(watch) }

    val commandRunning: Boolean
        @Synchronized get() = watches.isNotEmpty()

    @Synchronized
    fun shellOpened() {
        shells++
        watches.forEach { it.terminal = true }
    }

    @Synchronized
    fun shellClosed() {
        shells = (shells - 1).coerceAtLeast(0)
    }

    /** 这一轮的命令改动没能归到它（重叠、开着终端）：记在记录上，卡片和审阅页说明。 */
    @Synchronized
    fun markUnattributed(turn: TurnKey, reason: Unattributed) {
        if (!BookTurnTable.accepts(turn)) return
        val record = turnTable.get(turn) ?: TurnRecord(turn.conversationId, turn.messageId, startedAt = clock())
        putTurn(record.copy(commandsUnattributed = reason, finishedAt = null, archivedAt = null))
    }

    /** 这一轮命令收尾时系统放回了锁定内容（或从回收站放回了删掉的锁定文件）：把写下的样子记进记录。 */
    @Synchronized
    fun turnRestored(turn: TurnKey, key: String) {
        val sha = restoredShas.remove(key) ?: return
        val record = turnTable.get(turn) ?: return
        val file = record.files[key] ?: return
        putTurn(record.copy(files = record.files + (key to file.copy(restoredSha = sha, trashed = null, deleted = false))))
    }

    /**
     * 收尾：重算这一轮每个文件的“改后”= 这一轮写下的最后一版。[generating] 为真（这条消息又开始生成了）时不收尾。
     * 返回记录是否变了。
     */
    @Synchronized
    fun finishTurn(turn: TurnKey, generating: () -> Boolean): Boolean {
        // 收尾过、之后没有新写入的不用重算（新写入会把记录重新打开）
        val record = turnTable.get(turn)?.takeIf { it.finishedAt == null } ?: return false
        if (generating()) return false
        val files = index()
        val paths = files.entries.associate { (path, tracked) -> tracked.key to path }
        val next = record.files.mapNotNull { (key, file) -> finishFile(turn, key, file, paths[key], files) }.toMap()
        if (next.isEmpty() && record.commandsUnattributed == null) {
            removeTurn(turn)
        } else {
            putTurn(record.copy(finishedAt = clock(), files = next))
        }
        return true
    }

    private fun finishFile(turn: TurnKey, key: String, file: TurnFile, path: String?, files: Map<String, Tracked>): Pair<String, TurnFile>? {
        if (file.trashed != null || file.deleted) {
            // 同一轮先新建后删掉的：不算
            return if (file.created && path == null) null else key to file.copy(path = path ?: file.path, after = null)
        }
        val head = path?.let { files[it]?.head }
        val versions = readList(key).filterIsInstance<HistoryEntry.Version>()
        fun mine(source: HistorySource) = source is HistorySource.Ai && source.turn == turn
        val after = when {
            head != null && mine(head.source) && objectOf(head.sha).exists() -> head.sha
            file.restoredSha != null && head?.sha == file.restoredSha -> file.restoredSha
            else -> versions.lastOrNull { mine(it.source) }?.sha?.takeIf { objectOf(it).exists() }
                // 只改了名、内容没动
                ?: file.before?.takeIf { head?.sha == it }
        }
        val others = head != null && !mine(head.source) && head.sha != file.restoredSha && head.at >= file.since ||
            versions.any { it.at >= file.since && !mine(it.source) && it.source != HistorySource.Restore }
        if (after != null && after == file.before && !file.created && file.renamedFrom == null) return null
        val layoutOnly = after != null && file.before != null && run {
            val old = read(file.before)?.decodeToString() ?: return@run false
            val new = read(after)?.decodeToString() ?: return@run false
            old != new && keysOf(old) == keysOf(new)
        }
        return key to file.copy(
            path = path ?: file.path,
            after = after,
            missing = if (after == null) TurnMissing.AFTER else file.missing.takeUnless { it == TurnMissing.AFTER },
            mixed = file.mixed || others,
            layoutOnly = layoutOnly,
        )
    }

    private fun keysOf(text: String) = BookLocks.normalize(text).split('\n').map(BookLocks::key).filter { it.isNotEmpty() }

    /** 还没收尾的轮次（被杀、收尾出错；补收尾用）。 */
    @Synchronized
    fun openTurns(): List<TurnKey> = turnTable.all().filter { it.finishedAt == null }.map { it.key }

    @Synchronized
    fun turns(): List<TurnRecord> = turnTable.all().toList()

    @Synchronized
    fun turn(turn: TurnKey): TurnRecord? = turnTable.get(turn)

    /** 改一条记录（审阅完成、撤销、看过）；记录不在了返回 null。 */
    @Synchronized
    fun updateTurn(turn: TurnKey, transform: (TurnRecord) -> TurnRecord): TurnRecord? {
        val record = turnTable.get(turn) ?: return null
        return transform(record).also(::putTurn)
    }

    @Synchronized
    fun muted(): Set<String> = turnTable.state().muted

    @Synchronized
    fun mute(conversationId: String, muted: Boolean) {
        val state = turnTable.state()
        turnTable.writeState(state.copy(muted = if (muted) state.muted + conversationId else state.muted - conversationId))
    }

    /** 上次清理时的（墙钟，单调时钟）。 */
    @Synchronized
    fun turnClock(): Pair<Long, Long>? = turnTable.state().let { state -> state.cleanedWall?.let { wall -> state.cleanedMono?.let { wall to it } } }

    @Synchronized
    fun setTurnClock(wall: Long, mono: Long) {
        turnTable.writeState(turnTable.state().copy(cleanedWall = wall, cleanedMono = mono))
    }

    /** 审阅前强制存一版（[bytes] 是快照内容），记在现在写它的人名下；存不下返回 null。 */
    @Synchronized
    fun keepForReview(path: String, bytes: ByteArray): String? {
        if (!tracks(path) || bytes.size > MAX_BYTES) return null
        val files = index()
        val tracked = files[path] ?: return null
        val sha = sha256(bytes)
        if (!storeTurn(sha, bytes)) return null
        val file = File(filesDir, path)
        val current = currentHead(tracked, sha, file)
        keep(tracked.key, path, current, null)
        save(files + (path to tracked.copy(head = current.copy(since = 0, kept = true))))
        return sha
    }

    /** 历史键最后一次删除时进了回收站的名字。 */
    @Synchronized
    fun trashNameOf(key: String): String? = readList(key).lastOrNull { it is HistoryEntry.Deleted }?.let { (it as HistoryEntry.Deleted).trash }
            ?.takeIf { File(trash, "$key/$it").isFile }

    /** 历史键现在的路径（不在了为 null）。 */
    @Synchronized
    fun pathOf(key: String): String? = index().entries.firstOrNull { it.value.key == key }?.key

    /**
     * 按年龄与回复在不在归档、删除：已归档、已审阅超过 7 天的删掉；回复没了的归档；待审 7 天没看过的归档。
     * [trustAge] 为 false（墙钟跳过）时只按回复在不在处理。记录时间晚于现在 1 天以上的按现在重记。
     */
    @Synchronized
    fun ageTurns(now: Long, presence: Map<TurnKey, MessagePresence>, trustAge: Boolean) {
        fun age(at: Long) = (now - at).coerceAtLeast(0)
        for (record in turnTable.all().toList()) {
            val fixed = record.copy(
                startedAt = record.startedAt.coerceAtMost(now),
                finishedAt = record.finishedAt?.let { if (it > now + DAY_MS) now else it },
                seenAt = record.seenAt?.let { if (it > now + DAY_MS) now else it },
                archivedAt = record.archivedAt?.let { if (it > now + DAY_MS) now else it },
                reviewedAt = record.reviewedAt?.let { if (it > now + DAY_MS) now else it },
            )
            if (fixed.finishedAt == null) {
                if (fixed != record) putTurn(fixed)
                continue
            }
            val status = fixed.status
            when {
                trustAge && status == TurnStatus.ARCHIVED && age(fixed.archivedAt!!) > ARCHIVE_MS -> removeTurn(fixed.key)
                trustAge && status == TurnStatus.REVIEWED && age(fixed.reviewedAt ?: fixed.finishedAt) > ARCHIVE_MS -> removeTurn(fixed.key)
                status != TurnStatus.ARCHIVED && presence[fixed.key] == MessagePresence.GONE -> putTurn(fixed.copy(archivedAt = now))
                trustAge && status == TurnStatus.PENDING && age(maxOf(fixed.seenAt ?: 0, fixed.finishedAt)) > ARCHIVE_MS -> putTurn(fixed.copy(archivedAt = now))
                fixed != record -> putTurn(fixed)
            }
        }
    }

    // ---- 内部 ------------------------------------------------------------------------------------

    /** 文件现在的内容是谁写的；与记着的对不上，就是历史外改的（命令行等）。 */
    private fun currentHead(tracked: Tracked, sha: String, file: File): Head {
        tracked.head?.takeIf { it.sha == sha }?.let { return it }
        val at = file.lastModified()
        return Head(sha, if (tracked.head == null) HistorySource.Initial else HistorySource.External, at, at)
    }

    /** 同一段手写（10 分钟内）、同一轮对话里的连续写入只留第一次之前的。 */
    private fun HistorySource.continues(head: Head, now: Long): Boolean = when (this) {
        HistorySource.Manual -> head.source == HistorySource.Manual && now - head.since in 0 until MANUAL_WINDOW_MS
        is HistorySource.Ai -> (head.source as? HistorySource.Ai)?.let { it.conversationId == conversationId && it.messageId == messageId } == true
        HistorySource.Replace, HistorySource.ExternalApp, HistorySource.External, HistorySource.Initial, HistorySource.Restore -> false
    }

    /** 记一版；[bytes] 为 null 表示全文对象已经在了。 */
    private fun keep(key: String, path: String, head: Head, bytes: ByteArray?) {
        if (bytes != null && !store(head.sha, bytes)) return
        if (lastSha(key) != head.sha) append(key, HistoryEntry.Version(head.at, path, head.source, head.sha), sync = true)
    }

    /** 存全文对象；空间不够时不存（返回 false），历史暂停。[force]：加锁时的永久版本不受剩余空间门槛限制。 */
    private fun store(sha: String, bytes: ByteArray, force: Boolean = false): Boolean {
        val target = objectOf(sha)
        if (target.exists()) return true
        objects.mkdirs()
        if (!force && freeSpace(objects) < MIN_FREE_BYTES) {
            paused = true
            return false
        }
        try {
            val temp = File(objects, ".$sha.tmp")
            fastGzip(temp.outputStream()).use { it.write(bytes) }
            if (!temp.renameTo(target)) throw IOException("Cannot store a version")
        } catch (error: IOException) {
            paused = true
            throw error
        }
        paused = false
        return true
    }

    private fun objectOf(sha: String) = File(objects, "$sha.gz")

    /** 锁现在引用的全文（整章锁的底稿、段落锁加锁时的全文）。 */
    private fun lockShas(table: LockTableData): Set<String> =
        table.locks.values.flatMap { locks -> listOfNotNull(locks.chapter?.sha) + locks.passages.map { it.sha } }.toSet()

    private fun trashSlot(key: String, now: Long, extension: String): File {
        val folder = File(trash, key).apply { mkdirs() }
        var name = "$now.$extension"
        var n = 0
        while (File(folder, name).exists()) name = "$now-${++n}.$extension"
        return File(folder, name)
    }

    /** [root] 下要记的普通文件（不跟随软链），带相对路径。 */
    private fun trackedFiles(root: File): List<Pair<File, String>> = root.walkTopDown()
        .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
        .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        .mapNotNull { file -> relative(file)?.takeIf(::tracks)?.let { file to it } }
        .toList()

    private fun lastSha(key: String): String? {
        if (key !in lastShas) {
            lastShas[key] = readList(key).lastOrNull { it !is HistoryEntry.Renamed }?.let { (it as? HistoryEntry.Version)?.sha }
        }
        return lastShas[key]
    }

    private fun append(key: String, entry: HistoryEntry, sync: Boolean) {
        lists.mkdirs()
        RandomAccessFile(File(lists, "$key.jsonl"), "rw").use { file ->
            val length = file.length()
            // 上次在半行处被杀：先补换行，免得这一行也跟着读不出来
            val torn = length > 0 && file.run { seek(length - 1); read() } != '\n'.code
            file.seek(length)
            file.write(((if (torn) "\n" else "") + JsonInstant.encodeToString(entry) + "\n").toByteArray())
            if (sync) file.fd.sync()
        }
        when (entry) {
            is HistoryEntry.Version -> lastShas[key] = entry.sha
            is HistoryEntry.Deleted -> lastShas[key] = null
            is HistoryEntry.Renamed -> Unit
        }
    }

    private fun rewriteList(key: String, entries: List<HistoryEntry>) {
        val temp = File(lists, ".$key.jsonl.tmp")
        temp.writeText(entries.joinToString("") { JsonInstant.encodeToString(it) + "\n" })
        if (!temp.renameTo(File(lists, "$key.jsonl"))) throw IOException("Cannot rewrite a version list")
        lastShas.remove(key)
    }

    private fun readList(key: String): List<HistoryEntry> {
        val file = File(lists, "$key.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { runCatching { JsonInstant.decodeFromString<HistoryEntry>(it) }.getOrNull() }
        }
    }

    private fun index(): Map<String, Tracked> = index ?: (
        runCatching { JsonInstant.decodeFromString<Map<String, Tracked>>(indexFile.readText()) }.getOrNull() ?: rebuildIndex()
        ).also { index = it }

    /** 索引丢了或坏了：每个清单最后记的路径上要是还有文件，就认回来（文件现在的内容算不知道谁写的）。 */
    private fun rebuildIndex(): Map<String, Tracked> {
        val claims = lists.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.mapNotNull { file ->
            val key = file.name.removeSuffix(".jsonl")
            readList(key).lastOrNull()?.takeIf { it !is HistoryEntry.Deleted && File(filesDir, it.path).isFile }?.let { key to it }
        }
        return claims.groupBy { it.second.path }.mapValues { (_, same) -> Tracked(same.maxBy { it.second.at }.first) }
    }

    private fun save(files: Map<String, Tracked>) {
        if (files == index && indexFile.exists()) return
        index = files
        dir.mkdirs()
        val temp = File(dir, ".index.json.tmp")
        temp.writeText(JsonInstant.encodeToString(files))
        if (!temp.renameTo(indexFile)) throw IOException("Cannot save the history index")
    }

    private fun relative(file: File): String? =
        if (contains(file)) file.path.substring(filesDir.path.length + 1).replace(File.separatorChar, '/') else null

    companion object {
        private const val MAX_BYTES = BookStore.MAX_TEXT_BYTES
        private const val MANUAL_WINDOW_MS = 10 * 60 * 1000L
        private const val GRACE_MS = 60 * 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val MIN_FREE_BYTES = 1024L * 1024 * 1024
        private const val TURN_MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val KEEP_LATEST = 20
        private const val DORMANT_MS = 30 * 60 * 1000L
        private const val ARCHIVE_MS = 7 * DAY_MS
        private const val MAX_NOTICES = 50
        private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt")

        /** 只记 md / txt；点开头的文件和目录、`node_modules` 不记，`.book/` 里只记平行稿。 */
        fun tracks(path: String): Boolean {
            if (path.substringAfterLast('.', "").lowercase() !in TEXT_EXTENSIONS) return false
            val rest = path.removePrefix("${BookStore.BRANCHES_DIR}/")
            return rest.split('/').none { it.startsWith(".") || it == "node_modules" }
        }

        /**
         * 要留的版本：最近 20 版（按记录先后，不看时间，手机时钟跳了也不丢）、24 小时内全部、
         * 7 天内每小时最后一版、更早的每天最后一版（按本地日期）。
         */
        internal fun thin(versions: List<HistoryEntry.Version>, now: Long, pinned: Set<String> = emptySet()): Set<HistoryEntry.Version> {
            val kept = versions.takeLast(KEEP_LATEST).toMutableSet()
            versions.filterTo(kept) { it.sha in pinned }
            val zone = TimeZone.getDefault()
            versions.withIndex().groupBy { (index, version) ->
                val age = now - version.at
                val local = version.at + zone.getOffset(version.at)
                when {
                    age < DAY_MS -> "all:$index"
                    age < 7 * DAY_MS -> "hour:${local / HOUR_MS}"
                    else -> "day:${local / DAY_MS}"
                }
            }.values.mapTo(kept) { it.last().value }
            return kept
        }

        fun newKey(): String = Uuid.random().toHexString()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun fastGzip(output: OutputStream): GZIPOutputStream = object : GZIPOutputStream(output) {
            init {
                def.setLevel(Deflater.BEST_SPEED)
            }
        }
    }
}

/** 回收站里的一个文件：[key]/[name] 定位，[path] 是删掉前的路径。 */
@Serializable
data class TrashedFile(val key: String, val name: String, val path: String, val at: Long, val source: HistorySource, val size: Long)

/** 加锁、删版本被拒（不是读写出错）：[reason] 给界面选文案。 */
class LockRefusedException(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason {
        /** 选中的全是空行。 */
        EMPTY,

        /** 少于 [BookLocks.MIN_CHARS] 个字。 */
        TOO_SHORT,

        /** 和已有的段落锁重叠。 */
        OVERLAP,

        /** 不是作品记录的 md / txt 文件。 */
        NOT_TRACKED,

        /** 超过历史能记的大小。 */
        TOO_LARGE,

        /** 这一版是锁正在用的底稿。 */
        VERSION_IN_USE,
    }
}

/** 扫描发现的一处改动：[beforeSha] 改前（扫描时的 head），[afterSha] 改后（文件被删时为 null，[trashed] 是回收站里的名字）。 */
data class ScanChange(val key: String, val path: String, val beforeSha: String?, val afterSha: String?, val trashed: String?)

/** 扫描后发现碰到锁的一处改动：[passages] 是找不到了的段落锁。 */
data class LockFinding(
    val key: String,
    val path: String,
    val locks: FileLocks,
    val passages: List<PassageLock>,
    val beforeSha: String?,
    val afterSha: String?,
    val trashed: String?,
    val restorable: Boolean,
)
