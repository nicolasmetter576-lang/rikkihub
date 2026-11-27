package me.rerere.rikkihub.ui.pages.extensions.workspace.book

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.history.LockNotice
import me.rerere.hugeicons.stroke.Lock
import me.rerere.rikkihub.data.book.history.FileLocks
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookChapterEntry
import me.rerere.rikkihub.data.book.BookItem
import me.rerere.rikkihub.data.book.BookManifestException
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.ChapterNumbers
import me.rerere.rikkihub.data.book.ChapterStatus
import me.rerere.rikkihub.data.book.history.BookHistory
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.NumberFormat

@Composable
fun rememberBookVM(workspaceId: String): BookVM =
    koinViewModel(key = "book-$workspaceId", parameters = { parametersOf(workspaceId) })

/** 作品模式的「作品」页：目录、字数、状态；底部是继续写、新章、整理。 */
@Composable
fun BookPane(vm: BookVM, workspaceId: String, workspaceName: String, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { toaster.show(lockAwareMessage(context, it), type = ToastType.Error) } }

    val book = state.book
    // 回到这页（resume → refresh）时重读；暂停只是提示，正文照常保存
    val historyPaused = koinInject<BookHistory>().paused(workspaceId)
    when {
        book != null -> BookContents(
            book = book,
            historyPaused = historyPaused,
            workspaceName = workspaceName,
            vm = vm,
            modifier = modifier,
            onOpen = { nav.navigate(Screen.WorkspaceFileEditor(workspaceId, WorkspaceStorageArea.FILES.name, it)) },
            onRead = { nav.navigate(Screen.WorkspaceReader(workspaceId, WorkspaceStorageArea.FILES.name, it)) },
            onReplace = { nav.navigate(Screen.BookReplace(workspaceId)) },
            onImport = { nav.navigate(Screen.BookImport(workspaceId)) },
            onOutline = { nav.navigate(Screen.BookOutline(workspaceId)) },
            onChapterOutline = { nav.navigate(Screen.ChapterOutline(workspaceId, it)) },
            onHistory = { nav.navigate(Screen.BookHistory(workspaceId, it)) },
            onNotice = { nav.navigate(Screen.BookHistory(workspaceId, it.path, it.id)) },
            onTrash = { nav.navigate(Screen.BookTrash(workspaceId)) },
            onTurn = { nav.navigate(Screen.BookTurnReview(workspaceId, it.conversationId, it.messageId)) },
        )
        state.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> BookUnavailable(state, vm, modifier)
    }
}

@Composable
private fun BookUnavailable(state: BookUiState, vm: BookVM, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val corrupt = state.manifestError == BookManifestException.Reason.CORRUPT
        Text(
            when (state.manifestError) {
                BookManifestException.Reason.CORRUPT -> stringResource(R.string.book_manifest_corrupt)
                BookManifestException.Reason.NEWER_VERSION -> stringResource(R.string.book_manifest_newer)
                null -> state.error.orEmpty()
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::refresh, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.workspace_editor_retry)) }
            if (corrupt) {
                OutlinedButton(onClick = vm::rebuild, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.book_manifest_rebuild)) }
            }
        }
    }
}

private sealed interface BookDialog {
    data object NewChapter : BookDialog
    data object NewVolume : BookDialog
    data object Info : BookDialog
    data object Export : BookDialog
    data class EditChapter(val id: String) : BookDialog
    data class EditVolume(val id: String) : BookDialog
    data class MoveToVolume(val ids: Set<String>) : BookDialog
    data class Delete(val ids: Set<String>) : BookDialog
    data class Relink(val id: String) : BookDialog
}

@Composable
private fun BookContents(
    book: Book,
    historyPaused: Boolean,
    workspaceName: String,
    vm: BookVM,
    onOpen: (String) -> Unit,
    onRead: (String) -> Unit,
    onReplace: () -> Unit,
    onImport: () -> Unit,
    onOutline: () -> Unit,
    onChapterOutline: (String) -> Unit,
    onHistory: (String) -> Unit,
    onNotice: (LockNotice) -> Unit,
    onTrash: () -> Unit,
    onTurn: (me.rerere.rikkihub.data.book.history.TurnRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val turnState by vm.turns.collectAsStateWithLifecycle()
    var recentOpen by remember { mutableStateOf(false) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var dialog by remember { mutableStateOf<BookDialog?>(null) }
    var showSynopses by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var unlistedOpen by rememberSaveable { mutableStateOf(false) }
    val lockState by vm.locks.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    var noticesOpen by remember { mutableStateOf(false) }
    var unlockingChapter by remember { mutableStateOf<String?>(null) }
    var unlockingPassages by remember { mutableStateOf<String?>(null) }
    val toaster = LocalToaster.current
    val undoFailed = stringResource(R.string.book_lock_undo_failed)
    LaunchedEffect(vm) { vm.undoFailed.collect { toaster.show(undoFailed, type = ToastType.Error) } }
    val keepFailed = stringResource(R.string.book_lock_keep_failed)
    LaunchedEffect(vm) { vm.keepFailed.collect { toaster.show(keepFailed, type = ToastType.Warning) } }
    val existing = remember(book) { book.manifest.items.mapTo(HashSet()) { it.id } }
    LaunchedEffect(existing) { selected = selected intersect existing }
    fun stopArranging() {
        arranging = false
        selected = emptySet()
    }
    BackHandler(enabled = arranging) { stopArranging() }

    Column(modifier.fillMaxSize()) {
        BookHeader(
            book = book,
            workspaceName = workspaceName,
            showSynopses = showSynopses,
            onToggleSynopses = { showSynopses = !showSynopses },
            onNewVolume = { dialog = BookDialog.NewVolume },
            onInfo = { dialog = BookDialog.Info },
            onReplace = onReplace,
            onImport = onImport,
            onExport = { dialog = BookDialog.Export },
            onOutline = onOutline,
            onTrash = onTrash,
            onRecent = { recentOpen = true },
        )
        LockNoticeBanner(lockState, onOpen = { noticesOpen = true }, onRetry = vm::retryLocks)
        turnState.banner?.let { record ->
            BookTurnBanner(record, turnState.titles[record.conversationId], onOpen = { onTurn(record) }, onDismiss = { vm.dismissBanner(record) })
        }
        if (historyPaused) {
            Text(
                stringResource(R.string.book_history_paused),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 4.dp),
            )
        }
        if (arranging) {
            BookArrangeList(
                book = book,
                selected = selected,
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                onReorder = vm::reorder,
                modifier = Modifier.weight(1f),
            )
        } else {
            val entries = remember(book) { book.entries.associateBy { it.id } }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("book-list"),
                contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            ) {
                if (book.entries.isEmpty() && book.unlisted.isEmpty()) {
                    item("empty") {
                        Text(
                            stringResource(R.string.book_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(AppSpacing.md),
                        )
                        OutlinedButton(
                            onClick = onImport,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.padding(horizontal = AppSpacing.md),
                        ) { Text(stringResource(R.string.book_import_title)) }
                    }
                }
                val visible = visibleItems(book.manifest.items, collapsed.toSet())
                items(visible, key = { it.id }) { item ->
                    when (item) {
                        is BookVolume -> BookVolumeRow(
                            title = item.title,
                            words = book.entries.filter { it.volumeId == item.id }.sumOf { it.words },
                            collapsed = item.id in collapsed,
                            onToggle = { collapsed = if (item.id in collapsed) collapsed - item.id else collapsed + item.id },
                            onEdit = { dialog = BookDialog.EditVolume(item.id) },
                            onRemove = { vm.remove(setOf(item.id)) },
                        )
                        else -> entries[item.id]?.let { entry ->
                            val locks = lockState.locks[entry.path]
                            BookChapterRow(
                                entry = entry,
                                locks = locks,
                                alert = lockState.notices.any { it.path == entry.path },
                                onLock = { vm.lockChapters(listOf(entry.path)) },
                                onUnlock = { unlockingChapter = entry.path },
                                onUnlockPassages = { unlockingPassages = entry.path },
                                showSynopsis = showSynopses,
                                onOpen = { if (entry.missing) dialog = BookDialog.Relink(entry.id) else onOpen(entry.path) },
                                onRead = { onRead(entry.path) },
                                onEdit = { dialog = BookDialog.EditChapter(entry.id) },
                                onOutline = { onChapterOutline(entry.id) },
                                onHistory = { onHistory(entry.path) },
                                onMove = { dialog = BookDialog.MoveToVolume(setOf(entry.id)) },
                                onRemove = { vm.remove(setOf(entry.id)) },
                                onDelete = { dialog = BookDialog.Delete(setOf(entry.id)) },
                                onRelink = { dialog = BookDialog.Relink(entry.id) },
                                hasVolumes = book.manifest.items.any { it is BookVolume },
                            )
                        }
                    }
                }
                if (book.unlisted.isNotEmpty()) {
                    item("unlisted") {
                        BookUnlistedHeader(
                            count = book.unlisted.size,
                            open = unlistedOpen,
                            onToggle = { unlistedOpen = !unlistedOpen },
                            onAddAll = { vm.addFiles(book.unlisted) },
                        )
                    }
                    if (unlistedOpen) {
                        items(book.unlisted, key = { "file:$it" }) { path ->
                            BookUnlistedRow(
                                path = path,
                                onOpen = { onOpen(path) },
                                onAdd = { vm.addFiles(listOf(path)) },
                                onIgnore = { vm.ignoreFiles(listOf(path)) },
                            )
                        }
                    }
                }
            }
        }
        LockUndoBar(undo, onUndo = vm::undoLocks, onTimeout = vm::clearUndo)
        BookBottomBar(
            arranging = arranging,
            selected = selected,
            book = book,
            locks = lockState.locks,
            onLock = { vm.lockChapters(it) },
            onUnlock = { vm.unlockChapters(it) },
            onContinue = { book.entries.filterNot { it.missing }.maxByOrNull { it.file?.modifiedAt ?: 0L }?.let { onOpen(it.path) } },
            onNew = { dialog = BookDialog.NewChapter },
            onArrange = { arranging = true },
            onDone = ::stopArranging,
            onStatus = { vm.setStatus(selected, it) },
            onMove = { dialog = BookDialog.MoveToVolume(selected) },
            onRemove = {
                vm.remove(selected)
                selected = emptySet()
            },
        )
    }

    when (val current = dialog) {
        null -> Unit
        BookDialog.NewChapter -> NewChapterDialog(book, onDismiss = { dialog = null }) { name, afterId, folder ->
            dialog = null
            vm.createChapter(if (folder.isEmpty()) name else "$folder/$name", afterId, onOpen)
        }
        BookDialog.NewVolume -> TextDialog(stringResource(R.string.book_volume_new), stringResource(R.string.book_volume_title), "", { dialog = null }) {
            dialog = null
            vm.addVolume(it, null)
        }
        is BookDialog.EditVolume -> {
            val volume = book.manifest.items.firstOrNull { it.id == current.id } as? BookVolume
            if (volume != null) {
                TextDialog(stringResource(R.string.book_volume_rename), stringResource(R.string.book_volume_title), volume.title, { dialog = null }) { title ->
                    dialog = null
                    vm.edit { it.updateVolume(volume.id, title) }
                }
            }
        }
        BookDialog.Export -> BookExportSheet(book, onDismiss = { dialog = null })
        BookDialog.Info -> BookInfoDialog(book, onDismiss = { dialog = null }) { title, author ->
            dialog = null
            vm.setInfo(title, author)
        }
        is BookDialog.EditChapter -> book.entry(current.id)?.let { entry ->
            EditChapterDialog(entry, locked = lockState.locks[entry.path]?.chapter != null, onDismiss = { dialog = null }) { title, synopsis, target, status, lock ->
                dialog = null
                vm.updateChapter(entry.id, title, synopsis, target, status)
                if (lock) vm.lockChapters(listOf(entry.path))
            }
        }
        is BookDialog.MoveToVolume -> MoveToVolumeDialog(book, onDismiss = { dialog = null }) { volumeId ->
            dialog = null
            vm.moveToVolume(current.ids, volumeId)
        }
        is BookDialog.Relink -> RelinkDialog(book, onDismiss = { dialog = null }) { path ->
            dialog = null
            vm.relink(current.id, path)
        }
        is BookDialog.Delete -> Unit
    }
    val deleting = dialog as? BookDialog.Delete
    RikkaConfirmDialog(
        show = deleting != null,
        title = stringResource(R.string.book_chapter_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleting?.let { vm.delete(it.ids) }
            dialog = null
        },
        onDismiss = { dialog = null },
    ) {
        val paths = deleting?.ids.orEmpty().mapNotNull { book.entry(it)?.path }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.book_chapter_delete_confirm, paths.joinToString("\n")))
            val locked = paths.count { lockState.locks[it]?.isEmpty == false }
            if (locked > 0) Text(stringResource(R.string.book_lock_delete_note, locked), style = MaterialTheme.typography.bodySmall)
        }
    }
    RikkaConfirmDialog(
        show = unlockingChapter != null,
        title = stringResource(R.string.book_lock_unlock_chapter),
        confirmText = stringResource(R.string.book_lock_unlock),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            unlockingChapter?.let { vm.unlockChapters(listOf(it)) }
            unlockingChapter = null
        },
        onDismiss = { unlockingChapter = null },
    ) { Text(stringResource(R.string.book_lock_unlock_chapter_confirm)) }
    RikkaConfirmDialog(
        show = unlockingPassages != null,
        title = stringResource(R.string.book_lock_unlock_passages, lockState.locks[unlockingPassages]?.passages?.size ?: 0),
        confirmText = stringResource(R.string.book_lock_unlock),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            unlockingPassages?.let(vm::unlockPassages)
            unlockingPassages = null
        },
        onDismiss = { unlockingPassages = null },
    ) { Text(stringResource(R.string.book_lock_unlock_passages_confirm)) }
    if (recentOpen) {
        RecentTurnsSheet(turnState, onOpen = { record -> recentOpen = false; onTurn(record) }, onDismiss = { recentOpen = false })
    }
    if (noticesOpen) {
        LockNoticesSheet(
            notices = lockState.notices,
            titleOf = { path -> book.entryByPath(path)?.let { "${it.number}. ${it.title}" } ?: path },
            vm = vm,
            onView = { notice ->
                noticesOpen = false
                vm.markSeen(notice.id)
                onNotice(notice)
            },
            onDismiss = { noticesOpen = false },
        )
    }
}

/** 收起的卷只留卷名那一行。 */
internal fun visibleItems(items: List<BookItem>, collapsed: Set<String>): List<BookItem> {
    var hidden = false
    return items.filter { item ->
        if (item is BookVolume) {
            hidden = item.id in collapsed
            true
        } else !hidden
    }
}

@Composable
private fun wordsText(words: Int): String = stringResource(R.string.book_words, NumberFormat.getIntegerInstance().format(words))

@Composable
private fun BookHeader(
    book: Book,
    workspaceName: String,
    showSynopses: Boolean,
    onToggleSynopses: () -> Unit,
    onNewVolume: () -> Unit,
    onInfo: () -> Unit,
    onReplace: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOutline: () -> Unit,
    onTrash: () -> Unit,
    onRecent: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = AppSpacing.md, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (book.manifest.title != workspaceName) {
                Text(
                    book.manifest.title.ifBlank { stringResource(R.string.book_untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(
                    R.string.book_summary,
                    NumberFormat.getIntegerInstance().format(book.words),
                    book.entries.size,
                    book.entries.count { it.chapter.status == ChapterStatus.FINAL },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("book-menu")) {
                Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun entry(text: String, onClick: () -> Unit) = DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        menu = false
                        onClick()
                    },
                )
                entry(stringResource(R.string.book_outline_title), onOutline)
                entry(stringResource(R.string.book_replace_title), onReplace)
                entry(stringResource(R.string.book_import_title), onImport)
                entry(stringResource(R.string.book_export_title), onExport)
                entry(stringResource(R.string.book_turn_recent), onRecent)
                entry(stringResource(R.string.book_trash_title), onTrash)
                entry(stringResource(if (showSynopses) R.string.book_hide_synopses else R.string.book_show_synopses), onToggleSynopses)
                entry(stringResource(R.string.book_volume_new), onNewVolume)
                entry(stringResource(R.string.book_info), onInfo)
            }
        }
    }
}

@Composable
private fun BookVolumeRow(
    title: String,
    words: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 44.dp).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (collapsed) HugeIcons.ArrowRight01 else HugeIcons.ArrowDown01, null, Modifier.size(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(wordsText(words), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options), Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.book_volume_rename)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.book_volume_remove)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun StatusDot(status: ChapterStatus) {
    Box(Modifier.padding(start = 8.dp).size(8.dp).background(statusColor(status), CircleShape))
}

/** 锁和状态点合并：整章锁把状态点换成同色的小锁；只有段落锁时状态点旁加一个淡色小锁；有待处理的提醒时加红点。 */
@Composable
private fun StatusAndLock(status: ChapterStatus, locks: FileLocks?, alert: Boolean) {
    val chapter = locks?.chapter != null
    val passages = locks?.passages.orEmpty().any { it.dormantSince == null }
    if (chapter) {
        Box(Modifier.padding(start = 6.dp)) {
            // 草稿的状态色太淡，整章锁会比段落锁还不显眼：草稿用深一档的颜色
            val tint = if (status == ChapterStatus.DRAFT) MaterialTheme.colorScheme.onSurfaceVariant else statusColor(status)
            Icon(HugeIcons.Lock, stringResource(R.string.book_lock_locked), Modifier.size(12.dp), tint = tint)
            if (alert) AlertDot(Modifier.align(Alignment.TopEnd))
        }
        return
    }
    if (passages) {
        Box(Modifier.padding(start = 6.dp)) {
            Icon(HugeIcons.Lock, stringResource(R.string.book_lock_has_passages), Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
            if (alert) AlertDot(Modifier.align(Alignment.TopEnd))
        }
    }
    StatusDot(status)
}

@Composable
private fun AlertDot(modifier: Modifier) = Box(modifier.size(4.dp).background(MaterialTheme.colorScheme.error, CircleShape))

@Composable
private fun statusColor(status: ChapterStatus) = when (status) {
    ChapterStatus.DRAFT -> MaterialTheme.colorScheme.outlineVariant
    ChapterStatus.REVISING -> MaterialTheme.colorScheme.tertiary
    ChapterStatus.FINAL -> MaterialTheme.colorScheme.primary
}

@Composable
internal fun statusText(status: ChapterStatus): String = stringResource(
    when (status) {
        ChapterStatus.DRAFT -> R.string.book_status_draft
        ChapterStatus.REVISING -> R.string.book_status_revising
        ChapterStatus.FINAL -> R.string.book_status_final
    },
)

@Composable
private fun BookChapterRow(
    entry: BookChapterEntry,
    locks: FileLocks?,
    alert: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onUnlockPassages: () -> Unit,
    showSynopsis: Boolean,
    hasVolumes: Boolean,
    onOpen: () -> Unit,
    onRead: () -> Unit,
    onEdit: () -> Unit,
    onOutline: () -> Unit,
    onHistory: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    onRelink: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 48.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.number.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showSynopsis && entry.chapter.synopsis.isNotBlank()) {
                Text(
                    entry.chapter.synopsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.missing) {
            Text(stringResource(R.string.book_chapter_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            val count = NumberFormat.getIntegerInstance().format(entry.words)
            Text(
                if (entry.chapter.targetWords > 0) "$count/${NumberFormat.getIntegerInstance().format(entry.chapter.targetWords)}" else count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        StatusAndLock(entry.chapter.status, locks, alert)
        Box {
            IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options), Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun item(text: String, error: Boolean = false, onClick: () -> Unit) = DropdownMenuItem(
                    text = { Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        menu = false
                        onClick()
                    },
                )
                if (entry.missing) item(stringResource(R.string.book_chapter_relink), onClick = onRelink)
                else item(stringResource(R.string.workspace_reader_open), onClick = onRead)
                item(stringResource(R.string.book_chapter_edit), onClick = onEdit)
                if (!entry.missing) item(stringResource(R.string.book_outline_chapter), onClick = onOutline)
                if (!entry.missing) item(stringResource(R.string.book_history_title), onClick = onHistory)
                if (!entry.missing) {
                    if (locks?.chapter != null) item(stringResource(R.string.book_lock_unlock_chapter), onClick = onUnlock)
                    else item(stringResource(R.string.book_lock_chapter), onClick = onLock)
                    val passages = locks?.passages.orEmpty().size
                    if (passages > 0) item(stringResource(R.string.book_lock_unlock_passages, passages), onClick = onUnlockPassages)
                }
                if (hasVolumes) item(stringResource(R.string.book_move_to_volume), onClick = onMove)
                item(stringResource(R.string.book_chapter_remove), onClick = onRemove)
                if (!entry.missing) item(stringResource(R.string.book_chapter_delete), error = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun BookUnlistedHeader(count: Int, open: Boolean, onToggle: () -> Unit, onAddAll: () -> Unit) {
    Column {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 44.dp).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.book_unlisted, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (open) TextButton(onClick = onAddAll) { Text(stringResource(R.string.book_unlisted_add_all)) }
        }
    }
}

@Composable
private fun BookUnlistedRow(path: String, onOpen: () -> Unit, onAdd: () -> Unit, onIgnore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 44.dp).padding(start = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text(stringResource(R.string.book_unlisted_add)) }
        TextButton(onClick = onIgnore) { Text(stringResource(R.string.book_unlisted_ignore)) }
    }
}

/** 整理：手柄拖动排序（不占长按），点行多选。 */
@Composable
private fun BookArrangeList(
    book: Book,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var items by remember(book.manifest.items) { mutableStateOf(book.manifest.items) }
    val entries = remember(book) { book.entries.associateBy { it.id } }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag("book-arrange"),
        contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
    ) {
        items(items, key = { it.id }) { item ->
            ReorderableItem(reorderState, key = item.id) { dragging ->
                Surface(color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(item.id) }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = item.id in selected, onCheckedChange = { onToggle(item.id) })
                        val entry = entries[item.id]
                        Text(
                            if (item is BookVolume) item.title else "${entry?.number}  ${entry?.title}",
                            style = if (item is BookVolume) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                            color = if (item is BookVolume) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier.size(48.dp).draggableHandle(
                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                onDragStopped = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    onReorder(items.map { it.id })
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Icon(HugeIcons.DragDropVertical, stringResource(R.string.prompt_layout_drag)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookBottomBar(
    arranging: Boolean,
    selected: Set<String>,
    book: Book,
    locks: Map<String, FileLocks>,
    onLock: (List<String>) -> Unit,
    onUnlock: (List<String>) -> Unit,
    onContinue: () -> Unit,
    onNew: () -> Unit,
    onArrange: () -> Unit,
    onDone: () -> Unit,
    onStatus: (ChapterStatus) -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpacing.xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!arranging) {
                TextButton(onClick = onContinue, enabled = book.entries.any { !it.missing }) { Text(stringResource(R.string.book_continue)) }
                TextButton(onClick = onNew, modifier = Modifier.testTag("book-new")) { Text(stringResource(R.string.book_chapter_new)) }
                TextButton(onClick = onArrange, enabled = book.manifest.items.isNotEmpty(), modifier = Modifier.testTag("book-arrange-button")) {
                    Text(stringResource(R.string.book_arrange))
                }
            } else {
                var statusMenu by remember { mutableStateOf(false) }
                val chapters = selected.filter { book.entry(it) != null }
                Box {
                    TextButton(onClick = { statusMenu = true }, enabled = chapters.isNotEmpty()) { Text(stringResource(R.string.book_status)) }
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        ChapterStatus.entries.forEach { status ->
                            DropdownMenuItem(text = { Text(statusText(status)) }, onClick = { statusMenu = false; onStatus(status) })
                        }
                        // 定稿锁：分隔线和小标题隔开，选中的都没锁时不显示“解锁”
                        val paths = chapters.mapNotNull { book.entry(it)?.takeUnless { e -> e.missing }?.path }
                        if (paths.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.book_lock_section),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            val unlocked = paths.filter { locks[it]?.chapter == null }
                            val locked = paths.filter { locks[it]?.chapter != null }
                            if (unlocked.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.book_lock_selected, unlocked.size)) },
                                    onClick = { statusMenu = false; onLock(unlocked) },
                                )
                            }
                            if (locked.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.book_unlock_selected, locked.size)) },
                                    onClick = { statusMenu = false; onUnlock(locked) },
                                )
                            }
                        }
                    }
                }
                if (book.manifest.items.any { it is BookVolume }) {
                    TextButton(onClick = onMove, enabled = chapters.isNotEmpty() && chapters.size == selected.size) {
                        Text(stringResource(R.string.book_move_to_volume))
                    }
                }
                TextButton(onClick = onRemove, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.book_chapter_remove)) }
                TextButton(onClick = onDone) { Text(stringResource(R.string.book_done)) }
            }
        }
    }
}

@Composable
private fun TextDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 新章默认接在最后一章后面，文件名照它的写法加一。 */
@Composable
private fun NewChapterDialog(book: Book, onDismiss: () -> Unit, onConfirm: (name: String, afterId: String?, folder: String) -> Unit) {
    val last = book.entries.lastOrNull()
    val folder = last?.path?.substringBeforeLast('/', "").orEmpty()
    val fallback = stringResource(R.string.book_chapter_default_name)
    var name by remember {
        mutableStateOf(last?.let { ChapterNumbers.nextFileName(it.path.substringAfterLast('/')) } ?: "$fallback.md")
    }
    val normalized = name.trim().let { if (it.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "txt", "text")) it else "$it.md" }
    val taken = remember(book, normalized) {
        val path = if (folder.isEmpty()) normalized else "$folder/$normalized"
        book.entryByPath(path) != null || path in book.unlisted || path in book.manifest.ignored
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_chapter_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.book_chapter_file_name)) },
                singleLine = true,
                isError = taken,
                supportingText = if (taken) ({ Text(stringResource(R.string.book_chapter_exists)) }) else null,
                modifier = Modifier.fillMaxWidth().testTag("book-new-name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(normalized, last?.id, folder) }, enabled = name.isNotBlank() && !taken) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun BookInfoDialog(book: Book, onDismiss: () -> Unit, onConfirm: (title: String, author: String) -> Unit) {
    var title by remember { mutableStateOf(book.manifest.title) }
    var author by remember { mutableStateOf(book.manifest.author) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.book_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.book_author)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.book_backup_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, author) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun EditChapterDialog(
    entry: BookChapterEntry,
    locked: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (title: String, synopsis: String, targetWords: Int, status: ChapterStatus, lock: Boolean) -> Unit,
) {
    var lock by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(entry.chapter.title.orEmpty()) }
    var synopsis by remember { mutableStateOf(entry.chapter.synopsis) }
    var target by remember { mutableStateOf(entry.chapter.targetWords.takeIf { it > 0 }?.toString().orEmpty()) }
    var status by remember { mutableStateOf(entry.chapter.status) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_chapter_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text(stringResource(R.string.book_chapter_title)) },
                    placeholder = { Text(entry.file?.heading ?: entry.path.substringAfterLast('/').substringBeforeLast('.')) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    synopsis, { synopsis = it },
                    label = { Text(stringResource(R.string.book_synopsis)) },
                    minLines = 2, maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    target, { value -> target = value.filter { it.isDigit() }.take(6) },
                    label = { Text(stringResource(R.string.book_target_words)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ChapterStatus.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = status == value,
                            onClick = { status = value },
                            shape = SegmentedButtonDefaults.itemShape(index, ChapterStatus.entries.size),
                        ) { Text(statusText(value), maxLines = 1) }
                    }
                }
                // 只改状态不会锁住内容；选“定稿”时可以顺手锁上（默认不勾）
                if (status == ChapterStatus.FINAL && !locked && !entry.missing) {
                    Row(
                        Modifier.fillMaxWidth().clickable { lock = !lock },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = lock, onCheckedChange = { lock = it })
                        Column {
                            Text(stringResource(R.string.book_lock_with_final), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.book_lock_with_final_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, synopsis, target.toIntOrNull() ?: 0, status, lock && status == ChapterStatus.FINAL) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PickDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(options) { (label, onClick) ->
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun MoveToVolumeDialog(book: Book, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val none = stringResource(R.string.book_volume_none)
    PickDialog(
        stringResource(R.string.book_move_to_volume),
        listOf(none to { onPick(null) }) + book.manifest.items.filterIsInstance<BookVolume>().map { it.title to { onPick(it.id) } },
        onDismiss,
    )
}

@Composable
private fun RelinkDialog(book: Book, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    if (book.unlisted.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.book_chapter_relink)) },
            text = { Text(stringResource(R.string.book_chapter_relink_none)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) } },
        )
    } else {
        PickDialog(stringResource(R.string.book_chapter_relink), book.unlisted.map { it to { onPick(it) } }, onDismiss)
    }
}
