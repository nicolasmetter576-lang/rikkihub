/**
 * 统计页 API 封装与格式化。类型与 `UsageStatsService.kt` / `StatsRoutes.kt` 对齐。
 */

import api from "~/services/api";

export type UsageRange = "D7" | "D30" | "D90" | "ALL";

export const USAGE_RANGES: Array<{ value: UsageRange; query: string }> = [
  { value: "D7", query: "7d" },
  { value: "D30", query: "30d" },
  { value: "D90", query: "90d" },
  { value: "ALL", query: "all" },
];


export interface UsageAmount {
  requests: number;
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
  cost: number;
  pricedTokens: number;
}

export interface UsageBucketStat {
  bucket: string;
  amount: UsageAmount;
}

export interface UsageModelStat {
  /** Grouping key of the usage records; sent back to delete them. */
  key: string;
  modelId: string | null;
  name: string;
  providerName: string | null;
  priced: boolean;
  amount: UsageAmount;
}

export interface UsageSourceStat {
  source: string;
  amount: UsageAmount;
}

export type UsageProviderKind = "DEVIN" | "ANTIGRAVITY" | "CODEX" | "OTHER" | "UNKNOWN" | "DELETED";

export interface QuotaMeasure {
  perFull: number;
  variation: number | null;
  remaining?: number | null;
}

export interface QuotaEstimate {
  window: string;
  models: string[];
  accounts: number;
  remaining: number;
  resetAt: number | null;
  observedDrop: number;
  segments: number;
  enoughData: boolean;
  tokens: QuotaMeasure | null;
  cost: QuotaMeasure | null;
  requests: QuotaMeasure | null;
  likelyUnit: "tokens" | "cost" | "requests" | null;
  stale: boolean;
  boundaryUncertain: boolean;
}

export interface UsageProviderStat {
  providerId: string | null;
  name: string;
  kind: UsageProviderKind;
  amount: UsageAmount;
  models: UsageModelStat[];
  quotas: QuotaEstimate[];
}

export interface UsageStatsReport {
  range: UsageRange;
  bucketUnit: "day" | "month";
  total: UsageAmount;
  trend: UsageBucketStat[];
  models: UsageModelStat[];
  sources: UsageSourceStat[];
  providers: UsageProviderStat[];
}

export interface StatsOverview {
  conversations: number;
  messages: number;
  launchCount: number;
  requestsPerDay: Record<string, number>;
}

export interface StatsResponse {
  report: UsageStatsReport;
  overview: StatsOverview;
}

/** 后端 kotlinx 序列化默认不输出默认值，缺的字段补齐 */
const EMPTY_AMOUNT: UsageAmount = {
  requests: 0,
  promptTokens: 0,
  completionTokens: 0,
  cachedTokens: 0,
  totalTokens: 0,
  cost: 0,
  pricedTokens: 0,
};

export function normalizeAmount(amount: Partial<UsageAmount> | undefined | null): UsageAmount {
  return { ...EMPTY_AMOUNT, ...amount };
}

function normalizeQuota(q: Partial<QuotaEstimate>): QuotaEstimate {
  return {
    window: q.window ?? "",
    models: q.models ?? [],
    accounts: q.accounts ?? 0,
    remaining: q.remaining ?? 0,
    resetAt: q.resetAt ?? null,
    observedDrop: q.observedDrop ?? 0,
    segments: q.segments ?? 0,
    enoughData: q.enoughData ?? false,
    tokens: q.tokens ?? null,
    cost: q.cost ?? null,
    requests: q.requests ?? null,
    likelyUnit: q.likelyUnit ?? null,
    stale: q.stale ?? false,
    boundaryUncertain: q.boundaryUncertain ?? false,
  };
}

function normalizeModel(m: Partial<UsageModelStat>): UsageModelStat {
  return {
    key: m.key ?? "",
    modelId: m.modelId ?? null,
    name: m.name ?? "",
    providerName: m.providerName ?? null,
    priced: m.priced ?? false,
    amount: normalizeAmount(m.amount),
  };
}

export function normalizeReport(raw: Partial<UsageStatsReport>): UsageStatsReport {
  return {
    range: raw.range ?? "D30",
    bucketUnit: raw.bucketUnit ?? "day",
    total: normalizeAmount(raw.total),
    trend: (raw.trend ?? []).map((b) => ({ bucket: b.bucket, amount: normalizeAmount(b.amount) })),
    models: (raw.models ?? []).map(normalizeModel),
    sources: (raw.sources ?? []).map((s) => ({ source: s.source, amount: normalizeAmount(s.amount) })),
    providers: (raw.providers ?? []).map((p) => ({
      providerId: p.providerId ?? null,
      name: p.name ?? "",
      kind: p.kind ?? "OTHER",
      amount: normalizeAmount(p.amount),
      models: (p.models ?? []).map(normalizeModel),
      quotas: (p.quotas ?? []).map(normalizeQuota),
    })),
  };
}

export const statsApi = {
  async get(range: UsageRange, signal?: AbortSignal, refreshQuota = true): Promise<StatsResponse> {
    const query = USAGE_RANGES.find((r) => r.value === range)?.query ?? "30d";
    const res = await api.get<{ report: Partial<UsageStatsReport>; overview: Partial<StatsOverview> }>("stats", {
      searchParams: { range: query, refreshQuota: String(refreshQuota) },
      signal,
    });
    return {
      report: normalizeReport(res.report ?? {}),
      overview: {
        conversations: res.overview?.conversations ?? 0,
        messages: res.overview?.messages ?? 0,
        launchCount: res.overview?.launchCount ?? 0,
        requestsPerDay: res.overview?.requestsPerDay ?? {},
      },
    };
  },
  deleteModel(key: string) {
    return api.delete(`stats/models/${encodeURIComponent(key)}`);
  },
  deleteProvider(providerId: string | null) {
    return api.delete(`stats/providers/${encodeURIComponent(providerId ?? "unknown")}`);
  },
};

// ---- 格式化（与 App 统计页一致） ----

// Java Formatter rounds positive halves up; Number.toFixed can round 1.15 down.
const decimalFormats = [0, 1, 2].map((digits) => new Intl.NumberFormat("en-US", {
  minimumFractionDigits: digits, maximumFractionDigits: digits, useGrouping: false,
}));
function fixed(value: number, digits: number): string {
  return decimalFormats[digits].format(value);
}

export function formatCount(count: number): string {
  if (count >= 1_000_000) return `${fixed(count / 1_000_000, 1)}M`;
  if (count >= 1_000) return `${fixed(count / 1_000, 1)}K`;
  return String(Math.round(count));
}

export function formatTokens(count: number): string {
  if (count >= 1_000_000_000) return `${fixed(count / 1_000_000_000, 2)}B`;
  if (count >= 1_000_000) return `${fixed(count / 1_000_000, 2)}M`;
  if (count >= 1_000) return `${fixed(count / 1_000, 1)}K`;
  return String(Math.round(count));
}

/** Costs are always US dollars. */
export function formatCost(value: number): string {
  if (value <= 0) return "$0";
  if (value < 0.01) return "<$0.01";
  if (value < 100) return `$${fixed(value, 2)}`;
  return `$${Math.round(value).toLocaleString("en-US")}`;
}

export function formatPercent(value: number | null): string {
  if (value === null || !Number.isFinite(value)) return "—";
  if (value <= 0) return "0%";
  if (value < 0.1) return `${fixed(value * 100, 1)}%`;
  return `${fixed(value * 100, 0)}%`;
}

export function cacheHitRate(amount: UsageAmount): number | null {
  if (!Number.isFinite(amount.promptTokens) || !Number.isFinite(amount.cachedTokens) ||
    amount.promptTokens < 0 || amount.cachedTokens < 0 || amount.cachedTokens > amount.promptTokens) return null;
  return amount.promptTokens > 0 ? amount.cachedTokens / amount.promptTokens : 0;
}

export function isFullyPriced(amount: UsageAmount): boolean {
  return amount.promptTokens + amount.completionTokens > 0 &&
    amount.pricedTokens >= amount.promptTokens + amount.completionTokens;
}

/** 剩余量按所有账号合计 */
export function quotaLeft(quota: QuotaEstimate, measure: QuotaMeasure | null): number | null {
  if (!measure || quota.stale) return null;
  return measure.remaining ?? null;
}

export const SOURCE_KEYS = [
  "CHAT",
  "COMPRESSION",
  "TITLE",
  "SUGGESTION",
  "TRANSLATION",
  "MEMORY",
  "OCR",
  "POLISH",
  "TUIQIAO",
  "ZAIZAO",
  "BOOK",
  "SUBAGENT",
  "KEEPALIVE",
  "QUBAGU",
] as const;

function localKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

/** 53 local-calendar weeks, Sunday first. Calendar arithmetic also works across DST. */
export function buildHeatmapWeeks(today: Date): Array<Array<{ key: string; future: boolean }>> {
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - today.getDay() - 52 * 7);
  const todayKey = localKey(today);
  let future = false;
  return Array.from({ length: 53 }, (_, week) => Array.from({ length: 7 }, (_, day) => {
    const key = localKey(new Date(start.getFullYear(), start.getMonth(), start.getDate() + week * 7 + day));
    const result = { key, future };
    if (key === todayKey) future = true;
    return result;
  }));
}
