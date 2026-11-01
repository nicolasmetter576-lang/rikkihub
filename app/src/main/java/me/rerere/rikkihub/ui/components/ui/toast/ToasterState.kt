package me.rerere.rikkihub.ui.components.ui.toast

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ToastType {
    Normal,
    Success,
    Info,
    Warning,
    Error,
}

@Stable
class ToasterState internal constructor(
    private val scope: CoroutineScope,
    private val maxVisibleToasts: Int,
) {
    private val nextId = AtomicLong()

    internal val items = mutableStateListOf<ToastItem>()

    fun show(
        message: String,
        type: ToastType = ToastType.Normal,
        duration: Duration = DefaultDuration,
    ) {
        val item = add(message, type)
        item.job = scope.launch {
            delay(duration)
            dismiss(item)
        }
    }

    /** 显示一条不会自己消失的进度提示，任务结束后调用返回的句柄收起它，免得和结果提示叠在一起 */
    fun showProgress(message: String, type: ToastType = ToastType.Normal): ToastHandle {
        val item = add(message, type)
        return ToastHandle { dismiss(item) }
    }

    private fun add(message: String, type: ToastType): ToastItem {
        val item = ToastItem(id = nextId.getAndIncrement(), message = message, type = type)
        items.add(item)
        // 超出可见数量时让最早的先退出
        items.filter { it.visibleState.targetState }
            .dropLast(maxVisibleToasts)
            .forEach { dismiss(it) }
        return item
    }

    internal fun dismiss(item: ToastItem) {
        item.job?.cancel()
        item.visibleState.targetState = false
    }

    internal fun remove(item: ToastItem) {
        items.remove(item)
    }

    companion object {
        val DefaultDuration = 4.seconds
    }
}

/** [ToasterState.showProgress] 返回的句柄，[dismiss] 收起对应的提示，重复调用无害。 */
fun interface ToastHandle {
    fun dismiss()
}

internal class ToastItem(
    val id: Long,
    val message: String,
    val type: ToastType,
) {
    val visibleState = MutableTransitionState(false).apply { targetState = true }
    var job: Job? = null
}

@Composable
fun rememberToasterState(maxVisibleToasts: Int = 3): ToasterState {
    val scope = rememberCoroutineScope()
    return remember(scope, maxVisibleToasts) { ToasterState(scope, maxVisibleToasts) }
}
