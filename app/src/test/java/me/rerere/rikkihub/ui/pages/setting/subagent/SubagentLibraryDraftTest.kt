package me.rerere.rikkihub.ui.pages.setting.subagent

import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceSubagentPresetDraft
import me.rerere.rikkihub.ui.pages.extensions.workspace.workspaceSubagentPresetClone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubagentLibraryDraftTest {
    private val role = WorkspaceSubagentPreset(
        key = "reviewer", name = "Reviewer", description = "Review changes", systemPrompt = "Review the task",
    )

    private fun workspace(id: String, presets: List<WorkspaceSubagentPreset> = emptyList()) = WorkspaceEntity(
        id = id, name = id, root = id, createdAt = 0, updatedAt = 0,
        subagentPresets = WorkspaceSubagentPresets.encode(presets),
    )

    private fun projection(source: WorkspaceSubagentPreset = role) = source.copy(
        id = Uuid.random().toString(), libraryPresetId = source.id,
    )

    @Test
    fun `assignment reads raw linkage even when the global definition and projection are disabled`() {
        val disabled = role.copy(enabled = false)
        val bound = workspace("bound", listOf(projection(disabled)))
        val local = workspace("local", listOf(disabled))
        assertEquals(setOf("bound"), subagentLibraryWorkspaceIds(disabled.id, listOf(bound, local)))
        assertNull(subagentBindingBlock(disabled, workspace("empty")))
    }

    @Test
    fun `same-key local and other-library roles block binding rather than being adopted`() {
        assertEquals(SubagentBindingBlock.KEY_CONFLICT, subagentBindingBlock(role, workspace("local", listOf(role))))
        val other = projection(role.copy(id = Uuid.random().toString()))
        assertEquals(SubagentBindingBlock.KEY_CONFLICT, subagentBindingBlock(role, workspace("other", listOf(other))))
        assertNull(subagentBindingBlock(role, workspace("bound", listOf(projection()))))
    }

    @Test
    fun `full workspace blocks new assignment but permits removal of an existing link`() {
        val locals = (1..WorkspaceSubagentPresets.MAX_PRESETS).map { index ->
            role.copy(id = Uuid.random().toString(), key = "worker$index")
        }
        assertEquals(SubagentBindingBlock.LIMIT, subagentBindingBlock(role, workspace("full", locals)))
        assertNull(subagentBindingBlock(role, workspace("bound", locals.dropLast(1) + projection())))
    }

    @Test
    fun `corrupt workspace is not treated as empty`() {
        val corrupt = workspace("broken").copy(subagentPresets = "{broken")
        assertEquals(SubagentBindingBlock.CORRUPT, subagentBindingBlock(role, corrupt))
    }

    @Test
    fun `external library and assignment changes flag conflict without changing draft fields or selections`() {
        val draft = SubagentLibraryDraft(
            role = WorkspaceSubagentPresetDraft(role.copy(name = "Unsaved", systemPrompt = "Typed prompt"),
                expectedVersion = role.version, temperature = "not yet valid"),
            workspaceIds = setOf("pending"), originalWorkspaceIds = setOf("bound"),
        )
        val originalWorkspaces = listOf(workspace("bound", listOf(projection())), workspace("pending"))
        assertFalse(subagentLibraryDraftConflict(draft, listOf(role), originalWorkspaces))
        assertTrue(subagentLibraryDraftConflict(draft, listOf(role.copy(version = 2)), originalWorkspaces))
        assertTrue(subagentLibraryDraftConflict(draft, listOf(role), listOf(workspace("bound"), workspace("pending"))))
        assertTrue(subagentLibraryDraftConflict(draft, emptyList(), originalWorkspaces))
        assertTrue(subagentLibraryDraftConflict(draft, null, originalWorkspaces))
        assertEquals("Unsaved", draft.role.preset.name)
        assertEquals("Typed prompt", draft.role.preset.systemPrompt)
        assertEquals("not yet valid", draft.role.temperature)
        assertEquals(setOf("pending"), draft.workspaceIds)
        assertEquals(setOf("bound"), draft.originalWorkspaceIds)
    }

    @Test
    fun `removed or corrupt selected workspaces conflict but unrelated workspace updates do not`() {
        val draft = SubagentLibraryDraft(WorkspaceSubagentPresetDraft(role), workspaceIds = setOf("selected"))
        assertFalse(subagentLibraryDraftConflict(draft, listOf(role), listOf(workspace("selected"), workspace("unrelated"))))
        assertTrue(subagentLibraryDraftConflict(draft, listOf(role), listOf(workspace("unrelated"))))
        assertTrue(subagentLibraryDraftConflict(draft, listOf(role), listOf(workspace("selected").copy(subagentPresets = "bad"))))
        assertFalse(subagentLibraryDraftConflict(draft, listOf(role), listOf(workspace("selected").copy(name = "New name"))))
    }

    @Test
    fun `role fields and assignments remain one explicit save draft`() {
        val draft = SubagentLibraryDraft(WorkspaceSubagentPresetDraft(role), workspaceIds = setOf("a"))
        val edited = draft.copy(role = draft.role.copy(preset = role.copy(name = "Draft name")), workspaceIds = setOf("b"))
        assertEquals("Reviewer", draft.role.preset.name)
        assertEquals(setOf("a"), draft.workspaceIds)
        assertEquals("Draft name", edited.role.toPreset().name)
        assertEquals(setOf("b"), edited.workspaceIds)
    }

    @Test
    fun `cloned linked projection becomes an independent definition with no assignments`() {
        val cloned = workspaceSubagentPresetClone(projection(), setOf(role.key))
        val draft = SubagentLibraryDraft(WorkspaceSubagentPresetDraft(cloned))
        assertNull(cloned.libraryPresetId)
        assertNull(draft.role.expectedVersion)
        assertTrue(draft.workspaceIds.isEmpty())
        assertTrue(draft.originalWorkspaceIds.isEmpty())
        assertTrue(cloned.id != role.id)
        assertTrue(cloned.key != role.key)
    }
}
