package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/**
 * 工作区工具的稳定身份。由工具装配端（ChatToolFactory/createWorkspaceTools 候选阶段）
 * 填进 `Tool.workspaceIdentity`，过滤与包装后**再**应用 Override——它记录的是工具的
 * 稳定归属，不是请求 wire 名。
 *
 * 与 `Tool.name`（可能被 ToolOverride 改名、随请求冻结的 wire 名）不同，
 * [stableId] 跨模式/跨重命名保持稳定，是执行守卫的判定键。
 */
@Serializable
data class WorkspaceToolIdentity(
    /** 归属工作区 id（对应 `workspaces.id`） */
    val workspaceId: String,
    /**
     * 稳定内建 ID，如 `workspace_read_file`/`bash`/`subagent`（见 BuiltinToolIds）。
     * 非 wire 名，不受 Override 影响。
     */
    val stableId: String,
    /**
     * 当前实现语义 ID。当前恒等于 [stableId]；若未来同一 stableId 在不同模式下有
     * 语义不同的实现，用它区分。
     */
    val implementationId: String = stableId,
    /**
     * 装配模式：`"normal"` 或 `"harness_minimal"`（见 [WorkspaceToolMode]）。
     */
    val mode: String = WorkspaceToolMode.NORMAL,
    /**
     * 设备视图指纹（merged view 的结构指纹，由视图层产出）。无设备视图时为 null。
     */
    val viewFingerprint: String? = null,
    /**
     * 进程内激活纪元（per stableId 撤销令牌）。装配时取
     * `WorkspaceRuntimeGate.currentToolEpoch(workspaceId, stableId)` 的当前值；
     * 同进程内 禁用→再启用 会递增纪元，执行守卫拒绝旧纪元装配的 Tool 实例。
     */
    val activationEpoch: Long = 0,
    /** Process-local activation nonce prevents revocation-counter collisions after restart. */
    val activationSession: String? = null,
) {
    /**
     * 与「当前实现身份」精确匹配：workspaceId/stableId/implementationId/mode/
     * viewFingerprint 全部一致。
     */
    fun matches(current: WorkspaceToolIdentity): Boolean =
        workspaceId == current.workspaceId &&
            stableId == current.stableId &&
            implementationId == current.implementationId &&
            mode == current.mode &&
            viewFingerprint == current.viewFingerprint
}

object WorkspaceToolMode {
    const val NORMAL = "normal"
    const val HARNESS_MINIMAL = "harness_minimal"
}
