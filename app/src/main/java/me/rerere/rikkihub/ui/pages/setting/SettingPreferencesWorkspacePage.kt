package me.rerere.rikkihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.EditorTypography
import me.rerere.rikkihub.data.datastore.appEditorTypography
import me.rerere.rikkihub.ui.pages.extensions.workspace.EditorTypographyControls
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceEditorToolbarItem
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceEditorToolbarSize
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceEditorToolbarSizeSelector
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceEditorToolbarPositionSelector
import me.rerere.rikkihub.ui.pages.extensions.workspace.workspaceEditorToolbarOrder
import me.rerere.rikkihub.ui.pages.extensions.workspace.WORKSPACE_TOOLBAR_PER_ROW
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableColumn

/** 设置-偏好设置-工作区：编辑器文字外观、自动补全成对符号，以及快捷栏的位置（顶部/底部）、单栏/多栏（每栏按钮数）、尺寸与按钮顺序。 */
@Composable
fun SettingPreferencesWorkspacePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateDisplaySetting(setting: DisplaySetting) {
        val previous = displaySetting
        displaySetting = setting
        vm.updateDisplaySettings(previous, setting)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_workspace)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.editor_typography_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.editor_typography_settings_desc)) },
                        supportingContent = {
                            Column(Modifier.padding(top = 8.dp)) {
                                EditorTypographyControls(
                                    typography = displaySetting.appEditorTypography,
                                    display = displaySetting,
                                    default = EditorTypography.APP_DEFAULT,
                                    onChange = { updateDisplaySetting(displaySetting.copy(workspaceEditorTypography = it)) },
                                )
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_workspace_editor_input)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_workspace_editor_auto_pair)) },
                        supportingContent = { Text(stringResource(R.string.setting_workspace_editor_auto_pair_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.workspaceEditorAutoPair,
                                onCheckedChange = { updateDisplaySetting(displaySetting.copy(workspaceEditorAutoPair = it)) },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_workspace_editor_toolbar)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_workspace_editor_toolbar_position)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_workspace_editor_toolbar_position_desc))
                                WorkspaceEditorToolbarPositionSelector(
                                    atBottom = displaySetting.workspaceEditorToolbarAtBottom,
                                    onChange = { updateDisplaySetting(displaySetting.copy(workspaceEditorToolbarAtBottom = it)) },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_workspace_editor_toolbar_layout)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_workspace_editor_toolbar_layout_desc))
                                ToolbarLayoutSelector(
                                    doubleRow = displaySetting.workspaceEditorToolbarDoubleRow,
                                    onChange = { updateDisplaySetting(displaySetting.copy(workspaceEditorToolbarDoubleRow = it)) },
                                )
                                if (displaySetting.workspaceEditorToolbarDoubleRow) ToolbarPerRowStepper(
                                    perRow = displaySetting.workspaceEditorToolbarPerRow.coerceIn(WORKSPACE_TOOLBAR_PER_ROW),
                                    onChange = { updateDisplaySetting(displaySetting.copy(workspaceEditorToolbarPerRow = it)) },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_workspace_editor_toolbar_size)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_workspace_editor_toolbar_size_desc))
                                WorkspaceEditorToolbarSizeSelector(
                                    selected = WorkspaceEditorToolbarSize.of(displaySetting.workspaceEditorToolbarSize),
                                    onChange = { updateDisplaySetting(displaySetting.copy(workspaceEditorToolbarSize = it.id)) },
                                )
                            }
                        },
                    )
                }
            }

            item {
                ToolbarOrderCard(
                    displaySetting = displaySetting,
                    onOrderChange = { order ->
                        updateDisplaySetting(displaySetting.copy(workspaceEditorToolbarOrder = order))
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolbarLayoutSelector(doubleRow: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(
        false to R.string.setting_workspace_editor_toolbar_single,
        true to R.string.setting_workspace_editor_toolbar_double,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = { onChange(value) },
                selected = doubleRow == value,
            ) {
                Text(stringResource(label))
            }
        }
    }
}

/** 多栏时每栏几个按钮（3～15），按钮按顺序每这么多个排一栏。 */
@Composable
private fun ToolbarPerRowStepper(perRow: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.setting_workspace_editor_toolbar_per_row),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange(perRow - 1) }, enabled = perRow > WORKSPACE_TOOLBAR_PER_ROW.first) {
            Icon(HugeIcons.MinusSign, contentDescription = null)
        }
        Text(
            perRow.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp).testTag("workspace_toolbar_per_row"),
        )
        IconButton(onClick = { onChange(perRow + 1) }, enabled = perRow < WORKSPACE_TOOLBAR_PER_ROW.last) {
            Icon(HugeIcons.PlusSign, contentDescription = null)
        }
    }
}

@Composable
private fun ToolbarOrderCard(
    displaySetting: DisplaySetting,
    onOrderChange: (List<String>) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val items = workspaceEditorToolbarOrder(displaySetting.workspaceEditorToolbarOrder)
    // Which row each button lands on in multi-row mode (all buttons, as in a Markdown file), so the rows show while ordering.
    val perRow = displaySetting.workspaceEditorToolbarPerRow.coerceIn(WORKSPACE_TOOLBAR_PER_ROW)

    Card(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.setting_workspace_editor_toolbar_order),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.setting_workspace_editor_toolbar_order_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onOrderChange(emptyList()) },
                    enabled = items != WorkspaceEditorToolbarItem.entries,
                ) {
                    Text(stringResource(R.string.setting_workspace_editor_toolbar_order_reset))
                }
            }

            ReorderableColumn(
                list = items,
                onSettle = { fromIndex, toIndex ->
                    val reordered = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                    onOrderChange(reordered.map { it.id })
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { index, item, isDragging ->
                key(item.id) {
                    ReorderableItem {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(if (isDragging) 0.95f else 1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .draggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(HugeIcons.DragDropVertical, contentDescription = null)
                                }
                                Text(
                                    text = stringResource(item.label),
                                    modifier = Modifier.weight(1f),
                                )
                                if (displaySetting.workspaceEditorToolbarDoubleRow) {
                                    Text(
                                        text = stringResource(R.string.setting_workspace_editor_toolbar_row_n, index / perRow + 1),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
