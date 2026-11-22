package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.qubagu.Qubagu
import me.rerere.rikkihub.data.ai.qubagu.QubaguAnnotation
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefineException
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefiner
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.getCurrentChatModel

/**
 * 文件里的一条八股标注。[start]..[end] 是它**现在**在正文里的范围：未应用时那里是 `annotation.original`，
 * 应用后是 [applied]。
 */
internal data class WorkspaceQubaguMark(val annotation: QubaguAnnotation, val start: Int, val end: Int, val applied: String? = null) {
    /** 现在这个范围里应该是的文字。 */
    val shown: String get() = applied ?: annotation.original
}

/** 对正文的一次替换：把 [start]..[end] 换成 [text]。 */
internal data class WorkspaceQubaguEdit(val start: Int, val end: Int, val text: String)

/**
 * 一份文件的全部八股标注（按位置排序、互不重叠）。应用、撤销都是一次替换，之后的标注按长度差平移；
 * 标注期间正文只读，所以除了这里的替换，没有别的改动来源。
 */
internal data class WorkspaceQubaguMarks(val marks: List<WorkspaceQubaguMark>) {
    /** [offset] 落在哪条标注里（句末之后紧挨着的位置也算这句）。 */
    fun indexAt(offset: Int): Int? =
        marks.indexOfFirst { offset >= it.start && offset < it.end }.takeIf { it >= 0 }
            ?: marks.indexOfFirst { offset == it.end }.takeIf { it >= 0 }

    /** 第 [index] 条换成 [text]：返回要对正文做的替换和替换后的标注。 */
    fun apply(index: Int, text: String): Pair<WorkspaceQubaguEdit, WorkspaceQubaguMarks> = replace(index, text, applied = text)

    /** 第 [index] 条恢复原句。 */
    fun undo(index: Int): Pair<WorkspaceQubaguEdit, WorkspaceQubaguMarks> = replace(index, marks[index].annotation.original, applied = null)

    /** 去掉第 [index] 条标注（正文不动）。 */
    fun ignore(index: Int): WorkspaceQubaguMarks = WorkspaceQubaguMarks(marks.filterIndexed { i, _ -> i != index })

    /** 正文里每条标注的范围是不是仍是它该有的文字。 */
    fun matches(text: CharSequence): Boolean =
        marks.all { it.end <= text.length && text.regionMatches(it.start, it.shown, 0, it.shown.length) }

    private fun replace(index: Int, text: String, applied: String?): Pair<WorkspaceQubaguEdit, WorkspaceQubaguMarks> {
        val mark = marks[index]
        val delta = text.length - (mark.end - mark.start)
        val next = marks.mapIndexed { i, other ->
            when {
                i == index -> other.copy(end = other.start + text.length, applied = applied)
                i > index -> other.copy(start = other.start + delta, end = other.end + delta)
                else -> other
            }
        }
        return WorkspaceQubaguEdit(mark.start, mark.end, text) to WorkspaceQubaguMarks(next)
    }

    companion object {
        fun of(annotations: List<QubaguAnnotation>) = WorkspaceQubaguMarks(annotations.map { WorkspaceQubaguMark(it, it.start, it.end) })
    }
}

/**
 * 编辑器要的八股标注状态：[active] 时正文只读、标注高亮，点到 [marks] 里的某条就 [onOpen]；工具栏「八股」按钮调 [onToggle]。
 */
internal class WorkspaceEditorQubagu(
    val active: Boolean,
    val scanning: Boolean,
    val marks: List<WorkspaceQubaguMark>,
    val onToggle: () -> Unit,
    val onOpen: (Int) -> Unit,
)

/** 打开八股标注的结果：[Found] 以外的情况标注不打开。 */
internal enum class WorkspaceQubaguScan { FOUND, NO_RULES, NOTHING_FOUND, TOO_LARGE }

/** 一句的 AI 建议：在等、拿到了、或失败（[error] 为 null 是请求本身的错误，信息在 [message]）。 */
internal sealed interface WorkspaceQubaguAi {
    data object Loading : WorkspaceQubaguAi
    data class Done(val suggestions: List<String>) : WorkspaceQubaguAi
    data class Failed(val error: QubaguRefineException.Reason?, val message: String?) : WorkspaceQubaguAi
}

/**
 * 一份文件的八股标注：[marks] 为 null 表示没开。标注只活在这次打开里，不落盘；关掉、离开文件都丢掉。
 * AI 建议按标注的原句缓存，同一句再打开窗口不重复请求。
 */
internal class WorkspaceQubaguViewModel(
    private val engine: Qubagu,
    private val refiner: QubaguRefiner,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    var marks: WorkspaceQubaguMarks? by mutableStateOf(null)
        private set
    var scanning by mutableStateOf(false)
        private set

    /** 正在看的那条标注（弹窗开着）。 */
    var opened: Int? by mutableStateOf(null)
        private set
    var ai: Map<QubaguAnnotation, WorkspaceQubaguAi> by mutableStateOf(emptyMap())
        private set

    private var scan: Job? = null

    val active: Boolean get() = marks != null || scanning

    /** 扫描 [text] 打开标注；[onResult] 在扫完时回调（打开了是 [WorkspaceQubaguScan.FOUND]）。 */
    fun start(text: String, onResult: (WorkspaceQubaguScan) -> Unit) {
        if (active) return
        val config = settingsStore.settingsFlow.value.qubagu
        when {
            !config.hasWork -> return onResult(WorkspaceQubaguScan.NO_RULES)
            text.length > MAX_CHARS -> return onResult(WorkspaceQubaguScan.TOO_LARGE)
        }
        scanning = true
        scan = viewModelScope.launch {
            val found = try {
                engine.annotate(text, config)
            } finally {
                scanning = false
            }
            if (found.isEmpty()) onResult(WorkspaceQubaguScan.NOTHING_FOUND)
            else {
                marks = WorkspaceQubaguMarks.of(found)
                onResult(WorkspaceQubaguScan.FOUND)
            }
        }
    }

    fun stop() {
        scan?.cancel()
        scanning = false
        marks = null
        opened = null
        ai = emptyMap()
    }

    fun open(index: Int) {
        if (marks?.marks?.indices?.contains(index) == true) opened = index
    }

    fun dismiss() {
        opened = null
    }

    /** 让 [apply]/[undo] 算出的新标注生效（正文由调用方替换）。 */
    fun update(next: WorkspaceQubaguMarks) {
        marks = next
    }

    fun ignore(index: Int) {
        val current = marks ?: return
        marks = current.ignore(index)
        opened = null
    }

    /** 请求这句的三种 AI 改写；[text] 是整个文件（取上下文用）。 */
    fun requestAi(index: Int, text: String) {
        val annotation = marks?.marks?.getOrNull(index)?.annotation ?: return
        if (ai[annotation] == WorkspaceQubaguAi.Loading) return
        ai = ai + (annotation to WorkspaceQubaguAi.Loading)
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.value
            val result = try {
                WorkspaceQubaguAi.Done(refiner.suggest(text, annotation, settings, fallback = settings.getCurrentChatModel()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: QubaguRefineException) {
                WorkspaceQubaguAi.Failed(e.reason, null)
            } catch (e: Exception) {
                WorkspaceQubaguAi.Failed(null, e.message ?: e.javaClass.simpleName)
            }
            ai = ai + (annotation to result)
        }
    }

    companion object {
        /** 超过这个长度的文件不扫（规则逐条跑全文，太长时要等很久）。 */
        const val MAX_CHARS = 300_000
    }
}
