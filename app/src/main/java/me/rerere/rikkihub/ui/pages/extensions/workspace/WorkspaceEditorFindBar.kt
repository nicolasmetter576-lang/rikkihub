package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CaseSensitive
import me.rerere.hugeicons.stroke.Replace
import me.rerere.rikkihub.R

/** The find bar's state; matches are recomputed by the editor whenever the text, query or case option changes. */
@Stable
internal class WorkspaceFindState {
    var open by mutableStateOf(false)
    val query = TextFieldState()
    val replacement = TextFieldState()
    var caseSensitive by mutableStateOf(false)
    var replaceOpen by mutableStateOf(false)
    /** Match starts in the editor text; every match is [matchLength] long. */
    var matches by mutableStateOf(IntArray(0))
    var matchLength by mutableIntStateOf(0)
    /** The query and case option [matches] were found for; the count hides while a new query is searched. */
    var matchedFor by mutableStateOf<Pair<String, Boolean>?>(null)
    var current by mutableIntStateOf(-1)
    /** Bumped to ask the bar to focus its query field (opening, or the toolbar button pressed again). */
    var focusRequests by mutableIntStateOf(0)
    /** Set before a replacement: the next match to show is the first one at or after it. */
    var pendingAnchor: Int? = null

    val currentStart: Int? get() = matches.getOrNull(current)

    fun show(prefill: String?) {
        if (prefill != null) query.edit { replace(0, length, prefill) }
        query.edit { selectAll() }
        open = true
        focusRequests++
    }

    fun close() {
        open = false
        replaceOpen = false
    }
}

@Composable
internal fun WorkspaceEditorFindBar(
    find: WorkspaceFindState,
    canReplace: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(find.focusRequests) { if (find.focusRequests > 0) focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val count = when {
                    find.query.text.isEmpty() || find.matchedFor != (find.query.text.toString() to find.caseSensitive) -> ""
                    find.matches.isEmpty() -> stringResource(R.string.workspace_find_none)
                    else -> stringResource(R.string.workspace_find_count, find.current + 1, find.matches.size) +
                        if (find.matches.size >= WORKSPACE_FIND_LIMIT) "+" else ""
                }
                FindField(find.query, stringResource(R.string.workspace_find_hint), count, onNext, Modifier.weight(1f).focusRequester(focus))
                val hasMatches = find.matches.isNotEmpty()
                IconButton(onClick = onPrevious, enabled = hasMatches) {
                    Icon(HugeIcons.ArrowUp01, contentDescription = stringResource(R.string.workspace_find_previous))
                }
                IconButton(onClick = onNext, enabled = hasMatches) {
                    Icon(HugeIcons.ArrowDown01, contentDescription = stringResource(R.string.workspace_find_next))
                }
                IconToggleButton(checked = find.caseSensitive, onCheckedChange = { find.caseSensitive = it }) {
                    Icon(HugeIcons.CaseSensitive, contentDescription = stringResource(R.string.workspace_find_case))
                }
                if (canReplace) IconToggleButton(checked = find.replaceOpen, onCheckedChange = { find.replaceOpen = it }) {
                    Icon(HugeIcons.Replace, contentDescription = stringResource(R.string.workspace_find_toggle_replace))
                }
                IconButton(onClick = find::close) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.workspace_find_close))
                }
            }
            if (canReplace && find.replaceOpen) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FindField(find.replacement, stringResource(R.string.workspace_find_replace_hint), "", onReplace, Modifier.weight(1f))
                TextButton(onClick = onReplace, enabled = find.currentStart != null) { Text(stringResource(R.string.workspace_find_replace)) }
                TextButton(onClick = onReplaceAll, enabled = find.matches.isNotEmpty()) { Text(stringResource(R.string.workspace_find_replace_all)) }
            }
        }
    }
}

@Composable
private fun FindField(state: TextFieldState, hint: String, trailing: String, onEnter: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        state = state,
        modifier = modifier.semantics { contentDescription = hint },
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { onEnter() },
        decorator = { inner ->
            Row(
                Modifier.background(colors.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (state.text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    inner()
                }
                if (trailing.isNotEmpty()) Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
    )
}

