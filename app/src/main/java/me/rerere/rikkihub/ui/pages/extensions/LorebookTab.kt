package me.rerere.rikkihub.ui.pages.extensions

import me.rerere.rikkihub.ui.theme.AppSpacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share03
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.export.LorebookSerializer
import me.rerere.rikkihub.data.export.SillyTavernLorebookSerializer
import me.rerere.rikkihub.data.export.rememberExporter
import me.rerere.rikkihub.data.export.rememberImporter
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.ExportDialog
import me.rerere.rikkihub.ui.components.ui.Tag
import me.rerere.rikkihub.ui.components.ui.TagType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkihub.ui.components.ui.CardGroupDivider
import me.rerere.rikkihub.ui.components.ui.cardGroupItemShape

/**
 * 世界书列表：每本书一张卡（名称、条目数、总开关），点开进条目页；可导入（本应用格式、旧版文件、酒馆世界书文件）和导出。
 * 全局世界书设置的入口放在列表顶部。
 */
@Composable
internal fun LorebookTab(
    books: List<Lorebook>,
    onUpdate: ((List<Lorebook>) -> List<Lorebook>) -> Unit,
    onOpen: (Lorebook) -> Unit,
    onOpenGlobalSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 第 0 项是顶部的全局设置入口，书从第 1 项开始
        onUpdate { list ->
            val f = from.index - 1
            val t = to.index - 1
            if (f in list.indices && t in list.indices) list.toMutableList().apply { add(t, removeAt(f)) } else list
        }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate { it + imported }
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

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
            item(key = "global-settings") {
                OutlinedCard(
                    onClick = onOpenGlobalSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AppSpacing.sm),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(AppSpacing.sm),
                    ) {
                        Icon(HugeIcons.Settings03, null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.worldinfo_global_settings), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.worldinfo_global_settings_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(HugeIcons.ArrowRight01, null)
                    }
                }
            }
            if (books.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_lorebook_empty),
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
                itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                    ReorderableItem(state = reorderableState, key = book.id) { isDragging ->
                        LorebookCard(
                            book = book,
                            shape = cardGroupItemShape(index, books.size),
                            divider = index > 0,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onOpen = { onOpen(book) },
                            onDelete = { onUpdate { list -> list.filter { it.id != book.id } } },
                            onToggleEnabled = { enabled ->
                                onUpdate { list -> list.map { if (it.id == book.id) it.copy(enabled = enabled) else it } }
                            },
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
                    val book = Lorebook(name = "")
                    onUpdate { it + book }
                    onOpen(book)
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_lorebook))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LorebookCard(
    book: Lorebook,
    shape: Shape,
    divider: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }

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
                    .clickable(onClick = onOpen)
                    .padding(start = AppSpacing.sm, end = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = book.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_lorebook) },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (book.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(type = TagType.INFO) { Text(stringResource(R.string.prompt_page_entries_count_format, book.entries.size)) }
                        if (book.description.isNotBlank()) {
                            Text(
                                text = book.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(onClick = { showExport = true }) {
                    Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                }
                Switch(checked = book.enabled, onCheckedChange = onToggleEnabled)
            }
        }
    }

    if (showExport) LorebookExportDialog(book = book, onDismiss = { showExport = false })
}

/** 导出世界书：先选格式（本应用格式 / 酒馆世界书文件），再走通用的保存或分享。 */
@Composable
internal fun LorebookExportDialog(book: Lorebook, onDismiss: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    val native = rememberExporter(book, LorebookSerializer)
    val tavern = rememberExporter(book, SillyTavernLorebookSerializer)
    when (choice) {
        "native" -> ExportDialog(exporter = native, onDismiss = onDismiss)
        "tavern" -> ExportDialog(exporter = tavern, onDismiss = onDismiss)
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.worldinfo_export_format)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedCard(onClick = { choice = "native" }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(AppSpacing.sm)) {
                            Text(stringResource(R.string.worldinfo_export_native), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.worldinfo_export_native_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedCard(onClick = { choice = "tavern" }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(AppSpacing.sm)) {
                            Text(stringResource(R.string.worldinfo_export_tavern), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.worldinfo_export_tavern_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
