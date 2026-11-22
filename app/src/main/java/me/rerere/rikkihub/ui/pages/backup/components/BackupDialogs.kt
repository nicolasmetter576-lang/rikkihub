package me.rerere.rikkihub.ui.pages.backup.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Undo
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.sync.BackupCompatibilityReport
import me.rerere.rikkihub.data.sync.CompatibilityLevel
import me.rerere.rikkihub.data.sync.RestoreOutcome
import me.rerere.rikkihub.data.sync.RestoreReceipt
import me.rerere.rikkihub.data.sync.RestoreSource
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.pages.backup.RestoreStage
import me.rerere.rikkihub.utils.toLocalDateTime
import java.time.Instant

@Composable
fun SetBackupPasswordDialog(
    isChange: Boolean,
    initialRemember: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, rememberOnDevice: Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberOnDevice by remember { mutableStateOf(initialRemember) }
    var validationError by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Lucide.Lock, contentDescription = null) },
        title = {
            Text(
                stringResource(
                    if (isChange) R.string.backup_enc_change_password_title
                    else R.string.backup_enc_set_password_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = stringResource(R.string.backup_enc_no_recovery_warning),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = {
                        password = it
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.backup_enc_password_label)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                        }
                    },
                    singleLine = true,
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.backup_enc_confirm_password_label)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                )

                validationError?.let { resId ->
                    Text(
                        text = stringResource(resId),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberOnDevice = !rememberOnDevice }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.backup_enc_remember_password),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.backup_enc_remember_password_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rememberOnDevice,
                        onCheckedChange = { rememberOnDevice = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        password.isEmpty() -> validationError = R.string.backup_enc_password_empty
                        password != confirmPassword -> validationError = R.string.backup_enc_password_mismatch
                        else -> onConfirm(password, rememberOnDevice)
                    }
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.confirm))
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
fun BackupPasswordPromptDialog(
    title: String,
    description: String,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Lucide.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_enc_password_label)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                        }
                    },
                    singleLine = true,
                    isError = !errorMessage.isNullOrBlank(),
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.confirm))
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
fun RestoreBusyDialog(stage: RestoreStage) {
    val labelRes = when (stage) {
        RestoreStage.DOWNLOADING -> R.string.backup_precheck_downloading
        RestoreStage.DECRYPTING -> R.string.backup_precheck_decrypting
        RestoreStage.INSPECTING -> R.string.backup_precheck_inspecting
        RestoreStage.STAGING -> R.string.backup_precheck_staging
    }
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularWavyProgressIndicator(modifier = Modifier.size(28.dp))
                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
    )
}

@Composable
fun RestorePrecheckDialog(
    displayName: String,
    report: BackupCompatibilityReport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    var consentChecked by remember(displayName, report.level) { mutableStateOf(false) }

    val restoreParts = buildList {
        if (report.willRestoreSettings) add(context.getString(R.string.backup_precheck_part_settings))
        if (report.willRestoreDatabase) add(context.getString(R.string.backup_precheck_part_db))
        if (report.willRestoreFiles) add(context.getString(R.string.backup_precheck_part_files, report.attachmentFileCount))
    }.joinToString(" · ")

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (report.isBlocked || report.requiresExplicitConsent) {
                    Lucide.ShieldAlert
                } else {
                    Lucide.ShieldCheck
                },
                contentDescription = null,
                tint = if (report.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.backup_precheck_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                )

                val manifest = report.manifest
                if (manifest != null) {
                    Text(
                        text = stringResource(
                            R.string.backup_precheck_source_info,
                            manifest.appVersionName,
                            manifest.databaseSchemaVersion,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val formattedTime = runCatching {
                        Instant.parse(manifest.createdAtUtc).toLocalDateTime()
                    }.getOrDefault(manifest.createdAtUtc)
                    Text(
                        text = stringResource(R.string.backup_precheck_created_at, formattedTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.backup_precheck_stats,
                            manifest.stats.conversationCount,
                            manifest.stats.messageNodeCount,
                            manifest.stats.attachmentFileCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val detectedDb = report.detectedDatabaseVersion
                    Text(
                        text = if (detectedDb != null) {
                            stringResource(R.string.backup_precheck_legacy_info, detectedDb)
                        } else {
                            stringResource(R.string.backup_precheck_legacy_no_db)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (restoreParts.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_precheck_will_restore, restoreParts),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                when (report.level) {
                    CompatibilityLevel.NEWER_DATABASE -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.backup_precheck_blocked_newer_db,
                                    report.detectedDatabaseVersion ?: 0,
                                    report.currentDatabaseVersion,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    CompatibilityLevel.UNSUPPORTED_FORMAT -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                text = stringResource(R.string.backup_precheck_blocked_format),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    CompatibilityLevel.INVALID_ARCHIVE -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                text = stringResource(R.string.backup_precheck_blocked_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    CompatibilityLevel.NEWER_APP_SAME_DB -> {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.backup_precheck_warn_newer_app,
                                    manifest?.appVersionCode ?: 0,
                                    report.currentAppVersionCode,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { consentChecked = !consentChecked },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = consentChecked,
                                onCheckedChange = { consentChecked = it },
                            )
                            Text(
                                text = stringResource(R.string.backup_precheck_warn_newer_app_ack),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    CompatibilityLevel.OLDER_DATABASE -> {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.backup_precheck_info_older_db,
                                    report.detectedDatabaseVersion ?: 0,
                                    report.currentDatabaseVersion,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    CompatibilityLevel.COMPATIBLE -> Unit
                }

                if (!report.isBlocked) {
                    Text(
                        text = stringResource(
                            if (report.willRestoreFiles) {
                                R.string.backup_precheck_files_note
                            } else {
                                R.string.backup_precheck_safety_note
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (!report.isBlocked) {
                Button(
                    onClick = onConfirm,
                    enabled = !report.requiresExplicitConsent || consentChecked,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(R.string.backup_precheck_restore_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (report.isBlocked) R.string.backup_receipt_close else R.string.cancel))
            }
        },
    )
}

@Composable
fun RestoreReceiptDialog(
    receipt: RestoreReceipt,
    canUndo: Boolean,
    onDismiss: () -> Unit,
    onUndo: (String) -> Unit,
) {
    val context = LocalContext.current
    val isRolledBack = receipt.outcome == RestoreOutcome.ROLLED_BACK
    val sourceLabel = when (receipt.source) {
        RestoreSource.LOCAL -> context.getString(R.string.backup_page_import_export)
        RestoreSource.WEBDAV -> context.getString(R.string.backup_page_webdav_backup)
        RestoreSource.S3 -> context.getString(R.string.backup_page_s3_backup)
        RestoreSource.SNAPSHOT -> context.getString(R.string.backup_page_local_snapshots)
        RestoreSource.UNDO -> context.getString(R.string.backup_undo_action)
    }
    val displaySource = if (receipt.sourceDisplayName.isNotBlank()) {
        "$sourceLabel · ${receipt.sourceDisplayName}"
    } else {
        sourceLabel
    }

    val restoredItems = buildList {
        if (receipt.restoredSettings) add(context.getString(R.string.backup_precheck_part_settings))
        if (receipt.restoredDatabase) add(context.getString(R.string.backup_precheck_part_db))
        if (receipt.restoredFilesCount > 0) {
            add(context.getString(R.string.backup_precheck_part_files, receipt.restoredFilesCount))
        }
    }.joinToString(" · ")

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isRolledBack) Lucide.ShieldAlert else Lucide.ShieldCheck,
                contentDescription = null,
                tint = if (isRolledBack) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                stringResource(
                    if (isRolledBack) R.string.backup_receipt_rolled_back_title
                    else R.string.backup_receipt_committed_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isRolledBack) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = stringResource(R.string.backup_receipt_rolled_back_desc),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    if (!receipt.errorMessage.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.backup_receipt_error_detail, receipt.errorMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.backup_receipt_source, displaySource),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (restoredItems.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.backup_receipt_items, restoredItems),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.backup_receipt_close))
            }
        },
        dismissButton = {
            val undoSnapshotId = receipt.preRestoreSnapshotId
            if (!isRolledBack && canUndo && !undoSnapshotId.isNullOrBlank()) {
                TextButton(
                    onClick = { onUndo(undoSnapshotId) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Lucide.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.backup_undo_action))
                }
            }
        },
    )
}
