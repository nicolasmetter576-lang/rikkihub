package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.mergeCustomHeaders
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** codec, priority and request-identity contracts (no network). */
class ProviderCustomHeadersTest {
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val headers = listOf(CustomHeader("X-Provider", "saved"))
    private fun providers(): List<ProviderSetting> = listOf(
        ProviderSetting.OpenAI(), ProviderSetting.Google(), ProviderSetting.Claude(),
        ProviderSetting.Codex(), ProviderSetting.Devin(), ProviderSetting.Antigravity(),
    )

    @Test fun `all six old provider payloads decode with empty headers`() {
        providers().forEach { provider ->
            val old = JsonObject(codec.encodeToJsonElement<ProviderSetting>(provider).jsonObject - "customHeaders")
            val decoded = codec.decodeFromJsonElement(ProviderSetting.serializer(), old)
            assertEquals(provider.id, decoded.id)
            assertEquals(emptyList<CustomHeader>(), decoded.customHeaders)
        }
    }

    @Test fun `all six provider codecs and copyProvider preserve or explicitly clear headers`() {
        providers().forEach { provider ->
            val saved = provider.copyProvider(customHeaders = headers)
            val decoded = codec.decodeFromString<ProviderSetting>(codec.encodeToString(ProviderSetting.serializer(), saved))
            assertEquals(headers, decoded.customHeaders)
            assertEquals(headers, decoded.copyProvider(name = "renamed").customHeaders)
            assertEquals(emptyList<CustomHeader>(), decoded.copyProvider(customHeaders = emptyList()).customHeaders)
            assertEquals(provider.id, decoded.id)
        }
    }

    @Test fun `nested model provider override roundtrips custom headers`() {
        val override = ProviderSetting.Claude(customHeaders = headers)
        val model = Model(modelId = "model", providerOverwrite = override)
        val restored = codec.decodeFromString<Model>(codec.encodeToString(Model.serializer(), model))
        assertEquals(headers, restored.providerOverwrite!!.customHeaders)
        assertEquals(headers, restored.providerOverwrite!!.copyProvider(models = emptyList()).customHeaders)
    }

    @Test fun `request names replace every provider occurrence without changing request duplicates`() {
        val provider = ProviderSetting.OpenAI(customHeaders = listOf(
            CustomHeader("X-Trace", "p1"), CustomHeader("x-trace", "p2"),
            CustomHeader("X-Other", "p3"), CustomHeader(" ", "ignored"),
        ))
        val merged = provider.mergeCustomHeaders(listOf(
            CustomHeader("x-TRACE", "a1"), CustomHeader("X-Trace", "m1"),
            CustomHeader("", "ignored"),
        ))
        assertEquals(listOf("a1", "m1"), merged.values("X-Trace"))
        assertEquals("p3", merged["X-Other"])
        assertEquals(3, merged.size)
    }

    @Test fun `provider only auth collisions are removed but legacy request auth survives`() {
        val cases = listOf(
            ProviderSetting.OpenAI() to "Authorization",
            ProviderSetting.Claude() to "x-api-key",
            ProviderSetting.Claude() to "anthropic-version",
            ProviderSetting.Google() to "x-goog-api-key",
            ProviderSetting.Google(vertexAI = true, useServiceAccount = true) to "Authorization",
        )
        cases.forEach { (base, name) ->
            val provider = base.copyProvider(customHeaders = listOf(CustomHeader(name.uppercase(), "provider")))
            assertNull(provider.mergeCustomHeaders()[name])
            assertEquals(listOf("request"), provider.mergeCustomHeaders(listOf(CustomHeader(name, "request"))).values(name))
        }
        // A Vertex API-key gateway can legitimately use a separate proxy auth header.
        val vertex = ProviderSetting.Google(vertexAI = true, customHeaders = listOf(CustomHeader("Authorization", "proxy")))
        assertEquals("proxy", vertex.mergeCustomHeaders()["Authorization"])
        assertEquals("bearer", ProviderSetting.Claude(customHeaders = listOf(CustomHeader("Authorization", "bearer")))
            .mergeCustomHeaders()["Authorization"])
    }

    private fun request(provider: ProviderSetting, params: TextGenerationParams, url: String = "https://opencode.ai/v1/chat") =
        Request.Builder().url(url).headers(provider.mergeCustomHeaders(params.customHeaders))
            .configureSessionHeaders(url, params.resolvedSessionId(provider)).build()

    @Test fun `manual provider session wins over only the generated standalone default`() {
        val provider = ProviderSetting.OpenAI(customHeaders = listOf(
            CustomHeader("x-session-id", "provider-session"), CustomHeader("x-opencode-session", "provider-code"),
        ))
        val params = TextGenerationParams(Model(modelId = "m"))
        assertNotNull(params.sessionId)
        assertNull(params.resolvedSessionId(provider))
        val built = request(provider, params)
        assertEquals("provider-session", built.header("X-Session-ID"))
        assertEquals("provider-code", built.header("x-opencode-session"))
        assertEquals(params.sessionId, params.copy(maxTokens = 1).sessionId)
        assertNull(params.copy(maxTokens = 1).resolvedSessionId(provider))
    }

    @Test fun `explicit conversation session still overrides manual provider and request headers`() {
        val provider = ProviderSetting.OpenAI(customHeaders = listOf(CustomHeader("X-Session-ID", "provider")))
        val params = TextGenerationParams(Model(modelId = "m"), customHeaders = listOf(CustomHeader("X-Session-ID", "request")), sessionId = "conversation")
        val built = request(provider, params.copy(maxTokens = 1))
        assertEquals("conversation", built.header("X-Session-ID"))
        assertEquals("conversation", built.header("x-opencode-session"))
    }

    @Test fun `explicit null remains opt out even without manual provider headers`() {
        val params = TextGenerationParams(Model(modelId = "m"), sessionId = null)
        val provider = ProviderSetting.OpenAI()
        assertNull(params.resolvedSessionId(provider))
        assertNull(request(provider, params).header("X-Session-ID"))
    }

    @Test fun `manual request header added by copy also defeats generated standalone identity`() {
        val params = TextGenerationParams(Model(modelId = "m"))
        val copied = params.copy(customHeaders = listOf(CustomHeader("X-Session-ID", "late-manual")))
        assertEquals(params.sessionId, copied.sessionId)
        assertEquals("late-manual", request(ProviderSetting.OpenAI(), copied).header("X-Session-ID"))
    }

    @Test fun `codec preserves generated provenance and stable identity for replay`() {
        val params = TextGenerationParams(Model(modelId = "m"))
        val restored = codec.decodeFromString<TextGenerationParams>(codec.encodeToString(TextGenerationParams.serializer(), params))
        assertEquals(params, restored)
        assertEquals(params.sessionId, restored.resolvedSessionId(ProviderSetting.OpenAI()))
        val provider = ProviderSetting.OpenAI(customHeaders = listOf(CustomHeader("X-Session-ID", "manual")))
        assertNull(restored.copy(maxTokens = 1).resolvedSessionId(provider))
        val explicit = params.copy(sessionId = "conversation")
        val explicitRestored = codec.decodeFromString<TextGenerationParams>(codec.encodeToString(TextGenerationParams.serializer(), explicit))
        assertEquals("conversation", explicitRestored.resolvedSessionId(provider))
    }
}
