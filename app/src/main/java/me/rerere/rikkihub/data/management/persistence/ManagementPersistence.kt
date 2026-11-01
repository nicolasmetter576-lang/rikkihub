package me.rerere.rikkihub.data.management.persistence

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.ports.StoreKind

/**
 * Journal 阶段（write-ahead）。stepId 为提案内步骤序号
 * （"step-N"），operationId = "<proposalId>/<stepId>"。
 */
enum class JournalPhase {
    PROPOSAL_CLAIMED,
    STEP_INTENT,
    STEP_DONE,
    STEP_FAILED,
    COMPENSATION_INTENT,
    COMPENSATION_DONE,
    COMPENSATION_SKIPPED,
    COMPENSATION_FAILED,
    PROPOSAL_APPLIED,
    PROPOSAL_FAILED,
    PROPOSAL_NEEDS_RECOVERY,
    PROPOSAL_CANCELLED,
    PROPOSAL_CONFLICT,
    FENCE_INSTALLED,
    FENCE_RELEASED,
    EPOCH_CHANGED,
}

@Serializable
data class JournalEntry(
    val seq: Long,
    val atMillis: Long,
    val proposalId: String,
    val phase: JournalPhase,
    val stepId: String? = null,
    val operationId: String? = null,
    val store: StoreKind? = null,
    val objectRefs: List<ObjectRef> = emptyList(),
    /** 结构化摘要（错误码/step key）；绝不包含秘密正文。 */
    val payload: String = "",
)

interface ManagementJournal {
    /** 追加写 + fsync 语义（FileChannel.force）。 */
    suspend fun append(entry: JournalEntry)

    suspend fun entriesForProposal(proposalId: String): List<JournalEntry>

    suspend fun tail(limit: Int): List<JournalEntry>

    suspend fun allJournalEntries(): List<JournalEntry>
}
