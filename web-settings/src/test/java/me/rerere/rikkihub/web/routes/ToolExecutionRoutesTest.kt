package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.ai.mcp.McpTool
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

/** `GET/PUT /api/tool-execution` 的投影与 merge-patch 纯函数测试。 */
class ToolExecutionRoutesTest {

    private val serverId = Uuid.random()
    private val settings = Settings(
        toolOverrides = mapOf(BuiltinToolIds.WORKSPACE_READ_FILE to ToolOverride(name = "read")),
        mcpServers = listOf(
            McpServerConfig.StreamableHTTPServer(
                id = serverId,
                commonOptions = McpCommonOptions(
                    name = "srv",
                    tools = listOf(
                        McpTool(name = "lookup", readOnlyHint = true),
                        McpTool(name = "write", readOnlyHint = null),
                    ),
                ),
            )
        ),
    )

    private fun assertBadRequest(body: JsonObject) {
        try {
            mergeToolExecution(settings, body)
            fail("expected BadRequestException for $body")
        } catch (expected: BadRequestException) {
            // ok
        }
    }

    @Test
    fun `projection lists builtin and mcp tools with effective values`() {
        val dto = projectToolExecution(settings)
        assertTrue(dto.parallel)
        assertEquals(10, dto.maxParallel)
        val read = dto.builtinTools.single { it.id == BuiltinToolIds.WORKSPACE_READ_FILE }
        assertEquals("read", read.name)
        assertTrue(read.defaultParallel && read.parallel && !read.overridden)
        assertTrue(dto.builtinTools.single { it.id == BuiltinToolIds.STR_REPLACE_EDITOR }.argumentLimited)
        assertFalse(dto.builtinTools.single { it.id == BuiltinToolIds.BASH }.parallel)
        // 只是系统提示的条目不出现
        assertTrue(dto.builtinTools.none { it.id == BuiltinToolIds.WORKSPACE_REMINDER })
        assertEquals(listOf(true, false), dto.mcpTools.map { it.parallel })
        assertEquals("srv", dto.mcpTools.first().serverName)
    }

    @Test
    fun `merge updates global fields and keeps missing keys`() {
        val merged = mergeToolExecution(settings, buildJsonObject { put("maxParallel", 4) })
        assertEquals(ToolExecutionConfig(maxParallel = 4), merged.toolExecution)
        val off = mergeToolExecution(merged, buildJsonObject { put("parallel", false) })
        assertFalse(off.toolExecution.parallel)
        assertEquals(4, off.toolExecution.maxParallel)
    }

    @Test
    fun `builtin overrides store only differences from the default`() {
        val merged = mergeToolExecution(
            settings,
            buildJsonObject {
                put("builtinOverrides", buildJsonObject {
                    put(BuiltinToolIds.WORKSPACE_SHELL, true)
                    put(BuiltinToolIds.SUBAGENT, false)
                    put(BuiltinToolIds.WORKSPACE_READ_FILE, true) // 等于默认 → 不存
                })
            },
        )
        assertEquals(
            mapOf(BuiltinToolIds.WORKSPACE_SHELL to true, BuiltinToolIds.SUBAGENT to false),
            merged.toolExecution.parallelOverrides,
        )
        val reset = mergeToolExecution(
            merged,
            buildJsonObject { put("builtinOverrides", buildJsonObject { put(BuiltinToolIds.SUBAGENT, JsonNull) }) },
        )
        assertEquals(mapOf(BuiltinToolIds.WORKSPACE_SHELL to true), reset.toolExecution.parallelOverrides)
    }

    @Test
    fun `mcp tool override is set and cleared`() {
        val body = { value: kotlinx.serialization.json.JsonElement ->
            buildJsonObject {
                put("mcpTools", buildJsonArray {
                    addJsonObject {
                        put("serverId", serverId.toString())
                        put("tool", "write")
                        put("parallel", value)
                    }
                })
            }
        }
        val merged = mergeToolExecution(settings, body(JsonPrimitive(true)))
        val tool = merged.mcpServers.single().commonOptions.tools.single { it.name == "write" }
        assertEquals(true, tool.parallel)
        // 其他工具与字段原样保留
        assertEquals(true, merged.mcpServers.single().commonOptions.tools.single { it.name == "lookup" }.readOnlyHint)
        val cleared = mergeToolExecution(merged, body(JsonNull))
        assertNull(cleared.mcpServers.single().commonOptions.tools.single { it.name == "write" }.parallel)
    }

    @Test
    fun `invalid bodies are rejected`() {
        assertBadRequest(buildJsonObject { })
        assertBadRequest(buildJsonObject { put("unknown", true) })
        assertBadRequest(buildJsonObject { put("parallel", "yes") })
        assertBadRequest(buildJsonObject { put("maxParallel", 0) })
        assertBadRequest(buildJsonObject { put("maxParallel", 11) })
        assertBadRequest(buildJsonObject { put("maxParallel", 2.5) })
        assertBadRequest(buildJsonObject { put("maxParallel", "3") })
        assertBadRequest(buildJsonObject { put("builtinOverrides", buildJsonObject { put("nope", true) }) })
        assertBadRequest(buildJsonObject {
            put("builtinOverrides", buildJsonObject { put(BuiltinToolIds.WORKSPACE_REMINDER, true) })
        })
        assertBadRequest(buildJsonObject {
            put("builtinOverrides", buildJsonObject { put(BuiltinToolIds.BASH, "true") })
        })
        assertBadRequest(buildJsonObject {
            put("mcpTools", buildJsonArray {
                addJsonObject {
                    put("serverId", Uuid.random().toString())
                    put("tool", "write")
                    put("parallel", true)
                }
            })
        })
        assertBadRequest(buildJsonObject {
            put("mcpTools", buildJsonArray {
                addJsonObject {
                    put("serverId", serverId.toString())
                    put("tool", "missing")
                    put("parallel", true)
                }
            })
        })
        assertBadRequest(buildJsonObject {
            put("mcpTools", buildJsonArray {
                addJsonObject {
                    put("serverId", serverId.toString())
                    put("tool", "write")
                }
            })
        })
    }
}
