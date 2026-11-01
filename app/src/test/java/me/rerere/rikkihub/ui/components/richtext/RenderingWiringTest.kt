package me.rerere.rikkihub.ui.components.richtext

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** source guards only. Device selection, alpha composition and typography remain unverified. */
class RenderingWiringTest {
    private fun source(relative: String): String {
        val base = File("src/main/java/me/rerere/rikkihub/ui/components")
            .takeIf { it.isDirectory } ?: File("app/src/main/java/me/rerere/rikkihub/ui/components")
        return File(base, relative).readText()
    }

    @Test fun `thought nodes use per step segments not a second card fill or Clear layer`() {
        val chain = source("ui/ChainOfThought.kt")
        assertTrue(chain.contains("chainOfThoughtHeaderSegments(size.height, 10.dp.toPx(), isFirst, isLast)"))
        assertTrue(chain.contains("val isFirst = index == 0"))
        assertTrue(chain.contains("val isLast = index == visibleSteps.lastIndex"))
        assertTrue(chain.contains("if (!isLast)"))
        assertFalse(chain.contains("LocalCardColor"))
        assertFalse(chain.contains("BlendMode.Clear"))
        assertFalse(chain.contains("CompositingStrategy.Offscreen"))
    }

    @Test fun `controlled content and adaptive collapse retain fork contracts`() {
        val chain = source("ui/ChainOfThought.kt")
        assertTrue(chain.contains("val shouldFillCollapseControlWidth = expanded || !collapsedAdaptiveWidth"))
        assertTrue(chain.contains("visibleSteps.fastForEachIndexed"))
        assertTrue(chain.contains("contentVisible = expanded"))
        assertTrue(chain.contains("if (contentVisible && hasContent)"))
        assertTrue(chain.contains("onExpandedChange(!expanded)"))
        val chat = source("message/ChatMessage.kt")
        assertTrue(chat.contains("ThinkingStep.ServerToolStep"))
        assertTrue(chat.contains("collapsedAdaptiveWidth = isReasoningOnlyBlock"))
    }

    @Test fun `inline code disables ligatures in both Text and SpanStyle paths`() {
        val markdown = source("richtext/Markdown.kt")
        val flags = "fontFeatureSettings = \"'calt' 0, 'liga' 0, 'clig' 0\""
        assertEquals(2, Regex(Regex.escape(flags)).findAll(markdown).count())
        assertTrue(markdown.substringAfter("MarkdownElementTypes.CODE_SPAN ->")
            .substringBefore("MarkdownElementTypes.CODE_BLOCK ->").contains(flags))
        assertTrue(markdown.substringAfter("node.type == MarkdownElementTypes.CODE_SPAN ->")
            .substringBefore("node.type == GFMElementTypes.INLINE_MATH ->").contains(flags))
    }

    @Test fun `wrapped and default line numbers disable selection without disabling code`() {
        val highlight = source("richtext/HighlightCodeBlock.kt")
        assertTrue(highlight.contains("private fun AsyncCodeHighlightText("))
        val wrapped = highlight.substringAfter("private fun CodeBlockWithLineNumbersWrapped(")
            .substringBefore("private fun CodeBlockDefault(")
        val default = highlight.substringAfter("private fun CodeBlockDefault(").substringBefore("private fun CodeBlockHeader(")
        assertTrue(wrapped.contains("DisableSelection {"))
        assertTrue(default.contains("DisableSelection {"))
        assertTrue(wrapped.contains("SelectionContainer {"))
        assertTrue(default.contains("SelectionContainer {\n            AsyncCodeHighlightText("))
        assertEquals(2, Regex("DisableSelection \\{").findAll(highlight).count())
    }
}
