package me.rerere.rikkihub.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import me.rerere.rikkihub.data.files.FileFolders
import java.io.File

/**
 * `/api/files/path/{...}` admission policy.
 *
 * The endpoint used to serve any file under the app `filesDir` — including
 * datastore logs, workspaces, restore staging and private subtrees. It is now
 * restricted to explicit, flat, managed resource subtrees:
 *
 * - `upload/<name>`   — managed upload attachments (draft/chat attachments)
 * - `images/<name>`   — managed message images
 * - `fonts/<name>`    — imported chat fonts
 * - `subagent_runs/<id>.json|.txt` — persisted subagent run logs
 * - `tool_outputs/<id>.json|.txt`  — persisted tool outputs / legacy run logs
 *
 * Everything else (datastore, workspaces, skills, builtin_skills, restore
 * staging, arbitrary nesting, `..` traversal, absolute paths, dotfiles,
 * symlink escapes) is denied with a stable machine code before touching disk.
 * Active document types (HTML/SVG/XML/script) are never served inline for
 * execution on the admin origin — they are forced to attachment downloads by
 * [webFileServePlan].
 *
 * Pure JVM code (java.io.File only) so the policy is unit-testable.
 */

class WebFileForbiddenException : ApiException(
    message = "File path is not a managed web resource",
    status = HttpStatusCode.Forbidden,
    errorCode = WEB_ERROR_FILE_FORBIDDEN,
)

class WebFileSymlinkDeniedException : ApiException(
    message = "File path resolves through a link",
    status = HttpStatusCode.Forbidden,
    errorCode = WEB_ERROR_FILE_SYMLINK_DENIED,
)

class WebFileNotFoundException : ApiException(
    message = "File not found",
    status = HttpStatusCode.NotFound,
    errorCode = WEB_ERROR_FILE_NOT_FOUND,
)

/** Single-segment file name: no separators, no dot-only or dot-prefixed names, no control chars. */
private val WEB_FILE_SIMPLE_NAME = Regex("[^/\\\\%\\u0000-\\u001F\\u007F]+")

/** Subtrees where any flat file name is acceptable (managed attachments / images / fonts). */
private val WEB_FILE_FLAT_DIRS = setOf(
    FileFolders.UPLOAD,
    FileFolders.FONTS,
    "images",
)

/** Subtrees restricted to flat `*.json`/`*.txt` artifacts (run logs, tool outputs). */
private val WEB_FILE_LOG_DIRS = setOf(
    FileFolders.SUBAGENT_RUNS,
    FileFolders.TOOL_OUTPUTS,
)

private val WEB_FILE_LOG_EXTENSIONS = setOf("json", "txt")

/** All subtrees served by the path endpoint. */
val WEB_FILE_ALLOWED_DIRS: Set<String> = WEB_FILE_FLAT_DIRS + WEB_FILE_LOG_DIRS

private fun isWebFileNameAllowed(dir: String, name: String): Boolean {
    if (name.isEmpty() || name.startsWith(".") || name.contains("..")) return false
    if (!WEB_FILE_SIMPLE_NAME.matches(name)) return false
    return when (dir) {
        in WEB_FILE_LOG_DIRS -> name.substringAfterLast('.', "").lowercase() in WEB_FILE_LOG_EXTENSIONS
        in WEB_FILE_FLAT_DIRS -> true
        else -> false
    }
}

/**
 * Resolve a client-supplied relative path to a regular file inside `filesDir`,
 * enforcing the managed-subtree allowlist and rejecting symlink/alias escapes
 * (both the leaf and the directory itself must not resolve through links).
 *
 * The returned path is the normalized `dir/name` relative path (suitable for
 * managed-file DB lookup); the file is canonicalized.
 *
 * @throws WebFileForbiddenException outside the allowlist / malformed
 * @throws WebFileSymlinkDeniedException resolved through a symlink
 * @throws WebFileNotFoundException does not exist or is not a regular file
 */
fun resolveManagedWebFile(filesDir: File, relativePath: String): Pair<String, File> {
    // Reject absolute/rooted inputs BEFORE separator normalization — a
    // leading '/' or '\\' must not be silently rewritten into a managed
    // relative path (e.g. "/upload/x", "\\upload\\x", "//host/share").
    val trimmed = relativePath.trim()
    if (trimmed.isEmpty() || trimmed.startsWith('/') || trimmed.startsWith('\\')) {
        throw WebFileForbiddenException()
    }
    val normalized = trimmed.replace('\\', '/').trim('/')
    if (normalized.isEmpty() || normalized.contains('\u0000')) throw WebFileForbiddenException()

    val segments = normalized.split('/')
    if (segments.size != 2) throw WebFileForbiddenException()
    val dir = segments[0]
    val name = segments[1]
    if (dir !in WEB_FILE_ALLOWED_DIRS || !isWebFileNameAllowed(dir, name)) {
        throw WebFileForbiddenException()
    }

    val canonicalFilesDir = runCatching { filesDir.canonicalFile }.getOrNull()
        ?: throw WebFileForbiddenException()
    val dirFile = File(canonicalFilesDir, dir)

    // The allowlisted directory itself must be a real subdirectory — not a symlink
    // or a path that canonicalizes outside filesDir.
    val canonicalDir = runCatching { dirFile.canonicalFile }.getOrNull()
        ?: throw WebFileForbiddenException()
    if (canonicalDir.parentFile != canonicalFilesDir || canonicalDir != dirFile.absoluteFile) {
        throw WebFileSymlinkDeniedException()
    }

    val leaf = File(canonicalDir, name)
    val canonicalLeaf = runCatching { leaf.canonicalFile }.getOrNull()
        ?: throw WebFileSymlinkDeniedException()
    if (canonicalLeaf.parentFile != canonicalDir) throw WebFileSymlinkDeniedException()
    // Reject symlink aliases even when they resolve back inside the same dir.
    if (leaf.exists() && canonicalLeaf != leaf.absoluteFile) throw WebFileSymlinkDeniedException()

    if (!canonicalLeaf.exists() || !canonicalLeaf.isFile) throw WebFileNotFoundException()

    return "$dir/$name" to canonicalLeaf
}

// ---- Response policy ----

/** Content types that can execute script/markup in the admin origin — must download. */
private val ACTIVE_WEB_MIME_TYPES = setOf(
    "text/html",
    "application/xhtml+xml",
    "image/svg+xml",
    "text/xml",
    "application/xml",
    "text/javascript",
    "application/javascript",
    "text/ecmascript",
    "application/ecmascript",
    "text/php",
    "text/mhtml",
    "application/mhtml",
)

/** How a resolved file may be delivered. */
data class WebFileServePlan(
    val contentType: ContentType,
    /** Non-null → respond with `Content-Disposition: attachment`. */
    val forceAttachment: Boolean,
)

/**
 * Decide the effective content type + disposition for a managed file.
 * Managed DB mime wins when present (on-disk names are UUID-only); otherwise
 * fall back to the extension table. Active/executable types are demoted to
 * octet-stream and force `Content-Disposition: attachment` so browsers can
 * never execute them in the admin origin.
 */
fun webFileServePlan(fileName: String, managedMime: String?): WebFileServePlan {
    val resolved = managedMime?.trim()?.takeIf { it.isNotEmpty() }
        ?: guessWebFileMime(fileName)
    // Strip parameters (charset etc.) before the active-type check so a
    // managed mime like "image/svg+xml; charset=utf-8" can't slip through.
    val baseMime = resolved.substringBefore(';').trim().lowercase()
    if (baseMime in ACTIVE_WEB_MIME_TYPES) {
        return WebFileServePlan(
            contentType = ContentType.Application.OctetStream,
            forceAttachment = true,
        )
    }
    val parsed = runCatching { ContentType.parse(resolved) }
        .getOrDefault(ContentType.Application.OctetStream)
    return WebFileServePlan(contentType = parsed, forceAttachment = false)
}

/**
 * Serve a managed file with a safe content type. Executable/active document
 * types (HTML, SVG, XML, scripts) are demoted to octet-stream and force a
 * `Content-Disposition: attachment` download so they can never render inline
 * in the admin origin. `X-Content-Type-Options: nosniff` is applied globally
 * to /api responses by `installWebApiBoundary`.
 */
suspend fun ApplicationCall.respondManagedWebFile(file: File, managedMime: String?) {
    val plan = webFileServePlan(file.name, managedMime)
    if (plan.forceAttachment) {
        response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.toString())
    }
    respond(LocalFileContent(file, plan.contentType))
}

/** Extension-based mime fallback for files without a managed DB row. */
fun guessWebFileMime(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> ContentType.Image.JPEG.toString()
        "png" -> ContentType.Image.PNG.toString()
        "gif" -> ContentType.Image.GIF.toString()
        "webp" -> ContentType("image", "webp").toString()
        "svg" -> ContentType.Image.SVG.toString()
        "pdf" -> ContentType.Application.Pdf.toString()
        "json" -> ContentType.Application.Json.toString()
        "txt", "log", "conf", "config", "diff", "patch" -> ContentType.Text.Plain.toString()
        "md" -> ContentType("text", "markdown").toString()
        "html", "htm" -> ContentType.Text.Html.toString()
        "css" -> ContentType.Text.CSS.toString()
        "xml" -> ContentType.Text.Xml.toString()
        "csv" -> ContentType.Text.CSV.toString()
        "yml", "yaml" -> ContentType("text", "yaml").toString()
        "ini", "toml", "env" -> ContentType.Text.Plain.toString()
        "go", "py", "rs", "java", "kt", "c", "cpp", "h", "cs", "rb", "php",
        "swift", "dart", "scala", "sh", "bash", "zsh", "js", "ts", "tsx",
        "jsx", "vue", "scss", "less", "sql", "graphql", "proto" -> ContentType.Text.Plain.toString()
        "mp4" -> ContentType("video", "mp4").toString()
        "webm" -> ContentType("video", "webm").toString()
        "mp3" -> ContentType.Audio.MPEG.toString()
        "wav" -> ContentType("audio", "wav").toString()
        "ogg" -> ContentType("audio", "ogg").toString()
        else -> ContentType.Application.OctetStream.toString()
    }
