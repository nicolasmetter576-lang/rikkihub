package me.rerere.rikkihub.ui.pages.extensions.workspace

internal enum class WorkspaceMarkdownCommand { HEADING, BOLD, ITALIC, LIST, QUOTE, TASK, LINK, CODE, TABLE }
internal data class WorkspaceSourceEdit(val start: Int, val end: Int, val replacement: String, val selectionStart: Int, val selectionEnd: Int)

/** Source commands never serialize an AST or normalize document line endings. */
internal fun workspaceMarkdownCommand(source: String, selectionStart: Int, selectionEnd: Int, command: WorkspaceMarkdownCommand): WorkspaceSourceEdit {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, source.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, source.length)
    val selected = source.substring(start, end)
    if (command == WorkspaceMarkdownCommand.TABLE) return workspaceTableCommand(source, start, end)
    val wrapper = when (command) {
        WorkspaceMarkdownCommand.BOLD -> "**" to "**"
        WorkspaceMarkdownCommand.ITALIC -> "*" to "*"
        WorkspaceMarkdownCommand.LINK -> "[" to "](https://)"
        WorkspaceMarkdownCommand.CODE -> if ('\n' in selected) {
            val newline = if ("\r\n" in source) "\r\n" else "\n"
            "```$newline" to "$newline```"
        } else "`" to "`"
        else -> null
    }
    if (wrapper != null) return WorkspaceSourceEdit(start, end, wrapper.first + selected + wrapper.second,
        start + wrapper.first.length, end + wrapper.first.length)
    val prefix = when (command) {
        WorkspaceMarkdownCommand.HEADING -> "### "
        WorkspaceMarkdownCommand.LIST -> "- "
        WorkspaceMarkdownCommand.QUOTE -> "> "
        WorkspaceMarkdownCommand.TASK -> "- [ ] "
        else -> error("Unknown source command")
    }
    val lineStart = source.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)) + 1
    // End at column zero does not include the following unselected line.
    val lineEnd = if (end > start && source.getOrNull(end - 1) == '\n') end - 1 else end
    val block = source.substring(lineStart, lineEnd)
    val replacement = prefix + block.replace("\n", "\n$prefix")
    return WorkspaceSourceEdit(lineStart, lineEnd, replacement, lineStart + prefix.length, lineStart + replacement.length)
}

/** Inside a table: align its columns (one undoable edit). Elsewhere: insert a 3x2 table template. */
private fun workspaceTableCommand(source: String, start: Int, end: Int): WorkspaceSourceEdit {
    val newline = if ("\r\n" in source) "\r\n" else "\n"
    WorkspaceMarkdownTables.findTableAt(source, start)?.let { range ->
        val table = WorkspaceMarkdownTables.parse(source.substring(range.first, range.last + 1))
        if (table != null) {
            val formatted = WorkspaceMarkdownTables.format(table, newline)
            // Keep the caret on the same row, just inside its first cell.
            val row = source.substring(range.first, start).count { it == '\n' }
            var rowStart = 0
            repeat(row) { rowStart = formatted.indexOf('\n', rowStart) + 1 }
            val caret = range.first + minOf(rowStart + 2, formatted.length)
            return WorkspaceSourceEdit(range.first, range.last + 1, formatted, caret, caret)
        }
    }
    val lineStart = source.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)) + 1
    // A table needs its own block: blank line before it unless the document or a blank line precedes.
    val previousLine = source.substring(0, lineStart).removeSuffix("\n").removeSuffix("\r").substringAfterLast('\n')
    val before = when {
        start != lineStart -> newline + newline
        lineStart == 0 || previousLine.isBlank() -> ""
        else -> newline
    }
    // ...and a blank line after it, or the following text would become extra table rows.
    val rest = source.substring(end)
    val nextLine = rest.substringAfter('\n', "").substringBefore('\n')
    val after = when {
        rest.substringBefore('\n').isNotBlank() -> newline + newline
        '\n' in rest && nextLine.isNotBlank() -> newline
        else -> ""
    }
    val template = WorkspaceMarkdownTables.template(newline)
    val replacement = before + template + after
    val label = start + before.length + 2
    return WorkspaceSourceEdit(start, end, replacement, label, label + "Column 1".length)
}
