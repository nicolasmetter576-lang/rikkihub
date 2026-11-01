package me.rerere.ai.provider.stream

/**
 * 流式工具调用解码阶段的协议错误。
 *
 * wire 形态无法被可靠恢复且继续执行会混合/错配调用时抛出：非空 id 冲突
 * （同 index 换 id、同 id 跨 index 两个进行中调用）、缺 index 且多个进行中
 * 块无法唯一定位、[me.rerere.ai.ui.StreamChunk.ToolCallIdAssigned] 落在未知
 * streamKey/已执行/已 End 的调用、工具名终态零候选/多候选、候选数/长度超界等。
 *
 * **故意不是 `IOException` 子类**：协议错误不是网络故障，绝不能进网络重试
 * 路径；消费侧（如 `StreamChunkHandlingException` 包装链）须 unwrap 后按
 * [code] 分类，当前 provider attempt fail-closed，不执行任何客户端工具。
 *
 * [code] 是稳定的机器可读分类；[message] 只含结构化标识（index/id 状态），
 * 不复制协议正文或工具参数。
 */
class ToolStreamProtocolException(
    val code: String,
    message: String,
) : IllegalStateException(message)
