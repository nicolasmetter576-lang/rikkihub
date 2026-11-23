package me.rerere.rikkihub.data.book.transfer

import me.rerere.rikkihub.data.book.ChapterNumbers

/** 拆章方式；[Auto] 认「第 N 章」这类标题，其余是认不出来时的手动选择。 */
sealed interface SplitRule {
    data object Auto : SplitRule

    /** Markdown 标题，[level] 级及以上。 */
    data class Headings(val level: Int) : SplitRule

    /** 连续空行不少于 [count] 行处断开。 */
    data class BlankLines(val count: Int) : SplitRule

    /** 每 [chars] 字左右、在段落边界断开。 */
    data class FixedSize(val chars: Int) : SplitRule

    /** 以 [prefix] 开头的行是标题。 */
    data class LinePrefix(val prefix: String) : SplitRule
}

/**
 * 拆出来的一段：[start] 到 [end] 是全文里的 UTF-16 区间（含标题行）。[volume] 不为 null = 从这一章起进入这一卷。
 * [number] 是标题里的章号，没有则为 null。
 */
data class SplitPart(
    val title: String,
    val start: Int,
    val end: Int,
    val number: Int? = null,
    val volume: String? = null,
)

/** 把一本 TXT 拆成章（纯函数）。 */
object ChapterSplitter {
    private const val NUM = "[0-9０-９零〇○◯一二三四五六七八九十百千万两廿卅]+"
    private const val SEP = "[\\s　:：、.．·—\\-_]"
    private const val MAX_TITLE = 40
    private val CHAPTER = Regex("^(?:正文$SEP*)?第\\s*($NUM)\\s*([章节節回折幕话話])(.*)$")
    private val VOLUME = Regex("^(?:第\\s*$NUM\\s*[卷部集篇]|卷\\s*$NUM)(?:$SEP.*)?$")
    private val VOLUME_CHAPTER = Regex("^((?:第\\s*$NUM\\s*[卷部集篇]|卷\\s*$NUM)(?:$SEP.*?)?)$SEP*(第\\s*$NUM\\s*[章节節回折幕话話].*)$")
    private val ENGLISH = Regex("^(?:chapter|ch\\.?)\\s*(\\d+)(.*)$", RegexOption.IGNORE_CASE)
    private val SPECIAL = Regex("^(?:序章|序幕|序言|楔子|引子|前言|尾声|尾聲|终章|終章|后记|後記|番外(?:篇|\\s*$NUM)?|prologue|epilogue)(?:$SEP.*)?$", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = setOf('。', '，', '；', '…', ',', ';')
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")

    private class Line(val start: Int, val end: Int, val text: String)

    private fun lines(text: String): List<Line> = buildList {
        var at = 0
        while (at <= text.length) {
            val newline = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            add(Line(at, newline, text.substring(at, newline).trim { it.isWhitespace() || it == '　' || it == '﻿' }))
            at = newline + 1
        }
    }

    fun split(text: String, rule: SplitRule): List<SplitPart> {
        val breaks = when (rule) {
            SplitRule.Auto -> auto(lines(text))
            is SplitRule.Headings -> lines(text).mapNotNull { line ->
                HEADING.matchEntire(line.text)?.takeIf { it.groupValues[1].length <= rule.level }
                    ?.let { Break(line.start, it.groupValues[2], null, null) }
            }
            is SplitRule.BlankLines -> blankLines(lines(text), rule.count)
            is SplitRule.FixedSize -> fixedSize(text, lines(text), rule.chars)
            is SplitRule.LinePrefix -> lines(text).filter { rule.prefix.isNotBlank() && it.text.startsWith(rule.prefix.trim()) }
                .map { Break(it.start, it.text.take(MAX_TITLE), null, null) }
        }
        return dropContents(text, assemble(text, breaks))
    }

    private data class Break(val start: Int, val title: String, val number: Int?, val volume: String?)

    private fun assemble(text: String, breaks: List<Break>): List<SplitPart> {
        val parts = ArrayList<SplitPart>()
        val first = breaks.firstOrNull()?.start ?: text.length
        // 第一个标题之前的正文（书名、简介）单独成一段
        if (text.substring(0, first).isNotBlank()) parts += SplitPart("", 0, first)
        breaks.forEachIndexed { index, item ->
            val end = breaks.getOrNull(index + 1)?.start ?: text.length
            parts += SplitPart(item.title, item.start, end, item.number, item.volume)
        }
        return parts
    }

    /**
     * 文件开头抄了一遍目录：只有标题没有正文、而同名标题后面又出现的那一串并进开头那段；
     * 同一个标题连写两遍的并成一章。文字都还在，只是不单独成章。
     */
    private fun dropContents(text: String, parts: List<SplitPart>): List<SplitPart> {
        fun empty(part: SplitPart) = text.substring(part.start, part.end).lineSequence().count { it.isNotBlank() } <= 1
        val from = parts.indexOfFirst { it.title.isNotEmpty() }
        if (from < 0) return parts
        val until = (from until parts.size).firstOrNull { !empty(parts[it]) } ?: parts.size
        val later = parts.drop(until).mapTo(HashSet()) { it.title }
        val repeated = (from until until).count { parts[it].title in later }
        val kept = if (until - from >= 3 && repeated * 2 >= until - from) {
            listOf(SplitPart("", parts.first().start, parts.getOrNull(until)?.start ?: text.length)) + parts.drop(until)
        } else parts
        val result = ArrayList<SplitPart>()
        for (part in kept) {
            val previous = result.lastOrNull()
            if (previous != null && previous.title.isNotEmpty() && previous.title == part.title && empty(previous)) {
                result[result.lastIndex] = previous.copy(end = part.end)
            } else result += part
        }
        return result
    }

    private class Candidate(val line: Line, val title: String, val unit: String, val number: Int?, val strong: Boolean, val volume: String?)

    private fun candidate(line: Line): Candidate? {
        val text = line.text
        if (text.isEmpty() || text.length > MAX_TITLE || text.last() in SENTENCE_END) return null
        VOLUME_CHAPTER.matchEntire(text)?.let { match ->
            val inner = CHAPTER.matchEntire(match.groupValues[2]) ?: return@let
            val (number, unit, rest) = inner.destructured
            return Candidate(line, match.groupValues[2], unit, ChapterNumbers.parse(number), isSeparated(rest), match.groupValues[1].trim())
        }
        CHAPTER.matchEntire(text)?.let { match ->
            val (number, unit, rest) = match.destructured
            return Candidate(line, text, unit, ChapterNumbers.parse(number), isSeparated(rest), null)
        }
        ENGLISH.matchEntire(text)?.let { match ->
            val (number, rest) = match.destructured
            return Candidate(line, text, "chapter", number.toIntOrNull(), isSeparated(rest), null)
        }
        return null
    }

    private fun isSeparated(rest: String): Boolean =
        rest.isEmpty() || rest.first().isWhitespace() || rest.first() in "　:：、.．·—-_（(【《「"

    private fun auto(lines: List<Line>): List<Break> {
        val candidates = lines.mapNotNull(::candidate)
        val strong = candidates.filter { it.strong }
        val unit = (strong.ifEmpty { candidates }).groupingBy { normalUnit(it.unit) }.eachCount().maxByOrNull { it.value }?.key
        val byStart = candidates.filter { normalUnit(it.unit) == unit }.associateBy { it.line.start }
        val volumes = lines.filter { it.text.length <= MAX_TITLE && VOLUME.matches(it.text) && it.text.last() !in SENTENCE_END }.associateBy { it.start }
        val breaks = ArrayList<Break>()
        var previous: Int? = null
        var volume: String? = null
        var pendingVolume: String? = null
        var pendingStart = -1
        for (line in lines) {
            val chapter = byStart[line.start]
            when {
                chapter != null -> {
                    // 编号和标题连着写的只有正好接上才算，免得把正文里的「第二章我们讲过」当标题
                    val expected = chapter.number != null && chapter.number == (previous ?: 0) + 1 || pendingVolume != null && chapter.number == 1
                    if (!chapter.strong && !expected) continue
                    if (chapter.volume != null && chapter.volume != volume) pendingVolume = chapter.volume
                    val start = if (pendingStart >= 0) pendingStart else line.start
                    breaks += Break(start, chapter.title, chapter.number, pendingVolume)
                    if (pendingVolume != null) volume = pendingVolume
                    pendingVolume = null
                    pendingStart = -1
                    previous = chapter.number ?: previous
                }
                volumes.containsKey(line.start) -> {
                    pendingVolume = line.text
                    pendingStart = line.start
                }
                line.text.length <= MAX_TITLE && line.text.isNotEmpty() && line.text.last() !in SENTENCE_END && SPECIAL.matches(line.text) -> {
                    breaks += Break(if (pendingStart >= 0) pendingStart else line.start, line.text, null, pendingVolume)
                    if (pendingVolume != null) volume = pendingVolume
                    pendingVolume = null
                    pendingStart = -1
                }
            }
        }
        return breaks
    }

    private fun normalUnit(unit: String): String = when (unit) {
        "節" -> "节"
        "話" -> "话"
        else -> unit
    }

    private fun blankLines(lines: List<Line>, count: Int): List<Break> = buildList {
        var blank = count
        for (line in lines) {
            if (line.text.isEmpty()) {
                blank++
                continue
            }
            if (blank >= count) add(Break(line.start, line.text.take(MAX_TITLE), null, null))
            blank = 0
        }
    }

    private fun fixedSize(text: String, lines: List<Line>, chars: Int): List<Break> = buildList {
        require(chars > 0) { "Chapter size must be positive" }
        var size = chars
        for (line in lines) {
            if (line.text.isEmpty()) continue
            if (size >= chars) {
                add(Break(line.start, "", null, null))
                size = 0
            }
            size += line.text.length
        }
    }
}

enum class SplitIssue { NUMBER_GAP, NUMBER_REPEAT, TOO_LONG, TOO_SHORT }

/** 预览里要提醒的：编号跳号、重复，明显过长、过短。 */
fun splitIssues(parts: List<SplitPart>, words: List<Int>): List<Set<SplitIssue>> {
    val median = words.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    var previous: Int? = null
    return parts.mapIndexed { index, part ->
        buildSet {
            val number = part.number
            if (number != null) {
                val last = previous
                val restart = part.volume != null && number == 1
                if (last != null && !restart) {
                    if (number == last) add(SplitIssue.NUMBER_REPEAT)
                    else if (number != last + 1) add(SplitIssue.NUMBER_GAP)
                }
                previous = number
            }
            if (median > 0 && parts.size >= 5) {
                if (words[index] > median * 4) add(SplitIssue.TOO_LONG)
                if (words[index] * 10 < median) add(SplitIssue.TOO_SHORT)
            }
        }
    }
}
