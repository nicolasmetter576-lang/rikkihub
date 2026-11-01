package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Offline native regression for the dokar3 QuickJS runtime behind
 * [CustomJsSearchService]. All HTTP is short-circuited through an OkHttp
 * interceptor on an injected client; no real network is touched.
 */
class CustomJsSearchServiceTest {
    private suspend fun withStubbedHttp(
        interceptor: Interceptor,
        block: suspend () -> Unit,
    ) {
        val previousClient = SearchService.httpClient
        val stubClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        SearchService.httpClient = stubClient
        try {
            block()
        } finally {
            stubClient.dispatcher.cancelAll()
            SearchService.httpClient = previousClient
        }
    }

    @Test(timeout = 20_000)
    fun `existing synchronous search and fetch options still work`() = runBlocking {
        val query = "你好\n\"\u0001😀"
        withStubbedHttp({ chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("token", request.header("X-Test"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertEquals("{\"query\":\"你好\\n\\\"\\u0001😀\",\"limit\":3}", body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"results":[{"title":"结果😀","url":"https://example.com","snippet":"内容"}]}""".toResponseBody())
                .build()
        }) {
            val result = CustomJsSearchService.search(
                buildJsonObject { put("query", query) },
                SearchCommonOptions(resultSize = 3),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    function search(query, resultSize) {
                        console.log('searching');
                        const res = fetch('https://example.com', {
                            method: 'POST', headers: {'X-Test': 'token'},
                            body: {query: query, limit: resultSize}
                        });
                        if (!res.ok || res.status !== 200 || res.statusText !== 'OK') throw Error('response');
                        const data = res.json();
                        return {
                            items: data.results.map(r => ({title: r.title + '|' + query, url: r.url, text: r.snippet}))
                        };
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("结果😀|$query", result.items.single().title)
            assertEquals("内容", result.items.single().text)
        }
    }

    @Test(timeout = 20_000)
    fun `head get and post requests keep their wire shape`() = runBlocking {
        val seen = linkedMapOf<String, Triple<String, String?, String?>>()
        withStubbedHttp({ chain ->
            val request = chain.request()
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
            seen[request.url.encodedPath] = Triple(
                request.method,
                request.header("X-Marker"),
                body,
            )
            // A HEAD response must also carry an empty body so the host-side
            // read of response.body.string() never trips over a null body.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("".toResponseBody())
                .build()
        }) {
            val result = CustomJsSearchService.search(
                buildJsonObject { put("query", "test") },
                SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    function search() {
                        const h = fetch('https://example.com/head', {method: 'head'});
                        const g = fetch('https://example.com/get?q=1', {headers: {'X-Marker': 'g'}});
                        const p = fetch('https://example.com/post', {method: 'POST', headers: {'X-Marker': 'p'}, body: 'raw-body'});
                        return {items: [{title: '' + h.status + g.status + p.status, url: 'u', text: '' + h.ok + g.ok + p.ok}]};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("200200200", result.items.single().title)
            assertEquals("truetruetrue", result.items.single().text)
            assertEquals(Triple("HEAD", null, null), seen["/head"])
            assertEquals(Triple("GET", "g", null), seen["/get"])
            assertEquals(Triple("POST", "p", "raw-body"), seen["/post"])
        }
    }

    @Test(timeout = 20_000)
    fun `content type header lookup is case insensitive`() = runBlocking {
        var seenContentType: String? = null
        var seenHeader: String? = null
        var seenBody: String? = null
        withStubbedHttp({ chain ->
            val request = chain.request()
            seenContentType = request.body?.contentType().toString()
            seenHeader = request.header("Content-Type")
            seenBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }) {
            CustomJsSearchService.search(
                buildJsonObject { put("query", "test") },
                SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    function search() {
                        fetch('https://example.com', {
                            method: 'POST',
                            headers: {'content-type': 'text/plain; charset=utf-8'},
                            body: 'plain-body'
                        });
                        return {items: []};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("plain-body", seenBody)
            assertTrue("expected text/plain media type, got $seenContentType", seenContentType!!.startsWith("text/plain"))
            assertEquals("text/plain; charset=utf-8", seenHeader)
        }
    }

    @Test(timeout = 20_000)
    fun `fetch json tolerates a utf8 bom`() = runBlocking {
        val payload = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """{"results":[{"title":"bom","url":"https://example.com","snippet":"s"}]}"""
                .toByteArray(Charsets.UTF_8)
        withStubbedHttp({ chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType()))
                .build()
        }) {
            val result = CustomJsSearchService.search(
                buildJsonObject { put("query", "test") },
                SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    function search() {
                        const data = fetch('https://example.com').json();
                        return {items: data.results.map(function(r) { return {title: r.title, url: r.url, text: r.snippet}; })};
                    }
                """.trimIndent()),
            ).getOrThrow()
            // Without BOM stripping, JSON.parse would choke on the BOM.
            assertEquals("bom", result.items.single().title)
        }
    }

    @Test(timeout = 20_000)
    fun `fetch text decodes a utf16 bom`() = runBlocking {
        val payload = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "hello 世界".toByteArray(Charsets.UTF_16LE)
        withStubbedHttp({ chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(payload.toResponseBody("text/plain".toMediaType()))
                .build()
        }) {
            val result = CustomJsSearchService.scrape(
                buildJsonObject { put("urls", buildJsonArray { add("https://example.com") }) },
                SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(scrapeScript = """
                    function scrape(urls) {
                        return {urls: [{url: urls[0], content: fetch(urls[0]).text()}]};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("hello 世界", result.urls.single().content)
        }
    }

    @Test(timeout = 20_000)
    fun `scraping supports async functions and response text`() = runBlocking {
        withStubbedHttp({ chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("page content".toResponseBody()).build()
        }) {
            val result = CustomJsSearchService.scrape(
                buildJsonObject { put("urls", buildJsonArray { add("https://example.com") }) },
                SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(scrapeScript = """
                    async function scrape(urls) {
                        const res = await fetch(urls[0]);
                        return {urls: [{url: res.url, content: await res.text()}]};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("page content", result.urls.single().content)
            assertEquals("https://example.com", result.urls.single().url)
        }
    }

    @Test(timeout = 20_000)
    fun `search supports async functions with awaited fetch and json`() = runBlocking {
        withStubbedHttp({ chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"results":[{"title":"async","url":"https://a","snippet":"done"}]}""".toResponseBody())
                .build()
        }) {
            val result = CustomJsSearchService.search(
                buildJsonObject { put("query", "q\"q") },
                SearchCommonOptions(resultSize = 2),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    async function search(query, resultSize) {
                        const res = await fetch('https://example.com/s?q=' + encodeURIComponent(query));
                        const data = await res.json();
                        return {items: data.results.slice(0, resultSize).map(r => ({title: r.title, url: r.url, text: r.snippet}))};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("async", result.items.single().title)
            assertEquals("done", result.items.single().text)
        }
    }

    @Test(timeout = 30_000)
    fun `cancellation interrupts native evaluation as CancellationException`() = runBlocking {
        val observed = CompletableDeferred<Any>()
        val execution = async(Dispatchers.IO) {
            try {
                observed.complete(
                    CustomJsSearchService.search(
                        buildJsonObject { put("query", "test") },
                        SearchCommonOptions(),
                        SearchServiceOptions.CustomJsOptions(
                            searchScript = "function search() { while (true) {} }",
                        ),
                    ).toString()
                )
            } catch (t: Throwable) {
                observed.complete(t)
                throw t
            }
        }
        // Give the evaluation a moment to reach native code; the evidence is the
        // propagated exception type, not this delay.
        delay(200)
        execution.cancel()
        val outcome = withTimeout(15_000) { observed.await() }
        assertTrue(
            "external cancellation must propagate as CancellationException, got $outcome",
            outcome is CancellationException,
        )
        // The next runtime must remain usable after an interrupted engine.
        val ok = CustomJsSearchService.search(
            buildJsonObject { put("query", "test") },
            SearchCommonOptions(),
            SearchServiceOptions.CustomJsOptions(
                searchScript = "function search() { return {items: []}; }",
            ),
        ).getOrThrow()
        assertTrue(ok.items.isEmpty())
    }

    @Test(timeout = 30_000)
    fun `cancellation stops an in flight fetch`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Any>()
        withStubbedHttp({ chain ->
            started.complete(Unit)
            while (!chain.call().isCanceled()) Thread.sleep(5)
            cancelled.complete(Unit)
            throw IOException("Canceled")
        }) {
            val execution = async(Dispatchers.IO) {
                try {
                    observed.complete(
                        CustomJsSearchService.search(
                            buildJsonObject { put("query", "test") },
                            SearchCommonOptions(),
                            SearchServiceOptions.CustomJsOptions(),
                        ).toString()
                    )
                } catch (t: Throwable) {
                    observed.complete(t)
                    throw t
                }
            }
            withTimeout(10_000) { started.await() }
            execution.cancel()
            withTimeout(15_000) {
                // The coroutine cancellation must reach the blocking OkHttp call.
                cancelled.await()
            }
            val outcome = withTimeout(15_000) { observed.await() }
            assertTrue(
                "cancelling an in flight fetch must propagate CancellationException, got $outcome",
                outcome is CancellationException,
            )
        }
    }

    @Test(timeout = 20_000)
    fun `script errors do not poison subsequent searches`() = runBlocking {
        val params = buildJsonObject { put("query", "test") }
        val options = SearchServiceOptions.CustomJsOptions(searchScript = "throw Error('broken');")
        assertTrue(CustomJsSearchService.search(params, SearchCommonOptions(), options).isFailure)
        val valid = options.copy(searchScript = "async function search() { return {items: []}; }")
        assertTrue(CustomJsSearchService.search(params, SearchCommonOptions(), valid).getOrThrow().items.isEmpty())
    }

    @Test(timeout = 20_000)
    fun `search surfaces script throw and syntax errors as failures`() = runBlocking {
        val params = buildJsonObject { put("query", "test") }
        val common = SearchCommonOptions()
        assertTrue(
            CustomJsSearchService.search(
                params, common,
                SearchServiceOptions.CustomJsOptions(
                    searchScript = "function search() { throw new Error('js-failure'); }",
                ),
            ).isFailure,
        )
        assertTrue(
            CustomJsSearchService.search(
                params, common,
                SearchServiceOptions.CustomJsOptions(searchScript = "function search( {"),
            ).isFailure,
        )
        // Errors must not pollute the next runtime.
        val ok = SearchServiceOptions.CustomJsOptions(
            searchScript = "function search() { return {items: [{title: 'ok', url: 'u', text: 't'}]}; }",
        )
        assertEquals(
            "ok",
            CustomJsSearchService.search(params, common, ok).getOrThrow().items.single().title,
        )
    }

    @Test(timeout = 30_000)
    fun `oversized decoded response bodies fail without poisoning the next runtime`() = runBlocking {
        // Above the 8 MiB decoded-body cap; Kotlin-side reading must not fetch
        // unbounded bodies just to let the JS side reject them.
        val bigBody = "x".repeat(9 * 1024 * 1024)
        withStubbedHttp({ chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(bigBody.toResponseBody("text/plain".toMediaType()))
                .build()
        }) {
            val params = buildJsonObject { put("query", "test") }
            val options = SearchServiceOptions.CustomJsOptions(searchScript = """
                function search() {
                    const res = fetch('https://example.com/big');
                    return {items: [{title: 't', url: 'u', text: res.text()}]};
                }
            """.trimIndent())
            val failure = CustomJsSearchService.search(params, SearchCommonOptions(), options).exceptionOrNull()
            assertTrue("expected the HTTP body cap, got $failure", failure?.message?.contains("body exceeds") == true)
            // A capped body failure must not poison the next runtime.
            val ok = options.copy(searchScript = "function search() { return {items: []}; }")
            assertTrue(
                CustomJsSearchService.search(params, SearchCommonOptions(), ok).getOrThrow().items.isEmpty(),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `search results over the serialized cap fail without poisoning the next runtime`() = runBlocking {
        // The serialized result text is capped at 1 MiB inside the engine.
        val params = buildJsonObject { put("query", "test") }
        val options = SearchServiceOptions.CustomJsOptions(searchScript = """
            function search() {
                return {items: [{title: 'x'.repeat(2 * 1024 * 1024), url: 'u', text: 't'}]};
            }
        """.trimIndent())
        val failure = CustomJsSearchService.search(params, SearchCommonOptions(), options).exceptionOrNull()
        assertTrue("expected the serialized output cap, got $failure", failure?.message?.contains("output limit") == true)
        val ok = options.copy(searchScript = "function search() { return {items: []}; }")
        assertTrue(
            CustomJsSearchService.search(params, SearchCommonOptions(), ok).getOrThrow().items.isEmpty(),
        )
    }

    @Test(timeout = 20_000)
    fun `concurrent searches run in isolated runtimes`() = runBlocking {
        val params = buildJsonObject { put("query", "test") }
        val scriptFor = { n: Int ->
            """
            function search() {
                globalThis.marker = $n;
                var acc = 0;
                for (var i = 0; i < 100000; i++) acc += i;
                return {items: [{title: '' + acc + ':' + marker, url: 'u', text: 't'}]};
            }
            """.trimIndent()
        }
        val results = (1..4).map { n ->
            async(Dispatchers.IO) {
                CustomJsSearchService.search(
                    params,
                    SearchCommonOptions(),
                    SearchServiceOptions.CustomJsOptions(searchScript = scriptFor(n)),
                ).getOrThrow()
            }
        }.awaitAll()
        results.forEachIndexed { index, result ->
            // A shared runtime would let other searches overwrite globalThis.marker.
            assertEquals("4999950000:${index + 1}", result.items.single().title)
        }
        // Sequential searches also get clean globals.
        val leaked = SearchServiceOptions.CustomJsOptions(
            searchScript = "globalThis.leaked = 1; function search() { return {items: []}; }",
        )
        assertTrue(CustomJsSearchService.search(params, SearchCommonOptions(), leaked).isSuccess)
        val check = SearchServiceOptions.CustomJsOptions(
            searchScript = "function search() { return {items: [{title: String(typeof leaked), url: 'u', text: 't'}]}; }",
        )
        assertEquals(
            "undefined",
            CustomJsSearchService.search(params, SearchCommonOptions(), check).getOrThrow().items.single().title,
        )
    }

    @Test(timeout = 20_000)
    fun `native timeout covers script invocation and result conversion`() = runBlocking {
        for (script in listOf(
            "while (true) {}",
            "function search() { while (true) {} }",
            "function search() { return {toJSON() { while (true) {} }}; }",
        )) {
            val failure = runCatching {
                CustomJsSearchService.executeScript(script, "search()", timeoutMillis = 200)
            }.exceptionOrNull()
            assertTrue("expected native timeout, got $failure", failure?.message?.contains("timed out") == true)
            assertEquals("{\"items\":[]}", CustomJsSearchService.executeScript(
                "function search() { return {items: []}; }", "search()",
            ))
        }
    }

    @Test(timeout = 20_000)
    fun `search runtime bounds native memory and stack`() = runBlocking {
        for (script in listOf(
            "function search() { return new ArrayBuffer(128 * 1024 * 1024); }",
            "function search() { return search(); }",
        )) {
            val failure = runCatching {
                CustomJsSearchService.executeScript(script, "search()", timeoutMillis = 2_000)
            }.exceptionOrNull()
            assertTrue("expected native resource error, got $failure", failure != null)
        }
        assertEquals("{\"items\":[]}", CustomJsSearchService.executeScript(
            "function search() { return {items: []}; }", "search()",
        ))
    }

    @Test(timeout = 30_000)
    fun `body at exactly eight mib is accepted before small result serialization`() = runBlocking {
        val body = "x".repeat(8 * 1024 * 1024)
        withStubbedHttp({ chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }) {
            val result = CustomJsSearchService.search(
                buildJsonObject { put("query", "test") }, SearchCommonOptions(),
                SearchServiceOptions.CustomJsOptions(searchScript = """
                    function search() {
                        const size = fetch('https://example.com').text().length;
                        return {items: [{title: String(size), url: 'u', text: 't'}]};
                    }
                """.trimIndent()),
            ).getOrThrow()
            assertEquals("8388608", result.items.single().title)
        }
    }

    @Test(timeout = 10_000)
    fun `empty scripts and missing params fail fast`() = runBlocking {
        val common = SearchCommonOptions()
        assertTrue(
            CustomJsSearchService.search(
                buildJsonObject { put("query", "test") }, common,
                SearchServiceOptions.CustomJsOptions(searchScript = ""),
            ).isFailure,
        )
        assertTrue(
            CustomJsSearchService.search(
                buildJsonObject { }, common,
                SearchServiceOptions.CustomJsOptions(
                    searchScript = "function search() { return {items: []}; }",
                ),
            ).isFailure,
        )
        assertTrue(
            CustomJsSearchService.scrape(
                buildJsonObject { put("urls", buildJsonArray { add("https://example.com") }) },
                common,
                SearchServiceOptions.CustomJsOptions(scrapeScript = ""),
            ).isFailure,
        )
        assertTrue(
            CustomJsSearchService.scrape(
                buildJsonObject { }, common,
                SearchServiceOptions.CustomJsOptions(
                    scrapeScript = "function scrape() { return {urls: []}; }",
                ),
            ).isFailure,
        )
    }
}
