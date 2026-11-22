package me.rerere.rikkihub.service

import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.DEFAULT_MODEL_CONTEXT_LENGTH
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ContextCheckpointKind
import me.rerere.ai.ui.ContextCheckpointMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.displayTextOf
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionConfig
import me.rerere.rikkihub.data.ai.CompressionTemplates
import me.rerere.rikkihub.data.ai.prompts.buildChunkFinalPrompt
import me.rerere.rikkihub.data.ai.prompts.buildChunkStagePrompt
import me.rerere.rikkihub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkihub.data.ai.prompts.builtInCompressStructure
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.lastContextTokens
import me.rerere.tavern.token.TokenCounter
import kotlin.math.ceil
import kotlin.uuid.Uuid

/**
 * 后台压缩会话历史：状态、参数与纯函数部分。执行与提交在 [ChatService.startCompression]。
 *
 * 压缩不删任何消息，只在切点插入一条带 `isContextCheckpoint` 标记的摘要；组装请求时从最后一个
 * 检查点开始取，删掉这条摘要就等于撤销压缩，再次压缩只处理上一个检查点之后的部分（除非选择从原文重压）。
 */
/** [AUTO]：用量达到触发比例、或回复超限后自动发起，不是用户手动。 */
enum class CompressOrigin { APP, WEB, AUTO }

/** [AWAITING_REVIEW]：摘要已生成、等用户看过后采用或丢弃（「先看再采用」），期间仍占着会话。 */
enum class CompressStatus { RUNNING, AWAITING_REVIEW, SUCCEEDED, FAILED, CANCELLED }

data class CompressState(
    val status: CompressStatus,
    val origin: CompressOrigin,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** [CompressStatus.AWAITING_REVIEW] 时待确认的摘要 */
    val reviewSummary: String? = null,
    /** 本次压缩累计发起的模型请求次数（含分段与超限缩容重试） */
    val requestCount: Int = 0,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val chunked: Boolean = false,
    val fromOriginal: Boolean = false,
    /** 不走正常对话前缀（另选压缩模型 / 分段压缩 / 从原文重压）导致提示词缓存未命中 */
    val cacheBypassed: Boolean = false,
    /** 附带进检查点正文的用户原话条数 */
    val carriedCount: Int = 0,
    /** 本次压缩全部请求累计的模型用量 */
    val totalUsage: TokenUsage? = null,
    /** true = 摘要完成后写入新会话，原会话不动 */
    val createNewConversation: Boolean = false,
    /** [createNewConversation] 成功时新建会话的 id */
    val targetConversationId: Uuid? = null,
)

object CompressErrorCodes {
    /** 上一个检查点之后没有新消息 */
    const val NOTHING_TO_COMPRESS = "NOTHING_TO_COMPRESS"
    /** 有新消息，但扣掉要保留的最近几条之后没有可压缩的 */
    const val NOT_ENOUGH_MESSAGES = "NOT_ENOUGH_MESSAGES"
    /** 要压缩的范围里还有没执行完的工具调用（没有结果的调用会让请求被拒） */
    const val PENDING_TOOLS = "PENDING_TOOLS"
    const val ALREADY_COMPRESSING = "ALREADY_COMPRESSING"
    const val CONVERSATION_CHANGED = "CONVERSATION_CHANGED"
    const val CONVERSATION_BUSY = "CONVERSATION_BUSY"
    /** 模型没写摘要而是发起了工具调用 */
    const val TOOL_CALL_RESPONSE = "TOOL_CALL_RESPONSE"
    /** 分段压缩所需或实际发起的请求次数超过上限 */
    const val REQUEST_LIMIT_EXCEEDED = "REQUEST_LIMIT_EXCEEDED"
    /** 单次压缩请求超过配置的超时 */
    const val REQUEST_TIMEOUT = "REQUEST_TIMEOUT"
    const val COMPRESS_FAILED = "COMPRESS_FAILED"
    /** CLI 对话的历史在 CLI 里，由 CLI 自己的 /compact 压缩 */
    const val CLI_CONVERSATION = "CLI_CONVERSATION"
}

/** 「仅提醒」档位的提示：用量约占输入预算的 [percent]%（[exact] 同 [ContextUsage.exact]）。 */
data class CompressSuggestion(val percent: Int, val exact: Boolean)

/** Busy reason reported while a compression owns the conversation. */
const val BUSY_REASON_COMPRESSING = "COMPRESSING"

/** A compression that was refused or could not be committed; [code] is one of [CompressErrorCodes]. */
class CompressRejectedException(val code: String, message: String) : Exception(message)

/** 用户在对话框里修改单条「带上原话」副本的结果；只影响写入检查点正文的副本，原消息不动。 */
data class CarriedOriginalEdit(
    val userText: String? = null,
    val replyText: String? = null,
) {
    init {
        require((userText?.length ?: 0) <= MAX_EDIT_LENGTH && (replyText?.length ?: 0) <= MAX_EDIT_LENGTH) {
            "carried original edit must be at most $MAX_EDIT_LENGTH characters"
        }
    }

    companion object {
        const val MAX_EDIT_LENGTH = 32_000
    }
}

/** 选定将附带进检查点的一条用户原话（可选带当时回复）。 */
data class CarriedOriginalItem(
    val nodeId: Uuid,
    val userText: String,
    val replyText: String?,
    val tokens: Int,
    val edited: Boolean,
)

/** 原话带上的挑拣与容量评估结果，预估对话框和实际生成共用。 */
data class CarriedOriginalsPlan(
    val items: List<CarriedOriginalItem>,
    val totalTokens: Int,
    val excludedCount: Int,
    val skippedForBudget: Int,
    val overBudget: Boolean,
    val budgetTokens: Int?,
)

/**
 * 压缩参数。普通压缩固定用会话的聊天模型和结构化总结指令（与正常对话同一套请求组装，命中提示词缓存）；
 * 另选模型、分段压缩、从原文重新总结时不走正常对话前缀（不命中缓存）。
 */
data class CompressRequest(
    val additionalPrompt: String = "",
    val keepRecent: Int = 0,
    /** 摘要模板；null = 会话生效的模板（会话覆盖或全局默认） */
    val templateId: String? = null,
    /** 摘要生成后先停在 [CompressStatus.AWAITING_REVIEW]，等用户采用或丢弃 */
    val review: Boolean = false,
    /** 成功后从这条用户消息重新生成回复（回复因超限失败时的「压缩后重试」） */
    val retryMessageId: Uuid? = null,
    /** 跳过历史检查点，从第一条消息到切点的全部原文重新总结 */
    val fromOriginal: Boolean = false,
    /** null = 跟随全局设置 */
    val carryMode: CarryOriginalsMode? = null,
    val carryCount: Int? = null,
    val carryIncludeReplies: Boolean? = null,
    val carryExcludedNodeIds: Set<Uuid> = emptySet(),
    val carryEdits: Map<Uuid, CarriedOriginalEdit> = emptyMap(),
    /** true = 生成摘要后新建会话并带入摘要与保留消息，原会话保持不变 */
    val createNewConversation: Boolean = false,
) {
    init {
        require(additionalPrompt.length <= MAX_ADDITIONAL_PROMPT) {
            "additionalPrompt must be at most $MAX_ADDITIONAL_PROMPT characters"
        }
        require(keepRecent in 0..MAX_KEEP_RECENT) {
            "keepRecent must be between 0 and $MAX_KEEP_RECENT"
        }
        if (carryCount != null) {
            require(carryCount in CompressionConfig.MIN_CARRY_COUNT..CompressionConfig.MAX_CARRY_COUNT) {
                "carryCount must be between ${CompressionConfig.MIN_CARRY_COUNT} and ${CompressionConfig.MAX_CARRY_COUNT}"
            }
        }
        require(carryExcludedNodeIds.size <= MAX_CARRY_EXCLUDED) {
            "carryExcludedNodeIds must have at most $MAX_CARRY_EXCLUDED entries"
        }
        require(carryEdits.size <= MAX_CARRY_EDITS) {
            "carryEdits must have at most $MAX_CARRY_EDITS entries"
        }
    }

    companion object {
        const val MAX_ADDITIONAL_PROMPT = 2_000
        const val MAX_KEEP_RECENT = 100_000
        const val MAX_CARRY_EXCLUDED = 500
        const val MAX_CARRY_EDITS = 200
    }
}

internal fun hasContextCheckpoint(nodes: List<MessageNode>): Boolean =
    nodes.any { it.currentMessage.isContextCheckpoint }

/**
 * 要压缩的范围：
 * - [fromOriginal] 为 false：上一个检查点之后的节点，去掉最后 [keepRecent] 个。
 * - [fromOriginal] 为 true：从开头到切点（去掉最后 [keepRecent] 个）的全部非检查点原消息；
 *   切点不能落在最后一个检查点之前（否则新检查点插入切点后仍会被旧检查点遮蔽）。
 */
internal fun compressionRange(
    nodes: List<MessageNode>,
    keepRecent: Int = 0,
    fromOriginal: Boolean = false,
): List<MessageNode>? {
    val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
    val end = nodes.size - keepRecent.coerceAtLeast(0)
    if (!fromOriginal) {
        if (end <= checkpointIndex + 1) return null
        return nodes.subList(checkpointIndex + 1, end)
    }
    if (end <= 0 || end <= checkpointIndex) return null
    return nodes.subList(0, end)
        .filterNot { it.currentMessage.isContextCheckpoint }
        .takeIf { it.isNotEmpty() }
}

/**
 * 缓存保活用：基线之后插入了压缩检查点。下一次请求从检查点开始，基线这份前缀不会再被用到，
 * 继续保活只是白花钱（检查点追加在末尾时前缀核对本身发现不了；保留最近消息时检查点插在中间，
 * 按消息 id 判断两种位置都能认出）。
 */
internal fun compressedAfterBaseline(baseline: List<UIMessage>, current: List<UIMessage>): Boolean {
    val known = baseline.mapTo(HashSet()) { it.id }
    return current.any { it.isContextCheckpoint && it.id !in known }
}

/**
 * 范围里是否还有等审批或审批后待恢复的工具调用：它们还没有结果，压到检查点之前也再不会执行。
 * 免审批却没有输出的旧调用（中断残留、旧版本的空输出）不算，请求里照常带上，与正常发送一致。
 */
internal fun List<MessageNode>.hasUnfinishedToolCalls(): Boolean =
    any { node -> node.currentMessage.getTools().any { it.isPending || it.canResumeExecution } }

/**
 * 提交前（持久化锁内）复核：null 表示可以把检查点插到 [snapshot] 的最后一个节点之后。
 *
 * 被总结的范围（直到切点为止的当前分支）必须还是开始压缩时的那些消息；之后追加的节点、
 * 翻译、标题这类不改变内容的写入都不影响。排队允许：只可能是压缩期间被扣住的新消息。
 */
internal fun compressionCommitError(
    snapshot: Conversation,
    current: Conversation,
    busyReason: String?,
): String? {
    if (busyReason != null) return CompressErrorCodes.CONVERSATION_BUSY
    val cut = snapshot.messageNodes.lastOrNull() ?: return CompressErrorCodes.CONVERSATION_CHANGED
    val index = current.messageNodes.indexOfFirst { it.id == cut.id }
    if (index < 0) return CompressErrorCodes.CONVERSATION_CHANGED
    val planned = snapshot.messageNodes.map { it.id to it.currentMessage.id }
    val actual = current.messageNodes.subList(0, index + 1).map { it.id to it.currentMessage.id }
    return if (planned == actual) null else CompressErrorCodes.CONVERSATION_CHANGED
}

/** 没有设输出上限时给回复预留的 token 数 */
internal const val DEFAULT_OUTPUT_RESERVE = 8192
/** 模型窗口未知时，分段/兜底切段的默认容量上限 */
internal const val DEFAULT_UNKNOWN_WINDOW_CHUNK_BUDGET = 64_000
private const val DEFAULT_AUTO_CARRY_BUDGET = 16_000
private const val CARRY_HEADER_TOKENS = 16

/**
 * 输入预算：上下文窗口减去输出预留和 5% 余量。窗口未知（模型 id 没被识别、也没手动填过，仍是默认的 100 万）
 * 时返回 null，自动压缩不触发。
 */
internal fun compressionInputBudget(model: Model, maxOutputTokens: Int?): Int? {
    val window = model.contextLength
    val known = window != DEFAULT_MODEL_CONTEXT_LENGTH || ModelRegistry.MODEL_CONTEXT_LENGTH.getData(model.modelId) != null
    if (!known || window <= 0) return null
    val output = (maxOutputTokens ?: DEFAULT_OUTPUT_RESERVE).coerceIn(0, window / 2)
    return (window - output - ceil(window * 0.05).toInt()).takeIf { it > 0 }
}

/** 图片按固定体积估（各家计法不同，常见尺寸大约在这个量级） */
private const val IMAGE_TOKEN_ESTIMATE = 1_000
/** 每条消息的角色与分隔开销 */
private const val MESSAGE_OVERHEAD_TOKENS = 4

/** 一条消息发给模型时的大约 token 数（cl100k 近似）：文字、工具调用的参数与结果、图片。 */
internal fun UIMessage.estimatedTokens(): Int = MESSAGE_OVERHEAD_TOKENS + parts.sumOf { part ->
    when (part) {
        is UIMessagePart.Text -> TokenCounter.count(part.text)
        is UIMessagePart.Tool -> TokenCounter.count(part.input) +
            part.output.sumOf { (it as? UIMessagePart.Text)?.let { text -> TokenCounter.count(text.text) } ?: 0 }
        is UIMessagePart.Image -> IMAGE_TOKEN_ESTIMATE
        else -> 0
    }
}

/** 当前上下文用量：[exact] 表示基数来自服务端返回的真实用量，否则全部是本地估算。 */
data class ContextUsage(val tokens: Int, val exact: Boolean)

/**
 * 下一次请求的大约体积：上一条回复的真实用量（含系统提示词、工具等固定部分）加上还没发出的 [pendingText]；
 * 没有可用的真实用量（刚压缩过、提供商不返回用量）时只能估最后一个检查点之后的历史，固定部分不计。
 */
internal fun Conversation.contextUsage(pendingText: String = ""): ContextUsage {
    val pending = if (pendingText.isEmpty()) 0 else TokenCounter.count(pendingText) + MESSAGE_OVERHEAD_TOKENS
    lastContextTokens()?.let { return ContextUsage(it + pending, exact = true) }
    return ContextUsage(activeHistory().sumOf { it.estimatedTokens() } + pending, exact = false)
}

/**
 * 固定部分（系统提示词、记忆、世界书、工具定义……）的大约体积：真实用量减去估算的历史；没有真实用量时为 0。
 */
internal fun Conversation.fixedContextTokens(): Int =
    lastContextTokens()?.let { (it - activeHistory().sumOf { message -> message.estimatedTokens() }).coerceAtLeast(0) } ?: 0

/** 会发给模型的历史：最后一个检查点（若为清空检查点则跳过它本身）及之后的消息。 */
private fun Conversation.activeHistory(): List<UIMessage> {
    val messages = currentMessages
    val checkpoint = messages.indexOfLast { it.isContextCheckpoint }
    if (checkpoint < 0) return messages
    val start = if (messages[checkpoint].isClearContextCheckpoint) checkpoint + 1 else checkpoint
    return messages.subList(start, messages.size)
}

/** 用量占输入预算的百分比；预算未知时为 null。 */
internal fun usagePercent(tokens: Int, budget: Int?): Int? = budget?.let { (tokens * 100L / it).toInt() }

/**
 * 自动压缩时保留几条：从 [preferred] 开始往少里减，直到「固定部分 + 摘要上限 + 保留部分」低于 [targetTokens]；
 * 最少 [minKeep] 条（超限重试时失败的那一轮必须留下）。[tailTokens] 是检查点之后各节点的估算体积，按时间顺序。
 */
internal fun autoKeepRecent(
    tailTokens: List<Int>,
    preferred: Int,
    minKeep: Int,
    fixedTokens: Int,
    summaryTokens: Int,
    targetTokens: Int,
): Int {
    var keep = preferred.coerceIn(minKeep, tailTokens.size)
    while (keep > minKeep && fixedTokens + summaryTokens + tailTokens.takeLast(keep).sum() > targetTokens) keep--
    return keep
}

/**
 * 从将被收进摘要的范围里挑选附带的用户原话（可选附带当时助手的可见回复）。
 *
 * - [CarryOriginalsMode.RECENT]：取最新 [count] 条（被移除的项仍占位置，不顶替滑入更旧的）。
 * - [CarryOriginalsMode.AUTO]：在「压到」目标比例扣除固定部分、摘要预留与保留消息后的剩余空间内，
 *   从新到旧连续装入，遇到第一条放不下的就停；未知窗口时按 [DEFAULT_AUTO_CARRY_BUDGET] 兜底。
 */
internal fun planCarriedOriginals(
    conversation: Conversation,
    keepRecent: Int,
    fromOriginal: Boolean,
    mode: CarryOriginalsMode,
    count: Int,
    includeReplies: Boolean,
    excludedNodeIds: Set<Uuid> = emptySet(),
    edits: Map<Uuid, CarriedOriginalEdit> = emptyMap(),
    summaryTokens: Int = CompressionConfig.DEFAULT_SUMMARY_TOKENS,
    targetPercent: Int = CompressionConfig.DEFAULT_TARGET_PERCENT,
    chatBudget: Int? = null,
): CarriedOriginalsPlan {
    if (mode == CarryOriginalsMode.OFF) {
        return CarriedOriginalsPlan(emptyList(), 0, 0, 0, overBudget = false, budgetTokens = null)
    }
    val range = compressionRange(conversation.messageNodes, keepRecent, fromOriginal)
        ?: return CarriedOriginalsPlan(emptyList(), 0, 0, 0, overBudget = false, budgetTokens = null)

    val checkpointIndex = conversation.messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
    val previousSummary = if (fromOriginal) {
        null
    } else {
        conversation.messageNodes.getOrNull(checkpointIndex)?.currentMessage?.takeUnless { it.isClearContextCheckpoint }
    }
    val summarizedTokens = (listOfNotNull(previousSummary) + range.map { it.currentMessage }).sumOf { it.estimatedTokens() }
    val keptTokens = conversation.messageNodes.takeLast(keepRecent.coerceAtMost(conversation.messageNodes.size)).sumOf {
        it.currentMessage.estimatedTokens()
    }
    val fixed = conversation.fixedContextTokens()
    val summaryReserve = ceil(summarizedTokens * 0.3).toInt().coerceAtMost(summaryTokens)
    val carryBudget = chatBudget?.let { budget ->
        val targetTotal = (budget.toLong() * targetPercent / 100L).toInt()
        (targetTotal - fixed - summaryReserve - keptTokens - CARRY_HEADER_TOKENS).coerceAtLeast(0)
    } ?: if (mode == CarryOriginalsMode.AUTO) DEFAULT_AUTO_CARRY_BUDGET else null

    val candidates = ArrayList<CarriedOriginalItem>()
    for (i in range.indices) {
        val node = range[i]
        val message = node.currentMessage
        if (message.isContextCheckpoint || message.role != MessageRole.USER) continue
        val rawUser = message.toText().trim()
        val edit = edits[node.id]
        val userText = edit?.userText?.trim()?.takeIf { it.isNotEmpty() } ?: rawUser
        if (userText.isBlank()) continue

        val rawReply = if (includeReplies) {
            buildList {
                var j = i + 1
                while (j < range.size) {
                    val nextMsg = range[j].currentMessage
                    if (!nextMsg.isContextCheckpoint && nextMsg.role == MessageRole.USER) break
                    if (nextMsg.role == MessageRole.ASSISTANT) {
                        val visible = nextMsg.toText().trim()
                        if (visible.isNotEmpty()) add(visible)
                    }
                    j++
                }
            }.joinToString("\n\n").ifBlank { null }
        } else null

        val replyText = if (includeReplies) {
            if (edit?.replyText != null) edit.replyText.trim().ifEmpty { null } else rawReply
        } else null

        val edited = edit != null && (userText != rawUser || replyText != rawReply)
        val tokens = TokenCounter.count(userText) + 8 + (replyText?.let { TokenCounter.count(it) + 6 } ?: 0)
        candidates.add(CarriedOriginalItem(node.id, userText, replyText, tokens, edited))
    }

    if (candidates.isEmpty()) {
        return CarriedOriginalsPlan(emptyList(), 0, 0, 0, overBudget = false, budgetTokens = carryBudget)
    }

    var excludedCount = 0
    var skippedForBudget = 0
    val selected = if (mode == CarryOriginalsMode.RECENT) {
        val window = candidates.takeLast(count.coerceAtLeast(1))
        window.filter { item ->
            if (item.nodeId in excludedNodeIds) {
                excludedCount++
                false
            } else {
                true
            }
        }
    } else {
        val budget = carryBudget ?: DEFAULT_AUTO_CARRY_BUDGET
        val pickedReversed = ArrayList<CarriedOriginalItem>()
        var used = 0
        for (idx in candidates.indices.reversed()) {
            val item = candidates[idx]
            if (item.nodeId in excludedNodeIds) {
                excludedCount++
                continue
            }
            if (used + item.tokens <= budget) {
                pickedReversed.add(item)
                used += item.tokens
            } else {
                // 遇到第一条装不下的就停，保持按时间从新到旧的连续性
                for (k in idx downTo 0) {
                    if (candidates[k].nodeId !in excludedNodeIds) skippedForBudget++
                }
                break
            }
        }
        pickedReversed.reverse()
        pickedReversed
    }

    val totalTokens = selected.sumOf { it.tokens }
    val overBudget = mode == CarryOriginalsMode.RECENT &&
        selected.isNotEmpty() &&
        carryBudget != null &&
        totalTokens > carryBudget

    return CarriedOriginalsPlan(
        items = selected,
        totalTokens = totalTokens,
        excludedCount = excludedCount,
        skippedForBudget = skippedForBudget,
        overBudget = overBudget,
        budgetTokens = carryBudget,
    )
}

internal const val CARRIED_ORIGINALS_HEADING = "## Verbatim User Messages"

/** D7：将挑出的原话直接附在摘要之后写进检查点正文，可见、可编辑，删除检查点即连带撤销。 */
internal fun appendCarriedOriginals(summary: String, items: List<CarriedOriginalItem>): String {
    if (items.isEmpty()) return summary
    return buildString {
        append(summary.trimEnd())
        append("\n\n---\n\n")
        append(CARRIED_ORIGINALS_HEADING)
        append('\n')
        items.forEachIndexed { index, item ->
            append("\n### ").append(index + 1).append(". User\n")
            append(item.userText)
            if (!item.replyText.isNullOrBlank()) {
                append("\n\n### ").append(index + 1).append(". Assistant\n")
                append(item.replyText)
            }
            if (index < items.lastIndex) append('\n')
        }
    }
}

/** 把单条消息序列化为分段压缩输入（略过检查点与思考内容）。 */
internal fun serializeMessageForChunking(index: Int, message: UIMessage): String? {
    if (message.isContextCheckpoint) return null
    val roleLabel = when (message.role) {
        MessageRole.USER -> "User"
        MessageRole.ASSISTANT -> "Assistant"
        MessageRole.SYSTEM -> "System"
        MessageRole.TOOL -> "Tool"
    }
    val body = buildString {
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val text = message.displayTextOf(part).trim()
                    if (text.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(text)
                    }
                }
                is UIMessagePart.Tool -> {
                    if (isNotEmpty()) append("\n\n")
                    append("[Tool: ").append(part.toolName).append("]")
                    if (part.input.isNotBlank()) append("\nInput: ").append(part.input.trim())
                    val outText = part.output.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }.trim()
                    if (outText.isNotEmpty()) append("\nOutput: ").append(outText)
                }
                is UIMessagePart.ServerTool -> {
                    if (isNotEmpty()) append("\n\n")
                    append("[ServerTool: ").append(part.toolName).append("]")
                    part.input?.let { append("\nInput: ").append(it.toString()) }
                    part.output?.let { append("\nOutput: ").append(it.toString()) }
                }
                is UIMessagePart.Document -> {
                    if (isNotEmpty()) append("\n\n")
                    append("[Document: ").append(part.fileName.ifBlank { part.url }).append("]")
                }
                is UIMessagePart.Image -> {
                    if (isNotEmpty()) append("\n\n")
                    append("[Image]")
                }
                is UIMessagePart.Audio -> {
                    if (isNotEmpty()) append("\n\n")
                    append("[Audio]")
                }
                is UIMessagePart.Video -> {
                    if (isNotEmpty()) append("\n\n")
                    append("[Video]")
                }
                else -> Unit
            }
        }
    }.trim()
    if (body.isEmpty()) return null
    return "[Message #${index + 1} | $roleLabel]\n$body"
}

internal fun serializeForChunkedCompression(nodes: List<MessageNode>): List<String> =
    nodes.mapIndexedNotNull { index, node -> serializeMessageForChunking(index, node.currentMessage) }

/** 避免在 UTF-16 高低代理对中间截断，破坏 emoji 等增补平面字符。 */
internal fun utf16SafeEnd(text: String, end: Int): Int {
    val clamped = end.coerceIn(0, text.length)
    if (clamped <= 0 || clamped >= text.length) return clamped
    return if (Character.isHighSurrogate(text[clamped - 1]) && Character.isLowSurrogate(text[clamped])) {
        clamped - 1
    } else {
        clamped
    }
}

internal data class PlannedChunk(
    val prompt: String,
    val nextIndex: Int,
    val nextOffset: Int,
    val isFinal: Boolean,
    val segmentTokens: Int = 0,
)

/**
 * 按输入预算把待压缩的消息序列切分成一段段请求：
 * 尽量按完整消息边界切分，只有单条消息本身就超限时才按 UTF-16 安全边界并优先在换行处拆分。
 */
internal class ChunkCompressionPlanner(
    private val materials: List<String>,
    private val summaryTokens: Int,
    private val renderStage: (previousSummary: String, segment: String, targetTokens: Int) -> String,
    private val renderFinal: (previousSummary: String, segment: String, targetTokens: Int) -> String,
) {
    private fun effectiveTarget(budget: Int): Int = minOf(summaryTokens, (budget / 3).coerceAtLeast(128))

    private fun fits(prompt: String, budget: Int): Boolean =
        prompt.length <= MAX_PROMPT_CHARS && TokenCounter.count(prompt) <= budget

    private fun fitsSegment(segment: String, segmentCapTokens: Int?): Boolean =
        segmentCapTokens == null || TokenCounter.count(segment) <= segmentCapTokens

    private fun fragment(index: Int, start: Int, end: Int): String {
        val text = materials[index]
        if (start == 0 && end == text.length) return text
        val firstLine = text.substringBefore('\n').take(120)
        return "[Fragment ${index + 1}; $firstLine; UTF-16 $start..$end/${text.length}]\n${text.substring(start, end)}"
    }

    fun canFitEmptyPrompts(previousSummary: String, budget: Int): Boolean {
        val target = effectiveTarget(budget)
        return fits(renderStage(previousSummary, "", target), budget) &&
            fits(renderFinal(previousSummary, "", target), budget)
    }

    fun minPromptTokens(previousSummary: String, budget: Int): Int {
        val target = effectiveTarget(budget)
        return maxOf(
            TokenCounter.count(renderStage(previousSummary, "", target)),
            TokenCounter.count(renderFinal(previousSummary, "", target)),
        )
    }

    fun nextSegment(
        index: Int,
        offset: Int,
        previousSummary: String,
        budget: Int,
        segmentCapTokens: Int? = null,
    ): PlannedChunk {
        val target = effectiveTarget(budget)
        val emptyStage = renderStage(previousSummary, "", target)
        val emptyFinal = renderFinal(previousSummary, "", target)
        if (!fits(emptyStage, budget) || !fits(emptyFinal, budget)) {
            throw CompressRejectedException(
                CompressErrorCodes.COMPRESS_FAILED,
                "Compression prompt exceeds model context budget",
            )
        }
        if (index >= materials.size) {
            return PlannedChunk(emptyFinal, materials.size, 0, isFinal = true, segmentTokens = 0)
        }

        // 先看剩下全部材料能否在末段（带用户所选模板）里一次发完
        var remainingChars = 0L
        for (i in index until materials.size) {
            val start = if (i == index) offset else 0
            remainingChars += (materials[i].length - start) + if (i == index) 0 else 2
            if (remainingChars > MAX_PROMPT_CHARS) break
        }
        if (remainingChars <= MAX_PROMPT_CHARS - emptyFinal.length) {
            val allRemaining = (index until materials.size).joinToString("\n\n") { i ->
                val start = if (i == index) offset else 0
                fragment(i, start, materials[i].length)
            }
            val wholeFinal = renderFinal(previousSummary, allRemaining, target)
            if (fits(wholeFinal, budget) && fitsSegment(allRemaining, segmentCapTokens)) {
                return PlannedChunk(
                    prompt = wholeFinal,
                    nextIndex = materials.size,
                    nextOffset = 0,
                    isFinal = true,
                    segmentTokens = TokenCounter.count(allRemaining),
                )
            }
        }

        // 放不下末段或超出单段上限：按完整消息尽可能多装入阶段请求
        val availableChars = (MAX_PROMPT_CHARS - emptyStage.length).coerceAtLeast(0)
        val pieces = ArrayList<String>()
        var chars = 0
        for (i in index until materials.size) {
            val start = if (i == index) offset else 0
            val separator = if (pieces.isEmpty()) 0 else 2
            val piece = fragment(i, start, materials[i].length)
            if (chars + separator + piece.length > availableChars) break
            pieces.add(piece)
            chars += separator + piece.length
        }

        if (pieces.isNotEmpty()) {
            var low = 0
            var high = pieces.size
            while (low < high) {
                val mid = (low + high + 1) / 2
                val segText = pieces.take(mid).joinToString("\n\n")
                val candidate = renderStage(previousSummary, segText, target)
                if (fits(candidate, budget) && fitsSegment(segText, segmentCapTokens)) low = mid else high = mid - 1
            }
            var count = low
            // 若阶段请求吃光了全部剩余消息（末段因模板更长或刚好擦边），留最后一条给末段，确保最后一段必走用户模板
            if (count > 1 && index + count == materials.size) {
                count--
            }
            if (count > 0 && index + count < materials.size) {
                val segText = pieces.take(count).joinToString("\n\n")
                val prompt = renderStage(previousSummary, segText, target)
                return PlannedChunk(prompt, index + count, 0, isFinal = false, segmentTokens = TokenCounter.count(segText))
            }
        }

        // 单条消息（或最后一条剩余内容）本身放不下：按 UTF-16 安全边界切分
        val text = materials[index]
        val remaining = text.length - offset
        val splitCap = if (index == materials.lastIndex && remaining > 32) {
            minOf(remaining - 1, availableChars)
        } else {
            minOf(remaining, availableChars)
        }
        var low = 0
        var high = splitCap
        while (low < high) {
            val mid = (low + high + 1) / 2
            val end = utf16SafeEnd(text, offset + mid)
            val frag = if (end > offset) fragment(index, offset, end) else ""
            if (end > offset &&
                fits(renderStage(previousSummary, frag, target), budget) &&
                fitsSegment(frag, segmentCapTokens)
            ) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        var end = utf16SafeEnd(text, offset + low)
        if (end - offset < minOf(16, remaining)) {
            throw CompressRejectedException(
                CompressErrorCodes.COMPRESS_FAILED,
                "Single message fragment cannot fit model context budget",
            )
        }
        val slice = text.substring(offset, end)
        val halfway = slice.length / 2
        val paragraph = slice.lastIndexOf("\n\n")
        val line = slice.lastIndexOf('\n')
        val boundary = when {
            paragraph >= halfway -> offset + paragraph + 2
            line >= halfway -> offset + line + 1
            else -> end
        }.let { utf16SafeEnd(text, it) }
        if (boundary in (offset + 1)..end) {
            val boundaryFrag = fragment(index, offset, boundary)
            if (fits(renderStage(previousSummary, boundaryFrag, target), budget) &&
                fitsSegment(boundaryFrag, segmentCapTokens)
            ) {
                end = boundary
            }
        }
        val finalFrag = fragment(index, offset, end)
        val payload = renderStage(previousSummary, finalFrag, target)
        if (!fits(payload, budget)) {
            throw CompressRejectedException(
                CompressErrorCodes.COMPRESS_FAILED,
                "Message fragment exceeds model context budget",
            )
        }
        val finishedItem = end >= text.length
        return PlannedChunk(
            prompt = payload,
            nextIndex = if (finishedItem) index + 1 else index,
            nextOffset = if (finishedItem) 0 else end,
            isFinal = false,
            segmentTokens = TokenCounter.count(finalFrag),
        )
    }

    fun estimateChunkCount(
        initialPreviousSummary: String,
        budget: Int,
        maxCap: Int = 99,
        segmentCapTokens: Int? = null,
    ): Int {
        if (materials.isEmpty()) return if (initialPreviousSummary.isNotBlank()) 1 else 0
        var idx = 0
        var off = 0
        var prev = initialPreviousSummary
        var count = 0
        while (idx < materials.size && count < maxCap) {
            val seg = runCatching { nextSegment(idx, off, prev, budget, segmentCapTokens) }.getOrNull() ?: return maxCap
            count++
            if (seg.isFinal) return count
            if (seg.nextIndex >= materials.size) return (count + 1).coerceAtMost(maxCap)
            idx = seg.nextIndex
            off = seg.nextOffset
            if (count == 1) {
                val headroom = (budget - minPromptTokens("", budget) - 32).coerceAtLeast(16)
                val reserveTokens = minOf(summaryTokens, (budget / 4).coerceAtLeast(32), headroom)
                prev = "summary ".repeat(reserveTokens).trimEnd()
            }
        }
        return count.coerceAtLeast(1)
    }

    companion object {
        const val MAX_PROMPT_CHARS = 100_000
    }
}

/**
 * 若 [previousSummary] 自身已经大到空提示词放不进 [budget]，把它折进材料首条一起分段，避免卡死。
 */
internal fun prepareChunkingInputs(
    materials: List<String>,
    previousSummary: String,
    summaryTokens: Int,
    budget: Int,
    renderStage: (previousSummary: String, segment: String, targetTokens: Int) -> String,
    renderFinal: (previousSummary: String, segment: String, targetTokens: Int) -> String,
): Pair<List<String>, String> {
    if (previousSummary.isBlank()) return materials to ""
    val probe = ChunkCompressionPlanner(materials, summaryTokens, renderStage, renderFinal)
    return if (probe.canFitEmptyPrompts(previousSummary, budget)) {
        materials to previousSummary
    } else {
        (listOf("[Previous summary]\n$previousSummary") + materials) to ""
    }
}

/** 压缩前后的本地预估（不调模型），压缩对话框里显示。 */
data class CompressionEstimate(
    val summarizedMessages: Int,
    val summarizedChars: Int,
    val summarizedTokens: Int,
    val keptMessages: Int,
    val keptTokens: Int,
    val beforeTokens: Int,
    /** 压缩前用量来自服务端真实用量 */
    val beforeExact: Boolean,
    val afterMinTokens: Int,
    val afterMaxTokens: Int,
    /** 会话对话模型的输入预算；模型上下文窗口未知时为 null */
    val budgetTokens: Int?,
    val carriedCount: Int = 0,
    val carriedTokens: Int = 0,
    val carriedOverBudget: Boolean = false,
    val carriedSkippedForBudget: Int = 0,
    val carriedExcludedCount: Int = 0,
    val carriedItems: List<CarriedOriginalItem> = emptyList(),
    val hasPreviousCheckpoint: Boolean = false,
    val fromOriginal: Boolean = false,
    val willChunk: Boolean = false,
    val estimatedChunks: Int = 1,
    val maxChunkRequests: Int = CompressionConfig.DEFAULT_MAX_CHUNK_REQUESTS,
    val willBreakCache: Boolean = false,
)

/**
 * 按当前选项预估压缩结果；没有可压缩的消息时返回 null。
 *
 * 非从原文重压时，被总结部分包含上一份检查点摘要（它在请求开头，会被一并再总结）；
 * 从原文重压时跳过旧检查点，只统计原文消息。摘要按被总结部分的 10%–30% 估（不超过 [summaryTokens]），
 * 再加上 [carriedPlan] 的附带原话体积。
 */
internal fun Conversation.estimateCompression(
    keepRecent: Int,
    summaryTokens: Int,
    budget: Int?,
    fromOriginal: Boolean = false,
    carryMode: CarryOriginalsMode = CarryOriginalsMode.OFF,
    carryCount: Int = CompressionConfig.DEFAULT_CARRY_COUNT,
    carryIncludeReplies: Boolean = false,
    carryExcludedNodeIds: Set<Uuid> = emptySet(),
    carryEdits: Map<Uuid, CarriedOriginalEdit> = emptyMap(),
    targetPercent: Int = CompressionConfig.DEFAULT_TARGET_PERCENT,
    compressionBudget: Int? = budget,
    maxChunkRequests: Int = CompressionConfig.DEFAULT_MAX_CHUNK_REQUESTS,
    separateModel: Boolean = false,
    structure: String = builtInCompressStructure(CompressionTemplates.GENERAL),
    focus: String = "",
    locale: String = "en",
): CompressionEstimate? {
    val range = compressionRange(messageNodes, keepRecent, fromOriginal) ?: return null
    val checkpointIndex = messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
    val hasPrevCheckpoint = checkpointIndex >= 0
    val previousSummaryNode = if (fromOriginal) {
        null
    } else {
        messageNodes.getOrNull(checkpointIndex)?.currentMessage?.takeUnless { it.isClearContextCheckpoint }
    }
    val summarized = listOfNotNull(previousSummaryNode) + range.map { it.currentMessage }
    val keptLimit = if (fromOriginal) keepRecent else keepRecent.coerceAtMost(messageNodes.size - checkpointIndex - 1)
    val kept = messageNodes.takeLast(keptLimit.coerceAtLeast(0)).map { it.currentMessage }
    val summarizedTokens = summarized.sumOf { it.estimatedTokens() }
    val keptTokens = kept.sumOf { it.estimatedTokens() }
    val usage = contextUsage()
    val fixed = fixedContextTokens()
    val summaryMin = (summarizedTokens * 0.1).toInt().coerceAtMost(summaryTokens)
    val summaryMax = ceil(summarizedTokens * 0.3).toInt().coerceAtMost(summaryTokens)

    val carriedPlan = planCarriedOriginals(
        conversation = this,
        keepRecent = keepRecent,
        fromOriginal = fromOriginal,
        mode = carryMode,
        count = carryCount,
        includeReplies = carryIncludeReplies,
        excludedNodeIds = carryExcludedNodeIds,
        edits = carryEdits,
        summaryTokens = summaryTokens,
        targetPercent = targetPercent,
        chatBudget = budget,
    )

    val promptTokens = TokenCounter.count(buildCompressPrompt(structure, focus, summaryTokens, locale))
    val singlePassTokens = fixed + summarizedTokens + promptTokens
    val needsChunkByBudget = compressionBudget != null && singlePassTokens > compressionBudget
    val standaloneBudget = compressionBudget ?: DEFAULT_UNKNOWN_WINDOW_CHUNK_BUDGET
    val estimatedChunks = if (fromOriginal || needsChunkByBudget) {
        val rawMaterials = serializeForChunkedCompression(range)
        val prevText = previousSummaryNode?.toText().orEmpty()
        val stageRenderer = { prev: String, seg: String, target: Int ->
            buildChunkStagePrompt(prev, seg, focus, target, locale)
        }
        val finalRenderer = { prev: String, seg: String, target: Int ->
            buildChunkFinalPrompt(structure, prev, seg, focus, target, locale)
        }
        val (materials, initialPrev) = prepareChunkingInputs(
            rawMaterials, prevText, summaryTokens, standaloneBudget, stageRenderer, finalRenderer,
        )
        ChunkCompressionPlanner(materials, summaryTokens, stageRenderer, finalRenderer)
            .estimateChunkCount(initialPrev, standaloneBudget)
            .coerceAtLeast(1)
    } else {
        1
    }
    val willChunk = needsChunkByBudget || (fromOriginal && estimatedChunks > 1)
    val willBreakCache = fromOriginal || separateModel || willChunk

    return CompressionEstimate(
        summarizedMessages = summarized.size,
        summarizedChars = summarized.sumOf { it.toRawText().length },
        summarizedTokens = summarizedTokens,
        keptMessages = kept.size,
        keptTokens = keptTokens,
        beforeTokens = usage.tokens,
        beforeExact = usage.exact,
        afterMinTokens = fixed + summaryMin + keptTokens + carriedPlan.totalTokens,
        afterMaxTokens = fixed + summaryMax + keptTokens + carriedPlan.totalTokens,
        budgetTokens = budget,
        carriedCount = carriedPlan.items.size,
        carriedTokens = carriedPlan.totalTokens,
        carriedOverBudget = carriedPlan.overBudget,
        carriedSkippedForBudget = carriedPlan.skippedForBudget,
        carriedExcludedCount = carriedPlan.excludedCount,
        carriedItems = carriedPlan.items,
        hasPreviousCheckpoint = hasPrevCheckpoint,
        fromOriginal = fromOriginal,
        willChunk = willChunk,
        estimatedChunks = estimatedChunks,
        maxChunkRequests = maxChunkRequests,
        willBreakCache = willBreakCache,
    )
}

/** 压缩 / 清空记录里展示的被覆盖原文条目。 */
data class ContextRecordMessagePreview(
    val nodeId: Uuid,
    val messageId: Uuid,
    val nodeIndex: Int,
    val role: MessageRole,
    val previewText: String,
)

/** 会话里一条压缩或清空检查点记录。 */
data class ContextCheckpointRecord(
    val nodeId: Uuid,
    val messageId: Uuid,
    val nodeIndex: Int,
    val createdAt: String,
    val isClear: Boolean,
    val summaryText: String,
    val meta: ContextCheckpointMetadata?,
    val rangeStartIndex: Int?,
    val rangeEndIndex: Int?,
    val rangeStartNodeId: Uuid?,
    val rangeEndNodeId: Uuid?,
    val coveredMessageCount: Int,
    val coveredPreviews: List<ContextRecordMessagePreview>,
    val isActive: Boolean,
    val laterCheckpointNodeIds: List<Uuid>,
    val supersededByNodeId: Uuid?,
)

private fun messagePreviewSnippet(message: UIMessage): String {
    val text = message.toText().replace(Regex("\\s+"), " ").trim()
    if (text.isNotEmpty()) return text.take(160)
    return when (val firstPart = message.parts.firstOrNull()) {
        is UIMessagePart.Tool -> "[Tool: ${firstPart.toolName}]"
        is UIMessagePart.ServerTool -> "[ServerTool: ${firstPart.toolName}]"
        is UIMessagePart.Document -> "[Document: ${firstPart.fileName.ifBlank { "file" }}]"
        is UIMessagePart.Image -> "[Image]"
        is UIMessagePart.Audio -> "[Audio]"
        is UIMessagePart.Video -> "[Video]"
        is UIMessagePart.Reasoning -> "[Reasoning]"
        else -> "[Message]"
    }
}

/** 列出当前会话里的全部压缩/清空检查点，按时间顺序（旧在前），并解析对应原文范围与后继关系。 */
internal fun listContextRecords(nodes: List<MessageNode>): List<ContextCheckpointRecord> {
    val checkpointIndices = nodes.indices.filter { nodes[it].currentMessage.isContextCheckpoint }
    if (checkpointIndices.isEmpty()) return emptyList()
    val lastCheckpointIndex = checkpointIndices.last()
    return checkpointIndices.mapIndexed { pos, cpIdx ->
        val cpNode = nodes[cpIdx]
        val cpMsg = cpNode.currentMessage
        val meta = cpMsg.checkpointMeta
        val prevCpIdx = if (pos > 0) checkpointIndices[pos - 1] else -1

        val startMetaIdx = meta?.rangeStartNodeId?.let { sid ->
            nodes.indexOfFirst { it.id == sid }.takeIf { it in 0 until cpIdx }
        }
        val endMetaIdx = meta?.rangeEndNodeId?.let { eid ->
            nodes.indexOfFirst { it.id == eid }.takeIf { it in 0 until cpIdx }
        }
        val coveredIndexed = when {
            meta?.kind == ContextCheckpointKind.CLEAR && meta.summarizedCount == 0 -> emptyList()
            startMetaIdx != null && endMetaIdx != null && startMetaIdx <= endMetaIdx ->
                (startMetaIdx..endMetaIdx).mapNotNull { i ->
                    nodes[i].takeUnless { it.currentMessage.isContextCheckpoint }?.let { i to it }
                }
            meta?.fromOriginal == true ->
                (0 until cpIdx).mapNotNull { i ->
                    nodes[i].takeUnless { it.currentMessage.isContextCheckpoint }?.let { i to it }
                }
            else ->
                ((prevCpIdx + 1) until cpIdx).mapNotNull { i ->
                    nodes[i].takeUnless { it.currentMessage.isContextCheckpoint }?.let { i to it }
                }
        }
        val previews = coveredIndexed.map { (idx, node) ->
            val msg = node.currentMessage
            ContextRecordMessagePreview(
                nodeId = node.id,
                messageId = msg.id,
                nodeIndex = idx,
                role = msg.role,
                previewText = messagePreviewSnippet(msg),
            )
        }
        val laterIds = checkpointIndices.drop(pos + 1).map { nodes[it].id }
        val isActive = cpIdx == lastCheckpointIndex
        val supersededBy = if (isActive) {
            null
        } else {
            val fromOrigLater = checkpointIndices.drop(pos + 1)
                .firstOrNull { nodes[it].currentMessage.checkpointMeta?.fromOriginal == true }
            nodes[fromOrigLater ?: checkpointIndices[pos + 1]].id
        }
        ContextCheckpointRecord(
            nodeId = cpNode.id,
            messageId = cpMsg.id,
            nodeIndex = cpIdx,
            createdAt = cpMsg.createdAt.toString(),
            isClear = cpMsg.isClearContextCheckpoint,
            summaryText = if (cpMsg.isClearContextCheckpoint) "" else cpMsg.toText(),
            meta = meta,
            rangeStartIndex = coveredIndexed.firstOrNull()?.first,
            rangeEndIndex = coveredIndexed.lastOrNull()?.first,
            rangeStartNodeId = coveredIndexed.firstOrNull()?.second?.id ?: meta?.rangeStartNodeId,
            rangeEndNodeId = coveredIndexed.lastOrNull()?.second?.id ?: meta?.rangeEndNodeId,
            coveredMessageCount = if (coveredIndexed.isNotEmpty()) coveredIndexed.size else (meta?.summarizedCount ?: 0),
            coveredPreviews = previews,
            isActive = isActive,
            laterCheckpointNodeIds = laterIds,
            supersededByNodeId = supersededBy,
        )
    }
}

/** 会话非空且最后一条还不是「清空上下文」时才允许清空上下文。 */
internal fun canClearContext(nodes: List<MessageNode>): Boolean =
    nodes.isNotEmpty() && !nodes.last().currentMessage.isClearContextCheckpoint

/** 找出在 [messageId] 之后、会遮蔽它的所有检查点（按出现顺序）；改/删原文前提示用户。 */
internal fun affectedCheckpointsForMessage(nodes: List<MessageNode>, messageId: Uuid): List<MessageNode> {
    val idx = nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (idx < 0 || nodes[idx].currentMessage.isContextCheckpoint) return emptyList()
    return nodes.drop(idx + 1).filter { it.currentMessage.isContextCheckpoint }
}

/** 撤销指定检查点（及可选的后续检查点）；找不到或不是检查点返回 null。 */
internal fun revokeCheckpointNodes(
    nodes: List<MessageNode>,
    checkpointNodeId: Uuid,
    cascadeLater: Boolean,
): Pair<List<MessageNode>, List<Uuid>>? {
    val idx = nodes.indexOfFirst { it.id == checkpointNodeId && it.currentMessage.isContextCheckpoint }
    if (idx < 0) return null
    val removedIds = buildList {
        add(checkpointNodeId)
        if (cascadeLater) {
            for (i in (idx + 1) until nodes.size) {
                if (nodes[i].currentMessage.isContextCheckpoint) add(nodes[i].id)
            }
        }
    }
    val removedSet = removedIds.toSet()
    return nodes.filterNot { it.id in removedSet } to removedIds
}

/** 上下文用量分项（各分项均由本地 `TokenCounter` 估算，总计若有真实用量则标明 `totalExact`）。 */
data class ContextUsageBreakdown(
    val totalTokens: Int,
    val totalExact: Boolean,
    val reportedContextTokens: Int?,
    val estimatedTotalTokens: Int,
    val contextLength: Int?,
    val inputBudgetTokens: Int?,
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

internal fun estimateToolTokens(tool: Tool, model: Model, messages: List<UIMessage>): Int {
    val schemaTokens = tool.parameters()?.let { TokenCounter.count(Json.encodeToString(InputSchema.serializer(), it)) } ?: 0
    val promptText = tool.systemPrompt(model, messages)
    val promptTokens = if (promptText.isNotBlank()) TokenCounter.count(promptText) else 0
    return TokenCounter.count(tool.name) + TokenCounter.count(tool.description) + schemaTokens + 12 + promptTokens
}

internal fun estimateAttachmentPartTokens(
    part: UIMessagePart,
    readDocument: (UIMessagePart.Document) -> String,
): Int = when (part) {
    is UIMessagePart.Image, is UIMessagePart.Audio, is UIMessagePart.Video -> IMAGE_TOKEN_ESTIMATE
    is UIMessagePart.Document -> TokenCounter.count(readDocument(part)) + 16
    else -> 0
}

internal fun UIMessage.historyBodyEstimatedTokens(): Int = MESSAGE_OVERHEAD_TOKENS + parts.sumOf { part ->
    when (part) {
        is UIMessagePart.Text -> TokenCounter.count(part.text)
        is UIMessagePart.Tool -> TokenCounter.count(part.input) +
            part.output.sumOf { (it as? UIMessagePart.Text)?.let { text -> TokenCounter.count(text.text) } ?: 0 }
        is UIMessagePart.ServerTool -> (part.input?.toString()?.let(TokenCounter::count) ?: 0) +
            (part.output?.toString()?.let(TokenCounter::count) ?: 0)
        else -> 0
    }
}

internal fun estimateDraftPartsTokens(
    parts: List<UIMessagePart>,
    readDocument: (UIMessagePart.Document) -> String,
): Int {
    if (parts.isEmpty()) return 0
    var tokens = 0
    var hasContent = false
    parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) {
                hasContent = true
                tokens += TokenCounter.count(part.text)
            }
            is UIMessagePart.Image, is UIMessagePart.Document, is UIMessagePart.Audio, is UIMessagePart.Video -> {
                hasContent = true
                tokens += estimateAttachmentPartTokens(part, readDocument)
            }
            else -> Unit
        }
    }
    return if (hasContent) tokens + MESSAGE_OVERHEAD_TOKENS else 0
}
