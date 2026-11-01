package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {
    override val name: String = "DuckDuckGo"

    @Composable
    override fun Description() {
        Text("No API key. Uses DuckDuckGo HTML results. Local HTTP fetch is always available as scrape_web.")
    }

    override fun parameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(query.isNotBlank()) { "query is required" }

            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val url = SEARCH_URL + URLEncoder.encode(query, Charsets.UTF_8.name())
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", LocalHttpFetch.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", acceptLanguage)
                .header("Referer", "https://html.duckduckgo.com/html/")
                .build()
            val page = fetchPage(request, TIMEOUT_MS)
            val doc = Jsoup.parse(LocalHttpFetch.decodeBody(page.body, page.charset), page.url)

            val results = parseResults(doc).take(commonOptions.resultSize)

            require(results.isNotEmpty()) { "Search failed: no results found" }
            SearchResult(items = results)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<ScrapedResult> = Result.failure(Exception("Use LocalHttpFetch for scrape_web"))

    internal fun parseResults(doc: org.jsoup.nodes.Document): List<SearchResultItem> =
        doc.select("div.web-result").mapNotNull { element ->
            val titleEl = element.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            val href = titleEl.attr("abs:href").ifBlank { titleEl.attr("href") }
            val link = unwrapDuckDuckGoUrl(href)
            val snippet = element.selectFirst("a.result__snippet")?.text()?.trim().orEmpty()
                .ifBlank { element.selectFirst(".result__snippet")?.text()?.trim().orEmpty() }
            if (title.isBlank() || link.isBlank()) null
            else SearchResultItem(title = title, url = link, text = snippet)
        }

    internal fun unwrapDuckDuckGoUrl(raw: String): String {
        val href = raw.trim()
        val marker = "uddg="
        val start = href.indexOf(marker)
        if (start < 0) return href
        val encoded = href.substring(start + marker.length)
            .substringBefore('&')
            .substringBefore('#')
        return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrDefault(href)
    }

    private const val SEARCH_URL = "https://html.duckduckgo.com/html/?q="
    private const val TIMEOUT_MS = 10_000
}
