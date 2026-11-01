package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.service.CompressErrorCodes
import me.rerere.rikkihub.service.CompressOrigin
import me.rerere.rikkihub.service.CompressRejectedException
import me.rerere.rikkihub.service.CompressRequest
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import kotlin.uuid.Uuid

/** Wire form of [CompressState]; also the payload of the conversation SSE `compress_state` event. */
@Serializable
data class CompressStateDto(
    val conversationId: String,
    /** running | succeeded | failed | cancelled */
    val status: String,
    /** app | web */
    val origin: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class CompressStateResponse(val state: CompressStateDto? = null)

/** 压缩固定用会话的聊天模型和结构化总结指令，可选的是附加要求和要保留的最近消息数（旧客户端多带的字段忽略）。 */
@Serializable
data class CompressStartRequest(
    val additionalPrompt: String? = null,
    val keepRecent: Int? = null,
)

fun CompressState.toDto(conversationId: Uuid) = CompressStateDto(
    conversationId = conversationId.toString(),
    status = status.name.lowercase(),
    origin = origin.name.lowercase(),
    startedAt = startedAt,
    finishedAt = finishedAt,
    errorCode = errorCode,
    errorMessage = errorMessage,
)

/** Out-of-range values are a 400, not silently clamped. */
internal fun CompressStartRequest.toCompressRequest(): CompressRequest = try {
    CompressRequest(additionalPrompt = additionalPrompt.orEmpty(), keepRecent = keepRecent ?: 0)
} catch (e: IllegalArgumentException) {
    throw BadRequestException(e.message ?: "Invalid compression request")
}

/**
 * 后台压缩：在对话末尾插入摘要检查点（不删历史）。POST 立即返回 202 与 running 状态；进度与结果经会话
 * SSE 的 `compress_state` 事件推送。DELETE 取消；GET 取当前状态（会话未加载时为 null）。
 * 拒绝：没有新消息 400 `NOTHING_TO_COMPRESS`；扣掉要保留的最近消息后没有可压缩的 400 `NOT_ENOUGH_MESSAGES`；有未执行完的工具调用 409 `PENDING_TOOLS`；忙 / 已在压缩 409。
 */
fun Route.compressRoutes(chatService: ChatService, conversationRepo: ConversationRepository) {
    route("/conversations/{id}/compress") {
        get {
            val id = parseConversationId(call.parameters["id"])
            call.respond(CompressStateResponse(chatService.currentCompressState(id)?.toDto(id)))
        }

        post {
            val id = parseConversationId(call.parameters["id"])
            val request = call.receive<CompressStartRequest>().toCompressRequest()
            if (!conversationRepo.existsConversationById(id)) throw NotFoundException("Conversation not found")
            try {
                chatService.startCompression(id, request, CompressOrigin.WEB)
            } catch (e: CompressRejectedException) {
                val status = when (e.code) {
                    CompressErrorCodes.NOTHING_TO_COMPRESS, CompressErrorCodes.NOT_ENOUGH_MESSAGES -> HttpStatusCode.BadRequest
                    else -> HttpStatusCode.Conflict
                }
                throw CodedApiException(status, e.code, e.message ?: e.code)
            }
            call.respond(HttpStatusCode.Accepted, CompressStateResponse(chatService.currentCompressState(id)?.toDto(id)))
        }

        delete {
            val id = parseConversationId(call.parameters["id"])
            if (!chatService.cancelCompression(id)) throw NotFoundException("No compression is running")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun parseConversationId(raw: String?): Uuid =
    runCatching { Uuid.parse(raw.orEmpty()) }.getOrNull() ?: throw BadRequestException("Invalid conversation id")
