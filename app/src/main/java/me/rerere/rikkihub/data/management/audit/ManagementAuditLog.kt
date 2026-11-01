package me.rerere.rikkihub.data.management.audit

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.VersionToken
import me.rerere.rikkihub.data.management.ports.TokenRef
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode

@Serializable
enum class AuditKind {
    PROPOSAL_CREATED,
    APPROVED,
    APPLY_CLAIMED,
    STEP_APPLIED,
    STEP_FAILED,
    COMPENSATED,
    APPLIED,
    FAILED,
    CONFLICT,
    CANCELLED,
    EXPIRED,
    REJECTED,
    NEEDS_RECOVERY,
    RECOVERY_RESOLVED,
    UNDO_APPLIED,
    RETENTION_PRUNED,
}

/**
 * 追加审计事件：来源、批准 digest、before/after 版本、级联、
 * receipt、错误码、恢复进度。**模型不能改删审计**——审计对象不进模型
 * 可达面（仅 host/UI 引用），实现为 append-only 存储。
 */
@Serializable
data class AuditEvent(
    val auditEventId: String,
    val atMillis: Long,
    val proposalId: String,
    val sessionId: String?,
    val runId: String?,
    val approvalDigest: String?,
    val kind: AuditKind,
    val objectRefs: List<ObjectRef> = emptyList(),
    val beforeTokens: List<TokenRef> = emptyList(),
    val afterTokens: List<TokenRef> = emptyList(),
    val receiptOperationIds: List<String> = emptyList(),
    val errorCode: ProposalErrorCode? = null,
    val cascadeSummary: String? = null,
    /** 指向回收 payload（秘密 before-image）；审计本身不含明文秘密。 */
    val redactedPayloadRef: String? = null,
    val detail: String = "",
)

class AuditCapacityError(message: String) : IllegalStateException(message)

interface ManagementAuditLog {
    /** append-only；容量耗尽抛 [AuditCapacityError]——不默默删除恢复依据。 */
    suspend fun append(event: AuditEvent)

    suspend fun list(proposalId: String? = null, limit: Int = 200): List<AuditEvent>

    suspend fun byId(auditEventId: String): AuditEvent?

    suspend fun allAuditEntries(): List<AuditEvent>

    /**
     * 保留裁剪：删除早于 cutoff 且不属于未决恢复的审计。
     * [protectedProposalIds]（未决 NEEDS_RECOVERY/APPLYING）相关事件绝不删。
     */
    suspend fun pruneBefore(cutoffMillis: Long, protectedProposalIds: Set<String>): Int
}
