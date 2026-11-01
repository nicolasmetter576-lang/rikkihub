package me.rerere.rikkihub.ui.pages.setting.subagent

import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceSubagentPresetDraft

/** Role fields and assignment checkboxes form one explicit-save draft. Flows never replace it. */
data class SubagentLibraryDraft(
    val role: WorkspaceSubagentPresetDraft,
    val workspaceIds: Set<String> = emptySet(),
    val originalWorkspaceIds: Set<String> = emptySet(),
    val conflict: Boolean = false,
)

enum class SubagentBindingBlock { CORRUPT, KEY_CONFLICT, LIMIT }

/** Assignment is the raw link, not enabled: disabled definitions remain assigned. */
fun subagentLibraryWorkspaceIds(presetId: String, workspaces: List<WorkspaceEntity>): Set<String> =
    workspaces.filter { workspace ->
        WorkspaceSubagentPresets.tryParse(workspace.subagentPresets)
            ?.any { it.libraryPresetId == presetId } == true
    }.map { it.id }.toSet()

fun subagentBindingBlock(preset: WorkspaceSubagentPreset, workspace: WorkspaceEntity): SubagentBindingBlock? {
    val presets = WorkspaceSubagentPresets.tryParse(workspace.subagentPresets) ?: return SubagentBindingBlock.CORRUPT
    if (presets.any { it.libraryPresetId == preset.id }) return null // Always allow unbinding.
    if (presets.any { it.key == preset.key.trim() }) return SubagentBindingBlock.KEY_CONFLICT
    if (presets.size >= WorkspaceSubagentPresets.MAX_PRESETS) return SubagentBindingBlock.LIMIT
    return null
}

fun subagentLibraryDraftConflict(
    draft: SubagentLibraryDraft,
    presets: List<WorkspaceSubagentPreset>?,
    workspaces: List<WorkspaceEntity>,
): Boolean {
    if (presets == null) return true
    val version = draft.role.expectedVersion
    if (version != null && presets.firstOrNull { it.id == draft.role.preset.id }?.version != version) return true
    if (subagentLibraryWorkspaceIds(draft.role.preset.id, workspaces) != draft.originalWorkspaceIds) return true
    if (draft.workspaceIds.any { selected -> workspaces.none { it.id == selected } }) return true
    // A selected target may have been corrupted while the editor was open.
    return workspaces.any { it.id in draft.workspaceIds &&
        WorkspaceSubagentPresets.tryParse(it.subagentPresets) == null }
}
