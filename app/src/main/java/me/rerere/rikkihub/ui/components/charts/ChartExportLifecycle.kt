package me.rerere.rikkihub.ui.components.charts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Production lifecycle seam for native bitmap export. Stages are awaited in order, never callbacks.
 * A successful share retains its cache file for the receiving app; failure/cancellation cleans only
 * the file owned by this request. Resource cleanup is synchronous and runs even when cancelled.
 */
internal suspend fun <T> exportChartResource(
    capture: suspend () -> T,
    encode: suspend (T) -> Unit,
    gallery: suspend (T) -> Unit,
    share: suspend () -> Unit,
    recycle: (T) -> Unit,
    cleanupFailedFile: () -> Unit,
) {
    var resource: T? = null
    var completed = false
    try {
        currentCoroutineContext().ensureActive()
        val captured = capture()
        resource = captured
        currentCoroutineContext().ensureActive()
        encode(captured)
        currentCoroutineContext().ensureActive()
        gallery(captured)
        currentCoroutineContext().ensureActive()
        share()
        currentCoroutineContext().ensureActive()
        completed = true
    } finally {
        try {
            resource?.let(recycle)
        } finally {
            if (!completed) runCatching(cleanupFailedFile)
        }
    }
}
