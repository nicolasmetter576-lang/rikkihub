package me.rerere.rikkihub.data.ai.qubagu

import me.rerere.rikkihub.data.model.splitRegexLiteral
import me.rerere.tavern.core.JsMatch
import me.rerere.tavern.core.JsRegex
import me.rerere.tavern.core.JsRegexFactory
import me.rerere.tavern.core.JsRegexSyntaxException
import me.rerere.tavern.core.JsRegexTimeoutException
import kotlin.random.Random

/**
 * AI 精修要重写的一句：[start]..[end] 是它在原文里的范围，[sentence] 是给模型看的句子，[phrases] 是句中被标出的套话，
 * [rules] 是标出它们的规则 id（见 [QubaguRules.key]）。
 */
data class QubaguRefineTarget(val start: Int, val end: Int, val sentence: String, val phrases: List<String>, val rules: List<String>)

/**
 * 文件里的一处八股：[start]..[end] 是它所在的整句（同一句里的几处命中合成一条），[original] 是这句原文，
 * [phrases] 是句中被标出的套话，[rules] 是标出它们的规则 id，[suggestions] 是本地规则拼出的改法（最多三条，互不相同，不含原句）。
 */
data class QubaguAnnotation(
    val start: Int,
    val end: Int,
    val original: String,
    val phrases: List<String>,
    val rules: List<String>,
    val suggestions: List<String>,
)

/**
 * 去八股的本地引擎：在一段正文里找出套话，给出改动记录（不直接改正文，见 [QubaguRecord]）。
 *
 * 每条规则都在**原文**上各自找匹配，重叠时先到先得，所以规则之间不会连锁改写；改动的位置都是原文坐标。
 * 正文先按字符分成可改、台词、受保护三种（[zones]），匹配范围里有一个字符不允许，整条匹配作废。
 * 正则用 JS 引擎执行（和酒馆正则同一个），JVM 与真机行为一致。
 * 同一份原文、同样的规则和 [clean] 的 `recent` 永远得到同一份记录：重新检查不会改写已经发出去的历史，上下文缓存前缀不断。
 */
class Qubagu(private val regex: JsRegexFactory) {

    /**
     * [recent] 是本会话最近几条回复的文字：挑替换词时避开里面已经出现过的，免得替换词自己变成新的套话。
     */
    suspend fun clean(text: String, config: QubaguConfig, recent: String = ""): QubaguRecord {
        if (text.isBlank()) return QubaguRecord()
        val pass = run(text, config, recent, alternatives = false)
        val applied = config.mode == QubaguMode.AUTO
        return QubaguRecord(edits = pass.edits.sortedBy { it.start }.map { it.copy(applied = applied) })
    }

    /**
     * 工作区文件的八股标注：每处命中扩到整句（不越过台词与正文的边界），同一句里的命中合成一条，按位置排序。
     * 建议改法由每处命中的不同候选拼成；碎句合并这种跨行的改动自成一条，范围就是那几行。
     */
    suspend fun annotate(text: String, config: QubaguConfig): List<QubaguAnnotation> {
        if (text.isBlank()) return emptyList()
        val pass = run(text, config, recent = "", alternatives = true)
        val groups = mutableListOf<Pair<IntRange, MutableList<QubaguEdit>>>()
        for (edit in pass.edits.sortedBy { it.start }) {
            val span = sentenceSpan(text, pass.zones, edit.start, edit.end)
            val last = groups.lastOrNull()
            if (last != null && span.first <= last.first.last) {
                groups[groups.lastIndex] = (last.first.first..maxOf(last.first.last, span.last)) to last.second.apply { add(edit) }
            } else {
                groups += span to mutableListOf(edit)
            }
        }
        return groups.map { (span, edits) ->
            val start = span.first
            val original = text.substring(start, span.last + 1)
            val suggestions = (0 until MAX_SUGGESTIONS).map { k ->
                QubaguRecord(edits = edits.map { edit ->
                    val options = pass.alternatives[edit.start] ?: listOf(edit.after)
                    edit.copy(start = edit.start - start, after = options[k % options.size], applied = true)
                }).render(original)
            }.distinct().filter { it != original }
            QubaguAnnotation(
                start = start,
                end = span.last + 1,
                original = original,
                phrases = edits.map { it.before.trim() }.filter { it.isNotEmpty() }.distinct(),
                rules = edits.map { it.rule }.distinct(),
                suggestions = suggestions,
            )
        }
    }

    private suspend fun run(text: String, config: QubaguConfig, recent: String, alternatives: Boolean): Pass {
        val pass = Pass(text, zones(text, config), recent, alternatives)
        for ((key, rule) in config.activeRules) when (rule) {
            is QubaguRule.Replace -> replace(pass, key, rule)
            is QubaguRule.Quota -> quota(pass, key, rule)
        }
        if (config.merge) merge(pass, config)
        return pass
    }

    /**
     * [start]..[end] 所在的整句：向两边扩到句末标点或换行为止，不越过和 [start] 处不同的区域（台词与旁白各算各的），
     * 句首空白和台词的开引号不算，句末标点算进来。
     */
    private fun sentenceSpan(text: String, zones: ByteArray, start: Int, end: Int): IntRange {
        val zone = zones[start]
        var from = start
        while (from > 0 && zones[from - 1] == zone && text[from - 1] !in SENTENCE_BREAKS) from--
        while (from < start && (text[from].isWhitespace() || text[from] in OPENING_QUOTES)) from++
        var to = end
        while (to < text.length && zones[to] == zone && text[to] !in SENTENCE_BREAKS) to++
        while (to < text.length && zones[to] == zone && text[to] in SENTENCE_ENDS) to++
        return from until to
    }

    /** 规则的查找串能不能用；不能用时返回引擎的报错。 */
    suspend fun check(rule: QubaguRule): String? {
        if (rule !is QubaguRule.Replace || !rule.regex) return null
        val (source, flags) = splitRegexLiteral(rule.find) ?: (rule.find to "")
        return try {
            regex.compile(source, flags)
            null
        } catch (e: JsRegexSyntaxException) {
            e.message ?: "invalid"
        }
    }

    /**
     * AI 精修要重写哪些句子：含词汇命中或绕弯否定的句子，扩到整句（不越过台词和受保护内容）。
     * 句子太长、或和碎句合并 / 结尾清理的改动搅在一起的，不交给模型，保留本地结果。
     */
    suspend fun refineTargets(original: String, record: QubaguRecord, config: QubaguConfig): List<QubaguRefineTarget> {
        val zones = zones(original, config)
        val spans = mutableListOf<IntRange>()
        for (edit in record.edits) {
            if (!edit.refinable(config) || edit.end > original.length || (edit.start until edit.end).any { zones[it] != TEXT }) continue
            val span = sentenceSpan(original, zones, edit.start, edit.end)
            val last = spans.lastOrNull()
            if (last != null && span.first <= last.last) spans[spans.lastIndex] = last.first..maxOf(last.last, span.last)
            else spans += span
        }
        return spans.mapNotNull { span ->
            val start = span.first
            val end = span.last + 1
            if (end - start > MAX_SENTENCE_CHARS) return@mapNotNull null
            val touching = record.edits.filter { it.start < end && it.end > start }
            if (touching.any { it.start < start || it.end > end || it.rule == QubaguRules.MERGE || config.endsText(it.rule) }) {
                return@mapNotNull null
            }
            // 给模型看的句子：标点、水词这些不需要重写的改动先套上，要重写的套话保持原样
            val (refinable, settled) = touching.partition { it.refinable(config) }
            QubaguRefineTarget(
                start = start,
                end = end,
                sentence = QubaguRecord(edits = settled.map { it.copy(start = it.start - start, applied = true) })
                    .render(original.substring(start, end)),
                phrases = refinable.map { it.before.trim() }.distinct(),
                rules = refinable.map { it.rule }.distinct(),
            )
        }
    }

    /** 值得交给模型重写的改动：查找替换规则换掉的文字（标点、限量、碎句合并、结尾套话这些不算）。 */
    private fun QubaguEdit.refinable(config: QubaguConfig): Boolean =
        rule != QubaguRules.AI && rule != QubaguRules.MERGE && config.rule(rule) !is QubaguRule.Quota && !config.endsText(rule) &&
            before.any { it.isLetter() }

    /** 每个字符属于哪种区域。先到先得：前面的模式占了的字符，后面的模式不再改。 */
    internal suspend fun zones(text: String, config: QubaguConfig): ByteArray {
        val zones = ByteArray(text.length)
        suspend fun claim(pattern: String, flags: String, zone: Byte) {
            for (match in regex.compile(pattern, flags).matchAll(text)) {
                val range = match.index until match.index + match.text.length
                if (range.isEmpty() || range.any { zones[it] != TEXT }) continue
                for (i in range) zones[i] = zone
            }
        }
        if (config.protectDetails) claim("<details\\b[\\s\\S]*?</details>", "gi", PROTECTED)
        claim("```[\\s\\S]*?(?:```|$)|`[^`\\n]+`", "g", PROTECTED)
        claim("<(pre|code|style|script)\\b[\\s\\S]*?</\\1>", "gi", PROTECTED)
        claim("\\]\\([^)\\n]*\\)", "g", PROTECTED)
        if (config.protectCornerQuotes) claim("『[^』]*』", "g", PROTECTED)
        claim("</?[A-Za-z][^>\\n]*>", "g", PROTECTED)
        if (config.protectDialogue) claim("“[^”\\n]*”|「[^」\\n]*」|\"[^\"\\n]*\"", "g", DIALOGUE)
        return zones
    }

    private suspend fun compile(source: String, flags: String): JsRegex? = try {
        regex.compile(source, flags)
    } catch (e: JsRegexSyntaxException) {
        null
    }

    private suspend fun JsRegex.matchesIn(text: String): List<JsMatch> = try {
        matchAll(text)
    } catch (e: JsRegexTimeoutException) {
        emptyList()
    }

    private suspend fun replace(pass: Pass, key: String, rule: QubaguRule.Replace) {
        if (rule.find.isEmpty()) return
        val (source, flags) = if (rule.regex) splitRegexLiteral(rule.find) ?: (rule.find to "") else escape(rule.find) to ""
        val compiled = compile(source, if ('g' in flags) flags else flags + "g") ?: return
        val scope = if (!rule.atEnd) pass.text else {
            val end = pass.text.indices.lastOrNull { pass.zones[it] == TEXT && !pass.text[it].isWhitespace() } ?: return
            pass.text.substring(0, end + 1)
        }
        var seen = 0
        for (match in compiled.matchesIn(scope)) {
            val end = match.index + match.text.length
            if (rule.atEnd && scope.substring(end).isNotBlank()) continue
            if (!pass.allows(match.index, end, rule.dialogue)) continue
            if (seen++ == 0 && rule.keepFirst) continue
            fun expand(template: String) = GROUP_REFERENCE.replace(template) { match.groups.getOrNull(it.groupValues[1].toInt() - 1).orEmpty() }
            val template = if (rule.replacements.isEmpty()) "" else pass.pick(rule.replacements, key, match.index)
            val after = expand(template)
            if (pass.propose(match.index, end, after, key)) {
                pass.used += template
                if (pass.collectAlternatives) pass.alternatives[match.index] = (listOf(after) + rule.replacements.map(::expand)).distinct()
            }
        }
    }

    /** 每个词在一段正文里留前 N 次，之后的删掉（连同后面的「地」）。只算旁白，不算台词。 */
    private suspend fun quota(pass: Pass, key: String, rule: QubaguRule.Quota) {
        val words = rule.words.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        if (words.isEmpty()) return
        val max = rule.max.coerceIn(QubaguRule.Quota.MAX_RANGE)
        val compiled = regex.compile("(?:${words.joinToString("|") { escape(it) }})地?", "g")
        val counts = mutableMapOf<String, Int>()
        for (match in compiled.matchesIn(pass.text)) {
            val end = match.index + match.text.length
            if (!pass.allows(match.index, end, dialogue = false)) continue
            val word = words.first { match.text.startsWith(it) }
            val count = (counts[word] ?: 0) + 1
            counts[word] = count
            if (count > max) pass.propose(match.index, end, "", key)
        }
    }

    /** 连续几行都只有一个极短的句子时连成一句；这几行里已有的其他改动并进同一条记录。 */
    private fun merge(pass: Pass, config: QubaguConfig) {
        val text = pass.text
        val maxChars = config.effectiveMergeMaxChars
        val lines = buildList {
            var start = 0
            while (start <= text.length) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                add(start until end)
                start = end + 1
            }
        }
        // 每行去掉首尾空白后的范围；不是「一个极短句」的行为 null
        val short = lines.map { line ->
            val from = line.firstOrNull { !text[it].isWhitespace() } ?: return@map null
            val to = line.last { !text[it].isWhitespace() } + 1
            val body = to - from - 1
            if (text[to - 1] != '。' || body !in 1..maxChars || text[from] in LINE_MARKERS || text[from].isDigit()) return@map null
            if ((from until to - 1).any { text[it] in SENTENCE_ENDS } || (from until to).any { pass.zones[it] != TEXT }) return@map null
            if (pass.edits.any { it.start < to && it.end > from && config.endsText(it.rule) }) return@map null
            from until to
        }
        var index = 0
        while (index < lines.size) {
            val first = short[index]
            if (first == null) {
                index++
                continue
            }
            val run = mutableListOf(first)
            var next = index + 1
            while (next < lines.size) {
                // 中间隔一个空行也算连续
                val candidate = short[next] ?: short.getOrNull(next + 1)?.takeIf { lines[next].all { text[it].isWhitespace() } }?.also { next++ }
                run += candidate ?: break
                next++
            }
            index = next
            if (run.size < 2) continue
            var joined = false
            for ((upper, lower) in run.zipWithNext()) {
                if (pass.propose(upper.last, lower.first, "，", QubaguRules.MERGE)) joined = true
            }
            if (joined) pass.coalesce(run.first().first, run.last().last + 1, QubaguRules.MERGE)
        }
    }

    /** [collectAlternatives]：给每处替换记下它全部的候选改法（[alternatives]，按原文起点，挑中的排第一），给文件标注拼建议用。 */
    private class Pass(val text: String, val zones: ByteArray, private val recent: String, val collectAlternatives: Boolean) {
        val edits = mutableListOf<QubaguEdit>()
        val alternatives = mutableMapOf<Int, List<String>>()
        private val taken = BooleanArray(text.length)
        val used = mutableSetOf<String>()

        fun allows(start: Int, end: Int, dialogue: Boolean): Boolean =
            (start until end).all { zones[it] == TEXT || (dialogue && zones[it] == DIALOGUE) }

        /** 记下一处改动；范围为空、没有变化、或和已有改动重叠时不记，返回 false。 */
        fun propose(start: Int, end: Int, after: String, rule: String): Boolean {
            if (end <= start || (start until end).any { taken[it] }) return false
            val before = text.substring(start, end)
            if (before == after) return false
            for (i in start until end) taken[i] = true
            edits += QubaguEdit(start, before, after, rule)
            return true
        }

        /** 把 [start]..[end] 里的改动并成一条。 */
        fun coalesce(start: Int, end: Int, rule: String) {
            val inside = edits.filter { it.start >= start && it.end <= end }.sortedBy { it.start }
            edits.removeAll(inside)
            for (edit in inside) alternatives.remove(edit.start)
            val after = QubaguRecord(edits = inside.map { it.copy(start = it.start - start) }).render(text.substring(start, end))
            for (i in start until end) taken[i] = true
            edits += QubaguEdit(start, text.substring(start, end), after, rule)
        }

        /**
         * 挑一个替换词：先挑最近的回复和这条正文里都没出现过、这次也没用过的；都出现过就退而避开这次用过的。
         * 候选里的选择由（原文、规则、匹配位置）定种子，输入相同结果相同。
         */
        fun pick(options: List<String>, rule: String, start: Int): String {
            val unused = options.filter { it !in used }
            // 带 $1 的候选拿去掉引用后的字面部分比
            val fresh = unused.filter { option ->
                val literal = GROUP_REFERENCE.replace(option, "")
                literal.isEmpty() || (literal !in recent && literal !in text)
            }
            return fresh.ifEmpty { unused }.ifEmpty { options }.random(Random(listOf(text, rule, start).hashCode()))
        }
    }

    companion object {
        internal const val TEXT: Byte = 0
        internal const val DIALOGUE: Byte = 1
        internal const val PROTECTED: Byte = 2

        const val MAX_SENTENCE_CHARS = 300
        const val MAX_SUGGESTIONS = 3

        private const val SENTENCE_ENDS = "。！？!?…"
        private const val SENTENCE_BREAKS = "$SENTENCE_ENDS\n"
        private const val LINE_MARKERS = "#>-*|+=~"
        private const val OPENING_QUOTES = "“「『\""
        private val GROUP_REFERENCE = Regex("\\$([1-9])")
        private val REGEX_SPECIALS = Regex("[.*+?^\${}()|\\[\\]\\\\/]")

        fun escape(literal: String): String = REGEX_SPECIALS.replace(literal) { "\\" + it.value }
    }
}
