package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.workspace.WorkspaceFilesScope
import me.rerere.workspace.WorkspaceManager
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

/**
 * 解析附件在 CLI 容器内的路径（纯 JVM，无 Android Context 依赖）：
 * - 已复制到工作区的附件：未限定目录时要求合法 `/workspace/<rel>`；限定到 [filesScope] 时必须属于该目录，越界直接返回 null；
 * - 否则仅在未限定目录且挂载了 `/upload`（[exposeUpload] = true）时，把 `<filesDir>/<uploadDirName>/<fileName>` 映射为 `/upload/<fileName>`。
 */
internal fun resolveCliGuestPath(
    part: UIMessagePart,
    filesScope: String?,
    exposeUpload: Boolean,
    uploadDirName: String = FileFolders.UPLOAD,
): String? {
    val scopeNormalized = if (filesScope == null) {
        null
    } else {
        runCatching { WorkspaceFilesScope.normalize(filesScope) }.getOrElse { return null }
    }

    val workspacePath = part.workspacePath
    if (workspacePath != null) {
        val workspacePrefix = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/"
        if (!workspacePath.startsWith(workspacePrefix)) return null
        val rel = runCatching { WorkspaceFilesScope.normalize(workspacePath) }.getOrNull() ?: return null
        val normalizedGuestPath = "$workspacePrefix$rel"
        return when {
            scopeNormalized == null -> normalizedGuestPath
            WorkspaceFilesScope.contains(scopeNormalized, rel) -> normalizedGuestPath
            else -> null
        }
    }

    if (scopeNormalized != null || !exposeUpload || uploadDirName.isBlank()) return null

    val url = when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Document -> part.url
        is UIMessagePart.Video -> part.url
        is UIMessagePart.Audio -> part.url
        else -> return null
    }
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "file") return null
    val rawPath = uri.path ?: return null
    if (rawPath.endsWith('/') || '\u0000' in rawPath || '\\' in rawPath) return null
    if (rawPath.split('/').any { it == ".." || it == "." }) return null

    val file = runCatching { File(uri) }.getOrNull() ?: return null
    if (file.parentFile?.name != uploadDirName) return null
    val fileName = file.name
    if (fileName.isBlank() || fileName == "." || fileName == ".." || '/' in fileName || '\\' in fileName) {
        return null
    }
    return "/upload/$fileName"
}

/**
 * 校验本地 `file://` URI 并解析其规范常规文件，要求严格位于任一 [allowedDirs] 子路径下（防 symlink 逃逸与路径穿越）。
 */
internal fun resolveSafeLocalFile(url: String, allowedDirs: List<File>): File? {
    if (allowedDirs.isEmpty()) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "file") return null
    val canonical = runCatching { File(uri).canonicalFile }.getOrNull() ?: return null
    if (!canonical.isFile) return null
    val canonicalPath = canonical.toPath().normalize()
    val inside = allowedDirs.any { dir ->
        val dirPath = runCatching { dir.canonicalFile.toPath().normalize() }.getOrNull() ?: return@any false
        canonicalPath != dirPath && canonicalPath.startsWith(dirPath)
    }
    return canonical.takeIf { inside }
}
