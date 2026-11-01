package me.rerere.rikkihub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexDelimitersTest {
    private fun normalize(text: String, collapse: Boolean = true) =
        normalizeLatexDelimiters(text, collapseBlockLineBreaks = collapse)

    @Test
    fun `inline and block delimiters become dollars`() {
        assertEquals("a \$x\$ b", normalize("a \\(x\\) b"))
        assertEquals("a \$\$y\$\$ b", normalize("a \\[y\\] b"))
    }

    @Test
    fun `block formula collapses line breaks only when asked`() {
        assertEquals("\$\$a b\$\$", normalize("\\[\n a \n b \n\\]"))
        assertEquals("\$\$\n a \n b \n\$\$", normalize("\\[\n a \n b \n\\]", collapse = false))
    }

    @Test
    fun `code is left alone`() {
        assertEquals("`\\(a\\)` and \$b\$", normalize("`\\(a\\)` and \\(b\\)"))
        assertEquals("```\n\\[a\\]\n```", normalize("```\n\\[a\\]\n```"))
    }

    @Test
    fun `line break spacing is not the start of a block formula`() {
        // \\[4pt] 是带行距的换行，它里面的 \[ 不能把后面的内容吞进块级公式
        assertEquals(
            "\$\$a=1\\\\[4pt]b=2\$\$ and then \$\$c=3\$\$",
            normalize("\$\$a=1\\\\[4pt]b=2\$\$ and then \\[c=3\\]"),
        )
    }

    @Test
    fun `line break inside a delimited block formula is kept`() {
        assertEquals("\$\$a\\\\[2pt]b\$\$", normalize("\\[a\\\\[2pt]b\\]"))
    }

    @Test
    fun `text without formulas is unchanged`() {
        assertEquals("plain text", normalize("plain text"))
    }
}
