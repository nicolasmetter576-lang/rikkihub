package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole

/**
 * 每轮标题：模型按约定在回复最后一行写 `<rk-title>标题</rk-title>`。
 * 原文（含标签）原样存储、原样发回历史；生成结束时解析进 [UIMessage.title]，显示、复制、搜索等一律去掉标签。
 * 只认一段文字末尾那一行的标签：正文中间出现的（比如模型在讲解这个格式）不算。
 */
const val MESSAGE_TITLE_OPEN = "<rk-title>"
const val MESSAGE_TITLE_CLOSE = "</rk-title>"

private val TRAILING_TITLE = Regex("""[ \t]*<rk-title>([^\n]*?)</rk-title>\s*\z""")

/** 这段文字末行的标题，没有则为 null。 */
fun String.messageTitleOrNull(): String? =
    TRAILING_TITLE.find(this)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 去掉末行的标题标签。[streaming] 时回复还在生成，末尾半截的标签（`<rk-ti`、`<rk-title>标题未写完`）也藏起来，免得闪出来。
 */
fun String.stripMessageTitle(streaming: Boolean = false): String {
    TRAILING_TITLE.find(this)?.let { return removeRange(it.range).trimEnd() }
    if (!streaming) return this
    val lineStart = lastIndexOf('\n') + 1
    val open = indexOf(MESSAGE_TITLE_OPEN, lineStart)
    if (open >= 0) {
        return if (indexOf(MESSAGE_TITLE_CLOSE, open) < 0) substring(0, open).trimEnd() else this
    }
    val lt = lastIndexOf('<')
    return if (lt >= lineStart && MESSAGE_TITLE_OPEN.startsWith(substring(lt))) substring(0, lt).trimEnd() else this
}

/** 一条助手回复里的标题：各段文字里最后出现的那个。 */
fun UIMessage.extractMessageTitle(): String? =
    if (role != MessageRole.ASSISTANT) null
    else parts.filterIsInstance<UIMessagePart.Text>().mapNotNull { it.text.messageTitleOrNull() }.lastOrNull()

/** 显示用的文字：助手消息去掉标题标签，其余原样。 */
fun UIMessage.displayTextOf(part: UIMessagePart.Text, streaming: Boolean = false): String =
    if (role == MessageRole.ASSISTANT) part.text.stripMessageTitle(streaming) else part.text
