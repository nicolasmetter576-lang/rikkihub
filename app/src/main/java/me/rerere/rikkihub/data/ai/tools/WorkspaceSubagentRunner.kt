package me.rerere.rikkihub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolProgressException
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkihub.data.ai.GenerationAttemptProtocolException
import me.rerere.rikkihub.data.ai.GenerationChunk
import me.rerere.rikkihub.data.ai.GenerationEventObserver
import me.rerere.rikkihub.data.ai.GenerationLoop
import me.rerere.rikkihub.data.ai.GenerationRunStats
import me.rerere.rikkihub.data.ai.RateLimitRetryPolicy
import me.rerere.rikkihub.data.ai.rollbackProtocolFailedAttempt
import me.rerere.rikkihub.data.ai.subagentCompletionReason
import me.rerere.rikkihub.data.ai.toChatToolSnapshot
import me.rerere.rikkihub.data.ai.transcriptForStats
import me.rerere.rikkihub.data.ai.withBuiltinConcurrency
import me.rerere.rikkihub.data.ai.withoutToolApprovals
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.utils.JsonInstantPretty
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TAG = "WorkspaceSubagentRunner"

/** v2 adds full messages/statistics; v3 adds optional preset identity and safe startup config. */
const val SUBAGENT_LOG_VERSION = 3

/** 运行中写日志/发 metadata 的最小间隔, 避免每个流式 chunk 都整文件重写 */
internal const val SUBAGENT_PROGRESS_FLUSH_INTERVAL_MS = 400L

/** 子代理运行日志保留时长 (30 天), 写新日志时顺手按 mtime 清理 */
internal const val SUBAGENT_RUN_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

class WorkspaceSubagentRunner(
    private val generationHandler: GenerationLoop,
    /** 测试注入点: 生产由次构造包装 `settingsStore.settingsFlow::value` */
    private val settingsProvider: () -> Settings,
    private val reminderTransformer: InputMessageTransformer,
    private val context: Context,
    private val maxSteps: Int = SUBAGENT_DEFAULT_STEPS,
    /**
     * 测试注入点: running 快照的节流间隔。测试可传 0 让每个 Messages chunk
     * 都发布快照; 生产保持 [SUBAGENT_PROGRESS_FLUSH_INTERVAL_MS] 不变。
     */
    private val progressFlushIntervalMs: Long = SUBAGENT_PROGRESS_FLUSH_INTERVAL_MS,
) {
    /**
     * 生产构造: 直接使用 [SettingsStore.settingsFlow] 的当前值。
     */
    constructor(
        generationHandler: GenerationLoop,
        settingsStore: SettingsStore,
        reminderTransformer: InputMessageTransformer,
        context: Context,
        maxSteps: Int = SUBAGENT_DEFAULT_STEPS,
        progressFlushIntervalMs: Long = SUBAGENT_PROGRESS_FLUSH_INTERVAL_MS,
    ) : this(
        generationHandler = generationHandler,
        settingsProvider = { settingsStore.settingsFlow.value },
        reminderTransformer = reminderTransformer,
        context = context,
        maxSteps = maxSteps,
        progressFlushIntervalMs = progressFlushIntervalMs,
    )

    suspend fun run(
        parentAssistant: Assistant,
        parentModel: Model,
        cwd: String?,
        harnessMinimal: Boolean,
        allowContext: Boolean,
        subagentModelId: String?,
        args: JsonElement,
        childTools: List<Tool>,
        toolContext: ToolExecutionContext? = null,
        /**
         * child 工具快照来源：非 null 时每个 provider 请求/网络重试
         * 都重取一份 WorkspaceToolSnapshot（重读 DB 的 enablement/READY），
         * child 继承最新禁用集合；null = 固定 childTools（旧行为，派发时刻
         * 装配一次）。提供者返回 null 表示工作区已不可用 → 运行失败。
         */
        childToolSnapshotProvider: (suspend () -> WorkspaceToolSnapshot?)? = null,
        /** Frozen, workspace-owned role selected by the request closure; never model-supplied config. */
        preset: WorkspaceSubagentPreset? = null,
    ): List<UIMessagePart> {
        val parsed = parseSubagentArgs(args, allowContext)
        if (parsed is SubagentParseResult.Fail) {
            return failParts(parsed.status, parsed.message, title = null)
        }
        val request = (parsed as SubagentParseResult.Ok).args
        if (request.agent != WorkspaceSubagentPresets.DEFAULT_AGENT && request.agent != preset?.key) {
            return failParts("unavailable", "Selected subagent preset is unavailable", request.description)
        }
        if (preset != null) {
            if (!preset.enabled || runCatching { WorkspaceSubagentPresets.validate(preset) }.isFailure) {
                return failParts("unavailable", "Selected subagent preset is unavailable", request.description)
            }
            if (args is JsonObject && "context" in args && (!allowContext || !preset.acceptContext)) {
                return failParts("error", "Context is not allowed for this subagent preset", request.description)
            }
        }
        val settings = settingsProvider()
        val model = (if (preset == null) {
            resolveSubagentModel(settings, settings.findModelById(parentModel.id) ?: parentModel, subagentModelId)
                ?.takeIf { it.findProvider(settings.providers, checkOverwrite = false)?.enabled == true &&
                    it.findProvider(settings.providers)?.enabled == true }
        } else availableSubagentPresetModel(settings, parentModel, subagentModelId, preset, checkConfig = false))
            ?: return failParts(
                status = "model_unavailable",
                message = if (subagentModelId.isNullOrBlank()) {
                    "The parent chat model cannot use tools, so a subagent cannot run."
                } else {
                    "Configured subagent model is missing or cannot use tools."
                },
                title = request.description,
            )

        if (preset != null) {
            presetModelConfigConflict(model, preset)?.let { conflict ->
                return failParts("config_invalid", conflict, request.description)
            }
        }

        // 每次执行独立的运行 id / 日志文件, 并发或重试互不覆盖
        val logId = Uuid.random().toString()
        val guestLogPath = "/${FileFolders.SUBAGENT_RUNS}/$logId.json"
        val startedAtNanos = System.nanoTime()
        val startedAtEpochMs = System.currentTimeMillis()
        fun elapsedNow(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

        val worker = if (preset != null) presetWorkerAssistant(parentAssistant, model, preset)
            else stripWorkerAssistant(parentAssistant).copy(
                chatModelId = model.id,
                reasoningLevel = ReasoningLevel.AUTO,
                streamOutput = true,
            )
        val runMaxSteps = preset?.maxSteps ?: maxSteps
        // Safe startup snapshot only: no model custom bodies, credentials or raw headers.
        val generationConfig = buildJsonObject {
            worker.temperature?.let { put("temperature", it) }
            worker.topP?.let { put("top_p", it) }
            worker.maxTokens?.let { put("max_tokens", it) }
            put("reasoning_level", worker.reasoningLevel.name)
            put("context_message_limit", worker.contextMessageLimit)
            put("max_steps", runMaxSteps)
            put("stream_output", worker.streamOutput)
        }
        val userMessage = UIMessage.user(buildSubagentUserMessage(request))
        var transcript = listOf(userMessage)
        // 本次运行的会话标识, 透传给生成循环; 协议失败 descriptor 据此校验任务归属
        val runConversationId = Uuid.random()
        // 事件驱动统计: 每个成功 provider 步一次 StepFinished, 与合并后的
        // assistant 消息条数无关。所有出口 (结果 JSON / 运行中 metadata / v2 日志
        // / 取消恢复) 都读这一份口径。
        val runStats = GenerationRunStats()
        var status = "ok"
        var error: String? = null
        var lastFlushNanos = 0L

        fun buildResult(
            resultStatus: String,
            resultError: String?,
            completedAt: Long?,
            completionReason: String? = resultStatus,
        ): SubagentRunResult {
            // 统计剔除「未真实执行」的占位结果（unavailable/outcome_unknown）：
            // 不冒充工具使用，也不计入 edited_paths。
            val (toolsUsed, editedPaths) = collectSubagentStats(
                transcriptForStats(JsonInstant, transcript),
                resultStatus == "step_limit",
            )
            val summary = if (resultStatus == "running") "" else lastAssistantText(transcript).ifBlank {
                resultError ?: "The subagent finished without a text report."
            }
            val truncated = summary.length > 4_000
            // 快照读多个字段, 与 eventsObserver 写入并发时也不会拿到撕裂视图
            val statsSnapshot = runStats.snapshot()
            return SubagentRunResult(
                status = resultStatus,
                summary = if (truncated) summary.take(4_000) else summary,
                toolsUsed = toolsUsed,
                editedPaths = editedPaths,
                truncated = truncated,
                logPath = guestLogPath,
                stepCount = statsSnapshot.stepCount,
                title = request.description ?: request.prompt.replace("\n", " ").trim().take(80),
                error = resultError,
                modelId = model.modelId,
                elapsedMs = elapsedNow(),
                totalTokens = statsSnapshot.totalTokens,
                startedAtEpochMs = startedAtEpochMs,
                completedAtEpochMs = completedAt,
                // running 阶段不给出终态判断; 终态取生成循环的真实结束原因
                completionReason = if (resultStatus == "running") null else completionReason,
                presetId = preset?.id,
                presetKey = preset?.key,
                presetName = preset?.name,
                presetRevision = preset?.executionRevision,
            )
        }

        suspend fun persist(result: SubagentRunResult) = withContext(Dispatchers.IO) {
            runCatching {
                writeSubagentLogFile(
                    dir = File(context.filesDir, FileFolders.SUBAGENT_RUNS),
                    logId = logId,
                    request = request,
                    transcript = transcript,
                    result = result,
                    workerSystemPrompt = worker.systemPrompt,
                    generationConfig = generationConfig,
                    modelConfigId = model.id.toString(),
                )
            }.onFailure { Log.w(TAG, "write subagent log failed", it) }
        }

        // 运行中限频推进；首次请求之前也发布一次，等待首 token 时即可查看/取消。
        suspend fun flushProgress(force: Boolean) {
            val now = System.nanoTime()
            if (!force && TimeUnit.NANOSECONDS.toMillis(now - lastFlushNanos) < progressFlushIntervalMs) {
                return
            }
            lastFlushNanos = now
            val result = buildResult("running", null, completedAt = null, completionReason = null)
            // 先关联日志路径，再写文件；即使首次 IO 期间取消也不会遗失入口。
            toolContext?.publishMetadata(result.toSubagentMetadata().toMetadata())
            persist(result)
        }

        try {
            flushProgress(force = true)
            // 每个 provider 请求重取 child 快照：免审批 copy 保守卫；
            // provider 缺席时回落固定 childTools（同样剥审批）。
            val perRequestSnapshot: (suspend () -> ChatToolSnapshot)? =
                childToolSnapshotProvider?.let { provider ->
                    suspend {
                        val slice = provider()
                            ?: error("Workspace is not available for subagent child tools")
                        slice.toChatToolSnapshot().withoutToolApprovals()
                            .withBuiltinConcurrency(settingsProvider().toolExecution)
                    }
                }
            generationHandler.generateTextObserved(
                settings = settings,
                model = model,
                messages = listOf(userMessage),
                inputTransformers = listOf(reminderTransformer),
                outputTransformers = emptyList(),
                assistant = worker,
                memories = emptyList(),
                tools = if (perRequestSnapshot != null) {
                    emptyList()
                } else {
                    childTools.map(::withoutToolApproval).applyBuiltinConcurrency(settings.toolExecution)
                },
                toolSnapshotProvider = perRequestSnapshot,
                maxSteps = runMaxSteps,
                processingStatus = MutableStateFlow(null),
                conversationSystemPrompt = null,
                conversationId = runConversationId,
                conversationModeInjectionIds = emptySet(),
                conversationLorebookIds = emptySet(),
                workspaceCwd = cwd,
                harnessMinimalWorkspace = harnessMinimal,
                exposeUpload = true,
                // 统计走生产侧观测口: flowOn 的 channel 在上游抛错时会丢弃
                // 在途事件, collect 端靠不住; observer 在生产协程内同步收到
                // StepFinished/GenerationEnd, 与真实生产顺序一致。observer 只
                // 做轻量统计 (synchronized 内存记账), 不做 IO。
                eventsObserver = GenerationEventObserver(runStats::onChunk),
                // 并行的子代理同时请求时更容易撞限流：429/503 按 Retry-After 退避重试
                rateLimitRetry = RateLimitRetryPolicy(),
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        transcript = chunk.messages
                        flushProgress(force = false)
                    }

                    // StepFinished / GenerationEnd 已由 runStats 吸收, 无需转发
                    is GenerationChunk.StepFinished,
                    is GenerationChunk.GenerationEnd,
                        -> Unit
                }
            }
            // 终态由生成循环事件决定, 不再从 merged assistant 条数反推。
            when (runStats.snapshot().endReason) {
                GenerationChunk.GenerationEnd.Reason.STEP_LIMIT -> {
                    status = "step_limit"
                    error = "Subagent stopped after $runMaxSteps steps."
                }

                // 子代理工具全部免审批, 正常不应出现; 防御性映射为 error 而不是伪造 ok。
                GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL -> {
                    status = "error"
                    error = "Subagent paused unexpectedly waiting for tool approval."
                }

                // COMPLETED 只代表最后一步纯文本自然结束, 不附带任务成败判断。
                // null 理论上不可达 (正常循环退出必有一次 GenerationEnd), 按 completed 处理。
                GenerationChunk.GenerationEnd.Reason.COMPLETED, null -> Unit
            }
        } catch (cancelled: CancellationException) {
            // 协程已取消: 只完成日志 IO，绝不向外层 Flow 再发射。
            val result = buildResult(
                resultStatus = "cancelled",
                resultError = cancelled.message?.takeIf { it.isNotBlank() } ?: "Subagent was cancelled.",
                completedAt = System.currentTimeMillis(),
                completionReason = "cancelled",
            )
            withContext(NonCancellable) { persist(result) }
            throw cancelled
        } catch (failure: Throwable) {
            // 协议失败 attempt 的权威清理: flowOn 上抛时在途 Messages 事件可能已丢,
            // 不能依赖 collect 端见过的状态 —— 按 descriptor 把 transcript 中同 id
            // 消息恢复为 attempt 开始前的 baseMessage (未发布过则 no-op), 日志/messages
            // 落盘只含已验证的先前成功步, 不留半截工具 part。会话 id 校验任务归属。
            if (failure is GenerationAttemptProtocolException &&
                (failure.conversationId == null || failure.conversationId == runConversationId)
            ) {
                transcript = transcript.rollbackProtocolFailedAttempt(failure)
            }
            if (failure is ToolProgressException) {
                withContext(NonCancellable) {
                    persist(
                        buildResult(
                            "interrupted",
                            "Progress delivery was interrupted.",
                            System.currentTimeMillis(),
                            completionReason = "interrupted",
                        )
                    )
                }
                throw failure
            }
            status = "error"
            error = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        }

        val result = buildResult(
            status,
            error,
            completedAt = System.currentTimeMillis(),
            completionReason = runStats.snapshot().endReason?.subagentCompletionReason ?: status,
        )
        // 即使在生成刚结束时取消，也先留下已完成的真实终态供收尾逻辑恢复。
        withContext(NonCancellable) { persist(result) }
        // 终态强制发布, 让卡片从 running 收敛到准确终态; 若此刻协程被取消,
        // emit 会抛 CancellationException, 属预期收尾语义
        toolContext?.publishMetadata(result.toSubagentMetadata().toMetadata())
        return resultParts(result)
    }
}

fun resultParts(result: SubagentRunResult): List<UIMessagePart> {
    val json = encodeSubagentResult(result)
    return listOf(
        UIMessagePart.Text(
            text = json.toString(),
            metadata = result.toSubagentMetadata().toMetadata(),
        )
    )
}

internal suspend fun failParts(
    status: String,
    message: String,
    title: String?,
    toolContext: ToolExecutionContext? = null,
): List<UIMessagePart> {
    val result = SubagentRunResult(
        status = status,
        summary = message,
        toolsUsed = emptyList(),
        editedPaths = emptyList(),
        truncated = false,
        logPath = "",
        stepCount = 0,
        title = title ?: "subagent",
        error = message,
        elapsedMs = 0,
        completedAtEpochMs = System.currentTimeMillis(),
    )
    toolContext?.publishMetadata(result.toSubagentMetadata().toMetadata())
    return resultParts(result)
}

/**
 * 原子写子代理运行日志 (tmp + rename), 并把同一目录里过期日志按 mtime 清理。
 * 日志 IO 整体 best-effort, 由调用方包 runCatching。
 */
internal fun writeSubagentLogFile(
    dir: File,
    logId: String,
    request: SubagentArgs,
    transcript: List<UIMessage>,
    result: SubagentRunResult,
    workerSystemPrompt: String? = null,
    generationConfig: JsonObject? = null,
    modelConfigId: String? = null,
) {
    dir.mkdirs()
    val content = JsonInstantPretty.encodeToString(subagentLogObject(request, transcript, result,
        workerSystemPrompt, generationConfig, modelConfigId))
    val target = File(dir, "$logId.json")
    val tmp = File(dir, "$logId.json.tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(target)) {
        tmp.delete()
        throw IOException("Could not atomically replace subagent log")
    }
    // 清理不在流式热路径中进行。
    if (result.status != "running") pruneStaleSubagentLogs(dir)
}

/** 按 mtime 清理过期运行日志; 当前文件刚写完, mtime 是新的不会被误删 */
internal fun pruneStaleSubagentLogs(
    dir: File,
    nowMillis: Long = System.currentTimeMillis(),
    ttlMillis: Long = SUBAGENT_RUN_TTL_MILLIS,
) {
    val cutoff = nowMillis - ttlMillis
    runCatching {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff && !file.delete()) {
                Log.w(TAG, "pruneStaleSubagentLogs: failed to delete ${file.name}")
            }
        }
    }.onFailure { Log.w(TAG, "pruneStaleSubagentLogs failed", it) }
}

/**
 * 把日志里仍是 running 的 run 标记为 interrupted (进程被杀/崩溃的兜底收尾)。
 * 返回是否修改了文件。
 */
internal fun markSubagentLogInterrupted(
    file: File,
    nowMillis: Long = System.currentTimeMillis(),
    status: String = "interrupted",
): Boolean {
    val obj = runCatching {
        JsonInstant.parseToJsonElement(file.readText()) as? JsonObject
    }.getOrNull() ?: return false
    if (obj["status"]?.jsonPrimitive?.content != "running") return false
    val patched = JsonObject(
        obj + buildJsonObject {
            put("status", status)
            put("error", "Subagent run was interrupted before it could finish.")
            put("completed_at_epoch_ms", nowMillis)
            obj["started_at_epoch_ms"]?.jsonPrimitive?.longOrNull?.let { started ->
                put("elapsed_ms", (nowMillis - started).coerceAtLeast(0))
            }
        }
    )
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(JsonInstantPretty.encodeToString(patched))
    if (!tmp.renameTo(file)) {
        tmp.delete()
        throw IOException("Could not atomically finalize subagent log")
    }
    return true
}

/** 启动时对日志目录做一次 running->interrupted 清扫 */
internal fun markStaleRunningSubagentLogs(dir: File, nowMillis: Long = System.currentTimeMillis()): Int {
    // nowMillis 是本次进程启动时刻；后台扫描不得把启动后新创建的运行标成中断。
    val files = dir.listFiles()?.filter {
        it.isFile && it.extension == "json" && it.lastModified() < nowMillis
    } ?: return 0
    var patched = 0
    files.forEach { file ->
        // 进程停止后的离线时间不是执行耗时，冻结在最后一次持久化的时间。
        if (runCatching { markSubagentLogInterrupted(file, file.lastModified()) }.getOrDefault(false)) {
            patched++
        }
    }
    return patched
}

/** 在生成 job 已 join 后调用（IO 调度器）。优先恢复日志中的真实终态，避免取消覆盖刚完成的结果。 */
internal fun finishInterruptedSubagentTool(
    tool: UIMessagePart.Tool,
    logDir: File,
    nowMillis: Long = System.currentTimeMillis(),
): UIMessagePart.Tool? {
    var metadata = tool.metadataAs<SubagentMetadata>() ?: return null
    if (metadata.stepCount == null || metadata.status == null) return null
    val fileName = metadata.logPath?.removePrefix("/${FileFolders.SUBAGENT_RUNS}/")
    if (fileName != null && fileName.matches(Regex("[A-Za-z0-9_-]+\\.json"))) {
        runCatching {
            val file = File(logDir, fileName)
            if (file.canonicalFile.parentFile == logDir.canonicalFile && file.isFile) {
                markSubagentLogInterrupted(file, nowMillis, status = "cancelled")
                val log = JsonInstant.parseToJsonElement(file.readText()) as JsonObject
                val keys = mapOf(
                    "status" to "status", "summary" to "summary", "error" to "error",
                    "step_count" to "stepCount", "tools_used" to "toolsUsed",
                    "edited_paths" to "editedPaths", "model_id" to "modelId",
                    "elapsed_ms" to "elapsedMs", "total_tokens" to "totalTokens",
                    "started_at_epoch_ms" to "startedAtEpochMs",
                    "completed_at_epoch_ms" to "completedAtEpochMs",
                    "preset_id" to "presetId", "preset_key" to "presetKey",
                    "preset_name" to "presetName", "preset_revision" to "presetRevision",
                )
                val fields = keys.mapNotNull { (from, to) -> log[from]?.let { to to it } }.toMap()
                metadata = JsonInstant.decodeFromJsonElement(
                    JsonObject(metadata.toMetadata() + fields)
                )
            }
        }.onFailure { Log.w(TAG, "Could not recover interrupted subagent log", it) }
    }
    if (metadata.status == "running") {
        metadata = metadata.copy(
            status = "cancelled",
            error = "Generation cancelled by user before tool execution completed.",
            elapsedMs = metadata.startedAtEpochMs?.let { (nowMillis - it).coerceAtLeast(0) }
                ?: metadata.elapsedMs,
            completedAtEpochMs = nowMillis,
        )
    }
    val result = SubagentRunResult(
        status = metadata.status ?: "cancelled",
        summary = metadata.summary ?: metadata.error.orEmpty(),
        toolsUsed = metadata.toolsUsed,
        editedPaths = metadata.editedPaths,
        truncated = metadata.truncated ?: false,
        logPath = metadata.logPath.orEmpty(),
        stepCount = metadata.stepCount ?: 0,
        title = metadata.title ?: "subagent",
        error = metadata.error,
        modelId = metadata.modelId,
        elapsedMs = metadata.elapsedMs,
        totalTokens = metadata.totalTokens,
        startedAtEpochMs = metadata.startedAtEpochMs,
        completedAtEpochMs = metadata.completedAtEpochMs,
        presetId = metadata.presetId,
        presetKey = metadata.presetKey,
        presetName = metadata.presetName,
        presetRevision = metadata.presetRevision,
    )
    return tool.copy(output = resultParts(result), metadata = result.toSubagentMetadata().toMetadata())
}

private fun subagentLogObject(
    request: SubagentArgs,
    transcript: List<UIMessage>,
    result: SubagentRunResult,
    workerSystemPrompt: String? = null,
    generationConfig: JsonObject? = null,
    modelConfigId: String? = null,
): JsonObject = buildJsonObject {
    put("version", SUBAGENT_LOG_VERSION)
    workerSystemPrompt?.let { put("worker_system_prompt", it) }
    generationConfig?.let { put("generation_config", it) }
    modelConfigId?.let { put("model_config_id", it) }
    result.presetId?.let { put("preset_id", it) }
    result.presetKey?.let { put("preset_key", it) }
    result.presetName?.let { put("preset_name", it) }
    result.presetRevision?.let { put("preset_revision", it) }
    put("title", result.title)
    put("prompt", request.prompt)
    request.description?.let { put("description", it) }
    request.context?.let { put("context", it) }
    put("preamble", SUBAGENT_WORKER_PREAMBLE)
    put("status", result.status)
    put("summary", result.summary)
    put("step_count", result.stepCount)
    result.error?.let { put("error", it) }
    result.modelId?.let { put("model_id", it) }
    result.elapsedMs?.let { put("elapsed_ms", it) }
    result.totalTokens?.let { put("total_tokens", it) }
    result.startedAtEpochMs?.let { put("started_at_epoch_ms", it) }
    result.completedAtEpochMs?.let { put("completed_at_epoch_ms", it) }
    result.completionReason?.let { put("completion_reason", it) }
    put("tools_used", encodeSubagentResult(result)["tools_used"]!!)
    put("edited_paths", encodeSubagentResult(result)["edited_paths"]!!)
    // v2: 完整 UIMessage 列表, 保留 reasoning / 工具输入输出 / usage, 读端可直接渲染
    put("messages", JsonInstant.encodeToJsonElement(transcript))
    // 保留旧版可读 transcript 结构, 兼容只看 role/text/tools 的读取方
    put(
        "transcript",
        buildJsonArray {
            transcript.forEach { message ->
                add(
                    buildJsonObject {
                        put("role", message.role.name)
                        put("text", message.toText())
                        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
                            .joinToString("\n") { it.reasoning }
                        if (reasoning.isNotBlank()) put("reasoning", reasoning)
                        val tools = message.getTools()
                        if (tools.isNotEmpty()) {
                            put(
                                "tools",
                                buildJsonArray {
                                    tools.forEach { tool ->
                                        add(
                                            buildJsonObject {
                                                put("name", tool.toolName)
                                                put("input", tool.input)
                                                val output = tool.output
                                                    .filterIsInstance<UIMessagePart.Text>()
                                                    .joinToString("\n") { it.text }
                                                if (output.isNotBlank()) put("output", output)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    )
}
