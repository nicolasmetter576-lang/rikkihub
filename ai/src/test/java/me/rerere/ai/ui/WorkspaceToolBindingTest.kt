package me.rerere.ai.ui

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作区工具身份与 Tool part 的序列化/合并回归（2.7.0 起无签名绑定）。 */
class WorkspaceToolBindingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val identity = WorkspaceToolIdentity(
        workspaceId = "ws-1",
        stableId = "workspace_shell",
        implementationId = "workspace_shell",
        mode = WorkspaceToolMode.NORMAL,
        viewFingerprint = "view-fp-1",
    )

    private fun toolPart() = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "renamed_shell",
        input = "{\"command\":\"ls\"}",
    )

    // ---------- 序列化 ----------

    @Test
    fun `tool part round trip`() {
        val part: UIMessagePart = toolPart()
        assertEquals(part, json.decodeFromString<UIMessagePart>(json.encodeToString(part)))
    }

    @Test
    fun `legacy json with a stored localBinding still decodes`() {
        // 2.7.0 之前的工作区调用带签名绑定字段；字段已移除，旧数据按未知键忽略。
        val legacyJson = """
            {"type":"tool","toolCallId":"c1","toolName":"workspace_shell",
             "input":"{}","output":[],"approvalState":{"type":"auto"},
             "localBinding":{"identity":{"workspaceId":"w","stableId":"workspace_shell"},
              "originalWireName":"workspace_shell","conversationId":"c","messageId":"m",
              "toolCallId":"c1","issuedAt":1,"signature":"aa"}}
        """.trimIndent()
        val part = json.decodeFromString<UIMessagePart>(legacyJson) as UIMessagePart.Tool
        assertEquals("workspace_shell", part.toolName)
        assertEquals("c1", part.toolCallId)
    }

    @Test
    fun `identity defaults keep backward compatible decoding`() {
        val minimal = """
            {"workspaceId":"w","stableId":"bash"}
        """.trimIndent()
        val decoded = json.decodeFromString<WorkspaceToolIdentity>(minimal)
        assertEquals("bash", decoded.implementationId)
        assertEquals(WorkspaceToolMode.NORMAL, decoded.mode)
        assertNull(decoded.viewFingerprint)
    }

    @Test
    fun `identity serializes into wire field names`() {
        val encoded = json.encodeToString(identity)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("workspace_shell", obj["stableId"]?.let { it.toString().trim('"') })
    }

    // ---------- identity.matches ----------

    @Test
    fun `identity matches requires all fields`() {
        assertTrue(identity.matches(identity.copy()))
        assertFalse(identity.matches(identity.copy(workspaceId = "ws-2")))
        assertFalse(identity.matches(identity.copy(stableId = "bash")))
        assertFalse(identity.matches(identity.copy(implementationId = "other")))
        assertFalse(identity.matches(identity.copy(mode = WorkspaceToolMode.HARNESS_MINIMAL)))
        assertFalse(identity.matches(identity.copy(viewFingerprint = null)))
        assertFalse(identity.matches(identity.copy(viewFingerprint = "view-fp-2")))
    }

    // ---------- merge ----------

    @Test
    fun `merge concatenates wire fields`() {
        val merged = toolPart().merge(
            toolPart().copy(toolName = "_x", input = ",\"b\":1"),
        )
        assertEquals("renamed_shell_x", merged.toolName)
        assertEquals("{\"command\":\"ls\"},\"b\":1", merged.input)
    }

    // ---------- Tool.workspaceIdentity ----------

    @Test
    fun `core tool workspace identity defaults to null`() {
        val tool = me.rerere.ai.core.Tool(
            name = "x",
            description = "d",
            execute = { emptyList() },
        )
        assertNull(tool.workspaceIdentity)
        val bound = tool.copy(workspaceIdentity = identity)
        assertEquals(identity, bound.workspaceIdentity)
    }
}
