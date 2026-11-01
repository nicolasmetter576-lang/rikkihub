package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.claudelife.forCacheWarmReplay
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GoogleNewModelRequestTest {
    private val params = TextGenerationParams(
        model = Model(modelId = "gemini-4-pro", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)),
        reasoningLevel = ReasoningLevel.HIGH, maxTokens = 512, sessionId = "original-session",
    )

    @Test fun `saved explicit model capabilities and advanced settings are not automatically overwritten`() {
        val stored = Model(
            modelId = "gemini-4-pro", inputModalities = listOf(Modality.TEXT), abilities = emptyList(),
            customHeaders = listOf(CustomHeader("X-Custom", "keep")), ccDisguise = true,
        )
        val restored = json.decodeFromString<Model>(json.encodeToString(stored))
        assertEquals(stored, restored)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), ModelRegistry.MODEL_INPUT_MODALITIES.getData(stored.modelId))
        assertEquals(listOf(Modality.TEXT), restored.inputModalities)
        assertEquals(emptyList<ModelAbility>(), restored.abilities)
        val body = googleCompletionBody(params.copy(model = restored))
        assertFalse(body.getValue("generationConfig").jsonObject.containsKey("thinkingConfig"))
    }

    @Test fun `custom body retains final override precedence`() {
        val body = googleCompletionBody(params.copy(customBody = listOf(
            CustomBody("generationConfig", buildJsonObject {
                put("thinkingConfig", buildJsonObject { put("thinkingLevel", "low") })
            }),
        )))
        assertEquals("low", body.getValue("generationConfig").jsonObject.getValue("thinkingConfig")
            .jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
    }

    @Test fun `cache replay keeps session and Gemini4 level while only reducing output tokens`() {
        val replay = params.forCacheWarmReplay()
        assertEquals(params.sessionId, replay.sessionId)
        assertEquals(params.model, replay.model)
        val config = googleCompletionBody(replay).getValue("generationConfig").jsonObject
        assertEquals(1, config.getValue("maxOutputTokens").jsonPrimitive.int)
        assertEquals("high", config.getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        assertFalse(config.getValue("thinkingConfig").jsonObject.containsKey("thinkingBudget"))
    }

    private suspend fun capture(vertex: Boolean, stream: Boolean): Request {
        val captured = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured += chain.request()
            val response = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (stream) "data: $response\n\n" else response)
                    .toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        try {
            val provider = GoogleProvider(client)
            val setting = ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "test-key", vertexAI = vertex)
            if (stream) provider.streamText(setting, listOf(UIMessage.user("hi")), params).toList()
            else provider.generateText(setting, listOf(UIMessage.user("hi")), params)
            return captured.single()
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun checkCaptured(request: Request, vertex: Boolean, stream: Boolean) {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val config = json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("generationConfig").jsonObject
        assertEquals(512, config.getValue("maxOutputTokens").jsonPrimitive.int)
        val thinking = config.getValue("thinkingConfig").jsonObject
        assertEquals("high", thinking.getValue("thinkingLevel").jsonPrimitive.content)
        assertEquals(true, thinking.getValue("includeThoughts").jsonPrimitive.boolean)
        assertFalse(thinking.containsKey("thinkingBudget"))
        assertEquals(if (vertex) "aiplatform.googleapis.com" else "offline.invalid", request.url.host)
        val operation = if (stream) "streamGenerateContent" else "generateContent"
        assertEquals(if (vertex) "/v1/publishers/google/models/gemini-4-pro:$operation"
            else "/v1beta/models/gemini-4-pro:$operation", request.url.encodedPath)
    }

    @Test fun `Developer nonstream and stream use the updated shared body builder offline`() = runBlocking {
        listOf(false, true).forEach { checkCaptured(capture(vertex = false, stream = it), vertex = false, stream = it) }
    }

    @Test fun `Vertex nonstream and stream use the updated shared body builder offline`() = runBlocking {
        listOf(false, true).forEach { checkCaptured(capture(vertex = true, stream = it), vertex = true, stream = it) }
    }
}
