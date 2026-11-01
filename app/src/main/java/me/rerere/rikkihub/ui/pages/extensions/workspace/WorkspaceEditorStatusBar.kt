package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import java.text.NumberFormat

private data class WorkspaceStatsSnapshot(val total: WorkspaceTextStats, val selection: WorkspaceTextStats?)

/**
 * The editor's bottom status bar: characters and words of the file on the left, and of the selection
 * when there is one ([withSelection] off for the preview, whose selection is not the editor's); the file
 * name on the right, cut in the middle when long (a tap shows [path] whole). No [state] (a web preview,
 * still loading, unreadable): the name only. Counted off the main thread once input pauses, so typing in
 * a large file stays smooth.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkspaceEditorStatusBar(
    fileName: String,
    path: String,
    state: TextFieldState?,
    modifier: Modifier = Modifier,
    withSelection: Boolean = true,
) {
    // While typing, only the toolbar sits above the keyboard.
    if (WindowInsets.isImeVisible) return
    val toaster = LocalToaster.current
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        // The stats are measured first and keep their width; the name takes what is left.
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state != null) WorkspaceEditorStats(state, withSelection, style, color)
            Spacer(Modifier.weight(1f))
            Text(
                fileName,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(start = 12.dp)
                    .clickable { toaster.show(path, type = ToastType.Info) }
                    .padding(vertical = 4.dp)
                    .testTag("workspace_editor_file_name"),
            )
        }
    }
}

@Composable
private fun WorkspaceEditorStats(state: TextFieldState, withSelection: Boolean, style: TextStyle, color: Color) {
    var stats by remember(state) { mutableStateOf<WorkspaceStatsSnapshot?>(null) }
    LaunchedEffect(state, withSelection) {
        var countedText: CharSequence? = null
        var total = WorkspaceTextStats(0, 0)
        snapshotFlow { state.text to (if (withSelection) state.selection else TextRange.Zero) }.collectLatest { (text, selection) ->
            val textChanged = countedText?.let { it.length != text.length || !it.contentEquals(text) } ?: true
            if (textChanged && countedText != null) delay(150)
            stats = withContext(Dispatchers.Default) {
                if (textChanged) total = workspaceTextStats(text)
                countedText = text
                WorkspaceStatsSnapshot(total, if (selection.collapsed) null else workspaceTextStats(text, selection.min, selection.max))
            }
        }
    }
    val current = stats ?: return
    val format = remember { NumberFormat.getIntegerInstance() }
    val selection = current.selection
    val label = if (selection == null) {
        stringResource(R.string.workspace_stats, format.format(current.total.chars), format.format(current.total.words))
    } else {
        stringResource(
            R.string.workspace_stats_selection,
            format.format(selection.chars), format.format(current.total.chars),
            format.format(selection.words), format.format(current.total.words),
        )
    }
    Text(
        label,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(vertical = 4.dp).testTag("workspace_editor_stats"),
    )
}
