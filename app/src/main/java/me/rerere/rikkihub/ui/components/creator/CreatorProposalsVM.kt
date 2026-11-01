package me.rerere.rikkihub.ui.components.creator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.management.coordinator.WriteGateStatus
import me.rerere.rikkihub.data.management.proposal.NativeProposalReview
import me.rerere.rikkihub.data.management.proposal.Proposal
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.management.runtime.ManagementRuntime

data class CreatorProposalsState(
    val conversationId: String? = null,
    val proposals: List<Proposal> = emptyList(),
    val writeGate: WriteGateStatus? = null,
    val approvingAll: Boolean = false,
    val error: String? = null,
) {
    val pendingCount: Int get() = proposals.count { it.status == ProposalStatus.PENDING_APPROVAL }
}

/** A frozen proposal plus the in-process review handle for exactly that digest. */
data class CreatorReviewState(
    val proposal: Proposal,
    val handle: NativeProposalReview,
    val busy: Boolean = false,
    val result: Proposal? = null,
    val error: String? = null,
)

/**
 * Proposal queue of one creator conversation (proposal `sessionId` = conversation id).
 * Approval is reachable only through [approve], which needs a review handle created
 * for the exact frozen proposal shown on screen.
 */
class CreatorProposalsVM(
    private val management: ManagementRuntime,
) : ViewModel() {
    private val _state = MutableStateFlow(CreatorProposalsState())
    val state: StateFlow<CreatorProposalsState> = _state.asStateFlow()

    private val _review = MutableStateFlow<CreatorReviewState?>(null)
    val review: StateFlow<CreatorReviewState?> = _review.asStateFlow()

    fun bind(conversationId: String) {
        if (_state.value.conversationId != conversationId) {
            _state.value = CreatorProposalsState(conversationId = conversationId)
            _review.value = null
        }
        refresh()
    }

    fun refresh() {
        val conversationId = _state.value.conversationId ?: return
        viewModelScope.launch {
            val list = runCatching { management.list() }.getOrDefault(emptyList())
                .filter { it.sessionId == conversationId }
            val gate = runCatching { management.writeGateStatus() }.getOrNull()
            _state.update {
                if (it.conversationId == conversationId) it.copy(proposals = list, writeGate = gate) else it
            }
        }
    }

    fun openReview(proposalId: String) {
        viewModelScope.launch {
            try {
                val (proposal, handle) = management.review(proposalId)
                _review.value = CreatorReviewState(proposal, handle)
            } catch (failure: Exception) {
                _state.update { it.copy(error = failure.message?.take(500)) }
                refresh()
            }
        }
    }

    fun closeReview() {
        _review.value = null
        refresh()
    }

    fun approve() {
        val current = _review.value ?: return
        if (current.busy || current.result != null) return
        _review.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = management.approveAndApply(current.handle)
                _review.value = current.copy(busy = false, result = result)
            } catch (failure: Exception) {
                _review.value = current.copy(busy = false, error = failure.message?.take(500) ?: failure.javaClass.simpleName)
            } finally {
                refresh()
            }
        }
    }

    /** Approves and applies every pending proposal of this conversation, oldest first. */
    fun approveAll() {
        val conversationId = _state.value.conversationId ?: return
        if (_state.value.approvingAll) return
        _state.update { it.copy(approvingAll = true, error = null) }
        viewModelScope.launch {
            try {
                val failures = management.approveAllAndApply(conversationId).mapNotNull { it.exceptionOrNull() }
                if (failures.isNotEmpty()) {
                    _state.update { it.copy(error = failures.joinToString("\n") {
                        it.message?.take(200) ?: it.javaClass.simpleName }.take(500)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update { it.copy(error = failure.message?.take(500) ?: failure.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(approvingAll = false) }
                refresh()
            }
        }
    }

    fun reject() {
        val current = _review.value ?: return
        viewModelScope.launch {
            runCatching { management.reject(current.proposal.proposalId) }
            closeReview()
        }
    }

    fun cancelProposal(proposalId: String) {
        viewModelScope.launch {
            runCatching { management.cancel(proposalId) }
            refresh()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
