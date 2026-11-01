package me.rerere.rikkihub.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubagentRunLogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `preset identity stays separate from the delegated task title`() {
        val metadata = SubagentMetadata(
            title = "Fix tests", presetId = "id", presetKey = "tester", presetName = "Tester", presetRevision = 3,
        )
        assertEquals("Fix tests", subagentTaskName(metadata, null, null))
        assertEquals("Tester (tester)", subagentPresetIdentity(metadata))
        assertEquals("tester", subagentPresetIdentity(metadata.copy(presetName = null)))
        assertEquals("Tester", subagentPresetIdentity(metadata.copy(presetKey = null)))
        assertNull(subagentPresetIdentity(SubagentMetadata(title = "Legacy task")))
    }

    @Test
    fun `snake case preset fields are parsed from output and run log`() {
        val json = """{"status":"ok","title":"Task","preset_id":"id","preset_key":"reviewer","preset_name":"Reviewer","preset_revision":7}"""
        val log = parseSubagentRunLog(json)!!
        assertEquals("id", log.presetId)
        assertEquals("reviewer", log.presetKey)
        assertEquals("Reviewer", log.presetName)
        assertEquals(7L, log.presetRevision)
        val metadata = legacySubagentMetadata(JsonInstant.parseToJsonElement(json) as JsonObject)
        assertEquals("Task", metadata.title)
        assertEquals("reviewer", metadata.presetKey)
        assertEquals("Reviewer", metadata.presetName)
        assertEquals(7L, metadata.presetRevision)
    }

    @Test
    fun `older logs remain readable without preset fields`() {
        val log = parseSubagentRunLog("""{"status":"ok","prompt":"Old task","transcript":[]}""")!!
        assertEquals("Old task", log.prompt)
        assertNull(log.presetId)
        assertNull(log.presetKey)
        assertNull(log.presetName)
        assertNull(log.presetRevision)
        val malformed = parseSubagentRunLog("""{"status":"ok","preset_revision":{},"preset_name":null}""")!!
        assertNull(malformed.presetRevision)
        assertNull(malformed.presetName)
    }

    @Test
    fun `new metadata survives serialization and wins over snake case preset fallback`() {
        val metadata = SubagentMetadata(title = "Live task", presetKey = "live_key", presetName = "Live role", presetRevision = 4)
        val tool = UIMessagePart.Tool(
            toolCallId = "preset-run", toolName = "subagent", input = "{}", metadata = metadata.toMetadata(),
        )
        val output = buildJsonObject {
            put("preset_key", "old_key")
            put("preset_name", "Old role")
            put("preset_revision", 2)
        }
        val resolved = subagentMetadata(tool, output)!!
        assertEquals(metadata, resolved)
    }

    @Test
    fun `legacy live metadata gains optional preset fields without replacing task stats`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "preset-run", toolName = "subagent", input = "{}",
            metadata = SubagentMetadata(title = "Live task", stepCount = 5).toMetadata(),
        )
        val output = buildJsonObject {
            put("title", "Output task")
            put("step_count", 2)
            put("preset_key", "tester")
            put("preset_name", "Tester")
            put("preset_revision", 9)
        }
        val resolved = subagentMetadata(tool, output)!!
        assertEquals("Live task", resolved.title)
        assertEquals(5, resolved.stepCount)
        assertEquals("tester", resolved.presetKey)
        assertEquals("Tester", resolved.presetName)
        assertEquals(9L, resolved.presetRevision)
    }

    @Test
    fun `output part supplements missing preset snapshot on legacy tool metadata`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "preset-run", toolName = "subagent", input = "{}",
            metadata = SubagentMetadata(title = "Task", status = "ok").toMetadata(),
            output = listOf(UIMessagePart.Text(
                text = "{}",
                metadata = SubagentMetadata(presetId = "role-id", presetKey = "developer", presetRevision = 6).toMetadata(),
            )),
        )
        val resolved = subagentMetadata(tool, null)!!
        assertEquals("Task", resolved.title)
        assertEquals("role-id", resolved.presetId)
        assertEquals("developer", resolved.presetKey)
        assertEquals(6L, resolved.presetRevision)
    }

    // ---- 标题行格式化 ----

    @Test
    fun `header line joins all five segments`() {
        assertEquals(
            "检查构建 · claude-sonnet-4 · 3 步 · 12 s · 4.2 k tok",
            subagentHeaderLine(
                taskName = "检查构建",
                modelId = "claude-sonnet-4",
                stepsLabel = "3 步",
                elapsedLabel = "12 s",
                tokensLabel = "4.2 k tok",
                fallback = "Subagent",
            )
        )
    }

    @Test
    fun `header line keeps unknown segments and falls back for blank name`() {
        assertEquals(
            "Subagent · — · 2 steps · — s · 0.5 k tok",
            subagentHeaderLine(
                taskName = " ",
                modelId = null,
                stepsLabel = "2 steps",
                elapsedLabel = null,
                tokensLabel = "0.5 k tok",
                fallback = "Subagent",
            )
        )
    }

    @Test
    fun `kilo tokens formats one decimal and unknown stays unknown`() {
        assertEquals("4.2", subagentKiloTokens(4_200))
        assertEquals("0.5", subagentKiloTokens(500))
        assertEquals("0.0", subagentKiloTokens(0))
        assertNull(subagentKiloTokens(null))
        assertNull(subagentKiloTokens(-5))
    }

    @Test
    fun `task name prefers metadata title then description then prompt`() {
        val meta = SubagentMetadata(title = "meta title")
        assertEquals("meta title", subagentTaskName(meta, "desc", "prompt"))
        assertEquals("desc", subagentTaskName(null, "desc", "prompt"))
        assertEquals("prompt", subagentTaskName(null, " ", "prompt"))
        assertNull(subagentTaskName(null, null, null))
    }

    @Test
    fun `task name flattens newlines and truncates`() {
        val long = "a".repeat(100)
        val name = subagentTaskName(null, null, "first\nsecond")
        assertEquals("first second", name)
        val truncated = subagentTaskName(null, null, long, maxChars = 10)
        assertEquals(11, truncated!!.length)
        assertTrue(truncated.endsWith("…"))
    }

    // ---- 运行状态与计时 ----

    @Test
    fun `running requires loading and unfinished tool`() {
        val running = SubagentMetadata(status = "running")
        assertTrue(subagentIsRunning(loading = true, isExecuted = false, metadata = running))
        assertTrue(subagentIsRunning(loading = true, isExecuted = false, metadata = null))
        // 重启后残留的 running metadata: loading=false 时不能继续当运行中
        assertFalse(subagentIsRunning(loading = false, isExecuted = false, metadata = running))
        assertFalse(subagentIsRunning(loading = true, isExecuted = true, metadata = running))
        assertFalse(
            subagentIsRunning(
                loading = true,
                isExecuted = false,
                metadata = SubagentMetadata(status = "ok"),
            )
        )
    }

    @Test
    fun `elapsed seconds keeps ticking between progress updates`() {
        val meta = SubagentMetadata(status = "running", elapsedMs = 12_345, startedAtEpochMs = 1_000)
        assertEquals(998L, subagentElapsedSeconds(meta, isRunning = true, nowMs = 999_000))
        assertEquals(12L, subagentElapsedSeconds(meta, isRunning = false, nowMs = 999_000))
        // 无 elapsedMs 时由 startedAt 推算
        val noElapsed = SubagentMetadata(status = "running", startedAtEpochMs = 10_000)
        assertEquals(12L, subagentElapsedSeconds(noElapsed, isRunning = true, nowMs = 22_500))
        // 时钟异常不出现负数
        assertEquals(0L, subagentElapsedSeconds(noElapsed, isRunning = true, nowMs = 5_000))
    }

    @Test
    fun `elapsed seconds freezes at terminal values`() {
        // 终态优先用发布时的 elapsedMs, 不随 nowMs 增长
        val done = SubagentMetadata(status = "ok", elapsedMs = 5_000, startedAtEpochMs = 100)
        assertEquals(5L, subagentElapsedSeconds(done, isRunning = false, nowMs = Long.MAX_VALUE / 2))
        // 无 elapsedMs 时用 completed-started 之差
        val ranged = SubagentMetadata(startedAtEpochMs = 1_000, completedAtEpochMs = 4_500)
        assertEquals(3L, subagentElapsedSeconds(ranged, isRunning = false, nowMs = 0))
        // 负差值被钳制为 0
        val negative = SubagentMetadata(startedAtEpochMs = 5_000, completedAtEpochMs = 1_000)
        assertEquals(0L, subagentElapsedSeconds(negative, isRunning = false, nowMs = 0))
        // 完全没有计时信息
        assertNull(subagentElapsedSeconds(SubagentMetadata(status = "ok"), false, 0))
        assertNull(subagentElapsedSeconds(null, false, 0))
    }

    // ---- metadata 三来源合并 ----

    @Test
    fun `tool part metadata wins over output metadata and legacy json`() {
        val toolMeta = SubagentMetadata(title = "live", status = "running", stepCount = 3)
        val outputMeta = SubagentMetadata(title = "output", status = "ok")
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "subagent",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    text = "{}",
                    metadata = outputMeta.toMetadata(),
                )
            ),
            metadata = toolMeta.toMetadata(),
        )
        val resolved = subagentMetadata(tool, buildJsonObject { put("title", "legacy") })
        assertEquals("live", resolved?.title)
        assertEquals(3, resolved?.stepCount)
    }

    @Test
    fun `output part metadata used when tool metadata absent`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "subagent",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    text = "{}",
                    metadata = SubagentMetadata(title = "from-output", modelId = "m1").toMetadata(),
                )
            ),
        )
        val resolved = subagentMetadata(tool, null)
        assertEquals("from-output", resolved?.title)
        assertEquals("m1", resolved?.modelId)
    }

    @Test
    fun `legacy output json fills metadata when no part metadata exists`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "subagent",
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"status":"ok"}""")),
        )
        val legacy = buildJsonObject {
            put("status", "ok")
            put("summary", "done")
            put("log_path", "/tool_outputs/old.json")
            put("step_count", 4)
            put("model_id", "gpt-5")
            put("elapsed_ms", 2_500)
            put("total_tokens", 9_999)
            put("started_at_epoch_ms", 1_000)
            put("completed_at_epoch_ms", 3_500)
        }
        val resolved = subagentMetadata(tool, legacy)
        assertNotNull(resolved)
        assertEquals("ok", resolved!!.status)
        assertEquals("done", resolved.summary)
        assertEquals("/tool_outputs/old.json", resolved.logPath)
        assertEquals(4, resolved.stepCount)
        assertEquals("gpt-5", resolved.modelId)
        assertEquals(2_500L, resolved.elapsedMs)
        assertEquals(9_999L, resolved.totalTokens)
        assertEquals(1_000L, resolved.startedAtEpochMs)
        assertEquals(3_500L, resolved.completedAtEpochMs)
    }

    @Test
    fun `unrelated provider metadata does not hide legacy subagent output`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "legacy", toolName = "subagent", input = "{}",
            metadata = buildJsonObject { put("provider_tag", "irrelevant") },
            output = listOf(UIMessagePart.Text(
                text = "{}", metadata = SubagentMetadata(title = "legacy task", status = "ok").toMetadata(),
            )),
        )
        assertEquals("legacy task", subagentMetadata(tool, null)?.title)
    }

    @Test
    fun `metadata resolves to null when nothing available`() {
        val tool = UIMessagePart.Tool(toolCallId = "t1", toolName = "subagent", input = "{}")
        assertNull(subagentMetadata(tool, null))
        assertNull(subagentMetadata(tool, buildJsonObject { }))
    }

    // ---- 日志路径白名单 ----

    @Test
    fun `log file resolves only whitelisted json paths`() {
        val root = tempFolder.newFolder("files")
        val ok = subagentLogFile(root, "/subagent_runs/abc-123.json")
        assertNotNull(ok)
        assertEquals("abc-123.json", ok!!.name)
        assertEquals(FileFolders.SUBAGENT_RUNS, ok.parentFile!!.name)
        // 兼容旧版 tool_outputs
        assertNotNull(subagentLogFile(root, "/tool_outputs/legacy.json"))
        // 无前导斜杠也能解析
        assertNotNull(subagentLogFile(root, "subagent_runs/abc.json"))
    }

    @Test
    fun `log file rejects traversal and non whitelisted paths`() {
        val root = tempFolder.newFolder("files")
        assertNull(subagentLogFile(root, null))
        assertNull(subagentLogFile(root, ""))
        assertNull(subagentLogFile(root, "/subagent_runs/../secret.json"))
        assertNull(subagentLogFile(root, "/subagent_runs/../../etc/passwd.json"))
        assertNull(subagentLogFile(root, "/etc/passwd.json"))
        assertNull(subagentLogFile(root, "/subagent_runs/sub/dir.json"))
        assertNull(subagentLogFile(root, "/subagent_runs/notjson.txt"))
        assertNull(subagentLogFile(root, "/subagent_runs/.json"))
        assertNull(subagentLogFile(root, "\\subagent_runs\\x.json"))
        assertNull(subagentLogFile(root, "/subagent_runs/x.json.tmp"))
    }

    @Test
    fun `log file rejects symlinks outside the allowed directory`() {
        val root = tempFolder.newFolder("symlink-root")
        val outside = tempFolder.newFolder("outside")
        val target = outside.resolve("private.json").apply { writeText("{}") }
        val dir = root.resolve(FileFolders.SUBAGENT_RUNS).apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(dir.resolve("escape.json").toPath(), target.toPath())
        assertNull(subagentLogFile(root, "/subagent_runs/escape.json"))
        java.nio.file.Files.createSymbolicLink(root.resolve(FileFolders.TOOL_OUTPUTS).toPath(), outside.toPath())
        assertNull(subagentLogFile(root, "/tool_outputs/private.json"))
    }

    // ---- 日志解析 ----

    @Test
    fun `parse v2 log reads messages and stats`() {
        val json = """
        {
          "version": 2,
          "prompt": "do the task",
          "description": "demo",
          "preamble": "worker preamble",
          "status": "ok",
          "summary": "all done",
          "step_count": 1,
          "model_id": "claude-sonnet-4",
          "elapsed_ms": 12345,
          "total_tokens": 4200,
          "tools_used": ["workspace_shell"],
          "edited_paths": ["/workspace/a.txt"],
          "messages": [
            {"role": "user", "parts": [{"type": "text", "text": "do the task"}]},
            {"role": "assistant", "parts": [
              {"type": "reasoning", "reasoning": "thinking"},
              {"type": "tool", "toolCallId": "t1", "toolName": "workspace_shell",
               "input": "{\"command\":\"ls\"}",
               "output": [{"type": "text", "text": "file.txt"}]},
              {"type": "text", "text": "all done"}
            ], "usage": {"promptTokens": 10, "completionTokens": 5, "totalTokens": 15}}
          ]
        }
        """.trimIndent()
        val log = parseSubagentRunLog(json)
        assertNotNull(log)
        assertEquals("do the task", log!!.prompt)
        assertEquals("demo", log.description)
        assertEquals("worker preamble", log.preamble)
        assertEquals("ok", log.status)
        assertEquals("all done", log.summary)
        assertEquals(1, log.stepCount)
        assertEquals("claude-sonnet-4", log.modelId)
        assertEquals(12_345L, log.elapsedMs)
        assertEquals(4_200L, log.totalTokens)
        assertEquals(listOf("workspace_shell"), log.toolsUsed)
        assertEquals(listOf("/workspace/a.txt"), log.editedPaths)
        assertNotNull(log.messages)
        assertEquals(2, log.messages!!.size)

        // 时间线: user 种子跳过; assistant 的 reasoning/tool/text 按顺序展开
        val items = subagentTimelineItems(log)
        assertEquals(3, items.size)
        assertEquals(SubagentTimelineItem.ReasoningBlock("thinking"), items[0])
        val toolItem = items[1] as SubagentTimelineItem.ToolCall
        assertEquals("workspace_shell", toolItem.name)
        assertEquals("""{"command":"ls"}""", toolItem.input)
        assertEquals("file.txt", toolItem.output)
        assertEquals(SubagentTimelineItem.AssistantText("all done"), items[2])
    }

    @Test
    fun `parse legacy log falls back to transcript`() {
        val json = """
        {
          "prompt": "legacy task",
          "status": "ok",
          "summary": "legacy done",
          "step_count": 2,
          "transcript": [
            {"role": "USER", "text": "legacy task"},
            {"role": "ASSISTANT", "text": "working", "reasoning": "hmm",
             "tools": [{"name": "bash", "input": "ls -la", "output": "ok"}]},
            {"role": "ASSISTANT", "text": "legacy done"}
          ]
        }
        """.trimIndent()
        val log = parseSubagentRunLog(json)
        assertNotNull(log)
        assertNull(log!!.messages)
        assertEquals(3, log.legacyTranscript.size)

        val items = subagentTimelineItems(log)
        // USER 种子跳过; assistant 1 → reasoning + text + tool; assistant 2 → text
        assertEquals(4, items.size)
        assertEquals(SubagentTimelineItem.ReasoningBlock("hmm"), items[0])
        assertEquals(SubagentTimelineItem.AssistantText("working"), items[1])
        val tool = items[2] as SubagentTimelineItem.ToolCall
        assertEquals("bash", tool.name)
        assertEquals("ls -la", tool.input)
        assertEquals("ok", tool.output)
        assertEquals(SubagentTimelineItem.AssistantText("legacy done"), items[3])
    }

    @Test
    fun `parse invalid json returns null`() {
        assertNull(parseSubagentRunLog("not json"))
        assertNull(parseSubagentRunLog("[1,2,3]"))
        assertNull(parseSubagentRunLog("null"))
        assertNull(parseSubagentRunLog(""))
    }

    @Test
    fun `broken messages array still yields transcript`() {
        val json = """
        {
          "prompt": "p",
          "status": "ok",
          "messages": [{"role": "bogus"}],
          "transcript": [{"role": "ASSISTANT", "text": "hello"}]
        }
        """.trimIndent()
        val log = parseSubagentRunLog(json)
        assertNotNull(log)
        // messages 解码失败 (bogus role) → 回落 transcript
        assertEquals(
            listOf(SubagentTimelineItem.AssistantText("hello")),
            subagentTimelineItems(log!!)
        )
    }

    // ---- 文件读取状态 ----

    @Test
    fun `read log state maps io outcomes`() {
        val root = tempFolder.newFolder("files")
        // 无路径
        assertEquals(
            SubagentLogState.Unavailable,
            readSubagentLogState(root, null)
        )
        // 越界路径
        assertEquals(
            SubagentLogState.Unavailable,
            readSubagentLogState(root, "/outside/x.json")
        )
        // 文件不存在 (旧日志已被清理)
        assertEquals(
            SubagentLogState.Missing,
            readSubagentLogState(root, "/subagent_runs/gone.json")
        )
        // 非法内容
        val dir = root.resolve(FileFolders.SUBAGENT_RUNS).apply { mkdirs() }
        dir.resolve("bad.json").writeText("{{{")
        assertEquals(
            SubagentLogState.Invalid,
            readSubagentLogState(root, "/subagent_runs/bad.json")
        )
        // 正常解析
        dir.resolve("good.json").writeText("""{"prompt":"p","status":"ok"}""")
        val state = readSubagentLogState(root, "/subagent_runs/good.json")
        assertTrue(state is SubagentLogState.Parsed)
        assertEquals("p", (state as SubagentLogState.Parsed).log.prompt)
    }

    @Test
    fun `truncate limits huge outputs`() {
        val big = "x".repeat(SUBAGENT_TEXT_DISPLAY_LIMIT + 10)
        val truncated = truncateSubagentText(big)
        assertTrue(truncated.length < big.length)
        assertTrue(truncated.endsWith("…"))
        assertEquals("short", truncateSubagentText("short"))
    }
}
