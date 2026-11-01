package me.rerere.rikkihub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOverrideTest {
    private val dummyTool = Tool(
        name = BuiltinToolIds.EVAL_JAVASCRIPT,
        description = BuiltinToolCatalog.require(BuiltinToolIds.EVAL_JAVASCRIPT).defaultDescription,
        execute = { emptyList() },
    )

    @Test
    fun emptyOverrideKeepsDefaults() {
        val applied = dummyTool.applyBuiltinOverride(emptyMap())
        assertEquals(BuiltinToolIds.EVAL_JAVASCRIPT, applied.name)
        assertEquals(dummyTool.description, applied.description)
    }

    @Test
    fun overrideNameDescriptionAndPrompt() {
        val overrides = mapOf(
            BuiltinToolIds.EVAL_JAVASCRIPT to ToolOverride(
                name = "js_eval",
                description = "Run JS",
                systemPrompt = "Be careful",
            )
        )
        val applied = dummyTool.applyBuiltinOverride(overrides)
        assertEquals("js_eval", applied.name)
        assertEquals("Run JS", applied.description)
        assertEquals("Be careful", applied.systemPrompt(dummyModel(), emptyList()))
    }

    @Test
    fun blankNameFallsBackToDefault() {
        val applied = dummyTool.applyBuiltinOverride(
            mapOf(BuiltinToolIds.EVAL_JAVASCRIPT to ToolOverride(name = "  "))
        )
        assertEquals(BuiltinToolIds.EVAL_JAVASCRIPT, applied.name)
    }

    @Test
    fun interpolatesPlaceholders() {
        val text = interpolateToolText(
            "Today is {{today}} in {{timezone}}",
            mapOf("today" to "2026-01-01", "timezone" to "UTC"),
        )
        assertEquals("Today is 2026-01-01 in UTC", text)
    }

    @Test
    fun resolveRenamedToolId() {
        val overrides = mapOf(
            BuiltinToolIds.ASK_USER to ToolOverride(name = "ask_human")
        )
        assertEquals(
            BuiltinToolIds.ASK_USER,
            resolveBuiltinToolId("ask_human", overrides),
        )
        assertEquals(
            BuiltinToolIds.ASK_USER,
            resolveBuiltinToolId(BuiltinToolIds.ASK_USER, overrides),
        )
    }

    @Test
    fun ttsCustomPromptMergesWithProviderGuidance() {
        val tool = Tool(
            name = BuiltinToolIds.TEXT_TO_SPEECH,
            description = "speak",
            systemPrompt = { _, _ -> "provider guidance" },
            execute = { emptyList() },
        )
        val applied = tool.applyBuiltinOverride(
            mapOf(BuiltinToolIds.TEXT_TO_SPEECH to ToolOverride(systemPrompt = "custom"))
        )
        assertEquals("custom\nprovider guidance", applied.systemPrompt(dummyModel(), emptyList()))
    }

    @Test
    fun mcpToolsAreNotOverridden() {
        val mcp = Tool(
            name = "mcp__server__search",
            description = "remote",
            execute = { emptyList() },
        )
        val applied = mcp.applyBuiltinOverride(
            mapOf("mcp__server__search" to ToolOverride(name = "hacked", description = "nope"))
        )
        assertEquals("mcp__server__search", applied.name)
        assertEquals("remote", applied.description)
    }

    @Test
    fun workspaceShellInterpolatesCwd() {
        val tool = Tool(
            name = BuiltinToolIds.WORKSPACE_SHELL,
            description = BuiltinToolCatalog.require(BuiltinToolIds.WORKSPACE_SHELL).defaultDescription,
            execute = { emptyList() },
        )
        val applied = tool.applyBuiltinOverride(
            overrides = emptyMap(),
            placeholders = builtinToolPlaceholders(cwd = "src"),
        )
        assertTrue(applied.description.contains("Defaults to 'src'."))
        val withoutCwd = tool.applyBuiltinOverride(
            overrides = emptyMap(),
            placeholders = builtinToolPlaceholders(cwd = null),
        )
        assertFalse(withoutCwd.description.contains("Defaults to"))
    }
}

private fun dummyModel(): Model = Model(
    modelId = "dummy",
    displayName = "dummy",
)
