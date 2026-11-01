package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.heartbeat
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkihub.data.ai.TranslationHandler
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkihub.data.repository.TranslationHistoryRepository
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * 独立 Web 翻译页（会话外）API。
 *
 * 契约见 /tmp/rikkihub-web-six/TRANSLATOR-CONTRACT.md：
 *   POST /api/translator/translate  body {text, language, modelId?} -> SSE
 *
 * 设计约束：
 * - 真实复用 TranslationHandler.translateText（保留 translatePrompt /
 *   translateThinkingBudget / Qwen-MT 特殊路径）；per-request 模型覆盖通过
 *   `settings.copy(translateModeId = ...)` 传参，绝不写 settingsStore。
 * - 不创建会话 / 不写消息历史（不碰 conversationRepo / ChatService）；
 *   翻译成功（done）后写一条翻译历史，与 App 翻译页共用（最近 100 条）。
 * - POST + SSE：Ktor `sse{}` 只注册 GET，所以这里用 SSEServerContent 手动响应；
 *   所有校验都在 respond() 之前完成，400 走 StatusPages 返回 JSON。
 * - 断连探知：15s 心跳（写失败会取消 call job -> 取消上游生成），120s 总超时。
 */

internal const val TRANSLATOR_MAX_TEXT_BYTES = 32 * 1024
internal const val TRANSLATOR_MAX_LANGUAGE_TAG_LENGTH = 64
internal val TRANSLATOR_STREAM_TIMEOUT = 120.seconds
internal val TRANSLATOR_HEARTBEAT_PERIOD = 15.seconds
private const val TRANSLATOR_MAX_PUBLIC_ERROR_LENGTH = 280

// ---------- DTO ----------

@Serializable
data class TranslatorTranslateRequest(
    val text: String? = null,
    val language: String? = null,
    /** ProviderModel.id（UUID 字符串）。缺省时使用全局 translateModeId。 */
    val modelId: String? = null,
)

@Serializable
data class TranslatorTextEvent(val text: String)

@Serializable
data class TranslatorDoneEvent(val text: String)

@Serializable
data class TranslatorErrorEvent(val message: String)

@Serializable
data class TranslatorHistoryItemDto(
    val id: String,
    val sourceText: String,
    val translatedText: String,
    val targetLanguage: String,
    val modelId: String,
    val createAt: Long,
)

@Serializable
data class TranslatorHistoryPageDto(
    val items: List<TranslatorHistoryItemDto>,
    val total: Int,
)

internal const val TRANSLATOR_HISTORY_DEFAULT_LIMIT = 20
internal const val TRANSLATOR_HISTORY_MAX_LIMIT = 100

internal fun TranslationHistoryEntity.toDto() = TranslatorHistoryItemDto(
    id = id,
    sourceText = sourceText,
    translatedText = translatedText,
    targetLanguage = targetLanguage,
    modelId = modelId,
    createAt = createAt,
)

/** offset ≥ 0；limit 缺省 20，范围 1..100，非法值 400 */
internal fun parseTranslatorHistoryPage(offset: String?, limit: String?): Pair<Int, Int> {
    val parsedOffset = offset?.let { it.toIntOrNull() ?: throw BadRequestException("Invalid 'offset'") } ?: 0
    val parsedLimit = limit?.let { it.toIntOrNull() ?: throw BadRequestException("Invalid 'limit'") }
        ?: TRANSLATOR_HISTORY_DEFAULT_LIMIT
    if (parsedOffset < 0) throw BadRequestException("'offset' must not be negative")
    if (parsedLimit !in 1..TRANSLATOR_HISTORY_MAX_LIMIT) {
        throw BadRequestException("'limit' must be between 1 and $TRANSLATOR_HISTORY_MAX_LIMIT")
    }
    return parsedOffset to parsedLimit
}

// ---------- 校验 helpers（纯函数，供单测） ----------

/** 校验 text：非空、UTF-8 ≤ 32KiB。返回 trim 之前的原文（保留空白语义给模型）。 */
internal fun validateTranslatorText(text: String?): String {
    if (text == null) throw BadRequestException("Missing 'text'")
    if (text.isBlank()) throw BadRequestException("'text' must not be empty")
    if (text.toByteArray(Charsets.UTF_8).size > TRANSLATOR_MAX_TEXT_BYTES) {
        throw BadRequestException("'text' exceeds ${TRANSLATOR_MAX_TEXT_BYTES} bytes")
    }
    return text
}

/** 严格解析 BCP-47 tag；非法 tag / 无法得到语言子tag时 400。 */
internal fun parseTranslatorTargetLocale(language: String?): Locale {
    val tag = language?.trim().orEmpty()
    if (tag.isEmpty()) throw BadRequestException("Missing 'language'")
    if (tag.length > TRANSLATOR_MAX_LANGUAGE_TAG_LENGTH) {
        throw BadRequestException("'language' tag is too long")
    }
    val locale = runCatching { Locale.Builder().setLanguageTag(tag).build() }.getOrNull()
        ?: throw BadRequestException("Invalid language tag: $tag")
    if (locale.language.isBlank()) {
        throw BadRequestException("Invalid language tag: $tag")
    }
    return locale
}

/**
 * 决定本次请求实际使用的模型。
 *
 * - `modelId` 缺省：用 settings.translateModeId；解析失败给出可操作文案。
 * - `modelId` 提供：必须是合法 UUID、存在、type == CHAT、所属 provider enabled。
 *
 * 不做 settingsStore.update，只返回 Model，由调用方 settings.copy 覆盖。
 */
internal fun resolveTranslatorRequestModel(settings: Settings, modelId: String?): Model {
    if (modelId.isNullOrBlank()) {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: throw BadRequestException(
                "Default translation model is not configured; " +
                    "set a translation model in the app settings or pass 'modelId'"
            )
        if (model.type != ModelType.CHAT) {
            throw BadRequestException(
                "Default translation model is not a chat model; " +
                    "check the app settings or pass 'modelId'"
            )
        }
        return model
    }

    val uuid = runCatching { Uuid.parse(modelId.trim()) }.getOrNull()
        ?: throw BadRequestException("Invalid 'modelId'")
    val model = settings.providers.findModelById(uuid)
        ?: throw BadRequestException("Model not found: $modelId")
    if (model.type != ModelType.CHAT) {
        throw BadRequestException("Model is not a chat model: $modelId")
    }
    return model
}

/** 所属 provider 必须存在且 enabled（model.providerOverwrite 经由 findProvider 生效）。 */
internal fun validateTranslatorModelProvider(settings: Settings, model: Model) {
    val provider = model.findProvider(settings.providers)
        ?: throw BadRequestException("The provider of the selected model was not found")
    if (!provider.enabled) {
        throw BadRequestException("The provider of the selected model is disabled")
    }
}

// ---------- 错误脱敏 ----------

private val SECRET_PATTERNS = listOf(
    Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"),
    Regex("(?i)(api[_-]?key|apikey|access[_-]?token|secret|authorization|password)\\s*[=:]\\s*\\S+"),
    Regex("(?i)\\bsk-[A-Za-z0-9_-]{6,}"),
    Regex("\\bAIza[0-9A-Za-z_-]{10,}"),
    Regex("(?i)\\bkey-[A-Za-z0-9_-]{12,}"),
)

/** 公共错误文案：剥离令牌形态、折叠空白、截断，绝不回显原文/密钥。 */
internal fun sanitizePublicError(raw: String?): String {
    var message = raw?.trim().orEmpty()
    if (message.isEmpty()) return "Translation failed"
    SECRET_PATTERNS.forEach { pattern -> message = pattern.replace(message, "[redacted]") }
    message = message.replace(Regex("\\s+"), " ").trim()
    if (message.isEmpty() || message == "[redacted]") return "Translation failed"
    if (message.length > TRANSLATOR_MAX_PUBLIC_ERROR_LENGTH) {
        message = message.take(TRANSLATOR_MAX_PUBLIC_ERROR_LENGTH).trimEnd() + "…"
    }
    return message
}

// ---------- 路由 ----------

/**
 * 挂载到 `/api` 之下（由 WebApiModule.installApiRoutes 调用，JWT on/off 两处共享）。
 */
fun Route.translatorRoutes(
    settingsStore: SettingsStore,
    translationHandler: TranslationHandler,
    historyRepository: TranslationHistoryRepository? = null,
) {
    route("/translator") {
        if (historyRepository != null) {
            // GET /api/translator/history?offset&limit -> {items, total}，新的在前
            get("/history") {
                val (offset, limit) = parseTranslatorHistoryPage(
                    call.request.queryParameters["offset"],
                    call.request.queryParameters["limit"],
                )
                call.respond(
                    TranslatorHistoryPageDto(
                        items = historyRepository.page(offset, limit).map { it.toDto() },
                        total = historyRepository.count(),
                    )
                )
            }
            // DELETE /api/translator/history -> 清空
            delete("/history") {
                historyRepository.deleteAll()
                call.respond(HttpStatusCode.NoContent)
            }
            // DELETE /api/translator/history/{id}
            delete("/history/{id}") {
                val id = call.parameters["id"].orEmpty()
                if (!historyRepository.deleteById(id)) throw NotFoundException("Translation history not found")
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // POST /api/translator/translate -> text/event-stream
        post("/translate") {
            val request = try {
                call.receive<TranslatorTranslateRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }

            // ---- 响应开始前的全部校验（400 在此阶段返回 JSON） ----
            val sourceText = validateTranslatorText(request.text)
            val targetLocale = parseTranslatorTargetLocale(request.language)
            val settings = settingsStore.settingsFlow.value
            val model = resolveTranslatorRequestModel(settings, request.modelId)
            validateTranslatorModelProvider(settings, model)

            // per-request 覆盖只作用于传给 handler 的副本，不持久化
            val effectiveSettings = settings.copy(translateModeId = model.id)

            // POST 不能用 Ktor 的 sse{}（只注册 GET），用 SSEServerContent 手动开流。
            // Kotlin metadata 确认 handle 是 `suspend ServerSSESession.() -> Unit`（receiver），
            // 与 sse{} 的 body 同一语义；命名参数绑定避免 trailing lambda 误绑 serialize。
            call.respond(
                SSEServerContent(
                    call = call,
                    handle = {
                        serveTranslatorStream(
                            settings = effectiveSettings,
                            sourceText = sourceText,
                            targetLocale = targetLocale,
                            translationHandler = translationHandler,
                            onDone = { translated ->
                                historyRepository?.add(
                                    sourceText = sourceText,
                                    translatedText = translated,
                                    targetLanguage = targetLocale.toLanguageTag(),
                                    modelId = model.id,
                                )
                            },
                        )
                    },
                )
            )
        }
    }
}

private suspend fun ServerSSESession.serveTranslatorStream(
    settings: Settings,
    sourceText: String,
    targetLocale: Locale,
    translationHandler: TranslationHandler,
    onDone: suspend (String) -> Unit,
) {
    // 心跳注释帧：客户端断连后下一次心跳/数据写出失败，取消 call job ->
    // 取消 translateText 的上游收集（不只是在浏览器侧停止显示）。
    heartbeat {
        period = TRANSLATOR_HEARTBEAT_PERIOD
        event = ServerSentEvent(comments = "hb")
    }

    runTranslatorStreamCollect(
        textFlow = translationHandler.translateText(
            settings = settings,
            sourceText = sourceText,
            targetLanguage = targetLocale,
        ),
        sendEvent = { eventName, jsonData -> send(data = jsonData, event = eventName) },
        onDone = onDone,
    )
}

/**
 * 流式收集 seam：注入 Flow + 事件写出回调，事件语义与契约一致
 * （text 累计快照 -> done / error）。可测：取消传播、超时、错误脱敏、
 * 写出失败，都不需要 Ktor server 或真的 TranslationHandler。
 *
 * @param streamTimeout 总超时；超时发 error("Translation timed out") 后正常关流。
 */
internal suspend fun runTranslatorStreamCollect(
    textFlow: Flow<String>,
    sendEvent: suspend (eventName: String, jsonData: String) -> Unit,
    streamTimeout: Duration = TRANSLATOR_STREAM_TIMEOUT,
    onDone: suspend (String) -> Unit = {},
) {
    var lastText = ""
    // 区分两类异常：上游 Flow 抛的 = 业务错误 -> 发 error 事件；
    // sendEvent 自己抛的 = 连接已断 -> 必须传播，终止响应并让上游随 collect 中断。
    // 用哨兵包装 sendEvent，避免写出失败被下游 Throwable catch 误当上游错误。
    val guardedSend: suspend (String, String) -> Unit = { eventName, jsonData ->
        try {
            sendEvent(eventName, jsonData)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw TranslatorWriteFailedException(e)
        }
    }
    try {
        withTimeout(streamTimeout) {
            textFlow.collect { text ->
                lastText = text
                guardedSend("text", JsonInstant.encodeToString(TranslatorTextEvent(text)))
            }
        }
        // 记录翻译历史失败不影响这次翻译的结果
        try {
            onDone(lastText)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        guardedSend("done", JsonInstant.encodeToString(TranslatorDoneEvent(lastText)))
    } catch (e: TimeoutCancellationException) {
        sendEventSafely(sendEvent, "error", JsonInstant.encodeToString(TranslatorErrorEvent("Translation timed out")))
    } catch (e: CancellationException) {
        // 客户端断连 / 服务端关闭：把取消传播出去以真正取消上游生成。
        throw e
    } catch (e: TranslatorWriteFailedException) {
        // 写出失败（断连）：传播原始异常结束响应；上游 Flow 已被 collect 异常中断。
        throw e.cause ?: e
    } catch (e: Throwable) {
        sendEventSafely(
            sendEvent,
            "error",
            JsonInstant.encodeToString(TranslatorErrorEvent(sanitizePublicError(e.message)))
        )
    }
}

private class TranslatorWriteFailedException(cause: Throwable) : Exception(cause)

/**
 * error 事件的写出本身也可能因断连失败；此时静默吞掉（连接已死，无从上报）。
 * CancellationException 不吞：断连取消必须继续向上传播。
 */
private suspend fun sendEventSafely(
    sendEvent: suspend (eventName: String, jsonData: String) -> Unit,
    eventName: String,
    jsonData: String,
) {
    try {
        sendEvent(eventName, jsonData)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // 连接已断，error 事件无法送达
    }
}
