package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.WorkspaceToolWirePolicy
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolIdentity
import me.rerere.ai.ui.WorkspaceToolMode
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.ai.ScriptedAttempt
import me.rerere.rikkihub.data.ai.ScriptedProvider
import me.rerere.rikkihub.data.ai.echoTool
import me.rerere.rikkihub.data.ai.fileWritingTool
import me.rerere.rikkihub.data.ai.testAssistant
import me.rerere.rikkihub.data.ai.testChatModel
import me.rerere.rikkihub.data.ai.testGenerationLoop
import me.rerere.rikkihub.data.ai.testSettings
import me.rerere.rikkihub.data.ai.textStepScript
import me.rerere.rikkihub.data.ai.toolStepScript
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.workspace.WorkspaceChangeSpec
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [WorkspaceSubagentRunner] 真实统计/终态测试。经真实 [me.rerere.rikkihub.data.ai.GenerationLoop]
 * + 脚本化 Provider 跑完整生成循环, 校验结果 JSON / 运行中 metadata / v2 日志口径一致,
 * 且 transcript 是真实的多步合并形态 (单条 assistant 消息)。
 */
class WorkspaceSubagentRunnerStatsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val noopReminder = object : InputMessageTransformer {}

    /** 统一 model 实例: parentModel 与 settings.providers 内必须同 id, findProvider 才找得到 */
    private inner class Harness(
        attempts: List<ScriptedAttempt>,
        maxSteps: Int = SUBAGENT_MAX_STEPS,
    ) {
        val context = FakeContext(tempFolder.newFolder())
        val provider = ScriptedProvider(attempts = ArrayDeque(attempts))
        val model = testChatModel()
        val settings = testSettings(model)
        val runner = WorkspaceSubagentRunner(
            generationHandler = testGenerationLoop(provider, context),
            settingsProvider = { settings },
            reminderTransformer = noopReminder,
            context = context,
            maxSteps = maxSteps,
            // 生产节流 400ms 依赖墙钟; 单测内整轮运行只有毫秒级, 传 0
            // 保证 running 快照真实发布, 断言 deterministically。
            progressFlushIntervalMs = 0,
        )
    }

    private fun Harness.runSubagent(
        toolContext: ToolExecutionContext? = null,
        childTools: List<Tool> = listOf(echoTool()),
        childToolSnapshotProvider: (suspend () -> WorkspaceToolSnapshot?)? = null,
    ) = runBlocking {
        runner.run(
            parentAssistant = testAssistant(),
            parentModel = model,
            cwd = null,
            harnessMinimal = false,
            allowContext = false,
            subagentModelId = null,
            args = JsonInstant.parseToJsonElement("""{"prompt":"do the task","description":"demo"}"""),
            childTools = childTools,
            toolContext = toolContext,
            childToolSnapshotProvider = childToolSnapshotProvider,
        )
    }

    private fun resultJson(parts: List<UIMessagePart>) =
        JsonInstant.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    private fun Harness.singleLogFile(): File {
        val dir = File(context.filesDir, FileFolders.SUBAGENT_RUNS)
        val files = dir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        assertEquals("expected exactly one subagent log file in $dir", 1, files.size)
        return files.single()
    }

    private fun Harness.logJson() =
        JsonInstant.parseToJsonElement(singleLogFile().readText()).jsonObject

    @Test
    fun `multi-step merged transcript reports real step count and summed tokens`() = runBlocking {
        val harness = Harness(
            listOf(
                toolStepScript("c1", "echo_tool", usage = TokenUsage(10, 5, totalTokens = 15)),
                toolStepScript("c2", "echo_tool", usage = TokenUsage(20, 10, totalTokens = 30)),
                textStepScript("done report", usage = TokenUsage(30, 15, totalTokens = 45)),
            )
        )
        val parts = harness.runSubagent()
        val json = resultJson(parts)

        // 真实形态校验: 日志里 3 步合并成 1 条 assistant 消息, 但 step_count 必须是 3
        val log = harness.logJson()
        val messages = JsonInstant.decodeFromJsonElement<List<UIMessage>>(log["messages"]!!)
        assertEquals(1, messages.count { it.role == me.rerere.ai.core.MessageRole.ASSISTANT })

        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals(3, json["step_count"]!!.jsonPrimitive.intOrNull)
        assertEquals(90L, json["total_tokens"]!!.jsonPrimitive.longOrNull)
        assertEquals("completed", json["completion_reason"]!!.jsonPrimitive.content)

        // 结果 JSON 与 v2 日志同源
        assertEquals(3, log["step_count"]!!.jsonPrimitive.intOrNull)
        assertEquals(90L, log["total_tokens"]!!.jsonPrimitive.longOrNull)
        assertEquals("completed", log["completion_reason"]!!.jsonPrimitive.content)
        assertEquals("ok", log["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool loop hitting max steps reports step_limit with real count`() = runBlocking {
        val harness = Harness(
            List(3) { i -> toolStepScript("c$i", "echo_tool") },
            maxSteps = 3,
        )
        val parts = harness.runSubagent()
        val json = resultJson(parts)

        assertEquals("step_limit", json["status"]!!.jsonPrimitive.content)
        assertEquals(3, json["step_count"]!!.jsonPrimitive.intOrNull)
        assertEquals("step_limit", json["completion_reason"]!!.jsonPrimitive.content)
        assertNotNull(json["error"])
        // edited_paths 为空不等于失败, 不据此改 status
        assertTrue(json["edited_paths"]!!.jsonArray.isEmpty())

        val log = harness.logJson()
        assertEquals("step_limit", log["status"]!!.jsonPrimitive.content)
        assertEquals("step_limit", log["completion_reason"]!!.jsonPrimitive.content)
        assertEquals(3, log["step_count"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun `unknown tool error is fed back and run still completes`() = runBlocking {
        val harness = Harness(
            listOf(
                // 名称翻倍损坏: 错误回灌给模型而不是终止
                toolStepScript("bad-1", "echo_toolecho_tool"),
                textStepScript("report"),
            )
        )
        val parts = harness.runSubagent()
        val json = resultJson(parts)

        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals(2, json["step_count"]!!.jsonPrimitive.intOrNull)
        assertEquals("completed", json["completion_reason"]!!.jsonPrimitive.content)

        val log = harness.logJson()
        val messages = JsonInstant.decodeFromJsonElement<List<UIMessage>>(log["messages"]!!)
        val toolOutput = messages.last().getTools().single().output
            .filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(toolOutput.contains("not found"))
        // 未知工具名原样计入 tools_used (可观测), 不伪造成功
        assertEquals(
            listOf("echo_toolecho_tool"),
            log["tools_used"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `terminal metadata matches output json and running flushes carry live steps`() = runBlocking {
        val harness = Harness(
            listOf(
                toolStepScript("c1", "echo_tool", usage = TokenUsage(10, 5, totalTokens = 15)),
                textStepScript("report", usage = TokenUsage(5, 5, totalTokens = 10)),
            )
        )
        val metas = mutableListOf<SubagentMetadata>()
        val ctx = ToolExecutionContext("subagent-call") { raw ->
            metas += JsonInstant.decodeFromJsonElement<SubagentMetadata>(raw)
        }
        val parts = harness.runSubagent(toolContext = ctx)
        val json = resultJson(parts)

        // 终态 metadata 与输出 JSON 同源
        val finalMeta = metas.last()
        assertEquals(json["status"]!!.jsonPrimitive.content, finalMeta.status)
        assertEquals(2, finalMeta.stepCount)
        assertEquals(25L, finalMeta.totalTokens)
        assertEquals(finalMeta.status, parts.single().metadataAs<SubagentMetadata>()?.status)

        // 运行中发布过 running 快照, 步数是事件累计的真实值 (不为恒 1)
        val running = metas.filter { it.status == "running" }
        assertTrue(running.isNotEmpty())
        assertTrue(running.last().stepCount!! >= 1)
    }

    @Test
    fun `provider error marks error status and log matches`() = runBlocking {
        val harness = Harness(
            listOf(
                toolStepScript("c1", "echo_tool", usage = TokenUsage(10, 5, totalTokens = 15)),
                ScriptedAttempt(emptyList(), errorAfter = IllegalStateException("provider blew up")),
            )
        )
        val parts = harness.runSubagent()
        val json = resultJson(parts)

        assertEquals("error", json["status"]!!.jsonPrimitive.content)
        assertEquals("error", json["completion_reason"]!!.jsonPrimitive.content)
        assertEquals(1, json["step_count"]!!.jsonPrimitive.intOrNull)
        assertEquals(15L, json["total_tokens"]!!.jsonPrimitive.longOrNull)

        val log = harness.logJson()
        assertEquals("error", log["status"]!!.jsonPrimitive.content)
        assertEquals("error", log["completion_reason"]!!.jsonPrimitive.content)
        assertNotNull(log["error"])
    }

    @Test
    fun `usage snapshot corrections and shards fold into real run totals`() = runBlocking {
        val harness = Harness(
            listOf(
                // step1: 高估的完整快照 105, 同步内被 total-only 快照修正为 20
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "u1", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "u1", inputDelta = "{}"),
                        StreamChunk.ToolCallEnd(id = "u1"),
                        StreamChunk.Usage(TokenUsage(100, 5, totalTokens = 105)),
                        StreamChunk.Usage(TokenUsage(0, 0, totalTokens = 20)),
                        StreamChunk.Finish(finishReason = "tool_calls"),
                    )
                ),
                // step2: partial p/c 分片累计 (p 轴 50 + c 轴 15) = 65, 不是 15
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "u2", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "u2", inputDelta = "{}"),
                        StreamChunk.ToolCallEnd(id = "u2"),
                        StreamChunk.Usage(TokenUsage(50, 0, totalTokens = 50)),
                        StreamChunk.Usage(TokenUsage(0, 15, totalTokens = 15)),
                        StreamChunk.Finish(finishReason = "tool_calls"),
                    )
                ),
                // step3: 无 usage 不计入, 也不复用旧值
                textStepScript("done", usage = null),
            )
        )
        val parts = harness.runSubagent()
        val json = resultJson(parts)

        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals(3, json["step_count"]!!.jsonPrimitive.intOrNull)
        // 20 (修正后) + 65 (分片累计), 无 usage 的步贡献 0
        assertEquals(85L, json["total_tokens"]!!.jsonPrimitive.longOrNull)
        assertEquals("completed", json["completion_reason"]!!.jsonPrimitive.content)

        val log = harness.logJson()
        assertEquals(85L, log["total_tokens"]!!.jsonPrimitive.longOrNull)
        assertEquals(3, log["step_count"]!!.jsonPrimitive.intOrNull)
        assertEquals("completed", log["completion_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancellation persists cancelled log then rethrows`() = runBlocking {
        val harness = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = CancellationException("user stop")))
        )
        try {
            harness.runSubagent()
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // 取消不被吞
        }

        val log = harness.logJson()
        assertEquals("cancelled", log["status"]!!.jsonPrimitive.content)
        assertEquals("cancelled", log["completion_reason"]!!.jsonPrimitive.content)
        assertEquals(0, log["step_count"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun `protocol failure rolls transcript back to base and reports error without side effects`() = runBlocking {
        val target = File(tempFolder.newFolder(), "subagent-should-not-exist.txt")
        val harness = Harness(
            listOf(
                toolStepScript("c1", "echo_tool", usage = TokenUsage(10, 5, totalTokens = 15)),
                // step2 收到完整工具调用后流协议失败: attempt 失败即不执行、不计步
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "c2", toolName = "workspace_write_file"),
                        StreamChunk.ToolCallDelta(
                            id = "c2",
                            inputDelta = """{"path":"${target.absolutePath}","content":"x"}""",
                        ),
                        StreamChunk.ToolCallEnd(id = "c2"),
                    ),
                    errorAfter = ToolStreamProtocolException("dup_tool_name", "duplicate tool name"),
                ),
            )
        )
        val parts = harness.runSubagent()
        val json = resultJson(parts)

        assertEquals("error", json["status"]!!.jsonPrimitive.content)
        assertEquals("error", json["completion_reason"]!!.jsonPrimitive.content)
        // 失败 attempt 不计步, 前面成功步的统计保留
        assertEquals(1, json["step_count"]!!.jsonPrimitive.intOrNull)
        assertEquals(15L, json["total_tokens"]!!.jsonPrimitive.longOrNull)
        // 协议失败 attempt 的工具调用零副作用
        assertFalse(target.exists())

        // 日志 messages 经权威清理: 已恢复为 attempt 前快照, 不含半截 c2
        val log = harness.logJson()
        assertEquals("error", log["status"]!!.jsonPrimitive.content)
        val messages = JsonInstant.decodeFromJsonElement<List<UIMessage>>(log["messages"]!!)
        val assistants = messages.filter { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
        assertTrue(assistants.size <= 1)
        val toolIds = assistants.flatMap { it.getTools() }.map { it.toolCallId }
        // flowOn 可能丢掉在途 Messages (连 assistant 壳都没发布); 一旦发布过则
        // 必须已恢复到只含 c1 的 base, 绝不能残留半截 c2
        assertTrue("c2" !in toolIds)
        assertTrue(toolIds.isEmpty() || toolIds == listOf("c1"))
    }

    // ---------------------------------------------------------------
    // child 快照 provider 每 provider 请求重取（热禁用/改名生效）
    // ---------------------------------------------------------------
    @Test
    fun `child tool snapshot provider is refetched per provider request`() = runBlocking {
        var xRan = false
        var yRan = false
        val toolX = Tool(
            name = "tool_x",
            description = "d",
            parameters = { null },
            execute = { xRan = true; listOf(UIMessagePart.Text("{}")) },
        )
        val toolY = Tool(
            name = "tool_y",
            description = "d",
            parameters = { null },
            execute = { yRan = true; listOf(UIMessagePart.Text("{}")) },
        )
        var sliceCalls = 0
        val provider = suspend {
            sliceCalls++
            WorkspaceToolSnapshot(
                // step1 的请求构建/审批/执行/截断读（<=4 次）仍给 tool_x；
                // step2 请求构建起 child 面只剩 tool_y（tool_x 已热禁用）。
                tools = if (sliceCalls <= 4) listOf(toolX) else listOf(toolY),
                capabilities = WorkspaceRequestCapabilities(
                    workspaceId = "ws-1",
                    workspaceName = "ws",
                    harnessMinimal = false,
                    availableToolNames = emptyMap(),
                    exposedTargets = emptySet(),
                ),
                forbidden = emptyMap(),
                wirePolicy = WorkspaceToolWirePolicy(),
                policyEpoch = 0,
                viewFingerprint = "root:files:mode:normal",
            )
        }
        val harness = Harness(
            listOf(
                toolStepScript("c1", "tool_x"),
                toolStepScript("c2", "tool_y"),
                textStepScript("done"),
            )
        )
        val parts = harness.runSubagent(
            childTools = listOf(toolX),
            childToolSnapshotProvider = provider,
        )
        val json = resultJson(parts)
        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals(3, json["step_count"]!!.jsonPrimitive.intOrNull)
        assertTrue(xRan)
        assertTrue(yRan)
        // 每请求 + 审批前/执行前复核都经 provider 重取（次数 > 请求数）
        assertTrue(sliceCalls >= 3)
    }

    // ---------------------------------------------------------------
    // 真实 gate 在途取消：outcome_unknown 仍按「已开始」计入 tools_used，
    // unavailable（零执行）不冒充工具使用
    // ---------------------------------------------------------------
    @Test
    fun `gate-cancelled child tool counts as started and unavailable child tool is excluded`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val holdStarted = CompletableDeferred<Unit>()
        val gated = Tool(
            name = "gated_tool",
            description = "d",
            parameters = { null },
            executeWithContext = { _: kotlinx.serialization.json.JsonElement, _: ToolExecutionContext ->
                gate.withOperation(
                    workspaceId = "ws-1",
                    kind = WorkspaceOperationKind.TOOL,
                    stableIds = setOf("workspace_shell"),
                ) {
                    holdStarted.complete(Unit)
                    awaitCancellation()
                }
            },
            execute = { error("execute path not used") },
        )
        val unavailableIdentity = WorkspaceToolIdentity(
            workspaceId = "ws-1",
            stableId = "workspace_shell",
            implementationId = "workspace_shell",
            mode = WorkspaceToolMode.NORMAL,
        )
        val unavailable = Tool(
            name = "unavailable_tool",
            description = "d",
            parameters = { null },
            // 守卫准入拒绝（零执行）的同款输出：不计入统计
            workspaceIdentity = unavailableIdentity,
            execute = {
                workspaceToolUnavailableParts(
                    unavailableIdentity, WorkspaceToolUnavailableException.Reason.TOOL_DISABLED, "disabled",
                )
            },
        )
        val harness = Harness(
            listOf(
                ScriptedAttempt(
                    buildList {
                        add(StreamChunk.ToolCallStart(id = "c1", toolName = "gated_tool"))
                        add(StreamChunk.ToolCallDelta(id = "c1", inputDelta = "{}"))
                        add(StreamChunk.ToolCallEnd(id = "c1"))
                        add(StreamChunk.ToolCallStart(id = "c2", toolName = "unavailable_tool"))
                        add(StreamChunk.ToolCallDelta(id = "c2", inputDelta = "{}"))
                        add(StreamChunk.ToolCallEnd(id = "c2"))
                        add(StreamChunk.Finish(finishReason = "tool_calls"))
                    }
                ),
                textStepScript("report"),
            )
        )
        val runJob = async {
            harness.runSubagent(childTools = listOf(gated, unavailable))
        }
        holdStarted.await()
        gate.publishAndCancel("ws-1", WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
        val parts = runJob.await()
        val json = resultJson(parts)
        assertEquals("ok", json["status"]!!.jsonPrimitive.content)

        val tools = json["tools_used"]!!.jsonArray.map { it.jsonPrimitive.content }
        // 在途取消的 gated_tool 已开始执行（outcome_unknown）→ 计入 tools_used；
        // unavailable_tool 零执行 → 不冒充工具使用
        assertEquals(listOf("gated_tool"), tools)

        // 日志 transcript 保留原 part 的 status 词表供追溯
        val log = harness.logJson()
        val messages = JsonInstant.decodeFromJsonElement<List<UIMessage>>(log["messages"]!!)
        val toolParts = messages.flatMap { it.getTools() }
        assertEquals(2, toolParts.size)
        val statuses = toolParts.mapNotNull { part ->
            part.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.let {
                runCatching {
                    JsonInstant.parseToJsonElement(it.text).jsonObject["status"]?.jsonPrimitive?.content
                }.getOrNull()
            }
        }
        assertTrue("outcome_unknown" in statuses)
        assertTrue("unavailable" in statuses)
        assertEquals(listOf("gated_tool"), log["tools_used"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `error result keeps partial stats without fabricating fields`() = runBlocking {
        val harness = Harness(
            listOf(ScriptedAttempt(emptyList(), errorAfter = IllegalStateException("hard fail")))
        )
        val parts = harness.runSubagent()
        val json = resultJson(parts)
        assertEquals("error", json["status"]!!.jsonPrimitive.content)
        assertEquals("hard fail", json["error"]!!.jsonPrimitive.content)
        // 空产物如实记录, 不把空 edited_paths/tools_used 改写为别的状态
        assertTrue(json["tools_used"]!!.jsonArray.isEmpty())
        assertTrue(json["edited_paths"]!!.jsonArray.isEmpty())
        assertEquals(0, json["step_count"]!!.jsonPrimitive.intOrNull)
    }
}
