package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.heartbeat
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.ai.polish.PromptPolish
import me.rerere.rikkihub.data.ai.polish.PromptPolishConfig
import me.rerere.rikkihub.data.ai.polish.PromptPolishException
import me.rerere.rikkihub.data.ai.polish.PromptPolishHandler
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * 聊天框润色。提示词拼装、上下文截取、模型选择与 App 共用 [PromptPolish] / [PromptPolishHandler]。
 *
 *   POST /api/prompt-polish  {text, conversationId?} -> text/event-stream
 *     delta {text}（累计快照）… → done {text}；失败为 error {code, message}
 *     上下文开关打开且给了 conversationId 时，取该会话当前分支最近的消息。
 *   GET  /api/prompt-polish/config -> PromptPolishConfigDto
 *   PUT  /api/prompt-polish/config  merge-patch {modelId?, prompt?, includeContext?, contextMessages?}
 *
 * 空文本、过长、坏的会话 id 在开流前返回 400；模型问题走 SSE 事件。
 */
internal val PROMPT_POLISH_STREAM_TIMEOUT = 180.seconds
private val PROMPT_POLISH_HEARTBEAT_PERIOD = 15.seconds

@Serializable
data class PromptPolishHttpRequest(val text: String? = null, val conversationId: String? = null)

@Serializable
data class PromptPolishTextEvent(val text: String)

@Serializable
data class PromptPolishErrorEvent(val code: String, val message: String)

@Serializable
data class PromptPolishConfigDto(
    val modelId: String? = null,
    /** 指定的模型已不存在，实际用助手模型。 */
    val modelMissing: Boolean = false,
    /** 自定义提示词（null = 内置默认）。 */
    val prompt: String? = null,
    val defaultPrompt: String = PromptPolish.DEFAULT_PROMPT,
    val includeContext: Boolean = false,
    val contextMessages: Int = PromptPolishConfig.DEFAULT_CONTEXT_MESSAGES,
    val minContextMessages: Int = PromptPolishConfig.MIN_CONTEXT_MESSAGES,
    val maxContextMessages: Int = PromptPolishConfig.MAX_CONTEXT_MESSAGES,
    val maxPromptChars: Int = PromptPolishConfig.MAX_PROMPT_CHARS,
    val maxInputChars: Int = PromptPolish.MAX_INPUT_CHARS,
)

internal fun projectPromptPolishConfig(settings: Settings): PromptPolishConfigDto {
    val config = settings.promptPolish
    return PromptPolishConfigDto(
        modelId = config.modelId?.toString(),
        modelMissing = config.modelId != null && settings.findModelById(config.modelId) == null,
        prompt = config.prompt?.takeIf { it.isNotBlank() },
        includeContext = config.includeContext,
        contextMessages = config.effectiveContextMessages,
    )
}

/** 校验请求；任何问题都是 400。返回规整后的文本和会话 id。 */
internal fun parsePromptPolishRequest(body: PromptPolishHttpRequest): Pair<String, Uuid?> {
    val text = body.text ?: throw BadRequestException("Missing 'text'")
    try {
        PromptPolish.validateInput(text)
    } catch (e: PromptPolishException) {
        throw BadRequestException(
            if (e.reason == PromptPolishException.Reason.INPUT_TOO_LONG) "'text' is too long" else "'text' is empty"
        )
    }
    val conversationId = body.conversationId?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { Uuid.parse(raw) }.getOrNull() ?: throw BadRequestException("Invalid 'conversationId'")
    }
    return text to conversationId
}

private val PROMPT_POLISH_CONFIG_KEYS = setOf("modelId", "prompt", "includeContext", "contextMessages")

internal fun mergePromptPolishConfig(config: PromptPolishConfig, body: JsonObject, settings: Settings): PromptPolishConfig {
    val unknown = body.keys - PROMPT_POLISH_CONFIG_KEYS
    if (unknown.isNotEmpty()) throw BadRequestException("Unknown keys: ${unknown.sorted().joinToString()}")
    var result = config
    if ("modelId" in body) {
        result = result.copy(modelId = when (val value = body["modelId"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                val raw = value.takeIf { it.isString }?.content ?: throw BadRequestException("'modelId' must be a string or null")
                val uuid = runCatching { Uuid.parse(raw) }.getOrNull() ?: throw BadRequestException("Invalid 'modelId'")
                val model = settings.findModelById(uuid)
                if (uuid != config.modelId && (model == null || model.type != ModelType.CHAT)) {
                    throw BadRequestException("Unknown chat model")
                }
                uuid
            }
            else -> throw BadRequestException("'modelId' must be a string or null")
        })
    }
    if ("prompt" in body) {
        result = result.copy(prompt = when (val value = body["prompt"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                val text = value.takeIf { it.isString }?.content ?: throw BadRequestException("'prompt' must be a string or null")
                if (text.length > PromptPolishConfig.MAX_PROMPT_CHARS) throw BadRequestException("'prompt' is too long")
                text.takeIf { it.isNotBlank() }
            }
            else -> throw BadRequestException("'prompt' must be a string or null")
        })
    }
    body["includeContext"]?.let { value ->
        val enabled = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw BadRequestException("'includeContext' must be a boolean")
        result = result.copy(includeContext = enabled)
    }
    body["contextMessages"]?.let { value ->
        val count = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw BadRequestException("'contextMessages' must be an integer")
        if (count !in PromptPolishConfig.MIN_CONTEXT_MESSAGES..PromptPolishConfig.MAX_CONTEXT_MESSAGES) {
            throw BadRequestException(
                "'contextMessages' must be ${PromptPolishConfig.MIN_CONTEXT_MESSAGES}..${PromptPolishConfig.MAX_CONTEXT_MESSAGES}"
            )
        }
        result = result.copy(contextMessages = count)
    }
    return result
}

fun Route.promptPolishRoutes(
    settingsStore: SettingsStore,
    handler: PromptPolishHandler,
    conversationRepo: ConversationRepository,
) {
    route("/prompt-polish") {
        post {
            val body = try {
                call.receive<PromptPolishHttpRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            val (text, conversationId) = parsePromptPolishRequest(body)
            val settings = settingsStore.settingsFlow.value
            val context: List<UIMessage> = if (settings.promptPolish.includeContext && conversationId != null) {
                conversationRepo.getConversationById(conversationId)?.currentMessages.orEmpty()
            } else {
                emptyList()
            }
            call.respond(
                SSEServerContent(
                    call = call,
                    handle = {
                        heartbeat {
                            period = PROMPT_POLISH_HEARTBEAT_PERIOD
                            event = ServerSentEvent(comments = "hb")
                        }
                        runPromptPolishStream(
                            results = handler.polish(settings, text, context, conversationId),
                            sendEvent = { name, data -> send(data = data, event = name) },
                        )
                    },
                )
            )
        }
        get("/config") {
            call.respond(HttpStatusCode.OK, projectPromptPolishConfig(settingsStore.settingsFlow.value))
        }
        put("/config") {
            val body = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            settingsStore.settingsFlow.value.let { mergePromptPolishConfig(it.promptPolish, body, it) } // validate before writing
            var result: Settings? = null
            settingsStore.update { settings ->
                settings.copy(promptPolish = mergePromptPolishConfig(settings.promptPolish, body, settings)).also { result = it }
            }
            call.respond(HttpStatusCode.OK, projectPromptPolishConfig(result ?: settingsStore.settingsFlow.value))
        }
    }
}

/**
 * SSE seam (testable without Ktor or a model): delta {text} … → done {text}, or error {code, message}.
 * The last value of [results] is the final, cleaned text. A failed write (client gone) propagates so the
 * upstream generation is cancelled.
 */
internal suspend fun runPromptPolishStream(
    results: Flow<String>,
    streamTimeout: Duration = PROMPT_POLISH_STREAM_TIMEOUT,
    sendEvent: suspend (eventName: String, jsonData: String) -> Unit,
) {
    var last: String? = null
    try {
        withTimeout(streamTimeout) {
            results.collect { text ->
                last?.let { previous -> sendOrAbort(sendEvent, "delta", previous) }
                last = text
            }
        }
        val final = last
        if (final == null) {
            sendPolishErrorSafely(sendEvent, PromptPolishException.Reason.EMPTY_RESULT.name, PromptPolishException.Reason.EMPTY_RESULT.name)
        } else {
            sendOrAbort(sendEvent, "done", final)
        }
    } catch (e: TimeoutCancellationException) {
        sendPolishErrorSafely(sendEvent, "TIMEOUT", "Timed out")
    } catch (e: CancellationException) {
        throw e
    } catch (e: PromptPolishWriteFailedException) {
        throw generateSequence<Throwable>(e) { it.cause }.firstOrNull { it !is PromptPolishWriteFailedException } ?: e
    } catch (e: PromptPolishException) {
        sendPolishErrorSafely(sendEvent, e.reason.name, e.reason.name)
    } catch (e: Throwable) {
        sendPolishErrorSafely(sendEvent, "MODEL_ERROR", sanitizePublicError(e.message))
    }
}

private suspend fun sendOrAbort(sendEvent: suspend (String, String) -> Unit, name: String, text: String) {
    try {
        sendEvent(name, JsonInstant.encodeToString(PromptPolishTextEvent(text)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw PromptPolishWriteFailedException(e)
    }
}

private class PromptPolishWriteFailedException(cause: Throwable) : Exception(cause)

private suspend fun sendPolishErrorSafely(sendEvent: suspend (String, String) -> Unit, code: String, message: String) {
    try {
        sendEvent("error", JsonInstant.encodeToString(PromptPolishErrorEvent(code, message)))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Connection already gone; nothing to report to.
    }
}
