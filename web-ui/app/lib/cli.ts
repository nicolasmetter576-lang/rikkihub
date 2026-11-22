import api, { ApiError } from "~/services/api";
import type {
  CliPermissionMode,
  CliRuntimeEvent,
  ConversationCliSession,
  CustomCliAgentConfig,
  ToolPart,
} from "~/types";

export const EVENT_CLI_RUNTIME = "cli_runtime";
export const WEB_CLI_CONTROL_FORBIDDEN_CODE = "WEB_CLI_CONTROL_FORBIDDEN";

export const CLI_LOCAL_COMMANDS = [
  "/model",
  "/mode",
  "/effort",
  "/thinking",
  "/status",
  "/restart",
  "/login",
] as const;

export type CliLocalCommand = (typeof CLI_LOCAL_COMMANDS)[number];

export type CliParsedInput =
  | { kind: "local"; command: CliLocalCommand }
  | { kind: "shell"; command: string }
  | { kind: "prompt" };

/**
 * Mirrors the App's `CliInput.parse`: detects local `/commands` (exact trimmed match)
 * and `!shell` commands, while passing `/compact` and all other text through as prompts.
 */
export function parseCliInput(text: string): CliParsedInput {
  const trimmed = text.trim();
  if (trimmed.startsWith("!") && trimmed.length > 1) {
    return { kind: "shell", command: trimmed.slice(1).trim() };
  }
  if ((CLI_LOCAL_COMMANDS as readonly string[]).includes(trimmed)) {
    return { kind: "local", command: trimmed as CliLocalCommand };
  }
  return { kind: "prompt" };
}

/**
 * True when a CLI chat input should be blocked on Web instead of sent to the agent.
 * `!cmd` and `/restart` are supported via Web CLI control endpoints; `/compact` is sent as a prompt.
 */
export function isUnsupportedWebCliInput(text: string): boolean {
  const parsed = parseCliInput(text);
  return parsed.kind === "local" && parsed.command !== "/restart";
}

export function isCliControlForbiddenError(error: unknown): boolean {
  return error instanceof ApiError && error.errorCode === WEB_CLI_CONTROL_FORBIDDEN_CODE;
}

export interface UpdateConversationCliRequest {
  agentId?: string | null;
  permissionMode?: "ask" | "auto";
  modelId?: string | null;
  modeId?: string | null;
}

export async function updateConversationCli(
  conversationId: string,
  payload: UpdateConversationCliRequest,
): Promise<void> {
  await api.post(`conversations/${conversationId}/cli`, payload);
}

export async function restartConversationCli(conversationId: string): Promise<void> {
  await api.post(`conversations/${conversationId}/cli/restart`);
}

export async function runConversationCliShell(
  conversationId: string,
  command: string,
): Promise<void> {
  await api.post(`conversations/${conversationId}/cli/shell`, { command });
}

export interface CliSessionDisplayState {
  session: ConversationCliSession;
  busy: boolean;
  usedTokens: number | null;
  contextTokens: number | null;
  title: string | null;
}

/**
 * Merges persisted `ConversationCliSession` with live `cli_runtime` SSE updates
 * when the runtime event is active for `conversationId`.
 */
export function resolveCliSessionDisplay(
  cliSession: ConversationCliSession,
  runtime: CliRuntimeEvent | null | undefined,
  conversationId?: string | null,
): CliSessionDisplayState {
  const matchesActive =
    Boolean(runtime?.active) &&
    Boolean(conversationId) &&
    runtime?.conversationId === conversationId;
  if (!matchesActive || !runtime) {
    return {
      session: cliSession,
      busy: false,
      usedTokens: null,
      contextTokens: null,
      title: null,
    };
  }
  return {
    session: {
      ...cliSession,
      modelId: runtime.currentModelId ?? cliSession.modelId,
      modeId: runtime.currentModeId ?? cliSession.modeId,
    },
    busy: Boolean(runtime.busy),
    usedTokens: runtime.usedTokens ?? null,
    contextTokens: runtime.contextTokens ?? null,
    title: runtime.title ?? null,
  };
}

export const BUILTIN_CLI_AGENTS: Record<string, string> = {
  "builtin:agy": "Antigravity",
  "builtin:droid": "Droid",
};

export function resolveCliAgentName(
  agentId: string,
  customCliAgents?: ReadonlyArray<CustomCliAgentConfig> | null,
): string {
  const builtIn = BUILTIN_CLI_AGENTS[agentId];
  if (builtIn) return builtIn;
  const custom = customCliAgents?.find((agent) => agent.id === agentId);
  if (custom) {
    const name = custom.name?.trim();
    if (name) return name;
    const executable = custom.executable?.trim();
    if (executable) return executable;
  }
  return agentId;
}

export function normalizeCliPermissionMode(mode: CliPermissionMode | string | null | undefined): "ASK" | "AUTO" {
  return String(mode ?? "ask").toLowerCase() === "auto" ? "AUTO" : "ASK";
}

export function formatCliSessionSummary(
  cliSession: ConversationCliSession,
  customCliAgents?: ReadonlyArray<CustomCliAgentConfig> | null,
): string {
  const agentName = resolveCliAgentName(cliSession.agentId, customCliAgents);
  const modelId = cliSession.modelId?.trim() || null;
  const permissionMode = normalizeCliPermissionMode(cliSession.permissionMode);
  return [agentName, modelId, permissionMode].filter(Boolean).join(" · ");
}

export interface AcpDiffContent {
  type: "diff";
  path: string;
  oldText?: string | null;
  newText?: string | null;
  diff?: string | null;
}

export interface AcpToolMetadata {
  name?: string;
  kind?: string;
  title?: string;
  status?: string;
  local?: boolean;
  locations?: unknown[];
  content?: unknown[];
}

export interface ParsedAcpDiff {
  path: string;
  diff: string;
}

const ACP_TOOL_PREFIX = "acp_";

export function parseAcpMetadata(part: Pick<ToolPart, "metadata">): AcpToolMetadata | null {
  const meta = part.metadata;
  if (!meta || typeof meta !== "object" || Array.isArray(meta)) return null;
  const raw = (meta as Record<string, unknown>).acp;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  return raw as AcpToolMetadata;
}

export function isAcpToolPart(part: Pick<ToolPart, "toolName" | "metadata">): boolean {
  return part.toolName.startsWith(ACP_TOOL_PREFIX) || parseAcpMetadata(part) !== null;
}

/** Collapses line breaks in `acp.title`; falls back to a readable tool name when absent. */
export function getAcpToolTitle(
  part: Pick<ToolPart, "toolName" | "metadata">,
  acp: AcpToolMetadata | null = parseAcpMetadata(part),
): string {
  const rawTitle = typeof acp?.title === "string" ? acp.title.replace(/[\r\n]+/g, " ").trim() : "";
  if (rawTitle.length > 0) return rawTitle;
  const stripped = part.toolName.startsWith(ACP_TOOL_PREFIX)
    ? part.toolName.slice(ACP_TOOL_PREFIX.length)
    : part.toolName;
  const normalized = stripped.replace(/_/g, " ").trim();
  return normalized || part.toolName;
}

function splitLines(text: string): string[] {
  if (text.length === 0) return [];
  return text.replace(/\r\n/g, "\n").split("\n");
}

interface DiffOp {
  type: "eq" | "del" | "add";
  line: string;
  oldPos: number;
  newPos: number;
}

const MAX_LCS_CELLS = 250_000;

function computeLineOps(oldLines: string[], newLines: string[]): DiffOp[] {
  let prefix = 0;
  const minLen = Math.min(oldLines.length, newLines.length);
  while (prefix < minLen && oldLines[prefix] === newLines[prefix]) {
    prefix += 1;
  }

  let suffix = 0;
  while (
    suffix < oldLines.length - prefix &&
    suffix < newLines.length - prefix &&
    oldLines[oldLines.length - 1 - suffix] === newLines[newLines.length - 1 - suffix]
  ) {
    suffix += 1;
  }

  const ops: DiffOp[] = [];
  for (let i = 0; i < prefix; i += 1) {
    ops.push({ type: "eq", line: oldLines[i], oldPos: i, newPos: i });
  }

  const midOld = oldLines.slice(prefix, oldLines.length - suffix);
  const midNew = newLines.slice(prefix, newLines.length - suffix);
  const m = midOld.length;
  const n = midNew.length;

  if (m === 0 && n === 0) {
    return ops;
  }

  if (m === 0) {
    for (let j = 0; j < n; j += 1) {
      ops.push({ type: "add", line: midNew[j], oldPos: prefix, newPos: prefix + j });
    }
  } else if (n === 0) {
    for (let i = 0; i < m; i += 1) {
      ops.push({ type: "del", line: midOld[i], oldPos: prefix + i, newPos: prefix });
    }
  } else if (m * n <= MAX_LCS_CELLS) {
    const stride = n + 1;
    const dp = new Int32Array((m + 1) * stride);
    for (let i = m - 1; i >= 0; i -= 1) {
      const rowOffset = i * stride;
      const nextRowOffset = (i + 1) * stride;
      for (let j = n - 1; j >= 0; j -= 1) {
        if (midOld[i] === midNew[j]) {
          dp[rowOffset + j] = dp[nextRowOffset + j + 1] + 1;
        } else {
          const down = dp[nextRowOffset + j];
          const right = dp[rowOffset + j + 1];
          dp[rowOffset + j] = down >= right ? down : right;
        }
      }
    }

    let i = 0;
    let j = 0;
    while (i < m && j < n) {
      if (midOld[i] === midNew[j]) {
        ops.push({ type: "eq", line: midOld[i], oldPos: prefix + i, newPos: prefix + j });
        i += 1;
        j += 1;
      } else if (dp[(i + 1) * stride + j] >= dp[i * stride + j + 1]) {
        ops.push({ type: "del", line: midOld[i], oldPos: prefix + i, newPos: prefix + j });
        i += 1;
      } else {
        ops.push({ type: "add", line: midNew[j], oldPos: prefix + i, newPos: prefix + j });
        j += 1;
      }
    }
    while (i < m) {
      ops.push({ type: "del", line: midOld[i], oldPos: prefix + i, newPos: prefix + j });
      i += 1;
    }
    while (j < n) {
      ops.push({ type: "add", line: midNew[j], oldPos: prefix + i, newPos: prefix + j });
      j += 1;
    }
  } else {
    for (let i = 0; i < m; i += 1) {
      ops.push({ type: "del", line: midOld[i], oldPos: prefix + i, newPos: prefix });
    }
    for (let j = 0; j < n; j += 1) {
      ops.push({ type: "add", line: midNew[j], oldPos: prefix + m, newPos: prefix + j });
    }
  }

  const tailOldStart = oldLines.length - suffix;
  const tailNewStart = newLines.length - suffix;
  for (let k = 0; k < suffix; k += 1) {
    ops.push({
      type: "eq",
      line: oldLines[tailOldStart + k],
      oldPos: tailOldStart + k,
      newPos: tailNewStart + k,
    });
  }
  return ops;
}

/**
 * Generates a unified diff between `oldText` and `newText` for `path`, or `null` if identical.
 */
export function generateUnifiedDiff(
  oldText: string,
  newText: string,
  path: string,
  contextLines = 3,
): string | null {
  if (oldText === newText) return null;
  const oldLines = splitLines(oldText);
  const newLines = splitLines(newText);
  const ops = computeLineOps(oldLines, newLines);

  const changeIndices: number[] = [];
  for (let idx = 0; idx < ops.length; idx += 1) {
    if (ops[idx].type !== "eq") changeIndices.push(idx);
  }
  if (changeIndices.length === 0) return null;

  const hunks: Array<{ start: number; end: number }> = [];
  for (const idx of changeIndices) {
    const start = Math.max(0, idx - contextLines);
    const end = Math.min(ops.length - 1, idx + contextLines);
    const prev = hunks[hunks.length - 1];
    if (prev && start <= prev.end + 1) {
      prev.end = end;
    } else {
      hunks.push({ start, end });
    }
  }

  const out = [`--- a/${path}`, `+++ b/${path}`];
  for (const hunk of hunks) {
    let oldCount = 0;
    let newCount = 0;
    const body: string[] = [];
    for (let idx = hunk.start; idx <= hunk.end; idx += 1) {
      const op = ops[idx];
      if (op.type === "eq") {
        oldCount += 1;
        newCount += 1;
        body.push(` ${op.line}`);
      } else if (op.type === "del") {
        oldCount += 1;
        body.push(`-${op.line}`);
      } else {
        newCount += 1;
        body.push(`+${op.line}`);
      }
    }
    const first = ops[hunk.start];
    const oldStart = oldCount === 0 ? first.oldPos : first.oldPos + 1;
    const newStart = newCount === 0 ? first.newPos : first.newPos + 1;
    out.push(`@@ -${oldStart},${oldCount} +${newStart},${newCount} @@`);
    out.push(...body);
  }
  return out.join("\n");
}

/** Extracts unified diffs from `acp.content` items with `type === "diff"`. */
export function extractAcpDiffs(acp: AcpToolMetadata | null): ParsedAcpDiff[] {
  if (!acp || !Array.isArray(acp.content)) return [];
  const diffs: ParsedAcpDiff[] = [];
  for (const item of acp.content) {
    if (!item || typeof item !== "object" || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    if (record.type !== "diff") continue;
    const path = typeof record.path === "string" ? record.path : "";
    if (!path) continue;
    if (typeof record.diff === "string" && record.diff.trim().length > 0) {
      diffs.push({ path, diff: record.diff });
      continue;
    }
    const oldText = typeof record.oldText === "string" ? record.oldText : "";
    const newText = typeof record.newText === "string" ? record.newText : "";
    const generated = generateUnifiedDiff(oldText, newText, path);
    if (generated) {
      diffs.push({ path, diff: generated });
    }
  }
  return diffs;
}
