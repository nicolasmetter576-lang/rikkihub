package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
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
import java.io.File
import java.io.IOException

/**
 * [GenerationLoop] 真实链路补充覆盖 (离线 ScriptedProvider):
 * 非流式步、maxSteps=24 边界、未知工具回灌后的真实写文件、
 * 重试失败 attempt 的步数/usage/part 回滚、usage 分片与缺失、取消保态。
 * 全部经真实循环 + 真实工具执行 + 真实文件副作用断言, 不走 mock。
 */
class GenerationLoopRealChainTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private inner class StreamHarness(attempts: List<ScriptedAttempt>) {
        val provider = ScriptedProvider(attempts = ArrayDeque(attempts))
        val model: Model = testChatModel()
        val settings: Settings = testSettings(model)
        val loop: GenerationLoop = testGenerationLoop(provider, FakeContext(tempFolder.newFolder()))
    }

    private inner class ResultHarness(results: List<TextGenerationResult>) {
        val provider = ScriptedProvider(attempts = ArrayDeque(), results = ArrayDeque(results))
        val model: Model = testChatModel()
        val settings: Settings = testSettings(model)
        val loop: GenerationLoop = testGenerationLoop(provider, FakeContext(tempFolder.newFolder()))
    }

    private suspend fun GenerationLoop.generate(
        settings: Settings,
        model: Model,
        tools: List<Tool>,
        maxSteps: Int,
        streamOutput: Boolean = true,
    ) = generateText(
        settings = settings,
        model = model,
        messages = listOf(UIMessage.user("hi")),
        assistant = testAssistant(streamOutput = streamOutput),
        tools = tools,
        maxSteps = maxSteps,
    ).toList()

    private fun List<GenerationChunk>.steps() = filterIsInstance<GenerationChunk.StepFinished>()
    private fun List<GenerationChunk>.end() = filterIsInstance<GenerationChunk.GenerationEnd>()
    private fun List<GenerationChunk>.transcript() =
        filterIsInstance<GenerationChunk.Messages>().last().messages

    private fun toolCallResult(
        toolCallId: String,
        toolName: String,
        inputJson: String,
        usage: TokenUsage? = null,
        finishReason: String = "tool_calls",
    ) = TextGenerationResult(
        id = "r-$toolCallId",
        model = "scripted-chat",
        message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = toolCallId, toolName = toolName, input = inputJson)
            ),
        ),
        finishReason = finishReason,
        usage = usage,
    )

    private fun textResult(
        text: String,
        usage: TokenUsage? = null,
        finishReason: String = "stop",
    ) = TextGenerationResult(
        id = "r-text",
        model = "scripted-chat",
        message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
        finishReason = finishReason,
        usage = usage,
    )

    // ---------------------------------------------------------------
    // 非流式 step + usage 两轮
    // ---------------------------------------------------------------
    @Test
    fun `non streaming two steps accumulate usage and finish completed`() = runBlocking {
        val harness = ResultHarness(
            listOf(
                toolCallResult("ns-1", "echo_tool", """{"a":1}""", TokenUsage(10, 5, totalTokens = 15)),
                textResult("done", TokenUsage(20, 10, totalTokens = 30)),
            )
        )
        val chunks = harness.loop.generate(
            settings = harness.settings, model = harness.model,
            tools = listOf(echoTool()), maxSteps = 10, streamOutput = false,
        )
        assertEquals(2, harness.provider.generateCalls) // 非流式走 generateText, 不走 streamText
        val steps = chunks.steps()
        assertEquals(2, steps.size)
        assertEquals(15, steps[0].usage?.totalTokens)
        assertEquals(30, steps[1].usage?.totalTokens)
        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(45L, stats.totalTokens)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
        // 工具确实执行过
        val tool = chunks.transcript().last().getTools().single()
        assertTrue(tool.isExecuted)
    }

    // ---------------------------------------------------------------
    // 24 步耗尽 vs 第 24 步纯文本自然收尾
    // ---------------------------------------------------------------
    @Test
    fun `max steps 24 exhausted with pending tool reports step limit`() = runBlocking {
        val harness = StreamHarness(
            List(24) { i -> toolStepScript("exh-$i", "echo_tool") }
        )
        val chunks = harness.loop.generate(
            settings = harness.settings, model = harness.model,
            tools = listOf(echoTool()), maxSteps = 24,
        )
        assertEquals(24, chunks.steps().size)
        assertEquals(GenerationChunk.GenerationEnd.Reason.STEP_LIMIT, chunks.end().single().reason)
        // 末步仍带未执行工具 → 不是自然完成
        assertEquals(1, chunks.steps().last().pendingToolCount)
    }

    @Test
    fun `step 24 pure text finishes completed not step limit`() = runBlocking {
        val harness = StreamHarness(
            List(23) { i -> toolStepScript("exh-$i", "echo_tool") } + textStepScript("final report")
        )
        val chunks = harness.loop.generate(
            settings = harness.settings, model = harness.model,
            tools = listOf(echoTool()), maxSteps = 24,
        )
        assertEquals(24, chunks.steps().size)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
        assertEquals(0, chunks.steps().last().pendingToolCount)
    }

    // ---------------------------------------------------------------
    // 未知工具错误回灌后下一轮真实写出文件
    // ---------------------------------------------------------------
    @Test
    fun `unknown tool error fed back then next round writes real file`() = runBlocking {
        val target = File(tempFolder.newFolder("out"), "report.txt")
        val content = "real content from recovered tool"
        val harness = StreamHarness(
            listOf(
                // 名翻倍 → not found 回灌 (事故形态)
                toolStepScript("bad-1", "workspace_write_fileworkspace_write_file"),
                // 模型拿到错误后正确重试 → 真实写文件
                toolStepScript(
                    "good-2", "workspace_write_file",
                    """{"path":"${target.absolutePath.replace("\\", "\\\\")}","content":"$content"}"""
                ),
                textStepScript("wrote the file"),
            )
        )
        val chunks = harness.loop.generate(
            settings = harness.settings, model = harness.model,
            tools = listOf(fileWritingTool()), maxSteps = 10,
        )

        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
        assertEquals(3, chunks.steps().size)
        // 真实副作用: 文件存在且内容逐字一致
        assertTrue(target.isFile)
        assertEquals(content, target.readText())
        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        val bad = tools.first { it.toolCallId == "bad-1" }
        val badOut = bad.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(badOut.contains("not found"))
        val good = tools.first { it.toolCallId == "good-2" }
        assertTrue(good.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("\"written\""))
    }

    // ---------------------------------------------------------------
    // 重试失败 attempt 不计步/usage, partial stream 回滚不遗留 tool part
    // ---------------------------------------------------------------
    @Test
    fun `failed attempt rolls back partial tool and never counts step or usage`() = runBlocking {
        val target = File(tempFolder.newFolder("out"), "written.txt")
        val attempts = listOf(
            // attempt1: 半个 tool call + 半截 usage 后连接断开
            ScriptedAttempt(
                listOf(
                    StreamChunk.ToolCallStart(id = "dead-1", toolName = "workspace_write_file"),
                    StreamChunk.ToolCallDelta(id = "dead-1", toolNameDelta = "", inputDelta = """{"path":"/never""""),
                    StreamChunk.Usage(TokenUsage(777, 777, totalTokens = 1554)),
                ),
                errorAfter = IOException("connection reset mid tool"),
            ),
            // attempt2 (自动重试): 干净重放 → 执行真实写文件
            ScriptedAttempt(
                listOf(
                    StreamChunk.ToolCallStart(id = "ok-2", toolName = "workspace_write_file"),
                    StreamChunk.ToolCallDelta(
                        id = "ok-2", toolNameDelta = "",
                        inputDelta = """{"path":"${target.absolutePath.replace("\\", "\\\\")}","content":"payload"}"""
                    ),
                    StreamChunk.ToolCallEnd(id = "ok-2"),
                    StreamChunk.Usage(TokenUsage(10, 5, totalTokens = 15)),
                    StreamChunk.Finish(finishReason = "tool_calls"),
                )
            ),
            textStepScript("recovered", usage = TokenUsage(1, 1, totalTokens = 2)),
        )
        val harness = StreamHarness(attempts)
        val chunks = harness.loop.generate(
            settings = harness.settings, model = harness.model,
            tools = listOf(fileWritingTool()), maxSteps = 10,
        )

        // 网络层两次 attempt (含失败的一次), 逻辑步只有 2
        assertEquals(3, harness.provider.streamAttempts.size)
        val steps = chunks.steps()
        assertEquals(2, steps.size)
        assertEquals(listOf(15, 2), steps.map { it.usage?.totalTokens })

        // partial 回滚: 最终 transcript 没有 dead-1 part, 也没有它的 usage
        val tools = chunks.transcript().last().getTools()
        assertEquals(listOf("ok-2"), tools.map { it.toolCallId })
        assertTrue(target.isFile)
        assertEquals("payload", target.readText())
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
    }

    // ---------------------------------------------------------------
    // usage 多个分片合并 & 中途缺失
    // ---------------------------------------------------------------
    @Test
    fun `usage shards merge and missing mid stream stay null`() = runBlocking {
        val harness = StreamHarness(
            listOf(
                // step1: prompt/completion/total 分在三个 chunk, 0 段不重设已得值
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "u-1", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "u-1", toolNameDelta = "", inputDelta = "{}"),
                        StreamChunk.ToolCallEnd(id = "u-1"),
                        StreamChunk.Usage(TokenUsage(40, 0, totalTokens = 0)),
                        StreamChunk.Usage(TokenUsage(0, 15, totalTokens = 0)),
                        StreamChunk.Usage(TokenUsage(0, 0, totalTokens = 55)),
                        StreamChunk.Finish(finishReason = "tool_calls"),
                    )
                ),
                // step2: 完全没有 usage → null
                toolStepScript("u-2", "echo_tool", usage = null),
                textStepScript("done", usage = TokenUsage(3, 2, totalTokens = 5)),
            )
        )
        val chunks = harness.loop.generate(
            settings = harness.settings, model = harness.model,
            tools = listOf(echoTool()), maxSteps = 10,
        )
        val steps = chunks.steps()
        assertEquals(3, steps.size)
        // merge 语义: 正值覆盖, 0 保留; total=prompt+completion (40+15=55)
        assertEquals(40, steps[0].usage?.promptTokens)
        assertEquals(15, steps[0].usage?.completionTokens)
        assertEquals(55, steps[0].usage?.totalTokens)
        assertNull(steps[1].usage)
        assertEquals(5, steps[2].usage?.totalTokens)
        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(60L, stats.totalTokens)
    }

    // ---------------------------------------------------------------
    // 取消保持状态: 工具执行中取消 → 未执行 part 保留, 无 GenerationEnd
    // ---------------------------------------------------------------
    @Test
    fun `cancellation during tool execution preserves pending tool state`() = runBlocking {
        val cancellingTool = Tool(
            name = "hang_tool",
            description = "cancel while executing",
            parameters = { InputSchema.Obj(properties = kotlinx.serialization.json.JsonObject(emptyMap()), required = null) },
            execute = { _: JsonElement -> throw CancellationException("cancelled inside tool") },
        )
        val harness = StreamHarness(listOf(toolStepScript("cancel-1", "hang_tool")))
        val chunks = mutableListOf<GenerationChunk>()
        try {
            harness.loop.generateText(
                settings = harness.settings, model = harness.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
                tools = listOf(cancellingTool),
            ).collect { chunks += it }
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // 取消必须向上传播, 不能吞
        }
        // 无终态事件
        assertTrue(chunks.end().isEmpty())
        // 工具 part 保留未执行状态 (output 为空), 不伪造成功结果
        val lastMsg = chunks.transcript().last()
        val tool = lastMsg.getTools().single { it.toolCallId == "cancel-1" }
        assertTrue(tool.output.isEmpty())
    }
}
