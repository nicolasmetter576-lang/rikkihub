package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookManifestException
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterStatus
import me.rerere.rikkihub.data.book.history.BookHistory
import me.rerere.rikkihub.data.book.history.BookHistoryActions
import me.rerere.rikkihub.data.book.history.FileLocks
import me.rerere.rikkihub.data.book.history.LockNotice
import me.rerere.rikkihub.data.book.history.TurnRecord

data class BookUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    /** 结构文件读不了（坏了或版本更新）。 */
    val manifestError: BookManifestException.Reason? = null,
    val error: String? = null,
)

/** 作品页上的定稿锁：按路径的锁、待处理的提醒、锁表是否损坏。 */
data class BookLockState(
    val locks: Map<String, FileLocks> = emptyMap(),
    val notices: List<LockNotice> = emptyList(),
    val broken: Boolean = false,
)

/** 作品页上的审阅：横幅上的最近一轮（[banner]）与“最近的改动”列表；[titles] 会话 id → 标题。 */
data class BookTurnState(
    val banner: TurnRecord? = null,
    val recent: List<TurnRecord> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
)

/** 加锁、解锁之后的“撤销”：[previous] 是每个路径操作之前的锁。 */
data class LockUndo(val locked: Boolean, val count: Int, val previous: Map<String, FileLocks?>)

class BookVM(
    private val workspaceId: String,
    private val store: BookStore,
    private val history: BookHistory,
    private val actions: BookHistoryActions,
    private val conversationTitle: suspend (String) -> String? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(BookUiState())
    val state = _state.asStateFlow()
    private val _messages = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _locks = MutableStateFlow(BookLockState())
    val locks = _locks.asStateFlow()
    private val _turns = MutableStateFlow(BookTurnState())
    val turns = _turns.asStateFlow()
    private val _undo = MutableStateFlow<LockUndo?>(null)
    val undo = _undo.asStateFlow()
    /** 撤销时有锁已经对不上，没有恢复（界面提示）。 */
    private val _undoFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val undoFailed = _undoFailed.asSharedFlow()
    /** “保留这次修改”没做成（之后又改过、认不出锁定段）：提示先比较。 */
    private val _keepFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val keepFailed = _keepFailed.asSharedFlow()

    init {
        viewModelScope.launch { store.changes.filter { it == workspaceId }.collect { refresh() } }
        viewModelScope.launch { history.lockChanges.filter { it == workspaceId }.collect { refreshLocks() } }
        viewModelScope.launch {
            history.turnFlow(workspaceId).combine(history.mutedFlow(workspaceId)) { turns, muted -> turns to muted }
                .collectLatest { (turns, _) -> refreshTurns(turns) }
        }
        // 打开作品：扫描、核对锁，补收尾没收完的轮次
        viewModelScope.launch { runCatching { history.verify(workspaceId) } }
    }

    private suspend fun refreshTurns(turns: List<TurnRecord>) {
        val recent = turns.sortedByDescending { it.finishedAt }
        val banner = history.bannerTurn(workspaceId)
        val known = _turns.value.titles
        val titles = known + recent.map { it.conversationId }.distinct().filter { it !in known }
            .mapNotNull { id -> conversationTitle(id)?.let { id to it } }
        _turns.value = BookTurnState(banner, recent, titles)
    }

    /** 横幅上的 ×：这一轮不在横幅上提醒（仍是待审）。 */
    fun dismissBanner(record: TurnRecord) = viewModelScope.launch {
        history.updateTurn(workspaceId, record.conversationId, record.messageId) { it.copy(seenAt = System.currentTimeMillis()) }
    }

    fun refresh() {
        launch { store.open(workspaceId) }
        refreshLocks()
    }

    private fun refreshLocks() {
        viewModelScope.launch {
            _locks.value = BookLockState(history.locks(workspaceId), history.notices(workspaceId), history.locksBroken(workspaceId))
        }
    }

    /** 锁定这些章节（整章）；对着磁盘上现在的稿子。 */
    fun lockChapters(paths: List<String>) = lockWork(locked = true, paths) { path ->
        actions.lockChapter(workspaceId, path, store.read(workspaceId, path).revision)
    }

    fun unlockChapters(paths: List<String>) = lockWork(locked = false, paths) { path ->
        history.unlock(workspaceId, path, chapter = true, passageIds = emptySet())
    }

    fun unlockPassages(path: String) = lockWork(locked = false, listOf(path)) {
        history.unlock(workspaceId, path, chapter = false, passageIds = _locks.value.locks[path]?.passages.orEmpty().map { it.id }.toSet())
    }

    private fun lockWork(locked: Boolean, paths: List<String>, run: suspend (String) -> FileLocks?) = viewModelScope.launch {
        val previous = LinkedHashMap<String, FileLocks?>()
        for (path in paths) {
            try {
                previous[path] = run(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.tryEmit(error)
            }
        }
        if (previous.isNotEmpty()) _undo.value = LockUndo(locked, previous.size, previous)
        refreshLocks()
    }

    fun undoLocks(undo: LockUndo) = viewModelScope.launch {
        _undo.value = null
        val failed = undo.previous.count { (path, previous) -> !history.restoreLocks(workspaceId, path, previous) }
        if (failed > 0) _undoFailed.tryEmit(Unit)
        refreshLocks()
    }

    fun clearUndo() {
        _undo.value = null
    }

    fun putBack(id: String) = noticeWork { history.putBack(workspaceId, id) }

    fun relock(id: String) = noticeWork {
        history.relock(workspaceId, id)?.let { (path, previous) -> _undo.value = LockUndo(true, 1, mapOf(path to previous)) }
    }

    fun keepChange(id: String) = noticeWork {
        val kept = history.keepChange(workspaceId, id)
        if (kept != null) _undo.value = LockUndo(false, 1, mapOf(kept.first to kept.second)) else _keepFailed.tryEmit(Unit)
    }

    fun acknowledge(id: String) = noticeWork { history.acknowledge(workspaceId, id) }

    fun markSeen(id: String) = noticeWork { history.markNotices(workspaceId, setOf(id), seen = true) }

    fun retryLocks() = noticeWork { history.reloadLocks(workspaceId) }


    private fun noticeWork(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _messages.tryEmit(error)
        }
        refreshLocks()
    }

    fun rebuild() = launch { store.rebuild(workspaceId) }

    fun edit(transform: (BookManifest) -> BookManifest) = launch { store.edit(workspaceId) { transform(it.manifest) } }

    fun addFiles(paths: List<String>) = edit { manifest ->
        manifest.addChapters(paths.map { BookChapter(BookManifest.newId(), it) })
    }

    fun ignoreFiles(paths: List<String>) = edit { it.copy(ignored = it.ignored + paths) }

    fun setStatus(ids: Set<String>, status: ChapterStatus) = edit { manifest ->
        ids.fold(manifest) { current, id -> current.updateChapter(id) { it.copy(status = status) } }
    }

    fun updateChapter(id: String, title: String, synopsis: String, targetWords: Int, status: ChapterStatus) = edit { manifest ->
        manifest.updateChapter(id) {
            it.copy(title = title.trim().ifBlank { null }, synopsis = synopsis.trim(), targetWords = targetWords, status = status)
        }
    }

    fun addVolume(title: String, afterId: String?) = edit { it.addVolume(BookVolume(BookManifest.newId(), title.trim()), afterId) }

    fun setInfo(title: String, author: String) = edit { it.copy(title = title.trim(), author = author.trim()) }

    fun moveToVolume(ids: Set<String>, volumeId: String?) = edit { it.moveToVolume(ids, volumeId) }

    fun reorder(order: List<String>) = edit { it.reorder(order) }

    fun remove(ids: Set<String>) = edit { it.remove(ids) }

    fun delete(ids: Set<String>) = launch { store.deleteChapters(workspaceId, ids) }

    fun relink(chapterId: String, path: String) = launch { store.relink(workspaceId, chapterId, path) }

    fun createChapter(path: String, afterId: String?, onCreated: (String) -> Unit) = launch(
        onDone = { onCreated(path) },
    ) { store.createChapter(workspaceId, path, "", afterId) }

    private fun launch(onDone: () -> Unit = {}, block: suspend () -> Book) {
        viewModelScope.launch {
            try {
                val book = block()
                _state.value = BookUiState(loading = false, book = book)
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (broken: BookManifestException) {
                _state.value = BookUiState(loading = false, manifestError = broken.reason)
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                _state.update { it.copy(loading = false, error = message.takeIf { _ -> it.book == null }) }
                if (_state.value.book != null) _messages.tryEmit(error)
            }
        }
    }
}
