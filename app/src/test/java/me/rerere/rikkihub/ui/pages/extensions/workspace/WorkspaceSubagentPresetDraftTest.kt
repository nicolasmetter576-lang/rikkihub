package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkihub.data.ai.tools.SUBAGENT_MAX_FIELD_CHARS
import me.rerere.rikkihub.data.ai.tools.SubagentPresetGenerationConfig
import me.rerere.rikkihub.data.ai.tools.SubagentPresetModelSelection
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.workspaceSubagentPresetTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceSubagentPresetDraftTest {
    private val preset = WorkspaceSubagentPreset(
        key = "reviewer", name = "Reviewer", description = "Review changes", systemPrompt = "Review the task",
    )

    @Test
    fun `blank optional values remain null and do not inherit generation parameters`() {
        val saved = WorkspaceSubagentPresetDraft(preset).toPreset()
        assertNull(saved.generationConfig.temperature)
        assertNull(saved.generationConfig.topP)
        assertNull(saved.generationConfig.maxTokens)
        assertEquals(ReasoningLevel.AUTO, saved.generationConfig.reasoningLevel)
        assertEquals(SubagentPresetModelSelection.WORKSPACE_DEFAULT, saved.modelSelection)
    }

    @Test
    fun `numeric fields are parsed only on explicit conversion`() {
        val draft = WorkspaceSubagentPresetDraft(
            preset.copy(generationConfig = SubagentPresetGenerationConfig(reasoningLevel = ReasoningLevel.XHIGH)),
            temperature = " 0.7 ", topP = "0.95", maxTokens = "4096", maxSteps = "12",
        )
        assertNull(draft.validationError())
        assertNull(draft.preset.generationConfig.temperature)
        val saved = draft.toPreset()
        assertEquals(0.7f, saved.generationConfig.temperature!!, 0f)
        assertEquals(0.95f, saved.generationConfig.topP!!, 0f)
        assertEquals(4096, saved.generationConfig.maxTokens)
        assertEquals(ReasoningLevel.XHIGH, saved.generationConfig.reasoningLevel)
        assertEquals(12, saved.maxSteps)
    }

    @Test
    fun `float parser rejects nonfinite malformed and oversized fields`() {
        listOf("NaN", "Infinity", "-Infinity", "1e999", "abc", "2.01", "-0.01", "0".repeat(33), " ".repeat(33) + "1").forEach {
            assertFalse(it, workspaceSubagentPresetOptionalFloatValid(it, 0f, 2f))
        }
        listOf("", " ", "0", "2", "1e-2").forEach {
            assertTrue(it, workspaceSubagentPresetOptionalFloatValid(it, 0f, 2f))
        }
        assertFalse(workspaceSubagentPresetOptionalFloatValid("0", 0f, 1f, exclusiveMin = true))
        assertTrue(workspaceSubagentPresetOptionalFloatValid("1", 0f, 1f, exclusiveMin = true))
    }

    @Test
    fun `positive integer parser is bounded and rejects overflow fractional and negative input`() {
        listOf("", "0", "-1", "+1", "1.5", "1e3", "2147483648", "0".repeat(33) + "1", " ".repeat(33) + "1").forEach {
            assertNull(it, workspaceSubagentPresetPositiveInt(it))
        }
        assertEquals(1, workspaceSubagentPresetPositiveInt(" 1 "))
        assertEquals(Int.MAX_VALUE, workspaceSubagentPresetPositiveInt("2147483647"))
        assertEquals(WorkspaceSubagentPresetDraftError.MAX_TOKENS,
            WorkspaceSubagentPresetDraft(preset, maxTokens = "2147483648").validationError())
        listOf("0", "121", "2.5", "").forEach {
            assertEquals(WorkspaceSubagentPresetDraftError.MAX_STEPS,
                WorkspaceSubagentPresetDraft(preset, maxSteps = it).validationError())
        }
        assertNull(WorkspaceSubagentPresetDraft(preset, maxSteps = "120").validationError())
        assertNull(WorkspaceSubagentPresetDraft(preset, maxSteps = "1").validationError())
    }

    @Test
    fun `all bounded fields validate their boundaries`() {
        assertEquals("abc", workspaceSubagentPresetBoundedText("abcdef", 3))
        assertEquals("", workspaceSubagentPresetBoundedText("abc", 0))
        assertNull(WorkspaceSubagentPresetDraft(preset.copy(
            key = "a".repeat(32), name = "n".repeat(48), description = "d".repeat(240),
            systemPrompt = "p".repeat(SUBAGENT_MAX_FIELD_CHARS),
        )).validationError())
        listOf("default", "Upper", "1worker", "with space", "a".repeat(33), "").forEach {
            assertEquals(WorkspaceSubagentPresetDraftError.KEY,
                WorkspaceSubagentPresetDraft(preset.copy(key = it)).validationError())
        }
        assertEquals(WorkspaceSubagentPresetDraftError.NAME,
            WorkspaceSubagentPresetDraft(preset.copy(name = "n".repeat(49))).validationError())
        assertEquals(WorkspaceSubagentPresetDraftError.DESCRIPTION,
            WorkspaceSubagentPresetDraft(preset.copy(description = "d".repeat(241))).validationError())
        assertEquals(WorkspaceSubagentPresetDraftError.PROMPT,
            WorkspaceSubagentPresetDraft(preset.copy(systemPrompt = "p".repeat(32769))).validationError())
        assertEquals(WorkspaceSubagentPresetDraftError.PROMPT,
            WorkspaceSubagentPresetDraft(preset.copy(systemPrompt = " ")).validationError())
    }

    @Test
    fun `unavailable fixed model UUID is retained without falling back`() {
        val missingModelId = Uuid.random().toString()
        val fixed = preset.copy(modelSelection = SubagentPresetModelSelection.FIXED, modelId = missingModelId)
        assertEquals(missingModelId, WorkspaceSubagentPresetDraft(fixed).toPreset().modelId)
        assertEquals(WorkspaceSubagentPresetDraftError.MODEL,
            WorkspaceSubagentPresetDraft(fixed.copy(modelId = "bad uuid")).validationError())
        assertEquals(WorkspaceSubagentPresetDraftError.MODEL,
            WorkspaceSubagentPresetDraft(fixed.copy(modelId = null)).validationError())
        assertNull(WorkspaceSubagentPresetDraft(fixed.copy(modelSelection = SubagentPresetModelSelection.PARENT))
            .toPreset().modelId)
    }

    @Test
    fun `clone allocates independent identity revision and unique bounded key`() {
        val source = preset.copy(key = "a".repeat(32), version = 9, executionRevision = 7,
            libraryPresetId = Uuid.random().toString())
        val firstKey = workspaceSubagentPresetCopyKey(source.key, emptySet())
        val secondKey = workspaceSubagentPresetCopyKey(source.key, setOf(firstKey))
        assertTrue(firstKey.length <= 32)
        assertTrue(secondKey.length <= 32)
        assertNotEquals(source.key, firstKey)
        assertNotEquals(firstKey, secondKey)
        val clone = workspaceSubagentPresetClone(source, setOf(firstKey))
        assertNotEquals(source.id, clone.id)
        assertEquals(secondKey, clone.key)
        assertEquals(1L, clone.version)
        assertEquals(1L, clone.executionRevision)
        assertNull(clone.libraryPresetId)
        assertEquals(source.systemPrompt, clone.systemPrompt)
        assertEquals(source.generationConfig, clone.generationConfig)
        WorkspaceSubagentPresets.validate(clone)
        val copyDraft = WorkspaceSubagentPresetDraft(clone, workspaceId = "target-workspace")
        assertNull(copyDraft.expectedVersion)
        assertEquals("target-workspace", copyDraft.workspaceId)
        assertEquals("custom_key", copyDraft.copy(preset = clone.copy(key = "custom_key")).toPreset().key)
    }

    @Test
    fun `external updates mark conflict but do not change unsaved draft`() {
        val draft = WorkspaceSubagentPresetDraft(preset.copy(name = "Unsaved name"), expectedVersion = 1)
        assertFalse(workspaceSubagentPresetDraftConflict(draft, listOf(preset)))
        assertTrue(workspaceSubagentPresetDraftConflict(draft, listOf(preset.copy(version = 2))))
        assertTrue(workspaceSubagentPresetDraftConflict(draft, emptyList()))
        assertTrue(workspaceSubagentPresetDraftConflict(draft, null))
        assertEquals("Unsaved name", draft.preset.name)
        assertEquals(1L, draft.expectedVersion)
        assertFalse(workspaceSubagentPresetDraftConflict(draft.copy(expectedVersion = null), listOf(preset)))
    }

    @Test
    fun `catalogue preview uses parent description not worker role and omits disabled roles`() {
        val preview = workspaceSubagentPresetCataloguePreview(listOf(
            preset, preset.copy(id = Uuid.random().toString(), key = "hidden", enabled = false),
        ))
        assertTrue(preview.contains("reviewer: Reviewer"))
        assertTrue(preview.contains("Review changes"))
        assertFalse(preview.contains("Review the task"))
        assertFalse(preview.contains("hidden"))
        assertTrue(preview.contains("default"))
    }

    @Test
    fun `templates create separate valid drafts and never persist on conversion alone`() {
        val templates = workspaceSubagentPresetTemplates()
        assertEquals(listOf("explorer", "developer", "reviewer", "tester"), templates.map { it.key })
        templates.forEach { template ->
            val draft = WorkspaceSubagentPresetDraft(template)
            assertNull(draft.expectedVersion)
            WorkspaceSubagentPresets.validate(draft.toPreset())
        }
        assertNotEquals(templates.first().id, workspaceSubagentPresetTemplates().first().id)
    }
}
