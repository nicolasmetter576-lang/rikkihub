package me.rerere.rikkihub.data.db.dao

import me.rerere.rikkihub.data.db.entity.WorkspaceEntity

/**
 * 两版 WorkspaceEntity 之间的显式差分补丁.
 *
 * 每个可写字段一条: `null` = 该列不变; 可空列 (subagentModelId/lastAccessAt)
 * 额外用 `hasXxx` 区分「显式设为 null」与「不变」.
 * `updateInTransaction` 用它把 transform 结果落成逐列定向 UPDATE,
 * 保证一次配置写入只触碰有差异的列.
 */
data class WorkspaceConfigPatch(
    val name: String? = null,
    val hasSubagentModelId: Boolean = false,
    val subagentModelId: String? = null,
    val subagentAllowContext: Boolean? = null,
    val subagentPresets: String? = null,
    val exposeUpload: Boolean? = null,
    val exposeSkills: Boolean? = null,
    val exposeToolOutputs: Boolean? = null,
    val hasLastAccessAt: Boolean = false,
    val lastAccessAt: Long? = null,
    val shellStatus: String? = null,
    val shellCompatibilityMode: Boolean? = null,
    val firstMessageFileSnapshot: Boolean? = null,
    val firstMessageFileSnapshotDepth: Int? = null,
    val toolApprovals: String? = null,
    val toolEnablement: String? = null,
) {
    /** 是否有任何列差异; 无差异时调用方只需 touch updated_at */
    val hasChanges: Boolean
        get() = name != null || hasSubagentModelId || subagentAllowContext != null ||
            exposeUpload != null || exposeSkills != null || exposeToolOutputs != null ||
            hasLastAccessAt || shellStatus != null || shellCompatibilityMode != null || firstMessageFileSnapshot != null ||
            firstMessageFileSnapshotDepth != null ||
            toolApprovals != null || toolEnablement != null || subagentPresets != null

    companion object {
        /** 计算 [from] → [to] 的列差异 (不校验身份字段, 由调用方 require) */
        fun diff(from: WorkspaceEntity, to: WorkspaceEntity): WorkspaceConfigPatch =
            WorkspaceConfigPatch(
                name = to.name.takeIf { it != from.name },
                hasSubagentModelId = to.subagentModelId != from.subagentModelId,
                subagentModelId = to.subagentModelId.takeIf { to.subagentModelId != from.subagentModelId },
                subagentAllowContext = to.subagentAllowContext.takeIf { it != from.subagentAllowContext },
                subagentPresets = to.subagentPresets.takeIf { it != from.subagentPresets },
                exposeUpload = to.exposeUpload.takeIf { it != from.exposeUpload },
                exposeSkills = to.exposeSkills.takeIf { it != from.exposeSkills },
                exposeToolOutputs = to.exposeToolOutputs.takeIf { it != from.exposeToolOutputs },
                hasLastAccessAt = to.lastAccessAt != from.lastAccessAt,
                lastAccessAt = to.lastAccessAt.takeIf { to.lastAccessAt != from.lastAccessAt },
                shellStatus = to.shellStatus.takeIf { it != from.shellStatus },
                shellCompatibilityMode = to.shellCompatibilityMode.takeIf { it != from.shellCompatibilityMode },
                firstMessageFileSnapshot = to.firstMessageFileSnapshot.takeIf { it != from.firstMessageFileSnapshot },
                firstMessageFileSnapshotDepth = to.firstMessageFileSnapshotDepth.takeIf { it != from.firstMessageFileSnapshotDepth },
                toolApprovals = to.toolApprovals.takeIf { it != from.toolApprovals },
                toolEnablement = to.toolEnablement.takeIf { it != from.toolEnablement },
            )
    }
}
