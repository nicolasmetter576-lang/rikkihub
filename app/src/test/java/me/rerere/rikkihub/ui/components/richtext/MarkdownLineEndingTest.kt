package me.rerere.rikkihub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** With CRLF the closing code fence was not recognized and everything after it rendered as code. */
class MarkdownLineEndingTest {
    private val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))

    private fun topLevelTypes(text: String): List<String> =
        parser.buildMarkdownTreeFromString(text).children
            .map { it.type.name }
            .filter { it != "EOL" && it != "WHITE_SPACE" }

    private fun ASTNode.paragraphTexts(text: String): List<String> =
        children.filter { it.type == MarkdownElementTypes.PARAGRAPH }
            .map { text.substring(it.startOffset, it.endOffset) }

    @Test
    fun `crlf is normalized to lf`() {
        assertEquals("a\nb\nc\n", normalizeMarkdownLineEndings("a\r\nb\rc\r\n"))
        assertEquals("plain\ntext", normalizeMarkdownLineEndings("plain\ntext"))
    }

    @Test
    fun `code fence closes and the following paragraph is not code after normalization`() {
        val crlf = "```kotlin\r\nval a = 1\r\n```\r\nafter the code\r\n"

        val normalized = normalizeMarkdownLineEndings(crlf)
        assertFalse('\r' in normalized)
        val tree = parser.buildMarkdownTreeFromString(normalized)
        assertEquals(listOf("CODE_FENCE", "PARAGRAPH"), topLevelTypes(normalized))
        assertEquals(listOf("after the code"), tree.paragraphTexts(normalized))
    }
}
