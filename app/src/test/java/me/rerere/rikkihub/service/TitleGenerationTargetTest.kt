package me.rerere.rikkihub.service

import java.io.IOException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class TitleGenerationTargetTest {
    @Test fun `missing fast model throws supplied localized error instead of silently skipping`() {
        var lookedUpProvider = false
        val failure = runCatching {
            requireTitleGenerationTarget(null, { lookedUpProvider = true; null }, "检查快速模型设置", "provider missing")
        }.exceptionOrNull()
        assertEquals("检查快速模型设置", failure?.message)
        assertFalse(lookedUpProvider)
    }

    @Test fun `missing provider throws supplied localized error`() {
        val failure = runCatching {
            requireTitleGenerationTarget(Model(), { null }, "model missing", "Check the quick model provider")
        }.exceptionOrNull()
        assertEquals("Check the quick model provider", failure?.message)
    }

    @Test fun `valid target retains original model and provider request configuration`() {
        val model = Model(modelId = "fast")
        val provider = ProviderSetting.OpenAI(baseUrl = "https://proxy.example/v1", apiKey = "test-key")
        val target = requireTitleGenerationTarget(model, { assertSame(model, it); provider }, "m", "p")
        assertSame(model, target.first)
        assertSame(provider, target.second)
    }

    @Test fun `provider lookup failure remains the original error`() {
        val expected = IOException("lookup failed")
        val failure = runCatching {
            requireTitleGenerationTarget(Model(), { throw expected }, "m", "p")
        }.exceptionOrNull()
        assertSame(expected, failure)
    }
}
