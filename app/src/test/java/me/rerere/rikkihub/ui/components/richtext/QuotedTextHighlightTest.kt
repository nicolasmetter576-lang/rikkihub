package me.rerere.rikkihub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QuotedTextHighlightTest {
    private fun quoted(text: String, skip: List<IntRange> = emptyList()) =
        quotedTextRanges(text, skip).map { text.substring(it) }

    @Test
    fun `pairs straight curly and CJK quotes`() {
        assertEquals(
            listOf("\"hi\"", "“你好”", "「走吧」", "『书名』"),
            quoted("He said \"hi\", 她说“你好”，「走吧」『书名』"),
        )
    }

    @Test
    fun `unclosed quote highlights nothing`() {
        assertEquals(emptyList<String>(), quoted("still streaming “half a sentence"))
        assertEquals(listOf("“a”"), quoted("“a” and then “b"))
    }

    @Test
    fun `apostrophes and single quotes are ignored`() {
        assertEquals(emptyList<String>(), quoted("don't it’s 'single'"))
    }

    @Test
    fun `mismatched closers do not close a different opener`() {
        // 「 只认 」，中间的 ” 不算
        assertEquals(listOf("「a ” b」"), quoted("「a ” b」"))
    }

    @Test
    fun `skipped ranges neither open nor close`() {
        val text = "say `\"x` then \"y\""
        val codeStart = text.indexOf('`')
        val codeEnd = text.indexOf('`', codeStart + 1)
        assertEquals(listOf("\"y\""), quoted(text, listOf(codeStart..codeEnd)))
    }

    @Test
    fun `annotated string keeps code spans out and leaves text unchanged`() {
        val style = SpanStyle(color = Color.Red)
        val source = buildAnnotatedString {
            append("a “b” ")
            withAnnotation(QUOTE_HIGHLIGHT_SKIP_TAG, "") { append("“code") }
            append(" c”")
        }
        val result = source.withQuotedTextHighlight(style)
        assertEquals(source.text, result.text)
        val styled = result.spanStyles.filter { it.item == style }.map { result.text.substring(it.start, it.end) }
        assertEquals(listOf("“b”"), styled)
    }

    @Test
    fun `disabled highlight returns the same instance`() {
        val source = buildAnnotatedString { append("“b”") }
        assertSame(source, source.withQuotedTextHighlight(null))
    }
}
