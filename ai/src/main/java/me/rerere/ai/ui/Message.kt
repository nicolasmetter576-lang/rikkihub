package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.sum
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
enum class MessageStopReason {
    @SerialName("max_tokens")
    MAX_TOKENS,

    @SerialName("content_filter")
    CONTENT_FILTER,

    @SerialName("step_limit")
    STEP_LIMIT,

    @SerialName("incomplete")
    INCOMPLETE,
}

/**
 * 将上游 provider 上报的 finishReason 原文归类为需要提示用户的异常结束原因。
 * 仅认严格白名单；正常结束（stop / end_turn / completed / tool_calls 等）及未知代理值返回 null。
 */
fun classifyProviderFinishReason(finishReason: String?): MessageStopReason? {
    val normalized = finishReason?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        normalized in MAX_TOKENS_FINISH_REASONS -> MessageStopReason.MAX_TOKENS
        normalized in CONTENT_FILTER_FINISH_REASONS -> MessageStopReason.CONTENT_FILTER
        normalized == "max_turn_requests" -> MessageStopReason.STEP_LIMIT
        normalized == "incomplete" ||
            normalized == "pause_turn" ||
            normalized.startsWith("incomplete:") -> MessageStopReason.INCOMPLETE
        else -> null
    }
}

private val MAX_TOKENS_FINISH_REASONS = setOf(
    "length",
    "max_tokens",
    "max_output_tokens",
    "incomplete:max_output_tokens",
    "model_context_window_exceeded",
)

private val CONTENT_FILTER_FINISH_REASONS = setOf(
    "content_filter",
    "incomplete:content_filter",
    "refusal",
    "safety",
    "recitation",
    "blocklist",
    "prohibited_content",
    "spii",
    "image_safety",
    "image_prohibited_content",
    "image_recitation",
    "model_armor",
)

@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val finishedAt: LocalDateTime? = null,
    val modelId: Uuid? = null,
    // 模型名称的快照：modelId 指向的模型被删除后，靠它继续显示这条消息由谁生成
    val modelSnapshot: ModelSnapshot? = null,
    // 消息生成时使用的模型上下文窗口大小（token）
    val contextLength: Int? = null,
    // 同一助手消息各逻辑模型步骤已报告用量的累计，不包含未报告的用量或失败重试的完整账单
    val usage: TokenUsage? = null,
    // 应用侧观测耗时：流式首 chunk 至流结束；非流式为成功请求耗时。不含客户端工具/审批等待
    val generationTimeMs: Long? = null,
    // 当前逻辑模型步骤的完整输入/输出快照；明确缺报时为空，不沿用上一阶段
    val lastUsage: TokenUsage? = null,
    // 逻辑模型步骤数，流式预览包括当前步；不是实际 HTTP 请求/重试次数
    val stepCount: Int? = null,
    val translation: String? = null,
    // 每轮标题：生成结束时从回复末行的 <rk-title> 解析，用户可改（只改这里，不回改原文）
    val title: String? = null,
    // 压缩检查点：此消息是它之前全部历史的摘要，组装请求时从最后一个检查点开始取
    val isContextCheckpoint: Boolean = false,
    // 检查点元数据（生成方式、模板、覆盖范围、用量等）；旧检查点缺省为 null
    val checkpointMeta: ContextCheckpointMetadata? = null,
    // null = 旧消息完整性未知；false = 生成中或已知缺报；true = 各步骤成功且用量完整
    val usageComplete: Boolean? = null,
    // 异常结束原因（达到最大输出/内容拦截/工具步数上限/提前结束）；正常结束或旧消息为 null
    val stopReason: MessageStopReason? = null,
    // 请求期间生成的内部消息；该标记仅在内存中使用
    @Transient
    val isSynthetic: Boolean = false,
) {
    fun summaryAsText(maxLength: Int = Int.MAX_VALUE): String {
        val text = "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> displayTextOf(part)
                else -> ""
            }
        }
        return if (text.length > maxLength) text.take(maxLength) + "..." else text
    }

    /** 是否为「清空上下文」空摘要检查点（只截断，不作为用户消息发给模型）。 */
    val isClearContextCheckpoint: Boolean
        get() = isContextCheckpoint && (
            checkpointMeta?.kind == ContextCheckpointKind.CLEAR || toRawText().isBlank()
        )

    /** 文字内容（助手消息去掉每轮标题标签，见 [stripMessageTitle]）。 */
    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> displayTextOf(part)
            else -> ""
        }
    }

    /** 原样的文字内容（含标题标签），给要原样发回模型的地方用。 */
    fun toRawText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank() ||
                part.metadataAs<ClaudeReasoningMetadata>()?.redactedData != null
            else -> true
        }
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    fun hasBase64Part(): Boolean = parts.any {
        it is UIMessagePart.Image && it.url.startsWith("data:")
    }

    /**
     * 最近逻辑模型步骤的上下文参考。仅旧消息允许回退到累计字段；新消息的 null 表示缺报。
     * provider 内部续接（如 Claude pause_turn）可能折叠多个物理请求，不是下一请求的精确容量。
     */
    fun lastRequestUsage(): TokenUsage? = if (usageComplete == null) lastUsage ?: usage else lastUsage

    /** 最近模型步骤的输入 + 输出参考；未知时不拿累计量冒充。 */
    fun contextTokens(): Int? = lastRequestUsage()
        ?.takeIf { it.promptTokens >= 0 && it.completionTokens >= 0 }
        ?.let { it.promptTokens.toLong() + it.completionTokens }
        ?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    /**
     * 用快照还原出一个只用于展示名称和图标的 [Model]
     *
     * 仅在按 [modelId] 查不到模型时使用；其余字段都是默认值，不能拿它发起请求。
     */
    fun snapshotModel(): Model? {
        val id = modelId ?: return null
        val snapshot = modelSnapshot ?: return null
        return Model(modelId = snapshot.modelId, displayName = snapshot.displayName, id = id)
    }

    /**
     * 把一次请求的 usage 和生成耗时累加到 [stepBase]（这一步开始前的消息）的统计上。
     * 接收者的 [usage] 只带这一步的 usage。
     */
    fun withStepStats(
        stepBase: UIMessage?,
        stepTimeMillis: Long,
        hasCompleteUsage: Boolean,
        stepFinished: Boolean,
    ): UIMessage {
        // 旧基线缺少耗时时不能凭空补齐；完整性另外记录，界面不会把不完整统计当精确速度
        val baseTime = when {
            stepBase == null -> 0L
            stepBase.generationTimeMs != null -> stepBase.generationTimeMs
            stepBase.usage != null -> null
            else -> 0L
        }
        val baseSteps = stepBase?.stepCount ?: if (stepBase?.usage != null) 1 else 0
        val baseComplete = baseSteps == 0 || stepBase?.usageComplete == true
        return copy(
            usage = stepBase?.usage.sum(usage),
            generationTimeMs = baseTime?.plus(stepTimeMillis.coerceAtLeast(0)),
            lastUsage = usage.takeIf { hasCompleteUsage },
            stepCount = baseSteps + 1,
            usageComplete = baseComplete && stepFinished && hasCompleteUsage && usage != null,
        )
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/** 压缩检查点的产生方式。 */
@Serializable
enum class ContextCheckpointKind {
    @SerialName("manual")
    MANUAL,

    @SerialName("auto")
    AUTO,

    @SerialName("overflow_retry")
    OVERFLOW_RETRY,

    @SerialName("clear")
    CLEAR,
}

/** 压缩检查点的可选元数据；旧检查点没有该字段时整块为 null。 */
@Serializable
data class ContextCheckpointMetadata(
    val kind: ContextCheckpointKind? = null,
    val templateId: String? = null,
    val fromOriginal: Boolean = false,
    val chunked: Boolean = false,
    val requestCount: Int? = null,
    val carriedCount: Int? = null,
    val rangeStartNodeId: Uuid? = null,
    val rangeEndNodeId: Uuid? = null,
    val summarizedCount: Int? = null,
    val supersededCheckpointNodeId: Uuid? = null,
    val usage: TokenUsage? = null,
)

/**
 * 消息上保存的模型名称快照，对应 [Model.modelId] 与 [Model.displayName]
 */
@Serializable
data class ModelSnapshot(
    val modelId: String,
    val displayName: String,
)

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            is UIMessagePart.Tool,
            is UIMessagePart.ServerTool,
                -> false
            else -> true
        }
    }
}

/**
 * 截断后保留的消息条数占上限的比例
 *
 * 越小则截断点前进的步幅越大, 连续命中缓存的轮数越多, 但一次丢弃的上下文也越多
 */
private const val CONTEXT_KEEP_RATIO = 0.5f

/**
 * 截取实际发送给模型的上下文
 *
 * 先丢弃最后一个压缩检查点之前的消息(它们只保留给用户查看), 再按 [limit] 限制条数。
 * 检查点本身是被压缩历史的唯一来源, 始终保留, 条数限制只作用于它之后的消息。
 *
 * @param limit 触发截断的消息条数上限, 小于等于 0 表示不限制
 */
fun List<UIMessage>.limitContext(limit: Int): List<UIMessage> {
    val checkpointIndex = indexOfLast { it.isContextCheckpoint }
    if (checkpointIndex < 0) return limitMessageCount(limit)
    val checkpoint = this[checkpointIndex]
    val tail = subList(checkpointIndex + 1, size).limitMessageCount(limit)
    return if (checkpoint.isClearContextCheckpoint) tail else listOf(checkpoint) + tail
}

/**
 * 按阶梯式(滞回)策略限制上下文消息数量
 *
 * 与每轮平移一条的滑动窗口不同, 截断点只在消息数越过 [limit] 时才前进一大步,
 * 在此之后的连续多轮里保持不动, 使请求前缀保持稳定, 从而命中提示词缓存。
 * 截断点仅由消息条数推导, 不需要额外持久化状态, 且对追加消息天然稳定。
 *
 * 保留的条数始终落在 `[limit * CONTEXT_KEEP_RATIO, limit)` 区间内。
 *
 * @param limit 触发截断的消息条数上限, 小于等于 0 表示不限制
 */
private fun List<UIMessage>.limitMessageCount(limit: Int): List<UIMessage> {
    if (limit <= 0 || this.size <= limit) return this

    // 截断后回落到的目标条数, 以及两次截断之间截断点前进的步幅
    // limit 为 1 时无法构造滞回(步幅至少为 1), 此时退化为逐条平移的滑动窗口
    val target = (limit * CONTEXT_KEEP_RATIO).roundToInt().coerceIn(1, limit)
    val stride = (limit - target).coerceAtLeast(1)

    // 每越过一级台阶, 截断点前进 stride 条; 台阶之内截断点不动
    // 上界兜底保证至少保留一条消息, 正常路径(limit >= 2)不会触发
    val startIndex = (((this.size - limit) / stride + 1) * stride).coerceAtMost(this.size - 1)

    return this.subList(alignContextStart(startIndex), this.size)
}

/**
 * 将截断起点回退到安全边界, 避免把 tool call 与其结果拆散, 或让上下文从半截的工具调用开始
 *
 * 只会向前(下标减小)调整, 因此不会破坏 [limitMessageCount] 保留条数的下界。
 * 调整只依赖 `[0, startIndex]` 区间内的消息, 这部分在追加新消息时不会变化, 结果因此保持稳定。
 */
private fun List<UIMessage>.alignContextStart(startIndex: Int): Int {
    var adjustedStartIndex = startIndex

    // 循环往前查找, 直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含已执行的tool（有output），往前查找对应的tool call
        if (currentMessage.getTools().any { it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getTools().any { !it.isExecuted }) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含未执行的tool call，往前查找对应的用户消息
        if (currentMessage.getTools().any { !it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    return adjustedStartIndex
}

/**
 * Sort message parts by type priority:
 * - Reasoning (-1): shown first
 * - Text, Tool, ServerTool, ToolCall, ToolResult, Search (0): middle
 * - Image, Video, Audio, Document (1): shown last
 *
 * WARNING: This function is intended for migration only.
 * Do not use for new messages as it may break the semantic order
 * when a message contains multiple Reasoning/Text parts.
 */
@Deprecated(
    message = "Only use for migration. May break semantic order for messages with multiple Reasoning/Text parts.",
    level = DeprecationLevel.WARNING
)
fun List<UIMessagePart>.toSortedMessageParts(): List<UIMessagePart> {
    // Skip sorting if multiple Reasoning or Text parts exist to preserve semantic order
    val reasoningCount = count { it is UIMessagePart.Reasoning }
    val textCount = count { it is UIMessagePart.Text }
    if (reasoningCount > 1 || textCount > 1) {
        return this
    }
    return sortedBy { part ->
        when (part) {
            is UIMessagePart.Reasoning -> -1
            is UIMessagePart.Text -> 0
            is UIMessagePart.Tool -> 0
            is UIMessagePart.ServerTool -> 0
            is UIMessagePart.ToolCall -> 0
            is UIMessagePart.ToolResult -> 0
            is UIMessagePart.Search -> 0
            is UIMessagePart.Image -> 1
            is UIMessagePart.Video -> 1
            is UIMessagePart.Audio -> 1
            is UIMessagePart.Document -> 1
        }
    }
}

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

fun UIMessage.finishPendingTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

/**
 * Migrate legacy ToolCall parts to new Tool type within a single message.
 * This converts ToolCall parts to Tool parts with empty output.
 */
@Suppress("DEPRECATION")
private fun UIMessage.migrateToolParts(): UIMessage {
    val toolCalls = parts.filterIsInstance<UIMessagePart.ToolCall>()
    if (toolCalls.isEmpty()) {
        // Even if no ToolCall migration needed, ensure parts are sorted
        val sortedParts = parts.toSortedMessageParts()
        return if (sortedParts != parts) copy(parts = sortedParts) else this
    }

    val migratedParts = parts.map { part ->
        if (part is UIMessagePart.ToolCall) {
            UIMessagePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                input = part.arguments,
                output = emptyList(),
                approvalState = part.approvalState,
                metadata = part.metadata
            )
        } else {
            part
        }
    }
    return copy(parts = migratedParts.toSortedMessageParts())
}

/**
 * Migrate TOOL role messages into previous ASSISTANT messages by
 * merging ToolResult parts into corresponding Tool parts.
 * Returns the migrated list with TOOL messages removed.
 */
@Suppress("DEPRECATION")
fun List<UIMessage>.migrateToolMessages(): List<UIMessage> {
    val result = mutableListOf<UIMessage>()
    var i = 0

    while (i < size) {
        val message = this[i]

        // If this is a TOOL role message, merge its results into previous ASSISTANT message
        if (message.role == MessageRole.TOOL) {
            val toolResults = message.parts.filterIsInstance<UIMessagePart.ToolResult>()
            if (result.isNotEmpty() && result.last().role == MessageRole.ASSISTANT) {
                // Find the last ASSISTANT message and update its Tool parts with results
                val lastAssistant = result.removeAt(result.lastIndex)
                val updatedParts = lastAssistant.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            part.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                )
                            )
                        } else {
                            part
                        }
                    } else if (part is UIMessagePart.ToolCall) {
                        // Also handle legacy ToolCall parts
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                ),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        } else {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = emptyList(),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        }
                    } else {
                        part
                    }
                }
                result.add(lastAssistant.copy(parts = updatedParts.toSortedMessageParts()))
            }
            // Skip the TOOL message (don't add it to result)
            i++
            continue
        }

        // For other messages, migrate their tool parts first
        result.add(message.migrateToolParts())
        i++
    }

    return result
}

/**
 * Migrate legacy TOOL role messages at the MessageNode level.
 * This handles the case where TOOL messages are stored in separate MessageNodes
 * by merging ToolResult parts into the previous ASSISTANT node's Tool parts.
 *
 * @param MessageNode A container holding one or more UIMessages for branching.
 * @return Migrated list with TOOL nodes removed and their results merged into ASSISTANT nodes.
 */
@Suppress("DEPRECATION")
fun <T> List<T>.migrateToolNodes(
    getMessages: (T) -> List<UIMessage>,
    setMessages: (T, List<UIMessage>) -> T
): List<T> {
    val result = mutableListOf<T>()
    var i = 0

    while (i < size) {
        val node = this[i]
        val messages = getMessages(node)

        // Check if this node contains TOOL role messages
        val isToolNode = messages.any { it.role == MessageRole.TOOL }

        if (isToolNode && result.isNotEmpty()) {
            // Find the previous ASSISTANT node
            val lastIndex = result.lastIndex
            val lastNode = result[lastIndex]
            val lastMessages = getMessages(lastNode)
            val isAssistantNode = lastMessages.any { it.role == MessageRole.ASSISTANT }

            if (isAssistantNode) {
                // Collect all ToolResults from the TOOL node
                val toolResults = messages.flatMap { msg ->
                    msg.parts.filterIsInstance<UIMessagePart.ToolResult>()
                }

                // Update the ASSISTANT node's messages by merging ToolResults
                val updatedMessages = lastMessages.map { assistantMsg ->
                    if (assistantMsg.role != MessageRole.ASSISTANT) return@map assistantMsg

                    val updatedParts = assistantMsg.parts.map { part ->
                        when (part) {
                            is UIMessagePart.Tool -> {
                                if (!part.isExecuted) {
                                    val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                    if (matchingResult != null) {
                                        part.copy(
                                            output = listOf(
                                                UIMessagePart.Text(
                                                    json.encodeToString(matchingResult.content)
                                                )
                                            )
                                        )
                                    } else part
                                } else part
                            }

                            is UIMessagePart.ToolCall -> {
                                val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                if (matchingResult != null) {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(matchingResult.content)
                                            )
                                        ),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                } else {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = emptyList(),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                }
                            }

                            else -> part
                        }
                    }
                    assistantMsg.copy(parts = updatedParts.toSortedMessageParts())
                }

                result[lastIndex] = setMessages(lastNode, updatedMessages)
                // Skip the TOOL node (don't add it to result)
                i++
                continue
            }
        }

        // For non-TOOL nodes, migrate their internal tool parts
        val migratedMessages = messages.migrateToolMessages()
        result.add(setMessages(node, migratedMessages))
        i++
    }

    return result
}
