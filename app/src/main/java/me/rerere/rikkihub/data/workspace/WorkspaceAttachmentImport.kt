package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.InputStream
import java.net.URI

/** Part metadata key: guest path of the attachment's copy inside the confined working directory. */
const val WORKSPACE_PATH_METADATA_KEY = "workspacePath"

/** Guest path recorded by [copyAttachmentsIntoWorkspaceScope], or null. */
val UIMessagePart.workspacePath: String?
    get() = metadata?.get(WORKSPACE_PATH_METADATA_KEY)?.jsonPrimitive?.contentOrNull

/**
 * A confined conversation cannot see `/upload`, so its local file attachments
 * (documents and images) are copied into the root of the selected directory when the message is
 * sent. [import] stores one file and returns its FILES-relative path (renaming on conflict).
 * Each part is copied at most once: a part that already records a path is left alone, so a copy
 * the user deletes later is not put back. A failed copy keeps the part unchanged (no path given).
 */
suspend fun copyAttachmentsIntoWorkspaceScope(
    parts: List<UIMessagePart>,
    import: suspend (fileName: String, input: InputStream) -> String,
): List<UIMessagePart> = parts.map { part ->
    if (part.workspacePath != null) return@map part
    val (url, name) = when (part) {
        is UIMessagePart.Document -> part.url to part.fileName
        is UIMessagePart.Image -> part.url to null
        else -> return@map part
    }
    val file = localFile(url) ?: return@map part
    val fileName = (name ?: file.name).substringAfterLast('/').substringAfterLast('\\')
        .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: file.name
    val relative = try {
        file.inputStream().use { input -> import(fileName, input) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return@map part
    }
    part.withWorkspacePath("/workspace/$relative")
}

private fun localFile(url: String): File? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "file") return null
    File(uri).takeIf { it.isFile }
}.getOrNull()

private fun UIMessagePart.withWorkspacePath(path: String): UIMessagePart {
    val metadata = buildJsonObject {
        this@withWorkspacePath.metadata?.forEach { (key, value) -> put(key, value) }
        put(WORKSPACE_PATH_METADATA_KEY, JsonPrimitive(path))
    }
    return when (this) {
        is UIMessagePart.Document -> copy(metadata = metadata)
        is UIMessagePart.Image -> copy(metadata = metadata)
        else -> this
    }
}
