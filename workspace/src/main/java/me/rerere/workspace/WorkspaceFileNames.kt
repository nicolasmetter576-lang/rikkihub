package me.rerere.workspace

import java.nio.charset.StandardCharsets

/** Stable creation errors shared by the filesystem, UI and HTTP adapters. */
open class WorkspaceFileOperationException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    companion object {
        const val FILE_ALREADY_EXISTS = "FILE_ALREADY_EXISTS"
        const val INVALID_FILE_NAME = "INVALID_FILE_NAME"
        const val INVALID_PATH = "INVALID_PATH"
        const val PARENT_NOT_FOUND = "PARENT_NOT_FOUND"
        const val PARENT_NOT_DIRECTORY = "PARENT_NOT_DIRECTORY"
        const val SYMLINK_NOT_ALLOWED = "SYMLINK_NOT_ALLOWED"
        const val CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE"
        const val FILE_NOT_UTF8 = "FILE_NOT_UTF8"
        const val FILE_NOT_TEXT = "FILE_NOT_TEXT"
        const val FILE_REVISION_CONFLICT = "FILE_REVISION_CONFLICT"
        const val INVALID_REVISION = "INVALID_REVISION"
        const val FILE_NOT_FOUND = "FILE_NOT_FOUND"
        const val FILE_NOT_FILE = "FILE_NOT_FILE"
        const val FILE_IO_ERROR = "FILE_IO_ERROR"
        const val FILE_PERMISSION_DENIED = "FILE_PERMISSION_DENIED"
        const val ATOMIC_SAVE_UNSUPPORTED = "ATOMIC_SAVE_UNSUPPORTED"
        const val TRANSFER_SAME_LOCATION = "TRANSFER_SAME_LOCATION"
        const val TRANSFER_INTO_ITSELF = "TRANSFER_INTO_ITSELF"
        const val NOT_ENOUGH_SPACE = "NOT_ENOUGH_SPACE"
    }
}

/** A file, directory or symbolic link already occupies the requested leaf. */
class WorkspaceFileAlreadyExistsException(cause: Throwable? = null) :
    WorkspaceFileOperationException(
        WorkspaceFileOperationException.FILE_ALREADY_EXISTS,
        "File already exists",
        cause,
    )

/** Filename contract for exclusive creation; validation never rewrites the input. */
object WorkspaceFileNames {
    const val MAX_UTF8_BYTES = 255

    /**
     * Validate a single filename, not a path. Throws [WorkspaceFileOperationException]
     * with code `INVALID_FILE_NAME`. Unicode is preserved without normalization.
     */
    fun validate(name: String) {
        val valid = name.isNotEmpty() &&
            name != "." && name != ".." &&
            name == name.trim { it.isWhitespace() || it == '\uFEFF' } &&
            hasWellFormedUtf16(name) &&
            !name.startsWith(".l2s.") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() } &&
            name.toByteArray(StandardCharsets.UTF_8).size <= MAX_UTF8_BYTES
        if (!valid) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.INVALID_FILE_NAME,
                "Invalid file name",
            )
        }
    }

    // Java's UTF-8 encoder replaces malformed UTF-16 with '?'. Reject it rather
    // than validating a byte count for a different filename than the user typed.
    private fun hasWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                current.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }

    /**
     * Assemble the dialog's separate name and extension without rewriting either.
     * Empty extension denotes a directory to the caller; this helper only returns
     * its leaf name. Extensions have no leading/trailing dot; `tar.gz` is allowed.
     */
    fun creationName(name: String, extension: String): String {
        validate(name)
        if (extension.isEmpty()) return name
        validate(extension)
        if (extension.startsWith('.') || extension.endsWith('.')) {
            throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.INVALID_FILE_NAME,
                "Extension must not start or end with a dot",
            )
        }
        return "$name.$extension".also(::validate)
    }
}

/** Strict relative creation paths: no trimming, normalization or separator aliases. */
internal fun workspaceCreationSegments(path: String): List<String> {
    val segments = path.split('/')
    if (path.isEmpty() || path.startsWith('/') ||
        segments.any { it.isEmpty() || it == "." || it == ".." } ||
        path.any { it == '\\' || it.isISOControl() }
    ) {
        throw WorkspaceFileOperationException(
            WorkspaceFileOperationException.INVALID_PATH,
            "Invalid relative file path",
        )
    }
    // Only the new leaf is a filename being created. Existing parent names are kept
    // literally (including whitespace); their type and links are checked on disk.
    WorkspaceFileNames.validate(segments.last())
    return segments
}
