package me.rerere.rikkihub.ui.components.richtext

// 第 1 组：代码（里面的内容原样保留）和公式里的换行 `\\`。跳过 `\\`，不然 `\\[4pt]` 里的 `\[` 会被当成块级公式的开头，
// 把后面直到 `\]` 的内容都吞进去；第 2、3 组分别是 `\( \)` 和 `\[ \]`
private val LATEX_DELIMITER_REGEX =
    Regex("""(```[\s\S]*?```|`[^`\n]*`|\\\\)|\\\((.+?)\\\)|\\\[([\s\S]+?)\\]""")
private val LATEX_BLOCK_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

/**
 * 把 `\( \)`、`\[ \]` 形式的公式定界符换成解析器认识的 `$ $`、`$$ $$`，代码里的内容不动。
 * [collapseBlockLineBreaks] 为 true 时块级公式去掉首尾空白，内部换行合成一个空格。
 */
internal fun normalizeLatexDelimiters(content: String, collapseBlockLineBreaks: Boolean): String =
    LATEX_DELIMITER_REGEX.replace(content) { match ->
        val inline = match.groups[2]?.value
        val block = match.groups[3]?.value
        when {
            inline != null -> "\$" + inline + "\$"
            block != null -> {
                val formula = if (collapseBlockLineBreaks) {
                    block.trim().replace(LATEX_BLOCK_LINE_BREAK_REGEX, " ")
                } else {
                    block
                }
                "\$\$" + formula + "\$\$"
            }

            else -> match.value
        }
    }
