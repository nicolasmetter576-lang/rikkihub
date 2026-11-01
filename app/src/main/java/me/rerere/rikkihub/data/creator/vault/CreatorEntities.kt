package me.rerere.rikkihub.data.creator.vault

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * CreatorVault（独立 Room v1）实体层。
 *
 * 设计约束：
 * - **只存 DTO 字段**，不把域实体整包序列化给模型（payloadJson/readSetJson
 *   是 typed DTO 的 canonical 序列化，不是 Settings/Conversation 原值）；
 * - 不存任何可执行授权字段：grant/binding 只有 id + epoch + 终态；
 * - 创造历史不进普通 Conversation/FTS/记忆/tool_outputs/普通整库备份；
 * - 所有 id 以 String 落库（UUID 文本），避免 TypeConverter。
 */

@Entity(tableName = "creator_sessions")
data class CreatorSessionEntity(
    @PrimaryKey val sessionId: String,
    val createdAtMillis: Long,
    val definitionVersion: Int,
    val nextRunOrdinal: Int,
)

@Entity(
    tableName = "creator_runs",
    indices = [Index("sessionId"), Index("status")],
)
data class CreatorRunEntity(
    @PrimaryKey val runId: String,
    val sessionId: String,
    val ordinal: Int,
    val runNonce: String,
    val grantId: String,
    val status: String,             // RUNNING / PAUSED / terminal CreatorRunTerminalState
    val frozenModelId: String,
    val frozenProviderId: String,
    val definitionVersion: Int,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val terminalReason: String?,
)

@Entity(
    tableName = "creator_call_ledger",
    indices = [
        Index("runId"),
        Index("state"),
        Index(value = ["runId", "state"]),
    ],
)
data class CreatorCallLedgerEntity(
    /** hostCallInstanceId —— 宿主分配，唯一性不依赖 provider callId。 */
    @PrimaryKey val hostCallInstanceId: String,
    val bindingId: String,
    val runId: String,
    val sessionId: String,
    val grantId: String,
    val stableToolId: String,
    val wireName: String,
    val runNonce: String,
    val stepNonce: String,
    val stepIndex: Int,
    val messageId: String?,
    val partIndex: Int?,
    val inputDigest: String,
    /** ISSUED / EXECUTING / OK / ERROR / OUTCOME_UNKNOWN / INTERRUPTED / DENIED */
    val state: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val terminalAtMillis: Long?,
    val outputSummary: String?,
    val errorCode: String?,
)

/**
 * 提案记录（状态机 DRAFT→PENDING_APPROVAL→APPROVED→APPLYING→APPLIED 及旁支，
 * 状态值 = `CreatorProposalState` 名；状态机/批准/审计执行归 management 核心）。
 *
 * [payloadJson] 只含 typed operation DTO 的 canonical 序列化——
 * **禁止**把 Settings/Conversation/域实体整包写入（模型可读面不得含认证材料）。
 */
@Entity(
    tableName = "creator_proposals",
    indices = [Index("sessionId"), Index("state"), Index("operationId")],
)
data class CreatorProposalEntity(
    @PrimaryKey val proposalId: String,
    val sessionId: String,
    val runId: String?,
    val sourceCallId: String?,
    /** proposalId/stepId 的稳定 operationId（幂等 key）。 */
    val operationId: String,
    val state: String,
    val canonicalDigest: String,
    val payloadJson: String,
    val readSetJson: String,
    val riskSummary: String?,
    val definitionVersion: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val expiresAtMillis: Long,
)

/** 追加审计（只 append；模型不能改删——DAO 不提供 update/delete）。 */
@Entity(
    tableName = "creator_audit",
    indices = [Index("atMillis"), Index("refId")],
)
data class CreatorAuditEntity(
    @PrimaryKey(autoGenerate = true) val auditId: Long = 0,
    val atMillis: Long,
    val kind: String,
    /** 事件来源：native / run:<id> / call:<hostCallInstanceId> / system。 */
    val actor: String,
    val refId: String?,
    val detailJson: String,
)

/** 加密回收 payload（含秘密的 before-image 走这里，不进明文审计/模型面）。 */
@Entity(
    tableName = "creator_recycle_payloads",
    indices = [Index("expiresAtMillis")],
)
data class CreatorRecyclePayloadEntity(
    @PrimaryKey val payloadId: String,
    val kind: String,
    val refId: String,
    /** AES-GCM 密文（key 在 CreatorVaultKeyStore，noBackup）。 */
    val blob: ByteArray,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is CreatorRecyclePayloadEntity &&
            payloadId == other.payloadId && blob.contentEquals(other.blob)

    override fun hashCode(): Int = payloadId.hashCode() * 31 + blob.contentHashCode()
}

/** 库级版本账本（storageEpoch 等；恢复/epoch 更换由这里记录）。 */
@Entity(tableName = "creator_meta")
data class CreatorMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Grant 影子记录：只有 id/epoch/事件，**不含**可执行授权字段。 */
@Entity(
    tableName = "creator_grant_shadow",
    indices = [Index("atMillis")],
)
data class CreatorGrantShadowEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val grantId: String,
    val sessionId: String,
    val runId: String,
    val epoch: Long,
    val kind: String,               // ISSUED / REVOKED / REVOKE_ALL
    val atMillis: Long,
)
