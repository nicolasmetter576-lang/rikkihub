package me.rerere.rikkihub.data.mutation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MutationRuntime"

/**
 * 写入版本协议的进程内装配点：存储域 epoch 与普通写准入。
 *
 * 目录约定：
 * - `noBackupFilesDir/mutation/`：epochs 持久化，不进手工备份与 auto-backup。
 * - `filesDir/mutation/restore.marker`：PendingRestore 安装完成的标记，消费后删除
 *   并使所有 domain epoch bump（恢复前的版本 token、审批全部失效）。
 *
 * [forContext] 按目录缓存同一进程内的唯一实例；JVM 测试直接构造。
 */
class MutationRuntime(
    val rootDir: File,
    val restoreMarkerFile: File,
) {
    val epochs: MutationEpochStore = MutationEpochStore(rootDir)

    /** Installed by the management startup scanner before private writes can be enabled. */
    @Volatile private var admissionCheck: ((MutationDomain, Set<String>, String, MutationActor) -> Unit)? = null

    fun installWriteAdmission(check: (MutationDomain, Set<String>, String, MutationActor) -> Unit) {
        admissionCheck = check
    }

    fun checkWriteAdmission(domain: MutationDomain, keys: Set<String>, operationId: String, actor: MutationActor) {
        admissionCheck?.invoke(domain, keys, operationId, actor)
    }

    private val restoreCheckMutex = Mutex()
    @Volatile
    private var restoreMarkerConsumed = false

    /** 幂等地消费恢复标记：存在则 bump 全部 domain epoch 并删除标记。 */
    suspend fun consumeRestoreMarkerIfAny(): Boolean {
        if (restoreMarkerConsumed && !restoreMarkerFile.exists()) return false
        return restoreCheckMutex.withLock {
            if (!restoreMarkerFile.exists()) {
                restoreMarkerConsumed = true
                return@withLock false
            }
            Log.i(TAG, "storage epochs bumped after restore: ${epochs.bumpAllDomains()}")
            restoreMarkerFile.delete()
            restoreMarkerConsumed = true
            true
        }
    }

    /** 业务写入口统一前置：消费恢复标记（若有），保证读/写看到的 epoch 是新值。 */
    suspend fun ensureReady() {
        consumeRestoreMarkerIfAny()
    }

    /** 2.6.0–2.8.0 的写入日志与 fence 文件已无读者，升级后删除一次即可。 */
    fun deleteRetiredFiles() {
        File(rootDir, "journal").deleteRecursively()
        File(rootDir, "fences.json").delete()
    }

    companion object {
        private val instances = ConcurrentHashMap<String, MutationRuntime>()
        const val STORE_DIR_NAME = "mutation"
        const val RESTORE_MARKER = "restore.marker"

        /** 生产装配：root=noBackupFilesDir/mutation，marker=filesDir/mutation/restore.marker。 */
        fun forContext(context: Context): MutationRuntime {
            val root = File(context.noBackupFilesDir, STORE_DIR_NAME).canonicalFile
            return instances.getOrPut(root.path) {
                MutationRuntime(
                    rootDir = root,
                    restoreMarkerFile = File(File(context.filesDir, STORE_DIR_NAME), RESTORE_MARKER),
                )
            }
        }

        /** PendingRestore 在安装完成后调用：原子写 marker（tmp+fsync+rename）。 */
        fun writeRestoreMarker(filesDir: File) {
            val marker = File(File(filesDir, STORE_DIR_NAME), RESTORE_MARKER)
            val parent = requireNotNull(marker.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create mutation marker dir: $parent" }
            val tmp = File(parent, RESTORE_MARKER + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write((System.currentTimeMillis().toString() + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), marker.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

        /** 测试/诊断：重置某 root 的缓存实例（不影响磁盘文件）。 */
        fun resetForTest(rootDir: File) {
            instances.remove(rootDir.canonicalFile.path)
        }
    }
}
