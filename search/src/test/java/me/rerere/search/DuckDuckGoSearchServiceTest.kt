package me.rerere.search

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoSearchServiceTest {
    @Test
    fun unwrapsUddgRedirect() {
        val raw = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fopenai.com%2F&rut=abc"
        assertEquals("https://openai.com/", DuckDuckGoSearchService.unwrapDuckDuckGoUrl(raw))
    }

    @Test
    fun parseHtmlResults() {
        val html = """
            <div id="links" class="results">
              <div class="result results_links results_links_deep web-result">
                <div class="links_main links_deep result__body">
                  <h2 class="result__title">
                    <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fopenai.com%2F&amp;rut=abc">Official site</a>
                  </h2>
                  <a class="result__snippet">OpenAI is an AI research company.</a>
                </div>
              </div>
            </div>
        """.trimIndent()
        val results = DuckDuckGoSearchService.parseResults(Jsoup.parse(html))
        assertEquals(1, results.size)
        assertEquals("Official site", results.single().title)
        assertEquals("https://openai.com/", results.single().url)
        assertTrue(results.single().text.contains("AI research"))
    }
}
