/**
 * 存储页纯逻辑（零外部依赖，node:test 可单测）。
 * 注意：这里只认服务端保护结果，不做“无引用即可删”的乐观假设——
 * 未列出保护原因只表示“当前未检出引用”，活跃草稿/24h 新文件仍受服务端保护。
 */

import { isLocalAttachmentUrl } from "./draft-attachments";

/** 服务端保护原因 wire 值（与 StorageProtectionReason 对齐）。 */
export const STORAGE_PROTECTION_REASONS = [
  "referenced_db",
  "referenced_session",
  "referenced_settings",
  "leased",
  "new_file",
  "check_failed",
  "retained",
] as const;

export type StorageProtectionReason = (typeof STORAGE_PROTECTION_REASONS)[number];

export function isStorageProtectionReason(value: string): value is StorageProtectionReason {
  return (STORAGE_PROTECTION_REASONS as readonly string[]).includes(value);
}

export interface StorageFileLike {
  id: number;
  protected?: boolean;
  protectionReasons?: string[];
}

/** 某条目是否受保护（protected 标志或任一原因存在都算）。 */
export function isProtectedFile(file: StorageFileLike): boolean {
  return file.protected === true || (file.protectionReasons?.length ?? 0) > 0;
}

/**
 * 从草稿 part 的 url 字段中挑出可以续租的本地附件引用。
 * 过滤规则复用 `~/lib/files.ts#resolveFileUrl` 的“本地文件”分支
 * （`./draft-attachments.ts#isLocalAttachmentUrl`）：data:/blob:/http(s):/mailto:/tel: 剔除；
 * file:// 与裸相对路径保留。绝不把文本内容当 URL 上传。
 */
export function collectRenewableLeaseRefs(
  urls: readonly string[],
  max = 200,
): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of urls) {
    if (typeof raw !== "string") continue;
    const url = raw.trim();
    if (url.length === 0 || !isLocalAttachmentUrl(url)) continue;
    if (seen.has(url)) continue;
    seen.add(url);
    out.push(url);
    if (out.length >= max) break; // 与后端 STORAGE_MAX_LEASE_URLS 对齐
  }
  return out;
}

export interface CleanupItemLike {
  status: string;
  id?: number | null;
  bytesFreed?: number;
  reasons?: string[];
  error?: string | null;
}

export interface CleanupSummary {
  deleted: number;
  skipped: number;
  failed: number;
  deletedBytes: number;
}

export function summarizeCleanupItems(items: readonly CleanupItemLike[]): CleanupSummary {
  const summary: CleanupSummary = { deleted: 0, skipped: 0, failed: 0, deletedBytes: 0 };
  for (const item of items) {
    if (item.status === "deleted") {
      summary.deleted += 1;
      summary.deletedBytes += item.bytesFreed ?? 0;
    } else if (item.status === "skipped") {
      summary.skipped += 1;
    } else {
      summary.failed += 1;
    }
  }
  return summary;
}

/** 大小格式化（与 App 的 fileSizeToString 同口径：1024 进制，保留 1 位小数）。 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "0 B";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

/** epoch millis → 本地时间文本；非法值返回 "-"。 */
export function formatEpochMillis(epochMillis: number | null | undefined): string {
  if (epochMillis == null || !Number.isFinite(epochMillis) || epochMillis <= 0) return "-";
  const date = new Date(epochMillis);
  if (Number.isNaN(date.getTime())) return "-";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 分页拉取时 hasMore 的本地兜底判定（与后端 `offset + files.size < total` 一致）。
 * 便于测试和 UI 无 hasMore 字段时的退化处理。
 */
export function hasMoreStoragePage(offset: number, loaded: number, total: number): boolean {
  return offset + loaded < total;
}
