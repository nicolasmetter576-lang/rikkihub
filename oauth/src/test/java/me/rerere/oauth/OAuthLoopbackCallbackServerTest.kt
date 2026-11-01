package me.rerere.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class OAuthLoopbackCallbackServerTest {
    private val httpClient = OkHttpClient()

    @Test
    fun `callback is delivered only to matching state`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("expected-state")
        try {
            execute("${session.redirectUri}?code=wrong-code&state=wrong-state").use { response ->
                assertEquals(400, response.code)
            }

            val callbackAndClose = async(Dispatchers.IO) {
                session.awaitCallback(2.seconds).also { session.close() }
            }
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?code=auth-code&state=expected-state").use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.body.string().contains("Authorization complete"))
                }
            }

            val callback = callbackAndClose.await()
            assertEquals("auth-code", callback?.code)
            assertEquals("expected-state", callback?.state)
            assertFalse(callback?.error != null)
        } finally {
            session.close()
        }
    }

    @Test
    fun `localhost redirect uri is reachable over ipv4 loopback`() = runBlocking {
        // 对外公布 localhost 兼容 WAF，监听仍绑定 IPv4 回环，
        // 因此把 host 替换成 127.0.0.1 后必须能直接连通。
        val server = OAuthLoopbackCallbackServer(redirectHost = "localhost")
        val session = server.openSession("waf-state")
        try {
            assertTrue(session.redirectUri.startsWith("http://localhost:"))
            val ipv4Uri = session.redirectUri.replace("http://localhost:", "http://127.0.0.1:")

            val callback = async(Dispatchers.IO) { session.awaitCallback(5.seconds) }
            withContext(Dispatchers.IO) {
                execute("$ipv4Uri?code=ipv4-code&state=waf-state").use { response ->
                    assertEquals(200, response.code)
                }
            }
            assertEquals("ipv4-code", callback.await()?.code)
        } finally {
            session.close()
        }
    }

    @Test
    fun `default redirect host stays 127_0_0_1`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("default-state")
        try {
            assertTrue(session.redirectUri.startsWith("http://127.0.0.1:"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `non loopback redirect hosts are rejected`() {
        for (host in listOf("example.com", "0.0.0.0", "[::1]", "::1", "")) {
            try {
                OAuthLoopbackCallbackServer(redirectHost = host)
                fail("redirectHost=$host must be rejected")
            } catch (e: IllegalArgumentException) {
                // 预期
            }
        }
    }

    @Test
    fun `duplicate callback for the same state returns 409`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("dupe-state")
        try {
            val callback = async(Dispatchers.IO) { session.awaitCallback(5.seconds) }
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?code=first&state=dupe-state").use {
                    assertEquals(200, it.code)
                }
            }
            assertEquals("first", callback.await()?.code)

            // 同一 state 的第二次回调（浏览器重试/刷新）不得覆盖结果
            execute("${session.redirectUri}?code=second&state=dupe-state").use { response ->
                assertEquals(409, response.code)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `concurrent sessions are routed by state`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val sessionA = server.openSession("state-a")
        val sessionB = server.openSession("state-b")
        try {
            // 共享同一端口与 redirect URI 前缀
            assertEquals(sessionA.redirectUri, sessionB.redirectUri)

            val callbackA = async(Dispatchers.IO) { sessionA.awaitCallback(5.seconds) }
            val callbackB = async(Dispatchers.IO) { sessionB.awaitCallback(5.seconds) }
            withContext(Dispatchers.IO) {
                execute("${sessionB.redirectUri}?code=code-b&state=state-b").use {
                    assertEquals(200, it.code)
                }
                execute("${sessionA.redirectUri}?error=access_denied&state=state-a").use {
                    assertEquals(200, it.code)
                }
            }

            val (resultA, resultB) = awaitAll(callbackA, callbackB)
            assertEquals("code-b", resultB?.code)
            assertNull(resultA?.code)
            assertEquals("access_denied", resultA?.error)
            assertEquals("state-a", resultA?.state)
        } finally {
            sessionA.close()
            sessionB.close()
        }
    }

    @Test
    fun `error callback is delivered with error fields`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("error-state")
        try {
            val callback = async(Dispatchers.IO) { session.awaitCallback(5.seconds) }
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?error=access_denied&error_description=nope&state=error-state")
                    .use { response ->
                        assertEquals(200, response.code)
                        assertTrue(response.body.string().contains("Authorization failed"))
                    }
            }
            val result = callback.await()
            assertNull(result?.code)
            assertEquals("access_denied", result?.error)
            assertEquals("nope", result?.errorDescription)
        } finally {
            session.close()
        }
    }

    @Test
    fun `second open session with same state is rejected`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("taken-state")
        try {
            try {
                server.openSession("taken-state")
                fail("duplicate pending state must be rejected")
            } catch (e: IllegalStateException) {
                // 预期
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `server restarts on the same port after all sessions close`() = runBlocking {
        // 全部会话关闭后内置服务器停止并释放端口；重开必须能重新绑定同一端口。
        val first = OAuthLoopbackCallbackServer()
        val session1 = first.openSession("cycle-1")
        val port = session1.redirectUri
            .substringAfterLast(":")
            .substringBefore("/")
            .toInt()
        session1.close()

        val second = OAuthLoopbackCallbackServer(port = port)
        val session2 = second.openSession("cycle-2")
        try {
            assertTrue(session2.redirectUri.contains(":$port/"))
            val callback = async(Dispatchers.IO) { session2.awaitCallback(5.seconds) }
            withContext(Dispatchers.IO) {
                execute("${session2.redirectUri}?code=again&state=cycle-2").use {
                    assertEquals(200, it.code)
                }
            }
            assertEquals("again", callback.await()?.code)
        } finally {
            session2.close()
        }
    }

    @Test
    fun `callback without state is rejected with 400`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("any-state")
        try {
            execute("${session.redirectUri}?code=orphan").use { response ->
                assertEquals(400, response.code)
            }
        } finally {
            session.close()
        }
    }

    private fun execute(url: String) = httpClient.newCall(
        Request.Builder()
            .url(url)
            .build()
    ).execute()
}
