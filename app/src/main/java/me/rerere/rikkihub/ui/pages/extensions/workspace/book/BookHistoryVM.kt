package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.book.history.BookHistory
import me.rerere.rikkihub.data.book.history.BookHistoryActions
import me.rerere.rikkihub.data.book.history.BookLockedException
import me.rerere.rikkihub.data.book.history.HistoryEntry
import me.rerere.rikkihub.data.book.history.HistorySource
import me.rerere.rikkihub.data.book.history.LockNotice
import me.rerere.rikkihub.data.book.history.TrashedFile
import me.rerere.workspace.WorkspaceFileRevisionConflictException

/** 历史页、回收站页一次操作的结果，界面用它挑提示文字。 */
sealed interface BookHistoryNotice {
    data object Restored : BookHistoryNotice
    data object TakenBack : BookHistoryNotice
    data object Conflict : BookHistoryNotice
    data object Missing : BookHistoryNotice
    data object Cleared : BookHistoryNotice
    data class PutBack(val path: String, val locked: Boolean = false) : BookHistoryNotice
    /** 整章锁定，还原、取回被拒：页面问要不要解锁并还原。 */
    data object Locked : BookHistoryNotice
    /** 提醒处理了（放回、重新锁定）。 */
    data object NoticeHandled : BookHistoryNotice
    /** 放回没成（锁定内容认不出了）。 */
    data object PutBackFailed : BookHistoryNotice
    data class Failed(val message: String) : BookHistoryNotice
}

data class BookHistoryUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    /** 新的在前。 */
    val entries: List<HistoryEntry> = emptyList(),
    val paused: Boolean = false,
    val onlyBeforeAi: Boolean = false,
    /** 会话 id → 标题（找得到的）。 */
    val conversations: Map<String, String> = emptyMap(),
    val selected: HistoryEntry.Version? = null,
    val comparison: BookHistoryActions.Comparison? = null,
    /** 永久版本（加锁时存的）。 */
    val pinned: Set<String> = emptySet(),
    /** 这一章现在的锁在用的版本：锁还在时不能删。 */
    val inUse: Set<String> = emptySet(),
    /** 从“改了锁定内容”的提醒进来时，那条还没处理的提醒（比较页顶部给“放回 / 重新锁定”）。 */
    val notice: LockNotice? = null,
) {
    /** 只看 AI 改之前：紧接着下一版（或现在的稿子）是 AI 写的那些。 */
    fun shown(currentWriter: HistorySource?): List<HistoryEntry> {
        if (!onlyBeforeAi) return entries
        return entries.filterIndexed { index, entry ->
            val next = entries.subList(0, index).lastOrNull { it is HistoryEntry.Version }?.source ?: currentWriter
            entry is HistoryEntry.Version && next is HistorySource.Ai
        }
    }
}

/** 一个文件的版本历史：列表、与现在比较、取回一段、整章还原、删一版。 */
class BookHistoryVM(
    private val workspaceId: String,
    private val path: String,
    /** 从提醒的“查看”进来：打开时直接比较 AI 改之前那一版。 */
    private val noticeId: String?,
    private val history: BookHistory,
    private val actions: BookHistoryActions,
    private val conversationTitle: suspend (String) -> String?,
) : ViewModel() {
    private val _state = MutableStateFlow(BookHistoryUiState())
    val state = _state.asStateFlow()
    private val _notices = Channel<BookHistoryNotice>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    /** 现在的稿子是谁写的（只看 AI 改之前用）。 */
    var currentWriter: HistorySource? = null
        private set

    init {
        work {}
    }

    fun reload() = work {}

    /** 比较页顶部的“放回”：带着比较时看到的稿子，稿子变了就提示重新比较。 */
    fun putBackNotice() {
        val notice = _state.value.notice ?: return
        val expected = _state.value.comparison?.current?.content
        work { _notices.send(if (history.putBack(workspaceId, notice.id, expected)) BookHistoryNotice.NoticeHandled else BookHistoryNotice.PutBackFailed) }
    }

    /** 比较页顶部的“按现在的稿子重新锁定”。 */
    fun relockNotice() {
        val notice = _state.value.notice ?: return
        work {
            history.relock(workspaceId, notice.id)
            _notices.send(BookHistoryNotice.NoticeHandled)
        }
    }

    fun setOnlyBeforeAi(value: Boolean) = _state.update { it.copy(onlyBeforeAi = value) }

    fun select(version: HistoryEntry.Version?) {
        _state.update { it.copy(selected = version, comparison = null) }
        if (version != null) work {}
    }

    fun restore() = withComparison { version, comparison ->
        actions.restore(workspaceId, path, version.sha, comparison.current.revision)
        _notices.send(BookHistoryNotice.Restored)
    }

    fun takeBack(index: Int) = withComparison { version, comparison ->
        actions.takeBack(workspaceId, path, version.sha, index, comparison.current.revision)
        _notices.send(BookHistoryNotice.TakenBack)
    }

    /** 被整章锁挡住的还原：先解锁再还原（解锁可以在作品页、锁面板里撤销）。 */
    fun unlockAndRestore() = withComparison { version, comparison ->
        history.unlock(workspaceId, path, chapter = true, passageIds = emptySet())
        actions.restore(workspaceId, path, version.sha, comparison.current.revision)
        _notices.send(BookHistoryNotice.Restored)
    }

    fun deleteSelected() {
        val version = _state.value.selected ?: return
        work {
            history.deleteVersion(workspaceId, path, version)
            _state.update { it.copy(selected = null, comparison = null) }
        }
    }

    private fun withComparison(block: suspend (HistoryEntry.Version, BookHistoryActions.Comparison) -> Unit) {
        val state = _state.value
        val version = state.selected ?: return
        val comparison = state.comparison ?: return
        work { block(version, comparison) }
    }

    /** 跑 [block]（冲突、版本丢了给对应提示），然后重读列表和选中那版的比较。 */
    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            notify { block() }
            notify { refresh() }
            _state.update { it.copy(busy = false, loading = false) }
        }
    }

    private suspend fun notify(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: WorkspaceFileRevisionConflictException) {
            _notices.send(BookHistoryNotice.Conflict)
        } catch (_: BookHistoryActions.VersionMissingException) {
            _notices.send(BookHistoryNotice.Missing)
        } catch (_: BookLockedException) {
            _notices.send(BookHistoryNotice.Locked)
        } catch (e: Exception) {
            _notices.send(BookHistoryNotice.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun refresh() {
        val entries = history.entries(workspaceId, path).asReversed()
        currentWriter = history.currentWriter(workspaceId, path)
        val known = _state.value.conversations
        val titles = known + entries.mapNotNull { (it.source as? HistorySource.Ai)?.conversationId }.distinct()
            .filter { it !in known }.mapNotNull { id -> conversationTitle(id)?.let { id to it } }
        val locks = history.locksOf(workspaceId, path)
        val inUse = listOfNotNull(locks?.chapter?.sha).toSet() + locks?.passages.orEmpty().map { it.sha }
        val notice = noticeId?.let { id -> history.notices(workspaceId).firstOrNull { it.id == id && it.kind in COMPARE_KINDS } }
        // 第一次加载：直接比较提醒里 AI 改之前那一版
        val first = _state.value.loading && _state.value.selected == null
        val before = notice?.beforeSha?.takeIf { first }?.let { sha -> entries.firstOrNull { it is HistoryEntry.Version && it.sha == sha } as HistoryEntry.Version? }
        _state.update {
            it.copy(entries = entries, paused = history.paused(workspaceId), conversations = titles, comparison = null,
                pinned = history.pinned(workspaceId), inUse = inUse, notice = notice, selected = before ?: it.selected)
        }
        val selected = _state.value.selected ?: return
        val comparison = actions.compare(workspaceId, path, selected.sha)
        _state.update { if (it.selected == selected) it.copy(comparison = comparison) else it }
    }
}

/** 比较页顶部给“放回 / 重新锁定”的提醒：只提醒、没自动放回的那几种。 */
private val COMPARE_KINDS = setOf(LockNotice.Kind.CHANGED, LockNotice.Kind.NOT_RESTORED)

data class BookTrashUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val items: List<TrashedFile> = emptyList(),
    val usageBytes: Long = 0,
    val paused: Boolean = false,
)

/** 一本书的回收站：放回、彻底删除、清空；清空版本历史也在这里（只在 App 里）。 */
class BookTrashVM(
    private val workspaceId: String,
    private val history: BookHistory,
    private val actions: BookHistoryActions,
) : ViewModel() {
    private val _state = MutableStateFlow(BookTrashUiState())
    val state = _state.asStateFlow()
    private val _notices = Channel<BookHistoryNotice>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    init {
        work {}
    }

    fun putBack(item: TrashedFile) = work {
        val path = actions.restoreTrashed(workspaceId, item.key, item.name, item.path)
        val locked = path != null && history.locksOf(workspaceId, path)?.isEmpty == false
        _notices.send(if (path != null) BookHistoryNotice.PutBack(path, locked) else BookHistoryNotice.Missing)
    }

    fun delete(item: TrashedFile) = work { history.deleteTrashed(workspaceId, item.key, item.name) }

    fun emptyTrash() = work { history.emptyTrash(workspaceId) }

    fun clearHistory() = work {
        history.clear(workspaceId)
        _notices.send(BookHistoryNotice.Cleared)
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _notices.send(BookHistoryNotice.Failed(e.message ?: e.javaClass.simpleName))
            }
            _state.update {
                it.copy(
                    loading = false,
                    busy = false,
                    items = history.trashed(workspaceId),
                    usageBytes = history.usage(workspaceId),
                    paused = history.paused(workspaceId),
                )
            }
        }
    }
}
