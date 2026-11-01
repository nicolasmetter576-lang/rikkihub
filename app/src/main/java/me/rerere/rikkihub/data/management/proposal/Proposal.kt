package me.rerere.rikkihub.data.management.proposal

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.management.common.DiffLine
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSet
import me.rerere.rikkihub.data.management.common.RetentionNote
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.ports.StoreKind
import me.rerere.rikkihub.data.management.ports.StoreReceipt

/**
 * 提案状态。唯一合法转移表见 [ProposalStateMachine]；
 * 非法转移抛 [IllegalProposalTransition]，不持久化。
 */
enum class ProposalStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    APPLYING,

    // 终态
    APPLIED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    CONFLICT,
    FAILED,
    NEEDS_RECOVERY,
}

enum class ProposalErrorCode {
    UNKNOWN_OBJECT,
    RESERVED_ID,
    FORBIDDEN_FIELD,
    BUDGET_EXCEEDED,
    INVALID_VALUE,
    MISSING_REPLACEMENT_PLAN,
    CAPABILITY_INCOMPATIBLE,
    NOT_FOUND,
    INVALID_COLLECTION,
    TOKEN_CONFLICT,
    STALE_READSET,
    STALE_EPOCH,
    LEASE_BUSY,
    STEP_FAILED,
    OUTCOME_UNKNOWN,
    PARTIAL_APPLIED,
    COMPENSATION_FAILED,
    WRITE_GATE_CLOSED,
    APPROVAL_DIGEST_MISMATCH,
    APPROVAL_EXPIRED,
    NOT_APPLICABLE_STATE,
}

sealed class ProposalServiceError(
    val errorCode: ProposalErrorCode,
    message: String,
) : IllegalStateException(message) {
    class UnknownProposal(id: String) :
        ProposalServiceError(ProposalErrorCode.NOT_FOUND, "unknown proposal $id")

    class ApprovalDigestMismatch(id: String) :
        ProposalServiceError(ProposalErrorCode.APPROVAL_DIGEST_MISMATCH, "digest mismatch for $id")

    class ApprovalExpired(id: String) :
        ProposalServiceError(ProposalErrorCode.APPROVAL_EXPIRED, "approval expired for $id")

    class NotApplicableState(id: String, status: ProposalStatus) :
        ProposalServiceError(ProposalErrorCode.NOT_APPLICABLE_STATE, "$id in $status")

    class WriteGateClosed(reasons: List<String>) :
        ProposalServiceError(ProposalErrorCode.WRITE_GATE_CLOSED, "write gate closed: $reasons")

    class DomainError(code: ProposalErrorCode, detail: String) :
        ProposalServiceError(code, detail)
}

/** 精确批准：一次性的有限提交能力。 */
@Serializable
data class ProposalApproval(
    /** 必须 == Proposal.canonicalDigest —— 命令/readSet/diff/保留说明任一变化即失效。 */
    val digest: String,
    val readSetFingerprint: String,
    /** 批准时刻的 storageEpoch；epoch 变则批准失效。 */
    val storageEpoch: Long,
    val definitionVersion: Int,
    val approvedAtMillis: Long,
    /** 批准自身期限（独立于草稿期限）。 */
    val expiresAtMillis: Long,
    /** 宿主撤销代次（撤销提交能力后置变，旧批准失效）。 */
    val revocationEpoch: Long,
)

/**
 * 提案记录：typed 命令 + 冻结 read-set + 结构化差异 + digest。
 * payloadJson 是 ManagementCommand 序列化，**不含**秘密原文
 * （秘密 before-image 在 recovery/RecycleStore 的回收 payload）。
 */
@Serializable
data class Proposal(
    val proposalId: String,
    val sessionId: String,
    val runId: String?,
    val callBindingId: String?,
    val definitionVersion: Int,
    val commandJson: String,
    val commandDomain: ManagementDomain,
    /** 逻辑占位名（"$new0" 等）→ 宿主真实 ID；DRAFT 期冻结。 */
    val hostAssignedIds: Map<String, String>,
    val readSet: ReadSet,
    val readSetFingerprint: String,
    /** 冻结的执行步骤（执行期重放，不重新 plan）。 */
    val steps: List<PersistedStep>,
    val diffSummary: List<DiffLine>,
    val risk: RiskLevel,
    val retention: RetentionNote,
    val canonicalDigest: String,
    val status: ProposalStatus,
    val requiresEnhancedConfirm: Boolean,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val approval: ProposalApproval? = null,
    val statusReason: String? = null,
    val applyStartedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    /** APPLYING 期的停止标记：停止新步准入，不是状态本身。 */
    val cancelRequested: Boolean = false,
    /** Includes cascade targets; omission cannot silently drop a required fence. */
    val affectedRefs: List<ObjectRef> = emptyList(),
) {
    val isTerminal: Boolean
        get() = when (status) {
            ProposalStatus.APPLIED, ProposalStatus.REJECTED, ProposalStatus.EXPIRED,
            ProposalStatus.CANCELLED, ProposalStatus.CONFLICT, ProposalStatus.FAILED -> true

            ProposalStatus.NEEDS_RECOVERY,
            ProposalStatus.DRAFT, ProposalStatus.PENDING_APPROVAL,
            ProposalStatus.APPROVED, ProposalStatus.APPLYING -> false
        }
}

/** 持久化执行步骤：op 序列化 JSON + 步骤 id + 执行结果。 */
@Serializable
data class PersistedStep(
    val stepId: String,
    val opJson: String,
    val receipt: me.rerere.rikkihub.data.management.ports.StoreReceipt? = null,
)

/** 模型/调用来源：binding 真伪由上游校验，本层只做存在性对账。 */
data class Provenance(
    val sessionId: String,
    val runId: String,
    val callBindingId: String,
)

/** 结果引用：审计/模型可见结果一律指向真实 receipt。 */
@Serializable
data class ResultReference(
    val operationId: String,
    val stepId: String,
    val store: StoreKind,
    val receiptOperationId: String,
    val objectRefs: List<ObjectRef>,
)

/** 脱敏状态视图（模型 status 与 UI 共用 DTO）。 */
@Serializable
data class ProposalStatusView(
    val proposalId: String,
    val status: ProposalStatus,
    val statusReason: String?,
    val errorCode: ProposalErrorCode?,
    /** PENDING_APPROVAL / APPROVED 未认领 / APPLYING 停新步 时为 true。 */
    val canCancel: Boolean,
    /** CONFLICT / 过期 / 恢复场景 → 需要重新预览+原生批准。 */
    val needsNativeReapproval: Boolean,
    val resultRefs: List<ResultReference>,
    val readSetFingerprint: String,
)

@Serializable
data class ProposalSummary(
    val proposalId: String,
    val status: ProposalStatus,
    val domain: ManagementDomain,
    val risk: RiskLevel,
    val createdAtMillis: Long,
    val canonicalDigest: String,
    val diffLineCount: Int,
)

/** 恢复动作：重新预览实际剩余差异后取得新的有限批准。 */
enum class RecoveryAction {
    /** 仅核对事实；剩余步骤需要新的 RecoveryApproval 后按新差异续跑。 */
    RESUME_VERIFY,

    /** 补偿逆步：仅 when after-token 吻合（不覆盖崩溃后新改动）。 */
    COMPENSATE,

    /** 原生受限终结：标 FAILED，保留全部审计与参与记录。 */
    TERMINATE,
}

@Serializable
data class RecoveryApproval(
    val proposalId: String,
    /** 对当前实际剩余差异的 digest（原生重新预览时生成）。 */
    val residualDigest: String,
    val approvedAtMillis: Long,
    val expiresAtMillis: Long,
)

/** Domains this proposal changes: one, or several for a combined Settings change (`SettingsBatchCommand`). */
fun Proposal.commandDomains(): List<ManagementDomain> {
    val batch = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(commandJson) as? kotlinx.serialization.json.JsonObject
        (root?.get("commands") as? kotlinx.serialization.json.JsonArray)?.map { command ->
            val collection = ((command as kotlinx.serialization.json.JsonObject)["collectionId"]
                as kotlinx.serialization.json.JsonPrimitive).content
            me.rerere.rikkihub.data.management.ports.SettingsManagementPort.domainForCollection(collection)
        }
    }.getOrNull()
    return (listOf(commandDomain) + batch.orEmpty()).distinct()
}
