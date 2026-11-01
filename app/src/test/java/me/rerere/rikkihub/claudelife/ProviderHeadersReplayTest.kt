package me.rerere.rikkihub.claudelife

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.claudelife.CacheWarmReplayResult
import me.rerere.ai.claudelife.CacheWarmRequest
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.utils.JsonInstant as json
import me.rerere.rikkihub.data.datastore.Settings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** real adapter capture: replay re-resolves provider config, not frozen headers. */
class ProviderHeadersReplayTest {
    private suspend fun replay(
        captured: ProviderSetting.OpenAI,
        latest: ProviderSetting.OpenAI,
        model: Model,
        latestModel: Model = model,
        params: TextGenerationParams = TextGenerationParams(model, maxTokens = 128, customHeaders = listOf(CustomHeader("X-Override", "request"))),
    ): Request {
        val calls = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(("data: {\"id\":\"r\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val settings = Settings(providers = listOf(latest.copy(models = listOf(latestModel))))
        val resolved = resolveReplayProvider(model, captured, settings)!!
        val messages = listOf(UIMessage.user("hi"))
        val request = CacheWarmRequest(captured, messages, params, messages)
        @Suppress("UNCHECKED_CAST")
        val impl = OpenAIProvider(client) as Provider<ProviderSetting>
        val result = runCacheWarmReplay(impl, resolved, request)
        assertTrue("$result", result is CacheWarmReplayResult.Succeeded)
        assertEquals(1, calls.size)
        val actual = calls.single()
        val buffer = Buffer()
        actual.body!!.writeTo(buffer)
        val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("1", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals(128, params.maxTokens)
        return actual
    }

    @Test fun `replay uses latest provider headers credentials URL and preserves request override`() = runBlocking {
        val model = Model(modelId = "m")
        val captured = ProviderSetting.OpenAI(baseUrl = "https://old.invalid/v1", apiKey = "old", customHeaders = listOf(CustomHeader("X-Provider", "old")))
        val latest = captured.copy(baseUrl = "https://new.invalid/v1", apiKey = "new", customHeaders = listOf(
            CustomHeader("X-Provider", "new"), CustomHeader("X-Override", "provider"),
            CustomHeader("Authorization", "must-not-win"), CustomHeader("X-Session-ID", "manual"),
        ))
        val actual = replay(captured, latest, model)
        assertEquals("new.invalid", actual.url.host)
        assertEquals("new", actual.header("X-Provider"))
        assertEquals(listOf("Bearer new"), actual.headers.values("Authorization"))
        assertEquals(listOf("request"), actual.headers.values("X-Override"))
        assertEquals("manual", actual.header("X-Session-ID"))
    }

    @Test fun `clearing latest provider headers cannot reintroduce captured ones`() = runBlocking {
        val model = Model(modelId = "m")
        val captured = ProviderSetting.OpenAI(baseUrl = "https://old.invalid/v1", apiKey = "k", customHeaders = listOf(CustomHeader("X-Provider", "old")))
        val params = TextGenerationParams(model, maxTokens = 128)
        val actual = replay(captured, captured.copy(customHeaders = emptyList()), model, params = params)
        assertNull(actual.header("X-Provider"))
        assertEquals(params.sessionId, actual.header("X-Session-ID"))
    }

    @Test fun `latest model provider overwrite carries headers and explicit conversation priority`() = runBlocking {
        val model = Model(modelId = "m")
        val captured = ProviderSetting.OpenAI(baseUrl = "https://old.invalid/v1", apiKey = "old")
        val overwrite = ProviderSetting.OpenAI(baseUrl = "https://override.invalid/v1", apiKey = "override", customHeaders = listOf(
            CustomHeader("X-Provider", "override"), CustomHeader("X-Session-ID", "manual"),
        ))
        val actual = replay(captured, captured, model, latestModel = model.copy(providerOverwrite = overwrite),
            params = TextGenerationParams(model, maxTokens = 128, sessionId = "conversation"))
        assertEquals("override.invalid", actual.url.host)
        assertEquals("override", actual.header("X-Provider"))
        assertEquals(listOf("Bearer override"), actual.headers.values("Authorization"))
        assertEquals("conversation", actual.header("X-Session-ID"))
    }
}
