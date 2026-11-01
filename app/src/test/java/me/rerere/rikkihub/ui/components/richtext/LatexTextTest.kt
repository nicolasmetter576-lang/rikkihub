package me.rerere.rikkihub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexTextTest {
    @Test
    fun `line break spacing argument is dropped`() {
        // JLatexMath 不认识 \\[4pt]，[4pt] 会被当成正文画出来
        assertEquals("""a=1\\b=2""", processLatex("""a=1\\[4pt]b=2"""))
        assertEquals("""a\\ b""", processLatex("""a\\ [ -2.5 em ] b"""))
        assertEquals("""a\\b""", processLatex("""a\\[.5ex]b"""))
    }

    @Test
    fun `plain line breaks and brackets stay`() {
        assertEquals("""a\\b""", processLatex("""a\\b"""))
        assertEquals("""x\\[y]""", processLatex("""x\\[y]"""))
        assertEquals("""\left[ x \right]""", processLatex("""\left[ x \right]"""))
    }

    @Test
    fun `delimiters are still removed`() {
        assertEquals("a=1", processLatex("\$\$a=1\$\$"))
        assertEquals("a=1", processLatex("\$a=1\$"))
    }
}
