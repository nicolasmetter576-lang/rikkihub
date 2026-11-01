package me.rerere.search

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilySearchServiceTest {
    private val common = SearchCommonOptions(resultSize = 7)

    @Test
    fun `answer is requested by default`() {
        val body = TavilySearchService.searchBody("kotlin", "general", common, SearchServiceOptions.TavilyOptions())

        assertEquals(JsonPrimitive("advanced"), body.getValue("include_answer"))
        assertEquals("kotlin", body.getValue("query").jsonPrimitive.content)
        assertEquals(7, body.getValue("max_results").jsonPrimitive.content.toInt())
        assertEquals("advanced", body.getValue("search_depth").jsonPrimitive.content)
        assertEquals("general", body.getValue("topic").jsonPrimitive.content)
        assertTrue(body.getValue("include_images").jsonPrimitive.boolean)
    }

    @Test
    fun `answer can be switched off`() {
        val options = SearchServiceOptions.TavilyOptions(includeAnswer = false, depth = "basic")
        val body = TavilySearchService.searchBody("kotlin", "news", common, options)

        assertFalse(body.getValue("include_answer").jsonPrimitive.boolean)
        assertEquals("basic", body.getValue("search_depth").jsonPrimitive.content)
        assertEquals("news", body.getValue("topic").jsonPrimitive.content)
    }

    @Test
    fun `settings saved before the switch existed still ask for the answer`() {
        val saved = """{"type":"tavily","id":"2a7b1c34-0000-4000-8000-000000000001","apiKey":"k","depth":"basic"}"""

        val options = SearchService.json.decodeFromString<SearchServiceOptions>(saved)

        assertTrue(options is SearchServiceOptions.TavilyOptions)
        assertTrue((options as SearchServiceOptions.TavilyOptions).includeAnswer)
        assertEquals("basic", options.depth)
    }

    @Test
    fun `the switch survives a save and load`() {
        val options: SearchServiceOptions = SearchServiceOptions.TavilyOptions(apiKey = "k", includeAnswer = false)

        val encoded = SearchService.json.encodeToString(SearchServiceOptions.serializer(), options)
        val decoded = SearchService.json.decodeFromString<SearchServiceOptions>(encoded)

        assertFalse((decoded as SearchServiceOptions.TavilyOptions).includeAnswer)
    }

    @Test
    fun `a reply without an answer still decodes`() {
        val response = SearchService.json.decodeFromString<TavilySearchService.SearchResponse>(
            """{"query":"q","results":[{"title":"t","url":"https://example.com","content":"c","score":0.5}]}"""
        )

        assertEquals(null, response.answer)
        assertEquals(1, response.results.size)
    }
}
