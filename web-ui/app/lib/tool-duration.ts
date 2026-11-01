/** Shortest run worth showing; anything quicker is just noise next to the step title. */
export const TOOL_DURATION_MIN_MS = 100;

interface ToolTiming {
  startedAt?: string | null;
  finishedAt?: string | null;
}

/**
 * Kotlin writes instants with up to nanosecond digits; some browsers only parse milliseconds,
 * so cut the fraction to three digits before parsing.
 */
export function parseInstantMs(value: string | null | undefined): number | null {
  if (!value) return null;
  const ms = Date.parse(value.replace(/(\.\d{3})\d+/, "$1"));
  return Number.isFinite(ms) ? ms : null;
}

/**
 * How long a tool ran, or null when it was never timed (denied, answered, old messages) or was left
 * unfinished without running any more. A running tool counts up to [nowMs].
 */
export function toolDurationMs(tool: ToolTiming, nowMs: number, running: boolean): number | null {
  const startedAt = parseInstantMs(tool.startedAt);
  if (startedAt === null) return null;
  const finishedAt = parseInstantMs(tool.finishedAt);
  if (finishedAt !== null) return Math.max(0, finishedAt - startedAt);
  return running ? Math.max(0, nowMs - startedAt) : null;
}

/** Seconds with one decimal, like the app: `1.2s`. */
export function formatToolDuration(ms: number): string {
  return `${(Math.max(0, ms) / 1000).toFixed(1)}s`;
}
