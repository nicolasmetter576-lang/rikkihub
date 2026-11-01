package me.rerere.rikkihub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

// 开引号 -> 闭引号。单引号常被当作撇号（don't、it’s）使用，不参与配对
private val QUOTE_PAIRS = mapOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')

/** 构建段落时给代码片段打上这个注解，里面的引号不参与配对 */
internal const val QUOTE_HIGHLIGHT_SKIP_TAG = "rikkihub.quote_skip"

/**
 * 已经闭合的引号区间（含两端引号）。只高亮闭合的引号，
 * 避免流式输出时或孤立的引号把后面整段都染色。[skip] 内的字符既不开也不闭。
 */
internal fun quotedTextRanges(text: CharSequence, skip: List<IntRange> = emptyList()): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var pendingClose: Char? = null
    var pendingStart = 0
    for (index in text.indices) {
        if (skip.any { index in it }) continue
        val char = text[index]
        val close = pendingClose
        if (close == null) {
            QUOTE_PAIRS[char]?.let {
                pendingClose = it
                pendingStart = index
            }
        } else if (char == close) {
            ranges += pendingStart..index
            pendingClose = null
        }
    }
    return ranges
}

/** 给引号括起来的文字加上 [style]；为 null 时原样返回 */
internal fun AnnotatedString.withQuotedTextHighlight(style: SpanStyle?): AnnotatedString {
    if (style == null) return this
    val skip = getStringAnnotations(QUOTE_HIGHLIGHT_SKIP_TAG, 0, length).map { it.start until it.end }
    val ranges = quotedTextRanges(text, skip)
    if (ranges.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }.toAnnotatedString()
}
