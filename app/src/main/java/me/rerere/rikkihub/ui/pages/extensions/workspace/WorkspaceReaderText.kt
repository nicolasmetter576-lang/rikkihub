package me.rerere.rikkihub.ui.pages.extensions.workspace

/** Files the reading mode opens and chains: the chapters of a folder. */
internal val READER_EXTENSIONS = setOf("md", "markdown", "txt", "text")

internal fun isReadableName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in READER_EXTENSIONS

/**
 * File names in reading order: digit runs compare as numbers ("第2章" before "第10章"), the rest
 * case-insensitively, then the raw name as a tie-breaker.
 */
internal val naturalNameOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val endA = (i until a.length).firstOrNull { !a[it].isDigit() } ?: a.length
            val endB = (j until b.length).firstOrNull { !b[it].isDigit() } ?: b.length
            val numA = a.substring(i, endA).trimStart('0')
            val numB = b.substring(j, endB).trimStart('0')
            if (numA.length != numB.length) return@Comparator numA.length - numB.length
            val cmp = numA.compareTo(numB)
            if (cmp != 0) return@Comparator cmp
            i = endA
            j = endB
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return@Comparator cmp
            i++
            j++
        }
    }
    (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
}

/**
 * One line of the reading text; [heading] is the Markdown heading level (0 = body), [code] the
 * character ranges of [text] that are code (all of a fenced line, the backtick runs of inline code).
 */
internal data class ReaderLine(val text: String, val heading: Int = 0, val code: List<IntRange> = emptyList())

private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val EMPHASIS = Regex("(\\*\\*|__|~~)(.+?)\\1")
private val FENCE = Regex("^\\s*(```|~~~)")
private val INLINE_CODE = Regex("`[^`]*`")

/**
 * The text as it is read: plain text keeps every line; Markdown loses the marks that only make
 * sense to an editor (heading hashes, bold/strike markers, link targets, code fences) and keeps
 * the words. Line breaks and blank lines stay where they were.
 */
internal fun readingLines(content: String, markdown: Boolean): List<ReaderLine> {
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    if (!markdown) return lines.map { ReaderLine(it) }
    var inFence = false
    return lines.mapNotNull { raw ->
        if (FENCE.containsMatchIn(raw)) {
            inFence = !inFence
            return@mapNotNull null
        }
        if (inFence) return@mapNotNull ReaderLine(raw, code = listOf(raw.indices))
        val heading = HEADING.matchEntire(raw)
        val (text, level) = if (heading != null) heading.groupValues[2] to heading.groupValues[1].length else raw to 0
        val shown = text.replace(IMAGE, "$1").replace(LINK, "$1").replace(EMPHASIS, "$2")
        ReaderLine(text = shown, heading = level, code = INLINE_CODE.findAll(shown).map { it.range }.toList())
    }
}
