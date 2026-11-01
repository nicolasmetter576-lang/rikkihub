/**
 * 再造（工作区文本编辑器）：SSE 客户端、选区接口、配置读写与替换校验。
 * 选区规整与扩展、多轮消息、提示词、输出解析都在服务端（与 App 共用 Kotlin 代码）；服务端不存会话，
 * 每次请求都带上会话开始时的全文与选区、从原文到底稿的各轮和本轮。
 * @see web-settings/src/main/java/me/rerere/rikkihub/web/routes/ZaizaoRoutes.kt
 */

import api, { ApiError } from "~/services/api";
import { createTranslatorSseParser } from "@overlay/lib/translator";

export type ZaizaoType = "MODIFY" | "INCREMENT" | "REWRITE";
export const ZAIZAO_TYPES: readonly ZaizaoType[] = ["MODIFY", "INCREMENT", "REWRITE"];
/** 与 Zaizao.MAX_SELECTION_CHARS 一致。 */
export const ZAIZAO_MAX_SELECTION_CHARS = 5000;
/** 与 ZaizaoConfig.MAX_REQUIREMENT_CHARS 一致。 */
export const ZAIZAO_MAX_REQUIREMENT_CHARS = 2000;
/** 与 Zaizao.MAX_ROUNDS 一致：从原文到底稿一条线上最多这么多轮。 */
export const ZAIZAO_MAX_ROUNDS = 20;
export const ZAIZAO_REASONING_LEVELS = ["off", "auto", "low", "medium", "high", "xhigh", "max"] as const;
export type ZaizaoReasoningLevel = (typeof ZAIZAO_REASONING_LEVELS)[number];

/** 从原文到底稿那条线上已完成的一轮：要求和模型的完整回复。 */
export interface ZaizaoTurn {
  type: ZaizaoType;
  requirement: string;
  output: string;
}

export interface ZaizaoRequest {
  fileName: string;
  source: string;
  start: number;
  end: number;
  includeAfter?: boolean;
  history?: ZaizaoTurn[];
  type: ZaizaoType;
  requirement: string;
  /** 这一轮已经给过的稿（换一版）。 */
  avoid?: string[];
  /** false = 不记进最近用过的要求（换一版、重试）。 */
  record?: boolean;
}

export interface ZaizaoRange {
  start: number;
  end: number;
  original: string;
  format: "MARKDOWN" | "PLAIN";
}

export type ZaizaoEvent =
  | ({ type: "prepared" } & ZaizaoRange)
  | { type: "update"; reasoning: string; plan: string; result: string }
  | { type: "done"; reasoning: string; plan: string; result: string; output: string }
  | { type: "error"; code: string; message: string };

export interface ZaizaoConfig {
  modelId: string | null;
  modelMissing: boolean;
  reasoningLevel: ZaizaoReasoningLevel;
  temperature: number | null;
  topP: number | null;
  maxTokens: number | null;
  includeAfter: boolean;
  defaultType: ZaizaoType;
  /** 自定义的三个类型提示词；null = 内置默认。 */
  prompts: Partial<Record<ZaizaoType, string | null>>;
  defaultPrompts: Partial<Record<ZaizaoType, string>>;
  recentRequirements: string[];
  extensions: string[];
  maxRequirementChars: number;
  maxPromptChars: number;
  maxSelectionChars: number;
  maxRounds: number;
  maxRecent: number;
}

export interface ZaizaoConfigPatch {
  modelId?: string | null;
  reasoningLevel?: ZaizaoReasoningLevel;
  temperature?: number | null;
  topP?: number | null;
  maxTokens?: number | null;
  includeAfter?: boolean;
  defaultType?: ZaizaoType;
  prompts?: Partial<Record<ZaizaoType, string | null>>;
  recentRequirements?: string[];
}

export function fetchZaizaoConfig(): Promise<ZaizaoConfig> {
  return api.get<ZaizaoConfig>("zaizao/config");
}

export function updateZaizaoConfig(patch: ZaizaoConfigPatch): Promise<ZaizaoConfig> {
  return api.put<ZaizaoConfig>("zaizao/config", patch);
}

/** 第一轮之前：规整选区，[expand] 时扩到整句 / 整段（服务端规则）。选区空或太长时抛错。 */
export function fetchZaizaoRange(body: {
  fileName: string;
  source: string;
  start: number;
  end: number;
  expand?: "SENTENCE" | "PARAGRAPH";
}): Promise<ZaizaoRange> {
  return api.post<ZaizaoRange>("zaizao/range", body);
}

/** 记下一条要求（与 ZaizaoConfig.remember 一致）：去两端空白，挪到最前，最多 [max] 条。 */
export function zaizaoRemember(recent: readonly string[], requirement: string, max = 10): string[] {
  const text = requirement.trim();
  if (!text) return [...recent];
  return [text, ...recent.filter(item => item !== text)].slice(0, max);
}

/** 替换前校验：文档在 [start, end) 处仍是原文才替换；返回 CodeMirror change，原文已变返回 null。 */
export function zaizaoChange(
  doc: string,
  range: { start: number; end: number; original: string },
  text: string,
): { from: number; to: number; insert: string } | null {
  if (range.end > doc.length || doc.slice(range.start, range.end) !== range.original) return null;
  return { from: range.start, to: range.end, insert: text };
}

const str = (value: unknown): string => (typeof value === "string" ? value : "");

/** 解析一个 SSE 事件的 data；未知事件 / 非法 JSON 返回 undefined。 */
export function parseZaizaoEvent(event: string, data: string): ZaizaoEvent | undefined {
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>;
    switch (event) {
      case "prepared":
        if (typeof parsed.start !== "number" || typeof parsed.end !== "number") return undefined;
        return {
          type: "prepared",
          start: parsed.start,
          end: parsed.end,
          original: str(parsed.original),
          format: parsed.format === "PLAIN" ? "PLAIN" : "MARKDOWN",
        };
      case "update":
        return { type: "update", reasoning: str(parsed.reasoning), plan: str(parsed.plan), result: str(parsed.result) };
      case "done":
        return typeof parsed.result === "string" && parsed.result
          ? { type: "done", reasoning: str(parsed.reasoning), plan: str(parsed.plan), result: parsed.result, output: str(parsed.output) }
          : undefined;
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

export interface StreamZaizaoOptions {
  signal?: AbortSignal;
  /** 测试注入用；默认全局 fetch。 */
  fetchImpl?: typeof fetch;
}

/**
 * POST /api/zaizao 的 SSE 读取。事件逐个交给 onEvent；HTTP 错误（如 400 要求为空）与网络错误转成 error 事件
 * （code=HTTP_<status> / NETWORK）。abort 会断开连接，服务端随之取消生成（abort 后不再发事件）。
 */
export async function streamZaizao(
  request: ZaizaoRequest,
  onEvent: (event: ZaizaoEvent) => void,
  options: StreamZaizaoOptions = {},
): Promise<void> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const aborted = (error: unknown) =>
    options.signal?.aborted || (error instanceof Error && error.name === "AbortError");
  let response: Response;
  try {
    response = await fetchImpl("/api/zaizao", {
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
      const event = parseZaizaoEvent(block.event, block.data);
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
    if (!finished && !options.signal?.aborted) onEvent({ type: "error", code: "NETWORK", message: "Stream ended early" });
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
