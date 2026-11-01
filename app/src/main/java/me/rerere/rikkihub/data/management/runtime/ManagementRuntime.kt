package me.rerere.rikkihub.data.management.runtime

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.files.BuiltinSkillPublisher
import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.rikkihub.data.management.adapters.SettingsDomainAdapter
import me.rerere.rikkihub.data.management.adapters.conversation.ConversationDomainAdapter
import me.rerere.rikkihub.data.management.adapters.skills.SkillAdapter
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetAdapter
import me.rerere.rikkihub.data.management.audit.ManagementAuditLog
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.coordinator.DomainLeaseRegistry
import me.rerere.rikkihub.data.management.coordinator.EvidenceBasedWriteGate
import me.rerere.rikkihub.data.management.coordinator.ManagementDomainAdapter
import me.rerere.rikkihub.data.management.coordinator.ManagementMutationCoordinator
import me.rerere.rikkihub.data.management.coordinator.ManagementWriteGate
import me.rerere.rikkihub.data.management.coordinator.ObjectFenceRegistry
import me.rerere.rikkihub.data.management.coordinator.PlanContext
import me.rerere.rikkihub.data.management.coordinator.WriteGateStatus
import me.rerere.rikkihub.data.management.persistence.FileBackedManagementStore
import me.rerere.rikkihub.data.management.persistence.JournalEntry
import me.rerere.rikkihub.data.management.persistence.JournalPhase
import me.rerere.rikkihub.data.management.ports.CompositeManagementReadPort
import me.rerere.rikkihub.data.management.ports.HostIdAllocator
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.management.ports.OperationFactStatus
import me.rerere.rikkihub.data.management.ports.RoomManagementBackend
import me.rerere.rikkihub.data.management.ports.RoomManagementPort
import me.rerere.rikkihub.data.management.ports.SettingsManagementPort
import me.rerere.rikkihub.data.management.ports.SettingsStoreGateway
import me.rerere.rikkihub.data.management.ports.SkillsBackend
import me.rerere.rikkihub.data.management.ports.SkillsManagementPort
import me.rerere.rikkihub.data.management.ports.StoreGateway
import me.rerere.rikkihub.data.management.ports.StoreKind
import me.rerere.rikkihub.data.management.ports.StoreOperationCodec
import me.rerere.rikkihub.data.management.proposal.CreatorProposalService
import me.rerere.rikkihub.data.management.proposal.NativeApprovalAuthority
import me.rerere.rikkihub.data.management.proposal.NativeProposalReview
import me.rerere.rikkihub.data.management.proposal.Proposal
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.management.proposal.Provenance
import me.rerere.rikkihub.data.management.recovery.PayloadCipher
import me.rerere.rikkihub.data.mutation.ConversationHostIds
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand
import me.rerere.rikkihub.data.mutation.ConversationVersion
import me.rerere.rikkihub.data.mutation.MutationActor
import me.rerere.rikkihub.data.mutation.MutationDomain
import me.rerere.rikkihub.data.mutation.MutationFencedException
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.service.ChatService
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ManagementRuntime"

/**
 * The single assembly of the management chain:
 * planner adapters → [CreatorProposalService] (frozen digest, native one-shot approval,
 * atomic claim) → [ManagementMutationCoordinator] (lease, fence, per-step receipts,
 * reconciliation) → store gateways (Settings DataStore, Room, user Skills files).
 *
 * There is no model-facing commit: only [approveAndApply] (called from the native
 * review screen with an in-process review handle) can start business writes.
 */
class ManagementRuntime(
    private val root: File,
    settingsStore: SettingsStore,
    private val mutationRuntime: MutationRuntime,
    roomBackend: RoomManagementBackend,
    skillsBackend: SkillsBackend,
    cipher: PayloadCipher,
    /** Write gate input; see [EvidenceBasedWriteGate]. */
    mutationAccepted: () -> Boolean,
    private val definitionVersion: () -> Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val store = FileBackedManagementStore(root, cipher, nowMillis)
    val fences = ObjectFenceRegistry(store, nowMillis)
    private val leases = DomainLeaseRegistry()

    val settingsPort = SettingsManagementPort(
        settingsStore = settingsStore,
        fences = { fences.fencedRefs().map { it.ref } },
    )
    val roomPort = RoomManagementPort(roomBackend, store)
    val skillsPort = SkillsManagementPort(skillsBackend, store)
    val readPort = CompositeManagementReadPort(settingsPort, roomPort, skillsPort)

    private val gateways: Map<StoreKind, StoreGateway> = mapOf(
        StoreKind.SETTINGS_DATASTORE to SettingsStoreGateway(settingsPort, store, store),
        StoreKind.MAIN_ROOM to roomPort,
        StoreKind.USER_FILES to skillsPort,
    )

    val gate: ManagementWriteGate = EvidenceBasedWriteGate(mutationAccepted = mutationAccepted)

    /** Composite storage epoch: any restore/import of any store invalidates reviews and approvals. */
    @Volatile private var epochCache: Long = 0
    private suspend fun refreshEpoch(): Long {
        val value = listOf(StoreKind.SETTINGS_DATASTORE, StoreKind.MAIN_ROOM, StoreKind.USER_FILES)
            .fold(17L) { acc, kind -> acc * 31 + gateways.getValue(kind).storageEpoch() }
        epochCache = value
        return value
    }

    val approvals = NativeApprovalAuthority({ epochCache }, definitionVersion, nowMillis)
    val proposals = CreatorProposalService(store, store, store as ManagementAuditLog, fences, approvals, gate,
        definitionVersion, nowMillis)
    private val coordinator = ManagementMutationCoordinator(gateways, store, store, leases, fences, store, gate, nowMillis)

    private val adapters: Map<ManagementDomain, ManagementDomainAdapter> = buildMap {
        listOf(ManagementDomain.QUICK_MESSAGE, ManagementDomain.MODE_INJECTION, ManagementDomain.WORLD_BOOK,
            ManagementDomain.ASSISTANT, ManagementDomain.MODEL_AND_PROMPT_DEFAULTS).forEach { put(it, SettingsDomainAdapter(it)) }
        put(ManagementDomain.SUBAGENT_PRESET, SubagentPresetAdapter())
        put(ManagementDomain.USER_SKILL, SkillAdapter())
        put(ManagementDomain.CONVERSATION, ConversationDomainAdapter())
    }

    fun adapter(domain: ManagementDomain): ManagementDomainAdapter = adapters.getValue(domain)

    private val hostIds = object : HostIdAllocator {
        override fun newObjectId(): String = Uuid.random().toString()
        override fun newEntryId(scope: String): String = Uuid.random().toString()
    }

    private val startupMutex = Mutex()
    @Volatile private var started = false

    /**
     * Startup barrier: restore durable fences, revoke every unconsumed
     * approval, turn APPLYING into NEEDS_RECOVERY, then reconcile from the owning
     * stores' receipts. Only proposals whose every step is proven applied become
     * APPLIED; everything else keeps its fences until a native review.
     */
    @Volatile private var protectionInstalled = false

    /** Synchronous part of startup: durable fences are live before any ordinary writer runs. */
    suspend fun installStartupProtection() {
        if (protectionInstalled) return
        fences.restore()
        installOrdinaryWriteAdmission()
        protectionInstalled = true
    }

    suspend fun ensureStarted() {
        if (started) return
        startupMutex.withLock {
            if (started) return
            installStartupProtection()
            refreshEpoch()
            proposals.invalidateApprovalsAfterRestart()
            for (proposal in store.listAll().filter { it.status == ProposalStatus.NEEDS_RECOVERY }) {
                runCatching { reconcileRecovered(proposal) }
                    .onFailure { Log.w(TAG, "reconcile ${proposal.proposalId} failed", it) }
            }
            started = true
        }
    }

    private suspend fun reconcileRecovered(proposal: Proposal) {
        val facts = proposal.steps.map { step ->
            val op = StoreOperationCodec.decode(step.opJson)
            gateways[op.store]?.reconcile(op, "${proposal.proposalId}/${step.stepId}")?.status ?: OperationFactStatus.UNKNOWN
        }
        if (facts.isNotEmpty() && facts.all { it == OperationFactStatus.APPLIED }) {
            store.updateProposal(proposal.proposalId) { current ->
                checkNotNull(current).copy(status = ProposalStatus.APPLIED, statusReason = "RECONCILED_FROM_RECEIPTS",
                    completedAtMillis = nowMillis())
            }
            store.append(JournalEntry(nowMillis(), nowMillis(), proposal.proposalId, JournalPhase.PROPOSAL_APPLIED,
                payload = "reconciled"))
            proposal.steps.forEach { step ->
                val op = StoreOperationCodec.decode(step.opJson)
                gateways[op.store]?.releaseParticipation("${proposal.proposalId}/${step.stepId}")
            }
            fences.release(proposal.proposalId)
        } else if (facts.isNotEmpty() && facts.all { it == OperationFactStatus.NOT_APPLIED }) {
            // Provably no business effect: terminal FAILED without effect, fences released.
            store.updateProposal(proposal.proposalId) { current ->
                checkNotNull(current).copy(status = ProposalStatus.FAILED, statusReason = "RECONCILED_NO_EFFECT",
                    completedAtMillis = nowMillis())
            }
            fences.release(proposal.proposalId)
        }
    }

    /**
     * Ordinary App/Web Settings writers cannot change an object a pending management
     * operation fenced: they would otherwise consume a half-applied dependency.
     */
    private fun installOrdinaryWriteAdmission() {
        mutationRuntime.installWriteAdmission { domain, keys, operationId, actor ->
            if (actor == MutationActor.MANAGEMENT || actor == MutationActor.MAINTENANCE) return@installWriteAdmission
            if (domain != MutationDomain.SETTINGS) return@installWriteAdmission
            val fenced = fences.liveRefs()
            for (ref in fenced) {
                val key = runCatching { SettingsManagementPort.objectKey(ref) }.getOrNull()
                val blocked = when {
                    ref.id.startsWith("collection:") -> {
                        val prefix = collectionObjectPrefix(ref.id.removePrefix("collection:"))
                        prefix != null && keys.any { it.startsWith(prefix) }
                    }
                    key != null -> key in keys
                    else -> false
                }
                if (blocked) throw MutationFencedException(domain, key ?: ref.id, operationId)
            }
        }
    }

    private fun collectionObjectPrefix(collection: String): String? = when (collection) {
        "settings.quickMessages" -> "quickMessage:"
        "settings.modeInjections" -> "modeInjection:"
        "settings.lorebooks" -> "lorebook:"
        "settings.assistants" -> "assistant:"
        "settings.assistantTags" -> "assistantTag:"
        "settings.providers" -> "provider:"
        else -> if (collection.startsWith("settings.providers.")) "model:" else null
    }

    /** Is an ordinary conversation fenced by a pending/recovering management operation? */
    fun isConversationFenced(conversationId: Uuid): Boolean =
        fences.isFencedNow(ObjectRef(ManagementDomain.CONVERSATION, conversationId.toString()))

    // ---------------------------------------------------------------- planning

    /** Plan + freeze a proposal. Pure read on the stores; nothing is written outside the Vault/management store. */
    suspend fun preview(command: ManagementCommand, source: Provenance): Proposal =
        freeze(plan(command), source)

    /** A planned, not yet stored proposal: lets a caller plan several commands and store all or none. */
    class PlannedCommand internal constructor(
        val command: ManagementCommand,
        val plan: me.rerere.rikkihub.data.management.coordinator.PlannedMutation,
    )

    /** Plans [command] on the current state. Pure read; nothing is stored. */
    suspend fun plan(command: ManagementCommand): PlannedCommand {
        ensureStarted()
        val ctx = PlanContext(readPort, nowMillis(), refreshEpoch(), hostIds)
        return PlannedCommand(command, adapter(command.domain).plan(ctx, command))
    }

    /** Stores a planned command as a PENDING_APPROVAL proposal. */
    suspend fun freeze(planned: PlannedCommand, source: Provenance): Proposal =
        proposals.previewPlan(planned.command.domain, encodeCommand(planned.command), planned.plan, source)

    private fun encodeCommand(command: ManagementCommand): String {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        return when (command) {
            is me.rerere.rikkihub.data.management.adapters.SettingsBatchCommand ->
                json.encodeToString(me.rerere.rikkihub.data.management.adapters.SettingsBatchCommand.serializer(), command)
            is me.rerere.rikkihub.data.management.adapters.SettingsDomainCommand ->
                json.encodeToString(me.rerere.rikkihub.data.management.adapters.SettingsDomainCommand.serializer(), command)
            is me.rerere.rikkihub.data.management.adapters.conversation.ConversationManagementCommand ->
                json.encodeToString(me.rerere.rikkihub.data.management.adapters.conversation.ConversationManagementCommand.serializer(), command)
            is me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetCommand ->
                json.encodeToString(me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetCommand.serializer(), command)
            is me.rerere.rikkihub.data.management.adapters.skills.SkillCommand ->
                json.encodeToString(me.rerere.rikkihub.data.management.adapters.skills.SkillCommand.serializer(), command)
            else -> throw IllegalArgumentException("Unregistered management command")
        }
    }

    /**
     * Host-produced Undo as a NEW proposal. Precondition: every after-token recorded in
     * the original receipts is still current (nothing changed the touched objects or
     * collections since); otherwise CONFLICT. Settings steps restore the encrypted
     * before-image captured by the gateway at apply time; other domains use their
     * adapter inverse. The model never supplies the restored content.
     */
    suspend fun previewUndo(applied: Proposal, source: Provenance): Proposal? {
        ensureStarted()
        require(applied.status == ProposalStatus.APPLIED)
        val receipts = applied.steps.map { it.receipt ?: return null }
        for (receipt in receipts) for (token in receipt.afterTokens) {
            val gateway = gateways[receipt.store] ?: return null
            val current = if (token.ref.id.startsWith("collection:"))
                gateway.collectionToken(token.ref.id.removePrefix("collection:")) else gateway.currentToken(token.ref)
            if (current != token.token) throw me.rerere.rikkihub.data.management.proposal.ProposalServiceError.DomainError(
                me.rerere.rikkihub.data.management.proposal.ProposalErrorCode.TOKEN_CONFLICT,
                "The changed objects were modified after this proposal was applied")
        }
        val ctx = PlanContext(readPort, nowMillis(), refreshEpoch(), hostIds)
        val settingsSteps = applied.steps.filter { StoreOperationCodec.decode(it.opJson).store == StoreKind.SETTINGS_DATASTORE }
        if (settingsSteps.size == applied.steps.size) {
            val gateway = gateways.getValue(StoreKind.SETTINGS_DATASTORE) as SettingsStoreGateway
            val restoreOps = settingsSteps.map { step ->
                val operationId = "${applied.proposalId}/${step.stepId}"
                me.rerere.rikkihub.data.management.ports.SettingsRestoreOp(gateway.beforeImageRef(operationId),
                    StoreOperationCodec.decode(step.opJson).objectRefs, "undo-settings")
            }
            val readSet = me.rerere.rikkihub.data.management.common.ReadSet(receipts.flatMap { r ->
                r.afterTokens.map { t ->
                    if (t.ref.id.startsWith("collection:"))
                        me.rerere.rikkihub.data.management.common.ReadSetEntry.Collection(t.ref.id.removePrefix("collection:"), t.token)
                    else me.rerere.rikkihub.data.management.common.ReadSetEntry.Object(t.ref, t.token)
                }
            }.distinct())
            val diff = restoreOps.flatMap { op ->
                val snapshot = gateway.loadSnapshot(op.payloadRef)
                undoDiff(settingsPort.settingsSnapshot(), snapshot)
            }
            val plan = me.rerere.rikkihub.data.management.coordinator.PlannedMutation(readSet, restoreOps, diff,
                me.rerere.rikkihub.data.management.common.RiskLevel.HIGH,
                me.rerere.rikkihub.data.management.common.RetentionNote(recycleDays = 30),
                applied.affectedRefs, true, emptyMap())
            val command = kotlinx.serialization.json.buildJsonObject {
                put("undoOf", kotlinx.serialization.json.JsonPrimitive(applied.proposalId))
            }.toString()
            return proposals.previewPlan(applied.commandDomain, command, plan, source)
        }
        val adapter = adapter(applied.commandDomain)
        val original = runCatching { adapter.decodeCommand(applied.commandJson) }.getOrNull() ?: return null
        val inverse = adapter.inverse(ctx, original) ?: return null
        return preview(inverse, source)
    }

    private fun undoDiff(current: me.rerere.rikkihub.data.datastore.Settings,
                         snapshot: kotlinx.serialization.json.JsonObject): List<me.rerere.rikkihub.data.management.common.DiffLine> {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val raw = json.encodeToJsonElement(me.rerere.rikkihub.data.datastore.Settings.serializer(), current)
            as kotlinx.serialization.json.JsonObject
        val keyOf = mapOf("settings.quickMessages" to "quickMessages", "settings.modeInjections" to "modeInjections",
            "settings.lorebooks" to "lorebooks", "settings.assistants" to "assistants", "settings.assistantTags" to "assistantTags")
        return snapshot.flatMap { (target, value) ->
            val key = keyOf[target]
            if (key == null) listOf(me.rerere.rikkihub.data.management.common.DiffLine(target, "current", "restore previous value"))
            else {
                fun label(o: kotlinx.serialization.json.JsonElement) = (o as? kotlinx.serialization.json.JsonObject)?.let { obj ->
                    listOf("name", "title").firstNotNullOfOrNull { (obj[it] as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
                } ?: ""
                fun byId(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonArray).orEmpty()
                    .associateBy { ((it as kotlinx.serialization.json.JsonObject)["id"] as kotlinx.serialization.json.JsonPrimitive).content }
                val now = byId(raw[key]); val then = byId(value)
                (now.keys + then.keys).mapNotNull { id ->
                    when {
                        id !in then -> me.rerere.rikkihub.data.management.common.DiffLine("$target[$id]", label(now.getValue(id)), null)
                        id !in now -> me.rerere.rikkihub.data.management.common.DiffLine("$target[$id]", null, label(then.getValue(id)))
                        now[id] != then[id] -> me.rerere.rikkihub.data.management.common.DiffLine("$target[$id]", label(now.getValue(id)), label(then.getValue(id)) + " (previous version)")
                        else -> null
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- native approval

    /** Native screen loads the frozen proposal and gets an in-process, single-use review handle. */
    suspend fun review(proposalId: String): Pair<Proposal, NativeProposalReview> {
        ensureStarted()
        refreshEpoch()
        return proposals.nativeReview(proposalId)
    }

    /** The only business-write entry: exact native approval → atomic claim → coordinator. */
    suspend fun approveAndApply(review: NativeProposalReview): Proposal {
        ensureStarted()
        refreshEpoch()
        val permit = proposals.approveNative(review)
        val claimed = proposals.claimApprovedForApply(permit)
        return coordinator.applyClaimed(claimed.proposalId, adapter(claimed.commandDomain))
    }

    /**
     * Native "approve all": every pending proposal of [sessionId], oldest first, each through
     * the same review → approve → claim → apply path. Proposals planned from the same base
     * conflict once a sibling changed their collection; when every object such a proposal
     * read is still unchanged, its command is re-planned on the current state and applied
     * (object-level conflicts stay CONFLICT for manual review). A failure does not stop the rest.
     */
    suspend fun approveAllAndApply(sessionId: String): List<Result<Proposal>> {
        val pending = list()
            .filter { it.sessionId == sessionId && it.status == ProposalStatus.PENDING_APPROVAL }
            .sortedBy { it.createdAtMillis }
        return pending.map { proposal ->
            runCatching {
                val applied = approveAndApply(review(proposal.proposalId).second)
                if (applied.status == ProposalStatus.CONFLICT && readObjectsUnchanged(proposal)) {
                    rebaseAndApply(proposal) ?: applied
                } else applied
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    /** True when only collection revisions moved since planning: every read object is at its planned version. */
    private suspend fun readObjectsUnchanged(proposal: Proposal): Boolean =
        proposal.readSet.entries
            .filterIsInstance<me.rerere.rikkihub.data.management.common.ReadSetEntry.Object>()
            .all { entry ->
                gateways[me.rerere.rikkihub.data.management.ports.StoreRouting.forObject(entry.ref)]
                    ?.currentToken(entry.ref) == entry.expected
            }

    /** Re-plans [original]'s command against the current state and applies it; null when it cannot be re-planned. */
    private suspend fun rebaseAndApply(original: Proposal): Proposal? {
        val command = runCatching { adapter(original.commandDomain).decodeCommand(original.commandJson) }
            .getOrNull() ?: return null
        val source = Provenance(original.sessionId, original.runId ?: return null, original.callBindingId ?: return null)
        val rebased = preview(command, source)
        return approveAndApply(review(rebased.proposalId).second)
    }

    suspend fun reject(proposalId: String): Proposal {
        val proposal = proposals.get(proposalId)
        return proposals.rejectNative(proposalId, proposal.canonicalDigest)
    }

    suspend fun cancel(proposalId: String): Proposal {
        val proposal = proposals.get(proposalId)
        return proposals.cancel(proposalId, proposal.sessionId)
    }

    suspend fun list(): List<Proposal> {
        ensureStarted()
        return store.listAll().sortedByDescending { it.createdAtMillis }
    }

    fun writeGateStatus(): WriteGateStatus = gate.status()
}

/** Production Room backend: repositories + ChatService command channel. */
class AppRoomManagementBackend(
    private val chatService: () -> ChatService,
    private val conversations: ConversationRepository,
    private val subagents: SubagentPresetRepository,
    private val workspaces: WorkspaceRepository,
    private val mutationRuntime: MutationRuntime,
) : RoomManagementBackend {
    override suspend fun epoch(): Long = mutationRuntime.epochs.epoch(MutationDomain.ROOM_CONVERSATION)
    override suspend fun conversationRow(id: Uuid): Conversation? = conversations.managedRow(id)
    override suspend fun conversationTree(id: Uuid): Conversation? = conversations.managedTree(id)
    override suspend fun isDeleted(id: Uuid): Boolean = conversations.isSoftDeleted(id)
    override fun busyReason(id: Uuid): String? = chatService().managementBusyReason(id)
    override suspend fun referenceRows(): List<RoomManagementBackend.ReferenceRow> =
        conversations.referenceRows().map { RoomManagementBackend.ReferenceRow(it.id, it.modeInjectionIds, it.lorebookIds, it.revision, it.generation) }

    override suspend fun applyConversationCommand(
        id: Uuid, expected: ConversationVersion?, operationId: String,
        command: ConversationMutationCommand, hostIds: ConversationHostIds,
    ): ConversationVersion = chatService().applyConversationCommand(id, expected, operationId, command, hostIds)

    override suspend fun receiptAfter(operationId: String): String? = conversations.operationReceipt(operationId)?.afterToken
    override suspend fun writeReceipt(operationId: String, objectKey: String, after: String) =
        conversations.writeManagementReceipt(operationId, objectKey, after)

    override suspend fun library(): List<WorkspaceSubagentPreset> =
        subagents.list().presets ?: throw IllegalStateException("Preset library is corrupt")
    override suspend fun libraryRevision(): Long = subagents.libraryRevision()
    override suspend fun workspaces(): List<RoomManagementBackend.WorkspaceRow> =
        workspaces.list().map { RoomManagementBackend.WorkspaceRow(it.id, it.name, it.subagentPresets) }
    override suspend fun libraryCreate(preset: WorkspaceSubagentPreset, workspaceIds: Set<String>) {
        subagents.create(preset.copy(libraryPresetId = null), workspaceIds, assignedId = preset.id)
    }
    override suspend fun libraryUpdate(preset: WorkspaceSubagentPreset, expectedVersion: Long, workspaceIds: Set<String>?) {
        subagents.update(preset, expectedVersion, workspaceIds)
    }
    override suspend fun libraryDelete(presetId: String, expectedVersion: Long) { subagents.delete(presetId, expectedVersion) }
    override suspend fun libraryReorder(orderedIds: List<String>) { subagents.reorder(orderedIds) }
    override suspend fun localCreate(workspaceId: String, preset: WorkspaceSubagentPreset) {
        workspaces.createSubagentPreset(workspaceId, preset.copy(libraryPresetId = null), assignedId = preset.id)
    }
    override suspend fun localUpdate(workspaceId: String, preset: WorkspaceSubagentPreset, expectedVersion: Long) {
        workspaces.updateSubagentPreset(workspaceId, preset, expectedVersion)
    }
    override suspend fun localDelete(workspaceId: String, presetId: String, expectedVersion: Long) {
        workspaces.deleteSubagentPreset(workspaceId, presetId, expectedVersion)
    }
    override suspend fun localReorder(workspaceId: String, orderedIds: List<String>, expectedVersions: Map<String, Long>) {
        workspaces.reorderSubagentPresets(workspaceId, orderedIds, expectedVersions)
    }
}

/** Production Skills backend over the app's user/builtin skill roots. */
class AppSkillsBackend(
    private val filesRoot: File,
    private val receiptsDir: File,
    private val mutationRuntime: MutationRuntime,
) : SkillsBackend {
    private val root = filesRoot.canonicalFile
    override val userRoot: File = File(root, me.rerere.rikkihub.data.files.FileFolders.SKILLS)
    override val builtinRoot: File = File(root, me.rerere.rikkihub.data.files.FileFolders.BUILTIN_SKILLS)
    override suspend fun epoch(): Long = mutationRuntime.epochs.epoch(MutationDomain.SKILLS_FILES)
    override fun <T> locked(block: () -> T): T = BuiltinSkillPublisher.locked(root, block)
    override fun skillName(dir: File): String? {
        val file = File(dir, "SKILL.md")
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return null
        return (SkillFrontmatterParser.parse(file.readText())["name"] as? String)?.takeIf { it.isNotBlank() }
    }
    private fun receiptFile(operationId: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(operationId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(receiptsDir, "$digest.json")
    }
    override suspend fun receipt(operationId: String): String? =
        receiptFile(operationId).takeIf { it.isFile }?.readText()
    override suspend fun writeReceipt(operationId: String, json: String) {
        receiptsDir.mkdirs()
        val target = receiptFile(operationId)
        val temp = File.createTempFile(".receipt-", ".tmp", receiptsDir)
        temp.outputStream().use { it.write(json.toByteArray()); it.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
