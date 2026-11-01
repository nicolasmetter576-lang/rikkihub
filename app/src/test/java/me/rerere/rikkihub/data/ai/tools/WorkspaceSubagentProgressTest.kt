package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkihub.data.ai.GenerationChunk
import me.rerere.rikkihub.data.ai.GenerationRunStats
import me.rerere.rikkihub.data.ai.updateToolPartMetadataAt
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class WorkspaceSubagentProgressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- 契约: SubagentRunResult / encodeSubagentResult 新字段 ----

    @Test
    fun `result json carries contract fields and stays optional`() {
        val result = SubagentRunResult(
            status = "ok",
            summary = "done",
            toolsUsed = emptyList(),
            editedPaths = emptyList(),
            truncated = false,
            logPath = "/subagent_runs/abc.json",
            stepCount = 3,
            title = "t",
            modelId = "gpt-5",
            elapsedMs = 12_345,
            totalTokens = 9_999,
            startedAtEpochMs = 1_000,
            completedAtEpochMs = 2_000,
        )
        val json = encodeSubagentResult(result)
        assertEquals("gpt-5", json["model_id"]!!.jsonPrimitive.content)
        assertEquals(12_345L, json["elapsed_ms"]!!.jsonPrimitive.longOrNull)
        assertEquals(9_999L, json["total_tokens"]!!.jsonPrimitive.longOrNull)
        assertEquals(1_000L, json["started_at_epoch_ms"]!!.jsonPrimitive.longOrNull)
        assertEquals(2_000L, json["completed_at_epoch_ms"]!!.jsonPrimitive.longOrNull)

        // 可缺省: 不带新字段时输出里干脆没有 key, 旧读取方不受影响
        val legacy = encodeSubagentResult(
            result.copy(modelId = null, elapsedMs = null, totalTokens = null,
                startedAtEpochMs = null, completedAtEpochMs = null)
        )
        assertFalse(legacy.containsKey("model_id"))
        assertFalse(legacy.containsKey("elapsed_ms"))
        assertFalse(legacy.containsKey("total_tokens"))
        assertFalse(legacy.containsKey("started_at_epoch_ms"))
        assertFalse(legacy.containsKey("completed_at_epoch_ms"))
    }

    @Test
    fun `subagent metadata is backward and forward compatible`() {
        // 旧会话里持久化的 metadata (无新字段) 能解析, 新字段为 null
        val legacyJson = JsonInstant.decodeFromJsonElement<JsonObject>(
            JsonInstant.parseToJsonElement("""{"title":"t","status":"ok","logPath":"/tool_outputs/a.json"}""")
        )
        val legacy = JsonInstant.decodeFromJsonElement<SubagentMetadata>(legacyJson)
        assertEquals("t", legacy.title)
        assertEquals("ok", legacy.status)
        assertNull(legacy.modelId)
        assertNull(legacy.elapsedMs)
        assertNull(legacy.totalTokens)
        assertNull(legacy.startedAtEpochMs)
        assertNull(legacy.completedAtEpochMs)

        // 新 metadata 序列化后新字段存在, 且能往返
        val meta = SubagentMetadata(
            title = "t",
            status = "running",
            modelId = "m",
            elapsedMs = 7,
            totalTokens = 42,
            startedAtEpochMs = 1,
        )
        val roundTrip = JsonInstant.decodeFromJsonElement<SubagentMetadata>(meta.toMetadata())
        assertEquals("m", roundTrip.modelId)
        assertEquals(42L, roundTrip.totalTokens)
        assertEquals("running", roundTrip.status)
    }

    // ---- token / step 统计口径: 事件驱动为准, transcript 辅助函数只作兼容参考 ----

    @Suppress("DEPRECATION")
    @Test
    fun `merged assistant transcript distorts legacy message based stats`() {
        // 真实形态: 3 个生成步合并成同一条 assistant 消息, usage 只留末轮值。
        val mergedAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "t1", toolName = "a", input = "{}", output = listOf(UIMessagePart.Text("r1"))),
                UIMessagePart.Tool(toolCallId = "t2", toolName = "b", input = "{}", output = listOf(UIMessagePart.Text("r2"))),
                UIMessagePart.Text("final"),
            ),
            usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )
        val messages = listOf(UIMessage.user("seed"), mergedAssistant)

        // 旧口径 (已弃用, 仅证明失真): 3 步只数到 1 条 assistant
        assertEquals(1, subagentNewAssistantTurns(messages).size)
        assertFalse(subagentHitStepLimit(messages, SUBAGENT_MAX_STEPS))

        // 新口径: 事件驱动统计反映真实步数与累计 token
        val stats = GenerationRunStats()
        stats.onChunk(GenerationChunk.StepFinished(0, usage = TokenUsage(10, 5, totalTokens = 15)))
        stats.onChunk(GenerationChunk.StepFinished(1, usage = null))
        stats.onChunk(GenerationChunk.StepFinished(2, usage = TokenUsage(30, 15, totalTokens = 45)))
        stats.onChunk(GenerationChunk.GenerationEnd(GenerationChunk.GenerationEnd.Reason.COMPLETED))
        assertEquals(3, stats.stepCount)
        assertEquals(60L, stats.totalTokens)
        assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, stats.endReason)
    }

    @Test
    fun `run stats never reports zero tokens when no usage was emitted`() {
        val stats = GenerationRunStats()
        stats.onChunk(GenerationChunk.StepFinished(0, usage = null))
        assertNull(stats.totalTokens)
        assertEquals(1, stats.stepCount)
    }

    // ---- 进度发布: 按 part 下标精确定位, 并发/重复 id 调用互不串 ----

    @Test
    fun `publish metadata updates only the targeted part index even with duplicate ids`() {
        // 两个调用恰好同 toolCallId (空 id 或跨轮复用已执行 call 都会得到这种形态):
        // 按 id 的写回无法区分, 按下标定位必须各写各的。
        val toolA = UIMessagePart.Tool(toolCallId = "call-same", toolName = "subagent", input = "{}")
        val toolB = UIMessagePart.Tool(toolCallId = "call-same", toolName = "subagent", input = "{}")
        var messages = listOf(
            UIMessage.user("go"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(toolA, toolB)),
        )

        // 两个并发 subagent 调用的独立发布通道 (与生产 ToolExecutionContext 回调同构,
        // 定位参数是选取时记录的 messageIndex/partIndex/toolCallId 三元组)
        val publishedA = mutableListOf<JsonObject>()
        val ctxA = ToolExecutionContext("call-same") { meta ->
            messages = updateToolPartMetadataAt(messages, 1, 0, "call-same", meta)
            publishedA += meta
        }
        val publishedB = mutableListOf<JsonObject>()
        val ctxB = ToolExecutionContext("call-same") { meta ->
            messages = updateToolPartMetadataAt(messages, 1, 1, "call-same", meta)
            publishedB += meta
        }

        runBlocking {
            ctxA.publishMetadata(SubagentMetadata(status = "running", title = "A", stepCount = 1).toMetadata())
            ctxB.publishMetadata(SubagentMetadata(status = "running", title = "B", stepCount = 5).toMetadata())
            ctxA.publishMetadata(SubagentMetadata(status = "ok", title = "A", stepCount = 2).toMetadata())
        }

        val parts = messages.last().parts
        val metaA = (parts[0] as UIMessagePart.Tool).metadataAs<SubagentMetadata>()
        val metaB = (parts[1] as UIMessagePart.Tool).metadataAs<SubagentMetadata>()
        assertEquals("ok", metaA?.status)
        assertEquals(2, metaA?.stepCount)
        assertEquals("running", metaB?.status)
        assertEquals(5, metaB?.stepCount)
        assertEquals(2, publishedA.size)
        assertEquals(1, publishedB.size)

        // 无关 part / 消息不受影响
        assertEquals("go", messages.first().toText())
        assertEquals(2, messages.size)

        // 下标越界不产生改动; id 校验不过同样原样返回 (不做模糊 dispatch)
        val untouched = updateToolPartMetadataAt(messages, 1, 9, "call-same", buildJsonObject { put("x", 1) })
        assertEquals(messages, untouched)
        val mismatched = updateToolPartMetadataAt(messages, 1, 0, "other-id", buildJsonObject { put("x", 1) })
        assertEquals(messages, mismatched)
    }

    // ---- 日志 schema v2: 保留 reasoning 与工具输入输出, 兼容 legacy ----

    @Test
    fun `log file keeps reasoning and full tool io`() {
        val dir = tempFolder.newFolder("subagent_runs")
        val request = SubagentArgs(prompt = "do it", description = "demo", context = null)
        val toolCall = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "workspace_shell",
            input = """{"command":"ls"}""",
            output = listOf(UIMessagePart.Text("""{"exitCode":0}""")),
        )
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("thinking..."),
                toolCall,
                UIMessagePart.Text("final report"),
            ),
            usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )
        val transcript = listOf(UIMessage.user("do it"), assistant)
        val result = SubagentRunResult(
            status = "ok",
            summary = "final report",
            toolsUsed = listOf("workspace_shell"),
            editedPaths = emptyList(),
            truncated = false,
            logPath = "/subagent_runs/run-1.json",
            stepCount = 1,
            title = "demo",
            modelId = "m",
            elapsedMs = 1,
            totalTokens = 15,
            startedAtEpochMs = 10,
            completedAtEpochMs = 20,
        )

        writeSubagentLogFile(dir, "run-1", request, transcript, result)

        val file = dir.resolve("run-1.json")
        assertTrue(file.isFile)
        assertFalse(dir.resolve("run-1.json.tmp").exists()) // 原子替换不残留 tmp

        val obj = JsonInstant.parseToJsonElement(file.readText()).jsonObject
        assertEquals(3, obj["version"]!!.jsonPrimitive.intOrNull)
        assertEquals("m", obj["model_id"]!!.jsonPrimitive.content)
        assertEquals(15L, obj["total_tokens"]!!.jsonPrimitive.longOrNull)

        // v2 messages: 完整 UIMessage, 保留 reasoning / tool 输入输出 / usage
        val decoded = JsonInstant.decodeFromJsonElement<List<UIMessage>>(obj["messages"]!!)
        assertEquals(2, decoded.size)
        val decodedAssistant = decoded[1]
        assertEquals("thinking...", decodedAssistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        val decodedTool = decodedAssistant.getTools().single()
        assertEquals("""{"command":"ls"}""", decodedTool.input)
        assertEquals("""{"exitCode":0}""", (decodedTool.output.single() as UIMessagePart.Text).text)
        assertEquals(15, decodedAssistant.usage?.totalTokens)

        // legacy transcript 仍然可读, 且新增 reasoning 字段
        val legacyTranscript = obj["transcript"]!!.jsonArray
        val legacyAssistant = legacyTranscript[1].jsonObject
        assertEquals("ASSISTANT", legacyAssistant["role"]!!.jsonPrimitive.content)
        assertEquals("thinking...", legacyAssistant["reasoning"]!!.jsonPrimitive.content)
        assertEquals("workspace_shell", legacyAssistant["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy log without messages is still decodable`() {
        // 旧版 (无 version / 无 messages / 无 reasoning) 至少能读出 status 与 transcript
        val dir = tempFolder.newFolder("legacy")
        val file = dir.resolve("old.json")
        file.writeText(
            """{"prompt":"p","status":"ok","summary":"s","transcript":[{"role":"USER","text":"p"}]}"""
        )
        val obj = JsonInstant.parseToJsonElement(file.readText()).jsonObject
        assertNull(obj["version"])
        assertNull(obj["messages"])
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals(1, obj["transcript"]!!.jsonArray.size)
    }

    // ---- 取消 / 崩溃收尾 ----

    @Test
    fun `interrupted sweep only touches running logs`() {
        val dir = tempFolder.newFolder("runs")
        val running = dir.resolve("a.json").apply {
            writeText("""{"status":"running","prompt":"p","started_at_epoch_ms":100}""")
            setLastModified(500L)
        }
        val startedThisProcess = dir.resolve("new.json").apply {
            writeText("""{"status":"running","started_at_epoch_ms":1500}""")
            setLastModified(1500L)
        }
        val finished = dir.resolve("b.json").apply {
            writeText("""{"status":"ok","prompt":"p"}""")
        }
        val cancelled = dir.resolve("c.json").apply {
            writeText("""{"status":"cancelled","prompt":"p"}""")
        }
        val garbage = dir.resolve("d.json").apply { writeText("not json") }

        val patched = markStaleRunningSubagentLogs(dir, nowMillis = 999L)
        assertEquals(1, patched)

        val obj = JsonInstant.parseToJsonElement(running.readText()).jsonObject
        assertEquals("interrupted", obj["status"]!!.jsonPrimitive.content)
        assertEquals(500L, obj["completed_at_epoch_ms"]!!.jsonPrimitive.longOrNull)
        assertEquals(400L, obj["elapsed_ms"]!!.jsonPrimitive.longOrNull)
        assertNotNull(obj["error"])
        assertEquals("running", JsonInstant.parseToJsonElement(startedThisProcess.readText()).jsonObject["status"]!!.jsonPrimitive.content)

        assertEquals("ok", JsonInstant.parseToJsonElement(finished.readText()).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("cancelled", JsonInstant.parseToJsonElement(cancelled.readText()).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("not json", garbage.readText())
    }

    @Test
    fun `result parts keep output and metadata in the same terminal state`() {
        val cancelled = SubagentRunResult(
            status = "cancelled",
            summary = "Subagent was cancelled.",
            toolsUsed = emptyList(),
            editedPaths = emptyList(),
            truncated = false,
            logPath = "/subagent_runs/x.json",
            stepCount = 2,
            title = "t",
            error = "Subagent was cancelled.",
            modelId = "m",
            elapsedMs = 100,
            completedAtEpochMs = 50,
        )
        val parts = resultParts(cancelled)
        val text = parts.filterIsInstance<UIMessagePart.Text>().single()
        val outputJson = JsonInstant.parseToJsonElement(text.text).jsonObject
        val meta = text.metadataAs<SubagentMetadata>()
        assertEquals("cancelled", outputJson["status"]!!.jsonPrimitive.content)
        assertEquals("cancelled", meta?.status)
        assertEquals(outputJson["log_path"]!!.jsonPrimitive.content, meta?.logPath)
        assertEquals(100L, meta?.elapsedMs)
    }

    @Test
    fun `early failures publish the same terminal metadata as the output`() = runBlocking {
        var published: JsonObject? = null
        val context = ToolExecutionContext("invalid") { published = it }
        val parts = failParts("prompt_too_long", "too long", "task", context)
        assertEquals(parts.single().metadata, published)
        assertEquals("prompt_too_long", parts.single().metadataAs<SubagentMetadata>()?.status)
    }

    @Test
    fun `cancellation before first response preserves the run link and freezes time`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "early", toolName = "subagent", input = "{}",
            metadata = SubagentMetadata(
                status = "running", stepCount = 0, logPath = "/subagent_runs/early.json",
                startedAtEpochMs = 100, elapsedMs = 0, modelId = "model",
            ).toMetadata(),
        )
        val ended = finishInterruptedSubagentTool(tool, tempFolder.newFolder("early"), 1100)!!
        val metadata = ended.metadataAs<SubagentMetadata>()!!
        assertEquals("cancelled", metadata.status)
        assertEquals(1000L, metadata.elapsedMs)
        assertEquals("/subagent_runs/early.json", metadata.logPath)
        assertEquals(ended.metadata, ended.output.single().metadata)
    }

    @Test
    fun `cancellation after completion restores real terminal result from log`() {
        val dir = tempFolder.newFolder("completed")
        dir.resolve("run.json").writeText("""{
            "status":"ok", "summary":"done", "step_count":3, "total_tokens":4200,
            "elapsed_ms":1250, "completed_at_epoch_ms":2250
        }""")
        val tool = UIMessagePart.Tool(
            toolCallId = "race", toolName = "renamed_subagent", input = "{}",
            metadata = SubagentMetadata(
                status = "running", stepCount = 2, logPath = "/subagent_runs/run.json",
                startedAtEpochMs = 1000, modelId = "model",
            ).toMetadata(),
        )
        val ended = finishInterruptedSubagentTool(tool, dir, 9999)!!
        val metadata = ended.metadataAs<SubagentMetadata>()!!
        assertEquals("ok", metadata.status)
        assertEquals(3, metadata.stepCount)
        assertEquals(4200L, metadata.totalTokens)
        assertEquals(1250L, metadata.elapsedMs)
        assertEquals("model", metadata.modelId)
        assertEquals(ended.metadata, ended.output.single().metadata)
        val json = JsonInstant.parseToJsonElement((ended.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals("/subagent_runs/run.json", json["log_path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stale logs are pruned by mtime only`() {
        val dir = tempFolder.newFolder("runs2")
        val old = dir.resolve("old.json").apply { writeText("{}") }
        val fresh = dir.resolve("fresh.json").apply { writeText("{}") }
        val now = System.currentTimeMillis()
        assertTrue(old.setLastModified(now - SUBAGENT_RUN_TTL_MILLIS - 1_000))
        fresh.setLastModified(now)

        pruneStaleSubagentLogs(dir, nowMillis = now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
