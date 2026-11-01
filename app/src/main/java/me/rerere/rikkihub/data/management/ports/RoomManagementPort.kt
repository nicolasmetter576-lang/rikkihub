package me.rerere.rikkihub.data.management.ports

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.creator.session.CreatorCanonicalizer
import me.rerere.rikkihub.data.management.adapters.conversation.ConversationCollections
import me.rerere.rikkihub.data.management.adapters.conversation.conversationNodeJson
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentCollections
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetStoreOp
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.VersionToken
import me.rerere.rikkihub.data.management.persistence.ParticipationStore
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.mutation.ConversationHostIds
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand
import me.rerere.rikkihub.data.mutation.ConversationVersion
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Facts and typed writes the Room gateway needs. Production wraps the repositories/ChatService. */
interface RoomManagementBackend {
    suspend fun epoch(): Long

    // conversations
    suspend fun conversationRow(id: Uuid): Conversation?
    suspend fun conversationTree(id: Uuid): Conversation?
    suspend fun isDeleted(id: Uuid): Boolean
    fun busyReason(id: Uuid): String?
    suspend fun referenceRows(): List<ReferenceRow>
    suspend fun applyConversationCommand(
        id: Uuid, expected: ConversationVersion?, operationId: String,
        command: ConversationMutationCommand, hostIds: ConversationHostIds,
    ): ConversationVersion

    // receipts (operation_receipts table)
    suspend fun receiptAfter(operationId: String): String?
    suspend fun writeReceipt(operationId: String, objectKey: String, after: String)

    // subagent presets
    suspend fun library(): List<WorkspaceSubagentPreset>
    suspend fun libraryRevision(): Long
    suspend fun workspaces(): List<WorkspaceRow>
    suspend fun libraryCreate(preset: WorkspaceSubagentPreset, workspaceIds: Set<String>)
    suspend fun libraryUpdate(preset: WorkspaceSubagentPreset, expectedVersion: Long, workspaceIds: Set<String>?)
    suspend fun libraryDelete(presetId: String, expectedVersion: Long)
    suspend fun libraryReorder(orderedIds: List<String>)
    suspend fun localCreate(workspaceId: String, preset: WorkspaceSubagentPreset)
    suspend fun localUpdate(workspaceId: String, preset: WorkspaceSubagentPreset, expectedVersion: Long)
    suspend fun localDelete(workspaceId: String, presetId: String, expectedVersion: Long)
    suspend fun localReorder(workspaceId: String, orderedIds: List<String>, expectedVersions: Map<String, Long>)

    data class ReferenceRow(val id: String, val modeInjectionIds: String, val lorebookIds: String,
        val revision: Long, val generation: Long)

    data class WorkspaceRow(val id: String, val name: String, val subagentPresets: String)
}

/**
 * Room side of management: the read projection used at plan time AND the
 * MAIN_ROOM gateway used at apply time share one token function, so a read-set
 * baseline and the pre-commit verification can never disagree.
 *
 * Token conventions:
 * - conversation `ObjectRef(CONVERSATION, id)` → (epoch, generation, revision);
 * - `room.conversations[].lorebookIds|modeInjectionIds` → predicate digest over all active rows;
 * - `room.subagentPresetLibrary` → (epoch, 0, library CAS revision);
 * - `library:<id>` / `room.subagentLibrary` object → (epoch, 0, preset.version);
 * - `room.workspacePresets.<ws>` / `workspace:<ws>` → (epoch, 0, digest of the presets column);
 * - `workspace:<ws>:<preset>` → (epoch, 0, preset.version).
 */
class RoomManagementPort(
    private val backend: RoomManagementBackend,
    private val participation: ParticipationStore,
) : StoreGateway {
    override val store = StoreKind.MAIN_ROOM
    private val mutex = Mutex()

    // ---------------------------------------------------------------- read projection

    suspend fun snapshotIds(collectionId: String): List<String> = when (collectionId) {
        ConversationCollections.ROWS -> backend.referenceRows().map { it.id }
        SubagentCollections.LIBRARY -> backend.library().sortedBy { it.sortOrder }.map { it.id }
        "room.workspaces" -> backend.workspaces().map { it.id }
        else -> throw IllegalArgumentException("Unknown Room collection")
    }

    suspend fun readObjectJson(collectionId: String, id: String): JsonObject? = when (collectionId) {
        ConversationCollections.ROWS -> parseUuid(id)?.let { backend.conversationRow(it) }?.let { JsonObject(rowJson(it, null)) }
        ConversationCollections.TREE -> parseUuid(id)?.let { uuid ->
            backend.conversationTree(uuid)?.let { rowJson(it, backend.busyReason(uuid)) + ("nodes" to treeJson(it)) }
                ?.let(::JsonObject)
        }
        "room.subagentLibrary" -> backend.library().firstOrNull { it.id == id }
            ?.let { JsonInstant.encodeToJsonElement(WorkspaceSubagentPreset.serializer(), it) as JsonObject }
        "room.workspaces" -> backend.workspaces().firstOrNull { it.id == id }?.let {
            JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name),
                "subagentPresets" to JsonPrimitive(it.subagentPresets)))
        }
        else -> throw IllegalArgumentException("Unknown Room collection")
    }

    suspend fun objectToken(collectionId: String, id: String): VersionToken? = when (collectionId) {
        ConversationCollections.ROWS, ConversationCollections.TREE -> currentToken(ObjectRef(ManagementDomain.CONVERSATION, id))
        "room.subagentLibrary" -> currentToken(ObjectRef(ManagementDomain.SUBAGENT_PRESET, "library:$id"))
        "room.workspacePresets" -> currentToken(ObjectRef(ManagementDomain.SUBAGENT_PRESET, "workspace:$id"))
        else -> throw IllegalArgumentException("Unknown Room collection")
    }

    private fun rowJson(c: Conversation, busy: String?): Map<String, kotlinx.serialization.json.JsonElement> = mapOf(
        "id" to JsonPrimitive(c.id.toString()),
        "assistantId" to JsonPrimitive(c.assistantId.toString()),
        "title" to JsonPrimitive(c.title),
        "isPinned" to JsonPrimitive(c.isPinned),
        "folderId" to (c.folderId?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        "customSystemPrompt" to (c.customSystemPrompt?.let(::JsonPrimitive) ?: JsonNull),
        "workspaceCwd" to (c.workspaceCwd?.let(::JsonPrimitive) ?: JsonNull),
        "modeInjectionIds" to JsonArray(c.modeInjectionIds.map { JsonPrimitive(it.toString()) }.sortedBy { it.content }),
        "lorebookIds" to JsonArray(c.lorebookIds.map { JsonPrimitive(it.toString()) }.sortedBy { it.content }),
        "deleted" to JsonPrimitive(c.deletedAt != null),
        "busy" to (busy?.let(::JsonPrimitive) ?: JsonNull),
    )

    /** Plain text only; tool/server-tool/media candidates are visible as non-text and never editable. */
    private fun treeJson(c: Conversation) = JsonArray(c.messageNodes.map { node ->
        conversationNodeJson(node.id.toString(), node.selectIndex, node.messages.map { message ->
            val plain = message.parts.all { it is UIMessagePart.Text }
            Triple(message.id.toString(), message.role.name,
                if (plain) message.parts.joinToString("\n") { (it as UIMessagePart.Text).text } else null)
        })
    })

    // ---------------------------------------------------------------- tokens

    override suspend fun storageEpoch(): Long = backend.epoch()

    override suspend fun currentToken(ref: ObjectRef): VersionToken? {
        val epoch = backend.epoch()
        return when (ref.domain) {
            ManagementDomain.CONVERSATION -> parseUuid(ref.id)?.let { id ->
                backend.conversationRow(id)?.let { VersionToken(epoch, it.generation, it.revision) }
            }
            ManagementDomain.SUBAGENT_PRESET -> when {
                ref.id == "library" -> VersionToken(epoch, 0, backend.libraryRevision())
                ref.id.startsWith("library:") ->
                    backend.library().firstOrNull { it.id == ref.id.removePrefix("library:") }
                        ?.let { VersionToken(epoch, 0, it.version) }
                ref.id.startsWith("workspace:") -> {
                    val parts = ref.id.removePrefix("workspace:").split(':')
                    val row = backend.workspaces().firstOrNull { it.id == parts[0] } ?: return null
                    if (parts.size == 1) VersionToken(epoch, 0, digest64(row.subagentPresets))
                    else WorkspaceSubagentPresets.tryParse(row.subagentPresets)
                        ?.firstOrNull { it.id == parts[1] }?.let { VersionToken(epoch, 0, it.version) }
                }
                else -> null
            }
            else -> throw IllegalArgumentException("Object does not belong to Room")
        }
    }

    override suspend fun collectionToken(collectionId: String): VersionToken {
        val epoch = backend.epoch()
        return when {
            collectionId == SubagentCollections.LIBRARY -> VersionToken(epoch, 0, backend.libraryRevision())
            collectionId.startsWith("room.workspacePresets.") -> {
                val ws = collectionId.removePrefix("room.workspacePresets.")
                VersionToken(epoch, 0, backend.workspaces().firstOrNull { it.id == ws }?.let { digest64(it.subagentPresets) } ?: 0)
            }
            collectionId == "room.conversations[].lorebookIds" ->
                VersionToken(epoch, 0, digest64(backend.referenceRows().joinToString("\n") { "${it.id}|${it.lorebookIds}" }))
            collectionId == "room.conversations[].modeInjectionIds" ->
                VersionToken(epoch, 0, digest64(backend.referenceRows().joinToString("\n") { "${it.id}|${it.modeInjectionIds}" }))
            collectionId == ConversationCollections.ROWS ->
                VersionToken(epoch, 0, digest64(backend.referenceRows().joinToString("\n") { "${it.id}|${it.generation}" }))
            else -> throw IllegalArgumentException("Unknown Room collection")
        }
    }

    // ---------------------------------------------------------------- gateway

    override suspend fun apply(op: StoreOperation, operationId: String, expected: List<ReadSetEntry>): StoreReceipt =
        mutex.withLock {
            require(op.store == store)
            val fingerprint = fingerprint(op)
            backend.receiptAfter(receiptId(operationId))?.let { stored ->
                val receipt = JsonInstant.decodeFromString(StoreReceipt.serializer(), stored)
                require(receipt.operationFingerprint == fingerprint) { "Operation ID changed payload" }
                return@withLock receipt
            }
            // The coordinator verified the read-set inside the admission section; re-check our own
            // entries right before writing so a write that raced in is a conflict, not an overwrite.
            for (entry in expected) when (entry) {
                is ReadSetEntry.Object -> if (currentToken(entry.ref) != entry.expected)
                    throw ConcurrencyConflict(entry.ref, null, entry.expected, currentToken(entry.ref))
                is ReadSetEntry.Collection -> if (collectionToken(entry.collectionId) != entry.expected)
                    throw ConcurrencyConflict(null, entry.collectionId, entry.expected, collectionToken(entry.collectionId))
            }
            try {
                execute(op, operationId)
            } catch (e: MutationConflictException) {
                throw ConcurrencyConflict(op.objectRefs.firstOrNull(), null, null, null)
            } catch (e: me.rerere.rikkihub.data.ai.tools.SubagentPresetConflictException) {
                throw ConcurrencyConflict(op.objectRefs.firstOrNull(), null, null, null)
            }
            val after = op.objectRefs.mapNotNull { ref -> currentToken(ref)?.let { TokenRef(ref, it) } }
            val receipt = StoreReceipt(operationId, store, System.currentTimeMillis(), after,
                operationFingerprint = fingerprint, storageEpoch = backend.epoch())
            backend.writeReceipt(receiptId(operationId), op.objectRefs.joinToString(",") { it.toString() },
                JsonInstant.encodeToString(StoreReceipt.serializer(), receipt))
            receipt
        }

    private suspend fun execute(op: StoreOperation, operationId: String) {
        when (op) {
            is ConversationCommandOp -> {
                val expectedVersion = op.expected?.let { ConversationVersion(0, it.generation, it.revision) }
                backend.applyConversationCommand(Uuid.parse(op.conversationId), expectedVersion,
                    operationId, op.command, op.hostIds)
            }
            is ConversationRefStripOp -> {
                val removed = op.removedId
                val targets = when (val scope = op.scope) {
                    is RefScope.ByIds -> scope.ids
                    RefScope.AllOrdinary -> backend.referenceRows().map { it.id }
                }
                for ((index, id) in targets.sorted().withIndex()) {
                    val row = backend.conversationRow(Uuid.parse(id)) ?: continue
                    if (row.deletedAt != null) continue
                    val patch = when (op.field) {
                        RefField.LOREBOOK_IDS -> {
                            if (row.lorebookIds.none { it.toString() == removed }) continue
                            ConversationMutationCommand.PatchMetadata(
                                lorebookIds = row.lorebookIds.map { it.toString() }.filterNot { it == removed }.toSet())
                        }
                        RefField.MODE_INJECTION_IDS -> {
                            if (row.modeInjectionIds.none { it.toString() == removed }) continue
                            ConversationMutationCommand.PatchMetadata(
                                modeInjectionIds = row.modeInjectionIds.map { it.toString() }.filterNot { it == removed }.toSet())
                        }
                    }
                    backend.applyConversationCommand(row.id, ConversationVersion(0, row.generation, row.revision),
                        "$operationId#$index", patch, ConversationHostIds())
                }
            }
            is SubagentPresetStoreOp -> executeSubagent(op)
            else -> throw IllegalArgumentException("Unknown Room operation")
        }
    }

    private suspend fun executeSubagent(op: SubagentPresetStoreOp) {
        when (op) {
            is SubagentPresetStoreOp.LibraryCreate -> backend.libraryCreate(op.preset, op.workspaceIds)
            is SubagentPresetStoreOp.LibraryRestore -> backend.libraryCreate(op.preset, op.workspaceIds)
            is SubagentPresetStoreOp.LibraryPatch -> backend.libraryUpdate(op.preset, op.expectedVersion, null)
            is SubagentPresetStoreOp.LibraryDelete -> {
                val current = backend.library().firstOrNull { it.id == op.presetId }
                    ?: throw MutationConflictException("Shared preset already removed")
                backend.libraryDelete(op.presetId, current.version)
            }
            is SubagentPresetStoreOp.LibraryReorder -> backend.libraryReorder(op.orderedPresetIds)
            is SubagentPresetStoreOp.LibrarySetScope -> {
                val current = backend.library().firstOrNull { it.id == op.presetId }
                    ?: throw MutationConflictException("Shared preset unavailable")
                val linked = backend.workspaces().filter { row ->
                    WorkspaceSubagentPresets.tryParse(row.subagentPresets).orEmpty().any { it.libraryPresetId == op.presetId }
                }.map { it.id }.toSet()
                backend.libraryUpdate(current, current.version, linked + op.attachWorkspaceIds - op.detachWorkspaceIds)
            }
            is SubagentPresetStoreOp.LocalUpsert -> {
                val row = backend.workspaces().firstOrNull { it.id == op.workspaceId }
                    ?: throw MutationConflictException("Workspace unavailable")
                val existing = WorkspaceSubagentPresets.tryParse(row.subagentPresets)?.firstOrNull { it.id == op.preset.id }
                if (existing == null) backend.localCreate(op.workspaceId, op.preset)
                else backend.localUpdate(op.workspaceId, op.preset, existing.version)
            }
            is SubagentPresetStoreOp.LocalDelete -> {
                val row = backend.workspaces().firstOrNull { it.id == op.workspaceId }
                    ?: throw MutationConflictException("Workspace unavailable")
                val existing = WorkspaceSubagentPresets.tryParse(row.subagentPresets)?.firstOrNull { it.id == op.presetId }
                    ?: throw MutationConflictException("Preset already removed")
                backend.localDelete(op.workspaceId, op.presetId, existing.version)
            }
            is SubagentPresetStoreOp.LocalReorder -> {
                val row = backend.workspaces().firstOrNull { it.id == op.workspaceId }
                    ?: throw MutationConflictException("Workspace unavailable")
                val current = WorkspaceSubagentPresets.tryParse(row.subagentPresets)
                    ?: throw MutationConflictException("Workspace presets are corrupt")
                backend.localReorder(op.workspaceId, op.orderedPresetIds, current.associate { it.id to it.version })
            }
        }
    }

    /**
     * Facts from Room, never a self-reported result: a management receipt row, or for a
     * conversation command the business receipt written in the same transaction.
     */
    override suspend fun reconcile(op: StoreOperation, operationId: String): OperationFact {
        backend.receiptAfter(receiptId(operationId))?.let { stored ->
            val receipt = JsonInstant.decodeFromString(StoreReceipt.serializer(), stored)
            return if (receipt.operationFingerprint == fingerprint(op)) OperationFact(OperationFactStatus.APPLIED, receipt)
            else OperationFact(OperationFactStatus.UNKNOWN)
        }
        if (op is ConversationCommandOp) {
            val after = backend.receiptAfter(operationId)
                ?: return OperationFact(OperationFactStatus.NOT_APPLIED) // receipt is atomic with the row
            val (generation, revision) = after.split(':').map { it.toLong() }
            val receipt = StoreReceipt(operationId, store, System.currentTimeMillis(),
                op.objectRefs.map { TokenRef(it, VersionToken(backend.epoch(), generation, revision)) },
                operationFingerprint = fingerprint(op), storageEpoch = backend.epoch())
            return OperationFact(OperationFactStatus.APPLIED, receipt)
        }
        // Multi-row/derived Room writes without a management receipt: the outcome cannot be
        // proven either way from a missing row, so recovery must review it natively.
        return OperationFact(OperationFactStatus.UNKNOWN)
    }

    /** No automatic inverse writes for Room steps: recovery re-plans natively from current facts. */
    override suspend fun compensate(op: StoreOperation, operationId: String, afterTokens: List<TokenRef>): CompensationResult =
        CompensationResult(CompensationStatus.FAILED, reason = "Room steps require a reviewed recovery proposal")

    override suspend fun writeParticipation(record: ParticipationRecord) {
        require(record.store == store)
        participation.write(store, record)
    }
    override suspend fun readParticipation(operationId: String): ParticipationRecord? = participation.read(store, operationId)
    override suspend fun pendingParticipations(): List<ParticipationRecord> = participation.pending().filter { it.store == store }
    override suspend fun releaseParticipation(operationId: String) {
        val record = readParticipation(operationId) ?: return
        participation.write(store, record.copy(phase = ParticipationPhase.RELEASED, fenceHeld = false,
            updatedAtMillis = System.currentTimeMillis()))
    }

    private fun receiptId(operationId: String) = "mgmt:$operationId"

    companion object {
        fun fingerprint(op: StoreOperation): String = CreatorCanonicalizer.canonicalDigest(
            kotlinx.serialization.json.Json.parseToJsonElement(StoreOperationCodec.encode(op)))

        fun digest64(text: String): Long {
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[i].toLong() and 0xff)
            return value and Long.MAX_VALUE
        }

        private fun parseUuid(id: String): Uuid? = runCatching { Uuid.parse(id) }.getOrNull()
    }
}
