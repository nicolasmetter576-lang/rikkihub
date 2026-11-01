package me.rerere.rikkihub.data.mutation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * 存储域 epoch 的持久账本：`(domain, epoch)`，单调递增；备份恢复后全部 bump，
 * 旧 epoch 下的版本 token 与审批随之失效（ABA 防线）。
 *
 * 文件为单行 JSON，写为 tmp+fsync+ATOMIC_MOVE；本进程是唯一写者，读取走内存缓存。
 * 损坏读取抛错，不回退为空账本（空账本会让旧 token 重新生效）。
 */
class MutationEpochStore(private val rootDir: File) {
    private val epochsFile = File(rootDir, "epochs.json")
    private val mutex = Mutex()
    private var cache: Map<String, Long>? = null

    @Serializable
    private data class Epochs(val epochs: Map<String, Long> = emptyMap())

    private fun epochs(): Map<String, Long> = cache ?: (
        if (epochsFile.isFile) mutationJson.decodeFromString<Epochs>(epochsFile.readText()).epochs else emptyMap()
    ).also { cache = it }

    private fun write(epochs: Map<String, Long>) {
        check(rootDir.isDirectory || rootDir.mkdirs()) { "Cannot create mutation store dir: $rootDir" }
        val tmp = File(rootDir, epochsFile.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(mutationJson.encodeToString(Epochs.serializer(), Epochs(epochs)).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(tmp.toPath(), epochsFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        cache = epochs
    }

    suspend fun epoch(domain: MutationDomain): Long = mutex.withLock { epochs()[domain.name] ?: 1L }

    suspend fun bumpEpoch(domain: MutationDomain): Long = mutex.withLock {
        val next = (epochs()[domain.name] ?: 1L) + 1
        write(epochs() + (domain.name to next))
        next
    }

    /** 恢复整体替换存储后调用：全部 domain epoch +1。 */
    suspend fun bumpAllDomains(): Map<MutationDomain, Long> = mutex.withLock {
        val current = epochs()
        val bumped = MutationDomain.entries.associateWith { (current[it.name] ?: 1L) + 1 }
        write(current + bumped.mapKeys { it.key.name })
        bumped
    }
}
