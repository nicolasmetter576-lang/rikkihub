package me.rerere.rikkihub.ui.components.richtext

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/** 会话内查找：正文里标出 [query] 的命中；[current] 为 true 的是当前命中所在的消息，颜色更深。 */
data class FindHighlight(val query: String, val current: Boolean)

val LocalFindHighlight = compositionLocalOf<FindHighlight?> { null }

/** 不区分大小写、互不重叠的命中位置。 */
fun findMatches(text: CharSequence, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val index = text.indexOf(query, from, ignoreCase = true)
        if (index < 0) return ranges
        ranges += index until index + query.length
        from = index + query.length
    }
}

/** 当前查找的高亮样式；不在查找中时为 null。 */
@Composable
internal fun rememberFindHighlightStyle(): Pair<String, SpanStyle>? {
    val find = LocalFindHighlight.current ?: return null
    val colors = MaterialTheme.colorScheme
    return remember(find, colors) {
        find.query to if (find.current) {
            SpanStyle(background = colors.primary, color = colors.onPrimary)
        } else {
            SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)
        }
    }
}

internal fun AnnotatedString.withFindHighlight(find: Pair<String, SpanStyle>?): AnnotatedString {
    if (find == null) return this
    val ranges = findMatches(text, find.first)
    if (ranges.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        ranges.forEach { addStyle(find.second, it.first, it.last + 1) }
    }.toAnnotatedString()
}
