package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * [GenerationLoop] 步/终态事件契约测试。Provider 侧用脚本化重放,
 * 观察真实循环 (消息合并、工具执行、重试、审批) 下发射的事件序列。
 */
class GenerationLoopEventsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 同一 [model] 实例贯穿 settings.providers 与 generateText, findProvider 按 id 匹配 */
    private inner class Harness(attempts: List<ScriptedAttempt>) {
        val provider = ScriptedProvider(attempts = ArrayDeque(attempts))
        val model: Model = testChatModel()
        val settings: Settings = testSettings(model)
        val loop: GenerationLoop = testGenerationLoop(provider, FakeContext(tempFolder.newFolder()))
    }

    private suspend fun Harness.generate(
        tools: List<me.rerere.ai.core.Tool> = emptyList(),
        maxSteps: Int = 256,
    ) = loop.generateText(
        settings = settings,
        model = model,
        messages = listOf(UIMessage.user("hi")),
        assistant = testAssistant(),
        tools = tools,
        maxSteps = maxSteps,
    ).toList()

    private fun List<GenerationChunk>.steps() = filterIsInstance<GenerationChunk.StepFinished>()
    private fun List<GenerationChunk>.end() = filterIsInstance<GenerationChunk.GenerationEnd>()
    private fun List<GenerationChunk>.transcript() =
        filterIsInstance<GenerationChunk.Messages>().last().messages

    @Test
    fun `merged single assistant still emits one StepFinished per provider step`() = runBlocking {
        val chunks = Harness(
            listOf(
                toolStepScript("call-1", "echo_tool", usage = TokenUsage(10, 5, totalTokens = 15)),
                textStepScript("done", usage = TokenUsage(20, 10, totalTokens = 30)),
            )
        ).generate(tools = listOf(echoTool()))

        // 真实形态: 多步合进同一条 assistant 消息, 不能用消息条数当步数
        assertEquals(
            1,
            chunks.transcript().count { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
        )

        val steps = chunks.steps()
        assertEquals(2, steps.size)
        assertEquals(0, steps[0].stepIndex)
        assertEquals(1, steps[0].stepNumber)
        assertEquals(1, steps[0].pendingToolCount)
        assertEquals("tool_calls", steps[0].finishReason)
        assertEquals(15, steps[0].usage?.totalTokens)
        assertEquals(1, steps[1].stepIndex)
        assertEquals(2, steps[1].stepNumber)
        assertEquals(0, steps[1].pendingToolCount)

        assertEquals(
            listOf(GenerationChunk.GenerationEnd.Reason.COMPLETED),
            chunks.end().map { it.reason }
        )
    }

    @Test
    fun `step usage merges split Usage chunks and totals accumulate across steps`() = runBlocking {
        val chunks = Harness(
            listOf(
                // 一步内两段 Usage: 0 值保留旧值, 正值覆盖 —— 与消息 usage 同一 merge 语义。
                // 本步必须带 tool call, 否则循环在第一步就自然结束 (三步脚本变一步)。
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "call-0", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "call-0", toolNameDelta = "", inputDelta = "{}"),
                        StreamChunk.ToolCallEnd(id = "call-0"),
                        StreamChunk.Usage(TokenUsage(100, 50, totalTokens = 150)),
                        StreamChunk.Usage(TokenUsage(0, 60, totalTokens = 0)),
                        StreamChunk.Finish(finishReason = "tool_calls"),
                    )
                ),
                toolStepScript("call-1", "echo_tool", usage = TokenUsage(30, 20, totalTokens = 50)),
                textStepScript("done", usage = TokenUsage(10, 5, totalTokens = 15)),
            )
        ).generate(tools = listOf(echoTool()))

        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(3, stats.stepCount)
        // step1: prompt100 / completion60 -> 160; step2: 50; step3: 15
        assertEquals(160, chunks.steps()[0].usage?.totalTokens)
        assertEquals(225L, stats.totalTokens)
    }

    @Test
    fun `steps without usage never reuse the previous step value`() = runBlocking {
        val chunks = Harness(
            listOf(
                toolStepScript("call-1", "echo_tool", usage = TokenUsage(10, 5, totalTokens = 15)),
                toolStepScript("call-2", "echo_tool", usage = null),
                textStepScript("done", usage = null),
            )
        ).generate(tools = listOf(echoTool()))

        val steps = chunks.steps()
        assertEquals(15, steps[0].usage?.totalTokens)
        // 缺 usage 的步是 null, 不是上一步的残留值
        assertNull(steps[1].usage)
        assertNull(steps[2].usage)

        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(15L, stats.totalTokens) // 只累加上报过的步
        assertTrue(stats.sawUsageReport)
    }

    @Test
    fun `no usage anywhere yields null total instead of zero`() = runBlocking {
        val chunks = Harness(listOf(textStepScript("plain", usage = null))).generate()
        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(1, stats.stepCount)
        assertNull(stats.totalTokens)
        assertNull(chunks.steps().single().usage)
    }

    @Test
    fun `failed attempt usage is discarded and retries do not count as steps`() = runBlocking {
        val harness = Harness(
            listOf(
                // 第一次 attempt 已经上报 usage 与半截文本, 然后网络中断
                ScriptedAttempt(
                    listOf(
                        StreamChunk.TextStart(id = "t"),
                        StreamChunk.TextDelta(id = "t", text = "half"),
                        StreamChunk.TextEnd(id = "t"),
                        StreamChunk.Usage(TokenUsage(999, 999, totalTokens = 1998)),
                    ),
                    errorAfter = IOException("connection reset"),
                ),
                // 重试成功: 只有成功 attempt 的统计被保留
                textStepScript("full reply", usage = TokenUsage(40, 20, totalTokens = 60)),
            )
        )
        val chunks = harness.generate()

        assertEquals(2, harness.provider.streamAttempts.size) // 两次网络尝试
        val steps = chunks.steps()
        assertEquals(1, steps.size) // 逻辑步仍是 1
        assertEquals(60, steps.single().usage?.totalTokens)
        assertEquals("full reply", chunks.transcript().last().toText())
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.end().single().reason
        )
    }

    @Test
    fun `text final step completes while exhausted tool loop reports step limit`() = runBlocking {
        // 自然结束
        val completed = Harness(listOf(textStepScript("final"))).generate()
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            completed.end().single().reason
        )

        // 每步都请求工具, maxSteps 耗尽 -> STEP_LIMIT (不是 COMPLETED)
        val limited = Harness(
            List(3) { i -> toolStepScript("call-$i", "echo_tool") }
        ).generate(tools = listOf(echoTool()), maxSteps = 3)
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.STEP_LIMIT,
            limited.end().single().reason
        )
        assertEquals(3, limited.steps().size)
        assertEquals(1, limited.steps().last().pendingToolCount)
    }

    @Test
    fun `tool awaiting approval ends with awaiting approval reason`() = runBlocking {
        val approvalTool = echoTool().copy(needsApproval = { true })
        val chunks = Harness(
            listOf(toolStepScript("call-1", "echo_tool"))
        ).generate(tools = listOf(approvalTool))
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL,
            chunks.end().single().reason
        )
        // 审批中的 tool part 转 Pending, 不丢失
        val tool = chunks.transcript().last().getTools().single()
        assertEquals(me.rerere.ai.ui.ToolApprovalState.Pending, tool.approvalState)
    }

    @Test
    fun `unknown tool error is fed back and the loop continues`() = runBlocking {
        val chunks = Harness(
            listOf(
                // 名字损坏的工具调用: 必须写回错误输出而不是终止循环
                toolStepScript("call-bad", "echo_toolecho_tool"),
                textStepScript("recovered"),
            )
        ).generate(tools = listOf(echoTool()))

        val tool = chunks.transcript().last().getTools().single()
        val output = tool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(output.contains("not found"))
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.end().single().reason
        )
        assertEquals(2, chunks.steps().size)
    }

    @Test
    fun `provider error propagates without end event`() = runBlocking {
        val harness = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = IllegalStateException("boom")))
        )
        val chunks = mutableListOf<GenerationChunk>()
        try {
            harness.loop.generateText(
                settings = harness.settings,
                model = harness.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
                tools = emptyList(),
            ).collect { chunks += it }
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertTrue(chunks.end().isEmpty())
        assertTrue(chunks.steps().isEmpty())
    }

    @Test
    fun `cancellation propagates without end event`() = runBlocking {
        val harness = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = CancellationException("stopped")))
        )
        val chunks = mutableListOf<GenerationChunk>()
        try {
            harness.loop.generateText(
                settings = harness.settings,
                model = harness.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
                tools = emptyList(),
            ).collect { chunks += it }
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
        assertTrue(chunks.end().isEmpty())
    }
}
