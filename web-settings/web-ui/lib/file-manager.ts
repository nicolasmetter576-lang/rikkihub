import api, { ApiError } from "~/services/api";
import type { UploadedFileDto } from "~/types/dto";
import type {
  WorkspaceExtractResult,
  WorkspaceFileEntry,
  WorkspaceTransferResponse,
} from "@overlay/types/workspace";
import { archiveFilenameFromDisposition } from "@overlay/lib/workspace";

/** `library` or `device:<volumeId>`, as the API takes it. */
export type FileManagerRoot = string;

export const LIBRARY_ROOT: FileManagerRoot = "library";

export const deviceRoot = (volumeId: string): FileManagerRoot => `device:${volumeId}`;

export interface FileManagerVolume {
  id: string;
  label: string;
  primary: boolean;
}

export interface FileManagerRoots {
  /** READY / PERMISSION_REQUIRED (grant it in the App) / PASSWORD_REQUIRED (web access password is off). */
  deviceAccess: "READY" | "PERMISSION_REQUIRED" | "PASSWORD_REQUIRED";
  volumes: FileManagerVolume[];
}

export interface FileManagerDeleteResult {
  deletedPaths: string[];
  failures: { path: string; deleted: boolean; error?: string | null }[];
}

export interface FileManagerAttachResult {
  files: UploadedFileDto[];
  unsupported: string[];
  failed: string[];
}

export type FileManagerSort = "name" | "time" | "size";

const query = (params: Record<string, string>) => new URLSearchParams(params).toString();

export const fileManagerApi = {
  roots: () => api.get<FileManagerRoots>("file-manager/roots"),
  list: (root: FileManagerRoot, path: string) =>
    api.get<{ entries: WorkspaceFileEntry[] }>(`file-manager/list?${query({ root, path })}`).then((r) => r.entries),
  mkdir: (root: FileManagerRoot, path: string) => api.post<WorkspaceFileEntry>("file-manager/mkdir", { root, path }),
  createFile: (root: FileManagerRoot, path: string) =>
    api.post<WorkspaceFileEntry>("file-manager/create-file", { root, path }),
  rename: (root: FileManagerRoot, path: string, name: string) =>
    api.post<WorkspaceFileEntry>("file-manager/rename", { root, path, name }),
  delete: (root: FileManagerRoot, paths: string[]) =>
    api.post<FileManagerDeleteResult>("file-manager/delete", { root, paths }),
  transfer: (from: FileManagerRoot, paths: string[], to: FileManagerRoot, targetDir: string, mode: "copy" | "move") =>
    api.post<WorkspaceTransferResponse>("file-manager/transfer", { from, paths, to, targetDir, mode }),
  zip: (root: FileManagerRoot, dir: string, paths: string[], name: string) =>
    api.post<WorkspaceFileEntry>("file-manager/zip", { root, dir, paths, name }),
  extract: (root: FileManagerRoot, path: string, password: string | null, nameEncoding: string | null) =>
    api.post<WorkspaceExtractResult>("file-manager/extract", {
      root,
      path,
      ...(password ? { password } : {}),
      ...(nameEncoding ? { nameEncoding } : {}),
    }),
  readText: (root: FileManagerRoot, path: string) =>
    api.get<{ content: string; revision: string }>(`file-manager/text?${query({ root, path })}`),
  writeText: (root: FileManagerRoot, path: string, content: string, revision: string) =>
    api.put<{ revision: string }>("file-manager/text", { root, path, content, revision }),
  upload: (root: FileManagerRoot, dir: string, files: File[]) => {
    const form = new FormData();
    files.forEach((file) => form.append("files", file, file.name));
    return api.postMultipart<{ entries: WorkspaceFileEntry[] }>(`file-manager/upload?${query({ root, path: dir })}`, form);
  },
  attach: (root: FileManagerRoot, paths: string[]) =>
    api.post<FileManagerAttachResult>("file-manager/attach", { root, paths }),
};

/** URL of one file for <img>/<a>; the web session cookie authenticates it. */
export function fileManagerFileUrl(root: FileManagerRoot, path: string, download = false): string {
  return `/api/file-manager/file?${query({ root, path, ...(download ? { download: "1" } : {}) })}`;
}

const fileManagerErrors: Record<string, string> = {
  FILE_MANAGER_READ_ONLY_FOLDER: "workspaces_read_only",
  FILE_PERMISSION_DENIED: "device_permission_required",
};

/** i18n key (fileManager namespace) of a failed operation, by error code only; undefined for codes without a text of their own. */
export function fileManagerErrorKey(error: unknown): string | undefined {
  const code = error && typeof error === "object" && "errorCode" in error ? error.errorCode : undefined;
  return typeof code === "string" && Object.hasOwn(fileManagerErrors, code) ? fileManagerErrors[code] : undefined;
}

/** Downloads several items (folders included) as one zip. */
export async function downloadFileManagerZip(root: FileManagerRoot, paths: string[], name: string): Promise<{ blob: Blob; fileName: string }> {
  const response = await fetch("/api/file-manager/download-zip", {
    method: "POST",
    // JSON too: a refusal comes back as a JSON error body, which the server cannot send to an Accept of zip only (406).
    headers: { "Content-Type": "application/json", Accept: "application/zip, application/json" },
    body: JSON.stringify({ root, paths, name }),
    credentials: "same-origin",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(body.error || `HTTP ${response.status}`, response.status, body.errorCode);
  }
  return {
    blob: await response.blob(),
    fileName: archiveFilenameFromDisposition(response.headers.get("Content-Disposition")) ?? name,
  };
}

/** Folders first, then by the chosen order; hidden (dot) files only when asked for. */
export function visibleEntries(entries: WorkspaceFileEntry[], sort: FileManagerSort, showHidden: boolean): WorkspaceFileEntry[] {
  const shown = showHidden ? entries : entries.filter((entry) => !entry.name.startsWith("."));
  const order = (a: WorkspaceFileEntry, b: WorkspaceFileEntry) => {
    switch (sort) {
      case "time":
        return b.updatedAt - a.updatedAt || a.name.localeCompare(b.name);
      case "size":
        return b.sizeBytes - a.sizeBytes || a.name.localeCompare(b.name);
      default:
        return a.name.localeCompare(b.name);
    }
  };
  return [...shown].sort((a, b) => (a.isDirectory === b.isDirectory ? order(a, b) : a.isDirectory ? -1 : 1));
}

/** Breadcrumb targets of [path]: "" first, then each ancestor down to [path]. */
export function pathCrumbs(path: string): { label: string; path: string }[] {
  const segments = path ? path.split("/") : [];
  return segments.map((label, index) => ({ label, path: segments.slice(0, index + 1).join("/") }));
}

/** Android hides other apps' Android/data and Android/obb even with all files access. */
export function isRestrictedDevicePath(root: FileManagerRoot, path: string): boolean {
  if (!root.startsWith("device:")) return false;
  const lower = path.toLowerCase();
  return ["android/data", "android/obb"].some((dir) => lower === dir || lower.startsWith(`${dir}/`));
}
