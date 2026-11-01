package me.rerere.rikkihub.data.workspace

import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search results must stay complete JSON (result list, citations); other large outputs are still truncated. */
class ToolOutputTruncationExemptTest {
    @Test
    fun `search_web output is exempt even after the tool was renamed`() {
        assertTrue(isToolOutputTruncationExempt(BuiltinToolIds.SEARCH_WEB, emptyMap()))
        val renamed = mapOf(BuiltinToolIds.SEARCH_WEB to ToolOverride(name = "web_lookup"))
        assertTrue(isToolOutputTruncationExempt("web_lookup", renamed))
    }

    @Test
    fun `other tools keep being truncated`() {
        assertFalse(isToolOutputTruncationExempt(BuiltinToolIds.SCRAPE_WEB, emptyMap()))
        assertFalse(isToolOutputTruncationExempt(BuiltinToolIds.WORKSPACE_SHELL, emptyMap()))
        assertFalse(isToolOutputTruncationExempt("mcp__server__search_web", emptyMap()))
        val renamed = mapOf(BuiltinToolIds.SEARCH_WEB to ToolOverride(name = "web_lookup"))
        assertFalse(isToolOutputTruncationExempt("scrape_web", renamed))
    }
}
