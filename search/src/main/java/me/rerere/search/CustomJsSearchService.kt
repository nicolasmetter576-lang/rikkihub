package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.common.js.injectFetch
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import kotlin.coroutines.coroutineContext

object CustomJsSearchService : SearchService<SearchServiceOptions.CustomJsOptions> {
    override val name: String = "Custom JS"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.custom_js_desc))
    }

    override fun parameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? {
        if (options.scrapeScript.isBlank()) return null
        return InputSchema.Obj(
            properties = buildJsonObject {
                put("urls", buildJsonObject {
                    put("type", "array")
                    put("description", "urls to scrape")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
            },
            required = listOf("urls")
        )
    }

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val script = serviceOptions.searchScript.ifBlank { error("Search script is empty") }

            val resultJson = executeScript(
                userScript = script,
                invocation = "search(${JsonPrimitive(query)}, ${commonOptions.resultSize})"
            )

            json.decodeFromString<SearchResult>(resultJson)
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val script = serviceOptions.scrapeScript.ifBlank { error("Scrape script is empty") }
            val urlsJson = params["urls"]?.toString() ?: error("urls is required")

            val resultJson = executeScript(
                userScript = script,
                invocation = "scrape($urlsJson)"
            )

            json.decodeFromString<ScrapedResult>(resultJson)
        }.onFailure { if (it is CancellationException) throw it }
    }

    internal suspend fun executeScript(
        userScript: String,
        invocation: String,
        timeoutMillis: Long = JS_EXECUTION_TIMEOUT_MS,
    ): String =
        try {
            withTimeoutOrNull(timeoutMillis) {
                quickJs(Dispatchers.IO) {
                    memoryLimit = 64L * 1024 * 1024
                    maxStackSize = 256L * 1024
                    evaluationTimeoutMillis = timeoutMillis
                    // The previous runtime supplied console even without a log listener.
                    evaluate<Unit>("globalThis.console = { log() {}, info() {}, warn() {}, error() {}, debug() {} }; void 0;")
                    injectFetch(httpClient)
                    evaluate<Unit>(userScript + "\n;void 0;")

                    // Await and serialize inside the interruptible engine. Do not transfer
                    // an arbitrarily large JS value/string to the Kotlin heap first.
                    evaluate<String?>(
                        """
                        await (async () => {
                            const result = await $invocation;
                            const text = JSON.stringify(result);
                            if (text == null) return null;
                            if (typeof text !== 'string' || text.length > $JS_MAX_RESULT_CHARS) {
                                throw new Error('JavaScript search result exceeds output limit');
                            }
                            return text;
                        })()
                        """.trimIndent()
                    ) ?: error("Function returned null or undefined")
                }
            } ?: error("JavaScript search execution timed out after ${timeoutMillis}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (_: QuickJsInterruptedException) {
            // The engine also interrupts on coroutine cancellation; do not report an
            // outer cancellation as a normal timeout Result.
            coroutineContext.ensureActive()
            error("JavaScript search execution timed out after ${timeoutMillis}ms")
        }
}

private const val JS_EXECUTION_TIMEOUT_MS = 30_000L
private const val JS_MAX_RESULT_CHARS = 1024 * 1024
