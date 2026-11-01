package me.rerere.rikkihub.data.ai.tuiqiao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 发给模型的上下文：选区前的全部原文、选区、选区后最多几句。 */
data class TuiqiaoContext(val before: String, val selection: String, val after: String)

/** 一条候选改写。[why] 在解释关闭或模型没写时为空。 */
data class TuiqiaoCandidate(val text: String, val tone: String, val why: String = "")

/**
 * 推敲的纯函数部分（App 与 Web 共用）：档位判定、选区规整与扩展、上下文截取、
 * 流式输出解析、Markdown 标记校验。偏移都是源文件的 UTF-16 偏移。
 * 扩句/扩段按 [TuiqiaoFormat] 区分 Markdown 与纯文本。
 */
object Tuiqiao {
    const val MAX_SELECTION_CHARS = 3000
    const val AFTER_SENTENCES = 3
    const val AFTER_MAX_CHARS = 300

    private const val TERMINATORS = "。！？!?…"
    private const val CLOSERS = "”’」』）)\"'》】〉"

    // ---------- 选区 ----------

    /** 去掉选区两端的空白；全是空白时返回 null。 */
    fun normalizeRange(source: String, start: Int, end: Int): IntRange? {
        if (start < 0 || end > source.length || start >= end) return null
        var a = start
        var b = end
        while (a < b && source[a].isWhitespace()) a++
        while (b > a && source[b - 1].isWhitespace()) b--
        return if (a < b) a until b else null
    }

    fun classify(selection: String): TuiqiaoMode {
        val text = selection.trim()
        if (text.isEmpty()) return TuiqiaoMode.WORD
        if ('\n' in text) return TuiqiaoMode.PARAGRAPH
        if (sentenceEnds(text) >= 2) return TuiqiaoMode.PARAGRAPH
        val hasPunctuation = text.any { isPunctuation(it) }
        if (!hasPunctuation) {
            val hasCjk = text.any { isCjk(it) }
            if (hasCjk) {
                if (text.none { it.isWhitespace() } && text.codePointCount(0, text.length) <= 4) return TuiqiaoMode.WORD
            } else if (text.split(Regex("\\s+")).size <= 2) return TuiqiaoMode.WORD
        }
        return TuiqiaoMode.SENTENCE
    }

    /** 句子数：句末标点连成一串算一个；结尾没有句末标点的尾巴也算一句。 */
    internal fun sentenceEnds(text: String): Int {
        var count = 0
        var i = 0
        var tail = false
        while (i < text.length) {
            if (isTerminatorAt(text, i)) {
                while (i < text.length && (isTerminatorAt(text, i) || text[i] in CLOSERS)) i++
                count++
                tail = false
            } else {
                if (!text[i].isWhitespace()) tail = true
                i++
            }
        }
        return count + if (tail) 1 else 0
    }

    /**
     * 扩到整句：向前到上一个句末（或行首；Markdown 跳过行首的块标记），向后到本句句末（含收尾引号）。
     */
    fun expandToSentence(source: String, start: Int, end: Int, format: TuiqiaoFormat = TuiqiaoFormat.MARKDOWN): IntRange {
        var a = start
        while (a > 0) {
            val c = source[a - 1]
            if (c == '\n') break
            if (isTerminatorAt(source, a - 1)) break
            if (c in CLOSERS && a >= 2 && isTerminatorAt(source, a - 2)) break
            a--
        }
        if (format == TuiqiaoFormat.MARKDOWN) a = skipLinePrefix(source, a)
        var b = end
        if (!(b > start && endsSentence(source, b))) {
            while (b < source.length && source[b] != '\n' && !isTerminatorAt(source, b)) b++
            while (b < source.length && (isTerminatorAt(source, b) || source[b] in CLOSERS)) b++
        }
        return normalizeRange(source, minOf(a, start), maxOf(b, end)) ?: (start until end)
    }

    /**
     * 扩到整段。Markdown：相邻的正文行（空行、标题、围栏、表格行是边界）；纯文本：一行就是一段
     * （txt 小说常常一行一段、段间没有空行），选区跨行时取到首行行首、末行行尾。
     */
    fun expandToParagraph(source: String, start: Int, end: Int, format: TuiqiaoFormat = TuiqiaoFormat.MARKDOWN): IntRange {
        if (format == TuiqiaoFormat.PLAIN) {
            val a = if (start == 0) 0 else source.lastIndexOf('\n', start - 1) + 1
            val b = source.indexOf('\n', maxOf(end - 1, start)).let { if (it < 0) source.length else it }
            return normalizeRange(source, minOf(a, start), maxOf(b, end)) ?: (start until end)
        }
        var a = source.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0 || start == 0) 0 else it + 1 }
        if (isProseLine(lineAt(source, a))) {
            while (a > 0) {
                val prevStart = source.lastIndexOf('\n', a - 2).let { if (it < 0) 0 else it + 1 }
                if (!isProseLine(lineAt(source, prevStart))) break
                a = prevStart
            }
        }
        var b = source.indexOf('\n', end).let { if (it < 0) source.length else it }
        if (isProseLine(lineAt(source, a))) {
            while (b < source.length) {
                val nextStart = b + 1
                if (nextStart > source.length || !isProseLine(lineAt(source, nextStart))) break
                b = source.indexOf('\n', nextStart).let { if (it < 0) source.length else it }
            }
        }
        a = skipLinePrefix(source, a)
        return normalizeRange(source, minOf(a, start), maxOf(b, end)) ?: (start until end)
    }

    fun context(source: String, range: IntRange): TuiqiaoContext {
        val start = range.first
        val end = range.last + 1
        return TuiqiaoContext(source.substring(0, start), source.substring(start, end), afterContext(source, end))
    }

    /** 选区之后最多 [AFTER_SENTENCES] 句、[AFTER_MAX_CHARS] 字，遇到标题或围栏停。 */
    internal fun afterContext(source: String, end: Int): String {
        val limit = minOf(source.length, end + AFTER_MAX_CHARS)
        var i = end
        var sentences = 0
        while (i < limit) {
            if (source[i] == '\n') {
                val next = lineAt(source, i + 1).trimStart()
                if (next.startsWith("#") || next.startsWith("```") || next.startsWith("~~~")) break
            }
            if (isTerminatorAt(source, i)) {
                while (i < source.length && (isTerminatorAt(source, i) || source[i] in CLOSERS)) i++
                if (++sentences >= AFTER_SENTENCES) break
                continue
            }
            i++
        }
        return source.substring(end, minOf(i, source.length)).trimEnd()
    }

    // ---------- 输出解析 ----------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 从模型输出里依次取出完整的 JSON 对象（每行一个、数组、带代码围栏、跨行都行）。
     * [feed] 传入到目前为止的全部输出，返回新解析出的对象；未闭合的对象留到下一次。
     */
    class StreamParser {
        private var consumed = 0
        private var parsedAny = false

        fun feed(output: String): List<TuiqiaoCandidate> {
            if (output.length < consumed) consumed = 0
            val result = mutableListOf<TuiqiaoCandidate>()
            while (true) {
                val open = output.indexOf('{', consumed)
                if (open < 0) break
                val close = matchingBrace(output, open)
                if (close < 0) break
                consumed = close + 1
                parseObject(output.substring(open, close + 1))?.let {
                    parsedAny = true
                    result += it
                }
            }
            return result
        }

        /** 输出结束时调用：完全没有 JSON 时退回按行取（去掉编号与围栏）。 */
        fun finish(output: String): List<TuiqiaoCandidate> {
            val rest = feed(output)
            if (parsedAny) return rest
            return output.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("```") }
                .map { it.replace(LIST_PREFIX, "").trim() }
                .filter { it.isNotEmpty() }
                .map { TuiqiaoCandidate(text = it, tone = "") }
        }
    }

    private val LIST_PREFIX = Regex("""^(?:[-*•]\s+|\d+[.、)）]\s*)""")

    private fun parseObject(raw: String): TuiqiaoCandidate? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        fun field(vararg keys: String) = keys.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()
        val text = field("text")
        if (text.isEmpty()) return null
        return TuiqiaoCandidate(text = text, tone = field("tone"), why = field("why", "reason"))
    }

    /** [open] 处 `{` 对应的 `}`；字符串里的花括号和转义不算。未闭合返回 -1。 */
    internal fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    // ---------- 候选筛选 ----------

    /** Markdown 行内标记的指纹：种类、数量和链接地址都要和原文一致。 */
    internal fun markupSignature(text: String): List<Any> {
        val bold = count(text, "**")
        val stars = text.count { it == '*' } - bold * 2
        return listOf(bold, stars, count(text, "__"), count(text, "~~"), text.count { it == '`' },
            text.count { it == '[' }, count(text, "]("), LINK_URL.findAll(text).map { it.groupValues[1] }.toList())
    }

    private val LINK_URL = Regex("""\]\(([^)\s]*)""")

    fun keepsMarkup(original: String, candidate: String): Boolean = markupSignature(original) == markupSignature(candidate)

    /**
     * 角色候选的宽松校验：可以新增标记，但原文的标记一个不少、链接地址都在，
     * 新增的成对标记（`**`、`*`、`__`、`~~`、`` ` ``）必须成对。
     */
    fun keepsMarkupLeniently(original: String, candidate: String): Boolean {
        val before = markupSignature(original)
        val after = markupSignature(candidate)
        for (i in PAIRED_INDEXES) {
            val added = (after[i] as Int) - (before[i] as Int)
            if (added < 0 || added % 2 != 0) return false
        }
        if ((after[LINK_INDEX] as Int) < (before[LINK_INDEX] as Int)) return false
        val urls = (after[URL_INDEX] as List<*>).toMutableList()
        return (before[URL_INDEX] as List<*>).all { urls.remove(it) }
    }

    private val PAIRED_INDEXES = listOf(0, 1, 2, 3, 4)
    private const val LINK_INDEX = 6
    private const val URL_INDEX = 7

    /** 候选的标记校验方式：常规候选完全一致，角色候选可新增，纯文本不校验。 */
    enum class MarkupPolicy { STRICT, LENIENT, NONE }

    enum class Rejection { SAME_AS_ORIGINAL, DUPLICATE, MARKUP }

    /** 判断一条候选能否显示；[seen] 为已接受的候选文字（规整后）。 */
    fun reject(
        original: String,
        candidate: TuiqiaoCandidate,
        seen: Set<String>,
        markup: MarkupPolicy = MarkupPolicy.STRICT,
    ): Rejection? {
        val key = squash(candidate.text)
        return when {
            key == squash(original) -> Rejection.SAME_AS_ORIGINAL
            key in seen -> Rejection.DUPLICATE
            markup == MarkupPolicy.STRICT && !keepsMarkup(original, candidate.text) -> Rejection.MARKUP
            markup == MarkupPolicy.LENIENT && !keepsMarkupLeniently(original, candidate.text) -> Rejection.MARKUP
            else -> null
        }
    }

    fun squash(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    // ---------- 字符判定 ----------

    private fun count(text: String, token: String): Int {
        var n = 0
        var i = text.indexOf(token)
        while (i >= 0) {
            n++
            i = text.indexOf(token, i + token.length)
        }
        return n
    }

    private fun isTerminatorAt(text: String, i: Int): Boolean {
        val c = text[i]
        if (c in TERMINATORS) return true
        // 英文句点：后面是空白或结尾才算（排除 3.14、e.g 中间的点、URL）。
        return c == '.' && (i + 1 >= text.length || text[i + 1].isWhitespace()) && i > 0 && !text[i - 1].isDigit()
    }

    private fun endsSentence(text: String, end: Int): Boolean {
        var i = end - 1
        while (i >= 0 && text[i] in CLOSERS) i--
        return i >= 0 && isTerminatorAt(text, i)
    }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code).let {
        it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA ||
            it == Character.UnicodeScript.KATAKANA || it == Character.UnicodeScript.HANGUL
    }

    private fun lineAt(source: String, lineStart: Int): String {
        if (lineStart >= source.length) return ""
        val e = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
        return source.substring(lineStart, e)
    }

    private val BLOCK_LINE = Regex("""^\s*(?:#{1,6}(?:\s|$)|```|~~~|\|)""")
    private val LINE_PREFIX = Regex("""^[ \t]*(?:#{1,6}[ \t]+)?(?:>[ \t]?)*(?:[-*+][ \t]+(?:\[[ xX]][ \t]+)?|\d+[.)][ \t]+)?""")

    private fun isProseLine(line: String): Boolean = line.isNotBlank() && !BLOCK_LINE.containsMatchIn(line)

    /** 行首的引用、列表、任务标记不算正文。[at] 不在行首时原样返回。 */
    private fun skipLinePrefix(source: String, at: Int): Int {
        if (at != 0 && source[at - 1] != '\n') return at
        val match = LINE_PREFIX.find(lineAt(source, at)) ?: return at
        return at + match.value.length
    }
}
