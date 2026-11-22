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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.cli.AcpSessionManager
import me.rerere.rikkihub.data.cli.CliPermissionMode
import me.rerere.rikkihub.data.cli.CustomCliAgentConfig
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
            |      upd '{"sessionUpdate":"tool_call","toolCallId":"t1","title":"stub tool","kind":"execute","status":"in_progress"}'
            |      upd '{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"STUB-TOOL-DONE"}}]}'
            |      upd '{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"still working"}}'
            |      touch /workspace/stub-hanging ;;
            |    *'"method":"session/cancel"'*) echo '{"jsonrpc":"2.0","id":'${'$'}prompt',"result":{"stopReason":"cancelled"}}' ;;
            |    *) [ -n "${'$'}id" ] && echo '{"jsonrpc":"2.0","id":'${'$'}id',"result":{}}' ;;
            |  esac
            |done
        """.trimMargin() + "\n"
    }
}
