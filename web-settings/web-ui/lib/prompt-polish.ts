/**
 * 聊天框润色：SSE 客户端、配置读写，以及与界面无关的按钮状态机（与 App ChatInputPolisher 同一套规则）。
 * 提示词拼装、上下文截取、模型选择都在服务端（与 App 共用 Kotlin 代码）。
 * @see web-settings/src/main/java/me/rerere/rikkihub/web/routes/PromptPolishRoutes.kt
 */

import api, { ApiError } from "~/services/api";
import { createTranslatorSseParser } from "@overlay/lib/translator";

export interface PromptPolishConfig {
  modelId: string | null;
  /** 指定的模型已不存在，实际用助手模型。 */
  modelMissing: boolean;
  /** 自定义提示词；null = 内置默认。 */
  prompt: string | null;
  defaultPrompt: string;
  includeContext: boolean;
  contextMessages: number;
  minContextMessages: number;
  maxContextMessages: number;
  maxPromptChars: number;
  maxInputChars: number;
}

export interface PromptPolishConfigPatch {
  modelId?: string | null;
  prompt?: string | null;
  includeContext?: boolean;
  contextMessages?: number;
}

export function fetchPromptPolishConfig(): Promise<PromptPolishConfig> {
  return api.get<PromptPolishConfig>("prompt-polish/config");
}

export function updatePromptPolishConfig(patch: PromptPolishConfigPatch): Promise<PromptPolishConfig> {
  return api.put<PromptPolishConfig>("prompt-polish/config", patch);
}

export type PromptPolishEvent =
  | { type: "delta"; text: string }
  | { type: "done"; text: string }
  | { type: "error"; code: string; message: string };

/** 解析一个 SSE 事件的 data；未知事件 / 非法 JSON 返回 undefined。 */
export function parsePromptPolishEvent(event: string, data: string): PromptPolishEvent | undefined {
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>;
    switch (event) {
      case "delta":
      case "done":
        return typeof parsed.text === "string" ? { type: event, text: parsed.text } : undefined;
      case "error":
        return {
          type: "error",
          code: typeof parsed.code === "string" && parsed.code ? parsed.code : "MODEL_ERROR",
          message: typeof parsed.message === "string" ? parsed.message : "",
        };
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

export interface StreamPromptPolishOptions {
  signal?: AbortSignal;
  /** 测试注入用；默认全局 fetch。 */
  fetchImpl?: typeof fetch;
}

/**
 * POST /api/prompt-polish 的 SSE 读取。HTTP 错误与网络错误转成 error 事件（code=HTTP_<status> / NETWORK）；
 * abort 会断开连接（不再发事件），服务端随之取消生成。
 */
export async function streamPromptPolish(
  request: { text: string; conversationId?: string | null },
  onEvent: (event: PromptPolishEvent) => void,
  options: StreamPromptPolishOptions = {},
): Promise<void> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const aborted = (error: unknown) =>
    options.signal?.aborted || (error instanceof Error && error.name === "AbortError");
  let response: Response;
  try {
    response = await fetchImpl("/api/prompt-polish", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ text: request.text, conversationId: request.conversationId ?? null }),
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
      if (finished) return;
      const event = parsePromptPolishEvent(block.event, block.data);
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
    if (!finished && !aborted(error)) {
      onEvent({ type: "error", code: "NETWORK", message: error instanceof Error ? error.message : String(error) });
    }
  }
}

export type PromptPolishState =
  | { phase: "idle" }
  | { phase: "running"; original: string }
  | { phase: "done"; original: string; polished: string };

export interface PromptPolishControllerDeps {
  read: () => string;
  /** Streaming writes (plain value updates). */
  write: (text: string) => void;
  /** Final write; the chat input routes it through the browser's undo stack. Defaults to [write]. */
  commit?: (original: string, polished: string) => void;
  stream: (text: string, onEvent: (event: PromptPolishEvent) => void, signal: AbortSignal) => Promise<void>;
  onError: (code: string, message: string) => void;
  onState: (state: PromptPolishState) => void;
}

/**
 * 一按即润色：读输入框 → 流式覆盖 → 可撤销。取消、失败（含写了一半）都恢复原文；
 * 输入框文字不再是润色结果（用户改了、发送后清空）时撤销失效。[detach] 切换会话时用：只断开，原文写回原来的草稿。
 */
export function createPromptPolishController(deps: PromptPolishControllerDeps) {
  let state: PromptPolishState = { phase: "idle" };
  let abort: AbortController | null = null;
  /** Whether the running polish has already written into the box (so detach must put the original back). */
  let wrote = false;
  const set = (next: PromptPolishState) => {
    state = next;
    deps.onState(next);
  };

  return {
    get state() {
      return state;
    },
    async start() {
      if (state.phase === "running") return;
      const original = deps.read();
      if (!original.trim()) return;
      const controller = new AbortController();
      abort = controller;
      wrote = false;
      set({ phase: "running", original });
      let written: string | null = null;
      let failure: { code: string; message: string } | null = null;
      let final: string | null = null;
      await deps.stream(original, (event) => {
        if (controller.signal.aborted) return;
        if (event.type === "delta") {
          written = event.text;
          wrote = true;
          deps.write(event.text);
        } else if (event.type === "done") {
          final = event.text;
        } else {
          failure = { code: event.code, message: event.message };
        }
      }, controller.signal);
      if (abort !== controller) return; // detached or superseded
      abort = null;
      if (controller.signal.aborted) {
        if (written !== null) deps.write(original);
        set({ phase: "idle" });
        return;
      }
      if (final !== null && failure === null) {
        (deps.commit ?? ((_o: string, p: string) => deps.write(p)))(original, final);
        set({ phase: "done", original, polished: final });
        return;
      }
      if (written !== null) deps.write(original);
      set({ phase: "idle" });
      const reported = failure as { code: string; message: string } | null;
      deps.onError(reported?.code ?? "EMPTY_RESULT", reported?.message ?? "");
    },
    /** 润色中：取消并恢复原文。 */
    cancel() {
      abort?.abort();
    },
    /** 已润色：恢复原文。 */
    undo() {
      if (state.phase !== "done") return;
      const { original } = state;
      set({ phase: "idle" });
      deps.write(original);
    },
    textChanged(text: string) {
      if (state.phase === "done" && text !== state.polished) set({ phase: "idle" });
    },
    /**
     * 切换会话 / 卸载时用：断开请求，不经 [write] 写回（输入框可能已是另一个会话的草稿）；
     * 已经写了一半时把原文交给 [restore]，由调用方写回原来那份草稿。
     */
    detach(restore?: (original: string) => void) {
      const current = abort;
      abort = null;
      current?.abort();
      if (state.phase === "running" && wrote) restore?.(state.original);
      if (state.phase !== "idle") set({ phase: "idle" });
    },
  };
}

export type PromptPolishController = ReturnType<typeof createPromptPolishController>;

interface ModelLike { id: string; modelId?: string; displayName?: string }

/** 润色实际用的模型（与服务端一致）：指定的且仍存在 → 当前助手的模型 → 全局对话模型。找不到为 null。 */
export function resolvePromptPolishModel(
  settings: {
    providers?: Array<{ models?: ModelLike[] }>;
    chatModelId?: string | null;
    promptPolish?: { modelId?: string | null } | null;
  } | null | undefined,
  assistantChatModelId?: string | null,
): ModelLike | null {
  if (!settings) return null;
  const models = (settings.providers ?? []).flatMap((provider) => provider.models ?? []);
  const find = (id: string | null | undefined) => (id ? models.find((model) => model.id === id) ?? null : null);
  return find(settings.promptPolish?.modelId) ?? find(assistantChatModelId ?? settings.chatModelId) ?? null;
}

/**
 * 用润色结果整段替换输入框，并让浏览器的撤销（Ctrl+Z）能回到原文：先把原文写回（[setValue] 须同步提交到 DOM），
 * 再全选后用 `insertText` 插入结果——这一步进入原生撤销栈。浏览器不支持时退回直接赋值。
 */
export function replaceTextareaWithUndo(
  textarea: HTMLTextAreaElement | null,
  original: string,
  polished: string,
  setValue: (text: string) => void,
): void {
  setValue(original);
  if (textarea && typeof document !== "undefined" && typeof document.execCommand === "function") {
    textarea.readOnly = false;
    textarea.focus();
    textarea.select();
    let inserted = false;
    try {
      inserted = document.execCommand("insertText", false, polished);
    } catch {
      inserted = false;
    }
    if (inserted && textarea.value === polished) return;
  }
  setValue(polished);
}
