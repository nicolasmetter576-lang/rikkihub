package me.rerere.rikkihub.ui.pages.setting.components

import java.io.File
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class GoogleInteractionsConfigureTest {
    private fun source(path: String) = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun `old Google config defaults off and explicit flag survives codec and copy operations`() {
        val old = JsonInstant.decodeFromString<ProviderSetting>("""{"type":"google"}""") as ProviderSetting.Google
        assertFalse(old.useInteractionsApi)
        val provider = old.copy(useInteractionsApi = true, vertexAI = true,
            models = listOf(Model(modelId = "gemini")), customHeaders = listOf(CustomHeader("X", "v")))
        val roundTrip = JsonInstant.decodeFromString<ProviderSetting>(JsonInstant.encodeToString<ProviderSetting>(provider)) as ProviderSetting.Google
        assertEquals(provider, roundTrip)
        assertTrue((provider.copyProvider(name = "edited") as ProviderSetting.Google).useInteractionsApi)
        assertTrue((provider.resetBaseUrlToDefault() as ProviderSetting.Google).useInteractionsApi)
        assertSame(provider, provider.convertTo(ProviderSetting.Google::class))
        assertEquals(provider.customHeaders, provider.copy(vertexAI = false).customHeaders)
        assertEquals(provider.models, provider.copy(vertexAI = false).models)
    }

    @Test fun `model overwrite roundtrip and provider model edits preserve connection fields`() {
        val overwrite = ProviderSetting.Google(useInteractionsApi = true, vertexAI = true, apiKey = "saved",
            customHeaders = listOf(CustomHeader("X-Saved", "v")))
        val model = Model(modelId = "gemini", providerOverwrite = overwrite)
        val restored = JsonInstant.decodeFromString<Model>(JsonInstant.encodeToString(model))
        assertEquals(overwrite, restored.providerOverwrite)
        val provider = overwrite.copy(models = listOf(model))
        val edited = provider.editModel(model.copy(displayName = "edited")) as ProviderSetting.Google
        assertTrue(edited.useInteractionsApi)
        assertTrue(edited.vertexAI)
        assertEquals(provider.customHeaders, edited.customHeaders)
        assertEquals(provider.apiKey, edited.apiKey)
        assertEquals(overwrite, edited.models.single().providerOverwrite)
    }

    @Test fun `Google only flag is visible checked and disabled under Vertex without clearing`() {
        val ui = source("app/src/main/java/me/rerere/rikkihub/ui/pages/setting/components/ProviderConfigure.kt")
        val google = ui.substringAfter("private fun ProviderConfigureGoogle(")
        assertTrue(google.contains("checked = provider.useInteractionsApi"))
        assertTrue(google.contains("enabled = !provider.vertexAI"))
        assertTrue(google.contains("provider.copy(useInteractionsApi = it)"))
        assertTrue(google.contains("provider.copy(vertexAI = it)"))
        assertFalse(google.contains("useInteractionsApi = false"))
        assertFalse(ui.substringBefore("private fun ProviderConfigureGoogle(").contains("checked = provider.useInteractionsApi"))
        for (locale in listOf("values", "values-zh")) {
            val strings = source("app/src/main/res/$locale/strings.xml")
            for (key in listOf("use_interactions_api", "interactions_hint", "interactions_vertex_hint")) {
                assertTrue(strings.contains("name=\"setting_provider_page_$key\""))
            }
        }
    }
}
