package me.rerere.rikkihub.web.dto

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceFileEntry

@Serializable
data class CreateWorkspaceRequest(
    val name: String? = null,
    val harnessMinimal: Boolean = false,
)

@Serializable
data class WorkspaceDto(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
    val toolApprovals: Map<String, Boolean> = emptyMap(),
    val harnessMinimal: Boolean = false,
    val exposeUpload: Boolean = true,
    val exposeSkills: Boolean = true,
    val exposeToolOutputs: Boolean = true,
    val subagentModelId: String? = null,
    val subagentAllowContext: Boolean = false,
    val shellCompatibilityMode: Boolean = false,
    val firstMessageFileSnapshot: Boolean = false,
    val firstMessageFileSnapshotDepth: Int = 1,
    // 逐工具启用开关 (稳定内建 ID -> enabled), 缺键=启用;
    // 存储 JSON 损坏时为 null 且 toolEnablementCorrupt=true (fail-closed, UI 显示受限)
    val toolEnablement: Map<String, Boolean>? = null,
    val toolEnablementCorrupt: Boolean = false,
)

@Serializable
data class WorkspaceFileEntryDto(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
    // Present after a conditional editor save. Listing/legacy writes remain compatible.
    val revision: String? = null,
)

@Serializable
data class WorkspaceFileListResponse(
    val area: String,
    val path: String,
    val entries: List<WorkspaceFileEntryDto>,
)

@Serializable
data class WorkspaceFileContentDto(
    val path: String,
    val area: String,
    val content: String,
    val editable: Boolean,
    val revision: String? = null,
)

@Serializable
data class WriteWorkspaceFileRequest(
    val content: String,
    val overwrite: Boolean = true,
    val expectedRevision: String? = null,
)

@Serializable
data class CreateWorkspaceDirectoryRequest(
    val path: String,
    // Existing imports may use the legacy recursive mkdir contract. New UI
    // creates one empty directory exclusively, without adopting an existing one.
    val exclusive: Boolean = false,
)

@Serializable
data class MoveWorkspaceFileRequest(
    val source: String,
    val target: String,
    val overwrite: Boolean = false,
)

@Serializable
data class MoveWorkspaceFileResponse(
    val source: String,
    val target: String,
)

/** 「移动到 / 复制到」：放进 [targetWorkspaceId]（省略 = 本工作区）FILES 区的 [targetDir]，重名自动改名 */
@Serializable
data class TransferWorkspaceFilesRequest(
    val paths: List<String>,
    val targetWorkspaceId: String? = null,
    val targetDir: String = "",
    /** "move" | "copy" */
    val mode: String,
)

@Serializable
data class WorkspaceTransferItemDto(
    val source: String,
    val ok: Boolean,
    val target: String? = null,
    val isDirectory: Boolean = false,
    val renamed: Boolean = false,
    val skippedLinks: Int = 0,
    val errorCode: String? = null,
    val error: String? = null,
)

@Serializable
data class TransferWorkspaceFilesResponse(
    val targetWorkspaceId: String,
    val targetDir: String,
    val items: List<WorkspaceTransferItemDto>,
)

@Serializable
data class WorkspaceBatchDeleteFailureDto(
    val path: String,
    val error: String,
)

@Serializable
data class WorkspaceBatchDeleteResponse(
    val deletedPaths: List<String>,
    val failures: List<WorkspaceBatchDeleteFailureDto>,
)

@Serializable
data class WorkspaceTerminalSessionDto(
    val id: String,
    val workspaceId: String,
    val createdAt: Long,
    val cols: Int,
    val rows: Int,
)

fun WorkspaceEntity.toDto(): WorkspaceDto = WorkspaceDto(
    id = id,
    name = name,
    root = root,
    shellStatus = shellStatus,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessAt = lastAccessAt,
    toolApprovals = toolApprovalOverrides(),
    harnessMinimal = harnessMinimal,
    exposeUpload = exposeUpload,
    exposeSkills = exposeSkills,
    exposeToolOutputs = exposeToolOutputs,
    subagentModelId = subagentModelId,
    subagentAllowContext = subagentAllowContext,
    shellCompatibilityMode = shellCompatibilityMode,
    firstMessageFileSnapshot = firstMessageFileSnapshot,
    firstMessageFileSnapshotDepth = firstMessageFileSnapshotDepth,
    toolEnablement = WorkspaceToolEnablement.tryParse(toolEnablement),
    toolEnablementCorrupt = WorkspaceToolEnablement.tryParse(toolEnablement) == null,
)

fun WorkspaceFileEntry.toDto(): WorkspaceFileEntryDto = WorkspaceFileEntryDto(
    path = path,
    name = name,
    isDirectory = isDirectory,
    sizeBytes = sizeBytes,
    updatedAt = updatedAt,
)

/* ------------------------------------------------------------------ */
/* 设备目录直连 (无 COW): /workspaces/{id}/device/...                   */
/* 注意: 任何响应里只回 alias + 卷根相对路径, 绝不回宿主绝对路径。      */
/* ------------------------------------------------------------------ */

@Serializable
data class WorkspaceDeviceAvailabilityDto(
    /** READY / UNSUPPORTED_API / PERMISSION_REQUIRED / PRIMARY_VOLUME_UNAVAILABLE */
    val status: String,
    /** 仅 READY 时可浏览/连接/读写 */
    val canBrowse: Boolean,
    /** PERMISSION_REQUIRED: 授权动作只能在 App 端发起 (Web 只提示) */
    val needsPermission: Boolean,
)

@Serializable
data class WorkspaceDeviceMountDto(
    val id: String,
    val workspaceId: String,
    /** guest /device/<alias> 别名 */
    val alias: String,
    /** 主共享存储卷根下的相对路径 (非宿主绝对路径) */
    val sourceRelativePath: String,
    val enabled: Boolean,
    /** 运行态: 授权+目录校验通过 (resolveMounts 产物); enabled 但 false = 失效, 挂只读占位 */
    val active: Boolean,
    /** Freshness only, not authorization. Rejects edits/picker results from an old view. */
    val viewToken: String = "",
    /** rootfs 内固定挂载点 /device/<alias> (仅展示) */
    val guestPath: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class CreateDeviceMountRequest(
    val alias: String,
    val sourceRelativePath: String,
)

@Serializable
data class WorkspaceDeviceBrowseEntryDto(
    val name: String,
    val relativePath: String,
)

@Serializable
data class WorkspaceDeviceBrowseResponse(
    val path: String,
    val entries: List<WorkspaceDeviceBrowseEntryDto>,
)

@Serializable
data class WorkspaceDeviceFileListResponse(
    val mount: String,
    val path: String,
    val entries: List<WorkspaceFileEntryDto>,
)

@Serializable
data class WorkspaceDeviceFileContentDto(
    val mount: String,
    val path: String,
    val content: String,
    val editable: Boolean = true,
    val revision: String? = null,
)

fun me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity.toDeviceDto(
    active: Boolean,
    viewToken: String = "",
): WorkspaceDeviceMountDto = WorkspaceDeviceMountDto(
    id = id,
    workspaceId = workspaceId,
    alias = alias,
    sourceRelativePath = sourceRelativePath,
    enabled = enabled,
    active = active,
    viewToken = viewToken,
    guestPath = "${me.rerere.workspace.WorkspaceDeviceDirectoryGuard.DEVICE_GUEST_ROOT}/$alias",
    createdAt = createdAt,
    updatedAt = updatedAt,
)
