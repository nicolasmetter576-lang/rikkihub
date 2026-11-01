package me.rerere.rikkihub.data.management.persistence

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.ports.ParticipationRecord
import me.rerere.rikkihub.data.management.ports.StoreKind
import me.rerere.rikkihub.data.management.proposal.Proposal

/** 提案持久化（不含秘密；一提案一文件，tmp+rename 原子替换）。 */
interface ManagementProposalStore {
    suspend fun get(proposalId: String): Proposal?

    /** upsert；调用方负责在自身锁内完成"读-校验-写"原子转移。 */
    suspend fun put(proposal: Proposal)

    /** Atomic compare/update; a read-then-put fallback is forbidden for approval claims. */
    suspend fun updateProposal(proposalId: String, block: (Proposal?) -> Proposal): Proposal

    suspend fun listAll(): List<Proposal>

    suspend fun deleteProposal(proposalId: String)
}

/** 参与记录持久化（独立于 Vault）。 */
interface ParticipationStore {
    suspend fun write(store: StoreKind, record: ParticipationRecord)

    suspend fun read(store: StoreKind, operationId: String): ParticipationRecord?

    suspend fun allRecords(): List<ParticipationRecord>

    suspend fun pending(): List<ParticipationRecord>
}

@Serializable
data class FenceEntry(
    val ref: ObjectRef,
    val proposalId: String,
    val sinceMillis: Long,
)

/** fence 持久化（Vault 缺失/损坏也可从本存储+journal 重建）。 */
interface FenceStore {
    suspend fun install(entries: List<FenceEntry>)

    suspend fun release(proposalId: String)

    suspend fun allFences(): List<FenceEntry>
}
