package me.rerere.ai.claudelife

import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.*
import org.junit.Test

class CacheWarmInteractionsOverrideTest {
    private fun check(key: String, value: JsonElement): String? = cacheWarmIncompatibility(
        TextGenerationParams(model = Model(modelId = "gemini"), customBody = listOf(CustomBody(key, value)))
    )

    @Test fun `nested snake case output override is rejected regardless of value`() {
        for (value in listOf(JsonPrimitive(1), JsonPrimitive(100), JsonNull, JsonPrimitive("1"), JsonObject(emptyMap()))) {
            assertEquals("customBody overrides output token limit (generation_config.max_output_tokens)",
                check("generation_config", buildJsonObject { put("max_output_tokens", value) }))
        }
    }

    @Test fun `camel case and top level guards remain`() {
        assertNotNull(check("generationConfig", buildJsonObject { put("maxOutputTokens", 10) }))
        assertNotNull(check("max_output_tokens", JsonPrimitive(10)))
        assertNotNull(check("max_tokens", JsonPrimitive(10)))
    }

    @Test fun `guard is literal protocol path not recursive scan of user data`() {
        assertNull(check("generation_config", buildJsonObject { put("temperature", 0.5) }))
        assertNull(check("generation_config", buildJsonObject { put("literal", buildJsonObject { put("max_output_tokens", 100) }) }))
        assertNull(check("input", buildJsonObject { put("generation_config", buildJsonObject { put("max_output_tokens", 100) }) }))
        assertNull(check("generation_config", JsonPrimitive("{\"max_output_tokens\":100}")))
    }
}
