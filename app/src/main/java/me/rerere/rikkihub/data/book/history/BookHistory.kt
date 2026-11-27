package me.rerere.rikkihub.data.book.history

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.workspace.WorkspaceChange
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

private const val TAG = "BookHistory"

/** 一版内容是谁写的。 */
@Serializable
sealed interface HistorySource {
    /** 自己在 App 或 Web 里改的（编辑器、文件管理器）；没有标注的写入都算这个。 */
    @Serializable
    @SerialName("manual")
    data object Manual : HistorySource

    /** 对话里的模型（工具调用）；子代理的写入算在父消息上。 */
    @Serializable
    @SerialName("ai")
    data class Ai(val conversationId: String, val messageId: String, val toolCallId: String? = null) : HistorySource {
        val turn: TurnKey get() = TurnKey(conversationId, messageId)
    }

    @Serializable
    @SerialName("replace")
    data object Replace : HistorySource

    /** 别的 App 经系统文件选择器写的。 */
    @Serializable
    @SerialName("external_app")
    data object ExternalApp : HistorySource

    /** 从历史或回收站还原。 */
    @Serializable
    @SerialName("restore")
    data object Restore : HistorySource

    /** 历史没看到的改动：命令行、终端、CLI，或历史出错期间的写入。 */
    @Serializable
    @SerialName("external")
    data object External : HistorySource

    /** 开始记历史时文件就是这样。 */
    @Serializable
    @SerialName("initial")
    data object Initial : HistorySource
}

/**
 * 只给定稿锁看的放行，和来源一样标在协程上（[BookHistory.lockElement]），不进历史、不写进任何文件：
 * [restore] 系统自动放回锁定内容；[passages] 全书替换里你勾选了这几处段落锁的命中，按手写跟随；
 * [moveOut] App 里确认过的移出作品（移动成功后解锁）；[foreign] 网页端或别的 App 发起的删除（记提醒）。
 */
data class BookLockContext(
    val restore: Boolean = false,
    val passages: Set<String> = emptySet(),
    val moveOut: Boolean = false,
    val foreign: Boolean = false,
)

/** 一个文件版本清单里的一行。 */
@Serializable
sealed interface HistoryEntry {
    val at: Long
    val path: String
    val source: HistorySource

    /** 这时文件的全文是 [sha] 这个对象。 */
    @Serializable
    @SerialName("version")
    data class Version(override val at: Long, override val path: String, override val source: HistorySource, val sha: String) : HistoryEntry

    /** 文件被删，整个移进了这个文件的回收站，文件名 [trash]。 */
    @Serializable
    @SerialName("deleted")
    data class Deleted(override val at: Long, override val path: String, override val source: HistorySource, val trash: String) : HistoryEntry

    /** 从 [from] 改名或移到了 [path]。 */
    @Serializable
    @SerialName("renamed")
    data class Renamed(override val at: Long, override val path: String, override val source: HistorySource, val from: String) : HistoryEntry
}

/**
 * 作品的章节版本史（点子 8）：AI、批量操作、删除、移动改动作品文件之前自动留底。
 *
 * 只管作品模式的工作区（[setBooks]）；经 [me.rerere.workspace.WorkspaceChanges] 收到进程内的写入，按绝对路径认是哪本书。
 * 命令行、CLI、终端、外部 App 的改动靠前后扫描（[watching]、[scan]、[scanAfter]）。
 * 谁写的由调用方用 [sourceElement] 标在协程上，没标的算 [HistorySource.Manual]。
 * 历史出错一律放行：记日志，正文照常写。
 */
class BookHistory(
    private val scope: CoroutineScope,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /** 单调时钟（App 里是开机以来的毫秒）：清理间隔和墙钟跳变都按它判断。 */
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stores = ConcurrentHashMap<String, BookHistoryStore>()

    private val _lockChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** 锁表变了的作品（工作区 id）：目录、编辑器、对话据此刷新。 */
    val lockChanges: SharedFlow<String> = _lockChanges.asSharedFlow()

    private val _turnChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** 审阅记录变了的作品（工作区 id）：对话卡片、作品页横幅据此刷新。 */
    val turnChanges: SharedFlow<String> = _turnChanges.asSharedFlow()

    /** 每一轮碰过哪些书（收尾只处理这些；进程重启后丢了由补收尾接上）。 */
    private val touched = ConcurrentHashMap<TurnKey, MutableSet<String>>()
    private val finishing = ConcurrentHashMap<String, Mutex>()
    private val cleanedAt = ConcurrentHashMap<String, Long>()

    /** 这条回复正在生成吗（App 启动时接上 ChatService）：在生成的不收尾、不判已删除。 */
    @Volatile
    var isGenerating: (conversationId: String, messageId: String) -> Boolean = { _, _ -> false }

    /** 一个对话里这些回复现在在不在（App 启动时接上；没接上时不按回复在不在归档）。 */
    @Volatile
    var presence: (suspend (conversationId: String, messageIds: List<String>) -> Map<String, MessagePresence>)? = null

    @Volatile
    private var books: Map<String, BookHistoryStore> = emptyMap()

    /**
     * 放回锁定内容用的写入（App 启动时接上 BookStore）：读这个文件现在的文字，交给 compute 算出要写的，
     * 带版本号写回（来源记成还原、带放回的放行）；compute 返回 null 或稿子又变了时不写，返回 false。
     */
    @Volatile
    var writeBack: (suspend (workspaceId: String, path: String, compute: (String) -> String?) -> Boolean)? = null

    /** 现在开着作品模式的工作区 id → 它的 FILES 目录。同一本书一直用同一个 store（同一把锁）。 */
    fun setBooks(filesDirs: Map<String, File>) {
        books = filesDirs.mapValues { (id, dir) ->
            stores.getOrPut(id) {
                BookHistoryStore(dir.canonicalFile, clock, freeSpace, onLocksChanged = { _lockChanges.tryEmit(id) }, onTurnChanged = { turn ->
                    touched.getOrPut(turn) { ConcurrentHashMap.newKeySet() }.add(id)
                    _turnChanges.tryEmit(id)
                })
            }
        }
    }

    /** 工作区删除：历史目录随工作区目录一起删，这里只丢掉内存里的。 */
    fun forget(workspaceId: String) {
        stores.remove(workspaceId)
        cleanedAt.remove(workspaceId)
        touched.values.forEach { it.remove(workspaceId) }
        books = books - workspaceId
    }

    fun isBook(workspaceId: String): Boolean = workspaceId in books

    /**
     * 写入钩子。先查定稿锁：拒绝（[BookLockedException]）原样抛给写入方，锁自己出了别的错只记日志、放行；
     * 再留底：出错一律放行。删除时锁定的文件要连锁进回收站，进不去也是拒绝。
     */
    fun onChange(change: WorkspaceChange) {
        val raw = source.get()
        val context = lockContext.get()
        val source = raw ?: HistorySource.Manual
        lockGuarded {
            when (change) {
                is WorkspaceChange.Overwrite -> storeOf(change.file)?.checkOverwrite(change.file, change.content, raw, context)
                is WorkspaceChange.Moving -> storeOf(change.from)?.let { book -> book.checkMoving(change.from, storeOf(change.to) === book, context) }
                is WorkspaceChange.Delete -> storeOf(change.file)?.delete(change.file, source, foreign = context?.foreign == true)
                is WorkspaceChange.Written, is WorkspaceChange.Created, is WorkspaceChange.Moved -> Unit
            }
        }
        guarded {
            when (change) {
                is WorkspaceChange.Overwrite -> storeOf(change.file)?.overwrite(change.file, change.content, source, restore = context?.restore == true)
                is WorkspaceChange.Written -> storeOf(change.file)?.written(change.file, change.content, source)
                is WorkspaceChange.Created -> storeOf(change.file)?.created(change.file, change.content, source)
                is WorkspaceChange.Moved -> storeOf(change.from)?.let { book ->
                    book.moved(change.from, change.to.takeIf { storeOf(it) === book }, source)
                }
                is WorkspaceChange.Delete, is WorkspaceChange.Moving -> Unit
            }
        }
    }

    // ---- 扫描 ------------------------------------------------------------------------------------

    /** 扫一遍历史外的改动（回到前台、打开作品、终端打开时）。 */
    suspend fun scan(workspaceId: String) {
        val store = books[workspaceId] ?: return
        enforce(workspaceId, store, scanSafely(store, HistorySource.External), HistorySource.External)
    }

    /** 扫所有作品、补收尾，并给到期的作品做一次清理（启动、回到前台）。 */
    suspend fun scanAll() {
        for ((id, store) in books) {
            enforce(id, store, scanSafely(store, HistorySource.External), HistorySource.External)
            verifyAll(id, store)
            finishOpenTurns(store)
            val now = monotonic()
            val last = cleanedAt[id]
            if (last == null || now - last >= CLEANUP_INTERVAL_MS) {
                cleanedAt[id] = now
                io { guarded { store.cleanup(LIMIT_BYTES) } }
                ageTurns(store)
            }
        }
    }

    /**
     * 一次命令行或 CLI 回合：动手前扫一遍（每个文件现在的内容都存成对象），结束后再扫，
     * 改动算在这一轮对话上（只到“轮”，不认是哪次工具调用），不在对话里的算外部修改。
     */
    suspend fun <T> watching(workspaceId: String, onLocks: (List<LockReport>) -> Unit = {}, block: suspend () -> T): T {
        val store = books[workspaceId] ?: return block()
        val ai = (source.get() as? HistorySource.Ai)?.copy(toolCallId = null)
        var watch: BookHistoryStore.Watch? = null
        try {
            watch = store.beginWatch(ai?.turn)
            enforce(workspaceId, store, scanSafely(store, HistorySource.External, attribute = true), HistorySource.External)
            return block()
        } finally {
            withContext(NonCancellable) {
                // 同一本书上同时还有别的轮次的命令、或者开着终端：改动分不清是谁的，不归到这一轮（锁也按外部来源只提醒）
                val reason = watch?.let { store.unattributed(it) }
                val source = ai?.takeIf { reason == null } ?: HistorySource.External
                val changes = scanSafely(store, source, attribute = true)
                val late = watch?.let { store.endWatch(it) }
                if (ai != null && changes.isNotEmpty()) (reason ?: late)?.let { guarded { store.markUnattributed(ai.turn, it) } }
                // 这一轮对话里的命令改了锁定内容：放回；别的来源只提醒
                val reports = enforce(workspaceId, store, changes, source)
                if (reports.isNotEmpty()) runCatching { onLocks(reports) }
            }
        }
    }

    /** 终端这类开着就可能随时改文件的（打开前由调用方 [scan]）：[awaitDone] 返回（退出）后再扫。 */
    fun scanAfter(workspaceId: String, awaitDone: suspend () -> Unit) {
        val store = books[workspaceId] ?: return
        // 开着的终端随时可能改文件：期间的命令改动不归到任何一轮
        store.shellOpened()
        scope.launch {
            try {
                runCatching { awaitDone() }
            } finally {
                store.shellClosed()
            }
            scan(workspaceId)
        }
    }

    /**
     * 扫描之后核对锁（扫描已释放历史锁）。这一轮对话（[HistorySource.Ai]）里的改动自动放回：整章换回底稿、
     * 段落锁只放回锁定的行、删掉的从回收站放回；改前改后不都在历史里时不放回，只提醒。外部 App 改段落锁视同手写，锁跟着走。
     * 其他来源只提醒（可能是你自己改的）。返回给工具结果的说明。
     */
    private suspend fun enforce(workspaceId: String, store: BookHistoryStore, changes: List<ScanChange>, source: HistorySource): List<LockReport> {
        if (changes.isEmpty()) return emptyList()
        check(!Thread.holdsLock(store)) { "Locked content must be put back outside the history lock" }
        val findings = io { runCatching { store.lockFindings(changes) }.getOrDefault(emptyList()) }
        val reports = ArrayList<LockReport>()
        for (finding in findings) {
            try {
                reports += handle(workspaceId, store, finding, source) ?: continue
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Could not put locked content back", error)
                io { store.noticeFinding(finding, LockNotice.Kind.NOT_RESTORED, source) }
            }
        }
        return reports
    }

    private suspend fun handle(workspaceId: String, store: BookHistoryStore, finding: LockFinding, source: HistorySource): LockReport? {
        val chapter = finding.locks.chapter != null
        val heads = finding.passages.map { it.lines.firstOrNull().orEmpty().trim().take(20) }
        fun report(restored: Boolean, noSpace: Boolean = false) = LockReport(finding.path, chapter, restored, heads, noSpace)
        if (source == HistorySource.ExternalApp && !chapter && finding.trashed == null && finding.beforeSha != null && finding.afterSha != null) {
            io { store.followExternal(finding.key, finding.beforeSha, finding.afterSha) }
            return null
        }
        if (source !is HistorySource.Ai) {
            io { store.noticeFinding(finding, LockNotice.Kind.CHANGED, source) }
            return null
        }
        if (!finding.restorable) {
            io { store.noticeFinding(finding, LockNotice.Kind.NO_SPACE, source) }
            return report(restored = false, noSpace = true)
        }
        val restored = when {
            finding.trashed != null -> io { store.restore(finding.key, finding.trashed) } != null
            chapter -> {
                val locked = io { store.read(finding.locks.chapter!!.sha) }?.decodeToString() ?: return report(false).also {
                    io { store.noticeFinding(finding, LockNotice.Kind.BASE_MISSING, source) }
                }
                writeBackIfUnchanged(workspaceId, store, finding) { locked }
            }
            else -> {
                val before = io { store.read(finding.beforeSha!!) }!!.decodeToString()
                writeBackIfUnchanged(workspaceId, store, finding) { now ->
                    finding.passages.fold(now as String?) { text, passage ->
                        text?.let { BookLocks.putBack(BookLocks.Doc(before), BookLocks.Doc(it), passage) }
                    }
                }
            }
        }
        io { store.noticeFinding(finding, if (restored) LockNotice.Kind.RESTORED else LockNotice.Kind.NOT_RESTORED, source) }
        if (restored) io { guarded { store.turnRestored(source.turn, finding.key) } }
        return report(restored)
    }

    /** 稿子还是扫描时看到的样子才写回（中间又被改了就不动）。 */
    private suspend fun writeBackIfUnchanged(workspaceId: String, store: BookHistoryStore, finding: LockFinding, compute: (String) -> String?): Boolean {
        val writer = writeBack ?: return false
        val after = finding.afterSha?.let { io { store.read(it) } }?.decodeToString()?.let(BookLocks::normalize) ?: return false
        return writer(workspaceId, finding.path) { current ->
            if (BookLocks.normalize(current) != after) null else compute(after)
        }
    }

    /** 全量核对：每个有锁的文件读一遍（在历史锁外），对不上的记提醒，底稿丢了但文件没变的补存。 */
    private suspend fun verifyAll(workspaceId: String, store: BookHistoryStore) {
        val targets = io { runCatching { store.lockTargets() }.getOrDefault(emptyList()) }
        for ((path, key, locks) in targets) {
            val file = store.resolve(path) ?: continue
            val bytes = io { runCatching { file.readBytes() }.getOrNull() } ?: continue
            io { guarded { store.verified(key, path, locks, bytes) } }
        }
    }

    /** 打开作品、重新打开作品模式后：扫描并全量核对锁。 */
    suspend fun verify(workspaceId: String) {
        val store = books[workspaceId] ?: return
        enforce(workspaceId, store, scanSafely(store, HistorySource.External), HistorySource.External)
        verifyAll(workspaceId, store)
        finishOpenTurns(store)
    }

    /** 外部 App 要写 [file] 了：先把现在的内容存一版（同步，系统文件选择器的线程上）。 */
    fun beforeExternalWrite(file: File) {
        guarded { storeOf(file)?.checkpoint(file, null, HistorySource.ExternalApp) }
    }

    /** 外部 App 写完 [file]：只扫这一个文件。 */
    fun afterExternalWrite(file: File) {
        val store = storeOf(file) ?: return
        val id = books.entries.firstOrNull { it.value === store }?.key ?: return
        scope.launch(Dispatchers.IO) {
            val changes = runCatching { store.scan(HistorySource.ExternalApp, only = file) }.getOrDefault(emptyList())
            enforce(id, store, changes, HistorySource.ExternalApp)
        }
    }

    // ---- 界面用 ------------------------------------------------------------------------------------

    /** 编辑器里要应用推敲、再造、去八股、节拍了：把应用前的稿子 [text] 存一版。 */
    suspend fun checkpoint(workspaceId: String, path: String, text: String) {
        val store = books[workspaceId] ?: return
        val file = store.resolve(path) ?: return
        io { store.checkpoint(file, text.toByteArray(), HistorySource.Manual) }
    }

    /** 同上，文件管理器里按绝对路径打开的文件（可能是某本书里的）。 */
    suspend fun checkpoint(file: File, text: String) {
        val canonical = io { file.canonicalFile }
        val store = storeOf(canonical) ?: return
        io { store.checkpoint(canonical, text.toByteArray(), HistorySource.Manual) }
    }

    suspend fun entries(workspaceId: String, path: String): List<HistoryEntry> =
        books[workspaceId]?.let { store -> io { store.entries(path) } }.orEmpty()

    /** 现在的稿子是谁写的（还不知道就是 null）。 */
    suspend fun currentWriter(workspaceId: String, path: String): HistorySource? =
        books[workspaceId]?.let { store -> io { store.currentWriter(path) } }

    suspend fun read(workspaceId: String, sha: String): String? =
        books[workspaceId]?.let { store -> io { store.read(sha) } }?.decodeToString()

    suspend fun trashed(workspaceId: String): List<TrashedFile> =
        books[workspaceId]?.let { store -> io { store.trashed() } }.orEmpty()

    /** 放回原处，返回放回的路径。 */
    suspend fun restoreTrashed(workspaceId: String, key: String, name: String): String? =
        books[workspaceId]?.let { store -> io { store.restore(key, name) } }

    suspend fun deleteTrashed(workspaceId: String, key: String, name: String) {
        books[workspaceId]?.let { store -> io { store.deleteTrashed(key, name) } }
    }

    suspend fun emptyTrash(workspaceId: String) {
        books[workspaceId]?.let { store -> io { store.emptyTrash() } }
    }

    suspend fun deleteVersion(workspaceId: String, path: String, version: HistoryEntry.Version) {
        books[workspaceId]?.let { store -> io { store.deleteVersion(path, version) } }
    }

    suspend fun clear(workspaceId: String) {
        books[workspaceId]?.let { store -> io { store.clear() } }
    }

    suspend fun usage(workspaceId: String): Long = books[workspaceId]?.let { store -> io { store.usage() } } ?: 0L

    fun paused(workspaceId: String): Boolean = books[workspaceId]?.paused == true

    // ---- 逐处审阅 ------------------------------------------------------------------------------

    /**
     * 一轮结束（生成任务完全结束之后投递）：在应用作用域里按会话串行收尾这一轮碰过的书，生成任务不等它。
     * 出错只记日志，交给补收尾（[scanAll]、[verify]，规则相同）。
     */
    fun finishTurn(conversationId: String, messageId: String) = finish(listOf(TurnKey(conversationId, messageId)))

    /** 一个对话的生成任务结束了：收尾这个对话里还没收尾的轮次（正在生成的那条跳过）。 */
    fun finishConversation(conversationId: String) = finish(touched.keys.filter { it.conversationId == conversationId })

    private fun finish(turns: List<TurnKey>) {
        val work = turns.associateWith { touched[it]?.toList().orEmpty() }.filterValues { it.isNotEmpty() }
        if (work.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for ((turn, ids) in work) {
                finishing.getOrPut(turn.conversationId) { Mutex() }.withLock {
                    for (id in ids) {
                        val store = books[id] ?: continue
                        guarded { store.finishTurn(turn) { isGenerating(turn.conversationId, turn.messageId) } }
                    }
                }
            }
        }
    }

    /** 补收尾：没收尾的轮次（被杀、收尾出错），跳过正在生成的。 */
    private suspend fun finishOpenTurns(store: BookHistoryStore) {
        val open = io { runCatching { store.openTurns() }.getOrDefault(emptyList()) }
        for (turn in open) {
            finishing.getOrPut(turn.conversationId) { Mutex() }.withLock {
                io { guarded { store.finishTurn(turn) { isGenerating(turn.conversationId, turn.messageId) } } }
            }
        }
    }

    /** 每日清理时按回复在不在、按年龄归档或删掉审阅记录；墙钟跳过的这一次不按年龄处理。 */
    private suspend fun ageTurns(store: BookHistoryStore) {
        val records = io { runCatching { store.turns() }.getOrDefault(emptyList()) }.filter { it.finishedAt != null && it.archivedAt == null }
        val presence = probe(records)
        val wall = clock()
        val mono = monotonic()
        val last = io { runCatching { store.turnClock() }.getOrNull() }
        // 同一次开机（单调时钟没归零）里，墙钟比单调时钟多走了一天以上：时钟往前跳过
        val trustAge = last == null || mono < last.second || (wall - last.first) - (mono - last.second) <= JUMP_MS
        io {
            guarded {
                store.setTurnClock(wall, mono)
                store.ageTurns(wall, presence, trustAge)
            }
        }
    }

    private suspend fun probe(records: List<TurnRecord>): Map<TurnKey, MessagePresence> {
        val presence = presence ?: return emptyMap()
        return records.filterNot { isGenerating(it.conversationId, it.messageId) }.groupBy { it.conversationId }.flatMap { (conversationId, list) ->
            val found = runCatching { presence(conversationId, list.map { it.messageId }) }.getOrDefault(emptyMap())
            list.mapNotNull { record -> found[record.messageId]?.let { record.key to it } }
        }.toMap()
    }

    /** 回复现在在不在（作品页横幅只查最新一条用）。 */
    suspend fun presenceOf(conversationId: String, messageId: String): MessagePresence? =
        presence?.let { runCatching { it(conversationId, listOf(messageId)) }.getOrNull()?.get(messageId) }

    /** 这本书的审阅记录（已收尾的）。 */
    suspend fun turns(workspaceId: String): List<TurnRecord> =
        books[workspaceId]?.let { store -> io { store.turns() } }.orEmpty().filter { it.finishedAt != null }

    suspend fun turn(workspaceId: String, conversationId: String, messageId: String): TurnRecord? =
        books[workspaceId]?.let { store -> io { store.turn(TurnKey(conversationId, messageId)) } }?.takeIf { it.finishedAt != null }

    /** 一个对话的审阅记录，按回复 id；记录一变就重算（对话内卡片用）。 */
    fun conversationTurns(workspaceId: String, conversationId: String): kotlinx.coroutines.flow.Flow<Map<String, TurnRecord>> =
        _turnChanges.filter { it == workspaceId }.onStart { emit(workspaceId) }.map {
            turns(workspaceId).filter { it.conversationId == conversationId }.associateBy { it.messageId }
        }.distinctUntilChanged()

    /** 记录一变就推这本书现在的全部记录（作品页横幅、“最近的改动”）。 */
    fun turnFlow(workspaceId: String): kotlinx.coroutines.flow.Flow<List<TurnRecord>> =
        _turnChanges.filter { it == workspaceId }.onStart { emit(workspaceId) }.map { turns(workspaceId) }.distinctUntilChanged()

    suspend fun updateTurn(workspaceId: String, conversationId: String, messageId: String, transform: (TurnRecord) -> TurnRecord): TurnRecord? =
        books[workspaceId]?.let { store -> io { store.updateTurn(TurnKey(conversationId, messageId), transform) } }

    suspend fun muted(workspaceId: String): Set<String> = books[workspaceId]?.let { store -> io { store.muted() } }.orEmpty()

    /** 作品页横幅上的一轮：只看最近一轮待审、没静音的；点过 × 或回复不在当前分支上就不显示（App 与 Web 共用）。 */
    suspend fun bannerTurn(workspaceId: String): TurnRecord? {
        val muted = muted(workspaceId)
        val latest = turns(workspaceId).filter { it.status == TurnStatus.PENDING && it.conversationId !in muted }.maxByOrNull { it.finishedAt!! }
        return latest?.takeIf { it.seenAt == null }?.takeIf { record ->
            presenceOf(record.conversationId, record.messageId).let { it == null || it == MessagePresence.CURRENT }
        }
    }

    /** 不再提示的对话，变了就推。 */
    fun mutedFlow(workspaceId: String): kotlinx.coroutines.flow.Flow<Set<String>> =
        _turnChanges.filter { it == workspaceId }.onStart { emit(workspaceId) }.map { muted(workspaceId) }.distinctUntilChanged()

    suspend fun mute(workspaceId: String, conversationId: String, muted: Boolean) {
        val store = books[workspaceId] ?: return
        io { store.mute(conversationId, muted) }
        _turnChanges.tryEmit(workspaceId)
    }

    /** 审阅写回前强制存一版（快照内容）；存不下返回 null。 */
    suspend fun keepForReview(workspaceId: String, path: String, bytes: ByteArray): String? =
        books[workspaceId]?.let { store -> io { store.keepForReview(path, bytes) } }

    /** 历史键最后一次删除时进了回收站的名字（还在回收站里时）。 */
    suspend fun trashNameOf(workspaceId: String, key: String): String? = books[workspaceId]?.let { store -> io { store.trashNameOf(key) } }

    /** 历史键现在的路径。 */
    suspend fun pathOf(workspaceId: String, key: String): String? = books[workspaceId]?.let { store -> io { store.pathOf(key) } }

    /** 这本书上有命令正在进行（历史页显示“命令进行中”）。 */
    fun commandRunning(workspaceId: String): Boolean = books[workspaceId]?.commandRunning == true

    // ---- 定稿锁 --------------------------------------------------------------------------------

    /** 加整章锁；[bytes] 是调用方在存储根锁里按版本号读出的现在的内容。返回加锁前的锁（撤销用）。 */
    fun lockChapter(workspaceId: String, path: String, bytes: ByteArray, web: Boolean): FileLocks? {
        val store = books[workspaceId] ?: throw IllegalStateException("Not a book")
        val file = store.resolve(path) ?: throw IllegalArgumentException("Invalid path")
        return store.lockChapter(file, bytes, web)
    }

    /** 给 [rows]（归一后的行号）加一处段落锁，同 [lockChapter]。 */
    fun lockPassage(workspaceId: String, path: String, bytes: ByteArray, rows: IntRange, web: Boolean): Pair<PassageLock, FileLocks?> {
        val store = books[workspaceId] ?: throw IllegalStateException("Not a book")
        val file = store.resolve(path) ?: throw IllegalArgumentException("Invalid path")
        return store.lockPassage(file, bytes, rows, web)
    }

    /** 解锁整章（[chapter]）和/或这几处段落锁；返回解锁前的锁（撤销用）。只在 App 里调用。 */
    suspend fun unlock(workspaceId: String, path: String, chapter: Boolean, passageIds: Set<String>): FileLocks? =
        books[workspaceId]?.let { store -> io { store.unlock(path, chapter, passageIds) } }

    /**
     * 撤销加锁或解锁：换回 [previous]（原范围、原锚点）。先核对现在的稿子：对不上（之后改过）就不恢复一把已经失效的锁，返回 false。
     */
    suspend fun restoreLocks(workspaceId: String, path: String, previous: FileLocks?): Boolean {
        val store = books[workspaceId] ?: return false
        if (previous != null && !previous.isEmpty) {
            val bytes = store.resolve(path)?.let { io { runCatching { it.readBytes() }.getOrNull() } } ?: return false
            val doc = BookLocks.Doc(bytes.decodeToString())
            val chapterOk = previous.chapter == null || BookLocks.normalize(io { store.read(previous.chapter.sha) }?.decodeToString() ?: return false) == doc.text
            val passagesOk = previous.passages.all { it.dormantSince != null || BookLocks.locate(it, doc) != null }
            if (!chapterOk || !passagesOk) return false
        }
        io { store.restoreLocks(path, previous) }
        return true
    }

    /** 提醒里的“知道了”：关掉，并把这个文件现在的样子记成已处理。只在 App 里调用。 */
    suspend fun acknowledge(workspaceId: String, noticeId: String) {
        val store = books[workspaceId] ?: return
        val notice = io { store.notices() }.firstOrNull { it.id == noticeId } ?: return
        val sha = store.resolve(notice.path)?.let { io { runCatching { BookHistoryStore.sha256(it.readBytes()) }.getOrNull() } }
        io { store.dismiss(noticeId, sha) }
    }

    /** 这本书有效的锁，按路径（不是作品为空）。 */
    suspend fun locks(workspaceId: String): Map<String, FileLocks> =
        books[workspaceId]?.let { store -> io { store.locks() } }.orEmpty()

    suspend fun locksOf(workspaceId: String, path: String): FileLocks? =
        books[workspaceId]?.let { store -> io { store.locksOf(path) } }

    /** 这个对话的回复动了锁定内容留下的提醒，按回复 id；锁或提醒一变就重算（对话内卡片用）。 */
    fun conversationNotices(workspaceId: String, conversationId: String): kotlinx.coroutines.flow.Flow<Map<String, List<LockNotice>>> =
        _lockChanges.filter { it == workspaceId }.onStart { emit(workspaceId) }.map {
            notices(workspaceId).filter { it.conversationId == conversationId && it.messageId != null }.groupBy { it.messageId!! }
        }.distinctUntilChanged()

    /** 永久版本（加锁时）的哈希：历史里标“加锁时 · 永久”。 */
    suspend fun pinned(workspaceId: String): Set<String> = books[workspaceId]?.let { store -> io { store.pinned() } }.orEmpty()

    /** 一个文件的锁，锁一变就推一次（编辑器订阅：别处加了锁，开着的编辑器立刻只读）。 */
    fun lockFlow(workspaceId: String, path: String): kotlinx.coroutines.flow.Flow<FileLocks?> =
        _lockChanges.filter { it == workspaceId }.onStart { emit(workspaceId) }.map { locksOf(workspaceId, path) }.distinctUntilChanged()

    /** 按绝对路径（文件管理器打开的文件）：属于哪本书的哪个文件。 */
    fun bookFileOf(file: File): Pair<String, String>? {
        val canonical = file.canonicalFile
        val (id, store) = books.entries.firstOrNull { it.value.contains(canonical) } ?: return null
        val path = store.relativePath(canonical) ?: return null
        return id to path
    }

    suspend fun notices(workspaceId: String): List<LockNotice> =
        books[workspaceId]?.let { store -> io { store.notices() } }.orEmpty()

    /**
     * 提醒里的“放回”：被删的从回收站放回；整章换回加锁时的样子；段落锁只放回锁定的行（以 [LockNotice.beforeSha] 为准）。
     * 放回前现在的稿子照例留一版。放回了就关掉这条提醒。App 与 Web 都可以调用。
     * [expected]：比较页上看到的稿子；稿子已经变了就抛版本冲突，不写。
     */
    suspend fun putBack(workspaceId: String, noticeId: String, expected: String? = null): Boolean {
        val store = books[workspaceId] ?: return false
        val notice = io { store.notices() }.firstOrNull { it.id == noticeId } ?: return false
        if (notice.kind == LockNotice.Kind.DELETED) {
            val slot = io { store.trashedSlot(notice.key) } ?: return false
            return (io { store.restore(notice.key, slot) } != null).also { if (it) io { store.dismiss(noticeId, null) } }
        }
        val locks = io { store.locksOf(notice.path) } ?: return false
        val writer = writeBack ?: return false
        val chapter = locks.chapter?.let { io { store.read(it.sha) } ?: return false }?.decodeToString()
        val before = notice.beforeSha?.let { io { store.read(it) } }?.decodeToString()
        val passages = locks.passages.filter { it.dormantSince == null }
        var changed = false
        val done = writer(workspaceId, notice.path) { now ->
            if (expected != null && BookLocks.normalize(now) != BookLocks.normalize(expected)) {
                changed = true
                return@writer null
            }
            chapter ?: before?.let { old ->
                // 还完好的段落不动，改了的放回；有一处放不回就整个不写
                passages.fold(BookLocks.normalize(now) as String?) { text, passage ->
                    when {
                        text == null -> null
                        BookLocks.locate(passage, BookLocks.Doc(text)) != null -> text
                        else -> BookLocks.putBack(BookLocks.Doc(old), BookLocks.Doc(text), passage)
                    }
                }
            }
        }
        if (changed) throw me.rerere.workspace.WorkspaceFileRevisionConflictException()
        if (done) io { store.dismiss(noticeId, null) }
        return done
    }

    /** 提醒里的“按现在的稿子重新锁定”；返回（路径，之前的锁）供撤销。只在 App 里调用。 */
    suspend fun relock(workspaceId: String, noticeId: String): Pair<String, FileLocks?>? {
        val store = books[workspaceId] ?: return null
        val notice = io { store.notices() }.firstOrNull { it.id == noticeId } ?: return null
        val file = store.resolve(notice.path) ?: return null
        val now = io { file.readBytes() }
        val before = notice.beforeSha?.let { io { store.read(it) } }
        val previous = io { store.relock(file, before, now) }
        io { store.dismiss(noticeId, BookHistoryStore.sha256(now)) }
        return notice.path to previous
    }

    /**
     * 提醒里的“保留这次修改（解锁）”：解开被改到的锁，把锁定段换成被改成的样子，别处（包括之后又改的字）不动；
     * 只有段落锁、认得出时这样做（[BookLocks.keepIn]）。认不出时，稿子还是系统放回后的样子（按内容核对，不看版本时间）才整章还原到被改成的样子。
     * 还原前现在的稿子照例留一版。返回（路径，之前的锁）供撤销；还原不了时返回 null，锁不动。只在 App 里调用。
     */
    suspend fun keepChange(workspaceId: String, noticeId: String): Pair<String, FileLocks?>? {
        val store = books[workspaceId] ?: return null
        val notice = io { store.notices() }.firstOrNull { it.id == noticeId } ?: return null
        val changed = notice.changedSha?.let { io { store.read(it) } }?.decodeToString() ?: return null
        val locks = io { store.locksOf(notice.path) } ?: return null
        val writer = writeBack ?: return null
        val moved = locks.passages.filter { passage -> BookLocks.locate(passage, BookLocks.Doc(changed)) == null }
        val ids = moved.map { it.id }.toSet()
        val before = notice.beforeSha?.let { io { store.read(it) } }?.decodeToString()
        // 系统放回后的样子（与 putBack 同一算法）：稿子还是它，才可以整章换成被改的样子；之后又改过就不整章盖
        val restored = locks.chapter?.let { io { store.read(it.sha) } ?: return null }?.decodeToString()?.let(BookLocks::normalize)
            ?: before?.let { old ->
                locks.passages.filter { it.dormantSince == null }.fold(BookLocks.normalize(changed) as String?) { text, passage ->
                    when {
                        text == null -> null
                        BookLocks.locate(passage, BookLocks.Doc(text)) != null -> text
                        else -> BookLocks.putBack(BookLocks.Doc(old), BookLocks.Doc(text), passage)
                    }
                }
            }
        val previous = io { store.unlock(notice.path, chapter = true, passageIds = ids) }
        val written = writer(workspaceId, notice.path) { now ->
            val swapped = before?.takeIf { locks.chapter == null && moved.isNotEmpty() }?.let { old ->
                moved.fold(BookLocks.normalize(now) as String?) { text, passage ->
                    text?.let { BookLocks.keepIn(BookLocks.Doc(old), BookLocks.Doc(changed), BookLocks.Doc(it), passage) }
                }
            }
            swapped ?: changed.takeIf { restored != null && BookLocks.normalize(now) == restored }
        }
        if (!written) {
            io { store.restoreLocks(notice.path, previous) }
            return null
        }
        io { store.dismiss(noticeId, null) }
        return notice.path to previous
    }

    /** 关掉一条提醒；[currentSha] 不为 null 时这个状态记成已处理。只在 App 里调用。 */
    suspend fun dismissNotice(workspaceId: String, id: String, currentSha: String?) {
        books[workspaceId]?.let { store -> io { store.dismiss(id, currentSha) } }
    }

    suspend fun markNotices(workspaceId: String, ids: Set<String>, seen: Boolean = false, delivered: Boolean = false) {
        books[workspaceId]?.let { store -> io { store.markNotices(ids, seen, delivered) } }
    }

    /** 这一章是否已经提示过“已锁定”（第一次问时返回 false 并记下）。 */
    suspend fun tipShown(workspaceId: String, path: String): Boolean =
        books[workspaceId]?.let { store -> io { store.tipShown(path) } } ?: true

    fun locksBroken(workspaceId: String): Boolean = books[workspaceId]?.locksBroken == true

    private val cliDigests = ConcurrentHashMap<String, Int>()

    /**
     * CLI 回合前要不要交代定稿锁：新会话的第一回合（[fresh]）、锁变了之后的第一回合、有这个对话还没交代的放回提醒时，
     * 返回现在的锁和这些提醒（提醒随即记成已交代）；都不需要时返回 null。
     */
    suspend fun cliLockNews(workspaceId: String, conversationId: String, fresh: Boolean): Pair<Map<String, FileLocks>, List<LockNotice>>? {
        val store = books[workspaceId] ?: return null
        val locks = io { store.locks() }
        val kinds = setOf(LockNotice.Kind.RESTORED, LockNotice.Kind.NOT_RESTORED, LockNotice.Kind.NO_SPACE)
        val notices = io { store.notices() }.filter { it.conversationId == conversationId && !it.delivered && it.kind in kinds }
        val digest = locks.hashCode()
        val changed = cliDigests.put(conversationId, digest) != digest
        if (!fresh && !changed && notices.isEmpty()) return null
        if (locks.isEmpty() && notices.isEmpty()) return null
        if (notices.isNotEmpty()) io { store.markNotices(notices.map { it.id }.toSet(), delivered = true) }
        return locks to notices
    }

    /** [file]（绝对路径）是不是整章锁定的章节（系统文件选择器同步问）。 */
    fun chapterLocked(file: File): Boolean {
        val canonical = file.canonicalFile
        val store = storeOf(canonical) ?: return false
        val path = store.relativePath(canonical) ?: return false
        return store.locksOf(path)?.chapter != null
    }

    /** 这本书有没有锁（组装提示词时同步问；出错按没有）。 */
    fun hasLocks(workspaceId: String): Boolean = runCatching { books[workspaceId]?.locks()?.isNotEmpty() == true }.getOrDefault(false)

    suspend fun reloadLocks(workspaceId: String) {
        books[workspaceId]?.let { store -> io { store.reloadLocks() } }
    }

    private fun storeOf(file: File): BookHistoryStore? = books.values.firstOrNull { it.contains(file) }

    private inline fun lockGuarded(block: () -> Unit) {
        try {
            block()
        } catch (refused: BookLockedException) {
            throw refused
        } catch (error: Exception) {
            lockFailed = true
            Log.w(TAG, "Book locks skipped a change", error)
        }
    }

    /** 定稿锁自己出过错（不是拒绝）：作品页提示“定稿锁暂时失效”。 */
    @Volatile
    var lockFailed: Boolean = false
        private set

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, "Version history skipped a change", error)
        }
    }

    /** 扫描出错只记日志，不挡命令和终端。 */
    private suspend fun scanSafely(store: BookHistoryStore, source: HistorySource, attribute: Boolean? = null): List<ScanChange> = io {
        try {
            store.scan(source, attribute = attribute)
        } catch (error: Exception) {
            Log.w(TAG, "Version history skipped a scan", error)
            emptyList()
        }
    }

    /** 用户在历史界面主动做的操作，出错照常抛给界面。 */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val JUMP_MS = 24 * 60 * 60 * 1000L
        private const val LIMIT_BYTES = 200L * 1024 * 1024

        private val source = ThreadLocal<HistorySource?>()
        private val lockContext = ThreadLocal<BookLockContext?>()

        /** 把这段协程里的作品写入带上定稿锁的放行 [value]。 */
        fun lockElement(value: BookLockContext): CoroutineContext.Element = lockContext.asContextElement(value)

        /** 没有协程的调用方（系统文件选择器的线程）：在 [block] 里带上 [value]。 */
        fun <T> withLockContext(value: BookLockContext, block: () -> T): T {
            val outer = lockContext.get()
            lockContext.set(value)
            try {
                return block()
            } finally {
                lockContext.set(outer)
            }
        }

        /** 把这段协程里的作品写入标成 [value]；能穿过 withContext 和各种锁。 */
        fun sourceElement(value: HistorySource): CoroutineContext.Element = source.asContextElement(value)

        fun currentSource(): HistorySource? = source.get()
    }
}

/** 扫描后放回或没放回的一处锁定内容（交给工具结果、CLI 回合）。[passages] 是被碰到的段落开头。 */
data class LockReport(val path: String, val chapter: Boolean, val restored: Boolean, val passages: List<String>, val noSpace: Boolean)
