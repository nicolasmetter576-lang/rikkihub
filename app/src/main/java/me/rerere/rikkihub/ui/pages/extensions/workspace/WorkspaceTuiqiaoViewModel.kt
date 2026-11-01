package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoCandidate
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoException
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoMode
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPreparer
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoRequest
import me.rerere.rikkihub.data.datastore.SettingsStore

/** One candidate slot of the session: a role (one card, labelled with its name) or the regular prompt. */
internal data class WorkspaceTuiqiaoSlotInfo(val key: String, val roleName: String?, val count: Int)

/** The selection being refined: source offsets of the text the sheet will replace. */
internal data class WorkspaceTuiqiaoSession(
    val fileName: String,
    val start: Int,
    val end: Int,
    val original: String,
    val mode: TuiqiaoMode,
    val format: TuiqiaoFormat,
    val slots: List<WorkspaceTuiqiaoSlotInfo>,
)

internal sealed interface WorkspaceTuiqiaoStatus {
    data object Loading : WorkspaceTuiqiaoStatus
    data object Done : WorkspaceTuiqiaoStatus
    data class Failed(val reason: TuiqiaoException.Reason?, val message: String?) : WorkspaceTuiqiaoStatus
}

internal data class WorkspaceTuiqiaoItem(val slot: String, val candidate: TuiqiaoCandidate)

/**
 * 推敲 sheet of one file: which cached entry it shows and whether the sheet is up. Results live in
 * [WorkspaceTuiqiaoCache]: hiding the sheet keeps them (still-running requests finish), and selecting
 * the same text again shows them without a new request until 换一批.
 */
internal class WorkspaceTuiqiaoViewModel(
    private val cache: WorkspaceTuiqiaoCache,
    private val settingsStore: SettingsStore,
    private val fileKey: String,
) : ViewModel() {
    var entry: WorkspaceTuiqiaoEntry? by mutableStateOf(null)
        private set
    var visible by mutableStateOf(false)
        private set

    /** The shown session; null while the sheet is hidden. */
    val session: WorkspaceTuiqiaoSession? get() = entry?.takeIf { visible }?.session
    val status: WorkspaceTuiqiaoStatus get() = entry?.status ?: WorkspaceTuiqiaoStatus.Done
    val slotStatus: Map<String, WorkspaceTuiqiaoStatus> get() = entry?.slotStatus ?: emptyMap()
    val items: List<WorkspaceTuiqiaoItem> get() = entry?.items ?: emptyList()

    /**
     * Shows 推敲 for `source[start, end)`: the kept result when this text was refined before (and no
     * explicit [mode] is asked for), else a new request. Returns the rejection when the selection is unusable.
     */
    fun open(fileName: String, source: String, start: Int, end: Int, mode: TuiqiaoMode? = null, fresh: Boolean = false): TuiqiaoException.Reason? {
        if (!fresh && mode == null) {
            cache.lookup(fileKey, source, start, end)?.let { kept ->
                show(kept)
                return null
            }
        }
        val request = TuiqiaoRequest(fileName, source, start, end, mode)
        val next = try {
            TuiqiaoPreparer.prepare(settingsStore.settingsFlow.value.tuiqiao, request)
        } catch (e: TuiqiaoException) {
            return e.reason
        }
        show(cache.start(fileKey, fileName, next))
        return null
    }

    private fun show(next: WorkspaceTuiqiaoEntry) {
        entry = next
        visible = true
    }

    /** Re-runs one failed (or finished) slot of the shown entry. */
    fun retrySlot(key: String) {
        entry?.let { cache.retrySlot(it, key) }
    }

    /** 换一批 / another level: always a new request for the shown range. */
    fun regenerate(source: String, mode: TuiqiaoMode? = null) {
        val current = session ?: return
        open(current.fileName, source, current.start, current.end, mode ?: current.mode, fresh = true)
    }

    fun expandToSentence(source: String) = expand(source) { s, e, format -> Tuiqiao.expandToSentence(source, s, e, format) }

    fun expandToParagraph(source: String) = expand(source) { s, e, format -> Tuiqiao.expandToParagraph(source, s, e, format) }

    /** Grows the range and refines it anew (replacing a kept result for that larger text, as on the Web). */
    private fun expand(source: String, grow: (Int, Int, TuiqiaoFormat) -> IntRange) {
        val current = session ?: return
        if (current.end > source.length || source.substring(current.start, current.end) != current.original) return
        val range = grow(current.start, current.end, current.format)
        open(current.fileName, source, range.first, range.last + 1, fresh = true)
    }

    fun updateConfig(transform: (TuiqiaoConfig) -> TuiqiaoConfig) {
        viewModelScope.launch { settingsStore.update { it.copy(tuiqiao = transform(it.tuiqiao)) } }
    }

    /** The shown text was replaced: its result no longer applies anywhere. */
    fun consumed() {
        entry?.let(cache::remove)
        entry = null
        visible = false
    }

    /** Hides the sheet; the result stays in the cache. */
    fun close() {
        visible = false
    }
}
