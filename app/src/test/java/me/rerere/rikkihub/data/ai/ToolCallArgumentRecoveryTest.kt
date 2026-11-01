package me.rerere.rikkihub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallArgumentRecoveryTest {

    private fun tool(
        id: String,
        input: String,
        output: List<UIMessagePart> = emptyList(),
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "echo_tool",
        input = input,
        output = output,
        approvalState = approvalState,
        metadata = buildJsonObject { put("sig", JsonPrimitive("s")) },
    )

    private fun assistant(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `split keeps first id and metadata, derives the rest without collisions`() {
        val message = assistant(
            tool("call_1", """{"a":1}{"a":2}{"a":3}"""),
            tool("call_1_2", """{"b":1}"""),
        )
        val (result, splits) = message.splitConcatenatedToolCalls(JsonInstant)
        val tools = result.getTools()
        assertEquals(listOf("call_1", "call_1_3", "call_1_4", "call_1_2"), tools.map { it.toolCallId })
        assertEquals("s", (tools[0].metadata!!["sig"] as JsonPrimitive).content)
        assertNull(tools[1].metadata)
        assertEquals(listOf(ToolCallSplit("echo_tool", "call_1", 3, 0)), splits)
    }

    @Test
    fun `derived ids stay within the 40 char limit`() {
        val longId = "chatcmpl-0123456789abcdef0123456789:tool-0"
        val (result, _) = assistant(tool(longId, """{"a":1}{"a":2}"""))
            .splitConcatenatedToolCalls(JsonInstant)
        val derived = result.getTools()[1].toolCallId
        assertTrue(derived, derived.length <= 40 && derived.startsWith("call_"))
    }

    @Test
    fun `executed, pending, valid and unsplittable parts are left untouched`() {
        val message = assistant(
            tool("e", """{"a":1}{"a":2}""", output = listOf(UIMessagePart.Text("done"))),
            tool("p", """{"a":1}{"a":2}""", approvalState = ToolApprovalState.Pending),
            tool("v", """{"a":1}"""),
            tool("b", """{"a":1}{"a":"""),
        )
        val (result, splits) = message.splitConcatenatedToolCalls(JsonInstant)
        assertSame(message, result)
        assertTrue(splits.isEmpty())
    }

    @Test
    fun `calls with names outside the known table are not split`() {
        val message = assistant(tool("call_1", """{"a":1}{"a":2}"""))
        val (result, splits) = message.splitConcatenatedToolCalls(JsonInstant, setOf("other_tool"))
        assertSame(message, result)
        assertTrue(splits.isEmpty())
        // 名字在表里照常拆
        val (_, known) = message.splitConcatenatedToolCalls(JsonInstant, setOf("echo_tool"))
        assertEquals(1, known.size)
    }
}
