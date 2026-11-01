package me.rerere.ai.provider.providers.devin

import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移植 CLIProxyAPI devin_models_test.go 的全部用例.
 */
class DevinModelsTest {

    @Test
    fun `resolve chat model uid table`() {
        data class C(
            val raw: String,
            val level: String = "",
            val budget: Int = 0,
            val want: String,
        )

        val cases = listOf(
            C("claude-fable-5-1-max", want = "claude-fable-5-1-max"),
            C("swe-2-high", want = "swe-2-high"),
            C("swe-2", want = "swe-2-high"),
            C("swe-2", "minimal", want = "swe-2-medium"),
            C("swe-2", "low", want = "swe-2-medium"),
            C("swe-2", "xhigh", want = "swe-2-max"),
            C("swe-2", "max", want = "swe-2-max"),
            C("swe-2(max)", "medium", want = "swe-2-max"),
            C("claude-fable-5-1", budget = 64000, want = "claude-fable-5-1-max"),
            C("claude-fable-5-1", budget = 2048, want = "claude-fable-5-1-low"),
            C("claude-fable-5-1", "xhigh", want = "claude-fable-5-1-xhigh"),
            C("gpt-6-astra(high)", want = "gpt-6-astra-high"),
            C("glm-5-3", "medium", want = "glm-5-3-high"),
            C("devin/glm-5-3-flash", want = "glm-5-3-flash-high"),
            C("devin/glm-5-3-flash:low", want = "glm-5-3-flash-low"),
            C("devin/glm-5-3-flash(max)", want = "glm-5-3-flash-max"),
            C("devin/gpt-5-6-sol:none", want = "gpt-5-6-sol-none"),
            C("devin/gpt-5-6-sol", "none", want = "gpt-5-6-sol-none"),
            C("devin/gpt-5-6-terra:none", want = "gpt-5-6-terra-none"),
            C("devin/nemotron-3-ultra:none", want = "nemotron-3-ultra-none"),
            C("devin/glm-5-2", "high", want = "glm-5-2"),
            C("devin/glm-5-2:none", want = "glm-5-2-none"),
            C("devin/glm-5-2(max)", want = "glm-5-2-max"),
            C("devin/swe-2", want = "swe-2-high"),
            C("Devin/swe-2(max)", want = "swe-2-max"),
            C("devin/claude-fable-5-1", want = "claude-fable-5-1-medium"),
            C("Devin/gpt-6-astra(high)", want = "gpt-6-astra-high"),
            C("devin/swe-2-high", want = "swe-2-high"),
            C("devin/gemini-3-8-flash", want = "gemini-3-8-flash-high"),
            C("devin/gemini-3.8-flash(low)", want = "gemini-3-8-flash-low"),
            C("devin/grok-4-6", want = "grok-4-6-high"),
            C("devin/grok-4.6:xhigh", want = "grok-4-6-xhigh"),
            C("devin/deepseek-v4-flash", want = "deepseek-v4-flash-high"),
            C("devin/deepseek-v4.1-flash(max)", want = "deepseek-v4-1-flash-max"),
            C("devin/swe-1-7", want = "swe-1-7"),
            C("devin/swe-1-7:medium", want = "swe-1-7-medium"),
            C("devin/claude-haiku-4-5", want = "MODEL_PRIVATE_11"),
            C("devin/claude-sonnet-4-5", want = "MODEL_PRIVATE_2"),
            C("devin/claude-sonnet-4-5:high", want = "MODEL_PRIVATE_3"),
            C("devin/gpt-4-1", want = "MODEL_CHAT_GPT_4_1_2025_04_14"),
            C("devin/gemini-3-flash", want = "gemini-3-8-flash-high"),
            C("devin/gpt-5-6-luna", want = "gpt-5-6-luna-low"),
            C("devin/gpt-5-6-luna(high)", want = "gpt-5-6-luna-high"),
            C("devin/gpt-5-6-luna:none", want = "gpt-5-6-luna-none"),
            C("devin/gpt-5-6-sol", want = "gpt-5-6-sol-low"),
            C("devin/gpt-5-6-terra", want = "gpt-5-6-terra-low"),
            C("devin/gpt-5-5", want = "gpt-5-5-low"),
            C("devin/gpt-5-4", want = "gpt-5-4-low"),
            C("devin/gpt-5-4-mini", want = "gpt-5-4-mini-medium"),
            C("devin/gpt-5-3-codex", want = "gpt-5-3-codex-medium"),
            C("devin/claude-opus-5", want = "claude-opus-5-medium"),
            C("devin/claude-opus-4-8", want = "claude-opus-4-8-medium"),
            C("devin/claude-opus-4-7", want = "claude-opus-4-7-medium"),
            C("devin/claude-sonnet-5", want = "claude-sonnet-5-medium"),
            C("devin/gemini-3-7-flash", want = "gemini-3-7-flash-high"),
            C("devin/gemini-3-6-flash", want = "gemini-3-6-flash-high"),
            C("devin/gemini-3-5-flash", want = "gemini-3-5-flash-high"),
            C("devin/deepseek-v4-pro", want = "deepseek-v4-pro-high"),
            C("devin/grok-4-5", want = "grok-4-5-high"),
            C("devin/kimi-k3", want = "kimi-k3-high"),
            C("devin/nemotron-3-ultra", want = "nemotron-3-ultra-high"),
            C("devin/swe-1-6", want = "swe-1-6"),
            C("devin/swe-1-6:fast", want = "swe-1-6-fast"),
            C("devin/kimi-k2-6", want = "kimi-k2-6"),
            C("devin/kimi-k2-7", want = "kimi-k2-7"),
            C("devin/claude-opus-4-6", want = "claude-opus-4-6"),
            C("devin/claude-sonnet-4-6", want = "claude-sonnet-4-6"),
            // 上游 bc06434 新增用例
            C("devin/swe-1-6-slow", want = "swe-1-6-slow"),
            C("swe-1-6-slow", want = "swe-1-6-slow"),
            C("devin/swe-1-6-slow:low", want = "swe-1-6-slow"),
            C("devin/swe-1-6-slow:high", want = "swe-1-6-slow"),
            C("devin/claude-opus-4-6", "high", want = "claude-opus-4-6-thinking"),
            C("devin/claude-sonnet-4-6", "high", want = "claude-sonnet-4-6-thinking"),
            C("devin/claude-opus-4-6-1m", want = "claude-opus-4-6-1m"),
            C("devin/claude-opus-4-6-1m", "high", want = "claude-opus-4-6-thinking-1m"),
            C("devin/claude-sonnet-4-6-1m", want = "claude-sonnet-4-6-1m"),
            C("devin/claude-sonnet-4-6-1m", "high", want = "claude-sonnet-4-6-thinking-1m"),
            C("devin/glm-5-2-1m", want = "glm-5-2-1m"),
            C("devin/glm-5-2-1m", "none", want = "glm-5-2-none-1m"),
            C("devin/glm-5-2-1m", "max", want = "glm-5-2-max-1m"),
            C("devin/MODEL_GPT_5_2", want = "MODEL_GPT_5_2_LOW"),
            C("devin/MODEL_GPT_5_2", "none", want = "MODEL_GPT_5_2_NONE"),
            C("devin/MODEL_GPT_5_2", "medium", want = "MODEL_GPT_5_2_MEDIUM"),
            C("devin/MODEL_GPT_5_2", "high", want = "MODEL_GPT_5_2_HIGH"),
            C("devin/MODEL_GPT_5_2", "xhigh", want = "MODEL_GPT_5_2_XHIGH"),
            C("devin/MODEL_GOOGLE_GEMINI_3_0_FLASH", want = "MODEL_GOOGLE_GEMINI_3_0_FLASH_HIGH"),
            C("devin/MODEL_GOOGLE_GEMINI_3_0_FLASH", "minimal", want = "MODEL_GOOGLE_GEMINI_3_0_FLASH_MINIMAL"),
            C("devin/MODEL_GOOGLE_GEMINI_3_0_FLASH", "low", want = "MODEL_GOOGLE_GEMINI_3_0_FLASH_LOW"),
            C("devin/MODEL_GOOGLE_GEMINI_3_0_FLASH", "medium", want = "MODEL_GOOGLE_GEMINI_3_0_FLASH_MEDIUM"),
            C("devin/MODEL_GOOGLE_GEMINI_3_0_FLASH", "high", want = "MODEL_GOOGLE_GEMINI_3_0_FLASH_HIGH"),
            C("devin/MODEL_CLAUDE_4_5_OPUS", want = "MODEL_CLAUDE_4_5_OPUS"),
            C("devin/MODEL_CLAUDE_4_5_OPUS", "high", want = "MODEL_CLAUDE_4_5_OPUS_THINKING"),
            // 本地: 无 effort 的枚举型 UID 保持大写 (上游会把它们小写化)
            C("MODEL_PRIVATE_11", want = "MODEL_PRIVATE_11"),
            C("devin/model_private_2", want = "MODEL_PRIVATE_2"),
            C("MODEL_CHAT_GPT_4_1_2025_04_14", "high", want = "MODEL_CHAT_GPT_4_1_2025_04_14"),
            C("MODEL_GPT_5_2_XHIGH", want = "MODEL_GPT_5_2_XHIGH"),
            C("claude-opus-4-6-thinking", want = "claude-opus-4-6-thinking"),
            C("claude-opus-5-5-high-fast", want = "claude-opus-5-5-high-fast"),
            C("devin/claude-opus-5-5", want = "claude-opus-5-5-medium"),
            C("devin/claude-opus-5-5", "max", want = "claude-opus-5-5-max"),
            C("devin/gpt-6-sol", want = "gpt-6-sol-medium"),
            C("devin/swe-1-7-lightning", want = "swe-1-7-lightning"),
            C("devin/swe-1-7-lightning", "high", want = "swe-1-7-lightning"),
            C("devin/swe-1-7-lightning:medium", want = "swe-1-7-lightning-medium"),
        )

        for (c in cases) {
            assertEquals(
                "resolveDevinChatModelUid(${c.raw}, ${c.level}, ${c.budget})",
                c.want,
                resolveDevinChatModelUid(c.raw, c.level, c.budget),
            )
        }
    }

    @Test
    fun `all catalog models resolve non empty`() {
        assertTrue(DevinModelCatalog.models.isNotEmpty())
        val efforts = listOf("", "none", "low", "medium", "high", "xhigh", "max")
        for (m in DevinModelCatalog.models) {
            val baseId = m.id.removePrefix("devin/")
            for (eff in efforts) {
                val resolved = resolveDevinChatModelUid("devin/$baseId", eff, 0)
                assertTrue("model $baseId effort $eff resolved empty", resolved.isNotEmpty())
                if (m.thinking != null && m.thinking.levels.isNotEmpty()) {
                    if (!hasDevinEffortSuffix(resolved) &&
                        baseId !in setOf("swe-1-7", "swe-1-7-lightning", "glm-5-2", "glm-5-2-1m", "swe-1-6-slow", "MODEL_CLAUDE_4_5_OPUS")
                    ) {
                        org.junit.Assert.fail(
                            "thinking model $baseId with effort $eff resolved to bare UID $resolved"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `reasoning level overload maps off auto and explicit`() {
        // OFF → none
        assertEquals("gpt-5-6-sol-none", resolveDevinChatModelUid("devin/gpt-5-6-sol", ReasoningLevel.OFF))
        assertEquals("glm-5-2-none", resolveDevinChatModelUid("devin/glm-5-2", ReasoningLevel.OFF))
        // AUTO → 目录默认
        assertEquals("swe-2-high", resolveDevinChatModelUid("swe-2", ReasoningLevel.AUTO))
        assertEquals("gpt-5-6-sol-low", resolveDevinChatModelUid("gpt-5-6-sol", ReasoningLevel.AUTO))
        // 显式
        assertEquals("swe-2-max", resolveDevinChatModelUid("swe-2", ReasoningLevel.MAX))
        assertEquals("swe-2-medium", resolveDevinChatModelUid("swe-2", ReasoningLevel.LOW))
    }

    @Test
    fun `hasDevinEffortSuffix`() {
        assertTrue(hasDevinEffortSuffix("swe-2-high"))
        assertTrue(hasDevinEffortSuffix("GPT-5-6-SOL-NONE"))
        assertTrue(hasDevinEffortSuffix("swe-1-6-fast"))
        assertFalse(hasDevinEffortSuffix("swe-2"))
        assertFalse(hasDevinEffortSuffix("swe-2:high"))
        assertFalse(hasDevinEffortSuffix("glm-5-2:none"))
    }

    @Test
    fun `normalizeDevinThinkingLevel`() {
        assertEquals("low", normalizeDevinThinkingLevel("LOW", 0))
        assertEquals("none", normalizeDevinThinkingLevel("off", 0))
        assertEquals("none", normalizeDevinThinkingLevel("disabled", 0))
        assertEquals("high", normalizeDevinThinkingLevel("auto", 0))
        assertEquals("low", normalizeDevinThinkingLevel("", 2048))
        assertEquals("medium", normalizeDevinThinkingLevel("", 8192))
        assertEquals("high", normalizeDevinThinkingLevel("", 20000))
        assertEquals("max", normalizeDevinThinkingLevel("", 64000))
        assertEquals("", normalizeDevinThinkingLevel("", 0))
        assertEquals("", normalizeDevinThinkingLevel("bogus", 0))
    }

    @Test
    fun `embedded catalog parses`() {
        assertTrue(DevinModelCatalog.models.size > 20)
        val swe2 = DevinModelCatalog.lookup("swe-2")
        assertNotNull(swe2)
        assertEquals(listOf("medium", "high", "max"), swe2!!.thinking!!.levels)
        val namespaced = DevinModelCatalog.lookup("devin/SWE-2")
        assertNotNull(namespaced)
        val opus55 = DevinModelCatalog.lookup("claude-opus-5-5")
        assertNotNull(opus55)
        assertEquals("Claude Opus 5.5", opus55!!.displayName)
        assertNotNull(DevinModelCatalog.lookup("swe-1-6-slow"))
        assertNotNull(DevinModelCatalog.lookup("model_private_11"))
    }
}
