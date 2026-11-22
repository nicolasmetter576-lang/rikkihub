package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceReaderTextTest {
    @Test
    fun chaptersSortByTheNumbersInTheirNames() {
        val names = listOf("第10章.md", "第2章.md", "第1章.md", "序.txt", "Chapter 3.txt", "chapter 03b.txt", "第02章 续.md")
        assertEquals(
            listOf("Chapter 3.txt", "chapter 03b.txt", "序.txt", "第1章.md", "第02章 续.md", "第2章.md", "第10章.md"),
            names.sortedWith(naturalNameOrder),
        )
    }

    @Test
    fun onlyMarkdownAndTextFilesAreChapters() {
        assertTrue(isReadableName("a.MD"))
        assertTrue(isReadableName("notes.text"))
        assertFalse(isReadableName("page.html"))
        assertFalse(isReadableName("README"))
    }

    @Test
    fun plainTextKeepsEveryLineAsWritten() {
        val lines = readingLines("# not a heading\r\n**kept**\n\n  indent", markdown = false)
        assertEquals(listOf("# not a heading", "**kept**", "", "  indent"), lines.map { it.text })
        assertTrue(lines.all { it.heading == 0 })
    }

    @Test
    fun markdownKeepsTheWordsAndDropsEditorMarks() {
        val lines = readingLines(
            """
            ## Chapter **One** ##
            She said **hello** to __you__ and ~~left~~.
            See [the map](https://x.example) and ![a cat](cat.png).

            ```kotlin
            val code = "**as is**"
            ```
            """.trimIndent(),
            markdown = true,
        )
        assertEquals(ReaderLine("Chapter One", heading = 2), lines[0])
        assertEquals("She said hello to you and left.", lines[1].text)
        assertEquals("See the map and a cat.", lines[2].text)
        assertEquals("", lines[3].text)
        assertEquals("val code = \"**as is**\"", lines[4].text)
        assertEquals(5, lines.size)
    }

    @Test
    fun `code ranges cover fenced lines and inline code, so quotes there stay plain`() {
        val lines = readingLines("用 `\"a\"` 和 **“真”**\n```\nprint(\"x\")\n```\n“外”", markdown = true)
        assertEquals(listOf("用 `\"a\"` 和 “真”", "print(\"x\")", "“外”"), lines.map { it.text })
        assertEquals(listOf("`\"a\"`"), lines[0].code.map { lines[0].text.substring(it) })
        assertEquals(listOf(lines[1].text.indices), lines[1].code)
        assertEquals(emptyList<IntRange>(), lines[2].code)
        assertEquals(emptyList<IntRange>(), readingLines("`x` “y”", markdown = false).single().code)
    }
}
