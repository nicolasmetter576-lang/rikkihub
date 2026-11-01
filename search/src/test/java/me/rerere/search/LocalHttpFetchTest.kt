package me.rerere.search

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHttpFetchTest {
    @Test
    fun extractHtmlKeepsTitleAndBodyText() {
        val result = LocalHttpFetch.extractFetchedContent(
            url = "https://example.com/page",
            contentType = "text/html; charset=utf-8",
            body = """
                <html lang="zh">
                  <head>
                    <title>Example Title</title>
                    <meta name="description" content="A short summary">
                    <script>alert(1)</script>
                    <style>body { color: red; }</style>
                  </head>
                  <body>
                    <h1>Hello</h1>
                    <p>World</p>
                  </body>
                </html>
            """.trimIndent(),
            bodyBytes = 0,
        )

        assertEquals("https://example.com/page", result.url)
        assertEquals("Example Title", result.metadata?.title)
        assertEquals("A short summary", result.metadata?.description)
        assertEquals("zh", result.metadata?.language)
        assertTrue(result.content.contains("# Example Title"))
        assertTrue(result.content.contains("Hello"))
        assertTrue(result.content.contains("World"))
        assertTrue(!result.content.contains("alert(1)"))
        assertTrue(!result.content.contains("color: red"))
    }

    @Test
    fun extractJsonKeepsRawBody() {
        val result = LocalHttpFetch.extractFetchedContent(
            url = "https://example.com/api",
            contentType = "application/json",
            body = """{"ok":true}""",
            bodyBytes = 0,
        )

        assertEquals("""{"ok":true}""", result.content)
    }

    @Test
    fun rejectNonHttpUrl() {
        val error = runCatching { LocalHttpFetch.requireHttpUrl("file:///etc/passwd") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("http"))
    }

    @Test
    fun fetchExampleDotCom() = runBlocking {
        val html = """
            <html>
              <head><title>Example Domain</title></head>
              <body><h1>Example Domain</h1><p>This domain is for examples.</p></body>
            </html>
        """.trimIndent()
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        val original = SearchService.httpClient
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        try {
            val result = LocalHttpFetch.fetch("https://example.com/")
            val scraped = result.getOrElse { error("stubbed fetch failed: ${it.message}") }
            val page = scraped.urls.single()
            assertTrue(page.url.startsWith("https://example.com"))
            assertTrue(page.content.contains("Example Domain"))
            assertTrue(page.metadata?.title.orEmpty().contains("Example Domain"))
        } finally {
            SearchService.httpClient = original
        }
    }
}
