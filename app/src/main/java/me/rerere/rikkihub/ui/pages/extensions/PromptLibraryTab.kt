package me.rerere.rikkihub.ui.pages.extensions

import me.rerere.rikkihub.ui.theme.AppSpacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.export.ModeInjectionSerializer
import me.rerere.rikkihub.data.export.rememberExporter
import me.rerere.rikkihub.data.export.rememberImporter
import me.rerere.rikkihub.data.model.EntryPlacement
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.depth
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.rikkihub.data.model.newLibraryEntry
import me.rerere.rikkihub.data.model.order
import me.rerere.rikkihub.data.model.placement
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.rikkihub.ui.components.prompt.PromptEntryFields
import me.rerere.rikkihub.ui.components.prompt.PromptEntryScope
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.components.ui.ExportDialog
import me.rerere.rikkihub.ui.components.ui.FormItem
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.components.ui.Tag
import me.rerere.rikkihub.ui.components.ui.TagType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import me.rerere.tavern.prompt.PromptEntry
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkihub.ui.components.ui.CardGroupDivider
import me.rerere.rikkihub.ui.components.ui.cardGroupItemShape

/**
 * 提示词库（原「模式注入」）：全局可复用的提示词条目，助手和会话按需选用。
 * 条目和助手布局里的文本条目是同一种东西，走同一个组装器；这里多出的只有「放在哪个锚点」（相对条目）和总开关。
 *
 * 编辑是实时的：每次改动都按标识符落在最新的库上（[onUpdate] 收到的是对最新列表的变换），关掉面板就是完成。
 */
@Composable
internal fun PromptLibraryTab(
    library: List<PromptEntry>,
    onUpdate: ((List<PromptEntry>) -> List<PromptEntry>) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onUpdate { list -> list.toMutableList().apply { add(to.index, removeAt(from.index)) } }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(ModeInjectionSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate { it + imported }
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }
    val newEntryName = stringResource(R.string.prompt_layout_new_entry)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false },
                ),
            contentPadding = PaddingValues(AppSpacing.sm) + PaddingValues(bottom = 128.dp),
            state = lazyListState,
        ) {
            if (library.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_mode_injection_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                itemsIndexed(library, key = { _, entry -> entry.identifier }) { index, entry ->
                    ReorderableItem(state = reorderableState, key = entry.identifier) { isDragging ->
                        PromptLibraryCard(
                            entry = entry,
                            shape = cardGroupItemShape(index, library.size),
                            divider = index > 0,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onEdit = { editingId = entry.identifier },
                            onDelete = { onUpdate { list -> list.filter { it.identifier != entry.identifier } } },
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(
                onClick = {
                    val entry = newLibraryEntry(newEntryName)
                    onUpdate { it + entry }
                    editingId = entry.identifier
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_mode_injection))
                        }
                    }
                }
            }
        }
    }

    library.firstOrNull { it.identifier == editingId }?.let { entry ->
        PromptLibraryEditSheet(
            entry = entry,
            onDismiss = { editingId = null },
            onChange = { transform ->
                onUpdate { list -> list.map { if (it.identifier == entry.identifier) transform(it).copy(identifier = it.identifier) else it } }
            },
        )
    }
}

@Composable
private fun PromptLibraryCard(
    entry: PromptEntry,
    shape: Shape,
    divider: Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(entry, ModeInjectionSerializer)

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        Card(shape = shape, colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
            if (divider) CardGroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sm, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = entry.name.orEmpty().ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(type = TagType.INFO) { Text(placementLabel(entry)) }
                        Tag(type = TagType.DEFAULT) {
                            Text(
                                if (entry.placement == EntryPlacement.IN_CHAT) stringResource(R.string.prompt_library_order_format, entry.order)
                                else stringResource(R.string.prompt_page_priority_format, entry.order)
                            )
                        }
                        if (!entry.libraryEnabled) {
                            Tag(type = TagType.WARNING) { Text(stringResource(R.string.prompt_page_disabled)) }
                        }
                    }
                }
                IconButton(onClick = { showExportDialog = true }) {
                    Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(exporter = exporter, onDismiss = { showExportDialog = false })
    }
}

@Composable
private fun placementLabel(entry: PromptEntry): String = when {
    entry.placement == EntryPlacement.IN_CHAT -> stringResource(R.string.prompt_library_in_chat_depth, entry.depth)
    else -> stringResource(
        when (entry.libraryAnchor) {
            LibraryEntryMeta.BEFORE_SYSTEM -> R.string.prompt_layout_anchor_before_system
            LibraryEntryMeta.BEFORE_HISTORY -> R.string.prompt_layout_anchor_before_history
            else -> R.string.prompt_layout_anchor_after_system
        }
    )
}

@Composable
private fun PromptLibraryEditSheet(
    entry: PromptEntry,
    onDismiss: () -> Unit,
    onChange: ((PromptEntry) -> PromptEntry) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_mode_injection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CommitOnDoneTextField(
                    value = entry.name.orEmpty(),
                    onCommit = { value -> onChange { it.copy(name = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                )
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = entry.libraryEnabled,
                            onCheckedChange = { on -> onChange { it.withLibraryMeta(enabled = on) } },
                        )
                    },
                )
                PromptEntryFields(entry = entry, scope = PromptEntryScope.LIBRARY, onChange = onChange, showName = false)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_page_confirm)) }
            }
        }
    }
}
