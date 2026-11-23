package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.ai.book.BookAiException
import me.rerere.rikkihub.data.ai.book.OutlineProgress
import me.rerere.rikkihub.data.ai.book.OutlineTask
import me.rerere.rikkihub.data.book.BeatState
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.OutlineSection
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

/** 反向大纲：每章提取、对照到哪了；批量提取和对照在底部。 */
@Composable
fun BookOutlinePage(workspaceId: String) {
    val vm: BookOutlineVM = koinViewModel(key = "book-outline-$workspaceId", parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle(null)
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    var menu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var choosingFile by remember { mutableStateOf(false) }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val importedMessage = state.imported?.let { stringResource(R.string.book_outline_imported, it) }
    LaunchedEffect(importedMessage, state.error) {
        importedMessage?.let { toaster.show(it, type = ToastType.Success) }
        state.error?.let { toaster.show(it, type = ToastType.Error) }
        vm.clearMessages()
    }
    val pending = state.rows.filter { it.pending }
    val comparable = state.rows.count { it.comparePending }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_outline_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("book-outline-menu")) {
                            Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.book_outline_import)) }, onClick = {
                                menu = false
                                choosingFile = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.book_settings_title)) }, onClick = {
                                menu = false
                                nav.navigate(Screen.SettingBook)
                            })
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.md, vertical = 6.dp)) {
                    OutlineProgressRow(progress, title = { id -> state.book?.entry(id)?.title.orEmpty() }, onStop = vm::stop, onDismiss = vm::dismissProgress)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = vm::comparePending,
                            enabled = progress?.running != true && comparable > 0,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f).testTag("book-outline-compare"),
                        ) { Text(stringResource(R.string.book_outline_compare_count, comparable), maxLines = 1) }
                        Button(
                            onClick = {
                                vm.estimate()
                                confirming = true
                            },
                            enabled = progress?.running != true && pending.isNotEmpty(),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f).testTag("book-outline-extract"),
                        ) { Text(stringResource(R.string.book_outline_extract_count, pending.size), maxLines = 1) }
                    }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        ) {
            items(state.rows, key = { it.entry.id }) { row ->
                OutlineRowItem(row) { nav.navigate(Screen.ChapterOutline(workspaceId, row.entry.id)) }
            }
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.book_outline_title)) },
        text = {
            val estimate = state.estimate
            Text(
                if (estimate == null) stringResource(R.string.book_outline_estimating, pending.size)
                else stringResource(R.string.book_outline_estimate, pending.size, NumberFormat.getIntegerInstance().format(estimate)),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                confirming = false
                vm.extractPending()
            }) { Text(stringResource(R.string.book_outline_start)) }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    val book = state.book
    if (choosingFile && book != null) FileChooser(book.unlisted + book.manifest.ignored, onDismiss = { choosingFile = false }) {
        choosingFile = false
        vm.openOutlineFile(it)
    }
    val sections = state.sections
    if (sections != null && book != null) SectionsDialog(book, sections, onDismiss = vm::closeOutlineFile, onConfirm = vm::importSections)
}

@Composable
private fun OutlineRowItem(row: OutlineRow, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.entry.number.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(row.entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = ArrayList<String>()
            if (row.notes.beats.isNotEmpty()) parts += stringResource(R.string.book_outline_plan_count, row.notes.beats.size)
            row.notes.outline?.let { parts += stringResource(R.string.book_outline_point_count, it.points.size) }
            for (beatState in listOf(BeatState.CHANGED, BeatState.MISSING)) {
                val count = row.count(beatState)
                if (count > 0) parts += beatStateText(beatState) + " " + count
            }
            if (parts.isNotEmpty()) Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val label = when {
            row.stale -> R.string.book_outline_stale
            !row.extracted && row.entry.words > 0 -> R.string.book_outline_not_extracted
            row.comparePending -> R.string.book_outline_not_compared
            else -> null
        }
        if (label != null) Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = if (row.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 正在跑的任务，或因错停下的原因。 */
@Composable
internal fun OutlineProgressRow(progress: OutlineProgress?, title: (String) -> String, onStop: () -> Unit, onDismiss: () -> Unit) {
    if (progress == null) return
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val task = progress.current
        if (task != null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            val label = stringResource(
                when (task) {
                    is OutlineTask.Extract -> R.string.book_outline_extracting
                    is OutlineTask.Compare -> R.string.book_outline_comparing
                },
                progress.done + 1,
                progress.total,
            )
            Text("$label  ${title(task.chapterId)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onStop, modifier = Modifier.testTag("book-outline-stop")) { Text(stringResource(R.string.stop)) }
        } else {
            val reason = progress.aiError?.let { bookAiErrorText(it) } ?: progress.error.orEmpty()
            Text(
                stringResource(R.string.book_outline_stopped, progress.done, progress.total, reason),
                Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_terminal_close)) }
        }
    }
}

@Composable
internal fun bookAiErrorText(reason: BookAiException.Reason): String = stringResource(
    when (reason) {
        BookAiException.Reason.NO_MODEL -> R.string.book_ai_no_model
        BookAiException.Reason.EMPTY_RESULT -> R.string.zaizao_error_empty_result
    },
)

@Composable
internal fun beatStateText(state: BeatState): String = stringResource(
    when (state) {
        BeatState.WRITTEN -> R.string.book_beat_written
        BeatState.CHANGED -> R.string.book_beat_changed
        BeatState.MISSING -> R.string.book_beat_missing
    },
)

@Composable
private fun FileChooser(paths: List<String>, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_outline_import)) },
        text = {
            if (paths.isEmpty()) Text(stringResource(R.string.book_outline_no_file))
            else LazyColumn {
                items(paths) { path ->
                    Box(Modifier.fillMaxWidth().clickable { onChoose(path) }.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                        Text(path, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SectionsDialog(book: Book, initial: List<OutlineSection>, onDismiss: () -> Unit, onConfirm: (List<OutlineSection>) -> Unit) {
    var sections by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_outline_import)) },
        text = {
            if (sections.isEmpty()) Text(stringResource(R.string.book_outline_no_sections))
            else LazyColumn {
                item("hint") {
                    Text(stringResource(R.string.book_outline_import_hint), style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(sections) { index, section ->
                    var menu by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 6.dp)) {
                        Text("${section.title}（${section.beats.size}）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            val entry = section.chapterId?.let(book::entry)
                            Text(
                                if (entry == null) stringResource(R.string.book_outline_skip) else "→ ${entry.number}  ${entry.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                fun assign(id: String?) {
                                    menu = false
                                    sections = sections.mapIndexed { i, item -> if (i == index) item.copy(chapterId = id) else item }
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.book_outline_skip)) }, onClick = { assign(null) })
                                book.entries.forEach { chapter ->
                                    DropdownMenuItem(text = { Text("${chapter.number}  ${chapter.title}", maxLines = 1) }, onClick = { assign(chapter.id) })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sections) }, enabled = sections.any { it.chapterId != null }) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
