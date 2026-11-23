package me.rerere.rikkihub.data.book

data class ReplacePair(val find: String, val replace: String)

/** 一处命中：[start] 是全文里的 UTF-16 偏移，[pair] 是命中的是第几组。 */
data class ReplaceHit(val start: Int, val length: Int, val pair: Int, val line: Int, val before: String, val match: String, val after: String)

/** 全书查找替换的纯文本部分：字面匹配，不跨行。 */
internal object BookReplace {
    const val MAX_PAIRS = 20
    private const val CONTEXT = 20

    /**
     * 所有组一遍扫完：从左到右，同一位置取最长的一组；命中过的地方不再参与匹配，替换结果也不会再被匹配，
     * 所以 A、B 互换不会乱。
     */
    fun find(text: String, pairs: List<ReplacePair>, ignoreCase: Boolean): List<ReplaceHit> {
        val byFirst = HashMap<Char, MutableList<Int>>()
        pairs.forEachIndexed { index, pair ->
            require(pair.find.isNotEmpty() && '\n' !in pair.find) { "Nothing to find" }
            firstChars(pair.find[0], ignoreCase).forEach { byFirst.getOrPut(it) { ArrayList() }.add(index) }
        }
        val hits = ArrayList<ReplaceHit>()
        var line = 1
        var lineStart = 0
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c == '\n') {
                line++
                lineStart = index + 1
            }
            val best = byFirst[c]
                ?.filter { text.startsWith(pairs[it].find, index, ignoreCase) && !splitsSurrogate(text, index, pairs[it].find.length) }
                ?.maxByOrNull { pairs[it].find.length }
            if (best == null) {
                index++
                continue
            }
            val length = pairs[best].find.length
            hits += ReplaceHit(
                start = index,
                length = length,
                pair = best,
                line = line,
                before = before(text, lineStart, index),
                match = text.substring(index, index + length),
                after = after(text, index + length),
            )
            index += length
        }
        return hits
    }

    /** 把选中的命中（起点集合；null = 全部）换掉。 */
    fun replace(text: String, pairs: List<ReplacePair>, ignoreCase: Boolean, selected: Set<Int>?): Pair<String, Int> {
        val hits = find(text, pairs, ignoreCase).filter { selected == null || it.start in selected }
        val out = StringBuilder(text.length)
        var at = 0
        hits.forEach { hit ->
            out.append(text, at, hit.start).append(pairs[hit.pair].replace)
            at = hit.start + hit.length
        }
        return out.append(text, at, text.length).toString() to hits.size
    }

    private fun firstChars(c: Char, ignoreCase: Boolean): Set<Char> =
        if (ignoreCase) setOf(c, c.lowercaseChar(), c.uppercaseChar(), c.uppercaseChar().lowercaseChar()) else setOf(c)

    /** 命中的头尾不能落在一个代理对中间（只找半个表情）。 */
    private fun splitsSurrogate(text: String, start: Int, length: Int): Boolean =
        (text[start].isLowSurrogate() && start > 0 && text[start - 1].isHighSurrogate()) ||
            (start + length < text.length && text[start + length].isLowSurrogate() && text[start + length - 1].isHighSurrogate())

    private fun before(text: String, lineStart: Int, start: Int): String {
        var from = maxOf(lineStart, start - CONTEXT)
        if (from > lineStart && from < start && text[from].isLowSurrogate()) from++
        return (if (from > lineStart) "…" else "") + text.substring(from, start)
    }

    private fun after(text: String, end: Int): String {
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        var to = minOf(lineEnd, end + CONTEXT)
        if (to < lineEnd && to > end && text[to].isLowSurrogate()) to--
        return text.substring(end, to).trimEnd('\r') + if (to < lineEnd) "…" else ""
    }
}
