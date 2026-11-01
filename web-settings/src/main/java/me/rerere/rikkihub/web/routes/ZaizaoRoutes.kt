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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.data.ai.zaizao.Zaizao
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoConfig
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoException
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoHandler
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoPrepared
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoProgress
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoPrompts
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoRequest
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoRound
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoTurn
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoType
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * 再造（Web 工作区编辑器）。选区规整、多轮消息、输出解析与调用和 App 共用 [Zaizao] / [ZaizaoHandler]。
 * 服务端不存会话：浏览器每次发会话开始时的全文与选区、从原文到底稿的各轮（要求和完整回复）和本轮。
 *
 *   POST /api/zaizao/range  {fileName, source, start, end, expand?} -> {start, end, original, format}
 *     第一轮之前用：规整（去两端空白）选区，expand（SENTENCE / PARAGRAPH）时先扩到整句 / 整段；空或太长 400。
 *   POST /api/zaizao  {fileName, source, start, end, includeAfter?, history?: [{type, requirement, output}],
 *                      type, requirement, avoid?: [text], record?} -> text/event-stream
 *     prepared {start, end, original, format}（规整后的实际替换区间）
 *     → update {reasoning, plan, result}（累计值，节流）… → done {reasoning, plan, result, output}；或 error {code, message}
 *     includeAfter 省略 = 设置里的默认；record（默认 true）把本轮要求记进最近用过的要求，换一版和重试传 false。
 *   GET  /api/zaizao/config -> ZaizaoConfigDto
 *   PUT  /api/zaizao/config  merge-patch {modelId?, reasoningLevel?, temperature?, topP?, maxTokens?, includeAfter?,
 *                             defaultType?, prompts?, recentRequirements?}
 *
 * 选区、要求、历史的问题在开流前返回 400；模型问题走 SSE 事件。
 */
internal const val ZAIZAO_MAX_OUTPUT_CHARS = 64_000
internal val ZAIZAO_STREAM_TIMEOUT = 600.seconds
internal val ZAIZAO_UPDATE_INTERVAL = 150.milliseconds
private val ZAIZAO_HEARTBEAT_PERIOD = 15.seconds

@Serializable
data class ZaizaoTurnDto(val type: String? = null, val requirement: String? = null, val output: String? = null)

@Serializable
data class ZaizaoHttpRequest(
    val fileName: String? = null,
    val source: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    val includeAfter: Boolean? = null,
    val history: List<ZaizaoTurnDto> = emptyList(),
    val type: String? = null,
    val requirement: String? = null,
    /** Drafts this round already gave (换一版): the new one must differ. */
    val avoid: List<String> = emptyList(),
    val record: Boolean? = null,
)

@Serializable
data class ZaizaoRangeHttpRequest(
    val fileName: String? = null,
    val source: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    /** "SENTENCE" / "PARAGRAPH": grow the range to the whole sentence / paragraph. */
    val expand: String? = null,
)

@Serializable
data class ZaizaoRangeDto(val start: Int, val end: Int, val original: String, val format: String)

@Serializable
data class ZaizaoPreparedEvent(val start: Int, val end: Int, val original: String, val format: String)

@Serializable
data class ZaizaoUpdateEvent(val reasoning: String, val plan: String, val result: String)

@Serializable
data class ZaizaoDoneEvent(val reasoning: String, val plan: String, val result: String, val output: String)

@Serializable
data class ZaizaoErrorEvent(val code: String, val message: String)

@Serializable
data class ZaizaoConfigDto(
    val modelId: String? = null,
    /** 指定的模型已不存在，实际用助手模型。 */
    val modelMissing: Boolean = false,
    val reasoningLevel: String = "auto",
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val includeAfter: Boolean = true,
    val defaultType: String = ZaizaoType.MODIFY.name,
    /** 自定义的三个类型提示词（null = 内置默认）。 */
    val prompts: Map<String, String?> = emptyMap(),
    val defaultPrompts: Map<String, String> = emptyMap(),
    val recentRequirements: List<String> = emptyList(),
    /** 文件类型与推敲共用。 */
    val extensions: List<String> = emptyList(),
    val maxRequirementChars: Int = ZaizaoConfig.MAX_REQUIREMENT_CHARS,
    val maxPromptChars: Int = ZaizaoConfig.MAX_PROMPT_CHARS,
    val maxSelectionChars: Int = Zaizao.MAX_SELECTION_CHARS,
    val maxRounds: Int = Zaizao.MAX_ROUNDS,
    val maxRecent: Int = ZaizaoConfig.MAX_RECENT,
)

/** The wire name of a reasoning level, as everywhere else in the Web API ("off", "auto", "high" …). */
internal fun ReasoningLevel.wireName(): String =
    JsonInstant.encodeToJsonElement(ReasoningLevel.serializer(), this).jsonPrimitive.content

private fun ZaizaoConfig.customPrompt(type: ZaizaoType): String? = when (type) {
    ZaizaoType.MODIFY -> modifyPrompt
    ZaizaoType.INCREMENT -> incrementPrompt
    ZaizaoType.REWRITE -> rewritePrompt
}

internal fun projectZaizaoConfig(settings: Settings): ZaizaoConfigDto {
    val config = settings.zaizao
    return ZaizaoConfigDto(
        modelId = config.modelId?.toString(),
        modelMissing = config.modelId != null && settings.findModelById(config.modelId) == null,
        reasoningLevel = config.reasoningLevel.wireName(),
        temperature = config.temperature,
        topP = config.topP,
        maxTokens = config.maxTokens,
        includeAfter = config.includeAfter,
        defaultType = config.defaultType.name,
        prompts = ZaizaoType.entries.associate { it.name to config.customPrompt(it)?.takeIf { p -> p.isNotBlank() } },
        defaultPrompts = ZaizaoType.entries.associate { it.name to ZaizaoPrompts.defaultFor(it) },
        recentRequirements = config.recentRequirements,
        extensions = settings.tuiqiao.effectiveExtensions,
    )
}

private fun parseType(raw: String?, what: String): ZaizaoType =
    ZaizaoType.entries.firstOrNull { it.name == raw } ?: throw BadRequestException("Invalid '$what'")

private class ZaizaoSelection(val fileName: String, val source: String, val start: Int, val end: Int)

private fun zaizaoSelection(fileName: String?, source: String?, start: Int?, end: Int?): ZaizaoSelection {
    val text = source ?: throw BadRequestException("Missing 'source'")
    if (text.length > TUIQIAO_MAX_SOURCE_CHARS) throw BadRequestException("'source' is too long")
    val from = start ?: throw BadRequestException("Missing 'start'")
    val to = end ?: throw BadRequestException("Missing 'end'")
    if (from < 0 || to > text.length || from >= to) throw BadRequestException("Invalid selection range")
    return ZaizaoSelection(fileName?.trim()?.take(255).orEmpty().ifEmpty { "untitled.md" }, text, from, to)
}

private fun selectionError(e: ZaizaoException): Nothing = throw BadRequestException(
    if (e.reason == ZaizaoException.Reason.SELECTION_TOO_LONG) "Selection is too long" else "Selection is empty"
)

/** 第一轮之前的选区（可扩到整句 / 整段）；空或太长是 400。 */
internal fun zaizaoRange(body: ZaizaoRangeHttpRequest): ZaizaoRangeDto {
    val selection = zaizaoSelection(body.fileName, body.source, body.start, body.end)
    val format = TuiqiaoFormat.of(selection.fileName)
    val grown = when (body.expand) {
        null -> selection.start until selection.end
        "SENTENCE" -> Tuiqiao.expandToSentence(selection.source, selection.start, selection.end, format)
        "PARAGRAPH" -> Tuiqiao.expandToParagraph(selection.source, selection.start, selection.end, format)
        else -> throw BadRequestException("Invalid 'expand'")
    }
    val range = try {
        Zaizao.range(selection.source, grown.first, grown.last + 1)
    } catch (e: ZaizaoException) {
        selectionError(e)
    }
    return ZaizaoRangeDto(range.first, range.last + 1, selection.source.substring(range.first, range.last + 1), format.name)
}

/** 校验请求、规整选区并拼好消息；任何问题都是 400。 */
internal fun prepareZaizaoRequest(settings: Settings, body: ZaizaoHttpRequest): ZaizaoPrepared {
    val selection = zaizaoSelection(body.fileName, body.source, body.start, body.end)
    val history = body.history.mapIndexed { index, turn ->
        val output = turn.output ?: throw BadRequestException("Missing 'history[$index].output'")
        if (output.length > ZAIZAO_MAX_OUTPUT_CHARS) throw BadRequestException("'history[$index].output' is too long")
        ZaizaoTurn(ZaizaoRound(parseType(turn.type, "history[$index].type"), turn.requirement.orEmpty()), output)
    }
    if (body.avoid.size > Zaizao.MAX_AVOID) throw BadRequestException("At most ${Zaizao.MAX_AVOID} 'avoid' drafts")
    if (body.avoid.any { it.length > ZAIZAO_MAX_OUTPUT_CHARS }) throw BadRequestException("'avoid' draft is too long")
    val request = ZaizaoRequest(
        fileName = selection.fileName,
        source = selection.source,
        start = selection.start,
        end = selection.end,
        includeAfter = body.includeAfter ?: settings.zaizao.includeAfter,
        history = history,
        round = ZaizaoRound(parseType(body.type, "type"), body.requirement.orEmpty()),
        avoid = body.avoid,
    )
    return try {
        Zaizao.prepare(settings.zaizao, request)
    } catch (e: ZaizaoException) {
        throw BadRequestException(
            when (e.reason) {
                ZaizaoException.Reason.SELECTION_TOO_LONG -> "Selection is too long"
                ZaizaoException.Reason.EMPTY_REQUIREMENT -> "'requirement' is empty"
                ZaizaoException.Reason.REQUIREMENT_TOO_LONG -> "'requirement' is too long"
                ZaizaoException.Reason.TOO_MANY_ROUNDS -> "Too many rounds"
                else -> "Selection is empty"
            }
        )
    }
}

private val ZAIZAO_CONFIG_KEYS = setOf(
    "modelId", "reasoningLevel", "temperature", "topP", "maxTokens", "includeAfter", "defaultType", "prompts",
    "recentRequirements",
)

internal fun mergeZaizaoConfig(config: ZaizaoConfig, body: JsonObject, settings: Settings): ZaizaoConfig {
    val unknown = body.keys - ZAIZAO_CONFIG_KEYS
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
    body["reasoningLevel"]?.let { value ->
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val level = ReasoningLevel.entries.firstOrNull { it.wireName() == raw }
            ?: throw BadRequestException("'reasoningLevel' must be one of ${ReasoningLevel.entries.joinToString { it.wireName() }}")
        result = result.copy(reasoningLevel = level)
    }
    if ("temperature" in body) {
        result = result.copy(temperature = optionalFloat(body, "temperature") { it in ZaizaoConfig.MIN_TEMPERATURE..ZaizaoConfig.MAX_TEMPERATURE })
    }
    if ("topP" in body) {
        result = result.copy(topP = optionalFloat(body, "topP") { it > ZaizaoConfig.MIN_TOP_P && it <= ZaizaoConfig.MAX_TOP_P })
    }
    if ("maxTokens" in body) {
        result = result.copy(maxTokens = when (val value = body["maxTokens"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                ?: throw BadRequestException("'maxTokens' must be a positive integer or null")
            else -> throw BadRequestException("'maxTokens' must be a positive integer or null")
        })
    }
    body["includeAfter"]?.let { value ->
        val enabled = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw BadRequestException("'includeAfter' must be a boolean")
        result = result.copy(includeAfter = enabled)
    }
    body["defaultType"]?.let { value ->
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        result = result.copy(defaultType = ZaizaoType.entries.firstOrNull { it.name == raw }
            ?: throw BadRequestException("'defaultType' must be one of ${ZaizaoType.entries.joinToString { it.name }}"))
    }
    body["prompts"]?.let { value ->
        val obj = value as? JsonObject ?: throw BadRequestException("'prompts' must be an object")
        for ((key, prompt) in obj) {
            val type = ZaizaoType.entries.firstOrNull { it.name == key } ?: throw BadRequestException("Unknown prompt: $key")
            val text = when (prompt) {
                JsonNull -> null
                is JsonPrimitive -> prompt.takeIf { it.isString }?.content ?: throw BadRequestException("Prompt '$key' must be a string")
                else -> throw BadRequestException("Prompt '$key' must be a string")
            }
            if (text != null && text.length > ZaizaoConfig.MAX_PROMPT_CHARS) throw BadRequestException("Prompt '$key' is too long")
            result = result.withPrompt(type, text?.takeIf { it.isNotBlank() })
        }
    }
    body["recentRequirements"]?.let { value ->
        val array = value as? JsonArray ?: throw BadRequestException("'recentRequirements' must be an array")
        val items = array.map { item ->
            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?: throw BadRequestException("'recentRequirements' must be strings")
            if (text.isEmpty() || text.length > ZaizaoConfig.MAX_REQUIREMENT_CHARS) {
                throw BadRequestException("Each recent requirement must be 1..${ZaizaoConfig.MAX_REQUIREMENT_CHARS} characters")
            }
            text
        }.distinct()
        if (items.size > ZaizaoConfig.MAX_RECENT) throw BadRequestException("At most ${ZaizaoConfig.MAX_RECENT} recent requirements")
        result = result.copy(recentRequirements = items)
    }
    return result
}

private fun optionalFloat(body: JsonObject, key: String, valid: (Float) -> Boolean): Float? = when (val value = body[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf { !it.isString }?.floatOrNull?.takeIf { it.isFinite() && valid(it) }
        ?: throw BadRequestException("'$key' is out of range")
    else -> throw BadRequestException("'$key' must be a number or null")
}

fun Route.zaizaoRoutes(settingsStore: SettingsStore, handler: ZaizaoHandler) {
    route("/zaizao") {
        post("/range") {
            val body = try {
                call.receive<ZaizaoRangeHttpRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            call.respond(HttpStatusCode.OK, zaizaoRange(body))
        }
        post {
            val body = try {
                call.receive<ZaizaoHttpRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            val settings = settingsStore.settingsFlow.value
            val prepared = prepareZaizaoRequest(settings, body)
            if (body.record != false) {
                val requirement = body.requirement.orEmpty()
                settingsStore.update { it.copy(zaizao = it.zaizao.remember(requirement)) }
            }
            call.respond(
                SSEServerContent(
                    call = call,
                    handle = {
                        heartbeat {
                            period = ZAIZAO_HEARTBEAT_PERIOD
                            event = ServerSentEvent(comments = "hb")
                        }
                        runZaizaoStream(
                            prepared = prepared,
                            progress = handler.generate(settings, prepared),
                            sendEvent = { name, data -> send(data = data, event = name) },
                        )
                    },
                )
            )
        }
        get("/config") {
            call.respond(HttpStatusCode.OK, projectZaizaoConfig(settingsStore.settingsFlow.value))
        }
        put("/config") {
            val body = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            settingsStore.settingsFlow.value.let { mergeZaizaoConfig(it.zaizao, body, it) } // validate before writing
            var result: Settings? = null
            settingsStore.update { settings ->
                settings.copy(zaizao = mergeZaizaoConfig(settings.zaizao, body, settings)).also { result = it }
            }
            call.respond(HttpStatusCode.OK, projectZaizaoConfig(result ?: settingsStore.settingsFlow.value))
        }
    }
}

/**
 * SSE seam (testable without Ktor or a model): prepared → update … (at most one per [updateInterval]) → done, or
 * error {code, message} (model failure, empty result, timeout). A failed write (client gone) propagates so the
 * upstream generation is cancelled.
 */
internal suspend fun runZaizaoStream(
    prepared: ZaizaoPrepared,
    progress: Flow<ZaizaoProgress>,
    streamTimeout: Duration = ZAIZAO_STREAM_TIMEOUT,
    updateInterval: Duration = ZAIZAO_UPDATE_INTERVAL,
    sendEvent: suspend (eventName: String, jsonData: String) -> Unit,
) {
    sendEvent("prepared", JsonInstant.encodeToString(
        ZaizaoPreparedEvent(prepared.start, prepared.end, prepared.original, prepared.format.name)))
    try {
        var lastSent: TimeSource.Monotonic.ValueTimeMark? = null
        var finished = false
        withTimeout(streamTimeout) {
            progress.collect { step ->
                val (name, data) = if (step.done) {
                    finished = true
                    "done" to JsonInstant.encodeToString(ZaizaoDoneEvent(step.reasoning, step.plan, step.result, step.output))
                } else {
                    val mark = lastSent
                    if (mark != null && mark.elapsedNow() < updateInterval) return@collect
                    lastSent = TimeSource.Monotonic.markNow()
                    "update" to JsonInstant.encodeToString(ZaizaoUpdateEvent(step.reasoning, step.plan, step.result))
                }
                try {
                    sendEvent(name, data)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw ZaizaoWriteFailedException(e)
                }
            }
        }
        if (!finished) sendZaizaoErrorSafely(sendEvent, ZaizaoException.Reason.EMPTY_RESULT.name, ZaizaoException.Reason.EMPTY_RESULT.name)
    } catch (e: TimeoutCancellationException) {
        sendZaizaoErrorSafely(sendEvent, "TIMEOUT", "Timed out")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ZaizaoWriteFailedException) {
        // Client gone: propagate the original failure (stack-trace recovery may have wrapped the wrapper).
        throw generateSequence<Throwable>(e) { it.cause }.firstOrNull { it !is ZaizaoWriteFailedException } ?: e
    } catch (e: ZaizaoException) {
        sendZaizaoErrorSafely(sendEvent, e.reason.name, e.reason.name)
    } catch (e: Throwable) {
        sendZaizaoErrorSafely(sendEvent, "MODEL_ERROR", sanitizePublicError(e.message))
    }
}

private class ZaizaoWriteFailedException(cause: Throwable) : Exception(cause)

private suspend fun sendZaizaoErrorSafely(
    sendEvent: suspend (eventName: String, jsonData: String) -> Unit,
    code: String,
    message: String,
) {
    try {
        sendEvent("error", JsonInstant.encodeToString(ZaizaoErrorEvent(code, message)))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Connection already gone; nothing to report to.
    }
}
