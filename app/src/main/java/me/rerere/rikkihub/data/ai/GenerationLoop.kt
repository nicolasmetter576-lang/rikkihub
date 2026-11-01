package me.rerere.rikkihub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolProgressException
import me.rerere.ai.core.mayRunInParallel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.defaultTextSessionId
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.MessageTransformer
import me.rerere.rikkihub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkihub.data.ai.transformers.transforms
import me.rerere.rikkihub.data.ai.transformers.visualTransforms
import me.rerere.rikkihub.data.ai.transformers.transformThinkTags
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ChatToolSnapshot
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.ai.tools.resolveBuiltinToolId
import me.rerere.rikkihub.data.ai.tools.withProviderWireToolRenames
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import me.rerere.rikkihub.data.workspace.isToolOutputTruncationExempt
import me.rerere.rikkihub.data.workspace.planToolOutputTruncation
import me.rerere.rikkihub.data.workspace.renderNotice
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.model.effectiveSystemPrompt
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private const val TAG = "GenerationLoop"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L
private const val MAX_TOOL_ERROR_CHARS = 1_536
/** 工具返回空列表时写入的占位输出：输出为空会被当成没执行过，之后每一步都会再执行一次。 */
internal const val EMPTY_TOOL_OUTPUT = "(no output)"
/** 多个工具调用同时在跑时，进度更新合并后的最短发送间隔。 */
internal const val TOOL_PROGRESS_COALESCE_MS = 200L

/**
 * 开启并行且本轮至少两个工具可并行时追加到工具系统提示的固定文案（内容不随状态变化，
 * 不破坏 prompt cache 的稳定性）。
 */
internal const val PARALLEL_TOOL_CALLS_PROMPT =
    "Independent operations can be issued as multiple tool calls in a single response: read-only calls " +
        "in one response run concurrently, while calls that change files or state run one at a time in " +
        "the order given."
/** 限流（429/503）退避重试策略。只给子代理用，主对话照旧直接报错。 */
data class RateLimitRetryPolicy(
    val maxRetries: Int = 3,
    val baseDelayMillis: Long = 2_000,
    val maxDelayMillis: Long = 30_000,
)

internal data class RateLimitedStatus(val statusCode: Int, val retryAfterMillis: Long?)

/** 沿 cause 链找 HTTP 429/503（[HttpException] 或 Devin 的 [DevinApiException]）。 */
internal fun rateLimitedStatus(error: Throwable): RateLimitedStatus? =
    generateSequence(error) { it.cause }.take(8).firstNotNullOfOrNull { cause ->
        when (cause) {
            is me.rerere.ai.util.HttpException -> cause.statusCode?.let { RateLimitedStatus(it, cause.retryAfterMillis) }
            is me.rerere.ai.provider.providers.devin.DevinApiException -> RateLimitedStatus(cause.statusCode, null)
            else -> null
        }
    }?.takeIf { it.statusCode == 429 || it.statusCode == 503 }

internal const val PARALLEL_SUBAGENTS_PROMPT =
    "Subagents dispatched in the same response also run concurrently and share the workspace, so give " +
        "each one its own files to change."
private const val TOOL_OUTPUT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
/**
 * 请求构建后快照时效复查的最大重建次数：isCurrent 复查不过即整份重建，
 * 超过上限说明策略在被反复修改（busy loop）→ 抛 [StaleToolSnapshotException]
 * 本地错误终止本 step，不落入网络重试、也不发半更新请求。
 */
private const val MAX_STALE_SNAPSHOT_REBUILDS = 3

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

/**
 * 一次待处理工具调用在其宿主消息里的确定位置。
 *
 * [GenerationLoop] 在选取本轮要执行/待审批的工具时记录 part 下标 (及其宿主消息
 * 下标), 之后的执行回写 / 审批回写 / 实时 metadata 回写一律按下标定位, 不再用
 * `toolCallId` 匹配: 空 id / 同一 response 里重复 id / 跨轮复用已执行 call 的 id
 * 都会让按 id 的 `find` 把输出/metadata 写进所有同 id part (含已执行历史 part),
 * 或把两个独立 call 的结果混为一谈。下标在选取后不变 (工具执行期间 messages 只会
 * 被替换式更新, part 顺序与消息顺序不变), 因此是稳定定位。
 */
private data class ToolPartRef(
    val messageIndex: Int,
    val partIndex: Int,
    val tool: UIMessagePart.Tool,
)

/**
 * 生产侧同步观测口 (仅 internal, 见 [GenerationLoop.generateTextObserved])。
 *
 * `generateText` 末尾 `flowOn(Dispatchers.IO)` 把生产/消费解耦进同一 channel;
 * 上游一旦抛错, 尚在通道中未被消费的事件会被丢弃 (collect 端补不回来)。
 * 需要"每个成功步恰一次"精确统计的消费者 (如子代理 runStats) 实现本接口:
 * 它在生产协程内、与 emit 同一顺序收到 [GenerationChunk.StepFinished] /
 * [GenerationChunk.GenerationEnd], 不受通道丢弃影响。
 *
 * 契约: 只做轻量统计/记账, 禁止慢 IO; Messages 走 collect 即可 (显示用途允许
 * 尽力交付)。实现抛出的非取消异常不会冒充 provider 错误, 也不会终止生成:
 * 只记录日志后按原序继续 (下游 emit 依旧照常收到同一事件)。
 */
internal fun interface GenerationEventObserver {
    fun onEvent(chunk: GenerationChunk)
}

/**
 * 真实 provider 请求开始侧观测口（claudelife 缓存保活）。
 *
 * 在 [GenerationLoop] 每次调用 provider 的 `streamText`/`generateText` **之前**、
 * 生产协程内同步回调一次（网络重试的每个 attempt 各一次，末次生效）。参数为本次请求的
 * 完整构建输入：有效 provider、交给 provider 的最终消息（含 system、经全部输入 transformer
 * 处理）、请求参数；[contextMessages] 为构建前的会话层消息快照。
 *
 * 契约：只做轻量内存操作，禁止慢 IO；抛出的非取消异常只记日志，不影响生成。
 */
internal fun interface ProviderRequestObserver {
    fun onProviderRequestStart(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        contextMessages: List<UIMessage>,
    )
}

sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk

    /**
     * 一次成功的 provider 生成步恰好发射一次 (网络层自动重试不重复计步,
     * 失败 attempt 的统计被丢弃)。同一个 assistant UIMessage 可能承载多个 step,
     * 因此 step 数/usage 不能以消息条数或 message.usage 反推。
     *
     * @param stepIndex 该生成步在循环中的下标 (0 起)
     * @param stepNumber 人读序号, 恒等于 stepIndex + 1
     * @param usage 本步的用量。流式: attempt 内多个 Usage 按 [mergeStepUsage] 归并
     *              (p/c 正值覆盖, totalTokens 取最新有效快照, 允许向下修正);
     *              非流式: `TextGenerationResult.usage`。provider 未上报时为 null
     * @param finishReason provider 上报的结束原因原文 (流式 Finish / 非流式 result), 可为 null
     * @param pendingToolCount 本步产生且尚未执行的工具调用数; 0 表示文本步
     */
    data class StepFinished(
        val stepIndex: Int,
        val stepNumber: Int = stepIndex + 1,
        val usage: TokenUsage? = null,
        val finishReason: String? = null,
        val pendingToolCount: Int = 0,
        /**
         * The step's final message list (same object sent in the preceding Messages
         * chunk). Lets a synchronous [GenerationEventObserver] see the step's new tool
         * calls BEFORE they are executed; channel consumers may ignore it.
         */
        val stepMessages: List<UIMessage>? = null,
    ) : GenerationChunk

    /**
     * 生成循环每次正常退出恰好发射一次, 表示真实终态 (与是否产出文件/目标是否达成无关)。
     * 异常与取消仍然走 throw, 不发射本事件。
     */
    data class GenerationEnd(
        val reason: Reason,
    ) : GenerationChunk {
        enum class Reason {
            /** 最后一步以纯文本结束, 无未执行工具调用 */
            COMPLETED,

            /** 仍有工具循环要继续但 maxSteps 耗尽 */
            STEP_LIMIT,

            /** 有工具调用等待用户审批 (denied/answered 恢复语义不变) */
            AWAITING_APPROVAL,
        }
    }
}

/**
 * 一次成功 provider 生成的观测结果, 由 [GenerationLoop] 内部采集并转成
 * [GenerationChunk.StepFinished] 发射, 统计口径见 [mergeStepUsage]。
 */
internal data class GenerationStepOutcome(
    val usage: TokenUsage? = null,
    val finishReason: String? = null,
    /**
     * 本 step 成功 attempt 实际使用的工具快照（tools/bindings/capabilities/
     * wirePolicy 同源）。签发绑定与审批判定必须查这张冻结表，不能用最新
     * Override 反解 alias；固定 tools 调用者恒等于入参包装快照。
     */
    val toolSnapshot: ChatToolSnapshot? = null,
)

/**
 * 一次 provider 请求的完整构建输入（每 step / 每网络重试 attempt 重建）。
 * tools、系统提示里的工具段、transformer 能力与最终 wire 策略来自同一份
 * [ChatToolSnapshot]，避免半更新请求。
 */
private data class ProviderAttemptRequest(
    val snapshot: ChatToolSnapshot,
    val internalMessages: List<UIMessage>,
    val params: TextGenerationParams,
)

/**
 * 组装一次 provider 请求所需的输入（正常对话的每一步与压缩摘要共用，二者前缀一致才能命中提示词缓存）。
 */
private data class ProviderRequestInputs(
    val assistant: Assistant,
    val settings: Settings,
    val transformers: List<MessageTransformer>,
    val model: Model,
    val provider: ProviderSetting,
    val snapshotProvider: suspend () -> ChatToolSnapshot,
    val memories: List<MemoryFile>,
    val requestSessionId: String?,
    val processingStatus: MutableStateFlow<String?>,
    val conversationSystemPrompt: String?,
    val conversationId: Uuid?,
    val conversationModeInjectionIds: Set<Uuid>,
    val conversationLorebookIds: Set<Uuid>,
    val workspaceCwd: String?,
    val harnessMinimalWorkspace: Boolean,
    val exposeUpload: Boolean,
)

/**
 * 运行期状态文案解析 seam。生产默认走 `context.getString`;
 * `Context.getString` 是 final, JVM 单测无法构造真实 Resources,
 * 测试经 [GenerationLoop] 构造参数注入等价实现, 不改生产行为。
 */
internal fun interface StatusStringResolver {
    fun resolve(resId: Int, vararg formatArgs: Any?): String
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    /**
     * 运行期状态文案解析 seam。生产默认走 `context.getString`;
     * `Context.getString` 是 final, JVM 单测无法构造真实 Resources,
     * 测试在构造后经 `loop.statusStrings = ...` 注入等价实现, 不改生产行为。
     */
    internal var statusStrings: StatusStringResolver = StatusStringResolver { resId, args ->
        context.getString(resId, *args)
    }

    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<MemoryFile>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        harnessMinimalWorkspace: Boolean = false,
        exposeUpload: Boolean = true,
    ): Flow<GenerationChunk> = generateTextObserved(
        settings = settings,
        model = model,
        messages = messages,
        inputTransformers = inputTransformers,
        outputTransformers = outputTransformers,
        assistant = assistant,
        memories = memories,
        tools = tools,
        maxSteps = maxSteps,
        processingStatus = processingStatus,
        conversationSystemPrompt = conversationSystemPrompt,
        conversationId = conversationId,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        workspaceCwd = workspaceCwd,
        harnessMinimalWorkspace = harnessMinimalWorkspace,
        exposeUpload = exposeUpload,
        eventsObserver = null,
    )

    /**
     * [generateText] 的模块内观测变体。公共 API 不变; 额外暴露
     * [GenerationEventObserver] 让需要精确步统计的消费者 (子代理 runStats)
     * 在生产协程内同步收到 StepFinished/GenerationEnd, 绕开 `flowOn` channel
     * 在上游抛错时丢弃在途事件的问题 (见 [GenerationEventObserver] 契约)。
     */
    internal fun generateTextObserved(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<MemoryFile>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        harnessMinimalWorkspace: Boolean = false,
        exposeUpload: Boolean = true,
        eventsObserver: GenerationEventObserver? = null,
        requestObserver: ProviderRequestObserver? = null,
        /**
         * 请求一致工具快照来源。非 null 时**每个**下一 provider 请求与
         * 每个网络重试 attempt 都重新取一份（tools + 工具提示 + capabilities +
         * wirePolicy 同源重建），热禁用/改名在下一次请求生效；null = 固定
         * caller，全期复用入参 [tools]（旧行为不变）。快照失败抛出的异常
         * 不经网络重试路径，直接终止生成。
         */
        toolSnapshotProvider: (suspend () -> ChatToolSnapshot)? = null,
        /**
         * 限流（429/503）退避重试；null = 不重试（主对话的现行行为）。子代理传入，
         * 并行的子代理同时请求时更容易撞上限流。
         */
        rateLimitRetry: RateLimitRetryPolicy? = null,
    ): Flow<GenerationChunk> = channelFlow {
        // channelFlow 而非 flow{}：工具执行经 gate 会切到带 WorkspaceOpElement 的
        // SupervisorJob 上下文，flow{} 的 SafeCollector 对外来 emit 做严格 context
        // 等值检查会抛 invariant violation；channelFlow 的 send 只走通道，跨上下文
        // 调用合法。send 挂起交付，生产仍单线程顺序，不改变 emit 语义与顺序。
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
        // Once per collected run (not per tool step, retry, or snapshot rebuild).
        // A real conversation keeps its original identity/priority; standalone manual
        // session headers remain untouched instead of being overwritten by a new UUID.
        val requestSessionId = conversationId?.toString()
            ?: defaultTextSessionId(assistant.customHeaders + model.customHeaders)

        var messages: List<UIMessage> = messages

        // 工具执行阶段的并发安全：改 messages + 发送一律在锁内，并行调用的结果与进度
        // 不会互相覆盖。只有一个调用在跑时进度即时发送（与逐个执行时相同）；多个同时
        // 在跑时合并为最多每 TOOL_PROGRESS_COALESCE_MS 发一次最新状态。
        val messagesLock = Mutex()
        val runningTools = java.util.concurrent.atomic.AtomicInteger(0)
        var lastProgressSentAt = 0L
        var progressDirty = false
        var progressFlushJob: Job? = null

        fun nowMillis(): Long = System.nanoTime() / 1_000_000

        suspend fun publishProgress(update: (List<UIMessage>) -> List<UIMessage>) {
            messagesLock.withLock {
                messages = update(messages)
                val now = nowMillis()
                if (runningTools.get() <= 1 || now - lastProgressSentAt >= TOOL_PROGRESS_COALESCE_MS) {
                    send(GenerationChunk.Messages(messages))
                    lastProgressSentAt = now
                    progressDirty = false
                } else {
                    progressDirty = true
                    if (progressFlushJob?.isActive != true) {
                        val wait = TOOL_PROGRESS_COALESCE_MS - (now - lastProgressSentAt)
                        progressFlushJob = this@channelFlow.launch {
                            delay(wait.coerceAtLeast(0))
                            messagesLock.withLock {
                                if (progressDirty) {
                                    send(GenerationChunk.Messages(messages))
                                    lastProgressSentAt = nowMillis()
                                    progressDirty = false
                                }
                            }
                        }
                    }
                }
            }
        }

        // 单个调用完成即回写并发送：先完成的先出结果；停止生成时已完成的调用保留真实结果。
        suspend fun publishToolResult(executed: ToolPartRef) {
            messagesLock.withLock {
                messages = applyToolResultAt(messages, executed)
                send(GenerationChunk.Messages(messages))
                lastProgressSentAt = nowMillis()
                progressDirty = false
            }
        }

        // 固定 caller 的兼容快照（bindings 首重名保留 = 旧 `find{}` 语义）。
        val fixedSnapshot = fixedToolsSnapshot(tools)
        // 本 run 最近一次成功 provider 请求使用的签发快照（响应名表随该请求冻结）。
        var lastIssuedSnapshot: ChatToolSnapshot? = null
        // 工具选取/审批/执行当前快照：provider 模式下每次取最新；取失败即抛出
        //（失败不回落旧快照——禁用后沿用旧 tools 会复活已关能力），由调用点转成
        // 明确的 unavailable/错误结果。固定 caller 用最近一次签发快照，无签发时
        // 回落入参包装快照（旧 find 语义）。
        suspend fun currentExecutionSnapshot(): ChatToolSnapshot {
            if (toolSnapshotProvider != null) return toolSnapshotProvider.invoke()
            return lastIssuedSnapshot ?: fixedSnapshot
        }
        // provider 请求构建用的快照源（不含执行侧回落：拿不到最新快照不应发出请求）。
        val requestSnapshotProvider: suspend () -> ChatToolSnapshot =
            toolSnapshotProvider ?: { fixedSnapshot }

        // 输出转换只作用于本轮助手消息里这一步新生成的 part（下标 newPartsFrom 起）：
        // 历史消息和之前步骤的内容已经处理过，再处理会让正则这类改写叠加。
        suspend fun List<UIMessage>.withTransformedStep(
            newPartsFrom: Int,
            transform: suspend (List<UIMessage>) -> List<UIMessage>,
        ): List<UIMessage> {
            val last = lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
            return dropLast(1) + last.transformNewParts(newPartsFrom, transform)
        }

        var endReason: GenerationChunk.GenerationEnd.Reason? = null
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // 末尾助手消息里还没执行的调用（审批之后继续）。按下标选取（而不是按
            // toolCallId 存取）：空 id / 重复 id / 同 id 跨轮复用时每个未执行 part 仍是
            // 独立调用，回写只命中自己的 part。免审批（Auto）的调用也在里面：同一批里
            // 有调用等待审批时，整批都等审批之后一起执行。
            val lastMessageIndex = messages.lastIndex
            val pendingTools = messages.lastOrNull()
                ?.takeIf { it.role == MessageRole.ASSISTANT }
                ?.parts?.mapIndexedNotNull { index, part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        ToolPartRef(lastMessageIndex, index, part)
                    } else null
                } ?: emptyList()

            val toolsToProcess: List<ToolPartRef>
            // 并行分段用的工具表（只用来判定可否并行；执行仍按每次调用取的最新快照）
            var schedulingSnapshot: ChatToolSnapshot? = null

            // 置 Pending/进入执行前取一次最新快照：请求发出后被禁用的工具不得进入
            // Pending 卡住审批。快照刷新失败不回落旧表，免审批的调用直接写非空
            // unavailable 结果（解除 pending、不算执行统计）；执行守卫在 body 内仍会
            // 按最新 DB 做二次确认。取消是用户语义，必须传播。返回 true = 有调用在等审批。
            suspend fun gateApprovals(refs: List<ToolPartRef>): Pair<List<ToolPartRef>, Boolean> {
                val approvalSnapshotFailure = runCatching { currentExecutionSnapshot() }
                    .onFailure { if (it is CancellationException) throw it }
                val approvalSnapshot = approvalSnapshotFailure.getOrNull()
                schedulingSnapshot = approvalSnapshot
                var hasPendingApproval = false
                val gated = refs.map { ref ->
                    val tool = ref.tool
                    when {
                        // 仍在等审批（同一批里还有没处理的）→ 继续等
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            ref
                        }
                        // 已批准/拒绝/已回答：结果由执行阶段写入
                        tool.approvalState !is ToolApprovalState.Auto -> ref
                        // 本次快照取不到：明确受限，不回落旧表复活已关能力
                        approvalSnapshotFailure.isFailure -> ref.copy(
                            tool = tool.copy(
                                output = unavailableToolParts(json, "SNAPSHOT_REFRESH_FAILED", tool.toolName)
                            )
                        )
                        else -> {
                            val toolDef = approvalSnapshot?.bindings?.get(tool.toolName)
                            // 当前表里没有定义（模型幻觉/工具已关）→ 放行到执行阶段写
                            // "Tool not found" 错误 JSON 回灌，不置 Pending、不做近似匹配。
                            if (toolDef != null && toolDef.needsApproval(tool.inputAsJson())) {
                                hasPendingApproval = true
                                ref.copy(tool = tool.copy(approvalState = ToolApprovalState.Pending))
                            } else {
                                ref
                            }
                        }
                    }
                }
                // 有调用改成了 Pending（或写了 unavailable）就先写回消息
                if (gated.map { it.tool } != refs.map { it.tool }) {
                    val gatedByIndex = gated.associate { it.partIndex to it.tool }
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.mapIndexed { index, part ->
                        gatedByIndex[index]
                            ?.takeIf { part is UIMessagePart.Tool && part.toolCallId == it.toolCallId }
                            ?: part
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    send(GenerationChunk.Messages(messages))
                }
                return gated to hasPendingApproval
            }

            // 没有待处理的调用才请求模型
            if (pendingTools.isEmpty()) {
                // 本轮助手消息已有的 part 来自之前的步骤（或续写前的内容），不再转换
                val newPartsFrom = messages.lastOrNull()
                    ?.takeIf { it.role == MessageRole.ASSISTANT }?.parts?.size ?: 0
                val stepOutcome = generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    snapshotProvider = requestSnapshotProvider,
                    onUpdateMessages = { updated ->
                        // 循环内部保留原始内容（think 标签等要等这一步结束才真正处理），
                        // 发出去的是本步新 part 做过视觉转换的版本
                        messages = updated
                        send(GenerationChunk.Messages(updated.withTransformedStep(newPartsFrom) {
                            it.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings,
                            )
                        }))
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    requestSessionId = requestSessionId,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    harnessMinimalWorkspace = harnessMinimalWorkspace,
                    exposeUpload = exposeUpload,
                    requestObserver = requestObserver,
                    rateLimitRetry = rateLimitRetry,
                )
                messages = messages.withTransformedStep(newPartsFrom) {
                    it.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    ).visualTransforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    ).onGenerationFinish(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    )
                }
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                // 本 step 成功 attempt 的冻结快照：审批判定/后续签发一律查这张表，
                // 不用最新 Override 反解 alias。
                val issuedSnapshot = stepOutcome.toolSnapshot ?: fixedSnapshot
                lastIssuedSnapshot = issuedSnapshot
                // 拼接参数（{A}{B}）拆回独立调用：须在审批判定前，让每个调用按
                // 自己的参数审批/执行/回写。诊断日志只记身份与计数，不记参数正文。
                // 名字不在本轮表里的调用不拆（执行端只会报 not found）。
                val (splitLast, toolCallSplits) = messages.last()
                    .splitConcatenatedToolCalls(json, issuedSnapshot.bindings.keys)
                if (toolCallSplits.isNotEmpty()) {
                    toolCallSplits.forEach { split ->
                        Log.w(
                            TAG,
                            "generateText: split concatenated tool arguments " +
                                "(tool=${split.toolName}, callId=${split.toolCallId}, " +
                                "segments=${split.segments}, " +
                                "duplicatesDropped=${split.duplicatesDropped}, " +
                                "provider=${provider::class.simpleName}, model=${model.modelId})",
                        )
                    }
                    messages = messages.dropLast(1) + splitLast
                }
                send(GenerationChunk.Messages(messages))

                // 本轮新产生的未执行工具调用, 带各自 part 下标。已执行 part (含同 id
                // 的旧调用) 不参与: 回写一律经 ref 定位, 不会污染历史输出。
                // 注意消息下标在生成后重新取: generateInternal 会把新的 assistant
                // 消息追加到列表末尾。
                val toolCallRefs = messages.last().parts.mapIndexedNotNull { index, part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        ToolPartRef(messages.lastIndex, index, part)
                    } else null
                }
                val stepFinished = GenerationChunk.StepFinished(
                    stepIndex = stepIndex,
                    usage = stepOutcome.usage,
                    finishReason = stepOutcome.finishReason,
                    pendingToolCount = toolCallRefs.size,
                    stepMessages = messages,
                )
                notifyObserver(eventsObserver, stepFinished)
                send(stepFinished)
                if (toolCallRefs.isEmpty()) {
                    // no tool calls, break
                    endReason = GenerationChunk.GenerationEnd.Reason.COMPLETED
                    break
                }

                val (gatedRefs, hasPendingApproval) = gateApprovals(toolCallRefs)
                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    endReason = GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL
                    break
                }
                toolsToProcess = gatedRefs
            } else {
                // 审批之后继续：同一批里免审批的调用按当前定义重新判定，仍有调用在等
                // 审批就继续等，否则整批按原顺序处理。
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} unexecuted tools")
                val (gatedRefs, hasPendingApproval) = gateApprovals(pendingTools)
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: still waiting for tool approval")
                    endReason = GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL
                    break
                }
                toolsToProcess = gatedRefs
            }

            // Handle tools (execute approved tools, handle denied tools)
            // 单个调用的处理：结果追加进 [executedTools]（调用方负责回写/发送）。
            suspend fun runTool(ref: ToolPartRef, executedTools: MutableList<ToolPartRef>) {
                val tool = ref.tool
                // 审批前验证阶段已写入完成态结果（unavailable 等）的 part 不再走
                // 审批状态分派；原样进入回写集合。
                if (tool.isExecuted) {
                    executedTools += ref
                    return
                }
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += ref.copy(
                            tool = tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += ref.copy(
                            tool = tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(answer)
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        // 真正开始执行的时刻；校验阶段就失败的调用（找不到工具、参数非法）保持为空，不计时
                        var startedAt: Instant? = null
                        try {
                            // 最新策略快照（provider 模式每调用点取一次；固定 caller 即
                            // 入参包装）。守卫在 execute 内仍会按最新 DB 复核。取不到
                            // 最新快照时明确受限写 unavailable：绝不回落旧快照执行
                            // （禁用后沿用旧表会复活已关能力），也不产出泛化 error。
                            val executionSnapshot = runCatching { currentExecutionSnapshot() }
                                .getOrElse { failure ->
                                    if (failure is CancellationException) throw failure
                                    Log.w(
                                        TAG,
                                        "generateText: tool snapshot refresh failed before " +
                                            "execution (callId=${tool.toolCallId})",
                                        failure,
                                    )
                                    executedTools += ref.copy(
                                        tool = tool.copy(
                                            output = unavailableToolParts(
                                                json,
                                                "SNAPSHOT_REFRESH_FAILED",
                                                tool.toolName,
                                            )
                                        )
                                    )
                                    return
                                }
                            val toolDef = executionSnapshot.bindings[tool.toolName]
                            if (toolDef == null) {
                                Log.w(
                                    TAG,
                                    "generateText: tool not found (tool=${tool.toolName}, " +
                                        "callId=${tool.toolCallId}, " +
                                        "provider=${provider::class.simpleName}, model=${model.modelId})",
                                )
                                executedTools += ref.copy(
                                    tool = tool.copy(
                                        output = toolNotFoundParts(
                                            json,
                                            tool.toolName,
                                            executionSnapshot.bindings.keys,
                                        )
                                    )
                                )
                                return
                            }
                            // 非法参数（含无法严格切分的拼接参数）：回灌可操作的错误，
                            // 不附堆栈；日志不记参数正文。
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse { parseFailure ->
                                Log.w(
                                    TAG,
                                    "generateText: invalid tool arguments JSON " +
                                        "(tool=${tool.toolName}, callId=${tool.toolCallId}, " +
                                        "inputLength=${tool.input.length}, " +
                                        "provider=${provider::class.simpleName}, model=${model.modelId})",
                                )
                                executedTools += ref.copy(
                                    tool = tool.copy(
                                        output = invalidToolArgumentsParts(json, tool.toolName, parseFailure)
                                    )
                                )
                                return
                            }
                            // 不记录 args 正文（避免泄露敏感参数/撑爆日志）；只留调用
                            // 标识。注意这是「请求执行」而非「已开始 body」——守卫准入
                            // 在 execute 包装内先行，日志不误称已执行。
                            Log.i(
                                TAG,
                                "generateText: dispatching tool ${toolDef.name} " +
                                    "(callId=${tool.toolCallId}, partIndex=${ref.partIndex}, " +
                                    "stableId=${toolDef.workspaceIdentity?.stableId ?: "-"})",
                            )
                            // 实时 metadata 发布通道（如 subagent 进度）。回调在调用方
                            //（工具所在）协程内挂起执行：gate 会把工具体放进独立
                            // SupervisorJob/OpElement 上下文，channelFlow 的 send 只走
                            // 通道不受 SafeCollector context 检查影响，天然合法。并行
                            // 调用的发布经 publishProgress 加锁（并合流限频）。定位按选取
                            // 时记录的下标, 不按 toolCallId: 空 id / 重复 id / 同 id 复用
                            // 已执行 call 时, 进度只写进本次调用自己的 part。
                            val toolExecutionContext = ToolExecutionContext(
                                toolCallId = tool.toolCallId,
                            ) { metadata ->
                                publishProgress { current ->
                                    updateToolPartMetadataAt(
                                        messages = current,
                                        messageIndex = ref.messageIndex,
                                        partIndex = ref.partIndex,
                                        expectedToolCallId = ref.tool.toolCallId,
                                        metadata = metadata,
                                    )
                                }
                            }
                            // 先把开始时间写进消息，界面才能在执行期间实时显示耗时
                            val startedNow = Clock.System.now()
                            startedAt = startedNow
                            publishProgress { current ->
                                markToolStartedAt(
                                    messages = current,
                                    messageIndex = ref.messageIndex,
                                    partIndex = ref.partIndex,
                                    expectedToolCallId = tool.toolCallId,
                                    startedAt = startedNow,
                                )
                            }
                            runningTools.incrementAndGet()
                            val result = try {
                                toolDef.executeWithContext?.invoke(args, toolExecutionContext)
                                    ?: toolDef.execute(args)
                            } finally {
                                runningTools.decrementAndGet()
                            }
                            // 旧 hasShellAccess 口径仅作 capabilities==null（固定 caller）
                            // 的 fallback；快照 caller 由 planToolOutputTruncation 按
                            // 真实能力判定，不再用 Override 反查。截断提示渲染前再取一次
                            // 最新能力：工具执行/返回期间 readback 可能被禁用，不能继续
                            // 指引去读已不可回读的落盘文件；取不到时回退执行快照（仅影响
                            // 提示文案，不影响已完成的执行判定）。
                            val outputSnapshot = runCatching { currentExecutionSnapshot() }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull() ?: executionSnapshot
                            val fallbackCanSave = outputSnapshot.tools.any {
                                it.workspaceIdentity?.stableId == BuiltinToolIds.WORKSPACE_SHELL ||
                                    it.name == BuiltinToolIds.WORKSPACE_SHELL ||
                                    resolveBuiltinToolId(it.name, settings.toolOverrides) ==
                                    BuiltinToolIds.WORKSPACE_SHELL
                            }
                            executedTools += ref.copy(
                                tool = tool.timedSince(startedAt).copy(
                                    output = maybeTruncateToolOutput(
                                        toolCallId = tool.toolCallId,
                                        exempt = isToolOutputTruncationExempt(tool.toolName, settings.toolOverrides),
                                        output = result,
                                        capabilities = outputSnapshot.workspaceCapabilities,
                                        fallbackCanSave = fallbackCanSave,
                                    ).ifEmpty { listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)) }
                                )
                            )
                        } catch (policyCancelled: WorkspacePolicyCancellationException) {
                            // gate/范围策略取消（typed CE，主 job 仍 active）→ 记
                            // outcome_unknown 结果继续处理其余工具，不取消整次生成。
                            // 用户整体取消时 ensureActive 抛原 CE 上抛（不发正常
                            // GenerationEnd、不误记 provider 错误）。
                            currentCoroutineContext().ensureActive()
                            Log.w(
                                TAG,
                                "generateText: tool call cancelled in-flight by workspace " +
                                    "policy change (callId=${tool.toolCallId}, " +
                                    "partIndex=${ref.partIndex})",
                            )
                            executedTools += ref.copy(
                                tool = tool.timedSince(startedAt).copy(
                                    output = scopeCancelledToolParts(
                                        json,
                                        tool.toolName,
                                        policyCancelled,
                                    )
                                )
                            )
                        } catch (cancelled: CancellationException) {
                            // 其余取消一律向上传播（用户停止/外部 CE），不吞为工具结果。
                            // 并行段里 awaitAll 会把它重抛给段，段内其余调用随之取消。
                            throw cancelled
                        } catch (progress: ToolProgressException) {
                            throw (progress.cause ?: progress)
                        } catch (failure: Throwable) {
                            failure.printStackTrace()
                            executedTools += ref.copy(
                                tool = tool.timedSince(startedAt).copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${failure.javaClass.name}] ${failure.message}")
                                                        append("\n${failure.stackTraceToString().take(MAX_TOOL_ERROR_CHARS)}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                            )
                        }
                    }
                }
            }

            // 保序分段：连续的可并行调用合成一段同时执行（最多 toolLimit 个），独占调用
            // 单独成段并作为分隔点；段与段按原顺序进行。关闭并行/上限 1 = 逐个执行。
            val toolLimit = settings.toolExecution.effectiveLimit()
            val scheduling = schedulingSnapshot
            fun runsInParallel(ref: ToolPartRef): Boolean {
                if (toolLimit <= 1 || scheduling == null) return false
                val tool = ref.tool
                // 不执行的 part（已有结果/拒绝/已回答/待审批）不构成分隔点
                if (tool.isExecuted) return true
                when (tool.approvalState) {
                    is ToolApprovalState.Denied,
                    is ToolApprovalState.Answered,
                    is ToolApprovalState.Pending -> return true
                    else -> Unit
                }
                // 找不到定义/参数不合法：执行端会立即写错误，按独占处理（安全默认）
                val definition = scheduling.bindings[tool.toolName] ?: return false
                val args = runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }
                    .getOrNull() ?: return false
                return definition.concurrency.isParallel(args)
            }
            val segments = mutableListOf<List<ToolPartRef>>()
            var openSegment: MutableList<ToolPartRef>? = null
            toolsToProcess.forEach { ref ->
                if (runsInParallel(ref)) {
                    val segment = openSegment ?: mutableListOf<ToolPartRef>().also {
                        openSegment = it
                        segments += it
                    }
                    segment += ref
                } else {
                    openSegment = null
                    segments += listOf(ref)
                }
            }
            val executedTools = java.util.Collections.synchronizedList(arrayListOf<ToolPartRef>())
            suspend fun runToolAndPublish(ref: ToolPartRef) {
                val produced = arrayListOf<ToolPartRef>()
                runTool(ref, produced)
                produced.forEach { executed ->
                    executedTools += executed
                    publishToolResult(executed)
                }
            }
            try {
                for (segment in segments) {
                    if (segment.size == 1) {
                        runToolAndPublish(segment.single())
                    } else {
                        Log.i(TAG, "generateText: running ${segment.size} tool calls in parallel (limit=$toolLimit)")
                        coroutineScope {
                            val permits = Semaphore(toolLimit)
                            segment.map { ref ->
                                async { permits.withPermit { runToolAndPublish(ref) } }
                            }.awaitAll()
                        }
                    }
                }
                // 末尾回写会发完整状态；残留的合流发送不能晚于本段与后续单线程逻辑并发
                progressFlushJob?.cancelAndJoin()
            } finally {
                progressFlushJob?.cancel()
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                endReason = GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            // 执行期间可能已通过 publishMetadata 发布了实时 metadata (如 subagent 进度),
            // 合并时以 messages 里最新的 part metadata 为准, 避免执行前的 tool 快照覆盖它。
            // 回写严格按 part 下标定位: 空 id / 重复 id / 同 id 复用已执行 call 时,
            // 新结果只进自己的 part, 不覆盖已执行历史, 也不把两个 call 混为一个。
            val executedByIndex = executedTools.associate { it.partIndex to it.tool }
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.mapIndexed { index, part ->
                executedByIndex[index]
                    ?.takeIf { part is UIMessagePart.Tool && part.toolCallId == it.toolCallId }
                    ?.let { it.copy(metadata = (part as UIMessagePart.Tool).metadata ?: it.metadata) }
                    ?: part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            send(GenerationChunk.Messages(messages))
        }

        // 每次正常循环退出恰一次终态事件; maxSteps 耗尽时仍有待执行工具才算 STEP_LIMIT,
        // 其余正常退出路径在循环内已显式标注原因。异常/取消在到达这里之前抛出。
        val endEvent = GenerationChunk.GenerationEnd(
            endReason ?: GenerationChunk.GenerationEnd.Reason.STEP_LIMIT
        )
        notifyObserver(eventsObserver, endEvent)
        send(endEvent)

    }.flowOn(Dispatchers.IO)

    /**
     * 生成压缩摘要。
     *
     * 请求按正常对话组装（系统提示词、记忆、工具定义、输入转换、参数和 sessionId 都相同），
     * 只在末尾追加压缩指令：前缀与上一轮对话一致，可以命中提示词缓存，模型也能看到工具调用和结果。
     * 工具定义必须保留，它属于请求前缀，去掉或改动 tool_choice 都会让缓存失效。
     * 不经过请求开始侧观测（缓存保活不重放压缩请求）。
     *
     * @throws CompressionToolCallException 模型没写摘要而是发起了工具调用
     */
    internal suspend fun generateCompressionSummary(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer>,
        assistant: Assistant,
        memories: List<MemoryFile>,
        toolSnapshotProvider: suspend () -> ChatToolSnapshot,
        compressPrompt: String,
        conversationSystemPrompt: String?,
        conversationId: Uuid,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        harnessMinimalWorkspace: Boolean = false,
        exposeUpload: Boolean = true,
    ): String {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
        val inputs = ProviderRequestInputs(
            assistant = assistant,
            settings = settings,
            transformers = inputTransformers,
            model = model,
            provider = provider,
            snapshotProvider = toolSnapshotProvider,
            memories = memories,
            requestSessionId = conversationId.toString(),
            processingStatus = MutableStateFlow(null),
            conversationSystemPrompt = conversationSystemPrompt,
            conversationId = conversationId,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            workspaceCwd = workspaceCwd,
            harnessMinimalWorkspace = harnessMinimalWorkspace,
            exposeUpload = exposeUpload,
        )
        val result = executeProviderRequestWithRetry(
            processingStatus = inputs.processingStatus,
            enabled = settings.networkSetting.enableAutoRetry,
        ) {
            val attempt = buildCurrentProviderRequest(inputs, messages)
            providerImpl.generateText(
                providerSetting = provider,
                // 压缩指令不经过输入转换器（模板转换器会把其中的花括号当成模板语法）
                messages = attempt.internalMessages + UIMessage.user(compressPrompt),
                // 助手的输出上限是给聊天回复定的，结构化摘要写不完会被截断；max_tokens 不属于请求前缀
                params = attempt.params.copy(maxTokens = null),
            )
        }
        val summary = result.message.compressionSummaryOrNull() ?: throw CompressionToolCallException()
        return summary.ifBlank { error("The model returned an empty summary") }
    }

    /**
     * 观测口只做轻量统计。回调异常不得冒充 provider 错误也不应终止生成:
     * 取消异常照常传播, 其余只记日志后按原序继续, 下游 emit 不受影响。
     */
    /** 请求开始侧观测：同 [notifyObserver]，回调异常只记日志，取消照常传播。 */
    private fun notifyRequestObserver(
        observer: ProviderRequestObserver?,
        provider: ProviderSetting,
        internalMessages: List<UIMessage>,
        params: TextGenerationParams,
        contextMessages: List<UIMessage>,
    ) {
        if (observer == null) return
        try {
            observer.onProviderRequestStart(provider, internalMessages, params, contextMessages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "requestObserver threw; continuing", failure)
        }
    }

    private fun notifyObserver(observer: GenerationEventObserver?, chunk: GenerationChunk) {
        if (observer == null) return
        try {
            observer.onEvent(chunk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "eventsObserver threw for ${chunk.javaClass.simpleName}; continuing", failure)
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        snapshotProvider: suspend () -> ChatToolSnapshot,
        memories: List<MemoryFile>,
        stream: Boolean,
        requestSessionId: String?,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        harnessMinimalWorkspace: Boolean = false,
        exposeUpload: Boolean = true,
        requestObserver: ProviderRequestObserver? = null,
        rateLimitRetry: RateLimitRetryPolicy? = null,
    ): GenerationStepOutcome {

        val requestInputs = ProviderRequestInputs(
            assistant = assistant,
            settings = settings,
            transformers = transformers,
            model = model,
            provider = provider,
            snapshotProvider = snapshotProvider,
            memories = memories,
            requestSessionId = requestSessionId,
            processingStatus = processingStatus,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationId = conversationId,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            workspaceCwd = workspaceCwd,
            harnessMinimalWorkspace = harnessMinimalWorkspace,
            exposeUpload = exposeUpload,
        )
        suspend fun buildCurrentAttempt(): ProviderAttemptRequest = buildCurrentProviderRequest(requestInputs, messages)

        var messages: List<UIMessage> = messages
        // 本次成功 attempt 的观测结果。重试时在 attempt 开始时重置, 失败 attempt 的
        // 统计被丢弃, 保证“一次成功的 provider 请求恰一次 StepFinished”的口径。
        var stepUsage: TokenUsage? = null
        var stepFinishReason: String? = null
        // 本次 step 成功 attempt 使用的工具快照（签发绑定/审批判定的冻结表）。
        var stepSnapshot: ChatToolSnapshot? = null
        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                // 多轮工具调用共用同一条助手消息，消息上已有的 usage 和耗时来自之前的步骤。
                // 本步从清空的统计开始（StreamChunkHandler 的 merge 只适合同一次请求内的
                // 快照），结束时再与之前的步骤累加。
                val stepBase = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
                val responseBaseMessages = if (stepBase != null) {
                    messages.dropLast(1) + stepBase.copy(usage = null, generationTimeMs = null)
                } else {
                    messages + UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        modelId = model.id,
                    )
                }
                fun List<UIMessage>.withStepStats(firstChunk: TimeMark?): List<UIMessage> =
                    dropLast(1) + last().withStepStats(
                        stepBase = stepBase,
                        stepTimeMillis = firstChunk?.elapsedNow()?.inWholeMilliseconds ?: 0L,
                    ).withModelSnapshot(model)
                var retryCount = 0
                var rateLimitRetries = 0

                while (true) {
                    // 每个 attempt（含重试）重新取完整快照 + 重建 prompt/params；
                    // 失败不落盘，成功 attempt 的快照即本 step 的签发冻结表。
                    // 构建后复查快照时效（见 buildCurrentAttempt）。
                    val attempt = buildCurrentAttempt()
                    val attemptSnapshot = attempt.snapshot
                    val internalMessages = attempt.internalMessages
                    val params = attempt.params
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    var attemptUsage: TokenUsage? = null
                    var attemptFinishReason: String? = null
                    // TPS 只算模型实际生成的时间：从首个 chunk 到响应结束
                    var firstChunk: TimeMark? = null
                    try {
                        notifyRequestObserver(requestObserver, provider, internalMessages, params, messages)
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params
                        ).collect { chunk ->
                            try {
                                if (firstChunk == null) firstChunk = TimeSource.Monotonic.markNow()
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                // attempt 内多个 Usage chunk 归并: p/c 正值覆盖、0 保留;
                                // totalTokens 取最新的有效上报 (独立 total/完整快照覆盖,
                                // 无新上报时保留在案值), 允许向下修正, 不是历史 max。
                                when (chunk) {
                                    is StreamChunk.Usage -> attemptUsage = mergeStepUsage(attemptUsage, chunk.usage)
                                    is StreamChunk.Finish -> attemptFinishReason = chunk.finishReason
                                    else -> {}
                                }
                                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                                onUpdateMessages(attemptMessages.withStepStats(firstChunk))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        messages = attemptMessages.withStepStats(firstChunk)
                        stepUsage = attemptUsage
                        stepFinishReason = attemptFinishReason
                        stepSnapshot = attemptSnapshot
                        break
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        // 协议错误 (decoder 直接上抛, 或 handler/onUpdateMessages
                        // 抛出的 ToolStreamProtocolException 被 collect 回调包装成
                        // StreamChunkHandlingException): 先 unwrap 再分类。
                        // 整 attempt 标记失败 —— 不重试、不发 StepFinished/GenerationEnd,
                        // 由权威消费侧按 descriptor 回滚该消息到本 attempt 开始前的
                        // baseMessage (flowOn 的在途事件可能已丢, 不能靠 emit 恢复)。
                        // 有界 cause 链遍历: StreamChunkHandlingException 只包装一层,
                        // 但不排除未来回调再套壳, 按链分类避免漏识别。
                        val protocolError = generateSequence<Throwable>(error) { it.cause }
                            .take(8)
                            .filterIsInstance<ToolStreamProtocolException>()
                            .firstOrNull()
                        if (protocolError != null) {
                            // 回滚到这一步开始前的消息（含之前步骤的统计）
                            val baseMessage = stepBase ?: responseBaseMessages.last()
                            Log.w(
                                TAG,
                                "generateInternal: stream protocol failure " +
                                    "(code=${protocolError.code}, attempt=$retryCount, " +
                                    "conversation=$conversationId, assistant=${baseMessage.id}); " +
                                    "failing attempt closed",
                            )
                            throw GenerationAttemptProtocolException(
                                protocolError = protocolError,
                                conversationId = conversationId,
                                assistantMessageId = baseMessage.id,
                                attemptIndex = retryCount,
                                baseMessage = baseMessage,
                            )
                        }
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        if (awaitRateLimitRetry(error, rateLimitRetries, rateLimitRetry)) {
                            rateLimitRetries++
                            continue
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry,
                        )
                    }
                }
            } else {
                // 非流式拿不到首 token 时间，只能统计最后一次尝试的整个请求耗时
                var requestMark = TimeSource.Monotonic.markNow()
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    enabled = settings.networkSetting.enableAutoRetry,
                    rateLimitRetry = rateLimitRetry,
                ) {
                    // 非流式同样每 attempt 重建（快照/提示/能力/策略一致），
                    // 且构建后复查时效——发送面不落半更新快照。
                    val attempt = buildCurrentAttempt()
                    stepSnapshot = attempt.snapshot
                    notifyRequestObserver(
                        requestObserver,
                        provider,
                        attempt.internalMessages,
                        attempt.params,
                        messages,
                    )
                    requestMark = TimeSource.Monotonic.markNow()
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = attempt.internalMessages,
                        params = attempt.params,
                    )
                }
                val requestMillis = requestMark.elapsedNow().inWholeMilliseconds
                // 同流式：本步从清空的统计开始，一次请求只有一份 usage，再与之前的步骤累加
                val stepBase = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
                val stepStart = if (stepBase != null) {
                    messages.dropLast(1) + stepBase.copy(usage = null, generationTimeMs = null)
                } else {
                    messages
                }
                // 多步工具调用共用同一条助手消息：本步的文字/思考单独成段，不拼进上一步末尾
                messages = stepStart.handleTextGenerationResult(result = result, model = model, newStep = true)
                messages = messages.dropLast(1) + messages.last()
                    .copy(usage = result.usage)
                    .withStepStats(stepBase, requestMillis)
                    .withModelSnapshot(model)
                onUpdateMessages(messages)
                stepUsage = result.usage
                stepFinishReason = result.finishReason
            }
        } finally {
            processingStatus.value = null
        }
        return GenerationStepOutcome(
            usage = stepUsage,
            finishReason = stepFinishReason,
            toolSnapshot = stepSnapshot,
        )
    }

    // 每个真实 provider 请求（含网络重试 attempt）重建完整输入：工具表、
    // 系统提示里的工具段、transformer 能力快照与 wire 策略来自同一份
    // ChatToolSnapshot，热禁用/改名在下一次请求生效，不会发出半更新请求。
    // 快照/构建失败不经网络重试路径（调用点在 retry catch 之前）。
    private suspend fun buildProviderRequest(
        inputs: ProviderRequestInputs,
        messages: List<UIMessage>,
    ): ProviderAttemptRequest = with(inputs) {
        // 按 provider 要求重映射 wire 工具名（如 Devin 的保留名 bash）。
        // 改名在快照消费点发生：tools/bindings/capabilities/reminder 同源
        // 替换，wireRenames 记进快照供 params 回放历史调用名。
        val snapshot = snapshotProvider().withProviderWireToolRenames(
            provider = provider,
            overrides = settings.toolOverrides,
        )
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt = assistant.effectiveSystemPrompt(conversationSystemPrompt)
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories, settings.toolOverrides))
                }
                // 工具prompt（本 attempt 快照的 tools）
                snapshot.tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
                parallelToolCallsPrompt(settings.toolExecution, snapshot.tools)?.let {
                    appendLine()
                    append(it)
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
            harnessMinimalWorkspace = harnessMinimalWorkspace,
            exposeUpload = exposeUpload,
            workspaceCapabilities = snapshot.workspaceCapabilities,
        )
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = snapshot.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            defaultSessionId = if (conversationId == null) requestSessionId else null,
            sessionId = requestSessionId,
            workspaceToolPolicy = snapshot.wirePolicy,
            toolWireRenames = snapshot.wireRenames,
        )
        ProviderAttemptRequest(snapshot, internalMessages, params)
    }

    // 请求构建完成后、notifyRequestObserver/streamText 之前复查快照时效：
    // transformer 自身会重读 enablement/policy，构建期间策略若已变化，本
    // attempt 的提示/能力/策略就是半更新请求 → 整份重建（不是局部换表；
    // StreamChunkHandler 等也在下次 attempt 重建，绝不中途切换 decoder
    // 注册表）。复查不过 yield 后重建，上限内持续漂移即抛本地错误。
    private suspend fun buildCurrentProviderRequest(
        inputs: ProviderRequestInputs,
        messages: List<UIMessage>,
    ): ProviderAttemptRequest {
        var attempt = buildProviderRequest(inputs, messages)
        var rebuilds = 0
        while (!attempt.snapshot.isCurrent()) {
            rebuilds++
            if (rebuilds > MAX_STALE_SNAPSHOT_REBUILDS) {
                throw StaleToolSnapshotException(MAX_STALE_SNAPSHOT_REBUILDS)
            }
            // 尊重取消 + 让出正在发布策略变更的一方，避免紧忙重建循环
            yield()
            Log.i(
                TAG,
                "tool snapshot went stale during request build; " +
                    "rebuilding attempt ($rebuilds/$MAX_STALE_SNAPSHOT_REBUILDS)",
            )
            attempt = buildProviderRequest(inputs, messages)
        }
        return attempt
    }


    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        rateLimitRetry: RateLimitRetryPolicy? = null,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        var rateLimitRetries = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (awaitRateLimitRetry(error, rateLimitRetries, rateLimitRetry)) {
                    rateLimitRetries++
                    continue
                }
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                )
            }
        }
    }

    /**
     * 限流退避：[error] 是 429/503 且未超过 [policy] 的次数时等待后返回 true（调用方重发），
     * 否则返回 false 交给后续分类。等待取 `Retry-After`，没有则指数退避，都不超过上限。
     */
    private suspend fun awaitRateLimitRetry(
        error: Throwable,
        retries: Int,
        policy: RateLimitRetryPolicy?,
    ): Boolean {
        if (policy == null || retries >= policy.maxRetries) return false
        val limited = rateLimitedStatus(error) ?: return false
        currentCoroutineContext().ensureActive()
        val wait = (limited.retryAfterMillis ?: (policy.baseDelayMillis shl retries))
            .coerceIn(0, policy.maxDelayMillis)
        Log.w(
            TAG,
            "Provider rate limited (HTTP ${limited.statusCode}), retrying in ${wait}ms " +
                "(${retries + 1}/${policy.maxRetries})",
        )
        delay(wait)
        return true
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        if (!enabled || error !is IOException || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        val retryDelay = INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount
        processingStatus.value = statusStrings.resolve(
            R.string.chat_generation_network_retrying,
            getNetworkErrorMessage(error),
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun getNetworkErrorMessage(error: IOException): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return statusStrings.resolve(messageRes)
    }

    /**
     * 工具输出截断。判定委托 [planToolOutputTruncation]：
     * - [capabilities] 非 null（快照 caller）：按真实能力——有可回读工具且
     *   `/tool_outputs` 已挂载才保存全文并给回读指引；否则明确截断、不保存、
     *   不假称可回读（与是否有 shell 无关，大输出也截断）。
     * - [capabilities] 为 null（固定 tools 旧 caller）：沿用旧口径——
     *   [fallbackCanSave]（原 hasShellAccess）为 true 才保存，否则原样返回。
     * - [exempt]（[isToolOutputTruncationExempt]）为 true 时一律原样返回。
     */
    private fun maybeTruncateToolOutput(
        toolCallId: String,
        exempt: Boolean,
        output: List<UIMessagePart>,
        capabilities: WorkspaceRequestCapabilities?,
        fallbackCanSave: Boolean,
    ): List<UIMessagePart> {
        // 免截断的工具（搜索）：截断后不再是 JSON，结果列表显示为空、引用跳转失效
        if (exempt) return output
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        val plan = planToolOutputTruncation(
            totalChars = totalChars,
            maxOutputChars = MAX_TOOL_OUTPUT_CHARS,
            capabilities = capabilities,
            fallbackCanSave = fallbackCanSave,
        ) ?: return output

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        var fileName: String? = null
        if (plan.persistFullOutput) {
            Log.i(TAG, "maybeTruncateToolOutput: persisting tool $toolCallId output ($totalChars chars)")
            // toolCallId 由 provider 产生，落盘前过滤掉路径分隔符等非法字符
            fileName = toolOutputFileName(toolCallId)
            val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
            pruneStaleToolOutputs(outputDir)
            File(outputDir, fileName).writeText(fullText)
        } else {
            Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars, no readback)")
        }

        return listOf(
            UIMessagePart.Text(
                plan.renderNotice(fileName, preview),
                metadata = textParts.firstNotNullOfOrNull { it.metadata },
            )
        ) + nonTextParts
    }

    // 截断输出与消息无强引用，按 mtime 做 LRU 兜底清理，避免目录无限增长。
    private fun pruneStaleToolOutputs(dir: File) {
        val cutoff = System.currentTimeMillis() - TOOL_OUTPUT_TTL_MILLIS
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff && !file.delete()) {
                    Log.w(TAG, "pruneStaleToolOutputs: failed to delete ${file.name}")
                }
            }
        }.onFailure { Log.w(TAG, "pruneStaleToolOutputs failed", it) }
    }

}

/**
 * 把 [metadata] 写回 [messages] 里 [messageIndex] 消息的第 [partIndex] 个 part,
 * 其他 part 不动。只被 ToolExecutionContext 发布回调调用, 保持在原 flow 协程内顺序执行。
 *
 * 严格按下标定位, 不按 toolCallId 匹配: 空 id / 重复 id / 同 id 复用已执行 call
 * 时, 进度只写进本次调用自己的 part, 不会污染同 id 的已执行历史 part, 也不会把
 * 两个并行调用的进度写到同一处。[expectedToolCallId] 仅作确定性健全校验
 * (下标处的 part 必须与选取时记录的是同一个调用); 校验不过时原样返回,
 * 不做模糊查找。
 */
/**
 * 把一次调用的结果写回它自己的 part（按下标定位并核对 toolCallId，不按 id 查找）。
 * metadata 以 messages 里最新的为准：执行期间发布的实时进度不被执行前的快照覆盖。
 */
private fun applyToolResultAt(messages: List<UIMessage>, executed: ToolPartRef): List<UIMessage> {
    val target = messages.getOrNull(executed.messageIndex) ?: return messages
    val part = target.parts.getOrNull(executed.partIndex) as? UIMessagePart.Tool ?: return messages
    if (part.toolCallId != executed.tool.toolCallId) return messages
    val updated = target.copy(
        parts = target.parts.toMutableList().apply {
            set(executed.partIndex, executed.tool.copy(metadata = part.metadata ?: executed.tool.metadata))
        }
    )
    return messages.toMutableList().apply { set(executed.messageIndex, updated) }
}

/**
 * 截断落盘的文件名：provider 的 toolCallId 去掉非法字符；去掉了字符时追加原 id 的短哈希，
 * 避免 `a:b`/`a#b` 这类 id 落到同一个文件（并行调用会同时写）。
 */
internal fun toolOutputFileName(toolCallId: String): String {
    val sanitized = toolCallId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
    if (sanitized.isBlank()) return "tool_output_${Uuid.random().toHexString().take(8)}.txt"
    if (sanitized == toolCallId) return "$sanitized.txt"
    val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(toolCallId.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    return "${sanitized}_$hash.txt"
}

/**
 * 输出转换只处理这一步新生成的 part（下标 [newPartsFrom] 起）：之前步骤的内容已经处理过，
 * 再处理一遍会让正则这类改写叠加；也让每一步开头的 `<think>` 都能被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val keep = newPartsFrom.coerceIn(0, parts.size)
    val transformed = transform(listOf(copy(parts = parts.drop(keep)))).single()
    return transformed.copy(parts = parts.take(keep) + transformed.parts)
}

/** 压缩请求的回复里有工具调用而没有摘要。 */
class CompressionToolCallException : IllegalStateException("The model called a tool instead of writing the summary")

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是「我先看看文件」之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容也要在这里剥掉，否则会存进摘要。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .single()
        .toText()
        .trim()
}

/** 由本轮模型生成的消息记下模型名称，模型以后被删掉也能显示是谁生成的。 */
internal fun UIMessage.withModelSnapshot(model: Model): UIMessage =
    if (modelId == model.id && modelSnapshot == null) {
        copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
    } else {
        this
    }

/** 开启并行且至少两个工具可并行时的提示文案（子代理可并行时追加子代理句）。 */
internal fun parallelToolCallsPrompt(config: ToolExecutionConfig, tools: List<Tool>): String? {
    if (config.effectiveLimit() <= 1) return null
    val parallelTools = tools.filter { it.concurrency.mayRunInParallel }
    if (parallelTools.size < 2) return null
    val subagentParallel = parallelTools.any { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT }
    return if (subagentParallel) "$PARALLEL_TOOL_CALLS_PROMPT $PARALLEL_SUBAGENTS_PROMPT" else PARALLEL_TOOL_CALLS_PROMPT
}

/**
 * 在 [messageIndex]/[partIndex] 的工具 part 上写入开始执行时间（同时清掉可能残留的结束时间）。
 * 和 [updateToolPartMetadataAt] 一样按下标定位并核对 [expectedToolCallId]，对不上原样返回。
 */
internal fun markToolStartedAt(
    messages: List<UIMessage>,
    messageIndex: Int,
    partIndex: Int,
    expectedToolCallId: String,
    startedAt: Instant,
): List<UIMessage> {
    val target = messages.getOrNull(messageIndex) ?: return messages
    val part = target.parts.getOrNull(partIndex) as? UIMessagePart.Tool ?: return messages
    if (part.toolCallId != expectedToolCallId) return messages
    val updated = target.copy(
        parts = target.parts.toMutableList().apply {
            set(partIndex, part.copy(startedAt = startedAt, finishedAt = null))
        }
    )
    return messages.toMutableList().apply { set(messageIndex, updated) }
}

/** 工具从 [startedAt] 开始执行并在此刻结束；[startedAt] 为空（没有真正执行）时原样返回。 */
internal fun UIMessagePart.Tool.timedSince(startedAt: Instant?): UIMessagePart.Tool =
    if (startedAt == null) this else copy(startedAt = startedAt, finishedAt = Clock.System.now())

internal fun updateToolPartMetadataAt(
    messages: List<UIMessage>,
    messageIndex: Int,
    partIndex: Int,
    expectedToolCallId: String,
    metadata: JsonObject,
): List<UIMessage> {
    val target = messages.getOrNull(messageIndex) ?: return messages
    val part = target.parts.getOrNull(partIndex) as? UIMessagePart.Tool ?: return messages
    if (part.toolCallId != expectedToolCallId) {
        Log.w(
            TAG,
            "updateToolPartMetadataAt: index $messageIndex/$partIndex expected $expectedToolCallId " +
                "but found ${part.toolCallId}; skip (no fuzzy dispatch)",
        )
        return messages
    }
    val updated = target.copy(
        parts = target.parts.toMutableList().apply {
            // Progress replaces its own UI metadata as before, but must not erase
            // provider-issued signature/order provenance or inject forged replacements.
            val providerKeys = setOf("interactions_signature", "interactions_step_index",
                "interactions_content_index", "interactions_exchange_key")
            val preserved = part.metadata.orEmpty().filterKeys { it in providerKeys }
            val progress = metadata.filterKeys { it !in providerKeys }
            set(partIndex, part.copy(metadata = JsonObject(progress + preserved)))
        }
    )
    return messages.toMutableList().apply { set(messageIndex, updated) }
}
