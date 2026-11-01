package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.ai.GenerationLoop
import me.rerere.rikkihub.data.ai.ScriptedProvider
import me.rerere.rikkihub.data.ai.StatusStringResolver
import me.rerere.rikkihub.data.ai.echoTool
import me.rerere.rikkihub.data.ai.testChatModel
import me.rerere.rikkihub.data.ai.testGenerationLoop
import me.rerere.rikkihub.data.ai.testSettings
import me.rerere.rikkihub.data.ai.textStepScript
import me.rerere.rikkihub.data.ai.toolStepScript
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.workspace.WorkspaceShellStatus
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Offline regression coverage using the real GenerationLoop and scripted provider, never a live API. */
class WorkspaceSubagentPresetRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private val reminder = object : InputMessageTransformer {}
    private fun role() = WorkspaceSubagentPreset(key = "reviewer", name = "Reviewer",
        description = "Review changes", systemPrompt = "PRIVATE ROLE PROMPT", executionRevision = 7)
    private fun workspace(roles: List<WorkspaceSubagentPreset> = emptyList()) = WorkspaceEntity(
        id = "ws", name = "Workspace", root = "root", createdAt = 1, updatedAt = 1,
        shellStatus = WorkspaceShellStatus.READY.name,
        subagentPresets = WorkspaceSubagentPresets.encode(roles), subagentAllowContext = true)
    private fun json(value: String) = JsonInstant.parseToJsonElement(value)
    private fun result(parts: List<UIMessagePart>) = json((parts.single() as UIMessagePart.Text).text).jsonObject

    // Match the existing repository tests' detached SettingsStore seam. No DataStore/Android startup.
    private fun store(settings: Settings): SettingsStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val store = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, SettingsStore::class.java) as SettingsStore
        SettingsStore::class.java.getDeclaredField("settingsFlow").apply { isAccessible = true }
            .set(store, MutableStateFlow(settings))
        return store
    }
    private inner class HostHarness {
        val context = FakeContext(temp.newFolder())
        val model = testChatModel()
        val provider = ScriptedProvider(ArrayDeque(listOf(textStepScript("done"))))
        val settings = store(testSettings(model))
        val host = WorkspaceSubagentHost(testGenerationLoop(provider, context), settings, reminder,
            context, Assistant(), model, null)
        fun slice(ws: WorkspaceEntity, latest: () -> WorkspaceEntity = { ws },
            overrides: Map<String, ToolOverride> = emptyMap(), runner: WorkspaceToolRunner? = null) = createWorkspaceToolSnapshot(
            workspace = ws, workspaceRepository = null, subagentHost = host,
            loadWorkspace = { latest() }, overrides = overrides, toolRunner = runner)
    }

    @Test fun `schema default remains optional and only enabled available roles share the directory`() {
        val h = HostHarness()
        val ws = workspace(listOf(role(), role().copy(key = "hidden", enabled = false),
            role().copy(key = "missing", modelSelection = SubagentPresetModelSelection.FIXED,
                modelId = "11111111-1111-1111-1111-111111111111")))
        val slice = h.slice(ws, overrides = mapOf(BuiltinToolIds.SUBAGENT to ToolOverride(
            name = "delegate", description = "CUSTOM DESCRIPTION", systemPrompt = "CUSTOM PARENT PROMPT")))
        val tool = slice.tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("prompt"), schema.required)
        assertEquals(listOf("default", "reviewer"), schema.properties["agent"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content })
        assertEquals("delegate", tool.name)
        assertTrue(tool.description.startsWith("CUSTOM DESCRIPTION"))
        assertEquals(1, Regex("Workspace subagent directory").findAll(tool.description).count())
        assertTrue(tool.description.contains("reviewer: Reviewer"))
        assertFalse(tool.description.contains("hidden"))
        assertFalse(tool.description.contains("missing"))
        assertFalse(tool.description.contains("PRIVATE ROLE PROMPT"))
        assertEquals("CUSTOM PARENT PROMPT", tool.systemPrompt(h.model, emptyList()))
        assertEquals(workspaceToolIdentity(ws, BuiltinToolIds.SUBAGENT).implementationId,
            tool.workspaceIdentity!!.implementationId)
        val legacy = subagentParameters(false)
        assertEquals(listOf("default"), legacy.properties["agent"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content })
        assertEquals("default", (parseSubagentArgs(json("""{"prompt":"old call"}"""), false)
            as SubagentParseResult.Ok).args.agent)
    }

    @Test fun `raw declaration protection affects only a declared subagent with available presets`() {
        val h = HostHarness()
        val rawHost = h.host.copy(parentAssistant = Assistant(customBodies = listOf(
            CustomBody("tools", JsonInstant.parseToJsonElement("[]")))))
        val active = workspace(listOf(role()))
        fun snapshot(ws: WorkspaceEntity) = createWorkspaceToolSnapshot(workspace = ws,
            workspaceRepository = null, subagentHost = rawHost, loadWorkspace = { ws })
        assertThrows(IllegalStateException::class.java) { snapshot(active) }
        assertFalse(snapshot(active.copy(toolEnablement = """{"subagent":false}""")).tools.any {
            it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT })
        assertTrue(snapshot(active.copy(shellStatus = WorkspaceShellStatus.DISABLED.name)).tools.isEmpty())
        val disabled = snapshot(workspace(listOf(role().copy(enabled = false))))
            .tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
        assertFalse(disabled.description.contains("Workspace subagent directory"))
        val legacy = snapshot(workspace()).tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
        assertEquals(BuiltinToolCatalog.require(BuiltinToolIds.SUBAGENT).defaultDescription, legacy.description)
    }

    @Test fun `invalid disabled selectors wrong types and config arguments have zero effects`() = runBlocking {
        val h = HostHarness()
        val ws = workspace(listOf(role(), role().copy(key = "hidden", enabled = false)))
        val tool = h.slice(ws).tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
        var metadataCalls = 0
        val ctx = ToolExecutionContext("c") { metadataCalls++ }
        for (args in listOf(
            """{"prompt":"task","agent":"hidden"}""",
            """{"prompt":"task","agent":"missing"}""",
            """{"prompt":"task","agent":42}""",
            """{"prompt":true,"agent":"reviewer"}""",
            """{"prompt":"task","agent":"reviewer","temperature":0.2}""",
            """{"prompt":"task","agent":"reviewer","model_id":"x"}""",
            """{"prompt":"task","agent":"reviewer","context":{}}""")) {
            assertTrue(result(tool.executeWithContext!!(json(args), ctx))["status"]!!.jsonPrimitive.content
                in setOf("error", "unavailable"))
        }
        assertEquals(0, metadataCalls)
        assertTrue(h.provider.streamAttempts.isEmpty())
        assertFalse(File(h.context.filesDir, FileFolders.SUBAGENT_RUNS).exists())
    }

    @Test fun `preset context policy rejects presence while legacy context remains ignored`() = runBlocking {
        val h = HostHarness()
        for (ws in listOf(workspace(listOf(role().copy(acceptContext = false))),
            workspace(listOf(role())).copy(subagentAllowContext = false))) {
            val tool = h.slice(ws).tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
            assertEquals("error", result(tool.execute(json("""{"prompt":"task","agent":"reviewer","context":""}""")))
                ["status"]!!.jsonPrimitive.content)
        }
        assertNull((parseSubagentArgs(json("""{"prompt":"task","context":"secret"}"""), false)
            as SubagentParseResult.Ok).args.context)
        assertTrue(h.provider.streamAttempts.isEmpty())
        assertFalse(File(h.context.filesDir, FileFolders.SUBAGENT_RUNS).exists())
    }

    @Test fun `model availability is fail closed and settings invalidate catalogue snapshots`() = runBlocking {
        val h = HostHarness()
        val fixed = role().copy(modelSelection = SubagentPresetModelSelection.FIXED, modelId = h.model.id.toString())
        val ws = workspace(listOf(fixed))
        val slice = h.slice(ws)
        assertTrue(slice.isCurrent())
        h.settings.settingsFlow.value = testSettings(h.model).copy(providers = listOf(
            (testSettings(h.model).providers.single() as ProviderSetting.OpenAI).copy(enabled = false)))
        assertFalse(slice.isCurrent())
        val tool = slice.tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
        assertEquals("model_unavailable", result(tool.execute(json("""{"prompt":"task","agent":"reviewer"}""")))
            ["status"]!!.jsonPrimitive.content)
        assertTrue(h.provider.streamAttempts.isEmpty())
        assertFalse(File(h.context.filesDir, FileFolders.SUBAGENT_RUNS).exists())
        assertNull(availableSubagentPresetModel(testSettings(h.model.copy(type = ModelType.IMAGE)), h.model, null, fixed))
        assertNull(availableSubagentPresetModel(testSettings(h.model.copy(abilities = emptyList())), h.model, null, fixed))
        assertNull(availableSubagentPresetModel(testSettings(h.model), h.model, null,
            fixed.copy(modelId = "11111111-1111-1111-1111-111111111111")))
    }

    @Test fun `semantic guard rejects old definitions but accepts metadata only edits`() = runBlocking {
        val h = HostHarness()
        val original = role()
        val ws = workspace(listOf(original))
        var latest = ws
        val tool = h.slice(ws, { latest }).tools.single { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
        val identity = tool.workspaceIdentity!!
        latest = workspace(listOf(original.copy(name = "New display name", description = "New description",
            sortOrder = 4, version = 9)))
        requireWorkspaceToolAdmissible(identity, latest)
        latest = workspace(listOf(original.copy(systemPrompt = "new role", executionRevision = 8)))
        assertEquals("unavailable", result(tool.execute(json("""{"prompt":"task","agent":"reviewer"}""")))
            ["status"]!!.jsonPrimitive.content)
        assertTrue(h.provider.streamAttempts.isEmpty())
        assertFalse(File(h.context.filesDir, FileFolders.SUBAGENT_RUNS).exists())
        assertEquals(BuiltinToolIds.SUBAGENT, workspaceToolIdentity(workspace(), BuiltinToolIds.SUBAGENT).implementationId)
        assertNotEquals(identity.implementationId, workspaceToolIdentity(latest, BuiltinToolIds.SUBAGENT).implementationId)
        assertNotEquals(identity.implementationId, workspaceToolIdentity(ws.copy(subagentAllowContext = false),
            BuiltinToolIds.SUBAGENT).implementationId)
    }

    @Test fun `guard checks captured semantic identity under runner admission before operation`() = runBlocking {
        val h = HostHarness()
        val preset = role()
        val ws = workspace(listOf(preset))
        var latest = ws
        var operations = 0
        val runner = object : WorkspaceToolRunner {
            override val activationSession = "runtime-session"
            override fun currentToolEpoch(workspaceId: String, stableId: String) = 3L
            override suspend fun run(admission: WorkspaceToolCallAdmission, validate: suspend () -> Unit,
                operation: suspend () -> List<UIMessagePart>): List<UIMessagePart> {
                assertEquals(3L, admission.expectedToolEpoch)
                latest = workspace(listOf(preset.copy(systemPrompt = "replaced", executionRevision = 8)))
                validate() // simulated admission lock; only now load the changed row
                operations++
                return operation()
            }
        }
        val tool = h.slice(ws, { latest }, runner = runner).tools.single {
            it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
        assertEquals("runtime-session", tool.workspaceIdentity!!.activationSession)
        assertEquals(workspaceToolIdentity(ws, BuiltinToolIds.SUBAGENT).implementationId,
            tool.workspaceIdentity!!.implementationId)
        val output = result(tool.execute(json("""{"prompt":"task","agent":"reviewer"}""")))
        assertEquals("unavailable", output["status"]!!.jsonPrimitive.content)
        assertEquals("CONFIG_INVALID", output["reason"]!!.jsonPrimitive.content)
        assertEquals(0, operations)
        assertTrue(h.provider.streamAttempts.isEmpty())
    }

    @Test fun `preset resets every parent generation field and role injection surface`() {
        val parent = Assistant(systemPrompt = "parent secret", temperature = 1.8f, topP = 0.9f,
            maxTokens = 99, contextMessageLimit = 1, reasoningLevel = ReasoningLevel.HIGH,
            enableMemory = true, useGlobalMemory = true, enableWebSearch = true,
            customBodies = listOf(CustomBody("temperature", JsonPrimitive(2))),
            enabledSkills = setOf("secret"), mcpServers = setOf(kotlin.uuid.Uuid.random()),
            modeInjectionIds = setOf(kotlin.uuid.Uuid.random()), lorebookIds = setOf(kotlin.uuid.Uuid.random()),
            presetMessages = listOf(UIMessage.user("parent message")), messageTemplate = "PARENT {{ message }}",
            allowConversationSystemPrompt = true, allowConversationPromptInjection = true)
        val worker = presetWorkerAssistant(parent, testChatModel(), role())
        assertNull(worker.temperature)
        assertNull(worker.topP)
        assertNull(worker.maxTokens)
        assertEquals(0, worker.contextMessageLimit)
        assertEquals(ReasoningLevel.AUTO, worker.reasoningLevel)
        assertEquals(SUBAGENT_WORKER_PREAMBLE + "\n\nPRIVATE ROLE PROMPT", worker.systemPrompt)
        assertFalse(worker.enableMemory || worker.useGlobalMemory || worker.enableWebSearch)
        assertTrue(worker.enabledSkills.isEmpty() && worker.mcpServers.isEmpty() && worker.customBodies.isEmpty())
        assertTrue(worker.modeInjectionIds.isEmpty() && worker.lorebookIds.isEmpty() && worker.presetMessages.isEmpty())
        assertFalse(worker.allowConversationSystemPrompt || worker.allowConversationPromptInjection)
        assertEquals("{{ message }}", worker.messageTemplate)
        val configured = role().copy(generationConfig = SubagentPresetGenerationConfig(0.3f, 0.7f, 123, ReasoningLevel.LOW))
        assertEquals(0.3f, presetWorkerAssistant(parent, testChatModel(), configured).temperature)
        for (key in listOf("temperature", "top_p", "max_tokens", "max_completion_tokens", "max_output_tokens",
            "generationConfig.topP", "generationConfig.maxOutputTokens", "messages", "messages.0.role",
            "system", "system.text", "instructions", "input", "contents", "systemInstruction",
            "system_instruction.parts")) {
            assertNotNull(presetModelConfigConflict(testChatModel().copy(customBodies = listOf(CustomBody(key,
                JsonPrimitive(1)))), configured))
        }
    }

    @Test fun `real fake provider run freezes config and writes v3 metadata log snapshots`() = runBlocking {
        val context = FakeContext(temp.newFolder())
        val model = testChatModel()
        var settings = testSettings(model)
        val scripted = ScriptedProvider(ArrayDeque(listOf(toolStepScript("c", "echo_tool"), textStepScript("report"))))
        val seen = mutableListOf<TextGenerationParams>()
        val provider = object : Provider<ProviderSetting> by scripted {
            override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>,
                params: TextGenerationParams): Flow<StreamChunk> {
                seen += params
                settings = testSettings(model.copy(abilities = emptyList())) // changes cannot retarget this run
                return scripted.streamText(providerSetting, messages, params)
            }
        }
        val loop = GenerationLoop(context, ProviderManager(OkHttpClient(), context).apply {
            registerProvider("openai", provider)
        }, JsonInstant).apply { statusStrings = StatusStringResolver { _, _ -> "test" } }
        val preset = role().copy(maxSteps = 2, generationConfig = SubagentPresetGenerationConfig(0.2f, 0.6f, 321))
        val runner = WorkspaceSubagentRunner(loop, { settings }, reminder, context, progressFlushIntervalMs = 0)
        val metadata = mutableListOf<SubagentMetadata>()
        val parts = runner.run(Assistant(temperature = 1.9f, contextMessageLimit = 1), model, null,
            false, true, null, json("""{"prompt":"task","agent":"reviewer","description":"Task title"}"""),
            listOf(echoTool()), ToolExecutionContext("parent") {
                metadata += JsonInstant.decodeFromJsonElement<SubagentMetadata>(it)
            }, preset = preset)
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.model == model && it.temperature == 0.2f && it.topP == 0.6f && it.maxTokens == 321 })
        assertTrue(seen.all { params -> params.tools.none { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT } })
        assertTrue(metadata.isNotEmpty())
        assertTrue(metadata.all { it.presetId == preset.id && it.presetRevision == 7L && it.title == "Task title" })
        val output = result(parts)
        assertEquals(preset.key, output["preset_key"]!!.jsonPrimitive.content)
        val log = json(File(context.filesDir, FileFolders.SUBAGENT_RUNS).listFiles()!!.single().readText()).jsonObject
        assertEquals("3", log["version"]!!.jsonPrimitive.content)
        assertEquals(preset.id, log["preset_id"]!!.jsonPrimitive.content)
        assertEquals(model.id.toString(), log["model_config_id"]!!.jsonPrimitive.content)
        assertEquals(SUBAGENT_WORKER_PREAMBLE + "\n\n" + preset.systemPrompt, log["worker_system_prompt"]!!.jsonPrimitive.content)
        assertEquals("321", log["generation_config"]!!.jsonObject["max_tokens"]!!.jsonPrimitive.content)
        assertFalse(log.containsKey("custom_headers") || log.containsKey("provider"))
    }


    @Test fun `preset role custom body rejection happens before logs metadata and provider`() = runBlocking {
        val context = FakeContext(temp.newFolder())
        val model = testChatModel().copy(customBodies = listOf(CustomBody("messages", JsonPrimitive("replacement"))))
        val provider = ScriptedProvider(ArrayDeque(listOf(textStepScript("unused"))))
        val runner = WorkspaceSubagentRunner(testGenerationLoop(provider, context), { testSettings(model) }, reminder, context)
        var progress = 0
        val parts = runner.run(Assistant(), model, null, false, true, null,
            json("""{"prompt":"task","agent":"reviewer"}"""), emptyList(),
            ToolExecutionContext("c") { progress++ }, preset = role())
        assertEquals("config_invalid", result(parts)["status"]!!.jsonPrimitive.content)
        assertTrue(result(parts)["error"]!!.jsonPrimitive.content.contains("overrides the preset worker role"))
        assertEquals(0, progress)
        assertTrue(provider.streamAttempts.isEmpty())
        assertFalse(File(context.filesDir, FileFolders.SUBAGENT_RUNS).exists())
    }

    @Test fun `preset max steps owns worker limit regardless of runner legacy limit`() = runBlocking {
        val context = FakeContext(temp.newFolder())
        val model = testChatModel()
        val provider = ScriptedProvider(ArrayDeque(listOf(toolStepScript("c", "echo_tool"), textStepScript("unused"))))
        val runner = WorkspaceSubagentRunner(testGenerationLoop(provider, context), { testSettings(model) }, reminder,
            context, maxSteps = 24)
        val parts = runner.run(Assistant(), model, null, false, true, null,
            json("""{"prompt":"task","agent":"reviewer"}"""), listOf(echoTool()), preset = role().copy(maxSteps = 1))
        assertEquals("step_limit", result(parts)["status"]!!.jsonPrimitive.content)
        assertEquals("1", result(parts)["step_count"]!!.jsonPrimitive.content)
        assertEquals(1, provider.streamAttempts.size)
    }

    @Test fun `preset can use all 120 successful provider steps despite legacy runner default`() = runBlocking {
        val context = FakeContext(temp.newFolder())
        val model = testChatModel()
        val provider = ScriptedProvider(ArrayDeque(
            (1..120).map { step -> toolStepScript("step_$step", "echo_tool") } + textStepScript("must_not_run"),
        ))
        val runner = WorkspaceSubagentRunner(testGenerationLoop(provider, context), { testSettings(model) }, reminder,
            context, progressFlushIntervalMs = 0)
        val parts = runner.run(Assistant(), model, null, false, true, null,
            json("""{"prompt":"task","agent":"reviewer"}"""), listOf(echoTool()), preset = role().copy(maxSteps = 120))
        assertEquals("step_limit", result(parts)["status"]!!.jsonPrimitive.content)
        assertEquals("120", result(parts)["step_count"]!!.jsonPrimitive.content)
        assertEquals(120, provider.streamAttempts.size)
    }

    @Test fun `interrupted recovery preserves role identifiers and legacy results omit them`() {
        val dir = temp.newFolder()
        val preset = role()
        val running = SubagentRunResult("running", "", emptyList(), emptyList(), false,
            "/subagent_runs/run.json", 1, "Task title", presetId = preset.id, presetKey = preset.key,
            presetName = preset.name, presetRevision = preset.executionRevision)
        writeSubagentLogFile(dir, "run", SubagentArgs("task", null, null), emptyList(), running)
        val file = File(dir, "run.json")
        assertTrue(markSubagentLogInterrupted(file))
        val tool = UIMessagePart.Tool(toolCallId = "parent", toolName = "delegate", input = "{}",
            metadata = running.toSubagentMetadata().toMetadata())
        val recovered = finishInterruptedSubagentTool(tool, dir)!!
        val metadata = recovered.metadataAs<SubagentMetadata>()!!
        assertEquals("interrupted", metadata.status)
        assertEquals(preset.id, metadata.presetId)
        assertEquals(preset.executionRevision, metadata.presetRevision)
        assertEquals("Task title", metadata.title)
        assertEquals(preset.key, result(recovered.output)["preset_key"]!!.jsonPrimitive.content)
        assertNull(JsonInstant.decodeFromJsonElement<SubagentMetadata>(json("""{"status":"ok"}""")).presetId)
        assertFalse(encodeSubagentResult(running.copy(presetId = null, presetKey = null,
            presetName = null, presetRevision = null)).containsKey("preset_id"))
    }
}
