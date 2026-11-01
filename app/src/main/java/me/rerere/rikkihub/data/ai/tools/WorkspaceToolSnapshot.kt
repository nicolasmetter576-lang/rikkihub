package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.WorkspaceToolWirePolicy
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolIdentity
import me.rerere.ai.ui.WorkspaceToolMode
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceEpochs
import me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import me.rerere.rikkihub.data.workspace.rootfsEnvironmentPromptLines
import me.rerere.rikkihub.data.workspace.workspaceReminderPrompt
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException
import me.rerere.workspace.WorkspaceShellStatus

/**
 * T1 工具快照与双执行守卫的类型装配面。
 *
 * 流水线：候选 Tool（填 [Tool.workspaceIdentity]，activationEpoch 取
 * [WorkspaceToolRunner.currentToolEpoch] 当前值）→ 按最新 enablement/READY/模式过滤 →
 * 双执行守卫包装 → applyBuiltinOverrides → 精确 wireName→Tool 映射（重名即抛）。
 * 执行判定一律走 [Tool.workspaceIdentity]，禁止用 ToolOverride/BuiltinToolCatalog 反查旧 alias。
 */

/**
 * 一次生成请求的工具快照。由 [ChatToolFactory.createSnapshot] 产出；
 * [createTools] 保持兼容（等价于 `createSnapshot(...).tools`）。
 *
 * @param tools 最终 wire 面（已应用 Override），顺序即发出顺序
 * @param bindings 精确 wireName → Tool 映射（identity 直接读 [Tool.workspaceIdentity]）。
 *        任意两个工具解析出相同 wireName 即抛 [DuplicateToolWireNameException]
 * @param forbiddenWorkspaceToolNames 禁用/非本模式 workspace 稳定 ID 在当前 Override 下
 *        解析出的 wire 名集合（非 READY 时含全部可配置 ID）。启用工具名与禁用别名重合
 *        是显式冲突（[ForbiddenWorkspaceToolNameException]），不得静默删其他来源工具。
 *        无工作区绑定时为 null。与 [wirePolicy] 同源。
 * @param wirePolicy 从 [forbiddenWorkspaceToolNames] 派生的最终 wire 保护策略，
 *        供 TextGenerationParams.workspaceToolPolicy 在各协议收口点消费。
 * @param workspaceCapabilities 本次请求的工作区能力快照（供 transformer/截断计划
 *        按真实能力渲染）。绑定了工作区但非 READY 时仍是空能力对象（非 null），
 *        避免回退到旧式固定工具清单提示；完全未绑定工作区时为 null。
 */
data class ChatToolSnapshot(
    val tools: List<Tool>,
    val bindings: Map<String, Tool>,
    val forbiddenWorkspaceToolNames: Set<String>? = null,
    val wirePolicy: WorkspaceToolWirePolicy? = null,
    val workspaceCapabilities: WorkspaceRequestCapabilities? = null,
    /**
     * 本请求生效的 provider wire 改名表：原 wire 名 → 发出名（由
     * `withProviderWireToolRenames` 在请求构建处按 provider 写入；装配期
     * 恒为空）。非空时生成循环同步到 `TextGenerationParams.toolWireRenames`，
     * 历史消息中的调用名在 wire 构建时按同一映射回放。
     */
    val wireRenames: Map<String, String> = emptyMap(),
    /**
     * 快照时效回调（请求一致性）：装配时由工厂捕获 gate epoch 读数，
     * 生成循环在请求构建完、发出前复查；false = 策略/视图已变化，本快照
     * 构成的提示/能力/工具表是半更新面，调用方必须整份重建 attempt。
     * 默认恒 true（固定 tools 的旧调用方无时效概念）。非 suspend 纯内存读。
     */
    val isCurrent: () -> Boolean = { true },
)

/**
 * 工作区工具切片快照（ChatToolFactory 与子代理 child 装配共用）。
 * [tools] 是守卫包装后、未过 Override 的 workspace 工具（子代理直接使用；
 * 父面之后统一 applyBuiltinOverrides，capabilities 的 wire 名由调用方按
 * 最终 tools 重算）。
 */
data class WorkspaceToolSnapshot(
    val tools: List<Tool>,
    val capabilities: WorkspaceRequestCapabilities,
    /** wireName → stableId（已按调用方 overrides 解析）；child 路径传空 overrides */
    val forbidden: Map<String, String>,
    val wirePolicy: WorkspaceToolWirePolicy,
    val policyEpoch: Long,
    /** 装配时刻视图指纹；工作区不可用切片为 null */
    val viewFingerprint: String?,
    /**
     * 与 [ChatToolSnapshot.isCurrent] 同契约的切片时效回调：工厂在 gate
     * 读数处捕获 epochs，组装后策略再变即返回 false。默认恒 true。
     */
    val isCurrent: () -> Boolean = { true },
)

/** 任意来源工具解析出重复 wireName。 */
class DuplicateToolWireNameException(
    val wireName: String,
) : IllegalStateException("Duplicate tool wire name: $wireName")

/** 启用工具 wire 名与禁用/非本模式 workspace 工具别名重合（含被 Override 撞名）。 */
class ForbiddenWorkspaceToolNameException(
    val wireName: String,
    val stableId: String,
) : IllegalStateException(
    "Tool name '$wireName' conflicts with disabled workspace tool '$stableId'; " +
        "rename the override or re-enable the workspace tool",
)

/**
 * 工作区工具准入被拒绝的控制信号：守卫把它转成普通工具结果（status=unavailable），
 * 不置 Pending、不静默放行；gate 适配层也应抛它（reason=GATE_DENIED）。
 */
class WorkspaceToolUnavailableException(
    val identity: WorkspaceToolIdentity,
    val reason: Reason,
    message: String? = null,
) : IllegalStateException(
    message ?: "Workspace tool '${identity.stableId}' is unavailable: $reason",
) {
    enum class Reason {
        WORKSPACE_MISSING,
        NOT_READY,
        MODE_MISMATCH,
        TOOL_DISABLED,
        CONFIG_INVALID,
        VIEW_STALE,
        GATE_DENIED,
        /** 签发身份的 activationSession 与运行准入层的进程 nonce 不一致 */
        SESSION_MISMATCH,
    }
}

/** 守卫调用方归属：父会话工具面 / 子代理内部工具面（共用 merged，不递归） */
enum class WorkspaceToolCaller { PARENT, SUBAGENT_CHILD }

/** 守卫递交给运行准入层的载荷；WorkspaceRuntimeGate 适配器消费同一结构。 */
data class WorkspaceToolCallAdmission(
    val identity: WorkspaceToolIdentity,
    /** 装配时名（Override 前）；最终 wire 名经 ChatToolSnapshot.bindings 查 */
    val wireName: String,
    val caller: WorkspaceToolCaller,
    /** executeWithContext 路径有值；execute 路径为 null */
    val toolCallId: String? = null,
    /**
     * 装配时刻的 per-tool epoch，恒等于 [WorkspaceToolIdentity.activationEpoch]。
     * disable→enable 重开后该值递增，旧工具实例由此被拒绝复活。
     */
    val expectedToolEpoch: Long = 0L,
)

/**
 * 执行准入 seam（gate 挂点）。守卫把校验载荷交给实现方：
 *
 * - [validate]：**必须在实现方的准入临界区（admission lock）内执行**——它做最新
 *   DB 读（行存在/READY/模式/enablement/结构指纹），抛 [WorkspaceToolUnavailableException]
 *   即拒绝；适配层应原样传播该异常（reason 保留），不可吞成普通 false 丢原因。
 * - [operation]：真实工具 body（日志/metadata/bash 等全部 side effect 都在此之后）。
 * - 准入拒绝（WorkspaceUnavailableException 等 gate 拒绝）→ 实现方映射为
 *   [WorkspaceToolUnavailableException]（reason=GATE_DENIED）；victim 取消应让
 *   [WorkspacePolicyCancellationException] 原样上抛（守卫据此映射 outcome_unknown）。
 *   其余 CancellationException 不属于策略取消，守卫判别后原样传播。
 */
fun interface WorkspaceToolRunner {
    suspend fun run(
        admission: WorkspaceToolCallAdmission,
        validate: suspend () -> Unit,
        operation: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart>

    /**
     * 装配时刻的 per-tool epoch 读数（workspaceId+stableId 粒度，disable→enable 递增）。
     * 非 suspend 纯内存读；gate 未接入时默认 0 表示不校验。
     * 填入 [WorkspaceToolIdentity.activationEpoch] 并经 admission.expectedToolEpoch 回传。
     */
    fun currentToolEpoch(workspaceId: String, stableId: String): Long = 0L

    /** 装配时刻的 policy/view epoch（非 suspend 内存读）；无 gate 返回 null。 */
    fun currentEpochs(workspaceId: String): WorkspaceEpochs? = null

    /**
     * 进程级激活会话 nonce（gate 的 `WorkspaceRuntimeGate.activationSession`）。
     * 填入 [WorkspaceToolIdentity.activationSession] 参与签发/验签：进程重启后
     * epoch 归零，单靠 epoch 比对会让旧 pending 绑定与重新递增到同值的纪元
     * 碰撞复活；session 不同必失配 → EPOCH_REVOKED。无 gate 时为 null（兼容
     * 旧绑定字段缺省）。
     */
    val activationSession: String? get() = null

    /**
     * 该 workspace 当前是否处于不可准入态（closed/sealed/quarantined）。
     * 非 suspend 纯内存读；无 gate 返回 false。[ChatToolSnapshot.isCurrent]
     * 用它区分「快照期间的准入阻塞」与真正的策略漂移。
     */
    fun isWorkspaceBlocked(workspaceId: String): Boolean = false
}

/**
 * 当前工具面的结构指纹：`root:<root>:mode:<mode>`。
 * 刻意不含 enablement——启用配置变化只影响守卫准入，不作废已签发绑定
 * （policy 漂移不炸旧 part）；以后可由视图层替换/叠加 merged view 指纹。
 */
internal fun workspaceToolViewFingerprint(workspace: WorkspaceEntity, viewEpoch: Long = 0L): String {
    val mode = if (workspace.harnessMinimal) WorkspaceToolMode.HARNESS_MINIMAL else WorkspaceToolMode.NORMAL
    val base = "root:${workspace.root}:mode:$mode"
    // Each published device structure/activation transition owns a different view instance.
    // The separately signed process nonce prevents reuse of this counter after restart.
    return if (viewEpoch == 0L) base else "$base:view:$viewEpoch"
}

/**
 * 候选阶段签发身份：implementationId 当前恒等于 stableId；
 * activationEpoch 取 [toolRunner] 的当前 per-tool epoch（防关→开旧实例复活）。
 */
internal fun workspaceToolIdentity(
    workspace: WorkspaceEntity,
    stableId: String,
    toolRunner: WorkspaceToolRunner? = null,
): WorkspaceToolIdentity = WorkspaceToolIdentity(
    workspaceId = workspace.id,
    stableId = stableId,
    implementationId = if (stableId == BuiltinToolIds.SUBAGENT) {
        WorkspaceSubagentPresets.implementationId(workspace.subagentPresets,
            workspace.subagentModelId, workspace.subagentAllowContext)
    } else stableId,
    mode = if (workspace.harnessMinimal) WorkspaceToolMode.HARNESS_MINIMAL else WorkspaceToolMode.NORMAL,
    viewFingerprint = workspaceToolViewFingerprint(workspace, toolRunner?.currentEpochs(workspace.id)?.viewEpoch ?: 0L),
    activationEpoch = toolRunner?.currentToolEpoch(workspace.id, stableId) ?: 0L,
    activationSession = toolRunner?.activationSession,
)

/**
 * 按工作区当前模式+enablement 计算**应出现在 schema** 的稳定 ID 集合。
 * tool_enablement 损坏 → 空集（fail-closed：一个工具都不声明，仍守卫兜底）。
 */
internal fun enabledWorkspaceToolIds(workspace: WorkspaceEntity): Set<String> {
    val parsed = WorkspaceToolEnablement.tryParse(workspace.toolEnablement) ?: return emptySet()
    return WorkspaceToolEnablement.idsFor(workspace.harnessMinimal)
        .filterTo(LinkedHashSet()) { parsed.toolEnabled(it) }
}

/**
 * 禁用/非本模式 workspace 稳定 ID 的当前解析 wire 名（wireName → stableId）。
 * 非 READY / 损坏配置 → enabled 为空，全部可配置 ID 进禁用面。
 */
internal fun forbiddenWorkspaceToolNames(
    workspace: WorkspaceEntity?,
    overrides: Map<String, ToolOverride>,
): Map<String, String> {
    // 非 READY / 缺失工作区不声明任何工具：全部可配置 ID 进禁用面
    val enabled = if (workspace?.shellStatus == WorkspaceShellStatus.READY.name) {
        enabledWorkspaceToolIds(workspace)
    } else {
        emptySet()
    }
    return forbiddenWireNameMap(overrides) { it !in enabled }
}

/** 全部可配置稳定 ID 的禁用别名表（工作区缺失/不可用的 all-forbidden 口径）。 */
internal fun forbiddenAllWorkspaceToolNames(
    overrides: Map<String, ToolOverride>,
): Map<String, String> = forbiddenWireNameMap(overrides) { true }

/**
 * 禁用面的 wire 名映射：每个进禁用面的 stableId 同时保护**稳定原名与 Override
 * 生效名**（customBody/choice 可用任一形态把禁用工具写回 wire）。同一 wire 名
 * 被两个不同 stableId 声明（Override 互相撞名）是显式冲突，抛
 * [ForbiddenWorkspaceToolNameException]，绝不用 associate 静默覆盖隐藏冲突。
 */
private fun forbiddenWireNameMap(
    overrides: Map<String, ToolOverride>,
    include: (String) -> Boolean,
): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    fun claim(wireName: String, stableId: String) {
        val existing = map[wireName]
        if (existing != null && existing != stableId) {
            throw ForbiddenWorkspaceToolNameException(wireName, stableId)
        }
        map[wireName] = stableId
    }
    for (id in WorkspaceToolEnablement.configurableIds) {
        if (!include(id)) continue
        claim(id, id)
        claim(BuiltinToolCatalog.require(id).resolvedName(overrides), id)
    }
    return map
}

/** 精确 wireName→Tool 映射；任何重名（含 Override 撞名/MCP 撞名）抛异常。 */
internal fun buildToolBindings(tools: List<Tool>): Map<String, Tool> {
    val bindings = LinkedHashMap<String, Tool>(tools.size)
    for (tool in tools) {
        val previous = bindings.put(tool.name, tool)
        if (previous != null) throw DuplicateToolWireNameException(tool.name)
    }
    return bindings
}

/** 启用工具 wire 名撞上禁用 workspace 别名 → 显式冲突，不静默删改。 */
internal fun checkForbiddenNameConflicts(
    tools: List<Tool>,
    forbidden: Map<String, String>?,
) {
    if (forbidden.isNullOrEmpty()) return
    for (tool in tools) {
        val stableId = forbidden[tool.name] ?: continue
        throw ForbiddenWorkspaceToolNameException(tool.name, stableId)
    }
}

/**
 * 守卫的 DB 验证部分（[WorkspaceToolRunner.validate] 的实现体）：重读实体后逐项校验，
 * 拒绝抛 [WorkspaceToolUnavailableException]；必须在准入临界区内、任何 side effect
 * （日志/metadata/bash）之前执行。
 */
internal fun requireWorkspaceToolAdmissible(
    identity: WorkspaceToolIdentity,
    workspace: WorkspaceEntity?,
    viewEpoch: Long = 0L,
) {
    val entity = workspace
        ?: throw WorkspaceToolUnavailableException(
            identity,
            WorkspaceToolUnavailableException.Reason.WORKSPACE_MISSING,
            "Workspace ${identity.workspaceId} no longer exists",
        )
    if (entity.id != identity.workspaceId) {
        throw WorkspaceToolUnavailableException(
            identity,
            WorkspaceToolUnavailableException.Reason.WORKSPACE_MISSING,
            "Workspace id mismatch: ${entity.id} != ${identity.workspaceId}",
        )
    }
    if (entity.shellStatus != WorkspaceShellStatus.READY.name) {
        throw WorkspaceToolUnavailableException(
            identity,
            WorkspaceToolUnavailableException.Reason.NOT_READY,
            "Workspace ${identity.workspaceId} shell status is ${entity.shellStatus}, not READY",
        )
    }
    val currentMode =
        if (entity.harnessMinimal) WorkspaceToolMode.HARNESS_MINIMAL else WorkspaceToolMode.NORMAL
    if (identity.mode != currentMode ||
        identity.stableId !in WorkspaceToolEnablement.idsFor(entity.harnessMinimal)
    ) {
        throw WorkspaceToolUnavailableException(
            identity,
            WorkspaceToolUnavailableException.Reason.MODE_MISMATCH,
            "Tool '${identity.stableId}' (mode=${identity.mode}) does not belong to mode $currentMode",
        )
    }
    val enablement = WorkspaceToolEnablement.tryParse(entity.toolEnablement)
        ?: throw WorkspaceToolUnavailableException(
            identity,
            WorkspaceToolUnavailableException.Reason.CONFIG_INVALID,
            "Workspace ${identity.workspaceId} tool_enablement is corrupt; refusing execution",
        )
    if (!enablement.toolEnabled(identity.stableId)) {
        throw WorkspaceToolUnavailableException(
            identity,
            WorkspaceToolUnavailableException.Reason.TOOL_DISABLED,
            "Workspace tool '${identity.stableId}' is disabled",
        )
    }
    if (identity.stableId == BuiltinToolIds.SUBAGENT && identity.implementationId !=
        WorkspaceSubagentPresets.implementationId(entity.subagentPresets,
            entity.subagentModelId, entity.subagentAllowContext)) {
        throw WorkspaceToolUnavailableException(identity,
            WorkspaceToolUnavailableException.Reason.CONFIG_INVALID,
            "Workspace subagent catalogue or defaults changed; request a fresh tool snapshot")
    }
    val currentFingerprint = workspaceToolViewFingerprint(entity, viewEpoch)
    if (identity.viewFingerprint != null && identity.viewFingerprint != currentFingerprint) {
        throw WorkspaceToolUnavailableException(
            identity,
            WorkspaceToolUnavailableException.Reason.VIEW_STALE,
            "Workspace view fingerprint changed (was ${identity.viewFingerprint}, now $currentFingerprint)",
        )
    }
}

/**
 * 准入失败的普通工具结果（status=unavailable）。非空输出 → 终止 pending、不冒充
 * 成功执行；结构化字段供模型与上层判定。
 */
internal fun workspaceToolUnavailableParts(
    identity: WorkspaceToolIdentity,
    reason: WorkspaceToolUnavailableException.Reason,
    detail: String?,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("status", "unavailable")
            put("reason", reason.name)
            put("tool", identity.stableId)
            put("error", detail ?: "workspace tool is unavailable")
        }.toString(),
    ),
)

/**
 * 已开始执行后被策略变更取消的普通工具结果（status=outcome_unknown）。
 * `started=true` 明确声明该调用可能已产生部分副作用，不冒充零执行。
 */
internal fun workspaceToolOutcomeUnknownParts(
    identity: WorkspaceToolIdentity,
    detail: String?,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("status", "outcome_unknown")
            put("started", JsonPrimitive(true))
            put("tool", identity.stableId)
            put(
                "error",
                detail ?: "workspace operation was cancelled by a workspace configuration change; " +
                    "the call may have partially executed",
            )
        }.toString(),
    ),
)

/**
 * 双执行守卫包装：对 [Tool.execute] 与 [Tool.executeWithContext]（若有）统一在最前
 * 做准入（validate 经 [toolRunner] 进入 admission lock 做最新 DB 读），再执行真实 body。
 *
 * 结果语义（异常分类严格按类型，不靠字符串/class name 判别）：
 * - [WorkspaceToolUnavailableException] / gate 的 [WorkspaceUnavailableException]
 *   → 普通工具结果 status=unavailable（未开始，明确零执行）；
 * - [WorkspacePolicyCancellationException]（gate victim 取消，已开始）
 *   → 普通工具结果 status=outcome_unknown 且 started=true；
 * - 其余 CancellationException 经 [ensureActive] 判别：调用方协程已取消→原样重抛；
 *   仍 active 的非策略 CE 不属于本守卫分类，同样原样传播（不吞为工具结果）；
 * - runner/body 其他异常原样传播。
 */
internal fun Tool.withWorkspaceExecutionGuard(
    identity: WorkspaceToolIdentity,
    loadWorkspace: suspend (String) -> WorkspaceEntity?,
    toolRunner: WorkspaceToolRunner?,
    caller: WorkspaceToolCaller,
): Tool {
    val wireName = name

    suspend fun admit(
        toolCallId: String?,
        body: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val admission = WorkspaceToolCallAdmission(
            identity = identity,
            wireName = wireName,
            caller = caller,
            toolCallId = toolCallId,
            expectedToolEpoch = identity.activationEpoch,
        )
        val validate: suspend () -> Unit = {
            requireWorkspaceToolAdmissible(
                identity, loadWorkspace(identity.workspaceId),
                toolRunner?.currentEpochs(identity.workspaceId)?.viewEpoch ?: 0L,
            )
        }
        return try {
            // 进程 nonce 比对先于一切准入/body：签发身份与当前 runner 分属不同
            // 激活会话（如同进程换 gate 实例）一律拒绝，不让旧会话工具复活。
            val runnerSession = toolRunner?.activationSession
            if (runnerSession != null && identity.activationSession != runnerSession) {
                throw WorkspaceToolUnavailableException(
                    identity,
                    WorkspaceToolUnavailableException.Reason.SESSION_MISMATCH,
                    "Tool identity was issued under a different activation session",
                )
            }
            if (toolRunner != null) {
                // gate 准入：validate 在 admission lock 内跑最新 DB 读
                toolRunner.run(admission, validate, body)
            } else {
                // 无 gate：守卫自行验证后直跑 body
                validate()
                body()
            }
        } catch (policyCancelled: WorkspacePolicyCancellationException) {
            // 强类型政策取消：已开始执行被配置变更打断 → outcome_unknown。
            // 但若调用方协程同时已被用户整体取消，绝不能吞掉 user CE 伪报成功
            // 结束——先 ensureActive 让真实取消传播，仍 active 才归 outcome_unknown。
            currentCoroutineContext().ensureActive()
            workspaceToolOutcomeUnknownParts(identity, policyCancelled.message)
        } catch (denied: WorkspaceToolUnavailableException) {
            workspaceToolUnavailableParts(denied.identity, denied.reason, denied.message)
        } catch (gateDenied: WorkspaceUnavailableException) {
            // 防御：适配层若原样抛 gate 拒绝,仍归入 unavailable（不丢 reason 语义到输出）
            workspaceToolUnavailableParts(
                identity,
                WorkspaceToolUnavailableException.Reason.GATE_DENIED,
                gateDenied.message,
            )
        } catch (cancelled: CancellationException) {
            // 调用方协程真实取消 → ensureActive 重抛；仍 active 的非策略 CE
            // 不是本守卫的归口（可能来自操作内部），同样传播不误分类。
            currentCoroutineContext().ensureActive()
            throw cancelled
        }
    }

    return copy(
        // 双保险：守卫包装把 workspaceIdentity 定格为签发身份——直接对裸 Tool
        // 调用守卫（如测试 dummy）也保证身份非 null，与 buildWorkspaceTools
        // 的 guard copy 同源。
        workspaceIdentity = identity,
        execute = { args -> admit(null) { execute(args) } },
        executeWithContext = executeWithContext?.let { original ->
            { args: kotlinx.serialization.json.JsonElement, ctx: ToolExecutionContext ->
                admit(ctx.toolCallId) { original(args, ctx) }
            }
        },
    )
}

/**
 * 工作区工具切片快照的统一天装配入口（Factory 父面与子代理 child 共用）：
 * 候选→过滤→守卫包装，并附带能力/禁用别名/wire 策略。
 *
 * - [overrides] 在本函数内对切片工具应用一次（改名/描述/prompt 插值），
 *   [placeholders] 为插值表（child 传空 map 即不应用 Override）；调用方不得
 *   再对切片工具二次 applyBuiltinOverrides——Override 名恰为另一 builtin ID
 *   时二次套用会按别人 spec 重写 name/schema/prompt，身份与 wire 名错位；
 * - [capabilities].availableToolNames 直接用切片工具的最终 wire 名（已是
 *   Override 后名，与 bindings/forbidden 同源）；
 * - 非 READY 实体也返回非 null capabilities（空能力面），供提示层不做旧式回退；
 * - [isCurrent] 捕获装配时刻的 runner 双 epoch/激活 nonce/准入阻塞态：之后任一
 *   变化（policy/view epoch、session、closed/sealed/quarantined）即返回 false，
 *   调用方须整份重建快照（不消息/schema 混版）。无 runner 时恒 true。
 */
internal fun createWorkspaceToolSnapshot(
    workspace: WorkspaceEntity,
    workspaceRepository: WorkspaceRepository?,
    cwd: String? = null,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager? = null,
    subagentHost: WorkspaceSubagentHost? = null,
    includeSubagent: Boolean = true,
    toolRunner: WorkspaceToolRunner? = null,
    caller: WorkspaceToolCaller = WorkspaceToolCaller.PARENT,
    loadWorkspace: suspend (String) -> WorkspaceEntity?,
    overrides: Map<String, ToolOverride> = emptyMap(),
    placeholders: Map<String, String> = emptyMap(),
    /** gate.withSnapshot 锁内读取时刻的双 epoch；null 时回落 runner.currentEpochs（无 gate→0） */
    snapshotEpochs: WorkspaceEpochs? = null,
    /** Guest targets validated from the same request snapshot (never host source paths). */
    exposedTargets: Set<String> = workspace.exposedMountTargets(),
    /** Working-directory confinement of this request; null = whole workspace. */
    filesScope: String? = null,
): WorkspaceToolSnapshot {
    // 切片工具在此统一过 Override（有且仅有一次）：wire 名、bindings、
    // capabilities.availableToolNames、禁用别名全部以最终发出名同源。
    val catalog = if (includeSubagent && subagentHost != null) captureSubagentCatalog(workspace, subagentHost) else null
    val candidates = buildWorkspaceTools(
        workspace = workspace,
        workspaceRepository = workspaceRepository,
        cwd = cwd,
        persistentBashSessionManager = persistentBashSessionManager,
        subagentHost = subagentHost,
        includeSubagent = includeSubagent,
        toolRunner = toolRunner,
        caller = caller,
        loadWorkspace = loadWorkspace,
        subagentCatalog = catalog,
        filesScope = filesScope,
    )
    if (catalog?.available?.isNotEmpty() == true && subagentHost != null &&
        candidates.any { it.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT } &&
        (subagentHost.parentAssistant.customBodies + subagentHost.parentModel.customBodies).any {
            it.key.substringBefore('.').substringBefore('[').substringBefore('/') in setOf("tools", "functions")
        }) {
        // A raw declaration override could replace the selector enum/directory with disabled roles.
        // Fail closed rather than silently remove user configuration or expose a second tool surface.
        error("Raw custom body tool declarations cannot override the workspace subagent preset catalogue")
    }
    // Overrides intentionally replace the static description. Finalize the mandatory directory
    // from the very same captured catalogue used by parameters/dispatch, once and only once.
    val tools = candidates.applyBuiltinOverrides(overrides, placeholders).map { tool ->
        if (tool.workspaceIdentity?.stableId == BuiltinToolIds.SUBAGENT && catalog?.available?.isNotEmpty() == true) {
            tool.copy(description = tool.description + "\n\n" + subagentPresetDirectory(catalog.available))
        } else tool
    }
    val capturedEpochs = snapshotEpochs ?: toolRunner?.currentEpochs(workspace.id)
    val capturedSession = toolRunner?.activationSession
    val capturedBlocked = toolRunner?.isWorkspaceBlocked(workspace.id) ?: false
    val policyEpoch = capturedEpochs?.policyEpoch ?: 0L
    val viewFingerprint = workspaceToolViewFingerprint(workspace, capturedEpochs?.viewEpoch ?: 0L)
    val capabilities = WorkspaceRequestCapabilities(
        workspaceId = workspace.id,
        workspaceName = workspace.name,
        harnessMinimal = workspace.harnessMinimal,
        availableToolNames = tools.associate { it.workspaceIdentity!!.stableId to it.name },
        instructionsCwd = cwd,
        instructionsEligible = workspace.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name,
        exposedTargets = exposedTargets,
        policyEpoch = policyEpoch,
        viewFingerprint = viewFingerprint,
        filesScope = filesScope,
        rootfsEnvironment = rootfsEnvironmentPromptLines(workspaceRepository?.rootfsEnvironment(workspace)),
    )
    val forbidden = forbiddenWorkspaceToolNames(workspace, overrides)
    return WorkspaceToolSnapshot(
        tools = tools,
        capabilities = capabilities.copy(
            reminderPrompt = workspaceReminderPrompt(
                workspaceName = workspace.name,
                harnessMinimal = workspace.harnessMinimal,
                exposedTargets = exposedTargets,
                cwd = cwd,
                overrides = overrides,
                capabilities = capabilities,
            ),
        ),
        forbidden = forbidden,
        wirePolicy = WorkspaceToolWirePolicy(
            forbiddenWireNames = forbidden.keys,
            workspaceId = workspace.id,
            policyEpoch = policyEpoch,
            viewFingerprint = viewFingerprint,
        ),
        policyEpoch = policyEpoch,
        viewFingerprint = viewFingerprint,
        isCurrent = {
            sliceIsCurrent(toolRunner, workspace.id, capturedEpochs, capturedSession, capturedBlocked)() &&
                (catalog == null || subagentHost?.settingsStore?.settingsFlow?.value?.providers == catalog.providerSnapshot)
        },
    )
}

/**
 * 工作区不可用（缺失/closed/quarantined）时的显式空切片：tools 空、
 * capabilities 空表（非 null，提示层不走旧式固定清单回退）、forbidden 为
 * all-forbidden 口径（稳定原名+Override 生效名全保护）。
 */
internal fun unavailableWorkspaceToolSlice(
    workspaceId: String,
    overrides: Map<String, ToolOverride>,
    toolRunner: WorkspaceToolRunner?,
    /** 捕获时刻的 gate 读数；catch(closed) 路径传 runner 当前读数 */
    snapshotEpochs: WorkspaceEpochs? = null,
    blockedAtCapture: Boolean = false,
): WorkspaceToolSnapshot {
    val capturedEpochs = snapshotEpochs ?: toolRunner?.currentEpochs(workspaceId)
    val capturedSession = toolRunner?.activationSession
    val forbidden = forbiddenAllWorkspaceToolNames(overrides)
    val capabilities = WorkspaceRequestCapabilities(
        workspaceId = workspaceId,
        workspaceName = "",
        harnessMinimal = false,
        availableToolNames = emptyMap(),
        instructionsEligible = false,
        exposedTargets = emptySet(),
        policyEpoch = capturedEpochs?.policyEpoch ?: 0L,
        viewFingerprint = null,
    )
    return WorkspaceToolSnapshot(
        tools = emptyList(),
        capabilities = capabilities.copy(
            reminderPrompt = workspaceReminderPrompt(
                workspaceName = "",
                harnessMinimal = false,
                exposedTargets = emptySet(),
                cwd = null,
                overrides = overrides,
                capabilities = capabilities,
            ),
        ),
        forbidden = forbidden,
        wirePolicy = WorkspaceToolWirePolicy(
            forbiddenWireNames = forbidden.keys,
            workspaceId = workspaceId,
            policyEpoch = capturedEpochs?.policyEpoch,
            viewFingerprint = null,
        ),
        policyEpoch = capturedEpochs?.policyEpoch ?: 0L,
        viewFingerprint = null,
        isCurrent = sliceIsCurrent(
            toolRunner, workspaceId, capturedEpochs, capturedSession, blockedAtCapture,
        ),
    )
}

/**
 * 切片/快照时效闭包：装配时刻捕获的 epoch+session+阻塞态与 runner 当前读数比对。
 * 等值且阻塞态未变 → true；任何漂移 → false（调用方整份重建）。无 runner → 恒 true。
 */
private fun sliceIsCurrent(
    toolRunner: WorkspaceToolRunner?,
    workspaceId: String,
    capturedEpochs: WorkspaceEpochs?,
    capturedSession: String?,
    capturedBlocked: Boolean,
): () -> Boolean = {
    val runner = toolRunner
    when {
        runner == null -> true
        runner.activationSession != capturedSession -> false
        runner.currentEpochs(workspaceId) != capturedEpochs -> false
        else -> runner.isWorkspaceBlocked(workspaceId) == capturedBlocked
    }
}
