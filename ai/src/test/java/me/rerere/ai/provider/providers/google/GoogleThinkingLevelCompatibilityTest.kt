package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

internal fun googleCompletionBody(params: TextGenerationParams): JsonObject {
    val method = GoogleProvider::class.java.getDeclaredMethod(
        "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
    ).apply { isAccessible = true }
    return method.invoke(GoogleProvider(OkHttpClient()), listOf(UIMessage.user("hi")), params) as JsonObject
}

/** Real shared builder, not a replica of the production selector; no service requests. */
@RunWith(Parameterized::class)
class GoogleThinkingLevelCompatibilityTest(
    private val modelId: String,
    private val level: ReasoningLevel,
    private val mode: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0} {1}")
        fun cases(): List<Array<Any>> = listOf(
            "gemini-4-pro" to "level", "gateway/GEMINI-4-FLASH-preview" to "level",
            "gemini-3-flash" to "level", "gemini-2.5-flash" to "budget",
            "gemini-2.5-pro" to "budget_pro", "custom-reasoning-model" to "budget",
        ).flatMap { (id, mode) -> ReasoningLevel.entries.map { arrayOf<Any>(id, it, mode) } }
    }

    @Test fun `reasoning config follows level versus legacy budget contract`() {
        val params = TextGenerationParams(
            model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)), reasoningLevel = level,
        )
        val config = googleCompletionBody(params).getValue("generationConfig").jsonObject.getValue("thinkingConfig")
        val expected = buildJsonObject {
            put("includeThoughts", !(level == ReasoningLevel.OFF && mode == "budget"))
            when {
                level == ReasoningLevel.AUTO -> Unit
                mode == "level" -> put("thinkingLevel", when (level) {
                    ReasoningLevel.OFF -> "minimal"
                    ReasoningLevel.LOW -> "low"
                    ReasoningLevel.MEDIUM -> "medium"
                    else -> "high"
                })
                level != ReasoningLevel.OFF || mode != "budget_pro" -> put("thinkingBudget", level.budgetTokens)
            }
        }
        assertEquals(expected, config)
    }
}
