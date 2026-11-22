package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.data.ai.qubagu.Qubagu
import me.rerere.rikkihub.data.ai.qubagu.QubaguConfig
import me.rerere.rikkihub.data.ai.qubagu.QubaguMode
import me.rerere.rikkihub.data.ai.qubagu.QubaguImport
import me.rerere.rikkihub.data.ai.qubagu.QubaguImportChoice
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefine
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefineException
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulePack
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesException
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesJson
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import kotlin.uuid.Uuid

@Serializable
data class QubaguDefaultsDto(val refinePrompt: String)

/** 设置页要的全部东西：当前设置（含全部规则包）和内置默认值（用来显示和恢复）。 */
@Serializable
data class QubaguConfigResponse(val config: QubaguConfig, val defaults: QubaguDefaultsDto)

/**
 * 导入规则文件。[preview] 为 true 时只解析、不保存，返回每个包是否和已有的冲突；
 * 否则按 [choices]（包 id → OVERWRITE / COPY / SKIP，冲突的包缺省为 OVERWRITE）并进设置。
 */
@Serializable
data class QubaguImportRequest(val text: String? = null, val preview: Boolean = false, val choices: Map<String, QubaguImportChoice> = emptyMap())

@Serializable
data class QubaguImportPackDto(val id: String, val name: String, val rules: Int, val conflict: Boolean)

/** [invalid] 是正则写错、导入时被关掉的规则（「包名 › 规则名」）。保存后 [config] 是新设置。 */
@Serializable
data class QubaguImportResponse(
    val packs: List<QubaguImportPackDto>,
    val invalid: List<String>,
    val config: QubaguConfigResponse? = null,
)

@Serializable
data class QubaguTestRequest(val text: String? = null)

@Serializable
data class QubaguEditDto(val before: String, val after: String, val rule: String)

@Serializable
data class QubaguTestResponse(val text: String, val edits: List<QubaguEditDto>)

/**
 * 聊天里对一条回复的操作。apply / restore：[part]、[edit] 都不给 = 全部改动；scan：按现在的规则重新检查；
 * refine：让模型重写命中的句子（等它跑完才返回）。
 */
@Serializable
data class QubaguMessageRequest(
    val conversationId: String? = null,
    val messageId: String? = null,
    val action: String? = null,
    val part: Int? = null,
    val edit: Int? = null,
)

@Serializable
data class QubaguScanResponse(val found: Int)

internal const val QUBAGU_MAX_TEST_CHARS = 100_000
internal const val QUBAGU_MAX_IMPORT_CHARS = 4_000_000

internal fun projectQubaguConfig(settings: Settings): QubaguConfigResponse =
    QubaguConfigResponse(config = settings.qubagu, defaults = QubaguDefaultsDto(QubaguRefine.DEFAULT_PROMPT))

/**
 * 按请求里出现的键改设置（键名和取值同 [QubaguConfig] 的 JSON），其余沿用当前值；`packs` 整表替换。
 * 未知键、类型不对、超出范围、规则包不合格（见 [QubaguRulesJson.validate]）、不存在的模型、编译不了的正则都是 400。
 */
internal suspend fun mergeQubaguConfig(config: QubaguConfig, body: JsonObject, settings: Settings, engine: Qubagu): QubaguConfig {
    val current = JsonInstant.encodeToJsonElement(config).jsonObject
    val unknown = body.keys - current.keys
    if (unknown.isNotEmpty()) throw BadRequestException("Unknown keys: ${unknown.sorted().joinToString()}")
    val merged = try {
        JsonInstant.decodeFromJsonElement<QubaguConfig>(JsonObject(current + body))
    } catch (e: SerializationException) {
        throw BadRequestException("Invalid value: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
    } catch (e: IllegalArgumentException) {
        throw BadRequestException("Invalid value: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
    }
    if (merged.mergeMaxChars !in QubaguConfig.MERGE_MAX_CHARS_RANGE) throw BadRequestException("'mergeMaxChars' is out of range")
    QubaguRulesJson.validate(merged.packs)?.let { throw BadRequestException(it) }
    if ("packs" in body) {
        for (pack in merged.packs) for (rule in pack.rules) {
            engine.check(rule)?.let { throw BadRequestException("Invalid regular expression in rule '${rule.id}' of pack '${pack.id}': $it") }
        }
    }
    if (merged.refineModelId != null && merged.refineModelId != config.refineModelId) {
        val model = settings.findModelById(merged.refineModelId)
        if (model == null || model.type != ModelType.CHAT) throw BadRequestException("Unknown chat model")
    }
    if ((merged.refinePrompt?.length ?: 0) > QubaguConfig.MAX_REFINE_PROMPT_CHARS) throw BadRequestException("'refinePrompt' is too long")
    return merged.copy(refinePrompt = merged.refinePrompt?.takeIf { it.isNotBlank() && it != QubaguRefine.DEFAULT_PROMPT })
}

/** 解析规则文件并关掉写错正则的规则；文件不合格是 400 `QUBAGU_RULES_<原因>`。 */
internal suspend fun readQubaguRules(text: String, engine: Qubagu): QubaguImport {
    if (text.length > QUBAGU_MAX_IMPORT_CHARS) throw BadRequestException("'text' is too long")
    val packs = try {
        QubaguRulesJson.decode(text)
    } catch (e: QubaguRulesException) {
        throw CodedApiException(HttpStatusCode.BadRequest, "QUBAGU_RULES_${e.reason.name}", e.detail.ifEmpty { e.reason.name })
    }
    return QubaguRulesJson.checkRegex(packs, engine)
}

private suspend inline fun <reified T : Any> io.ktor.server.routing.RoutingCall.receiveBody(): T = try {
    receive<T>()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    throw BadRequestException("Invalid request body")
}

private fun parseUuid(raw: String?, name: String): Uuid =
    raw?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: throw BadRequestException("Invalid '$name'")

/**
 * 去八股的网页接口：`GET|PUT /qubagu/config`（设置页，含总开关 `enabled`）、`POST /qubagu/import`、
 * `GET /qubagu/export[?pack=]`（规则文件）、`POST /qubagu/test`（试一试）、`POST /qubagu/messages`（聊天里的改动清单；需要 [chatService]）。
 */
fun Route.qubaguRoutes(
    settingsStore: SettingsStore,
    engine: Qubagu,
    chatService: ChatService?,
    conversationRepo: ConversationRepository,
) {
    route("/qubagu") {
        get("/config") {
            call.respond(HttpStatusCode.OK, projectQubaguConfig(settingsStore.settingsFlow.value))
        }
        put("/config") {
            val body = call.receiveBody<JsonObject>()
            // 先在当前值上校验一遍（正则要编译，不能放进设置写入的临界区里）
            val validated = settingsStore.settingsFlow.value.let { mergeQubaguConfig(it.qubagu, body, it, engine) }
            var result: Settings? = null
            settingsStore.update { settings ->
                // 写入时在最新值上重放请求里的键；整份替换的键（规则表等）用校验后清理过的值
                val latest = JsonInstant.encodeToJsonElement(settings.qubagu).jsonObject
                val cleaned = JsonInstant.encodeToJsonElement(validated).jsonObject.filterKeys { it in body }
                settings.copy(qubagu = JsonInstant.decodeFromJsonElement<QubaguConfig>(JsonObject(latest + cleaned))).also { result = it }
            }
            call.respond(HttpStatusCode.OK, projectQubaguConfig(result ?: settingsStore.settingsFlow.value))
        }
        post("/import") {
            val body = call.receiveBody<QubaguImportRequest>()
            val imported = readQubaguRules(body.text ?: throw BadRequestException("'text' is required"), engine)
            val existing = settingsStore.settingsFlow.value.qubagu.packs.mapTo(HashSet()) { it.id }
            val summary = imported.packs.map { QubaguImportPackDto(it.id, it.name, it.rules.size, it.id in existing) }
            if (body.preview) {
                call.respond(HttpStatusCode.OK, QubaguImportResponse(summary, imported.invalid))
                return@post
            }
            val choose = { pack: QubaguRulePack -> body.choices[pack.id] ?: QubaguImportChoice.OVERWRITE }
            QubaguRulesJson.validate(QubaguRulesJson.merge(settingsStore.settingsFlow.value.qubagu.packs, imported.packs, choose))
                ?.let { throw BadRequestException(it) }
            var result: Settings? = null
            settingsStore.update { settings ->
                settings.copy(qubagu = settings.qubagu.copy(packs = QubaguRulesJson.merge(settings.qubagu.packs, imported.packs, choose))).also { result = it }
            }
            call.respond(HttpStatusCode.OK, QubaguImportResponse(summary, imported.invalid, projectQubaguConfig(result ?: settingsStore.settingsFlow.value)))
        }
        get("/export") {
            val packs = settingsStore.settingsFlow.value.qubagu.packs
            val chosen = call.request.queryParameters["pack"]?.let { id -> listOf(packs.firstOrNull { it.id == id } ?: throw NotFoundException("Pack not found")) } ?: packs
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "qubagu-rules.json").toString())
            call.respondText(QubaguRulesJson.encode(chosen), ContentType.Application.Json)
        }
        post("/test") {
            val text = call.receiveBody<QubaguTestRequest>().text ?: throw BadRequestException("'text' is required")
            if (text.length > QUBAGU_MAX_TEST_CHARS) throw BadRequestException("'text' is too long")
            val record = engine.clean(text, settingsStore.settingsFlow.value.qubagu.copy(mode = QubaguMode.AUTO))
            call.respond(HttpStatusCode.OK, QubaguTestResponse(record.render(text), record.edits.map { QubaguEditDto(it.before, it.after, it.rule) }))
        }
        if (chatService != null) {
            post("/messages") {
                val body = call.receiveBody<QubaguMessageRequest>()
                val conversationId = parseUuid(body.conversationId, "conversationId")
                val messageId = parseUuid(body.messageId, "messageId")
                if (!conversationRepo.existsConversationById(conversationId)) throw NotFoundException("Conversation not found")
                chatService.initializeConversation(conversationId)
                if (chatService.getConversationFlow(conversationId).value.currentMessages.none { it.id == messageId }) {
                    throw NotFoundException("Message not found")
                }
                when (body.action) {
                    "apply", "restore" -> {
                        if ((body.part == null) != (body.edit == null)) throw BadRequestException("'part' and 'edit' go together")
                        chatService.setQubaguApplied(conversationId, messageId, body.part, body.edit, applied = body.action == "apply")
                        call.respond(HttpStatusCode.NoContent)
                    }
                    "scan" -> call.respond(HttpStatusCode.OK, QubaguScanResponse(chatService.cleanMessageWithQubagu(conversationId, messageId)))
                    "refine" -> {
                        try {
                            chatService.refineMessageWithQubagu(conversationId, messageId)
                        } catch (e: QubaguRefineException) {
                            throw CodedApiException(HttpStatusCode.UnprocessableEntity, "QUBAGU_${e.reason.name}", refineMessage(e.reason))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw CodedApiException(HttpStatusCode.BadGateway, "QUBAGU_REFINE_FAILED", e.message ?: e.javaClass.simpleName)
                        }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    else -> throw BadRequestException("'action' must be one of apply, restore, scan, refine")
                }
            }
        }
    }
}

private fun refineMessage(reason: QubaguRefineException.Reason): String = when (reason) {
    QubaguRefineException.Reason.NO_MODEL -> "No model is available for AI refine"
    QubaguRefineException.Reason.NOTHING_TO_REFINE -> "This reply has no sentences to refine"
    QubaguRefineException.Reason.NO_REWRITES -> "The model did not return any usable rewrite"
}
