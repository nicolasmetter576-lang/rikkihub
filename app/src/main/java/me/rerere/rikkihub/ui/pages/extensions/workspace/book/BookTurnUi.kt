package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.history.TurnMissing
import me.rerere.rikkihub.data.book.history.TurnRecord
import me.rerere.rikkihub.data.book.history.TurnStatus
import me.rerere.rikkihub.data.book.history.Unattributed
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.ui.theme.AppSpacing

/** 对话里一条回复下面的审阅提示。[full]：完整一行（卡片里）；否则一行次要色小字。 */
internal data class TurnCard(val record: TurnRecord, val full: Boolean, val files: Int, val deleted: Int, val sibling: TurnRecord?)

/**
 * 当前分支上每条回复的审阅提示：只有最新一条待审、没静音的回复显示完整一行；
 * 删了文件的轮次总是完整一行；只新建了文件的、更早的，只给一行小字。已归档不显示。
 */
internal fun turnCards(records: Map<String, TurnRecord>, muted: Boolean, nodes: List<MessageNode>): Map<String, TurnCard> {
    if (records.isEmpty()) return emptyMap()
    val current = nodes.mapNotNull { node -> node.messages.getOrNull(node.selectIndex)?.let { node to it.id.toString() } }
    val latest = current.mapNotNull { (_, id) -> records[id]?.takeIf { it.status == TurnStatus.PENDING } }.maxByOrNull { it.finishedAt ?: 0 }
    return current.mapNotNull { (node, id) ->
        val record = records[id]?.takeIf { it.status != TurnStatus.ARCHIVED } ?: return@mapNotNull null
        val files = record.files.values.filter { !it.layoutOnly }
        val deleted = files.count { it.trashed != null || it.deleted }
        val onlyCreated = files.isNotEmpty() && files.all { it.created }
        // 重新生成：同一节点上最近一个还有未审记录、碰过同一批文件的兄弟回复
        val sibling = node.messages.filter { it.id.toString() != id }.mapNotNull { records[it.id.toString()] }
            .filter { it.status == TurnStatus.PENDING && it.files.keys.any { key -> key in record.files } }
            .maxByOrNull { it.finishedAt ?: 0 }
        val full = record.status == TurnStatus.PENDING && (deleted > 0 || record == latest && !muted && !onlyCreated)
        id to TurnCard(record, full, files.size.coerceAtLeast(1), deleted, sibling)
    }.toMap()
}

/** 卡片里的审阅行：`这轮改了 3 个文件 · 审阅`，行尾菜单“这个对话不再提示”。 */
@Composable
internal fun TurnReviewRow(card: TurnCard, onReview: () -> Unit, onMute: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book-turn-row"), verticalAlignment = Alignment.CenterVertically) {
            Text(
                turnCountText(card),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onReview) { Text(stringResource(R.string.book_turn_review)) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options), Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.book_turn_mute)) }, onClick = { menu = false; onMute() })
                }
            }
        }
        UnattributedNote(card.record)
    }
}

/** 回复下面的一行小字：更早的待审（“审阅改动 · 删了 1 个文件”）、已审阅（“已审阅 · 换回 2 处”）。 */
@Composable
internal fun TurnSmallLine(card: TurnCard, onReview: () -> Unit) {
    val text = if (card.record.status == TurnStatus.REVIEWED) {
        stringResource(R.string.book_turn_reviewed_summary, card.record.reverted)
    } else {
        stringResource(R.string.book_turn_review_small) + if (card.deleted > 0) " · " + stringResource(R.string.book_turn_deleted_count, card.deleted) else ""
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onReview).heightIn(min = 32.dp).padding(vertical = 8.dp).testTag("book-turn-line"),
        )
        if (card.record.status == TurnStatus.PENDING) UnattributedNote(card.record)
    }
}

/** 上一个回复也改过这些文件（重新生成后）。 */
@Composable
internal fun TurnSiblingLine(onView: () -> Unit) {
    Text(
        stringResource(R.string.book_turn_sibling),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp).clickable(onClick = onView).heightIn(min = 32.dp).padding(vertical = 8.dp),
    )
}

@Composable
private fun UnattributedNote(record: TurnRecord) {
    val reason = record.commandsUnattributed ?: return
    Text(
        stringResource(if (reason == Unattributed.OVERLAP) R.string.book_turn_unattributed_overlap else R.string.book_turn_unattributed_terminal),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun turnCountText(card: TurnCard): String =
    stringResource(R.string.book_turn_card, card.files) + if (card.deleted > 0) " · " + stringResource(R.string.book_turn_deleted_count, card.deleted) else ""

/** 作品页顶部：最近一轮待审的回复。× = 这一轮不在横幅上提醒。 */
@Composable
internal fun BookTurnBanner(record: TurnRecord, title: String?, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val files = record.files.values.count { !it.layoutOnly }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 36.dp).padding(start = AppSpacing.md).testTag("book-turn-banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (title != null) stringResource(R.string.book_turn_banner, title, files) else stringResource(R.string.book_turn_banner_untitled, files),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.book_turn_review), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(HugeIcons.Cancel01, stringResource(R.string.book_turn_banner_dismiss), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 作品页溢出菜单“最近的改动”：待审、7 天内已审阅与已归档的轮次（含静音的对话）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentTurnsSheet(state: BookTurnState, onOpen: (TurnRecord) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.book_turn_recent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 8.dp),
        )
        if (state.recent.isEmpty()) {
            Text(
                stringResource(R.string.book_turn_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 12.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(state.recent, key = { "${it.conversationId}/${it.messageId}" }) { record ->
                val status = when (record.status) {
                    TurnStatus.PENDING -> stringResource(R.string.book_turn_status_pending)
                    TurnStatus.REVIEWED -> stringResource(R.string.book_turn_reviewed_summary, record.reverted)
                    TurnStatus.ARCHIVED -> stringResource(R.string.book_turn_status_archived)
                }
                val cleaned = record.files.values.any { it.missing == TurnMissing.AFTER }
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(record) }.heightIn(min = 48.dp).padding(horizontal = AppSpacing.md, vertical = 6.dp),
                ) {
                    Text(
                        state.titles[record.conversationId] ?: stringResource(R.string.book_turn_untitled),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            record.finishedAt?.let(::formatHistoryTime),
                            stringResource(R.string.book_turn_card, record.files.values.count { !it.layoutOnly }.coerceAtLeast(1)),
                            status,
                            if (cleaned) stringResource(R.string.book_turn_cleaned) else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

