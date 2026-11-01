package me.rerere.rikkihub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolConcurrency
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.WorkspaceToolWirePolicy
import me.rerere.rikkihub.data.ai.GenerationLoop
import me.rerere.rikkihub.data.ai.mcp.McpManager
import me.rerere.rikkihub.data.ai.tools.local.LocalTools
import me.rerere.rikkihub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.definition.CreatorToolNamespace
import me.rerere.rikkihub.data.creator.runtime.CreatorToolSurface
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.memoryId
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceConfigException
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val generationLoop: GenerationLoop,
    private val persistentBashSessionManager: WorkspacePersistentBashSessionManager,
    /**
     * T0 公共运行准入：createSnapshot 的 workspace 读取走它的 withSnapshot
     * （准入锁内最新 DB 行 + 同时刻双 epoch，装配与守卫准入共用一致视图）。
     * 默认挂在 WorkspaceRepository.runtimeGate（集成方在 repo 上提供该字段）。
     */
    private val workspaceRuntimeGate: WorkspaceRuntimeGate = workspaceRepository.runtimeGate,
    /**
     * 工具执行准入实现：默认直接适配 gate（validate 在 admission lock 内跑
     * 最新 DB 读；victim 取消/epoch 拒绝由守卫分类为 unavailable/
     * outcome_unknown）。集成方如需替换整条准入链可注入其他实现。
     */
    private val workspaceToolRunner: WorkspaceToolRunner = WorkspaceGateToolRunner(workspaceRuntimeGate),
    /**
     * 创造助手的 `creator_*` 工具；会话助手为创造助手时追加。null（测试）时不追加。
     */
    private val creatorToolSurface: CreatorToolSurface? = null,
) {
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        workspace: WorkspaceEntity? = null,
        conversationId: kotlin.uuid.Uuid? = null,
        workspaceCwdScoped: Boolean = false,
    ): List<Tool> = createSnapshot(
        settings, assistant, model, workspaceCwd, workspace, conversationId, workspaceCwdScoped,
    ).tools

    /**
     * 一次生成请求的工具快照：除最终 wire 面外还产出精确 wireName→Tool 映射
     *（身份直接读 [Tool.workspaceIdentity]，不依赖 Override 反查）、禁用工具
     * 别名表、wire 策略与工作区能力快照。任何重复 wireName 抛
     * [DuplicateToolWireNameException]；启用工具与禁用 workspace 别名撞名抛
     * [ForbiddenWorkspaceToolNameException]。
     *
     * workspace 绑定归属只看 [assistant]（workspaceId），传入的 [workspace] 实
     * 体仅作调用方上下文不参与授权——与助手绑定不符的实体绝不发工具。切片
     * （最新 entity + 同时刻双 epoch + 工具装配）整个在
     * [WorkspaceRuntimeGate.withSnapshot] 准入锁内 materialize，与执行准入共用
     * 一致视图；工作区缺失/closed/quarantined（快照读 fail-fast）产出显式空能
     * 力面 + all-forbidden wire 策略而非 null，提示层不走旧式固定清单回退。
     *
     * workspace 切片工具在 [createWorkspaceToolSnapshot] 内完成唯一一次
     * Override；非 workspace 工具在此分别 Override 后拼接，绝不对切片二次
     * applyBuiltinOverrides（Override 名恰为另一 builtin ID 时会按别人 spec
     * 重写 name/schema/prompt，身份与 wire 名错位）。
     */
    /** Extra input transformers of a creator conversation (proposal status notes); empty otherwise. */
    fun creatorInputTransformers(
        assistant: Assistant,
        conversationId: kotlin.uuid.Uuid,
    ): List<me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer> =
        creatorToolSurface
            ?.takeIf { CreatorAssistantDefinition.isCreator(assistant.id) }
            ?.let { listOf(it.proposalReminder(conversationId)) }
            .orEmpty()

    suspend fun createSnapshot(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        workspace: WorkspaceEntity? = null,
        /** 创造助手会话的 ID：提案归属；创造助手且非 null 时追加 `creator_*` 工具。 */
        conversationId: kotlin.uuid.Uuid? = null,
        /** 会话开关：开且选了非根 cwd 时，模型只看得到该目录。 */
        workspaceCwdScoped: Boolean = false,
    ): ChatToolSnapshot {
        // 技能与 workspace 元数据每轮只取一次（调用方可在构造工具前预取复用）
        val allSkills = skillManager.listSkills()
        // 绑定归属权威：assistant.workspaceId。传入的 workspace 实体不参与授权，
        // 也绝不绕过锁内最新 DB 读——与助手绑定不符的传入实体得不到工具。
        val boundWorkspaceId = assistant.workspaceId?.toString()
        val overrides = settings.toolOverrides
        val nonWorkspacePlaceholders = builtinToolPlaceholders(
            cwd = workspaceCwd,
            skillsXml = formatAvailableSkillsXml(
                allSkills
                    .filter { it.name in assistant.enabledSkills }
                    .map { it.name to it.description }
            ),
        )

        // workspace 切片整体在 withSnapshot 准入锁内装配：锁内最新 DB 行 +
        // 同时刻双 epoch + 工具/守卫/禁用别名/wire 策略一次物化，不在锁外拼接。
        val scopeResult = if (workspaceCwdScoped) {
            runCatching { me.rerere.workspace.WorkspaceFilesScope.normalize(workspaceCwd) }
        } else Result.success(null)
        val workspaceSlice = boundWorkspaceId?.let { workspaceId ->
            try {
                workspaceRuntimeGate.withSnapshot(workspaceId) { epochs ->
                    val entity = workspaceRepository.getById(workspaceId)
                    if (entity == null) {
                        // 绑定还在但行缺失：空能力面 + 全禁 wire 策略，不能回落
                        // null（那会让提示层回退到旧式固定清单推荐禁用工具）。
                        unavailableWorkspaceToolSlice(
                            workspaceId = workspaceId,
                            overrides = overrides,
                            toolRunner = workspaceToolRunner,
                            snapshotEpochs = epochs,
                        )
                    } else if (scopeResult.isFailure) {
                        // Invalid cwd under the switch fails closed, never widens to the whole workspace.
                        unavailableWorkspaceToolSlice(
                            workspaceId = workspaceId,
                            overrides = overrides,
                            toolRunner = workspaceToolRunner,
                            snapshotEpochs = epochs,
                        )
                    } else {
                        val filesScope = scopeResult.getOrNull()
                        val targets = workspaceRepository.executionMountTargets(workspaceId, filesScope)
                        createWorkspaceToolSnapshot(
                            workspace = entity,
                            workspaceRepository = workspaceRepository,
                            cwd = workspaceCwd,
                            persistentBashSessionManager = persistentBashSessionManager,
                            subagentHost = WorkspaceSubagentHost(
                                generationHandler = generationLoop,
                                settingsStore = settingsStore,
                                reminderTransformer = workspaceReminderTransformer,
                                context = context,
                                parentAssistant = assistant,
                                parentModel = model,
                                cwd = workspaceCwd,
                            ),
                            includeSubagent = true,
                            toolRunner = workspaceToolRunner,
                            caller = WorkspaceToolCaller.PARENT,
                            loadWorkspace = { id -> workspaceRepository.getById(id) },
                            overrides = overrides,
                            placeholders = builtinToolPlaceholders(
                                cwd = workspaceCwd,
                                skillsXml = nonWorkspacePlaceholders["skills"].orEmpty(),
                                workspaceName = entity.name,
                                extraMounts = extraMountPromptLines(targets),
                            ),
                            snapshotEpochs = epochs,
                            exposedTargets = targets,
                            filesScope = filesScope,
                        )
                    }
                }
            } catch (_: WorkspaceDeviceConfigException) {
                // An active device source/permission disappeared: do not issue a partial schema.
                unavailableWorkspaceToolSlice(
                    workspaceId = workspaceId,
                    overrides = overrides,
                    toolRunner = workspaceToolRunner,
                )
            } catch (closed: WorkspaceUnavailableException) {
                // closed/quarantined：快照读 fail-fast（quarantine 不无限挂起）→
                // 同样产空能力面 + 全禁策略的显式不可用切片。
                unavailableWorkspaceToolSlice(
                    workspaceId = workspaceId,
                    overrides = overrides,
                    toolRunner = workspaceToolRunner,
                    blockedAtCapture = true,
                )
            }
        }

        // 非 workspace 工具各自过一次 Override（不与切片混批）：顺序保持
        // 旧装配序，workspace 切片插在原位置（recentChats 之后、skills 之前）。
        val headTools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
        }.applyBuiltinConcurrency(settings.toolExecution)
            .applyBuiltinOverrides(overrides, nonWorkspacePlaceholders)

        val skillTargets = workspaceSlice?.capabilities?.exposedTargets.orEmpty()
        val effectiveSkillPlaceholders = nonWorkspacePlaceholders + mapOf(
            "skills" to formatEffectiveSkillsXml(
                allSkills.filter { it.name in assistant.enabledSkills }, skillTargets,
            ),
        )
        val tailTools = buildList {
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = allSkills,
                        exposedTargets = skillTargets,
                    )
                )
            }
            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                        concurrency = if (tool.isParallel) ToolConcurrency.Parallel else ToolConcurrency.Exclusive,
                    )
                )
            }
        }.applyBuiltinConcurrency(settings.toolExecution)
            .applyBuiltinOverrides(overrides, effectiveSkillPlaceholders)

        // 工作区切片在切片内已改名，按 workspaceIdentity 的稳定 id 识别，改名后照样可写。
        val ordinaryTools = headTools +
            (workspaceSlice?.tools ?: emptyList()).applyBuiltinConcurrency(settings.toolExecution) +
            tailTools

        // T1: 精确 wireName→Tool 映射 + 禁用别名冲突校验。身份不走 Override 反查。
        // 切片工具名已在切片内 Override 完毕，finalWorkspaceToolNames 直接读最终名。
        val finalWorkspaceToolNames = ordinaryTools.mapNotNull { tool ->
            tool.workspaceIdentity?.let { it.stableId to tool.name }
        }.toMap()
        // 保留命名空间断言：普通工具最终 wire 面不得出现 `creator_` 前缀
        // （含 Override 改名后的最终名；伪名占私有空间即显式失败）。
        ordinaryTools.forEach { tool ->
            CreatorToolNamespace.requireOrdinaryWireName(tool.name)
        }
        val creatorTools = creatorToolSurface
            ?.takeIf { conversationId != null && CreatorAssistantDefinition.isCreator(assistant.id) }
            ?.tools(conversationId!!)
            ?.applyBuiltinConcurrency(settings.toolExecution)
            ?.applyBuiltinOverrides(overrides, nonWorkspacePlaceholders + CreatorToolSurface.placeholders())
            .orEmpty()
        val tools = ordinaryTools + creatorTools
        checkForbiddenNameConflicts(tools, workspaceSlice?.forbidden)
        return ChatToolSnapshot(
            tools = tools,
            bindings = buildToolBindings(tools),
            forbiddenWorkspaceToolNames = workspaceSlice?.forbidden?.keys?.toSet(),
            wirePolicy = workspaceSlice?.wirePolicy?.copy(
                toolOverridesFingerprint = toolOverridesFingerprint(overrides),
            ),
            workspaceCapabilities = workspaceSlice?.capabilities?.copy(
                availableToolNames = finalWorkspaceToolNames,
            ),
            isCurrent = {
                (workspaceSlice?.isCurrent?.invoke() ?: true) &&
                    settingsStore.settingsFlow.value.toolOverrides == overrides
            },
        )
    }
}
