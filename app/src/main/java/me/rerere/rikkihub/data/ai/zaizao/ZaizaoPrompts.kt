package me.rerere.rikkihub.data.ai.zaizao

import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat

/**
 * 再造提示词。可编辑的只有各类型的「任务」一节；文档、底稿说明、作者的要求和输出格式由代码固定拼接，
 * 用户改坏模板也不影响解析。第一条消息放文档（会话内各轮共用，前缀缓存能命中），之后每轮只追加短消息。
 */
object ZaizaoPrompts {
    val DEFAULT_MODIFY = """
        按作者的要求对底稿做局部修改：只改和要求有关的句子与措辞，要求没涉及的部分保持原样；改动的地方要与上下文的情节、人物口吻、人称和时态一致，读起来不留拼接痕迹。
    """.trimIndent()

    val DEFAULT_INCREMENT = """
        按作者的要求在底稿里补充新内容（细节、动作、对话、描写等）：底稿原有的文字基本保留，新内容融进叙述中合适的位置，不要只堆在开头或结尾；补充的内容要与上下文的情节、人物和文风一致。
    """.trimIndent()

    val DEFAULT_REWRITE = """
        按作者的要求重写底稿，彻底抛弃底稿的写法：先抽出底稿里的事件和信息（发生了什么、交代了什么），再从零开始写一个全新的版本。事件和信息保持不变（作者要求改动的除外）；呈现手法、描写和段落结构都要彻底换掉，不得重复本次对话中已经用过的写法——例如之前以用典呈现、引用了典故 A，这次可以换用典故 B，或改用对偶、通感、白描等手法。
    """.trimIndent()

    fun defaultFor(type: ZaizaoType): String = when (type) {
        ZaizaoType.MODIFY -> DEFAULT_MODIFY
        ZaizaoType.INCREMENT -> DEFAULT_INCREMENT
        ZaizaoType.REWRITE -> DEFAULT_REWRITE
    }

    fun typeName(type: ZaizaoType): String = when (type) {
        ZaizaoType.MODIFY -> "修改"
        ZaizaoType.INCREMENT -> "增量"
        ZaizaoType.REWRITE -> "重写"
    }

    /** 第一轮：角色说明、文档（后文开关关着时文档只到选区为止）、原文，再接本轮。 */
    fun first(
        config: ZaizaoConfig,
        fileName: String,
        context: ZaizaoContext,
        format: TuiqiaoFormat,
        round: ZaizaoRound,
        avoid: List<String> = emptyList(),
    ): String = buildString {
        appendLine("你是一位文字编辑，正在按作者的要求再造文档里的一段文字。下面是作者正在写的文档，需要再造的部分用 <selection> 标出。")
        appendLine()
        appendLine("<document name=\"${fileName.replace("\"", "'")}\">")
        append(context.before)
        append("<selection>")
        append(context.selection)
        append("</selection>")
        append(context.after)
        appendLine()
        appendLine("</document>")
        if (!context.includeAfter) {
            appendLine("（文档只提供到选区为止，选区之后的内容没有给出：不必考虑与后文衔接。）")
        }
        appendLine()
        appendLine("需要再造的原文：")
        appendLine("<selection>${context.selection}</selection>")
        appendLine()
        append(roundBlock(config, round, format, avoid, base = "底稿就是上面 <selection> 里的原文。", full = true))
    }

    /** 之后的每一轮：以上一条回复里的 result 为底稿。 */
    fun followUp(
        config: ZaizaoConfig,
        format: TuiqiaoFormat,
        round: ZaizaoRound,
        avoid: List<String> = emptyList(),
    ): String = buildString {
        appendLine("接着改：进行下一轮再造。")
        appendLine()
        append(roundBlock(config, round, format, avoid, base = "底稿是你上一条回复里 <result> 中的文字。", full = false))
    }

    private fun roundBlock(
        config: ZaizaoConfig,
        round: ZaizaoRound,
        format: TuiqiaoFormat,
        avoid: List<String>,
        base: String,
        full: Boolean,
    ): String = buildString {
        appendLine("本轮类型：${typeName(round.type)}")
        appendLine("任务：")
        appendLine(config.promptFor(round.type).trim())
        appendLine(base)
        appendLine()
        appendLine("作者的要求：")
        appendLine("<requirement>")
        appendLine(round.requirement.trim())
        appendLine("</requirement>")
        if (avoid.isNotEmpty()) {
            appendLine()
            append("这一轮已经给过下面这些稿，新稿要和它们明显不同")
            appendLine(if (round.type == ZaizaoType.REWRITE) "：呈现手法、描写和段落结构都不得重复。" else "：换一种改法。")
            avoid.forEach { appendLine("<draft>").appendLine(it.trim()).appendLine("</draft>") }
        }
        appendLine()
        appendLine("输出要求：")
        appendLine("- ${outputLine(round.type)}")
        if (full) {
            appendLine("- result 里只写用来替换 <selection> 的文字，不要带上选区前后的原文，不要加解释、标题或代码块；语言与原文一致。")
            append("- ").append(markupRule(format))
        } else {
            append("- 其余输出要求同第一轮。")
        }
    }

    private fun outputLine(type: ZaizaoType): String = when (type) {
        ZaizaoType.REWRITE -> "先输出 <plan>…</plan>：逐条列出底稿里的事件与信息、本次对话中已经用过的写法（呈现手法、描写、段落结构）、这一稿打算换用的写法；再输出 <result>…</result>。这两段之外不要输出任何内容。"
        else -> "只输出 <result>…</result>，result 之外不要输出任何内容。"
    }

    private fun markupRule(format: TuiqiaoFormat): String = when (format) {
        TuiqiaoFormat.PLAIN -> "原文里的符号、标签、时间码等非正文内容原样保留，不要添加 Markdown 标记。"
        TuiqiaoFormat.MARKDOWN -> "原文里的 Markdown 标记（如 **、*、~~、`、[文字](链接)）和链接保留，作者的要求另有说明的除外。"
    }
}
