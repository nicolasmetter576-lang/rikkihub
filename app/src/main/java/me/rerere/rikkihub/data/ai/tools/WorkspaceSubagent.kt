package me.rerere.rikkihub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.GenerationLoop
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.SystemPromptMode
import kotlin.uuid.Uuid

/** Preserve legacy/default budgets; the independently configurable ceiling is 120. */
const val SUBAGENT_DEFAULT_STEPS = 24
const val SUBAGENT_MAX_STEPS = 120
const val SUBAGENT_MAX_FIELD_CHARS = 32_768

val SUBAGENT_WORKER_PREAMBLE = """
You are a workspace subagent executing a delegated task for a parent assistant.
Finish the task using the available workspace tools, then stop with a concise report of what you did and which files you changed.
Do not ask the user questions. You cannot dispatch further subagents.
""".trimIndent()

data class WorkspaceSubagentHost(
    val generationHandler: GenerationLoop,
    val settingsStore: SettingsStore,
    /** 声明为 [InputMessageTransformer] 以便测试注入 no-op transformer; 生产仍传 [WorkspaceReminderTransformer] */
    val reminderTransformer: InputMessageTransformer,
    val context: Context,
    val parentAssistant: Assistant,
    val parentModel: Model,
    val cwd: String?,
)

data class SubagentArgs(
    val prompt: String,
    val description: String?,
    val context: String?,
    val agent: String = WorkspaceSubagentPresets.DEFAULT_AGENT,
)

sealed class SubagentParseResult {
    data class Ok(val args: SubagentArgs) : SubagentParseResult()
    data class Fail(val status: String, val message: String) : SubagentParseResult()
}

data class SubagentRunResult(
    val status: String,
    val summary: String,
    val toolsUsed: List<String>,
    val editedPaths: List<String>,
    val truncated: Boolean,
    val logPath: String,
    val stepCount: Int,
    val title: String,
    val error: String? = null,
    /** 实际运行的 model.modelId */
    val modelId: String? = null,
    /** 运行耗时毫秒; 运行中的阶段性结果里是截至当前时刻的实时值 */
    val elapsedMs: Long? = null,
    /** 各生成步 usage.totalTokens 之和(事件累计); 无 usage 数据时为 null */
    val totalTokens: Long? = null,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    /**
     * 生成循环真实终态 (completed/step_limit/awaiting_approval/cancelled/error),
     * 供结果 JSON 与日志的 `completion_reason` 字段使用。运行中为 null。
     * 该字段描述生成边界而非任务成败, 不能据此判断目标是否达成。
     */
    val completionReason: String? = null,
    val presetId: String? = null,
    val presetKey: String? = null,
    val presetName: String? = null,
    val presetRevision: Long? = null,
)

fun subagentParameters(
    allowContext: Boolean,
    presets: List<WorkspaceSubagentPreset> = emptyList(),
): InputSchema.Obj {
    val required = listOf("prompt")
    return InputSchema.Obj(
        properties = buildJsonObject {
            put("agent", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(WorkspaceSubagentPresets.DEFAULT_AGENT)
                    presets.forEach { add(it.key) }
                })
                put("description", "Optional role key from the workspace subagent directory. Omit or use default for the generic worker.")
            })
            put("prompt", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Self-contained task for the workspace subagent. The worker cannot see the parent conversation unless you also pass context.",
                )
            })
            put("description", buildJsonObject {
                put("type", "string")
                put("description", "Optional 3-10 word title shown on the subagent card. Not sent to the worker.")
            })
            if (allowContext) {
                put("context", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional extra excerpts the worker should see. Omit unless the workspace allows context.",
                    )
                })
            }
        },
        required = required,
    )
}

fun parseSubagentArgs(element: JsonElement, allowContext: Boolean): SubagentParseResult {
    val obj = element as? JsonObject
        ?: return SubagentParseResult.Fail("error", "subagent arguments must be a JSON object")
    val allowed = setOf("prompt", "description", "context", "agent")
    if (obj.keys.any { it !in allowed }) {
        return SubagentParseResult.Fail("error", "Only prompt, description, context and agent arguments are allowed")
    }
    for (field in allowed) {
        if (field == "context" && !allowContext) continue // legacy context is ignored
        val value = obj[field] ?: continue
        if (value !is JsonPrimitive || !value.isString) {
            return SubagentParseResult.Fail("error", "$field must be a string")
        }
    }
    val agent = obj.subagentString("agent") ?: WorkspaceSubagentPresets.DEFAULT_AGENT
    if (agent.isBlank()) return SubagentParseResult.Fail("unavailable", "agent is unavailable")
    val prompt = obj.subagentString("prompt")?.trim().orEmpty()
    if (prompt.isEmpty()) {
        return SubagentParseResult.Fail("error", "prompt is required")
    }
    if (prompt.length > SUBAGENT_MAX_FIELD_CHARS) {
        return SubagentParseResult.Fail(
            "prompt_too_long",
            "prompt exceeds $SUBAGENT_MAX_FIELD_CHARS characters (${prompt.length})",
        )
    }
    val description = obj.subagentString("description")?.trim()?.takeIf { it.isNotEmpty() }
    val context = if (allowContext) obj.subagentString("context")?.trim()?.takeIf { it.isNotEmpty() } else null
    if (context != null && context.length > SUBAGENT_MAX_FIELD_CHARS) {
        return SubagentParseResult.Fail(
            "context_too_long",
            "context exceeds $SUBAGENT_MAX_FIELD_CHARS characters (${context.length})",
        )
    }
    return SubagentParseResult.Ok(
        SubagentArgs(
            prompt = prompt,
            description = description,
            context = context,
            agent = agent,
        )
    )
}

fun stripWorkerAssistant(parent: Assistant): Assistant = parent.copy(
    systemPrompt = SUBAGENT_WORKER_PREAMBLE,
    systemPromptMode = SystemPromptMode.WHOLE,
    promptSections = emptyList(),
    presetMessages = emptyList(),
    messageTemplate = "{{ message }}",
    regexes = emptyList(),
    customHeaders = emptyList(),
    customBodies = emptyList(),
    enableMemory = false,
    useGlobalMemory = false,
    enableWebSearch = false,
    mcpServers = emptySet(),
    localTools = emptyList(),
    enabledSkills = emptySet(),
    lorebookIds = emptySet(),
    modeInjectionIds = emptySet(),
    quickMessageIds = emptySet(),
    enableRecentChatsReference = false,
    enableTimeReminder = false,
    allowConversationSystemPrompt = false,
    allowConversationPromptInjection = false,
)

fun isToolCapableChatModel(model: Model): Boolean =
    model.type == ModelType.CHAT && ModelAbility.TOOL in model.abilities

fun resolveSubagentModel(
    settings: Settings,
    parentModel: Model,
    subagentModelId: String?,
): Model? {
    if (subagentModelId.isNullOrBlank()) {
        return parentModel.takeIf(::isToolCapableChatModel)
    }
    val uuid = runCatching { Uuid.parse(subagentModelId) }.getOrNull() ?: return null
    val model = settings.findModelById(uuid) ?: return null
    return model.takeIf(::isToolCapableChatModel)
}

fun toolCapableChatModels(settings: Settings): List<Model> =
    settings.providers
        .filter { it.enabled }
        .flatMap { it.models }
        .filter(::isToolCapableChatModel)

fun withoutToolApproval(tool: Tool): Tool = tool.copy(needsApproval = { false })

fun buildSubagentUserMessage(args: SubagentArgs): String = buildString {
    append(args.prompt)
    val context = args.context
    if (!context.isNullOrBlank()) {
        append("\n\n<context>\n")
        append(context)
        append("\n</context>")
    }
}

fun collectSubagentStats(
    messages: List<UIMessage>,
    hitStepLimit: Boolean,
): Pair<List<String>, List<String>> {
    val toolsUsed = linkedSetOf<String>()
    val editedPaths = linkedSetOf<String>()
    messages.forEach { message ->
        message.getTools().forEach { tool ->
            toolsUsed += tool.toolName
            pathFromToolInput(tool)?.let { editedPaths += it }
        }
    }
    if (hitStepLimit) {
        // keep collected paths even when the loop is cut off
    }
    return toolsUsed.toList() to editedPaths.toList()
}

fun lastAssistantText(messages: List<UIMessage>): String =
    messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.toText()
        ?.trim()
        .orEmpty()

/**
 * 子代理运行期间新生成的 assistant turn。
 *
 * @deprecated 多个生成步会合并进同一条 assistant UIMessage, 按消息条数统计会
 * 失真 (恒为 1)。生产路径已改用事件驱动的 [me.rerere.rikkihub.data.ai.GenerationRunStats],
 * 此函数仅保留给兼容分析, 禁止用于新的统计口径。
 */
@Deprecated(
    "merged assistant message makes turn counts unreliable; use GenerationRunStats instead",
    ReplaceWith("messages.filter { it.role == MessageRole.ASSISTANT }"),
)
fun subagentNewAssistantTurns(messages: List<UIMessage>): List<UIMessage> =
    messages.filter { it.role == MessageRole.ASSISTANT }

/**
 * 只统计新生成 assistant turn 的 usage.totalTokens 之和。
 * 没有任何 usage 数据时返回 null (不虚报 0); 个别 turn 缺 usage 时按 0 计。
 *
 * @deprecated merged message 的 usage 只保留末轮值, 会丢掉前面各步的 token。
 * 生产路径已改用事件驱动的 [me.rerere.rikkihub.data.ai.GenerationRunStats.totalTokens]。
 */
@Deprecated("message.usage only keeps the last merged value; use GenerationRunStats instead")
fun subagentTotalTokens(messages: List<UIMessage>): Long? {
    var sawUsage = false
    var total = 0L
    subagentNewAssistantTurns(messages).forEach { message ->
        val usage = message.usage ?: return@forEach
        sawUsage = true
        val totalTokens = usage.totalTokens.toLong().takeIf { it > 0 }
            ?: (usage.promptTokens.toLong() + usage.completionTokens.toLong())
        total += totalTokens.coerceAtLeast(0)
    }
    return if (sawUsage) total else null
}

/**
 * @deprecated merged assistant message 使步数判断恒漏报 (assistant 条数恒 1)。
 * 生产路径已改用 [GenerationChunk.GenerationEnd] 的 STEP_LIMIT 终态。
 */
@Deprecated("assistant turns are merged into one message; use GenerationEnd reason instead")
fun subagentHitStepLimit(messages: List<UIMessage>, maxSteps: Int): Boolean {
    val assistantTurns = messages.count { it.role == MessageRole.ASSISTANT }
    if (assistantTurns < maxSteps) return false
    val last = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return false
    return last.getTools().isNotEmpty()
}

fun encodeSubagentResult(result: SubagentRunResult): JsonObject = buildJsonObject {
    put("status", result.status)
    put("title", result.title)
    put("summary", result.summary)
    put("tools_used", result.toolsUsed.toJsonStringArray())
    put("edited_paths", result.editedPaths.toJsonStringArray())
    put("truncated", result.truncated)
    put("log_path", result.logPath)
    put("step_count", result.stepCount)
    result.error?.let { put("error", it) }
    result.modelId?.let { put("model_id", it) }
    result.elapsedMs?.let { put("elapsed_ms", it) }
    result.totalTokens?.let { put("total_tokens", it) }
    result.startedAtEpochMs?.let { put("started_at_epoch_ms", it) }
    result.completedAtEpochMs?.let { put("completed_at_epoch_ms", it) }
    result.completionReason?.let { put("completion_reason", it) }
    result.presetId?.let { put("preset_id", it) }
    result.presetKey?.let { put("preset_key", it) }
    result.presetName?.let { put("preset_name", it) }
    result.presetRevision?.let { put("preset_revision", it) }
}

/** [SubagentRunResult] 到 [SubagentMetadata] 的映射, tool part 与输出 part 共用。 */
fun SubagentRunResult.toSubagentMetadata(): SubagentMetadata = SubagentMetadata(
    title = title,
    status = status,
    summary = summary.ifBlank { null },
    toolsUsed = toolsUsed,
    editedPaths = editedPaths,
    truncated = truncated,
    logPath = logPath.ifBlank { null },
    stepCount = stepCount,
    error = error,
    modelId = modelId,
    elapsedMs = elapsedMs,
    totalTokens = totalTokens,
    startedAtEpochMs = startedAtEpochMs,
    completedAtEpochMs = completedAtEpochMs,
    presetId = presetId,
    presetKey = presetKey,
    presetName = presetName,
    presetRevision = presetRevision,
)

fun JsonObject.subagentString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun List<String>.toJsonStringArray(): JsonArray = buildJsonArray {
    forEach { add(it) }
}

private fun pathFromToolInput(tool: UIMessagePart.Tool): String? {
    val resolved = resolveBuiltinToolId(tool.toolName)
    val mutating = when (resolved) {
        BuiltinToolIds.WORKSPACE_WRITE_FILE,
        BuiltinToolIds.WORKSPACE_EDIT_FILE,
            -> true
        BuiltinToolIds.STR_REPLACE_EDITOR -> {
            val command = tool.inputAsJson().jsonObjectOrNull()?.subagentString("command")
            command == "create" || command == "str_replace" || command == "insert"
        }
        else -> false
    }
    if (!mutating) return null
    return tool.inputAsJson().jsonObjectOrNull()?.subagentString("path")?.trim()?.takeIf { it.isNotEmpty() }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

/** Immutable request catalogue. Display metadata is captured too, never resolved from a later row. */
internal data class SubagentCatalogSnapshot(
    val rawJson: String,
    val workspaceModelId: String?,
    val allowContext: Boolean,
    val presets: List<WorkspaceSubagentPreset>?,
    val available: List<WorkspaceSubagentPreset>,
    val providerSnapshot: List<me.rerere.ai.provider.ProviderSetting>,
) {
    val implementationId: String get() = WorkspaceSubagentPresets.implementationId(rawJson, workspaceModelId, allowContext)
}

internal fun captureSubagentCatalog(workspace: me.rerere.rikkihub.data.db.entity.WorkspaceEntity,
    host: WorkspaceSubagentHost): SubagentCatalogSnapshot {
    val settings = host.settingsStore.settingsFlow.value
    val presets = WorkspaceSubagentPresets.tryParse(workspace.subagentPresets)
    return SubagentCatalogSnapshot(workspace.subagentPresets, workspace.subagentModelId,
        workspace.subagentAllowContext, presets,
        presets.orEmpty().filter { it.enabled && availableSubagentPresetModel(settings, host.parentModel,
            workspace.subagentModelId, it) != null }.sortedWith(compareBy({ it.sortOrder }, { it.key })),
        settings.providers)
}

internal fun subagentPresetDirectory(presets: List<WorkspaceSubagentPreset>): String = buildString {
    append("Workspace subagent directory (agent is optional; omitted or 'default' uses the generic worker):")
    presets.forEach { append("\n- ").append(it.key).append(": ").append(it.name)
        .append(" — ").append(it.description) }
}

/** Reject explicit generation settings that the model's custom wire body would override. */
internal fun presetModelConfigConflict(model: Model, preset: WorkspaceSubagentPreset): String? {
    val configured = buildSet {
        if (preset.generationConfig.temperature != null) addAll(listOf("temperature"))
        if (preset.generationConfig.topP != null) addAll(listOf("top_p", "topP"))
        if (preset.generationConfig.maxTokens != null) addAll(listOf("max_tokens", "max_completion_tokens", "max_output_tokens", "maxOutputTokens", "maxTokens"))
        if (preset.generationConfig.reasoningLevel != ReasoningLevel.AUTO) {
            addAll(listOf("reasoning_effort", "reasoning", "thinking", "enable_thinking", "thinking_mode", "thinking_budget",
                "thinkingConfig", "thinking_config", "thinkingBudget", "thinkingLevel", "includeThoughts"))
        }
    }
    val roleFields = setOf("messages", "system", "instructions", "input", "contents",
        "systemInstruction", "system_instruction")
    val roleOverride = model.customBodies.firstOrNull { body ->
        body.key.split('.', '/', '[', ']').firstOrNull { it.isNotEmpty() } in roleFields
    }
    if (roleOverride != null) return "Model custom body '${roleOverride.key}' overrides the preset worker role or messages"
    val streamOverride = model.customBodies.firstOrNull { it.key == "stream" && it.value != JsonPrimitive(true) }
    if (streamOverride != null) return "Model custom body 'stream' conflicts with the preset streaming worker"
    if (preset.generationConfig.reasoningLevel != ReasoningLevel.AUTO) {
        val effortOverride = model.customBodies.firstOrNull { body ->
            val outputConfig = body.value as? JsonObject
            (body.key == "output_config" && (outputConfig == null || "effort" in outputConfig)) ||
                body.key.startsWith("output_config.effort")
        }
        if (effortOverride != null) return "Model custom body '${effortOverride.key}' conflicts with preset reasoning configuration"
    }
    return model.customBodies.firstOrNull { body ->
        body.key.split('.', '/', '[', ']').any { it in configured } ||
            (body.key in setOf("generationConfig", "generation_config") && runCatching {
                val obj = body.value as? JsonObject
                configured.isNotEmpty() && (obj == null || obj.keys.any { it in configured })
            }.getOrDefault(false))
    }?.let { "Model custom body '${it.key}' conflicts with preset generation configuration" }
}

internal fun availableSubagentPresetModel(settings: Settings, parentModel: Model,
    workspaceModelId: String?, preset: WorkspaceSubagentPreset, checkConfig: Boolean = true): Model? {
    val parent = settings.findModelById(parentModel.id)
    val needsParent = preset.modelSelection == SubagentPresetModelSelection.PARENT ||
        (preset.modelSelection == SubagentPresetModelSelection.WORKSPACE_DEFAULT && workspaceModelId.isNullOrBlank())
    if (needsParent && parent == null) return null
    return resolveSubagentPresetModel(settings, parent ?: parentModel, workspaceModelId, preset)
        ?.takeIf { it.findProvider(settings.providers, checkOverwrite = false)?.enabled == true }
        ?.takeIf { !checkConfig || presetModelConfigConflict(it, preset) == null }
}

internal fun presetWorkerAssistant(parent: Assistant, model: Model, preset: WorkspaceSubagentPreset): Assistant =
    stripWorkerAssistant(parent).copy(
        name = preset.name,
        systemPrompt = SUBAGENT_WORKER_PREAMBLE + "\n\n" + preset.systemPrompt,
        chatModelId = model.id,
        temperature = preset.generationConfig.temperature,
        topP = preset.generationConfig.topP,
        maxTokens = preset.generationConfig.maxTokens,
        reasoningLevel = preset.generationConfig.reasoningLevel,
        contextMessageLimit = 0,
        streamOutput = true,
    )
