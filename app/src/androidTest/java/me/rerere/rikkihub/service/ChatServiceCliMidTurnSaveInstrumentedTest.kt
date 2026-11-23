package me.rerere.rikkihub.service

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.cli.AcpParts
import me.rerere.rikkihub.data.cli.AcpSessionManager
import me.rerere.rikkihub.data.cli.CliPermissionMode
import me.rerere.rikkihub.data.cli.ConversationCliSession
import me.rerere.rikkihub.data.cli.CustomCliAgentConfig
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A CLI turn saves a tool card as soon as it finishes, not only when the turn ends: a stub ACP
 * agent completes one tool call and then keeps the turn open, and the card must already be in
 * the database (what a killed process would come back to). Needs a real rootfs (skipped with `-e rootfs false`).
 */
@RunWith(AndroidJUnit4::class)
class ChatServiceCliMidTurnSaveInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val koin get() = GlobalContext.get()

    @Test(timeout = 20 * 60_000)
    fun finishedCliToolIsSavedWhileTheTurnIsStillRunning() = finishedToolIsSaved(busyMainThread = false)

    /** The turn runs on the main thread; a busy one lets the tool finish before anything there resumes. */
    @Test(timeout = 20 * 60_000)
    fun cliToolFinishedBeforeTheMainThreadResumesIsSaved() = finishedToolIsSaved(busyMainThread = true)

    @Test(timeout = 20 * 60_000)
    fun staleCliToolsAndOpenReasoningRecoverOnColdLoadAndUnblockNextTurn() = runBlocking {
        assumeTrue(args.getString("rootfs") != "false")
        val workspaces = koin.get<WorkspaceRepository>()
        val settingsStore = koin.get<SettingsStore>()
        val chat = koin.get<ChatService>()
        val conversations = koin.get<ConversationRepository>()
        val workspace = workspaces.create("itest-cli-stale-${System.currentTimeMillis()}")
        val assistant = Assistant(
            name = "itest cli stale",
            workspaceId = Uuid.parse(workspace.id),
            defaultCliAgentId = AGENT_ID,
            defaultCliPermissionMode = CliPermissionMode.AUTO,
        )
        val conversationId = Uuid.random()
        var previousAssistantId: Uuid? = null
        try {
            assertTrue(workspaces.installRootfs(workspace.id, defaultRootfsUrl()))
            val files = File(context.filesDir.canonicalFile, "workspaces/${workspace.root}/files")
            File(files, "stub-acp.sh").writeText(STUB_AGENT)
            settingsStore.update {
                previousAssistantId = it.assistantId
                it.copy(
                    assistants = it.assistants + assistant,
                    assistantId = assistant.id,
                    customCliAgents = it.customCliAgents + CustomCliAgentConfig(
                        id = AGENT_ID,
                        name = "stub",
                        executable = "bash",
                        args = listOf("/workspace/stub-acp.sh"),
                    ),
                )
            }

            // Seed a conversation in the exact state left by a mid-turn save followed by SIGKILL:
            // completed t1, open reasoning, in-progress Auto t2, dead Pending t3, and ask_question t4.
            val killedAt = Clock.System.now() - 3.hours
            val doneTool = UIMessagePart.Tool(
                toolCallId = "t1",
                toolName = "acp_run_command",
                input = "{}",
                output = listOf(UIMessagePart.Text("STUB-TOOL-DONE")),
                startedAt = killedAt - 5.seconds,
                finishedAt = killedAt,
                metadata = buildJsonObject {
                    putJsonObject(AcpParts.META_KEY) {
                        put("title", "finished tool")
                        put("kind", "execute")
                        put("status", "completed")
                    }
                },
            )
            val openReasoning = UIMessagePart.Reasoning(
                reasoning = "halfway thought",
                createdAt = killedAt - 3.seconds,
                finishedAt = null,
            )
            val autoRunning = UIMessagePart.Tool(
                toolCallId = "t2",
                toolName = "acp_run_command",
                input = "{}",
                output = emptyList(),
                approvalState = ToolApprovalState.Auto,
                startedAt = killedAt - 2.seconds,
                finishedAt = null,
                metadata = buildJsonObject {
                    putJsonObject(AcpParts.META_KEY) {
                        put("title", "interrupted auto tool")
                        put("kind", "execute")
                        put("status", "in_progress")
                    }
                },
            )
            val deadPending = UIMessagePart.Tool(
                toolCallId = "t3",
                toolName = "acp_run_command",
                input = "{}",
                output = emptyList(),
                approvalState = ToolApprovalState.Pending,
                startedAt = killedAt - 1.seconds,
                finishedAt = null,
                metadata = buildJsonObject {
                    putJsonObject(AcpParts.META_KEY) {
                        put("title", "dead pending tool")
                        put("kind", "execute")
                        put("status", "pending")
                    }
                },
            )
            val askQuestion = UIMessagePart.Tool(
                toolCallId = "t4",
                toolName = AcpParts.ASK_QUESTION,
                input = "{}",
                output = emptyList(),
                approvalState = ToolApprovalState.Auto,
                metadata = buildJsonObject {
                    putJsonObject(AcpParts.META_KEY) {
                        put("title", "Pick one")
                        put("kind", "other")
                        put("status", "pending")
                    }
                },
            )
            val killedReply = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(doneTool, openReasoning, autoRunning, deadPending, askQuestion),
                finishedAt = null,
            )
            val seeded = Conversation.ofId(conversationId).copy(
                assistantId = assistant.id,
                cliSession = ConversationCliSession(
                    agentId = AGENT_ID,
                    permissionMode = CliPermissionMode.AUTO,
                    acpSessionId = "dead-session",
                ),
                messageNodes = listOf(
                    UIMessage.user("turn 1").toMessageNode(),
                    killedReply.toMessageNode(),
                ),
            )
            conversations.insertConversation(seeded)

            // Cold load must recover stale CLI tools + open reasoning automatically and persist them.
            chat.initializeConversation(conversationId)
            val interruptedText = context.getString(R.string.cli_tool_interrupted)
            withTimeout(10_000) {
                while (true) {
                    val stored = conversations.getConversationById(conversationId)
                    val reply = stored?.currentMessages?.lastOrNull()
                    val t3 = reply?.parts?.filterIsInstance<UIMessagePart.Tool>()?.firstOrNull { it.toolCallId == "t3" }
                    val r = reply?.parts?.filterIsInstance<UIMessagePart.Reasoning>()?.singleOrNull()
                    if (t3?.isExecuted == true && r?.finishedAt != null) break
                    delay(100)
                }
            }

            val recovered = conversations.getConversationById(conversationId)!!.currentMessages.last()
            val recoveredReasoning = recovered.parts[1] as UIMessagePart.Reasoning
            assertNotNull(recoveredReasoning.finishedAt)
            assertTrue(
                "reasoning should stay bounded to killedAt, got ${recoveredReasoning.finishedAt}",
                recoveredReasoning.finishedAt!! <= killedAt,
            )
            val recoveredAuto = recovered.parts[2] as UIMessagePart.Tool
            assertEquals(ToolApprovalState.Auto, recoveredAuto.approvalState)
            assertEquals(listOf(UIMessagePart.Text(interruptedText)), recoveredAuto.output)
            assertEquals("interrupted", AcpParts.status(recoveredAuto))
            assertNull(recoveredAuto.finishedAt)

            val recoveredPending = recovered.parts[3] as UIMessagePart.Tool
            assertFalse(recoveredPending.isPending)
            assertEquals(ToolApprovalState.Pending, recoveredPending.approvalState)
            assertEquals(listOf(UIMessagePart.Text(interruptedText)), recoveredPending.output)
            assertEquals("interrupted", AcpParts.status(recoveredPending))
            assertNull(recoveredPending.finishedAt)

            val recoveredQuestion = recovered.parts[4] as UIMessagePart.Tool
            assertTrue(recoveredQuestion.isExecuted)
            assertEquals(listOf(UIMessagePart.Text("")), recoveredQuestion.output)
            assertEquals("pending", AcpParts.status(recoveredQuestion))

            // Sending the next message must NOT stay stuck on the dead Pending card and must finish cleanly.
            chat.sendMessage(conversationId, listOf(UIMessagePart.Text("finish-turn-2")))
            withTimeout(60_000) {
                chat.getConversationFlow(conversationId).first { conv ->
                    conv.currentMessages.lastOrNull()?.role == MessageRole.ASSISTANT &&
                        conv.currentMessages.last().toText().contains("TURN-2-OK")
                }
            }
            // Earlier recovered turn must not have been overwritten with HTTP cancel JSON.
            val turn1Reply = chat.getConversationFlow(conversationId).value.currentMessages[1]
            assertEquals(listOf(UIMessagePart.Text(interruptedText)), (turn1Reply.parts[2] as UIMessagePart.Tool).output)
        } finally {
            withContext(NonCancellable) {
                runCatching { chat.stopGeneration(conversationId) }
                runCatching { koin.get<AcpSessionManager>().closeConversation(conversationId) }
                conversations.getConversationById(conversationId)?.let { runCatching { conversations.deleteConversation(it) } }
                settingsStore.update { settings ->
                    settings.copy(
                        assistants = settings.assistants.filter { it.id != assistant.id },
                        assistantId = previousAssistantId ?: settings.assistantId,
                        customCliAgents = settings.customCliAgents.filter { it.id != AGENT_ID },
                    )
                }
                workspaces.delete(workspace.id)
            }
        }
    }

    private fun finishedToolIsSaved(busyMainThread: Boolean) = runBlocking {
        assumeTrue(args.getString("rootfs") != "false")
        val workspaces = koin.get<WorkspaceRepository>()
        val settingsStore = koin.get<SettingsStore>()
        val chat = koin.get<ChatService>()
        val conversations = koin.get<ConversationRepository>()
        val workspace = workspaces.create("itest-cli-${System.currentTimeMillis()}")
        val assistant = Assistant(
            name = "itest cli",
            workspaceId = Uuid.parse(workspace.id),
            defaultCliAgentId = AGENT_ID,
            defaultCliPermissionMode = CliPermissionMode.AUTO,
        )
        val conversationId = Uuid.random()
        var previousAssistantId: Uuid? = null
        try {
            assertTrue(workspaces.installRootfs(workspace.id, defaultRootfsUrl()))
            val files = File(context.filesDir.canonicalFile, "workspaces/${workspace.root}/files")
            File(files, "stub-acp.sh").writeText(STUB_AGENT)
            val hanging = File(files, "stub-hanging")
            settingsStore.update {
                previousAssistantId = it.assistantId
                it.copy(
                    assistants = it.assistants + assistant,
                    assistantId = assistant.id,
                    customCliAgents = it.customCliAgents + CustomCliAgentConfig(
                        id = AGENT_ID,
                        name = "stub",
                        executable = "bash",
                        args = listOf("/workspace/stub-acp.sh"),
                    ),
                )
            }
            chat.initializeConversation(conversationId)
            assertEquals(AGENT_ID, chat.getConversationFlow(conversationId).value.cliSession?.agentId)

            val backlog = if (busyMainThread) launch(Dispatchers.Default) { keepMainThreadBusy() } else null
            try {
                chat.sendMessage(conversationId, listOf(UIMessagePart.Text("run the tool")))
                withTimeout(300_000) { while (!hanging.exists()) delay(100) }
            } finally {
                backlog?.cancel()
            }
            withTimeout(10_000) { chat.getConversationFlow(conversationId).first { it.hasFinishedStubTool() } }

            val saved = withTimeoutOrNull(5_000) {
                while (conversations.getConversationById(conversationId)?.hasFinishedStubTool() != true) delay(100)
                true
            }
            val stored = conversations.getConversationById(conversationId)?.currentMessages
                ?.map { message -> message.role.name + ":" + message.parts.map { it::class.simpleName } }
            assertNotNull("finished tool card is only in memory; database has $stored", saved)
        } finally {
            withContext(NonCancellable) {
                runCatching { chat.stopGeneration(conversationId) }
                runCatching { koin.get<AcpSessionManager>().closeConversation(conversationId) }
                conversations.getConversationById(conversationId)?.let { runCatching { conversations.deleteConversation(it) } }
                settingsStore.update { settings ->
                    settings.copy(
                        assistants = settings.assistants.filter { it.id != assistant.id },
                        assistantId = previousAssistantId ?: settings.assistantId,
                        customCliAgents = settings.customCliAgents.filter { it.id != AGENT_ID },
                    )
                }
                workspaces.delete(workspace.id)
            }
        }
    }

    /** Keeps about a second of work queued on the main thread, so whatever is posted there waits for it. */
    private suspend fun keepMainThreadBusy() {
        val main = Handler(Looper.getMainLooper())
        val queued = AtomicInteger()
        while (true) {
            while (queued.get() < 20) {
                queued.incrementAndGet()
                main.post {
                    Thread.sleep(50)
                    queued.decrementAndGet()
                }
            }
            delay(10)
        }
    }

    private fun Conversation.hasFinishedStubTool() = currentMessages.any { message ->
        message.parts.any { part ->
            part is UIMessagePart.Tool && part.toolCallId == "t1" &&
                part.output.any { it is UIMessagePart.Text && it.text == "STUB-TOOL-DONE" }
        }
    }

    private companion object {
        const val AGENT_ID = "itest-stub-acp"

        /** One tool call that completes, a text chunk, then the turn stays open until session/cancel. */
        val STUB_AGENT = """
            |#!/bin/bash
            |upd() { echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"stub-1","update":'"${'$'}1"'}}'; }
            |while IFS= read -r line; do
            |  id=; [[ ${'$'}line =~ \"id\":([0-9]+) ]] && id=${'$'}{BASH_REMATCH[1]}
            |  case ${'$'}line in
            |    *'"method":"initialize"'*) echo '{"jsonrpc":"2.0","id":'${'$'}id',"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[]}}' ;;
            |    *'"method":"session/new"'*) echo '{"jsonrpc":"2.0","id":'${'$'}id',"result":{"sessionId":"stub-1"}}' ;;
            |    *'"method":"session/prompt"'*)
            |      prompt=${'$'}id
            |      case ${'$'}line in
            |        *'finish-turn-2'*)
            |          upd '{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"TURN-2-OK"}}'
            |          echo '{"jsonrpc":"2.0","id":'${'$'}id',"result":{"stopReason":"end_turn"}}' ;;
            |        *)
            |          upd '{"sessionUpdate":"tool_call","toolCallId":"t1","title":"stub tool","kind":"execute","status":"in_progress"}'
            |          upd '{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"STUB-TOOL-DONE"}}]}'
            |          upd '{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"still working"}}'
            |          touch /workspace/stub-hanging ;;
            |      esac ;;
            |    *'"method":"session/cancel"'*) echo '{"jsonrpc":"2.0","id":'${'$'}prompt',"result":{"stopReason":"cancelled"}}' ;;
            |    *) [ -n "${'$'}id" ] && echo '{"jsonrpc":"2.0","id":'${'$'}id',"result":{}}' ;;
            |  esac
            |done
        """.trimMargin() + "\n"
    }
}
