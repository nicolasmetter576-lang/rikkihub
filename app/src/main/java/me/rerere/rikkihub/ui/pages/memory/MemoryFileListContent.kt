package me.rerere.rikkihub.ui.pages.memory

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.MAX_BACKFILL_CONVERSATIONS
import me.rerere.rikkihub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.model.groupByDirectory
import me.rerere.rikkihub.data.model.memoryTitleOf
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.FilledTonalButton
import me.rerere.rikkihub.ui.components.ui.ItemAction
import me.rerere.rikkihub.ui.components.ui.ItemActionMenu
import me.rerere.rikkihub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkihub.ui.theme.AppSpacing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * 记忆库文件列表共享视图：供助手记忆页与独立全局记忆管理页复用。
 */
@Composable
internal fun MemoryFileListContent(
    memories: List<MemoryFile>,
    consolidation: MemoryConsolidationStatus?,
    autoDream: Boolean,
    canBackfill: Boolean,
    onOrganizeMemory: () -> Unit,
    onCancelDream: () -> Unit,
    onCountBackfill: suspend (sinceMs: Long) -> Int,
    onBackfill: (sinceMs: Long) -> Unit,
    onOpenMemory: (path: String) -> Unit,
    onDeleteMemory: (MemoryFile) -> Unit,
    deleteWarning: String? = null,
    headerContent: (@Composable ColumnScope.() -> Unit)? = null,
    emptyStateContent: @Composable () -> Unit,
) {
    var pendingDeleteMemory by remember { mutableStateOf<MemoryFile?>(null) }
    var showBackfillDialog by remember { mutableStateOf(false) }
    val memoryGroups = remember(memories) { memories.groupByDirectory() }

    if (memoryGroups.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.sm, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            headerContent?.invoke(this)
            if (consolidation != null) {
                MemoryConsolidationSection(
                    status = consolidation,
                    autoDream = autoDream,
                    canBackfill = canBackfill,
                    onOrganize = onOrganizeMemory,
                    onCancel = onCancelDream,
                    onOpenBackfill = { showBackfillDialog = true },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                emptyStateContent()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.sm, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            headerContent?.invoke(this)
            if (consolidation != null) {
                MemoryConsolidationSection(
                    status = consolidation,
                    autoDream = autoDream,
                    canBackfill = canBackfill,
                    onOrganize = onOrganizeMemory,
                    onCancel = onCancelDream,
                    onOpenBackfill = { showBackfillDialog = true },
                )
            }

            memoryGroups.fastForEach { (directory, files) ->
                CardGroup(
                    title = { Text(memorySectionTitle(directory)) },
                ) {
                    files.fastForEach { memory ->
                        item(
                            onClick = { onOpenMemory(memory.path) },
                            trailingContent = {
                                ItemActionMenu(
                                    actions = listOf(
                                        ItemAction(
                                            text = stringResource(R.string.delete),
                                            icon = HugeIcons.Delete01,
                                            destructive = true,
                                            onClick = { pendingDeleteMemory = memory },
                                        ),
                                    )
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = memory.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (memory.description.isNotEmpty()) {
                                        Text(
                                            text = memory.description,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.assistant_page_memory_updated_at,
                                            relativeTimeOf(memory.updatedAt),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = pendingDeleteMemory?.path.orEmpty(),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!deleteWarning.isNullOrBlank()) {
                    Text(
                        text = deleteWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )

    if (showBackfillDialog) {
        MemoryBackfillDialog(
            onCountCandidates = onCountBackfill,
            onConfirm = { sinceMs ->
                showBackfillDialog = false
                onBackfill(sinceMs)
            },
            onDismiss = { showBackfillDialog = false },
        )
    }
}

@Composable
internal fun MemoryEmptyState(
    description: String,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Brain02,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.assistant_page_memory_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
        if (primaryActionLabel != null && onPrimaryAction != null) {
            FilledTonalButton(onClick = onPrimaryAction) {
                Text(primaryActionLabel)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) {
                Text(secondaryActionLabel)
            }
        }
    }
}

/** 后台整理的状态、手动入口与有界历史回溯入口 */
@Composable
internal fun MemoryConsolidationSection(
    status: MemoryConsolidationStatus,
    autoDream: Boolean,
    canBackfill: Boolean,
    onOrganize: () -> Unit,
    onCancel: () -> Unit,
    onOpenBackfill: () -> Unit,
) {
    val lastRun = status.lastRun
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.memory_dream_title)) },
            supportingContent = { Text(memoryConsolidationSummary(status, autoDream)) },
            trailingContent = {
                if (status.running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ContainedLoadingIndicator(modifier = Modifier.size(28.dp))
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.memory_dream_stop))
                        }
                    }
                } else {
                    FilledTonalButton(onClick = onOrganize) {
                        Text(
                            stringResource(
                                if (lastRun?.error != null) {
                                    R.string.assistant_page_memory_dream_retry
                                } else {
                                    R.string.assistant_page_memory_dream_now
                                }
                            )
                        )
                    }
                }
            },
        )
        if (canBackfill && !status.running) {
            item(
                onClick = onOpenBackfill,
                leadingContent = { Icon(HugeIcons.Time02, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.memory_backfill_title)) },
                supportingContent = { Text(stringResource(R.string.memory_backfill_entry_desc)) },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun MemoryBackfillDialog(
    onCountCandidates: suspend (sinceMs: Long) -> Int,
    onConfirm: (sinceMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val windows = listOf(7, 30)
    var selectedDays by remember { mutableIntStateOf(30) }
    var candidateCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(selectedDays) {
        candidateCount = null
        val sinceMs = System.currentTimeMillis() - selectedDays * DAY_MS
        candidateCount = runCatching { onCountCandidates(sinceMs) }.getOrDefault(0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_backfill_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.memory_backfill_dialog_desc, MAX_BACKFILL_CONVERSATIONS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                windows.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDays = days }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = selectedDays == days,
                            onClick = { selectedDays = days },
                        )
                        Text(
                            text = stringResource(R.string.memory_backfill_window_days, days),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val count = candidateCount
                Text(
                    text = when {
                        count == null -> stringResource(R.string.memory_backfill_counting)
                        count == 0 -> stringResource(R.string.memory_backfill_none)
                        else -> stringResource(R.string.memory_backfill_found, count, minOf(count, MAX_BACKFILL_CONVERSATIONS))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (count == 0) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (candidateCount ?: 0) > 0,
                onClick = { onConfirm(System.currentTimeMillis() - selectedDays * DAY_MS) },
            ) {
                Text(stringResource(R.string.memory_backfill_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun memoryConsolidationSummary(status: MemoryConsolidationStatus, autoDream: Boolean): String {
    val lastRun = status.lastRun
    return when {
        status.running -> stringResource(R.string.memory_dreaming)
        lastRun?.error != null -> lastRun.resultText()
        else -> listOfNotNull(
            status.pendingTurns.takeIf { it > 0 }?.let {
                stringResource(R.string.assistant_page_memory_dream_pending, it)
            },
            lastRun?.let { "${relativeTimeOf(it.finishedAt)}: ${it.resultText()} (${durationTextOf(it.duration)})" },
        ).joinToString("\n").ifEmpty {
            stringResource(
                if (autoDream) R.string.assistant_page_memory_dream_hint else R.string.assistant_page_memory_dream_manual_hint
            )
        }
    }
}

private fun durationTextOf(duration: Duration): String = duration.inWholeSeconds.coerceAtLeast(1).seconds.toString()

@Composable
internal fun memorySectionTitle(directory: String): String = when (directory) {
    "/" -> stringResource(R.string.assistant_page_memory_section_you)
    "/topics" -> stringResource(R.string.assistant_page_memory_section_topics)
    "/areas" -> stringResource(R.string.assistant_page_memory_section_areas)
    "/people" -> stringResource(R.string.assistant_page_memory_section_people)
    else -> memoryTitleOf(directory.removePrefix("/"))
}

internal fun relativeTimeOf(time: Long): String = DateUtils.getRelativeTimeSpanString(
    time,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_MONTH,
).toString()
