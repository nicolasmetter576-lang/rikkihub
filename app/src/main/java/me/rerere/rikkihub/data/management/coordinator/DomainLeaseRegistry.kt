package me.rerere.rikkihub.data.management.coordinator

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.persistence.FenceEntry
import me.rerere.rikkihub.data.management.persistence.FenceStore
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局稳定 `(domain,id)` 顺序 lease。批准等待期间不持 lease；
 * 获取失败快速抛 LEASE_BUSY（不死等），由 UI 提示用户显式停止后重新提案。
 */
class DomainLeaseRegistry {
    private val mutex = Mutex()
    private val holders = HashMap<ObjectRef, String>()

    data class LeaseHandle internal constructor(
        internal val registry: DomainLeaseRegistry,
        val ownerId: String,
        val refs: List<ObjectRef>,
    ) {
        suspend fun release() = registry.release(this)
    }

    /**
     * 原子获取全部 ref 的 lease（排序避免死锁；任一已占用则整体失败）。
     * timeoutMillis 当前为 0-等待语义：占用即 BUSY——审批期间不持锁的
     * 设计下，BUSY 应该是罕见的竞争，提示重提案而非阻塞。
     */
    suspend fun acquire(refs: List<ObjectRef>, ownerId: String): LeaseHandle =
        mutex.withLock {
            val sorted = refs.distinct().sortedBy { "${it.domain.name}:${it.id}" }
            val busy = sorted.filter { it in holders && holders[it] != ownerId }
            if (busy.isNotEmpty()) {
                throw ProposalServiceError.DomainError(
                    ProposalErrorCode.LEASE_BUSY,
                    "busy: ${busy.joinToString()}",
                )
            }
            sorted.forEach { holders[it] = ownerId }
            LeaseHandle(this, ownerId, sorted)
        }

    suspend fun release(handle: LeaseHandle) = mutex.withLock {
        handle.refs.forEach { ref ->
            if (holders[ref] == handle.ownerId) holders.remove(ref)
        }
    }

    suspend fun ownerOf(ref: ObjectRef): String? = mutex.withLock { holders[ref] }
}

/**
 * 对象 fence：APPLYING 期间普通写/新 run 不得消费半完成依赖。
 * 持久到 [FenceStore]；`isFenced` 是供 mutation 普通写路径/生成装配挂接的
 * 检查点（跨包请求：PreferencesStore/Room 写者与新 run 装配先查本注册表）。
 */
class ObjectFenceRegistry(
    private val store: FenceStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val live = ConcurrentHashMap<ObjectRef, FenceEntry>()

    /** 启动重建：从持久 fence 恢复（Vault/进程死亡均可）。 */
    suspend fun restore() = mutex.withLock {
        val restored = store.allFences() // read/validate before touching live protection
        require(restored.groupBy { it.ref }.values.none { entries -> entries.map { it.proposalId }.distinct().size > 1 })
        live.clear()
        restored.forEach { live[it.ref] = it }
    }

    suspend fun install(proposalId: String, refs: List<ObjectRef>) = mutex.withLock {
        val entries = refs.map { FenceEntry(it, proposalId, nowMillis()) }
        entries.forEach { entry ->
            require(live[entry.ref]?.proposalId.let { it == null || it == proposalId }) { "Fence already owned" }
        }
        store.install(entries)
        entries.forEach { live[it.ref] = it }
    }

    suspend fun release(proposalId: String) = mutex.withLock {
        store.release(proposalId)
        live.entries.removeIf { it.value.proposalId == proposalId }
    }

    fun isFencedNow(ref: ObjectRef): Boolean = live.containsKey(ref)

    /** Non-suspending snapshot of live fences (ordinary write admission runs inside a DataStore edit). */
    fun liveRefs(): List<ObjectRef> = live.keys.toList()

    suspend fun isFenced(ref: ObjectRef): Boolean = isFencedNow(ref)

    suspend fun fencedRefs(): List<FenceEntry> = live.values.toList()

    suspend fun fencedBy(ref: ObjectRef): String? = live[ref]?.proposalId
}
