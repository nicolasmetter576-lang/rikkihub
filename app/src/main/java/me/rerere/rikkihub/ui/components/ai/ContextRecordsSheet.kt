package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ContextCheckpointKind
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkihub.R
import me.rerere.rikkihub.service.ContextCheckpointRecord
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.utils.formatNumber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private val recordTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun ContextRecordsSheet(
    records: List<ContextCheckpointRecord>,
    canClear: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onJumpToNode: (Uuid) -> Unit,
    onRevoke: (checkpointNodeId: Uuid, cascadeLater: Boolean) -> Unit,
    onClearContext: () -> Unit,
) {
    var pendingCascadeRecord by remember { mutableStateOf<ContextCheckpointRecord?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = HugeIcons.TransactionHistory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_page_context_records),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showClearConfirm = true },
                    enabled = canClear && !isBusy,
                    modifier = Modifier.testTag("context_records_clear_button"),
                ) {
                    Icon(HugeIcons.Clean, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_page_context_clear))
                }
            }

            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_page_context_records_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(records.asReversed(), key = { it.nodeId.toString() }) { record ->
                        ContextRecordCard(
                            record = record,
                            isBusy = isBusy,
                            onJump = {
                                onJumpToNode(record.nodeId)
                                onDismiss()
                            },
                            onRevokeClick = {
                                if (record.laterCheckpointNodeIds.isNotEmpty()) {
                                    pendingCascadeRecord = record
                                } else {
                                    onRevoke(record.nodeId, false)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingCascadeRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingCascadeRecord = null },
            title = { Text(stringResource(R.string.chat_page_context_record_revoke_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chat_page_context_record_revoke_later_message,
                        target.laterCheckpointNodeIds.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCascadeRecord = null
                        onRevoke(target.nodeId, true)
                    },
                    modifier = Modifier.testTag("context_record_revoke_cascade"),
                ) {
                    Text(
                        stringResource(R.string.chat_page_context_record_revoke_cascade),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { pendingCascadeRecord = null },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            pendingCascadeRecord = null
                            onRevoke(target.nodeId, false)
                        },
                        modifier = Modifier.testTag("context_record_revoke_single"),
                    ) {
                        Text(stringResource(R.string.chat_page_context_record_revoke_single))
                    }
                }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.chat_page_context_clear_confirm_title)) },
            text = { Text(stringResource(R.string.chat_page_context_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearContext()
                    },
                    modifier = Modifier.testTag("context_clear_confirm_button"),
                ) {
                    Text(stringResource(R.string.chat_page_context_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextRecordCard(
    record: ContextCheckpointRecord,
    isBusy: Boolean,
    onJump: () -> Unit,
    onRevokeClick: () -> Unit,
) {
    var previewsExpanded by remember(record.nodeId) { mutableStateOf(false) }
    val timestamp = remember(record.createdAt) {
        runCatching {
            LocalDateTime.ofInstant(Instant.parse(record.createdAt), ZoneId.systemDefault())
                .format(recordTimeFormatter)
        }.getOrElse { record.createdAt.take(16).replace('T', ' ') }
    }
    val kindRes = when (record.meta?.kind) {
        ContextCheckpointKind.MANUAL -> R.string.chat_page_context_record_kind_manual
        ContextCheckpointKind.AUTO -> R.string.chat_page_context_record_kind_auto
        ContextCheckpointKind.OVERFLOW_RETRY -> R.string.chat_page_context_record_kind_overflow
        ContextCheckpointKind.CLEAR -> R.string.chat_page_context_record_kind_clear
        null -> if (record.isClear) {
            R.string.chat_page_context_record_kind_clear
        } else {
            R.string.chat_page_context_record_kind_legacy
        }
    }
    val metaDetails = remember(record) {
        buildList {
            record.meta?.templateId?.takeIf { it.isNotBlank() }?.let(::add)
            record.meta?.requestCount?.takeIf { it > 0 }?.let { add("${it}x") }
            if (record.meta?.chunked == true) add("staged")
            record.meta?.carriedCount?.takeIf { it > 0 }?.let { add("+$it") }
            record.meta?.usage?.totalTokens?.takeIf { it > 0 }?.let { add("${it.formatNumber()} tok") }
        }.joinToString(" · ")
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("context_record_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RecordBadge(
                    text = stringResource(
                        if (record.isActive) R.string.chat_page_context_record_active
                        else R.string.chat_page_context_record_superseded
                    ),
                    containerColor = if (record.isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (record.isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                RecordBadge(
                    text = stringResource(kindRes),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (record.meta?.fromOriginal == true) {
                    RecordBadge(
                        text = stringResource(R.string.chat_page_context_record_from_original),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (record.coveredMessageCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.chat_page_context_record_covered_count,
                            record.coveredMessageCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (metaDetails.isNotEmpty()) {
                    Text(
                        text = metaDetails,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = if (record.isClear) {
                    stringResource(R.string.chat_page_context_cleared_hint)
                } else {
                    record.summaryText.take(260)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            if (record.coveredPreviews.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { previewsExpanded = !previewsExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (previewsExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (previewsExpanded) {
                                stringResource(R.string.chat_page_context_record_preview_toggle_hide)
                            } else {
                                stringResource(
                                    R.string.chat_page_context_record_preview_toggle_show,
                                    record.coveredPreviews.size,
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (previewsExpanded) {
                        HorizontalDivider()
                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            record.coveredPreviews.forEach { preview ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = when (preview.role) {
                                            MessageRole.USER -> "U"
                                            MessageRole.ASSISTANT -> "A"
                                            MessageRole.SYSTEM -> "S"
                                            MessageRole.TOOL -> "T"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = preview.previewText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onJump,
                    modifier = Modifier.testTag("context_record_jump_${record.nodeId}"),
                ) {
                    Text(stringResource(R.string.chat_page_context_record_jump))
                }
                TextButton(
                    onClick = onRevokeClick,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("context_record_revoke_${record.nodeId}"),
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.chat_page_context_record_revoke),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
