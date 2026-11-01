package me.rerere.rikkihub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.buffer

/**
 * HTTP request logger for the in-app log page (on by default since 2.8.0, user-switchable). Every header on the credential
 * denylist is masked before the entry is stored — auth material must never
 * reach the in-app log surface. Query credentials (`key=`/
 * `token=`/`access_token=`/`ticket=` style params) are masked in the logged
 * URL as well.
 */
class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toRedactedMap()
        val requestBody = request.body?.let { body -> loggedRequestBody(body, MAX_LOGGED_BODY_BYTES) }

        val loggedUrl = redactLoggedUrl(request.url.toString())

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = loggedUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toRedactedMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = loggedUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private companion object {
        const val MAX_LOGGED_BODY_BYTES = 4096
    }
}

/**
 * Head of a request body for the in-memory log. Memory stays bounded by [limit] (the rest of
 * the body is streamed through and discarded, never buffered). One-shot and duplex bodies are
 * not read at all, so logging can never consume the body the real request needs.
 */
internal fun loggedRequestBody(body: okhttp3.RequestBody, limit: Int): String {
    if (body.isOneShot() || body.isDuplex()) return "[streaming body not captured]"
    val capped = HeadCaptureSink(limit.toLong())
    capped.buffer().use { body.writeTo(it) }
    val text = capped.head.readUtf8()
    return if (capped.truncated) "$text…[truncated]" else text
}

private class HeadCaptureSink(private val limit: Long) : okio.Sink {
    val head = Buffer()
    var truncated = false
        private set

    override fun write(source: Buffer, byteCount: Long) {
        val keep = minOf(byteCount, limit - head.size).coerceAtLeast(0)
        if (keep > 0) head.write(source, keep)
        if (byteCount > keep) {
            truncated = true
            source.skip(byteCount - keep)
        }
    }

    override fun flush() = Unit
    override fun timeout(): okio.Timeout = okio.Timeout.NONE
    override fun close() = Unit
}

private const val REDACTED = "██"

/**
 * Headers that can carry credential material (auth tokens, cookies, API keys,
 * signatures). Compared case-insensitively; anything matching is masked.
 */
private val SENSITIVE_HEADER_NAMES = setOf(
    "authorization",
    "proxy-authorization",
    "www-authenticate",
    "proxy-authenticate",
    "cookie",
    "set-cookie",
    "x-api-key",
    "api-key",
    "x-goog-api-key",
    "anthropic-api-key",
    "x-auth-token",
    "x-access-token",
    "x-session-token",
    "x-device-token",
    "x-csrf-token",
    "x-xsrf-token",
    "private-token",
    "session-token",
)

/** Query parameters whose values may be credential material in a logged URL. */
private val SENSITIVE_QUERY_PARAMS = setOf(
    "access_token",
    "ticket",
    "token",
    "key",
    "api_key",
    "apikey",
    "password",
    "secret",
    "signature",
    "sig",
    "auth",
)

internal fun okhttp3.Headers.toRedactedMap(): Map<String, String> {
    return names().associateWith { name ->
        if (name.lowercase() in SENSITIVE_HEADER_NAMES) {
            REDACTED
        } else {
            get(name) ?: ""
        }
    }
}

/**
 * Mask credential-looking query params in a logged URL. Non-URL input is
 * returned unchanged (fail-open for the display string only — secrets still
 * never appear because the mask runs before logging).
 */
internal fun redactLoggedUrl(url: String): String {
    val queryIndex = url.indexOf('?')
    if (queryIndex < 0) return url
    val base = url.substring(0, queryIndex)
    val rest = url.substring(queryIndex + 1)
    val fragmentIndex = rest.indexOf('#')
    val query = if (fragmentIndex >= 0) rest.substring(0, fragmentIndex) else rest
    val fragment = if (fragmentIndex >= 0) rest.substring(fragmentIndex) else ""
    val redacted = query.split('&').joinToString("&") { pair ->
        val name = pair.substringBefore('=')
        val decoded = runCatching { java.net.URLDecoder.decode(name, Charsets.UTF_8) }
            .getOrDefault(name)
        if (decoded.lowercase() in SENSITIVE_QUERY_PARAMS && pair.contains('=')) {
            "$name=$REDACTED"
        } else {
            pair
        }
    }
    return "$base?$redacted$fragment"
}
