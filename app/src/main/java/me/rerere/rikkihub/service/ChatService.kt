package me.rerere.rikkihub.service

import me.rerere.rikkihub.data.ai.wrapOrSelf
import me.rerere.rikkihub.data.ai.UsageTag
import me.rerere.rikkihub.data.ai.UsageSource
import me.rerere.rikkihub.data.ai.UsageRecorder
import me.rerere.rikkihub.data.workspace.WorkspaceFileSnapshotTransformer
import me.rerere.rikkihub.data.workspace.withWorkspaceFileSnapshot
import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.cli.AcpException
import me.rerere.rikkihub.data.cli.AcpMcpGateway
import me.rerere.rikkihub.data.cli.AcpOpenResult
import me.rerere.rikkihub.data.cli.AcpParts
import me.rerere.rikkihub.data.cli.AcpPermissionOutcome
import me.rerere.rikkihub.data.cli.AcpPermissionRequest
import me.rerere.rikkihub.data.cli.AcpSession
import me.rerere.rikkihub.data.cli.AcpSessionManager
import me.rerere.rikkihub.data.cli.CliAgent
import me.rerere.rikkihub.data.cli.CliApprovalDecision
import me.rerere.rikkihub.data.cli.CliBusyException
import me.rerere.rikkihub.data.cli.CliLaunch
import me.rerere.rikkihub.data.cli.CliNotInstalledException
import me.rerere.rikkihub.data.cli.CliPermissionMode
import me.rerere.rikkihub.data.cli.CliPrompts
import me.rerere.rikkihub.data.cli.ConversationCliSession
import me.rerere.ai.ui.stripMessageTitle
import me.rerere.ai.ui.displayTextOf
import me.rerere.ai.core.TokenUsage
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ContextCheckpointKind
import me.rerere.ai.ui.ContextCheckpointMetadata
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.limitContext
import me.rerere.ai.provider.WorkspaceToolWirePolicy
import me.rerere.common.android.Logging
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.R
import me.rerere.ai.claudelife.CacheWarmRequest
import me.rerere.ai.claudelife.CacheWarmStatus
import me.rerere.ai.claudelife.cacheWarmPrefixIntact
import me.rerere.rikkihub.claudelife.ClaudeLifeManager
import me.rerere.rikkihub.claudelife.cacheWarmGoogleProtocolChange
import me.rerere.rikkihub.data.ai.ProviderRequestObserver
import me.rerere.rikkihub.data.ai.GenerationAttemptProtocolException
import me.rerere.rikkihub.data.ai.GenerationChunk
import me.rerere.rikkihub.data.ai.GenerationLoop
import me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkihub.data.ai.TranslationHandler
import me.rerere.rikkihub.data.ai.mcp.McpManager
import me.rerere.rikkihub.data.ai.tools.ChatToolFactory
import me.rerere.rikkihub.data.ai.tools.ChatToolSnapshot
import me.rerere.rikkihub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkihub.data.ai.tools.ToolCategory
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.ai.util.encodeLocalImageBase64
import me.rerere.rikkihub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkihub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.ai.transformers.readBoundedDocumentText
import me.rerere.rikkihub.data.workspace.resolveCliGuestPath
import me.rerere.rikkihub.data.workspace.resolveSafeLocalFile
import me.rerere.rikkihub.data.ai.transformers.OcrTransformer
import me.rerere.rikkihub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkihub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkihub.data.ai.tavern.ActivationRecord
import me.rerere.rikkihub.data.ai.tavern.ActiveCharacter
import me.rerere.rikkihub.data.ai.tavern.GreetingBuilder
import me.rerere.rikkihub.data.ai.tavern.PreparedTurn
import me.rerere.rikkihub.data.ai.tavern.RikkihubMacros
import me.rerere.rikkihub.data.ai.tavern.TavernInputProcessor
import me.rerere.rikkihub.data.ai.qubagu.Qubagu
import me.rerere.rikkihub.data.ai.qubagu.QubaguOutputTransformer
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefiner
import me.rerere.rikkihub.data.ai.qubagu.QubaguRecord
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefineException
import me.rerere.rikkihub.data.ai.qubagu.withQubagu
import me.rerere.rikkihub.data.ai.qubagu.withQubaguRecord
import me.rerere.rikkihub.data.ai.tavern.TavernRegexOutputTransformer
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.ai.tavern.TurnPreparation
import me.rerere.rikkihub.data.ai.tavern.TurnRequest
import me.rerere.rikkihub.data.ai.transformers.MessageTitleConventionTransformer
import me.rerere.rikkihub.data.ai.transformers.MessageTitleTransformer
import me.rerere.rikkihub.data.ai.transformers.TimeReminderTransformer
import me.rerere.tavern.core.GenerationType
import me.rerere.rikkihub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkihub.data.event.AppEvent
import me.rerere.rikkihub.data.event.AppEventBus
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.datastore.getAssistantById
import me.rerere.rikkihub.data.datastore.getCurrentAssistant
import me.rerere.rikkihub.data.datastore.getCurrentChatModel
import me.rerere.rikkihub.data.ai.tools.finishInterruptedSubagentTool
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.files.FileManagerService
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.files.extractUploadRelativePath
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.promptLibraryIds
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.appendClearContextCheckpoint
import me.rerere.rikkihub.data.model.fillModelSnapshots
import me.rerere.rikkihub.data.model.insertContextCheckpoint
import me.rerere.rikkihub.data.model.lastContextTokens
import me.rerere.rikkihub.data.model.legacyMainPrompt
import me.rerere.rikkihub.data.model.pendingToolCalls
import me.rerere.rikkihub.data.model.localFileUrls
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.data.mutation.ConversationVersion
import me.rerere.rikkihub.data.mutation.ConversationBusyException
import me.rerere.rikkihub.data.mutation.ConversationCommandApplier
import me.rerere.rikkihub.data.mutation.ConversationCommandContext
import me.rerere.rikkihub.data.mutation.ConversationHostIds
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FolderRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.memoryId
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceUsage
import me.rerere.rikkihub.data.workspace.WorkspaceHandleClass
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.utils.applyPlaceholders
import me.rerere.rikkihub.data.ai.AutoCompressMode
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionRequestLimitException
import me.rerere.rikkihub.data.ai.CompressionRunResult
import me.rerere.rikkihub.data.ai.CompressionTimeoutException
import me.rerere.rikkihub.data.ai.CompressionToolCallException
import me.rerere.rikkihub.data.ai.parallelToolCallsPrompt
import me.rerere.rikkihub.data.ai.prompts.buildChunkFinalPrompt
import me.rerere.rikkihub.data.ai.prompts.buildChunkStagePrompt
import me.rerere.rikkihub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkihub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkihub.data.ai.prompts.builtInCompressStructure
import me.rerere.tavern.prompt.AssembledItem
import me.rerere.tavern.prompt.PromptMarkers
import me.rerere.tavern.token.TokenCounter
import me.rerere.ai.util.isContextLengthError
import me.rerere.ai.core.sum
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val MAX_ERRORS = 50
// 等用户处理 CLI 审批卡的上限：到时按拒绝处理（前台通知一直挂着，不能无限等）
private const val CLI_APPROVAL_TIMEOUT_MS = 30 * 60_000L

internal fun backgroundTextGenerationParams(
    model: Model,
    conversationId: Uuid? = null,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
).let { params ->
    // Known background requests belong to their conversation; independent callers
    // keep the SDK default (including its manual-session-header compatibility).
    if (conversationId != null) params.copy(sessionId = conversationId.toString()) else params
}

internal fun requireTitleGenerationTarget(
    model: Model?,
    providerForModel: (Model) -> ProviderSetting?,
    missingModelMessage: String,
    missingProviderMessage: String,
): Pair<Model, ProviderSetting> {
    val resolvedModel = model ?: throw IllegalStateException(missingModelMessage)
    val provider = providerForModel(resolvedModel) ?: throw IllegalStateException(missingProviderMessage)
    return resolvedModel to provider
}

// 只认 fork 自己生成的序号：紧贴标题末尾、前面不是空白。「Notes (2024)」这类标题里的数字不当序号
private val forkTitleSuffixRegex = Regex("""(?<=\S)\((\d+)\)$""")

internal fun forkConversationTitle(sourceTitle: String, existingTitles: Set<String>): String {
    // 原会话还没有标题时保持为空，交给自动生成
    if (sourceTitle.isBlank()) return ""
    // 源标题已带 (N) 时递增序号，避免多次 fork 后叠成 xxx(1)(1)
    val suffix = forkTitleSuffixRegex.find(sourceTitle)
    val baseTitle = suffix?.let { sourceTitle.removeRange(it.range) } ?: sourceTitle
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    return generateSequence(start) { it + 1 }
        .map { "$baseTitle($it)" }
        .first { it !in existingTitles }
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    title = forkConversationTitle(source.title, existingTitles),
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    workspaceCwdScoped = source.workspaceCwdScoped,
    folderId = source.folderId,
    // 同一个 CLI、权限和模型设置，但另开 CLI 会话（带上复制过来的前文），两个分叉互不干扰
    cliSession = source.cliSession?.reset(),
)

internal fun createCompressedConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    title = forkConversationTitle(source.title, existingTitles),
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    workspaceCwdScoped = source.workspaceCwdScoped,
    folderId = source.folderId,
    tavernMetadata = source.tavernMetadata,
    characterId = source.characterId,
    compression = source.compression,
    cliSession = null,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
    /** 为 false 时回复本身不受影响（标题生成失败、工具不可用提醒），悬浮窗不把它当成这一轮失败 */
    val affectsReply: Boolean = true,
)

/** A failed CLI turn fixed by running [command] in the terminal of [workspaceId] ([solution] says which kind). */
class CliTurnException(
    message: String,
    val solution: ChatErrorSolution,
    val workspaceId: String,
    val command: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

enum class ChatErrorSolution {
    CheckFastModelSettings,
    /** Open the workspace terminal on the conversation's CLI login command. */
    CliLogin,
    /** Open the workspace terminal on the conversation's CLI install command. */
    CliInstall,
    /** Open the workspace detail page to repair missing rootfs or working directory. */
    WorkspaceDetail,
    /** The reply overflowed the context window: compress the history, then regenerate. */
    CompressAndRetry,
}

/**
 * 会话归属助手缺失/被删（边界）：本轮不恢复任何工作区工具，明确失败。
 * 不同于普通非法输入——不暗示用户操作可修；调用方按生成错误上报。
 */
internal class ConversationAssistantUnavailableException(
    conversationId: Uuid,
    assistantId: Uuid,
) : IllegalStateException(
    "assistant $assistantId of conversation $conversationId is unavailable " +
        "(deleted or unknown); workspace tools will not be restored for this run"
)

/**
 * 生成 run 途中会话助手的工作区归属改变（边界）：本 run 的 prompt/已签发
 * 工具与旧工作区绑定，静默继续只会以旧归属执行新请求。显式终止本 run，
 * 用户重新发送即以新归属开新 run。在途工具由 settings watcher 的政策取消
 * 处理（不 pause 队列）。
 */
internal class ConversationWorkspaceReboundException(
    conversationId: Uuid,
    initialWorkspaceId: Uuid?,
    currentWorkspaceId: Uuid?,
) : IllegalStateException(
    "workspace binding of conversation $conversationId changed mid-run " +
        "($initialWorkspaceId -> $currentWorkspaceId); aborting this run"
)

/**
 * 生成任务结束是否暂停队列。政策取消（gate 禁用/结构变更/助手重绑
 * 工作区导致的在途终止）不是用户停止，不能 pause——未开始的禁用调用由
 * pending 清理写实。独立成内部函数以便 JVM 单测精确覆盖。
 */
internal fun shouldPauseQueueOnGenerationFinished(cause: Throwable?): Boolean =
    cause != null && !cause.isWorkspacePolicyCancellation()

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        DocumentAsPromptTransformer,
        WorkspaceFileSnapshotTransformer,
        OcrTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val generationLoop: GenerationLoop,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val persistentBashSessionManager: WorkspacePersistentBashSessionManager,
    private val claudeLifeManager: ClaudeLifeManager,
    /**
     * 设备目录 busy 探针桥（可选；生产由 Koin 注入）。init 中注册
     * [isWorkspaceBusy]，由 WorkspaceDeviceDirectoryService.rejectIfBusy
     * 在 admissionMutex 内非 suspend 消费——覆盖 gate 册表看不到的
     * 「已派发未准入生成」与「无 job 的待审批」两缝。约定见
     * /tmp/workspace-direct/busy-api.txt。
     */
    private val workspaceDeviceUsage: WorkspaceDeviceUsage? = null,
    private val tavernRuntime: TavernRuntime = TavernRuntime(),
    private val characterRepository: CharacterRepository? = null,
    private val usageRecorder: UsageRecorder? = null,
    /** Collects sent attachments into the file manager library. */
    private val fileManager: FileManagerService? = null,
    /** Workspace CLI agents (ACP); null in tests that never open a CLI chat. */
    private val acpSessionManager: AcpSessionManager? = null,
    private val skillManager: SkillManager? = null,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()

    private val runtimeGate get() = workspaceRepository.runtimeGate

    /** gate 变更前回调的注销函数（cleanup 时解除注册）。 */
    private val workspaceBeforeChangeUnregister: () -> Unit

    /** busy 探针注册注销函数（未注入 usage 时为 no-op）。 */
    private val workspaceDeviceUsageUnregister: () -> Unit

    private val turnPreparation = TurnPreparation(tavernRuntime)
    private val tavernInput = TavernInputProcessor(tavernRuntime)
    private val qubagu = Qubagu(tavernRuntime.regexFactory)
    private val qubaguRefiner = QubaguRefiner(qubagu, providerManager, usageRecorder)
    private val outputTransformers = listOf(
        ThinkTagTransformer,
        MessageTitleTransformer,
        Base64ImageToLocalFileTransformer,
        TavernRegexOutputTransformer(tavernRuntime),
        QubaguOutputTransformer(qubagu, qubaguRefiner),
    )

    init {
        // claudelife：会话删除 → 立即停止该会话保活并取消在途重放。
        appScope.launch {
            conversationRepo.deletedConversationIds.collect { id ->
                stopCacheWarm(id, "conversation deleted")
            }
        }

        // workspace 配置发布前（victim 取消之前）立即停关联会话的保活并封锁
        // 本轮次——补「无 run 也可封锁」的能力缺口；同步、纯内存，绝不 suspend。
        workspaceBeforeChangeUnregister = runtimeGate.addOnBeforeChangeListener { change ->
            sessionsBoundToWorkspace(change.workspaceId).forEach { session ->
                session.cacheWarmer?.stop(
                    "workspace policy changed",
                    blockGeneration = true,
                )
            }
        }

        // 设备连接结构变更的 busy 探针：纯内存、非 suspend、无 gate 重入
        // （消费点在 admissionMutex 内），抛异常由 usage 桥按 busy 处理。
        workspaceDeviceUsageUnregister = workspaceDeviceUsage
            ?.registerProbe("chat-service") { workspaceId ->
                isWorkspaceBusy(workspaceId)
            } ?: {}

        // workspace 配置发布结束后（含 committed=false——epoch 已 bump）
        // 给关联会话排 pending 清理。committed=false 也要修：门已换代，按最新状态
        // 复核是幂等收敛，不做会留下新纪元下的旧 pending。
        appScope.launch {
            runtimeGate.changes.collect { event ->
                sessionsBoundToWorkspace(event.workspaceId).forEach { session ->
                    session.cacheWarmer?.stop(
                        "workspace policy changed",
                        blockGeneration = true,
                    )
                    scheduleWorkspacePendingRepair(
                        session.id,
                        "workspace config changed (ws=${event.workspaceId}, " +
                            "affected=${event.affectedStableIds}, committed=${event.committed})",
                    )
                }
            }
        }

        // 助手 workspaceId 变更 / 全局 toolOverrides 变化（Room 不触发，
        // 只能靠 settingsFlow 差分）→ 停关联 warmer + 排 pending 清理。
        // 第一帧只作基线，不把应用启动当成配置变更。
        appScope.launch {
            var previous: Settings? = null
            settingsStore.settingsFlow.collect { settings ->
                val old = previous
                previous = settings
                if (old != null) onSettingsPossiblyChanged(old, settings)
            }
        }
    }

    /**
     * 会话当前归属工作区 id（精确归属，不回落）：
     * conversation.assistantId → settings.getAssistantById → workspaceId。
     * 助手缺失 → null（不借全局助手归属，避免错误恢复其他工作区的调用）。
     */
    private fun sessionWorkspaceId(session: ConversationSession, settings: Settings): String? =
        settings.getAssistantById(session.state.value.assistantId)
            ?.workspaceId?.toString()

    private fun sessionsBoundToWorkspace(workspaceId: String): List<ConversationSession> {
        val settings = settingsStore.settingsFlow.value
        return sessions.values.filter { session ->
            sessionWorkspaceId(session, settings) == workspaceId
        }
    }

    /**
     * 该工作区当前是否存在「gate 册表之外仍属忙碌」的会话（设备连接结构
     * 变更 busy 探针；非 suspend、纯内存、无 I/O）。
     *
     * gate 的 GENERATION op 覆盖「admission 到 turn 收尾」区间；这里补
     * 两个缝：
     *  - 生成 job 已占槽（dispatch 决定派发）但尚未走到 admission：
     *    `session.getJob() != null` 即算忙（含排队中的修复/审批 job，
     *    偏保守但 fail-closed）。
     *  - 待审批工具（!isExecuted 且 approvalState is Pending）挂在会话
     *    树上、期间无 job 在跑：审批一旦通过将继续本 turn 的生成，此刻
     *    改设备连接会让恢复的工具拿新结构执行旧签发 → 按忙拒绝。
     * 归属按助手最新 workspaceId 映射（同一 settings 快照，不重查 DB）。
     */
    fun isWorkspaceBusy(workspaceId: String): Boolean {
        val settings = settingsStore.settingsFlow.value
        return sessions.values.any { session ->
            sessionWorkspaceId(session, settings) == workspaceId &&
                (session.getJob() != null || session.hasPendingToolApprovals())
        }
    }

    private fun onSettingsPossiblyChanged(old: Settings, new: Settings) {
        val overridesChanged = old.toolOverrides != new.toolOverrides
        sessions.values.forEach { session ->
            val assistantId = session.state.value.assistantId
            val oldWs = old.getAssistantById(assistantId)?.workspaceId
            val newWs = new.getAssistantById(assistantId)?.workspaceId
            val bound = newWs != null || oldWs != null
            if (!bound) return@forEach
            if (oldWs != newWs || overridesChanged) {
                session.cacheWarmer?.stop(
                    "workspace binding changed",
                    blockGeneration = true,
                )
                // 归属改绑（不只是 overrides 改名）：本会话在途的生成/工具
                // 仍按旧工作区签发，必须终止——同 bound 面只精确到"会话级"，
                // 不动绑定同一旧工作区的其他会话。用政策取消原因使
                // onGenerationFinished 不 pause 队列（非用户停止）；后继
                // 任务与清理仍走 setJob/前任 join 排序链。
                if (oldWs != newWs) {
                    session.cancelJobs(
                        WorkspacePolicyCancellationException(
                            oldWs?.toString() ?: newWs.toString()
                        )
                    )
                    appScope.launch {
                        acpSessionManager?.closeConversation(session.id)
                        updateSessionMetadata(
                            session.id,
                            update = { current ->
                                current.copy(
                                    workspaceCwd = null,
                                    workspaceCwdScoped = true,
                                    cliSession = if (newWs != null) current.cliSession?.reset() else current.cliSession,
                                )
                            },
                            persist = { updated ->
                                conversationRepo.updateWorkspaceCwd(session.id, updated.workspaceCwd)
                                conversationRepo.updateWorkspaceCwdScoped(session.id, updated.workspaceCwdScoped)
                                if (newWs != null) {
                                    conversationRepo.updateCliSession(session.id, updated.cliSession)
                                }
                            },
                        )
                    }
                }
                scheduleWorkspacePendingRepair(
                    session.id,
                    if (oldWs != newWs) {
                        "assistant workspace changed ($oldWs -> $newWs)"
                    } else {
                        "tool overrides changed"
                    },
                )
            }
        }
    }

    /**
     * 会话缓存保活状态（claudelife）。会话未装载或无 warmer 时为 null（UI 显示「未启用」）。
     */
    fun getCacheWarmStatusFlow(conversationId: Uuid): StateFlow<CacheWarmStatus>? =
        sessions[conversationId]?.cacheWarmer?.status

    /**
     * 会话缓存保活状态流（App / Web 共用）。会话装载与否都能订阅：
     * 未装载或会话被回收时发 [CacheWarmStatus.Inactive]；装载后跟随该会话 warmer。
     * 全局关闭时恒为 Inactive（warmer 关闭即停，状态自然回落）。
     */
    fun observeCacheWarmStatus(conversationId: Uuid): Flow<CacheWarmStatus> =
        _sessionsVersion.flatMapLatest {
            sessions[conversationId]?.cacheWarmer?.status ?: flowOf(CacheWarmStatus.Inactive())
        }.distinctUntilChanged()

    /** 与 [CacheWarmStatus.Scheduled.nextWarmAtMs] 同一单调时钟的当前毫秒值。 */
    fun cacheWarmNowMs(): Long = claudeLifeManager.nowMs()

    /** 会话被删除等使旧基线失效的操作：立即停止该会话的保活任务。 */
    fun stopCacheWarm(conversationId: Uuid, reason: String) {
        sessions[conversationId]?.cacheWarmer?.stop(reason, blockGeneration = true)
    }

    /**
     * 会话层结构性修改（编辑/删除消息、切换分支、压缩等）后的上下文复核：
     * 旧基线前缀已改变 → 停止；只是追加 → 保留。
     */
    private fun revalidateCacheWarm(conversationId: Uuid) {
        sessions[conversationId]?.cacheWarmer?.revalidate()
    }

    /**
     * 上下文有效性：返回 null 表示基线仍适用，否则返回失效原因。
     * 结合 rikki 的内容/版本（消息 id/角色/parts 前缀比较）与会话当前的
     * 助手、模型、system prompt 等明确的请求来源，而不是对象引用恒等。
     */
    private fun cacheWarmContextCheck(
        conversationId: Uuid,
        baseline: List<UIMessage>,
        modelId: Uuid,
        assistantId: Uuid,
        systemPrompt: String?,
        assistantPromptPreset: me.rerere.tavern.prompt.PromptPreset,
        assistantTavernMode: Boolean,
        requestProvider: ProviderSetting,
        // 本 attempt 实际捕获的 wire 策略（workspaceId/policyEpoch 快照），
        // 由 requestObserver 在 provider 请求开始侧从 params 取——不是生成启动时
        // 的旧 workspace，重试 attempt 各自带自己的捕获值。
        requestPolicy: WorkspaceToolWirePolicy?,
        // observer 启动时刻捕获的全局别名表指纹。isCurrent 在 replay 前
        // 同步复核——只靠异步 settingsFlow watcher 会让旧 observer 先 start；
        // epoch 只覆盖 Room 触发的 enablement，不覆盖 workspaceId 改绑与
        // toolOverrides 改名，这两项必须在本闭包内与实际值比较。
        requestToolOverrides: Map<String, ToolOverride>,
    ): () -> String? = check@{
        val session = sessions[conversationId] ?: return@check "session released"
        val conversation = session.state.value
        if (conversation.assistantId != assistantId) return@check "assistant changed"
        if (conversation.customSystemPrompt != systemPrompt) return@check "conversation system prompt changed"
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: return@check "assistant removed"
        if (assistant.promptPreset != assistantPromptPreset) return@check "assistant system prompt changed"
        if (assistant.tavernMode != assistantTavernMode) return@check "assistant tavern mode changed"
        val currentModelId = assistant.chatModelId ?: settings.chatModelId
        if (currentModelId != modelId) return@check "conversation model changed"
        if (settings.findModelById(modelId) == null) return@check "model removed"
        cacheWarmGoogleProtocolChange(requestProvider, currentModelId, settings)?.let { return@check it }
        if (!cacheWarmPrefixIntact(baseline, conversation.currentMessages)) {
            return@check "baseline messages modified"
        }
        if (compressedAfterBaseline(baseline, conversation.currentMessages)) {
            return@check "context compressed"
        }
        // 当前归属与捕获归属逐字比对——request 无策略（无工作区）时
        // 捕获侧为 null；两边都是 String?，不等即失配（null→非 null 的绑定
        // 新增也算 workspace binding changed）。
        if (assistant.workspaceId?.toString() != requestPolicy?.workspaceId) {
            return@check "workspace binding changed"
        }
        val capturedOverridesFingerprint = requestPolicy?.toolOverridesFingerprint
        if ((capturedOverridesFingerprint != null &&
                me.rerere.rikkihub.data.ai.tools.toolOverridesFingerprint(settings.toolOverrides) !=
                capturedOverridesFingerprint) || settings.toolOverrides != requestToolOverrides
        ) {
            return@check "tool overrides changed"
        }
        // 捕获 policyEpoch 与当前 epoch 等值比对（warmer/observer 只比
        // policyEpoch）。无捕获（无工作区请求）→ 跳过；策略已换代 → 停任务并封轮次。
        val capturedWorkspaceId = requestPolicy?.workspaceId
        val capturedEpoch = requestPolicy?.policyEpoch
        if (capturedWorkspaceId != null && capturedEpoch != null &&
            requestPolicy.isStale(runtimeGate.currentEpoch(capturedWorkspaceId))
        ) {
            return@check "workspace policy changed"
        }
        null
    }
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
        affectsReply: Boolean = true,
    ) {
        if (error is CancellationException) return
        val resolvedSolution = solution ?: (error as? CliTurnException)?.solution
        _errors.update {
            (it + ChatError(
                title = title,
                error = error,
                conversationId = conversationId,
                solution = resolvedSolution,
                affectsReply = affectsReply,
            ))
                .takeLast(MAX_ERRORS)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        workspaceBeforeChangeUnregister()
        workspaceDeviceUsageUnregister()
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        _sessionsVersion.update { it + 1 }
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        var created = false
        val session = sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                cacheWarmerFactory = { onTaskEnded ->
                    claudeLifeManager.createWarmer(id.toString(), onTaskEnded)
                },
                onGenerationFinished = { id, cause ->
                    val session = sessions[id]
                    // gate 政策取消（WorkspacePolicyCancellationException，含在途
                    // 工具 victim 取消穿透上来的情形与助手重绑触发的会话取消）不
                    // pause 队列——未开始的禁用调用由 pending 清理写实，与用户
                    // 主动停止的 pause 语义区分。
                    if (shouldPauseQueueOnGenerationFinished(cause)) {
                        session?.messageQueue?.pause()
                    }
                    if (session?.state?.value?.pendingToolCalls?.isNotEmpty() == true) {
                        session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
                    }
                    appScope.launch { dispatchNextQueuedMessage(id) }
                },
            ).also {
                created = true
                // 新 session 无引用方（如 Web/REST saveConversation 路径），立即排入空闲回收
                it.scheduleIdleCheck()
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
        // Notify only AFTER the registry has installed the session, never inside its factory.
        if (created) _sessionsVersion.update { it + 1 }
        return session
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.update { it + 1 }
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
            // 没人再看这个对话了：有待整理的轮次就不必等空闲计时（CLI 会话不跑记忆整理）
            if (session.state.value.cliSession == null) {
                memoryConsolidationScheduler.onConversationLeft(conversationId)
            }
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        observeSessionGenerationJob(_sessionsVersion) { sessions[conversationId] }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getReplyMessageIdFlow(conversationId: Uuid): StateFlow<Uuid?> =
        getOrCreateSession(conversationId).replyMessageId

    fun getGenerationStartedAtFlow(conversationId: Uuid): StateFlow<Long?> =
        getOrCreateSession(conversationId).generationStartedAt

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    private suspend fun <T> withConversationSession(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> T,
    ): T {
        val session = getOrCreateSession(conversationId)
        session.acquire()
        return try { block(session) } finally { session.release() }
    }

    private suspend fun initializeSession(session: ConversationSession, characterId: Uuid? = null) {
        session.initialize {
            conversationRepo.getConversationById(session.id) ?: run {
                val settings = settingsStore.settingsFlowRaw.first()
                val assistant = settings.getCurrentAssistant()
                val character = characterRepository?.let { ActiveCharacter.resolve(it, settings, characterId) }
                val override = pendingNewCliSessions.remove(session.id)
                val blank = Conversation.ofId(session.id, assistantId = assistant.id, newConversation = true).copy(
                    cliSession = resolveInitialCliSession(
                        hasCharacter = character != null,
                        override = override,
                        assistant = assistant,
                    ),
                )
                if (character == null) {
                    blank.updateCurrentMessages(assistant.presetMessages)
                } else {
                    // 绑了角色：开场白取代助手的预设消息
                    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: Model()
                    val greeting = greetingBuilder.build(
                        character, assistant, settings, model, RikkihubMacros.resolvers(context, settings, model, assistant),
                    )
                    blank.copy(
                        characterId = character.id,
                        messageNodes = listOfNotNull(greeting?.node),
                        tavernMetadata = greeting?.metadata ?: blank.tavernMetadata,
                    )
                }
            }
        }
    }

    /** [characterId] 只在会话还不存在时用：新建一个和这个角色的会话（带开场白）。 */
    suspend fun initializeConversation(conversationId: Uuid, characterId: Uuid? = null) =
        withConversationSession(conversationId) { session ->
            initializeSession(session, characterId)
            settingsStore.updateAssistant(session.state.value.assistantId)
            // Keep the fork's lazy pending-tool repair, including inactive branches.
            scheduleWorkspacePendingRepair(conversationId, "conversation loaded")
        }

    /**
     * 新建并保存一个和 [characterId] 的会话（开场白已在里面），返回会话 id。App 进聊天页时才建草稿，
     * 网页端没有草稿页，点“开始聊天”时就落库，这样能直接打开它。
     */
    suspend fun startCharacterConversation(characterId: Uuid): Uuid {
        val id = Uuid.random()
        // 初始化和保存之间一直拿着会话，免得它被回收后状态丢掉
        withConversationSession(id) { session ->
            initializeSession(session, characterId)
            saveConversation(id, session.state.value)
        }
        return id
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    /**
     * 只读探针：session 未加载时返回 null，不创建占位 session。
     * Web 侧展示队列状态（GET /queue、会话快照）用；需要真实队列的写路径仍走 getOrCreateSession。
     */
    fun getMessageQueueStateIfLoaded(conversationId: Uuid): MessageQueueState? =
        sessions[conversationId]?.messageQueue?.state?.value

    /**
     * 全部活跃 session 的本地附件 URL 只读快照（file:// URI 字符串集合）：
     * 覆盖每个 session 内存会话态的**全部分支**节点（含工具 output/input 嵌套）、
     * MessageQueue 待发送消息、以及已取出但尚未落库的 submittingMessage。
     * 只暴露 URL 字符串，不泄漏 Session/Conversation 对象本身。
     * 供 Web 存储清理的引用保护 fail-closed 判定使用（抛异常时调用方按有引用处理）。
     */
    fun referencedFileUrlsSnapshot(): Set<String> {
        return sessions.values.flatMap { session ->
            buildList {
                session.state.value.messageNodes.forEach { node ->
                    node.messages.forEach { addAll(it.parts.localFileUrls()) }
                }
                (session.messageQueue.state.value.messages + listOfNotNull(session.submittingMessage))
                    .forEach { addAll(it.parts.localFileUrls()) }
            }
        }.toSet()
    }

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                // 草稿租约复查：同文件可能正被另一输入框/草稿续租中，删除前放行
                val unleased = unusedFiles.filter { url ->
                    val rel = extractUploadRelativePath(url)
                    rel == null || !filesManager.attachmentLeases.isLeased(rel)
                }
                val leased = unusedFiles - unleased.toSet()
                if (leased.isNotEmpty()) {
                    Log.d(TAG, "cleanupQueuedAttachments: keep leased files $leased")
                }
                if (unleased.isNotEmpty()) {
                    filesManager.deleteChatFiles(unleased.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val parts = collectAttachments(content)
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(parts, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /** Marks the sent attachments as collected and copies the new ones into the file manager library. */
    private fun collectAttachments(parts: List<UIMessagePart>): List<UIMessagePart> {
        val fileManager = fileManager ?: return parts
        val (marked, pending) = fileManager.markChatUploads(parts)
        if (pending.isNotEmpty()) appScope.launch { fileManager.collectChatUploads(pending) }
        return marked
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.pendingToolCalls.isEmpty()) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            // 同一 monitor 段内检查 job 槽位与派发: getJob() != null 时绝不派发,
            // 所以 sendQueuedMessage 拿到的 previousJob 恒为 null —— 新任务天生
            // 排在前任完整结束 (含 NonCancellable 协议清理) 之后; 该方法体内仍
            // 保留 previousJob?.join() 作为统一防御 (见 sendQueuedMessage 注释)。
            if (session.getJob() != null || session.state.value.pendingToolCalls.isNotEmpty()) return null
            // A running compression owns the history; sends wait in the queue until it ends.
            if (session.isCompressing) return null
            val peeked = session.messageQueue.peekNext() ?: return null
            if (peeked.answer && autoCompressLocked(session, peeked)) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    // 调用约定: 在 synchronized(session) 内 (见 dispatchNextQueuedMessage),
    // 因此下面的 getJob() 与派发检查处于同一 monitor 段, 满足排序不变量
    // 的前任快照语义。
    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        // 排序不变量: 后继生成必须在前任完整结束 (含其 NonCancellable 协议失败
        // 清理) 之后才能读取会话态。dispatchNextQueuedMessage 已保证此处
        // previousJob 为 null (有 job 在槽位时不派发), 这里仍统一走
        // "捕获前任 -> job 体第一步 join" 模式: join 对已结束/不存在的
        // 前任即时返回, 且与 regenerate/审批路径保持同一防御写法, 若未来出现
        // 新调用点绕过派发检查也不会读旧状态。
        val previousJob = session.getJob()
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                previousJob?.join()
                // 新消息开始处理前就清掉上一轮的回复，处理失败也不能留着旧预览
                session.replyMessageId.value = null
                // 遗留工作区 pending 先按真实绑定/启用态精确复核
                // （unavailable 非空输出），再交旧的兜底 cancelled 清理。
                reconcileWorkspacePendingTools(conversationId)
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val cli = currentConversation.cliSession != null
                val newConversation = if (cli) {
                    // CLI 对话：正文原样交给 CLI，不过酒馆正则/宏，也不附首条文件列表（CLI 自己会看）
                    check(assistant.workspaceId != null) { context.getString(R.string.cli_error_no_workspace) }
                    currentConversation.copy(
                        messageNodes = currentConversation.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = importAttachmentsIntoScope(content, currentConversation, assistant),
                        ).toMessageNode(),
                    )
                } else {
                    // 用户输入先过 USER_INPUT 正则再求宏，结果存进消息（宏求值有副作用：变量写进会话/设置）
                    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                    val sent = tavernInput.processSend(
                        parts = content,
                        assistant = assistant,
                        settings = settings,
                        model = model ?: Model(),
                        conversationId = conversationId,
                        metadata = currentConversation.tavernMetadata,
                        history = currentConversation.currentMessages,
                        ownMacros = RikkihubMacros.resolvers(context, settings, model ?: Model(), assistant),
                        character = activeCharacterOf(currentConversation, settings),
                    )
                    sent.globalVariables?.let { updated ->
                        settingsStore.update { it.copy(tavern = it.tavern.copy(globalVariables = updated)) }
                    }
                    val processedContent = attachFirstMessageFileSnapshot(
                        importAttachmentsIntoScope(sent.parts, currentConversation, assistant),
                        currentConversation, assistant,
                    )
                    currentConversation.copy(
                        messageNodes = currentConversation.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,
                        ).toMessageNode(),
                        tavernMetadata = sent.metadata ?: currentConversation.tavernMetadata,
                    )
                }
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    if (cli) handleCliMessageComplete(conversationId) else handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    /**
     * A confined conversation cannot see `/upload`; copy its new local attachments into
     * the selected directory once. No-op when not confined or the directory no longer exists.
     */
    private suspend fun importAttachmentsIntoScope(
        parts: List<UIMessagePart>,
        conversation: Conversation,
        assistant: Assistant,
    ): List<UIMessagePart> {
        val workspaceId = assistant.workspaceId?.toString() ?: return parts
        if (!conversation.workspaceCwdScoped) return parts
        if (parts.none { it is UIMessagePart.Document || it is UIMessagePart.Image }) return parts
        val scope = runCatching {
            me.rerere.workspace.WorkspaceFilesScope.normalize(conversation.workspaceCwd)
        }.getOrNull() ?: return parts
        val guestPath = me.rerere.workspace.WorkspaceFilesScope.guestPath(scope)
        val scopeIsDirectory = runCatching {
            workspaceRepository.statRootfs(workspaceId, guestPath, scope).isDirectory
        }.getOrDefault(false)
        if (!scopeIsDirectory) return parts
        return me.rerere.rikkihub.data.workspace.copyAttachmentsIntoWorkspaceScope(parts) { name, input ->
            workspaceRepository.importFile(
                workspaceId, me.rerere.workspace.WorkspaceStorageArea.FILES, scope, name, input,
            ).path
        }
    }

    /**
     * 工作区开关「首条消息附带文件列表」：会话还没有用户消息时，把工作目录的一层列表冻结进这条消息
     * （[WorkspaceFileSnapshotTransformer] 请求时接到消息末尾），省掉模型开场先 ls 一次。失败就不附带。
     */
    private suspend fun attachFirstMessageFileSnapshot(
        parts: List<UIMessagePart>,
        conversation: Conversation,
        assistant: Assistant,
    ): List<UIMessagePart> {
        val workspaceId = assistant.workspaceId?.toString() ?: return parts
        if (conversation.currentMessages.any { it.role == MessageRole.USER }) return parts
        val snapshot = try {
            workspaceRepository.fileSnapshotForFirstMessage(workspaceId, conversation.workspaceCwd)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "first message file snapshot failed", e)
            null
        } ?: return parts
        return parts.withWorkspaceFileSnapshot(snapshot)
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        requireNotCompressing(session)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                session.replyMessageId.value = null
                val conversation = session.state.value

                if (conversation.cliSession != null && (message.role == MessageRole.USER || regenerateAssistantMsg)) {
                    // CLI 对话不留分支：截回到那条用户消息再跑一轮；历史变了，下一轮自动换新会话并带上前文
                    val nodeIndex = conversation.messageNodes.indexOf(conversation.getMessageNodeByMessage(message))
                    val keep = if (message.role == MessageRole.USER) nodeIndex + 1 else nodeIndex
                    saveConversation(conversationId, conversation.copy(messageNodes = conversation.messageNodes.subList(0, keep)))
                    handleCliMessageComplete(conversationId)
                } else if (message.role == MessageRole.USER) {
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val hasReply = conversation.messageNodes.getOrNull(indexAt + 1)
                        ?.currentMessage?.role == MessageRole.ASSISTANT
                    if (hasReply) {
                        // 后面已有回复：新回复成为它的另一个分支，旧回复（含已执行的工具结果）留着
                        handleMessageComplete(conversationId, messageRange = 0..indexAt, generationType = GenerationType.REGENERATE)
                    } else {
                        // 后面没有回复：截止到当前消息
                        val newConversation = conversation.copy(
                            messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                        )
                        saveConversation(conversationId, newConversation)
                        handleMessageComplete(conversationId)
                    }
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex, generationType = GenerationType.REGENERATE)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        requireNotCompressing(session)
        val previousJob = session.getJob()

        // CLI 审批卡：只唤醒正在等它的回合，状态由回合自己写；没人等（进程没了/App 重启过）就标为中断，绝不走 HTTP 续写
        val cliTool = session.state.value.currentMessages.asSequence().flatMap { it.getTools() }
            .firstOrNull { it.toolCallId == toolCallId && it.isPending && AcpParts.isAcpTool(it) }
        if (cliTool != null) {
            val decision = if (approved) CliApprovalDecision.Approve() else CliApprovalDecision.Deny(reason)
            if (acpSessionManager?.tryDeliverPermissionDecision(conversationId, toolCallId, decision) == true) return@synchronized
            if (previousJob != null) return@synchronized
            val job = launchGenerationJob(conversationId, keepAliveInBackground = false) {
                finishStaleCliTools(conversationId)
            }
            session.setJob(job, cancelPrevious = false)
            return@synchronized
        }

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    // 审批落点之前先复核残留 workspace pending——已被修成
                    // unavailable 的调用不再 isPending，下面的过期审批检查自然放行。
                    reconcileWorkspacePendingTools(conversationId)
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state (local update on the owning node)
                    val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
                        node.currentMessage.getTools().any {
                            it.toolCallId == toolCallId && it.isPending
                        }
                    }
                    if (targetNodeIndex < 0) return@afterPreviousGeneration
                    val targetNode = conversation.messageNodes[targetNodeIndex]
                    val updatedNodes = conversation.messageNodes.toMutableList().apply {
                        set(
                            targetNodeIndex,
                            targetNode.copy(
                                messages = targetNode.messages.map { msg ->
                                    msg.copy(
                                        parts = msg.parts.map { part ->
                                            when {
                                                // 只更新仍待审批的调用: 同 id 复用已执行 call
                                                // 的历史 part (output 非空) 不被审批结果改写。
                                                part is UIMessagePart.Tool &&
                                                    part.toolCallId == toolCallId &&
                                                    part.isPending -> {
                                                    part.copy(approvalState = newApprovalState)
                                                }

                                                else -> part
                                            }
                                        }
                                    )
                                }
                            )
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    // ---- 请求输入（正常对话与压缩共用：二者组装一致，压缩请求才能命中提示词缓存） ----

    private suspend fun memoriesFor(assistant: Assistant) =
        if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList()

    private fun generationInputTransformers(assistant: Assistant, conversationId: Uuid) = buildList {
        addAll(inputTransformers)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        addAll(chatToolFactory.creatorInputTransformers(assistant, conversationId))
        add(MessageTitleConventionTransformer)
    }

    /**
     * 每个下一 provider 请求/每个重试 attempt 一致重建快照的来源。
     * 本轮 run 绑定范围固定（scope to run）：会话助手 id 与工作区归属
     * 必须保持 run 开始值——中途重绑/换助手不换 ctx/prompt/已签发工具
     * 语义，静默继续只会让新 schema 配旧提示（caps id 失配回落 DB 旧
     * 清单）或让旧工具查新表。因此：
     *   - assistantId 变了 → IllegalStateException（会话级重绑定，显式失败）；
     *   - 会话助手缺失 → ConversationAssistantUnavailableException
     *     （不借回落助手恢复 workspace 工具，亦不借其 prompt）；
     *   - workspaceId 变了 → ConversationWorkspaceReboundException
     *     （停止下一个请求；在途工具由 settings watcher 的政策取消处理）。
     * 这些异常上抛终止本 run（与普通生成错误同一路径报错+暂停），用户
     * 重新发送即以新归属开新 run。settings/model：model 恒为 run 开始
     * 固定实例；settings 取最新（toolOverrides 热生效是有意的设计）。
     */
    private fun toolSnapshotProviderFor(
        conversationId: Uuid,
        assistantId: Uuid,
        workspaceId: Uuid?,
        model: Model,
    ): suspend () -> ChatToolSnapshot = {
        val freshSettings = settingsStore.settingsFlow.value
        val freshConversation = getConversationFlow(conversationId).value
        check(freshConversation.assistantId == assistantId) {
            "conversation assistant changed mid-run " +
                "($assistantId -> ${freshConversation.assistantId})"
        }
        val snapshotAssistant =
            freshSettings.getAssistantById(freshConversation.assistantId)
                ?: throw ConversationAssistantUnavailableException(
                    conversationId,
                    freshConversation.assistantId,
                )
        if (snapshotAssistant.workspaceId != workspaceId) {
            throw ConversationWorkspaceReboundException(
                conversationId,
                workspaceId,
                snapshotAssistant.workspaceId,
            )
        }
        chatToolFactory.createSnapshot(
            settings = freshSettings,
            assistant = snapshotAssistant,
            model = model,
            workspaceCwd = freshConversation.workspaceCwd,
            workspace = null,
            conversationId = conversationId,
            workspaceCwdScoped = freshConversation.workspaceCwdScoped,
        )
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        generationType: GenerationType = GenerationType.NORMAL,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        // workspace 归属精确绑定会话助手。缺失时不允许借用全局助手的
        // workspace——连 transformers 也不能看到回落助手的 workspaceId，
        // 否则 WorkspaceReminderTransformer 会按全局助手归属查 DB 注入不
        // 属于本会话的工作区提示。assistant 变量沿用旧回落仅用于 system
        // prompt/记忆/展示名（workspaceId 剥掉）。
        val conversationAssistant = settings.getAssistantById(initialConversation.assistantId)
        val assistant = conversationAssistant
            ?: settings.getCurrentAssistant().copy(workspaceId = null)
        // 模型在 run 开始固定：快照刷新绝不暗换模型。
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException("No chat model selected")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {

            // reset suggestions
            updateConversation(
                conversationId,
                initialConversation.copy(chatSuggestions = emptyList()),
                checkFileRefs = false,
            )

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools(assistant).isNotEmpty() ||
                    me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(assistant.id)
                ) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable),
                        affectsReply = false,
                    )
                }
            }

            // check invalid messages（先 reconcile 遗留 workspace pending，再判删）
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // workspace 元数据只从会话助手归属取；会话助手缺失 → null（工具面
            // 与能力面都拿不到工作区），不回落全局助手。非 READY 由 createSnapshot
            // 内部产出空能力面，不再提前过滤。
            val workspace = conversationAssistant?.workspaceId?.toString()
                ?.let { workspaceRepository.getById(it) }

            // 每个下一 provider 请求/每个重试 attempt 一致重建快照的来源。
            // 本轮 run 绑定范围固定（scope to run）：会话助手 id 与工作区归属
            // 必须保持 run 开始值——中途重绑/换助手不换 ctx/prompt/已签发工具
            // 语义，静默继续只会让新 schema 配旧提示（caps id 失配回落 DB 旧
            // 清单）或让旧工具查新表。因此：
            //   - assistantId 变了 → IllegalStateException（会话级重绑定，显式失败）；
            //   - 会话助手缺失 → ConversationAssistantUnavailableException
            //     （不借回落助手恢复 workspace 工具，亦不借其 prompt）；
            //   - workspaceId 变了 → ConversationWorkspaceReboundException
            //     （停止下一个请求；在途工具由 settings watcher 的政策取消处理）。
            // 这些异常上抛终止本 run（与普通生成错误同一路径报错+暂停），用户
            // 重新发送即以新归属开新 run。settings/model：model 恒为 run 开始
            // 固定实例；settings 取最新（toolOverrides 热生效是有意的设计）。
            val initialWorkspaceId = conversationAssistant?.workspaceId
            val toolSnapshotProvider = toolSnapshotProviderFor(
                conversationId = conversationId,
                assistantId = initialConversation.assistantId,
                workspaceId = initialWorkspaceId,
                model = model,
            )

            val initialSnapshot = try {
                toolSnapshotProvider()
            } catch (error: InvalidMcpServerNamesException) {
                sessions[conversationId]?.messageQueue?.pause()
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        )
                    ),
                    conversationId = conversationId,
                )
                return
            }

            // 整 turn 的生产循环（首个 provider 请求 → 步间间隙 → 步级重试）
            // 作为一个 GENERATION 操作在 gate 在册：设备连接结构变更的
            // prepare/admission 复查据此判定 busy 拒绝；其他结构级 publish
            // （affected=null）会把本 op 当 victim 取消并重试后不再准入——
            // 空 stableIds 与非 null 的 affectedStableIds 不相交，单工具开关
            // 不会取消整个 turn。生成 job 派发到 admission 的缝由
            // isWorkspaceBusy 探针补上。无工作区会话不登记（设备面不涉及）。
            suspend fun runGenerationTurn() {
            val session = getOrCreateSession(conversationId)
            val chatForTurn = conversation.currentMessages.let {
                if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it
            }
            // 审批后继续时接着上一条助手消息写，本轮回复就是它；否则等第一次输出
            session.replyMessageId.value = chatForTurn.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id
            val turn = prepareTurn(
                conversation = conversation,
                assistant = assistant,
                model = model,
                messages = chatForTurn,
                generationType = generationType,
            )
            // 世界书激活记录：生成开始前就有的消息不动，之后出现的回复补上记录（见 ActivationRecord）
            val knownMessageIds = conversation.currentMessages.mapTo(HashSet()) { it.id }
            val activationRecord = ActivationRecord.encode(turn.activations)
            // claudelife：每次用户发起的生成（发送/重新生成/审批后继续）一个轮次号；
            // 保活因失败/上下文变化停止后，本轮次后续请求不再重建任务。
            val cacheWarmGeneration = session.cacheWarmer?.newGeneration() ?: 0L
            generationLoop.generateTextObserved(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                turn = turn,
                workspaceCwd = conversation.workspaceCwd,
                harnessMinimalWorkspace = workspace?.harnessMinimal == true,
                exposeUpload = workspace?.exposeUpload != false,
                memories = memoriesFor(assistant),
                inputTransformers = generationInputTransformers(assistant, conversationId),
                outputTransformers = outputTransformers,
                tools = initialSnapshot.tools,
                toolSnapshotProvider = toolSnapshotProvider,
                // claudelife：每次真实 provider 请求开始侧（含重试 attempt）
                // 以本次完整构建输入建立/替换本会话的保活任务，旧任务与在途重放同时取消。
                requestObserver = session.cacheWarmer?.let { warmer ->
                    ProviderRequestObserver { providerSetting, requestMessages, params, contextMessages ->
                        warmer.start(
                            request = CacheWarmRequest(
                                providerSetting = providerSetting,
                                messages = requestMessages,
                                params = params,
                                contextMessages = contextMessages,
                            ),
                            generation = cacheWarmGeneration,
                            isCurrent = cacheWarmContextCheck(
                                conversationId = conversationId,
                                baseline = contextMessages,
                                modelId = model.id,
                                assistantId = conversation.assistantId,
                                systemPrompt = conversation.customSystemPrompt,
                                assistantPromptPreset = assistant.promptPreset,
                                assistantTavernMode = assistant.tavernMode,
                                requestProvider = providerSetting,
                                // 本 attempt 实际捕获的策略快照与
                                // observer 启动时刻的别名表（随重试刷新），
                                // isCurrent 闭包与 gate/最新 settings 实际比较。
                                requestPolicy = params.workspaceToolPolicy,
                                requestToolOverrides = settingsStore.settingsFlow.value.toolOverrides,
                            ),
                        )
                    }
                },
            ).onCompletion { cause ->
                try {
                    session.finishGenerationAfterStream(
                        cause = cause,
                        save = ::persistConversationSnapshot,
                        onSaveFailure = { saveError ->
                            Log.w(TAG, "final generation save failed ($conversationId)", saveError)
                            addError(saveError, conversationId)
                        },
                    )
                } finally {
                    appEventBus.tryEmit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            contentPreview = session.state.value.currentMessages.lastOrNull()
                                ?.toText()?.take(50)?.trim() ?: "",
                        )
                    )
                }
            }.let { generationFlow ->
                // collect lambda 与 onCompletion 默认跑在调用协程（Main）上，
                // 每 chunk 的会话重建挪到 Default，避免流式热路径阻塞 UI。
                withContext(Dispatchers.Default) {
                    // 回合中途落库：每一步结束、每多一个工具结果就存一次，进程被杀最多丢正在进行的这一步。
                    // 留下的未执行调用由下次发送/加载时的清理写成 unavailable，与中途停止一样。
                    var executedTools: Int? = null
                    generationFlow.collect { chunk ->
                        when (chunk) {
                            is GenerationChunk.Messages -> {
                                val chunkMessages = ActivationRecord.attach(chunk.messages, knownMessageIds, activationRecord)
                                val updatedConversation = getConversationFlow(conversationId).value
                                    .updateCurrentMessages(chunkMessages)
                                updateConversation(conversationId, updatedConversation, checkFileRefs = false)
                                val reply = chunkMessages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
                                reply?.let { session.replyMessageId.value = it.id }
                                val executed = reply?.parts?.count { it is UIMessagePart.Tool && it.isExecuted } ?: 0
                                val previousExecuted = executedTools
                                executedTools = executed
                                if (previousExecuted != null && executed > previousExecuted) saveMidTurn(session)

                                // 通知等边缘副作用由 ChatNotificationManager 消费；
                                // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                                chunkMessages.lastOrNull()?.let { lastMessage ->
                                    appEventBus.tryEmit(
                                        AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                                    )
                                }
                            }

                            // 主聊天 UI 以消息列表为准；步结束只用来落库，终态由收尾保存负责。
                            is GenerationChunk.StepFinished -> saveMidTurn(session)
                            is GenerationChunk.GenerationEnd -> Unit
                        }
                    }
                }
            }
            }
            val generationWorkspaceId = workspace?.id
            if (generationWorkspaceId == null) {
                runGenerationTurn()
            } else {
                runtimeGate.withOperation(
                    workspaceId = generationWorkspaceId,
                    kind = WorkspaceOperationKind.GENERATION,
                    stableIds = emptySet(),
                    handleClass = WorkspaceHandleClass.MODEL_EXECUTION,
                ) { runGenerationTurn() }
            }
        }.onFailure {
            // 协议失败 attempt 的权威清理: 上游错误经 flowOn 上抛时在途 Messages
            // 事件可能已丢, 不能靠 collect 恢复 —— 由 session 权威态按 id 把尾消息
            // 回滚到 attempt 开始前的 baseMessage, 然后无论是否回滚都落库 (最终保存
            // 跳过了协议失败, 不落库会丢掉本轮已执行的步骤)。清理不取消上下文内必须
            // 完成 (普通取消不会带协议异常, 此分支只在真实协议错误时执行)。不按 job 槽位门控: 后继任务的
            // setJob 与本清理之间无互斥, 安全由后继"先 join 前任再读状态"的
            // 排序不变量保证 (见 ConversationSession.rollbackProtocolFailedAttempt)。
            if (it is GenerationAttemptProtocolException) {
                val session = sessions[conversationId]
                if (session != null) {
                    runCatching {
                        withContext(NonCancellable) {
                            session.recoverProtocolFailedAttempt(it) { conversation ->
                                persistProtocolRollbackState(conversationId, conversation)
                            }
                        }
                    }.onFailure { cleanupError ->
                        Log.w(
                            TAG,
                            "protocol attempt rollback failed " +
                                "(code=${it.protocolCode}, conversation=$conversationId)",
                            cleanupError,
                        )
                    }
                }
            }
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            if (it is CancellationException) throw it
            sessions[conversationId]?.messageQueue?.pause()

            it.printStackTrace()
            val overflow = it.isContextLengthError()
            addError(
                it,
                conversationId,
                title = context.getString(R.string.error_title_generation),
                solution = if (overflow) ChatErrorSolution.CompressAndRetry else null,
            )
            if (overflow) scheduleOverflowRetry(conversationId)
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
            // 停在等工具审批时最后一轮还没结束，整理器会把它留到审批之后
            if (settingsStore.settingsFlow.value.getAssistantById(finalConversation.assistantId)?.enableMemory == true) {
                memoryConsolidationScheduler.onTurnFinished(finalConversation)
            }
        }
    }

    // ---- 工作区 CLI：设置 ----

    // 新对话打开前选好的 CLI（非空对话切换 CLI 时另开新对话）；建空白会话时取走
    private val pendingNewCliSessions = java.util.concurrent.ConcurrentHashMap<Uuid, PendingCliOverride>()

    val cliProcessState: StateFlow<me.rerere.rikkihub.data.cli.CliProcessState?> =
        acpSessionManager?.state ?: MutableStateFlow(null)

    /** The next blank conversation [conversationId] starts with this CLI setting. */
    fun prepareNewCliConversation(conversationId: Uuid, cliSession: ConversationCliSession?) {
        if (pendingNewCliSessions.size > 128) {
            val excess = pendingNewCliSessions.size - 128
            val keys = pendingNewCliSessions.keys.iterator()
            repeat(excess) {
                if (keys.hasNext()) pendingNewCliSessions.remove(keys.next())
            }
        }
        pendingNewCliSessions[conversationId] =
            if (cliSession == null) PendingCliOverride.Disabled else PendingCliOverride.Enabled(cliSession)
    }

    /** Turns the CLI of an (empty) conversation on, off, or to another agent. */
    suspend fun setConversationCli(conversationId: Uuid, agentId: String?) {
        val currentConversation = getConversationFlow(conversationId).value
        if (agentId != null) {
            require(currentConversation.characterId == null) { "Character conversations cannot enable CLI" }
        }
        val current = currentConversation.cliSession
        if (current?.agentId == agentId) return
        acpSessionManager?.closeConversation(conversationId)
        updateSessionMetadata(
            conversationId,
            update = { conversation ->
                conversation.copy(cliSession = agentId?.let {
                    ConversationCliSession(agentId = it, permissionMode = current?.permissionMode ?: CliPermissionMode.ASK)
                })
            },
            persist = { conversationRepo.updateCliSession(conversationId, it.cliSession) },
        )
    }

    /** Exits CLI mode in place for an existing conversation, keeping its message history for ordinary chat. */
    suspend fun exitCliToOrdinaryChat(conversationId: Uuid) {
        acpSessionManager?.closeConversation(conversationId)
        updateSessionMetadata(
            conversationId,
            update = { it.copy(cliSession = null) },
            persist = { conversationRepo.updateCliSession(conversationId, null) },
        )
    }

    suspend fun setCliPermissionMode(conversationId: Uuid, mode: CliPermissionMode) =
        updateCliSession(conversationId) { it.copy(permissionMode = mode) }

    /** A session config option (model, thinking level, mode); applied now when the agent runs, kept for new sessions. */
    suspend fun setCliConfigOption(conversationId: Uuid, configId: String, value: String) {
        liveCliSession(conversationId)?.setConfigOption(configId, value)
        updateCliSession(conversationId) { it.copy(configValues = it.configValues + (configId to value)) }
    }

    suspend fun setCliModel(conversationId: Uuid, modelId: String) {
        liveCliSession(conversationId)?.setModel(modelId)
        updateCliSession(conversationId) { it.copy(modelId = modelId) }
    }

    suspend fun setCliMode(conversationId: Uuid, modeId: String) {
        liveCliSession(conversationId)?.setMode(modeId)
        updateCliSession(conversationId) { it.copy(modeId = modeId) }
    }

    /** Ends the agent process; the next message starts a new agent session carrying the chat so far. */
    suspend fun restartCliSession(conversationId: Uuid) {
        acpSessionManager?.closeConversation(conversationId)
        updateCliSession(conversationId) { it.reset() }
    }

    fun cliStderrTail(conversationId: Uuid): String? =
        acpSessionManager?.liveSession(conversationId)?.stderrTail()

    private fun liveCliSession(conversationId: Uuid): AcpSession? = acpSessionManager?.liveSession(conversationId)

    /**
     * `!command` in a CLI chat: runs once in the conversation's working directory and records the
     * command and its output as a local tool card. The agent does not see it, and its session stays
     * in step with the chat (the card does not count as an edit of the history).
     */
    suspend fun runCliShellCommand(conversationId: Uuid, command: String) = withConversationSession(conversationId) { session ->
        initializeSession(session)
        val conversation = session.state.value
        val cli = conversation.cliSession ?: return@withConversationSession
        val settings = settingsStore.settingsFlow.first()
        val workspaceId = settings.getAssistantById(conversation.assistantId)?.workspaceId?.toString()
            ?: throw IllegalStateException(context.getString(R.string.cli_error_no_workspace))
        val filesScope = if (conversation.workspaceCwdScoped) {
            me.rerere.workspace.WorkspaceFilesScope.normalize(conversation.workspaceCwd)
        } else null
        val cwd = conversation.workspaceCwd.orEmpty()
            .removePrefix(me.rerere.workspace.WorkspaceManager.ROOTFS_WORKSPACE_DIR).trim('/')
        val started = Clock.System.now()
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd = cwd, filesScope = filesScope)
        val output = buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty() && !endsWith("\n")) append("\n")
                append(result.stderr)
            }
            if (result.exitCode != 0) append("\n[exit code: ${result.exitCode}]")
        }.ifBlank { "[exit code: ${result.exitCode}]" }
        val card = UIMessagePart.Tool(
            toolCallId = "shell-${Uuid.random()}",
            toolName = AcpParts.TOOL_PREFIX + "shell",
            input = buildJsonObject { put("command", command) }.toString(),
            output = listOf(UIMessagePart.Text(output)),
            metadata = buildJsonObject {
                putJsonObject(AcpParts.META_KEY) {
                    put("kind", "execute")
                    put("title", command)
                    put("status", if (result.exitCode == 0) "completed" else "failed")
                    put("local", true)
                }
            },
            startedAt = started,
            finishedAt = Clock.System.now(),
        )
        val before = conversation.currentMessages
        val updated = conversation.copy(
            messageNodes = conversation.messageNodes +
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("!$command"))).toMessageNode() +
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(card)).toMessageNode(),
        )
        saveConversation(conversationId, updated)
        if (cli.historyFingerprint == CliPrompts.fingerprint(before)) {
            updateCliSession(conversationId) { it.copy(historyFingerprint = CliPrompts.fingerprint(session.state.value.currentMessages)) }
        }
    }

    // ---- 工作区 CLI 回合 ----

    /**
     * One CLI turn for the last user message: the agent process (reused or started), its session
     * (resumed, or new with the history when the chat diverged from what the agent saw), the
     * prompt, and the streamed reply folded into a new assistant message. Permission requests
     * become Pending tool cards (or are allowed at once in AUTO); see [handleToolApproval].
     */
    private suspend fun handleCliMessageComplete(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val manager = checkNotNull(acpSessionManager) { "CLI sessions are not available" }
        val settings = settingsStore.settingsFlow.first()
        val conversation = session.state.value
        val cli = conversation.cliSession ?: return
        val assistant = settings.getAssistantById(conversation.assistantId)
        val workspaceId = assistant?.workspaceId?.toString()
            ?: throw IllegalStateException(context.getString(R.string.cli_error_no_workspace))
        val agent = CliAgent.find(settings, cli.agentId)
            ?: throw IllegalStateException(context.getString(R.string.cli_error_agent_missing))
        val filesScope = if (conversation.workspaceCwdScoped) {
            me.rerere.workspace.WorkspaceFilesScope.normalize(conversation.workspaceCwd)
        } else null
        val cwd = conversation.workspaceCwd
            ?: filesScope?.let(me.rerere.workspace.WorkspaceFilesScope::guestPath)
            ?: me.rerere.workspace.WorkspaceManager.ROOTFS_WORKSPACE_DIR
        val mounts = workspaceRepository.executionMounts(workspaceId, filesScope)
        val rootfsEnv = workspaceRepository.getById(workspaceId)?.let { workspaceRepository.rootfsEnvironment(it) }
        val prompt = CliPrompts.systemPrompt(
            assistant = assistant,
            conversation = conversation,
            settings = settings,
            exposedTargets = mounts.exposedTargets,
            filesScope = filesScope,
            rootfsEnvironment = rootfsEnv,
            allSkills = skillManager?.listSkills().orEmpty(),
        )
        val promptHash = CliPrompts.hash(prompt)
        val messages = conversation.currentMessages
        val userMessage = messages.lastOrNull()?.takeIf { it.role == MessageRole.USER } ?: return
        val history = messages.dropLast(1)
        val launch = CliLaunch(
            conversationId = conversationId,
            agent = agent,
            workspaceId = workspaceId,
            filesScope = filesScope,
            cwd = cwd,
            promptFileText = prompt.takeIf { agent.usesPromptFile },
        )

        val lock = Any()
        var reply = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val executedTools = MutableStateFlow(0)
        fun publish(transform: (UIMessage) -> UIMessage) = synchronized(lock) {
            reply = transform(reply)
            session.replyMessageId.value = reply.id
            updateConversation(conversationId, session.state.value.updateCurrentMessages(messages + reply), checkFileRefs = false)
            executedTools.value = reply.parts.count { it is UIMessagePart.Tool && it.isExecuted }
            appEventBus.tryEmit(AppEvent.ChatGenerationUpdate(conversationId, reply, agent.name))
        }

        var cause: Throwable? = null
        var started = false
        var prompted = false
        try {
            manager.withAgent(launch) { live ->
                val acp = live.session
                val diverged = cli.needsHistorySeed || cli.systemPromptHash != promptHash ||
                    cli.historyFingerprint != CliPrompts.fingerprint(history)
                val saved = cli.acpSessionId.takeUnless { diverged }
                if (diverged) cli.acpSessionId?.let { acp.closeSession(it) }
                val meta = if (acp.agent.agySystemPromptAppend && prompt.isNotBlank()) {
                    buildJsonObject { putJsonObject("antigravity") { putJsonObject("systemPrompt") { put("append", prompt) } } }
                } else null
                var gateway: AcpMcpGateway? = null
                try {
                    val opened = if (saved != null && acp.sessionId == saved) {
                        AcpOpenResult.Resumed(saved)
                    } else {
                        val mcpServers = if (
                            assistant != null &&
                            acp.agent.mcpHttp &&
                            mcpManager.getAllAvailableTools(assistant).isNotEmpty()
                        ) {
                            AcpMcpGateway.forAssistant(mcpManager, assistant).apply { start() }.also {
                                gateway = it
                            }.let { JsonArray(listOf(it.toAcpMcpServerJson())) }
                        } else {
                            JsonArray(emptyList())
                        }
                        acp.open(saved, cwd, meta, mcpServers)
                    }
                    val fresh = opened is AcpOpenResult.Created
                    updateCliSession(conversationId) {
                        it.copy(acpSessionId = opened.sessionId, systemPromptHash = promptHash, needsHistorySeed = false)
                    }
                    if (fresh) applyCliChoices(acp, cli)
                    if (opened is AcpOpenResult.Created && opened.previousLost && !diverged) {
                        addError(
                            IllegalStateException(context.getString(R.string.cli_session_rebuilt)),
                            conversationId,
                            title = agent.name,
                            affectsReply = false,
                        )
                    }

                    val exposeUpload = "/upload" in mounts.exposedTargets
                    val allowedImageDirs = listOf(filesManager.uploadDir())
                    val blocks = buildList {
                        if (fresh) {
                            if (meta == null && !agent.usesPromptFile && prompt.isNotBlank()) {
                                add(cliTextBlock("<assistant_instructions>\n$prompt\n</assistant_instructions>"))
                            }
                            CliPrompts.historySeed(history, filesScope = filesScope, exposeUpload = exposeUpload)
                                ?.let { add(cliTextBlock(it)) }
                        }
                        addAll(
                            cliPromptBlocks(
                                message = userMessage,
                                images = acp.agent.imagePrompts,
                                filesScope = filesScope,
                                exposeUpload = exposeUpload,
                                allowedImageDirs = allowedImageDirs,
                            )
                        )
                    }
                    started = true
                    publish { it }
                    prompted = true
                    var permissionWaitMs = 0L
                    val startTime = System.currentTimeMillis()
                    val result = coroutineScope {
                        // 和普通回合一样，每多一个已结束的工具卡片就落库一次（publish 在 ACP 读线程上，不能挂起）
                        // 主线程忙时工具可能先于这里的订阅完成，所以按 0 过滤，不能跳过第一个值
                        val midTurnSaves = launch { executedTools.filter { it > 0 }.collect { saveMidTurn(session) } }
                        try {
                            acp.prompt(
                                blocks = blocks,
                                onUpdate = { update -> publish { AcpParts.fold(it, update) } },
                                onPermission = { request ->
                                    val waitStart = System.currentTimeMillis()
                                    try {
                                        askCliPermission(conversationId, request, ::publish)
                                    } finally {
                                        permissionWaitMs += (System.currentTimeMillis() - waitStart).coerceAtLeast(0L)
                                    }
                                },
                            )
                        } finally {
                            midTurnSaves.cancel()
                        }
                    }
                    val generationTimeMs = (System.currentTimeMillis() - startTime - permissionWaitMs).coerceAtLeast(0L)
                    val runtime = acp.runtime.value
                    val modelSnapshot = ModelSnapshot(
                        modelId = runtime.currentModelId ?: cli.modelId ?: agent.id,
                        displayName = runtime.currentModelName ?: runtime.currentModeName ?: agent.name,
                    )
                    val contextLength = runtime.contextTokens?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                    val contextUsed = runtime.usedTokens?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
                    val tokenUsage = result.usage?.let { usage ->
                        TokenUsage(
                            promptTokens = usage.cliInt("inputTokens"),
                            completionTokens = usage.cliInt("outputTokens"),
                            cachedTokens = usage.cliInt("cachedReadTokens"),
                            totalTokens = usage.cliInt("totalTokens"),
                        )
                    }
                    if (tokenUsage != null || contextUsed != null) {
                        // The agent reports the whole turn at once; its context occupancy comes from usage_update.
                        publish {
                            it.copy(
                                modelSnapshot = modelSnapshot,
                                contextLength = contextLength,
                                generationTimeMs = generationTimeMs,
                                usage = tokenUsage ?: TokenUsage(),
                                lastUsage = contextUsed?.let { used -> TokenUsage(promptTokens = used, totalTokens = used) },
                                stepCount = 1,
                                usageComplete = tokenUsage != null,
                            )
                        }
                    } else {
                        publish {
                            it.copy(
                                modelSnapshot = modelSnapshot,
                                contextLength = contextLength,
                                generationTimeMs = generationTimeMs,
                            )
                        }
                    }
                    if (tokenUsage != null) {
                        usageRecorder?.recordCliUsage(
                            agentId = agent.id,
                            agentName = agent.name,
                            modelName = runtime.currentModelId ?: cli.modelId,
                            conversationId = conversationId,
                            usage = tokenUsage,
                        )
                    }
                    acp.runtime.value.title?.takeIf { session.state.value.title.isBlank() }?.let { title ->
                        updateConversationState(conversationId) { it.copy(title = title) }
                    }
                } finally {
                    gateway?.close()
                }
            }
        } catch (error: Throwable) {
            cause = error
            throw cliError(error, agent, workspaceId)
        } finally {
            if (started) withContext(NonCancellable) {
                publish {
                    AcpParts.finishTools(it, context.getString(R.string.cli_tool_cancelled))
                        .copy(finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()))
                }
                session.finishGenerationAfterStream(
                    cause = cause,
                    save = ::persistConversationSnapshot,
                    onSaveFailure = { saveError -> addError(saveError, conversationId) },
                )
                // Only a prompt the agent received makes its session match this chat.
                if (prompted) runCatching {
                    updateCliSession(conversationId) {
                        it.copy(
                            historyFingerprint = CliPrompts.fingerprint(session.state.value.currentMessages),
                            lastActiveAtMillis = System.currentTimeMillis(),
                        )
                    }
                }
                appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, agent.name, reply.toText().take(50).trim()))
            }
        }
    }

    /** ASK: a Pending card the user answers through [handleToolApproval]; AUTO: allowed at once. */
    private suspend fun askCliPermission(
        conversationId: Uuid,
        request: AcpPermissionRequest,
        publish: ((UIMessage) -> UIMessage) -> Unit,
    ): AcpPermissionOutcome {
        val manager = checkNotNull(acpSessionManager)
        val allow = request.options.firstOrNull { it.kind == "allow_once" }
            ?: request.options.firstOrNull { it.kind.startsWith("allow") }
        val reject = request.options.firstOrNull { it.kind == "reject_once" }
            ?: request.options.firstOrNull { it.kind.startsWith("reject") }
        val mode = sessions[conversationId]?.state?.value?.cliSession?.permissionMode
        if (mode == CliPermissionMode.AUTO && allow != null) return AcpPermissionOutcome.Selected(allow.optionId)

        fun setCard(state: ToolApprovalState, output: String? = null) = publish { message ->
            message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolCallId == request.toolCallId) {
                    part.copy(
                        approvalState = state,
                        output = output?.let { listOf(UIMessagePart.Text(it)) } ?: part.output,
                    )
                } else part
            })
        }
        val waiter = manager.awaitApproval(conversationId, request.toolCallId)
        try {
            setCard(ToolApprovalState.Pending)
            sessions[conversationId]?.let { saveConversation(conversationId, it.state.value) }
            val decision = withTimeoutOrNull(CLI_APPROVAL_TIMEOUT_MS) { waiter.await() }
                ?: CliApprovalDecision.Deny(context.getString(R.string.cli_approval_timeout))
            return when (decision) {
                is CliApprovalDecision.Approve -> {
                    setCard(ToolApprovalState.Approved)
                    val always = request.options.firstOrNull { it.kind == "allow_always" }
                    AcpPermissionOutcome.Selected(((if (decision.always) always else null) ?: allow)?.optionId ?: return AcpPermissionOutcome.Cancelled)
                }
                is CliApprovalDecision.Deny -> {
                    setCard(ToolApprovalState.Denied(decision.reason), decision.reason.ifBlank { context.getString(R.string.cli_tool_denied) })
                    reject?.let { AcpPermissionOutcome.Selected(it.optionId) } ?: AcpPermissionOutcome.Cancelled
                }
            }
        } finally {
            manager.dropApproval(conversationId, request.toolCallId)
        }
    }

    private suspend fun applyCliChoices(acp: AcpSession, cli: ConversationCliSession) {
        val runtime = acp.runtime.value
        cli.configValues.forEach { (id, value) ->
            if (runtime.configOptions.any { option -> option.id == id && option.currentValue != value && option.options.any { it.id == value } }) {
                runCatching { acp.setConfigOption(id, value) }
            }
        }
        if (runtime.configOptions.isEmpty()) {
            cli.modelId?.takeIf { it != runtime.currentModelId && runtime.models.any { m -> m.id == it } }
                ?.let { runCatching { acp.setModel(it) } }
            cli.modeId?.takeIf { it != runtime.currentModeId && runtime.modes.any { m -> m.id == it } }
                ?.let { runCatching { acp.setMode(it) } }
        }
    }

    private fun JsonObject.cliInt(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    /** User-facing error for a failed CLI turn; cancellation passes through. */
    private fun cliError(error: Throwable, agent: CliAgent, workspaceId: String): Throwable = when {
        error is CancellationException -> error
        error is CliBusyException -> IllegalStateException(context.getString(R.string.cli_error_busy), error)
        error is CliNotInstalledException -> CliTurnException(
            context.getString(R.string.cli_error_not_installed, agent.name),
            ChatErrorSolution.CliInstall, workspaceId, agent.installCommand, error,
        )
        error is AcpException && error.code == AcpException.AUTH_REQUIRED -> CliTurnException(
            context.getString(R.string.cli_error_login_required, agent.name) + "\n" + error.message.orEmpty(),
            ChatErrorSolution.CliLogin, workspaceId, agent.loginCommand, error,
        )
        error is IllegalArgumentException && error.message == "Rootfs is not installed" -> CliTurnException(
            context.getString(R.string.cli_error_rootfs_missing),
            ChatErrorSolution.WorkspaceDetail, workspaceId, "", error,
        )
        error is IllegalArgumentException && error.message == "Working directory does not exist" -> CliTurnException(
            context.getString(R.string.cli_error_cwd_missing),
            ChatErrorSolution.WorkspaceDetail, workspaceId, "", error,
        )
        else -> error
    }

    /** Persists a change of the conversation's CLI state (its own column; full saves keep it). */
    private suspend fun updateCliSession(conversationId: Uuid, update: (ConversationCliSession) -> ConversationCliSession) =
        updateSessionMetadata(
            conversationId,
            update = { conversation -> conversation.copy(cliSession = conversation.cliSession?.let(update)) },
            persist = { conversationRepo.updateCliSession(conversationId, it.cliSession) },
        )

    /** Pending CLI cards nobody waits for any more (process gone, app restarted). */
    private suspend fun finishStaleCliTools(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val interrupted = context.getString(R.string.cli_tool_interrupted)
        val nodes = conversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && AcpParts.isAcpTool(part) && !part.isExecuted) {
                        part.copy(output = listOf(UIMessagePart.Text(interrupted)), approvalState = ToolApprovalState.Denied(interrupted))
                    } else part
                })
            })
        }
        if (nodes != conversation.messageNodes) saveConversation(conversationId, conversation.copy(messageNodes = nodes))
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        // 先 reconcile——被禁/失效的工作区调用拿到非空 unavailable 输出后，
        // 消息不再落在「未执行且不可恢复」分支。
        reconcileWorkspacePendingTools(conversationId)
        val conversation = getConversationFlow(conversationId).value

        // 历史保留：不为 policy/失效消息删除整消息（也不为了它删其他任何
        // 消息）。唯一处理是 part 粒度写实：「未执行且不可恢复」的 Tool part
        // （Auto 被中断/旧历史残留）原位打非空 unavailable 输出——从未执行、不
        // 冒充成功、不冒充用户取消；metadata/approvalState 保留。
        // 例外（保持原语义）：
        //   - 审批 Pending 的 part 合法保留（含非当前分支）：就绪审批交互、队列
        //     阻塞语义照旧，不因清理重放或删除；
        //   - Approved/Denied/Answered 可恢复 → 不动；
        //   - 当前分支最后一条助手消息里还有调用在等审批或待恢复时，同一批的
        //     Auto 调用是在等这批审批，恢复时一起执行，不动；
        //   - 节点结构/selectIndex/非当前分支消息一律保留。
        val awaitingBatchId = awaitingToolBatchMessageId(conversation.currentMessages)
        val patchedNodes = conversation.messageNodes.map { node ->
            var changed = false
            val messages = node.messages.map { message ->
                if (message.id == awaitingBatchId) return@map message
                val parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool &&
                        !part.isExecuted &&
                        part.approvalState !is ToolApprovalState.Pending &&
                        !part.approvalState.canResumeToolExecution()
                    ) {
                        changed = true
                        part.copy(
                            output = closedPendingToolOutput(part, UNRESUMABLE_TOOL_CALL_REASON)
                        )
                    } else {
                        part
                    }
                }
                if (changed) message.copy(parts = parts) else message
            }
            if (changed) node.copy(messages = messages) else node
        }

        updateConversation(
            conversationId,
            conversation.copy(messageNodes = patchedNodes),
            checkFileRefs = false,
        )
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.cancelledByUser(kotlin.time.Clock.System.now())

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = withContext(Dispatchers.IO) {
            lastMessage.finishPendingTools { tool ->
                finishInterruptedSubagentTool(tool, File(context.filesDir, FileFolders.SUBAGENT_RUNS))
                    ?: cancelToolByUser(tool)
            }
        }
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 工作区 pending 清理 ----

    /**
     * 把 pending 修复排进会话前任 join 排序链：lazy job + 上任 generation join
     * 后执行，不取消在途生成（不借 stopGeneration——那会 pause 队列），不覆盖
     * loop 本地 messages（本清理只写会话权威态，与流式生产侧无交集）。
     *
     * 排序不变量复用审批路径：setJob(cancelPrevious=false) + job 体第一步
     * previousJob.join()——清理必定落在前任完整结束（含 NonCancellable 协议
     * 回滚）之后、后继生成读状态之前。job 占槽期间 dispatch 自然排队。
     *
     * 队列语义：体内吞掉非取消异常（记日志），job 正常完成 →
     * onGenerationFinished(cause=null) 不 pause 队列；CE 照常传播（用户
     * stop 仍能连带取消本清理）。清理失败不留 pending 永卡队列——判不出就
     * fail-closed 写 unavailable。
     */
    private fun scheduleWorkspacePendingRepair(conversationId: Uuid, reason: String) {
        val session = sessions[conversationId] ?: return
        // 快路径：全树没有未完成工具 part 时不排队（避免空 job 占槽）。
        val hasUnfinishedTool = session.state.value.messageNodes.any { node ->
            node.messages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && !it.isExecuted }
            }
        }
        if (!hasUnfinishedTool) return
        synchronized(session) {
            // Repair writes the history; it runs again on the next load after the compression.
            if (session.isCompressing) return
            val previousJob = session.getJob()
            val job = launchGenerationJob(conversationId, keepAliveInBackground = false) {
                try {
                    previousJob?.join()
                    reconcileWorkspacePendingTools(conversationId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(
                        TAG,
                        "workspace pending repair failed for $conversationId ($reason)",
                        failure,
                    )
                }
            }
            session.setJob(job, cancelPrevious = false)
        }
    }

    /**
     * 会话级 pending 复核：一次性组装 judge（最新 settings + 会话助手精确归属
     * 工作区 + 最新实体），然后 state.update 内按
     * node/message/part 原位打补（全分支都扫）。判定是纯内存操作，CAS 重放安全。
     *
     * 归属/DB 读取失败退化为「无会话工作区」判定表——工作区形状的 pending
     * 一律 unavailable，绝不让 pending 永卡队列。
     * 有变更才 saveConversation（持久化 + 触发队列重检）。
     */
    private suspend fun reconcileWorkspacePendingTools(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val judge = try {
            val settings = settingsStore.settingsFlow.value
            val sessionWorkspace = settings
                .getAssistantById(session.state.value.assistantId)
                ?.workspaceId?.toString()
                ?.let { workspaceRepository.getById(it) }
            buildWorkspacePendingToolJudge(
                sessionWorkspace = sessionWorkspace,
                toolOverrides = settings.toolOverrides,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "reconcileWorkspacePendingTools: degrade judge ($conversationId)", failure)
            buildWorkspacePendingToolJudge(
                sessionWorkspace = null,
                toolOverrides = emptyMap(),
            )
        }
        var result: WorkspacePendingToolPatchResult? = null
        session.updateState { current ->
            current.patchWorkspacePendingToolCalls(judge).also { result = it }.conversation
        }
        val patched = result ?: return
        if (patched.patchedParts > 0) {
            Log.i(
                TAG,
                "reconcileWorkspacePendingTools: $conversationId " +
                    "patched=${patched.patchedParts} examined=${patched.examinedParts}",
            )
            // 持久化走不分整状态回写的路径：patch 已在 state.update CAS 里落定，
            // 这里只落库最新快照，绝不再次覆盖会话态（避免在 job 链上把并发
            // 状态（如刚刚 dispatch 生成的首 chunk）回旧）。
            persistSessionState(conversationId)
            // 修复可能解除 pending 阻塞/改变基线，重检队列与保活基线。
            revalidateCacheWarm(conversationId)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /**
     * 只把会话当前内存态落库，不动 session.state（区别于 [saveConversation] 的
     * "调用方构造对象→覆盖内存→落库"）。供已在 CAS 内完成内存改写的路径使用
     * （pending 清理），保证持久化与并发状态更新不构成读旧-写回竞态。
     */
    private suspend fun persistSessionState(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        session.withPersistence { persistConversationSnapshot(session.state.value) }
    }

    /** 回合中途落库；失败只记日志，回合继续，收尾时还会再存一次。 */
    private suspend fun saveMidTurn(session: ConversationSession) {
        try {
            session.withPersistence { persistConversationSnapshot(session.state.value) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "mid-turn save failed (${session.id})", e)
        }
    }

    /**
     * Under the session persistence lock; no state replacement or attachment GC.
     *
     * Every durable row write advances `revision`; the session adopts the new version
     * so its next CAS write expects the right baseline. If another publication advanced
     * the row first (single-column metadata write, folder clear, management command),
     * the session — the tree's only writer — adopts the durable version and retries
     * once. A soft-deleted conversation is never resurrected.
     */
    private suspend fun persistConversationSnapshot(conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return
        val written = try {
            if (exists) conversationRepo.updateConversation(conversation)
            else conversationRepo.insertConversation(conversation)
        } catch (conflict: MutationConflictException) {
            val durable = conversationRepo.currentVersion(conversation.id) ?: throw conflict
            if (conversationRepo.isSoftDeleted(conversation.id)) throw conflict
            conversationRepo.updateConversation(
                conversation.copy(revision = durable.revision, generation = durable.generation)
            )
        }
        adoptDurableVersion(conversation.id, written)
    }

    private fun adoptDurableVersion(conversationId: Uuid, version: ConversationVersion) {
        sessions[conversationId]?.updateState { current ->
            current.copy(revision = version.revision, generation = version.generation)
        }
    }

    private suspend fun adoptDurableVersion(conversationId: Uuid) {
        conversationRepo.currentVersion(conversationId)?.let { adoptDurableVersion(conversationId, it) }
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        val settings = settingsStore.settingsFlow.first()
        // 关掉自动生成后只跳过对话结束时的自动调用，手动重新生成照常执行
        if (!force && !settings.enableAutoTitle) return@withContext

        runCatching {
            val (model, provider) = requireTitleGenerationTarget(
                model = settings.findModelById(settings.fastModelId),
                providerForModel = { it.findProvider(settings.providers) },
                missingModelMessage = context.getString(R.string.error_fast_model_not_found),
                missingProviderMessage = context.getString(R.string.error_fast_model_provider_not_found),
            )

            val providerHandler = usageRecorder.wrapOrSelf(
                providerManager.getProviderByType(provider),
                UsageTag(UsageSource.TITLE, assistantId = conversation.assistantId, conversationId = conversationId),
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了：标题走单列更新，
            // 内存态只覆盖 title 字段，不回读整行避免覆盖并发写入的其他字段
            val title = result.message.toText().trim()
            updateSessionMetadata(
                conversationId,
                update = { it.copy(title = title) },
                persist = { conversationRepo.updateTitle(conversationId, it.title) },
            )
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
                affectsReply = false,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = usageRecorder.wrapOrSelf(
                providerManager.getProviderByType(provider),
                UsageTag(UsageSource.SUGGESTION, assistantId = conversation.assistantId, conversationId = conversationId),
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(10)

            // 建议列表是会话行字段：单列更新 + 内存同步，不重建消息节点
            updateSessionMetadata(
                conversationId,
                update = { it.copy(chatSuggestions = suggestions) },
                persist = { conversationRepo.updateChatSuggestions(conversationId, it.chatSuggestions) },
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史（服务级后台任务，App 与 Web 共用） ----

    /** 压缩状态；App 聊天页与 Web 会话流都持有会话引用，取用时创建会话无副作用。 */
    fun compressStateFlow(conversationId: Uuid): StateFlow<CompressState?> =
        getOrCreateSession(conversationId).compressState.asStateFlow()

    /** 未加载的会话没有压缩状态；只读查询不为此创建会话。 */
    fun currentCompressState(conversationId: Uuid): CompressState? = sessions[conversationId]?.compressState?.value

    /** 「仅提醒」档位的提示；App 聊天页与 Web 会话流共用。 */
    fun compressSuggestionFlow(conversationId: Uuid): StateFlow<CompressSuggestion?> =
        getOrCreateSession(conversationId).compressSuggestion.asStateFlow()

    fun dismissCompressSuggestion(conversationId: Uuid) {
        sessions[conversationId]?.compressSuggestion?.value = null
    }

    /**
     * 开始后台压缩。开始时会话必须空闲（生成中、待审批、提交中、有排队消息都拒绝），上一个检查点之后
     * 有新消息且没有未执行完的工具调用；期间新消息只进队列不派发；提交时在持久化锁内复核忙碌状态与
     * 被总结的范围，变了就放弃（不覆盖）。成功后在切点插入摘要检查点，原消息原样保留。
     *
     * @throws CompressRejectedException 已在压缩 / 没有可压缩的消息 / 有未执行完的工具调用
     * @throws me.rerere.rikkihub.data.mutation.ConversationBusyException 会话忙
     */
    suspend fun startCompression(conversationId: Uuid, request: CompressRequest, origin: CompressOrigin) {
        withConversationSession(conversationId) { session ->
            initializeSession(session)
            val job = synchronized(session) {
                requireNotAlreadyCompressing(session)
                requireIdle(session, conversationId)
                beginCompressionLocked(session, request, origin)
            }
            job.start()
        }
    }

    /**
     * 回复因上下文超限失败后「压缩后重试」：最后一条用户消息及之后的留在检查点后面，之前的压进摘要，
     * 成功后从那条用户消息重新生成。失败后队列是暂停的，排着的消息不挡这次压缩。
     */
    suspend fun compressAndRetry(conversationId: Uuid, origin: CompressOrigin) {
        withConversationSession(conversationId) { session ->
            initializeSession(session)
            val job = synchronized(session) {
                requireNotAlreadyCompressing(session)
                requireIdle(session, conversationId, allowQueued = true)
                val nodes = session.state.value.messageNodes
                val userIndex = nodes.indexOfLast {
                    it.currentMessage.role == MessageRole.USER && !it.currentMessage.isContextCheckpoint
                }
                if (userIndex < 0) {
                    throw CompressRejectedException(
                        CompressErrorCodes.NOTHING_TO_COMPRESS,
                        context.getString(R.string.chat_page_compress_nothing_to_compress),
                    )
                }
                val request = CompressRequest(
                    keepRecent = nodes.size - userIndex,
                    retryMessageId = nodes[userIndex].currentMessage.id,
                )
                beginCompressionLocked(session, request, origin)
            }
            job.start()
        }
    }

    /** 「先看再采用」：[summary] 为 null 时丢弃，否则用它（可能改过）作为检查点。返回是否有待确认的压缩。 */
    fun resolveCompressionReview(conversationId: Uuid, summary: String?): Boolean {
        require(summary == null || summary.isNotBlank()) { "summary must not be blank" }
        val pending = sessions[conversationId]?.compressReview ?: return false
        return pending.complete(summary)
    }

    /** 压缩对话框的本地预估；会话未加载或没有可压缩的消息时为 null。 */
    fun compressionEstimate(
        conversationId: Uuid,
        keepRecent: Int,
        fromOriginal: Boolean = false,
        carryMode: CarryOriginalsMode? = null,
        carryCount: Int? = null,
        carryIncludeReplies: Boolean? = null,
        carryExcludedNodeIds: Set<Uuid> = emptySet(),
        carryEdits: Map<Uuid, CarriedOriginalEdit> = emptyMap(),
        templateId: String? = null,
        additionalPrompt: String = "",
    ): CompressionEstimate? {
        val conversation = sessions[conversationId]?.state?.value ?: return null
        val settings = settingsStore.settingsFlow.value
        val config = settings.compression.resolvedFor(conversation.compression)
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val chatModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        val compressionModel = config.modelId?.let { settings.findModelById(it) } ?: chatModel
        val chatBudget = chatModel?.let { compressionInputBudget(it, assistant.maxTokens) }
        val compressionBudget = compressionModel?.let { compressionInputBudget(it, config.summaryTokens) }
        val resolvedTemplateId = templateId?.takeIf { config.templateExists(it) } ?: config.templateId
        val structure = config.customTemplates.firstOrNull { it.id == resolvedTemplateId }?.prompt
            ?: builtInCompressStructure(resolvedTemplateId)
        return conversation.estimateCompression(
            keepRecent = keepRecent,
            summaryTokens = config.summaryTokens,
            budget = chatBudget,
            fromOriginal = fromOriginal,
            carryMode = carryMode ?: config.carryOriginalsMode,
            carryCount = carryCount ?: config.carryOriginalsCount,
            carryIncludeReplies = carryIncludeReplies ?: config.carryIncludeReplies,
            carryExcludedNodeIds = carryExcludedNodeIds,
            carryEdits = carryEdits,
            targetPercent = config.targetPercent,
            compressionBudget = compressionBudget,
            maxChunkRequests = config.maxChunkRequests,
            separateModel = chatModel != null && compressionModel != null && compressionModel.id != chatModel.id,
            structure = structure,
            focus = additionalPrompt,
            locale = Locale.getDefault().displayName,
        )
    }

    /** 会话对话模型的输入预算；模型未知或上下文窗口未知时为 null。 */
    private fun inputBudgetFor(conversation: Conversation, settings: Settings): Int? {
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return null
        return compressionInputBudget(model, assistant.maxTokens)
    }

    private fun requireNotAlreadyCompressing(session: ConversationSession) {
        if (session.isCompressing) {
            throw CompressRejectedException(CompressErrorCodes.ALREADY_COMPRESSING, "A compression is already running")
        }
    }

    /**
     * 在 `synchronized(session)` 内调用：校验范围、置运行状态并建好（未启动的）任务，调用方在锁外或锁内 `start()`。
     * 忙碌状态由调用方检查（自动压缩在派发排队消息时发起，队列本来就不空）。
     */
    private fun beginCompressionLocked(session: ConversationSession, request: CompressRequest, origin: CompressOrigin): Job {
        val current = session.state.value
        if (current.cliSession != null) {
            throw CompressRejectedException(
                CompressErrorCodes.CLI_CONVERSATION,
                context.getString(R.string.cli_compress_unsupported),
            )
        }
        val range = compressionRange(current.messageNodes, request.keepRecent, request.fromOriginal) ?: run {
            // 有新消息但都在要保留的最近几条里，和根本没有新消息分开提示
            val notEnough = request.keepRecent > 0 &&
                compressionRange(current.messageNodes, fromOriginal = request.fromOriginal) != null
            throw CompressRejectedException(
                if (notEnough) CompressErrorCodes.NOT_ENOUGH_MESSAGES else CompressErrorCodes.NOTHING_TO_COMPRESS,
                context.getString(
                    if (notEnough) R.string.chat_page_compress_not_enough_messages
                    else R.string.chat_page_compress_nothing_to_compress
                ),
            )
        }
        // 只总结到切点：保留的最近几条不进摘要请求，检查点也插在切点之后
        val snapshot = current.copy(
            messageNodes = current.messageNodes.take(current.messageNodes.size - request.keepRecent),
        )
        // 没有结果的工具调用会让请求被拒，压到检查点之前也再不会执行
        if (range.hasUnfinishedToolCalls()) {
            throw CompressRejectedException(
                CompressErrorCodes.PENDING_TOOLS,
                context.getString(R.string.chat_page_compress_handle_pending_tools),
            )
        }
        val startedAt = System.currentTimeMillis()
        session.compressState.value = CompressState(
            status = CompressStatus.RUNNING,
            origin = origin,
            startedAt = startedAt,
            fromOriginal = request.fromOriginal,
            createNewConversation = request.createNewConversation,
        )
        session.compressSuggestion.value = null
        // The task holds its own reference: leaving the chat page no longer stops it.
        session.acquire()
        val outcome = java.util.concurrent.atomic.AtomicReference<CompressState?>(null)
        return appScope.launch(start = CoroutineStart.LAZY) {
            outcome.set(runCompression(session, snapshot, current, request, origin, startedAt))
        }.also { job ->
            session.compressJob = job
            // Runs even when cancelled before the body started, so state and reference never leak.
            job.invokeOnCompletion { cause ->
                finishCompression(session, job, request, origin, startedAt, outcome.get(), cause)
            }
        }
    }

    /**
     * 派发排队消息前（锁内）检查用量：「仅提醒」越过触发比例时给出提示，「自动」时先压缩、这条消息留在队列里，
     * 压缩结束后照常派发。返回 true 表示已经开始压缩，这次不派发。
     */
    private fun autoCompressLocked(session: ConversationSession, next: QueuedMessage): Boolean {
        val conversation = session.state.value
        if (conversation.cliSession != null) return false
        val settings = settingsStore.settingsFlow.value
        val config = settings.compression.resolvedFor(conversation.compression)
        if (config.mode == AutoCompressMode.OFF) {
            session.compressSuggestion.value = null
            return false
        }
        val budget = inputBudgetFor(conversation, settings) ?: return false
        val pendingText = next.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val usage = conversation.contextUsage(pendingText)
        val percent = usagePercent(usage.tokens, budget) ?: return false
        if (percent < config.triggerPercent) {
            session.compressSuggestion.value = null
            session.autoCompressHistoryKey = null
            return false
        }
        val checkpointId = conversation.messageNodes.lastOrNull { it.currentMessage.isContextCheckpoint }?.id
        if (config.mode == AutoCompressMode.NOTIFY) {
            // 每越过一次阈值（之间没有新的检查点）只弹一次：还开着就跟着更新用量，关掉后不再弹
            val key = "notify:$checkpointId"
            if (session.autoCompressHistoryKey != key || session.compressSuggestion.value != null) {
                session.autoCompressHistoryKey = key
                session.compressSuggestion.value = CompressSuggestion(percent, usage.exact)
            }
            return false
        }
        // 同一份历史只自动压一次：失败后这条照常发出，下一条（历史变了）再试
        val key = "auto:${conversation.messageNodes.size}:$checkpointId"
        if (session.autoCompressHistoryKey == key) return false
        session.autoCompressHistoryKey = key
        val checkpointIndex = conversation.messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val keep = autoKeepRecent(
            tailTokens = conversation.messageNodes.drop(checkpointIndex + 1).map { it.currentMessage.estimatedTokens() },
            preferred = config.keepRecent,
            minKeep = 0,
            // 这条待发的消息压缩后也要放得下
            fixedTokens = conversation.fixedContextTokens() + TokenCounter.count(pendingText),
            summaryTokens = config.summaryTokens,
            targetTokens = (budget * config.targetPercent / 100),
        )
        return try {
            beginCompressionLocked(session, CompressRequest(keepRecent = keep), CompressOrigin.AUTO).start()
            true
        } catch (rejected: CompressRejectedException) {
            Log.i(TAG, "auto compression skipped for ${session.id}: ${rejected.code}")
            false
        }
    }

    /** 回复因上下文超限失败：自动档位下等这次生成结束后压缩一次再重试；同一条用户消息只自动重试一次。 */
    private fun scheduleOverflowRetry(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val conversation = session.state.value
        val config = settingsStore.settingsFlow.value.compression.resolvedFor(conversation.compression)
        if (config.mode != AutoCompressMode.AUTO || conversation.cliSession != null) return
        val userMessage = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER && !it.isContextCheckpoint }
            ?: return
        val key = "overflow:${userMessage.id}"
        synchronized(session) {
            if (session.autoCompressHistoryKey == key) return
            session.autoCompressHistoryKey = key
        }
        val generation = session.getJob()
        launchWithConversationReference(conversationId) {
            generation?.join()
            try {
                compressAndRetry(conversationId, CompressOrigin.AUTO)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addError(e, conversationId, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    /** 取消正在进行的压缩；返回是否真的有任务被取消。提交开始后不可取消（要么全成要么不动）。 */
    fun cancelCompression(conversationId: Uuid): Boolean {
        val job = sessions[conversationId]?.compressJob?.takeIf { it.isActive } ?: return false
        job.cancel()
        return true
    }

    private data class CompressionGenerationOutcome(
        val summary: String,
        val templateId: String,
        val requestCount: Int,
        val completedChunks: Int,
        val totalChunks: Int,
        val chunked: Boolean,
        val cacheBypassed: Boolean,
        val carriedCount: Int,
        val rangeStartNodeId: Uuid?,
        val rangeEndNodeId: Uuid?,
        val summarizedCount: Int,
        val supersededCheckpointNodeId: Uuid?,
        val totalUsage: TokenUsage?,
    )

    /** Body of the task; returns the terminal state, or throws [CancellationException] when cancelled. */
    private suspend fun runCompression(
        session: ConversationSession,
        snapshot: Conversation,
        originalConversation: Conversation,
        request: CompressRequest,
        origin: CompressOrigin,
        startedAt: Long,
    ): CompressState {
        fun finished(
            status: CompressStatus,
            code: String? = null,
            message: String? = null,
            generated: CompressionGenerationOutcome? = null,
            targetConversationId: Uuid? = null,
        ) = CompressState(
            status = status,
            origin = origin,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            errorCode = code,
            errorMessage = message,
            requestCount = generated?.requestCount ?: 0,
            completedChunks = generated?.completedChunks ?: 0,
            totalChunks = generated?.totalChunks ?: 0,
            chunked = generated?.chunked ?: false,
            fromOriginal = request.fromOriginal,
            cacheBypassed = generated?.cacheBypassed ?: false,
            carriedCount = generated?.carriedCount ?: 0,
            createNewConversation = request.createNewConversation,
            targetConversationId = targetConversationId,
            totalUsage = generated?.totalUsage,
        )
        return try {
            val generated = summarizeForCompression(
                session = session,
                snapshot = snapshot,
                originalConversation = originalConversation,
                request = request,
                origin = origin,
                startedAt = startedAt,
            )
            currentCoroutineContext().ensureActive()
            val summary = if (request.review) {
                val decision = CompletableDeferred<String?>()
                session.compressReview = decision
                session.compressState.value = CompressState(
                    status = CompressStatus.AWAITING_REVIEW,
                    origin = origin,
                    startedAt = startedAt,
                    reviewSummary = generated.summary,
                    requestCount = generated.requestCount,
                    completedChunks = generated.completedChunks,
                    totalChunks = generated.totalChunks,
                    chunked = generated.chunked,
                    fromOriginal = request.fromOriginal,
                    cacheBypassed = generated.cacheBypassed,
                    carriedCount = generated.carriedCount,
                    createNewConversation = request.createNewConversation,
                    totalUsage = generated.totalUsage,
                )
                try {
                    decision.await()
                } finally {
                    session.compressReview = null
                } ?: return finished(CompressStatus.CANCELLED, generated = generated)
            } else {
                generated.summary
            }
            val kind = when {
                request.retryMessageId != null -> ContextCheckpointKind.OVERFLOW_RETRY
                origin == CompressOrigin.AUTO -> ContextCheckpointKind.AUTO
                else -> ContextCheckpointKind.MANUAL
            }
            val checkpointMeta = ContextCheckpointMetadata(
                kind = kind,
                templateId = generated.templateId,
                fromOriginal = request.fromOriginal,
                chunked = generated.chunked,
                requestCount = generated.requestCount,
                carriedCount = generated.carriedCount,
                rangeStartNodeId = generated.rangeStartNodeId,
                rangeEndNodeId = generated.rangeEndNodeId,
                summarizedCount = generated.summarizedCount,
                supersededCheckpointNodeId = generated.supersededCheckpointNodeId,
                usage = generated.totalUsage,
            )
            val targetId = withContext(NonCancellable) {
                if (request.createNewConversation) {
                    commitCompressionToNewConversation(session, snapshot, summary, checkpointMeta).id
                } else {
                    commitCompression(session, snapshot, summary, checkpointMeta)
                    null
                }
            }
            finished(CompressStatus.SUCCEEDED, generated = generated, targetConversationId = targetId)
        } catch (rejected: CompressRejectedException) {
            finished(CompressStatus.FAILED, rejected.code, rejected.message)
        } catch (limit: CompressionRequestLimitException) {
            finished(
                CompressStatus.FAILED,
                CompressErrorCodes.REQUEST_LIMIT_EXCEEDED,
                context.getString(R.string.chat_page_compress_error_request_limit, limit.maxRequests),
            )
        } catch (timeout: CompressionTimeoutException) {
            finished(
                CompressStatus.FAILED,
                CompressErrorCodes.REQUEST_TIMEOUT,
                context.getString(R.string.chat_page_compress_error_timeout, timeout.timeoutSeconds),
            )
        } catch (toolCall: CompressionToolCallException) {
            finished(
                CompressStatus.FAILED,
                CompressErrorCodes.TOOL_CALL_RESPONSE,
                context.getString(R.string.chat_page_compress_tool_call_response),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "compression failed for ${session.id}", error)
            finished(CompressStatus.FAILED, CompressErrorCodes.COMPRESS_FAILED, error.message ?: error.javaClass.simpleName)
        }
    }

    /** Completion handler: publish the terminal state, drop the task's reference, release held sends. */
    private fun finishCompression(
        session: ConversationSession,
        job: Job,
        request: CompressRequest,
        origin: CompressOrigin,
        startedAt: Long,
        outcome: CompressState?,
        cause: Throwable?,
    ) {
        val final = outcome ?: CompressState(
            status = if (cause is CancellationException) CompressStatus.CANCELLED else CompressStatus.FAILED,
            origin = origin,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            errorCode = if (cause is CancellationException) null else CompressErrorCodes.COMPRESS_FAILED,
            errorMessage = cause?.takeUnless { it is CancellationException }?.message,
            fromOriginal = request.fromOriginal,
            createNewConversation = request.createNewConversation,
        )
        synchronized(session) {
            if (session.compressJob === job) session.compressJob = null
            session.compressState.value = final
        }
        session.release()
        // Web 手动发起的失败由它自己的对话框显示；App 与自动压缩的失败走错误卡片（Web 也会收到）
        if (final.status == CompressStatus.FAILED && origin != CompressOrigin.WEB) {
            addError(
                IllegalStateException(final.errorMessage ?: final.errorCode),
                conversationId = session.id,
                title = context.getString(R.string.error_title_compress_conversation),
            )
        }
        val retry = request.retryMessageId?.takeIf { final.status == CompressStatus.SUCCEEDED }
            ?.let { id -> session.state.value.currentMessages.firstOrNull { it.id == id } }
        if (retry != null) {
            runCatching { retryAfterCompression(session.id, retry) }.onFailure { error ->
                addError(error, session.id, title = context.getString(R.string.error_title_regenerate_message))
            }
        } else {
            // Messages sent meanwhile were held in the queue; release them in order.
            dispatchNextQueuedMessage(session.id)
        }
    }

    /**
     * 超限压缩成功后重试那一轮。回复停在已执行的工具结果上（多步回合中途超限）时接着这一轮往下生成，
     * 已完成的步骤都留着；否则按对用户消息重新生成处理（已有回复留作另一个分支）。
     */
    private fun retryAfterCompression(conversationId: Uuid, userMessage: UIMessage) {
        val nodes = getOrCreateSession(conversationId).state.value.messageNodes
        val userIndex = nodes.indexOfFirst { it.currentMessage.id == userMessage.id }
        val reply = nodes.getOrNull(userIndex + 1)?.currentMessage
        val midTurn = reply?.role == MessageRole.ASSISTANT &&
            (reply.parts.lastOrNull() as? UIMessagePart.Tool)?.isExecuted == true
        if (midTurn) continueGeneration(conversationId) else regenerateAtMessage(conversationId, userMessage)
    }

    /** 接着当前分支最后一条回复继续生成（与审批之后继续同一条路径）。 */
    private fun continueGeneration(conversationId: Uuid) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        requireNotCompressing(session)
        val previousJob = session.getJob()
        val job = launchGenerationJob(conversationId = conversationId, keepAliveInBackground = true) {
            try {
                previousJob?.join()
                handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }
        session.setJob(job)
    }

    private val greetingBuilder = GreetingBuilder(tavernRuntime)

    /** 会话绑定的角色（连同这一刻生效的角色书、卡内正则是否放行）；没绑或角色已删除返回 null。 */
    private suspend fun activeCharacterOf(conversation: Conversation, settings: Settings): ActiveCharacter? =
        characterRepository?.let { ActiveCharacter.resolve(it, settings, conversation.characterId) }

    /**
     * 回合准备：世界书扫描与激活、预设文本里的宏求值（一次，之后冻结）。结果里的变量与计时状态写回会话和设置；
     * 预演（[dryRun]）不写。
     */
    private suspend fun prepareTurn(
        conversation: Conversation,
        assistant: Assistant,
        model: Model,
        messages: List<UIMessage>,
        generationType: GenerationType,
        dryRun: Boolean = false,
    ): PreparedTurn {
        val settings = settingsStore.settingsFlow.first()
        val character = activeCharacterOf(conversation, settings)
        val turn = turnPreparation.prepare(
            TurnRequest(
                assistant = assistant,
                settings = settings,
                model = model,
                conversationId = conversation.id,
                metadata = conversation.tavernMetadata,
                conversationLibraryEntryIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                customSystemPrompt = conversation.customSystemPrompt,
                messages = messages,
                generationType = generationType,
                dryRun = dryRun,
                userName = RikkihubMacros.userName(settings),
                charName = RikkihubMacros.charName(assistant, character),
                character = character,
                ownMacros = RikkihubMacros.resolvers(context, settings, model, assistant),
            )
        )
        if (!dryRun) {
            val newMetadata = turn.newConversationState().toMetadata()
            if (newMetadata != conversation.tavernMetadata) {
                updateConversationState(conversation.id) { it.copy(tavernMetadata = newMetadata) }
            }
            if (turn.globalVariablesChanged) {
                val updated = turn.newGlobalVariables()
                settingsStore.update { it.copy(tavern = it.tavern.copy(globalVariables = updated)) }
            }
        }
        return turn
    }

    /**
     * 生成摘要：普通压缩走正常对话请求组装（同模型时命中提示词缓存）；超出预算 / 单次超限 / 「从原文重压」
     * 改走独立分段通道；若配置了「原话带上」，将选出的原话直接写进摘要正文之后（D7）。
     */
    private suspend fun summarizeForCompression(
        session: ConversationSession,
        snapshot: Conversation,
        originalConversation: Conversation,
        request: CompressRequest,
        origin: CompressOrigin,
        startedAt: Long,
    ): CompressionGenerationOutcome {
        val settings = settingsStore.settingsFlow.first()
        val conversationAssistant = settings.getAssistantById(snapshot.assistantId)
        val assistant = conversationAssistant ?: settings.getCurrentAssistant().copy(workspaceId = null)
        val config = settings.compression.resolvedFor(snapshot.compression)
        val chatModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        // 另选的压缩模型不再和对话共用请求前缀（命中不了缓存），被删掉时回到对话模型
        val model = config.modelId?.let { settings.findModelById(it) }
            ?: chatModel
            ?: throw IllegalStateException("No chat model selected")
        val separateModel = chatModel != null && model.id != chatModel.id
        val templateId = request.templateId?.takeIf { config.templateExists(it) } ?: config.templateId
        val structure = config.customTemplates.firstOrNull { it.id == templateId }?.prompt
            ?: builtInCompressStructure(templateId)
        val locale = Locale.getDefault().displayName
        val compressionBudget = compressionInputBudget(model, config.summaryTokens)
        val chatBudget = chatModel?.let { compressionInputBudget(it, assistant.maxTokens) }

        val carriedPlan = planCarriedOriginals(
            conversation = originalConversation,
            keepRecent = request.keepRecent,
            fromOriginal = request.fromOriginal,
            mode = request.carryMode ?: config.carryOriginalsMode,
            count = request.carryCount ?: config.carryOriginalsCount,
            includeReplies = request.carryIncludeReplies ?: config.carryIncludeReplies,
            excludedNodeIds = request.carryExcludedNodeIds,
            edits = request.carryEdits,
            summaryTokens = config.summaryTokens,
            targetPercent = config.targetPercent,
            chatBudget = chatBudget,
        )

        val range = compressionRange(snapshot.messageNodes, keepRecent = 0, fromOriginal = request.fromOriginal)
            ?: throw CompressRejectedException(
                CompressErrorCodes.NOTHING_TO_COMPRESS,
                context.getString(R.string.chat_page_compress_nothing_to_compress),
            )
        val checkpointIndex = snapshot.messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val previousCheckpointNode = snapshot.messageNodes.getOrNull(checkpointIndex)
        val previousSummaryNode = if (request.fromOriginal) {
            null
        } else {
            previousCheckpointNode?.currentMessage?.takeUnless { it.isClearContextCheckpoint }
        }
        val previousSummaryText = previousSummaryNode?.toText().orEmpty()
        val materials = serializeForChunkedCompression(range)

        val renderStage = { prev: String, seg: String, target: Int ->
            buildChunkStagePrompt(prev, seg, request.additionalPrompt, target, locale)
        }
        val renderFinal = { prev: String, seg: String, target: Int ->
            buildChunkFinalPrompt(structure, prev, seg, request.additionalPrompt, target, locale)
        }

        suspend fun runStandalone(
            budgetTokens: Int,
            maxRequests: Int,
            priorRequests: Int = 0,
            initialMaxSegmentTokens: Int? = null,
        ): CompressionRunResult {
            if (materials.isEmpty() && previousSummaryText.isBlank()) {
                throw CompressRejectedException(
                    CompressErrorCodes.NOTHING_TO_COMPRESS,
                    context.getString(R.string.chat_page_compress_nothing_to_compress),
                )
            }
            return generationLoop.generateStandaloneCompressionSummary(
                settings = settings,
                model = model,
                assistant = assistant,
                conversationId = snapshot.id,
                materials = materials,
                initialPreviousSummary = previousSummaryText,
                inputBudgetTokens = budgetTokens,
                summaryTokens = config.summaryTokens,
                maxRequests = maxRequests,
                timeoutSeconds = config.chunkTimeoutSeconds,
                initialMaxSegmentTokens = initialMaxSegmentTokens,
                renderStage = renderStage,
                renderFinal = renderFinal,
                onProgress = { completed, total, requests ->
                    currentCoroutineContext().ensureActive()
                    session.compressState.value = CompressState(
                        status = CompressStatus.RUNNING,
                        origin = origin,
                        startedAt = startedAt,
                        requestCount = requests + priorRequests,
                        completedChunks = completed,
                        totalChunks = total,
                        chunked = total > 1 || requests + priorRequests > 1,
                        fromOriginal = request.fromOriginal,
                        cacheBypassed = true,
                        createNewConversation = request.createNewConversation,
                    )
                },
            )
        }

        val runResult: CompressionRunResult
        val cacheBypassed: Boolean
        if (request.fromOriginal) {
            runResult = runStandalone(compressionBudget ?: DEFAULT_UNKNOWN_WINDOW_CHUNK_BUDGET, config.maxChunkRequests)
            cacheBypassed = true
        } else {
            val singlePassPrompt = buildCompressPrompt(
                structure = structure,
                focus = request.additionalPrompt,
                summaryTokens = config.summaryTokens,
                locale = locale,
            )
            val summarizedMessages = listOfNotNull(previousSummaryNode) + range.map { it.currentMessage }
            val singlePassTokens = originalConversation.fixedContextTokens() +
                summarizedMessages.sumOf { it.estimatedTokens() } +
                TokenCounter.count(singlePassPrompt)
            if (compressionBudget != null && singlePassTokens > compressionBudget) {
                runResult = runStandalone(compressionBudget, config.maxChunkRequests)
                cacheBypassed = true
            } else {
                val singleAttempt = try {
                    val workspace = conversationAssistant?.workspaceId?.toString()?.let { workspaceRepository.getById(it) }
                    // 摘要是预演：不推进世界书计时、不掷概率骰、变量不保存，但请求前缀与正常对话一致
                    val turn = prepareTurn(
                        conversation = snapshot,
                        assistant = assistant,
                        model = model,
                        messages = snapshot.currentMessages,
                        generationType = GenerationType.QUIET,
                        dryRun = true,
                    )
                    generationLoop.generateCompressionSummary(
                        settings = settings,
                        model = model,
                        messages = snapshot.currentMessages,
                        inputTransformers = generationInputTransformers(assistant, snapshot.id),
                        assistant = assistant,
                        memories = memoriesFor(assistant),
                        toolSnapshotProvider = toolSnapshotProviderFor(
                            conversationId = snapshot.id,
                            assistantId = snapshot.assistantId,
                            workspaceId = conversationAssistant?.workspaceId,
                            model = model,
                        ),
                        compressPrompt = singlePassPrompt,
                        conversationSystemPrompt = snapshot.customSystemPrompt,
                        turn = turn,
                        conversationId = snapshot.id,
                        workspaceCwd = snapshot.workspaceCwd,
                        harnessMinimalWorkspace = workspace?.harnessMinimal == true,
                        exposeUpload = workspace?.exposeUpload != false,
                        timeoutSeconds = config.chunkTimeoutSeconds,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException || !e.isContextLengthError()) throw e
                    // 单次请求意外超限时自动退到分段压缩：保持完整输入预算，仅收紧单段原文上限触发拆段
                    val materialsTokens = materials.sumOf { TokenCounter.count(it) }
                    val fallbackSegCap = if (materialsTokens > 16) {
                        minOf(materialsTokens - 1, (materialsTokens * 65 / 100)).coerceAtLeast(16)
                    } else {
                        null
                    }
                    val remaining = (config.maxChunkRequests - 1).coerceAtLeast(1)
                    val staged = try {
                        runStandalone(
                            budgetTokens = compressionBudget ?: DEFAULT_UNKNOWN_WINDOW_CHUNK_BUDGET,
                            maxRequests = remaining,
                            priorRequests = 1,
                            initialMaxSegmentTokens = fallbackSegCap,
                        )
                    } catch (e: CompressionRequestLimitException) {
                        throw CompressionRequestLimitException(config.maxChunkRequests)
                    }
                    staged.copy(requestCount = staged.requestCount + 1, chunked = true)
                }
                runResult = singleAttempt
                cacheBypassed = separateModel || singleAttempt.chunked
            }
        }

        val combinedSummary = appendCarriedOriginals(runResult.summary, carriedPlan.items)
        return CompressionGenerationOutcome(
            summary = combinedSummary,
            templateId = templateId,
            requestCount = runResult.requestCount,
            completedChunks = runResult.completedChunks,
            totalChunks = runResult.totalChunks,
            chunked = runResult.chunked,
            cacheBypassed = cacheBypassed,
            carriedCount = carriedPlan.items.size,
            rangeStartNodeId = range.firstOrNull()?.id,
            rangeEndNodeId = range.lastOrNull()?.id,
            summarizedCount = range.size,
            supersededCheckpointNodeId = previousCheckpointNode?.id,
            totalUsage = runResult.totalUsage,
        )
    }

    /**
     * 在持久化锁内复核：没有生成/待审批/提交中（排队允许——只可能是压缩期间被扣住的新消息），
     * 且被总结的范围没变；然后在切点之后插入摘要检查点并持久化。不删任何消息，附件引用不变。
     */
    private suspend fun commitCompression(
        session: ConversationSession,
        snapshot: Conversation,
        summary: String,
        meta: ContextCheckpointMetadata,
    ) = session.withPersistence {
        val busy = synchronized(session) {
            when {
                session.isGenerating -> "GENERATING"
                session.hasPendingToolApprovals() -> "PENDING_APPROVAL"
                session.submittingMessage != null -> "SUBMITTING"
                else -> null
            }
        }
        val current = session.state.value
        when (compressionCommitError(snapshot, current, busy)) {
            null -> Unit
            CompressErrorCodes.CONVERSATION_BUSY -> throw CompressRejectedException(
                CompressErrorCodes.CONVERSATION_BUSY, "Conversation became busy ($busy)",
            )
            else -> throw CompressRejectedException(
                CompressErrorCodes.CONVERSATION_CHANGED,
                context.getString(R.string.chat_page_compress_conversation_changed),
            )
        }
        val compressed = current.insertContextCheckpoint(
            afterNodeId = snapshot.messageNodes.last().id,
            summary = summary,
            meta = meta,
        )
            ?: throw CompressRejectedException(
                CompressErrorCodes.CONVERSATION_CHANGED,
                context.getString(R.string.chat_page_compress_conversation_changed),
            )
        val updated = session.replaceConversation(compressed.copy(chatSuggestions = emptyList()))
        persistConversationSnapshot(updated)
        revalidateCacheWarm(session.id)
    }

    /** 基于压缩摘要和切点之后保留的最近消息新建会话，原会话保持不变。 */
    private suspend fun commitCompressionToNewConversation(
        session: ConversationSession,
        snapshot: Conversation,
        summary: String,
        meta: ContextCheckpointMetadata,
    ): Conversation {
        val current = session.withPersistence {
            val busy = synchronized(session) {
                when {
                    session.isGenerating -> "GENERATING"
                    session.hasPendingToolApprovals() -> "PENDING_APPROVAL"
                    session.submittingMessage != null -> "SUBMITTING"
                    else -> null
                }
            }
            val latest = session.state.value
            when (compressionCommitError(snapshot, latest, busy)) {
                null -> Unit
                CompressErrorCodes.CONVERSATION_BUSY -> throw CompressRejectedException(
                    CompressErrorCodes.CONVERSATION_BUSY, "Conversation became busy ($busy)",
                )
                else -> throw CompressRejectedException(
                    CompressErrorCodes.CONVERSATION_CHANGED,
                    context.getString(R.string.chat_page_compress_conversation_changed),
                )
            }
            latest
        }
        val anchorId = snapshot.messageNodes.last().id
        val anchorIndex = current.messageNodes.indexOfFirst { it.id == anchorId }
        if (anchorIndex < 0) {
            throw CompressRejectedException(
                CompressErrorCodes.CONVERSATION_CHANGED,
                context.getString(R.string.chat_page_compress_conversation_changed),
            )
        }
        val checkpointNode = UIMessage.user(summary).copy(
            isContextCheckpoint = true,
            checkpointMeta = meta.copy(
                rangeStartNodeId = null,
                rangeEndNodeId = null,
                supersededCheckpointNodeId = null,
            ),
        ).toMessageNode()
        val copiedTailNodes = current.messageNodes
            .drop(anchorIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            id = Uuid.random(),
                            parts = message.parts.map { part -> part.copyWithForkedFileUrl() },
                        )
                    },
                )
            }
        val existingTitles = conversationRepo.getConversationTitlesOfAssistant(current.assistantId)
        val newConversation = createCompressedConversation(
            source = current,
            messageNodes = listOf(checkpointNode) + copiedTailNodes,
            existingTitles = existingTitles,
        )
        saveConversation(newConversation.id, newConversation)
        memoryConsolidationScheduler.onConversationForked(newConversation)
        return newConversation
    }

    private suspend fun loadConversationReadOnly(conversationId: Uuid): Conversation? {
        sessions[conversationId]?.takeIf { it.isInitialized }?.state?.value?.let { return it }
        return conversationRepo.getConversationById(conversationId)
    }

    /** 列出当前会话的全部压缩/清空检查点记录（旧在前），并一并返回当前是否允许执行「清空上下文」。 */
    suspend fun contextRecordsSnapshot(conversationId: Uuid): Pair<List<ContextCheckpointRecord>, Boolean> {
        val conversation = loadConversationReadOnly(conversationId)
            ?: throw NotFoundException("Conversation not found")
        return listContextRecords(conversation.messageNodes) to canClearContext(conversation.messageNodes)
    }

    /** 列出当前会话的全部压缩/清空检查点记录（旧在前）。 */
    suspend fun contextRecords(conversationId: Uuid): List<ContextCheckpointRecord> =
        contextRecordsSnapshot(conversationId).first

    /** 追加一条空摘要检查点（「清空上下文」），仅截断发往模型的历史，保留全部消息记录。 */
    suspend fun clearContext(conversationId: Uuid): ContextCheckpointRecord =
        withConversationSession(conversationId) { session ->
            initializeSession(session)
            val current = session.state.value
            if (current.cliSession != null) {
                throw CompressRejectedException(
                    CompressErrorCodes.CLI_CONVERSATION,
                    context.getString(R.string.cli_compress_unsupported),
                )
            }
            synchronized(session) {
                requireNotAlreadyCompressing(session)
                requireIdle(session, conversationId)
            }
            if (!canClearContext(current.messageNodes)) {
                throw CompressRejectedException(
                    CompressErrorCodes.NOTHING_TO_COMPRESS,
                    context.getString(R.string.chat_page_compress_nothing_to_compress),
                )
            }
            session.withPersistence {
                synchronized(session) {
                    requireNotAlreadyCompressing(session)
                    requireIdle(session, conversationId)
                }
                val latest = session.state.value
                if (!canClearContext(latest.messageNodes)) {
                    throw CompressRejectedException(
                        CompressErrorCodes.NOTHING_TO_COMPRESS,
                        context.getString(R.string.chat_page_compress_nothing_to_compress),
                    )
                }
                val prevCpIdx = latest.messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
                val prevCpNode = latest.messageNodes.getOrNull(prevCpIdx)
                val coveredNodes = latest.messageNodes.drop(prevCpIdx + 1)
                    .filterNot { it.currentMessage.isContextCheckpoint }
                val meta = ContextCheckpointMetadata(
                    kind = ContextCheckpointKind.CLEAR,
                    rangeStartNodeId = coveredNodes.firstOrNull()?.id,
                    rangeEndNodeId = coveredNodes.lastOrNull()?.id,
                    summarizedCount = coveredNodes.size,
                    supersededCheckpointNodeId = prevCpNode?.id,
                )
                val cleared = latest.appendClearContextCheckpoint(meta).copy(chatSuggestions = emptyList())
                val updated = session.replaceConversation(cleared)
                session.compressSuggestion.value = null
                session.autoCompressHistoryKey = null
                persistConversationSnapshot(updated)
                revalidateCacheWarm(conversationId)
                listContextRecords(updated.messageNodes).last()
            }
        }

    /** 撤销指定检查点；[cascadeLater] 为 true 时同时撤销该检查点之后的所有检查点。 */
    suspend fun revokeCheckpoints(
        conversationId: Uuid,
        checkpointNodeId: Uuid,
        cascadeLater: Boolean,
    ): List<Uuid> = withConversationSession(conversationId) { session ->
        initializeSession(session)
        synchronized(session) {
            requireNotAlreadyCompressing(session)
            requireIdle(session, conversationId)
        }
        session.withPersistence {
            synchronized(session) {
                requireNotAlreadyCompressing(session)
                requireIdle(session, conversationId)
            }
            val latest = session.state.value
            val (nextNodes, removedIds) = revokeCheckpointNodes(
                nodes = latest.messageNodes,
                checkpointNodeId = checkpointNodeId,
                cascadeLater = cascadeLater,
            ) ?: throw NotFoundException("Context checkpoint not found")
            val updated = session.replaceConversation(
                latest.copy(messageNodes = nextNodes, chatSuggestions = emptyList())
            )
            persistConversationSnapshot(updated)
            revalidateCacheWarm(conversationId)
            removedIds
        }
    }

    /** 计算当前会话（及可选草稿输入）的上下文分项构成。预演酒馆回合时严格只读（`dryRun = true`）。 */
    suspend fun contextUsageBreakdown(
        conversationId: Uuid,
        draftParts: List<UIMessagePart> = emptyList(),
    ): ContextUsageBreakdown = withContext(Dispatchers.Default) {
        val conversation = loadConversationReadOnly(conversationId)
            ?: throw NotFoundException("Conversation not found")
        val settings = settingsStore.settingsFlow.first()
        val conversationAssistant = settings.getAssistantById(conversation.assistantId)
        val assistant = conversationAssistant ?: settings.getCurrentAssistant().copy(workspaceId = null)
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        val activeMessages = conversation.currentMessages.limitContext(assistant.contextMessageLimit)
        val memories = memoriesFor(assistant)
        val memoryPrompt = if (assistant.enableMemory) {
            buildMemoryPrompt(memories, settings.toolOverrides)
        } else {
            ""
        }
        val memoryTokens = if (memoryPrompt.isNotBlank()) TokenCounter.count(memoryPrompt) else 0

        var systemPromptTokens = 0
        var lorebookAndTavernTokens = 0
        var activeLorebookCount = 0
        var toolsLocalTokens = 0
        var toolsMcpTokens = 0
        var toolsSkillsTokens = 0
        var toolsWorkspaceTokens = 0
        var toolsLocalCount = 0
        var toolsMcpCount = 0
        var toolsSkillsCount = 0
        var toolsWorkspaceCount = 0

        if (model != null) {
            val workspace = conversationAssistant?.workspaceId?.toString()?.let { workspaceRepository.getById(it) }
            val snapshot = runCatching {
                chatToolFactory.createSnapshot(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = conversation.workspaceCwd,
                    workspace = workspace,
                    conversationId = conversation.id,
                    workspaceCwdScoped = conversation.workspaceCwdScoped,
                )
            }.getOrNull()

            if (snapshot != null) {
                snapshot.tools.forEach { tool ->
                    val tokens = estimateToolTokens(tool, model, activeMessages)
                    when (snapshot.toolCategories[tool.name] ?: ToolCategory.LOCAL) {
                        ToolCategory.LOCAL -> {
                            toolsLocalCount++
                            toolsLocalTokens += tokens
                        }
                        ToolCategory.MCP -> {
                            toolsMcpCount++
                            toolsMcpTokens += tokens
                        }
                        ToolCategory.SKILLS -> {
                            toolsSkillsCount++
                            toolsSkillsTokens += tokens
                        }
                        ToolCategory.WORKSPACE -> {
                            toolsWorkspaceCount++
                            toolsWorkspaceTokens += tokens
                        }
                    }
                }
                parallelToolCallsPrompt(settings.toolExecution, snapshot.tools)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { systemPromptTokens += TokenCounter.count(it) }
            }

            val turn = prepareTurn(
                conversation = conversation,
                assistant = assistant,
                model = model,
                messages = activeMessages,
                generationType = GenerationType.NORMAL,
                dryRun = true,
            )
            activeLorebookCount = turn.activations.size
            val assembled = turn.inspect(activeMessages, rikkihubContext = "")
            assembled.forEach { item ->
                if (item !is AssembledItem.Generated) return@forEach
                if (item.identifier == PromptMarkers.RIKKIHUB_CONTEXT || item.content.isBlank()) return@forEach
                val itemTokens = TokenCounter.count(item.content) + 4
                val isTavernOrLore = item.identifier == PromptMarkers.WORLD_INFO_BEFORE ||
                    item.identifier == PromptMarkers.WORLD_INFO_AFTER ||
                    item.identifier == PromptMarkers.CHAR_DESCRIPTION ||
                    item.identifier == PromptMarkers.CHAR_PERSONALITY ||
                    item.identifier == PromptMarkers.SCENARIO ||
                    item.identifier == PromptMarkers.PERSONA_DESCRIPTION ||
                    item.identifier == PromptMarkers.DIALOGUE_EXAMPLES ||
                    item.identifier == "PERSONA_DESCRIPTION" ||
                    item.identifier == "DEPTH_PROMPT" ||
                    item.identifier == "authorsNote" ||
                    item.identifier == "example" ||
                    item.identifier == "summary" ||
                    item.identifier == "vectorsMemory" ||
                    item.identifier == "vectorsDataBank" ||
                    item.identifier == "smartContext" ||
                    item.identifier.startsWith("customDepthWI_") ||
                    item.identifier in turn.libraryIdentifiers
                if (isTavernOrLore) {
                    lorebookAndTavernTokens += itemTokens
                } else {
                    systemPromptTokens += itemTokens
                }
            }

            val reminderMessages = runCatching {
                workspaceReminderTransformer.transform(
                    TransformerContext(
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                        workspaceCwd = conversation.workspaceCwd,
                        harnessMinimalWorkspace = workspace?.harnessMinimal == true,
                        exposeUpload = workspace?.exposeUpload != false,
                        workspaceCapabilities = snapshot?.workspaceCapabilities,
                        turn = turn,
                    ),
                    emptyList(),
                )
            }.getOrDefault(emptyList())
            systemPromptTokens += reminderMessages.sumOf { msg ->
                val text = msg.toRawText()
                if (text.isBlank()) 0 else TokenCounter.count(text)
            }
        } else {
            val baseSystem = assistant.legacyMainPrompt(conversation.customSystemPrompt)
            if (baseSystem.isNotBlank()) {
                systemPromptTokens += TokenCounter.count(baseSystem) + 4
            }
        }

        val checkpointMessage = activeMessages.firstOrNull()?.takeIf { it.isContextCheckpoint }
        val regularMessages = if (checkpointMessage != null) activeMessages.drop(1) else activeMessages
        val historyCheckpointTokens = checkpointMessage?.historyBodyEstimatedTokens() ?: 0
        val historyMessagesTokens = regularMessages.sumOf { it.historyBodyEstimatedTokens() }

        var attachmentsTokens = 0
        var attachmentCount = 0
        val draftTokens: Int
        withContext(Dispatchers.IO) {
            val docReader: (UIMessagePart.Document) -> String = DocumentAsPromptTransformer::readDocumentContent
            activeMessages.forEach { message ->
                message.parts.forEach { part ->
                    val partTokens = estimateAttachmentPartTokens(part, docReader)
                    if (partTokens > 0) {
                        attachmentCount++
                        attachmentsTokens += partTokens
                    }
                }
            }
            draftTokens = estimateDraftPartsTokens(draftParts, docReader)
        }

        val estimatedTotalTokens = systemPromptTokens +
            memoryTokens +
            lorebookAndTavernTokens +
            toolsLocalTokens +
            toolsMcpTokens +
            toolsSkillsTokens +
            toolsWorkspaceTokens +
            historyCheckpointTokens +
            historyMessagesTokens +
            attachmentsTokens +
            draftTokens

        val reportedContextTokens = conversation.lastContextTokens()
        val totalExact = reportedContextTokens != null && draftTokens == 0
        val totalTokens = when {
            totalExact -> reportedContextTokens!!
            reportedContextTokens != null -> reportedContextTokens + draftTokens
            else -> estimatedTotalTokens
        }

        ContextUsageBreakdown(
            totalTokens = totalTokens,
            totalExact = totalExact,
            reportedContextTokens = reportedContextTokens,
            estimatedTotalTokens = estimatedTotalTokens,
            contextLength = model?.contextLength,
            inputBudgetTokens = model?.let { compressionInputBudget(it, assistant.maxTokens) },
            systemPromptTokens = systemPromptTokens,
            memoryTokens = memoryTokens,
            lorebookAndTavernTokens = lorebookAndTavernTokens,
            toolsLocalTokens = toolsLocalTokens,
            toolsMcpTokens = toolsMcpTokens,
            toolsSkillsTokens = toolsSkillsTokens,
            toolsWorkspaceTokens = toolsWorkspaceTokens,
            historyCheckpointTokens = historyCheckpointTokens,
            historyMessagesTokens = historyMessagesTokens,
            attachmentsTokens = attachmentsTokens,
            draftTokens = draftTokens,
            toolsLocalCount = toolsLocalCount,
            toolsMcpCount = toolsMcpCount,
            toolsSkillsCount = toolsSkillsCount,
            toolsWorkspaceCount = toolsWorkspaceCount,
            historyMessageCount = regularMessages.size,
            hasActiveCheckpoint = checkpointMessage != null,
            attachmentCount = attachmentCount,
            activeLorebookCount = activeLorebookCount,
            memoryFileCount = memories.size,
        )
    }

    private fun requireNotCompressing(session: ConversationSession) {
        if (session.isCompressing) {
            throw me.rerere.rikkihub.data.mutation.ConversationBusyException(
                session.id.toString(), listOf(BUSY_REASON_COMPRESSING),
            )
        }
    }

    // ---- 对话状态更新 ----

    /**
     * [checkFileRefs] 为 true 时对比新旧会话的附件引用并清理被移除的本地文件。
     * 只在删消息/删节点/分支截断/工具输出覆盖等结构性更新时需要；
     * 流式 chunk、翻译等不减少附件引用的热路径应传 false 跳过 O(files) 对比。
     */
    private fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        checkFileRefs: Boolean = true,
    ) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        session.replaceConversation(conversation) { updated, old ->
            if (checkFileRefs) checkFilesDelete(updated, old)
        }
    }

    /**
     * 收藏表变化后同步已加载会话的节点星标。未加载的会话不为此加载：加载时星标本来就从收藏表推出，
     * 而对未初始化会话写内存态会把它标成已初始化，让真正的加载被跳过。
     */
    suspend fun syncLoadedNodeFavorite(conversationId: Uuid, nodeId: Uuid, isFavorite: Boolean) {
        val session = sessions[conversationId]?.takeIf { it.isInitialized } ?: return
        session.withPersistence {
            session.updateState { conversation ->
                conversation.copy(messageNodes = conversation.messageNodes.map { node ->
                    if (node.id == nodeId) node.copy(isFavorite = isFavorite) else node
                })
            }
        }
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        getOrCreateSession(conversationId).updateState { old ->
            update(old).also { checkFilesDelete(it, old) }
        }
        revalidateCacheWarm(conversationId)
    }

    private suspend fun updateSessionMetadata(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) = withConversationSession(conversationId) { session ->
        // A history metadata edit must NOT switch the currently selected assistant.
        initializeSession(session)
        session.updateMetadata(update) { updated ->
            revalidateCacheWarm(conversationId)
            persist(updated)
            // Single-column writes advance the durable revision too.
            adoptDurableVersion(conversationId)
        }
    }

    /** 会话自己的压缩设置（档位、模板）；null 或全空 = 全部跟随全局。单列写，不受生成 / 压缩占用影响。 */
    suspend fun updateConversationCompression(
        conversationId: Uuid,
        override: me.rerere.rikkihub.data.ai.ConversationCompressionOverride?,
    ) = updateSessionMetadata(
        conversationId,
        update = { it.copy(compression = override?.takeUnless { value -> value.isEmpty }) },
        persist = { conversationRepo.updateCompression(conversationId, it.compression) },
    )

    suspend fun toggleConversationPin(conversationId: Uuid) = updateSessionMetadata(
        conversationId,
        update = { it.copy(isPinned = !it.isPinned) },
        persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
    )

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        val settings = settingsStore.settingsFlow.first()
        val newWs = settings.getAssistantById(assistantId)?.workspaceId
        withConversationSession(conversationId) { session ->
            initializeSession(session)
            val oldWs = settings.getAssistantById(session.state.value.assistantId)?.workspaceId
            val workspaceChanged = oldWs != newWs
            if (workspaceChanged) {
                session.cancelJobs().forEach { it.join() }
                acpSessionManager?.closeConversation(conversationId)
            }
            session.updateMetadata(
                update = { current ->
                    applyMoveConversationToAssistant(
                        current = current,
                        targetAssistantId = assistantId,
                        workspaceChanged = workspaceChanged,
                        newWorkspaceId = newWs,
                    )
                },
                persist = { updated ->
                    revalidateCacheWarm(conversationId)
                    conversationRepo.updateConversationAssistantAndCli(
                        conversationId = conversationId,
                        assistantId = updated.assistantId,
                        resetWorkspaceFields = workspaceChanged,
                        workspaceCwd = updated.workspaceCwd,
                        workspaceCwdScoped = updated.workspaceCwdScoped,
                        cliSession = updated.cliSession,
                    )
                    adoptDurableVersion(conversationId)
                },
            )
        }
    }

    /**
     * 还没落库的新会话跟随抽屉 / 侧边栏里选中的文件夹，首次落库时一并写入；
     * 已经落库的会话不受影响，移动走 [moveConversationToFolder]。
     * 判断和修改都在持久化锁内，不会和首次落库交错。
     */
    suspend fun updateDraftConversationFolder(conversationId: Uuid, folderId: Uuid?) {
        val folder = folderId?.let { folderRepository.getFolderById(it) }
        withConversationSession(conversationId) { session ->
            initializeSession(session)
            session.withPersistence {
                if (conversationRepo.existsConversationById(conversationId)) return@withPersistence
                session.updateState { latest ->
                    // 文件夹属于助手：已被删除或不属于会话的助手时不归入
                    val target = folder?.takeIf { it.assistantId == latest.assistantId }?.id
                    if (latest.folderId == target) latest else latest.copy(folderId = target)
                }
            }
        }
    }

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) = updateSessionMetadata(
        conversationId,
        update = { it.copy(folderId = folderId) },
        persist = { conversationRepo.updateConversationFolderId(conversationId, it.folderId) },
    )

    suspend fun updateConversationInjections(conversationId: Uuid, modeInjectionIds: Set<Uuid>, lorebookIds: Set<Uuid>) =
        updateSessionMetadata(
            conversationId,
            update = { it.copy(modeInjectionIds = modeInjectionIds, lorebookIds = lorebookIds) },
            persist = { conversationRepo.updateInjectionIds(conversationId, it.modeInjectionIds, it.lorebookIds) },
        )

    /**
     * 改会话里的酒馆状态（作者注释、聊天变量）：先把会话载入内存，再在**最新**状态上做变换并只写这一列，
     * 消息节点不动。App 的作者注释面板走会话页自己的保存，网页端走这里（会话没人开着时内存状态会被回收，只改内存就丢了）。
     */
    suspend fun updateTavernMetadata(
        conversationId: Uuid,
        transform: (kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject,
    ) = updateSessionMetadata(
        conversationId,
        update = { it.copy(tavernMetadata = transform(it.tavernMetadata)) },
        persist = { conversationRepo.updateTavernMetadata(conversationId, it.tavernMetadata) },
    )

    /** Web merge-patch: only touched columns are saved; streamed nodes are never rewritten. */
    suspend fun updateConversationMetadata(
        conversationId: Uuid,
        patch: me.rerere.rikkihub.web.dto.ConversationMetadataPatch,
    ) = updateSessionMetadata(
        conversationId,
        update = {
            it.copy(
                title = if (patch.patchedTitle) patch.title.orEmpty() else it.title,
                customSystemPrompt = if (patch.patchedCustomSystemPrompt) patch.customSystemPrompt else it.customSystemPrompt,
                workspaceCwd = if (patch.patchedWorkspaceCwd) patch.workspaceCwd else it.workspaceCwd,
                workspaceCwdScoped = patch.workspaceCwdScoped ?: it.workspaceCwdScoped,
            )
        },
        persist = {
            if (patch.patchedTitle) conversationRepo.updateTitle(conversationId, it.title)
            if (patch.patchedCustomSystemPrompt) conversationRepo.updateCustomSystemPrompt(conversationId, it.customSystemPrompt)
            if (patch.patchedWorkspaceCwd) conversationRepo.updateWorkspaceCwd(conversationId, it.workspaceCwd)
            if (patch.workspaceCwdScoped != null) conversationRepo.updateWorkspaceCwdScoped(conversationId, it.workspaceCwdScoped)
        },
    )

    /**
     * Typed ordinary-conversation command channel. Management writes enter
     * conversations ONLY here: full initialization → session persistence lock → BUSY and
     * version check → target built from the latest in-memory state → one Room transaction
     * (row CAS + nodes + FTS + retention + receipt) → memory adopts the durable result.
     *
     * A replay of an already committed [operationId] returns the durable version without
     * re-applying. Reserved/creator assistants are never valid targets.
     */
    suspend fun applyConversationCommand(
        conversationId: Uuid,
        expected: ConversationVersion?,
        operationId: String,
        command: ConversationMutationCommand,
        hostIds: ConversationHostIds,
    ): ConversationVersion {
        require(operationId.isNotBlank() && operationId.length <= 512)
        conversationRepo.operationReceipt(operationId)?.let {
            return conversationRepo.currentVersion(conversationId)
                ?: throw MutationConflictException("conversation $conversationId disappeared after commit")
        }
        val settings = settingsStore.settingsSnapshot()
        val registry = me.rerere.rikkihub.data.creator.definition.AssistantRegistry()
        val ordinary = settings.assistants.map { it.id }
            .filterNot { me.rerere.rikkihub.data.creator.definition.AssistantRegistry.isReservedAssistantId(it) }
            .toSet()
        suspend fun context(targetAssistant: Uuid?, folder: String?): ConversationCommandContext {
            val folderIds = folder?.let(Uuid::parse)?.let { id ->
                folderRepository.getFolderById(id)?.takeIf { targetAssistant == null || it.assistantId == targetAssistant }
                    ?.let { setOf(it.id) }
            }.orEmpty()
            return ConversationCommandContext(ordinary, folderIds,
                settings.promptLibraryIds(), settings.lorebooks.map { it.id }.toSet(),
                characterIds = characterRepository?.summaries()?.map { it.id }?.toSet().orEmpty())
        }
        return when (command) {
            is ConversationMutationCommand.CreateOrdinaryConversation -> {
                require(expected == null)
                require(hostIds.conversationId == conversationId.toString())
                if (conversationRepo.currentVersion(conversationId) != null)
                    throw MutationConflictException("conversation $conversationId already exists")
                val created = ConversationCommandApplier.create(command, hostIds,
                    context(Uuid.parse(command.assistantId), command.folderId))
                registry.requireOrdinaryAssistantId(created.assistantId)
                withConversationSession(conversationId) { session ->
                    session.withPersistence {
                        val revision = conversationRepo.persistConversationCas(created, operationId)
                        val version = ConversationVersion(0, created.generation, revision)
                        session.replaceConversation(created.copy(revision = revision))
                        version
                    }
                }
            }
            ConversationMutationCommand.SoftDelete -> withConversationSession(conversationId) { session ->
                initializeSession(session)
                requireIdle(session, conversationId)
                session.withPersistence {
                    val current = session.state.value
                    requireExpected(current, expected)
                    val files = current.files.map { it.toString() }
                    val byPath = files.mapNotNull { url ->
                        me.rerere.rikkihub.data.files.extractUploadRelativePath(url)?.let { it to url }
                    }.toMap()
                    val deleted = conversationRepo.softDeleteConversation(conversationId,
                        ConversationVersion(0, current.generation, current.revision), operationId,
                        retainRelativePaths = byPath.keys.toList(), retainSourceUrls = byPath)
                    ConversationVersion(0, deleted.generation, deleted.revision)
                }
            }.also { removeSessionAfterDelete(conversationId) }
            ConversationMutationCommand.Restore -> {
                val durable = conversationRepo.currentVersion(conversationId)
                    ?: throw MutationConflictException("conversation $conversationId not found")
                if (expected != null && durable != expected)
                    throw MutationConflictException("conversation $conversationId restore baseline changed")
                val restored = conversationRepo.restoreConversation(conversationId, durable.generation, operationId)
                sessions[conversationId]?.let { session ->
                    session.withPersistence { session.replaceConversation(restored) }
                }
                ConversationVersion(0, restored.generation, restored.revision)
            }
            else -> withConversationSession(conversationId) { session ->
                initializeSession(session)
                if (conversationRepo.isDirtyConversation(conversationId))
                    throw me.rerere.rikkihub.data.mutation.ConversationDirtyException(conversationId.toString())
                requireIdle(session, conversationId)
                session.withPersistence {
                    val current = session.state.value
                    requireExpected(current, expected)
                    registry.requireOrdinaryAssistantId(current.assistantId)
                    val target = (command as? ConversationMutationCommand.PatchMetadata)?.moveToAssistantId?.let(Uuid::parse)
                        ?: current.assistantId
                    val folder = (command as? ConversationMutationCommand.PatchMetadata)?.folderId
                    val result = ConversationCommandApplier.apply(current, command, hostIds, context(target, folder))
                    val retained = conversationRepo.retentionForReleasedFiles(conversationId,
                        result.releasedFiles, "management-command")
                    val revision = conversationRepo.persistConversationCas(result.conversation, operationId, retained)
                    val committed = result.conversation.copy(revision = revision)
                    session.updateState { committed }
                    revalidateCacheWarm(conversationId)
                    ConversationVersion(0, committed.generation, revision)
                }
            }
        }
    }

    /** Management planning view: a loaded session that is not idle (null = idle or not loaded). */
    fun managementBusyReason(conversationId: Uuid): String? {
        val session = sessions[conversationId] ?: return null
        return when {
            session.isCompressing -> BUSY_REASON_COMPRESSING
            session.isGenerating -> "GENERATING"
            session.hasPendingToolApprovals() -> "PENDING_APPROVAL"
            session.submittingMessage != null -> "SUBMITTING"
            session.messageQueue.state.value.messages.isNotEmpty() -> "QUEUED_MESSAGES"
            conversationRepo.isDirtyConversation(conversationId) -> "PARTIALLY_LOADED"
            else -> null
        }
    }

    private fun requireIdle(session: ConversationSession, conversationId: Uuid, allowQueued: Boolean = false) {
        val reason = when {
            session.isCompressing -> BUSY_REASON_COMPRESSING
            session.isGenerating -> "GENERATING"
            session.hasPendingToolApprovals() -> "PENDING_APPROVAL"
            session.submittingMessage != null -> "SUBMITTING"
            !allowQueued && session.messageQueue.state.value.messages.isNotEmpty() -> "QUEUED_MESSAGES"
            else -> null
        }
        if (reason != null) throw ConversationBusyException(conversationId.toString(), listOf(reason))
        if (conversationRepo.isDirtyConversation(conversationId))
            throw me.rerere.rikkihub.data.mutation.ConversationDirtyException(conversationId.toString())
    }

    private fun requireExpected(current: Conversation, expected: ConversationVersion?) {
        requireNotNull(expected) { "Conversation commands require an expected version" }
        if (current.revision != expected.revision || current.generation != expected.generation) {
            throw MutationConflictException("conversation ${current.id} changed since the change was planned")
        }
    }

    private fun removeSessionAfterDelete(conversationId: Uuid) {
        sessions[conversationId]?.let { session ->
            session.cancelJobs()
            if (!session.isInUse && sessions.remove(conversationId, session)) {
                session.cleanup()
                _sessionsVersion.update { it + 1 }
            }
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    fun hasActiveGenerationOrCompression(): Boolean {
        return sessions.values.any { it.isGenerating || it.isCompressing }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { moveConversationToFolder(it.id, null) }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val session = sessions[newConversation.id]
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        require(conversation.id == conversationId)
        // 引入模型快照之前的旧消息在会话下次保存时补齐（模型还在的才补得上）
        val withSnapshots = conversation.fillModelSnapshots(settingsStore.settingsFlow.value)
        withConversationSession(conversationId) { session ->
            session.withPersistence {
                val updated = session.replaceConversation(withSnapshots) { new, old -> checkFilesDelete(new, old) }
                persistConversationSnapshot(updated)
                revalidateCacheWarm(conversationId)
            }
        }
        // Still gated on generation, approval, pause and edit placeholders.
        dispatchNextQueuedMessage(conversationId)
    }

    /**
     * 协议失败回滚后的持久化路径，与 [saveConversation] 同一事务语义，但禁用
     * 附件引用 GC ([updateConversation] 的 checkFileRefs=false)。
     *
     * 为什么不能走带 GC 的保存：回滚把尾消息恢复为 attempt 开始前的快照，新旧
     * 状态差集中可能含半截工具 input 引用的 file:// 附件 —— 文件还在磁盘上且
     * 可能是用户上传的原件，按“旧减新”差集删除会误删文件。回滚本身已由
     * 生成方保证不新增真实附件引用，因此这里只做内存同步 + 行/节点 upsert。
     *
     * 不重新调度队列：协议失败的队列暂停与错误上报由 handleMessageComplete
     * 的 onFailure 负责；回滚持久化发生在失败任务体内，dispatch 检查即便执行
     * 也必然因当前任务仍在槽位而 no-op，这里显式不调以保持语义单一。
     */
    private suspend fun persistProtocolRollbackState(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        withConversationSession(conversationId) { session ->
            session.withPersistence {
                // No attachment GC or queue dispatch on protocol rollback.
                persistConversationSnapshot(session.replaceConversation(conversation))
            }
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { message.displayTextOf(it) }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        // 流式翻译更新只改内存态，完成后由调用方 saveConversation 一次性落库
        updateConversationNodeMessage(conversationId, messageId) { msg ->
            msg.copy(translation = translationText)
        }
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        editedParts: List<UIMessagePart>,
        revokeAffectedCheckpoints: Boolean = false,
    ) {
        if (editedParts.isEmptyInputMessage()) return
        val parts = collectAttachments(editedParts)

        val currentConversation = getConversationFlow(conversationId).value
        val affectedNodeIds = if (revokeAffectedCheckpoints) {
            affectedCheckpointsForMessage(currentConversation.messageNodes, messageId).mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val isCli = currentConversation.cliSession != null
        val processedParts = importAttachmentsIntoScope(
            if (isCli) parts else tavernInput.processEdit(parts, assistant, settings, activeCharacterOf(currentConversation, settings)),
            currentConversation, assistant,
        )
        val (updatedNodes, edited) = applyEditedMessageNodes(
            nodes = currentConversation.messageNodes,
            messageId = messageId,
            processedParts = processedParts,
            isCli = isCli,
        )

        if (!edited) return

        val finalNodes = if (affectedNodeIds.isEmpty()) updatedNodes else updatedNodes.filterNot { it.id in affectedNodeIds }
        saveConversation(conversationId, currentConversation.copy(messageNodes = finalNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val existingTitles = conversationRepo.getConversationTitlesOfAssistant(currentConversation.assistantId)
        val forkConversation = createForkConversation(currentConversation, copiedNodes, existingTitles)

        saveConversation(forkConversation.id, forkConversation)
        // 复制过来的轮次由原对话负责整理
        memoryConsolidationScheduler.onConversationForked(forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val session = getOrCreateSession(conversationId)
        val currentConversation = session.state.value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        // 只改 select_index 单列：节点内容不变，不需要整表删插重建
        conversationRepo.updateNodeSelectIndex(conversationId, nodeId, selectIndex)
        session.updateState { conversation ->
            conversation.copy(
                messageNodes = conversation.messageNodes.map { node ->
                    if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node
                }
            )
        }
        // claudelife：切换分支 → 旧基线不再适用即停止保活。
        revalidateCacheWarm(conversationId)

        // 惰性复核：切到的分支可能带着 gate 事件后未修的 pending。
        scheduleWorkspacePendingRepair(conversationId, "message node selected")

        // 切换分支可能解除工具审批阻塞，重新检查队列。
        dispatchNextQueuedMessage(conversationId)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
        revokeAffectedCheckpoints: Boolean = false,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val affectedNodeIds = if (revokeAffectedCheckpoints) {
            affectedCheckpointsForMessage(currentConversation.messageNodes, messageId).mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        val finalConversation = if (affectedNodeIds.isEmpty()) {
            updatedConversation
        } else {
            updatedConversation.copy(
                messageNodes = updatedConversation.messageNodes.filterNot { it.id in affectedNodeIds },
            )
        }
        saveConversation(conversationId, finalConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
        revokeAffectedCheckpoints: Boolean = false,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false, revokeAffectedCheckpoints = revokeAffectedCheckpoints)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        updateConversationNodeMessage(conversationId, messageId) { msg ->
            msg.copy(translation = null)
        }
    }

    /** 只更新包含 [messageId] 的那个节点，逐 chunk 调用不跑附件引用检查。 */
    private fun updateConversationNodeMessage(
        conversationId: Uuid,
        messageId: Uuid,
        transform: (UIMessage) -> UIMessage,
    ) {
        val session = getOrCreateSession(conversationId)
        session.updateState { conversation ->
            conversation.copy(
                messageNodes = conversation.messageNodes.map { node ->
                    if (node.messages.any { it.id == messageId }) {
                        node.copy(
                            messages = node.messages.map { msg ->
                                if (msg.id == messageId) transform(msg) else msg
                            }
                        )
                    } else {
                        node
                    }
                }
            )
        }
    }

    // ---- 去八股 ----

    /** 正在等 AI 精修结果的消息。 */
    val qubaguRefining: StateFlow<Set<Uuid>> get() = _qubaguRefining
    private val _qubaguRefining = MutableStateFlow<Set<Uuid>>(emptySet())

    /**
     * 改动清单里的应用 / 撤销：[part] 是文字段在消息 parts 里的下标，[edit] 是那一段记录里的第几条；
     * 两个都为 null = 这条消息的全部改动。
     */
    suspend fun setQubaguApplied(conversationId: Uuid, messageId: Uuid, part: Int?, edit: Int?, applied: Boolean) {
        updateConversationNodeMessage(conversationId, messageId) { message ->
            message.copy(parts = message.parts.mapIndexed { index, it ->
                if (it !is UIMessagePart.Text || (part != null && part != index)) it
                else it.withQubagu { _, record -> if (edit == null) record.withAllApplied(applied) else record.withApplied(edit, applied) }
            })
        }
        saveConversation(conversationId, getConversationFlow(conversationId).value)
    }

    /**
     * 手动给一条回复去八股（助手没开开关、或是以前的消息也可以）：按现在的规则重新找一遍，
     * 已有的记录先还原成原文。返回找到了几处。
     */
    suspend fun cleanMessageWithQubagu(conversationId: Uuid, messageId: Uuid): Int {
        val conversation = getConversationFlow(conversationId).value
        val messages = conversation.currentMessages
        val position = messages.indexOfFirst { it.id == messageId }
        val message = messages.getOrNull(position)?.takeIf { it.role == MessageRole.ASSISTANT } ?: return 0
        val config = settingsStore.settingsFlow.first().qubagu
        val recent = messages.take(position).filter { it.role == MessageRole.ASSISTANT }
            .takeLast(QubaguOutputTransformer.RECENT_REPLIES).joinToString("\n") { it.toText() }
        var found = 0
        val parts = message.parts.map { part ->
            if (part !is UIMessagePart.Text || part.text.isBlank()) return@map part
            val original = QubaguRecord.of(part)?.original(part.text) ?: part.text
            val record = qubagu.clean(original.stripMessageTitle(), config, recent)
            found += record.edits.size
            part.withQubaguRecord(original, record)
        }
        updateConversationNodeMessage(conversationId, messageId) { it.copy(parts = parts) }
        saveConversation(conversationId, getConversationFlow(conversationId).value)
        return found
    }

    /**
     * 让模型重写这条回复里命中套话的句子，结果并进改动记录。同一条消息同时只跑一次（重复调用直接返回）。
     * 没有可精修的句子、没有模型、模型没给出能用的改写时抛 [QubaguRefineException]。
     */
    suspend fun refineMessageWithQubagu(conversationId: Uuid, messageId: Uuid) {
        var claimed = false
        _qubaguRefining.update { current -> (current + messageId).also { claimed = messageId !in current } }
        if (!claimed) return
        try {
            val settings = settingsStore.settingsFlow.first()
            val conversation = getConversationFlow(conversationId).value
            val message = conversation.currentMessages.firstOrNull { it.id == messageId }
                ?: throw QubaguRefineException(QubaguRefineException.Reason.NOTHING_TO_REFINE)
            val assistant = settings.getAssistantById(conversation.assistantId)
            val fallback = settings.findModelById(assistant?.chatModelId ?: settings.chatModelId)
            var failure: QubaguRefineException? = null
            val refined = message.parts.mapIndexed { index, part ->
                val text = part as? UIMessagePart.Text ?: return@mapIndexed null
                val record = QubaguRecord.of(text) ?: return@mapIndexed null
                val original = record.original(text.text) ?: return@mapIndexed null
                try {
                    index to qubaguRefiner.refine(original, record, settings, fallback, assistant?.id, conversationId)
                } catch (e: QubaguRefineException) {
                    failure = e
                    null
                }
            }.filterNotNull().toMap()
            if (refined.isEmpty()) throw failure ?: QubaguRefineException(QubaguRefineException.Reason.NOTHING_TO_REFINE)
            // 等模型的这段时间里消息可能被改过：只在没变的段上落结果
            updateConversationNodeMessage(conversationId, messageId) { current ->
                current.copy(parts = current.parts.mapIndexed { index, part ->
                    val next = refined[index]
                    if (next == null || part !is UIMessagePart.Text || part != message.parts.getOrNull(index)) part
                    else part.withQubagu { _, _ -> next }
                })
            }
            saveConversation(conversationId, getConversationFlow(conversationId).value)
        } finally {
            _qubaguRefining.update { it - messageId }
        }
    }

    /** App 改动清单里的「AI 精修」：后台跑，出错记到会话的错误列表里。 */
    fun launchQubaguRefine(conversationId: Uuid, messageId: Uuid) {
        appScope.launch(Dispatchers.IO) {
            try {
                refineMessageWithQubagu(conversationId, messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val shown = when ((e as? QubaguRefineException)?.reason) {
                    QubaguRefineException.Reason.NO_MODEL -> IllegalStateException(context.getString(R.string.qubagu_refine_no_model))
                    QubaguRefineException.Reason.NOTHING_TO_REFINE -> IllegalStateException(context.getString(R.string.qubagu_refine_nothing))
                    QubaguRefineException.Reason.NO_REWRITES -> IllegalStateException(context.getString(R.string.qubagu_refine_no_rewrites))
                    null -> e
                }
                addError(shown, conversationId, title = context.getString(R.string.qubagu_refine_error_title))
            }
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        finishInterruptedPendingTools(conversationId)
    }
}

/**
 * 当前分支最后一条消息是一批还在等审批（或审批完待恢复）的工具调用时返回它的 id。
 *
 * 同一批里有调用需要审批时，免审批的调用不先执行，审批之后整批一起执行；这期间它们
 * 未执行、状态仍是 Auto，清理「未执行且不可恢复」的调用时不能把它们当成中断残留。
 */
internal fun awaitingToolBatchMessageId(currentMessages: List<UIMessage>): Uuid? {
    val last = currentMessages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return null
    val awaiting = last.getTools().any { tool ->
        !tool.isExecuted && (tool.isPending || tool.approvalState.canResumeToolExecution())
    }
    return if (awaiting) last.id else null
}

/**
 * 取消原因链里是否含 gate 政策取消（[WorkspacePolicyCancellationException]）。
 * gate 取消在册 victim 操作时给 job 的 cancellation cause 是该类型；穿透到
 * 生成 job 的完成 cause 后仍可在 cause 链上识别（有界遍历防环）。
 */
private fun Throwable.isWorkspacePolicyCancellation(): Boolean =
    generateSequence(this) { it.cause }
        .take(8)
        .any { it is WorkspacePolicyCancellationException }

internal sealed interface PendingCliOverride {
    data class Enabled(val session: ConversationCliSession) : PendingCliOverride
    data object Disabled : PendingCliOverride
}

internal fun resolveInitialCliSession(
    hasCharacter: Boolean,
    override: PendingCliOverride?,
    assistant: Assistant,
): ConversationCliSession? {
    if (hasCharacter) return null
    return when (override) {
        is PendingCliOverride.Enabled -> override.session
        PendingCliOverride.Disabled -> null
        null -> assistant.defaultCliAgentId?.takeIf { assistant.workspaceId != null }?.let {
            ConversationCliSession(agentId = it, permissionMode = assistant.defaultCliPermissionMode)
        }
    }
}

internal fun applyMoveConversationToAssistant(
    current: Conversation,
    targetAssistantId: Uuid,
    workspaceChanged: Boolean,
    newWorkspaceId: Uuid?,
): Conversation {
    val moved = current.copy(assistantId = targetAssistantId, folderId = null)
    if (!workspaceChanged) return moved
    return moved.copy(
        workspaceCwd = null,
        workspaceCwdScoped = true,
        cliSession = if (newWorkspaceId == null) null else current.cliSession?.reset(),
    )
}

internal fun applyEditedMessageNodes(
    nodes: List<MessageNode>,
    messageId: Uuid,
    processedParts: List<UIMessagePart>,
    isCli: Boolean,
): Pair<List<MessageNode>, Boolean> {
    var edited = false
    val updated = nodes.map { node ->
        val original = node.messages.firstOrNull { it.id == messageId } ?: return@map node
        edited = true
        if (original.isContextCheckpoint) {
            // 摘要原地改写：新建分支会丢掉检查点标记，删除摘要时还会露出旧版本
            return@map node.copy(
                messages = node.messages.map { message ->
                    if (message.id == messageId) message.copy(parts = processedParts) else message
                }
            )
        }
        if (isCli) {
            // CLI 会话原地单版本覆盖，不建分支且必须换新 id 让 CliPrompts.fingerprint 感知编辑
            return@map node.copy(
                messages = listOf(
                    UIMessage(
                        id = Uuid.random(),
                        role = node.role,
                        parts = processedParts,
                        createdAt = original.createdAt,
                        modelId = original.modelId,
                        modelSnapshot = original.modelSnapshot,
                    )
                ),
                selectIndex = 0,
            )
        }
        node.copy(
            messages = node.messages + UIMessage(
                role = node.role,
                parts = processedParts,
                // 编辑的是原消息的内容，来源仍是生成它的模型
                modelId = original.modelId,
                modelSnapshot = original.modelSnapshot,
            ),
            selectIndex = node.messages.size,
        )
    }
    return updated to edited
}

internal fun cliTextBlock(text: String) = buildJsonObject {
    put("type", "text")
    put("text", text)
}

/** The user's message as ACP content: text, images when the agent takes them, other files by workspace path or bounded inline text. */
internal suspend fun cliPromptBlocks(
    message: UIMessage,
    images: Boolean,
    filesScope: String? = null,
    exposeUpload: Boolean = false,
    allowedImageDirs: List<File> = emptyList(),
    readDocumentText: suspend (UIMessagePart.Document) -> String = ::readBoundedDocumentText,
): List<JsonObject> = message.parts.flatMap { part ->
    when (part) {
        is UIMessagePart.Text -> listOfNotNull(part.text.takeIf { it.isNotBlank() }?.let(::cliTextBlock))
        is UIMessagePart.Image -> {
            val guestPath = resolveCliGuestPath(part, filesScope, exposeUpload)
            val imageBlock = if (images) {
                resolveSafeLocalFile(part.url, allowedImageDirs)
                    ?.encodeLocalImageBase64()
                    ?.getOrNull()
                    ?.let { encoded ->
                        buildJsonObject {
                            put("type", "image")
                            put("mimeType", encoded.mimeType)
                            put("data", encoded.base64)
                        }
                    }
            } else {
                null
            }
            if (imageBlock != null) {
                if (guestPath != null) listOf(imageBlock, cliTextBlock("[Image at $guestPath]")) else listOf(imageBlock)
            } else {
                val fallback = if (guestPath != null) {
                    "[Image at $guestPath]"
                } else {
                    val fileName = part.url.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
                    "[Image: $fileName]"
                }
                listOf(cliTextBlock(fallback))
            }
        }
        is UIMessagePart.Document -> {
            val guestPath = resolveCliGuestPath(part, filesScope, exposeUpload)
            if (guestPath != null) {
                listOf(cliTextBlock("[File: ${part.fileName} at $guestPath]"))
            } else {
                val text = try {
                    readDocumentText(part)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (text != null) {
                    listOf(cliTextBlock("<UploadFile name=\"${part.fileName}\">\n```\n$text\n```\n</UploadFile>"))
                } else {
                    listOf(cliTextBlock("[File: ${part.fileName}]"))
                }
            }
        }
        is UIMessagePart.Video -> resolveCliGuestPath(part, filesScope, exposeUpload)?.let { guestPath ->
            listOf(cliTextBlock("[Video: ${guestPath.substringAfterLast('/')} at $guestPath]"))
        }.orEmpty()
        is UIMessagePart.Audio -> resolveCliGuestPath(part, filesScope, exposeUpload)?.let { guestPath ->
            listOf(cliTextBlock("[Audio: ${guestPath.substringAfterLast('/')} at $guestPath]"))
        }.orEmpty()
        else -> emptyList()
    }
}
