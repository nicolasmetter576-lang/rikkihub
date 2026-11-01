package me.rerere.rikkihub.data.creator.runtime

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.management.proposal.Proposal
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.management.proposal.commandDomains

/**
 * Tells the creator model what happened to its proposals: before each user message, a synthetic
 * `<creator_proposal_updates>` note lists the proposals of this conversation that reached a final
 * state (applied, rejected, conflict, expired, …) between the previous user message and this one.
 *
 * The note for a message depends only on that message's time and on final states, which never
 * change, so earlier notes are rebuilt identically on every request and do not disturb prompt
 * caching. Nothing is stored in the conversation.
 */
class CreatorProposalReminderTransformer(
    private val proposals: suspend () -> List<Proposal>,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : InputMessageTransformer {

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        apply(messages)

    internal suspend fun apply(messages: List<UIMessage>): List<UIMessage> {
        val finished = runCatching { proposals() }.getOrNull().orEmpty()
            .filter { it.isTerminal && it.completedAtMillis != null }
            .sortedBy { it.completedAtMillis }
        if (finished.isEmpty()) return messages
        val result = ArrayList<UIMessage>(messages.size + 4)
        var previous = Long.MIN_VALUE
        for (message in messages) {
            if (message.role == MessageRole.USER && !message.isSynthetic) {
                val at = message.createdAt.toInstant(timeZone).toEpochMilliseconds()
                val changed = finished.filter { val done = it.completedAtMillis!!; done > previous && done <= at }
                if (changed.isNotEmpty()) result += note(changed, finished)
                previous = at
            }
            result += message
        }
        return result
    }

    private fun note(changed: List<Proposal>, all: List<Proposal>): UIMessage {
        val text = buildString {
            appendLine("<creator_proposal_updates>")
            appendLine("Proposal results since the previous user message (reported by the app, not written by the user):")
            changed.take(MAX_LINES).forEach { p ->
                append("- ").append(p.proposalId).append(" (").append(p.commandDomains().joinToString(" + ") { it.name.lowercase() })
                // "Approve all" re-plans a proposal that only lost a race with its siblings: same command, new proposal.
                val replanned = if (p.status == ProposalStatus.CONFLICT) all.firstOrNull {
                    it.status == ProposalStatus.APPLIED && it.commandJson == p.commandJson && it.createdAtMillis >= p.createdAtMillis &&
                        it.proposalId != p.proposalId
                } else null
                append("): ").append(if (replanned != null) "re-planned automatically and applied as ${replanned.proposalId}." else outcome(p))
                if (p.status == ProposalStatus.APPLIED && p.hostAssignedIds.isNotEmpty()) {
                    append(" Created ids: ").append(p.hostAssignedIds.entries.joinToString(", ") {
                        if (OPERATION_LABEL.matches(it.key)) "${it.key}=${it.value}" else it.value
                    }).append('.')
                }
                appendLine()
            }
            if (changed.size > MAX_LINES) appendLine("- … and ${changed.size - MAX_LINES} more; see creator_get_change_status.")
            append("</creator_proposal_updates>")
        }
        return UIMessage.user(text).copy(isSynthetic = true)
    }

    private fun outcome(p: Proposal): String = when (p.status) {
        ProposalStatus.APPLIED -> "APPLIED — the change is now in effect."
        ProposalStatus.REJECTED -> "REJECTED by the user — not applied."
        ProposalStatus.CONFLICT -> "CONFLICT — not applied because the data changed after it was proposed; read again and propose anew if still wanted."
        ProposalStatus.EXPIRED -> "EXPIRED — not approved in time; not applied."
        ProposalStatus.CANCELLED -> "CANCELLED — not applied."
        ProposalStatus.FAILED -> "FAILED — not applied (${p.statusReason ?: "error"})."
        else -> p.status.name
    }

    companion object {
        private const val MAX_LINES = 12
        private val OPERATION_LABEL = Regex("^\\$\\d+$")
    }
}
