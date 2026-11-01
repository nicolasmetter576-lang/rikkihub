package me.rerere.rikkihub.web.routes

import java.io.File
import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.*
import org.junit.Test

class GoogleInteractionsUpdateTest {
    private fun raw(provider: ProviderSetting): JsonObject =
        JsonInstant.encodeToJsonElement<ProviderSetting>(provider).jsonObject

    private fun merge(latest: ProviderSetting, body: JsonObject): ProviderSetting {
        return mergeProvider(latest, parseProviderUpdate(body))
    }

    @Test fun `old payload defaults off but missing field preserves latest true models headers and builtin`() {
        val old = ProviderSetting.Google()
        val body = JsonObject(raw(old) - "useInteractionsApi" - "customHeaders")
        val draft = parseProviderUpdate(body)
        assertFalse((draft.incoming as ProviderSetting.Google).useInteractionsApi)
        assertNull(parseGoogleInteractionsField(body))
        val latest = old.copy(
            useInteractionsApi = true, builtIn = true,
            models = listOf(Model(modelId = "concurrent-model")),
            customHeaders = listOf(CustomHeader("X-Latest", "secret")),
            description = {},
        )
        val next = merge(latest, body) as ProviderSetting.Google
        assertTrue(next.useInteractionsApi)
        assertEquals(latest.models, next.models)
        assertEquals(latest.customHeaders, next.customHeaders)
        assertEquals(latest.balanceOption, next.balanceOption)
        assertEquals(latest.description, next.description)
        assertTrue(next.builtIn)
    }

    @Test fun `literal false closes true enables and explicit headers still clear`() {
        val existing = ProviderSetting.Google(useInteractionsApi = true, customHeaders = listOf(CustomHeader("X", "v")))
        for (flag in listOf(false, true)) {
            val body = JsonObject(raw(existing) + mapOf(
                "useInteractionsApi" to JsonPrimitive(flag), "customHeaders" to JsonArray(emptyList()),
            ))
            val next = merge(existing, body) as ProviderSetting.Google
            assertEquals(flag, next.useInteractionsApi)
            assertEquals(emptyList<CustomHeader>(), next.customHeaders)
        }
    }

    @Test fun `null strings numbers and containers fail as bad requests`() {
        for (value in listOf(JsonNull, JsonPrimitive("true"), JsonPrimitive("false"), JsonPrimitive(0),
            JsonPrimitive(1), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            val body = JsonObject(raw(ProviderSetting.Google()) + ("useInteractionsApi" to value))
            assertTrue("Expected 400 for $value", runCatching { parseProviderUpdate(body) }.exceptionOrNull() is BadRequestException)
            assertTrue(runCatching { parseGoogleInteractionsField(body) }.exceptionOrNull() is BadRequestException)
        }
    }

    @Test fun `Vertex does not erase saved flag`() {
        val existing = ProviderSetting.Google(useInteractionsApi = true)
        val body = JsonObject(raw(existing.copy(vertexAI = true)) - "useInteractionsApi")
        val next = merge(existing, body) as ProviderSetting.Google
        assertTrue(next.vertexAI)
        assertTrue(next.useInteractionsApi)
        assertTrue((merge(next, raw(next.copy(vertexAI = false))) as ProviderSetting.Google).useInteractionsApi)
    }

    @Test fun `unknown Google flag does not expand other provider allowlists`() {
        for (provider in listOf(ProviderSetting.OpenAI(), ProviderSetting.Claude(), ProviderSetting.Codex(), ProviderSetting.Devin(), ProviderSetting.Antigravity())) {
            for (value in listOf(JsonPrimitive(true), JsonNull, JsonPrimitive("true"))) {
                val body = JsonObject(raw(provider) + ("useInteractionsApi" to value))
                assertNull(parseGoogleInteractionsField(body))
                assertEquals(provider, merge(provider, body))
            }
        }
    }

    @Test fun `route passes typed raw presence into latest lock merge without whole settings write`() {
        val path = "web-settings/src/main/java/me/rerere/rikkihub/web/routes/ProviderRoutes.kt"
        val source = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
        val block = source.substringAfter("put(\"/{id}\") {").substringBefore("delete(\"/{id}\")")
        assertTrue(block.contains("parseProviderUpdate(raw)"))
        assertTrue(block.contains("mergeProvider(existing, draft)"))
        assertTrue(block.indexOf("settingsStore.update { current ->") < block.indexOf("val existing = current.providers.find"))
        assertFalse(block.contains("settingsFlow.value"))
    }
}
