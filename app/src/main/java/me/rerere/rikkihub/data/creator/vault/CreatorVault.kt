package me.rerere.rikkihub.data.creator.vault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** 保留策略：回收 30 天、审计 90 天；未决恢复不自动清理。 */
object CreatorRetentionPolicy {
    const val RECYCLE_TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    const val AUDIT_TTL_MILLIS: Long = 90L * 24 * 60 * 60 * 1000
    /** 提案/ledger 不自动过期清理；只清 grant 影子等噪声。 */
}

/**
 * CreatorVault 门面：把 [CreatorDatabase] 的 DAO 收敛成领域操作面。
 *
 * - 写入侧全部经本类（DAO 不直接暴露给 UI/路由/模型面）；
 * - [verifyHealthy] 是 `CreatorEntryGate` 的 vault 检查点；
 * - [onStorageEpochChanged] 在恢复/epoch 更换时调用：库级 meta epoch 递增 +
 *   打开中的 run 标记脱钩（旧 journal 不自动续写新存储）；
 * - 审计只 append；回收 payload 只写密文。
 */
class CreatorVault(
    internal val database: CreatorDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CreatorStore {
    companion object {
        const val META_STORAGE_EPOCH = "storage_epoch"
        const val META_CREATED_VERSION = "created_schema_version"
    }

    private val metaMutex = Mutex()

    // ---- sessions ----
    override suspend fun createSession(sessionId: Uuid, definitionVersion: Int) {
        database.creatorSessionDao().insert(
            CreatorSessionEntity(
                sessionId = sessionId.toString(),
                createdAtMillis = nowMillis(),
                definitionVersion = definitionVersion,
                nextRunOrdinal = 0,
            )
        )
    }

    override suspend fun getSession(sessionId: Uuid) =
        database.creatorSessionDao().get(sessionId.toString())

    override fun observeSessions() = database.creatorSessionDao().observeAll()

    /** 原子领取下一个 run 序号（重试/双击不跳号竞争）。 */
    override suspend fun nextRunOrdinal(sessionId: Uuid): Int = metaMutex.withLock {
        val id = sessionId.toString()
        val session = database.creatorSessionDao().get(id) ?: return -1
        val ordinal = session.nextRunOrdinal
        database.creatorSessionDao().updateNextRunOrdinal(id, ordinal + 1)
        ordinal
    }

    // ---- runs ----
    override suspend fun insertRun(entity: CreatorRunEntity) = database.creatorRunDao().insert(entity)

    override suspend fun finishRun(runId: String, status: String, reason: String?) =
        database.creatorRunDao().updateTerminal(runId, status, nowMillis(), reason)

    override suspend fun openRuns() = database.creatorRunDao().listOpen()

    override fun observeRuns(sessionId: Uuid) = database.creatorRunDao().observeBySession(sessionId.toString())

    // ---- call ledger ----
    override suspend fun insertCall(entity: CreatorCallLedgerEntity) =
        database.creatorCallLedgerDao().insert(entity)

    override suspend fun updateCallState(
        hostCallInstanceId: String,
        state: String,
        outputSummary: String?,
        errorCode: String?,
    ) = database.creatorCallLedgerDao().updateState(
        hostCallInstanceId, state, nowMillis(), outputSummary, errorCode,
    )

    override suspend fun callsByState(runId: String, states: List<String>) =
        database.creatorCallLedgerDao().listByState(runId, states)

    override fun observeCalls(runId: String) = database.creatorCallLedgerDao().observeByRun(runId)

    // ---- proposals（状态机/批准执行归 management 核心；此处是存储面） ----
    override suspend fun insertProposal(entity: CreatorProposalEntity) =
        database.creatorProposalDao().insert(entity)

    override suspend fun getProposal(proposalId: String) = database.creatorProposalDao().get(proposalId)

    /** CAS 状态迁移：expectedState 不符返回 0（并发领取不执行两次）。 */
    override suspend fun transitionProposal(
        proposalId: String,
        expectedState: String,
        newState: String,
    ): Boolean = database.creatorProposalDao().transition(
        proposalId, expectedState, newState, nowMillis(),
    ) == 1

    override fun observeProposals(sessionId: Uuid) =
        database.creatorProposalDao().observeBySession(sessionId.toString())

    override suspend fun proposalsByState(states: List<String>) =
        database.creatorProposalDao().listByState(states)

    // ---- audit（只追加） ----
    override suspend fun appendAudit(kind: String, actor: String, refId: String?, detailJson: String) =
        database.creatorAuditDao().insert(
            CreatorAuditEntity(
                atMillis = nowMillis(),
                kind = kind,
                actor = actor,
                refId = refId,
                detailJson = detailJson,
            )
        )

    override suspend fun recentAudit(limit: Int) = database.creatorAuditDao().listRecent(limit)

    // ---- recycle payloads（只写密文） ----
    override suspend fun putRecyclePayload(payloadId: String, kind: String, refId: String, blob: ByteArray) =
        database.creatorRecycleDao().insert(
            CreatorRecyclePayloadEntity(
                payloadId = payloadId,
                kind = kind,
                refId = refId,
                blob = blob,
                createdAtMillis = nowMillis(),
                expiresAtMillis = nowMillis() + CreatorRetentionPolicy.RECYCLE_TTL_MILLIS,
            )
        )

    override suspend fun getRecyclePayload(payloadId: String) = database.creatorRecycleDao().get(payloadId)

    // ---- grant shadow ----
    override suspend fun recordGrantShadow(
        grantId: String,
        sessionId: String,
        runId: String,
        epoch: Long,
        kind: String,
    ) = database.creatorGrantShadowDao().insert(
        CreatorGrantShadowEntity(
            grantId = grantId,
            sessionId = sessionId,
            runId = runId,
            epoch = epoch,
            kind = kind,
            atMillis = nowMillis(),
        )
    )

    // ---- meta / epoch ----
    override suspend fun storageEpoch(): Long = metaMutex.withLock {
        database.creatorMetaDao().get(META_STORAGE_EPOCH)?.value?.toLongOrNull() ?: 0L
    }

    /**
     * 数据恢复/epoch 更换：库级 epoch 递增 + 所有打开 run 标脱钩终态。
     * 旧 grant/binding 由 issuer 进程内册表同步失效（epochProvider 读这里）。
     */
    override suspend fun onStorageEpochChanged(reason: String) = metaMutex.withLock {
        val current = database.creatorMetaDao().get(META_STORAGE_EPOCH)?.value?.toLongOrNull() ?: 0L
        database.creatorMetaDao().put(CreatorMetaEntity(META_STORAGE_EPOCH, (current + 1).toString()))
        database.creatorRunDao().listOpen().forEach { run ->
            database.creatorRunDao().updateTerminal(
                run.runId, "DETACHED", nowMillis(), "epoch changed: $reason",
            )
        }
        current + 1
    }

    /** 库健康检查（gate 用）：meta 可读 + 关键表可查询。 */
    override suspend fun verifyHealthy(): Boolean = runCatching {
        database.creatorMetaDao().get(META_STORAGE_EPOCH)
        database.creatorRunDao().listOpen()
        true
    }.getOrDefault(false)

    /**
     * 过期清理（回收/审计）。未决恢复（NEEDS_RECOVERY/APPLYING）相关审计受保护；
     * 提案与 ledger 不自动过期（恢复依据不能被默默删除）。
     */
    override suspend fun pruneExpired(protectedAuditRefs: List<String>) {
        val now = nowMillis()
        database.creatorRecycleDao().pruneBefore(now)
        database.creatorAuditDao().pruneBefore(
            now - CreatorRetentionPolicy.AUDIT_TTL_MILLIS,
            protectedAuditRefs,
        )
    }
}
