package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.CredentialRotationStrategy
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.VertexServiceAccount
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.CredentialHealthStatus
import me.rerere.ai.util.CredentialLogTag
import me.rerere.ai.util.CredentialPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class GoogleProviderMultiSaTest {
    private val pem1 by lazy { generatePem() }
    private val pem2 by lazy { generatePem() }

    private fun generatePem(): String {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        return "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(key.encoded)}\n-----END PRIVATE KEY-----"
    }

    @Test
    fun `Vertex multi SA failover routes to backup SA project and location on 429`() = runBlocking {
        val recordedCalls = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            if (url.startsWith("https://oauth2.googleapis.com/token")) {
                val body = okio.Buffer().apply { req.body?.writeTo(this) }.readUtf8()
                val tok = if (body.contains("assertion=")) {
                    // Distinguish token calls sequentially
                    "tok-${recordedCalls.size}"
                } else "tok-x"
                return@addInterceptor Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"access_token":"$tok","expires_in":3600,"token_type":"Bearer"}"""
                        .toResponseBody("application/json".toMediaType()))
                    .build()
            }
            recordedCalls += Triple(url, req.header("Authorization"), req.tag(CredentialLogTag::class.java)?.label)
            if (url.contains("/projects/proj-1/locations/us-central1/")) {
                return@addInterceptor Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                    .header("Retry-After", "12")
                    .body("""{"error":{"code":429,"message":"exhausted","status":"RESOURCE_EXHAUSTED"}}"""
                        .toResponseBody("application/json".toMediaType()))
                    .build()
            }
            val sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hello from sa2\"}]},\"finishReason\":\"STOP\"}]}\n\n"
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()

        val pool = CredentialPool()
        val provider = GoogleProvider(client, pool)
        val setting = ProviderSetting.Google(
            vertexAI = true,
            useServiceAccount = true,
            location = "us-central1",
            rotationStrategy = CredentialRotationStrategy.FAILOVER,
            serviceAccounts = listOf(
                VertexServiceAccount(
                    id = "sa-1",
                    label = "Primary SA",
                    projectId = "proj-1",
                    serviceAccountEmail = "sa1@example.invalid",
                    privateKey = pem1,
                    location = "",
                    enabled = true,
                ),
                VertexServiceAccount(
                    id = "sa-2",
                    label = "Global Edge",
                    projectId = "proj-2",
                    serviceAccountEmail = "sa2@example.invalid",
                    privateKey = pem2,
                    location = "global",
                    enabled = true,
                ),
            ),
        )

        val chunks = withTimeout(5_000) {
            provider.streamText(
                setting,
                listOf(UIMessage.user("hi")),
                TextGenerationParams(model = Model(modelId = "gemini-2.5-pro")),
            ).toList()
        }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()

        val text = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("hello from sa2", text)
        assertEquals(2, recordedCalls.size)
        assertTrue(recordedCalls[0].first.contains("/projects/proj-1/locations/us-central1/"))
        assertEquals("Primary SA", recordedCalls[0].third)
        assertTrue(recordedCalls[1].first.contains("/projects/proj-2/locations/global/"))
        assertEquals("Global Edge", recordedCalls[1].third)
        // CredentialLogTag never leaks privateKey or access_token
        assertFalse(recordedCalls.any { it.third?.contains("PRIVATE KEY") == true })

        val snaps = pool.snapshotsFor(setting.id.toString(), listOf("sa-1", "sa-2"))
        assertEquals(CredentialHealthStatus.COOLING_DOWN, snaps["sa-1"]!!.status)
        assertEquals(CredentialHealthStatus.HEALTHY, snaps["sa-2"]!!.status)
    }

    @Test
    fun `invalid SA key triggers auth error cooldown and falls back to healthy second SA`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            if (req.url.toString().startsWith("https://oauth2.googleapis.com/token")) {
                return@addInterceptor Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"access_token":"tok-good","expires_in":3600}"""
                        .toResponseBody("application/json".toMediaType()))
                    .build()
            }
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"candidates":[{"content":{"role":"model","parts":[{"text":"recovered"}]},"finishReason":"STOP"}]}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val pool = CredentialPool()
        val provider = GoogleProvider(client, pool)
        val setting = ProviderSetting.Google(
            vertexAI = true,
            useServiceAccount = true,
            rotationStrategy = CredentialRotationStrategy.FAILOVER,
            serviceAccounts = listOf(
                VertexServiceAccount(
                    id = "sa-bad",
                    label = "Broken Key",
                    projectId = "proj-bad",
                    serviceAccountEmail = "bad@example.invalid",
                    privateKey = "not-a-valid-pem",
                    enabled = true,
                ),
                VertexServiceAccount(
                    id = "sa-good",
                    label = "Good SA",
                    projectId = "proj-good",
                    serviceAccountEmail = "good@example.invalid",
                    privateKey = pem2,
                    enabled = true,
                ),
            ),
        )

        val result = withTimeout(5_000) {
            provider.generateText(
                setting,
                listOf(UIMessage.user("hi")),
                TextGenerationParams(model = Model(modelId = "gemini-2.5-flash")),
            )
        }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()

        assertTrue(result.message.toText().contains("recovered"))
        val snaps = pool.snapshotsFor(setting.id.toString(), listOf("sa-bad", "sa-good"))
        assertEquals(CredentialHealthStatus.AUTH_ERROR, snaps["sa-bad"]!!.status)
        assertEquals(CredentialHealthStatus.HEALTHY, snaps["sa-good"]!!.status)
    }
}
