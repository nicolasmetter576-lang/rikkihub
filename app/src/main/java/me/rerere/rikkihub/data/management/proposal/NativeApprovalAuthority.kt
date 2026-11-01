package me.rerere.rikkihub.data.management.proposal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Native-only review handles are deliberately not DTOs, Serializable or Binder arguments. */
class NativeProposalReview internal constructor(
    internal val authority: NativeApprovalAuthority,
    internal val nonce: String,
    val proposalId: String,
    val digest: String,
    val readSetFingerprint: String,
    val storageEpoch: Long,
    val definitionVersion: Int,
    internal val generation: Long,
    internal val expiresAtMillis: Long,
)

/** A one-use in-process capability, never persisted or accepted from a model/Web/Intent. */
class NativeProposalApproval internal constructor(
    internal val authority: NativeApprovalAuthority,
    internal val nonce: String,
    val proposalId: String,
    val digest: String,
    val readSetFingerprint: String,
    val storageEpoch: Long,
    val definitionVersion: Int,
    internal val generation: Long,
    internal val expiresAtMillis: Long,
)

class NativeApprovalAuthority(
    private val storageEpoch: () -> Long,
    private val definitionVersion: () -> Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val generation = AtomicLong(0)
    private val reviews = ConcurrentHashMap<String, NativeProposalReview>()
    private val approvals = ConcurrentHashMap<String, NativeProposalApproval>()

    /**
     * Called from the native ViewModel after loading the exact structured diff. The handle
     * lives as long as the proposal itself; digest/epoch/generation checks keep it exact.
     */
    fun review(proposal: Proposal): NativeProposalReview {
        require(proposal.status == ProposalStatus.PENDING_APPROVAL)
        require(proposal.expiresAtMillis > nowMillis())
        require(proposal.definitionVersion == definitionVersion())
        val handle = NativeProposalReview(this, UUID.randomUUID().toString(), proposal.proposalId,
            proposal.canonicalDigest, proposal.readSetFingerprint, storageEpoch(), proposal.definitionVersion,
            generation.get(), proposal.expiresAtMillis)
        reviews[handle.nonce] = handle
        return handle
    }

    /** A callback from an obsolete review cannot approve the currently selected proposal. */
    fun approve(review: NativeProposalReview): NativeProposalApproval {
        require(review.authority === this && reviews[review.nonce] === review) { "Unknown native review" }
        checkEpoch(review.generation, review.storageEpoch, review.definitionVersion, review.expiresAtMillis)
        check(reviews.remove(review.nonce, review)) { "Native review already consumed" }
        val approval = NativeProposalApproval(this, UUID.randomUUID().toString(), review.proposalId,
            review.digest, review.readSetFingerprint, review.storageEpoch, review.definitionVersion,
            review.generation, review.expiresAtMillis)
        approvals[approval.nonce] = approval
        return approval
    }

    /** Validation grants no effect and consumes nothing. */
    internal fun validate(approval: NativeProposalApproval, proposal: Proposal) {
        require(approval.authority === this && approvals[approval.nonce] === approval) { "Unknown native approval" }
        checkEpoch(approval.generation, approval.storageEpoch, approval.definitionVersion, approval.expiresAtMillis)
        require(approval.proposalId == proposal.proposalId && approval.digest == proposal.canonicalDigest &&
            approval.readSetFingerprint == proposal.readSetFingerprint && proposal.definitionVersion == approval.definitionVersion) {
            "Approved diff or baseline changed"
        }
    }

    /** Must execute inside the proposal store's atomic claim. */
    internal fun consume(approval: NativeProposalApproval, proposal: Proposal) {
        validate(approval, proposal)
        check(approvals.remove(approval.nonce, approval)) { "Native approval already consumed" }
    }

    fun revokeAll() {
        generation.incrementAndGet()
        reviews.clear()
        approvals.clear()
    }

    private fun checkEpoch(expectedGeneration: Long, expectedEpoch: Long, version: Int, expiresAt: Long) {
        require(expectedGeneration == generation.get() && expectedEpoch == storageEpoch() &&
            version == definitionVersion() && expiresAt > nowMillis()) { "Native approval expired or revoked" }
    }
}
