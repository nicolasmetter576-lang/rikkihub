package me.rerere.rikkihub.ui.pages.extensions.workspace

/** The four one-tap layout commands of the toolbar's 排版 menu. [id] is the wire name shared with the web editor's tests. */
internal enum class WorkspaceFormatCommand(val id: String) {
    BLANK_LINE_BETWEEN("blank_line_between"),
    REMOVE_BLANK_LINES("remove_blank_lines"),
    INDENT("indent"),
    CJK_SPACING("cjk_spacing"),
}

/** Which files 排版 is offered for, and how their lines are read. */
internal enum class WorkspaceFormatKind { MARKDOWN, PLAIN }

internal fun workspaceFormatKind(fileName: String): WorkspaceFormatKind? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> WorkspaceFormatKind.MARKDOWN
        "txt", "text" -> WorkspaceFormatKind.PLAIN
        else -> null
    }

/**
 * 排版. Paragraphs are lines: in plain text every non-blank line, in Markdown only prose lines — code
 * fences, front matter, math blocks, headings, lists (and their continuation lines), quotes, tables,
 * HTML blocks, link definitions, rules and indented code stay as they are. A command touches the lines
 * the selection covers, or the whole document without a selection, and is ONE source edit (one undo
 * step). The web editor ports these rules line for line (`lib/writing-format.ts`); both run the cases
 * in `app/src/test/resources/writing/format-cases.json`.
 */
internal object WorkspaceWritingFormat {
    /** Two ideographic spaces: Markdown would turn four ASCII spaces into a code block. */
    const val INDENT = "\u3000\u3000"

    /** Null when the command changes nothing. The edit covers only the changed span (common prefix and suffix trimmed). */
    fun apply(source: String, selectionStart: Int, selectionEnd: Int, command: WorkspaceFormatCommand, kind: WorkspaceFormatKind): WorkspaceSourceEdit? {
        val lines = splitLines(source)
        val kinds = classify(lines.map { it.text }, kind)
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, source.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, source.length)
        val (first, last) = if (start == end) 0 to lines.lastIndex else {
            // A selection ending at column zero does not take in the following line.
            val endAt = if (source.getOrNull(end - 1) == '\n') end - 1 else end
            lineIndexAt(lines, start) to lineIndexAt(lines, endAt)
        }
        val newline = if ("\r\n" in source) "\r\n" else "\n"
        val out = when (command) {
            WorkspaceFormatCommand.BLANK_LINE_BETWEEN -> blankLineBetween(lines, kinds, first, last, newline)
            WorkspaceFormatCommand.REMOVE_BLANK_LINES -> removeBlankLines(lines, kinds, first, last)
            WorkspaceFormatCommand.INDENT -> lines.mapIndexed { i, line ->
                if (i in first..last && kinds[i].kind == LineKind.PROSE) line.copy(text = indent(line.text)) else line
            }
            WorkspaceFormatCommand.CJK_SPACING -> lines.mapIndexed { i, line ->
                if (i in first..last && kinds[i].kind.spaced) line.copy(text = cjkSpacing(line.text, kind)) else line
            }
        }
        val result = out.joinToString("") { it.text + it.ending }
        if (result == source) return null
        var prefix = 0
        val maxPrefix = minOf(source.length, result.length)
        while (prefix < maxPrefix && source[prefix] == result[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(source.length, result.length) - prefix &&
            source[source.length - 1 - suffix] == result[result.length - 1 - suffix]) suffix++
        val replacement = result.substring(prefix, result.length - suffix)
        // A selection keeps covering the formatted lines; a caret stays on its line, after the same text.
        val (selStart, selEnd) = if (start != end) {
            val rangeStart = lines[first].start
            val oldRangeEnd = lines[last].let { it.start + it.text.length }
            rangeStart to oldRangeEnd + (result.length - source.length)
        } else {
            val caret = mapCaret(lines, out, start)
            caret to caret
        }
        return WorkspaceSourceEdit(prefix, source.length - suffix, replacement, selStart, selEnd)
    }

    // ---- lines --------------------------------------------------------------------------------

    /** [index] is the line's number in the original text (-1 for a line a command inserted). */
    internal data class Line(val start: Int, val text: String, val ending: String, val index: Int = -1)

    /** Lines without their terminator; the last line has an empty ending (it may itself be empty). */
    internal fun splitLines(source: String): List<Line> {
        val lines = mutableListOf<Line>()
        var at = 0
        while (true) {
            val nl = source.indexOf('\n', at)
            if (nl < 0) {
                lines += Line(at, source.substring(at), "", lines.size)
                return lines
            }
            val cr = nl > at && source[nl - 1] == '\r'
            lines += Line(at, source.substring(at, if (cr) nl - 1 else nl), if (cr) "\r\n" else "\n", lines.size)
            at = nl + 1
        }
    }

    private fun lineIndexAt(lines: List<Line>, offset: Int): Int {
        var index = lines.binarySearchBy(offset) { it.start }
        if (index < 0) index = -index - 2
        return index.coerceIn(0, lines.lastIndex)
    }

    /** The caret's line in [out] (or the next kept line when its own was removed), at the same distance from the line end. */
    private fun mapCaret(lines: List<Line>, out: List<Line>, caret: Int): Int {
        val old = lineIndexAt(lines, caret)
        val column = caret - lines[old].start
        var offset = 0
        for (line in out) {
            if (line.index == old) {
                val fromEnd = (lines[old].text.length - column).coerceAtLeast(0)
                return offset + if (column == 0) 0 else (line.text.length - fromEnd).coerceIn(0, line.text.length)
            }
            if (line.index > old) return offset
            offset += line.text.length + line.ending.length
        }
        return offset
    }

    internal enum class LineKind(val spaced: Boolean) {
        BLANK(false),
        PROSE(true),
        HEADING(true),
        LIST(true),
        QUOTE(true),
        TABLE(true),
        /** Code fences, front matter, math, HTML, link definitions, rules, setext underlines, indented code. */
        VERBATIM(false),
    }

    /** [block] groups lines that must stay together (one list, one quote, one fence…); null = a line of its own. */
    internal data class LineClass(val kind: LineKind, val block: Int? = null)

    private fun isBlank(text: String) = text.all { isLayoutSpace(it) }

    internal fun isLayoutSpace(c: Char) = c == ' ' || c == '\t' || c == '\u3000' || c == '\u00A0' || c == '\u000B' || c == '\u000C'

    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")
    private val HEADING = Regex("""^ {0,3}#{1,6}(?:[ \t]|$)""")
    private val LIST = Regex("""^[ \t]*(?:[-*+]|\d{1,9}[.)])(?:[ \t]|$)""")
    private val QUOTE = Regex("""^ {0,3}>""")
    private val RULE = Regex("""^ {0,3}(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$""")
    private val SETEXT = Regex("""^ {0,3}(?:=+|-+)[ \t]*$""")
    private val TABLE_DELIMITER = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
    private val HTML = Regex("""^ {0,3}<(?:[A-Za-z][A-Za-z0-9-]*|/[A-Za-z]|!--|!\[CDATA\[|\?)""")
    private val LINK_DEFINITION = Regex("""^ {0,3}\[[^\]]+]:""")
    private val CJK = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsBopomofo}]""")

    internal fun classify(lines: List<String>, kind: WorkspaceFormatKind): List<LineClass> {
        if (kind == WorkspaceFormatKind.PLAIN) return lines.map { LineClass(if (isBlank(it)) LineKind.BLANK else LineKind.PROSE) }
        val result = MutableList(lines.size) { LineClass(LineKind.BLANK) }
        var block = 0
        var i = 0
        // Front matter: `---` on the very first line up to the next `---` / `...`.
        if (lines.firstOrNull()?.trimEnd() == "---") {
            val close = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
            if (close != null) {
                block++
                for (j in 0..close) result[j] = LineClass(LineKind.VERBATIM, block)
                i = close + 1
            }
        }
        var listBlock: Int? = null // open list: indented lines and lazy continuations still belong to it
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            when {
                fence != null -> {
                    val marker = fence.groupValues[1]
                    block++
                    result[i] = LineClass(LineKind.VERBATIM, block)
                    var j = i + 1
                    while (j < lines.size) {
                        result[j] = LineClass(LineKind.VERBATIM, block)
                        val trimmed = lines[j].trimStart(' ')
                        if (lines[j].length - trimmed.length <= 3 && trimmed.startsWith(marker) &&
                            trimmed.trimEnd().all { it == marker[0] }) break
                        j++
                    }
                    listBlock = null
                    i = j + 1
                    continue
                }
                line.trim() == "$$" -> {
                    block++
                    result[i] = LineClass(LineKind.VERBATIM, block)
                    var j = i + 1
                    while (j < lines.size) {
                        result[j] = LineClass(LineKind.VERBATIM, block)
                        if (lines[j].trim() == "$$") break
                        j++
                    }
                    listBlock = null
                    i = j + 1
                    continue
                }
                isBlank(line) -> result[i] = LineClass(LineKind.BLANK)
                listBlock != null && (line.startsWith(' ') || line.startsWith('\t')) && !LIST.containsMatchIn(line) ->
                    result[i] = LineClass(LineKind.LIST, listBlock)
                listBlock != null && result[i - 1].kind == LineKind.LIST && !startsBlock(lines, i) ->
                    result[i] = LineClass(LineKind.LIST, listBlock) // lazy continuation of the item above
                LIST.containsMatchIn(line) && !RULE.matches(line) -> {
                    val previous = result.getOrNull(i - 1)
                    val id = if (previous?.kind == LineKind.LIST) previous.block!! else (listBlock ?: ++block)
                    result[i] = LineClass(LineKind.LIST, id)
                    listBlock = id
                    i++
                    continue
                }
                SETEXT.matches(line) && i > 0 && result[i - 1].kind == LineKind.PROSE -> {
                    // `Title` + `===` / `---` is a heading: both lines stay together.
                    block++
                    result[i - 1] = LineClass(LineKind.HEADING, block)
                    result[i] = LineClass(LineKind.VERBATIM, block)
                }
                RULE.matches(line) -> result[i] = LineClass(LineKind.VERBATIM)
                HEADING.containsMatchIn(line) -> result[i] = LineClass(LineKind.HEADING)
                QUOTE.containsMatchIn(line) -> {
                    val previous = result.getOrNull(i - 1)
                    result[i] = LineClass(LineKind.QUOTE, if (previous?.kind == LineKind.QUOTE) previous.block else ++block)
                }
                isTableLine(lines, i) || '|' in line && result.getOrNull(i - 1)?.kind == LineKind.TABLE -> {
                    val previous = result.getOrNull(i - 1)
                    result[i] = LineClass(LineKind.TABLE, if (previous?.kind == LineKind.TABLE) previous.block else ++block)
                }
                HTML.containsMatchIn(line) -> {
                    val previous = result.getOrNull(i - 1)
                    result[i] = LineClass(LineKind.VERBATIM, if (previous?.kind == LineKind.VERBATIM && previous.block != null &&
                        HTML.containsMatchIn(lines[i - 1])) previous.block else ++block)
                }
                LINK_DEFINITION.containsMatchIn(line) -> result[i] = LineClass(LineKind.VERBATIM)
                isIndentedCode(lines, result, i) -> {
                    val previous = result.getOrNull(i - 1)
                    result[i] = LineClass(LineKind.VERBATIM, if (previous?.kind == LineKind.VERBATIM && previous.block != null &&
                        isIndented(lines[i - 1])) previous.block else ++block)
                }
                else -> result[i] = LineClass(LineKind.PROSE)
            }
            if (result[i].kind != LineKind.BLANK && result[i].kind != LineKind.LIST) listBlock = null
            i++
        }
        return result
    }

    /** A line that would start a new block even right under a list item (so it is no lazy continuation). */
    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        return HEADING.containsMatchIn(line) || QUOTE.containsMatchIn(line) || RULE.matches(line) || FENCE.containsMatchIn(line) ||
            HTML.containsMatchIn(line) || isTableLine(lines, i)
    }

    private fun isTableLine(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        if ('|' !in line) return false
        if (line.trimStart().startsWith('|') || TABLE_DELIMITER.matches(line) && '-' in line) return true
        // A header row without the leading pipe: the next line is the delimiter row.
        return lines.getOrNull(i + 1)?.let { TABLE_DELIMITER.matches(it) && '|' in it && '-' in it } == true
    }

    private fun isIndented(line: String) = line.startsWith('\t') || line.startsWith("    ")

    /** Four spaces / a tab after a blank line or more code, with no CJK in it: Markdown's indented code. */
    private fun isIndentedCode(lines: List<String>, kinds: List<LineClass>, i: Int): Boolean {
        val line = lines[i]
        if (!isIndented(line) || CJK.containsMatchIn(line)) return false
        val previous = kinds.getOrNull(i - 1) ?: return true
        return previous.kind == LineKind.BLANK || previous.kind == LineKind.VERBATIM && isIndented(lines[i - 1])
    }

    // ---- commands -----------------------------------------------------------------------------

    private fun separable(a: LineClass, b: LineClass): Boolean = a.block == null || a.block != b.block

    private fun blankLineBetween(lines: List<Line>, kinds: List<LineClass>, first: Int, last: Int, newline: String): List<Line> {
        val out = mutableListOf<Line>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            out += line
            if (i !in first until last || kinds[i].kind == LineKind.BLANK) {
                i++
                continue
            }
            // Next non-blank line within the range: keep exactly one blank line before it.
            var j = i + 1
            while (j <= last && kinds[j].kind == LineKind.BLANK) j++
            if (j > last) {
                i++
                continue
            }
            if (!separable(kinds[i], kinds[j]) && j == i + 1) {
                i++
                continue
            }
            val ending = line.ending.ifEmpty { newline }
            if (j == i + 1) {
                out[out.lastIndex] = line.copy(ending = ending)
                out += Line(-1, "", ending)
            } else {
                out += lines[i + 1].copy(text = "") // the first blank line stays (emptied of any spaces)
            }
            i = j
        }
        return out
    }

    private fun removeBlankLines(lines: List<Line>, kinds: List<LineClass>, first: Int, last: Int): List<Line> {
        val drop = BooleanArray(lines.size)
        var i = first
        while (i <= last) {
            if (kinds[i].kind != LineKind.BLANK) {
                i++
                continue
            }
            var j = i
            while (j <= last && kinds[j].kind == LineKind.BLANK) j++
            val before = i - 1
            if (before >= first && j <= last && kinds[before].kind == LineKind.PROSE && kinds[j].kind == LineKind.PROSE) {
                for (k in i until j) drop[k] = true
            }
            i = j
        }
        return lines.filterIndexed { index, _ -> !drop[index] }
    }

    private fun indent(text: String): String {
        val body = text.dropWhile { isLayoutSpace(it) }
        return INDENT + body
    }

    // ---- 中英文间加空格 ---------------------------------------------------------------------------

    /** A web address ends at whitespace, CJK text or full-width punctuation. */
    private const val URL = """(?:(?:https?|ftp)://|www\.)[^\s\u3000-\u303F\uFF00-\uFFEF\p{IsHan}\p{IsHiragana}\p{IsKatakana}]+"""
    private val MARKDOWN_PROTECTED = Regex(
        """(`+).+?\1""" + // inline code
            """|]\([^)\s]*(?:\s+"[^"]*")?\)""" + // link destination
            """|]\[[^\]]*]""" + // reference link label
            """|<[A-Za-z/!][^>]*>""" + // HTML tag or autolink
            """|\$[^$\s](?:[^$]*[^$\s])?\$(?!\d)""" + // inline math (not「$5 和 $10」)
            "|$URL",
    )
    private val PLAIN_PROTECTED = Regex("""$URL|[\w.+-]+@[\w-]+\.[A-Za-z][\w.-]*""")

    internal fun cjkSpacing(text: String, kind: WorkspaceFormatKind): String {
        val protected = if (kind == WorkspaceFormatKind.MARKDOWN) MARKDOWN_PROTECTED else PLAIN_PROTECTED
        val out = StringBuilder(text.length + 8)
        // Spaces go inside plain runs and at their borders with a protected piece (「用 https://… 打开」), never inside one.
        fun add(piece: String, spaced: Boolean) {
            if (piece.isEmpty()) return
            if (out.isNotEmpty() && needsSpace(out.codePointBefore(out.length), piece.codePointAt(0))) out.append(' ')
            out.append(if (spaced) spaceRun(piece) else piece)
        }
        var at = 0
        for (match in protected.findAll(text)) {
            add(text.substring(at, match.range.first), true)
            add(match.value, false)
            at = match.range.last + 1
        }
        add(text.substring(at), true)
        return out.toString()
    }

    private fun spaceRun(text: String): String {
        if (text.length < 2) return text
        val out = StringBuilder(text.length + 4)
        var previous = -1
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (previous >= 0 && needsSpace(previous, cp)) out.append(' ')
            out.appendCodePoint(cp)
            previous = cp
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun needsSpace(left: Int, right: Int): Boolean =
        isCjk(left) && isLatinWord(right) || (isLatinWord(left) || left == '%'.code) && isCjk(right)

    internal fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.BOPOMOFO -> true
        else -> false
    }

    private fun isLatinWord(cp: Int): Boolean = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code ||
        Character.isLetter(cp) && when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC -> true
            else -> false
        }
}
