package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** DeepSeek 官方 Search / Fetch 接口（https://api-docs.deepseek.com/guides/search） */
object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name: String = "DeepSeek"

    private const val SEARCH_URL = "https://api.deepseek.com/v1/tools/search"
    private const val FETCH_URL = "https://api.deepseek.com/v1/tools/fetch"

    // 接口要求 query 为 1～128 个字符，count 为 1～50
    private const val MAX_QUERY_LENGTH = 128
    private const val MAX_COUNT = 50

    @Composable
    override fun Description() {
        DescriptionLink("https://platform.deepseek.com/api_keys")
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "search keyword, 1-128 characters. Supports operators: site:, -site:, -keyword, \"exact phrase\", " +
                            "lang:zh,en, after:2025-01-01, before:2026-06-01"
                    )
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = normalizeQuery(params["query"]?.jsonPrimitive?.content ?: error("query is required"))
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize.coerceIn(1, MAX_COUNT))
            }
            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            httpClient.newCall(request).await().use { response ->
                val bodyRaw = response.body.string()
                if (!response.isSuccessful) {
                    error("DeepSeek search failed: ${errorMessage(response.code, bodyRaw)}")
                }
                mapSearchResult(json.decodeFromString<DeepSeekSearchResponse>(bodyRaw))
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: error("url is required")
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val body = buildJsonObject {
                put("url", url)
            }
            val request = Request.Builder()
                .url(FETCH_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            httpClient.newCall(request).await().use { response ->
                val bodyRaw = response.body.string()
                if (!response.isSuccessful) {
                    error("DeepSeek fetch failed: ${errorMessage(response.code, bodyRaw)}")
                }
                val fetchResponse = json.decodeFromString<DeepSeekFetchResponse>(bodyRaw)
                ScrapedResult(
                    urls = listOf(
                        ScrapedResultUrl(
                            url = url,
                            content = fetchResponse.content,
                            metadata = ScrapedResultMetadata(title = fetchResponse.title.ifBlank { null }),
                        )
                    )
                )
            }
        }
    }

    /** 去掉首尾空白并截到接口允许的长度（按 Unicode 字符计），空的直接报错，不浪费一次计费请求 */
    internal fun normalizeQuery(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "query is required" }
        if (trimmed.codePointCount(0, trimmed.length) <= MAX_QUERY_LENGTH) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_QUERY_LENGTH)).trimEnd()
    }

    internal fun mapSearchResult(response: DeepSeekSearchResponse): SearchResult = SearchResult(
        items = response.results
            .filter { it.url.isNotBlank() }
            .map { result ->
                SearchResultItem(
                    title = result.title,
                    url = result.url,
                    text = result.summary.ifBlank { result.snippet },
                    publishedDate = result.date,
                )
            }
    )

    internal fun errorMessage(code: Int, bodyRaw: String): String {
        val message = runCatching {
            json.decodeFromString<DeepSeekErrorResponse>(bodyRaw).error.message
        }.getOrNull() ?: bodyRaw.take(500)
        return "#$code $message"
    }

    @Serializable
    data class DeepSeekSearchResponse(
        val results: List<DeepSeekSearchResultItem> = emptyList(),
    )

    @Serializable
    data class DeepSeekSearchResultItem(
        val title: String = "",
        val url: String = "",
        val snippet: String = "",
        val summary: String = "",
        val content: String = "",
        @SerialName("site_name")
        val siteName: String? = null,
        val date: String? = null,
        @SerialName("rerank_score")
        val rerankScore: Double? = null,
        val authority: Int? = null,
    )

    @Serializable
    data class DeepSeekFetchResponse(
        val title: String = "",
        val content: String = "",
    )

    @Serializable
    data class DeepSeekErrorResponse(
        val error: DeepSeekError,
    )

    @Serializable
    data class DeepSeekError(
        val message: String,
    )
}
