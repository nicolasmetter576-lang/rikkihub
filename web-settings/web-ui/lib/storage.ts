/**
 * 存储页 API 封装。类型与 web-settings `StorageWebDto.kt` 严格对齐。
 * 鉴权：同源请求自动携带 Web 会话 cookie。
 */

import api from "~/services/api";
import type { Options } from "ky";

export interface StorageFileDto {
  id: number;
  displayName: string;
  mimeType: string;
  sizeBytes: number;
  createdAt: number;
  existsOnDisk: boolean;
  /** wire 字段名是 "protected"。 */
  protected: boolean;
  protectionReasons: string[];
  leaseExpiresAt: number | null;
}

export interface StorageFileListResponse {
  files: StorageFileDto[];
  total: number;
  totalSizeBytes: number;
  overallTotal: number;
  overallSizeBytes: number;
  offset: number;
  limit: number;
  hasMore: boolean;
}

export interface StorageDeleteResult {
  status: "deleted" | "skipped" | "failed" | string;
  id?: number | null;
  reasons?: string[];
  bytesFreed?: number;
  alreadyMissing?: boolean;
  error?: string | null;
}

export interface StorageCleanupRequest {
  ids?: number[];
  olderThanDays?: number;
}

export interface StorageCleanupResponse {
  scanned: number;
  deletedBytes: number;
  items: StorageDeleteResult[];
  truncated?: boolean;
}

export interface StorageLeaseRenewRequest {
  urls: string[];
}

export interface StorageLeaseRenewResponse {
  renewed: number;
  rejected: string[];
  leaseTtlMillis: number;
}

export interface StorageListParams {
  offset?: number;
  limit?: number;
  query?: string;
  /** 服务端过滤：只列早于该天数的文件。 */
  olderThanDays?: number;
}

function toSearchParams(params: StorageListParams): Record<string, string | number> {
  const out: Record<string, string | number> = {};
  if (params.offset != null) out.offset = params.offset;
  if (params.limit != null) out.limit = params.limit;
  if (params.query != null && params.query.trim() !== "") out.query = params.query.trim();
  if (params.olderThanDays != null) out.olderThanDays = params.olderThanDays;
  return out;
}

export const storageApi = {
  list(params: StorageListParams = {}, options?: Options): Promise<StorageFileListResponse> {
    return api.get<StorageFileListResponse>("storage/files", {
      ...options,
      searchParams: toSearchParams(params),
    });
  },

  /**
   * 逐项删除。服务端语义：受保护 409 + {status:"skipped", reasons[]}；
   * 未知/越界 404；删除失败 500。ky 会把非 2xx 抛成 ApiError（code=409/404/500）。
   */
  delete(id: number, options?: Options): Promise<StorageDeleteResult> {
    return api.delete<StorageDeleteResult>(`storage/files/${id}`, options);
  },

  cleanup(request: StorageCleanupRequest, options?: Options): Promise<StorageCleanupResponse> {
    return api.post<StorageCleanupResponse>("storage/cleanup", request, options);
  },

  renewLeases(urls: readonly string[], options?: Options): Promise<StorageLeaseRenewResponse> {
    return api.post<StorageLeaseRenewResponse>("storage/leases", { urls: [...urls] }, options);
  },
};

/**
 * 预览/下载 URL：直接用于 <img>/<a>，会话 cookie 随同源请求携带。
 */
export function storagePreviewUrl(id: number): string {
  return `/api/storage/files/${id}/preview`;
}

export function storageDownloadUrl(id: number): string {
  return `/api/storage/files/${id}/download`;
}

/** 服务端预览白名单（与 StorageRoutes 一致）：仅这些 mime 有 preview。 */
const PREVIEWABLE_MIMES = new Set(["image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp"]);

export function isPreviewable(mimeType: string): boolean {
  return PREVIEWABLE_MIMES.has(mimeType.toLowerCase());
}
