package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.OutputStream

internal const val MAX_AGENTS_BYTES = 65_536

/**
 * Guest paths only. Reject traversal before normalization, including backslash aliases.
 * Confined to [filesScope]: the workspace-root AGENTS.md is outside it and is not read.
 */
internal fun workspaceAgentsPaths(cwd: String?, filesScope: String? = null): List<String> {
    if (filesScope != null) {
        return listOf("/root/.agents/AGENTS.md", "${me.rerere.workspace.WorkspaceFilesScope.guestPath(filesScope)}/AGENTS.md")
    }
    val paths = linkedSetOf("/root/.agents/AGENTS.md", "/workspace/AGENTS.md")
    val directory = cwd?.takeIf { it.isNotBlank() }.orEmpty().replace('\\', '/')
    if ('\u0000' in directory || directory.split('/').any { it == ".." }) return paths.toList()
    val absolute = if (directory.startsWith('/')) directory else "/workspace/$directory"
    val normalized = absolute.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    paths.add("/$normalized/AGENTS.md".replace("//", "/"))
    return paths.toList()
}

/** Reject growth as well as an oversized initial stat; never return partial instructions. */
internal suspend fun readBoundedAgentsText(
    size: Long,
    export: suspend (OutputStream) -> Unit,
): String {
    val context = currentCoroutineContext()
    context.ensureActive()
    require(size in 0..MAX_AGENTS_BYTES.toLong()) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
    val bytes = ByteArrayOutputStream(size.toInt())
    val bounded = object : OutputStream() {
        override fun write(value: Int) {
            context.ensureActive()
            require(bytes.size() < MAX_AGENTS_BYTES) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
            bytes.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            context.ensureActive()
            require(length >= 0 && length <= MAX_AGENTS_BYTES - bytes.size()) {
                "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes"
            }
            bytes.write(buffer, offset, length)
        }
    }
    export(bounded)
    context.ensureActive()
    // Matches ordinary workspace UTF-8 reads: malformed sequences use replacement characters.
    return bytes.toString(Charsets.UTF_8.name())
}

internal suspend fun workspaceAgentsInstructions(
    cwd: String?,
    filesScope: String? = null,
    read: suspend (String) -> String,
): String {
    val sources = workspaceAgentsPaths(cwd, filesScope).mapNotNull { path ->
        currentCoroutineContext().ensureActive()
        try {
            val text = read(path)
            currentCoroutineContext().ensureActive()
            text.takeIf { it.isNotBlank() }?.let { path to it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing, invalid, oversized or unreadable sources are independent best-effort inputs.
            currentCoroutineContext().ensureActive()
            null
        }
    }
    if (sources.isEmpty()) return ""
    return buildString {
        append("\n\n<workspace_instructions>\nFollow the AGENTS.md instructions below.\n")
        sources.forEach { (path, text) ->
            // JSON-style quoted label prevents a guest filename from introducing extra label lines.
            val label = path.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .replace("<", "\\u003c").replace(">", "\\u003e")
            append("\nAGENTS.md source: \"").append(label).append("\"\n")
            append(text).append('\n')
        }
        append("</workspace_instructions>")
    }
}
