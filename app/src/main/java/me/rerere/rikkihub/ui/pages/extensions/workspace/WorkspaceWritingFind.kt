package me.rerere.rikkihub.ui.pages.extensions.workspace

/** Plain-text find for the editor's find bar: non-overlapping matches, at most [limit] of them. */
internal fun workspaceFindMatches(text: CharSequence, query: String, caseSensitive: Boolean, limit: Int = WORKSPACE_FIND_LIMIT): IntArray {
    if (query.isEmpty()) return IntArray(0)
    val starts = ArrayList<Int>()
    val source = text.toString()
    var at = source.indexOf(query, 0, ignoreCase = !caseSensitive)
    while (at >= 0 && starts.size < limit) {
        starts += at
        at = source.indexOf(query, at + query.length, ignoreCase = !caseSensitive)
    }
    return starts.toIntArray()
}

internal const val WORKSPACE_FIND_LIMIT = 10_000

/** The match to show after the matches changed: the first one at or after [previousStart], else the first. */
internal fun workspaceFindCurrent(matches: IntArray, previousStart: Int): Int {
    if (matches.isEmpty()) return -1
    val index = matches.indexOfFirst { it >= previousStart }
    return if (index >= 0) index else 0
}

/** Every match replaced: the new text and how many were replaced. */
internal fun workspaceReplaceAll(text: CharSequence, matches: IntArray, length: Int, replacement: String): String {
    val out = StringBuilder(text.length + matches.size * (replacement.length - length).coerceAtLeast(0))
    var at = 0
    for (start in matches) {
        out.append(text, at, start).append(replacement)
        at = start + length
    }
    return out.append(text, at, text.length).toString()
}
