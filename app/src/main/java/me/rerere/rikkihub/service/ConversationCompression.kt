package me.rerere.rikkihub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode

/**
 * 后台压缩会话历史：状态、参数与纯函数部分。执行与提交在 [ChatService.startCompression]。
 *
 * 压缩不删任何消息，只在切点插入一条带 `isContextCheckpoint` 标记的摘要；组装请求时从最后一个
 * 检查点开始取，删掉这条摘要就等于撤销压缩，再次压缩只处理上一个检查点之后的部分。
 */
enum class CompressOrigin { APP, WEB }

enum class CompressStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class CompressState(
    val status: CompressStatus,
    val origin: CompressOrigin,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
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
    const val COMPRESS_FAILED = "COMPRESS_FAILED"
}

/** Busy reason reported while a compression owns the conversation. */
const val BUSY_REASON_COMPRESSING = "COMPRESSING"

/** A compression that was refused or could not be committed; [code] is one of [CompressErrorCodes]. */
class CompressRejectedException(val code: String, message: String) : Exception(message)

/**
 * 压缩参数。压缩固定用会话的聊天模型和结构化总结指令（与正常对话同一套请求组装，命中提示词缓存），
 * 用户可选的是附加要求和要保留的最近消息数。
 *
 * [keepRecent] 为 0 时压缩全部新消息，请求和上一轮对话前缀一致，缓存命中最好；大于 0 时最近的这些
 * 消息不参与压缩，检查点插在它们之前、它们留在摘要之后，对话已经长到整段发不出去时用来缩小压缩请求。
 */
data class CompressRequest(
    val additionalPrompt: String = "",
    val keepRecent: Int = 0,
) {
    init {
        require(additionalPrompt.length <= MAX_ADDITIONAL_PROMPT) {
            "additionalPrompt must be at most $MAX_ADDITIONAL_PROMPT characters"
        }
        require(keepRecent in 0..MAX_KEEP_RECENT) {
            "keepRecent must be between 0 and $MAX_KEEP_RECENT"
        }
    }

    companion object {
        const val MAX_ADDITIONAL_PROMPT = 2_000
        const val MAX_KEEP_RECENT = 100_000
    }
}

/**
 * 要压缩的范围：上一个检查点之后的节点，去掉最后 [keepRecent] 个；没有可压缩的节点时返回 null。
 * 范围之后的节点原样留在检查点之后。
 */
internal fun compressionRange(nodes: List<MessageNode>, keepRecent: Int = 0): List<MessageNode>? {
    val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
    val end = nodes.size - keepRecent.coerceAtLeast(0)
    if (end <= checkpointIndex + 1) return null
    return nodes.subList(checkpointIndex + 1, end)
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
