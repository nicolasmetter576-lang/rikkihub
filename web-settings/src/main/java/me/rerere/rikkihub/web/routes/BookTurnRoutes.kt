package me.rerere.rikkihub.web.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.book.history.TurnFileOutcome
import me.rerere.rikkihub.data.book.history.TurnFilePick
import me.rerere.rikkihub.data.book.history.TurnRecord
import me.rerere.rikkihub.data.book.history.TurnStatus
import me.rerere.rikkihub.data.book.history.Unattributed
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException

/*
 * 逐处审阅（App 同一套引擎与读写）。网页端能看、选接受 / 拒绝、完成、撤销、静音；
 * 不能解锁（整章锁的文件提示到 App 里解锁），不能清空。
 *
 *   GET  /api/workspaces/{id}/book/turns?conversation=    -> {turns: [BookTurnSummaryDto], muted}（对话里的卡片）
 *   GET  /api/workspaces/{id}/book/turns/latest           -> {turn?, title?}（作品页横幅）
 *   GET  /api/workspaces/{id}/book/turns/recent           -> {turns, titles}（最近的改动）
 *   GET  /api/workspaces/{id}/book/turns/{conversation}/{message}           -> TurnReviewDto（记成看过）
 *   POST /api/workspaces/{id}/book/turns/{conversation}/{message}/complete  {files: [TurnFilePick]} -> {results}
 *   POST /api/workspaces/{id}/book/turns/{conversation}/{message}/undo      -> {results}
 *   POST /api/workspaces/{id}/book/turns/{conversation}/{message}/seen      -> 204（横幅上的 ×）
 *   POST /api/workspaces/{id}/book/turns/mute             {conversation, muted} -> 204
 *
 * 逐文件结果用 outcome（ok | conflict | locked | failed | missing），整体 200；请求本身不合法才 4xx。
 */

@Serializable
data class BookTurnSummaryDto(
    val conversationId: String,
    val messageId: String,
    val finishedAt: Long,
    val status: TurnStatus,
    /** 卡片上数的文件（不含只改了缩进、空行的）。 */
    val files: Int,
    val deleted: Int,
    /** 只新建了文件（卡片只给一行小字）。 */
    val onlyCreated: Boolean,
    val reverted: Int,
    val unattributed: Unattributed? = null,
    /** 碰过的文件（历史键）：重新生成时判断兄弟回复是否改过同一批文件。 */
    val keys: List<String>,
    /** 这一轮写下的版本已被清理。 */
    val cleaned: Boolean,
)

@Serializable
data class BookTurnsDto(val turns: List<BookTurnSummaryDto>, val muted: Boolean = false, val titles: Map<String, String> = emptyMap())

@Serializable
data class BookTurnLatestDto(val turn: BookTurnSummaryDto? = null, val title: String? = null)

@Serializable
data class BookTurnCompleteRequest(val files: List<TurnFilePick>)

@Serializable
data class BookTurnResultsDto(val results: List<TurnFileOutcome>)

@Serializable
data class BookTurnMuteRequest(val conversation: String, val muted: Boolean)

internal fun TurnRecord.summary(): BookTurnSummaryDto {
    val counted = files.values.filter { !it.layoutOnly }
    return BookTurnSummaryDto(
        conversationId = conversationId,
        messageId = messageId,
        finishedAt = finishedAt!!,
        status = status,
        files = counted.size.coerceAtLeast(1),
        deleted = counted.count { it.trashed != null || it.deleted },
        onlyCreated = counted.isNotEmpty() && counted.all { it.created },
        reverted = reverted,
        unattributed = commandsUnattributed,
        keys = files.keys.toList(),
        cleaned = files.values.any { it.missing == me.rerere.rikkihub.data.book.history.TurnMissing.AFTER },
    )
}

internal fun Route.bookTurnRoutes(repository: WorkspaceRepository, services: WebBookServices) {
    val history = services.history
    val actions = services.turnActions
    route("/turns") {
        get {
            val id = bookWorkspaceId(call, repository)
            val conversation = call.request.queryParameters["conversation"] ?: throw BadRequestException("Missing 'conversation'")
            val turns = history.turns(id).filter { it.conversationId == conversation && it.status != TurnStatus.ARCHIVED }
            call.respond(BookTurnsDto(turns.map { it.summary() }, muted = conversation in history.muted(id)))
        }
        get("/latest") {
            val id = bookWorkspaceId(call, repository)
            val turn = history.bannerTurn(id)
            call.respond(BookTurnLatestDto(turn?.summary(), turn?.let { services.conversationTitle(it.conversationId) }))
        }
        get("/recent") {
            val id = bookWorkspaceId(call, repository)
            val turns = history.turns(id).sortedByDescending { it.finishedAt }
            val titles = turns.map { it.conversationId }.distinct().mapNotNull { c -> services.conversationTitle(c)?.let { c to it } }.toMap()
            call.respond(BookTurnsDto(turns.map { it.summary() }, titles = titles))
        }
        post("/mute") {
            val id = bookWorkspaceId(call, repository)
            val request = call.receive<BookTurnMuteRequest>()
            history.mute(id, request.conversation, request.muted)
            call.respond(io.ktor.http.HttpStatusCode.NoContent)
        }
        route("/{conversation}/{message}") {
            get {
                val id = bookWorkspaceId(call, repository)
                val (conversation, message) = call.turnKey()
                call.respond(workspaceFileApiOperation { actions.load(id, conversation, message) } ?: throw NotFoundException("Turn not found"))
            }
            post("/complete") {
                val id = bookWorkspaceId(call, repository)
                val (conversation, message) = call.turnKey()
                val request = call.receive<BookTurnCompleteRequest>()
                call.respond(BookTurnResultsDto(workspaceFileApiOperation { actions.complete(id, conversation, message, request.files) }))
            }
            post("/undo") {
                val id = bookWorkspaceId(call, repository)
                val (conversation, message) = call.turnKey()
                call.respond(BookTurnResultsDto(workspaceFileApiOperation { actions.undo(id, conversation, message) }))
            }
            post("/seen") {
                val id = bookWorkspaceId(call, repository)
                val (conversation, message) = call.turnKey()
                history.updateTurn(id, conversation, message) { it.copy(seenAt = it.seenAt ?: System.currentTimeMillis()) }
                call.respond(io.ktor.http.HttpStatusCode.NoContent)
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.turnKey(): Pair<String, String> {
    val conversation = parameters["conversation"] ?: throw BadRequestException("Missing conversation")
    val message = parameters["message"] ?: throw BadRequestException("Missing message")
    return conversation to message
}
