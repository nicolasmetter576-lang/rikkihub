package me.rerere.rikkihub.data.workspace

import me.rerere.workspace.WorkspaceFilesScope
import me.rerere.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream

/**
 * Where the model's file paths point for one conversation: the bound workspace, the directory the
 * model is confined to (null when it is not) and the guest directory that relative references
 * are resolved against.
 */
data class WorkspaceImageContext(
    val workspaceId: String,
    val filesScope: String?,
    val cwd: String,
)

/**
 * Context for showing images the model references, or null when the conversation has no usable workspace.
 * An unusable working directory yields null instead of a wider view, like the model's own file tools.
 */
fun workspaceImageContext(workspaceId: String?, workspaceCwd: String?, scoped: Boolean): WorkspaceImageContext? {
    if (workspaceId == null) return null
    val directory = try {
        WorkspaceFilesScope.normalize(workspaceCwd)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return WorkspaceImageContext(
        workspaceId = workspaceId,
        filesScope = if (scoped) directory else null,
        cwd = directory?.let(WorkspaceFilesScope::guestPath) ?: WorkspaceManager.ROOTFS_WORKSPACE_DIR,
    )
}

/** Turns the image address in a message into a guest path the model's file tools would understand. */
object WorkspaceImageRefs {
    /** A relative address must look like an image: `![](notes)` is more likely a mistake than a file. */
    val RELATIVE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif", "svg")

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * Absolute guest path for [reference] (`/workspace/out.png`, `/tmp/plot.png`, or `out.png` relative to
     * [cwd]), or null when it is not a workspace file reference: other schemes, `//host`, traversal.
     * Percent-escapes (spaces, non-ASCII) are decoded and any query or fragment is dropped.
     */
    fun guestPath(reference: String, cwd: String): String? {
        var value = reference.trim()
        if (value.startsWith("<") && value.endsWith(">")) value = value.substring(1, value.length - 1).trim()
        if (value.isEmpty() || value.startsWith("//") || value.startsWith("#") || SCHEME.containsMatchIn(value)) return null

        val cut = value.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) value = value.substring(0, cut)
        value = percentDecode(value)
        if (value.isEmpty() || '\u0000' in value || '\\' in value) return null

        val absolute = value.startsWith("/")
        if (!absolute) {
            val extension = value.substringAfterLast('.', "").lowercase()
            if (extension !in RELATIVE_IMAGE_EXTENSIONS) return null
        }
        val segments = (if (absolute) value else "$cwd/$value")
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        return "/" + segments.joinToString("/")
    }

    /** UTF-8 percent-decoding; a malformed escape stays as written and `+` is not a space. */
    internal fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val high = if (value[i] == '%' && i + 2 < value.length) hexValue(value[i + 1]) else -1
            val low = if (high >= 0) hexValue(value[i + 2]) else -1
            if (high >= 0 && low >= 0) {
                out.write(high * 16 + low)
                i += 3
            } else {
                val codePoint = value.codePointAt(i)
                out.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(codePoint)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
