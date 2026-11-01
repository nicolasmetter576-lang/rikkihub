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

object BraveSearchService : SearchService<SearchServiceOptions.BraveOptions> {
    override val name: String = "Brave"

    @Composable
    override fun Description() {
        DescriptionLink("https://api.search.brave.com/")
    }

    override fun parameters(options: SearchServiceOptions.BraveOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BraveOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BraveOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val url = "https://api.search.brave.com/res/v1/web/search" +
                    "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                    "&count=${commonOptions.resultSize}"

            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Subscription-Token", apiKey)
                .build()

            httpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    val searchResponse = json.decodeFromString<BraveSearchResponse>(responseBody)

                    val items = searchResponse.web?.results?.map { result ->
                        SearchResultItem(
                            title = result.title,
                            url = result.url,
                            text = result.description ?: ""
                        )
                    } ?: emptyList()

                    return@withContext Result.success(
                        SearchResult(
                            answer = null,
                            items = items
                        )
                    )
                } else {
                    error("Brave search failed with code ${response.code}: ${response.body.string().take(500)}")
                }
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BraveOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Brave"))
    }

    @Serializable
    data class BraveSearchResponse(
        val type: String? = null,
        val web: WebResults? = null,
    )

    @Serializable
    data class WebResults(
        val type: String? = null,
        val results: List<WebResult>? = null,
    )

    @Serializable
    data class WebResult(
        val type: String,
        val title: String,
        val url: String,
        val description: String? = null,
    )
}
