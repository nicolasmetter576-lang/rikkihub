package me.rerere.rikkihub.data.management.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.management.audit.AuditCapacityError
import me.rerere.rikkihub.data.management.audit.AuditEvent
import me.rerere.rikkihub.data.management.audit.ManagementAuditLog
import me.rerere.rikkihub.data.management.common.sha256Hex
import me.rerere.rikkihub.data.management.proposal.Proposal
import me.rerere.rikkihub.data.management.ports.ParticipationRecord
import me.rerere.rikkihub.data.management.ports.ParticipationPhase
import me.rerere.rikkihub.data.management.ports.StoreKind
import me.rerere.rikkihub.data.management.recovery.ManagementRetentionPolicy
import me.rerere.rikkihub.data.management.recovery.NoopPayloadCipher
import me.rerere.rikkihub.data.management.recovery.PayloadCipher
import me.rerere.rikkihub.data.management.recovery.RecyclePayloadMeta
import me.rerere.rikkihub.data.management.recovery.RecycleStore
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File

/**
 * `filesDir/creator_management/` 聚合后端。
 *
 * 持久化独立于 Vault（Vault 缺失/损坏也能重建 fence）；
 * 目录整体由 `CreatorBackupScope` 类排除出普通备份（mutation 接线时消费）。
 *
 * 布局：proposals/、journal/、audit/、participation/、fences/、recycle/。
 * 全部原子写（tmp+force+rename）或 append+force；读容忍尾部截断。
 */
class FileBackedManagementStore(
    rootDir: File,
    private val cipher: PayloadCipher = NoopPayloadCipher(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ManagementProposalStore, ManagementJournal, ManagementAuditLog,
    ParticipationStore, FenceStore, RecycleStore {

    private val io = AtomicFileIO()
    private val proposalsDir = File(rootDir, "proposals")
    private val journalFile = File(rootDir, "journal/journal.log")
    private val auditFile = File(rootDir, "audit/audit.log")
    private val participationDir = File(rootDir, "participation")
    private val fencesFile = File(rootDir, "fences/fences.json")
    private val recycleDir = File(rootDir, "recycle")

    private val proposalMutex = Mutex()
    private val auditCapacityMutex = Mutex()
    private val participationMutex = Mutex()
    private val fenceMutex = Mutex()
    private val recycleMutex = Mutex()

    private val journalLedger = NdjsonLedger(journalFile, JournalEntry.serializer(), io)
    private val auditLedger = NdjsonLedger(auditFile, AuditEvent.serializer(), io)

    // ---------- ManagementProposalStore ----------

    private fun proposalFile(proposalId: String): File {
        require(proposalId.isNotBlank() && proposalId.none { it == '/' || it == '\\' }) {
            "unsafe proposal id"
        }
        return File(proposalsDir, "$proposalId.json")
    }

    override suspend fun get(proposalId: String): Proposal? = proposalMutex.withLock {
        io.readJson(proposalFile(proposalId), Proposal.serializer())
    }

    override suspend fun put(proposal: Proposal) {
        proposalMutex.withLock {
            io.writeAtomic(proposalFile(proposal.proposalId), Proposal.serializer(), proposal)
        }
    }

    override suspend fun listAll(): List<Proposal> = proposalMutex.withLock {
        proposalsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { io.readJson(it, Proposal.serializer()) }
            ?: emptyList()
    }

    override suspend fun deleteProposal(proposalId: String) {
        proposalMutex.withLock { proposalFile(proposalId).delete() }
    }

    /** 锁内读-改-写（状态机转移需要原子比较当前状态）。 */
    override suspend fun updateProposal(
        proposalId: String,
        block: (Proposal?) -> Proposal,
    ): Proposal = proposalMutex.withLock {
        val current = io.readJson(proposalFile(proposalId), Proposal.serializer())
        val next = block(current)
        io.writeAtomic(proposalFile(proposalId), Proposal.serializer(), next)
        next
    }

    // ---------- ManagementJournal ----------

    override suspend fun append(entry: JournalEntry) = journalLedger.appendEntry(entry)

    override suspend fun entriesForProposal(proposalId: String): List<JournalEntry> =
        journalLedger.readAll().filter { it.proposalId == proposalId }

    override suspend fun tail(limit: Int): List<JournalEntry> = journalLedger.readTail(limit)

    override suspend fun allJournalEntries(): List<JournalEntry> = journalLedger.readAll()

    // ---------- ManagementAuditLog ----------

    override suspend fun append(event: AuditEvent) = auditCapacityMutex.withLock {
        // 容量上限：拒绝新写并提示，不默默删除恢复依据
        val size = auditLedger.readAll().size
        if (size >= ManagementRetentionPolicy.AUDIT_MAX_ENTRIES) {
            throw AuditCapacityError(
                "audit log capacity ${ManagementRetentionPolicy.AUDIT_MAX_ENTRIES} reached"
            )
        }
        auditLedger.appendEntry(event)
    }

    override suspend fun list(proposalId: String?, limit: Int): List<AuditEvent> =
        auditLedger.readAll()
            .let { if (proposalId == null) it else it.filter { e -> e.proposalId == proposalId } }
            .takeLast(limit)

    override suspend fun byId(auditEventId: String): AuditEvent? =
        auditLedger.readAll().find { it.auditEventId == auditEventId }

    override suspend fun allAuditEntries(): List<AuditEvent> = auditLedger.readAll()

    override suspend fun pruneBefore(cutoffMillis: Long, protectedProposalIds: Set<String>): Int {
        var removed = 0
        auditLedger.rewrite { e ->
            val keep = e.atMillis >= cutoffMillis || e.proposalId in protectedProposalIds
            if (!keep) removed++
            keep
        }
        return removed
    }

    // ---------- ParticipationStore ----------

    private fun participationFile(store: StoreKind, operationId: String): File {
        require(operationId.isNotBlank() && operationId.length <= 512)
        val safe = sha256Hex(operationId)
        return File(participationDir, "${store.name}-$safe.json")
    }

    override suspend fun write(store: StoreKind, record: ParticipationRecord) {
        participationMutex.withLock {
            io.writeAtomic(
                participationFile(store, record.operationId),
                ParticipationRecord.serializer(), record,
            )
        }
    }

    override suspend fun read(store: StoreKind, operationId: String): ParticipationRecord? =
        participationMutex.withLock {
            io.readJson(participationFile(store, operationId), ParticipationRecord.serializer())
        }

    override suspend fun allRecords(): List<ParticipationRecord> = participationMutex.withLock {
        participationDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { io.readJson(it, ParticipationRecord.serializer()) }
            ?: emptyList()
    }

    override suspend fun pending(): List<ParticipationRecord> =
        allRecords().filter { it.phase == ParticipationPhase.INTENT || it.fenceHeld }

    // ---------- FenceStore ----------

    override suspend fun install(entries: List<FenceEntry>) {
        fenceMutex.withLock {
            val existing = io.readJson(fencesFile, FenceList.serializer())?.entries.orEmpty()
            entries.forEach { entry ->
                require(existing.none { it.ref == entry.ref && it.proposalId != entry.proposalId }) {
                    "Fence is owned by another proposal"
                }
            }
            val merged = (existing + entries).distinctBy { it.ref }
            io.writeAtomic(fencesFile, FenceList.serializer(), FenceList(merged))
        }
    }

    override suspend fun release(proposalId: String) {
        fenceMutex.withLock {
            val existing = io.readJson(fencesFile, FenceList.serializer())?.entries.orEmpty()
            io.writeAtomic(
                fencesFile, FenceList.serializer(),
                FenceList(existing.filterNot { it.proposalId == proposalId }),
            )
        }
    }

    override suspend fun allFences(): List<FenceEntry> = fenceMutex.withLock {
        io.readJson(fencesFile, FenceList.serializer())?.entries.orEmpty()
    }

    @kotlinx.serialization.Serializable
    private data class FenceList(val entries: List<FenceEntry>)

    // ---------- RecycleStore ----------

    private fun recycleMetaFile(proposalId: String): File {
        proposalFile(proposalId) // identical safe identifier validation
        return File(recycleDir, "$proposalId.meta.json")
    }
    private fun recycleBodyFile(proposalId: String): File {
        proposalFile(proposalId)
        return File(recycleDir, "$proposalId.bin")
    }

    override suspend fun store(
        proposalId: String,
        payload: ByteArray,
        containsSecrets: Boolean,
    ): RecyclePayloadMeta = recycleMutex.withLock {
        val incomplete = containsSecrets && !cipher.available
        // 容量上限：超限拒绝而不是静默丢恢复依据
        val used = recycleDir.listFiles { f -> f.extension == "bin" }
            ?.sumOf { it.length() } ?: 0L
        if (used + payload.size > ManagementRetentionPolicy.RECYCLE_MAX_BYTES) {
            throw IllegalStateException("recycle store capacity reached")
        }
        recycleDir.mkdirs()
        val encrypted = containsSecrets && cipher.available
        if (!incomplete) {
            val bytes = if (encrypted) cipher.encrypt(payload) else payload
            io.writeAtomicText(
                recycleBodyFile(proposalId),
                java.util.Base64.getEncoder().encodeToString(bytes),
            )
        }
        val meta = RecyclePayloadMeta(
            proposalId = proposalId,
            createdAtMillis = nowMillis(),
            expiresAtMillis = nowMillis() +
                ManagementRetentionPolicy.RECYCLE_DAYS * 24L * 3600 * 1000,
            incomplete = incomplete,
            encrypted = encrypted,
            contentSha256 = sha256Hex(payload),
            sizeBytes = payload.size.toLong(),
        )
        io.writeAtomic(recycleMetaFile(proposalId), RecyclePayloadMeta.serializer(), meta)
        meta
    }

    override suspend fun load(proposalId: String): ByteArray? = recycleMutex.withLock {
        val meta = io.readJson(recycleMetaFile(proposalId), RecyclePayloadMeta.serializer())
            ?: return@withLock null
        if (meta.incomplete) return@withLock null
        val body = recycleBodyFile(proposalId)
        if (!body.isFile) return@withLock null
        if (meta.expiresAtMillis <= nowMillis()) return@withLock null
        val bytes = java.util.Base64.getDecoder().decode(body.readText(Charsets.UTF_8))
        val plain = if (meta.encrypted) cipher.decrypt(bytes) else bytes
        check(plain.size.toLong() == meta.sizeBytes && sha256Hex(plain) == meta.contentSha256) {
            "Recycle payload integrity mismatch"
        }
        plain
    }

    override suspend fun meta(proposalId: String): RecyclePayloadMeta? = recycleMutex.withLock {
        io.readJson(recycleMetaFile(proposalId), RecyclePayloadMeta.serializer())
    }

    override suspend fun listMetas(): List<RecyclePayloadMeta> = recycleMutex.withLock {
        recycleDir.listFiles { f -> f.name.endsWith(".meta.json") }
            ?.mapNotNull { io.readJson(it, RecyclePayloadMeta.serializer()) }
            ?: emptyList()
    }

    override suspend fun deletePayload(proposalId: String) {
        recycleMutex.withLock {
            recycleMetaFile(proposalId).delete()
            recycleBodyFile(proposalId).delete()
        }
    }
}
