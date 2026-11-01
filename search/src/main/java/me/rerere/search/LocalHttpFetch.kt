package me.rerere.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.InputSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.SearchService.Companion.httpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

object LocalHttpFetch {
    val parameters: InputSchema = InputSchema.Obj(
        properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "http(s) url to fetch, like curl/wget GET")
            })
        },
        required = listOf("url"),
    )

    suspend fun fetch(rawUrl: String): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireHttpUrl(rawUrl)
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .build()
            val page = fetchPage(request, FETCH_TIMEOUT_MS)

            ScrapedResult(
                urls = listOf(
                    extractFetchedContent(
                        url = page.url,
                        contentType = page.contentType.orEmpty(),
                        body = decodeBody(page.body, page.charset),
                        bodyBytes = page.body.size,
                    )
                )
            )
        }
    }

    internal fun extractFetchedContent(
        url: String,
        contentType: String,
        body: String,
        bodyBytes: Int,
    ): ScrapedResultUrl {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return when {
            mime.isEmpty() || mime.contains("html") || mime == "application/xhtml+xml" -> {
                val document = Jsoup.parse(body, url)
                ScrapedResultUrl(
                    url = url,
                    content = extractReadableText(document),
                    metadata = ScrapedResultMetadata(
                        title = document.title().ifBlank { null },
                        description = document.selectFirst("meta[name=description]")
                            ?.attr("content")
                            ?.ifBlank { null },
                        language = document.selectFirst("html")?.attr("lang")?.ifBlank { null },
                    ),
                )
            }

            mime.startsWith("text/") || mime in TEXTISH_TYPES -> ScrapedResultUrl(
                url = url,
                content = body.take(MAX_TEXT_CHARS),
                metadata = ScrapedResultMetadata(title = url),
            )

            else -> ScrapedResultUrl(
                url = url,
                content = "Binary or unsupported content type: $contentType ($bodyBytes bytes skipped)",
                metadata = ScrapedResultMetadata(title = url),
            )
        }
    }

    private fun extractReadableText(document: Document): String {
        document.select("script, style, noscript, svg, canvas, iframe").remove()
        val title = document.title().trim()
        val text = document.body().text().trim()
        val combined = buildString {
            if (title.isNotBlank()) {
                appendLine("# $title")
                appendLine()
            }
            append(text)
        }.trim()
        require(combined.isNotBlank()) { "no readable text extracted" }
        return combined.take(MAX_TEXT_CHARS)
    }

    internal fun decodeBody(bytes: ByteArray, charsetName: String?): String {
        val charset = charsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    internal fun requireHttpUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrElse {
            error("invalid url")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "only http(s) urls are allowed"
        }
        require(!uri.host.isNullOrBlank()) { "url host is required" }
        return uri.toString()
    }

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val FETCH_TIMEOUT_MS = 15_000
    const val MAX_BODY_BYTES = 1_048_576
    private const val MAX_TEXT_CHARS = 80_000
    private val TEXTISH_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/xhtml+xml",
        "application/rss+xml",
        "application/atom+xml",
    )
}

internal class FetchedPage(
    val url: String,
    val contentType: String?,
    val charset: String?,
    val body: ByteArray,
)

internal suspend fun fetchPage(request: Request, timeoutMs: Int): FetchedPage =
    withContext(Dispatchers.IO) {
        httpClient.newCall(request).apply {
            timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }.await().use { response ->
            require(response.code in 200..299) {
                "fetch failed: HTTP ${response.code} ${response.message}"
            }

            val responseBody = response.body
            val source = responseBody.source()
            source.request(LocalHttpFetch.MAX_BODY_BYTES.toLong())
            val body = source.buffer.readByteArray(
                minOf(LocalHttpFetch.MAX_BODY_BYTES.toLong(), source.buffer.size)
            )
            require(body.isNotEmpty()) { "empty response body" }

            FetchedPage(
                url = response.request.url.toString(),
                contentType = responseBody.contentType()?.toString(),
                charset = responseBody.contentType()?.charset()?.name(),
                body = body,
            )
        }
    }
