package me.rerere.rikkihub.data.ai.tools

import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspacePathInfo

internal const val STR_REPLACE_EDITOR_MAX_OUTPUT_CHARS = 16_000
internal const val STR_REPLACE_EDITOR_LIST_DEPTH = 2
internal const val STR_REPLACE_MAX_READ_BYTES = 8L * 1024 * 1024

private const val TRUNCATED_MESSAGE =
    "<response clipped><NOTE>To save on context only part of this file has been shown to you. You should retry this tool after you have searched inside the file with grep -n in order to find the line numbers of what you are looking for.</NOTE>"

internal val STR_REPLACE_EDITOR_HIDDEN_NAMES = setOf("node_modules", "__pycache__")

internal enum class StrReplaceEditorCommand {
    VIEW,
    CREATE,
    STR_REPLACE,
    INSERT,
    ;

    companion object {
        fun parse(raw: String?): StrReplaceEditorCommand {
            val value = raw?.trim().orEmpty()
            return entries.firstOrNull { it.wireName == value }
                ?: error(
                    "Unknown command `$value`. Allowed options are: `view`, `create`, `str_replace`, `insert`.",
                )
        }
    }

    val wireName: String
        get() = when (this) {
            VIEW -> "view"
            CREATE -> "create"
            STR_REPLACE -> "str_replace"
            INSERT -> "insert"
        }
}

internal data class StrReplaceEditorArgs(
    val command: StrReplaceEditorCommand,
    val path: String,
    val fileText: String? = null,
    val insertLine: Int? = null,
    val newStr: String? = null,
    val oldStr: String? = null,
    val viewRange: List<Int>? = null,
)

internal fun requireAbsoluteRootfsPath(path: String?): String {
    val trimmed = path?.replace('\\', '/')?.trim().orEmpty()
    require(trimmed.isNotEmpty()) { "path must be a non-empty string" }
    require(trimmed.startsWith("/")) {
        "The path $trimmed is not an absolute path, it should start with `/`. Maybe you meant /$trimmed?"
    }
    require(!trimmed.contains('\u0000')) { "path contains invalid character" }
    require(!trimmed.split('/').any { it == ".." }) { "path must not contain '..' segments" }
    return trimmed
}

internal fun maybeTruncateEditor(content: String, maxOutputChars: Int): String {
    if (content.length <= maxOutputChars) return content
    return content.take(maxOutputChars) + TRUNCATED_MESSAGE
}

internal fun formatFileView(
    path: String,
    content: String,
    maxOutputChars: Int,
    viewRange: List<Int>?,
): String {
    val allLines = content.split("\n")
    var lines = allLines
    var initialLine = 1
    var prompt = "Here's the content of $path with line numbers (which has a total of ${allLines.size} lines)"
    if (viewRange != null) {
        require(viewRange.size == 2) { "Invalid `view_range`. It should be a list of two integers." }
        val requestedInitialLine = viewRange[0]
        val requestedFinalLine = viewRange[1]
        initialLine = requestedInitialLine
        if (initialLine < 1 || initialLine > allLines.size) {
            error(
                "Invalid `view_range`: [${viewRange.joinToString(", ")}]. Its first element `$initialLine` should be within the range of lines of the file: [1, ${allLines.size}]",
            )
        }
        if (requestedFinalLine > allLines.size) {
            error(
                "Invalid `view_range`: [${viewRange.joinToString(", ")}]. Its second element `$requestedFinalLine` should be smaller than the number of lines in the file: `${allLines.size}`",
            )
        }
        if (requestedFinalLine != -1 && requestedFinalLine < initialLine) {
            error(
                "Invalid `view_range`: [${viewRange.joinToString(", ")}]. Its second element `$requestedFinalLine` should be larger or equal than its first `$initialLine`",
            )
        }
        lines = if (requestedFinalLine == -1) {
            allLines.drop(initialLine - 1)
        } else {
            allLines.subList(initialLine - 1, requestedFinalLine)
        }
        prompt += " with view_range=[$initialLine, $requestedFinalLine]"
    }
    val numbered = lines.mapIndexed { index, line ->
        "${(initialLine + index).toString().padStart(6, ' ')}  $line"
    }.joinToString("\n")
    return maybeTruncateEditor("$prompt:\n$numbered\n", maxOutputChars)
}

internal fun matchOffsets(content: String, search: String): List<Int> {
    val offsets = mutableListOf<Int>()
    var offset = 0
    while (true) {
        val match = content.indexOf(search, offset)
        if (match < 0) return offsets
        offsets += match
        offset = match + search.length
    }
}

internal fun lineNumbersAt(content: String, offsets: List<Int>): List<Int> {
    var line = 1
    var cursor = 0
    return offsets.map { offset ->
        while (cursor < offset) {
            if (content[cursor] == '\n') line += 1
            cursor += 1
        }
        line
    }
}

internal fun applyStrReplace(content: String, oldStr: String, newStr: String, path: String): String {
    require(oldStr.isNotEmpty()) { "Parameter `old_str` is empty for command: str_replace" }
    val offsets = matchOffsets(content, oldStr)
    val offset = offsets.firstOrNull()
        ?: error("No replacement was performed, old_str `$oldStr` did not appear verbatim in $path.")
    if (offsets.size > 1) {
        error(
            "No replacement was performed. Multiple occurrences of old_str `$oldStr` in lines [${lineNumbersAt(content, offsets).joinToString(", ")}]. Please ensure it is unique",
        )
    }
    return content.substring(0, offset) + newStr + content.substring(offset + oldStr.length)
}

internal fun applyInsert(content: String, insertLine: Int, newStr: String): String {
    val lines = content.split("\n")
    require(insertLine >= 0 && insertLine <= lines.size) {
        "Invalid `insert_line` parameter: $insertLine. It should be within the range of lines of the file: [0, ${lines.size}]"
    }
    return (lines.take(insertLine) + newStr.split("\n") + lines.drop(insertLine)).joinToString("\n")
}

internal fun shouldHideDirectoryEntry(name: String): Boolean =
    name.startsWith(".") || name in STR_REPLACE_EDITOR_HIDDEN_NAMES

internal fun formatDirectoryListing(path: String, rows: List<String>, maxOutputChars: Int): String {
    val listing = maybeTruncateEditor(rows.joinToString("\n") + "\n", maxOutputChars)
    return "Here're the files and directories up to 2 levels deep in $path, excluding hidden items, node_modules, and Python cache directories:\n$listing\n"
}

internal suspend fun executeStrReplaceEditor(
    workspaceId: String,
    args: StrReplaceEditorArgs,
    workspaceRepository: WorkspaceRepository,
    maxOutputChars: Int = STR_REPLACE_EDITOR_MAX_OUTPUT_CHARS,
    /** Working-directory confinement of this request; null = whole workspace. */
    filesScope: String? = null,
): String {
    val path = requireAbsoluteRootfsPath(args.path)
    return when (args.command) {
        StrReplaceEditorCommand.VIEW -> viewPath(workspaceId, path, args.viewRange, workspaceRepository, filesScope, maxOutputChars)
        StrReplaceEditorCommand.CREATE -> createFile(workspaceId, path, args.fileText, workspaceRepository, filesScope)
        StrReplaceEditorCommand.STR_REPLACE -> replaceInFile(
            workspaceId,
            path,
            args.oldStr,
            args.newStr,
            workspaceRepository,
            filesScope,
        )
        StrReplaceEditorCommand.INSERT -> insertInFile(
            workspaceId,
            path,
            args.insertLine,
            args.newStr,
            workspaceRepository,
            filesScope,
        )
    }
}

private suspend fun viewPath(
    workspaceId: String,
    path: String,
    viewRange: List<Int>?,
    workspaceRepository: WorkspaceRepository,
    filesScope: String?,
    maxOutputChars: Int,
): String {
    val info = workspaceRepository.statRootfs(workspaceId, path, filesScope = filesScope)
    if (!info.exists) {
        error("The path $path does not exist. Please provide a valid path.")
    }
    if (info.isDirectory) {
        require(viewRange == null) {
            "The `view_range` parameter is not allowed when `path` points to a directory."
        }
        return listDirectory(workspaceId, path, workspaceRepository, filesScope, maxOutputChars)
    }
    require(info.isFile) { "cannot view \"$path\": not a regular file or directory" }
    val content = workspaceRepository.readRootfsText(workspaceId, path, STR_REPLACE_MAX_READ_BYTES, filesScope = filesScope)
    return formatFileView(path, content, maxOutputChars, viewRange)
}

private suspend fun listDirectory(
    workspaceId: String,
    path: String,
    workspaceRepository: WorkspaceRepository,
    filesScope: String?,
    maxOutputChars: Int,
): String {
    val rows = mutableListOf("d\t$path")
    suspend fun visit(dir: String, depth: Int) {
        val entries = workspaceRepository.listRootfs(workspaceId, dir, filesScope = filesScope)
            .filter { !shouldHideDirectoryEntry(it.name) }
            .sortedBy { it.path }
        for (entry in entries) {
            val type = if (entry.isDirectory) "d" else "f"
            rows += "$type\t${entry.path}"
            if (entry.isDirectory && depth < STR_REPLACE_EDITOR_LIST_DEPTH) {
                visit(entry.path, depth + 1)
            }
        }
    }
    visit(path, 1)
    rows.sortBy { it.substringAfter('\t') }
    return formatDirectoryListing(path, rows, maxOutputChars)
}

private suspend fun createFile(
    workspaceId: String,
    path: String,
    fileText: String?,
    workspaceRepository: WorkspaceRepository,
    filesScope: String?,
): String {
    val content = fileText ?: error("Parameter `file_text` is required for command: create")
    val info = workspaceRepository.statRootfs(workspaceId, path, filesScope = filesScope)
    if (info.exists) {
        error("File already exists at: $path. Cannot overwrite files using command `create`.")
    }
    workspaceRepository.writeRootfsText(workspaceId, path, content, overwrite = false, filesScope = filesScope)
    return "New file created successfully at: $path"
}

private suspend fun replaceInFile(
    workspaceId: String,
    path: String,
    oldStr: String?,
    newStr: String?,
    workspaceRepository: WorkspaceRepository,
    filesScope: String?,
): String {
    val oldValue = oldStr ?: error("Parameter `old_str` is required for command: str_replace")
    require(oldValue.isNotEmpty()) { "Parameter `old_str` is empty for command: str_replace" }
    val newValue = newStr ?: ""
    val info = requireExistingFile(workspaceRepository.statRootfs(workspaceId, path, filesScope = filesScope), path, "str_replace")
    require(info.isFile) { "cannot edit \"$path\": not a regular file" }
    val before = workspaceRepository.readRootfsText(workspaceId, path, STR_REPLACE_MAX_READ_BYTES, filesScope = filesScope)
    val after = applyStrReplace(before, oldValue, newValue, path)
    workspaceRepository.writeRootfsText(workspaceId, path, after, overwrite = true, filesScope = filesScope)
    return "The file $path has been edited successfully."
}

private suspend fun insertInFile(
    workspaceId: String,
    path: String,
    insertLine: Int?,
    newStr: String?,
    workspaceRepository: WorkspaceRepository,
    filesScope: String?,
): String {
    require(insertLine != null) { "Parameter `insert_line` is required for command: insert" }
    val value = newStr ?: error("Parameter `new_str` is required for command: insert")
    val info = requireExistingFile(workspaceRepository.statRootfs(workspaceId, path, filesScope = filesScope), path, "insert")
    require(info.isFile) { "cannot insert into \"$path\": not a regular file" }
    val before = workspaceRepository.readRootfsText(workspaceId, path, STR_REPLACE_MAX_READ_BYTES, filesScope = filesScope)
    val after = applyInsert(before, insertLine, value)
    workspaceRepository.writeRootfsText(workspaceId, path, after, overwrite = true, filesScope = filesScope)
    return "The file $path has been edited successfully."
}

private fun requireExistingFile(info: WorkspacePathInfo, path: String, command: String): WorkspacePathInfo {
    if (!info.exists) {
        error("The path $path does not exist. Please provide a valid path.")
    }
    if (info.isDirectory && command != "view") {
        error("The path $path is a directory and only the `view` command can be used on directories")
    }
    return info
}
