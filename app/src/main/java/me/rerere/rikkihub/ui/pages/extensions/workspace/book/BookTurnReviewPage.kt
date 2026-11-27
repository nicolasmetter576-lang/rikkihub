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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.book.history.TurnBlockDto
import me.rerere.rikkihub.data.book.history.TurnFileDto
import me.rerere.rikkihub.data.book.history.TurnFileOutcome
import me.rerere.rikkihub.data.book.history.TurnMissing
import me.rerere.rikkihub.data.book.history.TurnReviewActions
import me.rerere.rikkihub.data.book.history.TurnStatus
import me.rerere.rikkihub.data.book.history.Unattributed
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastAction
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.seconds

/**
 * 一轮改动的审阅页：按文件列出这一轮改了什么，每块选接受或拒绝，选好后“完成”一次写回。
 * 已审阅的只读查看，顶部可以撤销。不进编辑器。
 */
@Composable
fun BookTurnReviewPage(workspaceId: String, conversationId: String, messageId: String) {
    val vm: BookTurnReviewVM = koinViewModel(
        key = "book-turn-$workspaceId-$conversationId-$messageId",
        parameters = { parametersOf(workspaceId, conversationId, messageId) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val appScope = koinInject<AppScope>()
    val actions = koinInject<TurnReviewActions>()
    var confirmComplete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<TurnFileDto?>(null) }
    val review = state.review
    val reviewing = review != null && review.status == TurnStatus.PENDING

    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            when (notice) {
                is TurnReviewNotice.Done -> {
                    if (notice.reverted > 0) {
                        // 页面已经返回，撤销在应用级作用域里做
                        toaster.show(
                            context.getString(R.string.book_turn_reverted_toast, notice.reverted),
                            type = ToastType.Success,
                            duration = 8.seconds,
                            action = ToastAction(context.getString(R.string.book_turn_undo)) {
                                appScope.launch {
                                    val skipped = runCatching { actions.undo(workspaceId, conversationId, messageId) }
                                        .map { results -> results.count { it.outcome != TurnFileOutcome.Outcome.OK } }.getOrDefault(-1)
                                    toaster.show(undoneText(context, skipped), type = if (skipped == 0) ToastType.Success else ToastType.Warning)
                                }
                            },
                        )
                    } else {
                        toaster.show(context.getString(R.string.book_turn_reviewed_toast), type = ToastType.Success)
                    }
                    navController.popBackStack()
                }
                TurnReviewNotice.Partly -> toaster.show(context.getString(R.string.book_turn_partly), type = ToastType.Warning)
                is TurnReviewNotice.Undone -> toaster.show(undoneText(context, notice.skipped), type = if (notice.skipped == 0) ToastType.Success else ToastType.Warning)
                is TurnReviewNotice.Failed -> toaster.show(notice.message, type = ToastType.Error)
            }
        }
    }

    val leave = { if (state.rejectCount > 0) confirmDiscard = true else navController.popBackStack() }
    BackHandler(enabled = state.rejectCount > 0) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.book_turn_title), maxLines = 1)
                        val subtitle = listOfNotNull(state.title, review?.finishedAt?.let(::formatHistoryTime)).joinToString(" · ")
                        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackButton(onClick = { leave() }) },
                actions = {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (reviewing) {
                        TextButton(
                            onClick = { if (state.rejectCount > 0) confirmComplete = true else vm.complete() },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("book-turn-done"),
                        ) { Text(stringResource(R.string.book_turn_done)) }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                review == null -> Text(
                    stringResource(R.string.book_turn_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(AppSpacing.md),
                )
                else -> TurnReviewContent(
                    state = state,
                    onToggle = vm::toggle,
                    onRejectAll = { file ->
                        val skipped = vm.rejectAll(file)
                        if (skipped > 0) toaster.show(context.getString(R.string.book_turn_reject_all_skipped, skipped), type = ToastType.Info)
                    },
                    onRestoreWhole = { restoring = it },
                    onHistory = { path -> navController.navigate(Screen.BookHistory(workspaceId, path)) },
                    onUndo = vm::undo,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = confirmComplete,
        title = stringResource(R.string.book_turn_done),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmComplete = false
            vm.complete()
        },
        onDismiss = { confirmComplete = false },
    ) { Text(stringResource(R.string.book_turn_complete_confirm, state.rejectCount, state.rejectFiles)) }
    RikkaConfirmDialog(
        show = confirmDiscard,
        title = stringResource(R.string.book_turn_discard),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmDiscard = false
            vm.clearSelection()
            navController.popBackStack()
        },
        onDismiss = { confirmDiscard = false },
    ) { Text(stringResource(R.string.book_turn_discard_text)) }
    restoring?.let { file ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.book_turn_restore_whole),
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                restoring = null
                vm.restoreWhole(file)
            },
            onDismiss = { restoring = null },
        ) {
            Text(
                file.changesSince?.takeIf { it > 0 }?.let { stringResource(R.string.book_turn_restore_whole_confirm, it) }
                    ?: stringResource(if (file.changesSince == 0) R.string.book_turn_restore_whole_confirm_none else R.string.book_turn_restore_whole_confirm_unknown),
            )
        }
    }
}

private fun undoneText(context: android.content.Context, skipped: Int): String = when {
    skipped == 0 -> context.getString(R.string.book_turn_undone)
    skipped > 0 -> context.getString(R.string.book_turn_undone_skipped, skipped)
    else -> context.getString(R.string.book_turn_undo_failed)
}

@Composable
private fun TurnReviewContent(
    state: TurnReviewUiState,
    onToggle: (TurnFileDto, TurnBlockDto) -> Unit,
    onRejectAll: (TurnFileDto) -> Unit,
    onRestoreWhole: (TurnFileDto) -> Unit,
    onHistory: (String) -> Unit,
    onUndo: () -> Unit,
) {
    val review = state.review!!
    // 只有一个文件时不折叠
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val single = review.files.size == 1
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs)) {
        item(key = "summary") {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                val count = review.files.sumOf { file -> file.blocks.count { it.state == TurnBlockDto.BlockState.PENDING } }
                Text(
                    if (review.status == TurnStatus.PENDING) stringResource(R.string.book_turn_summary, count, state.rejectCount)
                    else stringResource(R.string.book_turn_reviewed_summary, review.reverted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (review.files.any { it.canUndo }) {
                    TextButton(onClick = onUndo, enabled = !state.busy, modifier = Modifier.testTag("book-turn-undo")) { Text(stringResource(R.string.book_turn_undo)) }
                }
            }
            review.unattributed?.let { reason ->
                Note(stringResource(if (reason == Unattributed.OVERLAP) R.string.book_turn_unattributed_overlap else R.string.book_turn_unattributed_terminal))
            }
        }
        review.files.forEach { file ->
            item(key = "file-${file.key}") {
                FileHeader(
                    file = file,
                    collapsed = !single && file.key in collapsed,
                    onCollapse = if (single) null else { { collapsed = if (file.key in collapsed) collapsed - file.key else collapsed + file.key } },
                    onRejectAll = { onRejectAll(file) },
                    onRestoreWhole = { onRestoreWhole(file) },
                    onHistory = { onHistory(file.path) },
                    enabled = !state.busy,
                )
                FileNotes(file, state.outcomes[file.key])
            }
            if (single || file.key !in collapsed) {
                val rejected = state.rejected[file.key].orEmpty()
                items(file.blocks, key = { "block-${file.key}-${it.index}" }) { block ->
                    TurnBlock(
                        file = file,
                        block = block,
                        rejected = block.index in rejected,
                        enabled = !state.busy,
                        onToggle = { onToggle(file, block) },
                        onHistory = { onHistory(file.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun FileHeader(
    file: TurnFileDto,
    collapsed: Boolean,
    onCollapse: (() -> Unit)?,
    onRejectAll: () -> Unit,
    onRestoreWhole: () -> Unit,
    onHistory: () -> Unit,
    enabled: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onCollapse != null) Modifier.clickable(onClick = onCollapse) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (file.kind == TurnFileDto.Kind.RENAMED) "${file.renamedFrom} → ${file.path}" else file.path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val kind = when (file.kind) {
                TurnFileDto.Kind.CREATED -> stringResource(R.string.book_turn_kind_created)
                TurnFileDto.Kind.DELETED -> stringResource(if (file.unrecoverable) R.string.book_turn_deleted_unrecoverable else R.string.book_turn_kind_deleted)
                TurnFileDto.Kind.RENAMED -> stringResource(R.string.book_turn_renamed_note)
                TurnFileDto.Kind.CHANGED -> null
            }
            val parts = listOfNotNull(kind, if (collapsed) stringResource(R.string.book_turn_collapsed, file.blocks.size) else null)
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (file.chapterLocked) Icon(HugeIcons.Lock, stringResource(R.string.book_turn_chapter_locked), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        val modifiable = file.kind == TurnFileDto.Kind.CHANGED || file.kind == TurnFileDto.Kind.RENAMED
        Box {
            IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (file.blocks.any { file.selectable(it) }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.book_turn_reject_all)) }, enabled = enabled, onClick = { menu = false; onRejectAll() })
                }
                if (modifiable && !file.done && !file.gone && !file.chapterLocked && file.before != null && file.missing != TurnMissing.BEFORE) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.book_turn_restore_whole), color = MaterialTheme.colorScheme.error) },
                        enabled = enabled,
                        onClick = { menu = false; onRestoreWhole() },
                    )
                }
                if (!file.gone) DropdownMenuItem(text = { Text(stringResource(R.string.book_turn_open_history)) }, onClick = { menu = false; onHistory() })
            }
        }
    }
}

/** 文件行下面的说明：为什么不能逐处审阅、上一次完成为什么没成。 */
@Composable
private fun FileNotes(file: TurnFileDto, outcome: TurnFileOutcome?) {
    when (outcome?.outcome) {
        TurnFileOutcome.Outcome.CONFLICT -> Note(stringResource(R.string.book_turn_outcome_conflict), error = true)
        TurnFileOutcome.Outcome.LOCKED -> Note(stringResource(R.string.book_turn_chapter_locked), error = true)
        TurnFileOutcome.Outcome.FAILED -> Note(stringResource(R.string.book_turn_outcome_failed, outcome.failed.size.coerceAtLeast(1)), error = true)
        TurnFileOutcome.Outcome.MISSING -> Note(stringResource(R.string.book_turn_outcome_missing), error = true)
        TurnFileOutcome.Outcome.OK, null -> Unit
    }
    if (file.gone && file.kind != TurnFileDto.Kind.DELETED) Note(stringResource(R.string.book_turn_file_gone))
    if (file.chapterLocked && outcome?.outcome != TurnFileOutcome.Outcome.LOCKED && !file.done) Note(stringResource(R.string.book_turn_chapter_locked))
    if (file.mixed) Note(stringResource(R.string.book_turn_mixed))
    file.missing?.let { missing ->
        Note(
            stringResource(
                when (missing) {
                    TurnMissing.BEFORE -> R.string.book_turn_missing_before
                    TurnMissing.AFTER -> R.string.book_turn_missing_after
                    TurnMissing.DELETED_VERSION -> R.string.book_turn_missing_deleted_version
                },
            ),
        )
    }
    if (file.kind == TurnFileDto.Kind.CREATED && file.blocks.any { it.state == TurnBlockDto.BlockState.CHANGED_LATER }) Note(stringResource(R.string.book_turn_created_changed))
    if (file.whole) Note(stringResource(R.string.book_turn_whole_only))
    if (file.layoutOnly) Note(stringResource(R.string.book_turn_layout_only))
    if (file.layout > 0) Note(stringResource(R.string.book_turn_layout_more, file.layout))
}

/** 一块：两稿上下排，底下一行是接受 / 拒绝，或者这块现在的状态。 */
@Composable
private fun TurnBlock(file: TurnFileDto, block: TurnBlockDto, rejected: Boolean, enabled: Boolean, onToggle: () -> Unit, onHistory: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (block.locked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HugeIcons.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(if (rejected) R.string.book_turn_locked_reject_hint else R.string.book_turn_block_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        // 已换回的块：稿子里现在是改前的样子，按拒绝的样子画
        DiffLines(block.old, block.new, block.spans, rejected = rejected || block.state == TurnBlockDto.BlockState.REVERTED)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when {
                file.selectable(block) -> Choice(rejected, enabled, onToggle)
                block.state == TurnBlockDto.BlockState.REVERTED -> StateLabel(stringResource(R.string.book_turn_state_reverted))
                block.state == TurnBlockDto.BlockState.GONE -> StateLabel(stringResource(R.string.book_turn_state_gone))
                block.state == TurnBlockDto.BlockState.CHANGED_LATER -> {
                    StateLabel(stringResource(R.string.book_turn_state_changed_later))
                    if (!file.gone) TextButton(onClick = onHistory, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(32.dp)) {
                        Text(stringResource(R.string.book_turn_open_history))
                    }
                }
            }
        }
    }
}

/** 两态切换：接受（默认）/ 拒绝，32dp 高。 */
@Composable
private fun Choice(rejected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.height(32.dp).testTag("book-turn-choice")) {
        SegmentedButton(
            selected = !rejected,
            onClick = { if (rejected) onToggle() },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = {},
        ) { Text(stringResource(R.string.book_turn_accept), style = MaterialTheme.typography.labelMedium) }
        SegmentedButton(
            selected = rejected,
            onClick = { if (!rejected) onToggle() },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = {},
        ) { Text(stringResource(R.string.book_turn_reject), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun StateLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
}
