package me.rerere.rikkihub.data.ai

import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * 一次流式生成 attempt 因流协议错误 ([ToolStreamProtocolException]) 失败。
 *
 * [GenerationLoop] 在逐 attempt 的 catch 里把协议错误包装成本异常立即终止
 * 当前生成: 不做网络重试、不发 StepFinished/GenerationEnd。携带的恢复
 * descriptor 交给权威消费侧 (ChatService 会话态 / 子代理运行日志) 消费:
 * 把 [assistantMessageId] 消息恢复为本 attempt 开始前的完整快照
 * [baseMessage] (= 循环内 `responseBaseMessages.last()`)。
 *
 * 为什么不靠 emit 恢复: 上游异常经 `flowOn` channel 上抛时在途的 Messages
 * 事件可能已被丢弃, 消费端无法保证见过“半截状态”; 因此恢复只按 id 命中
 * 权威状态里**已存在**的消息原位替换 —— 目标从未发布过时是 no-op,
 * 绝不能为清理新建消息; 也不会回写整棵会话树、不标伪 Denied、不伪造
 * 工具 output。[baseMessage] 内的先前成功步/已执行工具原样保留。
 *
 * 恢复操作幂等: 目标已等于 [baseMessage] 时不产生任何变更。
 */
class GenerationAttemptProtocolException(
    /** 底层协议错误 (code 见 [ToolStreamProtocolException.code])。 */
    val protocolError: ToolStreamProtocolException,
    /** 所属生成任务的会话 id; 消费侧据此验证任务归属, 不匹配则拒绝清理。 */
    val conversationId: Uuid?,
    /** 失败 attempt 写入的 assistant 消息 id (即 [baseMessage].id)。 */
    val assistantMessageId: Uuid,
    /** 该步内的 attempt 下标 (0 起; 网络重试会递增)。 */
    val attemptIndex: Int,
    /** 该 attempt 开始前的完整尾消息快照 (含此前成功步的全部 parts)。 */
    val baseMessage: UIMessage,
) : IllegalStateException(
    // 只记结构化标识, 不复制协议正文/工具参数, 日志不泄露 args。
    "stream protocol failure in generation attempt #$attemptIndex " +
        "(conversation=$conversationId, assistant=$assistantMessageId)",
    protocolError,
) {
    val protocolCode: String get() = protocolError.code
}

/**
 * 把列表中 id 命中 [failure] 目标的消息原位恢复为 `baseMessage`。
 *
 * - 目标从未发布 (无同 id 消息) -> 原样返回, 不为清理新建消息;
 * - 目标已等于 baseMessage -> 原样返回 (幂等, 含实例相等);
 * - 其余消息一律不动 (只回滚当前失败 attempt 的尾消息,
 *   不回写整棵会话树)。
 */
internal fun List<UIMessage>.rollbackProtocolFailedAttempt(
    failure: GenerationAttemptProtocolException,
): List<UIMessage> {
    var changed = false
    val restored = map { message ->
        if (message.id == failure.assistantMessageId && message != failure.baseMessage) {
            changed = true
            failure.baseMessage
        } else {
            message
        }
    }
    return if (changed) restored else this
}

/**
 * 会话级协议失败恢复: 同 id 候选可能落在其他分支槽位 (网络重试共享同一
 * assistant 消息 id), 一律按 id 恢复; 节点结构、selectIndex、其余消息不变。
 * 未发生任何替换时返回原实例 (幂等, 供持久化层跳过写库)。
 */
internal fun Conversation.restoreProtocolFailedAttempt(
    failure: GenerationAttemptProtocolException,
): Conversation {
    var changed = false
    val nodes = messageNodes.map { node ->
        val restored = node.messages.rollbackProtocolFailedAttempt(failure)
        if (restored === node.messages) {
            node
        } else {
            changed = true
            node.copy(messages = restored)
        }
    }
    return if (changed) copy(messageNodes = nodes) else this
}
