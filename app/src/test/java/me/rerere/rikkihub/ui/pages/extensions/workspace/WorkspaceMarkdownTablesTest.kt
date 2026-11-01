package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceMarkdownTablesTest {
    private fun apply(source: String, edit: WorkspaceSourceEdit) =
        source.substring(0, edit.start) + edit.replacement + source.substring(edit.end)

    @Test fun `row split handles outer pipes escapes and code spans`() {
        assertEquals(listOf("a", "b"), WorkspaceMarkdownTables.splitRow("| a | b |"))
        assertEquals(listOf("a", "b"), WorkspaceMarkdownTables.splitRow("a|b"))
        assertEquals(listOf("x \\| y", "`p | q`"), WorkspaceMarkdownTables.splitRow("| x \\| y | `p | q` |"))
        assertEquals(listOf("", "z"), WorkspaceMarkdownTables.splitRow("|  | z |"))
    }

    @Test fun `alignment and ragged rows follow GFM`() {
        val table = checkNotNull(WorkspaceMarkdownTables.parse("| a | b | c | d |\n|---|:--|:-:|--:|\n| 1 |\n| 1 | 2 | 3 | 4 | 5 |\n"))
        assertEquals(listOf(WorkspaceTableAlign.NONE, WorkspaceTableAlign.LEFT, WorkspaceTableAlign.CENTER, WorkspaceTableAlign.RIGHT), table.aligns)
        assertEquals(listOf("1", "", "", ""), table.rows[0])
        assertEquals(listOf("1", "2", "3", "4"), table.rows[1])
        assertNull(WorkspaceMarkdownTables.parse("| a | b |\n| c | d |"))
        assertNull(WorkspaceMarkdownTables.parse("| a | b |\n|---|"))
    }

    @Test fun `display width counts CJK and emoji as two columns`() {
        assertEquals(3, WorkspaceMarkdownTables.displayWidth("abc"))
        assertEquals(4, WorkspaceMarkdownTables.displayWidth("中文"))
        assertEquals(2, WorkspaceMarkdownTables.displayWidth("😀"))
    }

    @Test fun `format pads every column to its display width and keeps alignment`() {
        val table = checkNotNull(WorkspaceMarkdownTables.parse("|名称|n|\n|:-:|--:|\n|a|12345|"))
        assertEquals(
            "| 名称 |     n |\n" +
                "| :--: | ----: |\n" +
                "|  a   | 12345 |",
            WorkspaceMarkdownTables.format(table),
        )
    }

    @Test fun `table command formats the table under the caret as one edit`() {
        val source = "intro\n\n|a|bb|\n|-|-|\n|ccc|d|\n\nafter"
        val caret = source.indexOf("ccc")
        val edit = workspaceMarkdownCommand(source, caret, caret, WorkspaceMarkdownCommand.TABLE)
        val result = apply(source, edit)
        assertEquals("intro\n\n| a   | bb  |\n| --- | --- |\n| ccc | d   |\n\nafter", result)
        assertEquals(result.indexOf("ccc"), edit.selectionStart)
        // Idempotent.
        val again = workspaceMarkdownCommand(result, edit.selectionStart, edit.selectionStart, WorkspaceMarkdownCommand.TABLE)
        assertEquals(result, apply(result, again))
    }

    @Test fun `table command inserts a template as its own block`() {
        val source = "para\nnext"
        val caret = source.indexOf("\n")
        val edit = workspaceMarkdownCommand(source, caret, caret, WorkspaceMarkdownCommand.TABLE)
        val result = apply(source, edit)
        assertEquals("para\n\n" + WorkspaceMarkdownTables.template() + "\n\nnext", result)
        assertEquals("Column 1", result.substring(edit.selectionStart, edit.selectionEnd))
        assertEquals(1, WorkspaceMarkdownAnalyzer.analyze(result).tables.size)

        val crlf = workspaceMarkdownCommand("", 0, 0, WorkspaceMarkdownCommand.TABLE).replacement
        assertEquals(WorkspaceMarkdownTables.template(), crlf)
    }

    @Test fun `analyzer reports table ranges header and delimiter but not tables inside code`() {
        val source = "```\n| a | b |\n|---|---|\n```\n\n| h | i |\n|:--|--:|\n| x | y |\n"
        val analysis = WorkspaceMarkdownAnalyzer.analyze(source)
        val range = analysis.tables.single()
        assertTrue(source.substring(range.first, range.last + 1).startsWith("| h | i |"))
        assertTrue(analysis.spans.any { it.style == WorkspaceMarkdownStyle.TABLE_HEADER && source.indexOf("h |") in it.start until it.end })
        assertTrue(analysis.spans.any { it.style == WorkspaceMarkdownStyle.TABLE_DELIMITER && source.indexOf(":--") in it.start until it.end })
    }

    @Test fun `preview splits source blocks around tables`() {
        val source = "# Title\n\n| h | i |\n|---|---|\n| **b** | [site](https://example.com) |\n\ntail"
        val blocks = WorkspaceMarkdownPreviewModel.blocks(WorkspaceMarkdownAnalyzer.analyze(source))
        assertEquals(3, blocks.size)
        val first = blocks[0] as WorkspacePreviewBlock.Source
        assertEquals("# Title", source.substring(first.start, first.end))
        val table = blocks[1] as WorkspacePreviewBlock.Table
        assertEquals(listOf("h", "i"), table.header.map { it.text })
        val bold = table.rows[0][0]
        assertEquals("b", bold.text)
        assertTrue(bold.spans.any { it.style == WorkspaceMarkdownStyle.BOLD && it.start == 0 && it.end == 1 })
        val link = table.rows[0][1]
        assertEquals("site", link.text)
        assertEquals("https://example.com", link.links.single().url)
        val last = blocks[2] as WorkspacePreviewBlock.Source
        assertEquals("tail", source.substring(last.start, last.end))
    }

    @Test fun `cell rendering unescapes pipes and keeps code literal`() {
        assertEquals("a | b", WorkspaceMarkdownPreviewModel.renderCell("a \\| b").text)
        val code = WorkspaceMarkdownPreviewModel.renderCell("`x*y*`")
        assertEquals("x*y*", code.text)
        assertTrue(code.spans.any { it.style == WorkspaceMarkdownStyle.CODE })
        assertFalse(code.spans.any { it.style == WorkspaceMarkdownStyle.ITALIC })
        val image = WorkspaceMarkdownPreviewModel.renderCell("![alt](https://example.com/x.png)")
        assertEquals("alt", image.text)
        assertTrue(image.links.isEmpty())
    }
}
