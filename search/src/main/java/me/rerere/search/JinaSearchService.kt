package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object JinaSearchService : SearchService<SearchServiceOptions.JinaOptions> {
    private const val DEFAULT_SEARCH_URL = "https://s.jina.ai/"
    private const val DEFAULT_SCRAPE_URL = "https://r.jina.ai/"

    override val name: String = "Jina"

    @Composable
    override fun Description() {
        DescriptionLink("https://jina.ai/")
    }

    override fun parameters(options: SearchServiceOptions.JinaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.JinaOptions): InputSchema? =
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
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("q", query)
            }

            val searchUrl = serviceOptions.searchUrl.ifBlank { DEFAULT_SEARCH_URL }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url(searchUrl)
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body.string().let {
                        json.decodeFromString<JinaSearchResponse>(it)
                    }

                    return@withContext Result.success(
                        SearchResult(
                            items = responseData.data.take(commonOptions.resultSize).map {
                                SearchResultItem(
                                    title = it.title,
                                    url = it.url,
                                    text = it.description
                                )
                            }
                        )
                    )
                } else {
                    error("response failed #${response.code}: ${response.body.string().take(500)}")
                }
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("urls is required")

            val body = buildJsonObject {
                put("url", url)
            }

            val scrapeUrl = serviceOptions.scrapeUrl.ifBlank { DEFAULT_SCRAPE_URL }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url(scrapeUrl)
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Return-Format", "markdown")
                .build()

            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed for url $url #${response.code}: ${response.body.string().take(500)}")
                }
                val responseData = response.body.string().let {
                    json.decodeFromString<JinaScrapeResponse>(it)
                }

                ScrapedResult(
                    urls = listOf(
                        ScrapedResultUrl(
                            url = responseData.data.url,
                            content = responseData.data.content,
                            metadata = ScrapedResultMetadata(
                                title = responseData.data.title,
                                description = responseData.data.description
                            )
                        )
                    )
                )
            }
        }
    }

    @Serializable
    data class JinaSearchResponse(
        val code: Int,
        val status: Int,
        val data: List<JinaSearchResultItem>
    )

    @Serializable
    data class JinaSearchResultItem(
        val title: String,
        val url: String,
        val description: String,
        val content: String = "",
        val usage: JinaUsage? = null
    )

    @Serializable
    data class JinaUsage(
        val tokens: Int
    )

    @Serializable
    data class JinaScrapeResponse(
        val code: Int,
        val status: Int,
        val data: JinaScrapeData
    )

    @Serializable
    data class JinaScrapeData(
        val title: String,
        val description: String = "",
        val url: String,
        val content: String,
        val publishedTime: String? = null,
        val usage: JinaUsage? = null
    )
}
