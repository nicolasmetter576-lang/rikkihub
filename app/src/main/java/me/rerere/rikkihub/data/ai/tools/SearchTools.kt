package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstantPretty
import me.rerere.search.LocalHttpFetch
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = BuiltinToolIds.SEARCH_WEB,
                description = BuiltinToolCatalog.require(BuiltinToolIds.SEARCH_WEB).defaultDescription,
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    ).getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        add(
            Tool(
                name = BuiltinToolIds.SCRAPE_WEB,
                description = BuiltinToolCatalog.require(BuiltinToolIds.SCRAPE_WEB).defaultDescription,
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.scrapingParameters(options) ?: LocalHttpFetch.parameters
                },
                execute = {
                    val url = it.jsonObject["url"]?.jsonPrimitive?.content
                        ?: error("url is required")
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = if (service.scrapingParameters(options) != null) {
                        service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                    } else {
                        LocalHttpFetch.fetch(url)
                    }
                    val payload = JsonInstantPretty.encodeToJsonElement(
                        result.getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                    ).jsonObject
                    listOf(UIMessagePart.Text(payload.toString()))
                }
            )
        )
    }
}
