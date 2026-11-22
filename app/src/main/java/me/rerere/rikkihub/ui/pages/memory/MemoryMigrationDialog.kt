package me.rerere.rikkihub.ui.pages.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.repository.MemoryConflictStrategy
import me.rerere.rikkihub.data.repository.MemoryMigrationPreview
import me.rerere.rikkihub.ui.components.ui.AlertDialog

/**
 * 私有记忆 → 全局记忆的安全迁移确认弹窗：
 * 展示新增/相同/冲突预检清单、冲突处理策略（合并/跳过/覆盖）、48KB 超限告警与做梦进度同步勾选项。
 */
@Composable
internal fun MemoryMigrationDialog(
    sourceName: String,
    preview: MemoryMigrationPreview?,
    loading: Boolean,
    migrating: Boolean,
    dreamRunning: Boolean,
    showSyncMarksOption: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (strategy: MemoryConflictStrategy, syncMarks: Boolean) -> Unit,
) {
    var strategy by remember(preview) { mutableStateOf(MemoryConflictStrategy.MERGE) }
    var syncMarks by remember(preview) { mutableStateOf(showSyncMarksOption) }

    LaunchedEffect(strategy, preview) {
        if (preview != null && strategy == MemoryConflictStrategy.SKIP && preview.newPaths.isEmpty()) {
            syncMarks = false
        }
    }

    val oversizedOnMerge = remember(preview) {
        preview?.conflicts?.filter { it.mergeExceedsLimit }?.map { it.path }.orEmpty()
    }
    val hasWork = preview != null && (
        preview.newPaths.isNotEmpty() ||
            (preview.conflicts.isNotEmpty() && strategy != MemoryConflictStrategy.SKIP) ||
            (showSyncMarksOption && syncMarks && preview.sourceCount > 0)
        )

    AlertDialog(
        onDismissRequest = { if (!migrating) onDismiss() },
        title = { Text(stringResource(R.string.memory_migrate_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_migrate_dialog_source, sourceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (dreamRunning) {
                    Text(
                        text = stringResource(R.string.memory_migrate_dream_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                when {
                    loading || preview == null -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.memory_migrate_preview_loading))
                        }
                    }

                    preview.sourceCount == 0 -> {
                        Text(
                            text = stringResource(R.string.memory_migrate_empty_source),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> {
                        Text(
                            text = stringResource(
                                R.string.memory_migrate_preview_summary,
                                preview.sourceCount,
                                preview.newPaths.size,
                                preview.identicalPaths.size,
                                preview.conflicts.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        if (preview.conflicts.isNotEmpty()) {
                            val shownPaths = preview.conflicts.take(6).joinToString("\n") { "• ${it.path}" } +
                                if (preview.conflicts.size > 6) "\n• +${preview.conflicts.size - 6}" else ""
                            Text(
                                text = stringResource(R.string.memory_migrate_conflicts_header, shownPaths),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            ConflictOptionRow(
                                selected = strategy == MemoryConflictStrategy.MERGE,
                                title = stringResource(R.string.memory_migrate_strategy_merge),
                                description = stringResource(R.string.memory_migrate_strategy_merge_desc),
                                onClick = { strategy = MemoryConflictStrategy.MERGE },
                            )
                            if (strategy == MemoryConflictStrategy.MERGE && oversizedOnMerge.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.memory_migrate_merge_oversized_warn,
                                        oversizedOnMerge.joinToString(", "),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 36.dp),
                                )
                            }

                            ConflictOptionRow(
                                selected = strategy == MemoryConflictStrategy.SKIP,
                                title = stringResource(R.string.memory_migrate_strategy_skip),
                                description = stringResource(R.string.memory_migrate_strategy_skip_desc),
                                onClick = { strategy = MemoryConflictStrategy.SKIP },
                            )

                            ConflictOptionRow(
                                selected = strategy == MemoryConflictStrategy.OVERWRITE,
                                title = stringResource(R.string.memory_migrate_strategy_overwrite),
                                description = stringResource(R.string.memory_migrate_strategy_overwrite_desc),
                                destructive = true,
                                onClick = { strategy = MemoryConflictStrategy.OVERWRITE },
                            )
                        } else if (!preview.hasActionableChanges) {
                            Text(
                                text = stringResource(R.string.memory_migrate_already_synced),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }

                        if (showSyncMarksOption) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { syncMarks = !syncMarks }
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = syncMarks,
                                    onCheckedChange = { syncMarks = it },
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(R.string.memory_migrate_sync_marks),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.memory_migrate_sync_marks_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.memory_migrate_preserve_source_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && !migrating && !dreamRunning && hasWork,
                onClick = { onConfirm(strategy, showSyncMarksOption && syncMarks) },
            ) {
                Text(stringResource(R.string.memory_migrate_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !migrating, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ConflictOptionRow(
    selected: Boolean,
    title: String,
    description: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive && selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (destructive && selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
