/**
 * 推敲（工作区文本编辑器）：SSE 客户端、配置读写、文件类型判断与替换校验。
 * 上下文截取、档位判定、角色分配、提示词、解析都在服务端（与 App 共用 Kotlin 代码）。
 * @see web-settings/src/main/java/me/rerere/rikkihub/web/routes/TuiqiaoRoutes.kt
 */

import api, { ApiError } from "~/services/api";
import { createTranslatorSseParser } from "@overlay/lib/translator";

export type TuiqiaoMode = "WORD" | "SENTENCE" | "PARAGRAPH";
export const TUIQIAO_MODES: readonly TuiqiaoMode[] = ["WORD", "SENTENCE", "PARAGRAPH"];
export type TuiqiaoExplanation = "OFF" | "ONE_LINE" | "DETAILED";
export const TUIQIAO_EXPLANATIONS: readonly TuiqiaoExplanation[] = ["OFF", "ONE_LINE", "DETAILED"];
/** 与 Tuiqiao.MAX_SELECTION_CHARS 一致：超过就不发请求。 */
export const TUIQIAO_MAX_SELECTION_CHARS = 3000;

/** 与 TuiqiaoConfig.DEFAULT_EXTENSIONS 一致：设置里 extensions 为 null 时用它。 */
export const TUIQIAO_DEFAULT_EXTENSIONS: readonly string[] = [
  "txt", "text", "md", "markdown", "rst", "org", "adoc", "asciidoc", "tex", "fountain", "srt", "vtt",
];

export interface TuiqiaoRequest {
  fileName: string;
  source: string;
  start: number;
  end: number;
  mode?: TuiqiaoMode;
  /** Grow the range to the whole sentence / paragraph on the server first (mode re-detected). */
  expand?: "SENTENCE" | "PARAGRAPH";
  /** Re-run only this slot. */
  slot?: string;
}

/** 一个候选位：推敲角色（出 1 条，卡片标签是角色名）或常规提示词（slot = "default"）。 */
export interface TuiqiaoSlot {
  slot: string;
  kind: "role" | "default";
  roleId?: string | null;
  name?: string | null;
}

export interface TuiqiaoPrepared {
  start: number;
  end: number;
  original: string;
  mode: TuiqiaoMode;
  format?: "MARKDOWN" | "PLAIN";
  slots?: TuiqiaoSlot[];
}

export interface TuiqiaoCandidate {
  text: string;
  tone: string;
  why: string;
}

export type TuiqiaoEvent =
  | ({ type: "prepared" } & TuiqiaoPrepared)
  | ({ type: "candidate"; slot: string } & TuiqiaoCandidate)
  | { type: "slot_error"; slot: string; code: string; message: string }
  | { type: "done" }
  | { type: "error"; code: string; message: string };

export interface TuiqiaoRole {
  id: string;
  name: string;
  prompt: string;
  modes: TuiqiaoMode[];
  modelId: string | null;
  enabled: boolean;
  /** 指定的模型已不存在，实际用助手模型。 */
  modelMissing?: boolean;
}

export interface TuiqiaoConfig {
  candidateCount: number;
  explanation: TuiqiaoExplanation;
  roles: TuiqiaoRole[];
  extensions: string[];
  extensionsCustomized: boolean;
  /** 自定义的三档提示词；null = 内置默认。 */
  prompts: Partial<Record<TuiqiaoMode, string | null>>;
  defaultPrompts: Partial<Record<TuiqiaoMode, string>>;
  defaultExtensions: string[];
  availableExtensions: string[];
  minCandidates: number;
  maxCandidates: number;
  maxRoles: number;
  maxRoleNameChars: number;
  maxRolePromptChars: number;
}

export interface TuiqiaoConfigPatch {
  candidateCount?: number;
  explanation?: TuiqiaoExplanation;
  /** 整表替换；新角色可不带 id。 */
  roles?: Array<Omit<TuiqiaoRole, "id" | "modelMissing"> & { id?: string }>;
  /** null = 恢复默认清单。 */
  extensions?: string[] | null;
  prompts?: Partial<Record<TuiqiaoMode, string | null>>;
}

export function fetchTuiqiaoConfig(): Promise<TuiqiaoConfig> {
  return api.get<TuiqiaoConfig>("tuiqiao/config");
}

export function updateTuiqiaoConfig(patch: TuiqiaoConfigPatch): Promise<TuiqiaoConfig> {
  return api.put<TuiqiaoConfig>("tuiqiao/config", patch);
}

/** 这个文件能否推敲：扩展名在设置的清单里（未设置时用默认清单）。 */
export function tuiqiaoEnabledFor(fileName: string | undefined, extensions?: readonly string[] | null): boolean {
  if (!fileName) return false;
  const base = fileName.slice(fileName.lastIndexOf("/") + 1);
  const dot = base.lastIndexOf(".");
  if (dot < 0) return false;
  return (extensions ?? TUIQIAO_DEFAULT_EXTENSIONS).includes(base.slice(dot + 1).toLowerCase());
}

/** 角色的保存形式（去掉只读的 modelMissing）。 */
export function tuiqiaoRolesPatch(roles: TuiqiaoRole[]): NonNullable<TuiqiaoConfigPatch["roles"]> {
  return roles.map(({ modelMissing: _missing, ...role }) => role);
}

/** 选区能否推敲：去掉两端空白后非空且不超长（与服务端规整一致）。 */
export function tuiqiaoSelectionUsable(source: string, from: number, to: number): boolean {
  if (from < 0 || to > source.length || from >= to) return false;
  const trimmed = source.slice(from, to).trim();
  return trimmed.length > 0 && trimmed.length <= TUIQIAO_MAX_SELECTION_CHARS;
}

/**
 * 替换前校验：文档在 [start, end) 处仍是当初的原文才替换。
 * 返回 CodeMirror change；原文已变返回 null（不替换）。
 */
export function tuiqiaoChange(
  doc: string,
  prepared: Pick<TuiqiaoPrepared, "start" | "end" | "original">,
  text: string,
): { from: number; to: number; insert: string } | null {
  if (prepared.end > doc.length || doc.slice(prepared.start, prepared.end) !== prepared.original) return null;
  return { from: prepared.start, to: prepared.end, insert: text };
}

const str = (value: unknown): string => (typeof value === "string" ? value : "");

/** 解析一个 SSE 事件的 data；未知事件 / 非法 JSON 返回 undefined。 */
export function parseTuiqiaoEvent(event: string, data: string): TuiqiaoEvent | undefined {
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>;
    switch (event) {
      case "prepared": {
        const mode = parsed.mode as TuiqiaoMode;
        if (typeof parsed.start !== "number" || typeof parsed.end !== "number" || !TUIQIAO_MODES.includes(mode)) return undefined;
        const slots = Array.isArray(parsed.slots)
          ? (parsed.slots as Array<Record<string, unknown>>).filter(s => s && typeof s.slot === "string").map(s => ({
            slot: s.slot as string,
            kind: s.kind === "role" ? "role" as const : "default" as const,
            roleId: typeof s.roleId === "string" ? s.roleId : null,
            name: typeof s.name === "string" ? s.name : null,
          }))
          : [{ slot: "default", kind: "default" as const }];
        const format = parsed.format === "PLAIN" ? "PLAIN" as const : "MARKDOWN" as const;
        return { type: "prepared", start: parsed.start, end: parsed.end, original: str(parsed.original), mode, format, slots };
      }
      case "candidate":
        return typeof parsed.text === "string" && parsed.text
          ? { type: "candidate", slot: str(parsed.slot) || "default", text: parsed.text, tone: str(parsed.tone), why: str(parsed.why) }
          : undefined;
      case "slot_error":
        return typeof parsed.slot === "string"
          ? { type: "slot_error", slot: parsed.slot, code: str(parsed.code) || "MODEL_ERROR", message: str(parsed.message) }
          : undefined;
      case "done":
        return { type: "done" };
      case "error":
        return { type: "error", code: str(parsed.code) || "MODEL_ERROR", message: str(parsed.message) };
      default:
        return undefined;
    }
  } catch {
    return undefined;
  }
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const data = (await response.json()) as { error?: unknown };
    if (typeof data.error === "string" && data.error.length > 0) return data.error;
  } catch {
    // ignore body parse errors
  }
  return `HTTP ${response.status}`;
}

export interface StreamTuiqiaoOptions {
  signal?: AbortSignal;
  /** 测试注入用；默认全局 fetch。 */
  fetchImpl?: typeof fetch;
}

/**
 * POST /api/tuiqiao 的 SSE 读取。事件逐个交给 onEvent；HTTP 错误（如 400 选区为空）与网络错误
 * 转成 error 事件（code=HTTP_<status> / NETWORK）。abort 会断开连接，服务端随之取消生成。
 */
export async function streamTuiqiao(
  request: TuiqiaoRequest,
  onEvent: (event: TuiqiaoEvent) => void,
  options: StreamTuiqiaoOptions = {},
): Promise<void> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const aborted = (error: unknown) =>
    options.signal?.aborted || (error instanceof Error && error.name === "AbortError");
  let response: Response;
  try {
    response = await fetchImpl("/api/tuiqiao", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify(request),
      signal: options.signal ?? null,
    });
  } catch (error) {
    if (!aborted(error)) onEvent({ type: "error", code: "NETWORK", message: error instanceof Error ? error.message : String(error) });
    return;
  }
  if (!response.ok) {
    const message = await readErrorMessage(response);
    if (response.status === 401 && typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent("rikkihub:web-auth-required", { detail: { message, code: 401 } }));
    }
    onEvent({ type: "error", code: `HTTP_${response.status}`, message });
    return;
  }
  const body = response.body;
  if (!body) {
    onEvent({ type: "error", code: "NETWORK", message: new ApiError("Response body is not readable", 0).message });
    return;
  }
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = createTranslatorSseParser();
  let finished = false;
  const emit = (text: string) => {
    for (const block of parser.feed(text)) {
      const event = parseTuiqiaoEvent(block.event, block.data);
      if (!event) continue;
      if (event.type === "done" || event.type === "error") finished = true;
      onEvent(event);
    }
  };
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) emit(decoder.decode(value, { stream: true }));
    }
    emit(decoder.decode());
    if (!finished) onEvent({ type: "error", code: "NETWORK", message: "Stream ended early" });
  } catch (error) {
    if (!aborted(error)) onEvent({ type: "error", code: "NETWORK", message: error instanceof Error ? error.message : String(error) });
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // ignore
    }
  }
}
