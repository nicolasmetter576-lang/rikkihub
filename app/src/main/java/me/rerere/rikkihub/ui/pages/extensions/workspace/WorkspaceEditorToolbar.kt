package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Collapse
import me.rerere.hugeicons.stroke.ExpandParagraph
import me.rerere.hugeicons.stroke.LetterSpacing
import me.rerere.hugeicons.stroke.TextIndent
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.context.LocalToaster

/**
 * Buttons of the workspace editor's top toolbar. [id] is what `DisplaySetting.workspaceEditorToolbarOrder`
 * stores, so it must never change; [command] is set for the Markdown formatting buttons. The writing
 * buttons come first in the default order; users who reordered get new buttons appended at the end.
 */
internal enum class WorkspaceEditorToolbarItem(val id: String, @StringRes val label: Int, val command: WorkspaceMarkdownCommand? = null) {
    TUIQIAO("tuiqiao", R.string.tuiqiao_title),
    ZAIZAO("zaizao", R.string.zaizao_title),
    FIND("find", R.string.workspace_editor_find),
    FORMAT("format", R.string.workspace_editor_format),
    HEADING("heading", R.string.workspace_editor_heading, WorkspaceMarkdownCommand.HEADING),
    BOLD("bold", R.string.workspace_editor_bold, WorkspaceMarkdownCommand.BOLD),
    ITALIC("italic", R.string.workspace_editor_italic, WorkspaceMarkdownCommand.ITALIC),
    LIST("list", R.string.workspace_editor_list, WorkspaceMarkdownCommand.LIST),
    QUOTE("quote", R.string.workspace_editor_quote, WorkspaceMarkdownCommand.QUOTE),
    TASK("task", R.string.workspace_editor_task, WorkspaceMarkdownCommand.TASK),
    LINK("link", R.string.workspace_editor_link, WorkspaceMarkdownCommand.LINK),
    CODE("code", R.string.workspace_editor_code, WorkspaceMarkdownCommand.CODE),
    TABLE("table", R.string.workspace_editor_table, WorkspaceMarkdownCommand.TABLE),
    UNDO("undo", R.string.workspace_editor_undo),
    REDO("redo", R.string.workspace_editor_redo),
}

/**
 * The stored order applied to the full button set: known ids in their stored order (unknown and
 * repeated ids dropped), then any button the stored list lacks in its default place order. An empty
 * list is the default order, so new buttons added later still show up for users who reordered.
 */
internal fun workspaceEditorToolbarOrder(stored: List<String>): List<WorkspaceEditorToolbarItem> {
    val byId = WorkspaceEditorToolbarItem.entries.associateBy { it.id }
    val ordered = stored.mapNotNull { byId[it] }.distinct()
    return ordered + WorkspaceEditorToolbarItem.entries.filterNot { it in ordered }
}

/** Buttons per row in multi-row mode; 15 puts every button of a Markdown file on one row. */
internal val WORKSPACE_TOOLBAR_PER_ROW = 3..15

/**
 * Multi-row mode cuts the buttons in order into rows of [perRow] (clamped to [WORKSPACE_TOOLBAR_PER_ROW]),
 * the last row taking what is left; single-row mode keeps them on one scrolling row.
 */
internal fun workspaceEditorToolbarRows(
    items: List<WorkspaceEditorToolbarItem>,
    multiRow: Boolean,
    perRow: Int,
): List<List<WorkspaceEditorToolbarItem>> =
    if (!multiRow || items.isEmpty()) listOf(items) else items.chunked(perRow.coerceIn(WORKSPACE_TOOLBAR_PER_ROW))

/**
 * What the open file offers. Markdown buttons need [markdown]; 排版 needs a [formatKind] (Markdown and
 * plain text only); 推敲/再造 show when their callback is set (same rule as the selection menu); Find,
 * Undo and Redo are always there.
 */
internal class WorkspaceEditorToolbarActions(
    val markdown: Boolean,
    val formatKind: WorkspaceFormatKind?,
    val onTuiqiao: ((start: Int, end: Int) -> Unit)? = null,
    val onZaizao: ((start: Int, end: Int) -> Unit)? = null,
    val onFind: () -> Unit = {},
)

internal fun WorkspaceEditorToolbarItem.availableFor(actions: WorkspaceEditorToolbarActions): Boolean = when (this) {
    WorkspaceEditorToolbarItem.TUIQIAO -> actions.onTuiqiao != null
    WorkspaceEditorToolbarItem.ZAIZAO -> actions.onZaizao != null
    WorkspaceEditorToolbarItem.FORMAT -> actions.formatKind != null
    WorkspaceEditorToolbarItem.FIND, WorkspaceEditorToolbarItem.UNDO, WorkspaceEditorToolbarItem.REDO -> true
    else -> actions.markdown
}

/** [onEdited] runs after a button changed the text (the editor drops its fill boxes then). */
@Composable
internal fun WorkspaceEditorToolbar(state: TextFieldState, actions: WorkspaceEditorToolbarActions, onEdited: () -> Unit = {}) {
    val display = LocalSettings.current.displaySetting
    val items = workspaceEditorToolbarOrder(display.workspaceEditorToolbarOrder).filter { it.availableFor(actions) }
    val size = WorkspaceEditorToolbarSize.of(display.workspaceEditorToolbarSize)
    if (!display.workspaceEditorToolbarDoubleRow) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            items.forEach { item -> WorkspaceEditorToolbarButton(state, item, actions, size, fill = false, onEdited) }
        }
        return
    }
    // Rows of equal-width cells lined up as a grid, every button on screen: no horizontal scrolling.
    // A short last row leaves its remaining cells empty; fewer buttons than a row holds share the row.
    val rows = workspaceEditorToolbarRows(items, multiRow = true, display.workspaceEditorToolbarPerRow)
    val columns = rows.maxOfOrNull { it.size } ?: return
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Box(Modifier.weight(1f)) { WorkspaceEditorToolbarButton(state, item, actions, size, fill = true, onEdited) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A button label; in a grid cell it shrinks (down to 9sp) rather than wrap or cut when the cell is narrow. */
@Composable
private fun ToolbarLabel(text: String, fill: Boolean) {
    if (!fill) Text(text)
    else Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = LocalTextStyle.current.fontSize),
    )
}

@Composable
private fun WorkspaceEditorToolbarButton(
    state: TextFieldState,
    item: WorkspaceEditorToolbarItem,
    actions: WorkspaceEditorToolbarActions,
    size: WorkspaceEditorToolbarSize,
    fill: Boolean,
    onEdited: () -> Unit,
) {
    val label = stringResource(item.label)
    when (item) {
        WorkspaceEditorToolbarItem.UNDO -> WorkspaceEditorToolbarTextButton(onClick = { state.undoState.undo(); onEdited() }, size, enabled = state.undoState.canUndo, fill = fill) { ToolbarLabel(label, fill) }

        WorkspaceEditorToolbarItem.REDO -> WorkspaceEditorToolbarTextButton(onClick = { state.undoState.redo(); onEdited() }, size, enabled = state.undoState.canRedo, fill = fill) { ToolbarLabel(label, fill) }

        WorkspaceEditorToolbarItem.TUIQIAO, WorkspaceEditorToolbarItem.ZAIZAO -> {
            // Selection only (user decision): the button is grey until text is selected.
            val action = if (item == WorkspaceEditorToolbarItem.TUIQIAO) actions.onTuiqiao else actions.onZaizao
            val selection = state.selection
            WorkspaceEditorToolbarTextButton(onClick = { action?.invoke(selection.min, selection.max) }, size, enabled = action != null && !selection.collapsed, fill = fill) {
                ToolbarLabel(label, fill)
            }
        }

        WorkspaceEditorToolbarItem.FIND -> WorkspaceEditorToolbarTextButton(onClick = actions.onFind, size, fill = fill) { ToolbarLabel(label, fill) }

        WorkspaceEditorToolbarItem.FORMAT -> actions.formatKind?.let { WorkspaceEditorFormatButton(state, it, label, size, fill, onEdited) }

        else -> WorkspaceEditorToolbarTextButton(onClick = {
            val selection = state.selection
            val edit = workspaceMarkdownCommand(state.text.toString(), selection.start, selection.end, item.command!!)
            state.edit {
                replace(edit.start, edit.end, edit.replacement)
                this.selection = TextRange(edit.selectionStart, edit.selectionEnd)
            }
            onEdited()
        }, size, fill = fill) { ToolbarLabel(label, fill) }
    }
}

/** 排版: four independent one-tap commands (user decision), each one undo step, on the selected lines or the whole file. */
@Composable
private fun WorkspaceEditorFormatButton(
    state: TextFieldState,
    kind: WorkspaceFormatKind,
    label: String,
    size: WorkspaceEditorToolbarSize,
    fill: Boolean,
    onEdited: () -> Unit,
) {
    val toaster = LocalToaster.current
    var expanded by remember { mutableStateOf(false) }
    val doneMessage = stringResource(R.string.workspace_editor_format_done)
    val unchangedMessage = stringResource(R.string.workspace_editor_format_unchanged)
    // A plain button like the others (no dropdown arrow): the menu opens below it.
    Box {
        WorkspaceEditorToolbarTextButton(onClick = { expanded = true }, size, fill = fill) { ToolbarLabel(label, fill) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val selection = state.selection
            val scope = if (selection.collapsed) stringResource(R.string.workspace_editor_format_scope_all) else {
                val text = state.text
                val end = if (selection.max > selection.min && text[selection.max - 1] == '\n') selection.max - 1 else selection.max
                val lines = text.subSequence(selection.min, end).count { it == '\n' } + 1
                stringResource(R.string.workspace_editor_format_scope_lines, lines)
            }
            Text(
                scope,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            FORMAT_COMMANDS.forEach { (command, icon, text) ->
                DropdownMenuItem(
                    text = { Text(stringResource(text)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    onClick = {
                        expanded = false
                        val current = state.selection
                        val edit = WorkspaceWritingFormat.apply(state.text.toString(), current.start, current.end, command, kind)
                        if (edit == null) {
                            toaster.show(unchangedMessage, type = ToastType.Info)
                        } else {
                            state.edit {
                                replace(edit.start, edit.end, edit.replacement)
                                this.selection = TextRange(edit.selectionStart, edit.selectionEnd)
                            }
                            onEdited()
                            toaster.show(doneMessage, type = ToastType.Success)
                        }
                    },
                )
            }
        }
    }
}

private val FORMAT_COMMANDS: List<Triple<WorkspaceFormatCommand, ImageVector, Int>> = listOf(
    Triple(WorkspaceFormatCommand.BLANK_LINE_BETWEEN, HugeIcons.ExpandParagraph, R.string.workspace_editor_format_blank_line_between),
    Triple(WorkspaceFormatCommand.REMOVE_BLANK_LINES, HugeIcons.Collapse, R.string.workspace_editor_format_remove_blank_lines),
    Triple(WorkspaceFormatCommand.INDENT, HugeIcons.TextIndent, R.string.workspace_editor_format_indent),
    Triple(WorkspaceFormatCommand.CJK_SPACING, HugeIcons.LetterSpacing, R.string.workspace_editor_format_cjk_spacing),
)
