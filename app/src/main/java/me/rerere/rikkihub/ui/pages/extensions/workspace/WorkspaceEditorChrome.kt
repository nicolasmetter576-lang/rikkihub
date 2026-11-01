package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.theme.CustomColors

/**
 * Row height of the editor toolbar (and the preview's Undo/Redo row). [id] is what
 * `DisplaySetting.workspaceEditorToolbarSize` stores, so it must never change. STANDARD is the look
 * before sizes existed: a 40dp button in a 48dp touch row.
 */
internal enum class WorkspaceEditorToolbarSize(
    val id: String,
    @StringRes val label: Int,
    val rowHeight: Dp,
    val fontSize: TextUnit,
    val horizontalPadding: Dp,
    val minWidth: Dp,
) {
    STANDARD("standard", R.string.setting_workspace_editor_toolbar_size_standard, 48.dp, 14.sp, 12.dp, 58.dp),
    COMPACT("compact", R.string.setting_workspace_editor_toolbar_size_compact, 40.dp, 14.sp, 10.dp, 48.dp),
    MINI("mini", R.string.setting_workspace_editor_toolbar_size_mini, 32.dp, 13.sp, 8.dp, 40.dp);

    companion object {
        /** Unknown ids (a newer or damaged setting) read as the default, MINI. */
        fun of(id: String): WorkspaceEditorToolbarSize = entries.firstOrNull { it.id == id } ?: MINI
    }
}

/**
 * A text button of the editor toolbar at [size]: the row is exactly [WorkspaceEditorToolbarSize.rowHeight]
 * tall. [fill]: the button takes its grid cell's whole width (multi-row mode) instead of its own.
 */
@Composable
internal fun WorkspaceEditorToolbarTextButton(
    onClick: () -> Unit,
    size: WorkspaceEditorToolbarSize,
    enabled: Boolean = true,
    fill: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    // In a row of its own a min height (not a fixed one) replaces the button's 40dp minimum and leaves the
    // touch size free to grow it: STANDARD stays a 40dp button in a 48dp row. A grid cell is the button
    // itself, row tall, with no touch size that would spill into a narrow neighbouring cell.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (fill) Dp.Unspecified else size.rowHeight) {
        TextButton(
            onClick = onClick,
            modifier = if (fill) Modifier.heightIn(min = size.rowHeight).fillMaxWidth()
            else Modifier.heightIn(min = min(size.rowHeight, 40.dp)).widthIn(min = size.minWidth),
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = if (fill) 2.dp else size.horizontalPadding),
        ) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontSize = size.fontSize)) { content() }
        }
    }
}

/** Standard / Compact / Mini, on 设置-偏好设置-工作区 and the editor's Aa sheet. */
@Composable
internal fun WorkspaceEditorToolbarSizeSelector(selected: WorkspaceEditorToolbarSize, onChange: (WorkspaceEditorToolbarSize) -> Unit) {
    val sizes = WorkspaceEditorToolbarSize.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        sizes.forEachIndexed { index, size ->
            SegmentedButton(
                selected = size == selected,
                onClick = { onChange(size) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sizes.size),
            ) { Text(stringResource(size.label)) }
        }
    }
}

/** Top / Bottom for the toolbar, on 设置-偏好设置-工作区 and the editor's Aa sheet. */
@Composable
internal fun WorkspaceEditorToolbarPositionSelector(atBottom: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(false to R.string.setting_workspace_editor_toolbar_position_top, true to R.string.setting_workspace_editor_toolbar_position_bottom)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = atBottom == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

/**
 * The editor page's top bar: 48dp instead of the usual 64dp, and no file name (it sits in the status
 * bar at the bottom), so the buttons have the row to themselves.
 */
@Composable
internal fun WorkspaceEditorTopBar(
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    title: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = title,
        navigationIcon = navigation,
        actions = actions,
        expandedHeight = 48.dp,
        colors = CustomColors.topBarColors,
    )
}
