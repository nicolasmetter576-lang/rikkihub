package me.rerere.rikkihub.ui.floating

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.cli.AcpParts
import me.rerere.rikkihub.data.cli.CliAgent
import me.rerere.rikkihub.data.cli.ConversationCliSession
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.GenerationPhase
import me.rerere.rikkihub.data.model.generationPhase
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.service.ChatFloatingTracker.Action
import me.rerere.rikkihub.service.ChatFloatingTracker.TrackingState
import me.rerere.rikkihub.service.MessageQueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FloatingChatCliStateTest {
    private fun acpTool(
        toolName: String = AcpParts.TOOL_PREFIX + "run_command",
        title: String? = "Run tests",
        approvalState: ToolApprovalState = ToolApprovalState.Pending,
        executed: Boolean = false,
    ) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = toolName,
        input = "{\"cmd\":\"./gradlew test\"}",
        output = if (executed) listOf(UIMessagePart.Text("ok")) else emptyList(),
        approvalState = approvalState,
        metadata = buildJsonObject {
            put(
                AcpParts.META_KEY,
                buildJsonObject {
                    if (title != null) put("title", title)
                    put("status", if (executed) "completed" else "pending")
                },
            )
        },
    )

    @Test
    fun `buildFloatingChatUiState sets isCliSession and sendBlockedReason`() {
        val ordinary = Conversation.ofId(Uuid.random())
        val cli = ordinary.copy(cliSession = ConversationCliSession(agentId = CliAgent.AGY))

        val ordinaryState = buildFloatingChatUiState(
            conversation = ordinary,
            replyMessageId = null,
            generating = false,
            compressing = false,
            processingStatus = null,
            queue = MessageQueueState(),
            error = null,
            sendBlockedReason = "no-model",
        )
        assertFalse(ordinaryState.isCliSession)
        assertEquals("no-model", ordinaryState.sendBlockedReason)

        val cliState = buildFloatingChatUiState(
            conversation = cli,
            replyMessageId = null,
            generating = true,
            compressing = false,
            processingStatus = null,
            queue = MessageQueueState(),
            error = null,
            sendBlockedReason = null,
        )
        assertTrue(cliState.isCliSession)
        assertNull(cliState.sendBlockedReason)
    }

    @Test
    fun `resolveSendBlockedReason checks model for ordinary chat and workspace and agent for CLI chat`() {
        val model = Model(modelId = "gpt-4o")
        val assistantId = Uuid.random()
        val workspaceId = Uuid.random()

        val ordinary = Conversation.ofId(Uuid.random()).copy(assistantId = assistantId)
        val noModelSettings = Settings(
            assistants = listOf(Assistant(id = assistantId, chatModelId = null)),
        )
        assertEquals(
            "no-model",
            resolveSendBlockedReason(ordinary, noModelSettings, "no-model", "no-workspace", "no-agent"),
        )

        val validOrdinarySettings = Settings(
            assistants = listOf(Assistant(id = assistantId, chatModelId = model.id)),
            providers = listOf(ProviderSetting.OpenAI(apiKey = "k", models = listOf(model))),
        )
        assertNull(
            resolveSendBlockedReason(ordinary, validOrdinarySettings, "no-model", "no-workspace", "no-agent"),
        )

        val cliMissingWorkspace = ordinary.copy(cliSession = ConversationCliSession(agentId = CliAgent.AGY))
        val noWorkspaceSettings = Settings(
            assistants = listOf(Assistant(id = assistantId, workspaceId = null)),
        )
        assertEquals(
            "no-workspace",
            resolveSendBlockedReason(cliMissingWorkspace, noWorkspaceSettings, "no-model", "no-workspace", "no-agent"),
        )

        val cliMissingAgent = ordinary.copy(cliSession = ConversationCliSession(agentId = "missing-agent"))
        val validWorkspaceSettings = Settings(
            assistants = listOf(Assistant(id = assistantId, workspaceId = workspaceId)),
        )
        assertEquals(
            "no-agent",
            resolveSendBlockedReason(cliMissingAgent, validWorkspaceSettings, "no-model", "no-workspace", "no-agent"),
        )

        val validCli = ordinary.copy(cliSession = ConversationCliSession(agentId = CliAgent.AGY))
        assertNull(
            resolveSendBlockedReason(validCli, validWorkspaceSettings, "no-model", "no-workspace", "no-agent"),
        )
    }

    @Test
    fun `AcpParts ASK_QUESTION is treated as ask-user tool and reported in pendingTools`() {
        val askTool = acpTool(toolName = AcpParts.ASK_QUESTION, title = "Pick an option")
        val regularAcpTool = acpTool(toolName = AcpParts.TOOL_PREFIX + "edit", title = "Edit main.kt")
        val builtinAsk = UIMessagePart.Tool(
            toolCallId = "call-2",
            toolName = BuiltinToolIds.ASK_USER,
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )

        assertTrue(askTool.isAskUserTool())
        assertTrue(builtinAsk.isAskUserTool())
        assertFalse(regularAcpTool.isAskUserTool())

        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(askTool))
        val conversation = Conversation.ofId(Uuid.random()).copy(
            cliSession = ConversationCliSession(agentId = CliAgent.AGY),
            messageNodes = listOf(reply.toMessageNode()),
        )
        val state = buildFloatingChatUiState(
            conversation = conversation,
            replyMessageId = reply.id,
            generating = true,
            compressing = false,
            processingStatus = null,
            queue = MessageQueueState(),
            error = null,
        )
        assertEquals(1, state.pendingTools.size)
        assertTrue(state.pendingTools.any { it.isAskUserTool() })
    }

    @Test
    fun `generationPhase uses AcpParts titleOf for acp tools`() {
        val tool = acpTool(toolName = AcpParts.TOOL_PREFIX + "run_command", title = "Run unit tests")
        val phase = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)).generationPhase()
        assertEquals(GenerationPhase.RunningTool(toolName = "Run unit tests", input = tool.input), phase)
    }

    @Test
    fun `tracker does not preempt target when keepTarget is true`() {
        val cliId = Uuid.random()
        val ordinaryId = Uuid.random()
        val state = TrackingState()
            .reduce(Action.JobsChanged(setOf(cliId)))
            .reduce(Action.JobsChanged(setOf(cliId, ordinaryId), keepTarget = true))
        assertEquals(cliId, state.target)
    }
}
