package me.rerere.rikkihub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.sync.InsufficientSnapshotSpaceException
import me.rerere.rikkihub.data.sync.LocalSnapshotEntry
import me.rerere.rikkihub.data.sync.LocalSnapshotOrigin
import me.rerere.rikkihub.data.sync.LocalSnapshotSkipReason
import me.rerere.rikkihub.data.sync.RestoreOutcome
import me.rerere.rikkihub.data.sync.RestoreSource
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.StickyHeader
import me.rerere.rikkihub.ui.components.ui.switchItem
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.pages.backup.BackupVM
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.utils.fileSizeToString
import me.rerere.rikkihub.utils.toLocalDateTime
import java.time.Instant

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalSnapshotsTab(
    vm: BackupVM,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val deviceState by vm.deviceState.collectAsStateWithLifecycle()
    val latestReceipt by vm.latestReceipt.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val isTaking by vm.isTakingSnapshot.collectAsStateWithLifecycle()

    val config = deviceState.snapshotConfig
    val runState = deviceState.snapshotRunState

    var deleteTarget by remember { mutableStateOf<LocalSnapshotEntry?>(null) }
    var exportTarget by remember { mutableStateOf<LocalSnapshotEntry?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val entry = exportTarget
        exportTarget = null
        if (uri != null && entry != null) {
            scope.launch {
                runCatching {
                    vm.exportSnapshotToUri(entry, uri)
                    toaster.show(
                        context.getString(R.string.backup_snapshot_exported),
                        type = ToastType.Success,
                    )
                }.onFailure { err ->
                    toaster.show(
                        err.message ?: context.getString(R.string.backup_page_unknown_error),
                        type = ToastType.Error,
                    )
                }
            }
        }
    }

    val undoCandidate = remember(latestReceipt, snapshots) {
        val r = latestReceipt
        val snapshotId = r?.preRestoreSnapshotId
        if (r != null &&
            r.outcome == RestoreOutcome.COMMITTED &&
            r.source != RestoreSource.UNDO &&
            !snapshotId.isNullOrBlank() &&
            snapshots.any { it.id == snapshotId }
        ) {
            r to snapshotId
        } else {
            null
        }
    }

    val totalBytes = remember(snapshots) { snapshots.sumOf { it.bytes } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(AppSpacing.sm),
    ) {
        if (undoCandidate != null) {
            val (receipt, undoSnapshotId) = undoCandidate
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Lucide.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.backup_undo_banner_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.backup_undo_banner_desc,
                                receipt.sourceDisplayName.ifBlank { receipt.source.name },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching { vm.undoRestore(undoSnapshotId) }
                                            .onFailure { err ->
                                                toaster.show(
                                                    err.message ?: context.getString(R.string.backup_page_unknown_error),
                                                    type = ToastType.Error,
                                                )
                                            }
                                    }
                                },
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text(stringResource(R.string.backup_undo_action))
                            }
                        }
                    }
                }
            }
        }

        item {
            CardGroup {
                item(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.backup_snapshot_status_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.backup_snapshot_summary,
                                    snapshots.size,
                                    totalBytes.fileSizeToString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val lastTimeText = runState.lastSuccessAtMillis?.let { millis ->
                                stringResource(
                                    R.string.backup_snapshot_last_success,
                                    Instant.ofEpochMilli(millis).toLocalDateTime(),
                                )
                            } ?: stringResource(R.string.backup_snapshot_never)
                            Text(
                                text = lastTimeText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val lastFailure = runState.lastFailureMessage
                            val lastSkip = runState.lastSkipReason
                            if (!lastFailure.isNullOrBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.backup_snapshot_last_error,
                                            lastFailure,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
                            } else if (lastSkip != null) {
                                val reasonRes = when (lastSkip) {
                                    LocalSnapshotSkipReason.DISABLED -> R.string.backup_snapshot_skip_disabled
                                    LocalSnapshotSkipReason.NOT_DUE -> R.string.backup_snapshot_skip_not_due
                                    LocalSnapshotSkipReason.BACKOFF -> R.string.backup_snapshot_skip_backoff
                                    LocalSnapshotSkipReason.BUSY -> R.string.backup_snapshot_skip_busy
                                    LocalSnapshotSkipReason.GENERATING -> R.string.backup_snapshot_skip_generating
                                    LocalSnapshotSkipReason.INSUFFICIENT_SPACE -> R.string.backup_snapshot_skip_space
                                    LocalSnapshotSkipReason.UNCHANGED -> R.string.backup_snapshot_skip_unchanged
                                }
                                Text(
                                    text = stringResource(
                                        R.string.backup_snapshot_last_skip,
                                        stringResource(reasonRes),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (lastSkip == LocalSnapshotSkipReason.INSUFFICIENT_SPACE) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    },
                    leadingContent = {
                        Icon(Lucide.History, contentDescription = null)
                    },
                )
                formItem {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTaking,
                        shape = MaterialTheme.shapes.medium,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    vm.takeManualSnapshot()
                                    toaster.show(
                                        context.getString(R.string.backup_snapshot_take_success),
                                        type = ToastType.Success,
                                    )
                                }.onFailure { err ->
                                    val msg = if (err is InsufficientSnapshotSpaceException) {
                                        context.getString(R.string.backup_snapshot_insufficient_space)
                                    } else {
                                        err.message ?: context.getString(R.string.backup_page_unknown_error)
                                    }
                                    toaster.show(
                                        context.getString(R.string.backup_snapshot_take_failed, msg),
                                        type = ToastType.Error,
                                    )
                                }
                            }
                        },
                    ) {
                        if (isTaking) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                if (isTaking) R.string.backup_snapshot_taking
                                else R.string.backup_snapshot_take_now
                            )
                        )
                    }
                }
            }
        }

        item {
            CardGroup(title = { Text(stringResource(R.string.backup_snapshot_policy_title)) }) {
                switchItem(
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        vm.updateSnapshotConfig { it.copy(enabled = enabled) }
                    },
                    supportingContent = { Text(stringResource(R.string.backup_snapshot_enable_desc)) },
                ) {
                    Text(stringResource(R.string.backup_snapshot_enable))
                }

                if (config.enabled) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.backup_snapshot_interval)) },
                        supportingContent = { Text(stringResource(R.string.backup_snapshot_interval_auto_desc)) },
                    ) {
                        val intervalOptions = listOf(0, 1, 3, 7, 14, 30)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            intervalOptions.forEach { days ->
                                FilterChip(
                                    selected = config.intervalDays == days,
                                    onClick = { vm.updateSnapshotConfig { it.copy(intervalDays = days) } },
                                    label = {
                                        Text(
                                            if (days == 0) stringResource(R.string.backup_snapshot_interval_auto)
                                            else stringResource(R.string.backup_snapshot_interval_days, days)
                                        )
                                    },
                                )
                            }
                        }
                    }

                    formItem(
                        headlineContent = { Text(stringResource(R.string.backup_snapshot_keep_recent)) },
                    ) {
                        val recentOptions = listOf(1, 3, 5, 7, 10)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recentOptions.forEach { count ->
                                FilterChip(
                                    selected = config.keepRecent == count,
                                    onClick = { vm.updateSnapshotConfig { it.copy(keepRecent = count) } },
                                    label = { Text(count.toString()) },
                                )
                            }
                        }
                    }

                    switchItem(
                        checked = config.keepWeekly,
                        onCheckedChange = { v -> vm.updateSnapshotConfig { it.copy(keepWeekly = v) } },
                        supportingContent = { Text(stringResource(R.string.backup_snapshot_keep_weekly_desc)) },
                    ) {
                        Text(stringResource(R.string.backup_snapshot_keep_weekly))
                    }

                    switchItem(
                        checked = config.keepMonthly,
                        onCheckedChange = { v -> vm.updateSnapshotConfig { it.copy(keepMonthly = v) } },
                        supportingContent = { Text(stringResource(R.string.backup_snapshot_keep_monthly_desc)) },
                    ) {
                        Text(stringResource(R.string.backup_snapshot_keep_monthly))
                    }

                    formItem(
                        headlineContent = { Text(stringResource(R.string.backup_snapshot_max_size)) },
                        supportingContent = { Text(stringResource(R.string.backup_snapshot_max_size_desc)) },
                    ) {
                        val limits = listOf(
                            512L * 1024L * 1024L to "512 MB",
                            1024L * 1024L * 1024L to "1 GB",
                            2L * 1024L * 1024L * 1024L to "2 GB",
                            5L * 1024L * 1024L * 1024L to "5 GB",
                            0L to stringResource(R.string.backup_snapshot_size_unlimited),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            limits.forEach { (bytes, label) ->
                                FilterChip(
                                    selected = config.maxTotalBytes == bytes,
                                    onClick = { vm.updateSnapshotConfig { it.copy(maxTotalBytes = bytes) } },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    switchItem(
                        checked = config.announceResult,
                        onCheckedChange = { v -> vm.updateSnapshotConfig { it.copy(announceResult = v) } },
                        supportingContent = { Text(stringResource(R.string.backup_snapshot_announce_desc)) },
                    ) {
                        Text(stringResource(R.string.backup_snapshot_announce))
                    }
                }
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_snapshot_list_title))
            }
        }

        if (snapshots.isEmpty()) {
            item {
                CardGroup {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.backup_snapshot_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        } else {
            items(snapshots, key = { it.id }) { entry ->
                SnapshotEntryCard(
                    entry = entry,
                    onPinToggle = { vm.togglePinSnapshot(entry) },
                    onExport = {
                        exportTarget = entry
                        exportLauncher.launch("rikkihub_${entry.id}")
                    },
                    onDelete = { deleteTarget = entry },
                    onRestore = {
                        scope.launch {
                            runCatching { vm.startSnapshotRestore(entry) }
                                .onFailure { err ->
                                    toaster.show(
                                        err.message ?: context.getString(R.string.backup_page_unknown_error),
                                        type = ToastType.Error,
                                    )
                                }
                        }
                    },
                )
            }
        }
    }

    deleteTarget?.let { target ->
        val contentSnapshotCount = snapshots.count { it.hasContent }
        val isLastWithContent = target.hasContent && contentSnapshotCount <= 1
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.backup_page_delete)) },
            text = {
                Text(
                    if (isLastWithContent) {
                        stringResource(R.string.backup_snapshot_delete_last_confirm)
                    } else {
                        stringResource(
                            R.string.backup_snapshot_delete_confirm,
                            Instant.ofEpochMilli(target.createdAtMillis).toLocalDateTime(),
                        )
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteSnapshot(target)
                        deleteTarget = null
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SnapshotEntryCard(
    entry: LocalSnapshotEntry,
    onPinToggle: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    val originText = when (entry.origin) {
        LocalSnapshotOrigin.AUTOMATIC -> stringResource(R.string.backup_snapshot_origin_auto)
        LocalSnapshotOrigin.MANUAL -> stringResource(R.string.backup_snapshot_origin_manual)
        LocalSnapshotOrigin.BEFORE_RESTORE -> stringResource(R.string.backup_snapshot_origin_before_restore)
    }
    val metaText = stringResource(
        R.string.backup_snapshot_meta_line,
        entry.bytes.fileSizeToString(),
        entry.conversationCount,
        entry.messageNodeCount,
    )
    CardGroup {
        formItem(
            headlineContent = {
                Text(
                    text = Instant.ofEpochMilli(entry.createdAtMillis).toLocalDateTime(),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val versionLine = entry.appVersionName?.let { ver ->
                        val dbSuffix = entry.databaseSchemaVersion?.let { " · DB v$it" }.orEmpty()
                        "$originText · $ver$dbSuffix"
                    } ?: originText
                    Text(
                        text = if (entry.pinned) {
                            "$versionLine · ${stringResource(R.string.backup_snapshot_pinned)}"
                        } else {
                            versionLine
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            trailingContent = {
                IconButton(onClick = onPinToggle) {
                    Icon(
                        imageVector = if (entry.pinned) Lucide.Pin else Lucide.PinOff,
                        contentDescription = stringResource(
                            if (entry.pinned) R.string.backup_snapshot_action_unpin
                            else R.string.backup_snapshot_action_pin
                        ),
                        tint = if (entry.pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(R.string.backup_page_delete),
                        modifier = Modifier.size(18.dp),
                    )
                }
                OutlinedButton(
                    onClick = onExport,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Lucide.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.backup_snapshot_action_export))
                }
                Button(
                    onClick = onRestore,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(R.string.backup_snapshot_action_restore))
                }
            }
        }
    }
}
