package me.rerere.ai.provider.providers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 只有一个系统提示词位置的协议（Claude、Gemini、Responses、Interactions、Devin）用的拆分，照酒馆的转换
 * （prompt-converters.js）：开头连续的系统消息是 [system]；后面的（酒馆预设里助手回合之后的条目、聊天之后的条目、
 * 深度注入）原位改成用户消息，并入相邻的用户消息，两条本来就是用户的消息不合并。
 * 只有空白的系统消息（预设里只含 `{{setvar}}` 之类宏的条目求值后）不发：Claude 拒收空白文字块。
 */
internal class SystemPromptSplit(val system: List<UIMessage>, val conversation: List<UIMessage>)

internal fun List<UIMessage>.splitSystemPrompt(): SystemPromptSplit {
    val messages = filterNot { message ->
        message.role == MessageRole.SYSTEM && message.parts.all { it is UIMessagePart.Text && it.text.isBlank() }
    }
    val system = messages.takeWhile { it.role == MessageRole.SYSTEM }
    val conversation = ArrayList<UIMessage>(messages.size - system.size)
    // conversation 末条里有没有本来就是用户的消息
    var lastHasRealUser = false
    for (message in messages.drop(system.size)) {
        val converted = message.role == MessageRole.SYSTEM
        val realUser = message.role == MessageRole.USER
        val last = conversation.lastOrNull()
        if (last?.role == MessageRole.USER && (converted || realUser && !lastHasRealUser)) {
            // 合并后的消息沿用真实用户消息的 id 等属性
            val base = if (converted) last else message
            conversation[conversation.lastIndex] = base.copy(parts = last.parts + message.parts)
            lastHasRealUser = lastHasRealUser || realUser
        } else {
            conversation += if (converted) message.copy(role = MessageRole.USER) else message
            lastHasRealUser = realUser
        }
    }
    return SystemPromptSplit(system, conversation)
}

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse。
 * 带步界标记（[UIMessagePart.Tool.startsStep]）的工具另起一组：[Tool1, Tool2(步首)] 分成两组。
 */
/**
 * Claude 和 Gemini 的请求必须以用户消息开头；聊天以助手消息开头时（角色的开场白）前面补一句用户消息，
 * 文字同酒馆的 `promptPlaceholder` 默认值。
 */
internal const val LEADING_USER_PLACEHOLDER = "Let's get started."

internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            // 另一次回复里的调用不并进上一步：请求里上一步已经单独成组，合并会改写已发出的前缀
            if (part.startsStep) flushTools()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}
