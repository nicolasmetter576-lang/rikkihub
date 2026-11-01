package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import me.rerere.rikkihub.R

/** Quiet period before an Android editor autosaves; same as the Web editor session. */
internal const val WORKSPACE_EDITOR_AUTOSAVE_DELAY_MS = 800L

/**
 * Autosave trigger shared by the FILES page and the device editor. Each source change restarts
 * the debounce (selection-only changes do not); once quiet, wait for any in-flight save, then call
 * [save], which re-checks every gate itself (CAS baseline, dirty, paused). Never retries a failure:
 * a failed save pauses autosave until a manual save succeeds or the file is reloaded.
 */
internal suspend fun runWorkspaceEditorAutoSave(
    state: TextFieldState,
    awaitIdle: suspend () -> Unit,
    delayMillis: Long = WORKSPACE_EDITOR_AUTOSAVE_DELAY_MS,
    save: () -> Unit,
) {
    snapshotFlow { state.text }
        .distinctUntilChanged { a, b -> a === b || (a.length == b.length && a.contentEquals(b)) }
        .collectLatest {
            delay(delayMillis)
            awaitIdle()
            save()
        }
}

/** Why autosave stopped; cleared by a successful manual save or a reload. */
enum class WorkspaceEditorAutoSavePause(val messageRes: Int) {
    CONFLICT(R.string.workspace_editor_autosave_conflict),
    FAILED(R.string.workspace_editor_autosave_failed),
}

/** Revert target is the disk text when this session opened the file; null = unknown (pre-v3 draft). */
internal fun workspaceEditorCanRevert(opened: String?, current: CharSequence): Boolean =
    opened != null && !current.contentEquals(opened)

/** Whole-source replacement as ONE native undoable edit: Undo brings back what Revert replaced. */
internal fun TextFieldState.revertWorkspaceEditorTo(opened: String) {
    edit {
        replace(0, length, opened)
        selection = TextRange(opened.length)
    }
}
