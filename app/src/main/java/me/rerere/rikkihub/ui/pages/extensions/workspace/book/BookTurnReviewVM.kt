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
import me.rerere.rikkihub.data.book.history.TurnBlockDto
import me.rerere.rikkihub.data.book.history.TurnFileDto
import me.rerere.rikkihub.data.book.history.TurnFileOutcome
import me.rerere.rikkihub.data.book.history.TurnFilePick
import me.rerere.rikkihub.data.book.history.TurnReviewActions
import me.rerere.rikkihub.data.book.history.TurnReviewDto

/** 审阅页一次操作的结果。 */
sealed interface TurnReviewNotice {
    /** 全部处理完，页面返回；[reverted] 换回了几处（> 0 时提示条带撤销）。 */
    data class Done(val reverted: Int) : TurnReviewNotice

    /** 有文件没处理成，留在页面（文件顶部写着原因）。 */
    data object Partly : TurnReviewNotice

    /** 撤销：[skipped] 个文件之后又改过（或已不在），没动。 */
    data class Undone(val skipped: Int) : TurnReviewNotice
    data class Failed(val message: String) : TurnReviewNotice
}

data class TurnReviewUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    /** null 且不在加载：这一轮的记录已经没了。 */
    val review: TurnReviewDto? = null,
    val title: String? = null,
    /** 文件（历史键）→ 选了拒绝的块；新建、删除的文件整个文件是第 0 块。 */
    val rejected: Map<String, Set<Int>> = emptyMap(),
    /** 上一次完成没处理成的文件。 */
    val outcomes: Map<String, TurnFileOutcome> = emptyMap(),
) {
    val rejectCount: Int get() = rejected.values.sumOf { it.size }
    val rejectFiles: Int get() = rejected.count { it.value.isNotEmpty() }
}

/** 文件里的块现在能不能单独切换接受 / 拒绝。 */
internal fun TurnFileDto.selectable(block: TurnBlockDto): Boolean = !done && when (kind) {
    // 删掉的：还在回收站里（路径上没有文件）才能放回
    TurnFileDto.Kind.DELETED -> gone && !unrecoverable
    // 新建的：之后又改过也能拒绝（删进回收站，界面写明）
    TurnFileDto.Kind.CREATED -> !gone && !chapterLocked && block.state.let { it == TurnBlockDto.BlockState.PENDING || it == TurnBlockDto.BlockState.CHANGED_LATER }
    TurnFileDto.Kind.CHANGED, TurnFileDto.Kind.RENAMED ->
        !gone && !chapterLocked && !whole && !layoutOnly && missing == null && block.state == TurnBlockDto.BlockState.PENDING
}

/** 一轮改动的审阅：选拒绝、完成、整章还原、撤销。选择只在内存里。 */
class BookTurnReviewVM(
    private val workspaceId: String,
    private val conversationId: String,
    private val messageId: String,
    private val actions: TurnReviewActions,
    private val conversationTitle: suspend (String) -> String?,
) : ViewModel() {
    private val _state = MutableStateFlow(TurnReviewUiState())
    val state = _state.asStateFlow()
    private val _notices = Channel<TurnReviewNotice>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    init {
        work { _state.update { it.copy(title = conversationTitle(conversationId)) } }
    }

    fun reload() = work {}

    /** 切换一块（移动绑定的另一块跟着切）。 */
    fun toggle(file: TurnFileDto, block: TurnBlockDto) {
        if (!file.selectable(block)) return
        val partner = block.partner?.takeIf { index -> file.blocks.getOrNull(index)?.let { file.selectable(it) } == true }
        _state.update { state ->
            val current = state.rejected[file.key].orEmpty()
            val indices = setOfNotNull(block.index, partner)
            val next = if (block.index in current) current - indices else current + indices
            state.copy(rejected = state.rejected + (file.key to next))
        }
    }

    /** 全部拒绝：只切换能单独拒绝、不碰锁的块；返回切不了的块数。 */
    fun rejectAll(file: TurnFileDto): Int {
        val pending = file.blocks.filter { it.state == TurnBlockDto.BlockState.PENDING }
        val picked = pending.filter { file.selectable(it) && !it.locked }.map { it.index }.toSet()
        _state.update { it.copy(rejected = it.rejected + (file.key to it.rejected[file.key].orEmpty() + picked)) }
        return pending.size - picked.size
    }

    fun clearSelection() = _state.update { it.copy(rejected = emptyMap()) }

    /** 完成：没选拒绝的文件也标为处理过。 */
    fun complete() {
        val review = _state.value.review ?: return
        val rejected = _state.value.rejected
        val picks = review.files.filter { !it.done }.map { file ->
            val reject = rejected[file.key].orEmpty()
            val whole = file.kind == TurnFileDto.Kind.CREATED || file.kind == TurnFileDto.Kind.DELETED
            TurnFilePick(file.key, file.revision, file.before, file.after, if (whole) emptySet() else reject, whole && reject.isNotEmpty())
        }
        work {
            val outcomes = actions.complete(workspaceId, conversationId, messageId, picks)
            val failed = outcomes.filter { it.outcome != TurnFileOutcome.Outcome.OK }
            // 写失败的文件留着选择（已换回的块重新加载后不能再选，自然去掉）；冲突的文件稿子变了，重新选
            val retry = failed.filter { it.outcome == TurnFileOutcome.Outcome.FAILED }.map { it.key }.toSet()
            _state.update { state -> state.copy(outcomes = failed.associateBy { it.key }, rejected = state.rejected.filterKeys { it in retry }) }
            _notices.send(if (failed.isEmpty()) TurnReviewNotice.Done(outcomes.sumOf { it.reverted }) else TurnReviewNotice.Partly)
        }
    }

    /** 整章还原到改前（单独的危险操作，确认框里写明会丢掉之后的几处修改）。 */
    fun restoreWhole(file: TurnFileDto) = work {
        val outcome = actions.complete(workspaceId, conversationId, messageId, listOf(TurnFilePick(file.key, file.revision, file.before, file.after, whole = true))).single()
        _state.update { state ->
            state.copy(
                outcomes = if (outcome.outcome == TurnFileOutcome.Outcome.OK) state.outcomes - file.key else state.outcomes + (file.key to outcome),
                rejected = state.rejected - file.key,
            )
        }
    }

    fun undo() = work {
        val results = actions.undo(workspaceId, conversationId, messageId)
        _state.update { it.copy(outcomes = emptyMap(), rejected = emptyMap()) }
        _notices.send(TurnReviewNotice.Undone(results.count { it.outcome != TurnFileOutcome.Outcome.OK }))
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
                _notices.send(TurnReviewNotice.Failed(e.message ?: e.javaClass.simpleName))
            }
            val review = runCatching { actions.load(workspaceId, conversationId, messageId) }.getOrNull()
            _state.update { state ->
                // 记录变了（续写重开、文件没了）：选择只留还能选的块
                val rejected = state.rejected.mapNotNull { (key, set) ->
                    val file = review?.files?.firstOrNull { it.key == key } ?: return@mapNotNull null
                    key to set.filter { index -> file.blocks.getOrNull(index)?.let { file.selectable(it) } == true }.toSet()
                }.toMap().filterValues { it.isNotEmpty() }
                state.copy(busy = false, loading = false, review = review, rejected = rejected)
            }
        }
    }
}
