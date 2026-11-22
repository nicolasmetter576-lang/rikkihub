package me.rerere.rikkihub.data.sync

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.data.event.AppEvent
import me.rerere.rikkihub.data.event.AppEventBus
import me.rerere.rikkihub.utils.fileSizeToString
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LocalSnapshotService"

sealed interface LocalSnapshotRunResult {
    data class Created(val entry: LocalSnapshotEntry, val prunedCount: Int) : LocalSnapshotRunResult
    data class Skipped(val reason: LocalSnapshotSkipReason) : LocalSnapshotRunResult
    data class Failed(val message: String) : LocalSnapshotRunResult
}

class InsufficientSnapshotSpaceException(
    val availableBytes: Long,
    val requiredWithFloorBytes: Long,
    message: String = "Insufficient free storage for snapshot",
) : Exception(message)

class LocalSnapshotService(
    private val context: Context,
    private val backupManager: BackupManager,
    private val store: LocalSnapshotStore,
    private val stateStore: BackupDeviceStateStore,
    private val isBusy: () -> LocalSnapshotSkipReason? = { null },
) {
    private val mutex = Mutex()

    private fun liveDatabaseFile(): File = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
    private fun liveSettingsFile(): File = context.preferencesDataStoreFile("settings")
    private fun liveDatabaseBytes(dbFile: File): Long {
        val db = if (dbFile.isFile) dbFile.length() else 0L
        val wal = File(dbFile.path + "-wal").let { if (it.isFile) it.length() else 0L }
        return db + wal
    }

    suspend fun list(): List<LocalSnapshotEntry> = withContext(Dispatchers.IO) {
        store.list()
    }

    suspend fun findById(id: String): LocalSnapshotEntry? = withContext(Dispatchers.IO) {
        store.findById(id)
    }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        store.setPinned(id, pinned)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
    }

    suspend fun pruneNow(nowMillis: Long = System.currentTimeMillis()): List<LocalSnapshotEntry> =
        withContext(Dispatchers.IO) {
            store.prune(stateStore.state.value.snapshotConfig, nowMillis)
        }

    suspend fun runIfDue(nowMillis: Long = System.currentTimeMillis()): LocalSnapshotRunResult {
        if (!mutex.tryLock()) {
            return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.BUSY)
        }
        try {
            val deviceState = stateStore.state.value
            val config = deviceState.snapshotConfig
            val runState = deviceState.snapshotRunState

            val dbFile = liveDatabaseFile()
            val dbBytes = liveDatabaseBytes(dbFile)

            val dueSkip = LocalSnapshotSchedule.dueSkipReason(
                nowMillis = nowMillis,
                config = config,
                state = runState,
                databaseBytes = dbBytes,
            )
            if (dueSkip != null) {
                return LocalSnapshotRunResult.Skipped(dueSkip)
            }

            val firstObserved = runState.firstObservedAtMillis
            if (firstObserved == null) {
                stateStore.update { current ->
                    current.copy(
                        snapshotRunState = current.snapshotRunState.copy(
                            firstObservedAtMillis = nowMillis,
                            lastSkipReason = LocalSnapshotSkipReason.NOT_DUE,
                        )
                    )
                }
                return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.NOT_DUE)
            }
            if (runState.lastSuccessAtMillis == null &&
                firstObserved <= nowMillis &&
                nowMillis - firstObserved < LocalSnapshotSchedule.INITIAL_GRACE_MS
            ) {
                return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.NOT_DUE)
            }

            val externalBusy = isBusy()
            if (externalBusy != null) {
                recordSkip(externalBusy)
                return LocalSnapshotRunResult.Skipped(externalBusy)
            }

            val preFingerprint = withContext(Dispatchers.IO) {
                SnapshotChangeFingerprint.read(dbFile, liveSettingsFile())
            }
            val lastFingerprint = SnapshotChangeFingerprint.decode(runState.lastFingerprint)
            if (preFingerprint.matches(lastFingerprint)) {
                recordSkip(LocalSnapshotSkipReason.UNCHANGED)
                return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.UNCHANGED)
            }

            val availableBytes = availableNoBackupBytes()
            val requiredWithFloor = LocalSnapshotSchedule.estimatedRequiredBytes(dbBytes) +
                LocalSnapshotSchedule.FREE_SPACE_FLOOR_BYTES
            if (availableBytes != null && availableBytes < requiredWithFloor) {
                recordSkip(LocalSnapshotSkipReason.INSUFFICIENT_SPACE)
                return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.INSUFFICIENT_SPACE)
            }

            return try {
                val (entry, pruned) = takeLocked(
                    origin = LocalSnapshotOrigin.AUTOMATIC,
                    pinned = false,
                    prune = true,
                    nowMillis = nowMillis,
                )
                stateStore.update { current ->
                    current.copy(
                        snapshotRunState = current.snapshotRunState.copy(
                            lastSuccessAtMillis = nowMillis,
                            lastFailureAtMillis = null,
                            lastFailureMessage = null,
                            failureStreak = 0,
                            lastSkipReason = null,
                            // 保存拍照前的指纹，拍照期间的新写入下轮仍能被察觉
                            lastFingerprint = preFingerprint.encode(),
                        )
                    )
                }
                LocalSnapshotRunResult.Created(entry, pruned.size)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                Log.e(TAG, "Automatic local snapshot failed", e)
                val message = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                stateStore.update { current ->
                    current.copy(
                        snapshotRunState = current.snapshotRunState.copy(
                            lastFailureAtMillis = nowMillis,
                            lastFailureMessage = message,
                            failureStreak = current.snapshotRunState.failureStreak + 1,
                            lastSkipReason = null,
                        )
                    )
                }
                LocalSnapshotRunResult.Failed(message)
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun take(
        origin: LocalSnapshotOrigin,
        pinned: Boolean = false,
        prune: Boolean = origin != LocalSnapshotOrigin.BEFORE_RESTORE,
        nowMillis: Long = System.currentTimeMillis(),
    ): LocalSnapshotEntry {
        mutex.lock()
        try {
            val dbFile = liveDatabaseFile()
            val dbBytes = liveDatabaseBytes(dbFile)
            val availableBytes = availableNoBackupBytes()
            val requiredWithFloor = LocalSnapshotSchedule.estimatedRequiredBytes(dbBytes) +
                LocalSnapshotSchedule.FREE_SPACE_FLOOR_BYTES
            if (availableBytes != null && availableBytes < requiredWithFloor) {
                throw InsufficientSnapshotSpaceException(
                    availableBytes = availableBytes,
                    requiredWithFloorBytes = requiredWithFloor,
                    message = context.getString(R.string.backup_snapshot_insufficient_space),
                )
            }

            val preFingerprint = withContext(Dispatchers.IO) {
                SnapshotChangeFingerprint.read(dbFile, liveSettingsFile())
            }
            val (entry, _) = takeLocked(
                origin = origin,
                pinned = pinned,
                prune = prune,
                nowMillis = nowMillis,
            )
            if (origin != LocalSnapshotOrigin.BEFORE_RESTORE) {
                stateStore.update { current ->
                    current.copy(
                        snapshotRunState = current.snapshotRunState.copy(
                            lastSuccessAtMillis = nowMillis,
                            lastFailureAtMillis = null,
                            lastFailureMessage = null,
                            failureStreak = 0,
                            lastSkipReason = null,
                            lastFingerprint = preFingerprint.encode(),
                        )
                    )
                }
            }
            return entry
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 恢复前自动留底：活库非空时强制留底并 Fail-Closed（失败则抛异常阻止继续恢复）；
     * 全新空库则跳过并返回 null。
     */
    suspend fun createBeforeRestoreIfNonEmpty(): LocalSnapshotEntry? {
        if (!backupManager.hasLiveData()) return null
        return take(
            origin = LocalSnapshotOrigin.BEFORE_RESTORE,
            pinned = true,
            prune = false,
        )
    }

    suspend fun stageUndoRestore(snapshotId: String) {
        val entry = findById(snapshotId)
            ?: throw IllegalStateException("Pre-restore snapshot not found: $snapshotId")
        backupManager.stageRestore(
            archive = entry.file,
            includeDatabase = true,
            includeFiles = false,
            metadata = RestoreMetadata(
                source = RestoreSource.UNDO,
                sourceDisplayName = entry.id,
                preRestoreSnapshotId = null,
                archiveAppVersionName = entry.appVersionName,
                archiveDatabaseVersion = entry.databaseSchemaVersion,
            ),
        )
    }

    private suspend fun takeLocked(
        origin: LocalSnapshotOrigin,
        pinned: Boolean,
        prune: Boolean,
        nowMillis: Long,
    ): Pair<LocalSnapshotEntry, List<LocalSnapshotEntry>> = withContext(Dispatchers.IO) {
        store.sweepIncomplete()
        val prepared = backupManager.createSnapshotArchive()
        val entry = try {
            store.publish(
                preparedZip = prepared.file,
                createdAtMillis = nowMillis,
                origin = origin,
                pinned = pinned,
                conversationCount = prepared.conversationCount,
                messageNodeCount = prepared.messageNodeCount,
                appVersionName = prepared.appVersionName,
                appVersionCode = prepared.appVersionCode,
                databaseSchemaVersion = prepared.databaseSchemaVersion,
            )
        } finally {
            prepared.file.delete()
        }
        val pruned = if (prune) {
            store.prune(stateStore.state.value.snapshotConfig, nowMillis)
        } else {
            emptyList()
        }
        entry to pruned
    }

    private suspend fun recordSkip(reason: LocalSnapshotSkipReason) {
        stateStore.update { current ->
            current.copy(
                snapshotRunState = current.snapshotRunState.copy(lastSkipReason = reason)
            )
        }
    }

    private fun availableNoBackupBytes(): Long? = runCatching {
        val dir = context.noBackupFilesDir
        if (!dir.exists()) dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    }.getOrNull()
}

/**
 * 监听 ProcessLifecycleOwner 在前台窗口期触发自动本地快照：
 * 首次进入延迟 8 秒、热切回前台延迟 3 秒、最短检查间距 60 秒；切到后台立即取消未启动的任务。
 */
class LocalSnapshotScheduler(
    private val appScope: AppScope,
    private val service: LocalSnapshotService,
    private val stateStore: BackupDeviceStateStore,
    private val eventBus: AppEventBus,
    private val context: Context,
) {
    private val started = AtomicBoolean(false)
    private var hasLaunchedOnce = false
    private var scheduledJob: Job? = null
    private var lastCheckElapsedMillis: Long = -MINIMUM_CHECK_GAP_MS

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val delayMs = if (!hasLaunchedOnce) {
                    hasLaunchedOnce = true
                    LAUNCH_DELAY_MS
                } else {
                    RESUME_DELAY_MS
                }
                schedule(delayMs)
            }

            override fun onStop(owner: LifecycleOwner) {
                scheduledJob?.cancel()
                scheduledJob = null
            }
        })
    }

    private fun schedule(delayMs: Long) {
        scheduledJob?.cancel()
        scheduledJob = appScope.launch {
            delay(delayMs)
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (nowElapsed - lastCheckElapsedMillis < MINIMUM_CHECK_GAP_MS) {
                return@launch
            }
            lastCheckElapsedMillis = nowElapsed

            when (val result = service.runIfDue()) {
                is LocalSnapshotRunResult.Created -> {
                    if (stateStore.state.value.snapshotConfig.announceResult) {
                        eventBus.tryEmit(
                            AppEvent.BackupNotice(
                                message = context.getString(
                                    R.string.backup_snapshot_auto_created,
                                    result.entry.bytes.fileSizeToString(),
                                ),
                                isError = false,
                            )
                        )
                    }
                }
                is LocalSnapshotRunResult.Failed -> {
                    // 连续失败链只在第一次弹出错误提示，备份页长期展示失败原因，避免每次切回前台反复打扰
                    if (stateStore.state.value.snapshotRunState.failureStreak == 1) {
                        eventBus.tryEmit(
                            AppEvent.BackupNotice(
                                message = context.getString(
                                    R.string.backup_snapshot_auto_failed,
                                    result.message,
                                ),
                                isError = true,
                            )
                        )
                    }
                }
                is LocalSnapshotRunResult.Skipped -> Unit
            }
        }
    }

    private companion object {
        const val LAUNCH_DELAY_MS = 8_000L
        const val RESUME_DELAY_MS = 3_000L
        const val MINIMUM_CHECK_GAP_MS = 60_000L
    }
}
