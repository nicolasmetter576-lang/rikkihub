package me.rerere.rikkihub.data.workspace

import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds

/**
 * 单个 provider 请求的工作区能力快照（提示/输出按真实能力渲染）。
 *
 * 由 ChatToolFactory 在装配 ChatToolSnapshot 时一次性构造，经
 * `TransformerContext.workspaceCapabilities` 传给输入 transformer；
 * 不依赖 WorkspaceEntity / Android，纯 Kotlin 可 JVM 单测。
 *
 * 请求绑定了工作区时非 null，未 READY/已失效也提供空能力面；null = 未传快照的旧调用。
 * 提示与工具表同源，动态请求不重新查 DB 或借用旧固定清单。
 *
 * @property availableToolNames 已启用的工作区工具：stableId → 本次请求实际 wire 名
 *   （ToolOverride 改名后的发出名）。禁用工具与模式外工具不出现在此表。
 * @property exposedTargets 挂载目标快照（`/skills`/`/upload`/`/tool_outputs`/
 *   `/subagent_runs`，设备视图落地后含 `/device/<alias>`）。
 * @property policyEpoch 装配时刻的 workspace 策略 epoch（tool_enablement 等准入策略；
 *   见 WorkspaceRuntimeGate.currentEpochs）。无 gate 来源时传 0。
 * @property viewFingerprint 装配时刻的视图指纹（binding 签发用的
 *   workspaceToolViewFingerprint / 未来 merged view 指纹），无则 null。
 */
data class WorkspaceRequestCapabilities(
    val workspaceId: String,
    val workspaceName: String,
    val harnessMinimal: Boolean,
    val availableToolNames: Map<String, String>,
    val exposedTargets: Set<String>,
    val policyEpoch: Long = 0L,
    val viewFingerprint: String? = null,
    /** Fully rendered reminder from this same request snapshot (including current overrides). */
    val reminderPrompt: String? = null,
    /** Captured cwd, including explicit null; never borrow a later/earlier context cwd. */
    val instructionsCwd: String? = null,
    /** READY is independent of tool enablement. Unavailable/legacy snapshots default closed. */
    val instructionsEligible: Boolean = false,
    /** FILES directory the agent is confined to in this request; null = whole workspace. */
    val filesScope: String? = null,
    /** Rendered `{{rootfs_environment}}` lines (shell + distribution) captured with this snapshot. */
    val rootfsEnvironment: String = "",
) {
    /** [stableId] 对应的本次 wire 名（null = 未启用/不在快照）。 */
    fun wireNameOf(stableId: String): String? = availableToolNames[stableId]

    /** [stableId] 本次是否可用。 */
    fun hasTool(stableId: String): Boolean = stableId in availableToolNames

    /** 按偏好序取第一个可用回读工具的 stableId：shell 族优先于查看族。 */
    val readbackStableId: String?
        get() = READBACK_TOOL_IDS.firstOrNull { it in availableToolNames }

    /** 任一可回读 `/tool_outputs` 的工具可用：read/bash/shell/editor 之一。 */
    val hasReadbackTool: Boolean get() = readbackStableId != null

    /** 回读工具的 wire 名（用于提示文案展示真实名）。 */
    val readbackWireName: String? get() = readbackStableId?.let { availableToolNames[it] }

    /** `/tool_outputs` 是否在本次快照的挂载目标里。 */
    val exposesToolOutputs: Boolean get() = TOOL_OUTPUTS_GUEST_PATH in exposedTargets

    /**
     * 是否允许「截断输出全文落盘 + 回读指引」：必须同时有可回读工具与
     * `/tool_outputs` 挂载，否则只许「明确截断、不保存、不假称可回读」。
     */
    val canSaveToolOutputs: Boolean get() = exposesToolOutputs && hasReadbackTool

    companion object {
        const val TOOL_OUTPUTS_GUEST_PATH = "/tool_outputs"

        /** 可回读工具输出的稳定 ID，按提示偏好排序（shell 族优先，可直接 grep/cat）。 */
        val READBACK_TOOL_IDS: List<String> = listOf(
            BuiltinToolIds.BASH,
            BuiltinToolIds.WORKSPACE_SHELL,
            BuiltinToolIds.WORKSPACE_READ_FILE,
            BuiltinToolIds.STR_REPLACE_EDITOR,
        )
    }
}
