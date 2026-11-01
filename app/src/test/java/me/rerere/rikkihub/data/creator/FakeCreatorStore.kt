package me.rerere.rikkihub.data.creator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.rerere.rikkihub.data.creator.vault.CreatorAuditEntity
import me.rerere.rikkihub.data.creator.vault.CreatorCallLedgerEntity
import me.rerere.rikkihub.data.creator.vault.CreatorMetaEntity
import me.rerere.rikkihub.data.creator.vault.CreatorProposalEntity
import me.rerere.rikkihub.data.creator.vault.CreatorRecyclePayloadEntity
import me.rerere.rikkihub.data.creator.vault.CreatorRunEntity
import me.rerere.rikkihub.data.creator.vault.CreatorSessionEntity
import me.rerere.rikkihub.data.creator.vault.CreatorStore
import kotlin.uuid.Uuid

/**
 * 内存 CreatorStore fake（JVM 单测）。语义忠实于接口：proposal CAS、
 * 原子 run 序号、epoch 递增、审计只追加。失败注入：`failOn` 指定操作抛错
 * （用于杀进程/写失败场景的有限近似；真 SQLite 行为走 instrumented）。
 */
class FakeCreatorStore(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CreatorStore {

    /** 注入失败的操作名（如 "insertRun"/"updateCallState"）——写入时抛错。 */
    @Volatile
    var failOn: String? = null

    val sessions = LinkedHashMap<String, CreatorSessionEntity>()
    val runs = LinkedHashMap<String, CreatorRunEntity>()
    val calls = LinkedHashMap<String, CreatorCallLedgerEntity>()
    val proposals = LinkedHashMap<String, CreatorProposalEntity>()
    val audits = mutableListOf<CreatorAuditEntity>()
    val recyclePayloads = LinkedHashMap<String, CreatorRecyclePayloadEntity>()
    val grantShadows = mutableListOf<me.rerere.rikkihub.data.creator.vault.CreatorGrantShadowEntity>()
    var epoch = 0L
    private var auditSeq = 0L

    private fun maybeFail(op: String) {
        if (failOn == op) throw IllegalStateException("injected failure: $op")
    }

    override suspend fun createSession(sessionId: Uuid, definitionVersion: Int) {
        maybeFail("createSession")
        val id = sessionId.toString()
        if (sessions.containsKey(id)) throw IllegalStateException("duplicate session")
        sessions[id] = CreatorSessionEntity(id, nowMillis(), definitionVersion, 0)
    }

    override suspend fun getSession(sessionId: Uuid) = sessions[sessionId.toString()]

    override fun observeSessions(): Flow<List<CreatorSessionEntity>> =
        MutableStateFlow(sessions.values.toList())

    override suspend fun nextRunOrdinal(sessionId: Uuid): Int {
        val id = sessionId.toString()
        val s = sessions[id] ?: return -1
        sessions[id] = s.copy(nextRunOrdinal = s.nextRunOrdinal + 1)
        return s.nextRunOrdinal
    }

    override suspend fun insertRun(entity: CreatorRunEntity) {
        maybeFail("insertRun")
        if (runs.containsKey(entity.runId)) throw IllegalStateException("duplicate run")
        runs[entity.runId] = entity
    }

    override suspend fun finishRun(runId: String, status: String, reason: String?) {
        maybeFail("finishRun")
        runs[runId]?.let {
            runs[runId] = it.copy(status = status, endedAtMillis = nowMillis(), terminalReason = reason)
        }
    }

    override suspend fun openRuns(): List<CreatorRunEntity> =
        runs.values.filter { it.status in listOf("RUNNING", "PAUSED") }

    override fun observeRuns(sessionId: Uuid): Flow<List<CreatorRunEntity>> =
        MutableStateFlow(runs.values.filter { it.sessionId == sessionId.toString() })

    override suspend fun insertCall(entity: CreatorCallLedgerEntity) {
        maybeFail("insertCall")
        calls[entity.hostCallInstanceId] = entity
    }

    override suspend fun updateCallState(
        hostCallInstanceId: String,
        state: String,
        outputSummary: String?,
        errorCode: String?,
    ) {
        maybeFail("updateCallState")
        calls[hostCallInstanceId]?.let {
            calls[hostCallInstanceId] = it.copy(
                state = state,
                terminalAtMillis = nowMillis(),
                outputSummary = outputSummary,
                errorCode = errorCode,
            )
        }
    }

    override suspend fun callsByState(runId: String, states: List<String>) =
        calls.values.filter { it.runId == runId && it.state in states }

    override fun observeCalls(runId: String): Flow<List<CreatorCallLedgerEntity>> =
        MutableStateFlow(calls.values.filter { it.runId == runId })

    override suspend fun insertProposal(entity: CreatorProposalEntity) {
        maybeFail("insertProposal")
        proposals[entity.proposalId] = entity
    }

    override suspend fun getProposal(proposalId: String) = proposals[proposalId]

    override suspend fun transitionProposal(
        proposalId: String,
        expectedState: String,
        newState: String,
    ): Boolean {
        val p = proposals[proposalId] ?: return false
        if (p.state != expectedState) return false
        proposals[proposalId] = p.copy(state = newState, updatedAtMillis = nowMillis())
        return true
    }

    override fun observeProposals(sessionId: Uuid): Flow<List<CreatorProposalEntity>> =
        MutableStateFlow(proposals.values.filter { it.sessionId == sessionId.toString() })

    override suspend fun proposalsByState(states: List<String>) =
        proposals.values.filter { it.state in states }

    override suspend fun appendAudit(kind: String, actor: String, refId: String?, detailJson: String) {
        maybeFail("appendAudit")
        audits += CreatorAuditEntity(
            auditId = ++auditSeq,
            atMillis = nowMillis(),
            kind = kind,
            actor = actor,
            refId = refId,
            detailJson = detailJson,
        )
    }

    override suspend fun recentAudit(limit: Int) = audits.takeLast(limit)

    override suspend fun putRecyclePayload(payloadId: String, kind: String, refId: String, blob: ByteArray) {
        recyclePayloads[payloadId] = CreatorRecyclePayloadEntity(
            payloadId, kind, refId, blob, nowMillis(),
            nowMillis() + 30L * 24 * 3600 * 1000,
        )
    }

    override suspend fun getRecyclePayload(payloadId: String) = recyclePayloads[payloadId]

    override suspend fun recordGrantShadow(
        grantId: String,
        sessionId: String,
        runId: String,
        epoch: Long,
        kind: String,
    ) {
        grantShadows += me.rerere.rikkihub.data.creator.vault.CreatorGrantShadowEntity(
            grantId = grantId, sessionId = sessionId, runId = runId,
            epoch = epoch, kind = kind, atMillis = nowMillis(),
        )
    }

    override suspend fun storageEpoch(): Long = epoch

    override suspend fun onStorageEpochChanged(reason: String): Long {
        epoch++
        openRuns().forEach { finishRun(it.runId, "DETACHED", "epoch changed: $reason") }
        return epoch
    }

    override suspend fun verifyHealthy(): Boolean = true

    override suspend fun pruneExpired(protectedAuditRefs: List<String>) {}
}
