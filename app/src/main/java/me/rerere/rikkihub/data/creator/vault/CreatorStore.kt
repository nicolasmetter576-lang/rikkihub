package me.rerere.rikkihub.data.creator.vault

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Vault 存储面（controller/entry/gate 依赖此接口而非 Room 具体类：
 * JVM 单测可用内存 fake 覆盖授权/账本/对账语义，instrumented 测真 Room）。
 *
 * 语义契约：
 * - [transitionProposal] 是 CAS：expectedState 不符返回 false（并发/旧回调
 *   不执行两次）；
 * - [appendAudit] 只追加，接口面不提供审计改删；
 * - [onStorageEpochChanged] 原子递增 epoch 并把打开 run 标脱钩。
 */
interface CreatorStore {
    // sessions
    suspend fun createSession(sessionId: Uuid, definitionVersion: Int)

    suspend fun getSession(sessionId: Uuid): CreatorSessionEntity?

    fun observeSessions(): Flow<List<CreatorSessionEntity>>

    /** 原子领取下一个 run 序号；会话不存在返回 -1。 */
    suspend fun nextRunOrdinal(sessionId: Uuid): Int

    // runs
    suspend fun insertRun(entity: CreatorRunEntity)

    suspend fun finishRun(runId: String, status: String, reason: String?)

    suspend fun openRuns(): List<CreatorRunEntity>

    fun observeRuns(sessionId: Uuid): Flow<List<CreatorRunEntity>>

    // call ledger
    suspend fun insertCall(entity: CreatorCallLedgerEntity)

    suspend fun updateCallState(
        hostCallInstanceId: String,
        state: String,
        outputSummary: String? = null,
        errorCode: String? = null,
    )

    suspend fun callsByState(runId: String, states: List<String>): List<CreatorCallLedgerEntity>

    fun observeCalls(runId: String): Flow<List<CreatorCallLedgerEntity>>

    // proposals
    suspend fun insertProposal(entity: CreatorProposalEntity)

    suspend fun getProposal(proposalId: String): CreatorProposalEntity?

    suspend fun transitionProposal(
        proposalId: String,
        expectedState: String,
        newState: String,
    ): Boolean

    fun observeProposals(sessionId: Uuid): Flow<List<CreatorProposalEntity>>

    suspend fun proposalsByState(states: List<String>): List<CreatorProposalEntity>

    // audit（只追加）
    suspend fun appendAudit(kind: String, actor: String, refId: String?, detailJson: String)

    suspend fun recentAudit(limit: Int): List<CreatorAuditEntity>

    // recycle payloads
    suspend fun putRecyclePayload(payloadId: String, kind: String, refId: String, blob: ByteArray)

    suspend fun getRecyclePayload(payloadId: String): CreatorRecyclePayloadEntity?

    // grant shadow（id/epoch/事件，无可执行授权字段）
    suspend fun recordGrantShadow(
        grantId: String,
        sessionId: String,
        runId: String,
        epoch: Long,
        kind: String,
    )

    // meta / epoch / 生命周期
    suspend fun storageEpoch(): Long

    suspend fun onStorageEpochChanged(reason: String): Long

    suspend fun verifyHealthy(): Boolean

    suspend fun pruneExpired(protectedAuditRefs: List<String> = emptyList())
}
