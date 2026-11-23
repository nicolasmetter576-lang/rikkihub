package me.rerere.rikkihub.data.ai.book

import me.rerere.rikkihub.data.ai.tools.BookPromptLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookAliasesTest {
    @Test
    fun `only names that occur in the book are kept`() {
        val output = """
            ```json
            {"alias":"小林","to":"小苏"}
            - {"alias":"林哥","to":"苏哥"}
            {"alias":"林婉清","to":"苏婉清"}
            {"alias":"小林","to":"重复"}
            {"alias":"","to":"x"}
            {"alias":"没出现","to":"x"}
            {"alias":"半截
            ```
        """.trimIndent()
        val counts = mapOf("小林" to 3, "林哥" to 1)
        assertEquals(
            listOf(BookAlias("小林", "小苏", 3), BookAlias("林哥", "苏哥", 1)),
            BookAliasFinder.parse(output, "林婉清") { counts[it] ?: 0 },
        )
    }

    @Test
    fun `snippets stay inside the text, merge neighbours and are sampled`() {
        val text = "林婉清" + "字".repeat(20) + "林婉清" + "字".repeat(500) + "林婉清"
        assertEquals(2, BookAliasFinder.snippets(listOf(text), "林婉清").size)
        val long = List(500) { "字".repeat(300) + "林婉清" }.joinToString("")
        assertEquals(60, BookAliasFinder.snippets(listOf(long), "林婉清").size)
        assertTrue(BookAliasFinder.snippets(listOf("没有"), "林婉清").isEmpty())
    }

    @Test
    fun `the prompt follows the language and sends snippets only`() {
        val zh = BookAliasFinder.prompt(BookPromptLanguage.ZH, "林婉清", "苏婉清", listOf("片段一"))
        assertTrue(zh.contains("片段一") && zh.contains("「林婉清」"))
        assertFalse(Regex("\\p{IsHan}").containsMatchIn(BookAliasFinder.prompt(BookPromptLanguage.EN, "Lin", "Su", listOf("passage"))))
    }
}
