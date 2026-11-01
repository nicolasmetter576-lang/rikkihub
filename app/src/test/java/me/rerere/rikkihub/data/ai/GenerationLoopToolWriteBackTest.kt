package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ToolApprovalState
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [GenerationLoop] 工具回写定位回归 (缺陷 G-2 的 app 侧):
 * 按 toolCallId 的 `find`/`map` 回写在空 id / 重复 id / 同 id 复用已执行 call 时
 * 会把新输出/metadata 写进所有同 id part (覆盖已执行历史、混同两个调用)。
 * 修复后回写一律按选取时记录的 part 下标定位。
 *
 * 覆盖面: 恢复路径同 id / 同轮双空 id / 非流式跨轮同 id / 单 response 内
 * 双空 id 与同 id / 审批映射定位 / pending-tools 恢复耗尽 / observer 契约。
 * 全部经真实循环 + 真实工具执行断言, 不走 mock。
 */
class GenerationLoopToolWriteBackTest {

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

    /** 记录执行参数并回显的工具; executeWithContext 里同步发带序号的进度 metadata。 */
    private class CountingTool(name: String = "echo_tool") {
        val executions = CopyOnWriteArrayList<String>()
        val tool: Tool = Tool(
            name = name,
            description = "counted echo",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
            execute = { _: JsonElement -> error("executeWithContext expected") },
            executeWithContext = { args, ctx ->
                executions += args.toString()
                ctx.publishMetadata(buildJsonObject { put("exec_seq", executions.size) })
                listOf(UIMessagePart.Text("""{"echoed":$args}"""))
            },
        )
    }

    private fun toolResult(
        parts: List<UIMessagePart>,
        usage: TokenUsage? = null,
        finishReason: String = "tool_calls",
    ) = TextGenerationResult(
        id = "r",
        model = "scripted-chat",
        message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
        finishReason = finishReason,
        usage = usage,
    )

    private fun textResult(text: String) = TextGenerationResult(
        id = "r-text",
        model = "scripted-chat",
        message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
        finishReason = "stop",
    )

    private fun List<GenerationChunk>.steps() = filterIsInstance<GenerationChunk.StepFinished>()
    private fun List<GenerationChunk>.end() = filterIsInstance<GenerationChunk.GenerationEnd>()
    private fun List<GenerationChunk>.transcript() =
        filterIsInstance<GenerationChunk.Messages>().last().messages

    private fun toolOutputText(tool: UIMessagePart.Tool) =
        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    // ---------------------------------------------------------------
    // 恢复路径: 已执行 part 与新 part 同 id, 输出只写进新 part
    // ---------------------------------------------------------------
    @Test
    fun `resume with same id executed and pending parts writes output only to the new part`() = runBlocking {
        val oldTool = UIMessagePart.Tool(
            toolCallId = "dup", toolName = "echo_tool", input = """{"a":1}""",
            output = listOf(UIMessagePart.Text("OLD-OUTPUT")),
        )
        val approvedNew = UIMessagePart.Tool(
            toolCallId = "dup", toolName = "echo_tool", input = """{"a":2}""",
            approvalState = ToolApprovalState.Approved,
        )
        val harness = StreamHarness(listOf(textStepScript("after resume")))
        val counting = CountingTool()

        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(
                UIMessage.user("go"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(oldTool, approvedNew)),
            ),
            assistant = testAssistant(),
            tools = listOf(counting.tool),
            maxSteps = 10,
        ).toList()

        // 恢复路径只执行审批过的新 call, 一次
        assertEquals(listOf("""{"a":2}"""), counting.executions)
        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        // 旧 part: input/name/output 全部原样保留, 不被新输出覆盖
        assertEquals("""{"a":1}""", tools[0].input)
        assertEquals("OLD-OUTPUT", toolOutputText(tools[0]))
        // 新 part: 输出独立回显自己的 input
        assertEquals("""{"echoed":{"a":2}}""", toolOutputText(tools[1]))
        // 实时 metadata 只落在新 part
        assertNull(tools[0].metadata)
        assertEquals(1, tools[1].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        // 恢复后还要走一个 provider 步才 COMPLETED
        assertEquals(1, chunks.steps().size)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
    }

    // ---------------------------------------------------------------
    // 恢复路径: 同一消息里两个空 id 未执行 call, 各自执行、各自输出
    // ---------------------------------------------------------------
    @Test
    fun `two blank id calls in one turn each execute and write back independently`() = runBlocking {
        val blankA = UIMessagePart.Tool(
            toolCallId = "", toolName = "echo_tool", input = """{"n":1}""",
            approvalState = ToolApprovalState.Approved,
        )
        val blankB = UIMessagePart.Tool(
            toolCallId = "", toolName = "echo_tool", input = """{"n":2}""",
            approvalState = ToolApprovalState.Approved,
        )
        val harness = StreamHarness(listOf(textStepScript("after both")))
        val counting = CountingTool()

        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(
                UIMessage.user("go"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(blankA, blankB)),
            ),
            assistant = testAssistant(),
            tools = listOf(counting.tool),
            maxSteps = 10,
        ).toList()

        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), counting.executions)
        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        // 两个独立调用的结果不混为一个
        assertEquals("""{"echoed":{"n":1}}""", toolOutputText(tools[0]))
        assertEquals("""{"echoed":{"n":2}}""", toolOutputText(tools[1]))
        assertEquals(1, tools[0].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        assertEquals(2, tools[1].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
    }

    // ---------------------------------------------------------------
    // 非流式跨轮同 id: 第二轮输出/metadata 不覆盖已执行的旧 call
    // ---------------------------------------------------------------
    @Test
    fun `non streaming reused id across rounds keeps old call untouched`() = runBlocking {
        val harness = ResultHarness(
            listOf(
                toolResult(listOf(UIMessagePart.Tool("dup", "echo_tool", """{"a":1}"""))),
                toolResult(listOf(UIMessagePart.Tool("dup", "echo_tool", """{"a":2}"""))),
                textResult("done"),
            )
        )
        val counting = CountingTool()
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("go")),
            assistant = testAssistant(streamOutput = false),
            tools = listOf(counting.tool),
            maxSteps = 10,
        ).toList()

        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), counting.executions)
        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        assertEquals(listOf("dup", "dup"), tools.map { it.toolCallId })
        // 旧 call 原样: input 第一轮, output 第一轮回显, metadata 第一轮进度
        assertEquals("""{"a":1}""", tools[0].input)
        assertEquals("""{"echoed":{"a":1}}""", toolOutputText(tools[0]))
        assertEquals(1, tools[0].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        // 新 call 独立: 第二轮 input/output/metadata
        assertEquals("""{"a":2}""", tools[1].input)
        assertEquals("""{"echoed":{"a":2}}""", toolOutputText(tools[1]))
        assertEquals(2, tools[1].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
    }

    // ---------------------------------------------------------------
    // 单条非流式 response 内两个空 id call: ai 侧各开新 part, app 各写各
    // ---------------------------------------------------------------
    @Test
    fun `non streaming two blank id calls in one response dispatch independently`() = runBlocking {
        val harness = ResultHarness(
            listOf(
                toolResult(
                    listOf(
                        UIMessagePart.Tool("", "echo_tool", """{"n":1}"""),
                        UIMessagePart.Tool("", "echo_tool", """{"n":2}"""),
                    )
                ),
                textResult("done"),
            )
        )
        val counting = CountingTool()
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("go")),
            assistant = testAssistant(streamOutput = false),
            tools = listOf(counting.tool),
            maxSteps = 10,
        ).toList()

        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), counting.executions)
        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        assertEquals("""{"echoed":{"n":1}}""", toolOutputText(tools[0]))
        assertEquals("""{"echoed":{"n":2}}""", toolOutputText(tools[1]))
        assertEquals(1, tools[0].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        assertEquals(2, tools[1].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
    }

    // ---------------------------------------------------------------
    // 单条非流式 response 内两个同 id call: 首个 response 直接落地为新
    // assistant 消息 (appendMessage 只在与既有 assistant 合并时按 id 匹配),
    // 两个同 id 未执行 part 各自独立执行、各自回写, 不互相覆盖
    // ---------------------------------------------------------------
    @Test
    fun `non streaming duplicate same id in one response executes each call independently`() = runBlocking {
        val harness = ResultHarness(
            listOf(
                toolResult(
                    listOf(
                        UIMessagePart.Tool("dup", "echo_tool", """{"a":1}"""),
                        UIMessagePart.Tool("dup", "echo_tool", """{"a":2}"""),
                    )
                ),
                textResult("done"),
            )
        )
        val counting = CountingTool()
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("go")),
            assistant = testAssistant(streamOutput = false),
            tools = listOf(counting.tool),
            maxSteps = 10,
        ).toList()

        // 两个同 id 调用各自真实执行一次, 输入互不串扰
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), counting.executions)
        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        assertEquals(listOf("dup", "dup"), tools.map { it.toolCallId })
        assertEquals("""{"echoed":{"a":1}}""", toolOutputText(tools[0]))
        assertEquals("""{"echoed":{"a":2}}""", toolOutputText(tools[1]))
        assertEquals(1, tools[0].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        assertEquals(2, tools[1].metadata?.get("exec_seq")?.jsonPrimitive?.intOrNull)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.end().single().reason)
    }

    // ---------------------------------------------------------------
    // 审批映射定位: 同 id 已执行 part 旁的新 call 转 Pending, 旧 part 不动
    // ---------------------------------------------------------------
    @Test
    fun `approval pending write back targets only the new part next to executed same id`() = runBlocking {
        // 场景: assistant 消息里已有同 id 已执行 call, 新一轮又产出同 id 调用且
        // 该工具需要审批 → 只有新 part 转 Pending, 旧 part 的 output/approval 原样。
        val harness = ResultHarness(
            listOf(toolResult(listOf(UIMessagePart.Tool("dup", "echo_tool", """{"a":2}"""))))
        )
        val oldTool = UIMessagePart.Tool(
            toolCallId = "dup", toolName = "echo_tool", input = """{"a":1}""",
            output = listOf(UIMessagePart.Text("OLD-OUTPUT")),
        )
        val approvalTool = echoTool().copy(needsApproval = { true })
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(
                UIMessage.user("go"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(oldTool)),
            ),
            assistant = testAssistant(streamOutput = false),
            tools = listOf(approvalTool),
            maxSteps = 10,
        ).toList()

        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        // 旧 call: 执行历史与 Auto 状态原样保留
        assertEquals("OLD-OUTPUT", toolOutputText(tools[0]))
        assertEquals(ToolApprovalState.Auto, tools[0].approvalState)
        assertEquals("""{"a":1}""", tools[0].input)
        // 新 call: 转 Pending, 不执行
        assertEquals(ToolApprovalState.Pending, tools[1].approvalState)
        assertTrue(tools[1].output.isEmpty())
        assertEquals(GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL, chunks.end().single().reason)
    }

    // ---------------------------------------------------------------
    // pending-tools 恢复耗尽: 纯工具迭代耗尽 maxSteps → STEP_LIMIT, 无 StepFinished
    // ---------------------------------------------------------------
    @Test
    fun `resume exhaustion yields step limit without a provider step`() = runBlocking {
        val approved = UIMessagePart.Tool(
            toolCallId = "p1", toolName = "echo_tool", input = "{}",
            approvalState = ToolApprovalState.Approved,
        )
        val harness = StreamHarness(emptyList())
        val counting = CountingTool()
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(
                UIMessage.user("go"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(approved)),
            ),
            assistant = testAssistant(),
            tools = listOf(counting.tool),
            maxSteps = 1,
        ).toList()

        assertEquals(listOf("{}"), counting.executions)
        // 恢复执行不是 provider 步: 没有 StepFinished, 但循环正常退出必有终态事件
        assertTrue(chunks.steps().isEmpty())
        assertEquals(GenerationChunk.GenerationEnd.Reason.STEP_LIMIT, chunks.end().single().reason)
        // 输出确实写回
        assertEquals("""{"echoed":{}}""", toolOutputText(chunks.transcript().last().getTools().single()))
    }

    // ---------------------------------------------------------------
    // observer 契约: 上游 error 时 StepFinished 已在生产侧同步观测 (BUG1 回归)
    // ---------------------------------------------------------------
    @Test
    fun `eventsObserver sees step finished even when upstream error drops the channel`() = runBlocking {
        val harness = StreamHarness(
            listOf(
                toolStepScript("c1", "echo_tool", usage = TokenUsage(10, 5, totalTokens = 15)),
                ScriptedAttempt(emptyList(), errorAfter = IllegalStateException("provider blew up")),
            )
        )
        val observed = CopyOnWriteArrayList<GenerationChunk>()
        val chunks = mutableListOf<GenerationChunk>()
        try {
            harness.loop.generateTextObserved(
                settings = harness.settings,
                model = harness.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
                tools = listOf(echoTool()),
                eventsObserver = GenerationEventObserver { observed += it },
            ).collect { chunks += it }
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // provider 错误原样传播
        }
        // 生产侧观测到 step1 的 StepFinished (collect 通道可能丢弃在途事件)
        val observedSteps = observed.filterIsInstance<GenerationChunk.StepFinished>()
        assertEquals(1, observedSteps.size)
        assertEquals(0, observedSteps.single().stepIndex)
        assertEquals(15, observedSteps.single().usage?.totalTokens)
        // 异常路径无 GenerationEnd
        assertTrue(observed.filterIsInstance<GenerationChunk.GenerationEnd>().isEmpty())
    }

    // ---------------------------------------------------------------
    // observer 契约: 回调异常不冒充 provider 错误、不终止生成
    // ---------------------------------------------------------------
    @Test
    fun `throwing observer neither aborts generation nor masks provider errors`() = runBlocking {
        val harness = StreamHarness(listOf(textStepScript("still completes")))
        var observerCalls = 0
        val chunks = harness.loop.generateTextObserved(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            eventsObserver = GenerationEventObserver {
                observerCalls++
                throw IllegalStateException("observer bug")
            },
        ).toList()

        // 生成正常完成, 终态事件照常发射给下游
        assertEquals(2, observerCalls) // StepFinished + GenerationEnd
        assertEquals(
            GenerationChunk.GenerationEnd.Reason.COMPLETED,
            chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason
        )
        assertEquals("still completes", chunks.transcript().last().toText())

        // provider 错误仍按原异常传播, 不被 observer 异常掩盖
        val failing = StreamHarness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = IllegalArgumentException("real provider error")))
        )
        try {
            failing.loop.generateTextObserved(
                settings = failing.settings,
                model = failing.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
                eventsObserver = GenerationEventObserver { throw IllegalStateException("observer bug") },
            ).collect {}
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("real provider error", expected.message)
        }
    }

    // ---------------------------------------------------------------
    // runStats 线程安全快照: 并发喂事件时多字段视图不撕裂
    // ---------------------------------------------------------------
    @Test
    fun `run stats snapshot stays consistent under concurrent feeding`() = runBlocking {
        val stats = GenerationRunStats()
        val feeders = (0 until 4).map { f ->
            async(Dispatchers.Default) {
                repeat(50) { i ->
                    stats.onChunk(
                        GenerationChunk.StepFinished(
                            stepIndex = f * 50 + i,
                            usage = TokenUsage(1, 1, totalTokens = 2),
                            finishReason = "stop",
                        )
                    )
                }
            }
        }
        val readers = (0 until 2).map {
            async(Dispatchers.Default) {
                repeat(500) {
                    val snap = stats.snapshot()
                    // 所有 usage=2: totalTokens 必为 stepCount*2, 撕裂视图会破坏不变量
                    if (snap.sawUsageReport) {
                        assertEquals(2L * snap.stepCount, snap.totalTokens)
                    } else {
                        assertEquals(0, snap.stepCount)
                        assertNull(snap.totalTokens)
                    }
                }
            }
        }
        feeders.awaitAll()
        readers.awaitAll()
        stats.onChunk(GenerationChunk.GenerationEnd(GenerationChunk.GenerationEnd.Reason.COMPLETED))
        val snap = stats.snapshot()
        assertEquals(200, snap.stepCount)
        assertEquals(400L, snap.totalTokens)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, snap.endReason)
        assertEquals("stop", snap.lastFinishReason)
    }

    // ---------------------------------------------------------------
    // 步级 usage 归并: 只报 total 的分片不被 p+c 重算归零, total>p+c 保留
    // ---------------------------------------------------------------
    @Test
    fun `step usage preserves reported totals that prompt plus completion cannot derive`() = runBlocking {
        // helper 级
        val totalOnly = mergeStepUsage(null, TokenUsage(0, 0, totalTokens = 55))
        assertEquals(55, totalOnly.totalTokens)
        val accumulated = mergeStepUsage(totalOnly, TokenUsage(40, 0, totalTokens = 0))
        assertEquals(40, accumulated.promptTokens)
        assertEquals(55, accumulated.totalTokens)
        val highTotal = mergeStepUsage(null, TokenUsage(40, 15, totalTokens = 200))
        assertEquals(200, highTotal.totalTokens)

        // 真实循环: provider 只上报 totalTokens 的分片, StepFinished 不归零
        val harness = StreamHarness(
            listOf(
                ScriptedAttempt(
                    listOf(
                        me.rerere.ai.ui.StreamChunk.ToolCallStart(id = "t-1", toolName = "echo_tool"),
                        me.rerere.ai.ui.StreamChunk.ToolCallDelta(id = "t-1", inputDelta = "{}"),
                        me.rerere.ai.ui.StreamChunk.ToolCallEnd(id = "t-1"),
                        me.rerere.ai.ui.StreamChunk.Usage(TokenUsage(0, 0, totalTokens = 55)),
                        me.rerere.ai.ui.StreamChunk.Finish(finishReason = "tool_calls"),
                    )
                ),
                textStepScript("done", usage = TokenUsage(5, 5, totalTokens = 10)),
            )
        )
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            tools = listOf(echoTool()),
            maxSteps = 10,
        ).toList()
        val steps = chunks.steps()
        assertEquals(55, steps[0].usage?.totalTokens)
        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(65L, stats.totalTokens)
    }
}
