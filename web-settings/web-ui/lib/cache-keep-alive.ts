/**
 * 全局缓存保活（claudelife）纯逻辑 + API helper。
 * 纯函数部分零外部依赖，node:test 可单测；API 部分走 `~/services/api` 的鉴权 header。
 * @see claudelife/src/main/java/me/rerere/ai/claudelife/CacheKeepAliveConfig.kt
 */

import api from "~/services/api";
import type { CacheKeepAliveConfig } from "@overlay/types/settings";

/** 服务端默认值对齐：间隔 270s（4:30），上限 30 分钟。 */
export const DEFAULT_CACHE_KEEP_ALIVE: CacheKeepAliveConfig = {
  enabled: false,
  intervalSeconds: 270,
  maxDurationMinutes: 30,
};

/** 会话 SSE `cache_warm` 事件的保活状态（nextWarmAt 已换算为本地时钟）。 */
export type CacheWarmState = {
  state: "inactive" | "scheduled" | "refreshing";
  nextWarmAt: number | null;
  serverTime: number;
};

export function fetchCacheKeepAlive(): Promise<CacheKeepAliveConfig> {
  return api.get<CacheKeepAliveConfig>("cache-keep-alive");
}

export function updateCacheKeepAlive(
  patch: Partial<CacheKeepAliveConfig>,
): Promise<CacheKeepAliveConfig> {
  return api.put<CacheKeepAliveConfig>("cache-keep-alive", patch);
}

const POSITIVE_INT_RE = /^\d+$/;
const INT32_MAX = 2_147_483_647;

/** 只接受纯数字正整数（trim 后），0 / 负数 / 小数 / 超 int32 → null。 */
export function parsePositiveInt(text: string): number | null {
  const trimmed = text.trim();
  if (!POSITIVE_INT_RE.test(trimmed)) return null;
  const value = Number(trimmed);
  if (!Number.isSafeInteger(value) || value <= 0 || value > INT32_MAX) return null;
  return value;
}

/**
 * 校验保活参数：间隔/上限必须是正整数，且 maxDurationMinutes * 60 > intervalSeconds。
 * 返回错误码；null = 合法。不限制上限大小。
 */
export function validateCacheKeepAlive(
  intervalSeconds: number,
  maxDurationMinutes: number,
): "interval" | "max" | "max_le_interval" | null {
  if (!Number.isInteger(intervalSeconds) || intervalSeconds <= 0) return "interval";
  if (!Number.isInteger(maxDurationMinutes) || maxDurationMinutes <= 0) return "max";
  if (maxDurationMinutes * 60 <= intervalSeconds) return "max_le_interval";
  return null;
}

/** 倒计时显示：向上取整秒数，负数归零。270000 → "🔥 4:30"。 */
export function formatCacheWarmCountdown(remainingMs: number): string {
  const seconds = Math.ceil(Math.max(0, remainingMs) / 1000);
  const minutes = Math.floor(seconds / 60);
  const rest = String(seconds % 60).padStart(2, "0");
  return `🔥 ${minutes}:${rest}`;
}
