package me.rerere.rikkihub.utils

private val CODE_OR_INLINE_CODE_REGEX = Regex("```[\\s\\S]*?```|`[^`]*?`")
private val LINK_OR_IMAGE_REGEX = Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)")
private val BOLD_STAR_REGEX = Regex("\\*\\*([^*]+?)\\*\\*")
private val ITALIC_STAR_REGEX = Regex("\\*([^*]+?)\\*")
private val BOLD_UNDERSCORE_REGEX = Regex("__([^_]+?)__")
private val ITALIC_UNDERSCORE_REGEX = Regex("_([^_]+?)_")
private val STRIKETHROUGH_REGEX = Regex("~~([^~]+?)~~")
private val HEADING_MARKER_REGEX = Regex("(?m)^#+\\s*")
private val BULLET_MARKER_REGEX = Regex("(?m)^\\s*[-*+]\\s+")
private val ORDERED_LIST_MARKER_REGEX = Regex("(?m)^\\s*\\d+\\.\\s+")
private val QUOTE_MARKER_REGEX = Regex("(?m)^>\\s*")
private val HORIZONTAL_RULE_REGEX = Regex("(?m)^(\\s*[-*_]){3,}\\s*$")
private val MULTIPLE_NEWLINES_REGEX = Regex("\n{3,}")
private val THINKING_TITLE_BOLD_REGEX = Regex("^\\*\\*(.+?)\\*\\*$")

// HTML：只认真正的标签名，属性要符合语法。`x<y && y>0`、`<p@example.com>` 这类正文不会被当成标签
private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
private val HTML_RAW_TEXT_BLOCK_REGEX =
    Regex("<(style|script)(\\s[^<>]*)?>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
private const val HTML_ATTRIBUTES =
    "(?:\\s+[a-zA-Z_:][-a-zA-Z0-9_:.]*(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'<>`=]+))?)*\\s*/?"
private const val HTML_BLOCK_TAGS = "br|p|div|li|ul|ol|dl|dt|dd|tr|table|thead|tbody|tfoot|h[1-6]|details|summary|" +
    "blockquote|section|article|header|footer|hr|figure|figcaption|pre"
private const val HTML_INLINE_TAGS = "b|i|u|s|em|strong|span|a|img|font|small|big|sub|sup|code|del|ins|mark|strike|" +
    "center|kbd|abbr|cite|q|td|th|label|button|input|video|audio|source|svg|path|iframe|caption|colgroup|col"
private val HTML_BLOCK_TAG_REGEX = Regex("</?(?:$HTML_BLOCK_TAGS)$HTML_ATTRIBUTES>", RegexOption.IGNORE_CASE)
private val HTML_INLINE_TAG_REGEX = Regex("</?(?:$HTML_INLINE_TAGS)$HTML_ATTRIBUTES>", RegexOption.IGNORE_CASE)
private val HTML_NUMERIC_ENTITY_REGEX = Regex("&#(?:x([0-9a-fA-F]{1,6})|([0-9]{1,7}));")
private val HTML_NAMED_ENTITIES = mapOf(
    "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ",
)
private val HTML_NAMED_ENTITY_REGEX = Regex("&(?:lt|gt|quot|apos|nbsp);")
private val AMPERSAND_ENTITY_REGEX = Regex("&amp;")

/**
 * 移除字符串中的Markdown格式
 * @return 移除Markdown格式后的纯文本
 */
fun String.stripMarkdown(): String {
    return this
        // 移除代码块 (```...``` 和 `...`)
        .replace(CODE_OR_INLINE_CODE_REGEX, "")
        // 移除 HTML：注释、style / script 块、标签（换行和块级标签留一个换行，行内标签直接去掉）
        .replace(HTML_COMMENT_REGEX, "")
        .replace(HTML_RAW_TEXT_BLOCK_REGEX, "")
        .replace(HTML_BLOCK_TAG_REGEX, "\n")
        .replace(HTML_INLINE_TAG_REGEX, "")
        // 移除图片和链接，但保留其文本内容
        .replace(LINK_OR_IMAGE_REGEX, "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(BOLD_STAR_REGEX, "$1")
        .replace(ITALIC_STAR_REGEX, "$1")
        // 移除下划线
        .replace(BOLD_UNDERSCORE_REGEX, "$1")
        .replace(ITALIC_UNDERSCORE_REGEX, "$1")
        // 移除删除线
        .replace(STRIKETHROUGH_REGEX, "$1")
        // 移除标题标记 (多行模式)
        .replace(HEADING_MARKER_REGEX, "")
        // 移除列表标记 (多行模式)
        .replace(BULLET_MARKER_REGEX, "")
        .replace(ORDERED_LIST_MARKER_REGEX, "")
        // 移除引用标记 (多行模式)
        .replace(QUOTE_MARKER_REGEX, "")
        // 移除水平分割线
        .replace(HORIZONTAL_RULE_REGEX, "")
        // 将多个换行符压缩，以保留段落
        .replace(MULTIPLE_NEWLINES_REGEX, "\n\n")
        // 最后解码 HTML 实体，只解码一次。解出来的 < > 是正文，后面不会再被当成标签
        .decodeHtmlEntities()
        .trim()
}

private fun String.decodeHtmlEntities(): String =
    replace(HTML_NUMERIC_ENTITY_REGEX) { match ->
        val code = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16)
            ?: match.groupValues[2].toIntOrNull()
        if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
    }
        .replace(HTML_NAMED_ENTITY_REGEX) { HTML_NAMED_ENTITIES.getValue(it.value) }
        .replace(AMPERSAND_ENTITY_REGEX, "&")

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()

        // 检查是否为加粗格式且独占一整行
        val match = THINKING_TITLE_BOLD_REGEX.find(line)

        if (match != null) {
            // 返回加粗标记内的文本内容
            return match.groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}
