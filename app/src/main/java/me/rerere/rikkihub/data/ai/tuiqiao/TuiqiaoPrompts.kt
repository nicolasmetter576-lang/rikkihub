package me.rerere.rikkihub.data.ai.tuiqiao

/**
 * 推敲提示词。可编辑的只有各档的「改写要求」（占位符 `{count}`、`{explanation}`）；
 * 文档上下文与输出格式由 [build] 固定拼接，用户改坏模板也不影响解析。
 * 文档放在最前、任务放在最后：同一文件反复推敲时，各家的自动前缀缓存能命中。
 */
object TuiqiaoPrompts {
    val DEFAULT_WORD = """
        选中的是一个词语或短语。请给出 {count} 个可以替换它的近义词或短语，放回原句后要通顺，贴合上下文的语境和文风。tone 写出它和原词的细微差别（如：更书面、更口语、语气更重、更含蓄），2～6 个字。{explanation}
    """.trimIndent()

    val DEFAULT_SENTENCE = """
        选中的是一句话。请给出 {count} 种改写：意思不变，变换句式、节奏或语气（如：短促有力、舒缓、冷峻、口语化），并和前后文自然衔接。tone 用 2～6 个字概括这种写法的语气。{explanation}
    """.trimIndent()

    val DEFAULT_PARAGRAPH = """
        选中的是一段文字。请给出 {count} 种整体重写：保留原意、情节信息、人物口吻、人称和时态，可以调整结构、节奏和措辞；原文分了段的保持分段。tone 用 2～6 个字概括这一版的风格。{explanation}
    """.trimIndent()

    fun defaultFor(mode: TuiqiaoMode): String = when (mode) {
        TuiqiaoMode.WORD -> DEFAULT_WORD
        TuiqiaoMode.SENTENCE -> DEFAULT_SENTENCE
        TuiqiaoMode.PARAGRAPH -> DEFAULT_PARAGRAPH
    }

    fun explanationRule(explanation: TuiqiaoExplanation): String = when (explanation) {
        TuiqiaoExplanation.OFF -> "不需要解释，不要输出 why。"
        TuiqiaoExplanation.ONE_LINE -> "why 用一句话（不超过 40 字）说明为什么这样改更合适。"
        TuiqiaoExplanation.DETAILED -> "why 用 2～4 句话说明为什么这样改更合适：和原文差在哪里、适合什么场合。"
    }

    /** 单遍替换：模板里的 `{count}` / `{explanation}`。不做链式 replace，替换值里的花括号不会被再次展开。 */
    fun fillTemplate(template: String, count: Int, explanation: TuiqiaoExplanation): String =
        PLACEHOLDER.replace(template) { match ->
            when (match.groupValues[1]) {
                "count" -> count.toString()
                "explanation" -> explanationRule(explanation)
                else -> match.value
            }
        }

    fun build(
        config: TuiqiaoConfig,
        mode: TuiqiaoMode,
        fileName: String,
        context: TuiqiaoContext,
        setting: String = "",
        format: TuiqiaoFormat = TuiqiaoFormat.of(fileName),
        count: Int = config.effectiveCandidateCount,
    ): String {
        val task = fillTemplate(config.promptFor(mode), count, config.explanation)
        val off = config.explanation == TuiqiaoExplanation.OFF
        return compose(
            fileName = fileName,
            context = context,
            setting = setting,
            task = task,
            count = count,
            fields = if (off) "text、tone" else "text、tone、why",
            example = if (off) """{"text":"…","tone":"…"}""" else """{"text":"…","tone":"…","why":"…"}""",
            markupRule = markupRule(format, strict = true),
            extraRules = listOf("各候选之间要有明显差别，不要和原文相同。"),
        )
    }

    /**
     * 推敲角色：角色的要求替换「任务」一节，只要一条；上下文、解释长度、输出格式与常规候选相同。
     * 不含助手提示词或任何工具说明。
     */
    fun buildRole(
        config: TuiqiaoConfig,
        role: TuiqiaoRole,
        fileName: String,
        context: TuiqiaoContext,
        setting: String = "",
        format: TuiqiaoFormat = TuiqiaoFormat.of(fileName),
    ): String {
        val off = config.explanation == TuiqiaoExplanation.OFF
        val task = buildString {
            appendLine(role.prompt.trim())
            append("给出 1 个结果。text 会直接替换 <selection>：要求是增补（如加旁白、批注）时，text 写原文连同增补的内容；要求是改写时，text 写改写后的文字。")
            append(explanationRule(config.explanation))
        }
        return compose(
            fileName = fileName,
            context = context,
            setting = setting,
            task = task,
            count = 1,
            fields = if (off) "text" else "text、why",
            example = if (off) """{"text":"…"}""" else """{"text":"…","why":"…"}""",
            markupRule = markupRule(format, strict = false),
            extraRules = listOf("结果不要和原文相同。"),
        )
    }

    private fun markupRule(format: TuiqiaoFormat, strict: Boolean): String = when {
        format == TuiqiaoFormat.PLAIN -> "原文里的符号、标签、时间码等非正文内容原样保留，不要添加 Markdown 标记。"
        strict -> "原文里的 Markdown 标记（如 **、*、~~、`、[文字](链接)）必须原样保留、数量对应，不要新增标记。"
        else -> "原文里的 Markdown 标记（如 **、*、~~、`、[文字](链接)）必须全部保留；新增的标记要成对。"
    }

    private fun compose(
        fileName: String,
        context: TuiqiaoContext,
        setting: String,
        task: String,
        count: Int,
        fields: String,
        example: String,
        markupRule: String,
        extraRules: List<String>,
    ): String = buildString {
        appendLine("你是一位文字编辑，正在帮作者推敲字句。下面是作者正在写的文档，需要推敲的部分用 <selection> 标出。")
        appendLine()
        appendLine("<document name=\"${fileName.replace("\"", "'")}\">")
        append(context.before)
        append("<selection>")
        append(context.selection)
        append("</selection>")
        append(context.after)
        appendLine()
        appendLine("</document>")
        if (setting.isNotBlank()) {
            appendLine()
            appendLine("<setting>")
            appendLine(setting.trim())
            appendLine("</setting>")
        }
        appendLine()
        appendLine("需要推敲的原文：")
        appendLine("<selection>${context.selection}</selection>")
        appendLine()
        appendLine("任务：")
        appendLine(task.trim())
        appendLine()
        appendLine("输出要求：")
        appendLine("- 只输出 $count 行，每行一个 JSON 对象，字段为 $fields；不要输出代码块、编号、前言或其他任何内容。")
        appendLine("- text 是用来替换 <selection> 的新文字，只包含选区本身，不要带上选区前后的文字；语言和原文一致。")
        appendLine("- $markupRule")
        extraRules.forEach { appendLine("- $it") }
        append("- 示例：").append(example)
    }

    private val PLACEHOLDER = Regex("""\{(count|explanation)\}""")
}
