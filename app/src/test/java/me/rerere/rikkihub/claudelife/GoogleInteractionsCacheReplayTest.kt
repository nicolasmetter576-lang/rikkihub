package me.rerere.rikkihub.claudelife

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.claudelife.CacheWarmReplayResult
import me.rerere.ai.claudelife.CacheWarmRequest
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Offline intercepted real SDK streaming replay; no network or observer changes. */
class GoogleInteractionsCacheReplayTest {
    private suspend fun capture(
        captured: ProviderSetting.Google,
        latest: ProviderSetting.Google,
        model: Model = Model(modelId = "gemini-2.5-flash"),
        latestModel: Model = model,
    ): Request {
        val calls = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request()
            val event = if (latest.useInteractionsApi && !latest.vertexAI) {
                "event: interaction.created\ndata: {\"interaction\":{\"id\":\"warm\",\"model\":\"gemini-2.5-flash\"}}\n\n" +
                    "event: interaction.completed\ndata: {\"interaction\":{\"id\":\"warm\",\"status\":\"completed\"}}\n\n"
            } else {
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(event.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val settings = Settings(providers = listOf(latest.copy(models = listOf(latestModel))))
            val resolved = resolveReplayProvider(model, captured, settings)!!
            val messages = listOf(UIMessage.user("hi"))
            val params = TextGenerationParams(model, maxTokens = 256, sessionId = "conversation",
                customHeaders = listOf(CustomHeader("X-Override", "request")))
            val baseline = CacheWarmRequest(captured, messages, params, messages)
            @Suppress("UNCHECKED_CAST")
            val impl = GoogleProvider(client) as Provider<ProviderSetting>
            val result = withTimeout(10_000) { runCacheWarmReplay(impl, resolved, baseline) }
            assertTrue("$result", result is CacheWarmReplayResult.Succeeded)
            assertEquals(1, calls.size)
            assertEquals(256, params.maxTokens)
            return calls.single()
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun body(request: Request): JsonObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JsonInstant.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    @Test fun `Interactions replay uses max output 1 latest credentials URL headers and conversation identity`() = runBlocking {
        val captured = ProviderSetting.Google(useInteractionsApi = true, baseUrl = "https://old.invalid/v1beta", apiKey = "old")
        val latest = captured.copy(baseUrl = "https://new.invalid/v1beta", apiKey = "new", customHeaders = listOf(
            CustomHeader("X-Provider", "latest"), CustomHeader("X-Override", "provider"),
            CustomHeader("x-goog-api-key", "must-not-win"), CustomHeader("X-Session-ID", "manual"),
        ))
        val request = capture(captured, latest)
        assertEquals("new.invalid", request.url.host)
        assertEquals("/v1beta/interactions", request.url.encodedPath)
        assertEquals("latest", request.header("X-Provider"))
        assertEquals(listOf("new"), request.headers.values("x-goog-api-key"))
        assertEquals(listOf("request"), request.headers.values("X-Override"))
        assertEquals("conversation", request.header("X-Session-ID"))
        val body = body(request)
        assertEquals(1, body.getValue("generation_config").jsonObject.getValue("max_output_tokens").jsonPrimitive.int)
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertFalse(body.containsKey("previous_interaction_id"))
    }

    @Test fun `default and Vertex keep generateContent stream max1 even with saved Vertex flag true`() = runBlocking {
        for (setting in listOf(ProviderSetting.Google(), ProviderSetting.Google(vertexAI = true, useInteractionsApi = true))) {
            val configured = setting.copy(baseUrl = "https://offline.invalid/v1beta", apiKey = "key")
            val request = capture(configured, configured)
            assertTrue(request.url.encodedPath.endsWith(":streamGenerateContent"))
            assertEquals(1, body(request).getValue("generationConfig").jsonObject.getValue("maxOutputTokens").jsonPrimitive.int)
            assertFalse(body(request).containsKey("generation_config"))
        }
    }

    @Test fun `latest overwrite carries current Interactions credentials headers and endpoint`() = runBlocking {
        val staleOverwrite = ProviderSetting.Google(useInteractionsApi = true, baseUrl = "https://stale.invalid/v1beta", apiKey = "stale")
        val model = Model(modelId = "gemini-2.5-flash", providerOverwrite = staleOverwrite)
        val overwrite = staleOverwrite.copy(baseUrl = "https://override.invalid/v1beta", apiKey = "new",
            customHeaders = listOf(CustomHeader("X-Provider", "override")))
        val parent = ProviderSetting.Google(useInteractionsApi = true)
        val request = capture(staleOverwrite, parent, model, model.copy(providerOverwrite = overwrite))
        assertEquals("override.invalid", request.url.host)
        assertEquals("override", request.header("X-Provider"))
        assertEquals("new", request.header("x-goog-api-key"))
        assertEquals(1, body(request).getValue("generation_config").jsonObject.getValue("max_output_tokens").jsonPrimitive.int)
    }
}
