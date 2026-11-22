package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageUpdate
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * 多步工具调用共用一条助手消息时的统计：usage 各步相加、lastUsage 是最后一次请求、
 * 记录步数与生成耗时（移植自上游 e7f2c04 / 660b5d6 的 AgentLoopTest 用例）。
 */
class GenerationLoopUsageStatsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun run(provider: ScriptedProvider, stream: Boolean): UIMessage = runBlocking {
        val model = testChatModel()
        testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).generateText(
            settings = testSettings(model),
            model = model,
            messages = listOf(UIMessage.user("go")),
            assistant = testAssistant(streamOutput = stream),
            tools = listOf(echoTool()),
        ).toList().filterIsInstance<GenerationChunk.Messages>().last().messages.last()
    }

    @Test
    fun `streaming steps add up usage and keep the last request separately`() {
        val provider = ScriptedProvider(
            ArrayDeque(
                listOf(
                    toolStepScript("c1", "echo_tool", usage = TokenUsage(promptTokens = 100, completionTokens = 10)),
                    textStepScript("done", usage = TokenUsage(promptTokens = 150, completionTokens = 20, cachedTokens = 90)),
                )
            )
        )
        val message = run(provider, stream = true)
        assertEquals(250, message.usage?.promptTokens)
        assertEquals(30, message.usage?.completionTokens)
        assertEquals(90, message.usage?.cachedTokens)
        assertEquals(TokenUsage(promptTokens = 150, completionTokens = 20, cachedTokens = 90, totalTokens = 170), message.lastUsage)
        assertEquals(2, message.stepCount)
        assertEquals(170, message.contextTokens())
        assertNotNull(message.generationTimeMs)
        assertEquals(true, message.usageComplete)
    }

    @Test
    fun `non streaming steps add up usage the same way`() {
        fun result(parts: List<UIMessagePart>, usage: TokenUsage, finish: String) = TextGenerationResult(
            id = "r",
            model = "scripted-chat",
            message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
            finishReason = finish,
            usage = usage.copy(totalTokens = usage.promptTokens + usage.completionTokens),
            hasCompleteUsage = true,
        )
        val provider = ScriptedProvider(
            attempts = ArrayDeque(),
            results = ArrayDeque(
                listOf(
                    result(
                        listOf(UIMessagePart.Tool(toolCallId = "c1", toolName = "echo_tool", input = "{}")),
                        TokenUsage(promptTokens = 20, completionTokens = 2),
                        "tool_calls",
                    ),
                    result(listOf(UIMessagePart.Text("answer")), TokenUsage(promptTokens = 30, completionTokens = 3), "stop"),
                )
            ),
        )
        val message = run(provider, stream = false)
        assertEquals(TokenUsage(promptTokens = 50, completionTokens = 5, totalTokens = 0), message.usage?.copy(totalTokens = 0))
        assertEquals(TokenUsage(promptTokens = 30, completionTokens = 3, totalTokens = 33), message.lastUsage)
        assertEquals(2, message.stepCount)
        assertTrue((message.generationTimeMs ?: -1) >= 0)
        assertEquals(true, message.usageComplete)
    }

    @Test
    fun `a successful missing-usage step marks partial totals and clears last snapshot`() {
        val message = run(ScriptedProvider(ArrayDeque(listOf(
            toolStepScript("c", "echo_tool", usage = TokenUsage(100, 50)),
            textStepScript("unmetered answer"),
        ))), stream = true)
        assertEquals(50, message.usage?.completionTokens)
        assertEquals(2, message.stepCount)
        assertEquals(false, message.usageComplete)
        assertNull(message.lastUsage)
        assertNull(message.contextTokens())
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test
    fun `flow completion publishes final elapsed time not the last chunk preview`() = runBlocking {
        val clock = TestTimeSource()
        val delegate = ScriptedProvider(ArrayDeque())
        val provider = object : Provider<ProviderSetting> by delegate {
            override suspend fun streamText(
                providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
            ): Flow<StreamChunk> = flow {
                emit(StreamChunk.TextStart("t"))
                clock += 100.milliseconds
                emit(StreamChunk.TextDelta("t", "answer"))
                emit(StreamChunk.Usage(UsageUpdate(100, 50, totalTokens = 150)))
                emit(StreamChunk.Finish("stop"))
                clock += 500.milliseconds
            }
        }
        val model = testChatModel()
        val loop = testGenerationLoop(provider, FakeContext(tempFolder.newFolder())).apply {
            generationTimeSource = clock
        }
        val events = loop.generateText(
            settings = testSettings(model), model = model,
            messages = listOf(UIMessage.user("go")), assistant = testAssistant(),
        ).toList()
        val published = events.filterIsInstance<GenerationChunk.Messages>().map { it.messages.last() }
        val final = published.last()
        assertEquals(600L, final.generationTimeMs)
        assertEquals(true, final.usageComplete)
        assertEquals(1, final.stepCount)
        assertTrue(published.dropLast(1).all { it.usageComplete == false })
        assertTrue(published.dropLast(1).any { it.generationTimeMs == 100L })
        val step = events.filterIsInstance<GenerationChunk.StepFinished>().single()
        assertEquals(final.usage, step.usage)
        assertEquals(final, step.stepMessages?.last())
    }
}
