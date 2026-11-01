package me.rerere.search

import org.junit.Assert.assertNull
import org.junit.Test

class BingSearchServiceTest {
    @Test
    fun bingDoesNotExposeDedicatedScrapeSchema() {
        assertNull(
            BingSearchService.scrapingParameters(SearchServiceOptions.BingLocalOptions())
        )
    }
}
