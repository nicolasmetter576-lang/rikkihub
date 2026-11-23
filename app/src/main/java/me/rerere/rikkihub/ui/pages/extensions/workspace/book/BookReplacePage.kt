package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.book.BookAiException
import me.rerere.rikkihub.data.book.BookReplace
import me.rerere.rikkihub.data.book.BookReplaceFile
import me.rerere.rikkihub.data.book.BookReplaceResult
import me.rerere.rikkihub.data.book.BookUndoResult
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ReplaceHit
import me.rerere.rikkihub.data.book.ReplacePair
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.util.Date

/** 全书查找替换：先列出全部命中再勾选，逐章带版本校验写入，可撤销。 */
@Composable
fun BookReplacePage(workspaceId: String) {
    val vm: BookReplaceVM = koinViewModel(key = "book-replace-$workspaceId", parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    var historyOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val noModel = stringResource(R.string.book_ai_no_model)
    LaunchedEffect(state.error, state.aiError) {
        val message = state.error ?: noModel.takeIf { state.aiError == BookAiException.Reason.NO_MODEL }
        if (message != null) {
            toaster.show(message, type = ToastType.Error)
            vm.clearError()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_replace_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { historyOpen = true }, modifier = Modifier.testTag("book-replace-history")) {
                        Icon(HugeIcons.TransactionHistory, stringResource(R.string.book_replace_history))
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = AppSpacing.md, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Button(
                        onClick = vm::search,
                        enabled = state.canSearch,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).testTag("book-replace-search"),
                    ) { Text(stringResource(R.string.book_replace_search)) }
                    Button(
                        onClick = { if (state.deletes) confirm = true else vm.replace() },
                        enabled = !state.busy && state.selectedCount > 0,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).testTag("book-replace-apply"),
                    ) { Text(stringResource(R.string.book_replace_apply, state.selectedCount)) }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        ) {
            itemsIndexed(state.pairs, key = { index, _ -> "pair$index" }) { index, pair ->
                PairRow(
                    pair = pair,
                    onChange = { vm.setPair(index, it) },
                    onRemove = { vm.removePair(index) }.takeIf { state.pairs.size > 1 || pair != ReplacePair("", "") },
                )
            }
            item("options") { Options(state, vm) }
            state.aliases?.let { aliases ->
                item("aliases") { Aliases(aliases.isEmpty()) { aliases.forEach { alias ->
                    AssistChip(
                        onClick = { vm.takeAlias(alias) },
                        label = { Text(if (alias.replacement.isBlank()) "${alias.alias} ×${alias.count}" else "${alias.alias} → ${alias.replacement} ×${alias.count}") },
                        leadingIcon = { Icon(HugeIcons.Add01, null, Modifier.size(16.dp)) },
                    )
                } } }
            }
            state.result?.let { result -> item("result") { ResultCard(result, onUndo = { result.undoId?.let(vm::undo) }) } }
            state.undone?.let { undone -> item("undone") { UndoneCard(undone) } }
            state.preview?.let { preview ->
                item("summary") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (preview.files.isEmpty()) stringResource(R.string.book_replace_none)
                            else stringResource(R.string.book_replace_found, preview.files.sumOf { it.hits.size }, preview.files.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (preview.files.isNotEmpty()) {
                            TextButton(onClick = vm::toggleEverything) {
                                Text(stringResource(if (state.unselected.isEmpty()) R.string.book_replace_select_none else R.string.book_replace_select_all))
                            }
                        }
                    }
                }
                state.mentions?.takeIf { !it.isEmpty }?.let { mentions -> item("mentions") { Mentions(mentions) } }
                if (preview.unreadable.isNotEmpty()) {
                    item("unreadable") { Note(stringResource(R.string.book_replace_unreadable, preview.unreadable.joinToString("、"))) }
                }
                preview.files.forEach { file ->
                    item("file:${file.path}") { FileRow(file, state, onToggle = { vm.toggleFile(file.path) }) }
                    items(file.hits, key = { "${file.path}:${it.start}" }) { hit ->
                        val key = HitKey(file.path, hit.start)
                        HitRow(hit, state.query?.pairs?.getOrNull(hit.pair), selected = key !in state.unselected, onToggle = { vm.toggle(key) })
                    }
                }
            }
        }
    }
    RikkaConfirmDialog(
        show = confirm,
        title = stringResource(R.string.book_replace_apply, state.selectedCount),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirm = false
            vm.replace()
        },
        onDismiss = { confirm = false },
    ) {
        Text(stringResource(R.string.book_replace_delete_confirm, state.query?.pairs.orEmpty().filter { it.replace.isEmpty() }.joinToString("、") { it.find }))
    }
    if (historyOpen) HistoryDialog(state, onUndo = { id ->
        historyOpen = false
        vm.undo(id)
    }, onDismiss = { historyOpen = false })
}

@Composable
private fun PairRow(pair: ReplacePair, onChange: (ReplacePair) -> Unit, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            pair.find, { onChange(pair.copy(find = it.replace("\n", ""))) },
            placeholder = { Text(stringResource(R.string.book_replace_find)) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("book-replace-find"),
        )
        Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp))
        OutlinedTextField(
            pair.replace, { onChange(pair.copy(replace = it.replace("\n", ""))) },
            placeholder = { Text(stringResource(R.string.book_replace_with)) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("book-replace-with"),
        )
        IconButton(onClick = { onRemove?.invoke() }, enabled = onRemove != null, modifier = Modifier.size(36.dp)) {
            Icon(HugeIcons.Cancel01, stringResource(R.string.common_delete), Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(state: BookReplaceUiState, vm: BookReplaceVM) {
    var scopeMenu by remember { mutableStateOf(false) }
    val volumes = state.book?.manifest?.items.orEmpty().filterIsInstance<BookVolume>()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { vm.addPair() },
            enabled = state.pairs.size < BookReplace.MAX_PAIRS,
            label = { Text(stringResource(R.string.book_replace_add_pair)) },
        )
        Box {
            AssistChip(
                onClick = { scopeMenu = true },
                label = { Text(volumes.firstOrNull { it.id == state.volumeId }?.title ?: stringResource(R.string.book_replace_scope_all)) },
            )
            DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.book_replace_scope_all)) }, onClick = { scopeMenu = false; vm.setVolume(null) })
                volumes.forEach { volume ->
                    DropdownMenuItem(text = { Text(volume.title) }, onClick = { scopeMenu = false; vm.setVolume(volume.id) })
                }
            }
        }
        FilterChip(
            selected = !state.ignoreCase,
            onClick = { vm.setIgnoreCase(!state.ignoreCase) },
            label = { Text(stringResource(R.string.book_replace_case)) },
        )
        if (state.volumeId == null && state.book?.unlisted.orEmpty().isNotEmpty()) {
            FilterChip(
                selected = state.includeUnlisted,
                onClick = { vm.setIncludeUnlisted(!state.includeUnlisted) },
                label = { Text(stringResource(R.string.book_replace_unlisted)) },
            )
        }
        val first = state.pairs.first()
        if (state.findingAliases) {
            AssistChip(
                onClick = vm::cancelAliases,
                label = { Text(stringResource(R.string.book_replace_finding_aliases)) },
                leadingIcon = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) },
            )
        } else {
            AssistChip(
                onClick = { vm.findAliases(first) },
                enabled = first.find.isNotBlank() && first.replace.isNotBlank(),
                label = { Text(stringResource(R.string.book_replace_aliases)) },
                modifier = Modifier.testTag("book-replace-aliases"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Aliases(empty: Boolean, chips: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            stringResource(if (empty) R.string.book_replace_aliases_none else R.string.book_replace_aliases_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

@Composable
private fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
)

@Composable
private fun Mentions(mentions: BookMentions) {
    if (mentions.lorebooks.isNotEmpty()) Note(stringResource(R.string.book_replace_in_lorebooks, mentions.lorebooks.joinToString("、")))
    if (mentions.characters.isNotEmpty()) Note(stringResource(R.string.book_replace_in_characters, mentions.characters.joinToString("、")))
}

@Composable
private fun FileRow(file: BookReplaceFile, state: BookReplaceUiState, onToggle: () -> Unit) {
    val entry = state.book?.entryByPath(file.path)
    val selected = file.hits.count { HitKey(file.path, it.start) !in state.unselected }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected == file.hits.size, onCheckedChange = { onToggle() })
        Text(
            entry?.let { "${it.number}  ${it.title}" } ?: file.path,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$selected/${file.hits.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HitRow(hit: ReplaceHit, pair: ReplacePair?, selected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        val error = MaterialTheme.colorScheme.error
        val primary = MaterialTheme.colorScheme.primary
        Text(
            buildAnnotatedString {
                append(hit.before)
                withStyle(SpanStyle(color = error, textDecoration = TextDecoration.LineThrough)) { append(hit.match) }
                if (pair != null) withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append(pair.replace) }
                append(hit.after)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun ResultCard(result: BookReplaceResult, onUndo: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.book_replace_done, result.replaced, result.changed.size), style = MaterialTheme.typography.titleSmall)
            if (result.conflicts.isNotEmpty()) Note(stringResource(R.string.book_replace_conflicts, result.conflicts.joinToString("、")))
            if (result.failed.isNotEmpty()) Note(stringResource(R.string.book_replace_failed, result.failed.joinToString("、")))
            if (result.undoId != null) TextButton(onClick = onUndo) { Text(stringResource(R.string.book_replace_undo)) }
        }
    }
}

@Composable
private fun UndoneCard(undone: BookUndoResult) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.book_replace_undone, undone.restored.size), style = MaterialTheme.typography.titleSmall)
            if (undone.changedSince.isNotEmpty()) Note(stringResource(R.string.book_replace_undo_changed, undone.changedSince.joinToString("、")))
            if (undone.failed.isNotEmpty()) Note(stringResource(R.string.book_replace_failed, undone.failed.joinToString("、")))
        }
    }
}

@Composable
private fun HistoryDialog(state: BookReplaceUiState, onUndo: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_replace_history)) },
        text = {
            if (state.history.isEmpty()) Text(stringResource(R.string.book_replace_history_empty))
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(state.history, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(
                                    R.string.book_replace_history_line,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.time)),
                                    entry.replaced, entry.files.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onUndo(entry.id) }, enabled = !state.busy) { Text(stringResource(R.string.book_replace_undo)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_done)) } },
    )
}
