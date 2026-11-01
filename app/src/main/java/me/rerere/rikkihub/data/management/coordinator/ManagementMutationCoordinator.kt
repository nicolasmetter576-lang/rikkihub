package me.rerere.rikkihub.data.management.coordinator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.management.audit.*
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.management.persistence.*
import me.rerere.rikkihub.data.management.ports.*
import me.rerere.rikkihub.data.management.proposal.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Receipt-driven saga. A missing reply is never interpreted as a missing effect. */
class ManagementMutationCoordinator(
    private val gateways: Map<StoreKind, StoreGateway>,
    private val journal: ManagementJournal,
    private val proposals: ManagementProposalStore,
    private val leases: DomainLeaseRegistry,
    private val fences: ObjectFenceRegistry,
    private val audit: ManagementAuditLog,
    private val gate: ManagementWriteGate,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val applying = ConcurrentHashMap.newKeySet<String>()
    private val sequence = AtomicLong(0)

    suspend fun applyClaimed(proposalId: String, adapter: ManagementDomainAdapter): Proposal {
        val proposal = get(proposalId)
        if (proposal.status == ProposalStatus.APPLIED) return proposal
        if (proposal.status != ProposalStatus.APPLYING)
            throw ProposalServiceError.NotApplicableState(proposalId, proposal.status)
        if (!applying.add(proposalId)) return get(proposalId)
        try {
            // UI rotation/cancelling generation cannot abandon an already claimed
            // bounded store transaction. Explicit business cancellation is checked
            // before each step through the persisted cancelRequested flag.
            return withContext(NonCancellable) { drive(proposal, adapter) }
        } finally { applying.remove(proposalId) }
    }

    private suspend fun drive(proposal: Proposal, adapter: ManagementDomainAdapter): Proposal {
        val gateStatus = gate.status()
        if (gateStatus is WriteGateStatus.Disabled) return needsRecovery(proposal, "WRITE_GATE_CLOSED")
        val steps = try {
            proposal.steps.map { step -> step.stepId to (adapter.decodeStep(step.opJson)
                ?: throw IllegalArgumentException("Unrestorable step")) }
        } catch (_: Exception) { return needsRecovery(proposal, "STEP_CODEC_UNAVAILABLE") }
        val refs = (proposal.affectedRefs + steps.flatMap { it.second.objectRefs } +
            proposal.readSet.entries.filterIsInstance<ReadSetEntry.Object>().map { it.ref }).distinct()
        val lease = leases.acquire(refs, proposal.proposalId)
        val applied = mutableListOf<Pair<String, StoreReceipt>>()
        try {
            fences.install(proposal.proposalId, refs)
            append(proposal, JournalPhase.FENCE_INSTALLED, refs = refs)
            try { verifyReadSet(proposal.readSet) }
            catch (_: ConcurrencyConflict) {
                return terminal(proposal, ProposalStatus.CONFLICT, AuditKind.CONFLICT, "TOKEN_CONFLICT", emptyList())
            }
            catch (_: Exception) { return needsRecovery(proposal, "READSET_UNVERIFIABLE") }

            for ((stepId, operation) in steps) {
                if (get(proposal.proposalId).cancelRequested) {
                    return if (applied.isEmpty()) terminal(proposal, ProposalStatus.FAILED,
                        AuditKind.CANCELLED, "CANCELLED_BEFORE_EFFECT", emptyList())
                    else needsRecovery(proposal, "CANCELLED_AFTER_PARTIAL_EFFECT")
                }
                val gateway = gateways[operation.store]
                    ?: return needsRecovery(proposal, "STORE_GATEWAY_UNAVAILABLE")
                val operationId = "${proposal.proposalId}/$stepId"
                append(proposal, JournalPhase.STEP_INTENT, stepId, operationId, operation.store, operation.objectRefs)
                gateway.writeParticipation(ParticipationRecord(operationId, operation.store,
                    operation.objectRefs, ParticipationPhase.INTENT, gateway.storageEpoch(), true,
                    null, nowMillis()))
                val expected = proposal.readSet.entries.filter { entry -> when (entry) {
                    is ReadSetEntry.Object -> StoreRouting.forObject(entry.ref) == operation.store
                    is ReadSetEntry.Collection -> StoreRouting.forCollection(entry.collectionId) == operation.store
                } }
                val receipt = try {
                    gateway.apply(operation, operationId, expected)
                } catch (error: Exception) {
                    val fact = try { gateway.reconcile(operation, operationId) }
                    catch (_: Exception) { OperationFact(OperationFactStatus.UNKNOWN) }
                    when (fact.status) {
                        OperationFactStatus.UNKNOWN -> return needsRecovery(proposal, "STEP_OUTCOME_UNKNOWN")
                        OperationFactStatus.APPLIED -> fact.receipt
                            ?: return needsRecovery(proposal, "APPLIED_RECEIPT_MISSING")
                        OperationFactStatus.NOT_APPLIED -> {
                            append(proposal, JournalPhase.STEP_FAILED, stepId, operationId, operation.store,
                                operation.objectRefs, if (error is ConcurrencyConflict) "TOKEN_CONFLICT"
                                    else "STEP_FAILED:${error.javaClass.simpleName}")
                            return compensateKnown(proposal, steps.toMap(), applied,
                                if (error is ConcurrencyConflict) "TOKEN_CONFLICT" else "STEP_FAILED")
                        }
                    }
                }
                require(receipt.operationId == operationId && receipt.store == operation.store) {
                    "Owning store returned unrelated receipt"
                }
                applied += stepId to receipt
                // Persist actual receipt into the proposal BEFORE proceeding to another step.
                proposals.updateProposal(proposal.proposalId) { current -> checkNotNull(current).copy(
                    steps = current.steps.map { if (it.stepId == stepId) it.copy(receipt = receipt) else it }) }
                append(proposal, JournalPhase.STEP_DONE, stepId, operationId, operation.store, operation.objectRefs)
                gateway.writeParticipation(ParticipationRecord(operationId, operation.store,
                    operation.objectRefs, ParticipationPhase.APPLIED, gateway.storageEpoch(), true,
                    receipt.operationId, nowMillis()))
            }
            append(proposal, JournalPhase.PROPOSAL_APPLIED)
            return terminal(proposal, ProposalStatus.APPLIED, AuditKind.APPLIED, null, applied)
        } catch (_: Exception) {
            // Journal/audit/participation errors after a commit also keep fences.
            return needsRecovery(proposal, "POST_COMMIT_RECONCILIATION_REQUIRED")
        } finally { lease.release() }
    }

    suspend fun verifyReadSet(readSet: ReadSet) {
        for (entry in readSet.entries) when (entry) {
            is ReadSetEntry.Object -> {
                val gateway = gateways[StoreRouting.forObject(entry.ref)]
                    ?: throw IllegalStateException("Object store unavailable")
                val actual = gateway.currentToken(entry.ref)
                if (actual != entry.expected) throw ConcurrencyConflict(entry.ref, null, entry.expected, actual)
            }
            is ReadSetEntry.Collection -> {
                val gateway = gateways[StoreRouting.forCollection(entry.collectionId)]
                    ?: throw IllegalStateException("Collection store unavailable")
                val actual = gateway.collectionToken(entry.collectionId)
                if (actual != entry.expected) throw ConcurrencyConflict(null, entry.collectionId, entry.expected, actual)
            }
        }
    }

    private suspend fun compensateKnown(
        proposal: Proposal, operations: Map<String, StoreOperation>, applied: List<Pair<String, StoreReceipt>>,
        reason: String,
    ): Proposal {
        if (applied.isEmpty()) return terminal(proposal, ProposalStatus.FAILED, AuditKind.FAILED, reason, emptyList())
        for ((stepId, receipt) in applied.asReversed()) {
            val operation = operations[stepId] ?: return needsRecovery(proposal, "INVERSE_OPERATION_MISSING")
            val gateway = gateways[operation.store] ?: return needsRecovery(proposal, "STORE_GATEWAY_UNAVAILABLE")
            if (receipt.afterTokens.isEmpty()) return needsRecovery(proposal, "AFTER_BASELINE_MISSING")
            // ALL affected after-tokens matter, not only the first object.
            if (receipt.afterTokens.any { gateway.currentToken(it.ref) != it.token })
                return needsRecovery(proposal, "COMPENSATION_BASELINE_CHANGED")
            val compensationId = "${receipt.operationId}/compensate"
            append(proposal, JournalPhase.COMPENSATION_INTENT, stepId, compensationId,
                operation.store, operation.objectRefs)
            val result = try { gateway.compensate(operation, compensationId, receipt.afterTokens) }
            catch (_: Exception) { return needsRecovery(proposal, "COMPENSATION_OUTCOME_UNKNOWN") }
            when (result.status) {
                CompensationStatus.APPLIED -> append(proposal, JournalPhase.COMPENSATION_DONE, stepId,
                    compensationId, operation.store, operation.objectRefs)
                CompensationStatus.SKIPPED_TOKEN_MISMATCH -> return needsRecovery(proposal, "COMPENSATION_BASELINE_CHANGED")
                CompensationStatus.FAILED -> return needsRecovery(proposal, "COMPENSATION_FAILED")
            }
        }
        return terminal(proposal, ProposalStatus.FAILED, AuditKind.COMPENSATED, reason, applied)
    }

    private suspend fun terminal(
        proposal: Proposal, status: ProposalStatus, kind: AuditKind, reason: String?,
        applied: List<Pair<String, StoreReceipt>>,
    ): Proposal {
        // Terminal metadata and append-only audit precede releasing any fence.
        val updated = transition(proposal.proposalId, status, reason)
        audit.append(AuditEvent(UUID.randomUUID().toString(), nowMillis(), proposal.proposalId,
            proposal.sessionId, proposal.runId, proposal.canonicalDigest, kind,
            afterTokens = applied.flatMap { it.second.afterTokens },
            receiptOperationIds = applied.map { it.second.operationId }))
        for (step in updated.steps) {
            val operation = StoreOperationCodec.decode(step.opJson)
            gateways[operation.store]?.releaseParticipation("${proposal.proposalId}/${step.stepId}")
        }
        fences.release(proposal.proposalId)
        append(proposal, JournalPhase.FENCE_RELEASED)
        return updated
    }

    private suspend fun needsRecovery(proposal: Proposal, code: String): Proposal {
        append(proposal, JournalPhase.PROPOSAL_NEEDS_RECOVERY, payload = code)
        return proposals.updateProposal(proposal.proposalId) { current ->
            val p = current ?: throw ProposalServiceError.UnknownProposal(proposal.proposalId)
            // A terminal commit whose cleanup failed remains APPLIED/FAILED with a
            // durable fence; startup verifies receipts before cleanup. Never rewrite
            // a committed success as "no effect" or rerun it automatically.
            if (p.isTerminal) p.copy(statusReason = code)
            else {
                if (p.status != ProposalStatus.NEEDS_RECOVERY) ProposalStateMachine.require(p.status, ProposalStatus.NEEDS_RECOVERY)
                p.copy(status = ProposalStatus.NEEDS_RECOVERY, statusReason = code)
            }
        }
    }

    private suspend fun transition(id: String, status: ProposalStatus, reason: String?): Proposal =
        proposals.updateProposal(id) { current ->
            val p = current ?: throw ProposalServiceError.UnknownProposal(id)
            ProposalStateMachine.require(p.status, status)
            p.copy(status = status, statusReason = reason, completedAtMillis = nowMillis())
        }

    private suspend fun get(id: String) = proposals.get(id) ?: throw ProposalServiceError.UnknownProposal(id)

    private suspend fun append(
        p: Proposal, phase: JournalPhase, stepId: String? = null, operationId: String? = null,
        store: StoreKind? = null, refs: List<ObjectRef> = emptyList(), payload: String = "",
    ) { journal.append(JournalEntry(sequence.incrementAndGet(), nowMillis(), p.proposalId, phase,
        stepId, operationId, store, refs, payload)) }
}
