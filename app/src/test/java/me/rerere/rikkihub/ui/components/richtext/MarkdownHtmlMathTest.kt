package me.rerere.rikkihub.ui.components.richtext

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/** 消息里有 HTML 时走 HTML 生成路径，公式里的 `<` 不能被当成标签。 */
class MarkdownHtmlMathTest {
    private fun mathSpans(markdown: String) =
        Jsoup.parse(generateMarkdownHtml(markdown)).select("span.math")

    @Test
    fun `inline formula keeps less-than signs`() {
        val spans = mathSpans("with html <b>bold</b> and \$0<y<1\$ end")
        assertEquals(listOf("0<y<1"), spans.map { it.text() })
        assertEquals(listOf("true"), spans.map { it.attr("inline") })
    }

    @Test
    fun `block formula keeps less-than signs`() {
        val spans = mathSpans("<b>x</b>\n\n\$\$a<b\$\$\n")
        assertEquals(listOf("a<b"), spans.map { it.text() })
        assertEquals(listOf("false"), spans.map { it.attr("inline") })
    }

    @Test
    fun `formula with ampersand and greater-than is intact`() {
        assertEquals(listOf("a&b"), mathSpans("\$a&b\$").map { it.text() })
        assertEquals(listOf("x<y>z"), mathSpans("\$x<y>z\$").map { it.text() })
    }

    @Test
    fun `text around the formula survives`() {
        val doc = Jsoup.parse(generateMarkdownHtml("Plain: \$0<x<1\$ ok.\n\nWith html <b>bold</b> and \$0<y<1\$ end."))
        assertEquals("Plain: 0<x<1 ok.", doc.select("p").first()!!.text())
        assertEquals(2, doc.select("span.math").size)
    }
}
