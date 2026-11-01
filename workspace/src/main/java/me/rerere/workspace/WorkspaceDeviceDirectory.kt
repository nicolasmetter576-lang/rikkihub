package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/** Validated direct-write directory. This is not an OS sandbox for arbitrary shell commands. */
data class WorkspaceDeviceMount(val id: String, val alias: String, val source: File) {
    val guestPath: String get() = "${WorkspaceDeviceDirectoryGuard.DEVICE_GUEST_ROOT}/$alias"
    fun toBindMount(required: Boolean = true): WorkspaceBindMount =
        WorkspaceBindMount(source = source, target = guestPath, required = required)
}

/** Only existing ordinary directories at or below the runtime primary shared-storage root. */
class WorkspaceDeviceDirectoryGuard(primaryRoot: File) {
    private val primary = primaryRoot.canonicalFile.toPath()

    /** The app translates a controlled volume-relative selection into this absolute host path. */
    fun resolveSource(path: String): File {
        require(path.isNotBlank() && '\u0000' !in path && ':' !in path) {
            "Invalid device directory path"
        }
        val candidate = File(path)
        require(candidate.isAbsolute) { "Device directory path must be absolute" }
        val lexical = candidate.toPath()
        require(lexical.none { it.toString() == ".." }) { "Parent traversal is not allowed" }
        val canonical = candidate.canonicalFile.toPath()
        require(canonical.startsWith(primary)) {
            "Choose a directory inside the primary shared storage"
        }
        val relative = primary.relativize(canonical)
        // The root itself may be connected (its Android/ subtree stays OS-restricted);
        // an explicit Android/ selection is still refused.
        require(!relative.first().toString().equals(ANDROID_DIR_NAME, ignoreCase = true)) {
            "Android/ and its descendants cannot be connected"
        }
        // Do not accept a selected path whose components can silently retarget another source.
        // The primary root itself is canonicalized (Android may expose it via a platform alias).
        var component = primary
        for (segment in relative) {
            component = component.resolve(segment)
            require(!Files.isSymbolicLink(component)) { "Device directory cannot use symbolic links" }
        }
        // Also inspect the supplied spelling, not only its already resolved canonical destination.
        val supplied = lexical.normalize()
        if (supplied.startsWith(primary)) {
            component = primary
            for (segment in primary.relativize(supplied)) {
                component = component.resolve(segment)
                require(!Files.isSymbolicLink(component)) { "Device directory cannot use symbolic links" }
            }
        }
        val file = canonical.toFile()
        require(file.isDirectory) { "Device directory is missing or is not a directory" }
        return file
    }

    fun validateConnections(mounts: List<WorkspaceDeviceMount>) {
        require(mounts.size <= MAX_MOUNTS) { "At most $MAX_MOUNTS device directories per workspace" }
        val aliases = HashSet<String>()
        val ids = HashSet<String>()
        val sources = mutableListOf<java.nio.file.Path>()
        for (mount in mounts) {
            require(mount.id.isNotBlank() && ids.add(mount.id)) { "Duplicate or empty device mount ID" }
            require(mount.alias.matches(ALIAS_REGEX) && aliases.add(mount.alias)) {
                "Invalid or duplicate device directory alias"
            }
            val source = resolveSource(mount.source.path).toPath()
            require(sources.none { source.startsWith(it) || it.startsWith(source) }) {
                "Device directories must not duplicate or nest"
            }
            sources.add(source)
        }
    }

    companion object {
        const val MAX_MOUNTS = 8
        const val DEVICE_GUEST_ROOT = "/device"
        const val ANDROID_DIR_NAME = "Android"
        val ALIAS_REGEX = Regex("[a-z][a-z0-9_-]{0,31}")
    }
}
