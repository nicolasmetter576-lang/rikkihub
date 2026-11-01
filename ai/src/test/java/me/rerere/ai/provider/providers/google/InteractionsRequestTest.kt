package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** All transports are canned application interceptors. None calls chain.proceed,
 * even for Vertex's hardcoded public host. These are request/SDK contract tests,
 * not evidence of live Gemini compatibility. Schema facts adapted from fixed U. */
class InteractionsRequestTest {
    private val model = Model(modelId = "gemini-3-flash-preview", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
    private val messages = listOf(UIMessage.system("You are a cat."), UIMessage.user("Hello there"))
    private val tool = Tool(name = "save_weather", description = "Save weather", execute = { emptyList() })
    private val api = InteractionsAPI(OkHttpClient())
    private fun objectOf(text: String) = json.parseToJsonElement(text).jsonObject
    private fun Request.bodyJson() = objectOf(Buffer().also { requireNotNull(body).writeTo(it) }.readUtf8())
    private fun params(body: List<CustomBody> = emptyList()) = TextGenerationParams(model = model, tools = listOf(tool), customBody = body)
    private class Capture(private val answer: (Request) -> Pair<String, Boolean>) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (text, stream) = answer(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(text.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    private val interaction = """{"id":"int-offline","status":"completed","model":"gemini-3-flash-preview","steps":[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]}"""
    private val generateContent = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
    private fun interactionsCapture() = Capture { request ->
        val stream = request.bodyJson()["stream"]!!.jsonPrimitive.boolean
        (if (stream) "event: interaction.completed\ndata: {\"interaction\":$interaction}\n\n" else interaction) to stream
    }

    @Test fun `old and default provider codecs leave interactions disabled`() {
        val old = json.decodeFromString<ProviderSetting>("""{"type":"google","apiKey":"old"}""") as ProviderSetting.Google
        assertFalse(old.useInteractionsApi)
        assertFalse(ProviderSetting.Google().useInteractionsApi)
        assertEquals(old, json.decodeFromString<ProviderSetting>(json.encodeToString<ProviderSetting>(old)))
        val enabled = old.copy(useInteractionsApi = true)
        assertTrue((enabled.copyProvider(name = "copy") as ProviderSetting.Google).useInteractionsApi)
        assertTrue((json.decodeFromString<ProviderSetting>(json.encodeToString<ProviderSetting>(enabled)) as ProviderSetting.Google).useInteractionsApi)
    }

    @Test fun `request uses stateless flattened schema and snake case max1`() {
        val p = params().copy(temperature = 0.5f, topP = 0.8f, maxTokens = 1, reasoningLevel = ReasoningLevel.MEDIUM,
            model = model.copy(tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext)))
        val body = api.buildRequestBody(messages, p, true)
        assertEquals("gemini-3-flash-preview", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("previous_interaction_id"))
        assertEquals("You are a cat.", body["system_instruction"]!!.jsonPrimitive.content)
        assertEquals("Hello there", body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val config = body["generation_config"]!!.jsonObject
        assertEquals(1, config["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals("medium", config["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("auto", config["thinking_summaries"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("generationConfig"))
        assertFalse(body.containsKey("safety_settings"))
        assertEquals(listOf("function", "google_search", "url_context"), body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(setOf("save_weather"), interactionsClientToolNames(body))
    }

    @Test fun `thinking levels retain fixed upstream model rules`() {
        fun level(id: String, level: ReasoningLevel) = api.buildRequestBody(messages, params().copy(
            model = model.copy(modelId = id), reasoningLevel = level), false)["generation_config"]!!.jsonObject["thinking_level"]?.jsonPrimitive?.content
        assertNull(level("gemini-3-flash-preview", ReasoningLevel.AUTO))
        assertEquals("minimal", level("gemini-3-flash-preview", ReasoningLevel.OFF))
        assertEquals("low", level("gemini-2.5-flash", ReasoningLevel.OFF))
        assertEquals("high", level("gemini-2.5-pro", ReasoningLevel.XHIGH))
    }

    @Test fun `image model requests formats and omits unsupported system instruction`() {
        val body = api.buildRequestBody(messages, TextGenerationParams(model = Model(modelId = "image", outputModalities = listOf(Modality.TEXT, Modality.IMAGE))), false)
        assertEquals(listOf("text", "image"), body["response_format"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertFalse(body.containsKey("system_instruction"))
    }

    @Test fun `custom body cannot switch stateful store previous id or replace current branch`() {
        for (change in listOf(CustomBody("store", JsonPrimitive(true)), CustomBody("previous_interaction_id", JsonPrimitive("stale")), CustomBody("input", JsonArray(emptyList())))) {
            assertThrows(IllegalArgumentException::class.java) { api.buildRequestBody(messages, params(listOf(change)), false) }
        }
    }

    @Test fun `final custom body declarations freeze exact tool table`() {
        val body = api.buildRequestBody(messages, params(listOf(CustomBody("tools", json.parseToJsonElement("""[{"type":"function","name":"alias","parameters":{"properties":{"name":{"const":"save_weather"}}}},{"type":"google_search","name":"save_weather"}]""")))), false)
        assertEquals(setOf("alias"), interactionsClientToolNames(body))
    }

    @Test fun `wire policy rejects flattened forbidden declaration after custom body merge`() {
        val p = params(listOf(CustomBody("tools", json.parseToJsonElement("""[{"type":"function","name":"blocked"}]""")))).copy(
            workspaceToolPolicy = WorkspaceToolWirePolicy(setOf("blocked")))
        val error = assertThrows(WorkspaceToolPolicyConflictException::class.java) { api.buildRequestBody(messages, p, false) }
        assertEquals(setOf("blocked"), error.names)
        assertEquals("google_interactions", error.protocol)
    }

    @Test fun `wire policy checks actual nested named function selector`() {
        val p = params(listOf(CustomBody("generation_config", objectOf("""{"tool_choice":{"type":"function","name":"blocked"}}""")))).copy(
            workspaceToolPolicy = WorkspaceToolWirePolicy(setOf("blocked")))
        assertThrows(WorkspaceToolPolicyConflictException::class.java) { api.buildRequestBody(messages, p, false) }
    }

    @Test fun `wire policy ignores history schema server and unrelated selector literals`() {
        val body = objectOf("""{"tools":[{"type":"function","name":"safe","parameters":{"name":"blocked","properties":{"name":{"const":"blocked"}}}},{"type":"google_search","name":"blocked"}],"input":[{"type":"function_call","name":"blocked"}],"tool_choice":{"type":"function","name":"blocked"},"generation_config":{"tool_choice":{"type":"google_search","name":"blocked"}},"toolConfig":{"functionCallingConfig":{"allowedFunctionNames":["blocked"]}}}""")
        WorkspaceToolWirePolicy(setOf("blocked")).enforceGoogleInteractionsBody(body)
        assertEquals(setOf("safe"), interactionsClientToolNames(body))
    }

    @Test fun `developer public once and stream delegate only flag enabled`() = runBlocking {
        interactionsCapture().use { c ->
            val sdk = GoogleProvider(c.client)
            val setting = ProviderSetting.Google(useInteractionsApi = true, apiKey = "auto", baseUrl = "https://offline.invalid/v1beta")
            assertEquals("ok", sdk.generateText(setting, messages, params()).message.toText())
            val chunks = withTimeout(10_000) { sdk.streamText(setting, messages, params()).toList() }
            assertEquals(1, chunks.count { it is StreamChunk.Finish })
            assertEquals("int-offline", (chunks.last() as StreamChunk.Finish).responseId)
            assertEquals(listOf(false, true), c.requests.map { it.bodyJson()["stream"]!!.jsonPrimitive.boolean })
            c.requests.forEach { assertEquals("/v1beta/interactions", it.url.encodedPath) }
        }
    }

    @Test fun `default developer and both Vertex flag values keep old text endpoints`() = runBlocking {
        for (vertex in listOf(false, true)) for (flag in listOf(false, true)) {
            if (!vertex && flag) continue
            Capture { request ->
                val stream = request.url.encodedPath.endsWith(":streamGenerateContent")
                (if (stream) "data: $generateContent\n\n" else generateContent) to stream
            }.use { c ->
                val sdk = GoogleProvider(c.client)
                val setting = ProviderSetting.Google(vertexAI = vertex, useInteractionsApi = flag, apiKey = "auto", baseUrl = "https://offline.invalid/v1beta")
                sdk.generateText(setting, messages, params())
                withTimeout(10_000) { sdk.streamText(setting, messages, params()).toList() }
                assertEquals(2, c.requests.size)
                c.requests.forEach { assertTrue(it.bodyJson().containsKey("contents")); assertFalse(it.url.encodedPath.endsWith("/interactions")) }
                if (vertex) c.requests.forEach { assertEquals("aiplatform.googleapis.com", it.url.host); assertEquals("auto", it.url.queryParameter("key")) }
            }
        }
    }

    @Test fun `Vertex service account enabled interactions flag is no op for once and stream`() = runBlocking {
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getEncoder().encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----"
        Capture { request ->
            if (request.url.host == "oauth2.googleapis.com") """{"access_token":"offline-token","expires_in":3600}""" to false
            else {
                val stream = request.url.encodedPath.endsWith(":streamGenerateContent")
                (if (stream) "data: $generateContent\n\n" else generateContent) to stream
            }
        }.use { c ->
            val setting = ProviderSetting.Google(vertexAI = true, useServiceAccount = true, useInteractionsApi = true,
                privateKey = pem, serviceAccountEmail = "offline@test.iam.gserviceaccount.com", projectId = "test")
            val sdk = GoogleProvider(c.client)
            sdk.generateText(setting, messages, params())
            withTimeout(10_000) { sdk.streamText(setting, messages, params()).toList() }
            c.requests.filter { it.url.host == "aiplatform.googleapis.com" }.forEach {
                assertTrue(it.url.encodedPath.contains("/publishers/google/models/"))
                assertTrue(it.bodyJson().containsKey("contents"))
                assertEquals("Bearer offline-token", it.header("Authorization"))
            }
            assertEquals(3, c.requests.size)
        }
    }

    @Test fun `list and unsupported embedding are untouched by enabled flag`() = runBlocking {
        Capture { """{"models":[{"name":"models/test","displayName":"Test","supportedGenerationMethods":["generateContent"]}]}""" to false }.use { c ->
            val sdk = GoogleProvider(c.client)
            val setting = ProviderSetting.Google(useInteractionsApi = true, apiKey = "auto", baseUrl = "https://offline.invalid/v1beta")
            assertEquals("test", sdk.listModels(setting).single().modelId)
            assertEquals("/v1beta/models", c.requests.single().url.encodedPath)
            try { sdk.generateEmbedding(setting, EmbeddingGenerationParams(model, listOf("test"))); fail("unsupported embedding should fail") }
            catch (_: IllegalStateException) { }
            assertEquals(1, c.requests.size)
        }
    }

    @Test fun `headers auth duplicates and complete session copy codec matrix apply to once and stream`() = runBlocking {
        interactionsCapture().use { c ->
            val sdk = GoogleProvider(c.client)
            val base = ProviderSetting.Google(useInteractionsApi = true, apiKey = "auto", baseUrl = "https://opencode.ai/v1beta",
                customHeaders = listOf(CustomHeader("X-Provider", "global"), CustomHeader("X-Override", "global"), CustomHeader("X-Goog-Api-Key", "filtered")))
            val defaults = params().copy(tools = emptyList(), customHeaders = listOf(CustomHeader("x-override", "params")))
            val manualProvider = base.copy(customHeaders = base.customHeaders + CustomHeader("x-session-id", "provider-manual"))
            val manualParams = defaults.copy(customHeaders = defaults.customHeaders + CustomHeader("x-session-id", "params-manual"))
            val cases = listOf(
                Triple(base, defaults, defaults.sessionId),
                Triple(base, defaults.copy(maxTokens = 1), defaults.sessionId),
                Triple(base, json.decodeFromString<TextGenerationParams>(json.encodeToString(defaults)), defaults.sessionId),
                Triple(manualProvider, defaults, "provider-manual"),
                Triple(manualProvider, manualParams, "params-manual"),
                Triple(manualProvider, defaults.copy(sessionId = "conversation"), "conversation"),
                Triple(base, defaults.copy(sessionId = null), null),
                Triple(manualProvider, defaults.copy(sessionId = null), "provider-manual"),
            )
            for ((setting, p, expected) in cases) for (stream in listOf(false, true)) {
                val actual = p.copy(customHeaders = p.customHeaders + CustomHeader("x-goog-api-key", "legacy"))
                withTimeout(10_000) { if (stream) sdk.streamText(setting, messages, actual).toList() else sdk.generateText(setting, messages, actual) }
                val request = c.requests.last()
                assertEquals(listOf("legacy", "auto"), request.headers.values("x-goog-api-key"))
                assertEquals("global", request.header("x-provider"))
                assertEquals(listOf("params"), request.headers.values("x-override"))
                assertEquals(expected, request.header("X-Session-ID"))
                assertEquals(if (p.resolvedSessionId(setting) != null) expected else null, request.header("x-opencode-session"))
            }
            sdk.generateText(base, messages, defaults)
            assertEquals(listOf("auto"), c.requests.last().headers.values("x-goog-api-key"))
        }
    }

    @Test fun `final merged alias is used by real stream decoder instead of params table`() = runBlocking {
        val step = """{"type":"function_call","name":"alias","id":"call","arguments":{}}"""
        Capture { "event: step.start\ndata: {\"index\":0,\"step\":$step}\n\nevent: step.stop\ndata: {\"index\":0}\n\ndata: [DONE]\n\n" to true }.use { c ->
            val p = params(listOf(CustomBody("tools", json.parseToJsonElement("""[{"type":"function","name":"alias"}]"""))))
            val setting = ProviderSetting.Google(useInteractionsApi = true, baseUrl = "https://offline.invalid/v1beta")
            val chunks = withTimeout(10_000) { GoogleProvider(c.client).streamText(setting, messages, p).toList() }
            assertEquals("alias", chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().toolName)
            assertEquals(1, chunks.count { it is StreamChunk.Finish })
        }
    }
}
