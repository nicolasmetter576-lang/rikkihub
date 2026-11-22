package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ContextCheckpointMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.AutoCompressMode
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionConfig
import me.rerere.rikkihub.data.ai.CompressionTemplate
import me.rerere.rikkihub.data.ai.CompressionTemplates
import me.rerere.rikkihub.data.ai.ConversationCompressionOverride
import me.rerere.rikkihub.data.ai.prompts.builtInCompressStructure
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.service.CarriedOriginalEdit
import me.rerere.rikkihub.service.CarriedOriginalItem
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.service.CompressErrorCodes
import me.rerere.rikkihub.service.CompressOrigin
import me.rerere.rikkihub.service.CompressRejectedException
import me.rerere.rikkihub.service.CompressRequest
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.service.CompressSuggestion
import me.rerere.rikkihub.service.CompressionEstimate
import me.rerere.rikkihub.service.ContextCheckpointRecord
import me.rerere.rikkihub.service.ContextRecordMessagePreview
import me.rerere.rikkihub.service.ContextUsageBreakdown
import me.rerere.rikkihub.service.canClearContext
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import kotlin.uuid.Uuid

/** Wire form of [CompressState]; also the payload of the conversation SSE `compress_state` event. */
@Serializable
data class CompressStateDto(
    val conversationId: String,
    /** running | awaiting_review | succeeded | failed | cancelled */
    val status: String,
    /** app | web | auto */
    val origin: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** awaiting_review 时待确认的摘要 */
    val reviewSummary: String? = null,
    val requestCount: Int = 0,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val chunked: Boolean = false,
    val fromOriginal: Boolean = false,
    val cacheBypassed: Boolean = false,
    val carriedCount: Int = 0,
    val createNewConversation: Boolean = false,
    val targetConversationId: String? = null,
    val totalUsage: TokenUsage? = null,
)

@Serializable
data class CompressStateResponse(val state: CompressStateDto? = null)

@Serializable
data class CarriedOriginalEditDto(
    val userText: String? = null,
    val replyText: String? = null,
)

/** 可选：重点要求、保留条数、模板、先看再采用、从原文重压、原话带上选项与单条编辑/移除。 */
@Serializable
data class CompressStartRequest(
    val additionalPrompt: String? = null,
    val keepRecent: Int? = null,
    val templateId: String? = null,
    val review: Boolean? = null,
    val fromOriginal: Boolean? = null,
    val carryMode: String? = null,
    val carryCount: Int? = null,
    val carryIncludeReplies: Boolean? = null,
    val carryExcludedNodeIds: List<String>? = null,
    val carryEdits: Map<String, CarriedOriginalEditDto>? = null,
    val createNewConversation: Boolean? = null,
)

/** POST .../compress/estimate 请求体：与发起压缩使用同一套参数来算本地预估与原话列表。 */
@Serializable
data class CompressEstimateRequest(
    val additionalPrompt: String? = null,
    val keepRecent: Int? = null,
    val templateId: String? = null,
    val fromOriginal: Boolean? = null,
    val carryMode: String? = null,
    val carryCount: Int? = null,
    val carryIncludeReplies: Boolean? = null,
    val carryExcludedNodeIds: List<String>? = null,
    val carryEdits: Map<String, CarriedOriginalEditDto>? = null,
)

@Serializable
data class CarriedOriginalPreviewDto(
    val nodeId: String,
    val userText: String,
    val replyText: String? = null,
    val tokens: Int,
    val edited: Boolean = false,
)

/** 「先看再采用」的决定：summary 为 null 时丢弃，否则用它（可改过）插入检查点。 */
@Serializable
data class CompressReviewRequest(val summary: String? = null)

/** 压缩前的本地预估（不调模型）；会话未加载或没有可压缩的消息时 estimate 为 null。 */
@Serializable
data class CompressEstimateDto(
    val summarizedMessages: Int,
    val summarizedChars: Int,
    val summarizedTokens: Int,
    val keptMessages: Int,
    val keptTokens: Int,
    val beforeTokens: Int,
    val beforeExact: Boolean,
    val afterMinTokens: Int,
    val afterMaxTokens: Int,
    val budgetTokens: Int? = null,
    val carriedCount: Int = 0,
    val carriedTokens: Int = 0,
    val carriedOverBudget: Boolean = false,
    val carriedSkippedForBudget: Int = 0,
    val carriedExcludedCount: Int = 0,
    val carriedItems: List<CarriedOriginalPreviewDto> = emptyList(),
    val hasPreviousCheckpoint: Boolean = false,
    val fromOriginal: Boolean = false,
    val willChunk: Boolean = false,
    val estimatedChunks: Int = 1,
    val maxChunkRequests: Int = CompressionConfig.DEFAULT_MAX_CHUNK_REQUESTS,
    val willBreakCache: Boolean = false,
)

@Serializable
data class CompressEstimateResponse(val estimate: CompressEstimateDto? = null)

@Serializable
data class ContextRecordMessagePreviewDto(
    val nodeId: String,
    val messageId: String,
    val nodeIndex: Int,
    val role: String,
    val previewText: String,
)

@Serializable
data class ContextCheckpointRecordDto(
    val nodeId: String,
    val messageId: String,
    val nodeIndex: Int,
    val createdAt: String,
    val isClear: Boolean,
    val summaryText: String,
    val meta: ContextCheckpointMetadata? = null,
    val rangeStartIndex: Int? = null,
    val rangeEndIndex: Int? = null,
    val rangeStartNodeId: String? = null,
    val rangeEndNodeId: String? = null,
    val coveredMessageCount: Int,
    val coveredPreviews: List<ContextRecordMessagePreviewDto> = emptyList(),
    val isActive: Boolean,
    val laterCheckpointNodeIds: List<String> = emptyList(),
    val supersededByNodeId: String? = null,
)

@Serializable
data class ContextRecordsResponse(
    val records: List<ContextCheckpointRecordDto>,
    val canClear: Boolean,
)

@Serializable
data class ClearContextResponse(
    val record: ContextCheckpointRecordDto,
    val canClear: Boolean,
)

@Serializable
data class RevokeCheckpointResponse(
    val removedNodeIds: List<String>,
)

@Serializable
data class ContextUsageRequest(
    val draftText: String = "",
    val draftImageCount: Int = 0,
)

@Serializable
data class ContextUsageBreakdownDto(
    val totalTokens: Int,
    val totalExact: Boolean,
    val reportedContextTokens: Int? = null,
    val estimatedTotalTokens: Int,
    val contextLength: Int? = null,
    val inputBudgetTokens: Int? = null,
    val systemPromptTokens: Int,
    val memoryTokens: Int,
    val lorebookAndTavernTokens: Int,
    val toolsLocalTokens: Int,
    val toolsMcpTokens: Int,
    val toolsSkillsTokens: Int,
    val toolsWorkspaceTokens: Int,
    val historyCheckpointTokens: Int,
    val historyMessagesTokens: Int,
    val attachmentsTokens: Int,
    val draftTokens: Int,
    val toolsLocalCount: Int,
    val toolsMcpCount: Int,
    val toolsSkillsCount: Int,
    val toolsWorkspaceCount: Int,
    val historyMessageCount: Int,
    val hasActiveCheckpoint: Boolean,
    val attachmentCount: Int,
    val activeLorebookCount: Int,
    val memoryFileCount: Int,
)

/** 「仅提醒」档位的提示（SSE `compress_suggestion`；null = 清掉）。 */
@Serializable
data class CompressSuggestionEvent(
    val type: String = "compress_suggestion",
    val conversationId: String,
    val percent: Int? = null,
    val exact: Boolean = false,
)

/** 会话自己的压缩设置；字段为 null 表示跟随全局。 */
@Serializable
data class ConversationCompressionDto(val mode: String? = null, val templateId: String? = null)

@Serializable
data class CompressionTemplateDto(val id: String, val name: String, val prompt: String)

@Serializable
data class BuiltInCompressionTemplateDto(val id: String, val prompt: String)

@Serializable
data class CompressionConfigDto(
    /** off | notify | auto */
    val mode: String,
    val triggerPercent: Int,
    val targetPercent: Int,
    val keepRecent: Int,
    val templateId: String,
    val summaryTokens: Int,
    /** null = 跟随对话模型 */
    val modelId: String? = null,
    /** 指定的压缩模型已不存在，实际用对话模型 */
    val modelMissing: Boolean = false,
    val reviewBeforeApply: Boolean,
    /** off | recent | auto */
    val carryOriginalsMode: String = CarryOriginalsMode.OFF.wire(),
    val carryOriginalsCount: Int = CompressionConfig.DEFAULT_CARRY_COUNT,
    val carryIncludeReplies: Boolean = false,
    val maxChunkRequests: Int = CompressionConfig.DEFAULT_MAX_CHUNK_REQUESTS,
    /** null = 跟随全局网络超时 */
    val chunkTimeoutSeconds: Int? = null,
    val customTemplates: List<CompressionTemplateDto>,
    val builtInTemplates: List<BuiltInCompressionTemplateDto>,
    val minTriggerPercent: Int = CompressionConfig.MIN_TRIGGER_PERCENT,
    val maxTriggerPercent: Int = CompressionConfig.MAX_TRIGGER_PERCENT,
    val minTargetPercent: Int = CompressionConfig.MIN_TARGET_PERCENT,
    val maxKeepRecent: Int = CompressionConfig.MAX_KEEP_RECENT,
    val minSummaryTokens: Int = CompressionConfig.MIN_SUMMARY_TOKENS,
    val maxSummaryTokens: Int = CompressionConfig.MAX_SUMMARY_TOKENS,
    val maxCustomTemplates: Int = CompressionConfig.MAX_CUSTOM_TEMPLATES,
    val maxTemplateName: Int = CompressionConfig.MAX_TEMPLATE_NAME,
    val maxTemplatePrompt: Int = CompressionConfig.MAX_TEMPLATE_PROMPT,
    val minCarryCount: Int = CompressionConfig.MIN_CARRY_COUNT,
    val maxCarryCount: Int = CompressionConfig.MAX_CARRY_COUNT,
    val minMaxChunkRequests: Int = CompressionConfig.MIN_MAX_CHUNK_REQUESTS,
    val maxMaxChunkRequests: Int = CompressionConfig.MAX_MAX_CHUNK_REQUESTS,
    val minChunkTimeoutSeconds: Int = CompressionConfig.MIN_CHUNK_TIMEOUT_SECONDS,
    val maxChunkTimeoutSeconds: Int = CompressionConfig.MAX_CHUNK_TIMEOUT_SECONDS,
)

internal fun AutoCompressMode.wire(): String = name.lowercase()

internal fun CarryOriginalsMode.wire(): String = name.lowercase()

private fun parseMode(raw: String): AutoCompressMode =
    AutoCompressMode.entries.firstOrNull { it.wire() == raw } ?: throw BadRequestException("Unknown mode '$raw'")

internal fun parseCarryMode(raw: String): CarryOriginalsMode =
    CarryOriginalsMode.entries.firstOrNull { it.wire() == raw }
        ?: throw BadRequestException("Unknown carryMode '$raw'")

private fun parseNodeUuid(raw: String, field: String): Uuid =
    runCatching { Uuid.parse(raw) }.getOrNull() ?: throw BadRequestException("Invalid UUID in '$field'")

internal fun projectCompressionConfig(settings: Settings): CompressionConfigDto {
    val config = settings.compression.normalized()
    return CompressionConfigDto(
        mode = config.mode.wire(),
        triggerPercent = config.triggerPercent,
        targetPercent = config.targetPercent,
        keepRecent = config.keepRecent,
        templateId = config.templateId.takeIf { config.templateExists(it) } ?: CompressionTemplates.GENERAL,
        summaryTokens = config.summaryTokens,
        modelId = config.modelId?.toString(),
        modelMissing = config.modelId != null && settings.findModelById(config.modelId) == null,
        reviewBeforeApply = config.reviewBeforeApply,
        carryOriginalsMode = config.carryOriginalsMode.wire(),
        carryOriginalsCount = config.carryOriginalsCount,
        carryIncludeReplies = config.carryIncludeReplies,
        maxChunkRequests = config.maxChunkRequests,
        chunkTimeoutSeconds = config.chunkTimeoutSeconds,
        customTemplates = config.customTemplates.map { CompressionTemplateDto(it.id, it.name, it.prompt) },
        builtInTemplates = CompressionTemplates.BUILT_IN.map { BuiltInCompressionTemplateDto(it, builtInCompressStructure(it)) },
    )
}

private val COMPRESSION_CONFIG_KEYS = setOf(
    "mode", "triggerPercent", "targetPercent", "keepRecent", "templateId", "summaryTokens", "modelId",
    "customTemplates", "reviewBeforeApply",
    "carryOriginalsMode", "carryOriginalsCount", "carryIncludeReplies", "maxChunkRequests", "chunkTimeoutSeconds",
)

private fun JsonObject.int(key: String, range: IntRange): Int? = this[key]?.let { value ->
    val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: throw BadRequestException("'$key' must be an integer")
    if (number !in range) throw BadRequestException("'$key' must be ${range.first}..${range.last}")
    number
}

private fun JsonObject.string(key: String): String? = this[key]?.let { value ->
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BadRequestException("'$key' must be a string")
}

private fun JsonObject.bool(key: String): Boolean? = this[key]?.let { value ->
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: throw BadRequestException("'$key' must be a boolean")
}

/** merge-patch：只改给出的键；未知键、越界、指向不存在的模板或模型都是 400。删掉默认模板时回到通用。 */
internal fun mergeCompressionConfig(config: CompressionConfig, body: JsonObject, settings: Settings): CompressionConfig {
    val unknown = body.keys - COMPRESSION_CONFIG_KEYS
    if (unknown.isNotEmpty()) throw BadRequestException("Unknown keys: ${unknown.sorted().joinToString()}")
    var result = config
    body.string("mode")?.let { result = result.copy(mode = parseMode(it)) }
    body.int("triggerPercent", CompressionConfig.MIN_TRIGGER_PERCENT..CompressionConfig.MAX_TRIGGER_PERCENT)?.let {
        result = result.copy(triggerPercent = it)
    }
    body.int("targetPercent", CompressionConfig.MIN_TARGET_PERCENT..CompressionConfig.MAX_TRIGGER_PERCENT)?.let {
        result = result.copy(targetPercent = it)
    }
    if (result.targetPercent > result.triggerPercent - 5) {
        throw BadRequestException("'targetPercent' must be at least 5 below 'triggerPercent'")
    }
    body.int("keepRecent", 0..CompressionConfig.MAX_KEEP_RECENT)?.let { result = result.copy(keepRecent = it) }
    body.int("summaryTokens", CompressionConfig.MIN_SUMMARY_TOKENS..CompressionConfig.MAX_SUMMARY_TOKENS)?.let {
        result = result.copy(summaryTokens = it)
    }
    body.bool("reviewBeforeApply")?.let { result = result.copy(reviewBeforeApply = it) }
    body.string("carryOriginalsMode")?.let { result = result.copy(carryOriginalsMode = parseCarryMode(it)) }
    body.int("carryOriginalsCount", CompressionConfig.MIN_CARRY_COUNT..CompressionConfig.MAX_CARRY_COUNT)?.let {
        result = result.copy(carryOriginalsCount = it)
    }
    body.bool("carryIncludeReplies")?.let { result = result.copy(carryIncludeReplies = it) }
    body.int("maxChunkRequests", CompressionConfig.MIN_MAX_CHUNK_REQUESTS..CompressionConfig.MAX_MAX_CHUNK_REQUESTS)?.let {
        result = result.copy(maxChunkRequests = it)
    }
    if ("chunkTimeoutSeconds" in body) {
        result = result.copy(
            chunkTimeoutSeconds = when (body["chunkTimeoutSeconds"]) {
                null, JsonNull -> null
                else -> body.int(
                    "chunkTimeoutSeconds",
                    CompressionConfig.MIN_CHUNK_TIMEOUT_SECONDS..CompressionConfig.MAX_CHUNK_TIMEOUT_SECONDS,
                )
            }
        )
    }
    if ("modelId" in body) {
        result = result.copy(modelId = when (val value = body["modelId"]) {
            null, JsonNull -> null
            else -> {
                val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw BadRequestException("'modelId' must be a string or null")
                val uuid = runCatching { Uuid.parse(raw) }.getOrNull() ?: throw BadRequestException("Invalid 'modelId'")
                val model = settings.findModelById(uuid)
                if (uuid != config.modelId && (model == null || model.type != ModelType.CHAT)) {
                    throw BadRequestException("Unknown chat model")
                }
                uuid
            }
        })
    }
    body["customTemplates"]?.let { value ->
        val items = value as? JsonArray ?: throw BadRequestException("'customTemplates' must be an array")
        if (items.size > CompressionConfig.MAX_CUSTOM_TEMPLATES) throw BadRequestException("Too many custom templates")
        val templates = items.map { item ->
            val obj = item as? JsonObject ?: throw BadRequestException("Each custom template must be an object")
            val id = obj.string("id")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
                ?: throw BadRequestException("Custom template needs an 'id'")
            val name = obj.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= CompressionConfig.MAX_TEMPLATE_NAME }
                ?: throw BadRequestException("Custom template '$id' needs a 'name' of 1..${CompressionConfig.MAX_TEMPLATE_NAME} characters")
            val prompt = obj.string("prompt")?.trim()?.takeIf { it.isNotEmpty() && it.length <= CompressionConfig.MAX_TEMPLATE_PROMPT }
                ?: throw BadRequestException("Custom template '$id' needs a 'prompt' of 1..${CompressionConfig.MAX_TEMPLATE_PROMPT} characters")
            if (id in CompressionTemplates.BUILT_IN) throw BadRequestException("'$id' is a built-in template id")
            CompressionTemplate(id, name, prompt)
        }
        if (templates.map { it.id }.toSet().size != templates.size) throw BadRequestException("Duplicate custom template ids")
        result = result.copy(customTemplates = templates)
        if (!result.templateExists(result.templateId)) result = result.copy(templateId = CompressionTemplates.GENERAL)
    }
    body.string("templateId")?.let { id ->
        if (!result.templateExists(id)) throw BadRequestException("Unknown template '$id'")
        result = result.copy(templateId = id)
    }
    return result
}

internal fun ConversationCompressionOverride?.toDto() = ConversationCompressionDto(
    mode = this?.mode?.wire(),
    templateId = this?.templateId,
)

/** 会话设置整体替换：null 字段 = 跟随全局；模板必须存在。 */
internal fun ConversationCompressionDto.toOverride(config: CompressionConfig): ConversationCompressionOverride {
    templateId?.let { if (!config.templateExists(it)) throw BadRequestException("Unknown template '$it'") }
    return ConversationCompressionOverride(mode = mode?.let(::parseMode), templateId = templateId)
}

internal fun CarriedOriginalItem.toDto() = CarriedOriginalPreviewDto(
    nodeId = nodeId.toString(),
    userText = userText,
    replyText = replyText,
    tokens = tokens,
    edited = edited,
)

internal fun CompressionEstimate.toDto() = CompressEstimateDto(
    summarizedMessages = summarizedMessages,
    summarizedChars = summarizedChars,
    summarizedTokens = summarizedTokens,
    keptMessages = keptMessages,
    keptTokens = keptTokens,
    beforeTokens = beforeTokens,
    beforeExact = beforeExact,
    afterMinTokens = afterMinTokens,
    afterMaxTokens = afterMaxTokens,
    budgetTokens = budgetTokens,
    carriedCount = carriedCount,
    carriedTokens = carriedTokens,
    carriedOverBudget = carriedOverBudget,
    carriedSkippedForBudget = carriedSkippedForBudget,
    carriedExcludedCount = carriedExcludedCount,
    carriedItems = carriedItems.map { it.toDto() },
    hasPreviousCheckpoint = hasPreviousCheckpoint,
    fromOriginal = fromOriginal,
    willChunk = willChunk,
    estimatedChunks = estimatedChunks,
    maxChunkRequests = maxChunkRequests,
    willBreakCache = willBreakCache,
)

fun CompressSuggestion?.toEvent(conversationId: Uuid) = CompressSuggestionEvent(
    conversationId = conversationId.toString(),
    percent = this?.percent,
    exact = this?.exact == true,
)

fun CompressState.toDto(conversationId: Uuid) = CompressStateDto(
    conversationId = conversationId.toString(),
    status = status.name.lowercase(),
    origin = origin.name.lowercase(),
    startedAt = startedAt,
    finishedAt = finishedAt,
    errorCode = errorCode,
    errorMessage = errorMessage,
    reviewSummary = reviewSummary,
    requestCount = requestCount,
    completedChunks = completedChunks,
    totalChunks = totalChunks,
    chunked = chunked,
    fromOriginal = fromOriginal,
    cacheBypassed = cacheBypassed,
    carriedCount = carriedCount,
    createNewConversation = createNewConversation,
    targetConversationId = targetConversationId?.toString(),
    totalUsage = totalUsage,
)

private fun buildCompressRequest(
    additionalPrompt: String?,
    keepRecent: Int?,
    templateId: String?,
    review: Boolean?,
    fromOriginal: Boolean?,
    carryMode: String?,
    carryCount: Int?,
    carryIncludeReplies: Boolean?,
    carryExcludedNodeIds: List<String>?,
    carryEdits: Map<String, CarriedOriginalEditDto>?,
): CompressRequest = try {
    CompressRequest(
        additionalPrompt = additionalPrompt.orEmpty(),
        keepRecent = keepRecent ?: 0,
        templateId = templateId,
        review = review == true,
        fromOriginal = fromOriginal == true,
        carryMode = carryMode?.let(::parseCarryMode),
        carryCount = carryCount,
        carryIncludeReplies = carryIncludeReplies,
        carryExcludedNodeIds = carryExcludedNodeIds.orEmpty().map { parseNodeUuid(it, "carryExcludedNodeIds") }.toSet(),
        carryEdits = carryEdits.orEmpty().entries.associate { (k, v) ->
            parseNodeUuid(k, "carryEdits") to CarriedOriginalEdit(userText = v.userText, replyText = v.replyText)
        },
    )
} catch (e: BadRequestException) {
    throw e
} catch (e: IllegalArgumentException) {
    throw BadRequestException(e.message ?: "Invalid compression request")
}

/** Out-of-range values are a 400, not silently clamped. */
internal fun CompressStartRequest.toCompressRequest(): CompressRequest = buildCompressRequest(
    additionalPrompt = additionalPrompt,
    keepRecent = keepRecent,
    templateId = templateId,
    review = review,
    fromOriginal = fromOriginal,
    carryMode = carryMode,
    carryCount = carryCount,
    carryIncludeReplies = carryIncludeReplies,
    carryExcludedNodeIds = carryExcludedNodeIds,
    carryEdits = carryEdits,
).copy(createNewConversation = createNewConversation == true)

internal fun ContextRecordMessagePreview.toDto() = ContextRecordMessagePreviewDto(
    nodeId = nodeId.toString(),
    messageId = messageId.toString(),
    nodeIndex = nodeIndex,
    role = role.name.lowercase(),
    previewText = previewText,
)

internal fun ContextCheckpointRecord.toDto() = ContextCheckpointRecordDto(
    nodeId = nodeId.toString(),
    messageId = messageId.toString(),
    nodeIndex = nodeIndex,
    createdAt = createdAt,
    isClear = isClear,
    summaryText = summaryText,
    meta = meta,
    rangeStartIndex = rangeStartIndex,
    rangeEndIndex = rangeEndIndex,
    rangeStartNodeId = rangeStartNodeId?.toString(),
    rangeEndNodeId = rangeEndNodeId?.toString(),
    coveredMessageCount = coveredMessageCount,
    coveredPreviews = coveredPreviews.map { it.toDto() },
    isActive = isActive,
    laterCheckpointNodeIds = laterCheckpointNodeIds.map { it.toString() },
    supersededByNodeId = supersededByNodeId?.toString(),
)

internal fun ContextUsageBreakdown.toDto() = ContextUsageBreakdownDto(
    totalTokens = totalTokens,
    totalExact = totalExact,
    reportedContextTokens = reportedContextTokens,
    estimatedTotalTokens = estimatedTotalTokens,
    contextLength = contextLength,
    inputBudgetTokens = inputBudgetTokens,
    systemPromptTokens = systemPromptTokens,
    memoryTokens = memoryTokens,
    lorebookAndTavernTokens = lorebookAndTavernTokens,
    toolsLocalTokens = toolsLocalTokens,
    toolsMcpTokens = toolsMcpTokens,
    toolsSkillsTokens = toolsSkillsTokens,
    toolsWorkspaceTokens = toolsWorkspaceTokens,
    historyCheckpointTokens = historyCheckpointTokens,
    historyMessagesTokens = historyMessagesTokens,
    attachmentsTokens = attachmentsTokens,
    draftTokens = draftTokens,
    toolsLocalCount = toolsLocalCount,
    toolsMcpCount = toolsMcpCount,
    toolsSkillsCount = toolsSkillsCount,
    toolsWorkspaceCount = toolsWorkspaceCount,
    historyMessageCount = historyMessageCount,
    hasActiveCheckpoint = hasActiveCheckpoint,
    attachmentCount = attachmentCount,
    activeLorebookCount = activeLorebookCount,
    memoryFileCount = memoryFileCount,
)

internal fun CompressEstimateRequest.toCompressRequest(): CompressRequest = buildCompressRequest(
    additionalPrompt = additionalPrompt,
    keepRecent = keepRecent,
    templateId = templateId,
    review = false,
    fromOriginal = fromOriginal,
    carryMode = carryMode,
    carryCount = carryCount,
    carryIncludeReplies = carryIncludeReplies,
    carryExcludedNodeIds = carryExcludedNodeIds,
    carryEdits = carryEdits,
)

/**
 * 后台压缩：在对话末尾插入摘要检查点（不删历史）。POST 立即返回 202 与 running 状态；进度与结果经会话
 * SSE 的 `compress_state` 事件推送。DELETE 取消；GET 取当前状态（会话未加载时为 null）。
 * 拒绝：没有新消息 400 `NOTHING_TO_COMPRESS`；扣掉要保留的最近消息后没有可压缩的 400 `NOT_ENOUGH_MESSAGES`；有未执行完的工具调用 409 `PENDING_TOOLS`；忙 / 已在压缩 409。
 */
fun Route.compressRoutes(chatService: ChatService, conversationRepo: ConversationRepository, settingsStore: SettingsStore) {
    route("/compression/config") {
        get {
            call.respond(projectCompressionConfig(settingsStore.settingsFlow.value))
        }
        put {
            val body = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            settingsStore.settingsFlow.value.let { mergeCompressionConfig(it.compression, body, it) } // validate before writing
            var result: Settings? = null
            settingsStore.update { settings ->
                settings.copy(compression = mergeCompressionConfig(settings.compression, body, settings)).also { result = it }
            }
            call.respond(projectCompressionConfig(result ?: settingsStore.settingsFlow.value))
        }
    }

    /** 本会话的压缩设置：GET 取，PUT 整体替换（字段 null = 跟随全局）。 */
    route("/conversations/{id}/compression") {
        get {
            val id = parseConversationId(call.parameters["id"])
            val conversation = conversationRepo.getConversationById(id) ?: throw NotFoundException("Conversation not found")
            call.respond(conversation.compression.toDto())
        }
        put {
            val id = parseConversationId(call.parameters["id"])
            val override = call.receive<ConversationCompressionDto>().toOverride(settingsStore.settingsFlow.value.compression)
            if (!conversationRepo.existsConversationById(id)) throw NotFoundException("Conversation not found")
            chatService.updateConversationCompression(id, override)
            call.respond(override.takeUnless { it.isEmpty }.toDto())
        }
    }

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

        /** 本地预估（GET 兼容旧查询；POST 传原话移除/编辑后的完整选项）。 */
        get("/estimate") {
            val id = parseConversationId(call.parameters["id"])
            val keepRecent = call.request.queryParameters["keepRecent"]?.let {
                it.toIntOrNull()?.takeIf { value -> value in 0..CompressRequest.MAX_KEEP_RECENT }
                    ?: throw BadRequestException("Invalid 'keepRecent'")
            } ?: 0
            val fromOriginal = call.request.queryParameters["fromOriginal"]?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> throw BadRequestException("Invalid 'fromOriginal'")
                }
            } ?: false
            val carryMode = call.request.queryParameters["carryMode"]?.let(::parseCarryMode)
            val carryCount = call.request.queryParameters["carryCount"]?.let {
                it.toIntOrNull()?.takeIf { value -> value in CompressionConfig.MIN_CARRY_COUNT..CompressionConfig.MAX_CARRY_COUNT }
                    ?: throw BadRequestException("Invalid 'carryCount'")
            }
            val carryIncludeReplies = call.request.queryParameters["carryIncludeReplies"]?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> throw BadRequestException("Invalid 'carryIncludeReplies'")
                }
            }
            call.respond(
                CompressEstimateResponse(
                    chatService.compressionEstimate(
                        conversationId = id,
                        keepRecent = keepRecent,
                        fromOriginal = fromOriginal,
                        carryMode = carryMode,
                        carryCount = carryCount,
                        carryIncludeReplies = carryIncludeReplies,
                    )?.toDto()
                )
            )
        }

        post("/estimate") {
            val id = parseConversationId(call.parameters["id"])
            val parsed = call.receive<CompressEstimateRequest>().toCompressRequest()
            call.respond(
                CompressEstimateResponse(
                    chatService.compressionEstimate(
                        conversationId = id,
                        keepRecent = parsed.keepRecent,
                        fromOriginal = parsed.fromOriginal,
                        carryMode = parsed.carryMode,
                        carryCount = parsed.carryCount,
                        carryIncludeReplies = parsed.carryIncludeReplies,
                        carryExcludedNodeIds = parsed.carryExcludedNodeIds,
                        carryEdits = parsed.carryEdits,
                        templateId = parsed.templateId,
                        additionalPrompt = parsed.additionalPrompt,
                    )?.toDto()
                )
            )
        }

        /** 「先看再采用」：采用（可改过的）摘要或丢弃。没有待确认的压缩时 404。 */
        post("/review") {
            val id = parseConversationId(call.parameters["id"])
            val summary = call.receive<CompressReviewRequest>().summary
            if (summary != null && summary.isBlank()) throw BadRequestException("'summary' must not be blank")
            if (!chatService.resolveCompressionReview(id, summary)) throw NotFoundException("No summary is waiting for review")
            call.respond(HttpStatusCode.NoContent)
        }

        /** 回复超限失败后「压缩后重试」：拒绝时的状态码与 POST 相同。 */
        post("/retry") {
            val id = parseConversationId(call.parameters["id"])
            if (!conversationRepo.existsConversationById(id)) throw NotFoundException("Conversation not found")
            try {
                chatService.compressAndRetry(id, CompressOrigin.WEB)
            } catch (e: CompressRejectedException) {
                val status = when (e.code) {
                    CompressErrorCodes.NOTHING_TO_COMPRESS, CompressErrorCodes.NOT_ENOUGH_MESSAGES -> HttpStatusCode.BadRequest
                    else -> HttpStatusCode.Conflict
                }
                throw CodedApiException(status, e.code, e.message ?: e.code)
            }
            call.respond(HttpStatusCode.Accepted, CompressStateResponse(chatService.currentCompressState(id)?.toDto(id)))
        }

        /** 关掉「仅提醒」的提示。 */
        delete("/suggestion") {
            val id = parseConversationId(call.parameters["id"])
            chatService.dismissCompressSuggestion(id)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/conversations/{id}/context-records") {
        get {
            val id = parseConversationId(call.parameters["id"])
            val (records, canClear) = chatService.contextRecordsSnapshot(id)
            call.respond(
                ContextRecordsResponse(
                    records = records.map { it.toDto() },
                    canClear = canClear,
                )
            )
        }

        post("/clear") {
            val id = parseConversationId(call.parameters["id"])
            if (!conversationRepo.existsConversationById(id)) throw NotFoundException("Conversation not found")
            val record = try {
                chatService.clearContext(id)
            } catch (e: CompressRejectedException) {
                val status = when (e.code) {
                    CompressErrorCodes.NOTHING_TO_COMPRESS, CompressErrorCodes.NOT_ENOUGH_MESSAGES ->
                        HttpStatusCode.BadRequest
                    else -> HttpStatusCode.Conflict
                }
                throw CodedApiException(status, e.code, e.message ?: e.code)
            }
            call.respond(
                HttpStatusCode.Created,
                ClearContextResponse(
                    record = record.toDto(),
                    canClear = false,
                )
            )
        }

        delete("/{nodeId}") {
            val id = parseConversationId(call.parameters["id"])
            val nodeId = runCatching { Uuid.parse(call.parameters["nodeId"].orEmpty()) }.getOrNull()
                ?: throw BadRequestException("Invalid checkpoint node id")
            val cascade = when (call.request.queryParameters["cascade"]) {
                null, "false" -> false
                "true" -> true
                else -> throw BadRequestException("Invalid 'cascade'")
            }
            if (!conversationRepo.existsConversationById(id)) throw NotFoundException("Conversation not found")
            val removed = chatService.revokeCheckpoints(id, nodeId, cascadeLater = cascade)
            call.respond(RevokeCheckpointResponse(removedNodeIds = removed.map { it.toString() }))
        }
    }

    route("/conversations/{id}/context-usage") {
        get {
            val id = parseConversationId(call.parameters["id"])
            call.respond(chatService.contextUsageBreakdown(id).toDto())
        }

        post {
            val id = parseConversationId(call.parameters["id"])
            val body = try {
                call.receive<ContextUsageRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw BadRequestException("Invalid request body")
            }
            if (body.draftImageCount !in 0..50) {
                throw BadRequestException("'draftImageCount' must be 0..50")
            }
            val draftParts = buildList {
                if (body.draftText.isNotEmpty()) add(UIMessagePart.Text(body.draftText))
                repeat(body.draftImageCount) { add(UIMessagePart.Image(url = "draft:image")) }
            }
            call.respond(chatService.contextUsageBreakdown(id, draftParts).toDto())
        }
    }
}

internal fun parseConversationId(raw: String?): Uuid =
    runCatching { Uuid.parse(raw.orEmpty()) }.getOrNull() ?: throw BadRequestException("Invalid conversation id")
