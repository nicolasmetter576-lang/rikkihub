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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoEvent
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoException
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoExplanation
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoHandler
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoMode
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPrepared
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPreparer
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPrompts
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoRequest
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoRole
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * 推敲（Web 工作区编辑器）。上下文截取、档位判定、角色分配、提示词、解析与 App 共用
 * [TuiqiaoPreparer] / [TuiqiaoHandler]；客户端只发全文和选区偏移。
 *
 *   POST /api/tuiqiao  {fileName, source, start, end, mode?, expand?, slot?} -> text/event-stream
 *     prepared {start, end, original, mode, format, slots[]}（规整后的实际替换区间；角色在前）
 *     → candidate {slot, text, tone, why} / slot_error {slot, code, message} …
 *     → done；全部候选位都失败时为 error {code, message}
 *     slot：只重跑这一个候选位（卡片上的「重试」）。
 *   GET  /api/tuiqiao/config -> TuiqiaoConfigDto
 *   PUT  /api/tuiqiao/config  merge-patch {candidateCount?, explanation?, roles?, extensions?, prompts?}
 *
 * 选区问题（空、太长、越界、未知候选位）在开流前返回 400；模型问题走 SSE 事件。
 */
internal const val TUIQIAO_MAX_SOURCE_CHARS = 1_048_576
internal val TUIQIAO_STREAM_TIMEOUT = 180.seconds
private val TUIQIAO_HEARTBEAT_PERIOD = 15.seconds
private const val TUIQIAO_MAX_TEMPLATE_CHARS = 4000

@Serializable
data class TuiqiaoHttpRequest(
    val fileName: String? = null,
    val source: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    val mode: String? = null,
    /** "SENTENCE" / "PARAGRAPH": grow the range to the whole sentence / paragraph first (mode re-detected). */
    val expand: String? = null,
    /** Re-run only this slot ([TuiqiaoSlot.key]). */
    val slot: String? = null,
)

@Serializable
data class TuiqiaoSlotDto(val slot: String, val kind: String, val roleId: String? = null, val name: String? = null)

@Serializable
data class TuiqiaoPreparedEvent(
    val start: Int,
    val end: Int,
    val original: String,
    val mode: String,
    val format: String,
    val slots: List<TuiqiaoSlotDto>,
)

@Serializable
data class TuiqiaoCandidateEvent(val slot: String, val text: String, val tone: String, val why: String)

@Serializable
data class TuiqiaoSlotErrorEvent(val slot: String, val code: String, val message: String)

@Serializable
data class TuiqiaoErrorEvent(val code: String, val message: String)

@Serializable
data class TuiqiaoRoleDto(
    val id: String,
    val name: String,
    val prompt: String,
    val modes: List<String>,
    val modelId: String? = null,
    val enabled: Boolean = true,
    /** 指定的模型已不存在，实际用助手模型。 */
    val modelMissing: Boolean = false,
)

@Serializable
data class TuiqiaoConfigDto(
    val candidateCount: Int,
    val explanation: String,
    val roles: List<TuiqiaoRoleDto> = emptyList(),
    val extensions: List<String> = TuiqiaoConfig.DEFAULT_EXTENSIONS,
    val extensionsCustomized: Boolean = false,
    /** 自定义的三档提示词（null = 内置默认）。 */
    val prompts: Map<String, String?> = emptyMap(),
    val defaultPrompts: Map<String, String> = emptyMap(),
    val defaultExtensions: List<String> = TuiqiaoConfig.DEFAULT_EXTENSIONS,
    val availableExtensions: List<String> = emptyList(),
    val minCandidates: Int = TuiqiaoConfig.MIN_CANDIDATES,
    val maxCandidates: Int = TuiqiaoConfig.MAX_CANDIDATES,
    val maxRoles: Int = TuiqiaoConfig.MAX_ROLES,
    val maxRoleNameChars: Int = TuiqiaoRole.MAX_NAME_CHARS,
    val maxRolePromptChars: Int = TuiqiaoRole.MAX_PROMPT_CHARS,
)

private fun TuiqiaoConfig.customPrompt(mode: TuiqiaoMode): String? = when (mode) {
    TuiqiaoMode.WORD -> wordPrompt
    TuiqiaoMode.SENTENCE -> sentencePrompt
    TuiqiaoMode.PARAGRAPH -> paragraphPrompt
}

internal fun projectTuiqiaoConfig(settings: Settings): TuiqiaoConfigDto {
    val config = settings.tuiqiao
    return TuiqiaoConfigDto(
        candidateCount = config.effectiveCandidateCount,
        explanation = config.explanation.name,
        roles = config.roles.map { role ->
            TuiqiaoRoleDto(
                id = role.id.toString(),
                name = role.name,
                prompt = role.prompt,
                modes = TuiqiaoMode.entries.filter { it in role.modes }.map { it.name },
                modelId = role.modelId?.toString(),
                enabled = role.enabled,
                modelMissing = role.modelId != null && settings.findModelById(role.modelId) == null,
            )
        },
        extensions = config.effectiveExtensions,
        extensionsCustomized = config.extensions != null,
        prompts = TuiqiaoMode.entries.associate { it.name to config.customPrompt(it)?.takeIf { p -> p.isNotBlank() } },
        defaultPrompts = TuiqiaoMode.entries.associate { it.name to TuiqiaoPrompts.defaultFor(it) },
        availableExtensions = TuiqiaoConfig.AVAILABLE_EXTENSIONS.sorted(),
    )
}

/** 校验请求并规整选区；任何问题都是 400。 */
internal fun prepareTuiqiaoRequest(settings: Settings, body: TuiqiaoHttpRequest): TuiqiaoPrepared {
    val source = body.source ?: throw BadRequestException("Missing 'source'")
    if (source.length > TUIQIAO_MAX_SOURCE_CHARS) throw BadRequestException("'source' is too long")
    val start = body.start ?: throw BadRequestException("Missing 'start'")
    val end = body.end ?: throw BadRequestException("Missing 'end'")
    if (start < 0 || end > source.length || start >= end) throw BadRequestException("Invalid selection range")
    val mode = body.mode?.let { raw ->
        TuiqiaoMode.entries.firstOrNull { it.name == raw } ?: throw BadRequestException("Invalid 'mode'")
    }
    val fileName = body.fileName?.trim()?.take(255).orEmpty().ifEmpty { "untitled.md" }
    val format = TuiqiaoFormat.of(fileName)
    val range = when (body.expand) {
        null -> start until end
        "SENTENCE" -> Tuiqiao.expandToSentence(source, start, end, format)
        "PARAGRAPH" -> Tuiqiao.expandToParagraph(source, start, end, format)
        else -> throw BadRequestException("Invalid 'expand'")
    }
    val prepared = try {
        TuiqiaoPreparer.prepare(settings.tuiqiao,
            TuiqiaoRequest(fileName, source, range.first, range.last + 1, if (body.expand != null) null else mode))
    } catch (e: TuiqiaoException) {
        throw BadRequestException(
            when (e.reason) {
                TuiqiaoException.Reason.SELECTION_TOO_LONG -> "Selection is too long"
                else -> "Selection is empty"
            }
        )
    }
    val slot = body.slot ?: return prepared
    return prepared.only(slot) ?: throw BadRequestException("Unknown 'slot'")
}

private val TUIQIAO_CONFIG_KEYS = setOf("candidateCount", "explanation", "roles", "extensions", "prompts")

internal fun mergeTuiqiaoConfig(config: TuiqiaoConfig, body: JsonObject, settings: Settings): TuiqiaoConfig {
    val unknown = body.keys - TUIQIAO_CONFIG_KEYS
    if (unknown.isNotEmpty()) throw BadRequestException("Unknown keys: ${unknown.sorted().joinToString()}")
    var result = config
    body["candidateCount"]?.let { value ->
        val count = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw BadRequestException("'candidateCount' must be an integer")
        if (count !in TuiqiaoConfig.MIN_CANDIDATES..TuiqiaoConfig.MAX_CANDIDATES) {
            throw BadRequestException("'candidateCount' must be ${TuiqiaoConfig.MIN_CANDIDATES}..${TuiqiaoConfig.MAX_CANDIDATES}")
        }
        result = result.copy(candidateCount = count)
    }
    body["explanation"]?.let { value ->
        val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val explanation = TuiqiaoExplanation.entries.firstOrNull { it.name == name }
            ?: throw BadRequestException("'explanation' must be one of ${TuiqiaoExplanation.entries.joinToString { it.name }}")
        result = result.copy(explanation = explanation)
    }
    body["roles"]?.let { value ->
        val array = value as? JsonArray ?: throw BadRequestException("'roles' must be an array")
        if (array.size > TuiqiaoConfig.MAX_ROLES) throw BadRequestException("At most ${TuiqiaoConfig.MAX_ROLES} roles")
        val roles = array.map { parseTuiqiaoRole(it, config, settings) }
        if (roles.map { it.id }.toSet().size != roles.size) throw BadRequestException("Duplicate role id")
        result = result.copy(roles = roles)
    }
    if ("extensions" in body) {
        val value = body["extensions"]
        result = result.copy(extensions = when (value) {
            null, JsonNull -> null
            is JsonArray -> value.map { item ->
                val raw = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw BadRequestException("'extensions' must be strings")
                TuiqiaoConfig.normalizeExtension(raw) ?: throw BadRequestException("Unsupported extension: $raw")
            }.distinct()
            else -> throw BadRequestException("'extensions' must be an array or null")
        })
    }
    body["prompts"]?.let { value ->
        val obj = value as? JsonObject ?: throw BadRequestException("'prompts' must be an object")
        for ((key, prompt) in obj) {
            val mode = TuiqiaoMode.entries.firstOrNull { it.name == key } ?: throw BadRequestException("Unknown prompt: $key")
            val text = when (prompt) {
                JsonNull -> null
                is JsonPrimitive -> prompt.takeIf { it.isString }?.content ?: throw BadRequestException("Prompt '$key' must be a string")
                else -> throw BadRequestException("Prompt '$key' must be a string")
            }
            if (text != null && text.length > TUIQIAO_MAX_TEMPLATE_CHARS) throw BadRequestException("Prompt '$key' is too long")
            result = result.withPrompt(mode, text?.takeIf { it.isNotBlank() })
        }
    }
    return result
}

/** 角色：名称、要求非空且不超长，应用位置至少一个；模型必须是现有对话模型（原样保留已删除的旧值）。 */
private fun parseTuiqiaoRole(element: JsonElement, current: TuiqiaoConfig, settings: Settings): TuiqiaoRole {
    val obj = element as? JsonObject ?: throw BadRequestException("Each role must be an object")
    fun string(key: String): String? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> v.takeIf { it.isString }?.content ?: throw BadRequestException("Role '$key' must be a string")
        else -> throw BadRequestException("Role '$key' must be a string")
    }
    val id = string("id")?.let { raw ->
        runCatching { Uuid.parse(raw) }.getOrNull() ?: throw BadRequestException("Invalid role id")
    } ?: Uuid.random()
    val name = string("name")?.trim().orEmpty()
    if (name.isEmpty() || name.length > TuiqiaoRole.MAX_NAME_CHARS) {
        throw BadRequestException("Role name must be 1..${TuiqiaoRole.MAX_NAME_CHARS} characters")
    }
    val prompt = string("prompt")?.trim().orEmpty()
    if (prompt.isEmpty() || prompt.length > TuiqiaoRole.MAX_PROMPT_CHARS) {
        throw BadRequestException("Role prompt must be 1..${TuiqiaoRole.MAX_PROMPT_CHARS} characters")
    }
    val modes = (obj["modes"] as? JsonArray ?: throw BadRequestException("Role 'modes' must be an array")).map { item ->
        val raw = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
        TuiqiaoMode.entries.firstOrNull { it.name == raw } ?: throw BadRequestException("Invalid role mode: $raw")
    }.toSet()
    if (modes.isEmpty()) throw BadRequestException("Role needs at least one mode")
    val modelId = string("modelId")?.let { raw ->
        val uuid = runCatching { Uuid.parse(raw) }.getOrNull() ?: throw BadRequestException("Invalid role modelId")
        val unchanged = current.roles.any { it.id == id && it.modelId == uuid }
        val model = settings.findModelById(uuid)
        if (!unchanged && (model == null || model.type != ModelType.CHAT)) throw BadRequestException("Unknown chat model")
        uuid
    }
    val enabled = when (val v = obj["enabled"]) {
        null -> true
        is JsonPrimitive -> v.booleanOrNull ?: throw BadRequestException("Role 'enabled' must be a boolean")
        else -> throw BadRequestException("Role 'enabled' must be a boolean")
    }
    return TuiqiaoRole(id = id, name = name, prompt = prompt, modes = modes, modelId = modelId, enabled = enabled)
}

fun Route.tuiqiaoRoutes(settingsStore: SettingsStore, handler: TuiqiaoHandler) {
    route("/tuiqiao") {
        post {
            val body = try {
                call.receive<TuiqiaoHttpRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            val settings = settingsStore.settingsFlow.value
            val prepared = prepareTuiqiaoRequest(settings, body)
            call.respond(
                SSEServerContent(
                    call = call,
                    handle = {
                        heartbeat {
                            period = TUIQIAO_HEARTBEAT_PERIOD
                            event = ServerSentEvent(comments = "hb")
                        }
                        runTuiqiaoStream(
                            prepared = prepared,
                            events = handler.events(settings, prepared),
                            sendEvent = { name, data -> send(data = data, event = name) },
                        )
                    },
                )
            )
        }
        get("/config") {
            call.respond(HttpStatusCode.OK, projectTuiqiaoConfig(settingsStore.settingsFlow.value))
        }
        put("/config") {
            val body = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            settingsStore.settingsFlow.value.let { mergeTuiqiaoConfig(it.tuiqiao, body, it) } // validate before writing
            var result: Settings? = null
            settingsStore.update { settings ->
                settings.copy(tuiqiao = mergeTuiqiaoConfig(settings.tuiqiao, body, settings)).also { result = it }
            }
            call.respond(HttpStatusCode.OK, projectTuiqiaoConfig(result ?: settingsStore.settingsFlow.value))
        }
    }
}

/**
 * SSE seam (testable without Ktor or a model): prepared → candidate / slot_error … → done, or error {code, message}
 * when every slot failed (or on timeout). A failed write (client gone) propagates so the upstream generation is cancelled.
 */
internal suspend fun runTuiqiaoStream(
    prepared: TuiqiaoPrepared,
    events: Flow<TuiqiaoEvent>,
    streamTimeout: Duration = TUIQIAO_STREAM_TIMEOUT,
    sendEvent: suspend (eventName: String, jsonData: String) -> Unit,
) {
    sendEvent("prepared", JsonInstant.encodeToString(TuiqiaoPreparedEvent(
        start = prepared.start,
        end = prepared.end,
        original = prepared.original,
        mode = prepared.mode.name,
        format = prepared.format.name,
        slots = prepared.slots.map { slot ->
            TuiqiaoSlotDto(slot.key, if (slot.role == null) "default" else "role", slot.role?.id?.toString(), slot.role?.name)
        },
    )))
    val failures = mutableListOf<TuiqiaoEvent.SlotFailed>()
    try {
        withTimeout(streamTimeout) {
            events.collect { event ->
                val (name, data) = when (event) {
                    is TuiqiaoEvent.Candidate -> "candidate" to JsonInstant.encodeToString(
                        TuiqiaoCandidateEvent(event.slot, event.candidate.text, event.candidate.tone, event.candidate.why))
                    is TuiqiaoEvent.SlotFailed -> {
                        failures += event
                        val (code, message) = slotFailureCode(event)
                        "slot_error" to JsonInstant.encodeToString(TuiqiaoSlotErrorEvent(event.slot, code, message))
                    }
                }
                try {
                    sendEvent(name, data)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw TuiqiaoWriteFailedException(e)
                }
            }
        }
        if (prepared.slots.isNotEmpty() && failures.size >= prepared.slots.size) {
            val (code, message) = slotFailureCode(failures.first())
            sendErrorSafely(sendEvent, code, message)
        } else {
            sendEvent("done", "{}")
        }
    } catch (e: TimeoutCancellationException) {
        sendErrorSafely(sendEvent, "TIMEOUT", "Timed out")
    } catch (e: CancellationException) {
        throw e
    } catch (e: TuiqiaoWriteFailedException) {
        // Client gone: propagate the original failure (stack-trace recovery may have wrapped the wrapper).
        throw generateSequence<Throwable>(e) { it.cause }.firstOrNull { it !is TuiqiaoWriteFailedException } ?: e
    } catch (e: Throwable) {
        sendErrorSafely(sendEvent, "MODEL_ERROR", sanitizePublicError(e.message))
    }
}

private fun slotFailureCode(failure: TuiqiaoEvent.SlotFailed): Pair<String, String> =
    failure.reason?.let { it.name to it.name } ?: ("MODEL_ERROR" to sanitizePublicError(failure.message))

private class TuiqiaoWriteFailedException(cause: Throwable) : Exception(cause)

private suspend fun sendErrorSafely(
    sendEvent: suspend (eventName: String, jsonData: String) -> Unit,
    code: String,
    message: String,
) {
    try {
        sendEvent("error", JsonInstant.encodeToString(TuiqiaoErrorEvent(code, message)))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Connection already gone; nothing to report to.
    }
}
