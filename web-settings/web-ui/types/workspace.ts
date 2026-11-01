export type { WorkspaceSubagentPreset, SubagentPresetDraft, SubagentPresetsResponse } from "./subagent-preset";

export type WorkspaceShellStatus = "DISABLED" | "INSTALLING" | "READY" | "BROKEN";

export type WorkspaceStorageArea = "FILES" | "LINUX";

export interface Workspace {
  id: string;
  name: string;
  root: string;
  shellStatus: WorkspaceShellStatus;
  createdAt: number;
  updatedAt: number;
  lastAccessAt?: number | null;
  toolApprovals?: Record<string, boolean>;
  harnessMinimal?: boolean;
  exposeUpload?: boolean;
  exposeSkills?: boolean;
  exposeToolOutputs?: boolean;
  subagentModelId?: string | null;
  subagentAllowContext?: boolean;
  shellCompatibilityMode?: boolean;
  /** 会话第一条用户消息附带工作目录的文件列表（相当于 ls），默认关 */
  firstMessageFileSnapshot?: boolean;
  /** 文件列表层数 1..10（1 = 只列工作目录本身） */
  firstMessageFileSnapshotDepth?: number;
  /** 逐工具启用开关 (稳定 ID -> enabled)，缺键=启用；存储损坏时为 null/undefined */
  toolEnablement?: Record<string, boolean> | null;
  /** 存储 JSON 损坏标志：true 时 fail-closed（全部视为禁用），UI 显示受限 + 重置入口 */
  toolEnablementCorrupt?: boolean;
}

export interface WorkspaceFileEntry {
  /** Present on confirmed CAS writes. Absent for legacy list/create operations. */
  revision?: string;
  path: string;
  name: string;
  isDirectory: boolean;
  sizeBytes: number;
  updatedAt: number;
}

/** 「移动到 / 复制到」 */
export type WorkspaceTransferMode = "move" | "copy";

export interface WorkspaceTransferItem {
  source: string;
  ok: boolean;
  /** 落点路径（相对目标 FILES 根）；失败为 null */
  target?: string | null;
  isDirectory?: boolean;
  /** 目标里已有同名项，落点改成了 "name (n)" */
  renamed?: boolean;
  skippedLinks?: number;
  errorCode?: string | null;
  error?: string | null;
}

export interface WorkspaceTransferResponse {
  targetWorkspaceId: string;
  targetDir: string;
  items: WorkspaceTransferItem[];
}

export interface WorkspaceFileListResponse {
  area: WorkspaceStorageArea;
  path: string;
  entries: WorkspaceFileEntry[];
}

/** Existing mkdir endpoint; exclusive defaults to false for legacy/import callers. */
export interface CreateWorkspaceDirectoryRequest {
  path: string;
  exclusive?: boolean;
}

export interface WorkspaceFileContent {
  /** SHA-256 of raw bytes; absence means editor must not save. */
  revision?: string;
  path: string;
  area: WorkspaceStorageArea;
  content: string;
  editable: boolean;
}

export interface WorkspaceTerminalSession {
  id: string;
  workspaceId: string;
  createdAt: number;
  cols: number;
  rows: number;
}

export type WorkspaceFileKind = "text" | "image" | "other";

/** `/api/events` 的 `workspace_update` 事件负载：相邻快照 diff 出的受影响 id。 */
export interface WorkspaceUpdateEvent {
  workspaceIds: string[];
  timestamp: number;
}

/* ------------------------------------------------------------------ */
/* 设备目录直连（无 COW）：/workspaces/{id}/device/*                      */
/* 响应只含 alias + 卷根相对路径，绝不暴露宿主绝对路径。                  */
/* ------------------------------------------------------------------ */

export type WorkspaceDeviceAvailabilityStatus =
  | "READY"
  | "UNSUPPORTED_API"
  | "PERMISSION_REQUIRED"
  | "PRIMARY_VOLUME_UNAVAILABLE";

export interface WorkspaceDeviceAvailability {
  status: WorkspaceDeviceAvailabilityStatus;
  /** 仅 READY 时可浏览/连接/读写 */
  canBrowse: boolean;
  /** PERMISSION_REQUIRED：授权只能在 App 端发起，Web 只提示 */
  needsPermission: boolean;
}

export interface WorkspaceDeviceMount {
  id: string;
  workspaceId: string;
  alias: string;
  sourceRelativePath: string;
  enabled: boolean;
  /** 运行态：授权+激活+目录校验全部通过 */
  active: boolean;
  /** Opaque opened-view freshness marker, not an authorization credential. */
  viewToken?: string;
  /** rootfs 内固定挂载点 /device/<alias>（仅展示） */
  guestPath: string;
  createdAt: number;
  updatedAt: number;
}

export interface WorkspaceDeviceBrowseEntry {
  name: string;
  relativePath: string;
}

export interface WorkspaceDeviceBrowseResponse {
  path: string;
  entries: WorkspaceDeviceBrowseEntry[];
}

export interface WorkspaceDeviceFileListResponse {
  mount: string;
  path: string;
  entries: WorkspaceFileEntry[];
}

export interface WorkspaceDeviceFileContent {
  revision?: string;
  mount: string;
  path: string;
  content: string;
  editable: boolean;
}

/** Rootfs install shared by the App page and the Web page. */
export interface RootfsInstallState {
  workspaceId: string;
  status: "installing" | "failed";
  url: string;
  stage?: "DOWNLOADING" | "EXTRACTING" | "INSTALLED" | null;
  bytesRead?: number;
  totalBytes?: number | null;
  entriesExtracted?: number;
  error?: string | null;
}

export interface RootfsInstallStatus {
  /** null = nothing running and no failure to show */
  state: RootfsInstallState | null;
  defaultUrl: string;
}

export type WorkspaceArchiveFormat =
  | "ZIP" | "SEVEN_ZIP" | "RAR" | "TAR" | "TAR_GZ" | "TAR_BZ2" | "TAR_XZ" | "GZ" | "BZ2" | "XZ";

export interface WorkspaceExtractResult {
  /** Path (FILES area) of the single item the archive landed as */
  path: string;
  name: string;
  isDirectory: boolean;
  format: WorkspaceArchiveFormat;
  files: number;
  directories: number;
  skipped: number;
  nameEncoding?: string | null;
}
