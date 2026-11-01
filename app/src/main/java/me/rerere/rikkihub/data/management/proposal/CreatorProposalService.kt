package me.rerere.rikkihub.data.management.proposal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkihub.data.creator.session.CreatorCanonicalizer
import me.rerere.rikkihub.data.management.audit.*
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.management.coordinator.*
import me.rerere.rikkihub.data.management.persistence.*
import me.rerere.rikkihub.data.management.ports.StoreOperationCodec
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Frozen structured plan -> one-use native approval -> atomic claim. No model commit API. */
class CreatorProposalService(
    private val store: ManagementProposalStore,
    private val journal: ManagementJournal,
    private val audit: ManagementAuditLog,
    private val fences: ObjectFenceRegistry,
    private val approvalAuthority: NativeApprovalAuthority,
    private val gate: ManagementWriteGate,
    private val definitionVersion: () -> Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val sequence = AtomicLong(0)
    private val sequenceMutex = Mutex()
    @Volatile private var sequenceInitialized = false

    suspend fun previewPlan(
        domain: ManagementDomain,
        commandJson: String,
        plan: PlannedMutation,
        source: Provenance,
        expiresInMillis: Long = DEFAULT_PROPOSAL_TTL_MILLIS,
    ): Proposal {
        require(source.sessionId.isNotBlank() && source.runId.isNotBlank() && source.callBindingId.isNotBlank())
        require(plan.steps.size in 1..64 && plan.diffSummary.size <= 4096)
        val steps = plan.steps.mapIndexed { index, operation ->
            PersistedStep("step-$index", StoreOperationCodec.encode(operation))
        }
        val readSetFingerprint = CreatorCanonicalizer.canonicalDigest(json.encodeToJsonElement(plan.readSet))
        val now = nowMillis()
        val proposal = Proposal(
            proposalId = UUID.randomUUID().toString(), sessionId = source.sessionId,
            runId = source.runId, callBindingId = source.callBindingId, definitionVersion = definitionVersion(),
            commandJson = commandJson, commandDomain = domain, hostAssignedIds = plan.hostAssignedIds,
            readSet = plan.readSet, readSetFingerprint = readSetFingerprint, steps = steps,
            diffSummary = plan.diffSummary, risk = plan.risk, retention = plan.retention,
            canonicalDigest = "", status = ProposalStatus.PENDING_APPROVAL,
            requiresEnhancedConfirm = plan.requiresEnhancedConfirm || plan.risk == RiskLevel.HIGH,
            createdAtMillis = now, expiresAtMillis = now + expiresInMillis.coerceIn(1, 86_400_000),
            affectedRefs = (plan.cascadeTargets + plan.steps.flatMap { it.objectRefs } +
                plan.readSet.entries.filterIsInstance<ReadSetEntry.Object>().map { it.ref }).distinct(),
        ).let { it.copy(canonicalDigest = digestOf(it)) }
        require(proposal.affectedRefs.size <= 256)
        require(json.encodeToString(proposal).toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        store.put(proposal)
        audit.append(event(proposal, AuditKind.PROPOSAL_CREATED))
        return proposal
    }

    /** The ViewModel shows this exact frozen proposal before making a review handle. */
    suspend fun nativeReview(proposalId: String): Pair<Proposal, NativeProposalReview> {
        val proposal = get(proposalId)
        verifyDigest(proposal)
        return proposal to approvalAuthority.review(proposal)
    }

    suspend fun approveNative(review: NativeProposalReview): NativeProposalApproval {
        val permit = approvalAuthority.approve(review)
        store.updateProposal(review.proposalId) { current ->
            val proposal = current ?: throw ProposalServiceError.UnknownProposal(review.proposalId)
            verifyDigest(proposal)
            if (proposal.status != ProposalStatus.PENDING_APPROVAL)
                throw ProposalServiceError.NotApplicableState(proposal.proposalId, proposal.status)
            if (proposal.canonicalDigest != review.digest || proposal.readSetFingerprint != review.readSetFingerprint)
                throw ProposalServiceError.ApprovalDigestMismatch(proposal.proposalId)
            ProposalStateMachine.require(proposal.status, ProposalStatus.APPROVED)
            proposal.copy(status = ProposalStatus.APPROVED, approval = ProposalApproval(
                review.digest, review.readSetFingerprint, review.storageEpoch, review.definitionVersion,
                nowMillis(), permit.expiresAtMillis, permit.generation))
        }
        audit.append(event(get(review.proposalId), AuditKind.APPROVED))
        return permit
    }

    suspend fun claimApprovedForApply(permit: NativeProposalApproval): Proposal {
        val gateStatus = gate.status()
        if (gateStatus is WriteGateStatus.Disabled)
            throw ProposalServiceError.WriteGateClosed(gateStatus.reasons.map { it.name })
        // Durable fences precede the claim. Failure can strand a fence, but cannot
        // expose a half-applied object or let a consumed approval silently run.
        val snapshot = get(permit.proposalId)
        verifyDigest(snapshot)
        approvalAuthority.validate(permit, snapshot)
        fences.install(snapshot.proposalId, snapshot.affectedRefs)
        val claimed = store.updateProposal(permit.proposalId) { current ->
            val proposal = current ?: throw ProposalServiceError.UnknownProposal(permit.proposalId)
            verifyDigest(proposal)
            if (proposal.status != ProposalStatus.APPROVED)
                throw ProposalServiceError.NotApplicableState(proposal.proposalId, proposal.status)
            approvalAuthority.consume(permit, proposal)
            ProposalStateMachine.require(proposal.status, ProposalStatus.APPLYING)
            proposal.copy(status = ProposalStatus.APPLYING, applyStartedAtMillis = nowMillis())
        }
        appendJournal(claimed, JournalPhase.PROPOSAL_CLAIMED)
        audit.append(event(claimed, AuditKind.APPLY_CLAIMED))
        return claimed
    }

    suspend fun rejectNative(proposalId: String, expectedDigest: String): Proposal =
        store.updateProposal(proposalId) { current ->
            val proposal = current ?: throw ProposalServiceError.UnknownProposal(proposalId)
            require(proposal.canonicalDigest == expectedDigest)
            ProposalStateMachine.require(proposal.status, ProposalStatus.REJECTED)
            proposal.copy(status = ProposalStatus.REJECTED, completedAtMillis = nowMillis())
        }.also { audit.append(event(it, AuditKind.REJECTED)) }

    /** Stopping generation and cancelling business effects are intentionally separate. */
    suspend fun cancel(proposalId: String, sessionId: String): Proposal = store.updateProposal(proposalId) { current ->
        val proposal = current ?: throw ProposalServiceError.UnknownProposal(proposalId)
        require(proposal.sessionId == sessionId)
        if (proposal.status == ProposalStatus.APPLYING) proposal.copy(cancelRequested = true)
        else {
            ProposalStateMachine.require(proposal.status, ProposalStatus.CANCELLED)
            proposal.copy(status = ProposalStatus.CANCELLED, approval = null, completedAtMillis = nowMillis())
        }
    }

    suspend fun status(proposalId: String, sessionId: String): ProposalStatusView {
        val proposal = get(proposalId)
        require(proposal.sessionId == sessionId)
        val entries = journal.entriesForProposal(proposalId)
        return ProposalStatusView(proposalId, proposal.status, proposal.statusReason, null,
            proposal.status in setOf(ProposalStatus.PENDING_APPROVAL, ProposalStatus.APPROVED, ProposalStatus.APPLYING),
            proposal.status in setOf(ProposalStatus.CONFLICT, ProposalStatus.EXPIRED, ProposalStatus.NEEDS_RECOVERY),
            entries.filter { it.phase == JournalPhase.STEP_DONE && it.stepId != null && it.store != null }.map {
                ResultReference(it.operationId.orEmpty(), it.stepId!!, it.store!!, it.operationId.orEmpty(), it.objectRefs)
            }, proposal.readSetFingerprint)
    }

    /** Startup NEVER turns persisted APPROVED into executable authority. */
    suspend fun invalidateApprovalsAfterRestart() {
        approvalAuthority.revokeAll()
        fences.restore()
        for (proposal in store.listAll()) when (proposal.status) {
            ProposalStatus.APPROVED -> store.updateProposal(proposal.proposalId) { current ->
                checkNotNull(current).copy(status = ProposalStatus.EXPIRED, approval = null,
                    statusReason = "PROCESS_RESTART", completedAtMillis = nowMillis())
            }
            ProposalStatus.APPLYING -> {
                fences.install(proposal.proposalId, proposal.affectedRefs)
                store.updateProposal(proposal.proposalId) { current -> checkNotNull(current).copy(
                    status = ProposalStatus.NEEDS_RECOVERY, approval = null, statusReason = "OUTCOME_REQUIRES_RECEIPT_RECONCILIATION") }
            }
            else -> Unit
        }
    }

    suspend fun get(proposalId: String): Proposal = store.get(proposalId)
        ?: throw ProposalServiceError.UnknownProposal(proposalId)

    private fun verifyDigest(proposal: Proposal) {
        require(proposal.definitionVersion == definitionVersion()) { "Definition changed" }
        require(proposal.canonicalDigest == digestOf(proposal)) { "Persisted proposal integrity changed" }
        require(proposal.readSetFingerprint == CreatorCanonicalizer.canonicalDigest(json.encodeToJsonElement(proposal.readSet)))
    }

    internal fun digestOf(p: Proposal): String = CreatorCanonicalizer.canonicalDigest(buildJsonObject {
        put("proposalId", p.proposalId); put("sessionId", p.sessionId); put("runId", p.runId)
        put("sourceCall", p.callBindingId); put("definitionVersion", p.definitionVersion)
        put("domain", p.commandDomain.name); put("command", json.parseToJsonElement(p.commandJson))
        put("hostIds", json.encodeToJsonElement(p.hostAssignedIds)); put("readSet", json.encodeToJsonElement(p.readSet))
        put("steps", JsonArray(p.steps.map { json.parseToJsonElement(it.opJson) }))
        put("diff", json.encodeToJsonElement(p.diffSummary)); put("risk", p.risk.name)
        put("retention", json.encodeToJsonElement(p.retention)); put("enhanced", p.requiresEnhancedConfirm)
        put("affectedRefs", json.encodeToJsonElement(p.affectedRefs)); put("expiresAt", p.expiresAtMillis)
    })

    private fun event(p: Proposal, kind: AuditKind) = AuditEvent(
        auditEventId = UUID.randomUUID().toString(), atMillis = nowMillis(), proposalId = p.proposalId,
        sessionId = p.sessionId, runId = p.runId, approvalDigest = p.canonicalDigest, kind = kind)

    private suspend fun appendJournal(p: Proposal, phase: JournalPhase) {
        if (!sequenceInitialized) sequenceMutex.withLock {
            if (!sequenceInitialized) {
                sequence.set(journal.allJournalEntries().maxOfOrNull { it.seq } ?: 0)
                sequenceInitialized = true
            }
        }
        journal.append(JournalEntry(sequence.incrementAndGet(), nowMillis(), p.proposalId, phase,
            objectRefs = p.affectedRefs))
    }

    companion object {
        /** Pending proposals stay approvable for a day; apply-time CAS still catches stale bases. */
        const val DEFAULT_PROPOSAL_TTL_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
