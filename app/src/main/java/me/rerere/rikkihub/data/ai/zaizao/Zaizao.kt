package me.rerere.rikkihub.data.ai.zaizao

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat

/** 发给模型的文档：选区前全部、选区、选区后全部（[includeAfter] 关着时为空）。 */
data class ZaizaoContext(val before: String, val selection: String, val after: String, val includeAfter: Boolean)

/** 一轮再造的类型和作者的要求。 */
data class ZaizaoRound(val type: ZaizaoType, val requirement: String)

/** 从原文到底稿那条线上已完成的一轮：要求和模型的完整回复（含 `<plan>`，便于后续轮避开用过的写法）。 */
data class ZaizaoTurn(val round: ZaizaoRound, val output: String)

/**
 * 一次再造请求。[source]/[start]/[end] 是会话开始时的全文与选区（UTF-16 偏移）；[history] 是从原文到底稿的各轮，
 * [round] 是本轮；[avoid] 是换一版时这一轮已经给过的稿（新稿要和它们不同）。
 */
data class ZaizaoRequest(
    val fileName: String,
    val source: String,
    val start: Int,
    val end: Int,
    val includeAfter: Boolean,
    val history: List<ZaizaoTurn>,
    val round: ZaizaoRound,
    val avoid: List<String> = emptyList(),
)

/** 规整后的请求：实际替换的区间（两端空白已去掉）、格式档和发给模型的消息（最后一条是本轮的 user 消息）。 */
data class ZaizaoPrepared(
    val start: Int,
    val end: Int,
    val original: String,
    val format: TuiqiaoFormat,
    val messages: List<UIMessage>,
)

/** 模型输出拆出的两段：重写的构思（[plan]）和用来替换选区的结果（[result]）。 */
data class ZaizaoOutput(val plan: String, val result: String)

class ZaizaoException(val reason: Reason, message: String? = null) : RuntimeException(message ?: reason.name) {
    enum class Reason {
        EMPTY_SELECTION, SELECTION_TOO_LONG, EMPTY_REQUIREMENT, REQUIREMENT_TOO_LONG, TOO_MANY_ROUNDS, NO_MODEL, EMPTY_RESULT,
    }
}

/**
 * 再造的纯函数部分（App 与 Web 共用）：选区规整与校验、上下文、多轮消息拼装、输出解析。
 * 选区规整、扩句扩段、格式档复用推敲的 [Tuiqiao] / [TuiqiaoFormat]。
 */
object Zaizao {
    const val MAX_SELECTION_CHARS = 5000
    const val MAX_ROUNDS = 20
    const val MAX_AVOID = 10

    /** 去掉两端空白后的选区；空或超过 [MAX_SELECTION_CHARS] 时抛 [ZaizaoException]。 */
    fun range(source: String, start: Int, end: Int): IntRange {
        val range = Tuiqiao.normalizeRange(source, start, end) ?: throw ZaizaoException(ZaizaoException.Reason.EMPTY_SELECTION)
        if (range.last + 1 - range.first > MAX_SELECTION_CHARS) throw ZaizaoException(ZaizaoException.Reason.SELECTION_TOO_LONG)
        return range
    }

    fun context(source: String, range: IntRange, includeAfter: Boolean): ZaizaoContext {
        val end = range.last + 1
        return ZaizaoContext(
            before = source.substring(0, range.first),
            selection = source.substring(range.first, end),
            after = if (includeAfter) source.substring(end) else "",
            includeAfter = includeAfter,
        )
    }

    /** 要求不能为空、不能超过 [ZaizaoConfig.MAX_REQUIREMENT_CHARS]。 */
    fun checkRequirement(requirement: String) {
        if (requirement.isBlank()) throw ZaizaoException(ZaizaoException.Reason.EMPTY_REQUIREMENT)
        if (requirement.trim().length > ZaizaoConfig.MAX_REQUIREMENT_CHARS) {
            throw ZaizaoException(ZaizaoException.Reason.REQUIREMENT_TOO_LONG)
        }
    }

    /**
     * 拼出多轮消息：第一条 user 是文档 + 第一轮，之后每轮是「上一稿的完整回复（assistant）+ 本轮（user）」。
     * [ZaizaoRequest.avoid] 附在最后一条 user 消息里。
     */
    fun prepare(config: ZaizaoConfig, request: ZaizaoRequest): ZaizaoPrepared {
        val range = range(request.source, request.start, request.end)
        val rounds = request.history.map { it.round } + request.round
        if (rounds.size > MAX_ROUNDS) throw ZaizaoException(ZaizaoException.Reason.TOO_MANY_ROUNDS)
        rounds.forEach { checkRequirement(it.requirement) }
        val avoid = request.avoid.filter { it.isNotBlank() }.takeLast(MAX_AVOID)
        val format = TuiqiaoFormat.of(request.fileName)
        val context = context(request.source, range, request.includeAfter)
        val messages = buildList {
            add(UIMessage.user(ZaizaoPrompts.first(config, request.fileName, context, format, rounds[0],
                if (rounds.size == 1) avoid else emptyList())))
            request.history.forEachIndexed { index, turn ->
                add(UIMessage.assistant(turn.output))
                val last = index + 1 == rounds.lastIndex
                add(UIMessage.user(ZaizaoPrompts.followUp(config, format, rounds[index + 1], if (last) avoid else emptyList())))
            }
        }
        return ZaizaoPrepared(range.first, range.last + 1, context.selection, format, messages)
    }

    // ---------- 输出解析 ----------

    private const val PLAN_OPEN = "<plan>"
    private const val PLAN_CLOSE = "</plan>"
    private const val RESULT_OPEN = "<result>"
    private const val RESULT_CLOSE = "</result>"

    /**
     * 从到目前为止的输出里取出 plan 和 result。有 `<result>` 取标签内（未闭合取到末尾，去掉写了一半的闭合标签）；
     * 没有时：生成中、已经在写 `<plan>` 或刚写出半个标签就先不显示，结束时取 `<plan>` 块之外的全部。代码围栏剥掉。
     */
    fun parse(output: String, finished: Boolean): ZaizaoOutput {
        val plan = section(output, PLAN_OPEN, PLAN_CLOSE).orEmpty()
        val result = when {
            RESULT_OPEN in output -> section(output, RESULT_OPEN, RESULT_CLOSE).orEmpty()
            finished -> withoutPlan(output)
            PLAN_OPEN in output -> ""
            isTagPrefix(output.trimStart()) -> ""
            else -> dropPartialTag(output, RESULT_CLOSE)
        }
        return ZaizaoOutput(plan.trim(), clean(result, finished))
    }

    /** [open] 之后到 [close]（未闭合时到末尾，去掉写了一半的 [close]）；没有 [open] 返回 null。 */
    private fun section(output: String, open: String, close: String): String? {
        val start = output.indexOf(open).takeIf { it >= 0 }?.plus(open.length) ?: return null
        val end = output.indexOf(close, start)
        return if (end >= 0) output.substring(start, end) else dropPartialTag(output.substring(start), close)
    }

    private fun withoutPlan(output: String): String {
        val open = output.indexOf(PLAN_OPEN)
        if (open < 0) return output
        val close = output.indexOf(PLAN_CLOSE, open)
        return if (close < 0) output.substring(0, open) else output.substring(0, open) + output.substring(close + PLAN_CLOSE.length)
    }

    /** 末尾若是 [tag] 写了一半（如 `</res`）就去掉。 */
    private fun dropPartialTag(text: String, tag: String): String {
        val lt = text.lastIndexOf('<')
        if (lt < 0) return text
        val tail = text.substring(lt)
        return if (tail.length < tag.length && tag.startsWith(tail)) text.substring(0, lt) else text
    }

    private fun isTagPrefix(text: String): Boolean =
        text.isNotEmpty() && (RESULT_OPEN.startsWith(text) || PLAN_OPEN.startsWith(text))

    private val FENCE_OPEN = Regex("""^```[^\n`]*\n""")
    private val FENCE_CLOSE = Regex("""\n?```\s*$""")

    /** 去两端空白；剥掉包住整段的代码围栏（生成中只剥开头那一行）。 */
    private fun clean(text: String, finished: Boolean): String {
        var body = text.trim()
        val fenced = FENCE_OPEN.find(body)
        if (fenced != null) {
            body = body.substring(fenced.range.last + 1)
            body = FENCE_CLOSE.replace(body, "")
        } else if (!finished && body.startsWith("```")) {
            return ""
        }
        return body.trim()
    }
}
