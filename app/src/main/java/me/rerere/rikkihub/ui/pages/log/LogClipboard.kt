package me.rerere.rikkihub.ui.pages.log

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Clipboard/Binder errors are recoverable, but leaving this page must still cancel the copy. */
internal suspend fun copyLogBodyToClipboard(
    writeClipboard: suspend () -> Unit,
    onFailure: () -> Unit,
) {
    currentCoroutineContext().ensureActive()
    try {
        writeClipboard()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Includes wrapped RuntimeException and checked Binder exceptions. Do not expose body/headers.
        currentCoroutineContext().ensureActive()
        onFailure()
    }
}
