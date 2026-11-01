package me.rerere.rikkihub.ui.pages.setting.components

import java.io.File
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ProviderHeadersConfigureTest {
    @Test fun `standard provider type conversion keeps headers identity and model configuration`() {
        val headers = listOf(CustomHeader("X-Provider", "saved"))
        val model = Model(modelId = "m", customHeaders = listOf(CustomHeader("X-Model", "saved")))
        val sources = listOf(ProviderSetting.OpenAI(customHeaders = headers, models = listOf(model)),
            ProviderSetting.Google(customHeaders = headers, models = listOf(model)),
            ProviderSetting.Claude(customHeaders = headers, models = listOf(model)))
        sources.forEach { source ->
            listOf(ProviderSetting.OpenAI::class, ProviderSetting.Google::class, ProviderSetting.Claude::class).forEach { target ->
                val converted = source.convertTo(target)
                assertEquals(headers, converted.customHeaders)
                assertEquals(source.id, converted.id)
                assertEquals(source.models, converted.models)
                assertEquals(source.balanceOption, converted.balanceOption)
                assertEquals(source.builtIn, converted.builtIn)
            }
        }
    }

    @Test fun `App provider editors and shared name picker keep existing model editor wired`() {
        fun source(path: String) = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
        val root = "app/src/main/java/me/rerere/rikkihub/ui/pages/"
        val standard = source(root + "setting/SettingProviderDetailPage.kt")
        assertTrue(standard.contains("headers = internalProvider.customHeaders"))
        assertTrue(standard.contains("internalProvider.copyProvider(customHeaders = it)"))
        assertTrue(standard.contains("headers = model.customHeaders"))
        listOf("Codex", "Devin", "Antigravity").forEach { type ->
            assertTrue(source(root + "setting/components/${type}ProviderConfigure.kt")
                .contains("CustomHeaders(provider.customHeaders) { onEdit(provider.copy(customHeaders = it)) }"))
        }
        val editor = source(root + "assistant/detail/PropertyEditor.kt")
        assertTrue(editor.contains("R.string.header_common_names"))
        assertTrue(editor.contains("old.copy(name = name)"))
        assertTrue(editor.contains("CustomHeader(\"\", \"\")"))
        listOf("Authorization", "User-Agent", "anthropic-beta", "X-Session-ID").forEach {
            assertTrue(editor.contains("\"$it\""))
        }
    }
}
