package me.rerere.rikkihub.data.creator

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCategory
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.applyBuiltinOverrides
import me.rerere.rikkihub.data.ai.tools.resolvedName
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ToolIds
import me.rerere.rikkihub.data.creator.runtime.CreatorCommandDecoder
import me.rerere.rikkihub.data.creator.runtime.CreatorManagementBridge
import me.rerere.rikkihub.data.creator.runtime.CreatorToolSurface
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.runtime.ManagementRuntime
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 创造工具：七个固定名称、执行直达读取面/结构说明/管理链、描述与提示词走「设置-工具」覆盖。 */
class CreatorToolSurfaceTest {

    private val quick = QuickMessage(id = Uuid.random(), title = "greeting", content = "hello")

    private fun surface(runtime: () -> ManagementRuntime = { error("management chain not ready") }) = CreatorToolSurface(
        readDomain = CreatorReadDomain(
            json = JsonInstant,
            settingsProvider = { Settings(quickMessages = listOf(quick)) },
            assistantRegistry = AssistantRegistry(),
        ),
        bridge = CreatorManagementBridge(runtime = runtime, json = JsonInstant),
    )

    private fun text(parts: List<UIMessagePart>) = (parts.single() as UIMessagePart.Text).text

    @Test
    fun `seven fixed creator tools without approval or workspace identity`() {
        val tools = surface().tools(Uuid.random())
        assertEquals(ToolIds.ALL, tools.map { it.name })
        tools.forEach { tool ->
            assertNull(tool.workspaceIdentity)
            assertFalse(tool.needsApproval(JsonObject(emptyMap())))
        }
    }

    @Test
    fun `list and read go straight to the read domain`() = runBlocking<Unit> {
        val tools = surface().tools(Uuid.random()).associateBy { it.name }
        val listed = text(tools.getValue(ToolIds.LIST_OBJECTS).execute(buildJsonObject {
            put("kind", JsonPrimitive("QUICK_MESSAGE"))
        }))
        assertTrue(listed.contains(quick.id.toString()))
        val read = text(tools.getValue(ToolIds.READ_OBJECT).execute(buildJsonObject {
            put("kind", JsonPrimitive("QUICK_MESSAGE")); put("id", JsonPrimitive(quick.id.toString()))
        }))
        assertTrue(read.contains("hello"))
        val bad = text(tools.getValue(ToolIds.READ_OBJECT).execute(JsonObject(emptyMap())))
        assertEquals("BAD_INPUT", JsonInstant.parseToJsonElement(bad).jsonObject["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `management chain unavailable is reported as a structured rejection`() = runBlocking<Unit> {
        val tool = surface().tools(Uuid.random()).single { it.name == ToolIds.PREVIEW_CHANGE }
        val args = JsonInstant.parseToJsonElement(
            """{"operations":[{"kind":"QUICK_MESSAGE","command":"Create","params":{"fields":{"title":"t","content":"c"}}}]}"""
        )
        val result = text(checkNotNull(tool.executeWithContext)(args, ToolExecutionContext("call-1") {}))
        assertTrue(result, result.contains("\"rejected\""))
        assertTrue(result, result.contains("UNAVAILABLE"))
    }

    @Test
    fun `status reports an unavailable management chain`() = runBlocking<Unit> {
        val tool = surface().tools(Uuid.random()).single { it.name == ToolIds.GET_CHANGE_STATUS }
        val result = text(tool.execute(buildJsonObject { put("proposalId", JsonPrimitive("unknown")) }))
        assertEquals("UNAVAILABLE", JsonInstant.parseToJsonElement(result).jsonObject["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `describe explains every kind and accepts lenient kind names`() = runBlocking<Unit> {
        val tool = surface().tools(Uuid.random()).single { it.name == ToolIds.DESCRIBE }
        me.rerere.rikkihub.data.creator.session.CreatorObjectKind.entries.forEach { kind ->
            val out = JsonInstant.parseToJsonElement(text(tool.execute(buildJsonObject { put("kind", JsonPrimitive(kind.name)) }))).jsonObject
            assertEquals(kind.name, out["kind"]?.jsonPrimitive?.content)
            assertTrue(kind.name, out["summary"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
        }
        val lorebook = text(tool.execute(buildJsonObject { put("kind", JsonPrimitive("world book")) }))
        assertTrue(lorebook, lorebook.contains("\"kind\":\"LOREBOOK\""))
        // Enum values are the stored serial names.
        assertTrue(lorebook, lorebook.contains("after_system_prompt"))
        val unknown = text(tool.execute(buildJsonObject { put("kind", JsonPrimitive("EVERYTHING")) }))
        assertEquals("BAD_INPUT", JsonInstant.parseToJsonElement(unknown).jsonObject["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `catalog defaults are applied and commands placeholder is filled`() {
        val tools = surface().tools(Uuid.random())
            .applyBuiltinOverrides(emptyMap(), CreatorToolSurface.placeholders())
            .associateBy { it.name }
        ToolIds.ALL.forEach { id ->
            val spec = BuiltinToolCatalog.require(id)
            assertEquals(BuiltinToolCategory.CREATOR, spec.category)
            assertFalse(spec.renamable)
        }
        val preview = tools.getValue(ToolIds.PREVIEW_CHANGE)
        assertTrue(preview.description.contains(CreatorCommandDecoder.catalogue()))
        assertFalse(preview.description.contains("{{creator_commands}}"))
        val defaultPrompt = BuiltinToolCatalog.require(ToolIds.PREVIEW_CHANGE).defaultSystemPrompt.orEmpty()
        assertTrue(defaultPrompt.isNotBlank())
        assertEquals(defaultPrompt, preview.systemPrompt(Model(), emptyList()))
    }

    @Test
    fun `overrides change description and prompt but never the name`() {
        val overrides = mapOf(
            ToolIds.PREVIEW_CHANGE to ToolOverride(name = "renamed", description = "custom {{creator_commands}}",
                systemPrompt = "custom prompt"),
        )
        val preview = surface().tools(Uuid.random())
            .applyBuiltinOverrides(overrides, CreatorToolSurface.placeholders())
            .single { it.name == ToolIds.PREVIEW_CHANGE }
        assertEquals("custom " + CreatorCommandDecoder.catalogue(), preview.description)
        assertEquals("custom prompt", preview.systemPrompt(Model(), emptyList()))
        assertEquals(ToolIds.PREVIEW_CHANGE, BuiltinToolCatalog.require(ToolIds.PREVIEW_CHANGE).resolvedName(overrides))
    }
}
