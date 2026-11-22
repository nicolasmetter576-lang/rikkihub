package me.rerere.rikkihub.ui.pages.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.BuildConfig
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.WebDavConfig
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.files.saveUploadFromBytes
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.sync.BackupCompatibilityChecker
import me.rerere.rikkihub.data.sync.BackupCompatibilityReport
import me.rerere.rikkihub.data.sync.BackupDecryptionException
import me.rerere.rikkihub.data.sync.BackupDeviceState
import me.rerere.rikkihub.data.sync.BackupDeviceStateStore
import me.rerere.rikkihub.data.sync.BackupEncryption
import me.rerere.rikkihub.data.sync.BackupEncryptionConfig
import me.rerere.rikkihub.data.sync.BackupManager
import me.rerere.rikkihub.data.sync.BackupPasswordStore
import me.rerere.rikkihub.data.sync.InsufficientSnapshotSpaceException
import me.rerere.rikkihub.data.sync.LocalSnapshotConfig
import me.rerere.rikkihub.data.sync.LocalSnapshotEntry
import me.rerere.rikkihub.data.sync.LocalSnapshotOrigin
import me.rerere.rikkihub.data.sync.LocalSnapshotService
import me.rerere.rikkihub.data.sync.RestoreMetadata
import me.rerere.rikkihub.data.sync.RestoreReceipt
import me.rerere.rikkihub.data.sync.RestoreSource
import me.rerere.rikkihub.data.sync.S3BackupItem
import me.rerere.rikkihub.data.sync.S3Sync
import me.rerere.rikkihub.data.sync.UnsupportedBackupEncryptionVersionException
import me.rerere.rikkihub.data.sync.importer.ChatboxImporter
import me.rerere.rikkihub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkihub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkihub.data.sync.webdav.WebDavSync
import me.rerere.rikkihub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

enum class RestoreStage {
    DOWNLOADING,
    DECRYPTING,
    INSPECTING,
    STAGING,
}

data class RestoreSession(
    val source: RestoreSource,
    val displayName: String,
    val includeDatabase: Boolean,
    val includeFiles: Boolean,
    val archiveFile: File,
    val ownsArchiveFile: Boolean,
    val decryptedFile: File? = null,
) {
    val inspectTarget: File get() = decryptedFile ?: archiveFile
}

sealed interface RestoreFlowState {
    data object Idle : RestoreFlowState
    data class Busy(val stage: RestoreStage) : RestoreFlowState
    data class NeedPassword(val session: RestoreSession, val errorMessage: String? = null) : RestoreFlowState
    data class ReadyToConfirm(val session: RestoreSession, val report: BackupCompatibilityReport) : RestoreFlowState
    data object StagedNeedRestart : RestoreFlowState
}

sealed interface BackupPasswordResolution {
    data object NotEncrypted : BackupPasswordResolution
    data class Resolved(val password: String) : BackupPasswordResolution
    data class NeedPrompt(val showKeystoreWarning: Boolean) : BackupPasswordResolution
}

class BackupVM(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val backupManager: BackupManager,
    private val deviceStateStore: BackupDeviceStateStore,
    private val passwordStore: BackupPasswordStore,
    private val localSnapshotService: LocalSnapshotService,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy(),
    )

    val deviceState: StateFlow<BackupDeviceState> = deviceStateStore.state
    val latestReceipt: StateFlow<RestoreReceipt?> = deviceStateStore.latestReceipt

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())

    private val _snapshots = MutableStateFlow<List<LocalSnapshotEntry>>(emptyList())
    val snapshots: StateFlow<List<LocalSnapshotEntry>> = _snapshots.asStateFlow()

    private val _isTakingSnapshot = MutableStateFlow(false)
    val isTakingSnapshot: StateFlow<Boolean> = _isTakingSnapshot.asStateFlow()

    private val _hasRememberedPassword = MutableStateFlow(passwordStore.hasStoredPassword())
    val hasRememberedPassword: StateFlow<Boolean> = _hasRememberedPassword.asStateFlow()

    private val _restoreFlow = MutableStateFlow<RestoreFlowState>(
        if (backupManager.hasPendingRestore()) RestoreFlowState.StagedNeedRestart else RestoreFlowState.Idle
    )
    val restoreFlow: StateFlow<RestoreFlowState> = _restoreFlow.asStateFlow()

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
        refreshSnapshots()
        viewModelScope.launch { deviceStateStore.reloadReceipt() }
    }

    override fun onCleared() {
        super.onCleared()
        cleanupFlowSession(_restoreFlow.value)
    }

    /** 在最新的设置上修改，不整份写回界面手里的快照 */
    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.tryUpdate(transform)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    // ---------- 加密配置与口令管理 ----------

    suspend fun enableOrChangeEncryption(password: String, rememberOnDevice: Boolean) {
        require(password.isNotEmpty()) { context.getString(R.string.backup_enc_password_empty) }
        val verifier = BackupEncryption.createPasswordVerifier(password)
        withContext(Dispatchers.IO) {
            if (rememberOnDevice) passwordStore.save(password) else passwordStore.clear()
        }
        deviceStateStore.update { current ->
            current.copy(
                encryption = BackupEncryptionConfig(
                    enabled = true,
                    rememberPasswordOnDevice = rememberOnDevice,
                    passwordVerifier = verifier,
                )
            )
        }
        _hasRememberedPassword.value = withContext(Dispatchers.IO) { passwordStore.hasStoredPassword() }
    }

    suspend fun disableEncryption() {
        withContext(Dispatchers.IO) {
            passwordStore.clear()
        }
        deviceStateStore.update { current ->
            current.copy(
                encryption = BackupEncryptionConfig(
                    enabled = false,
                    rememberPasswordOnDevice = false,
                    passwordVerifier = null,
                )
            )
        }
        _hasRememberedPassword.value = false
    }

    suspend fun forgetRememberedPassword() {
        withContext(Dispatchers.IO) {
            passwordStore.clear()
        }
        deviceStateStore.update { current ->
            current.copy(encryption = current.encryption.copy(rememberPasswordOnDevice = false))
        }
        _hasRememberedPassword.value = false
    }

    suspend fun enableRememberPassword(password: String): Boolean {
        val verifier = deviceState.value.encryption.passwordVerifier
        if (verifier != null && !BackupEncryption.verifyPassword(password, verifier)) {
            return false
        }
        withContext(Dispatchers.IO) {
            passwordStore.save(password)
        }
        deviceStateStore.update { current ->
            current.copy(encryption = current.encryption.copy(rememberPasswordOnDevice = true))
        }
        _hasRememberedPassword.value = withContext(Dispatchers.IO) { passwordStore.hasStoredPassword() }
        return true
    }

    suspend fun resolveBackupPassword(): BackupPasswordResolution {
        val enc = deviceState.value.encryption
        if (!enc.enabled) return BackupPasswordResolution.NotEncrypted
        if (enc.rememberPasswordOnDevice) {
            val loaded = withContext(Dispatchers.IO) { passwordStore.load() }
            if (!loaded.isNullOrEmpty()) {
                val verifier = enc.passwordVerifier
                if (verifier == null || BackupEncryption.verifyPassword(loaded, verifier)) {
                    return BackupPasswordResolution.Resolved(loaded)
                }
            }
            _hasRememberedPassword.value = false
            return BackupPasswordResolution.NeedPrompt(showKeystoreWarning = true)
        }
        return BackupPasswordResolution.NeedPrompt(showKeystoreWarning = false)
    }

    suspend fun verifyEnteredBackupPassword(password: String): Boolean {
        if (password.isEmpty()) return false
        val enc = deviceState.value.encryption
        val verifier = enc.passwordVerifier
        if (verifier != null && !BackupEncryption.verifyPassword(password, verifier)) {
            return false
        }
        if (enc.rememberPasswordOnDevice) {
            withContext(Dispatchers.IO) { passwordStore.save(password) }
            _hasRememberedPassword.value = withContext(Dispatchers.IO) { passwordStore.hasStoredPassword() }
        }
        return true
    }

    // ---------- 统一恢复预检 / 解密 / 留底暂存管线 ----------

    fun cancelRestoreFlow() {
        cleanupFlowSession(_restoreFlow.value)
        _restoreFlow.value = RestoreFlowState.Idle
    }

    fun dismissRestartDialog() {
        if (_restoreFlow.value is RestoreFlowState.StagedNeedRestart) {
            _restoreFlow.value = RestoreFlowState.Idle
        }
    }

    suspend fun startLocalRestoreFromUri(uri: Uri, displayName: String) {
        ensureNoPendingRestore()
        val tempFile = withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("local_restore_", ".tmp", BackupManager.backupTempDir(context))
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(context.getString(R.string.backup_page_cannot_open_file))
                input.use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out) }
                }
                tmp
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }
        val session = RestoreSession(
            source = RestoreSource.LOCAL,
            displayName = displayName,
            includeDatabase = WebDavConfig.BackupItem.DATABASE in localBackupItems.value,
            includeFiles = WebDavConfig.BackupItem.FILES in localBackupItems.value,
            archiveFile = tempFile,
            ownsArchiveFile = true,
        )
        inspectOrPromptPassword(session)
    }

    suspend fun startWebDavRestore(item: WebDavBackupItem) {
        ensureNoPendingRestore()
        _restoreFlow.value = RestoreFlowState.Busy(RestoreStage.DOWNLOADING)
        val downloaded = try {
            webDavSync.downloadBackupFile(settings.value.webDavConfig, item)
        } catch (e: Throwable) {
            _restoreFlow.value = RestoreFlowState.Idle
            throw e
        }
        val cfg = settings.value.webDavConfig
        val session = RestoreSession(
            source = RestoreSource.WEBDAV,
            displayName = item.displayName,
            includeDatabase = WebDavConfig.BackupItem.DATABASE in cfg.items,
            includeFiles = WebDavConfig.BackupItem.FILES in cfg.items,
            archiveFile = downloaded,
            ownsArchiveFile = true,
        )
        inspectOrPromptPassword(session)
    }

    suspend fun startS3Restore(item: S3BackupItem) {
        ensureNoPendingRestore()
        _restoreFlow.value = RestoreFlowState.Busy(RestoreStage.DOWNLOADING)
        val downloaded = try {
            s3Sync.downloadBackupFile(settings.value.s3Config, item)
        } catch (e: Throwable) {
            _restoreFlow.value = RestoreFlowState.Idle
            throw e
        }
        val cfg = settings.value.s3Config
        val session = RestoreSession(
            source = RestoreSource.S3,
            displayName = item.displayName,
            includeDatabase = me.rerere.rikkihub.data.sync.s3.S3Config.BackupItem.DATABASE in cfg.items,
            includeFiles = me.rerere.rikkihub.data.sync.s3.S3Config.BackupItem.FILES in cfg.items,
            archiveFile = downloaded,
            ownsArchiveFile = true,
        )
        inspectOrPromptPassword(session)
    }

    suspend fun startSnapshotRestore(entry: LocalSnapshotEntry) {
        ensureNoPendingRestore()
        val session = RestoreSession(
            source = RestoreSource.SNAPSHOT,
            displayName = entry.id,
            includeDatabase = true,
            includeFiles = false,
            archiveFile = entry.file,
            ownsArchiveFile = false,
        )
        inspectOrPromptPassword(session)
    }

    suspend fun submitRestorePassword(password: String) {
        val current = _restoreFlow.value as? RestoreFlowState.NeedPassword ?: return
        val session = current.session
        _restoreFlow.value = RestoreFlowState.Busy(RestoreStage.DECRYPTING)
        val decrypted = withContext(Dispatchers.IO) {
            File.createTempFile("decrypted_", ".zip", BackupManager.backupTempDir(context))
        }
        try {
            BackupEncryption.decryptFile(session.archiveFile, decrypted, password)
            val updated = session.copy(decryptedFile = decrypted)
            inspectPlaintextArchive(updated)
        } catch (e: CancellationException) {
            decrypted.delete()
            cleanupSession(session)
            _restoreFlow.value = RestoreFlowState.Idle
            throw e
        } catch (e: BackupDecryptionException) {
            decrypted.delete()
            _restoreFlow.value = RestoreFlowState.NeedPassword(
                session = session,
                errorMessage = context.getString(R.string.backup_enc_wrong_password),
            )
        } catch (e: UnsupportedBackupEncryptionVersionException) {
            decrypted.delete()
            cleanupSession(session)
            _restoreFlow.value = RestoreFlowState.Idle
            throw IllegalStateException(
                context.getString(R.string.backup_enc_unsupported_version, e.version),
                e,
            )
        } catch (e: Throwable) {
            decrypted.delete()
            cleanupSession(session)
            _restoreFlow.value = RestoreFlowState.Idle
            throw e
        }
    }

    suspend fun confirmAndStageRestore() {
        val current = _restoreFlow.value as? RestoreFlowState.ReadyToConfirm ?: return
        val session = current.session
        val report = current.report
        check(!report.isBlocked) { "Blocked backup cannot be restored" }

        _restoreFlow.value = RestoreFlowState.Busy(RestoreStage.STAGING)
        try {
            val preRestoreSnapshot = try {
                localSnapshotService.createBeforeRestoreIfNonEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val reason = if (e is InsufficientSnapshotSpaceException) {
                    context.getString(R.string.backup_snapshot_insufficient_space)
                } else {
                    e.message ?: e::class.java.simpleName
                }
                throw IllegalStateException(
                    context.getString(R.string.backup_precheck_before_restore_failed, reason),
                    e,
                )
            }

            val metadata = RestoreMetadata(
                source = session.source,
                sourceDisplayName = session.displayName,
                preRestoreSnapshotId = preRestoreSnapshot?.id,
                archiveAppVersionName = report.manifest?.appVersionName,
                archiveDatabaseVersion = report.detectedDatabaseVersion,
            )
            backupManager.stageRestore(
                archive = session.inspectTarget,
                includeDatabase = session.includeDatabase,
                includeFiles = session.includeFiles,
                metadata = metadata,
            )
            cleanupSession(session)
            refreshSnapshots()
            _restoreFlow.value = RestoreFlowState.StagedNeedRestart
        } catch (e: Throwable) {
            cleanupSession(session)
            _restoreFlow.value = RestoreFlowState.Idle
            throw e
        }
    }

    suspend fun undoRestore(snapshotId: String) {
        ensureNoPendingRestore()
        _restoreFlow.value = RestoreFlowState.Busy(RestoreStage.STAGING)
        try {
            localSnapshotService.stageUndoRestore(snapshotId)
            deviceStateStore.acknowledgeReceipt()
            _restoreFlow.value = RestoreFlowState.StagedNeedRestart
        } catch (e: Throwable) {
            _restoreFlow.value = RestoreFlowState.Idle
            throw e
        }
    }

    suspend fun acknowledgeReceipt() {
        deviceStateStore.acknowledgeReceipt()
    }

    private suspend fun inspectOrPromptPassword(session: RestoreSession) {
        try {
            val encrypted = withContext(Dispatchers.IO) {
                BackupEncryption.isEncrypted(session.archiveFile)
            }
            if (!encrypted) {
                inspectPlaintextArchive(session)
                return
            }

            val remembered = withContext(Dispatchers.IO) { passwordStore.load() }
            if (!remembered.isNullOrEmpty()) {
                _restoreFlow.value = RestoreFlowState.Busy(RestoreStage.DECRYPTING)
                val decrypted = withContext(Dispatchers.IO) {
                    File.createTempFile("decrypted_", ".zip", BackupManager.backupTempDir(context))
                }
                try {
                    BackupEncryption.decryptFile(session.archiveFile, decrypted, remembered)
                    inspectPlaintextArchive(session.copy(decryptedFile = decrypted))
                    return
                } catch (_: BackupDecryptionException) {
                    decrypted.delete()
                } catch (e: UnsupportedBackupEncryptionVersionException) {
                    decrypted.delete()
                    cleanupSession(session)
                    _restoreFlow.value = RestoreFlowState.Idle
                    throw IllegalStateException(
                        context.getString(R.string.backup_enc_unsupported_version, e.version),
                        e,
                    )
                } catch (e: Throwable) {
                    decrypted.delete()
                    throw e
                }
            }

            _restoreFlow.value = RestoreFlowState.NeedPassword(session)
        } catch (e: Throwable) {
            if (_restoreFlow.value !is RestoreFlowState.NeedPassword) {
                cleanupSession(session)
                _restoreFlow.value = RestoreFlowState.Idle
            }
            throw e
        }
    }

    private suspend fun inspectPlaintextArchive(session: RestoreSession) {
        _restoreFlow.value = RestoreFlowState.Busy(RestoreStage.INSPECTING)
        val report = withContext(Dispatchers.IO) {
            BackupCompatibilityChecker.inspect(
                archive = session.inspectTarget,
                includeDatabase = session.includeDatabase,
                includeFiles = session.includeFiles,
                currentAppVersionCode = BuildConfig.VERSION_CODE.toIntOrNull() ?: 0,
            )
        }
        _restoreFlow.value = RestoreFlowState.ReadyToConfirm(session, report)
    }

    private fun ensureNoPendingRestore() {
        check(!backupManager.hasPendingRestore()) {
            context.getString(R.string.backup_page_pending_restore_exists)
        }
    }

    private fun cleanupFlowSession(state: RestoreFlowState) {
        when (state) {
            is RestoreFlowState.NeedPassword -> cleanupSession(state.session)
            is RestoreFlowState.ReadyToConfirm -> cleanupSession(state.session)
            RestoreFlowState.Idle, is RestoreFlowState.Busy, RestoreFlowState.StagedNeedRestart -> Unit
        }
    }

    private fun cleanupSession(session: RestoreSession) {
        session.decryptedFile?.delete()
        if (session.ownsArchiveFile) {
            session.archiveFile.delete()
        }
    }

    // ---------- 本地快照管理 ----------

    fun refreshSnapshots() {
        viewModelScope.launch {
            _snapshots.value = localSnapshotService.list()
        }
    }

    suspend fun takeManualSnapshot(): LocalSnapshotEntry {
        _isTakingSnapshot.value = true
        return try {
            val entry = localSnapshotService.take(
                origin = LocalSnapshotOrigin.MANUAL,
                pinned = false,
                prune = true,
            )
            _snapshots.value = localSnapshotService.list()
            entry
        } finally {
            _isTakingSnapshot.value = false
        }
    }

    fun togglePinSnapshot(entry: LocalSnapshotEntry) {
        viewModelScope.launch {
            localSnapshotService.setPinned(entry.id, !entry.pinned)
            _snapshots.value = localSnapshotService.list()
        }
    }

    fun deleteSnapshot(entry: LocalSnapshotEntry) {
        viewModelScope.launch {
            runCatching {
                localSnapshotService.delete(entry.id)
                _snapshots.value = localSnapshotService.list()
            }.onFailure {
                Log.w(TAG, "deleteSnapshot failed", it)
            }
        }
    }

    suspend fun exportSnapshotToUri(entry: LocalSnapshotEntry, targetUri: Uri) =
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(targetUri)
                ?: throw IllegalStateException(context.getString(R.string.backup_page_cannot_open_file))
            output.use { out ->
                entry.file.inputStream().use { ins -> ins.copyTo(out) }
            }
        }

    fun updateSnapshotConfig(transform: (LocalSnapshotConfig) -> LocalSnapshotConfig) {
        viewModelScope.launch {
            deviceStateStore.update { current ->
                current.copy(snapshotConfig = transform(current.snapshotConfig))
            }
        }
    }

    // ---------- 导出与第三方导入 ----------

    suspend fun exportToUri(targetUri: Uri, encryptPassword: String?) {
        val exportFile = backupManager.createBackup(
            includeDatabase = WebDavConfig.BackupItem.DATABASE in localBackupItems.value,
            includeFiles = WebDavConfig.BackupItem.FILES in localBackupItems.value,
            encryptPassword = encryptPassword,
        )
        try {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(targetUri)
                    ?: throw IllegalStateException(context.getString(R.string.backup_page_cannot_open_file))
                output.use { out ->
                    exportFile.inputStream().use { ins -> ins.copyTo(out) }
                }
            }
            recordBackupTime()
        } finally {
            exportFile.delete()
        }
    }

    suspend fun restoreFromChatBox(uri: Uri): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("temp_chatbox_", ".zip", BackupManager.backupTempDir(context))
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(context.getString(R.string.backup_page_cannot_open_file))
            input.use { ins -> tempFile.outputStream().use { out -> ins.copyTo(out) } }

            val currentSettings = settings.value
            var importedConversations = 0
            var skippedExistingConversations = 0
            val result = ChatboxImporter.importStreaming(
                file = tempFile,
                assistantId = currentSettings.assistantId,
                providers = currentSettings.providers,
                shouldImportConversation = { conversationId ->
                    val exists = conversationRepository.existsConversationById(conversationId)
                    if (exists) skippedExistingConversations++
                    !exists
                },
                saveImage = { resource ->
                    val entity = filesManager.saveUploadFromBytes(
                        bytes = resource.bytes,
                        displayName = resource.fileName,
                        mimeType = resource.mimeType,
                    )
                    filesManager.getFile(entity).toUri().toString()
                },
                onConversation = { conversation ->
                    conversationRepository.importConversation(conversation)
                    importedConversations++
                }
            )

            val targetAssistantId = currentSettings.assistantId
            settingsStore.update { latestSettings ->
                latestSettings.copy(
                    providers = result.providers + latestSettings.providers.filterNot { existing ->
                        result.providers.any { imported -> imported.id == existing.id }
                    },
                    assistants = latestSettings.assistants.map { assistant ->
                        if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                            assistant.copy(allowConversationSystemPrompt = true)
                        } else {
                            assistant
                        }
                    }
                )
            }

            Log.i(
                TAG,
                "restoreFromChatBox: import ${result.providers.size} providers, " +
                    "$importedConversations conversations, skip $skippedExistingConversations existing"
            )
            ChatboxRestoreResult(
                importedProviders = result.providers.size,
                importedConversations = importedConversations,
                skippedExistingConversations = skippedExistingConversations,
                importedImageParts = result.importedImageParts,
                skippedImageParts = result.skippedImageParts,
                skippedEmptyMessages = result.skippedEmptyMessages,
                skippedForkMessages = result.skippedForkMessages,
                skippedSessions = result.skippedSessions,
            )
        } finally {
            tempFile.delete()
        }
    }

    suspend fun restoreFromCherryStudio(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("temp_cherry_", ".zip", BackupManager.backupTempDir(context))
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(context.getString(R.string.backup_page_cannot_open_file))
            input.use { ins -> tempFile.outputStream().use { out -> ins.copyTo(out) } }

            val importProviders = CherryStudioProviderImporter.importProviders(tempFile)
            if (importProviders.isEmpty()) {
                throw IllegalArgumentException(context.getString(R.string.backup_page_no_cherry_providers))
            }

            Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers")
            settingsStore.tryUpdate { latest -> latest.copy(providers = importProviders + latest.providers) }
            importProviders.size
        } finally {
            tempFile.delete()
        }
    }

    // ---------- WebDAV & S3 ----------

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup(encryptPassword: String? = null) {
        webDavSync.backup(settings.value.webDavConfig, encryptPassword)
        recordBackupTime()
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3(encryptPassword: String? = null) {
        s3Sync.backupToS3(settings.value.s3Config, encryptPassword)
        recordBackupTime()
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.tryUpdate { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)
