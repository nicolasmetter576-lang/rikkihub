package me.rerere.rikkihub.ui.components.richtext

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator

/**
 * 代替 GFM 自带的公式输出。它把公式源码原样写进 HTML，`0<x<1`、`\lambda(x)<k` 里的 `<`
 * 会被 Jsoup 当成标签的开头，公式就从那里断掉了，所以这里先转义再输出。标签和属性和自带的一致，
 * 下游按 `span.math` 的 `inline` 属性取公式文字。
 */
internal class HtmlMathProvider(private val inline: Boolean) : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        // 首尾两个子节点是 $ 或 $$ 定界符
        val formula = node.children.subList(1, node.children.size - 1).joinToString("") { it.getTextInNode(text) }
        visitor.consumeTagOpen(node, "span", "class=\"math\"", "inline=\"$inline\"")
        visitor.consumeHtml(escapeHtml(formula.trimSurroundingBackticks()))
        visitor.consumeTagClose("span")
    }

    // 兼容 $`x`$ 这种写法，去掉成对包住公式的反引号
    private fun String.trimSurroundingBackticks(): String {
        var n = 0
        while (length - 2 * n > 2 && this[n] == '`' && this[lastIndex - n] == '`') n++
        return substring(n, length - n).trim()
    }
}

private fun escapeHtml(text: CharSequence): String =
    text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
