package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceSubagentPresetCodecTest {
    private fun preset() = WorkspaceSubagentPreset(
        key = "reviewer", name = "Reviewer", description = "Inspect correctness and safety.",
        systemPrompt = "Review the delegated scope and provide evidence.",
    )

    @Test
    fun emptyAndRoundTripAreStable() {
        assertEquals(emptyList<WorkspaceSubagentPreset>(), WorkspaceSubagentPresets.parse("[]"))
        val role = preset()
        val encoded = WorkspaceSubagentPresets.encode(listOf(role))
        assertEquals(listOf(role), WorkspaceSubagentPresets.parse(encoded))
        assertEquals(WorkspaceSubagentPresets.parse(encoded), WorkspaceSubagentPresets.parse(encoded))
        assertEquals(BuiltinToolIds.SUBAGENT, WorkspaceSubagentPresets.implementationId("[]", null, false))
        assertEquals(BuiltinToolIds.SUBAGENT, WorkspaceSubagentPresets.implementationId("[]", Uuid.random().toString(), true))
    }

    @Test
    fun malformedIdentityAndLiteralTypesFailClosed() {
        val encoded = JsonInstant.parseToJsonElement(WorkspaceSubagentPresets.encode(listOf(preset())))
            as kotlinx.serialization.json.JsonArray
        val obj = encoded.single() as JsonObject
        listOf(
            "", "null", "{}", "[{}]", "not-json",
            "[${JsonObject(obj - "id")}]",
            "[${JsonObject(obj + ("enabled" to JsonPrimitive("true")))}]",
            "[${JsonObject(obj + ("version" to JsonPrimitive("1")))}]",
            "[${JsonObject(obj + ("toolPermissions" to JsonPrimitive("read_only")))}]",
            "[${JsonObject(obj + ("generationConfig" to JsonObject(mapOf("temperature" to JsonPrimitive("0.2")))))}]",
            "[${JsonObject(obj + ("generationConfig" to JsonObject(mapOf("futureBudget" to JsonPrimitive(100)))))}]",
        ).forEach { json ->
            assertThrows("accepted corrupt JSON: $json", IllegalArgumentException::class.java) {
                WorkspaceSubagentPresets.parse(json)
            }
            assertNull(WorkspaceSubagentPresets.tryParse(json))
        }
        assertEquals("invalid", WorkspaceSubagentPresets.executionFingerprint(null))
    }

    @Test
    fun boundedFieldsKeysAndVersionsAreValidated() {
        val base = preset()
        val invalid = listOf(
            base.copy(key = "default"), base.copy(key = "Review"), base.copy(key = "review me"),
            base.copy(key = "x".repeat(33)), base.copy(name = ""), base.copy(name = "x".repeat(49)),
            base.copy(description = "x".repeat(241)), base.copy(systemPrompt = ""),
            base.copy(systemPrompt = "x".repeat(SUBAGENT_MAX_FIELD_CHARS + 1)),
            base.copy(name = "bad\u0000name"), base.copy(id = "invalid-id"),
            base.copy(version = 0), base.copy(executionRevision = Long.MAX_VALUE),
            base.copy(maxSteps = 0), base.copy(maxSteps = 121), base.copy(sortOrder = -1),
            base.copy(generationConfig = SubagentPresetGenerationConfig(temperature = Float.NaN)),
            base.copy(generationConfig = SubagentPresetGenerationConfig(temperature = 2.1f)),
            base.copy(generationConfig = SubagentPresetGenerationConfig(topP = 0f)),
            base.copy(generationConfig = SubagentPresetGenerationConfig(topP = 1.1f)),
            base.copy(generationConfig = SubagentPresetGenerationConfig(maxTokens = 0)),
            base.copy(modelSelection = SubagentPresetModelSelection.FIXED),
            base.copy(modelId = Uuid.random().toString()),
        )
        invalid.forEach { role ->
            assertThrows(IllegalArgumentException::class.java) { WorkspaceSubagentPresets.validate(role) }
        }
        WorkspaceSubagentPresets.validate(base.copy(maxSteps = 1))
        WorkspaceSubagentPresets.validate(base.copy(generationConfig = SubagentPresetGenerationConfig(temperature = 0f, topP = 1f, maxTokens = 1)))
    }

    @Test
    fun collectionBoundsAndUniquenessAreValidated() {
        val base = preset()
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSubagentPresets.encode(listOf(base, base.copy(id = Uuid.random().toString())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSubagentPresets.encode(listOf(base, base.copy(key = "other")))
        }
        val roles = List(WorkspaceSubagentPresets.MAX_PRESETS) { index ->
            base.copy(id = Uuid.random().toString(), key = "agent$index", sortOrder = index)
        }
        assertEquals(roles, WorkspaceSubagentPresets.parse(WorkspaceSubagentPresets.encode(roles)))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSubagentPresets.encode(roles + base.copy(id = Uuid.random().toString(), key = "extra"))
        }
    }

    @Test
    fun cosmeticChangesPreserveExecutionFingerprint() {
        val base = preset()
        val edited = base.copy(name = "New name", description = "A clearer description", sortOrder = 3, version = 2)
        assertTrue(WorkspaceSubagentPresets.sameExecution(base, edited))
        assertEquals(WorkspaceSubagentPresets.executionFingerprint(listOf(base)), WorkspaceSubagentPresets.executionFingerprint(listOf(edited)))
        val other = base.copy(id = Uuid.random().toString(), key = "other")
        assertEquals(
            WorkspaceSubagentPresets.executionFingerprint(listOf(base, other)),
            WorkspaceSubagentPresets.executionFingerprint(listOf(other, base)),
        )
    }

    @Test
    fun semanticChangesAndReenableNeverReuseOriginalIdentity() {
        val base = preset()
        val initial = WorkspaceSubagentPresets.executionFingerprint(listOf(base))
        val changed = base.copy(systemPrompt = "A different role", version = 2, executionRevision = 2)
        assertFalse(WorkspaceSubagentPresets.sameExecution(base, changed))
        assertNotEquals(initial, WorkspaceSubagentPresets.executionFingerprint(listOf(changed)))
        val reenabled = base.copy(version = 3, executionRevision = 3)
        assertNotEquals(initial, WorkspaceSubagentPresets.executionFingerprint(listOf(reenabled)))
        val json = WorkspaceSubagentPresets.encode(listOf(base))
        assertNotEquals(
            WorkspaceSubagentPresets.implementationId(json, null, true),
            WorkspaceSubagentPresets.implementationId(json, null, false),
        )
    }

    @Test
    fun onlyRevocationOperationsInterruptActiveFamily() {
        val base = preset()
        assertFalse(WorkspaceSubagentPresets.requiresRevocation(listOf(base), listOf(base.copy(systemPrompt = "Updated role"))))
        assertFalse(WorkspaceSubagentPresets.requiresRevocation(listOf(base), listOf(base.copy(name = "Renamed"))))
        assertTrue(WorkspaceSubagentPresets.requiresRevocation(listOf(base), emptyList()))
        assertTrue(WorkspaceSubagentPresets.requiresRevocation(listOf(base), listOf(base.copy(enabled = false))))
        assertTrue(WorkspaceSubagentPresets.requiresRevocation(listOf(base), listOf(base.copy(acceptContext = false))))
        assertTrue(WorkspaceSubagentPresets.requiresRevocation(null, emptyList()))
        assertFalse(WorkspaceSubagentPresets.requiresRevocation(listOf(base.copy(enabled = false)), emptyList()))
    }

    @Test
    fun modelModesAreExplicitAndFixedModelsNeverFallback() {
        val parent = Model(modelId = "parent", abilities = listOf(ModelAbility.TOOL))
        val child = Model(modelId = "child", abilities = listOf(ModelAbility.TOOL))
        val plain = Model(modelId = "plain", abilities = emptyList())
        val image = Model(modelId = "image", type = ModelType.IMAGE, abilities = listOf(ModelAbility.TOOL))
        val provider = ProviderSetting.OpenAI(models = listOf(parent, child, plain, image))
        val settings = Settings(providers = listOf(provider))
        val base = preset()
        assertEquals(child, resolveSubagentPresetModel(settings, parent, child.id.toString(), base))
        assertEquals(parent, resolveSubagentPresetModel(settings, parent, child.id.toString(), base.copy(modelSelection = SubagentPresetModelSelection.PARENT)))
        val fixed = base.copy(modelSelection = SubagentPresetModelSelection.FIXED, modelId = child.id.toString())
        assertEquals(child, resolveSubagentPresetModel(settings, parent, null, fixed))
        assertNull(resolveSubagentPresetModel(settings, parent, null, fixed.copy(modelId = Uuid.random().toString())))
        assertNull(resolveSubagentPresetModel(settings, parent, null, fixed.copy(modelId = plain.id.toString())))
        assertNull(resolveSubagentPresetModel(settings, parent, null, fixed.copy(modelId = image.id.toString())))
        assertNull(resolveSubagentPresetModel(settings.copy(providers = listOf(provider.copy(enabled = false))), parent, null, fixed))
        assertNull(resolveSubagentPresetModel(settings, parent, "broken", base))
    }

    @Test
    fun templatesAreValidIndependentExplicitCopies() {
        val first = workspaceSubagentPresetTemplates()
        val second = workspaceSubagentPresetTemplates()
        WorkspaceSubagentPresets.validateAll(first)
        assertEquals(listOf("explorer", "developer", "reviewer", "tester"), first.map { it.key })
        assertTrue(first.zip(second).all { (a, b) -> a.id != b.id })
        assertEquals(SubagentPresetModelSelection.WORKSPACE_DEFAULT, first.first().modelSelection)
    }
}
