/**
 * 独立翻译页（非会话）客户端。
 * 契约见 /tmp/rikkihub-web-six/TRANSLATOR-CONTRACT.md：
 *   POST /api/translator/translate  {text, language, modelId?}  ->  text/event-stream
 *   事件： text{ "text" }(累计快照) / done{ "text" } / error{ "message" }，注释行为心跳。
 *
 * 认证：同源请求自动携带 Web 会话 cookie；401 时 dispatch rikkihub:web-auth-required（与 api.ts 一致）。
 */

import api, { ApiError } from "~/services/api";
import type { Settings } from "@overlay/types/settings";

export const TRANSLATOR_MAX_TEXT_BYTES = 32 * 1024;
export const TRANSLATOR_MAX_LANGUAGE_TAG_LENGTH = 64;

const WEB_AUTH_REQUIRED_EVENT = "rikkihub:web-auth-required";

// ---------- 请求 / 事件类型（与 TranslatorRoutes.kt 的 DTO 一一对应） ----------

export interface TranslatorTranslateRequest {
  text: string;
  language: string;
  /** ProviderModel.id（UUID）；省略时使用全局 translateModeId。 */
  modelId?: string;
}

export type TranslatorStreamEvent =
  | { type: "text"; text: string }
  | { type: "done"; text: string }
  | { type: "error"; message: string };

export interface TranslatorStreamCallbacks {
  onEvent: (event: TranslatorStreamEvent) => void;
  /** 网络层 / HTTP 层错误（非 SSE error 事件）。 */
  onError?: (error: Error) => void;
  onOpen?: () => void;
  /** 流结束（正常 / abort / error 事件后）都会调用，且只调用一次。 */
  onClose?: () => void;
}

// ---------- 纯 helpers（node:test 可测） ----------

/** BCP-47 tag 校验（与后端 Locale.Builder 同源的宽松校验：结构合法且有语言子tag）。 */
export function isValidBcp47Tag(tag: string): boolean {
  const value = tag.trim();
  if (value.length === 0 || value.length > TRANSLATOR_MAX_LANGUAGE_TAG_LENGTH) return false;
  try {
    const locale = new Intl.Locale(value);
    return Boolean(locale.language);
  } catch {
    return false;
  }
}

/** UTF-8 字节数（服务端按字节限 32KiB，前端预检一致）。 */
export function translatorTextByteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

export function isTranslatorTextAcceptable(text: string): boolean {
  return text.trim().length > 0 && translatorTextByteLength(text) <= TRANSLATOR_MAX_TEXT_BYTES;
}

/** 解析一个 SSE 事件的 data JSON；未知事件 / 非法 JSON 返回 undefined。 */
export function parseTranslatorEventData(event: string, data: string): TranslatorStreamEvent | undefined {
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>;
    switch (event) {
      case "text":
        return typeof parsed.text === "string" ? { type: "text", text: parsed.text } : undefined;
      case "done":
        return typeof parsed.text === "string" ? { type: "done", text: parsed.text } : undefined;
      case "error":
        return typeof parsed.message === "string"
          ? { type: "error", message: parsed.message }
          : { type: "error", message: "" };
      default:
        return undefined;
    }
  } catch {
    return undefined;
  }
}

export interface ParsedSseBlock {
  event: string;
  data: string;
}

/**
 * 增量 SSE 解析器：feed() 吃进（已解码的）字符串片段，吐出完整事件块；
 * 处理 \n / \r\n / 孤立 \r 行尾、注释行（心跳 `: hb`）、多行 data: 拼接。
 * 字符串层面分片安全；UTF-8 多字节分片由调用方 TextDecoder({stream:true}) 保证。
 */
export function createTranslatorSseParser(): {
  feed: (chunk: string) => ParsedSseBlock[];
} {
  let buffer = "";
  let event = "message";
  let dataLines: string[] = [];

  const dispatch = (): ParsedSseBlock | undefined => {
    if (dataLines.length === 0) {
      // 空块 / 纯注释块：重置 event，不产生输出
      event = "message";
      return undefined;
    }
    const block: ParsedSseBlock = { event, data: dataLines.join("\n") };
    event = "message";
    dataLines = [];
    return block;
  };

  const handleLine = (rawLine: string): ParsedSseBlock | undefined => {
    if (rawLine === "") {
      return dispatch();
    }
    if (rawLine.startsWith(":")) {
      return undefined; // 注释 / 心跳
    }
    const colonIndex = rawLine.indexOf(":");
    const field = colonIndex === -1 ? rawLine : rawLine.slice(0, colonIndex);
    let value = colonIndex === -1 ? "" : rawLine.slice(colonIndex + 1);
    if (value.startsWith(" ")) value = value.slice(1);

    if (field === "event") {
      event = value;
    } else if (field === "data") {
      dataLines.push(value);
    }
    // id / retry 等字段本协议不使用，忽略
    return undefined;
  };

  return {
    feed(chunk: string): ParsedSseBlock[] {
      buffer += chunk;
      const out: ParsedSseBlock[] = [];
      let start = 0;
      for (let i = 0; i < buffer.length; i += 1) {
        const ch = buffer[i];
        if (ch === "\n" || ch === "\r") {
          const line = buffer.slice(start, i);
          if (ch === "\r" && buffer[i + 1] === "\n") i += 1; // \r\n 算作一个行尾
          start = i + 1;
          const block = handleLine(line);
          if (block) out.push(block);
        }
      }
      buffer = buffer.slice(start);
      return out;
    },
  };
}

/** 把 UTF-8 字节分片安全地转成字符串，再走 SSE 解析；返回累计事件数组。 */
export function feedTranslatorSseBytes(
  parser: { feed: (chunk: string) => ParsedSseBlock[] },
  decoder: TextDecoder,
  chunk: Uint8Array,
): TranslatorStreamEvent[] {
  const text = decoder.decode(chunk, { stream: true });
  return parser
    .feed(text)
    .map((block) => parseTranslatorEventData(block.event, block.data))
    .filter((item): item is TranslatorStreamEvent => item !== undefined);
}

// ---------- 模型选择（只读快照，不写设置） ----------

export interface TranslatorModelOption {
  /** ProviderModel.id（请求体 modelId 用这个值） */
  id: string;
  /** 展示用 modelId（模型 API 名） */
  modelId: string;
  label: string;
  providerName: string;
}

/** 可翻译模型 = enabled provider 下的 CHAT 模型；只读，切换不写任何设置。 */
export function listTranslatorChatModels(settings: Settings | undefined | null): TranslatorModelOption[] {
  if (!settings) return [];
  const options: TranslatorModelOption[] = [];
  for (const provider of settings.providers ?? []) {
    if (!provider.enabled) continue;
    for (const model of provider.models ?? []) {
      if (model.type !== "CHAT") continue;
      const name = model.displayName || model.modelId || model.id;
      options.push({
        id: model.id,
        modelId: model.modelId,
        label: `${provider.name} / ${name}`,
        providerName: provider.name,
      });
    }
  }
  return options;
}

/** 全局默认翻译模型 id：Settings.translateModeId（types 未声明，走 index signature 收窄）。 */
export function readTranslateModeId(settings: Settings | undefined | null): string | undefined {
  const value = (settings as Record<string, unknown> | undefined | null)?.translateModeId;
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

/** 构造请求体：模型选择等于全局默认时省略 modelId（服务端同样回落到默认）。 */
export function buildTranslatorRequest(
  text: string,
  language: string,
  selectedModelId: string | undefined,
  defaultModelId: string | undefined,
): TranslatorTranslateRequest {
  const trimmedLanguage = language.trim();
  const request: TranslatorTranslateRequest = { text, language: trimmedLanguage };
  if (selectedModelId && selectedModelId !== defaultModelId) {
    request.modelId = selectedModelId;
  }
  return request;
}

// ---------- stale 防旧响应覆盖 ----------

/**
 * 自增序号守卫：start() 拿一个 token，之后用 isCurrent(token) 判断该请求仍是最新。
 * 开始新请求 / 卸载时旧 token 自动失效。
 */
export function createRequestEpoch(): {
  next: () => number;
  isCurrent: (epoch: number) => boolean;
  invalidate: () => void;
} {
  let current = 0;
  return {
    next: () => {
      current += 1;
      return current;
    },
    isCurrent: (epoch: number) => epoch === current,
    invalidate: () => {
      current += 1;
    },
  };
}

// ---------- POST SSE 流 ----------

function dispatchAuthRequired(message: string): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(
    new CustomEvent(WEB_AUTH_REQUIRED_EVENT, { detail: { message, code: 401 } }),
  );
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const data = (await response.json()) as { error?: unknown; code?: unknown };
    if (typeof data.error === "string" && data.error.length > 0) return data.error;
  } catch {
    // ignore body parse errors
  }
  return `HTTP ${response.status}`;
}

export interface StreamTranslateOptions {
  signal?: AbortSignal;
  /** 测试注入用；默认全局 fetch。 */
  fetchImpl?: typeof fetch;
}

/**
 * POST /api/translator/translate 的 SSE 读取。
 * - 事件经 callbacks.onEvent 分发；SSE error 事件同样走 onEvent（type=error）。
 * - 返回 Promise 在流结束 / abort / 致命错误后 resolve（不 reject 正常 abort）。
 * - AbortController.abort() 会真正断开连接 -> 服务端取消上游生成。
 */
export async function streamTranslate(
  request: TranslatorTranslateRequest,
  callbacks: TranslatorStreamCallbacks,
  options: StreamTranslateOptions = {},
): Promise<void> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const url = "/api/translator/translate";

  let response: Response;
  try {
    response = await fetchImpl(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
      },
      body: JSON.stringify(request),
      signal: options.signal ?? null,
    });
  } catch (error) {
    if (options.signal?.aborted || (error instanceof Error && error.name === "AbortError")) {
      callbacks.onClose?.();
      return;
    }
    callbacks.onError?.(error instanceof Error ? error : new Error(String(error)));
    callbacks.onClose?.();
    return;
  }

  if (!response.ok) {
    const message = await readErrorMessage(response);
    if (response.status === 401) {
      dispatchAuthRequired(message);
    }
    callbacks.onError?.(new ApiError(message, response.status));
    callbacks.onClose?.();
    return;
  }

  callbacks.onOpen?.();

  const body = response.body;
  if (!body) {
    callbacks.onError?.(new ApiError("Response body is not readable", 0));
    callbacks.onClose?.();
    return;
  }

  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = createTranslatorSseParser();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      for (const eventItem of feedTranslatorSseBytes(parser, decoder, value)) {
        callbacks.onEvent(eventItem);
      }
    }
    // 流尾 flush 解码器里可能残留的多字节尾巴 + buffer 里的不完整块按协议丢弃
    const tail = decoder.decode();
    if (tail) {
      for (const eventItem of parser
        .feed(tail)
        .map((block) => parseTranslatorEventData(block.event, block.data))
        .filter((item): item is TranslatorStreamEvent => item !== undefined)) {
        callbacks.onEvent(eventItem);
      }
    }
  } catch (error) {
    if (options.signal?.aborted || (error instanceof Error && error.name === "AbortError")) {
      // 主动取消：不算错误
    } else {
      callbacks.onError?.(error instanceof Error ? error : new Error(String(error)));
    }
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // ignore
    }
    callbacks.onClose?.();
  }
}

// ---------- 翻译历史（App 与 Web 共用，服务端只留最近 100 条） ----------

export interface TranslatorHistoryItem {
  id: string;
  sourceText: string;
  translatedText: string;
  /** BCP-47 tag */
  targetLanguage: string;
  modelId: string;
  /** epoch millis */
  createAt: number;
}

export interface TranslatorHistoryPage {
  items: TranslatorHistoryItem[];
  total: number;
}

export const TRANSLATOR_HISTORY_PAGE_SIZE = 20;

export function fetchTranslatorHistory(
  offset = 0,
  limit = TRANSLATOR_HISTORY_PAGE_SIZE,
): Promise<TranslatorHistoryPage> {
  return api.get<TranslatorHistoryPage>("translator/history", {
    searchParams: { offset, limit },
  });
}

export function deleteTranslatorHistoryItem(id: string): Promise<unknown> {
  return api.delete(`translator/history/${encodeURIComponent(id)}`);
}

export function clearTranslatorHistory(): Promise<unknown> {
  return api.delete("translator/history");
}

/** 合并新取回的一页：按 id 去重，保持新的在前（服务端已按时间倒序） */
export function mergeTranslatorHistory(
  current: TranslatorHistoryItem[],
  incoming: TranslatorHistoryItem[],
): TranslatorHistoryItem[] {
  const seen = new Set(current.map((item) => item.id));
  return [...current, ...incoming.filter((item) => !seen.has(item.id))];
}

