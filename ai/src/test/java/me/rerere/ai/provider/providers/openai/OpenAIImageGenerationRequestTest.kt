package me.rerere.ai.provider.providers.openai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 离线测试：OpenAI 图片生成请求的 size 字段处理。
 *
 * Grok（api.x.ai 及其子域）的图片接口不接受 `size`，构建请求时必须丢弃；
 * 但判定不能对整个 baseUrl 字符串做 `contains("x.ai")` —— `max.ai` 中转、
 * query/path 里碰巧含 `x.ai` 的 URL 都会被误伤。判定只认真实 URL host；
 * host 大小写归一；URL 解析失败一律不当 Grok。
 *
 * size 值为空白或 "auto"/"AUTO" 时不发送；其余 size 在非 Grok 请求中正常带上。
 * 非 x.ai baseUrl 走 loopback HttpServer 验证真实请求体；x.ai baseUrl 用 OkHttp
 * 拦截器短路（不发起真实网络），同样走完整 `generateImage` 构建路径。
 */
class OpenAIImageGenerationRequestTest {

    /** 最小 loopback：记录 /v1/images/generations 请求体，返回固定 b64 响应。 */
    private class FakeImageEndpoint : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = ConcurrentLinkedQueue<String>()

        init {
            server.createContext("/v1/images/generations") { exchange ->
                try {
                    bodies += exchange.requestBody.readBytes().decodeToString()
                    val bytes = """{"data":[{"b64_json":"QUJD"}]}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"
        override fun close() = server.stop(0)
    }

    /** 拦截器短路客户端：捕获请求并直接返回脚本响应（x.ai baseUrl 用，零网络）。 */
    private fun capturingClient(
        captured: ConcurrentLinkedQueue<Request>,
        body: String,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            captured += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun imageModel(modelId: String = "gpt-image-1") = Model(
        modelId = modelId,
        type = ModelType.IMAGE,
    )

    private fun imageParams(
        model: Model,
        size: String,
        customBody: List<CustomBody> = emptyList(),
    ) = ImageGenerationParams(
        model = model,
        prompt = "a cat",
        numOfImages = 1,
        size = size,
        customBody = customBody,
    )

    private fun Request.readBodyText(): String =
        okio.Buffer().also { requireNotNull(body).writeTo(it) }.readUtf8()

    // ==================== 真实构建路径（loopback，非 Grok baseUrl） ====================

    @Test
    fun `non-grok provider keeps regular size and other fields`() {
        FakeImageEndpoint().use { ep ->
            val setting = ProviderSetting.OpenAI(baseUrl = ep.baseUrl, apiKey = "k")
            runBlocking {
                OpenAIProvider(OkHttpClient())
                    .generateImage(setting, imageParams(imageModel(), "1024x1024")).toList()
            }

            val body = json.parseToJsonElement(ep.bodies.single()).jsonObject
            assertEquals("1024x1024", body["size"]?.jsonPrimitive?.content)
            assertEquals("gpt-image-1", body["model"]?.jsonPrimitive?.content)
            assertEquals("a cat", body["prompt"]?.jsonPrimitive?.content)
            assertEquals(1, body["n"]?.jsonPrimitive?.content?.toInt())
        }
    }

    @Test
    fun `non-grok provider omits auto and blank size`() {
        FakeImageEndpoint().use { ep ->
            for (size in listOf("", " ", "auto", "AUTO", ImageGenSize.AUTO.value)) {
                val setting = ProviderSetting.OpenAI(baseUrl = ep.baseUrl, apiKey = "k")
                runBlocking {
                    OpenAIProvider(OkHttpClient())
                        .generateImage(setting, imageParams(imageModel(), size)).toList()
                }
                val body = json.parseToJsonElement(ep.bodies.last()).jsonObject
                assertFalse("size='$size' must be omitted", body.containsKey("size"))
                // 其余字段照常
                assertEquals("a cat", body["prompt"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `transit host containing x-ai substring is not grok`() {
        // loopback host 是 127.0.0.1：等价于 max.ai 一类「字符串里不含 x.ai 域名」
        // 的任意中转，size 正常带上；真实域名形态见 host 判定用例
        FakeImageEndpoint().use { ep ->
            val setting = ProviderSetting.OpenAI(baseUrl = ep.baseUrl, apiKey = "k")
            runBlocking {
                OpenAIProvider(OkHttpClient())
                    .generateImage(setting, imageParams(imageModel(), "1024x1024")).toList()
            }
            val body = json.parseToJsonElement(ep.bodies.single()).jsonObject
            assertEquals("1024x1024", body["size"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `custom body fields merge with generated fields`() {
        FakeImageEndpoint().use { ep ->
            val setting = ProviderSetting.OpenAI(baseUrl = ep.baseUrl, apiKey = "k")
            runBlocking {
                OpenAIProvider(OkHttpClient()).generateImage(
                    setting,
                    imageParams(
                        imageModel(),
                        "1024x1024",
                        customBody = listOf(
                            CustomBody("quality", JsonPrimitive("hd")),
                            CustomBody("output_format", JsonPrimitive("webp")),
                        )
                    )
                ).toList()
            }
            val body = json.parseToJsonElement(ep.bodies.single()).jsonObject
            assertEquals("1024x1024", body["size"]?.jsonPrimitive?.content)
            assertEquals("hd", body["quality"]?.jsonPrimitive?.content)
            assertEquals("webp", body["output_format"]?.jsonPrimitive?.content)
        }
    }

    // ==================== x.ai baseUrl 走完整 generateImage（拦截器短路） ====================

    @Test
    fun `api x-ai request drops size via full generateImage path`() = runBlocking {
        val captured = ConcurrentLinkedQueue<Request>()
        val provider = OpenAIProvider(
            capturingClient(captured, """{"data":[{"b64_json":"QUJD"}]}""")
        )
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1", apiKey = "k")

        provider.generateImage(setting, imageParams(imageModel(), "1024x1024")).toList()

        val request = captured.single()
        assertEquals("api.x.ai", request.url.host)
        assertEquals("/v1/images/generations", request.url.encodedPath)
        val body = json.parseToJsonElement(request.readBodyText()).jsonObject
        assertFalse("Grok must not receive size", body.containsKey("size"))
        assertEquals("gpt-image-1", body["model"]?.jsonPrimitive?.content)
        assertEquals("a cat", body["prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `x-ai subdomain request drops size`() = runBlocking {
        val captured = ConcurrentLinkedQueue<Request>()
        val provider = OpenAIProvider(
            capturingClient(captured, """{"data":[{"b64_json":"QUJD"}]}""")
        )
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.grok.x.ai/v1", apiKey = "k")

        provider.generateImage(setting, imageParams(imageModel(), "1024x1024")).toList()

        val request = captured.single()
        assertEquals("api.grok.x.ai", request.url.host)
        val body = json.parseToJsonElement(request.readBodyText()).jsonObject
        assertFalse(body.containsKey("size"))
    }

    @Test
    fun `grok model name drops size even on non-xai host`() {
        // modelId 含 grok 的既有兜底判定保留：host 非 x.ai 也丢 size
        FakeImageEndpoint().use { ep ->
            val setting = ProviderSetting.OpenAI(baseUrl = ep.baseUrl, apiKey = "k")
            runBlocking {
                OpenAIProvider(OkHttpClient())
                    .generateImage(setting, imageParams(imageModel("grok-2-image"), "1024x1024"))
                    .toList()
            }
            val body = json.parseToJsonElement(ep.bodies.single()).jsonObject
            assertFalse(body.containsKey("size"))
            assertEquals("grok-2-image", body["model"]?.jsonPrimitive?.content)
        }
    }

    // ==================== host 判定（isXAiApiHost） ====================

    @Test
    fun `x-ai host and subdomains are detected as grok`() {
        assertTrue(OpenAIProvider.isXAiApiHost("https://api.x.ai/v1"))
        assertTrue(OpenAIProvider.isXAiApiHost("https://x.ai/v1"))
        assertTrue(OpenAIProvider.isXAiApiHost("https://api.grok.x.ai/v1"))
        assertTrue(OpenAIProvider.isXAiApiHost("http://api.x.ai:8443/v1"))
        // host 大小写归一
        assertTrue(OpenAIProvider.isXAiApiHost("https://API.X.AI/v1"))
        assertTrue(OpenAIProvider.isXAiApiHost("https://X.AI/v1"))
    }

    @Test
    fun `evil hosts query strings and parse failures are not grok`() {
        assertFalse(OpenAIProvider.isXAiApiHost("https://max.ai/v1"))
        assertFalse(OpenAIProvider.isXAiApiHost("https://x.ai.evil.com/v1"))
        assertFalse(OpenAIProvider.isXAiApiHost("https://evil.com/api.x.ai/v1"))
        assertFalse(OpenAIProvider.isXAiApiHost("https://notx.ai/v1"))
        assertFalse(OpenAIProvider.isXAiApiHost("https://example.org/v1?key=x.ai"))
        assertFalse(OpenAIProvider.isXAiApiHost("https://example.org?redirect=x.ai"))
        assertFalse(OpenAIProvider.isXAiApiHost("not-a-url"))
        assertFalse(OpenAIProvider.isXAiApiHost(""))
        assertFalse(OpenAIProvider.isXAiApiHost("ftp://api.x.ai/v1"))
    }
}
