package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for newly registered model definitions
 * (GEMINI_4, DEEPSEEK_FLASH, STEP_5, XIAOMI_MIMO_V2_6) and the
 * neighbouring definitions they must not steal matches from.
 *
 * Only the public ModelRegistry surface is exercised:
 * group `match()` plus MODEL_INPUT_MODALITIES / MODEL_OUTPUT_MODALITIES /
 * MODEL_ABILITIES / MODEL_CONTEXT_LENGTH.
 */
class NewModelCapabilitiesTest {

    private val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
    private val textOnly = listOf(Modality.TEXT)
    private val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)

    /** vision input + TEXT output + [TOOL, REASONING] + no guessed context. */
    private fun assertVisionToolReasoning(modelId: String) {
        assertEquals("input modalities for $modelId", visionInput,
            ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId))
        assertEquals("output modalities for $modelId", textOnly,
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId))
        assertEquals("abilities for $modelId", toolReasoning,
            ModelRegistry.MODEL_ABILITIES.getData(modelId))
        assertNull("context length for $modelId",
            ModelRegistry.MODEL_CONTEXT_LENGTH.getData(modelId))
    }

    // ------------------------------------------------------------------
    // GEMINI_4
    // ------------------------------------------------------------------

    @Test
    fun gemini4GroupMembership() {
        val ids = listOf(
            "gemini-4",
            "GEMINI-4",
            "google/gemini-4",
            "gemini-4-20260701",
        )
        for (id in ids) {
            assertTrue("GEMINI_SERIES should match $id", ModelRegistry.GEMINI_SERIES.match(id))
            assertFalse("GEMINI_3_SERIES should not match $id", ModelRegistry.GEMINI_3_SERIES.match(id))
        }
    }

    @Test
    fun gemini4Capabilities() {
        val ids = listOf(
            "gemini-4",
            "GEMINI-4",
            "google/gemini-4",
            "gemini-4-20260701",
        )
        ids.forEach(::assertVisionToolReasoning)
    }

    // ------------------------------------------------------------------
    // DEEPSEEK_FLASH (tokens "deepseek" "flash")
    // ------------------------------------------------------------------

    @Test
    fun deepseekFlashCapabilities() {
        val ids = listOf(
            "deepseek-flash",
            "DeepSeek-Flash",
            "openrouter/deepseek-flash",
            "deepseek-flash-20260601",
        )
        ids.forEach(::assertVisionToolReasoning)
    }

    @Test
    fun deepseekFlashDoesNotStealMoreSpecificV4() {
        // deepseek-v4-flash stays text-only with tool+reasoning and 1m context
        assertEquals(textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-flash"))

        // vision-exp variant keeps vision
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision-exp"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-flash-vision-exp"))

        // v4.1 flash keeps vision
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4.1-flash"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4.1-flash"))

        // v4 pro unaffected
        assertEquals(textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-pro"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-pro"))
    }

    // ------------------------------------------------------------------
    // STEP_5
    // ------------------------------------------------------------------

    @Test
    fun step5Capabilities() {
        val ids = listOf(
            "step-5",
            "STEP-5",
            "stepfun/step-5",
            "step-5-20261111",
        )
        ids.forEach(::assertVisionToolReasoning)
    }

    @Test
    fun step3xRegression() {
        // pre-existing step definitions keep their coverage
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-3"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("step-3"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-3.7-flash"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("step-3.7-flash"))
    }

    // ------------------------------------------------------------------
    // XIAOMI_MIMO_V2_6
    // ------------------------------------------------------------------

    @Test
    fun mimoV26Capabilities() {
        val ids = listOf(
            "mimo-v2.6",
            "MiMo-V2.6",
            "xiaomi/mimo-v2.6",
            "mimo-v2.6-pro",
        )
        ids.forEach(::assertVisionToolReasoning)
    }

    @Test
    fun mimoV25ProStaysTextOnly() {
        // v2.5 pro keeps its original text-only + tool/reasoning profile
        assertEquals(textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5-pro"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("mimo-v2.5-pro"))
        // v2.5 (non-pro) keeps its existing vision coverage
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5"))
        // v2 / v2 pro stay text-only
        assertEquals(textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2"))
        assertEquals(textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2-pro"))
    }

    // ------------------------------------------------------------------
    // Existing coverage that must not be removed
    // ------------------------------------------------------------------

    @Test
    fun claudeSeriesCoveragePreserved() {
        val ids = listOf(
            "claude-3.5-sonnet",
            "claude-3.5-sonnet-20241022",
            "claude-3.7-sonnet",
            "claude-sonnet-5",
            "claude-opus-5",
            "claude-sonnet-5.5",
            "claude-opus-5.5",
        )
        for (id in ids) {
            assertTrue("CLAUDE_SERIES should match $id", ModelRegistry.CLAUDE_SERIES.match(id))
            assertEquals("input modalities for $id", visionInput,
                ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertEquals("abilities for $id", toolReasoning,
                ModelRegistry.MODEL_ABILITIES.getData(id))
        }
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-opus-5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5.5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-opus-5.5"))
        assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-3.5-sonnet"))
        assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-3.7-sonnet"))
    }

    // ------------------------------------------------------------------
    // Fallback invariants
    // ------------------------------------------------------------------

    @Test
    fun unknownModelFallbackUnchanged() {
        val id = "totally-unknown-llm-9000"
        assertEquals(textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
        assertEquals(textOnly, ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id))
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData(id))
        assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData(id))
    }

    @Test
    fun unsupportedBareAndAdjacentAliasesDoNotGainCapabilities() {
        listOf("flash", "step55", "deepseekflash", "mimov26", "gemini-14").forEach { id ->
            assertEquals(id, textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertEquals(id, emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData(id))
            assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData(id))
        }
    }

    @Test
    fun newModelsDoNotGuessContextOrImageOutput() {
        // none of the new definitions declare a context length or image output
        val ids = listOf(
            "gemini-4",
            "deepseek-flash",
            "step-5",
            "mimo-v2.6",
        )
        for (id in ids) {
            assertEquals("output modalities for $id", textOnly,
                ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id))
            assertNull("context length for $id",
                ModelRegistry.MODEL_CONTEXT_LENGTH.getData(id))
        }
    }
}
