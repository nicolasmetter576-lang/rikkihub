package me.rerere.rikkihub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** Saving the MCP sheet must not roll back the tool list or OAuth tokens synced while it was open. */
class McpServerEditMergeTest {
    private val id = Uuid.random()

    private fun server(
        name: String = "docs",
        url: String = "https://mcp.example.com/mcp",
        tools: List<McpTool> = emptyList(),
        oauth: McpOAuthState? = null,
        headers: List<Pair<String, String>> = emptyList(),
        enable: Boolean = true,
    ) = McpServerConfig.StreamableHTTPServer(
        id = id,
        url = url,
        commonOptions = McpCommonOptions(enable = enable, name = name, headers = headers, tools = tools, oauth = oauth),
    )

    @Test
    fun `user edits land while the synced tool list and oauth state are kept`() {
        val opened = server(tools = listOf(McpTool(name = "search")), oauth = McpOAuthState(enabled = true, accessToken = "old"))
        val latest = server(
            tools = listOf(McpTool(name = "search", description = "synced"), McpTool(name = "fetch")),
            oauth = McpOAuthState(enabled = true, accessToken = "refreshed", refreshToken = "rotated"),
        )
        val edited = opened.copy(
            url = "https://mcp.example.com/v2",
            commonOptions = opened.commonOptions.copy(
                name = "docs2",
                enable = false,
                headers = listOf("X-Key" to "1"),
                tools = listOf(McpTool(name = "search", enable = false, needsApproval = true, parallel = true)),
            ),
        )

        val merged = latest.withUserEdits(edited) as McpServerConfig.StreamableHTTPServer

        assertEquals(id, merged.id)
        assertEquals("https://mcp.example.com/v2", merged.url)
        assertEquals("docs2", merged.commonOptions.name)
        assertEquals(false, merged.commonOptions.enable)
        assertEquals(listOf("X-Key" to "1"), merged.commonOptions.headers)
        assertEquals("refreshed", merged.commonOptions.oauth?.accessToken)
        assertEquals("rotated", merged.commonOptions.oauth?.refreshToken)
        assertEquals(listOf("search", "fetch"), merged.commonOptions.tools.map { it.name })
        val search = merged.commonOptions.tools.first()
        assertEquals("synced", search.description)
        assertEquals(false, search.enable)
        assertEquals(true, search.needsApproval)
        assertEquals(true, search.parallel)
        assertTrue(merged.commonOptions.tools.last().enable)
    }

    @Test
    fun `switching the transport keeps the edited type`() {
        val latest = server(oauth = McpOAuthState(accessToken = "t"))
        val edited = McpServerConfig.SseTransportServer(
            id = id,
            url = "https://mcp.example.com/sse",
            commonOptions = latest.commonOptions.copy(oauth = null),
        )

        val merged = latest.withUserEdits(edited)

        assertTrue(merged is McpServerConfig.SseTransportServer)
        assertEquals("https://mcp.example.com/sse", merged.serverUrl)
        assertEquals("t", merged.commonOptions.oauth?.accessToken)
    }
}
