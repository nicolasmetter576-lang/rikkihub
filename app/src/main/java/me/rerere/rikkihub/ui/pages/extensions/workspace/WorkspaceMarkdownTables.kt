package me.rerere.rikkihub.ui.pages.extensions.workspace

internal enum class WorkspaceTableAlign { NONE, LEFT, CENTER, RIGHT }

/** One GFM table: raw cell sources (escapes and inline markup kept) normalized to the header width. */
internal data class WorkspaceMarkdownTable(
    val header: List<String>,
    val aligns: List<WorkspaceTableAlign>,
    val rows: List<List<String>>,
) {
    val columnCount: Int get() = header.size
}

/**
 * GFM pipe tables for the workspace editor/preview (Markor-style formatting, Obsidian-style grid).
 * Pure functions over source text; table boundaries for the preview come from the GFM parser
 * (`WorkspaceMarkdownAnalysis.tables`), so fenced code never turns into a table.
 */
internal object WorkspaceMarkdownTables {
    const val MAX_CELLS = 5_000
    private val delimiterCell = Regex("^:?-+:?$")

    /** Splits one table row into trimmed raw cells: optional outer pipes, `\|` escapes, pipes inside code spans. */
    fun splitRow(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.substring(0, text.length - 1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        var codeTicks = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> { current.append(c).append(text[i + 1]); i += 2; continue }
                c == '`' -> {
                    var run = 0
                    while (i + run < text.length && text[i + run] == '`') run++
                    codeTicks = if (codeTicks == 0) run else if (codeTicks == run) 0 else codeTicks
                    current.append(text, i, i + run); i += run; continue
                }
                c == '|' && codeTicks == 0 -> { cells += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString().trim()
        return cells
    }

    fun isDelimiterRow(line: String): Boolean {
        if ('-' !in line) return false
        val cells = splitRow(line)
        return cells.isNotEmpty() && cells.all { delimiterCell.matches(it.replace(" ", "")) }
    }

    fun alignOf(cell: String): WorkspaceTableAlign {
        val c = cell.replace(" ", "")
        val left = c.startsWith(":")
        val right = c.endsWith(":")
        return when {
            left && right -> WorkspaceTableAlign.CENTER
            right -> WorkspaceTableAlign.RIGHT
            left -> WorkspaceTableAlign.LEFT
            else -> WorkspaceTableAlign.NONE
        }
    }

    /** Parses the table in [text] (header, delimiter, body rows); null when it is not a GFM table. */
    fun parse(text: String): WorkspaceMarkdownTable? {
        val lines = text.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.size < 2 || !isDelimiterRow(lines[1])) return null
        val header = splitRow(lines[0])
        val columns = header.size
        if (splitRow(lines[1]).size != columns) return null
        if ((lines.size - 1).toLong() * columns > MAX_CELLS) return null
        fun fit(cells: List<String>) = List(columns) { cells.getOrElse(it) { "" } }
        return WorkspaceMarkdownTable(
            header = header,
            aligns = fit(splitRow(lines[1])).map(::alignOf),
            rows = lines.drop(2).map { fit(splitRow(it)) },
        )
    }

    /** Monospace display width: East Asian wide/fullwidth characters and emoji count as 2. */
    fun displayWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            width += if (isWide(cp)) 2 else if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) 0 else 1
            i += Character.charCount(cp)
        }
        return width
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F ||
            cp in 0x1F900..0x1F9FF || cp in 0x20000..0x3FFFD

    /** Re-renders [table] with every column padded to the same display width (Markor / Advanced Tables). */
    fun format(table: WorkspaceMarkdownTable, newline: String = "\n"): String {
        val widths = IntArray(table.columnCount) { col ->
            maxOf(3, displayWidth(table.header[col]), table.rows.maxOfOrNull { displayWidth(it[col]) } ?: 0)
        }
        fun pad(cell: String, col: Int): String {
            val gap = widths[col] - displayWidth(cell)
            return when (table.aligns[col]) {
                WorkspaceTableAlign.RIGHT -> " ".repeat(gap) + cell
                WorkspaceTableAlign.CENTER -> " ".repeat(gap / 2) + cell + " ".repeat(gap - gap / 2)
                else -> cell + " ".repeat(gap)
            }
        }
        fun row(cells: List<String>) = cells.indices.joinToString(" | ", "| ", " |") { pad(cells[it], it) }
        val delimiter = table.aligns.indices.joinToString(" | ", "| ", " |") { col ->
            val w = widths[col]
            when (table.aligns[col]) {
                WorkspaceTableAlign.NONE -> "-".repeat(w)
                WorkspaceTableAlign.LEFT -> ":" + "-".repeat(w - 1)
                WorkspaceTableAlign.RIGHT -> "-".repeat(w - 1) + ":"
                WorkspaceTableAlign.CENTER -> ":" + "-".repeat(w - 2) + ":"
            }
        }
        return (listOf(row(table.header), delimiter) + table.rows.map(::row)).joinToString(newline)
    }

    /**
     * Line range `[start, end)` (end excludes the final line break) of the table containing
     * [offset]: a run of non-blank lines containing `|` whose second line is a delimiter row.
     */
    fun findTableAt(source: String, offset: Int): IntRange? {
        val lineStarts = ArrayList<Int>().apply {
            add(0)
            source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }
        fun lineEnd(index: Int): Int {
            val next = lineStarts.getOrNull(index + 1) ?: return source.length
            return if (next >= 2 && source[next - 2] == '\r') next - 2 else next - 1
        }
        fun line(index: Int) = source.substring(lineStarts[index], lineEnd(index))
        fun isRow(index: Int) = index in lineStarts.indices && line(index).let { it.isNotBlank() && '|' in it }
        val current = lineStarts.indexOfLast { it <= offset.coerceIn(0, source.length) }
        if (!isRow(current)) return null
        var first = current
        while (isRow(first - 1)) first--
        var last = current
        while (isRow(last + 1)) last++
        if (last - first < 1 || !isDelimiterRow(line(first + 1))) return null
        return lineStarts[first] until lineEnd(last)
    }

    fun template(newline: String = "\n"): String = listOf(
        "| Column 1 | Column 2 | Column 3 |",
        "| -------- | -------- | -------- |",
        "|          |          |          |",
    ).joinToString(newline)
}

/** A table cell ready for display: markup characters removed, styles/links in display offsets. */
internal data class WorkspaceCellText(
    val text: String,
    val spans: List<WorkspaceMarkdownSpan>,
    val links: List<WorkspaceMarkdownLink>,
)

/** Preview blocks: untouched semantic-source ranges and rendered GFM tables. */
internal sealed interface WorkspacePreviewBlock {
    data class Source(val start: Int, val end: Int) : WorkspacePreviewBlock
    data class Table(
        val aligns: List<WorkspaceTableAlign>,
        val header: List<WorkspaceCellText>,
        val rows: List<List<WorkspaceCellText>>,
    ) : WorkspacePreviewBlock
}

internal object WorkspaceMarkdownPreviewModel {
    private val cellStyles = setOf(
        WorkspaceMarkdownStyle.BOLD, WorkspaceMarkdownStyle.ITALIC, WorkspaceMarkdownStyle.STRIKE,
        WorkspaceMarkdownStyle.CODE, WorkspaceMarkdownStyle.LINK,
    )

    /** Splits [analysis] into source blocks and parsed tables; blank lines around a table are dropped. */
    fun blocks(analysis: WorkspaceMarkdownAnalysis, checkCancelled: () -> Unit = {}): List<WorkspacePreviewBlock> {
        val source = analysis.source
        val result = ArrayList<WorkspacePreviewBlock>()
        var cursor = 0
        var cells = 0
        fun addSource(from: Int, to: Int) {
            var a = from
            var b = to
            while (a < b && (source[a] == '\n' || source[a] == '\r')) a++
            while (b > a && (source[b - 1] == '\n' || source[b - 1] == '\r')) b--
            if (a < b) result += WorkspacePreviewBlock.Source(a, b)
        }
        for (range in analysis.tables) {
            if (range.first < cursor || range.last >= source.length) continue
            val table = WorkspaceMarkdownTables.parse(source.substring(range.first, range.last + 1))
            cells += table?.let { (it.rows.size + 1) * it.columnCount } ?: 0
            if (table == null || cells > WorkspaceMarkdownTables.MAX_CELLS) continue
            addSource(cursor, range.first)
            result += WorkspacePreviewBlock.Table(
                aligns = table.aligns,
                header = table.header.map { checkCancelled(); renderCell(it) },
                rows = table.rows.map { row -> row.map { checkCancelled(); renderCell(it) } },
            )
            cursor = range.last + 1
        }
        addSource(cursor, source.length)
        return result
    }

    /** Inline markdown of one cell with its markup hidden (`**b**` → bold "b", `[t](url)` → link "t", `\|` → "|"). */
    fun renderCell(raw: String): WorkspaceCellText {
        val analysis = WorkspaceMarkdownAnalyzer.analyze(raw)
        val hidden = BooleanArray(raw.length)
        val code = analysis.spans.filter { it.style == WorkspaceMarkdownStyle.CODE }
        fun inCode(i: Int) = code.any { i in it.start until it.end }
        if (!analysis.plainFallback) {
            // Code span content is literal: inside it only the backtick fences are markup.
            analysis.spans.filter { it.style == WorkspaceMarkdownStyle.MARKER }.forEach { span ->
                for (i in span.start until span.end) if (!inCode(i) || raw[i] == '`') hidden[i] = true
            }
            // Link destinations are not shown, only the label.
            analysis.spans.filter { it.style == WorkspaceMarkdownStyle.LINK && raw.getOrNull(it.start) == '[' }.forEach { span ->
                val close = raw.indexOf("](", span.start)
                if (close in span.start until span.end) {
                    hidden[span.start] = true
                    for (i in close until span.end) hidden[i] = true
                }
            }
        }
        raw.forEachIndexed { i, c ->
            val next = raw.getOrNull(i + 1) ?: return@forEachIndexed
            val escapes = next == '|' || (next.code in 0x21..0x7E && !next.isLetterOrDigit() && !inCode(i))
            if (c == '\\' && escapes && !hidden[i]) hidden[i] = true
        }
        val map = IntArray(raw.length + 1)
        val out = StringBuilder()
        raw.forEachIndexed { i, c ->
            map[i] = out.length
            if (!hidden[i]) out.append(c)
        }
        map[raw.length] = out.length
        val spans = analysis.spans.filter { it.style in cellStyles }
            .map { it.copy(start = map[it.start], end = map[it.end]) }
            .filter { it.start < it.end }
        val links = analysis.links.map { it.copy(start = map[it.start], end = map[it.end]) }.filter { it.start < it.end }
        return WorkspaceCellText(out.toString(), spans, links)
    }
}
