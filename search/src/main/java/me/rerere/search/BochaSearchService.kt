package me.rerere.search

import android.util.Log
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

private const val TAG = "BochaSearchService"

object BochaSearchService : SearchService<SearchServiceOptions.BochaOptions> {
    override val name: String = "Bocha"

    @Composable
    override fun Description() {
        DescriptionLink("https://open.bochaai.com/")
    }

    override fun parameters(options: SearchServiceOptions.BochaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BochaOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BochaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("summary", JsonPrimitive(serviceOptions.summary))
                put("count", JsonPrimitive(commonOptions.resultSize))
            }

            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            val request = Request.Builder()
                .url("https://api.bochaai.com/v1/web-search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val bodyRaw = response.body.string()
                    val bochaResponse = runCatching {
                        json.decodeFromString<BochaResponse>(bodyRaw)
                    }.onFailure {
                        Log.w(TAG, "Failed to decode Bocha response", it)
                        error("Failed to decode response: ${bodyRaw.take(500)}")
                    }.getOrThrow()

                    if (bochaResponse.code != 200) {
                        error("Bocha API error: ${bochaResponse.msg ?: "Unknown error"}")
                    }

                    return@withContext Result.success(
                        SearchResult(
                            items = bochaResponse.data?.webPages?.value?.map {
                                SearchResultItem(
                                    title = it.name,
                                    url = it.url,
                                    text = it.summary ?: it.snippet,
                                )
                            } ?: emptyList()
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
        serviceOptions: SearchServiceOptions.BochaOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Bocha"))
    }

    @Serializable
    data class BochaResponse(
        @SerialName("code")
        val code: Int,
        @SerialName("log_id")
        val logId: String? = null,
        @SerialName("msg")
        val msg: String? = null,
        @SerialName("data")
        val data: BochaData? = null
    )

    @Serializable
    data class BochaData(
        @SerialName("_type")
        val type: String? = null,
        @SerialName("queryContext")
        val queryContext: BochaQueryContext? = null,
        @SerialName("webPages")
        val webPages: BochaWebPages? = null,
    )

    @Serializable
    data class BochaQueryContext(
        @SerialName("originalQuery")
        val originalQuery: String
    )

    @Serializable
    data class BochaWebPages(
        @SerialName("webSearchUrl")
        val webSearchUrl: String? = null,
        @SerialName("totalEstimatedMatches")
        val totalEstimatedMatches: Long? = null,
        @SerialName("value")
        val value: List<BochaWebPage> = emptyList(),
        @SerialName("someResultsRemoved")
        val someResultsRemoved: Boolean? = null
    )

    @Serializable
    data class BochaWebPage(
        @SerialName("id")
        val id: String? = null,
        @SerialName("name")
        val name: String,
        @SerialName("url")
        val url: String,
        @SerialName("displayUrl")
        val displayUrl: String? = null,
        @SerialName("snippet")
        val snippet: String,
        @SerialName("summary")
        val summary: String? = null,
        @SerialName("siteName")
        val siteName: String? = null,
        @SerialName("siteIcon")
        val siteIcon: String? = null,
        @SerialName("dateLastCrawled")
        val dateLastCrawled: String? = null,
        @SerialName("cachedPageUrl")
        val cachedPageUrl: String? = null,
        @SerialName("language")
        val language: String? = null,
        @SerialName("isFamilyFriendly")
        val isFamilyFriendly: Boolean? = null,
        @SerialName("isNavigational")
        val isNavigational: Boolean? = null
    )
}
