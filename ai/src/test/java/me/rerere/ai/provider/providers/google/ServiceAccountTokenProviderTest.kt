package me.rerere.ai.provider.providers.google

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ServiceAccountTokenProviderTest {
    companion object {
        private val pem by lazy {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
            "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(key.encoded)}\n-----END PRIVATE KEY-----"
        }
        private const val EMAIL = "synthetic@example.invalid"
    }

    private class TokenSocket(private val bodyStarted: Boolean, private val trickle: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        private val executor = Executors.newSingleThreadExecutor()
        private var socket: Socket? = null
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val bodyClosed = AtomicBoolean()
        val calls = CopyOnWriteArrayList<Call>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
            })
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .url("http://127.0.0.1:${server.localPort}/token").build())
            }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val source = object : ForwardingSource(body.source()) {
                    override fun close() { bodyClosed.set(true); super.close() }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }
            .build()

        init {
            executor.submit {
                try {
                    server.accept().use { connection ->
                        socket = connection
                        connection.soTimeout = 60_000
                        val reader = connection.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: return@submit
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { check(reader.read() != -1) }
                        val output = connection.getOutputStream()
                        if (bodyStarted) {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n ".toByteArray())
                            output.flush()
                        }
                        started.countDown()
                        if (trickle) {
                            connection.soTimeout = 100
                            while (true) {
                                try {
                                    if (reader.read() == -1) break
                                } catch (_: SocketTimeoutException) {
                                    output.write(' '.code)
                                    output.flush()
                                }
                            }
                        } else {
                            check(reader.read() == -1)
                        }
                    }
                } catch (_: IOException) {
                    // Socket closure/reset is also a real transport disconnect.
                } finally { disconnected.countDown() }
            }
        }
        override fun close() {
            socket?.close()
            server.close()
            executor.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `concurrent exchanges on one provider cancel independently`() = runBlocking {
        TokenSocket(true).use { first ->
            TokenSocket(true).use { second ->
                val calls = CopyOnWriteArrayList<Call>()
                val sequence = AtomicInteger()
                val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                    override fun callStart(call: Call) { calls += call }
                }).addInterceptor { chain ->
                    val port = if (sequence.incrementAndGet() == 1) first.port else second.port
                    chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:$port/token").build())
                }.build()
                val provider = ServiceAccountTokenProvider(client)
                val one = launch(Dispatchers.Default) { provider.fetchAccessToken(EMAIL, pem) }
                var two: Job? = null
                try {
                    assertTrue(withContext(Dispatchers.IO) { first.started.await(3, TimeUnit.SECONDS) })
                    two = launch(Dispatchers.Default) { provider.fetchAccessToken(EMAIL, pem) }
                    assertTrue(withContext(Dispatchers.IO) { second.started.await(3, TimeUnit.SECONDS) })
                    withTimeout(2_000) { one.cancelAndJoin() }
                    assertTrue(calls[0].isCanceled())
                    assertFalse(calls[1].isCanceled())
                    assertTrue(two.isActive)
                    withTimeout(2_000) { two.cancelAndJoin() }
                    assertTrue(calls[1].isCanceled())
                } finally {
                    one.cancelAndJoin()
                    two?.cancelAndJoin()
                    client.dispatcher.executorService.shutdownNow()
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    @Test
    fun `cancellation before headers cancels real call and joins before next task`() = cancellation(false)

    @Test
    fun `cancellation during body closes response and joins before next task`() = cancellation(true)

    private fun cancellation(bodyStarted: Boolean) = runBlocking {
        TokenSocket(bodyStarted).use { fixture ->
            val provider = ServiceAccountTokenProvider(fixture.client)
            val task = launch(Dispatchers.Default) { provider.fetchAccessToken(EMAIL, pem) }
            assertTrue(withContext(Dispatchers.IO) { fixture.started.await(5, TimeUnit.SECONDS) })
            val start = System.nanoTime()
            task.cancel()
            withTimeout(2_000) { task.join() }
            assertTrue(fixture.calls.single().isCanceled())
            assertTrue(withContext(Dispatchers.IO) { fixture.disconnected.await(2, TimeUnit.SECONDS) })
            if (bodyStarted) assertTrue(fixture.bodyClosed.get())
            assertTrue(task.children.none())
            assertEquals(0, fixture.client.dispatcher.runningCallsCount())
            println("token cancellation body=$bodyStarted join_ms=${(System.nanoTime() - start) / 1_000_000}")
            assertEquals("next", async { "next" }.await())
        }
    }

    @Test
    fun `outer timeout remains cancellation rather than token timeout`() = runBlocking {
        TokenSocket(true).use { fixture ->
            val failure = try {
                withTimeout(500) { ServiceAccountTokenProvider(fixture.client).fetchAccessToken(EMAIL, pem) }
                null
            } catch (e: TimeoutCancellationException) { e }
            assertNotNull(failure)
            assertTrue(fixture.calls.single().isCanceled())
            assertTrue(fixture.bodyClosed.get())
        }
    }

    @Test
    fun `single 45 second budget cancels a continuously arriving body`() = runBlocking {
        TokenSocket(bodyStarted = true, trickle = true).use { fixture ->
            val start = System.nanoTime()
            val failure = try {
                ServiceAccountTokenProvider(fixture.client).fetchAccessToken(EMAIL, pem)
                null
            } catch (e: SocketTimeoutException) { e }
            val millis = (System.nanoTime() - start) / 1_000_000
            assertEquals("Service account token exchange timed out", failure?.message)
            assertTrue("deadline $millis", millis in 44_000..48_000)
            assertTrue(fixture.calls.single().isCanceled())
            assertTrue(fixture.bodyClosed.get())
            println("token budget elapsed_ms=$millis")
        }
    }

    @Test
    fun `IO dispatch wait is included in the single deadline and sends no stale call`() = runBlocking {
        val parallelism = System.getProperty("kotlinx.coroutines.io.parallelism")?.toInt()
            ?: maxOf(64, Runtime.getRuntime().availableProcessors())
        val ready = CountDownLatch(parallelism)
        val release = CountDownLatch(1)
        val blockers = List(parallelism) {
            launch(Dispatchers.IO) { ready.countDown(); check(release.await(55, TimeUnit.SECONDS)) }
        }
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            error("Cancelled IO-queued exchange must not send")
        }.build()
        try {
            assertTrue(withContext(Dispatchers.Default) { ready.await(5, TimeUnit.SECONDS) })
            val task = async(Dispatchers.Default) {
                try { ServiceAccountTokenProvider(client).fetchAccessToken(EMAIL, pem); null }
                catch (e: SocketTimeoutException) { e }
            }
            delay(46_000)
            release.countDown()
            withTimeout(2_000) {
                assertEquals("Service account token exchange timed out", task.await()?.message)
                blockers.joinAll()
            }
            assertEquals(0, requests.get())
        } finally {
            release.countDown()
            blockers.joinAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `synchronous token exchange bypasses saturated async host queue`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val client = OkHttpClient.Builder().dispatcher(Dispatcher().apply {
            maxRequests = 1
            maxRequestsPerHost = 1
        }).addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/busy") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"synthetic-token"}""".toResponseBody()).build()
        }.build()
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) { completed.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); completed.countDown() }
        }
        try {
            client.newCall(Request.Builder().url("https://oauth2.googleapis.com/busy").build()).enqueue(callback)
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            client.newCall(Request.Builder().url("https://oauth2.googleapis.com/busy").build()).enqueue(callback)
            assertEquals(1, client.dispatcher.queuedCallsCount())
            withTimeout(2_000) {
                assertEquals("synthetic-token", ServiceAccountTokenProvider(client).fetchAccessToken(EMAIL, pem))
            }
            assertEquals(1, client.dispatcher.queuedCallsCount())
        } finally {
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { completed.await(3, TimeUnit.SECONDS) })
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `token HTTP and malformed JSON errors never expose echoed credentials or poison cache`() = runBlocking {
        for (status in listOf(400, 200)) {
            val count = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val first = count.incrementAndGet() == 1
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (first) status else 200).message("OK")
                    .body((if (first) """{"access_token":"synthetic-secret","expires_in":"synthetic-jwt"}"""
                        else """{"access_token":"valid-synthetic"}""").toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                val provider = ServiceAccountTokenProvider(client)
                val failure = try { provider.fetchAccessToken(EMAIL, pem); null }
                    catch (e: IllegalStateException) { e }
                assertNotNull(failure)
                assertFalse(failure!!.stackTraceToString().contains("synthetic-"))
                assertFalse(generateSequence<Throwable>(failure) { it.cause }
                    .any { it is kotlinx.serialization.SerializationException })
                assertTrue(failure.suppressed.isEmpty())
                assertEquals("valid-synthetic", provider.fetchAccessToken(EMAIL, pem))
                assertEquals("valid-synthetic", provider.fetchAccessToken(EMAIL, pem))
                assertEquals(2, count.get())
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `cache scopes expiry and derived client leave original unchanged`() = runBlocking {
        val count = AtomicInteger()
        val original = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                assertEquals(20_000, chain.connectTimeoutMillis())
                assertEquals(30_000, chain.readTimeoutMillis())
                assertEquals(30_000, chain.writeTimeoutMillis())
                assertEquals(0, chain.call().timeout().timeoutNanos())
                val n = count.incrementAndGet()
                val expires = if (n == 1) 299 else 3600
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"access_token":"synthetic-$n","expires_in":$expires}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
        try {
            val provider = ServiceAccountTokenProvider(original)
            assertEquals("synthetic-1", provider.fetchAccessToken(EMAIL, pem, listOf("b", "a")))
            assertEquals("synthetic-2", provider.fetchAccessToken(EMAIL, pem, listOf("a", "b")))
            assertEquals("synthetic-2", provider.fetchAccessToken(EMAIL, pem, listOf("b", "a")))
            assertEquals("synthetic-3", provider.fetchAccessToken(EMAIL, pem, listOf("c")))
            assertEquals(3, count.get())
            // Changing privateKeyPem for the same email misses the cache and rejects invalid PEM.
            val keyMismatch = try {
                provider.fetchAccessToken(EMAIL, "different invalid key", listOf("a", "b"))
                null
            } catch (e: me.rerere.ai.util.ServiceAccountAuthException) { e }
            assertNotNull(keyMismatch)
            assertEquals(600_000, original.readTimeoutMillis)
            assertEquals(120_000, original.writeTimeoutMillis)
            assertEquals(90_000, original.callTimeoutMillis)
        } finally {
            original.dispatcher.executorService.shutdownNow()
            original.connectionPool.evictAll()
        }
    }
}
