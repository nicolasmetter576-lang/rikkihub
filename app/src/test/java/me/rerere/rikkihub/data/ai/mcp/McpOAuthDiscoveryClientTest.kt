package me.rerere.rikkihub.data.ai.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 授权发现兼容契约。
 * - 优先 401 `WWW-Authenticate: resource_metadata` 与 RFC 9728 well-known 资源元数据；
 *   元数据缺失的旧式服务器只退回自身 origin 做 RFC 8414 / OIDC discovery。
 * - 最终发现失败必须抛错，绝不伪造授权端点。
 * - canonical resource / server origin 的尾部斜杠、query、凭据、IPv6、默认端口边界。
 * - 取消不是发现失败：协程取消后立即停止，不得继续发出请求。
 */
class McpOAuthDiscoveryClientTest {

    // ---------- 本地 HTTP stub（无真实网络） ----------

    private class Stub(
        val match: (String) -> Boolean,
        val respond: (Request) -> Response.Builder,
    )

    private fun canned(body: String, code: Int = 200): (Request) -> Response.Builder = { req ->
        Response.Builder()
            .request(req)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(null))
    }

    private class RecordingClient {
        val urls = CopyOnWriteArrayList<String>()
    }

    private fun stubClient(vararg stubs: Stub): Pair<RecordingClient, OkHttpClient> {
        val recording = RecordingClient()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                recording.urls += request.url.toString()
                val builder = stubs.firstOrNull { it.match(request.url.toString()) }
                    ?.respond(request) ?: canned("not found", 404)(request)
                builder.build()
            })
            .build()
        return recording to client
    }

    private fun jsonStub(url: String, body: String) =
        Stub({ it == url }, canned(body))

    private val protectedResourceJson = """
        {"resource": "https://mcp.example.com/mcp",
         "authorization_servers": ["https://auth.example.com"],
         "scopes_supported": ["read", "write"]}
    """.trimIndent()

    private val authServerJson = """
        {"issuer": "https://auth.example.com",
         "authorization_endpoint": "https://auth.example.com/authorize",
         "token_endpoint": "https://auth.example.com/token",
         "registration_endpoint": "https://auth.example.com/register"}
    """.trimIndent()

    // ---------- 测试 ----------

    @Test
    fun `resource metadata issuer wins over server origin`() = runBlocking {
        val (recording, client) = stubClient(
            Stub({ it == "https://mcp.example.com/mcp" }) { req ->
                canned("{}", 401)(req).header(
                    "WWW-Authenticate",
                    "Bearer resource_metadata=\"https://meta.example.com/prm\"",
                )
            },
            jsonStub("https://meta.example.com/prm", protectedResourceJson),
            jsonStub("https://auth.example.com/.well-known/oauth-authorization-server", authServerJson),
        )
        val discovery = McpOAuthDiscoveryClient(client).discoverAuthorization("https://mcp.example.com/mcp")

        assertNotNull(discovery.protectedResource)
        assertEquals(
            "https://auth.example.com/authorize",
            discovery.authorizationServer.authorizationEndpoint,
        )
        assertEquals(
            "https://auth.example.com/token",
            discovery.authorizationServer.tokenEndpoint,
        )
        assertTrue(
            "authorization-server metadata must be looked up on the issuer origin",
            recording.urls.any { it.startsWith("https://auth.example.com/") },
        )
    }

    @Test
    fun `legacy server without resource metadata falls back to own origin discovery`() = runBlocking {
        val (recording, client) = stubClient(
            // 服务器本身不返回 401 resource_metadata
            jsonStub("https://mcp.example.com/.well-known/openid-configuration", authServerJson),
        )
        val discovery = McpOAuthDiscoveryClient(client).discoverAuthorization("https://mcp.example.com/mcp")

        assertNull(discovery.protectedResource)
        assertEquals(
            "https://auth.example.com/authorize",
            discovery.authorizationServer.authorizationEndpoint,
        )
        // 尝试了 RFC 9728 路径与 RFC 8414，最终在 OIDC well-known 命中
        assertTrue(
            recording.urls.contains("https://mcp.example.com/.well-known/oauth-protected-resource/mcp"),
        )
        assertTrue(
            recording.urls.contains("https://mcp.example.com/.well-known/oauth-authorization-server"),
        )
        // OIDC 发现成功前不应带 server path
        assertTrue(
            recording.urls.none { it == "https://other.example.com/.well-known/openid-configuration" },
        )
    }

    @Test
    fun `rfc8414 candidates are tried before oidc`() = runBlocking {
        val (recording, client) = stubClient(
            jsonStub("https://mcp.example.com/.well-known/oauth-protected-resource", protectedResourceJson),
            // RFC 8414 返回不完整元数据（缺端点）→ 必须继续尝试 OIDC
            Stub({ it == "https://auth.example.com/.well-known/oauth-authorization-server" }, canned("""{"issuer":"x"}""")),
            jsonStub("https://auth.example.com/.well-known/openid-configuration", authServerJson),
        )
        val discovery = McpOAuthDiscoveryClient(client).discoverAuthorization("https://mcp.example.com/mcp")

        assertEquals(
            "https://auth.example.com/authorize",
            discovery.authorizationServer.authorizationEndpoint,
        )
        val rfcIndex = recording.urls.indexOf("https://auth.example.com/.well-known/oauth-authorization-server")
        val oidcIndex = recording.urls.indexOf("https://auth.example.com/.well-known/openid-configuration")
        assertTrue(rfcIndex in 0 until oidcIndex)
    }

    @Test
    fun `issuer path produces path suffixed well known candidates`() = runBlocking {
        val (recording, client) = stubClient(
            jsonStub("https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                """{"authorization_servers": ["https://auth.example.com/tenant"]}"""),
            jsonStub("https://auth.example.com/tenant/.well-known/openid-configuration", authServerJson),
        )
        val discovery = McpOAuthDiscoveryClient(client).discoverAuthorization("https://mcp.example.com/mcp")

        assertEquals(
            "https://auth.example.com/authorize",
            discovery.authorizationServer.authorizationEndpoint,
        )
        assertTrue(
            recording.urls.contains("https://auth.example.com/.well-known/oauth-authorization-server/tenant"),
        )
        assertTrue(
            recording.urls.contains("https://auth.example.com/tenant/.well-known/openid-configuration"),
        )
    }

    @Test
    fun `complete discovery failure throws instead of fabricating endpoints`() = runBlocking {
        val (_, client) = stubClient()
        try {
            McpOAuthDiscoveryClient(client).discoverAuthorization("https://mcp.example.com/mcp")
            fail("discovery failure must throw")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException)
        }
    }

    @Test
    fun `protected resource candidate without authorization_servers is skipped`() = runBlocking {
        val (recording, client) = stubClient(
            jsonStub("https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                """{"resource": "x"}"""), // 无 authorization_servers → 不算有效元数据
            jsonStub("https://mcp.example.com/.well-known/oauth-protected-resource", protectedResourceJson),
            jsonStub("https://auth.example.com/.well-known/oauth-authorization-server", authServerJson),
        )
        val discovery = McpOAuthDiscoveryClient(client).discoverAuthorization("https://mcp.example.com/mcp")

        assertEquals(
            "https://auth.example.com",
            discovery.protectedResource?.authorizationServers?.single(),
        )
    }

    @Test
    fun `401 resource_metadata hint is probed before well known candidates`() = runBlocking {
        val (recording, client) = stubClient(
            Stub({ it == "https://mcp.example.com/mcp" }) { req ->
                canned("{}", 401)(req).header(
                    "WWW-Authenticate",
                    "Bearer realm=\"mcp\", resource_metadata=\"https://mcp.example.com/hinted-prm\"",
                )
            },
            jsonStub("https://mcp.example.com/hinted-prm", protectedResourceJson),
            jsonStub("https://auth.example.com/.well-known/oauth-authorization-server", authServerJson),
        )
        val discovery = McpOAuthDiscoveryClient(client).discoverAuthorization("https://mcp.example.com/mcp")

        assertNotNull(discovery.protectedResource)
        val hintedIndex = recording.urls.indexOf("https://mcp.example.com/hinted-prm")
        assertTrue(hintedIndex >= 0)
        // hinted URL 命中后不再请求 well-known 资源元数据
        assertTrue(
            recording.urls.none { it.contains("/.well-known/oauth-protected-resource") },
        )
    }

    @Test
    fun `server origin strips credentials path query fragment keeps non default port`() {
        assertEquals(
            "https://mcp.example.com:8443",
            McpOAuthDiscoveryClient.serverOrigin("https://user:pass@mcp.example.com:8443/mcp/x?a=1#frag"),
        )
        assertEquals(
            "https://mcp.example.com",
            McpOAuthDiscoveryClient.serverOrigin("https://mcp.example.com:443/mcp"),
        )
        assertEquals(
            "http://mcp.example.com",
            McpOAuthDiscoveryClient.serverOrigin("http://mcp.example.com:80/mcp"),
        )
        assertNull(McpOAuthDiscoveryClient.serverOrigin("not a url"))
    }

    @Test
    fun `server origin keeps ipv6 brackets`() {
        assertEquals(
            "http://[2001:db8::1]:8080",
            McpOAuthDiscoveryClient.serverOrigin("http://[2001:db8::1]:8080/mcp"),
        )
    }

    @Test
    fun `canonical resource drops trailing slash only for root path without query`() {
        assertEquals(
            "https://mcp.example.com",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.com/"),
        )
        assertEquals(
            "https://mcp.example.com",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.com"),
        )
        // 非根路径保留尾部斜杠
        assertEquals(
            "https://mcp.example.com/mcp/",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.com/mcp/"),
        )
        // 根路径但带 query → 不去斜杠
        assertEquals(
            "https://mcp.example.com/?a=1",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.com/?a=1"),
        )
        // fragment 剥离、query 保留
        assertEquals(
            "https://mcp.example.com/mcp?a=1",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.com/mcp?a=1#frag"),
        )
    }

    @Test
    fun `canonical resource leaves unparseable url untouched`() {
        assertEquals("not a url", McpOAuthDiscoveryClient.canonicalResource("not a url"))
    }

    // ---------- 取消：必须立即停止，不得继续请求 ----------

    /**
     * 官方 Call 假实现：enqueue 后挂起，cancel() 时通过官方 Callback 回调 onFailure，
     * 绝不抛 unchecked 异常（避免 OkHttp 线程永远不回调）。
     */
    private class HangingClient(
        private val requestStarted: CountDownLatch,
    ) : OkHttpClient() {
        val calls = CopyOnWriteArrayList<FakeCall>()

        override fun newCall(request: Request): Call = FakeCall(request).also { calls += it }

        inner class FakeCall(private val request: Request) : Call {
            @Volatile private var callback: Callback? = null
            @Volatile private var canceled = false

            override fun request(): Request = request
            override fun execute(): Response = throw IOException("synchronous execute unsupported")
            override fun enqueue(responseCallback: Callback) {
                callback = responseCallback
                requestStarted.countDown()
            }
            override fun cancel() {
                canceled = true
                callback?.onFailure(this, IOException("Canceled"))
            }
            override fun isExecuted(): Boolean = true
            override fun isCanceled(): Boolean = canceled
            override fun timeout(): okio.Timeout = okio.Timeout.NONE
            override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = request.tag(type)
            @Suppress("UNCHECKED_CAST")
            override fun <T> tag(type: Class<out T>): T? = request.tag(type)
            override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T =
                request.tag(type) ?: computeIfAbsent()
            override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
                request.tag(type) ?: computeIfAbsent()
            override fun clone(): Call = this
        }
    }

    @Test
    fun `metadata http cancellation stops discovery without further requests`() = runBlocking {
        val started = CountDownLatch(1)
        val client = HangingClient(started)
        val discovery = McpOAuthDiscoveryClient(client)

        val job = async(Dispatchers.Default) {
            discovery.discoverAuthorization("https://mcp.example.com/mcp")
        }
        assertTrue("first request must be dispatched", started.await(5, TimeUnit.SECONDS))
        job.cancel()

        try {
            job.await()
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // 预期：取消向上传播
        }
        delay(100)
        assertEquals("cancelled call must not continue to further requests", 1, client.calls.size)
    }
}
