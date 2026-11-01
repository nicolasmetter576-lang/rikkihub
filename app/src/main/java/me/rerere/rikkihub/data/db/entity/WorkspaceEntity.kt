package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    // 逐工具启用开关 (稳定内建 ID -> enabled)，默认 "{}" 缺键启用;
    // 解码/校验见 data.ai.tools.WorkspaceToolEnablement, 损坏配置 fail-closed
    @ColumnInfo("tool_enablement", defaultValue = "{}")
    val toolEnablement: String = "{}",
    // 创建时选择后固定：屏蔽 workspace_read/write/edit/shell，改用 DSH 极简 bash + str_replace_editor
    @ColumnInfo("harness_minimal", defaultValue = "0")
    val harnessMinimal: Boolean = false,
    @ColumnInfo("expose_upload", defaultValue = "1")
    val exposeUpload: Boolean = true,
    @ColumnInfo("expose_skills", defaultValue = "1")
    val exposeSkills: Boolean = true,
    @ColumnInfo("expose_tool_outputs", defaultValue = "1")
    val exposeToolOutputs: Boolean = true,
    @ColumnInfo("subagent_model_id")
    val subagentModelId: String? = null,
    @ColumnInfo("subagent_allow_context", defaultValue = "0")
    val subagentAllowContext: Boolean = false,
    // Strict codec: WorkspaceSubagentPresets. Corrupt stored JSON is preserved until explicit repair.
    @ColumnInfo("subagent_presets", defaultValue = "[]")
    val subagentPresets: String = "[]",
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
    // 会话第一条用户消息附带工作目录的一层文件列表（相当于 ls），见 data/workspace/WorkspaceFileSnapshot.kt
    @ColumnInfo("first_message_file_snapshot", defaultValue = "0")
    val firstMessageFileSnapshot: Boolean = false,
    // 文件列表展开的层数（1 = 只列工作目录本身，相当于 ls），范围见 WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH
    @ColumnInfo("first_message_file_snapshot_depth", defaultValue = "1")
    val firstMessageFileSnapshotDepth: Int = 1,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun exposedMountTargets(): Set<String> = buildSet {
        if (exposeSkills) add("/skills")
        if (exposeUpload) add("/upload")
        // /subagent_runs 与 /tool_outputs 共用一个暴露开关, 避免为日志目录新增 DB 列
        if (exposeToolOutputs) {
            add("/tool_outputs")
            add("/subagent_runs")
        }
    }

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
