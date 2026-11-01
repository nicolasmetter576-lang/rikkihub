package me.rerere.workspace

import java.io.File
import java.security.MessageDigest

/**
 * Confines the agent to one FILES directory. The selected directory keeps its real guest path
 * `/workspace/<scope>`; `/workspace` and its ancestors become empty shells that only reveal the
 * chain leading to it. Everything else under `/workspace` does not exist for the agent, and the
 * same error is raised whether or not an outside path exists.
 *
 * The shells live under the workspace temp dir: anything written there is not FILES content,
 * is invisible to the file tools, and is wiped with the temp dir on the next app start.
 */
object WorkspaceFilesScope {
    const val OUTSIDE_MESSAGE = "Path is outside the selected working directory"
    const val UNAVAILABLE_MESSAGE = "The selected working directory no longer exists"
    private const val SHELL_DIR = "scope_shells"

    /**
     * FILES-relative scope (`a/b`) from `a/b`, `/workspace/a/b` or `a//b/`; null for the FILES
     * root. Traversal, NUL, backslashes and absolute paths outside `/workspace` are rejected.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        require('\u0000' !in raw && '\\' !in raw) { "Invalid working directory" }
        var value = raw.trim()
        if (value.startsWith("/")) {
            val root = WorkspaceManager.ROOTFS_WORKSPACE_DIR
            require(value == root || value.startsWith("$root/")) { "Working directory must be inside /workspace" }
            value = value.removePrefix(root)
        }
        val segments = value.split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "Invalid working directory" }
        return segments.joinToString("/").ifEmpty { null }
    }

    fun guestPath(scope: String): String = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$scope"

    /** Names only the selected directory, which the agent already knows; says nothing about the requested path. */
    fun outsideMessage(scope: String): String = "$OUTSIDE_MESSAGE ${guestPath(scope)}"

    /** True when FILES-relative [relative] is [scope] itself or inside it. */
    fun contains(scope: String, relative: String): Boolean =
        relative == scope || relative.startsWith("$scope/")

    /** Existing directory inside [filesDir] with no symlinked component; fails closed otherwise. */
    fun resolveSource(filesDir: File, scope: String): File {
        val base = filesDir.canonicalFile
        val dir = File(base, scope).absoluteFile.normalize()
        val canonical = dir.canonicalFile
        if (canonical != dir || !canonical.path.startsWith(base.path + File.separator) || !canonical.isDirectory) {
            throw IllegalArgumentException(UNAVAILABLE_MESSAGE)
        }
        return canonical
    }

    /** Empty shell tree that contains only the directory chain to [scope]; bound at `/workspace`. */
    fun shell(tempDir: File, scope: String): File {
        val dir = File(File(tempDir, SHELL_DIR), sha256(scope).take(16))
        File(dir, scope).mkdirs()
        return dir
    }

    /**
     * `/workspace` binds for PRoot: the whole FILES area when [scope] is null, otherwise the empty
     * shell at `/workspace` plus the selected directory at its real guest path.
     */
    fun workspaceBinds(filesDir: File, tempDir: File, scope: String?): List<WorkspaceBindMount> {
        val root = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        if (scope == null) return listOf(WorkspaceBindMount(filesDir, root))
        val source = resolveSource(filesDir, scope)
        return listOf(
            WorkspaceBindMount(shell(tempDir, scope), root),
            WorkspaceBindMount(source, guestPath(scope), required = true),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
