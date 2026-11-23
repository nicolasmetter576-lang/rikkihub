package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.transfer.BookImportException
import me.rerere.rikkihub.data.book.transfer.EpubException
import me.rerere.rikkihub.data.book.transfer.ImportRow
import me.rerere.rikkihub.data.book.transfer.SplitIssue
import me.rerere.rikkihub.data.book.transfer.SplitRule
import me.rerere.rikkihub.data.book.transfer.TextDecoding
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.NumberFormat

/** 导入 TXT / EPUB：先看拆出来的章节，改好再写。 */
@Composable
fun BookImportPage(workspaceId: String) {
    val vm: BookImportVM = koinViewModel(key = "book-import-$workspaceId", parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.open(uri) }
    var renaming by remember { mutableStateOf<ImportRow?>(null) }
    var ruleDialog by remember { mutableStateOf(false) }
    val doneMessage = state.done?.let { stringResource(R.string.book_import_done, it) }
    LaunchedEffect(doneMessage) {
        if (doneMessage != null) {
            toaster.show(doneMessage, type = ToastType.Success)
            nav.popBackStack()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            toaster.show(it, type = ToastType.Error)
            vm.clearError()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.fileName ?: stringResource(R.string.book_import_title), maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.md, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).testTag("book-import-pick"),
                    ) { Text(stringResource(R.string.book_import_pick)) }
                    Button(
                        onClick = vm::import,
                        enabled = !state.busy && state.rows.isNotEmpty(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).testTag("book-import-apply"),
                    ) { Text(stringResource(R.string.book_import_apply, state.rows.size)) }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        ) {
            val problem = when {
                state.epubError != null -> when (state.epubError) {
                    EpubException.Reason.DRM -> R.string.book_import_drm
                    EpubException.Reason.TOO_LARGE -> R.string.book_import_too_large
                    else -> R.string.book_import_not_epub
                }
                state.importError != null -> when (state.importError) {
                    BookImportException.Reason.TOO_LARGE -> R.string.book_import_too_large
                    BookImportException.Reason.UNKNOWN_ENCODING -> R.string.book_import_encoding_failed
                    else -> R.string.book_import_no_text
                }
                state.fileName == null && !state.busy -> R.string.book_import_hint
                else -> null
            }
            if (problem != null) item("problem") {
                Text(stringResource(problem), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(AppSpacing.sm))
            }
            if (state.isText) item("options") { TextOptions(state, vm, onRule = { ruleDialog = true }) }
            if (state.rows.isNotEmpty()) item("summary") {
                val flagged = state.rows.count { it.issues.isNotEmpty() }
                Text(
                    stringResource(R.string.book_import_summary, state.rows.size, NumberFormat.getIntegerInstance().format(state.rows.sumOf { it.words })) +
                        if (flagged > 0) " · " + stringResource(R.string.book_import_flagged, flagged) else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(4.dp),
                )
            }
            itemsIndexed(state.rows, key = { _, row -> row.source }) { index, row ->
                val newVolume = row.volume?.takeIf { it != state.rows.take(index).lastOrNull { previous -> previous.volume != null }?.volume }
                RowItem(index, row, newVolume, onRename = { renaming = row }, onMerge = { vm.mergeUp(row.source) })
            }
        }
    }
    renaming?.let { row ->
        var title by remember(row) { mutableStateOf(row.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.book_chapter_title)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(row.source, title)
                    renaming = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (ruleDialog) RuleDialog(state.rule, onDismiss = { ruleDialog = false }) {
        ruleDialog = false
        vm.setRule(it)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextOptions(state: BookImportUiState, vm: BookImportVM, onRule: () -> Unit) {
    var charsetMenu by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            AssistChip(
                onClick = { charsetMenu = true },
                label = { Text(stringResource(R.string.book_import_encoding, state.charset ?: state.detectedCharset ?: "?")) },
            )
            DropdownMenu(expanded = charsetMenu, onDismissRequest = { charsetMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.book_import_auto)) }, onClick = { charsetMenu = false; vm.setCharset(null) })
                TextDecoding.CHARSETS.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { charsetMenu = false; vm.setCharset(name) })
                }
            }
        }
        AssistChip(onClick = onRule, label = { Text(stringResource(R.string.book_import_rule, ruleName(state.rule))) })
    }
}

@Composable
private fun ruleName(rule: SplitRule): String = stringResource(
    when (rule) {
        SplitRule.Auto -> R.string.book_import_rule_auto
        is SplitRule.Headings -> R.string.book_import_rule_headings
        is SplitRule.BlankLines -> R.string.book_import_rule_blank
        is SplitRule.FixedSize -> R.string.book_import_rule_size
        is SplitRule.LinePrefix -> R.string.book_import_rule_prefix
    },
)

@Composable
private fun RuleDialog(current: SplitRule, onDismiss: () -> Unit, onConfirm: (SplitRule) -> Unit) {
    var rule by remember { mutableStateOf(current) }
    val options = listOf(SplitRule.Auto, SplitRule.Headings(2), SplitRule.BlankLines(2), SplitRule.FixedSize(3000), SplitRule.LinePrefix(""))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_import_rule_title)) },
        text = {
            Column {
                options.forEach { option ->
                    val selected = option::class == rule::class
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected) { if (!selected) rule = option },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(ruleName(option), Modifier.weight(1f))
                        val value = rule
                        if (selected) when (value) {
                            is SplitRule.Headings -> NumberInput(value.level) { rule = SplitRule.Headings(it.coerceIn(1, 6)) }
                            is SplitRule.BlankLines -> NumberInput(value.count) { rule = SplitRule.BlankLines(it.coerceAtLeast(1)) }
                            is SplitRule.FixedSize -> NumberInput(value.chars) { rule = SplitRule.FixedSize(it.coerceAtLeast(100)) }
                            is SplitRule.LinePrefix -> OutlinedTextField(value.prefix, { rule = SplitRule.LinePrefix(it) }, singleLine = true, modifier = Modifier.widthIn(max = 120.dp))
                            SplitRule.Auto -> Unit
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(rule) }, enabled = (rule as? SplitRule.LinePrefix)?.prefix?.isBlank() != true) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun NumberInput(value: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { input ->
            text = input.filter { it.isDigit() }.take(6)
            text.toIntOrNull()?.let(onChange)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.widthIn(max = 96.dp),
    )
}

@Composable
private fun RowItem(index: Int, row: ImportRow, newVolume: String?, onRename: () -> Unit, onMerge: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        if (newVolume != null) {
            Text(newVolume, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (index + 1).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(row.title.ifBlank { stringResource(R.string.book_import_opening) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.issues.isNotEmpty()) {
                    val labels = ArrayList<String>()
                    for (issue in row.issues) labels += stringResource(
                        when (issue) {
                            SplitIssue.NUMBER_GAP -> R.string.book_import_issue_gap
                            SplitIssue.NUMBER_REPEAT -> R.string.book_import_issue_repeat
                            SplitIssue.TOO_LONG -> R.string.book_import_issue_long
                            SplitIssue.TOO_SHORT -> R.string.book_import_issue_short
                        },
                    )
                    Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(NumberFormat.getIntegerInstance().format(row.words), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options), Modifier.size(18.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.book_import_rename)) }, onClick = { menu = false; onRename() })
                    if (index > 0) DropdownMenuItem(text = { Text(stringResource(R.string.book_import_merge)) }, onClick = { menu = false; onMerge() })
                }
            }
        }
    }
}
