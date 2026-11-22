package me.rerere.rikkihub.data.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File

private const val TAG = "BackupDeviceStateStore"

@Serializable
data class BackupEncryptionConfig(
    val enabled: Boolean = false,
    val rememberPasswordOnDevice: Boolean = true,
    val passwordVerifier: String? = null,
)

@Serializable
data class LocalSnapshotConfig(
    val enabled: Boolean = true,
    /** 0 = 自动（按数据库大小调整 1/3/7 天）；正数 = 固定天数。 */
    val intervalDays: Int = 0,
    val keepRecent: Int = 3,
    val keepWeekly: Boolean = true,
    val keepMonthly: Boolean = true,
    /** 软容量上限（字节），0 表示不限。 */
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    /** 自动快照成功时是否轻提示；失败始终提示。 */
    val announceResult: Boolean = false,
)

@Serializable
enum class LocalSnapshotSkipReason {
    DISABLED,
    NOT_DUE,
    BACKOFF,
    BUSY,
    GENERATING,
    INSUFFICIENT_SPACE,
    UNCHANGED,
}

@Serializable
data class LocalSnapshotRunState(
    val firstObservedAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastFailureMessage: String? = null,
    val failureStreak: Int = 0,
    val lastSkipReason: LocalSnapshotSkipReason? = null,
    val lastFingerprint: String? = null,
)

@Serializable
data class BackupDeviceState(
    val encryption: BackupEncryptionConfig = BackupEncryptionConfig(),
    val snapshotConfig: LocalSnapshotConfig = LocalSnapshotConfig(),
    val snapshotRunState: LocalSnapshotRunState = LocalSnapshotRunState(),
    val outstandingPartials: Map<String, Set<String>> = emptyMap(),
)

@Serializable
enum class RestoreSource {
    LOCAL,
    WEBDAV,
    S3,
    SNAPSHOT,
    UNDO,
}

@Serializable
enum class RestoreOutcome {
    COMMITTED,
    ROLLED_BACK,
}

@Serializable
data class RestoreMetadata(
    val source: RestoreSource,
    val sourceDisplayName: String,
    val preRestoreSnapshotId: String? = null,
    val archiveAppVersionName: String? = null,
    val archiveDatabaseVersion: Int? = null,
)

@Serializable
data class RestoreReceipt(
    val outcome: RestoreOutcome,
    val timestampMillis: Long,
    val source: RestoreSource = RestoreSource.LOCAL,
    val sourceDisplayName: String = "",
    val restoredSettings: Boolean = false,
    val restoredDatabase: Boolean = false,
    val restoredFilesCount: Int = 0,
    val preRestoreSnapshotId: String? = null,
    val errorMessage: String? = null,
    val acknowledged: Boolean = false,
)

/**
 * 设备本地备份状态存储（置于 noBackupFilesDir，绝不进 settings.json、导出备份与系统自动备份）。
 */
class BackupDeviceStateStore(
    private val rootDir: File,
    private val json: Json = JsonInstant,
) {
    private val mutex = Mutex()
    private val stateFile = File(rootDir, STATE_FILE_NAME)
    private val receiptFile = File(rootDir, RECEIPT_FILE_NAME)

    private val _state = MutableStateFlow(readStateFromDisk())
    val state: StateFlow<BackupDeviceState> = _state.asStateFlow()

    private val _latestReceipt = MutableStateFlow(readReceipt())
    val latestReceipt: StateFlow<RestoreReceipt?> = _latestReceipt.asStateFlow()

    suspend fun update(transform: (BackupDeviceState) -> BackupDeviceState): BackupDeviceState {
        return mutex.withLock {
            val current = readStateFromDisk()
            val updated = transform(current)
            if (updated != current) {
                check(rootDir.isDirectory || rootDir.mkdirs()) {
                    "Cannot create backup state directory: ${rootDir.path}"
                }
                PendingRestore.writeDurably(stateFile, stateJson.encodeToString(updated))
                _state.value = updated
            } else if (_state.value != updated) {
                _state.value = updated
            }
            updated
        }
    }

    fun readReceipt(): RestoreReceipt? {
        if (!receiptFile.isFile) return null
        return runCatching {
            stateJson.decodeFromString<RestoreReceipt>(receiptFile.readText())
        }.onFailure {
            Log.w(TAG, "Failed to read restore receipt", it)
        }.getOrNull()
    }

    suspend fun reloadReceipt(): RestoreReceipt? = mutex.withLock {
        val loaded = readReceipt()
        _latestReceipt.value = loaded
        loaded
    }

    suspend fun acknowledgeReceipt(): RestoreReceipt? = mutex.withLock {
        val current = readReceipt() ?: return@withLock null
        if (current.acknowledged) {
            _latestReceipt.value = current
            return@withLock current
        }
        val acknowledged = current.copy(acknowledged = true)
        check(rootDir.isDirectory || rootDir.mkdirs()) {
            "Cannot create backup state directory: ${rootDir.path}"
        }
        PendingRestore.writeDurably(receiptFile, stateJson.encodeToString(acknowledged))
        _latestReceipt.value = acknowledged
        acknowledged
    }

    private fun readStateFromDisk(): BackupDeviceState {
        if (!stateFile.isFile) return BackupDeviceState()
        return runCatching {
            stateJson.decodeFromString<BackupDeviceState>(stateFile.readText())
        }.onFailure {
            Log.e(TAG, "Failed to read backup device state; resetting to defaults", it)
        }.getOrDefault(BackupDeviceState())
    }

    companion object {
        const val STATE_DIR_NAME = "backup-state"
        const val STATE_FILE_NAME = "state.json"
        const val RECEIPT_FILE_NAME = "last-restore-receipt.json"

        internal val stateJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun stateDir(context: Context): File = File(context.noBackupFilesDir, STATE_DIR_NAME)

        fun receiptFile(context: Context): File = File(stateDir(context), RECEIPT_FILE_NAME)
    }
}
