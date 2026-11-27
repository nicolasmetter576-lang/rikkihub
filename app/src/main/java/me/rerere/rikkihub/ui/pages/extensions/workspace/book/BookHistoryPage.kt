package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.history.ChapterDiff
import me.rerere.rikkihub.data.book.history.HistoryEntry
import me.rerere.rikkihub.data.book.history.HistorySource
import me.rerere.rikkihub.data.book.history.TrashedFile
import me.rerere.rikkihub.ui.components.nav.BackButton
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

/** 一个文件的版本历史：列表 → 点一版与现在比较（窄屏上下排列），可取回一段或整章还原。 */
@Composable
fun BookHistoryPage(workspaceId: String, path: String, noticeId: String? = null) {
    val vm: BookHistoryVM = koinViewModel(key = "book-history-$workspaceId-$path-$noticeId", parameters = { parametersOf(workspaceId, path, noticeId) })
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var lockedRestore by remember { mutableStateOf(false) }
    BookHistoryNotices(vm.notices, onLocked = { lockedRestore = true })
    val selected = state.selected
    BackHandler(enabled = selected != null) { vm.select(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.book_history_title), maxLines = 1)
                        Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (selected == null) BackButton()
                    else IconButton(onClick = { vm.select(null) }) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) }
                },
                actions = {
                    if (selected != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // 锁还在用的永久版本不能删，说明原因
                            val inUse = selected.sha in state.inUse
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            stringResource(R.string.book_history_delete_version),
                                            color = if (inUse) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                        )
                                        if (inUse) Text(stringResource(R.string.book_lock_version_in_use), style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                enabled = !inUse,
                                onClick = {
                                    menu = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        bottomBar = {
            val comparison = state.comparison
            if (selected != null && comparison != null && comparison.diff.blocks.any { it is ChapterDiff.Block.Changed }) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.md, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Button(
                            onClick = { confirmRestore = true },
                            enabled = !state.busy,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.book_history_restore)) }
                    }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                selected == null -> BookHistoryList(state, vm.currentWriter, onToggle = vm::setOnlyBeforeAi, onSelect = vm::select)
                else -> BookHistoryCompare(state, selected, onTakeBack = vm::takeBack, onPutBack = vm::putBackNotice, onRelock = vm::relockNotice)
            }
        }
    }
    RikkaConfirmDialog(
        show = confirmRestore,
        title = stringResource(R.string.book_history_restore),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmRestore = false
            vm.restore()
        },
        onDismiss = { confirmRestore = false },
    ) { Text(stringResource(R.string.book_history_restore_confirm)) }
    RikkaConfirmDialog(
        show = confirmDelete,
        title = stringResource(R.string.book_history_delete_version),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmDelete = false
            vm.deleteSelected()
        },
        onDismiss = { confirmDelete = false },
    ) { Text(stringResource(R.string.book_history_delete_version_confirm)) }
    RikkaConfirmDialog(
        show = lockedRestore,
        title = stringResource(R.string.book_lock_unlock_and_restore),
        confirmText = stringResource(R.string.book_lock_unlock_and_restore),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            lockedRestore = false
            vm.unlockAndRestore()
        },
        onDismiss = { lockedRestore = false },
    ) { Text(stringResource(R.string.book_lock_restore_locked)) }
}

@Composable
private fun BookHistoryList(
    state: BookHistoryUiState,
    currentWriter: HistorySource?,
    onToggle: (Boolean) -> Unit,
    onSelect: (HistoryEntry.Version) -> Unit,
) {
    val shown = state.shown(currentWriter)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)) {
        if (state.paused) item { PausedNotice() }
        item {
            FilterChip(
                selected = state.onlyBeforeAi,
                onClick = { onToggle(!state.onlyBeforeAi) },
                label = { Text(stringResource(R.string.book_history_only_before_ai)) },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (shown.isEmpty()) item {
            Text(
                stringResource(R.string.book_history_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(AppSpacing.md),
            )
        }
        items(shown) { entry ->
            val time = formatHistoryTime(entry.at)
            when (entry) {
                is HistoryEntry.Version -> Column(
                    Modifier.fillMaxWidth().clickable { onSelect(entry) }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(historySourceLabel(entry.source, state.conversations), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val pinned = entry.sha in state.pinned
                    Text(
                        if (pinned) "$time · ${stringResource(R.string.book_lock_pinned_version)}" else time,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is HistoryEntry.Deleted -> HistoryInfoRow("${stringResource(R.string.book_history_deleted)} · ${historySourceLabel(entry.source, state.conversations)}", time)
                is HistoryEntry.Renamed -> HistoryInfoRow(stringResource(R.string.book_history_renamed, entry.from), time)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun HistoryInfoRow(text: String, time: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookHistoryCompare(
    state: BookHistoryUiState,
    selected: HistoryEntry.Version,
    onTakeBack: (Int) -> Unit,
    onPutBack: () -> Unit,
    onRelock: () -> Unit,
) {
    val comparison = state.comparison
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs)) {
        // 从“改了锁定内容”的提醒进来：二选一，放回或按现在的稿子重新锁定
        if (state.notice != null && comparison != null) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.book_lock_compare_notice), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPutBack, enabled = !state.busy) { Text(stringResource(R.string.book_lock_put_back)) }
                    TextButton(onClick = onRelock, enabled = !state.busy) { Text(stringResource(R.string.book_lock_relock)) }
                }
            }
        }
        item {
            Text(
                "${stringResource(R.string.book_history_this_version)} · ${historySourceLabel(selected.source, state.conversations)} · ${formatHistoryTime(selected.at)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        when {
            comparison == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            comparison.diff.blocks.none { it is ChapterDiff.Block.Changed } -> item { Text(stringResource(R.string.book_history_same)) }
            comparison.diff.rewritten -> {
                item { Text(stringResource(R.string.book_history_rewritten), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
                item { SelectionContainer { Text(comparison.old, style = MaterialTheme.typography.bodyMedium) } }
            }
            else -> items(comparison.diff.blocks) { block ->
                when (block) {
                    is ChapterDiff.Block.Same -> SameBlock(block.lines)
                    is ChapterDiff.Block.Changed -> ChangedBlock(block, enabled = !state.busy) { onTakeBack(block.index) }
                }
            }
        }
    }
}

/** 没变的段（不算空行）：两段以内照印，多了只露头尾。 */
@Composable
private fun SameBlock(all: List<String>) {
    val lines = all.filter { it.isNotBlank() }
    if (lines.isEmpty()) return
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.outline
    Column(Modifier.padding(vertical = 2.dp)) {
        if (lines.size <= 2) lines.forEach { Text(it, style = style, color = color) }
        else {
            Text(lines.first(), style = style, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.book_history_unchanged, lines.size - 2),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(lines.last(), style = style, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 一处改动：上面是这一版（灰字，删掉的字划线），下面是现在（正常字，加上的字带底色）；
 * 能逐字比的段只标出改了的字。空行不画（取回时照样带上）。
 */
@Composable
private fun ChangedBlock(block: ChapterDiff.Block.Changed, enabled: Boolean, onTakeBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DiffLines(block.old, block.new, block.spans)
        TextButton(
            onClick = onTakeBack,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.align(Alignment.End).heightIn(min = 36.dp),
        ) { Text(stringResource(R.string.book_history_take_back)) }
    }
}

/**
 * 一处改动的两稿（不带按钮，历史比较与逐处审阅共用）：上面是旧稿（灰字，删掉的字划线），下面是新稿（加上的字带底色）；
 * 能逐字比的段只标出改了的字。空行不画。[rejected]：选了拒绝，旧稿转正常色、新稿整段划线，所见即结果。
 */
@Composable
internal fun DiffLines(old: List<String>, new: List<String>, spans: List<List<ChapterDiff.Span>>?, rejected: Boolean = false) {
    val removedColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
    val addedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val muted = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val normal = MaterialTheme.typography.bodyMedium
    val oldStyle = if (rejected) normal else muted
    val newStyle = if (rejected) muted.copy(textDecoration = TextDecoration.LineThrough) else normal
    if (spans != null) {
        spans.forEach { line -> Text(paragraph(line, keep = ChapterDiff.Kind.REMOVED, removedColor, strike = !rejected), style = oldStyle) }
        spans.forEach { line -> Text(paragraph(line, keep = ChapterDiff.Kind.ADDED, addedColor), style = newStyle) }
    } else {
        old.filter { it.isNotBlank() }.forEach { line ->
            Text(line, style = oldStyle, textDecoration = if (rejected) null else TextDecoration.LineThrough, modifier = Modifier.fillMaxWidth().background(removedColor))
        }
        new.filter { it.isNotBlank() }.forEach { line ->
            Text(line, style = newStyle, modifier = Modifier.fillMaxWidth().background(addedColor))
        }
    }
}

/** 一段的一边：没变的字照印，[keep] 那种改动加底色（删掉的再划线），另一种不印。 */
private fun paragraph(spans: List<ChapterDiff.Span>, keep: ChapterDiff.Kind, color: Color, strike: Boolean = true): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        when (span.kind) {
            ChapterDiff.Kind.SAME -> append(span.text)
            keep -> withStyle(
                SpanStyle(background = color, textDecoration = if (keep == ChapterDiff.Kind.REMOVED && strike) TextDecoration.LineThrough else null),
            ) { append(span.text) }
            else -> Unit
        }
    }
}

/** 一本书的回收站。清空版本历史、清空回收站只在 App 里。 */
@Composable
fun BookTrashPage(workspaceId: String) {
    val vm: BookTrashVM = koinViewModel(key = "book-trash-$workspaceId", parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashedFile?>(null) }
    BookHistoryNotices(vm.notices)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_trash_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_trash_empty)) },
                                enabled = state.items.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    confirmEmpty = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_history_clear), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)) {
            if (state.paused) item { PausedNotice() }
            item {
                Text(
                    stringResource(R.string.book_history_usage, Formatter.formatShortFileSize(context, state.usageBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (!state.loading && state.items.isEmpty()) item {
                Text(stringResource(R.string.book_trash_empty_state), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(AppSpacing.md))
            }
            items(state.items, key = { "${it.key}/${it.name}" }) { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(item.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                item.path.substringBeforeLast('/', "").ifEmpty { null },
                                formatHistoryTime(item.at),
                                Formatter.formatShortFileSize(context, item.size),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { vm.putBack(item) }, enabled = !state.busy) { Text(stringResource(R.string.book_trash_restore)) }
                    IconButton(onClick = { confirmDelete = item }, enabled = !state.busy) {
                        Icon(HugeIcons.Delete01, stringResource(R.string.book_trash_delete), Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
    RikkaConfirmDialog(
        show = confirmEmpty,
        title = stringResource(R.string.book_trash_empty),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmEmpty = false
            vm.emptyTrash()
        },
        onDismiss = { confirmEmpty = false },
    ) { Text(stringResource(R.string.book_trash_empty_confirm)) }
    RikkaConfirmDialog(
        show = confirmClear,
        title = stringResource(R.string.book_history_clear),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmClear = false
            vm.clearHistory()
        },
        onDismiss = { confirmClear = false },
    ) { Text(stringResource(R.string.book_history_clear_confirm)) }
    RikkaConfirmDialog(
        show = confirmDelete != null,
        title = stringResource(R.string.book_trash_delete),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmDelete?.let(vm::delete)
            confirmDelete = null
        },
        onDismiss = { confirmDelete = null },
    ) { Text(confirmDelete?.path.orEmpty()) }
}

@Composable
private fun PausedNotice() {
    Text(
        stringResource(R.string.book_history_paused),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun BookHistoryNotices(notices: kotlinx.coroutines.flow.Flow<BookHistoryNotice>, onLocked: () -> Unit = {}) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val restored = stringResource(R.string.book_history_restored)
    val takenBack = stringResource(R.string.book_history_taken_back)
    val conflict = stringResource(R.string.book_history_conflict)
    val missing = stringResource(R.string.book_history_missing)
    val cleared = stringResource(R.string.book_history_cleared)
    val handled = stringResource(R.string.book_lock_notice_handled)
    val putBackFailed = stringResource(R.string.book_lock_put_back_failed)
    LaunchedEffect(notices) {
        notices.collect { notice ->
            when (notice) {
                BookHistoryNotice.Restored -> toaster.show(restored, type = ToastType.Success)
                BookHistoryNotice.TakenBack -> toaster.show(takenBack, type = ToastType.Success)
                BookHistoryNotice.Conflict -> toaster.show(conflict, type = ToastType.Error)
                BookHistoryNotice.Missing -> toaster.show(missing, type = ToastType.Error)
                BookHistoryNotice.Cleared -> toaster.show(cleared, type = ToastType.Success)
                is BookHistoryNotice.PutBack -> toaster.show(
                    context.getString(R.string.book_trash_restored, notice.path) +
                        (if (notice.locked) "\n" + context.getString(R.string.book_lock_restored_still_locked) else ""),
                    type = ToastType.Success,
                )
                BookHistoryNotice.Locked -> onLocked()
                BookHistoryNotice.NoticeHandled -> toaster.show(handled, type = ToastType.Success)
                BookHistoryNotice.PutBackFailed -> toaster.show(putBackFailed, type = ToastType.Error)
                is BookHistoryNotice.Failed -> toaster.show(notice.message, type = ToastType.Error)
            }
        }
    }
}

@Composable
private fun historySourceLabel(source: HistorySource, conversations: Map<String, String>): String = when (source) {
    HistorySource.Manual -> stringResource(R.string.book_history_source_manual)
    is HistorySource.Ai -> conversations[source.conversationId]?.let { stringResource(R.string.book_history_source_ai_in, it) }
        ?: stringResource(R.string.book_history_source_ai)
    HistorySource.Replace -> stringResource(R.string.book_history_source_replace)
    HistorySource.ExternalApp -> stringResource(R.string.book_history_source_external_app)
    HistorySource.External -> stringResource(R.string.book_history_source_external)
    HistorySource.Initial -> stringResource(R.string.book_history_source_initial)
    HistorySource.Restore -> stringResource(R.string.book_history_source_restore)
}

internal fun formatHistoryTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
