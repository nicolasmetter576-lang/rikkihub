package me.rerere.rikkihub.data.files

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内轻量附件草稿租约：为「已创建但尚未进入任何持久引用」的 UPLOAD 文件
 * 提供默认 24h 的删除保护。Web/App 输入框草稿在服务端本不可见，唯一可追踪的
 * 信号就是这条租约；心跳续租由调用方（路由 / Compose effect）驱动。
 *
 * 线程模型：全部操作走同一个内部 synchronized 锁（不用 Mutex，避免挂起点与
 * 取消语义；锁内只做 O(1) map 操作与单次 File.delete/exists，不调用 suspend）。
 * 调用方需要把「存在性校验 + 登记」或「租约复查 + 删盘」合并成原子步骤时，
 * 通过 [withLeaseLock] / [guardedDeleteIfUnleased] 组合，保证注册到清理判断之间
 * 不会被并发续租插队。
 */
class AttachmentLeaseRegistry(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    val defaultTtlMillis: Long = DEFAULT_LEASE_TTL_MILLIS,
) {
    companion object {
        /** 默认租约 24h，兼作进程重启后的新文件兜底保护窗口。 */
        const val DEFAULT_LEASE_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

        /** 清理时的「新文件」兜底窗口，与默认租约等长（不使用分钟级 grace）。 */
        const val NEW_FILE_GUARD_MILLIS: Long = DEFAULT_LEASE_TTL_MILLIS
    }

    private data class LeaseEntry(val expiresAt: Long)

    private val lock = Any()
    private val leases = ConcurrentHashMap<String, LeaseEntry>()

    /**
     * 在租约锁内执行 [block]。锁内不得调用 suspend / DB / 网络。
     * 非 inline：避免 public API 暴露内部锁引用。
     */
    fun <T> withLeaseLock(block: () -> T): T = synchronized(lock, block)

    /**
     * 登记/续租 key（按相对路径，如 `upload/<uuid>.png`）。
     * [stillValid] 在锁内评估（例如 file.exists()），返回 false 时拒绝登记。
     */
    fun track(relativePath: String, ttlMillis: Long = defaultTtlMillis, stillValid: () -> Boolean = { true }): Boolean =
        synchronized(lock) {
            if (!stillValid()) return@synchronized false
            leases[relativePath] = LeaseEntry(nowMillis() + ttlMillis)
            true
        }

    /** [track] 的语义别名：已存在则延长，不存在则新建。 */
    fun renew(relativePath: String, ttlMillis: Long = defaultTtlMillis, stillValid: () -> Boolean = { true }): Boolean =
        track(relativePath, ttlMillis, stillValid)

    /** 是否有未过期租约（读取前顺手清掉过期项）。 */
    fun isLeased(relativePath: String): Boolean = synchronized(lock) {
        (leases[relativePath] ?: return@synchronized false).let { entry ->
            if (entry.expiresAt <= nowMillis()) {
                leases.remove(relativePath)
                false
            } else {
                true
            }
        }
    }

    /** 未过期租约的到期时间戳；无租约/已过期返回 null。 */
    fun leaseExpiry(relativePath: String): Long? = synchronized(lock) {
        val entry = leases[relativePath] ?: return@synchronized null
        if (entry.expiresAt <= nowMillis()) {
            leases.remove(relativePath)
            null
        } else {
            entry.expiresAt
        }
    }

    /** 手动释放（例如明确知道草稿已消费）。不存在时返回 false。 */
    fun release(relativePath: String): Boolean = synchronized(lock) {
        leases.remove(relativePath) != null
    }

    /** 清理过期租约，返回移除条数。 */
    fun pruneExpired(): Int = synchronized(lock) {
        val now = nowMillis()
        val expired = leases.entries.filter { it.value.expiresAt <= now }.map { it.key }
        expired.forEach { leases.remove(it) }
        expired.size
    }

    /** 当前未过期租约快照（诊断/测试用）。 */
    fun activeLeases(): Map<String, Long> = synchronized(lock) {
        val now = nowMillis()
        leases.entries.filter { it.value.expiresAt > now }.associate { it.key to it.value.expiresAt }
    }

    /**
     * 锁内完成「复查租约 → 删盘」两步，消灭续租与删除之间的竞态窗口。
     * 文件不存在视为已删除（幂等成功）。DB 行清理由调用方在锁外执行。
     */
    fun guardedDeleteIfUnleased(relativePath: String, file: File): LeaseDeleteResult =
        synchronized(lock) {
            if (isLeased(relativePath)) {
                return@synchronized LeaseDeleteResult.Leased(leases.getValue(relativePath).expiresAt)
            }
            if (!file.exists()) {
                leases.remove(relativePath)
                return@synchronized LeaseDeleteResult.Deleted(bytesFreed = 0, alreadyMissing = true)
            }
            val size = file.length().coerceAtLeast(0)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                leases.remove(relativePath)
                LeaseDeleteResult.Deleted(bytesFreed = size, alreadyMissing = false)
            } else {
                LeaseDeleteResult.DeleteFailed
            }
        }
}

sealed interface LeaseDeleteResult {
    /** 仍有未过期租约，未删。 */
    data class Leased(val expiresAt: Long) : LeaseDeleteResult

    /** 已删盘（或盘上本就不存在）。 */
    data class Deleted(val bytesFreed: Long, val alreadyMissing: Boolean) : LeaseDeleteResult

    /** 无租约但 File.delete() 失败。 */
    data object DeleteFailed : LeaseDeleteResult
}
