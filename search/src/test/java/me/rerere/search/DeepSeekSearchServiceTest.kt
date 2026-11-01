package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepSeekSearchServiceTest {
    @Test
    fun `search response maps summary first and keeps the date`() {
        val response = SearchService.json.decodeFromString<DeepSeekSearchService.DeepSeekSearchResponse>(
            """
            {
              "results": [
                {
                  "rerank_score": 2.4,
                  "title": "DeepSeek V4 model released",
                  "url": "https://example.com/deepseek-v4",
                  "snippet": "short snippet",
                  "summary": "long summary",
                  "content": "full content",
                  "site_name": "Example News",
                  "site_icon": "https://example.com/favicon.ico",
                  "date": "2026-06-01T00:00:00+00:00",
                  "authority": 3
                },
                {
                  "title": "No summary",
                  "url": "https://example.com/b",
                  "snippet": "only snippet",
                  "site_name": null,
                  "date": null
                },
                { "title": "No url" }
              ]
            }
            """.trimIndent()
        )

        val items = DeepSeekSearchService.mapSearchResult(response).items
        assertEquals(listOf("https://example.com/deepseek-v4", "https://example.com/b"), items.map { it.url })
        assertEquals("long summary", items[0].text)
        assertEquals("2026-06-01T00:00:00+00:00", items[0].publishedDate)
        assertEquals("only snippet", items[1].text)
        assertNull(items[1].publishedDate)
    }

    @Test
    fun `error body message is extracted`() {
        val body = """
            {"error":{"message":"'count' must be between 1 and 50","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}
        """.trimIndent()
        assertEquals("#400 'count' must be between 1 and 50", DeepSeekSearchService.errorMessage(400, body))
        assertEquals("#502 Bad Gateway", DeepSeekSearchService.errorMessage(502, "Bad Gateway"))
    }

    @Test
    fun `query is trimmed and capped at 128 characters`() {
        assertEquals("hello", DeepSeekSearchService.normalizeQuery("  hello \n"))
        val long = "搜".repeat(200)
        assertEquals(128, DeepSeekSearchService.normalizeQuery(long).length)
        // 代理对（emoji）按一个字符计，截断不会把它拆成半个
        val emoji = "😀".repeat(130)
        val capped = DeepSeekSearchService.normalizeQuery(emoji)
        assertEquals(128, capped.codePointCount(0, capped.length))
        assertThrows(IllegalArgumentException::class.java) { DeepSeekSearchService.normalizeQuery("   ") }
    }

    @Test
    fun `options serialize with the deepseek type tag`() {
        val options: SearchServiceOptions = SearchServiceOptions.DeepSeekOptions(apiKey = "sk-test")
        val encoded = SearchService.json.encodeToString(SearchServiceOptions.serializer(), options)
        assertEquals(true, encoded.contains("\"type\":\"deepseek\""))
        assertEquals(options, SearchService.json.decodeFromString(SearchServiceOptions.serializer(), encoded))
        assertEquals("DeepSeek", SearchServiceOptions.TYPES[SearchServiceOptions.DeepSeekOptions::class])
    }
}
