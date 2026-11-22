package me.rerere.rikkihub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.extractMessageTitle

/**
 * 开了每轮标题的助手：把约定追加在系统提示词末尾。排在所有输入转换之后，约定文字每轮不变，不破坏提示词缓存前缀。
 */
object MessageTitleConventionTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!ctx.assistant.enableMessageTitle) return messages
        val prompt = ctx.settings.messageTitlePrompt.takeIf { it.isNotBlank() } ?: return messages
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt").copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }
}

/**
 * 生成结束时把回复末行的 `<rk-title>` 解析进 [UIMessage.title]；原文不动（照原样发回历史）。
 * 开关关着也照样解析：老对话里已经写了标签的回复仍然有标题。这一步没写标题时保留前几步解析出的。
 */
object MessageTitleTransformer : OutputMessageTransformer {
    override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map { message -> message.extractMessageTitle()?.let { message.copy(title = it) } ?: message }
}
