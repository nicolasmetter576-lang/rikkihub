package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.prompts.buildChunkFinalPrompt
import me.rerere.rikkihub.data.ai.prompts.buildChunkStagePrompt
import me.rerere.rikkihub.data.ai.tools.ChatToolSnapshot
import me.rerere.rikkihub.data.ai.tools.applyBuiltinOverrides
import me.rerere.rikkihub.data.ai.tools.local.PseudoIoNames
import me.rerere.rikkihub.data.ai.tools.local.buildPseudoIoTools
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

/**
 * 压缩摘要（移植上游 7cceda2 的 CompressionSummaryTest，另加请求前缀一致的用例）：
 * 摘要请求与正常对话用同一套组装，只在末尾多一条指令，才能命中提示词缓存。
 */
class CompressionSummaryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun reply(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `plain text reply is the summary`() {
        val summary = reply(UIMessagePart.Text("  [Summary of previous conversation]\n## User Goals\n...  "))
            .compressionSummaryOrNull()
        assertEquals("[Summary of previous conversation]\n## User Goals\n...", summary)
    }

    @Test
    fun `reply with a tool call is rejected even if it has text`() {
        val summary = reply(
            UIMessagePart.Text("Let me check the file first."),
            UIMessagePart.Tool(toolCallId = "call_1", toolName = "workspace_shell", input = "{}"),
        ).compressionSummaryOrNull()
        assertNull(summary)
    }

    @Test
    fun `inline think tags and reasoning parts are not part of the summary`() {
        assertEquals(
            "[Summary of previous conversation]",
            reply(UIMessagePart.Text("<think>planning</think>\n[Summary of previous conversation]")).compressionSummaryOrNull(),
        )
        assertEquals(
            "[Summary]",
            reply(UIMessagePart.Reasoning(reasoning = "planning"), UIMessagePart.Text("[Summary]")).compressionSummaryOrNull(),
        )
        assertEquals("", reply().compressionSummaryOrNull())
    }

    private fun textResult(message: UIMessage) = TextGenerationResult(
        id = "r", model = "scripted-chat", message = message, finishReason = "stop",
    )

    @Test
    fun `summary request is the chat request plus one instruction, without the output cap`() = runBlocking {
        val model = testChatModel()
        val settings = testSettings(model)
        val assistant = testAssistant().copy(promptPreset = AssistantPresets.withSystemPrompt("You are helpful."), maxTokens = 256)
        val history = listOf(UIMessage.user("hello"), UIMessage.assistant("hi there"))
        val tool = echoTool()

        // 正常对话的一步：记下发给 provider 的消息
        val chatProvider = ScriptedProvider(ArrayDeque(listOf(textStepScript("ok"))))
        testGenerationLoop(chatProvider, FakeContext(tempFolder.newFolder())).generateText(
            settings = settings, model = model, messages = history, assistant = assistant, tools = listOf(tool),
            conversationId = kotlin.uuid.Uuid.random(),
        ).toList()
        val chatRequest = chatProvider.streamMessages.single()

        val summaryProvider = ScriptedProvider(
            attempts = ArrayDeque(),
            results = ArrayDeque(listOf(textResult(UIMessage.assistant("[Summary]")))),
        )
        val conversationId = kotlin.uuid.Uuid.random()
        val result = testGenerationLoop(summaryProvider, FakeContext(tempFolder.newFolder())).generateCompressionSummary(
            settings = settings,
            model = model,
            messages = history,
            inputTransformers = emptyList(),
            assistant = assistant,
            memories = emptyList(),
            toolSnapshotProvider = { fixedToolsSnapshot(listOf(tool)) },
            compressPrompt = "SUMMARIZE",
            conversationSystemPrompt = null,
            turn = null,
            conversationId = conversationId,
        )
        assertEquals("[Summary]", result.summary)
        assertEquals(1, result.requestCount)
        assertFalse(result.chunked)
        val params = summaryProvider.generateParams.single()
        assertNull("the summary must not be cut off by the chat reply cap", params.maxTokens)
        assertEquals(listOf(tool.name), params.tools.map { it.name })
        assertEquals(conversationId.toString(), params.sessionId)
        val sent = summaryProvider.generateMessages.single()
        // 同样的系统提示词和历史，末尾多一条指令
        assertEquals(chatRequest.map { it.role to it.toText() }, sent.dropLast(1).map { it.role to it.toText() })
        assertEquals(MessageRole.USER to "SUMMARIZE", sent.last().role to sent.last().toText())
    }

    @Test
    fun `a summary request answered with a tool call fails instead of saving`() {
        val model = testChatModel()
        val provider = ScriptedProvider(
            attempts = ArrayDeque(),
            results = ArrayDeque(
                listOf(textResult(reply(UIMessagePart.Tool(toolCallId = "c", toolName = "echo_tool", input = "{}"))))
            ),
        )
        assertThrows(CompressionToolCallException::class.java) {
            runBlocking {
                testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateCompressionSummary(
                    settings = testSettings(model),
                    model = model,
                    messages = listOf(UIMessage.user("hello")),
                    inputTransformers = emptyList(),
                    assistant = testAssistant(),
                    memories = emptyList(),
                    toolSnapshotProvider = { fixedToolsSnapshot(listOf(echoTool())) },
                    compressPrompt = "SUMMARIZE",
                    conversationSystemPrompt = null,
                    turn = null,
                    conversationId = kotlin.uuid.Uuid.random(),
                )
            }
        }
    }

    /** 伪工具读写开着时历史全是 write 调用，模型照样用 write 交摘要（常常并行带一个 read）。 */
    @Test
    fun `with pseudo io a summary written through write is the summary`() {
        val model = testChatModel()
        val names = PseudoIoNames(read = "read", write = "write")
        val tools = buildPseudoIoTools().applyBuiltinOverrides(emptyMap())
        fun summarize(reply: UIMessage) = runBlocking {
            val provider = ScriptedProvider(attempts = ArrayDeque(), results = ArrayDeque(listOf(textResult(reply))))
            testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateCompressionSummary(
                settings = testSettings(model),
                model = model,
                messages = listOf(UIMessage.user("hello")),
                inputTransformers = emptyList(),
                assistant = testAssistant(),
                memories = emptyList(),
                toolSnapshotProvider = { ChatToolSnapshot(tools = tools, bindings = tools.associateBy { it.name }, pseudoIo = names) },
                compressPrompt = "SUMMARIZE",
                conversationSystemPrompt = null,
                turn = null,
                conversationId = kotlin.uuid.Uuid.random(),
            )
        }
        val write = UIMessagePart.Tool(toolCallId = "w", toolName = "write", input = """{"content":"[Summary]"}""")
        val read = UIMessagePart.Tool(toolCallId = "r", toolName = "read", input = "{}")
        assertEquals("[Summary]", summarize(reply(write)).summary)
        assertEquals("[Summary]", summarize(reply(write, read)).summary)
        // write 没带内容、或者调了别的工具，仍然算失败
        assertThrows(CompressionToolCallException::class.java) {
            summarize(reply(UIMessagePart.Tool(toolCallId = "w", toolName = "write", input = "{}")))
        }
        assertThrows(CompressionToolCallException::class.java) {
            summarize(reply(write, UIMessagePart.Tool(toolCallId = "e", toolName = "echo_tool", input = "{}")))
        }
    }

    @Test
    fun `requests after a checkpoint start from the summary`() = runBlocking {
        val model = testChatModel()
        val provider = ScriptedProvider(ArrayDeque(listOf(textStepScript("ok"))))
        val checkpoint = UIMessage.user("[Summary]").copy(isContextCheckpoint = true)
        testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateText(
            settings = testSettings(model),
            model = model,
            messages = listOf(UIMessage.user("old"), UIMessage.assistant("old reply"), checkpoint, UIMessage.user("new")),
            assistant = testAssistant(),
        ).toList()
        val sent = provider.streamMessages.single().filter { it.role != MessageRole.SYSTEM }
        assertEquals(listOf("[Summary]", "new"), sent.map { it.toText() })
        assertTrue(sent.first().isContextCheckpoint)
    }

    private class HandlerProvider(
        private val onGenerate: suspend (Int, List<UIMessage>, TextGenerationParams) -> TextGenerationResult,
    ) : Provider<ProviderSetting> {
        val prompts = mutableListOf<String>()
        val paramsList = mutableListOf<TextGenerationParams>()
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            prompts += messages.single().toText()
            paramsList += params
            return onGenerate(prompts.size, messages, params)
        }
        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = flow {}
        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("unused")
    }

    private fun standaloneLoop(
        provider: Provider<ProviderSetting>,
        dao: UsageTestDAO? = null,
        scope: CoroutineScope? = null,
    ): GenerationLoop {
        val fakeCtx = FakeContext(tempFolder.newFolder())
        val pm = ProviderManager(OkHttpClient(), fakeCtx).apply { registerProvider("openai", provider) }
        val recorder = if (dao != null && scope != null) UsageRecorder(dao, scope) else null
        return GenerationLoop(context = fakeCtx, providerManager = pm, json = JsonInstant, usageRecorder = recorder)
    }

    @Test
    fun `standalone chunked compression hands running summary across stages and records compression usage`() = runBlocking<Unit> {
        val model = testChatModel()
        val dao = UsageTestDAO()
        val usageScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = HandlerProvider { call, msgs, _ ->
            val prompt = msgs.single().toText()
            val text = if (prompt.startsWith("Write an updated running summary")) {
                "STAGE_$call"
            } else {
                "[Summary of previous conversation]\nFINAL"
            }
            TextGenerationResult(
                id = "r$call",
                model = "scripted-chat",
                message = UIMessage.assistant(text),
                finishReason = "stop",
                usage = TokenUsage(promptTokens = 100 * call, completionTokens = 20 * call, totalTokens = 120 * call),
            )
        }
        val progress = mutableListOf<Triple<Int, Int, Int>>()
        try {
            val materials = (1..6).map { "[Message #$it | User]\n" + "alpha beta gamma delta ".repeat(15) + "end$it" }
            val result = standaloneLoop(provider, dao, usageScope).generateStandaloneCompressionSummary(
                settings = testSettings(model),
                model = model,
                assistant = testAssistant(),
                conversationId = Uuid.random(),
                materials = materials,
                initialPreviousSummary = "",
                inputBudgetTokens = 360,
                summaryTokens = 80,
                maxRequests = 8,
                timeoutSeconds = null,
                renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
                renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Result", prev, seg, "", t, "English") },
                onProgress = { c, total, req -> progress += Triple(c, total, req) },
            )
            assertTrue(result.chunked)
            assertTrue("expected >=2 calls, got ${result.requestCount}", result.requestCount >= 2)
            assertTrue(result.summary.startsWith("[Summary of previous conversation]"))
            assertTrue("STAGE_1" in provider.prompts[1])
            assertTrue(progress.isNotEmpty())
            assertEquals(result.requestCount, dao.records.size)
            assertTrue(dao.records.all { it.source == UsageSource.COMPRESSION.name })
            assertEquals(provider.prompts.indices.sumOf { (it + 1) * 120 }, result.totalUsage?.totalTokens)
            assertTrue(provider.paramsList.all { it.tools.isEmpty() && it.maxTokens == null })
        } finally {
            usageScope.cancel()
        }
    }

    @Test
    fun `standalone compression shrinks budget on context length error and enforces maxRequests`() = runBlocking<Unit> {
        val model = testChatModel()
        var firstFailure = true
        val provider = HandlerProvider { call, _, _ ->
            if (firstFailure) {
                firstFailure = false
                throw IllegalStateException("This model's maximum context length is 200 tokens (context_length_exceeded)")
            }
            TextGenerationResult(
                id = "r$call",
                model = "scripted-chat",
                message = UIMessage.assistant("Summary step $call"),
                finishReason = "stop",
                usage = TokenUsage(promptTokens = 50, completionTokens = 10, totalTokens = 60),
            )
        }
        val materials = (1..4).map { "[Message #$it | User]\n" + "alpha beta gamma delta ".repeat(40) + "m$it" }
        val shrunkResult = standaloneLoop(provider).generateStandaloneCompressionSummary(
            settings = testSettings(model),
            model = model,
            assistant = testAssistant(),
            conversationId = Uuid.random(),
            materials = materials,
            initialPreviousSummary = "",
            inputBudgetTokens = 900,
            summaryTokens = 80,
            maxRequests = 8,
            timeoutSeconds = null,
            renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
            renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Result", prev, seg, "", t, "English") },
        )
        assertTrue(shrunkResult.chunked)
        assertTrue(shrunkResult.requestCount >= 2)

        assertThrows(CompressionRequestLimitException::class.java) {
            runBlocking {
                val failProvider = HandlerProvider { call, _, _ ->
                    TextGenerationResult(id = "r$call", model = "scripted-chat", message = UIMessage.assistant("S$call"))
                }
                standaloneLoop(failProvider).generateStandaloneCompressionSummary(
                    settings = testSettings(model),
                    model = model,
                    assistant = testAssistant(),
                    conversationId = Uuid.random(),
                    materials = materials,
                    initialPreviousSummary = "",
                    inputBudgetTokens = 400,
                    summaryTokens = 80,
                    maxRequests = 1,
                    timeoutSeconds = null,
                    renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
                    renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Result", prev, seg, "", t, "English") },
                )
            }
        }
    }

    @Test
    fun `request timeout throws CompressionTimeoutException while external cancellation stays CancellationException`() {
        val model = testChatModel()
        val slowProvider = HandlerProvider { _, _, _ ->
            delay(2_500)
            TextGenerationResult(id = "late", model = "scripted-chat", message = UIMessage.assistant("late"))
        }
        assertThrows(CompressionTimeoutException::class.java) {
            runBlocking {
                standaloneLoop(slowProvider).generateStandaloneCompressionSummary(
                    settings = testSettings(model),
                    model = model,
                    assistant = testAssistant(),
                    conversationId = Uuid.random(),
                    materials = listOf("[Message #1 | User]\nhi"),
                    initialPreviousSummary = "",
                    inputBudgetTokens = 1_000,
                    summaryTokens = 100,
                    maxRequests = 4,
                    timeoutSeconds = 1,
                    renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
                    renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Result", prev, seg, "", t, "English") },
                )
            }
        }

        // External coroutine cancellation must propagate CancellationException, never misclassify as timeout
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val waitingProvider = HandlerProvider { _, _, _ ->
                entered.complete(Unit)
                delay(10_000)
                TextGenerationResult(id = "never", model = "scripted-chat", message = UIMessage.assistant("never"))
            }
            var caught: Throwable? = null
            val job = launch {
                try {
                    standaloneLoop(waitingProvider).generateStandaloneCompressionSummary(
                        settings = testSettings(model),
                        model = model,
                        assistant = testAssistant(),
                        conversationId = Uuid.random(),
                        materials = listOf("[Message #1 | User]\nhi"),
                        initialPreviousSummary = "",
                        inputBudgetTokens = 1_000,
                        summaryTokens = 100,
                        maxRequests = 4,
                        timeoutSeconds = 30,
                        renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
                        renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Result", prev, seg, "", t, "English") },
                    )
                } catch (t: Throwable) {
                    caught = t
                }
            }
            entered.await()
            job.cancel()
            job.join()
            assertTrue("expected CancellationException, got $caught", caught is CancellationException)
        }
    }
}
