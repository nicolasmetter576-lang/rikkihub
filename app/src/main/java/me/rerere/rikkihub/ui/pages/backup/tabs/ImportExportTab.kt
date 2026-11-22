package me.rerere.rikkihub.ui.pages.backup.tabs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.WebDavConfig
import me.rerere.rikkihub.data.sync.BackupEncryption
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.StickyHeader
import me.rerere.rikkihub.ui.components.ui.switchItem
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.pages.backup.BackupPasswordResolution
import me.rerere.rikkihub.ui.pages.backup.BackupVM
import me.rerere.rikkihub.ui.pages.backup.components.BackupPasswordPromptDialog
import me.rerere.rikkihub.ui.pages.backup.components.SetBackupPasswordDialog
import me.rerere.rikkihub.ui.theme.AppSpacing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()
    val deviceState by vm.deviceState.collectAsStateWithLifecycle()
    val hasRememberedPwd by vm.hasRememberedPassword.collectAsStateWithLifecycle()
    val encryptionConfig = deviceState.encryption

    var isExporting by remember { mutableStateOf(false) }
    var isImportingThirdParty by remember { mutableStateOf(false) }
    var thirdPartyType by remember { mutableStateOf("") }
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var isChangePasswordMode by remember { mutableStateOf(false) }
    var showDisableEncryptionConfirm by remember { mutableStateOf(false) }
    var showRememberPasswordPrompt by remember { mutableStateOf(false) }
    var rememberPromptError by remember { mutableStateOf<String?>(null) }

    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var showExportPasswordPrompt by remember { mutableStateOf(false) }
    var exportPasswordPromptError by remember { mutableStateOf<String?>(null) }
    var exportKeystoreWarning by remember { mutableStateOf(false) }

    fun launchPlainExport(launcher: androidx.activity.result.ActivityResultLauncher<String>) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        pendingExportPassword = null
        launcher.launch("rikkihub_backup_$timestamp.zip")
    }

    fun launchEncryptedExport(
        launcher: androidx.activity.result.ActivityResultLauncher<String>,
        password: String,
    ) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        pendingExportPassword = password
        launcher.launch("rikkihub_backup_$timestamp.${BackupEncryption.EXTENSION}")
    }

    fun handleExportUri(uri: Uri?, password: String?) {
        val targetUri = uri ?: run {
            pendingExportPassword = null
            return
        }
        scope.launch {
            isExporting = true
            runCatching {
                vm.exportToUri(targetUri, password)
                toaster.show(
                    context.getString(R.string.backup_page_backup_success),
                    type = ToastType.Success,
                )
            }.onFailure { e ->
                e.printStackTrace()
                toaster.show(
                    e.message ?: context.getString(R.string.backup_page_unknown_error),
                    type = ToastType.Error,
                )
            }
            pendingExportPassword = null
            isExporting = false
        }
    }

    val createPlainDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> handleExportUri(uri, null) }

    val createEncryptedDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> handleExportUri(uri, pendingExportPassword) }

    val openLocalBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val sourceUri = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val displayName = resolveDisplayName(context, sourceUri) ?: "backup"
                vm.startLocalRestoreFromUri(sourceUri, displayName)
            }.onFailure { e ->
                e.printStackTrace()
                toaster.show(
                    context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                    type = ToastType.Error,
                )
            }
        }
    }

    val openThirdPartyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val sourceUri = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isImportingThirdParty = true
            runCatching {
                when (thirdPartyType) {
                    "chatbox" -> {
                        val result = vm.restoreFromChatBox(sourceUri)
                        toaster.show(
                            context.getString(
                                R.string.backup_page_chatbox_import_done,
                                result.importedConversations,
                                result.importedProviders,
                                result.skippedExistingConversations,
                            ),
                            type = ToastType.Success,
                        )
                    }
                    "cherry" -> {
                        val count = vm.restoreFromCherryStudio(sourceUri)
                        toaster.show(
                            context.getString(R.string.backup_page_cherry_import_done, count),
                            type = ToastType.Success,
                        )
                    }
                }
            }.onFailure { e ->
                e.printStackTrace()
                toaster.show(
                    context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                    type = ToastType.Error,
                )
            }
            isImportingThirdParty = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(AppSpacing.sm),
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                    supportingContent = {
                        MultiChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            WebDavConfig.BackupItem.entries.forEachIndexed { index, item ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = WebDavConfig.BackupItem.entries.size
                                    ),
                                    onCheckedChange = { checked ->
                                        val newItems = if (checked) {
                                            selectedBackupItems + item
                                        } else {
                                            selectedBackupItems - item
                                        }
                                        vm.updateLocalBackupItems(newItems)
                                    },
                                    checked = item in selectedBackupItems
                                ) {
                                    Text(
                                        when (item) {
                                            WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                                            WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                                        }
                                    )
                                }
                            }
                        }
                    },
                )
                item(
                    onClick = if (!isExporting) {
                        {
                            scope.launch {
                                when (val res = vm.resolveBackupPassword()) {
                                    BackupPasswordResolution.NotEncrypted ->
                                        launchPlainExport(createPlainDocumentLauncher)
                                    is BackupPasswordResolution.Resolved ->
                                        launchEncryptedExport(createEncryptedDocumentLauncher, res.password)
                                    is BackupPasswordResolution.NeedPrompt -> {
                                        exportKeystoreWarning = res.showKeystoreWarning
                                        exportPasswordPromptError = null
                                        showExportPasswordPrompt = true
                                    }
                                }
                            }
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = { showImportConfirmDialog = true },
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_desc)) },
                    leadingContent = { Icon(HugeIcons.FileImport, null) },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_enc_section_title))
            }
        }

        item {
            CardGroup {
                switchItem(
                    checked = encryptionConfig.enabled,
                    onCheckedChange = { wantEnable ->
                        if (wantEnable) {
                            isChangePasswordMode = false
                            showSetPasswordDialog = true
                        } else {
                            showDisableEncryptionConfirm = true
                        }
                    },
                    leadingContent = { Icon(Lucide.Lock, contentDescription = null) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (encryptionConfig.enabled) R.string.backup_enc_enabled_status
                                else R.string.backup_enc_disabled_status
                            )
                        )
                    },
                ) {
                    Text(stringResource(R.string.backup_enc_enable))
                }

                if (encryptionConfig.enabled) {
                    switchItem(
                        checked = encryptionConfig.rememberPasswordOnDevice && hasRememberedPwd,
                        onCheckedChange = { wantRemember ->
                            if (wantRemember) {
                                rememberPromptError = null
                                showRememberPasswordPrompt = true
                            } else {
                                scope.launch { vm.forgetRememberedPassword() }
                            }
                        },
                        supportingContent = { Text(stringResource(R.string.backup_enc_remember_password_desc)) },
                    ) {
                        Text(stringResource(R.string.backup_enc_remember_password))
                    }

                    item(
                        onClick = {
                            isChangePasswordMode = true
                            showSetPasswordDialog = true
                        },
                        leadingContent = { Icon(Lucide.Key, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.backup_enc_change_password)) },
                    )
                }
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isImportingThirdParty) {
                        {
                            thirdPartyType = "chatbox"
                            openThirdPartyLauncher.launch(arrayOf("application/zip", "*/*"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isImportingThirdParty && thirdPartyType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isImportingThirdParty) {
                        {
                            thirdPartyType = "cherry"
                            openThirdPartyLauncher.launch(arrayOf("application/zip", "*/*"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isImportingThirdParty && thirdPartyType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        // 使用 */* 以允许选择 .rkbackup 加密备份与 .zip 备份
                        openLocalBackupLauncher.launch(arrayOf("*/*"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSetPasswordDialog) {
        SetBackupPasswordDialog(
            isChange = isChangePasswordMode,
            initialRemember = if (isChangePasswordMode) encryptionConfig.rememberPasswordOnDevice else true,
            onDismiss = { showSetPasswordDialog = false },
            onConfirm = { password, rememberOnDevice ->
                showSetPasswordDialog = false
                scope.launch {
                    runCatching {
                        vm.enableOrChangeEncryption(password, rememberOnDevice)
                        toaster.show(
                            context.getString(
                                if (isChangePasswordMode) R.string.backup_enc_password_updated
                                else R.string.backup_enc_password_saved
                            ),
                            type = ToastType.Success,
                        )
                    }.onFailure { err ->
                        toaster.show(
                            err.message ?: context.getString(R.string.backup_page_unknown_error),
                            type = ToastType.Error,
                        )
                    }
                }
            },
        )
    }

    if (showDisableEncryptionConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableEncryptionConfirm = false },
            title = { Text(stringResource(R.string.backup_enc_disable_confirm_title)) },
            text = { Text(stringResource(R.string.backup_enc_disable_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableEncryptionConfirm = false
                        scope.launch {
                            vm.disableEncryption()
                            toaster.show(
                                context.getString(R.string.backup_enc_disabled_toast),
                                type = ToastType.Info,
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableEncryptionConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRememberPasswordPrompt) {
        BackupPasswordPromptDialog(
            title = stringResource(R.string.backup_enc_remember_password),
            description = stringResource(R.string.backup_enc_enter_password_encrypt_desc),
            errorMessage = rememberPromptError,
            onDismiss = { showRememberPasswordPrompt = false },
            onConfirm = { password ->
                scope.launch {
                    if (vm.enableRememberPassword(password)) {
                        showRememberPasswordPrompt = false
                    } else {
                        rememberPromptError = context.getString(R.string.backup_enc_wrong_password)
                    }
                }
            },
        )
    }

    if (showExportPasswordPrompt) {
        BackupPasswordPromptDialog(
            title = stringResource(R.string.backup_enc_enter_password_title),
            description = stringResource(
                if (exportKeystoreWarning) R.string.backup_enc_keystore_unavailable
                else R.string.backup_enc_enter_password_encrypt_desc
            ),
            errorMessage = exportPasswordPromptError,
            onDismiss = { showExportPasswordPrompt = false },
            onConfirm = { password ->
                scope.launch {
                    if (vm.verifyEnteredBackupPassword(password)) {
                        showExportPasswordPrompt = false
                        launchEncryptedExport(createEncryptedDocumentLauncher, password)
                    } else {
                        exportPasswordPromptError = context.getString(R.string.backup_enc_wrong_password)
                    }
                }
            },
        )
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}
