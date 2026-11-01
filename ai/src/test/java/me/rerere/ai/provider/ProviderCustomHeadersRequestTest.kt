package me.rerere.ai.provider

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ccdisguise.ClaudeCodeDisguise
import me.rerere.ai.ccdisguise.ClaudeCodeDisguiseConfig
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** public provider entry points, real request builders, entirely canned transport.
 * The interceptor never proceeds, including Vertex's hardcoded public host.
 * Model/assistant headers arrive at the adapters as params.customHeaders, as in production.
 */
class ProviderCustomHeadersRequestTest {
    private val messages = listOf(UIMessage.system("keep system"), UIMessage.user("hello"))
    private val model = Model(modelId = "test-model")
    private val global = listOf(
        CustomHeader("X-Provider", "global"), CustomHeader("X-Override", "provider"),
        CustomHeader("  ", "ignored"), CustomHeader("x-session-id", "manual-provider"),
    )
    private val overrides = listOf(CustomHeader("x-OVERRIDE", "params"), CustomHeader("", "ignored"))

    private class Capture(private val response: (Request) -> Pair<String, Boolean>) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (body, stream) = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType()))
                .build()
        }.build()
        override fun close() {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun Request.bodyText() = Buffer().also { requireNotNull(body).writeTo(it) }.readUtf8()
    private fun Request.bodyJson(): JsonObject = json.parseToJsonElement(bodyText()).jsonObject
    private fun checkHeaders(request: Request, overridden: Boolean = true) {
        assertEquals(listOf("global"), request.headers.values("X-Provider"))
        assertEquals(listOf(if (overridden) "params" else "provider"), request.headers.values("X-Override"))
        assertFalse(request.headers.names().any { it.isBlank() })
    }
    private fun checkSession(request: Request, explicit: Boolean) {
        assertEquals(listOf(if (explicit) "conversation" else "manual-provider"), request.headers.values("X-Session-ID"))
    }
    private fun params(legacyAuth: List<CustomHeader> = emptyList(), explicit: Boolean = false) =
        if (explicit) TextGenerationParams(model = model, customHeaders = overrides + legacyAuth, sessionId = "conversation")
        else TextGenerationParams(model = model, customHeaders = overrides + legacyAuth)

    private val chat = """{"id":"chat-test","model":"test-model","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
    private val response = """{"id":"resp-test","model":"test-model","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""
    private val google = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
    private val claude = """{"id":"msg-test","model":"test-model","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""

    @Test fun `OpenAI ChatCompletions and Responses stream and nonstream keep provider headers and legacy auth`() = runBlocking {
        for (responses in listOf(false, true)) for (stream in listOf(false, true)) {
            Capture {
                (if (!stream) { if (responses) response else chat }
                else if (responses) "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$response}\n\n"
                else "data: {\"id\":\"chat-test\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n") to stream
            }.use { capture ->
                val provider = OpenAIProvider(capture.client)
                val setting = ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1", apiKey = "builtin",
                    useResponseApi = responses, customHeaders = global + CustomHeader("aUtHoRiZaTiOn", "provider-credential"))
                // Provider-only credential is filtered; params-level duplicate remains intentional.
                for (legacy in listOf(false, true)) {
                    val p = params(if (legacy) listOf(CustomHeader("authorization", "legacy")) else emptyList(), explicit = legacy)
                    withTimeout(10_000) {
                        if (stream) provider.streamText(setting, messages, p).toList() else provider.generateText(setting, messages, p)
                    }
                    val request = capture.requests.last()
                    assertEquals(if (responses) "/v1/responses" else "/v1/chat/completions", request.url.encodedPath)
                    checkHeaders(request)
                    checkSession(request, legacy)
                    assertEquals(if (legacy) listOf("legacy", "Bearer builtin") else listOf("Bearer builtin"), request.headers.values("Authorization"))
                    assertEquals("test-model", request.bodyJson()["model"]!!.jsonPrimitive.content)
                    assertEquals(stream.toString(), request.bodyJson()["stream"]!!.jsonPrimitive.content)
                }
                assertEquals(2, capture.requests.size)
            }
        }
    }

    @Test fun `OpenAI list balance embedding image generation and multipart edit carry global headers`() = runBlocking {
        Capture { request ->
            (when (request.url.encodedPath) {
                "/v1/models" -> """{"data":[{"id":"test-model"}]}"""
                "/v1/credits" -> """{"data":{"total_usage":12.5}}"""
                "/v1/embeddings" -> """{"model":"test-model","data":[{"embedding":[0.1,0.2]}]}"""
                else -> """{"data":[{"b64_json":"QUJD"}]}"""
            }) to false
        }.use { capture ->
            val provider = OpenAIProvider(capture.client)
            val setting = ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1", apiKey = "builtin",
                customHeaders = global + CustomHeader("AUTHORIZATION", "provider-credential"))
            assertEquals("test-model", provider.listModels(setting).single().modelId)
            assertTrue(provider.getBalance(setting).contains("12"))
            capture.requests.forEach {
                checkHeaders(it, overridden = false)
                assertEquals(listOf("Bearer builtin"), it.headers.values("Authorization"))
                assertEquals("GET", it.method)
            }
            val image = File.createTempFile("g03-image-", ".png")
            try {
                image.writeBytes(byteArrayOf(1, 2, 3))
                for (legacy in listOf(false, true)) {
                    val headers = overrides + if (legacy) listOf(CustomHeader("Authorization", "legacy")) else emptyList()
                    provider.generateEmbedding(setting, EmbeddingGenerationParams(model, listOf("hello"), customHeaders = headers))
                    provider.generateImage(setting, ImageGenerationParams(model, "draw", customHeaders = headers)).toList()
                    provider.editImage(setting, ImageEditParams(model, "edit", listOf(image.absolutePath), customHeaders = headers)).toList()
                    val requests = capture.requests.takeLast(3)
                    assertEquals(listOf("/v1/embeddings", "/v1/images/generations", "/v1/images/edits"), requests.map { it.url.encodedPath })
                    requests.forEach {
                        checkHeaders(it)
                        assertEquals("POST", it.method)
                        assertEquals(if (legacy) listOf("legacy", "Bearer builtin") else listOf("Bearer builtin"), it.headers.values("Authorization"))
                    }
                    assertEquals("hello", requests[0].bodyJson()["input"]!!.jsonPrimitive.content)
                    assertEquals("draw", requests[1].bodyJson()["prompt"]!!.jsonPrimitive.content)
                    val multipart = requests[2].bodyText()
                    assertTrue(multipart.contains("name=\"image\""))
                    assertTrue(multipart.contains("name=\"prompt\""))
                    assertTrue(multipart.contains("\r\n\r\nedit\r\n"))
                }
            } finally { assertTrue(image.delete()) }
            assertEquals(8, capture.requests.size)
        }
    }

    @Test fun `Google developer and Vertex API key list stream and nonstream preserve protocol authentication`() = runBlocking {
        for (vertex in listOf(false, true)) {
            Capture { request ->
                val listing = request.method == "GET"
                val stream = request.url.encodedPath.endsWith(":streamGenerateContent")
                (if (listing) """{"models":[{"name":"models/test-model","displayName":"Test","supportedGenerationMethods":["generateContent"]}]}"""
                else if (stream) "data: $google\n\n" else google) to stream
            }.use { capture ->
                val provider = GoogleProvider(capture.client)
                val setting = ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "builtin", vertexAI = vertex,
                    customHeaders = global + CustomHeader("X-Goog-Api-Key", "provider-credential"))
                assertEquals("test-model", provider.listModels(setting).single().modelId)
                val listing = capture.requests.single()
                checkHeaders(listing, overridden = false)
                assertEquals("100", listing.url.queryParameter("pageSize"))
                for (stream in listOf(false, true)) for (legacy in listOf(false, true)) {
                    val p = params(if (legacy) listOf(CustomHeader("x-goog-api-key", "legacy")) else emptyList(), explicit = legacy)
                    withTimeout(10_000) {
                        if (stream) provider.streamText(setting, messages, p).toList() else provider.generateText(setting, messages, p)
                    }
                    val request = capture.requests.last()
                    checkHeaders(request)
                    checkSession(request, legacy)
                    assertTrue(request.url.encodedPath.endsWith(if (stream) ":streamGenerateContent" else ":generateContent"))
                    assertTrue(request.bodyJson().containsKey("contents"))
                    if (vertex) {
                        assertEquals("aiplatform.googleapis.com", request.url.host)
                        assertEquals(listOf("builtin"), request.url.queryParameterValues("key"))
                        // API-key Vertex does not own this header: it remains a normal custom header.
                        assertEquals(listOf(if (legacy) "legacy" else "provider-credential"), request.headers.values("x-goog-api-key"))
                    } else {
                        assertNull(request.url.queryParameter("key"))
                        assertEquals(if (legacy) listOf("legacy", "builtin") else listOf("builtin"), request.headers.values("x-goog-api-key"))
                    }
                }
                if (vertex) {
                    assertEquals(listOf("builtin"), listing.url.queryParameterValues("key"))
                    assertEquals(listOf("provider-credential"), listing.headers.values("x-goog-api-key"))
                } else assertEquals(listOf("builtin"), listing.headers.values("x-goog-api-key"))
                assertEquals(5, capture.requests.size)
            }
        }
    }

    @Test fun `Vertex service account token exchange is isolated from provider headers and content uses one bearer`() = runBlocking {
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            java.util.Base64.getEncoder().encodeToString(keyPair.private.encoded) + "\n-----END PRIVATE KEY-----"
        Capture { request ->
            when (request.url.host) {
                "oauth2.googleapis.com" -> {
                    assertEquals("/token", request.url.encodedPath)
                    """{"access_token":"account-token","token_type":"Bearer","expires_in":3600}""" to false
                }
                "aiplatform.googleapis.com" -> {
                    val stream = request.url.encodedPath.endsWith(":streamGenerateContent")
                    (if (request.method == "GET")
                        """{"models":[{"name":"models/test-model","displayName":"Test","supportedGenerationMethods":["generateContent"]}]}"""
                    else if (stream) "data: $google\n\n" else google) to stream
                }
                else -> error("Unexpected offline request: ${request.url}")
            }
        }.use { capture ->
            val provider = GoogleProvider(capture.client)
            val setting = ProviderSetting.Google(vertexAI = true, useServiceAccount = true,
                privateKey = pem, serviceAccountEmail = "offline@test-project.iam.gserviceaccount.com",
                projectId = "test-project", location = "us-central1",
                customHeaders = global + CustomHeader("aUtHoRiZaTiOn", "provider-credential"))
            assertEquals("test-model", provider.listModels(setting).single().modelId)
            for (stream in listOf(false, true)) for (legacy in listOf(false, true)) {
                val p = params(if (legacy) listOf(CustomHeader("Authorization", "legacy")) else emptyList(), explicit = legacy)
                withTimeout(10_000) {
                    if (stream) provider.streamText(setting, messages, p).toList() else provider.generateText(setting, messages, p)
                }
                val request = capture.requests.last()
                checkHeaders(request)
                checkSession(request, legacy)
                assertEquals(if (legacy) listOf("legacy", "Bearer account-token") else listOf("Bearer account-token"), request.headers.values("Authorization"))
                assertTrue(request.url.encodedPath.startsWith("/v1/projects/test-project/locations/us-central1/publishers/google/models/"))
                assertNull(request.url.queryParameter("key"))
            }
            val token = capture.requests.single { it.url.host == "oauth2.googleapis.com" }
            assertEquals("POST", token.method)
            assertNull(token.header("Authorization"))
            assertNull(token.header("X-Provider"))
            assertNull(token.header("X-Override"))
            assertNull(token.header("X-Session-ID"))
            val form = token.body as okhttp3.FormBody
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form.value(0))
            assertEquals(3, form.value(1).split('.').size)
            val listing = capture.requests.single { it.method == "GET" }
            checkHeaders(listing, overridden = false)
            assertEquals(listOf("Bearer account-token"), listing.headers.values("Authorization"))
            assertEquals("/v1/projects/test-project/locations/us-central1/models", listing.url.encodedPath)
            // The same provider caches the canned account token across listing and content calls.
            assertEquals(6, capture.requests.size)
        }
    }

    private fun claudeCapture() = Capture { request ->
        val stream = request.body?.let { request.bodyJson()["stream"]?.jsonPrimitive?.content == "true" } == true
        (if (request.method == "GET") """{"data":[{"id":"test-model","display_name":"Test"}]}"""
        else if (stream) "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n" else claude) to stream
    }

    @Test fun `Claude list messages and stream filter provider credentials but retain params auth duplicates`() = runBlocking {
        claudeCapture().use { capture ->
            val provider = ClaudeProvider(capture.client)
            val setting = ProviderSetting.Claude(baseUrl = "https://offline.invalid/v1", apiKey = "builtin",
                customHeaders = global + listOf(CustomHeader("X-API-KEY", "provider-credential"), CustomHeader("ANTHROPIC-VERSION", "provider-version")))
            assertEquals("test-model", provider.listModels(setting).single().modelId)
            val listing = capture.requests.single()
            checkHeaders(listing, overridden = false)
            assertEquals(listOf("builtin"), listing.headers.values("x-api-key"))
            assertEquals(listOf("2023-06-01"), listing.headers.values("anthropic-version"))
            assertNull(listing.header("x-app"))
            for (stream in listOf(false, true)) for (legacy in listOf(false, true)) {
                val p = params(if (legacy) listOf(CustomHeader("x-api-key", "legacy"), CustomHeader("anthropic-version", "legacy-version")) else emptyList(), explicit = legacy)
                withTimeout(10_000) {
                    if (stream) provider.streamText(setting, messages, p).toList() else provider.generateText(setting, messages, p)
                }
                val request = capture.requests.last()
                checkHeaders(request)
                checkSession(request, legacy)
                assertEquals("/v1/messages", request.url.encodedPath)
                assertEquals(if (legacy) listOf("legacy", "builtin") else listOf("builtin"), request.headers.values("x-api-key"))
                assertEquals(if (legacy) listOf("legacy-version", "2023-06-01") else listOf("2023-06-01"), request.headers.values("anthropic-version"))
                assertFalse(request.bodyJson().containsKey("metadata"))
            }
            assertEquals(5, capture.requests.size)
        }
    }

    @Test fun `Claude Code unions effective provider or model beta with CLI tags and keeps identity body`() = runBlocking {
        claudeCapture().use { capture ->
            val config = ClaudeCodeDisguiseConfig(autoUserId = "stable-test-user", anthropicBeta = "cli-beta,shared")
            val provider = ClaudeProvider(capture.client, configProvider = { config })
            val setting = ProviderSetting.Claude(baseUrl = "https://offline.invalid/v1", apiKey = "builtin",
                customHeaders = global + listOf(CustomHeader("anthropic-beta", "provider-beta,shared"),
                    CustomHeader("User-Agent", "provider-agent"), CustomHeader("x-app", "provider-app")))
            for (stream in listOf(false, true)) for (modelOverride in listOf(false, true)) {
                val ccModel = model.copy(ccDisguise = true, customHeaders = if (modelOverride)
                    listOf(CustomHeader("ANTHROPIC-BETA", "model-beta,shared,shared")) else emptyList())
                val p = TextGenerationParams(model = ccModel, customHeaders = overrides + ccModel.customHeaders)
                withTimeout(10_000) {
                    if (stream) provider.streamText(setting, messages, p).toList() else provider.generateText(setting, messages, p)
                }
                val request = capture.requests.last()
                checkHeaders(request)
                checkSession(request, explicit = false)
                assertEquals(listOf("builtin"), request.headers.values("x-api-key"))
                assertEquals(listOf(config.userAgent), request.headers.values("User-Agent"))
                assertEquals(listOf(config.xApp), request.headers.values("x-app"))
                val tags = request.headers.values("anthropic-beta").single().split(',').map { it.trim() }
                assertEquals(setOf(if (modelOverride) "model-beta" else "provider-beta", "shared", "cli-beta"), tags.toSet())
                assertEquals(3, tags.size)
                val body = request.bodyJson()
                assertEquals("stable-test-user", body["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
                val system = body["system"]!!.jsonArray
                assertEquals(ClaudeCodeDisguise.IDENTITY, system.first().jsonObject["text"]!!.jsonPrimitive.content)
                assertEquals("keep system", system.last().jsonObject["text"]!!.jsonPrimitive.content)
                assertEquals(2, system.size)
                assertEquals("hello", body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            }
            // Listing must retain provider headers without applying the model-only disguise.
            provider.listModels(setting)
            val listing = capture.requests.last()
            assertEquals("provider-agent", listing.header("User-Agent"))
            assertEquals("provider-app", listing.header("x-app"))
            assertEquals("provider-beta,shared", listing.header("anthropic-beta"))
            assertEquals(5, capture.requests.size)
        }
    }
}
