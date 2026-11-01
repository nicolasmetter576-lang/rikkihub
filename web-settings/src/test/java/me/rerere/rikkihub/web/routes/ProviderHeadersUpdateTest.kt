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

/** Tests production provider PUT parser/merge, not a duplicate decoder. */
class ProviderHeadersUpdateTest {
    private val saved = listOf(CustomHeader("X-Saved", "secret"))
    private fun providers(): List<ProviderSetting> = listOf(
        ProviderSetting.OpenAI(), ProviderSetting.Google(), ProviderSetting.Claude(),
        ProviderSetting.Codex(), ProviderSetting.Devin(), ProviderSetting.Antigravity(),
    ).map { it.copyProvider(customHeaders = saved, models = listOf(Model(modelId = "kept-model"))) }
    private fun raw(provider: ProviderSetting): JsonObject = JsonInstant.encodeToJsonElement<ProviderSetting>(provider).jsonObject
    private fun field(value: JsonElement): JsonObject = JsonObject(raw(ProviderSetting.OpenAI()) + ("customHeaders" to value))
    private fun entry(name: JsonElement = JsonPrimitive("X-Test"), value: JsonElement = JsonPrimitive("value")) =
        buildJsonObject { put("name", name); put("value", value) }
    private fun rejected(body: JsonObject) {
        val failure = runCatching { parseProviderUpdate(body) }.exceptionOrNull()
        assertTrue("Expected 400, got $failure", failure is BadRequestException)
    }

    @Test fun `missing headers preserve latest values for all six types`() {
        providers().forEach { latest ->
            val draft = parseProviderUpdate(JsonObject(raw(latest) - "customHeaders"))
            assertNull(draft.customHeaders)
            assertEquals(saved, mergeProvider(latest, draft).customHeaders)
        }
    }

    @Test fun `explicit empty array clears all six types and keeps latest models and local fields`() {
        providers().forEach { existing ->
            val latest = existing.copyProvider(name = "latest", models = existing.models + Model(modelId = "concurrent-model"))
            val stale = JsonObject(raw(existing) + ("customHeaders" to JsonArray(emptyList())))
            val draft = parseProviderUpdate(stale)
            val next = mergeProvider(latest, draft)
            assertNotNull(draft.customHeaders)
            assertEquals(emptyList<CustomHeader>(), next.customHeaders)
            assertEquals(latest.models, next.models)
            assertEquals(latest.id, next.id)
            assertEquals(latest.balanceOption, next.balanceOption)
            assertEquals(latest.builtIn, next.builtIn)
            if (latest is ProviderSetting.Devin) assertEquals(latest.deviceSeed, (next as ProviderSetting.Devin).deviceSeed)
        }
    }

    @Test fun `explicit list replaces rather than append or merge and preserves duplicate row order`() {
        val replacement = listOf(CustomHeader("X-Dup", "one"), CustomHeader("x-dup", "two"))
        providers().forEach { existing ->
            val body = raw(existing.copyProvider(customHeaders = replacement))
            val draft = parseProviderUpdate(body)
            assertEquals(replacement, mergeProvider(existing, draft).customHeaders)
        }
    }

    @Test fun `blank draft name is allowed and ignored by HTTP builder`() {
        val body = field(JsonArray(listOf(entry(JsonPrimitive(" ")))))
        val draft = parseProviderUpdate(body)
        assertNotNull(draft.customHeaders)
        assertEquals(" ", draft.customHeaders!!.single().name)
    }

    @Test fun `null object string number and boolean collections are rejected`() {
        listOf(JsonNull, JsonObject(emptyMap()), JsonPrimitive("[]"), JsonPrimitive(1), JsonPrimitive(false))
            .forEach { rejected(field(it)) }
    }

    @Test fun `nonobject entries and missing fields are rejected`() {
        listOf(JsonNull, JsonPrimitive("header"), JsonPrimitive(3), JsonArray(emptyList()),
            buildJsonObject { put("name", "X-Test") }, buildJsonObject { put("value", "v") })
            .forEach { rejected(field(JsonArray(listOf(it)))) }
    }

    @Test fun `names and values must be literal strings even if codec coerces primitives`() {
        listOf(JsonNull, JsonPrimitive(12), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))
            .forEach { value ->
                rejected(field(JsonArray(listOf(entry(name = value)))))
                if (value == JsonNull) {
                    val draft = parseProviderUpdate(field(JsonArray(listOf(entry(value = value)))))
                    assertNull(draft.customHeaders!!.single().newValue) // masked null preserves
                } else {
                    rejected(field(JsonArray(listOf(entry(value = value)))))
                }
            }
    }

    @Test fun `invalid token names and CRLF DEL Unicode values are rejected without secret leakage`() {
        listOf("bad name", "bad:name", "line\n", "\r\n", "名", "bad\u007f")
            .forEach { rejected(field(JsonArray(listOf(entry(JsonPrimitive(it)))))) }
        listOf("secret\r\ninjected: true", "secret\u0000", "secret\u007f", "secret值")
            .forEach { value ->
                val body = field(JsonArray(listOf(entry(value = JsonPrimitive(value)))))
                val failure = runCatching { parseProviderUpdate(body) }.exceptionOrNull()
                assertTrue(failure is BadRequestException)
                assertFalse(failure!!.message.orEmpty().contains("secret"))
            }
    }

    @Test fun `empty values and horizontal tab are valid`() {
        val draft = parseProviderUpdate(field(JsonArray(listOf(entry(value = JsonPrimitive("")), entry(value = JsonPrimitive("a\tb"))))))
        assertEquals(listOf("", "a\tb"), draft.customHeaders!!.map { it.newValue })
    }

    @Test fun `malformed provider codec and forbidden type conversion are 400`() {
        rejected(buildJsonObject { put("type", "unknown") })
        rejected(JsonObject(raw(ProviderSetting.OpenAI()) + ("enabled" to JsonPrimitive("invalid"))))
        val failure = runCatching {
            mergeProvider(
                ProviderSetting.OpenAI(),
                ProviderUpdateDraft(ProviderSetting.Google(), null, null, null, null),
            )
        }.exceptionOrNull()
        assertTrue(failure is BadRequestException)
    }

    @Test fun `stale missing-header editor cannot erase concurrent headers or models`() {
        val before = ProviderSetting.OpenAI(customHeaders = saved)
        val staleBody = JsonObject(raw(before) - "customHeaders")
        val latest = before.copy(customHeaders = listOf(CustomHeader("X-New", "new")), models = listOf(Model(modelId = "added")))
        val draft = parseProviderUpdate(staleBody)
        val next = mergeProvider(latest, draft) as ProviderSetting.OpenAI
        assertEquals(latest.customHeaders, next.customHeaders)
        assertEquals(latest.models, next.models)
    }

    @Test fun `actual route parses raw presence and merges inside latest settings update`() {
        val path = "web-settings/src/main/java/me/rerere/rikkihub/web/routes/ProviderRoutes.kt"
        val source = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
        val block = source.substringAfter("put(\"/{id}\") {").substringBefore("delete(\"/{id}\")")
        assertTrue(block.contains("parseProviderUpdate(raw)"))
        assertTrue(block.indexOf("settingsStore.update { current ->") < block.indexOf("val existing = current.providers.find"))
        assertTrue(block.contains("mergeProvider(existing, draft)"))
        assertFalse(block.contains("settingsFlow.value"))
        assertTrue(block.indexOf("call.respond") > block.indexOf("settingsStore.update"))
    }
}
