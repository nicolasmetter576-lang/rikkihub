package me.rerere.rikkihub.data.repository

import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.ai.tools.toolEnabled
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.workspace.WorkspaceChangeSpec

/** Content/file writes are deliberately absent: only configuration changes invalidate requests. */
internal fun workspaceConfigChangeSpec(
    current: WorkspaceEntity,
    next: WorkspaceEntity,
): WorkspaceChangeSpec? {
    val presetsChanged = current.subagentPresets != next.subagentPresets
    // Validate writes, but never reject or normalize unchanged corrupt JSON on metadata updates.
    val newPresets = if (presetsChanged) WorkspaceSubagentPresets.parse(next.subagentPresets) else null

    if (current.root != next.root || current.harnessMinimal != next.harnessMinimal ||
        current.shellStatus != next.shellStatus ||
        current.exposedMountTargets() != next.exposedMountTargets() ||
        current.shellCompatibilityMode != next.shellCompatibilityMode
    ) return WorkspaceChangeSpec.structural()

    val old = WorkspaceToolEnablement.tryParse(current.toolEnablement)
    val new = WorkspaceToolEnablement.parse(next.toolEnablement)
    val affected = WorkspaceToolEnablement.configurableIds.filterTo(linkedSetOf()) { id ->
        // A damaged configuration has no available tools; explicit repair must invalidate old bindings.
        (old?.toolEnabled(id) ?: false) != new.toolEnabled(id)
    }
    if (newPresets != null && WorkspaceSubagentPresets.requiresRevocation(
            WorkspaceSubagentPresets.tryParse(current.subagentPresets), newPresets,
        )
    ) affected += BuiltinToolIds.SUBAGENT
    if (current.subagentAllowContext && !next.subagentAllowContext &&
        // Corrupt catalogs are fail-closed; conservatively revoke their old bindings as well.
        WorkspaceSubagentPresets.tryParse(current.subagentPresets)?.isNotEmpty() != false
    ) affected += BuiltinToolIds.SUBAGENT
    // Collect all tool and preset revocations before returning (one combined patch may change both).
    if (affected.isNotEmpty()) return WorkspaceChangeSpec.toolPolicy(affected)

    // These alter request prompts/approval/configuration but must not cancel unrelated executing tools.
    if (current.name != next.name || current.toolApprovals != next.toolApprovals ||
        current.subagentModelId != next.subagentModelId ||
        current.subagentAllowContext != next.subagentAllowContext ||
        current.firstMessageFileSnapshot != next.firstMessageFileSnapshot ||
        current.firstMessageFileSnapshotDepth != next.firstMessageFileSnapshotDepth ||
        current.toolEnablement != next.toolEnablement || presetsChanged
    ) return WorkspaceChangeSpec.toolPolicy(emptySet())
    return null
}
