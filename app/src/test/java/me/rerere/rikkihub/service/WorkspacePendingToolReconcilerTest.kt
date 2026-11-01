package me.rerere.rikkihub.service

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * pending 清理的纯逻辑回归（无 Android/DB 依赖）：judge 只看会话工作区的当前状态，
 * 没有签名/进程会话/纪元比对（2.7.0 起）。
 */
class WorkspacePendingToolReconcilerTest {

    private fun workspace(
        id: String = "ws-1",
        harnessMinimal: Boolean = false,
        shellStatus: String = WorkspaceShellStatus.READY.name,
        toolEnablement: String = "{}",
    ) = WorkspaceEntity(
        id = id,
        name = "ws",
        root = id,
        createdAt = 1L,
        updatedAt = 1L,
        shellStatus = shellStatus,
        harnessMinimal = harnessMinimal,
        toolEnablement = toolEnablement,
    )

    private fun judge(
        sessionWorkspace: WorkspaceEntity?,
        overrides: Map<String, ToolOverride> = emptyMap(),
    ) = buildWorkspacePendingToolJudge(
        sessionWorkspace = sessionWorkspace,
        toolOverrides = overrides,
    )

    private fun toolPart(
        wireName: String,
        input: String = "{}",
        executed: Boolean = false,
        metadata: kotlinx.serialization.json.JsonObject? = null,
        toolCallId: String = "call-1",
    ) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = wireName,
        input = input,
        output = if (executed) listOf(UIMessagePart.Text("done")) else emptyList(),
        approvalState = ToolApprovalState.Pending,
        metadata = metadata,
    )

    private fun conversationWith(
        messages: List<UIMessage>,
        branchMessages: List<UIMessage> = emptyList(),
    ): Conversation {
        val nodes = mutableListOf<MessageNode>()
        messages.forEach { nodes += MessageNode(messages = listOf(it), selectIndex = 0) }
        // 非当前分支：同一节点放第二条候选消息（selectIndex 仍指向第一条）
        if (branchMessages.isNotEmpty() && nodes.isNotEmpty()) {
            val last = nodes.last()
            nodes[nodes.lastIndex] = last.copy(messages = last.messages + branchMessages)
        }
        return Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = nodes)
    }

    private fun assistantWith(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun outputJson(part: UIMessagePart.Tool) =
        part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.let {
            runCatching { JsonInstant.parseToJsonElement(it.text).jsonObject }.getOrNull()
        }

    private fun statusOf(part: UIMessagePart.Tool): String? = outputJson(part)?.get("status")?.jsonPrimitive?.content
    private fun reasonOf(part: UIMessagePart.Tool): String? = outputJson(part)?.get("reason")?.jsonPrimitive?.content

    @Test
    fun `enabled workspace pending call stays ready, also across a process restart`() {
        // 没有进程会话/签名：只要工具当前仍启用，挂起调用保持可批准。
        val part = toolPart("workspace_shell", "{\"command\":\"ls\"}")
        val conversation = conversationWith(listOf(assistantWith(part)))
        val result = conversation.patchWorkspacePendingToolCalls(judge(workspace()))
        assertEquals(1, result.examinedParts)
        assertEquals(0, result.patchedParts)
        assertSame(conversation, result.conversation)
    }

    @Test
    fun `disabled tool becomes unavailable`() {
        val disabled = workspace(toolEnablement = """{"workspace_shell":false}""")
        val conversation = conversationWith(listOf(assistantWith(toolPart("workspace_shell"))))
        val result = conversation.patchWorkspacePendingToolCalls(judge(disabled))
        assertEquals(1, result.patchedParts)
        val patched = result.conversation.messageNodes.last().messages[0].parts[0] as UIMessagePart.Tool
        assertEquals("unavailable", statusOf(patched))
        assertEquals("TOOL_DISABLED", reasonOf(patched))
    }

    @Test
    fun `workspace-shaped calls without a usable workspace close while other tools stay ready`() {
        val conversation = conversationWith(
            listOf(
                assistantWith(
                    toolPart("workspace_shell"),
                    toolPart("search_web", toolCallId = "call-2"),
                ),
            ),
        )
        val result = conversation.patchWorkspacePendingToolCalls(judge(sessionWorkspace = null))
        assertEquals(2, result.examinedParts)
        assertEquals(1, result.patchedParts)
        val parts = result.conversation.messageNodes.last().messages[0].parts.map { it as UIMessagePart.Tool }
        assertEquals(WORKSPACE_UNAVAILABLE_REASON, reasonOf(parts[0]))
        assertFalse(parts[1].isExecuted)
    }

    @Test
    fun `not ready workspace closes its pending calls as disabled`() {
        val installing = workspace(shellStatus = WorkspaceShellStatus.INSTALLING.name)
        val conversation = conversationWith(listOf(assistantWith(toolPart("workspace_shell"))))
        val result = conversation.patchWorkspacePendingToolCalls(judge(installing))
        assertEquals(1, result.patchedParts)
    }

    @Test
    fun `renamed enabled tool via override stays ready under its current name`() {
        val overrides = mapOf(BuiltinToolIds.WORKSPACE_SHELL to ToolOverride(name = "my_shell"))
        val conversation = conversationWith(listOf(assistantWith(toolPart("my_shell"))))
        val result = conversation.patchWorkspacePendingToolCalls(judge(workspace(), overrides))
        assertEquals(0, result.patchedParts)
    }

    @Test
    fun `non-current branch pending part is repaired and executed history untouched`() {
        val hostMessage = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val pendingPart = toolPart("workspace_shell")
        val executedPart = toolPart("workspace_read_file", executed = true)
        val conversation = conversationWith(
            listOf(hostMessage.copy(parts = listOf(executedPart))),
            branchMessages = listOf(hostMessage.copy(parts = listOf(pendingPart))),
        )

        val disabledWs = workspace(toolEnablement = """{"workspace_shell":false}""")
        val result = conversation.patchWorkspacePendingToolCalls(judge(disabledWs))

        assertEquals(1, result.examinedParts) // 已执行 part 不计入
        assertEquals(1, result.patchedParts)
        val node = result.conversation.messageNodes.last()
        val branchPart = node.messages[1].parts[0] as UIMessagePart.Tool
        assertTrue(branchPart.isExecuted)
        assertEquals("unavailable", statusOf(branchPart))
        assertSame(executedPart, node.messages[0].parts[0])
    }

    @Test
    fun `duplicate resolved wire names are explicitly rejected`() {
        val overrides = mapOf(
            BuiltinToolIds.WORKSPACE_SHELL to ToolOverride(name = "dup_name"),
            BuiltinToolIds.WORKSPACE_READ_FILE to ToolOverride(name = "dup_name"),
        )
        val conversation = conversationWith(listOf(assistantWith(toolPart("dup_name"))))
        val result = conversation.patchWorkspacePendingToolCalls(judge(workspace(), overrides))
        assertEquals(1, result.patchedParts)
        val patched = result.conversation.messageNodes.last().messages[0].parts[0] as UIMessagePart.Tool
        assertEquals(NAME_COLLISION_REASON, reasonOf(patched))
    }

    @Test
    fun `patched part preserves metadata approval state and input`() {
        val meta = kotlinx.serialization.json.buildJsonObject { put("stats", "keep-me") }
        val input = "{\"command\":\"ls\"}"
        val part = toolPart("workspace_shell", input, metadata = meta)
        val conversation = conversationWith(listOf(assistantWith(part)))

        val disabled = workspace(toolEnablement = """{"workspace_shell":false}""")
        val result = conversation.patchWorkspacePendingToolCalls(judge(disabled))

        assertEquals(1, result.patchedParts)
        val patched = result.conversation.messageNodes.last().messages[0].parts[0] as UIMessagePart.Tool
        assertEquals(meta, patched.metadata)
        assertEquals(ToolApprovalState.Pending, patched.approvalState)
        assertEquals(input, patched.input)
        assertEquals("call-1", patched.toolCallId)
    }

    @Test
    fun `generation-cause pause predicate distinguishes policy cancellation`() {
        // 政策取消（WorkspacePolicyCancellationException 及其包装链）不 pause 队列；
        // 用户停止（普通 CE）与真实错误仍 pause；正常完成为 null 不 pause。
        assertFalse(shouldPauseQueueOnGenerationFinished(null))
        assertTrue(shouldPauseQueueOnGenerationFinished(Exception("boom")))
        assertTrue(shouldPauseQueueOnGenerationFinished(kotlinx.coroutines.CancellationException("user stop")))
        assertFalse(
            shouldPauseQueueOnGenerationFinished(
                me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException("ws-1")
            )
        )
        assertFalse(
            shouldPauseQueueOnGenerationFinished(
                kotlinx.coroutines.CancellationException("outer").also {
                    it.initCause(
                        me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException("ws-1")
                    )
                }
            )
        )
    }
}
