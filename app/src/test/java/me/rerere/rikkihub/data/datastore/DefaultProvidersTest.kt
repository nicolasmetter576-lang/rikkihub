package me.rerere.rikkihub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DefaultProvidersTest {
    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }

    @Test
    fun `tencent hunyuan default uses tokenhub base url`() {
        val providers = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.id == Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5") }

        assertEquals(1, providers.size)

        val provider = providers.single()
        assertEquals("腾讯Hunyuan", provider.name)
        assertEquals("https://tokenhub.tencentmaas.com/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
    }

    @Test
    fun `default provider ids stay unique and keep built in entries`() {
        val ids = DEFAULT_PROVIDERS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.contains(DEFAULT_CODEX_PROVIDER_ID))
        assertTrue(ids.contains(DEFAULT_DEVIN_PROVIDER_ID))
        assertTrue(ids.contains(Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5")))
    }

    @Test
    fun `saved provider json keeps user base url and key`() {
        val savedJson = """
            {
              "type": "openai",
              "id": "ef5d149b-8e34-404b-818c-6ec242e5c3c5",
              "enabled": true,
              "name": "腾讯Hunyuan",
              "models": [],
              "balanceOption": {},
              "baseUrl": "https://api.hunyuan.cloud.tencent.com/v1",
              "apiKey": "user-key",
              "useResponseApi": false
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<ProviderSetting>(savedJson)
        val provider = decoded as ProviderSetting.OpenAI

        assertEquals("https://api.hunyuan.cloud.tencent.com/v1", provider.baseUrl)
        assertEquals("user-key", provider.apiKey)
        assertTrue(provider.enabled)
    }
}
