package me.rerere.rikkihub.web.routes

import me.rerere.rikkihub.web.BadRequestException
import me.rerere.workspace.WorkspaceFilesScope

/** Largest image the chat page may pull out of a workspace. */
internal const val MAX_ROOTFS_IMAGE_BYTES = 32L * 1024 * 1024

/** Formats a browser draws in an `<img>`; the type is chosen here, never taken from the file. */
private val ROOTFS_IMAGE_TYPES = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "avif" to "image/avif",
    "ico" to "image/x-icon",
    "svg" to "image/svg+xml",
)

internal data class RootfsImageRequest(
    /** Absolute guest path, the way the model's file tools write it. */
    val path: String,
    /** FILES-relative directory the conversation is confined to, null when it is not. */
    val filesScope: String?,
    val contentType: String,
) {
    /** SVG is an active document: it must never be shown by navigating to it, only inside an `<img>`. */
    val forceAttachment: Boolean get() = contentType == "image/svg+xml"
}

/**
 * Validates the query of `GET /workspaces/{id}/rootfs-file`. Traversal and files outside [scope] are
 * refused later by the workspace itself; this only rejects what can never be a picture to show.
 */
internal fun parseRootfsImageRequest(path: String?, scope: String?): RootfsImageRequest {
    val raw = path.orEmpty()
    if (raw.isBlank()) throw BadRequestException("Missing file path")
    if (!raw.startsWith("/") || '\u0000' in raw) throw BadRequestException("The path must be an absolute workspace path")
    val extension = raw.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    val contentType = ROOTFS_IMAGE_TYPES[extension] ?: throw BadRequestException("Only image files can be shown")
    val filesScope = try {
        WorkspaceFilesScope.normalize(scope?.takeIf { it.isNotBlank() })
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid working directory")
    }
    return RootfsImageRequest(raw, filesScope, contentType)
}

/** Changes whenever the picture is rewritten, so a reload shows the new one and an untouched one is a 304. */
internal fun rootfsImageEtag(sizeBytes: Long, updatedAt: Long): String = "\"$sizeBytes-$updatedAt\""

/** `If-None-Match` handling: any listed tag (weak or strong) equal to [etag], or `*`. */
internal fun rootfsImageEtagMatches(ifNoneMatch: String?, etag: String): Boolean {
    if (ifNoneMatch.isNullOrBlank()) return false
    return ifNoneMatch.split(',')
        .map { it.trim().removePrefix("W/") }
        .any { it == "*" || it == etag }
}
