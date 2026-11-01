package me.rerere.rikkihub.data.management.ports

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.management.persistence.ParticipationStore
import me.rerere.rikkihub.data.management.recovery.RecycleStore
import me.rerere.rikkihub.data.management.recovery.SettingsRecoverySnapshots

/** Actual DataStore gateway + authenticated PRIVATE recovery images. */
class SettingsStoreGateway(
    private val port: SettingsManagementPort,
    private val participation: ParticipationStore,
    private val recycle: RecycleStore,
) : StoreGateway {
    override val store = StoreKind.SETTINGS_DATASTORE
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun apply(op: StoreOperation, operationId: String, expected: List<ReadSetEntry>): StoreReceipt =
        mutex.withLock {
            require(op.store == store)
            val existing = port.receiptFor(operationId, op.objectRefs.toSet())
            if (existing != null) {
                require(existing.storageEpoch == storageEpoch() && existing.operationFingerprint == SettingsManagementPort.fingerprint(listOf(op))) { "Operation ID changed payload" }
                return@withLock existing
            }
            val captured = if (op is SettingsRestoreOp) mapOf(op.payloadRef to loadSnapshot(op.payloadRef)) else emptyMap()
            val targets = targets(op, captured)
            require(targets.isNotEmpty())
            for (target in targets) if (!target.startsWith("settings.defaults.")) {
                require(expected.any { it is ReadSetEntry.Collection && it.collectionId == target ||
                    it is ReadSetEntry.Object && it.ref.id == "collection:$target" }) { "Collection baseline required" }
            }
            val key = beforeKey(operationId)
            if (recycle.meta(key) == null) {
                val snapshot = SettingsRecoverySnapshots.capture(port.settingsSnapshot(), targets)
                val meta = recycle.store(key, snapshot.toString().toByteArray(Charsets.UTF_8), containsSecrets = true)
                check(!meta.incomplete && meta.encrypted) { "Authenticated recovery storage unavailable" }
            }
            port.mutateCaptured(listOf(op), expected, operationId, captured)
        }

    override suspend fun currentToken(ref: ObjectRef): VersionToken? = port.tokenFor(ref)
    override suspend fun collectionToken(collectionId: String): VersionToken = port.collectionToken(collectionId)
    override suspend fun storageEpoch(): Long = port.collectionToken("settings.assistants").epoch

    override suspend fun reconcile(op: StoreOperation, operationId: String): OperationFact {
        val receipt = port.receiptFor(operationId, op.objectRefs.toSet())
            ?: return OperationFact(OperationFactStatus.NOT_APPLIED) // atomic receipt+business edit
        if (receipt.storageEpoch != storageEpoch() || receipt.operationFingerprint != SettingsManagementPort.fingerprint(listOf(op)))
            return OperationFact(OperationFactStatus.UNKNOWN)
        return OperationFact(OperationFactStatus.APPLIED, receipt)
    }

    override suspend fun compensate(op: StoreOperation, operationId: String, afterTokens: List<TokenRef>): CompensationResult =
        mutex.withLock {
            require(operationId.endsWith("/compensate") && afterTokens.isNotEmpty())
            val originalId = operationId.removeSuffix("/compensate")
            val fact = reconcile(op, originalId)
            if (fact.status != OperationFactStatus.APPLIED || fact.receipt?.afterTokens != afterTokens)
                return@withLock CompensationResult(CompensationStatus.FAILED, reason = "Operation ownership mismatch")
            if (afterTokens.any { currentToken(it.ref) != it.token })
                return@withLock CompensationResult(CompensationStatus.SKIPPED_TOKEN_MISMATCH)
            val key = beforeKey(originalId)
            val snapshot = loadSnapshot(key)
            val restore = SettingsRestoreOp(key, op.objectRefs, "compensate-settings")
            val expected = afterTokens.map { token ->
                if (token.ref.id.startsWith("collection:")) ReadSetEntry.Collection(token.ref.id.removePrefix("collection:"), token.token)
                else ReadSetEntry.Object(token.ref, token.token)
            }
            try {
                val receipt = port.mutateCaptured(listOf(restore), expected, operationId, mapOf(key to snapshot))
                CompensationResult(CompensationStatus.APPLIED, receipt)
            } catch (_: ConcurrencyConflict) {
                CompensationResult(CompensationStatus.SKIPPED_TOKEN_MISMATCH)
            }
        }

    /** The Undo planner references this opaque key, never sends raw before-images. */
    fun beforeImageRef(operationId: String): String = beforeKey(operationId)

    internal suspend fun loadSnapshot(key: String): JsonObject = json.parseToJsonElement(
        checkNotNull(recycle.load(key)) { "Recovery materials unavailable" }.toString(Charsets.UTF_8)).jsonObject

    override suspend fun writeParticipation(record: ParticipationRecord) {
        require(record.store == store && record.storageEpoch == storageEpoch())
        participation.write(store, record)
    }
    override suspend fun readParticipation(operationId: String): ParticipationRecord? = participation.read(store, operationId)
    override suspend fun pendingParticipations(): List<ParticipationRecord> = participation.pending().filter { it.store == store }
    override suspend fun releaseParticipation(operationId: String) {
        val record = readParticipation(operationId) ?: return
        participation.write(store, record.copy(phase = ParticipationPhase.RELEASED, fenceHeld = false,
            updatedAtMillis = System.currentTimeMillis()))
    }

    private fun targets(op: StoreOperation, captured: Map<String, JsonObject>): Set<String> = when (op) {
        is SettingsCollectionOp -> setOf(op.collectionId)
        is SettingsFieldOp -> setOf(op.fieldId)
        is SettingsTransactionOp -> {
            require(op.operations.size in 1..64)
            op.operations.map(StoreOperationCodec::decode).flatMap { child ->
                require(child is SettingsCollectionOp || child is SettingsFieldOp)
                targets(child, emptyMap())
            }.toSet()
        }
        is SettingsRestoreOp -> captured.getValue(op.payloadRef).keys
        is me.rerere.rikkihub.data.management.adapters.skills.SkillStoreOp.CascadeEnabledSkillsRename -> setOf("settings.assistants")
        else -> throw IllegalArgumentException("Unknown Settings operation")
    }

    private fun beforeKey(operationId: String) = "settings-" + sha256Hex(operationId)
}
