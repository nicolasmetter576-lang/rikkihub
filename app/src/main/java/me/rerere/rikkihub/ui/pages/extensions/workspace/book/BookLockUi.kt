package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Lock
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.history.BookLockedException
import me.rerere.rikkihub.data.book.history.LockNotice
import me.rerere.rikkihub.ui.theme.AppSpacing

/** 加锁、解锁之后在原位置出现的一行“撤销”，6 秒后消失（不经全局 Toaster，它没有按钮）。 */
@Composable
internal fun LockUndoBar(undo: LockUndo?, onUndo: (LockUndo) -> Unit, onTimeout: () -> Unit, modifier: Modifier = Modifier) {
    if (undo == null) return
    LaunchedEffect(undo) {
        delay(6_000)
        onTimeout()
    }
    Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.small, modifier = modifier.padding(horizontal = AppSpacing.sm, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (undo.locked) R.string.book_lock_done_locked else R.string.book_lock_done_unlocked, undo.count),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onUndo(undo) }, modifier = Modifier.testTag("book-lock-undo")) {
                Text(stringResource(R.string.book_lock_undo), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

/** 作品页顶部：有待处理的提醒时一行横幅；锁表损坏时常驻提示。都没有时不占空间。 */
@Composable
internal fun LockNoticeBanner(state: BookLockState, onOpen: () -> Unit, onRetry: () -> Unit) {
    when {
        state.broken -> Row(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.book_lock_table_broken),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.book_lock_retry)) }
        }
        state.notices.isNotEmpty() -> Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 36.dp).padding(horizontal = AppSpacing.md).testTag("book-lock-banner"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.book_lock_notices_banner, state.notices.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            Text(stringResource(R.string.book_lock_view), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 一条提醒说的是什么。 */
@Composable
internal fun lockNoticeText(notice: LockNotice, title: String): String = stringResource(
    when (notice.kind) {
        LockNotice.Kind.RESTORED -> R.string.book_lock_notice_restored
        LockNotice.Kind.NOT_RESTORED -> R.string.book_lock_notice_not_restored
        LockNotice.Kind.NO_SPACE -> R.string.book_lock_notice_no_space
        LockNotice.Kind.CHANGED -> R.string.book_lock_notice_changed
        LockNotice.Kind.LOST -> R.string.book_lock_notice_lost
        LockNotice.Kind.BASE_MISSING -> R.string.book_lock_notice_base_missing
        LockNotice.Kind.DELETED -> R.string.book_lock_notice_deleted
    },
    title,
)

/** 提醒列表：查看（打开这一章的历史）、放回、按现在的稿子重新锁定、保留这次修改、知道了。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LockNoticesSheet(
    notices: List<LockNotice>,
    titleOf: (String) -> String,
    vm: BookVM,
    onView: (LockNotice) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(notices.isEmpty()) { if (notices.isEmpty()) onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.book_lock_notices_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(notices.sortedByDescending { it.at }, key = { it.id }) { notice ->
                    LockNoticeRow(notice, titleOf(notice.path), vm, onView = { onView(notice) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LockNoticeRow(notice: LockNotice, title: String, vm: BookVM, onView: () -> Unit) {
    var confirmKeep by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.md, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(lockNoticeText(notice, title), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (notice.earlier > 0) {
            Text(
                stringResource(R.string.book_lock_notice_earlier, notice.earlier),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (notice.kind in PUT_BACK_KINDS) {
            Text(stringResource(R.string.book_lock_put_back_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (notice.kind != LockNotice.Kind.DELETED) TextButton(onClick = onView) { Text(stringResource(R.string.book_lock_view)) }
            if (notice.kind in PUT_BACK_KINDS) TextButton(onClick = { vm.putBack(notice.id) }) { Text(stringResource(R.string.book_lock_put_back)) }
            if (notice.kind in RELOCK_KINDS) TextButton(onClick = { vm.relock(notice.id) }) { Text(stringResource(R.string.book_lock_relock)) }
            // 之后这一章又改过：只换锁定段，认不出时提示先比较（不会整章盖掉之后的修改）
            if (notice.kind == LockNotice.Kind.RESTORED && notice.changedSha != null) {
                TextButton(onClick = { confirmKeep = true }) { Text(stringResource(R.string.book_lock_keep_change)) }
            }
            TextButton(onClick = { vm.acknowledge(notice.id) }) { Text(stringResource(R.string.book_lock_acknowledge)) }
        }
    }
    if (confirmKeep) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmKeep = false },
            title = { Text(stringResource(R.string.book_lock_keep_change)) },
            text = { Text(stringResource(R.string.book_lock_keep_change_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmKeep = false
                    vm.keepChange(notice.id)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmKeep = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private val PUT_BACK_KINDS = setOf(LockNotice.Kind.NOT_RESTORED, LockNotice.Kind.NO_SPACE, LockNotice.Kind.CHANGED, LockNotice.Kind.DELETED)
private val RELOCK_KINDS = setOf(LockNotice.Kind.NOT_RESTORED, LockNotice.Kind.NO_SPACE, LockNotice.Kind.CHANGED, LockNotice.Kind.BASE_MISSING)

/** 出错提示：被定稿锁挡住的说清楚是哪一章、为什么（异常本身不带界面语言）；其他照原样。 */
internal fun lockAwareMessage(context: Context, error: Throwable): String {
    val locked = generateSequence(error) { it.cause }.filterIsInstance<BookLockedException>().firstOrNull()
        ?: return error.message ?: error.javaClass.simpleName
    val name = locked.path.substringAfterLast('/')
    return context.getString(
        when (locked.reason) {
            BookLockedException.Reason.CHAPTER -> R.string.book_lock_error_chapter
            BookLockedException.Reason.PASSAGES -> R.string.book_lock_error_passages
            BookLockedException.Reason.TRASH -> R.string.book_lock_error_trash
            BookLockedException.Reason.MOVE_OUT -> R.string.book_lock_error_move_out
        },
        name,
    )
}

/**
 * 对话内卡片：这条回复改了锁定的章节（放回了或没放回）。由界面按提醒渲染，不改消息内容，也不进分享和导出。
 * 查看打开这一章的历史；保留这次修改要确认（之后又改过时先比较）。
 */
@Composable
internal fun ConversationLockCard(
    workspaceId: String,
    notices: List<LockNotice>,
    onView: (LockNotice) -> Unit,
    review: (@Composable () -> Unit)? = null,
) {
    val history = org.koin.compose.koinInject<me.rerere.rikkihub.data.book.history.BookHistory>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var keeping by remember { mutableStateOf<LockNotice?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        // 锁提醒在上，审阅行在下（同一张卡）
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (notices.isEmpty()) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            notices.forEach { notice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HugeIcons.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        lockNoticeText(notice, notice.path.substringAfterLast('/')),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                }
                Row {
                    TextButton(onClick = { onView(notice) }) { Text(stringResource(R.string.book_lock_view)) }
                    if (notice.kind == LockNotice.Kind.RESTORED && notice.changedSha != null) {
                        TextButton(onClick = { keeping = notice }) { Text(stringResource(R.string.book_lock_keep_change)) }
                    }
                }
            }
            if (notices.isNotEmpty()) {
                Text(
                    stringResource(R.string.book_lock_card_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            review?.invoke()
        }
    }
    keeping?.let { notice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { keeping = null },
            title = { Text(stringResource(R.string.book_lock_keep_change)) },
            text = { Text(stringResource(R.string.book_lock_keep_change_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    keeping = null
                    scope.launch {
                        // 只换锁定段；认不出、之后又改过时去比较页，不整章盖掉之后的修改
                        if (history.keepChange(workspaceId, notice.id) == null) onView(notice)
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { keeping = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
