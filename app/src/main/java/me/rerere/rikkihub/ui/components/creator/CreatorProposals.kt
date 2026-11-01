package me.rerere.rikkihub.ui.components.creator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Task01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.management.coordinator.WriteGateStatus
import me.rerere.rikkihub.data.management.proposal.Proposal
import me.rerere.rikkihub.data.management.proposal.commandDomains
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import org.koin.androidx.compose.koinViewModel

/**
 * Top-bar proposal entry of a creator conversation: badge with the pending count, the
 * proposal sheet (list / cancel / approve all) and the native review dialog — the ONLY
 * place a creator proposal can be approved.
 *
 * [refreshKey] changes whenever the conversation may have produced new proposals
 * (e.g. the number of executed creator tool calls).
 */
@Composable
fun CreatorProposalsButton(
    conversationId: String,
    refreshKey: Any?,
    vm: CreatorProposalsVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    var showProposals by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(conversationId) { vm.bind(conversationId) }
    LaunchedEffect(refreshKey) { vm.refresh() }

    IconButton(onClick = { showProposals = true; vm.refresh() }) {
        BadgedBox(badge = { if (state.pendingCount > 0) Badge { Text(state.pendingCount.toString()) } }) {
            Icon(HugeIcons.Task01, contentDescription = stringResource(R.string.creator_proposals))
        }
    }

    if (showProposals) {
        ModalBottomSheet(onDismissRequest = { showProposals = false }) {
            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    onClick = vm::dismissError,
                ) {
                    Text(error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            ProposalList(
                proposals = state.proposals,
                approveAllEnabled = !state.approvingAll && state.writeGate !is WriteGateStatus.Disabled,
                onOpen = vm::openReview,
                onCancel = vm::cancelProposal,
                onApproveAll = vm::approveAll,
            )
        }
    }

    review?.let { current ->
        ReviewDialog(
            state = current,
            writeGate = state.writeGate,
            onApprove = vm::approve,
            onReject = vm::reject,
            onClose = vm::closeReview,
        )
    }
}

@Composable
private fun ProposalList(
    proposals: List<Proposal>,
    approveAllEnabled: Boolean,
    onOpen: (String) -> Unit,
    onCancel: (String) -> Unit,
    onApproveAll: () -> Unit,
) {
    val pending = proposals.count { it.status == ProposalStatus.PENDING_APPROVAL }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.creator_proposals), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            if (pending > 0) {
                TextButton(onClick = onApproveAll, enabled = approveAllEnabled) {
                    Text(stringResource(R.string.creator_approve_all, pending))
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        if (proposals.isEmpty()) {
            Text(stringResource(R.string.creator_proposals_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(proposals, key = { it.proposalId }) { proposal ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(
                        enabled = proposal.status == ProposalStatus.PENDING_APPROVAL,
                    ) { onOpen(proposal.proposalId) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(domainLabel(proposal), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(statusLabel(proposal.status), style = MaterialTheme.typography.labelMedium,
                                color = statusColor(proposal.status))
                        }
                        proposal.diffSummary.firstOrNull()?.let { line ->
                            Text(line.path, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Text(stringResource(R.string.creator_review_risk, riskLabel(proposal)) +
                            " · " + stringResource(R.string.creator_review_changes, proposal.diffSummary.size),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (proposal.status == ProposalStatus.PENDING_APPROVAL) {
                            TextButton(onClick = { onCancel(proposal.proposalId) }) {
                                Text(stringResource(R.string.creator_cancel_proposal))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewDialog(
    state: CreatorReviewState,
    writeGate: WriteGateStatus?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
) {
    val proposal = state.proposal
    val gateClosed = writeGate is WriteGateStatus.Disabled
    AlertDialog(
        onDismissRequest = { if (!state.busy) onClose() },
        title = { Text(stringResource(R.string.creator_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(domainLabel(proposal) + " · " + stringResource(R.string.creator_review_risk, riskLabel(proposal)),
                    style = MaterialTheme.typography.labelLarge)
                proposal.diffSummary.forEach { line ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(8.dp)).padding(8.dp)) {
                        Text(line.path, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        if (line.secret) {
                            Text(stringResource(R.string.creator_review_secret), style = MaterialTheme.typography.bodySmall)
                        } else {
                            line.before?.let {
                                Text(stringResource(R.string.creator_review_before) + ": " + it.take(4000),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            line.after?.let {
                                Text(stringResource(R.string.creator_review_after) + ": " + it.take(4000),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (proposal.retention.recycleDays > 0) {
                    Text(stringResource(R.string.creator_review_retention, proposal.retention.recycleDays),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (gateClosed) {
                    Text(stringResource(R.string.creator_review_write_gate_closed), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                state.result?.let { result -> Text(resultText(result), style = MaterialTheme.typography.bodyMedium) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        },
        confirmButton = {
            if (state.result == null) {
                TextButton(
                    onClick = onApprove,
                    enabled = !state.busy && !gateClosed,
                ) { Text(stringResource(R.string.creator_review_approve)) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.creator_review_close)) }
            }
        },
        dismissButton = {
            if (state.result == null) {
                TextButton(onClick = onReject, enabled = !state.busy) { Text(stringResource(R.string.creator_review_reject)) }
            }
        },
    )
}

@Composable
private fun resultText(result: Proposal): String = stringResource(
    when (result.status) {
        ProposalStatus.APPLIED -> R.string.creator_review_result_applied
        ProposalStatus.CONFLICT -> R.string.creator_review_result_conflict
        ProposalStatus.NEEDS_RECOVERY -> R.string.creator_review_result_recovery
        else -> R.string.creator_review_result_failed
    }
)

@Composable
private fun statusLabel(status: ProposalStatus): String = stringResource(
    when (status) {
        ProposalStatus.DRAFT -> R.string.creator_status_draft
        ProposalStatus.PENDING_APPROVAL -> R.string.creator_status_pending
        ProposalStatus.APPROVED -> R.string.creator_status_approved
        ProposalStatus.APPLYING -> R.string.creator_status_applying
        ProposalStatus.APPLIED -> R.string.creator_status_applied
        ProposalStatus.REJECTED -> R.string.creator_status_rejected
        ProposalStatus.EXPIRED -> R.string.creator_status_expired
        ProposalStatus.CANCELLED -> R.string.creator_status_cancelled
        ProposalStatus.CONFLICT -> R.string.creator_status_conflict
        ProposalStatus.FAILED -> R.string.creator_status_failed
        ProposalStatus.NEEDS_RECOVERY -> R.string.creator_status_recovery
    }
)

@Composable
private fun statusColor(status: ProposalStatus) = when (status) {
    ProposalStatus.APPLIED -> MaterialTheme.colorScheme.primary
    ProposalStatus.CONFLICT, ProposalStatus.FAILED, ProposalStatus.NEEDS_RECOVERY -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun riskLabel(proposal: Proposal): String = stringResource(
    when (proposal.risk) {
        me.rerere.rikkihub.data.management.common.RiskLevel.LOW -> R.string.creator_risk_low
        me.rerere.rikkihub.data.management.common.RiskLevel.MEDIUM -> R.string.creator_risk_medium
        me.rerere.rikkihub.data.management.common.RiskLevel.HIGH -> R.string.creator_risk_high
    }
)

/** One label per changed domain: a combined Settings change lists every domain it touches. */
@Composable
private fun domainLabel(proposal: Proposal): String =
    proposal.commandDomains().map { domainLabel(it) }.joinToString(" + ")

@Composable
private fun domainLabel(domain: me.rerere.rikkihub.data.management.common.ManagementDomain): String = stringResource(
    when (domain) {
        me.rerere.rikkihub.data.management.common.ManagementDomain.QUICK_MESSAGE -> R.string.creator_domain_quick_message
        me.rerere.rikkihub.data.management.common.ManagementDomain.MODE_INJECTION -> R.string.creator_domain_mode_injection
        me.rerere.rikkihub.data.management.common.ManagementDomain.WORLD_BOOK -> R.string.creator_domain_lorebook
        me.rerere.rikkihub.data.management.common.ManagementDomain.ASSISTANT -> R.string.creator_domain_assistant
        me.rerere.rikkihub.data.management.common.ManagementDomain.MODEL_AND_PROMPT_DEFAULTS -> R.string.creator_domain_defaults
        me.rerere.rikkihub.data.management.common.ManagementDomain.SUBAGENT_PRESET -> R.string.creator_domain_subagent
        me.rerere.rikkihub.data.management.common.ManagementDomain.USER_SKILL -> R.string.creator_domain_skill
        me.rerere.rikkihub.data.management.common.ManagementDomain.CONVERSATION -> R.string.creator_domain_conversation
    }
)
