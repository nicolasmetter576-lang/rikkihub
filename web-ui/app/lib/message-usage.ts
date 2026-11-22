import type { MessageDto, TokenUsage } from "~/types";

export type UsageMessage = Pick<
  MessageDto,
  "usage" | "lastUsage" | "usageComplete" | "stepCount" | "generationTimeMs" | "createdAt" | "finishedAt"
>;

/** Only legacy messages may use their aggregate as a compatibility snapshot. */
export function lastRequestUsage(message: Pick<UsageMessage, "usage" | "lastUsage" | "usageComplete">): TokenUsage | null {
  return message.usageComplete == null
    ? (message.lastUsage ?? message.usage ?? null)
    : (message.lastUsage ?? null);
}

export function contextTokensOf(usage: TokenUsage | null): number | null {
  if (!usage || !validTokenCount(usage.promptTokens) || !validTokenCount(usage.completionTokens)) return null;
  const tokens = usage.promptTokens + usage.completionTokens;
  // Keep the same representable range as the app's Int context helper, without wrapping.
  return tokens <= 2_147_483_647 ? tokens : null;
}

function validTokenCount(value: number): boolean {
  return Number.isSafeInteger(value) && value >= 0;
}

/** Without field-presence history, a zero in partial/legacy totals may mean unreported. */
export function reportedTokenCount(value: number, usageComplete: boolean | null | undefined): number | null {
  return validTokenCount(value) && (value > 0 || usageComplete === true) ? value : null;
}

/** Device-local ISO dates use wall-clock arithmetic, like Kotlin LocalDateTime, not browser DST. */
function timestamp(value: string | null | undefined): { millis: number; subMillisNanos: number } | null {
  if (!value) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?$/.exec(value);
  if (!match) return null;
  const [, y, m, d, h, min, sec = "0", fraction = "", zone = "Z"] = match;
  const year = Number(y), month = Number(m), day = Number(d);
  const calendar = new Date(0);
  calendar.setUTCFullYear(year, month - 1, day);
  if (month < 1 || month > 12 || day < 1 || calendar.getUTCMonth() !== month - 1 ||
      Number(h) > 23 || Number(min) > 59 || Number(sec) > 59) return null;
  const normalized = `${y}-${m}-${d}T${h}:${min}:${sec.padStart(2, "0")}.${fraction.padEnd(3, "0").slice(0, 3)}${zone}`;
  const millis = Date.parse(normalized);
  return Number.isFinite(millis) ? { millis, subMillisNanos: Number(fraction.padEnd(9, "0").slice(3)) } : null;
}

/** Pure numeric values and display eligibility. Never depends on the viewing time. */
export function messageUsageStats(message: UsageMessage) {
  const lastUsage = lastRequestUsage(message);
  const contextTokens = contextTokensOf(lastUsage);
  const finished = timestamp(message.finishedAt);
  let durationMs: number | null = null;
  let estimated = false;
  if (finished !== null) {
    if (message.generationTimeMs != null) {
      // Explicit zero/invalid exact timings do not become a legacy wall-clock estimate.
      if (Number.isFinite(message.generationTimeMs) && message.generationTimeMs > 0) {
        durationMs = message.generationTimeMs;
      }
    } else if (message.usageComplete == null) {
      const created = timestamp(message.createdAt);
      if (created !== null) {
        // Match Duration.between(...).toMillis(): truncate the interval, not each endpoint.
        const elapsed = finished.millis - created.millis - (finished.subMillisNanos < created.subMillisNanos ? 1 : 0);
        if (elapsed > 0) {
          durationMs = elapsed;
          estimated = true;
        }
      }
    }
  }
  const outputTokens = message.usage
    ? reportedTokenCount(message.usage.completionTokens, message.usageComplete)
    : null;
  const rate = durationMs !== null && outputTokens !== null && outputTokens > 0 && message.usageComplete !== false
    ? outputTokens / durationMs * 1000
    : null;
  return {
    lastUsage,
    contextTokens,
    outputTokens,
    steps: Number.isSafeInteger(message.stepCount) && message.stepCount! > 0 ? message.stepCount! : 1,
    durationMs,
    estimated,
    tps: rate !== null && Number.isFinite(rate) && rate > 0 ? rate : null,
    usageComplete: message.usageComplete ?? null,
  };
}
