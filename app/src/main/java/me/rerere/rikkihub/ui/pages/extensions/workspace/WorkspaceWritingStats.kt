package me.rerere.rikkihub.ui.pages.extensions.workspace

/**
 * The status bar's counts. [chars]: code points that are not whitespace (spaces, ideographic spaces, tabs,
 * line breaks). [words]: every Han / kana character is one word, every run of letters and digits is one
 * (an apostrophe or hyphen inside it, and a `.` or `,` between digits, keep the run going: don't,
 * well-known, 3.14); punctuation and symbols are not words — Word's 字数. Counted on the source as
 * written. The web editor ports this (`lib/writing-stats.ts`) and runs the same cases.
 */
internal data class WorkspaceTextStats(val chars: Int, val words: Int)

internal fun workspaceTextStats(text: CharSequence, start: Int = 0, end: Int = text.length): WorkspaceTextStats {
    var chars = 0
    var words = 0
    var inWord = false
    var previousDigit = false
    var i = start.coerceIn(0, text.length)
    val stop = end.coerceIn(i, text.length)
    while (i < stop) {
        val cp = Character.codePointAt(text, i)
        val next = i + Character.charCount(cp)
        if (isStatsSpace(cp)) {
            inWord = false
        } else {
            chars++
            when {
                WorkspaceWritingFormat.isCjk(cp) -> {
                    words++
                    inWord = false
                }
                Character.isLetterOrDigit(cp) -> {
                    if (!inWord) words++
                    inWord = true
                }
                inWord && isMark(cp) -> Unit
                inWord && next < stop && isJoiner(cp, previousDigit, Character.codePointAt(text, next)) -> Unit
                else -> inWord = false
            }
        }
        previousDigit = Character.isDigit(cp)
        i = next
    }
    return WorkspaceTextStats(chars, words)
}

private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

private fun isJoiner(cp: Int, previousDigit: Boolean, next: Int): Boolean = when (cp) {
    '\''.code, '’'.code, '-'.code -> Character.isLetterOrDigit(next) && !WorkspaceWritingFormat.isCjk(next)
    '.'.code, ','.code -> previousDigit && Character.isDigit(next)
    else -> false
}

/** Explicit so the web port can match it exactly (Java's and JavaScript's whitespace sets differ). */
internal fun isStatsSpace(cp: Int): Boolean =
    cp in 0x09..0x0D || cp == 0x20 || cp in 0x1C..0x1F || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
        cp in 0x2000..0x200B || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0xFEFF
