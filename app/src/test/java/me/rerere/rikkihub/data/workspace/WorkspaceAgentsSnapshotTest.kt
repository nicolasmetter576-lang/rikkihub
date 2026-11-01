package me.rerere.rikkihub.data.workspace

import me.rerere.rikkihub.data.ai.tools.createWorkspaceToolSnapshot
import me.rerere.rikkihub.data.ai.tools.unavailableWorkspaceToolSlice
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentsSnapshotTest {
    private val workspace = WorkspaceEntity("w", "test", "root", WorkspaceShellStatus.READY.name, 0, 0,
        toolEnablement = "{\"workspace_read_file\":false,\"workspace_write_file\":false,\"workspace_edit_file\":false,\"workspace_shell\":false,\"bash\":false,\"str_replace_editor\":false,\"subagent\":false}")

    @Test fun readyWithDisabledToolsCapturesEligibilityAndCwd() {
        val snapshot = createWorkspaceToolSnapshot(workspace, null, cwd = "/device/docs/project", loadWorkspace = { workspace })
        assertTrue(snapshot.tools.isEmpty())
        assertTrue(snapshot.capabilities.instructionsEligible)
        assertEquals("/device/docs/project", snapshot.capabilities.instructionsCwd)
    }

    @Test fun clearedCwdIsCapturedAsNull() {
        val snapshot = createWorkspaceToolSnapshot(workspace, null, cwd = null, loadWorkspace = { workspace })
        assertNull(snapshot.capabilities.instructionsCwd)
        assertTrue(snapshot.capabilities.instructionsEligible)
    }

    @Test fun notReadyAndUnavailableSlicesDisallowInstructions() {
        val snapshot = createWorkspaceToolSnapshot(workspace.copy(shellStatus = WorkspaceShellStatus.DISABLED.name), null,
            cwd = "/workspace/project", loadWorkspace = { workspace })
        assertFalse(snapshot.capabilities.instructionsEligible)
        assertFalse(unavailableWorkspaceToolSlice("w", emptyMap(), null).capabilities.instructionsEligible)
    }

    @Test fun manualCapabilitiesHaveConservativeDefaults() {
        val caps = WorkspaceRequestCapabilities("w", "test", false, emptyMap(), emptySet())
        assertFalse(caps.instructionsEligible)
        assertNull(caps.instructionsCwd)
    }
}
