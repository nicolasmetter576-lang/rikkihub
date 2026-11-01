import { requestWebAuthTicket } from "~/services/api";
import api from "~/services/api";
import type { ToolOverride } from "@overlay/types/settings";
import type {
  Workspace,
  CreateWorkspaceDirectoryRequest,
  WorkspaceFileContent,
  WorkspaceFileEntry,
  WorkspaceFileKind,
  WorkspaceFileListResponse,
  WorkspaceStorageArea,
  WorkspaceTerminalSession,
  WorkspaceUpdateEvent,
  WorkspaceSubagentPreset,
  SubagentPresetDraft,
  SubagentPresetsResponse,
  RootfsInstallStatus,
  WorkspaceExtractResult,
  WorkspaceTransferMode,
  WorkspaceTransferResponse,
} from "@overlay/types/workspace";

/* ------------------------------------------------------------------ */
/* 工具启用（tool_enablement）：双端配置                                 */
/*                                                                     */
/* 后端契约：PUT /api/workspaces/{id} patch 键 `toolEnablement`          */
/* （对象，键=七稳定 ID，值=字面 boolean，增量 merge，缺字段保留），      */
/* `resetToolEnablement: true` 显式重置为 "{}"（损坏配置修复）。         */
/* T0–T2 快验后开放工具配置；设备 COW 仍独立等待 D0 放行。               */
/* ------------------------------------------------------------------ */

export const WORKSPACE_TOOL_ENABLEMENT_UI_ENABLED = true;

/** 对照 app `WorkspaceToolEnablement.normalIds`/`minimalIds`（七稳定 ID） */
export const WORKSPACE_TOOL_ENABLEMENT_NORMAL_IDS = [
  "workspace_read_file",
  "workspace_write_file",
  "workspace_edit_file",
  "workspace_shell",
  "subagent",
] as const;

export const WORKSPACE_TOOL_ENABLEMENT_MINIMAL_IDS = [
  "bash",
  "str_replace_editor",
  "subagent",
] as const;

export function workspaceToolEnablementIds(harnessMinimal: boolean): readonly string[] {
  return harnessMinimal
    ? WORKSPACE_TOOL_ENABLEMENT_MINIMAL_IDS
    : WORKSPACE_TOOL_ENABLEMENT_NORMAL_IDS;
}

/**
 * 当前 wire 名：用户在设置里 Override 过工具名时显示 Override 名，
 * 否则默认名 = 稳定 ID。仅用于展示，不作为任何身份反查键。
 */
export function resolveWorkspaceToolWireName(
  toolId: string,
  toolOverrides?: Record<string, ToolOverride>,
): string {
  const overridden = toolOverrides?.[toolId]?.name?.trim();
  return overridden ? overridden : toolId;
}

/**
 * React Query key：列表是 ["workspaces", "list"]，detail 是 ["workspaces", id]。
 * list 显式占第二段而不是裸 ["workspaces"] 前缀——invalidate 前缀匹配会连带
 * 所有 detail key（全详情页重拉），独立段才能让"只失效列表"精确命中。
 * workspace id 是 UUID，不会与 "list" 冲突；`all` 保留给将来需要全域失效的场景。
 */
export const workspaceQueryKeys = {
  all: ["workspaces"] as const,
  list: ["workspaces", "list"] as const,
  detail: (id: string) => ["workspaces", id] as const,
  // Shares detail prefix: existing workspace_update invalidation also refreshes the catalogue.
  presets: (id: string) => ["workspaces", id, "subagent-presets"] as const,
};

/** React Query `invalidateQueries` 的最小结构接口，便于纯函数测试/复用。 */
export interface WorkspaceQueryInvalidator {
  invalidateQueries(options: { queryKey: readonly string[] }): unknown;
}

/**
 * `/api/events` 的 `workspace_update` 事件 → React Query 失效（纯逻辑，hook 委托它）：
 *  - workspaceIds 中的字符串 id -> 精确失效对应 detail key；
 *  - 列表总是失效（新增/删除/改名只影响列表）；
 *  - malformed / 空 ids：跳过 detail，仍失效列表，绝不抛错。
 */
export function invalidateWorkspaceQueriesEvent(
  queryClient: WorkspaceQueryInvalidator,
  data: WorkspaceUpdateEvent | null | undefined,
): void {
  const ids = data?.workspaceIds;
  if (Array.isArray(ids)) {
    for (const id of ids) {
      if (typeof id === "string" && id.length > 0) {
        void queryClient.invalidateQueries({
          queryKey: workspaceQueryKeys.detail(id),
        });
      }
    }
  }
  void queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.list });
}

export interface WorkspaceBatchDeleteFailure {
  path: string;
  error: string;
}

export interface WorkspaceBatchDeleteResult {
  deletedPaths: string[];
  failures: WorkspaceBatchDeleteFailure[];
}

/** 批删请求契约（A 后端）：POST /api/workspaces/{id}/files/batch-delete?area=… body {paths} */
export function buildBatchDeleteRequest(
  workspaceId: string,
  area: WorkspaceStorageArea,
  paths: string[],
): { url: string; body: { paths: string[] } } {
  const params = new URLSearchParams({ area });
  return {
    url: `workspaces/${workspaceId}/files/batch-delete?${params.toString()}`,
    body: { paths },
  };
}

/** ZIP 归档请求契约：POST /api/workspaces/{id}/files/archive?area=… body {paths} */
export function buildArchiveRequest(
  workspaceId: string,
  area: WorkspaceStorageArea,
  paths: string[],
): { path: string; body: string } {
  const params = new URLSearchParams({ area });
  return {
    path: `/api/workspaces/${workspaceId}/files/archive?${params.toString()}`,
    body: JSON.stringify({ paths }),
  };
}

/** .md / .markdown 才可预览。 */
export function isWorkspaceMarkdownFile(fileName?: string): boolean {
  const name = fileName?.toLowerCase() ?? "";
  return name.endsWith(".md") || name.endsWith(".markdown");
}

const IMAGE_EXTENSIONS = new Set([
  "png",
  "jpg",
  "jpeg",
  "gif",
  "webp",
  "bmp",
  "svg",
  "heic",
  "heif",
  "avif",
  "ico",
]);

const TEXT_EXTENSIONS = new Set([
  "txt",
  "md",
  "markdown",
  "json",
  "json5",
  "xml",
  "yaml",
  "yml",
  "toml",
  "ini",
  "conf",
  "cfg",
  "properties",
  "env",
  "csv",
  "tsv",
  "log",
  "html",
  "htm",
  "css",
  "scss",
  "sass",
  "less",
  "js",
  "mjs",
  "cjs",
  "ts",
  "tsx",
  "jsx",
  "kt",
  "kts",
  "java",
  "py",
  "rb",
  "go",
  "rs",
  "c",
  "h",
  "cpp",
  "hpp",
  "cc",
  "cs",
  "swift",
  "sh",
  "bash",
  "zsh",
  "gradle",
  "sql",
  "gitignore",
  "dockerfile",
  "lua",
  "php",
  "pl",
  "r",
  "dart",
  "vue",
  "svelte",
  "gql",
  "graphql",
  "proto",
  "diff",
  "patch",
  "srt",
  "vtt",
]);

export function detectWorkspaceFileKind(name: string): WorkspaceFileKind {
  const ext = name.includes(".") ? name.slice(name.lastIndexOf(".") + 1).toLowerCase() : "";
  if (!ext) return "other";
  if (IMAGE_EXTENSIONS.has(ext)) return "image";
  if (TEXT_EXTENSIONS.has(ext)) return "text";
  return "other";
}

export function formatWorkspaceBytes(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

export function parentWorkspacePath(path: string): string {
  if (!path) return "";
  const index = path.lastIndexOf("/");
  return index <= 0 ? "" : path.slice(0, index);
}

/** 目录在前、同层按名称排序，文件列表统一用它排序。 */
export function sortWorkspaceEntries(entries: WorkspaceFileEntry[]): WorkspaceFileEntry[] {
  return [...entries].sort((a, b) => {
    if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
}

export function joinWorkspacePath(base: string, name: string): string {
  const cleanName = name.replaceAll("\\", "/").replace(/^\/+/, "").replace(/\/+$/, "");
  if (!base) return cleanName;
  return `${base.replace(/\/+$/, "")}/${cleanName}`;
}

/**
 * 「移动到 / 复制到」能否把 FILES 区的 source 放进 destinationDir（"" 为根）。只有同一工作区才有限制：
 * 文件夹不能放进自己或自己的子目录；移动的落点不能是原文件夹（原地不动）。复制允许落在原文件夹，
 * 生成 "name (1)" 副本。与 App 的 workspaceTransferAllowed 一致。
 */
export function workspaceTransferAllowed(
  source: string,
  sameWorkspace: boolean,
  destinationDir: string,
  mode: WorkspaceTransferMode,
): boolean {
  const src = source.replace(/^\/+|\/+$/g, "");
  const dir = destinationDir.replace(/^\/+|\/+$/g, "");
  if (!src) return false;
  if (!sameWorkspace) return true;
  if (dir === src || dir.startsWith(`${src}/`)) return false;
  const slash = src.lastIndexOf("/");
  const parent = slash < 0 ? "" : src.slice(0, slash);
  return !(mode === "move" && dir === parent);
}

/** 落点里已有同名项、会被自动改名的数量 */
export function workspaceTransferConflictCount(
  sourceNames: ReadonlyArray<string>,
  destinationNames: ReadonlySet<string>,
): number {
  return sourceNames.filter((name) => destinationNames.has(name)).length;
}

/** 逐项结果汇总：成功数、改名数、跳过的链接数、失败项（原样，供保留选择与报原因） */
export function summarizeWorkspaceTransfer(response: WorkspaceTransferResponse) {
  const succeeded = response.items.filter((item) => item.ok);
  return {
    succeeded: succeeded.length,
    renamed: succeeded.filter((item) => item.renamed).length,
    skippedLinks: response.items.reduce((sum, item) => sum + (item.skippedLinks ?? 0), 0),
    failures: response.items.filter((item) => !item.ok),
  };
}

/** 单项失败码 → i18n key（workspace 命名空间）；未知码用通用文案 */
export function workspaceTransferErrorKey(code: string | null | undefined): string {
  switch (code) {
    case "FILE_NOT_FOUND":
      return "files.transfer_error.not_found";
    case "TRANSFER_SAME_LOCATION":
      return "files.transfer_error.same_location";
    case "TRANSFER_INTO_ITSELF":
      return "files.transfer_error.into_itself";
    case "SYMLINK_NOT_ALLOWED":
      return "files.transfer_error.symlink";
    case "NOT_ENOUGH_SPACE":
      return "files.transfer_error.no_space";
    case "FILE_ALREADY_EXISTS":
      return "files.transfer_error.name_taken";
    default:
      return "files.transfer_error.failed";
  }
}

/** Path as the model and the terminal see it: FILES → /workspace/…, rootfs → /… ("copy path"). */
export function workspaceGuestPath(area: WorkspaceStorageArea, path: string): string {
  const clean = path.replace(/^\/+|\/+$/g, "");
  if (area === "LINUX") return `/${clean}`;
  return clean ? `/workspace/${clean}` : "/workspace";
}

/** Device directories are mounted at /device/<alias> inside the workspace. */
export function workspaceDeviceGuestPath(alias: string, path: string): string {
  const clean = path.replace(/^\/+|\/+$/g, "");
  return clean ? `/device/${alias}/${clean}` : `/device/${alias}`;
}

const WORKSPACE_ARCHIVE_SUFFIXES = [
  ".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz",
  ".zip", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz",
];

/** UI hint for offering "Extract" (same list as WorkspaceArchiveExtractor.isArchiveName); the server sniffs content. */
export function isWorkspaceArchiveName(name: string): boolean {
  const lower = name.toLowerCase();
  return WORKSPACE_ARCHIVE_SUFFIXES.some((suffix) => lower.endsWith(suffix) && lower.length > suffix.length);
}

/** File-name charsets offered by the extract dialog; null = detect automatically. */
export const WORKSPACE_EXTRACT_ENCODINGS: ReadonlyArray<string | null> = [
  null, "UTF-8", "GB18030", "Big5", "Shift_JIS", "EUC-KR", "IBM437",
];

const extractErrors: Record<string, string> = {
  ARCHIVE_PASSWORD_REQUIRED: "extract.password_required",
  ARCHIVE_WRONG_PASSWORD: "extract.wrong_password",
  ARCHIVE_UNSUPPORTED: "extract.unsupported",
  ARCHIVE_CORRUPT: "extract.corrupt",
  ARCHIVE_ENCODING_UNSUPPORTED: "extract.encoding_unsupported",
  FILE_NOT_FOUND: "extract.not_found",
};

/** i18n key for an extract failure, by error code only. */
export function workspaceExtractErrorKey(error: unknown): string {
  const code = error && typeof error === "object" && "errorCode" in error ? error.errorCode : undefined;
  return typeof code === "string" && Object.hasOwn(extractErrors, code) ? extractErrors[code] : "extract.failed";
}

/** Download URL for <img>/<a>/window.open; the web session cookie authenticates it. */
export function workspaceFileUrl(
  workspaceId: string,
  area: WorkspaceStorageArea,
  path: string,
): string {
  const params = new URLSearchParams({ area, path });
  return `/api/workspaces/${workspaceId}/files/download?${params.toString()}`;
}

/* ------------------------------------------------------------------ */
/* 多选（纯函数，UI 挂 useState<Set<string>> 即可复用）                  */
/* ------------------------------------------------------------------ */

/** 后端契约上限：单次批量操作最多 200 项。 */
export const WORKSPACE_BATCH_MAX_PATHS = 200;
/** 后端契约上限：单条路径最长 1024 字符。 */
export const WORKSPACE_MAX_PATH_CHARS = 1024;
/** 前端 zip blob 上限，超过直接拒绝，避免浏览器被大文件拖死。 */
export const WORKSPACE_ARCHIVE_MAX_BYTES = 200 * 1024 * 1024;

/** ".." 这类导航占位项不可选。 */
export function isSelectableWorkspaceEntry(entry: { name: string }): boolean {
  return entry.name !== "..";
}

export function toggleWorkspaceSelection(
  selected: ReadonlySet<string>,
  path: string,
): Set<string> {
  const next = new Set(selected);
  if (next.has(path)) {
    next.delete(path);
  } else {
    next.add(path);
  }
  return next;
}

/** 当前页可选条目是否已全部选中（无可选项时返回 false）。 */
export function isWorkspaceSelectionAll(
  selected: ReadonlySet<string>,
  entries: ReadonlyArray<{ name: string; path: string }>,
): boolean {
  const selectable = entries.filter(isSelectableWorkspaceEntry);
  return selectable.length > 0 && selectable.every((entry) => selected.has(entry.path));
}

/** 全选/取消：all=true 选中当前页全部可选项，否则清空。 */
export function setWorkspaceSelectionAll(
  entries: ReadonlyArray<{ name: string; path: string }>,
  all: boolean,
): Set<string> {
  if (!all) return new Set();
  return new Set(entries.filter(isSelectableWorkspaceEntry).map((entry) => entry.path));
}

/** 刷新后修剪：只留下仍然存在的路径，保留失败项等剩余选择的语义由调用方决定。 */
export function pruneWorkspaceSelection(
  selected: ReadonlySet<string>,
  entries: ReadonlyArray<{ path: string }>,
): Set<string> {
  const live = new Set(entries.map((entry) => entry.path));
  const next = new Set<string>();
  for (const path of selected) {
    if (live.has(path)) next.add(path);
  }
  return next;
}

/** 校验批量请求路径集，返回错误原因 key（null=可提交）。 */
export function validateWorkspaceBatchPaths(
  paths: readonly string[],
): "empty" | "too_many" | "path_too_long" | null {
  if (paths.length === 0) return "empty";
  if (paths.length > WORKSPACE_BATCH_MAX_PATHS) return "too_many";
  if (paths.some((path) => path.length > WORKSPACE_MAX_PATH_CHARS)) return "path_too_long";
  return null;
}

/**
 * scope（workspace/area/path/file 视图）变化时的选择迁移：
 * 换目录/换区/换工作区/进入文件视图 → 清空；同 scope 保留。
 */
export function nextSelectionForScope<T>(
  state: { scopeKey: string; selected: ReadonlySet<T> },
  scopeKey: string,
): { scopeKey: string; selected: ReadonlySet<T> } {
  if (state.scopeKey === scopeKey) return state;
  return { scopeKey, selected: new Set<T>() };
}

/**
 * 批删响应 → UI 反馈。两个文件面板共用，保证 partial 语义一致：
 * all=全删；partial=部分成功（失败项保留选择）；failed=全失败。
 */
export function summarizeBatchDeleteResult(result: WorkspaceBatchDeleteResult): {
  outcome: "all" | "partial" | "failed";
  deletedCount: number;
  failedPaths: string[];
} {
  const failedPaths = (result.failures ?? []).map((failure) => failure.path);
  const deletedCount = result.deletedPaths?.length ?? 0;
  const outcome =
    failedPaths.length === 0 ? "all" : deletedCount === 0 ? "failed" : "partial";
  return { outcome, deletedCount, failedPaths };
}

/**
 * WS terminals cannot send Authorization headers — the ONLY remaining URL
 * credential is a single-use, ~90s `?ticket=` bound to this exact path and
 * scope "ws" (consumed by the upgrade handshake, replay-dead). With web auth
 * switched off the server returns an empty ticket and no credential is sent.
 */
export async function openWorkspaceTerminalSocket(
  workspaceId: string,
  sessionId: string,
): Promise<WebSocket> {
  const target = `/api/workspaces/${workspaceId}/terminal/sessions/${sessionId}`;
  const { ticket } = await requestWebAuthTicket("ws", target);
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const query = ticket ? `?ticket=${encodeURIComponent(ticket)}` : "";
  return new WebSocket(`${protocol}//${window.location.host}${target}${query}`);
}

export const workspaceApi = {
  list() {
    return api.get<Workspace[]>("workspaces");
  },
  get(id: string) {
    return api.get<Workspace>(`workspaces/${id}`);
  },
  create(name?: string, harnessMinimal?: boolean) {
    return api.post<Workspace>("workspaces", { name, harnessMinimal: harnessMinimal ?? false });
  },
  rename(id: string, name: string) {
    return api.put<Workspace>(`workspaces/${id}`, { name });
  },
  update(
    id: string,
    patch: {
      name?: string;
      subagentModelId?: string | null;
      subagentAllowContext?: boolean;
      shellCompatibilityMode?: boolean;
      firstMessageFileSnapshot?: boolean;
      firstMessageFileSnapshotDepth?: number;
      exposeUpload?: boolean;
      exposeSkills?: boolean;
      exposeToolOutputs?: boolean;
      toolApprovals?: Record<string, boolean>;
      toolEnablement?: Record<string, boolean>;
      resetToolEnablement?: boolean;
    },
  ) {
    return api.put<Workspace>(`workspaces/${id}`, patch);
  },
  delete(id: string) {
    return api.delete(`workspaces/${id}`);
  },
  listFiles(id: string, area: WorkspaceStorageArea, path: string) {
    const params = new URLSearchParams({ area, path });
    return api.get<WorkspaceFileListResponse>(`workspaces/${id}/files?${params.toString()}`);
  },
  mkdir(id: string, area: WorkspaceStorageArea, path: string) {
    const params = new URLSearchParams({ area });
    return api.post<WorkspaceFileEntry>(`workspaces/${id}/files/mkdir?${params.toString()}`, { path });
  },
  /** Empty folder from the name/extension dialog: never reuse an existing directory. */
  createDirectory(id: string, path: string) {
    const params = new URLSearchParams({ area: "FILES" });
    const request: CreateWorkspaceDirectoryRequest = { path, exclusive: true };
    return api.post<WorkspaceFileEntry>(`workspaces/${id}/files/mkdir?${params.toString()}`, request, { retry: 0 });
  },
  readFile(id: string, area: WorkspaceStorageArea, path: string) {
    const params = new URLSearchParams({ area, path });
    return api.get<WorkspaceFileContent>(`workspaces/${id}/files/content?${params.toString()}`);
  },
  writeFile(id: string, path: string, content: string, expectedRevision?: string) {
    const params = new URLSearchParams({ area: "FILES", path });
    return api.put<WorkspaceFileEntry>(`workspaces/${id}/files/content?${params.toString()}`, {
      content,
      overwrite: true,
      ...(expectedRevision !== undefined ? { expectedRevision } : {}),
    }, { retry: 0 });
  },
  createFile(id: string, path: string) {
    const params = new URLSearchParams({ area: "FILES", path });
    // An interrupted create has an unknown result: never automatically retry a PUT.
    return api.put<WorkspaceFileEntry>(`workspaces/${id}/files/content?${params.toString()}`, {
      content: "",
      overwrite: false,
    }, { retry: 0 });
  },
  /**
   * Move or copy FILES entries into a folder of this or another workspace (renames on a clash).
   * Per-item results; a copy can take long, so no timeout and no automatic retry.
   */
  transferFiles(
    id: string,
    paths: ReadonlyArray<string>,
    targetWorkspaceId: string,
    targetDir: string,
    mode: WorkspaceTransferMode,
  ) {
    return api.post<WorkspaceTransferResponse>(
      `workspaces/${id}/files/transfer`,
      { paths: [...paths], targetWorkspaceId, targetDir, mode },
      { retry: 0, timeout: false },
    );
  },
  moveFile(id: string, source: string, target: string) {
    const params = new URLSearchParams({ area: "FILES" });
    return api.post<{ source: string; target: string }>(
      `workspaces/${id}/files/move?${params.toString()}`,
      { source, target, overwrite: false },
    );
  },
  /** Extract an archive next to itself (FILES area). Big archives take minutes: no timeout, no automatic retry. */
  extract(id: string, path: string, password?: string | null, nameEncoding?: string | null) {
    return api.post<WorkspaceExtractResult>(
      `workspaces/${id}/files/extract`,
      {
        path,
        ...(password ? { password } : {}),
        ...(nameEncoding ? { nameEncoding } : {}),
      },
      { retry: 0, timeout: false },
    );
  },
  rootfsStatus(id: string) {
    return api.get<RootfsInstallStatus>(`workspaces/${id}/rootfs`);
  },
  installRootfs(id: string, url?: string | null) {
    return api.post<RootfsInstallStatus>(`workspaces/${id}/rootfs`, url ? { url } : {}, { retry: 0 });
  },
  cancelRootfs(id: string) {
    return api.delete<RootfsInstallStatus>(`workspaces/${id}/rootfs`);
  },
  upload(id: string, area: WorkspaceStorageArea, path: string, file: File) {
    const params = new URLSearchParams({ area, path });
    const form = new FormData();
    form.append("file", file, file.name);
    return api.postMultipart<WorkspaceFileEntry>(
      `workspaces/${id}/files/upload?${params.toString()}`,
      form,
    );
  },
  deleteFile(id: string, area: WorkspaceStorageArea, path: string, recursive: boolean) {
    const params = new URLSearchParams({
      area,
      path,
      recursive: String(recursive),
    });
    return api.delete(`workspaces/${id}/files?${params.toString()}`);
  },
  /** POST /files/batch-delete → { deletedPaths, failures[{path,error}] } */
  batchDeleteFiles(id: string, area: WorkspaceStorageArea, paths: string[]) {
    const req = buildBatchDeleteRequest(id, area, paths);
    return api.post<WorkspaceBatchDeleteResult>(req.url, req.body);
  },
  createTerminal(id: string, cols: number, rows: number) {
    const params = new URLSearchParams({
      cols: String(cols),
      rows: String(rows),
    });
    return api.post<WorkspaceTerminalSession>(
      `workspaces/${id}/terminal/sessions?${params.toString()}`,
      {},
    );
  },
};

/**
 * 用 fetch keepalive 写文件：页面卸载 / SPA 卸载时 ky 请求可能被中断，
 * keepalive 请求在浏览器侧会继续走完。返回 false 表示未能发出（如 keepalive
 * 超限），调用方应回退 workspaceApi.writeFile。
 */
export async function writeWorkspaceFileKeepalive(
  workspaceId: string, path: string, content: string, expectedRevision: string,
): Promise<WorkspaceFileEntry> {
  const params = new URLSearchParams({ area: "FILES", path });
  const response = await fetch(
    `/api/workspaces/${workspaceId}/files/content?${params.toString()}`,
    { method: "PUT", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content, overwrite: true, expectedRevision }), keepalive: true },
  );
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw Object.assign(new Error("File save rejected"), { code: response.status, errorCode: body.errorCode });
  }
  return response.json();
}

/* ------------------------------------------------------------------ */
/* ZIP 归档下载（POST /files/archive，流式附件）                        */
/* ------------------------------------------------------------------ */

const ARCHIVE_CONTENT_TYPES = new Set([
  "application/zip",
  "application/x-zip-compressed",
  "application/octet-stream",
]);

export class WorkspaceArchiveError extends Error {
  constructor(
    message: string,
    readonly code: "http" | "content_type" | "too_large" | "body" = "body",
    readonly status?: number,
  ) {
    super(message);
    this.name = "WorkspaceArchiveError";
  }
}

/**
 * 从 Content-Disposition 解析文件名；只允许 ASCII 安全字符，拒绝路径分隔符
 * 与奇异 unicode，防止响应头注入到本地文件名。解析失败回退 undefined。
 */
export function archiveFilenameFromDisposition(header: string | null): string | undefined {
  if (!header) return undefined;
  // filename*=（RFC 5987 编码）优先；filename= 的引号值允许内含 ;
  const encoded = /filename\*=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  const quoted = /filename="([^"]*)"/i.exec(header);
  const plain = /filename=([^;\s]+)/i.exec(header);
  let candidate = encoded?.[1] ?? quoted?.[1] ?? plain?.[1];
  if (!candidate) return undefined;
  try {
    candidate = decodeURIComponent(candidate);
  } catch {
    /* keep raw */
  }
  const name = candidate.split(/[\\/]/).pop()?.trim() ?? "";
  if (!name || name === "." || name === ".." || name.length > 200) return undefined;
  // 只允许字母数字 . _ - ( ) 空格，其余字符删除而非保留
  const safe = name.replace(/[^\w.\-() ]/g, "");
  return safe && safe !== "." && safe !== ".." ? safe : undefined;
}

/** 解析并校验归档响应头；返回应使用的安全文件名。 */
export function inspectArchiveResponseHeaders(headers: {
  get(name: string): string | null;
}): { fileName?: string } {
  const type = (headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();
  if (!ARCHIVE_CONTENT_TYPES.has(type)) {
    throw new WorkspaceArchiveError(`unexpected content-type: ${type || "none"}`, "content_type");
  }
  const length = Number(headers.get("content-length") ?? "");
  if (Number.isFinite(length) && length > WORKSPACE_ARCHIVE_MAX_BYTES) {
    throw new WorkspaceArchiveError("archive exceeds client blob limit", "too_large");
  }
  return { fileName: archiveFilenameFromDisposition(headers.get("content-disposition")) };
}

function archiveFallbackName(area: WorkspaceStorageArea): string {
  const stamp = new Date()
    .toISOString()
    .replaceAll(/[:.]/g, "-")
    .replace("T", "_")
    .slice(0, 19);
  return `workspace-${area.toLowerCase()}-${stamp}.zip`;
}

function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === "AbortError") ||
    (error instanceof Error && error.name === "AbortError")
  );
}

/**
 * 下载所选文件为 zip（会话 cookie 随同源请求携带）。校验状态码与
 * Content-Type，实体超限即终止；文件名只取安全字符。不做自动保存——
 * 调用方拿到 blob 后自行触发下载，错误路径绝不会把 JSON 写成 .zip。
 */
export async function downloadWorkspaceArchive(
  workspaceId: string,
  area: WorkspaceStorageArea,
  paths: string[],
  options: { signal?: AbortSignal } = {},
): Promise<{ blob: Blob; fileName: string }> {
  const req = buildArchiveRequest(workspaceId, area, paths);
  const response = await fetch(req.path, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/zip" },
    body: req.body,
    signal: options.signal ?? null,
    credentials: "same-origin",
  });
  if (!response.ok) {
    // 错误体可能是 JSON，只取少量文本做报错，绝不落盘
    let detail = "";
    try {
      detail = (await response.text()).slice(0, 200);
    } catch {
      /* ignore */
    }
    throw new WorkspaceArchiveError(
      detail || `HTTP ${response.status}`,
      "http",
      response.status,
    );
  }
  const { fileName } = inspectArchiveResponseHeaders(response.headers);
  const blob = await response.blob();
  if (blob.size > WORKSPACE_ARCHIVE_MAX_BYTES) {
    throw new WorkspaceArchiveError("archive exceeds client blob limit", "too_large");
  }
  return { blob, fileName: fileName ?? archiveFallbackName(area) };
}

/** 触发浏览器下载；revokeObjectURL 延迟到事件循环之后，避免过早回收。 */
export function saveBlobAs(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    anchor.rel = "noopener";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }
}

export { isAbortError as isWorkspaceAbortError };

/* ------------------------------------------------------------------ */
/* Markdown 预览：渲染层 URL 解析（不改源码）                           */
/*                                                                     */
/* 安全由共享 Markdown 的 allowRawHtml=false 保证：raw HTML 不入 DOM、 */
/* KaTeX trust=false、safeMarkdownUrlTransform 协议白名单兜底。这里    */
/* 只补一件事——相对图片 src 映射到带鉴权的工作区下载 URL。              */
/* ------------------------------------------------------------------ */

export interface WorkspaceMarkdownContext {
  workspaceId: string;
  area: WorkspaceStorageArea;
  /** 当前文件所在目录（"" = 根），相对图片基于它解析。 */
  baseDir: string;
}

/** 相对路径归一化（拒绝越出根的 ..），命中返回规范化后的绝对相对路径。 */
export function resolveWorkspaceRelativePath(baseDir: string, rawPath: string): string | null {
  const raw = rawPath.trim().replaceAll("\\", "/");
  if (!raw || /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(raw)) return null; // 有协议的不走这里
  const combined = raw.startsWith("/") ? raw.slice(1) : `${baseDir}/${raw}`;
  const parts: string[] = [];
  for (const part of combined.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") {
      if (parts.length === 0) return null;
      parts.pop();
      continue;
    }
    parts.push(part);
  }
  return parts.join("/");
}

type MarkdownUrlResolver = (
  url: string,
  key: string,
  node: unknown,
) => string | null | undefined;

const URL_SCHEME_OR_SPECIAL = /^([a-zA-Z][a-zA-Z0-9+.-]*:|\/|#)/;

/**
 * 工作区 Markdown 预览的 urlTransform 预解析（传给共享 Markdown 的
 * resolveUrl，仅安全模式生效，返回值再过一遍 safeMarkdownUrlTransform）：
 * - 仅处理图片/资源类 src、srcSet；href 等交给默认过滤。
 * - 相对路径（含 ./ ../ 与含空格路径）归一化到 workspace 内后映射为
 *   /api/workspaces/{id}/files/download（带 JWT query）；越出根 → ""
 *   （渲染为空 src，不加载）；无 ctx → ""。
 * - http(s)/data:image/锚点/绝对路径返回 undefined（不干预，交回安全过滤）。
 * 代码块/inline code/普通 Markdown 原文一律不动——AST 层天然免正则误伤。
 */
export function workspaceMarkdownUrlResolver(
  ctx: WorkspaceMarkdownContext | null,
): MarkdownUrlResolver {
  return (url, key) => {
    if (key !== "src" && key !== "srcSet") return undefined;
    const trimmed = url.trim();
    if (!trimmed) return "";
    // Workspace preview never automatically contacts a remote image host. Chat defaults unchanged.
    if (/^(?:https?:|\/\/)/i.test(trimmed)) return "";
    if (URL_SCHEME_OR_SPECIAL.test(trimmed)) return /^data:image\//i.test(trimmed) ? undefined : "";
    if (!ctx) return "";
    // 相对资源上的 ?/# 后缀对本地文件无意义，剥离后解析
    let clean = trimmed.split(/[?#]/, 1)[0];
    // mdast 里的 URL 是 percent-encoded（<my img.png> → my%20img.png），
    // 先解码再拼路径，避免 % 被二次编码；解码失败按原文处理。
    try {
      clean = decodeURIComponent(clean);
    } catch {
      /* malformed percent — keep raw */
    }
    const resolved = clean ? resolveWorkspaceRelativePath(ctx.baseDir, clean) : null;
    if (resolved == null) return "";
    return workspaceFileUrl(ctx.workspaceId, ctx.area, resolved);
  };
}

/**
 * 传给共享 Markdown 组件的固定安全 props。C 会为 Markdown 增加
 * allowRawHtml（默认 true 保持原消息行为）；这里显式 false。
 */
export const WORKSPACE_MARKDOWN_SAFE_PROPS = {
  allowRawHtml: false,
  allowCodePreview: false,
  isAnimating: false,
} as const;

/* ------------------------------------------------------------------ */
/* 设备目录直连（无 COW）                                                */
/*                                                                     */
/* 后端契约：/api/workspaces/{id}/device/*，Bearer 同源。                */
/* - mount 引用：files* 的 ?mount= 同时接受 mountId 或 alias。           */
/* - 错误：400 校验 / 403 权限 / 404 未找到 / 409 忙碌·未激活·source失效 */
/* - DELETE mounts/{id} 只删配置；授权只能 App 端发起。                   */
/* ------------------------------------------------------------------ */

/** A frozen GUI/picker view; strings keep explicit latest-view API calls compatible. */
export type DeviceMountRef = string | { id: string; viewToken?: string };
function deviceMountQuery(ref: DeviceMountRef): URLSearchParams {
  const query = new URLSearchParams({ mount: typeof ref === "string" ? ref : ref.id });
  if (typeof ref !== "string" && ref.viewToken) query.set("view", ref.viewToken);
  return query;
}

export interface WorkspaceDeviceMountPatch {
  alias?: string;
  sourceRelativePath?: string;
  enabled?: boolean;
}

function deviceFilesQuery(mountRef: DeviceMountRef, path: string, extra?: Record<string, string>): string {
  const params = new URLSearchParams({ mount: typeof mountRef === "string" ? mountRef : mountRef.id, path, ...extra });
  if (typeof mountRef !== "string" && mountRef.viewToken) params.set("view", mountRef.viewToken);
  return params.toString();
}

/** 设备文件下载 URL（直接用于 <a download>，会话 cookie 认证）。 */
export function workspaceDeviceFileUrl(
  workspaceId: string,
  mountRef: DeviceMountRef,
  path: string,
): string {
  return `/api/workspaces/${workspaceId}/device/files/download?${deviceFilesQuery(mountRef, path)}`;
}

/** alias 输入校验（与后端 WorkspaceDeviceDirectoryGuard.ALIAS_REGEX 一致）。 */
export function isValidDeviceMountAlias(alias: string): boolean {
  return /^[a-z][a-z0-9_-]{0,31}$/.test(alias);
}

/** mount 相对路径是否可提交（受控相对路径：非空、非绝对、无 .. 组件）。 */
/** Volume-relative source; the empty path connects the whole primary shared storage. */
export function isValidDeviceSourceRelativePath(path: string): boolean {
  const normalized = path.trim().replaceAll("\\", "/").replace(/^\/+|\/+$/g, "");
  return !normalized.split("/").some((part) => part === "..");
}

export const workspaceDeviceApi = {
  availability(workspaceId: string) {
    return api.get<import("@overlay/types/workspace").WorkspaceDeviceAvailability>(
      `workspaces/${workspaceId}/device/availability`,
    );
  },
  listMounts(workspaceId: string) {
    return api.get<import("@overlay/types/workspace").WorkspaceDeviceMount[]>(
      `workspaces/${workspaceId}/device/mounts`,
    );
  },
  browse(workspaceId: string, path: string) {
    const params = new URLSearchParams({ path });
    return api.get<import("@overlay/types/workspace").WorkspaceDeviceBrowseResponse>(
      `workspaces/${workspaceId}/device/browse?${params.toString()}`,
    );
  },
  createMount(
    workspaceId: string,
    body: { alias: string; sourceRelativePath: string },
  ) {
    return api.post<import("@overlay/types/workspace").WorkspaceDeviceMount>(
      `workspaces/${workspaceId}/device/mounts`,
      body,
    );
  },
  updateMount(workspaceId: string, mountId: string, patch: WorkspaceDeviceMountPatch) {
    return api.patch<import("@overlay/types/workspace").WorkspaceDeviceMount>(
      `workspaces/${workspaceId}/device/mounts/${mountId}`,
      patch,
    );
  },
  /** 只删连接配置，不删宿主目录。 */
  deleteMount(workspaceId: string, mountId: string) {
    return api.delete(`workspaces/${workspaceId}/device/mounts/${mountId}`);
  },
  listFiles(workspaceId: string, mountRef: DeviceMountRef, path: string) {
    return api.get<import("@overlay/types/workspace").WorkspaceDeviceFileListResponse>(
      `workspaces/${workspaceId}/device/files?${deviceFilesQuery(mountRef, path)}`,
    );
  },
  readFile(workspaceId: string, mountRef: DeviceMountRef, path: string) {
    return api.get<import("@overlay/types/workspace").WorkspaceDeviceFileContent>(
      `workspaces/${workspaceId}/device/files/content?${deviceFilesQuery(mountRef, path)}`,
    );
  },
  writeFile(workspaceId: string, mountRef: DeviceMountRef, path: string, content: string, expectedRevision?: string) {
    return api.put<WorkspaceFileEntry>(
      `workspaces/${workspaceId}/device/files/content?${deviceFilesQuery(mountRef, path)}`,
      { content, overwrite: true, ...(expectedRevision !== undefined ? { expectedRevision } : {}) },
      { retry: 0 },
    );
  },
  upload(workspaceId: string, mountRef: DeviceMountRef, path: string, file: File) {
    const form = new FormData();
    form.append("file", file, file.name);
    return api.postMultipart<WorkspaceFileEntry>(
      `workspaces/${workspaceId}/device/files/upload?${deviceFilesQuery(mountRef, path)}`,
      form,
    );
  },
  mkdir(workspaceId: string, mountRef: DeviceMountRef, path: string) {
    return api.post<WorkspaceFileEntry>(
      `workspaces/${workspaceId}/device/files/mkdir?${deviceFilesQuery(mountRef, path)}`,
      { path },
    );
  },
  createDirectory(workspaceId: string, mountRef: DeviceMountRef, path: string) {
    const request: CreateWorkspaceDirectoryRequest = { path, exclusive: true };
    return api.post<WorkspaceFileEntry>(
      `workspaces/${workspaceId}/device/files/mkdir?${deviceFilesQuery(mountRef, path)}`,
      request,
      { retry: 0 },
    );
  },
  moveFile(workspaceId: string, mountRef: DeviceMountRef, source: string, target: string) {
    return api.post<{ source: string; target: string }>(
      `workspaces/${workspaceId}/device/files/move?${deviceFilesQuery(mountRef, "")}`,
      { source, target, overwrite: false },
    );
  },
  deleteFile(workspaceId: string, mountRef: DeviceMountRef, path: string, recursive: boolean) {
    return api.delete(
      `workspaces/${workspaceId}/device/files?${deviceFilesQuery(mountRef, path, {
        recursive: String(recursive),
      })}`,
    );
  },
  batchDeleteFiles(workspaceId: string, mountRef: DeviceMountRef, paths: string[]) {
    const params = deviceMountQuery(mountRef);
    return api.post<WorkspaceBatchDeleteResult>(
      `workspaces/${workspaceId}/device/files/batch-delete?${params.toString()}`,
      { paths },
    );
  },
  /** Exclusive empty-file creation: conflicts are 409 / FILE_ALREADY_EXISTS. */
  createFile(workspaceId: string, mountRef: DeviceMountRef, path: string) {
    return api.put<WorkspaceFileEntry>(
      `workspaces/${workspaceId}/device/files/content?${deviceFilesQuery(mountRef, path)}`,
      { content: "", overwrite: false },
      { retry: 0 },
    );
  },
  /**
   * 文件夹导入的单文件端点：POST /device/files/import?mount&path
   * path 是 mount 内完整目标相对路径（受控，仅路径），body 为原始字节。
   * 与 multipart 上传等价但路径全由前端规划，不信任浏览器 filename。
   */
  async importFile(
    workspaceId: string,
    mountRef: DeviceMountRef,
    path: string,
    file: File,
    options: { signal?: AbortSignal } = {},
  ): Promise<void> {
    const params = deviceMountQuery(mountRef);
    params.set("path", path);
    const response = await fetch(
      `/api/workspaces/${workspaceId}/device/files/import?${params.toString()}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: file,
        signal: options.signal ?? null,
        credentials: "same-origin",
      },
    );
    if (!response.ok) {
      let detail = "";
      try {
        detail = (await response.text()).slice(0, 200);
      } catch {
        /* ignore */
      }
      throw new Error(detail || `HTTP ${response.status}`);
    }
  },
};

/* ------------------------------------------------------------------ */
/* 设备目录：文件夹导入 / ZIP 下载（纯函数可测）                          */
/* ------------------------------------------------------------------ */

/** 清洗 webkitRelativePath 的一段目录名；含分隔符/控制字符/.. 时返回 null。 */
export function sanitizeDevicePathSegment(segment: string): string | null {
  const clean = segment
    .replace(/[\\/]/g, "")
    .replace(/[\u0000-\u001F\u007F]/g, "")
    .trim();
  if (!clean || clean === "." || clean === "..") return null;
  return clean;
}

/**
 * webkitRelativePath（如 "photos/2024/a.jpg"）→ mount 内受控路径规划。
 * 只保留第一个根之下的相对目录 + basename；任何非法段返回 null（跳过该文件）。
 * 绝不把浏览器给的完整路径原样提交。
 */
export function deviceImportTargetPath(
  createdRoot: string,
  webkitRelativePath: string,
  fallbackName: string,
): string | null {
  const parts = webkitRelativePath.replaceAll("\\", "/").split("/").filter(Boolean);
  // 第一段是所选目录根名，跳过；末段是文件名
  const dirParts = parts.slice(1, -1);
  const filePart = parts.length > 0 ? parts[parts.length - 1] : fallbackName;
  const fileName = sanitizeDevicePathSegment(filePart);
  if (!fileName) return null;
  const dirs: string[] = [];
  for (const part of dirParts) {
    const clean = sanitizeDevicePathSegment(part);
    if (!clean) return null;
    dirs.push(clean);
  }
  return [createdRoot, ...dirs, fileName].join("/");
}

/** 文件夹导入的落地根名：rootName 被占用时自动递增 -N。 */
export function nextDeviceImportRootName(
  taken: ReadonlySet<string>,
  rootName: string,
): string {
  const base = sanitizeDevicePathSegment(rootName) ?? "imported";
  if (!taken.has(base)) return base;
  for (let i = 2; i < 100; i++) {
    const candidate = `${base}-${i}`;
    if (!taken.has(candidate)) return candidate;
  }
  return `${base}-${Date.now()}`;
}

/** 设备 ZIP 归档请求契约：POST /device/files/archive?mount= body {paths} */
export function buildDeviceArchiveRequest(
  workspaceId: string,
  mountRef: DeviceMountRef,
  paths: string[],
): { path: string; body: string } {
  const params = deviceMountQuery(mountRef);
  return {
    path: `/api/workspaces/${workspaceId}/device/files/archive?${params.toString()}`,
    body: JSON.stringify({ paths }),
  };
}

function deviceArchiveFallbackName(mountRef: DeviceMountRef): string {
  const stamp = new Date()
    .toISOString()
    .replaceAll(/[:.]/g, "-")
    .replace("T", "_")
    .slice(0, 19);
  const safe = (typeof mountRef === "string" ? mountRef : mountRef.id).replace(/[^\w.-]/g, "") || "device";
  return `${safe}-${stamp}.zip`;
}

/**
 * 下载设备目录所选文件为 zip。与 downloadWorkspaceArchive 同一套头部校验/
 * blob 上限，只是后端端点与 mount 参数不同。
 */
export async function downloadDeviceArchive(
  workspaceId: string,
  mountRef: DeviceMountRef,
  paths: string[],
  options: { signal?: AbortSignal } = {},
): Promise<{ blob: Blob; fileName: string }> {
  const req = buildDeviceArchiveRequest(workspaceId, mountRef, paths);
  const response = await fetch(req.path, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/zip" },
    body: req.body,
    signal: options.signal ?? null,
    credentials: "same-origin",
  });
  if (!response.ok) {
    let detail = "";
    try {
      detail = (await response.text()).slice(0, 200);
    } catch {
      /* ignore */
    }
    throw new WorkspaceArchiveError(
      detail || `HTTP ${response.status}`,
      "http",
      response.status,
    );
  }
  const { fileName } = inspectArchiveResponseHeaders(response.headers);
  const blob = await response.blob();
  if (blob.size > WORKSPACE_ARCHIVE_MAX_BYTES) {
    throw new WorkspaceArchiveError("archive exceeds client blob limit", "too_large");
  }
  return { blob, fileName: fileName ?? deviceArchiveFallbackName(mountRef) };
}

export { isAbortError as isDeviceAbortError };

const presetPath = (workspaceId: string) => `workspaces/${encodeURIComponent(workspaceId)}/subagent-presets`;
export const workspaceSubagentPresetsApi = {
  list: (workspaceId: string) => api.get<SubagentPresetsResponse>(presetPath(workspaceId)),
  templates: () => api.get<WorkspaceSubagentPreset[]>("subagent-presets/templates"),
  create: (workspaceId: string, draft: SubagentPresetDraft) =>
    api.post<WorkspaceSubagentPreset>(presetPath(workspaceId), draft),
  update: (workspaceId: string, presetId: string, draft: SubagentPresetDraft, expectedVersion: number) =>
    api.put<WorkspaceSubagentPreset>(`${presetPath(workspaceId)}/${encodeURIComponent(presetId)}`, { ...draft, expectedVersion }),
  delete: (workspaceId: string, presetId: string, expectedVersion: number) =>
    api.delete<void>(`${presetPath(workspaceId)}/${encodeURIComponent(presetId)}?expectedVersion=${expectedVersion}`),
  reorder: (workspaceId: string, request: { presetIds: string[]; expectedVersions: Record<string, number> }) =>
    api.post<WorkspaceSubagentPreset[]>(`${presetPath(workspaceId)}/order`, request),
  reset: (workspaceId: string) => api.post<SubagentPresetsResponse>(`${presetPath(workspaceId)}/reset`, { confirm: true }),
};
