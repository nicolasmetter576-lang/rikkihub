import type { TokenUsage, ToolApprovalState, UIMessagePart } from "~/types";

/**
 * Workspace subagent helpers.
 *
 * The backend publishes `SubagentMetadata` (camelCase JsonObject) onto
 * `UIMessagePart.Tool.metadata` while the run is live, and the same values
 * (snake_case) inside the tool output JSON and the persisted run log at
 * `/subagent_runs/{uuid}.json` (v2 schema keeps a full `messages` array plus a
 * legacy `transcript`). Old runs may point at `/tool_outputs/*.json`, which is
 * wiped on app start — treat missing/expired logs as a normal case.
 */

export interface SubagentMetadata {
  presetId?: string;
  presetKey?: string;
  presetName?: string;
  presetRevision?: number;
  title?: string;
  status?: string;
  summary?: string;
  toolsUsed: string[];
  editedPaths: string[];
  truncated?: boolean;
  logPath?: string;
  stepCount?: number;
  error?: string;
  modelId?: string;
  elapsedMs?: number;
  totalTokens?: number;
  startedAtEpochMs?: number;
  completedAtEpochMs?: number;
}

/** Entry of the legacy `transcript` array (pre-v2 logs / compatibility readers). */
export interface SubagentTranscriptEntry {
  role: string;
  text: string;
  reasoning?: string;
  tools: { name: string; input: string; output?: string }[];
}

/** One UIMessage-ish entry decoded from a v2 log `messages` array. */
export interface SubagentLogMessage {
  role: string;
  parts: UIMessagePart[];
  modelId?: string;
  usage?: TokenUsage;
}

export interface SubagentRunLog {
  presetId?: string;
  presetKey?: string;
  presetName?: string;
  presetRevision?: number;
  version?: number;
  prompt?: string;
  description?: string;
  context?: string;
  preamble?: string;
  status?: string;
  summary?: string;
  stepCount?: number;
  error?: string;
  modelId?: string;
  elapsedMs?: number;
  totalTokens?: number;
  startedAtEpochMs?: number;
  completedAtEpochMs?: number;
  toolsUsed: string[];
  editedPaths: string[];
  /** v2: full UIMessage list (reasoning / tool IO / usage preserved). */
  messages: SubagentLogMessage[];
  /** v1 fallback / compatibility view. */
  transcript: SubagentTranscriptEntry[];
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

function pickString(record: Record<string, unknown>, ...keys: string[]): string | undefined {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "string") return value;
  }
  return undefined;
}

function pickNumber(record: Record<string, unknown>, ...keys: string[]): number | undefined {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "number" && Number.isFinite(value)) return value;
  }
  return undefined;
}

function pickBoolean(record: Record<string, unknown>, ...keys: string[]): boolean | undefined {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "boolean") return value;
  }
  return undefined;
}

function pickStringArray(record: Record<string, unknown>, ...keys: string[]): string[] {
  for (const key of keys) {
    const value = record[key];
    if (Array.isArray(value)) {
      return value.filter((item): item is string => typeof item === "string");
    }
  }
  return [];
}

function defined(value: SubagentMetadata): SubagentMetadata | undefined {
  return Object.entries(value).some(
    ([key, v]) =>
      v !== undefined &&
      !(Array.isArray(v) && v.length === 0 && (key === "toolsUsed" || key === "editedPaths")),
  )
    ? value
    : undefined;
}

/** Decode a metadata/output object. Accepts camelCase (Tool.metadata) and snake_case (output JSON). */
export function parseSubagentMetadata(value: unknown): SubagentMetadata | undefined {
  const record = asRecord(value);
  if (!record) return undefined;
  const meta: SubagentMetadata = {
    presetId: pickString(record, "presetId", "preset_id"),
    presetKey: pickString(record, "presetKey", "preset_key"),
    presetName: pickString(record, "presetName", "preset_name"),
    presetRevision: pickNumber(record, "presetRevision", "preset_revision"),
    title: pickString(record, "title"),
    status: pickString(record, "status"),
    summary: pickString(record, "summary"),
    toolsUsed: pickStringArray(record, "toolsUsed", "tools_used"),
    editedPaths: pickStringArray(record, "editedPaths", "edited_paths"),
    truncated: pickBoolean(record, "truncated"),
    logPath: pickString(record, "logPath", "log_path"),
    stepCount: pickNumber(record, "stepCount", "step_count"),
    error: pickString(record, "error"),
    modelId: pickString(record, "modelId", "model_id"),
    elapsedMs: pickNumber(record, "elapsedMs", "elapsed_ms"),
    totalTokens: pickNumber(record, "totalTokens", "total_tokens"),
    startedAtEpochMs: pickNumber(record, "startedAtEpochMs", "started_at_epoch_ms"),
    completedAtEpochMs: pickNumber(record, "completedAtEpochMs", "completed_at_epoch_ms"),
  };
  return defined(meta);
}

/** Merge partial metadata sources; later entries override defined fields only. */
export function mergeSubagentMetadata(
  ...sources: (SubagentMetadata | undefined)[]
): SubagentMetadata | undefined {
  const out: SubagentMetadata = { toolsUsed: [], editedPaths: [] };
  let seen = false;
  for (const source of sources) {
    if (!source) continue;
    seen = true;
    for (const [key, value] of Object.entries(source)) {
      if (value === undefined) continue;
      (out as unknown as Record<string, unknown>)[key] = value;
    }
  }
  return seen ? out : undefined;
}

/** Does this record look like SubagentMetadata (used to detect renamed subagent tools). */
export function hasSubagentMetadataShape(value: unknown): boolean {
  const meta = parseSubagentMetadata(value);
  return Boolean(meta && (meta.logPath !== undefined || meta.stepCount !== undefined || meta.presetId !== undefined));
}

const RUNNING_STATUS = "running";

export function isSubagentRunningStatus(status: string | undefined): boolean {
  return status === RUNNING_STATUS;
}

/**
 * Validate a persisted-log location. Only `/subagent_runs/*.json` and the legacy
 * `/tool_outputs/*.json` (flat file names, no traversal) are allowed so log
 * metadata can never become an arbitrary fetch URL.
 */
export function isSubagentLogPath(path: string | undefined): path is string {
  if (!path) return false;
  const trimmed = path.trim();
  return /^\/?(subagent_runs|tool_outputs)\/[A-Za-z0-9_-]+\.json$/.test(trimmed);
}

/**
 * Resolve a validated log path to the `/api/files/path/...` endpoint.
 * Same-origin fetches of it carry the web session cookie.
 */
export function resolveSubagentLogUrl(path: string): string | null {
  if (!isSubagentLogPath(path)) return null;
  const relative = path.trim().replace(/^\/+/, "");
  const encoded = relative
    .split("/")
    .map((segment) => encodeURIComponent(segment))
    .join("/");
  return `/api/files/path/${encoded}`;
}

function parseUsage(value: unknown): TokenUsage | undefined {
  const record = asRecord(value);
  if (!record) return undefined;
  const usage: TokenUsage = {
    promptTokens: pickNumber(record, "promptTokens", "prompt_tokens") ?? 0,
    completionTokens: pickNumber(record, "completionTokens", "completion_tokens") ?? 0,
    cachedTokens: pickNumber(record, "cachedTokens", "cached_tokens") ?? 0,
    totalTokens: pickNumber(record, "totalTokens", "total_tokens") ?? 0,
  };
  return usage;
}

function parseApprovalState(value: unknown): ToolApprovalState {
  const record = asRecord(value);
  const type = record?.type;
  switch (type) {
    case "pending":
      return { type: "pending" };
    case "approved":
      return { type: "approved" };
    case "denied":
      return { type: "denied", reason: pickString(record ?? {}, "reason") ?? "" };
    case "answered":
      return { type: "answered", answer: pickString(record ?? {}, "answer") ?? "" };
    default:
      return { type: "auto" };
  }
}

/**
 * Normalize a raw JSON part (from the log `messages` array or nested tool
 * `output`) into a typed UIMessagePart. Unknown shapes are dropped (null).
 * Tool `output` parts are normalized non-recursively — nested tool parts are
 * rendered as data, never as a nested ToolPart tree.
 */
export function normalizeLogPart(value: unknown, depth = 0): UIMessagePart | null {
  const record = asRecord(value);
  if (!record) return null;
  const type = record.type;
  const metadata = asRecord(record.metadata) ?? null;

  switch (type) {
    case "text": {
      const text = pickString(record, "text");
      if (text === undefined) return null;
      return { type: "text", text, metadata };
    }
    case "image": {
      const url = pickString(record, "url");
      if (!url) return null;
      return { type: "image", url, metadata };
    }
    case "video": {
      const url = pickString(record, "url");
      if (!url) return null;
      return { type: "video", url, metadata };
    }
    case "audio": {
      const url = pickString(record, "url");
      if (!url) return null;
      return { type: "audio", url, metadata };
    }
    case "document": {
      const url = pickString(record, "url");
      if (!url) return null;
      return {
        type: "document",
        url,
        fileName: pickString(record, "fileName", "file_name") ?? "",
        mime: pickString(record, "mime") ?? "text/*",
        metadata,
      };
    }
    case "reasoning": {
      const reasoning = pickString(record, "reasoning");
      if (reasoning === undefined) return null;
      return {
        type: "reasoning",
        reasoning,
        createdAt: pickString(record, "createdAt", "created_at"),
        finishedAt: pickString(record, "finishedAt", "finished_at") ?? null,
        metadata,
      };
    }
    case "tool": {
      if (depth > 0) return null;
      const output = Array.isArray(record.output)
        ? record.output
            .map((part) => normalizeLogPart(part, depth + 1))
            .filter((part): part is UIMessagePart => part !== null)
        : [];
      return {
        type: "tool",
        toolCallId: pickString(record, "toolCallId", "tool_call_id") ?? "",
        toolName: pickString(record, "toolName", "tool_name") ?? "",
        input: pickString(record, "input") ?? "",
        output,
        approvalState: parseApprovalState(record.approvalState ?? record.approval_state),
        metadata,
      };
    }
    default:
      return null;
  }
}

function normalizeLogMessage(value: unknown): SubagentLogMessage | null {
  const record = asRecord(value);
  if (!record) return null;
  const role = pickString(record, "role");
  const rawParts = Array.isArray(record.parts) ? record.parts : [];
  const parts = rawParts
    .map((part) => normalizeLogPart(part))
    .filter((part): part is UIMessagePart => part !== null);
  if (!role && parts.length === 0) return null;
  return {
    role: role ?? "assistant",
    parts,
    modelId: pickString(record, "modelId", "model_id"),
    usage: parseUsage(record.usage),
  };
}

function normalizeTranscriptEntry(value: unknown): SubagentTranscriptEntry | null {
  const record = asRecord(value);
  if (!record) return null;
  const text = pickString(record, "text");
  if (text === undefined) return null;
  const tools = (Array.isArray(record.tools) ? record.tools : [])
    .map((tool) => {
      const toolRecord = asRecord(tool);
      if (!toolRecord) return null;
      const name = pickString(toolRecord, "name");
      const input = pickString(toolRecord, "input");
      if (!name || input === undefined) return null;
      const output = pickString(toolRecord, "output");
      return output === undefined ? { name, input } : { name, input, output };
    })
    .filter((tool): tool is NonNullable<typeof tool> => tool !== null);
  return {
    role: pickString(record, "role") ?? "",
    text,
    reasoning: pickString(record, "reasoning"),
    tools,
  };
}

/**
 * Parse the persisted subagent run log. Returns null for non-object JSON so the
 * caller can keep previous content / fall back instead of wiping the view.
 */
export function parseSubagentRunLog(value: unknown): SubagentRunLog | null {
  const record = asRecord(value);
  if (!record) return null;
  const messages = (Array.isArray(record.messages) ? record.messages : [])
    .map(normalizeLogMessage)
    .filter((message): message is SubagentLogMessage => message !== null);
  const transcript = (Array.isArray(record.transcript) ? record.transcript : [])
    .map(normalizeTranscriptEntry)
    .filter((entry): entry is SubagentTranscriptEntry => entry !== null);
  if (messages.length === 0 && transcript.length === 0 && record.version === undefined) {
    // Not a subagent log shape at all.
    if (!("status" in record || "prompt" in record)) return null;
  }
  return {
    presetId: pickString(record, "presetId", "preset_id"),
    presetKey: pickString(record, "presetKey", "preset_key"),
    presetName: pickString(record, "presetName", "preset_name"),
    presetRevision: pickNumber(record, "presetRevision", "preset_revision"),
    version: pickNumber(record, "version"),
    prompt: pickString(record, "prompt"),
    description: pickString(record, "description"),
    context: pickString(record, "context"),
    preamble: pickString(record, "preamble"),
    status: pickString(record, "status"),
    summary: pickString(record, "summary"),
    stepCount: pickNumber(record, "step_count", "stepCount"),
    error: pickString(record, "error"),
    modelId: pickString(record, "model_id", "modelId"),
    elapsedMs: pickNumber(record, "elapsed_ms", "elapsedMs"),
    totalTokens: pickNumber(record, "total_tokens", "totalTokens"),
    startedAtEpochMs: pickNumber(record, "started_at_epoch_ms", "startedAtEpochMs"),
    completedAtEpochMs: pickNumber(record, "completed_at_epoch_ms", "completedAtEpochMs"),
    toolsUsed: pickStringArray(record, "tools_used", "toolsUsed"),
    editedPaths: pickStringArray(record, "edited_paths", "editedPaths"),
    messages,
    transcript,
  };
}

/**
 * Live elapsed time for the title line: while running, prefer
 * `now - startedAtEpochMs` (tick-based); otherwise use the backend's elapsedMs.
 */
export function subagentElapsedMs(
  meta: SubagentMetadata | undefined,
  now: number,
  running = isSubagentRunningStatus(meta?.status),
): number | undefined {
  if (!meta) return undefined;
  if (
    running &&
    meta.status === RUNNING_STATUS &&
    meta.startedAtEpochMs !== undefined &&
    meta.completedAtEpochMs === undefined
  ) {
    return Math.max(0, now - meta.startedAtEpochMs, meta.elapsedMs ?? 0);
  }
  if (meta.elapsedMs !== undefined) return Math.max(0, meta.elapsedMs);
  if (meta.startedAtEpochMs !== undefined && meta.completedAtEpochMs !== undefined) {
    return Math.max(0, meta.completedAtEpochMs - meta.startedAtEpochMs);
  }
  return undefined;
}

/** Matches the App header: elapsed whole seconds and one-decimal k tok. */
export function formatElapsedSeconds(ms: number | undefined): string {
  if (ms === undefined || !Number.isFinite(ms)) return "— s";
  return `${Math.floor(Math.max(0, ms) / 1000)} s`;
}

export function formatTokenCount(total: number | undefined): string {
  if (total === undefined || !Number.isFinite(total) || total < 0) return "— k tok";
  return `${(total / 1000).toFixed(1)} k tok`;
}
