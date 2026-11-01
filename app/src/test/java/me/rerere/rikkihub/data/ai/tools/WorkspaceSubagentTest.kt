package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import me.rerere.rikkihub.data.workspace.workspaceReminderPrompt
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceSubagentTest {
    @Test
    fun catalogAndApprovalsIncludeSubagentOnBothSurfaces() {
        assertEquals(BuiltinToolIds.SUBAGENT, BuiltinToolCatalog.require(BuiltinToolIds.SUBAGENT).id)
        assertTrue(workspaceToolNamesFor(false).contains(BuiltinToolIds.SUBAGENT))
        assertTrue(workspaceToolNamesFor(true).contains(BuiltinToolIds.SUBAGENT))
        assertTrue(workspaceToolApprovalItemsFor(false).contains(BuiltinToolIds.SUBAGENT))
        assertTrue(workspaceToolApprovalItemsFor(true).contains(BuiltinToolIds.SUBAGENT))
        assertFalse(resolveWorkspaceToolApproval(BuiltinToolIds.SUBAGENT, emptyMap()))
        assertTrue(resolveWorkspaceToolApproval(BuiltinToolIds.SUBAGENT, mapOf(BuiltinToolIds.SUBAGENT to true)))
    }

    @Test
    fun remindersNameSubagent() {
        // 模板里的 {{available_tools}} 由真实能力快照渲染：分别按 normal /
        // harness_minimal 能力面渲染 reminder，断言 subagent 以 wire 名入列，
        // 未启用时明确不推荐（不能是默认模板字面提示）。
        val capsNormal = WorkspaceRequestCapabilities(
            workspaceId = "ws-1",
            workspaceName = "demo",
            harnessMinimal = false,
            availableToolNames = mapOf(
                BuiltinToolIds.WORKSPACE_SHELL to BuiltinToolIds.WORKSPACE_SHELL,
                BuiltinToolIds.WORKSPACE_WRITE_FILE to BuiltinToolIds.WORKSPACE_WRITE_FILE,
                BuiltinToolIds.SUBAGENT to BuiltinToolIds.SUBAGENT,
            ),
            exposedTargets = setOf("/upload", "/skills"),
        )
        val capsHarness = capsNormal.copy(
            harnessMinimal = true,
            availableToolNames = mapOf(
                BuiltinToolIds.BASH to BuiltinToolIds.BASH,
                BuiltinToolIds.STR_REPLACE_EDITOR to BuiltinToolIds.STR_REPLACE_EDITOR,
                BuiltinToolIds.SUBAGENT to BuiltinToolIds.SUBAGENT,
            ),
        )
        val capsNoSubagent = capsNormal.copy(
            availableToolNames = capsNormal.availableToolNames - BuiltinToolIds.SUBAGENT,
        )

        val defaultReminder = workspaceReminderPrompt(
            workspaceName = "demo",
            harnessMinimal = false,
            exposedTargets = capsNormal.exposedTargets,
            cwd = null,
            overrides = emptyMap(),
            capabilities = capsNormal,
        )
        val harnessReminder = workspaceReminderPrompt(
            workspaceName = "demo",
            harnessMinimal = true,
            exposedTargets = capsHarness.exposedTargets,
            cwd = null,
            overrides = emptyMap(),
            capabilities = capsHarness,
        )
        val noSubagentReminder = workspaceReminderPrompt(
            workspaceName = "demo",
            harnessMinimal = false,
            exposedTargets = capsNormal.exposedTargets,
            cwd = null,
            overrides = emptyMap(),
            capabilities = capsNoSubagent,
        )

        assertTrue(defaultReminder.contains("`subagent`"))
        assertTrue(harnessReminder.contains("`subagent`"))
        // 能力面未含 subagent 时渲染不推荐它
        assertFalse(noSubagentReminder.contains("`subagent`"))
    }

    @Test
    fun schemaHidesContextUnlessAllowed() {
        val hidden = subagentParameters(allowContext = false)
        val shown = subagentParameters(allowContext = true)
        assertFalse(hidden.properties.containsKey("context"))
        assertTrue(shown.properties.containsKey("context"))
        assertEquals(listOf("prompt"), hidden.required)
        assertEquals(listOf("prompt"), shown.required)
    }

    @Test
    fun parseArgsRejectsMissingAndOversizedFields() {
        val missing = parseSubagentArgs(buildJsonObject {}, allowContext = true)
        assertTrue(missing is SubagentParseResult.Fail)
        assertEquals("error", (missing as SubagentParseResult.Fail).status)

        val tooLongPrompt = parseSubagentArgs(
            buildJsonObject { put("prompt", "x".repeat(SUBAGENT_MAX_FIELD_CHARS + 1)) },
            allowContext = true,
        )
        assertEquals("prompt_too_long", (tooLongPrompt as SubagentParseResult.Fail).status)

        val tooLongContext = parseSubagentArgs(
            buildJsonObject {
                put("prompt", "do it")
                put("context", "y".repeat(SUBAGENT_MAX_FIELD_CHARS + 1))
            },
            allowContext = true,
        )
        assertEquals("context_too_long", (tooLongContext as SubagentParseResult.Fail).status)
    }

    @Test
    fun parseArgsIgnoresContextWhenSwitchOff() {
        val parsed = parseSubagentArgs(
            buildJsonObject {
                put("prompt", "edit notes")
                put("description", "fix file")
                put("context", "secret parent chat")
            },
            allowContext = false,
        ) as SubagentParseResult.Ok
        assertEquals("edit notes", parsed.args.prompt)
        assertEquals("fix file", parsed.args.description)
        assertNull(parsed.args.context)
    }

    @Test
    fun workerAssistantIsStripped() {
        val parent = Assistant(
            systemPrompt = "You are a pirate",
            enableMemory = true,
            enableWebSearch = true,
            localTools = emptyList(),
            messageTemplate = "{{ role }} {{ message }}",
            regexes = listOf(
                AssistantRegex(id = Uuid.random(), findRegex = "a", replaceString = "b"),
            ),
        )
        val worker = stripWorkerAssistant(parent)
        assertEquals(SUBAGENT_WORKER_PREAMBLE, worker.systemPrompt)
        assertEquals("{{ message }}", worker.messageTemplate)
        assertTrue(worker.regexes.isEmpty())
        assertFalse(worker.enableMemory)
        assertFalse(worker.enableWebSearch)
        assertTrue(worker.localTools.isEmpty())
        assertFalse(worker.allowConversationSystemPrompt)
        assertEquals(parent.id, worker.id)
        assertEquals(parent.workspaceId, worker.workspaceId)
    }

    @Test
    fun maxStepsCeilingIs120ButLegacyDefaultRemains24() {
        assertEquals(120, SUBAGENT_MAX_STEPS)
        assertEquals(24, SUBAGENT_DEFAULT_STEPS)
    }

    @Test
    fun resolveModelRequiresToolAbilityAndFallsBackToParent() {
        val parentId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val childId = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val parent = Model(
            id = parentId,
            modelId = "parent",
            displayName = "parent",
            type = ModelType.CHAT,
            abilities = listOf(ModelAbility.TOOL),
        )
        val child = Model(
            id = childId,
            modelId = "child",
            displayName = "child",
            type = ModelType.CHAT,
            abilities = listOf(ModelAbility.TOOL),
        )
        val noTool = Model(
            id = Uuid.parse("33333333-3333-3333-3333-333333333333"),
            modelId = "plain",
            displayName = "plain",
            type = ModelType.CHAT,
        )
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(models = listOf(parent, child, noTool)),
            ),
        )
        assertEquals(parent, resolveSubagentModel(settings, parent, null))
        assertEquals(child, resolveSubagentModel(settings, parent, childId.toString()))
        assertNull(resolveSubagentModel(settings, parent, noTool.id.toString()))
        assertNull(resolveSubagentModel(settings, parent.copy(abilities = emptyList()), null))
    }

    @Test
    fun resultJsonAndStepLimitDetection() {
        val result = encodeSubagentResult(
            SubagentRunResult(
                status = "ok",
                summary = "done",
                toolsUsed = listOf("workspace_edit_file"),
                editedPaths = listOf("/workspace/a.txt"),
                truncated = false,
                logPath = "/tool_outputs/abc.json",
                stepCount = 2,
                title = "edit",
            )
        )
        assertEquals("ok", result.subagentString("status"))
        assertEquals("done", result.subagentString("summary"))
        assertEquals("/tool_outputs/abc.json", result.subagentString("log_path"))

        val assistantWithTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = BuiltinToolIds.WORKSPACE_WRITE_FILE,
                    input = """{"path":"/workspace/a.txt","text":"hi"}""",
                )
            ),
        )
        // 已弃用的消息条数口径: 真实多步 transcript 是单条 merged assistant,
        // 这里保留断言语义仅证明遗留 helper 的文档化行为 (条数达到 max 且尾部有工具)。
        @Suppress("DEPRECATION")
        assertTrue(subagentHitStepLimit(List(24) { assistantWithTool }, 24))
        @Suppress("DEPRECATION")
        assertFalse(subagentHitStepLimit(listOf(UIMessage.assistant("done")), 24))
        @Suppress("DEPRECATION")
        // 真实形态 (24 步合 1 条): 旧口径漏报, 生产已改用 GenerationEnd 事件
        assertFalse(subagentHitStepLimit(listOf(assistantWithTool), 24))

        val (tools, paths) = collectSubagentStats(listOf(assistantWithTool), false)
        assertEquals(listOf(BuiltinToolIds.WORKSPACE_WRITE_FILE), tools)
        assertEquals(listOf("/workspace/a.txt"), paths)
    }
}
