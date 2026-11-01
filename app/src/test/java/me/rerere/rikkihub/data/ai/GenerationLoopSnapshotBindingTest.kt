package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolIdentity
import me.rerere.ai.ui.WorkspaceToolMode
import me.rerere.rikkihub.data.ai.tools.ChatToolSnapshot
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.workspace.WorkspaceChangeSpec
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * 请求一致快照刷新 + 执行侧按最新快照取工具 + 输出能力的定向回归（2.7.0 起无签名绑定）。
 * 全部离线（RecordingProvider 回放 chunk 并记录每 attempt 的 params）。
 *
 * 注意：每个含工具步的脚本之后都要跟一条收尾 attempt（循环会在工具执行后
 * 发起下一个 provider 请求）；快照 provider 的调用序为
 * [step0 请求, 审批前验证, 执行前, step1 请求, ...]。
 */
class GenerationLoopSnapshotBindingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val conversationId = Uuid.random()

    // ---------------------------------------------------------------
    // 记录每个 attempt params 的脚本 provider（ScriptedProvider 是 final，
    // 这里自带一个等价的简化实现，不碰共享 fixture）
    // ---------------------------------------------------------------
    private class RecordingProvider(
        private val attempts: ArrayDeque<ScriptedAttempt>,
        private val results: ArrayDeque<TextGenerationResult> = ArrayDeque(),
    ) : Provider<ProviderSetting> {
        val paramsHistory = mutableListOf<TextGenerationParams>()
        val nonStreamParamsHistory = mutableListOf<TextGenerationParams>()
        /** 每次流式请求实际发送的消息列表（含 system prompt），供断言能力提示同源。 */
        val messagesHistory = mutableListOf<List<UIMessage>>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            nonStreamParamsHistory += params
            return results.removeFirstOrNull()
                ?: error("RecordingProvider: no scripted generateText result left")
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = flow {
            paramsHistory += params
            messagesHistory += messages
            val attempt = attempts.removeFirstOrNull()
                ?: error("RecordingProvider: no scripted stream attempt left")
            attempt.chunks.forEach { emit(it) }
            attempt.errorAfter?.let { throw it }
        }

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = error("not used in tests")
    }

    private inner class Harness(
        val filesDir: File,
        attempts: List<ScriptedAttempt>,
        results: List<TextGenerationResult> = emptyList(),
    ) {
        val provider = RecordingProvider(ArrayDeque(attempts), ArrayDeque(results))
        val model: Model = testChatModel()
        val settings: Settings = testSettings(model)
        val loop = GenerationLoop(
            context = FakeContext(filesDir),
            providerManager = ProviderManager(okhttp3.OkHttpClient(), FakeContext(filesDir)).apply {
                registerProvider("openai", provider)
            },
            json = JsonInstant,
        ).apply {
            statusStrings = StatusStringResolver { resId, args ->
                "status:$resId" + if (args.isNotEmpty()) ":" + args.joinToString(",") else ""
            }
        }
    }

    private fun harness(
        attempts: List<ScriptedAttempt> = emptyList(),
        results: List<TextGenerationResult> = emptyList(),
    ) = Harness(tempFolder.newFolder(), attempts, results)

    private fun wsIdentity(workspaceId: String = "ws-1", stableId: String = "workspace_shell") =
        WorkspaceToolIdentity(
            workspaceId = workspaceId,
            stableId = stableId,
            implementationId = stableId,
            mode = WorkspaceToolMode.NORMAL,
            viewFingerprint = "root:files:mode:normal",
        )

    private fun wsTool(
        name: String,
        identity: WorkspaceToolIdentity = wsIdentity(),
        body: suspend (JsonElement) -> List<UIMessagePart> = {
            listOf(UIMessagePart.Text("""{"ok":true}"""))
        },
    ) = Tool(
        name = name,
        description = "workspace tool",
        parameters = { null },
        workspaceIdentity = identity,
        execute = body,
    )

    private fun snapshotOf(vararg tools: Tool, caps: WorkspaceRequestCapabilities? = null) =
        ChatToolSnapshot(
            tools = tools.toList(),
            bindings = tools.associateBy { it.name },
            workspaceCapabilities = caps,
        )

    /** 固定顺序 + 末元素兜底重复（快照调用数随实现细节浮动：每请求构建 + 审批前
     *  + 执行前 + 截断能力复查都会读一次，末尾余量防欠账）。 */
    private fun snapshotQueue(first: ChatToolSnapshot, vararg rest: ChatToolSnapshot): suspend () -> ChatToolSnapshot {
        val deque = ArrayDeque(listOf(first) + rest.toList() + List(16) { rest.lastOrNull() ?: first })
        return { deque.removeFirst() }
    }

    private suspend fun GenerationLoop.collectAll(
        settings: Settings,
        model: Model,
        snapshotProvider: (suspend () -> ChatToolSnapshot)? = null,
        initialMessages: List<UIMessage> = listOf(UIMessage.user("hi")),
        assistant: Assistant = testAssistant(),
        inputTransformers: List<InputMessageTransformer> = emptyList(),
    ): List<GenerationChunk> = generateTextObserved(
        settings = settings,
        model = model,
        messages = initialMessages,
        assistant = assistant,
        inputTransformers = inputTransformers,
        tools = emptyList(),
        maxSteps = 8,
        conversationId = conversationId,
        toolSnapshotProvider = snapshotProvider,
    ).toList()

    private fun List<GenerationChunk>.transcript() =
        filterIsInstance<GenerationChunk.Messages>().last().messages

    private fun List<GenerationChunk>.endReason() =
        filterIsInstance<GenerationChunk.GenerationEnd>().last().reason

    private fun UIMessagePart.Tool.firstOutputJson(): JsonObject? = runCatching {
        JsonInstant.parseToJsonElement(
            output.filterIsInstance<UIMessagePart.Text>().first().text
        ).jsonObject
    }.getOrNull()

    private fun statusOf(part: UIMessagePart.Tool): String? =
        part.firstOutputJson()?.get("status")?.jsonPrimitive?.content

    private fun reasonOf(part: UIMessagePart.Tool): String? =
        part.firstOutputJson()?.get("reason")?.jsonPrimitive?.content

    // ---------------------------------------------------------------
    // 下一 step 重取快照
    // ---------------------------------------------------------------
    @Test
    fun `snapshot refetched for each next step request`() = runBlocking {
        val toolA = wsTool("alpha")
        val toolB = wsTool("beta")
        val h = harness(listOf(toolStepScript("c1", "alpha", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(
            h.settings,
            model = h.model,
            snapshotProvider = snapshotQueue(snapshotOf(toolA), snapshotOf(toolB)),
        )
        assertEquals(2, h.provider.paramsHistory.size)
        assertEquals(listOf("alpha"), h.provider.paramsHistory[0].tools.map { it.name })
        assertEquals(listOf("beta"), h.provider.paramsHistory[1].tools.map { it.name })
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    // ---------------------------------------------------------------
    // 网络重试 attempt 重取快照
    // ---------------------------------------------------------------
    @Test
    fun `snapshot refetched per network retry attempt`() = runBlocking {
        val toolA = wsTool("alpha")
        val toolB = wsTool("beta")
        val h = harness(
            listOf(
                ScriptedAttempt(chunks = emptyList(), errorAfter = IOException("conn reset")),
                toolStepScript("c1", "beta", "{}"),
                textStepScript("done"),
            )
        )
        val chunks = h.loop.collectAll(
            h.settings,
            model = h.model,
            snapshotProvider = snapshotQueue(snapshotOf(toolA), snapshotOf(toolB)),
        )
        assertEquals(3, h.provider.paramsHistory.size) // fail + retry + step2
        assertEquals(listOf("alpha"), h.provider.paramsHistory[0].tools.map { it.name })
        assertEquals(listOf("beta"), h.provider.paramsHistory[1].tools.map { it.name })
        val part = chunks.transcript().last().getTools().first()
        assertTrue(part.isExecuted)
        assertNull(statusOf(part))
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    // ---------------------------------------------------------------
    // T1: 签发冻结——绑定用签发请求的快照身份；执行时取最新表，
    // 身份漂移（改绑/alias 复用）一律 unavailable
    // ---------------------------------------------------------------
    @Test
    fun `execution uses the latest snapshot tool for the requested name`() = runBlocking {
        val idA = wsIdentity(workspaceId = "ws-A")
        val idB = wsIdentity(workspaceId = "ws-B")
        var executedB = false
        val toolB = wsTool("ws_tool", idB) { executedB = true; listOf(UIMessagePart.Text("{}")) }
        val h = harness(listOf(toolStepScript("c1", "ws_tool", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(
            h.settings,
            model = h.model,
            snapshotProvider = snapshotQueue(
                snapshotOf(wsTool("ws_tool", idA)), // 请求
                snapshotOf(toolB),                  // 审批前/执行前：最新表
            ),
        )
        val part = chunks.transcript().first { it.getTools().isNotEmpty() }.getTools().first()
        assertTrue(part.isExecuted)
        assertNull(statusOf(part))
        assertTrue(executedB)
    }

    @Test
    fun `workspace part executes when identity stays the same`() = runBlocking {
        var executed = false
        val tool = wsTool("ws_tool") { executed = true; listOf(UIMessagePart.Text("""{"ran":1}""")) }
        val h = harness(listOf(toolStepScript("c1", "ws_tool", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(h.settings, h.model, snapshotProvider = snapshotQueue(snapshotOf(tool)))
        val part = chunks.transcript().first { it.getTools().isNotEmpty() }.getTools().first()
        assertTrue(executed)
        assertNull(statusOf(part))
    }

    // ---------------------------------------------------------------
    // 恢复 Approved：历史里的已批准调用（例如进程重启后批准）按当前工具执行
    // ---------------------------------------------------------------
    @Test
    fun `approved workspace part from history executes with the current tool`() = runBlocking {
        var executed = false
        val tool = wsTool("ws_tool") { executed = true; listOf(UIMessagePart.Text("{}")) }
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c1",
                    toolName = "ws_tool",
                    input = "{}",
                    approvalState = ToolApprovalState.Approved,
                )
            ),
        )
        val h = harness(listOf(textStepScript("done")))
        val chunks = h.loop.collectAll(
            h.settings,
            model = h.model,
            snapshotProvider = snapshotQueue(snapshotOf(tool)),
            initialMessages = listOf(UIMessage.user("hi"), pending),
        )
        val part = chunks.transcript().last().getTools().first()
        assertTrue(part.isExecuted)
        assertNull(statusOf(part))
        assertTrue(executed)
    }

    // ---------------------------------------------------------------
    // 请求发出后工具从最新表消失——不置 Pending、不执行，回灌 not found 错误
    // ---------------------------------------------------------------
    @Test
    fun `tool removed from latest snapshot is not executed nor left pending`() = runBlocking {
        var executed = false
        val tool = wsTool("ws_tool") { executed = true; listOf(UIMessagePart.Text("{}")) }
        val emptySnapshot = ChatToolSnapshot(tools = emptyList(), bindings = emptyMap())
        val h = harness(listOf(toolStepScript("c1", "ws_tool", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(
            h.settings,
            model = h.model,
            snapshotProvider = snapshotQueue(
                snapshotOf(tool), // 请求
                emptySnapshot,    // 审批前验证：工具已不在最新表
                emptySnapshot,    // 执行前：仍不在
                snapshotOf(tool), // step2 请求
            ),
        )
        val part = chunks.transcript().first { it.getTools().isNotEmpty() }.getTools().first()
        assertTrue(part.isExecuted)
        assertTrue(part.firstOutputJson()?.get("error")?.jsonPrimitive?.content.orEmpty().contains("not found"))
        assertTrue(!executed)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    // ---------------------------------------------------------------
    // 非 workspace 工具正常执行
    // ---------------------------------------------------------------
    @Test
    fun `plain non-workspace tool still executes under snapshot provider`() = runBlocking {
        var executed = false
        val plain = Tool(
            name = "plain_tool",
            description = "d",
            parameters = { null },
            execute = { executed = true; listOf(UIMessagePart.Text("""{"ok":2}""")) },
        )
        val h = harness(listOf(toolStepScript("c1", "plain_tool", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(h.settings, h.model, snapshotProvider = snapshotQueue(snapshotOf(plain)))
        assertTrue(executed)
        val part = chunks.transcript().first { it.getTools().isNotEmpty() }.getTools().first()
        assertNull(statusOf(part))
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    // ---------------------------------------------------------------
    // 安全 part 下标写回：两个工具各自回写自己的 part
    // ---------------------------------------------------------------
    @Test
    fun `multiple tool parts write back by index`() = runBlocking {
        val t1 = wsTool("t1") { listOf(UIMessagePart.Text("""{"which":"one"}""")) }
        val t2 = wsTool("t2") { listOf(UIMessagePart.Text("""{"which":"two"}""")) }
        val h = harness(
            listOf(
                ScriptedAttempt(
                    buildList {
                        add(StreamChunk.ToolCallStart("c1", "t1"))
                        add(StreamChunk.ToolCallDelta("c1", inputDelta = "{}"))
                        add(StreamChunk.ToolCallEnd("c1"))
                        add(StreamChunk.ToolCallStart("c2", "t2"))
                        add(StreamChunk.ToolCallDelta("c2", inputDelta = "{}"))
                        add(StreamChunk.ToolCallEnd("c2"))
                        add(StreamChunk.Finish("tool_calls"))
                    }
                ),
                textStepScript("done"),
            )
        )
        val chunks = h.loop.collectAll(h.settings, h.model, snapshotProvider = snapshotQueue(snapshotOf(t1, t2)))
        val tools = chunks.transcript().last().getTools()
        assertEquals(2, tools.size)
        assertEquals("one", tools[0].firstOutputJson()!!["which"]!!.jsonPrimitive.content)
        assertEquals("two", tools[1].firstOutputJson()!!["which"]!!.jsonPrimitive.content)
        assertEquals("c1", tools[0].toolCallId)
        assertEquals("c2", tools[1].toolCallId)
    }

    // ---------------------------------------------------------------
    // gate 范围取消 → outcome_unknown（不取消整次生成；已发布 metadata 保留）
    // ---------------------------------------------------------------
    @Test
    fun `gate policy cancellation maps to outcome_unknown and keeps metadata`() = runBlocking {
        val gate = WorkspaceRuntimeGate()
        val holdStarted = CompletableDeferred<Unit>()
        val meta = JsonObject(mapOf("k" to JsonPrimitive("v")))
        val tool = Tool(
            name = "gated_tool",
            description = "d",
            parameters = { null },
            execute = { error("execute path not used") },
            executeWithContext = { _: JsonElement, ctx: ToolExecutionContext ->
                gate.withOperation(
                    workspaceId = "ws-1",
                    kind = WorkspaceOperationKind.TOOL,
                    stableIds = setOf("workspace_shell"),
                ) {
                    ctx.publishMetadata(meta)
                    holdStarted.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val h = harness(listOf(toolStepScript("c1", "gated_tool", "{}"), textStepScript("after")))
        val collectJob = async {
            h.loop.collectAll(h.settings, h.model, snapshotProvider = snapshotQueue(snapshotOf(tool)))
        }
        holdStarted.await()
        gate.publishAndCancel("ws-1", WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
        val chunks = collectJob.await()
        val part = chunks.transcript().first { it.getTools().isNotEmpty() }.getTools().first()
        assertEquals("outcome_unknown", statusOf(part))
        assertEquals("true", part.firstOutputJson()?.get("started")?.jsonPrimitive?.content)
        assertEquals(meta, part.metadata)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    // ---------------------------------------------------------------
    // 非策略 CE（用户停止语义）原样上抛，不吞为工具结果
    // ---------------------------------------------------------------
    @Test
    fun `non policy cancellation propagates as cancellation`() = runBlocking {
        val tool = Tool(
            name = "cancel_tool",
            description = "d",
            parameters = { null },
            execute = { throw CancellationException("user stop") },
        )
        val h = harness(listOf(toolStepScript("c1", "cancel_tool", "{}"), textStepScript("done")))
        try {
            h.loop.collectAll(h.settings, h.model, snapshotProvider = snapshotQueue(snapshotOf(tool)))
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // 预期：整次生成取消，不写 cancelled 工具结果
        }
    }

    // ---------------------------------------------------------------
    // 输出截断按真实能力（无回读 → 明确截断不保存；有回读+挂载 → 落盘指引）
    // ---------------------------------------------------------------
    @Test
    fun `large output truncated without save when no readback tool`() = runBlocking {
        val big = "x".repeat(40 * 1024)
        val tool = wsTool("writer") { listOf(UIMessagePart.Text(big)) }
        val caps = WorkspaceRequestCapabilities(
            workspaceId = "ws-1",
            workspaceName = "ws",
            harnessMinimal = false,
            availableToolNames = mapOf("workspace_write_file" to "writer"),
            exposedTargets = setOf("/tool_outputs"),
            policyEpoch = 0,
        )
        val h = harness(listOf(toolStepScript("c1", "writer", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(h.settings, h.model, snapshotProvider = snapshotQueue(snapshotOf(tool, caps = caps)))
        val text = chunks.transcript().first { it.getTools().isNotEmpty() }.getTools().first()
            .output.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(text.contains("Tool output truncated"))
        assertTrue(text.contains("not saved"))
        val outDir = File(h.filesDir, "tool_outputs")
        assertTrue(!outDir.exists() || outDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `large output persisted when readback tool and tool_outputs mount exist`() = runBlocking {
        val big = "y".repeat(40 * 1024)
        val tool = wsTool("writer") { listOf(UIMessagePart.Text(big)) }
        val caps = WorkspaceRequestCapabilities(
            workspaceId = "ws-1",
            workspaceName = "ws",
            harnessMinimal = false,
            availableToolNames = mapOf(
                "workspace_shell" to "workspace_shell",
                "workspace_write_file" to "writer",
            ),
            exposedTargets = setOf("/tool_outputs"),
            policyEpoch = 0,
        )
        val h = harness(listOf(toolStepScript("c1", "writer", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(h.settings, h.model, snapshotProvider = snapshotQueue(snapshotOf(tool, caps = caps)))
        val text = chunks.transcript().first { it.getTools().isNotEmpty() }.getTools().first()
            .output.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(text.contains("Tool output truncated"))
        assertTrue(text.contains("/tool_outputs/"))
        val outDir = File(h.filesDir, "tool_outputs")
        assertTrue(outDir.isDirectory && outDir.listFiles()!!.isNotEmpty())
    }

    // ---------------------------------------------------------------
    // 快照时效复查：构建完成但策略已变 → 有界整份重建；持续漂移 →
    // 本地错误终止（不经网络重试、不发半更新请求）
    // ---------------------------------------------------------------
    @Test
    fun `stale snapshot during request build is rebuilt before send`() = runBlocking {
        var builds = 0
        var checks = 0
        val toolA = wsTool("alpha")
        val toolB = wsTool("beta")
        val snapA = snapshotOf(toolA).copy(isCurrent = { ++checks; checks >= 2 })
        val snapB = snapshotOf(toolB).copy(isCurrent = { true })
        val snapshots = ArrayDeque(listOf(snapA, snapB))
        val h = harness(listOf(textStepScript("done")))
        val chunks = h.loop.collectAll(
            h.settings,
            model = h.model,
            snapshotProvider = {
                builds++
                snapshots.removeFirstOrNull() ?: snapB
            },
        )
        // 第一份快照在构建后已失效 → 整份重建后才发请求
        assertEquals(2, builds)
        assertEquals(1, h.provider.paramsHistory.size)
        assertEquals(listOf("beta"), h.provider.paramsHistory[0].tools.map { it.name })
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    @Test
    fun `continuously stale snapshot fails locally without sending request`() = runBlocking {
        var builds = 0
        val stale = snapshotOf(wsTool("alpha")).copy(isCurrent = { false })
        val h = harness(listOf(textStepScript("done")))
        val failure = runCatching {
            h.loop.collectAll(
                h.settings,
                model = h.model,
                snapshotProvider = { builds++; stale },
            )
        }.exceptionOrNull()
        // 有界上限：MAX_STALE_SNAPSHOT_REBUILDS=3 → 1+3 次构建后本地失败
        assertEquals(4, builds)
        assertNotNull(failure)
        assertTrue(failure is StaleToolSnapshotException)
        // 未发出任何 provider 请求（也不按网络错误重试）
        assertTrue(h.provider.paramsHistory.isEmpty())
    }

    // ---------------------------------------------------------------
    // 执行前快照刷新失败：明确受限 unavailable，不落泛化 error 也不回落旧表
    // ---------------------------------------------------------------
    @Test
    fun `execution snapshot refresh failure writes unavailable not generic error`() = runBlocking {
        var executed = false
        val tool = wsTool("ws_tool") { executed = true; listOf(UIMessagePart.Text("{}")) }
        var calls = 0
        val h = harness(listOf(toolStepScript("c1", "ws_tool", "{}"), textStepScript("done")))
        val collected = mutableListOf<GenerationChunk>()
        runCatching {
            h.loop.generateTextObserved(
                settings = h.settings,
                model = h.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
                tools = emptyList(),
                conversationId = conversationId,
                toolSnapshotProvider = {
                    calls++
                    // build + 审批前验证成功，执行前取快照开始失败
                    if (calls <= 2) snapshotOf(tool) else error("snapshot backend down")
                },
                // Isolate the execution refusal from a separate next-request failure;
                // provider failures can legitimately drop buffered downstream events.
                maxSteps = 1,
            ).collect { collected += it }
        }
        val part = collected.filterIsInstance<GenerationChunk.Messages>()
            .last().messages.last().getTools().first()
        assertEquals("unavailable", statusOf(part))
        assertEquals("SNAPSHOT_REFRESH_FAILED", reasonOf(part))
        assertTrue(!executed)
    }

    // ---------------------------------------------------------------
    // 每请求能力提示同源：两次 step 的 reminderPrompt 变化反映到实际发送
    // 的消息上（transformer 只读 ctx.workspaceCapabilities，不查 DB）。
    // ---------------------------------------------------------------
    @Test
    fun `per-request reminder prompt reflects latest snapshot capabilities`() = runBlocking {
        // 轻量 transformer 替身：与生产 WorkspaceReminderTransformer 同契约——
        // 只消费 ctx.workspaceCapabilities.reminderPrompt，不读 DB/Settings。
        val reminderEcho = object : InputMessageTransformer {
            override suspend fun transform(
                ctx: TransformerContext,
                messages: List<UIMessage>,
            ): List<UIMessage> {
                val prompt = ctx.workspaceCapabilities?.reminderPrompt ?: return messages
                return listOf(
                    UIMessage.system(prompt).copy(isSynthetic = true)
                ) + messages
            }
        }
        val toolA = wsTool("alpha")
        val capsA = WorkspaceRequestCapabilities(
            workspaceId = "ws-1",
            workspaceName = "ws",
            harnessMinimal = false,
            availableToolNames = mapOf("alpha" to "alpha"),
            exposedTargets = emptySet(),
            reminderPrompt = "REMINDER_V1",
        )
        val capsB = capsA.copy(reminderPrompt = "REMINDER_V2")
        val h = harness(listOf(toolStepScript("c1", "alpha", "{}"), textStepScript("done")))
        val chunks = h.loop.collectAll(
            h.settings,
            model = h.model,
            snapshotProvider = snapshotQueue(snapshotOf(toolA, caps = capsA), snapshotOf(toolA, caps = capsB)),
            inputTransformers = listOf(reminderEcho),
        )
        assertEquals(2, h.provider.messagesHistory.size)
        assertTrue(h.provider.messagesHistory[0].first().toText().contains("REMINDER_V1"))
        assertTrue(h.provider.messagesHistory[1].first().toText().contains("REMINDER_V2"))
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.endReason())
    }

    // ---------------------------------------------------------------
    // 快照刷新失败：明确受限/报错，不回落旧 tools 复活已关能力
    // ---------------------------------------------------------------
    @Test
    fun `snapshot refresh failure marks tools unavailable then fails step`() = runBlocking {
        // channelFlow/flowOn may discard in-flight messages on upstream failure (documented
        // production contract). Observe the unavailable emission before inducing the next
        // request failure, rather than depending on IO/collector scheduling luck.
        val unavailableObserved = CompletableDeferred<Unit>()
        var executed = false
        val tool = wsTool("ws_tool") { executed = true; listOf(UIMessagePart.Text("{}")) }
        var calls = 0
        val h = harness(listOf(toolStepScript("c1", "ws_tool", "{}"), textStepScript("done")))
        val collected = mutableListOf<GenerationChunk>()
        val failure = runCatching {
            h.loop.generateTextObserved(
                settings = h.settings,
                model = h.model,
                messages = listOf(UIMessage.user("hi")),
                assistant = testAssistant(),
                tools = emptyList(),
                conversationId = conversationId,
                toolSnapshotProvider = {
                    calls++
                    if (calls >= 3) kotlinx.coroutines.withTimeout(2_000) { unavailableObserved.await() }
                    if (calls == 1) snapshotOf(tool) else error("snapshot backend down")
                },
            ).collect { chunk ->
                collected += chunk
                if (chunk is GenerationChunk.Messages && chunk.messages.lastOrNull()?.getTools()
                        ?.any { statusOf(it) == "unavailable" } == true) {
                    unavailableObserved.complete(Unit)
                }
            }
        }.exceptionOrNull()
        // 审批前验证拿不到快照 → part 直接写 unavailable（已发射到 Messages）；
        // 之后 step1 请求再次取快照仍失败 → 生成以明确错误终止，未用旧表执行
        assertNotNull(failure)
        val part = collected.filterIsInstance<GenerationChunk.Messages>()
            .last().messages.last().getTools().first()
        assertEquals("unavailable", statusOf(part))
        assertEquals("SNAPSHOT_REFRESH_FAILED", reasonOf(part))
        assertTrue(!executed)
        assertTrue(calls >= 3)
    }
}
