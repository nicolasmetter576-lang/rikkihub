package me.rerere.ai.provider.providers.antigravity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AntigravityWireTest {
    @Test
    fun `stream events are unwrapped and keepalives ignored`() {
        val unwrapped = unwrapAntigravityEvent(
            """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]}}]},"traceId":"t"}""",
            null,
        )!!
        assertTrue(unwrapped.startsWith("{\"candidates\""))
        assertNull(unwrapAntigravityEvent("""{"response":{}}""", null))
        assertNull(unwrapAntigravityEvent("{}", null))
        assertNull(unwrapAntigravityEvent("  ", null))
    }

    @Test
    fun `stream error events become http exceptions with status`() {
        try {
            unwrapAntigravityEvent("""{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}""", null)
            fail("expected an exception")
        } catch (error: HttpException) {
            assertEquals(429, error.statusCode)
            assertTrue(error.message!!.contains("Resource exhausted"))
        }
    }

    @Test
    fun `claude function call placeholders are stripped from stream args`() {
        val schemas = mapOf("search" to json.parseToJsonElement("""{"type":"object","properties":{"q":{"type":"string"}}}"""))
        val out = unwrapAntigravityEvent(
            """{"response":{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"search","args":{"q":"x","_":true}}}]}}]}}""",
            schemas,
        )!!
        val args = json.parseToJsonElement(out).jsonObject["candidates"]!!.let { it as kotlinx.serialization.json.JsonArray }[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.let { it as kotlinx.serialization.json.JsonArray }[0]
            .jsonObject["functionCall"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("q"), args.keys)
    }

    @Test
    fun `retry delay comes from RetryInfo then quotaResetDelay`() {
        val retryInfo = antigravityHttpError(
            429,
            """{"error":{"code":429,"message":"slow down","details":[
              {"@type":"type.googleapis.com/google.rpc.ErrorInfo","metadata":{"quotaResetDelay":"1h0m0s"}},
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3.5s"}]}}""",
            null,
        )
        assertEquals(429, retryInfo.statusCode)
        assertEquals(3_500L, retryInfo.retryAfterMillis)
        assertEquals("Antigravity 429: slow down", retryInfo.message)

        val quota = antigravityHttpError(
            429,
            """{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","metadata":{"quotaResetDelay":"2m30s"}}]}}""",
            null,
        )
        assertEquals(150_000L, quota.retryAfterMillis)
        assertEquals(7_000L, antigravityHttpError(429, "", "7").retryAfterMillis)
        assertEquals("Antigravity 500: oops", antigravityHttpError(500, "oops", null).message)
    }

    @Test
    fun `go durations`() {
        assertEquals(250L, parseGoDurationMillis("250ms"))
        assertEquals(3_723_400L, parseGoDurationMillis("1h2m3.4s"))
        assertNull(parseGoDurationMillis("abc"))
        assertNull(parseGoDurationMillis("5x"))
    }

    @Test
    fun `token and project responses`() {
        val tokens = parseTokenResponse("""{"access_token":"a","refresh_token":"r","expires_in":3599,"token_type":"Bearer"}""")
        assertEquals(AntigravityTokens("a", "r", 3599), tokens)
        assertNull(parseTokenResponse("""{"access_token":"a"}""").refreshToken)

        fun obj(text: String) = json.parseToJsonElement(text) as JsonObject
        assertEquals("p1", extractProjectId(obj("""{"cloudaicompanionProject":"p1"}""")))
        assertEquals("p2", extractProjectId(obj("""{"cloudaicompanionProject":{"id":"p2"}}""")))
        assertNull(extractProjectId(obj("""{"cloudaicompanionProject":""}""")))
        assertEquals("standard-tier", defaultTierId(obj("""{"allowedTiers":[{"id":"free-tier"},{"id":"standard-tier","isDefault":true}]}""")))
        assertEquals("legacy", defaultTierId(obj("""{"currentTier":{"id":"legacy"}}""")))
        assertEquals("free-tier", defaultTierId(obj("{}")))
    }

    @Test
    fun `model catalog filters internal models and maps abilities`() {
        val catalog = parseAntigravityModelCatalog(
            """{"models":{
              "gemini-3-flash":{"displayName":"Gemini 3 Flash","maxTokens":1048576,"maxOutputTokens":65536,"quotaInfo":{"remainingFraction":0.25,"resetTime":"2026-10-14T00:00:00Z"}},
              "claude-sonnet-4-6":{"displayName":"Claude Sonnet 4.6"},
              "gpt-oss-120b-medium":{},
              "gemini-3.1-flash-image":{"displayName":"Image"},
              "chat_20706":{},"tab_flash_lite_preview":{},"gemini-2.5-pro":{}
            },"webSearchModelIds":["Gemini-3-Flash"]}"""
        )
        assertEquals(
            listOf("claude-sonnet-4-6", "gemini-3-flash", "gemini-3.1-flash-image", "gpt-oss-120b-medium"),
            catalog.models.map { it.id },
        )
        val flash = catalog.models.first { it.id == "gemini-3-flash" }
        assertEquals(0.25, flash.remainingFraction!!, 0.0)
        assertEquals("2026-10-14T00:00:00Z", flash.resetTime)
        assertEquals(65536L, flash.maxOutputTokens)
        assertEquals(setOf("gemini-3-flash"), catalog.webSearchModelIds)

        val models = catalog.models.associate { it.id to it.toModel() }
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), models["claude-sonnet-4-6"]!!.abilities)
        assertEquals(listOf(ModelAbility.TOOL), models["gpt-oss-120b-medium"]!!.abilities)
        assertEquals(listOf(Modality.TEXT), models["gpt-oss-120b-medium"]!!.inputModalities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models["gemini-3.1-flash-image"]!!.outputModalities)
        assertEquals("gpt-oss-120b-medium", models["gpt-oss-120b-medium"]!!.displayName)
    }

    @Test
    fun `manifest version parsing`() {
        assertEquals("2.10.3", AntigravityVersion.parseManifestVersion("version: 2.10.3\nfiles:\n  - url: x"))
        assertEquals("2.10.3", AntigravityVersion.parseManifestVersion("version: '2.10.3'"))
        assertNull(AntigravityVersion.parseManifestVersion("version: 2.10"))
        assertNull(AntigravityVersion.parseManifestVersion("files: []"))
        assertEquals("antigravity/hub/2.9.1 darwin/arm64", AntigravityVersion.userAgent(AntigravityVersion.FALLBACK))
    }

    @Test
    fun `auth url matches the CPA parameters`() {
        val url = AntigravityChatApi(OkHttpClient()).buildAuthUrl("st", "http://localhost:51121/oauth-callback").toHttpUrl()
        assertEquals("accounts.google.com", url.host)
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals("consent", url.queryParameter("prompt"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(AntigravityChatApi.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals("http://localhost:51121/oauth-callback", url.queryParameter("redirect_uri"))
        assertEquals("st", url.queryParameter("state"))
        assertEquals(AntigravityChatApi.SCOPES.joinToString(" "), url.queryParameter("scope"))
        assertNull(url.queryParameter("code_challenge"))
    }

    // ---- HTTP（拦截器假响应） ----

    private fun client(captured: MutableList<Request>, respond: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            captured += request
            val (code, body) = respond(request)
            val type = if (request.url.encodedPath.endsWith(":streamGenerateContent")) "text/event-stream" else "application/json"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test
    fun `stream request uses the daily endpoint, bearer token and antigravity UA`() = runBlocking {
        val captured = mutableListOf<Request>()
        val sse = """data: {"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}}

data: {"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}}

"""
        val api = AntigravityChatApi(client(captured) { 200 to sse }, baseUrl = "https://daily.invalid")
        val chunks = api.streamGenerate(
            AntigravityStreamRequest(
                accessToken = "tok",
                body = json.parseToJsonElement("""{"model":"gemini-3-flash","request":{}}""").jsonObject,
                modelId = "gemini-3-flash",
                customHeaders = headersOf("X-Custom", "1", "Authorization", "wrong", "User-Agent", "wrong"),
            )
        ).toList()
        val request = captured.single()
        assertEquals("https://daily.invalid/v1internal:streamGenerateContent?alt=sse", request.url.toString())
        assertEquals(listOf("Bearer tok"), request.headers.values("Authorization"))
        assertEquals(listOf("antigravity/hub/2.9.1 darwin/arm64"), request.headers.values("User-Agent"))
        assertEquals("1", request.header("X-Custom"))
        assertEquals("gemini-3-flash", json.parseToJsonElement(request.bodyText()).jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("Hello", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals(1, chunks.count { it is StreamChunk.Finish })
    }

    @Test
    fun `stream http failure carries status and retry delay`() = runBlocking {
        val api = AntigravityChatApi(
            client(mutableListOf()) {
                429 to """{"error":{"code":429,"message":"quota","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"12s"}]}}"""
            },
            baseUrl = "https://daily.invalid",
        )
        try {
            api.streamGenerate(
                AntigravityStreamRequest("tok", JsonObject(emptyMap()), "gemini-3-flash")
            ).toList()
            fail("expected failure")
        } catch (error: HttpException) {
            assertEquals(429, error.statusCode)
            assertEquals(12_000L, error.retryAfterMillis)
        }
    }

    @Test
    fun `project discovery onboards when loadCodeAssist has no project`() = runBlocking {
        val captured = mutableListOf<Request>()
        val api = AntigravityChatApi(
            client(captured) { request ->
                when {
                    request.url.encodedPath.endsWith(":loadCodeAssist") ->
                        200 to """{"allowedTiers":[{"id":"free-tier","isDefault":true}]}"""
                    request.url.encodedPath.endsWith(":onboardUser") ->
                        200 to """{"done":true,"response":{"cloudaicompanionProject":{"id":"onboarded"}}}"""
                    else -> 404 to "{}"
                }
            },
            baseUrl = "https://daily.invalid",
            controlBaseUrl = "https://prod.invalid",
        )
        assertEquals("onboarded", api.fetchProjectId("tok"))
        val load = captured[0]
        assertEquals("prod.invalid", load.url.host)
        assertEquals("""{"metadata":{"ideType":"ANTIGRAVITY"}}""", load.bodyText())
        val onboard = captured[1]
        assertEquals("daily.invalid", onboard.url.host)
        assertTrue(onboard.header("User-Agent")!!.endsWith(" google-api-nodejs-client/10.3.0"))
        assertEquals("gl-node/22.21.1", onboard.header("X-Goog-Api-Client"))
        val onboardBody = json.parseToJsonElement(onboard.bodyText()).jsonObject
        assertEquals("free-tier", onboardBody["tier_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `token refresh posts the desktop client credentials`() = runBlocking {
        val captured = mutableListOf<Request>()
        val api = AntigravityChatApi(
            client(captured) { 200 to """{"access_token":"new","expires_in":3600}""" },
            oauthTokenUrl = "https://token.invalid/token",
        )
        val tokens = api.refreshToken("refresh-1")
        assertEquals("new", tokens.accessToken)
        assertNull(tokens.refreshToken)
        val request = captured.single()
        assertEquals("Go-http-client/2.0", request.header("User-Agent"))
        val form = request.bodyText()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-1"))
        assertTrue(form.contains("client_secret="))

        val failing = AntigravityChatApi(
            client(mutableListOf()) { 400 to """{"error":"invalid_grant"}""" },
            oauthTokenUrl = "https://token.invalid/token",
        )
        try {
            failing.refreshToken("bad")
            fail("expected failure")
        } catch (error: HttpException) {
            assertEquals(400, error.statusCode)
            assertTrue(error.message!!.contains("invalid_grant"))
        }
    }
}
