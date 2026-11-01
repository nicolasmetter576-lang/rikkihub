package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolConcurrency
import me.rerere.ai.ui.WorkspaceToolIdentity
import me.rerere.ai.core.mayRunInParallel
import me.rerere.rikkihub.data.ai.mcp.McpTool
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolConcurrencyPolicyTest {

    private fun bare(name: String, identity: WorkspaceToolIdentity? = null) = Tool(
        name = name,
        description = name,
        workspaceIdentity = identity,
        execute = { emptyList() },
    )

    private fun args(command: String) = buildJsonObject { put("command", command) }

    @Test
    fun `defaults mark read only tools parallel and the rest exclusive`() {
        val config = ToolExecutionConfig()
        assertTrue(config.isBuiltinParallel(BuiltinToolIds.WORKSPACE_READ_FILE))
        assertTrue(config.isBuiltinParallel(BuiltinToolIds.SUBAGENT))
        assertTrue(config.isBuiltinParallel(BuiltinToolIds.SEARCH_WEB))
        assertFalse(config.isBuiltinParallel(BuiltinToolIds.WORKSPACE_WRITE_FILE))
        assertFalse(config.isBuiltinParallel(BuiltinToolIds.BASH))
        assertFalse(config.isBuiltinParallel(BuiltinToolIds.MEMORY_WRITE))
        assertFalse(config.isBuiltinParallel(BuiltinToolIds.MEMORY_DELETE))
        assertTrue(config.isBuiltinParallel(BuiltinToolIds.MEMORY_READ))
        assertTrue(config.isBuiltinParallel(BuiltinToolIds.MEMORY_LIST))
        assertFalse(config.isBuiltinParallel(BuiltinToolIds.ASK_USER))
        assertFalse(config.isBuiltinParallel(BuiltinToolIds.CREATOR_PREVIEW_CHANGE))
        // 默认表里的每个 id 都是真实存在的可配置工具
        assertTrue(ToolConcurrencyDefaults.configurableIds().containsAll(ToolConcurrencyDefaults.PARALLEL_BY_DEFAULT))
    }

    @Test
    fun `overrides win over defaults`() {
        val config = ToolExecutionConfig(
            parallelOverrides = mapOf(
                BuiltinToolIds.WORKSPACE_READ_FILE to false,
                BuiltinToolIds.WORKSPACE_SHELL to true,
            )
        )
        assertFalse(config.isBuiltinParallel(BuiltinToolIds.WORKSPACE_READ_FILE))
        assertTrue(config.isBuiltinParallel(BuiltinToolIds.WORKSPACE_SHELL))
        assertEquals(ToolConcurrency.Exclusive, config.builtinConcurrency(BuiltinToolIds.WORKSPACE_READ_FILE))
        assertEquals(ToolConcurrency.Parallel, config.builtinConcurrency(BuiltinToolIds.WORKSPACE_SHELL))
    }

    @Test
    fun `str replace editor is parallel only for view`() {
        val concurrency = ToolExecutionConfig().builtinConcurrency(BuiltinToolIds.STR_REPLACE_EDITOR)
        assertTrue(concurrency.mayRunInParallel)
        assertTrue(concurrency.isParallel(args("view")))
        assertFalse(concurrency.isParallel(args("str_replace")))
        assertFalse(concurrency.isParallel(args("create")))
        assertFalse(concurrency.isParallel(buildJsonObject { }))
        val off = ToolExecutionConfig(parallelOverrides = mapOf(BuiltinToolIds.STR_REPLACE_EDITOR to false))
        assertFalse(off.builtinConcurrency(BuiltinToolIds.STR_REPLACE_EDITOR).isParallel(args("view")))
    }

    @Test
    fun `effective limit honours switch and range`() {
        assertEquals(10, ToolExecutionConfig().effectiveLimit())
        assertEquals(1, ToolExecutionConfig(parallel = false).effectiveLimit())
        assertEquals(1, ToolExecutionConfig(maxParallel = 0).effectiveLimit())
        assertEquals(MAX_PARALLEL_TOOL_CALLS, ToolExecutionConfig(maxParallel = 99).effectiveLimit())
        assertEquals(4, ToolExecutionConfig(maxParallel = 4).effectiveLimit())
    }

    @Test
    fun `apply uses workspace identity and leaves non builtin tools alone`() {
        val identity = WorkspaceToolIdentity(
            workspaceId = "ws",
            stableId = BuiltinToolIds.WORKSPACE_READ_FILE,
            implementationId = "impl",
        )
        val renamed = bare("my_reader", identity)
        val search = bare(BuiltinToolIds.SEARCH_WEB)
        val mcp = bare("mcp__srv__lookup").copy(concurrency = ToolConcurrency.Parallel)
        val custom = bare("echo_tool")
        val applied = listOf(renamed, search, mcp, custom).applyBuiltinConcurrency(ToolExecutionConfig())
        assertEquals(ToolConcurrency.Parallel, applied[0].concurrency)
        assertEquals(ToolConcurrency.Parallel, applied[1].concurrency)
        assertSame(mcp, applied[2])
        assertSame(custom, applied[3])
        assertEquals(ToolConcurrency.Exclusive, applied[3].concurrency)
    }

    @Test
    fun `mcp tools follow read only hint unless overridden`() {
        assertTrue(McpTool(name = "a", readOnlyHint = true).isParallel)
        assertFalse(McpTool(name = "a", readOnlyHint = null).isParallel)
        assertFalse(McpTool(name = "a", readOnlyHint = false).isParallel)
        assertFalse(McpTool(name = "a", readOnlyHint = true, parallel = false).isParallel)
        assertTrue(McpTool(name = "a", readOnlyHint = null, parallel = true).isParallel)
    }

    @Test
    fun `settings without tool execution decode to defaults and round trip`() {
        val decoded = JsonInstant.decodeFromString<Settings>("{}")
        assertEquals(ToolExecutionConfig(), decoded.toolExecution)
        val original = Settings(
            toolExecution = ToolExecutionConfig(
                parallel = false,
                maxParallel = 3,
                parallelOverrides = mapOf(BuiltinToolIds.SUBAGENT to false),
            )
        )
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(original))
        assertEquals(original.toolExecution, restored.toolExecution)
        // 旧 McpTool（无新字段）照常解码
        val tool = JsonInstant.decodeFromString<McpTool>("""{"name":"t","enable":true}""")
        assertEquals(null, tool.readOnlyHint)
        assertEquals(null, tool.parallel)
    }
}
