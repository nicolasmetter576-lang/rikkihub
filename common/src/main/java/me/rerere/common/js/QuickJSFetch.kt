package me.rerere.common.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException

private val json = Json { ignoreUnknownKeys = true }

/**
 * Response bodies are read on the Kotlin heap, which the 64 MiB QuickJS memory
 * limit does not cover. Cap decoded bodies at 8 MiB and fail loudly instead of
 * buffering an unbounded response.
 */
private const val FETCH_MAX_BODY_BYTES = 8L * 1024 * 1024

@Serializable
private data class HttpResponseDto(
    val status: Int,
    val ok: Boolean,
    val statusText: String,
    val body: String,
)

// Keep fetch() synchronous for compatibility with existing custom search scripts.
// Both direct use and `await fetch(...)` work with this Response object.
private const val FETCH_POLYFILL = """
globalThis.fetch = function(url, options) {
    options = options || {};
    var method = (options.method || 'GET').toUpperCase();
    var headers = options.headers ? JSON.stringify(options.headers) : null;
    var body = options.body;
    if (typeof body === 'object' && body !== null) {
        body = JSON.stringify(body);
    } else if (typeof body !== 'string') {
        body = null;
    }

    var raw = __httpRequest(url, method, headers, body);
    var data = JSON.parse(raw);
    return {
        status: data.status,
        ok: data.ok,
        statusText: data.statusText,
        url: url,
        _body: data.body,
        text: function() { return this._body; },
        json: function() { return JSON.parse(this._body); }
    };
};
"""

/**
 * Same decoding as [ResponseBody.string] (including BOM/charset handling),
 * but refuses to buffer more than [FETCH_MAX_BODY_BYTES].
 */
private fun ResponseBody.readBoundedString(): String {
    val source = source()
    if (!source.request(FETCH_MAX_BODY_BYTES + 1)) {
        // Entire body is buffered and fits within the limit. Keep OkHttp's BOM-aware
        // decoder rather than reimplementing charset detection and breaking old scripts.
        return string()
    }
    throw IOException("Response body exceeds $FETCH_MAX_BODY_BYTES bytes limit")
}

suspend fun QuickJs.injectFetch(httpClient: OkHttpClient) {
    val parentJob = currentCoroutineContext().job
    function("__httpRequest") { args ->
        val url = args[0] as? String ?: error("url is required")
        val method = (args[1] as? String ?: "GET").uppercase()
        val headersJson = args[2] as? String
        val body = args[3] as? String

        val requestBuilder = Request.Builder().url(url)

        val parsedHeaders = if (!headersJson.isNullOrBlank() && headersJson != "null") {
            json.parseToJsonElement(headersJson).jsonObject
        } else null

        parsedHeaders?.entries?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value.jsonPrimitive.content)
        }

        val contentType = try {
            parsedHeaders?.entries?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }

        val mediaType = (contentType ?: "application/json").toMediaType()
        when (method) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            else -> {
                val reqBody = body?.toRequestBody(mediaType)
                    ?: if (method in setOf("POST", "PUT", "PATCH")) {
                        "".toRequestBody(mediaType)
                    } else {
                        null
                    }
                requestBuilder.method(method, reqBody)
            }
        }

        val call = httpClient.newCall(requestBuilder.build())
        // Native JS interruption cannot interrupt a blocking HTTP callback. A child job
        // observes cancellation immediately, even while evaluate() is still running.
        val cancellation = Job(parentJob)
        cancellation.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                json.encodeToString(
                    HttpResponseDto(
                        status = response.code,
                        ok = response.isSuccessful,
                        statusText = response.message,
                        body = response.body.readBoundedString(),
                    )
                )
            }
        } finally {
            cancellation.complete()
        }
    }

    evaluate<Unit>(FETCH_POLYFILL + "\nvoid 0;")
}
