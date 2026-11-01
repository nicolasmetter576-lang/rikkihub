package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.io.Serializable
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

/**
 * Snapshots captured right before a SAF picker is launched.
 *
 * The system picker can destroy and recreate the activity, so the result
 * callback must not depend on state that only lives in composition (or on the
 * VM's current area/path, which may have changed while picking). These are
 * [Serializable] so they survive `rememberSaveable`.
 */
internal data class PendingFolderImport(
    val area: WorkspaceStorageArea,
    val destinationPath: String,
) : Serializable

internal data class PendingArchiveExport(
    val area: WorkspaceStorageArea,
    /** Stored as an ArrayList at the call site so the whole object stays saveable. */
    val paths: List<String>,
) : Serializable

/**
 * Snapshot for a pending multi-delete confirmation. Captured when the delete
 * button is tapped (not when the dialog confirms), so the confirm callback
 * never re-reads the live selection/area — navigating elsewhere or a refresh
 * while the dialog is open cannot retarget the deletion.
 */
internal data class PendingBatchDelete(
    val area: WorkspaceStorageArea,
    /** Stored as an ArrayList at the call site so the whole object stays saveable. */
    val paths: List<String>,
    /** Whether any selected entry was a directory, for the recursive warning. */
    val includesDirectories: Boolean,
) : Serializable

/** Toggle [path] inside [selected], preserving the existing iteration order. */
internal fun toggleWorkspacePathSelection(selected: Set<String>, path: String): Set<String> =
    if (path in selected) selected - path else selected + path

/**
 * Drop selected paths that no longer exist in [entries] (after refresh /
 * directory / area / workspace switches). Returns the same instance when
 * nothing changed so callers can skip a state write.
 */
internal fun pruneWorkspaceSelection(
    selected: Set<String>,
    entries: List<WorkspaceFileEntry>,
): Set<String> {
    if (selected.isEmpty()) return selected
    val existing = entries.mapTo(HashSet()) { it.path }
    val kept = selected.filterTo(LinkedHashSet()) { it in existing }
    return if (kept.size == selected.size) selected else kept
}

/**
 * Suggested file name for a batch ZIP export.
 *
 * - a single selected entry uses its own name (extension stripped for files,
 *   so `notes.md` becomes `notes.zip`)
 * - multiple entries inside a directory use that directory's name
 * - multiple entries at the storage root fall back to the workspace name
 *
 * The base is sanitized to a filesystem-friendly form; the result always ends
 * with `.zip`.
 */
internal fun defaultWorkspaceArchiveName(
    workspaceName: String?,
    directoryPath: String,
    selectedPaths: List<String>,
): String {
    val base = when {
        selectedPaths.size == 1 -> {
            val name = selectedPaths.single().substringAfterLast('/')
            name.substringBeforeLast('.').ifBlank { name }
        }

        directoryPath.isNotBlank() -> directoryPath.substringAfterLast('/')
        else -> workspaceName
    }
    val sanitized = base.orEmpty()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('.', '-')
        .ifBlank { "workspace" }
    return "$sanitized.zip"
}
