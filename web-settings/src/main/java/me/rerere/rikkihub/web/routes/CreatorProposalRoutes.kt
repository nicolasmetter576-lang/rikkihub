package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.management.coordinator.WriteGateStatus
import me.rerere.rikkihub.data.management.proposal.Proposal
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError
import me.rerere.rikkihub.data.management.proposal.commandDomains
import me.rerere.rikkihub.data.management.runtime.ManagementRuntime
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException

@Serializable
data class CreatorDiffLineDto(val path: String, val before: String? = null, val after: String? = null, val secret: Boolean = false)

/** One change the creator assistant proposed. [digest] is what an approval must quote: it names exactly the diff the page showed. */
@Serializable
data class CreatorProposalDto(
    val id: String,
    val conversationId: String,
    /** pending_approval | applying | applied | rejected | expired | cancelled | conflict | failed | needs_recovery | … (lower case) */
    val status: String,
    val statusReason: String? = null,
    val risk: String,
    val requiresEnhancedConfirm: Boolean,
    val domains: List<String>,
    val createdAt: Long,
    val expiresAt: Long,
    val digest: String,
    val diff: List<CreatorDiffLineDto>,
    val omittedDiffLines: Int = 0,
    /** `$<index>` names used by the creator → the ids the app assigned. */
    val createdIds: Map<String, String> = emptyMap(),
)

@Serializable
data class CreatorProposalListResponse(
    val proposals: List<CreatorProposalDto>,
    val writeGateOpen: Boolean,
    val writeGateReasons: List<String> = emptyList(),
)

@Serializable
data class CreatorApproveRequest(val digest: String)

@Serializable
data class CreatorApproveAllRequest(val conversationId: String)

@Serializable
data class CreatorApprovalResultDto(val proposalId: String? = null, val status: String? = null, val error: String? = null)

@Serializable
data class CreatorApproveAllResponse(val results: List<CreatorApprovalResultDto>)

private const val MAX_DIFF_LINES = 200
private const val MAX_DIFF_VALUE = 4000
private const val MAX_LISTED = 100

internal fun Proposal.toCreatorDto(): CreatorProposalDto {
    val lines = diffSummary.take(MAX_DIFF_LINES).map { line ->
        if (line.secret) CreatorDiffLineDto(line.path.take(500), secret = true)
        else CreatorDiffLineDto(line.path.take(500), line.before?.take(MAX_DIFF_VALUE), line.after?.take(MAX_DIFF_VALUE))
    }
    return CreatorProposalDto(
        id = proposalId, conversationId = sessionId, status = status.name.lowercase(), statusReason = statusReason,
        risk = risk.name.lowercase(), requiresEnhancedConfirm = requiresEnhancedConfirm,
        domains = commandDomains().map { it.name }, createdAt = createdAtMillis, expiresAt = expiresAtMillis,
        digest = canonicalDigest, diff = lines, omittedDiffLines = (diffSummary.size - lines.size).coerceAtLeast(0),
        createdIds = hostAssignedIds.filterKeys { it.startsWith("$") },
    )
}

private fun Throwable.toApiError(): CodedApiException = when (this) {
    is ProposalServiceError -> CodedApiException(
        when (errorCode.name) { "NOT_FOUND" -> HttpStatusCode.NotFound; else -> HttpStatusCode.Conflict },
        errorCode.name, message.orEmpty().take(500),
    )
    is IllegalArgumentException, is IllegalStateException ->
        CodedApiException(HttpStatusCode.Conflict, "NOT_APPLICABLE", message.orEmpty().take(500).ifBlank { "The proposal cannot be applied now" })
    else -> CodedApiException(HttpStatusCode.InternalServerError, "FAILED", message.orEmpty().take(500).ifBlank { javaClass.simpleName })
}

/**
 * Review and approval of the changes the creator assistant proposes, for the web page (the app has the same on its Proposals sheet).
 * Approving quotes the digest of the diff the page showed; if the proposal changed since, nothing is applied (409).
 */
fun Route.creatorProposalRoutes(runtime: ManagementRuntime) {
    route("/creator/proposals") {
        get {
            val conversationId = call.request.queryParameters["conversationId"]?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("Missing conversationId")
            val proposals = runtime.list().filter { it.sessionId == conversationId }
                .sortedByDescending { it.createdAtMillis }.take(MAX_LISTED)
            val gate = runtime.writeGateStatus()
            call.respond(CreatorProposalListResponse(
                proposals = proposals.map { it.toCreatorDto() },
                writeGateOpen = gate !is WriteGateStatus.Disabled,
                writeGateReasons = (gate as? WriteGateStatus.Disabled)?.reasons?.map { it.toString() }.orEmpty(),
            ))
        }

        post("/approve-all") {
            val request = call.receive<CreatorApproveAllRequest>()
            if (request.conversationId.isBlank()) throw BadRequestException("Missing conversationId")
            val results = runtime.approveAllAndApply(request.conversationId).map { result ->
                result.fold(
                    { CreatorApprovalResultDto(it.proposalId, it.status.name.lowercase()) },
                    { CreatorApprovalResultDto(error = it.message.orEmpty().take(300).ifBlank { it.javaClass.simpleName }) },
                )
            }
            call.respond(CreatorApproveAllResponse(results))
        }

        route("/{id}") {
            get {
                val id = call.parameters["id"].orEmpty()
                val proposal = runCatching { runtime.proposals.get(id) }.getOrNull() ?: throw NotFoundException("Proposal not found")
                call.respond(proposal.toCreatorDto())
            }

            post("/approve") {
                val id = call.parameters["id"].orEmpty()
                val request = call.receive<CreatorApproveRequest>()
                val applied = try {
                    val (proposal, review) = runtime.review(id)
                    if (proposal.canonicalDigest != request.digest) throw CodedApiException(
                        HttpStatusCode.Conflict, "APPROVAL_DIGEST_MISMATCH", "The proposal changed after it was shown; review it again")
                    runtime.approveAndApply(review)
                } catch (e: CodedApiException) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw e.toApiError()
                }
                call.respond(applied.toCreatorDto())
            }

            post("/reject") {
                val id = call.parameters["id"].orEmpty()
                val rejected = try { runtime.reject(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw e.toApiError() }
                call.respond(rejected.toCreatorDto())
            }

            post("/cancel") {
                val id = call.parameters["id"].orEmpty()
                val cancelled = try { runtime.cancel(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw e.toApiError() }
                call.respond(cancelled.toCreatorDto())
            }
        }
    }
}
