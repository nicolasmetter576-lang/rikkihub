import ky, { type Options, HTTPError } from "ky";

interface ErrorResponse {
  error: string;
  code: number;
  errorCode?: string;
}

interface WebAuthTokenResponse {
  token: string;
  expiresAt: number;
}

interface WebAuthRequiredEventDetail {
  message: string;
  code: number;
  /**
   * true = 后端已 fail-closed：服务启用但无可用认证材料（未配置访问密码），
   * 弹密码框无意义，WebAuthGate 应展示"不可用"状态而非登录表单。
   */
  disabled?: boolean;
}

export class ApiError extends Error {
  code: number;
  errorCode?: string;
  /** The parsed error body, for endpoints that add fields (e.g. skill import conflicts list `names`). */
  details?: Record<string, unknown>;

  constructor(
    message: string,
    code: number,
    errorCode?: string,
    details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.errorCode = errorCode;
    this.details = details;
  }
}

const WEB_AUTH_REQUIRED_EVENT = "rikkihub:web-auth-required";
/**
 * 后端 fail-closed 的稳定 errorCode（WebApiPolicy 契约；只认机器码，
 * 不解析英文 error 文案——消息文本可能随后端措辞变化）。
 *  - WEB_AUTH_DISABLED：服务在跑但 web auth 未启用 → 管理 API 关闭
 *  - WEB_AUTH_PASSWORD_NOT_CONFIGURED：auth 启用但无可用密码 → 管理 API 关闭
 * 两者都表示"弹密码框无意义"，WebAuthGate 展示不可用状态。
 */
const WEB_AUTH_CLOSED_ERROR_CODES = new Set([
  "WEB_AUTH_DISABLED",
  "WEB_AUTH_PASSWORD_NOT_CONFIGURED",
]);
function isBrowser(): boolean {
  return typeof window !== "undefined";
}

function dispatchWebAuthRequired(detail: WebAuthRequiredEventDetail) {
  if (!isBrowser()) return;
  window.dispatchEvent(
    new CustomEvent<WebAuthRequiredEventDetail>(WEB_AUTH_REQUIRED_EVENT, { detail }),
  );
}

// The web session is an HttpOnly cookie set by `auth/token`; same-origin requests carry it.
const kyInstance = ky.create({
  prefixUrl: "/api",
  timeout: 30000,
  credentials: "same-origin",
});

async function handleError(error: unknown): Promise<never> {
  if (error instanceof HTTPError) {
    const { response } = error;
    let errorData: ErrorResponse | undefined;
    try {
      errorData = await response.json();
    } catch {
      // Ignore JSON parse error
    }
    const code = errorData?.code ?? response.status;
    const message = errorData?.error ?? error.message;
    const isAuthTokenEndpoint = response.url.includes("/api/auth/token");
    const isAuthDisabled =
      typeof errorData?.errorCode === "string" &&
      WEB_AUTH_CLOSED_ERROR_CODES.has(errorData.errorCode);
    if (isAuthDisabled && !isAuthTokenEndpoint) {
      // 禁匿名 fail-closed：不是密码错误，不弹登录框，转"不可用"状态
      dispatchWebAuthRequired({ message, code, disabled: true });
    } else if (code === 401 && !isAuthTokenEndpoint) {
      dispatchWebAuthRequired({ message, code });
    }
    throw new ApiError(
      message,
      code,
      typeof errorData?.errorCode === "string" ? errorData.errorCode : undefined,
      errorData && typeof errorData === "object"
        ? (errorData as unknown as Record<string, unknown>)
        : undefined,
    );
  }
  throw error;
}

export function onWebAuthRequired(
  listener: (detail: WebAuthRequiredEventDetail) => void,
): () => void {
  if (!isBrowser()) return () => {};

  const handler = (event: Event) => {
    const customEvent = event as CustomEvent<WebAuthRequiredEventDetail>;
    listener(customEvent.detail);
  };
  window.addEventListener(WEB_AUTH_REQUIRED_EVENT, handler);

  return () => {
    window.removeEventListener(WEB_AUTH_REQUIRED_EVENT, handler);
  };
}

export interface WebAuthTicketResponse {
  ticket: string;
  expiresAt: number;
  scope: string;
}

/**
 * Mint a single-use, ~90s ticket bound to `scope` and the exact request
 * `target` path. Only `scope: "ws"` exists; the ticket is consumed by the
 * WebSocket upgrade handshake and cannot be replayed or used for REST calls.
 */
export async function requestWebAuthTicket(
  scope: "ws",
  target: string,
): Promise<WebAuthTicketResponse> {
  return api.post<WebAuthTicketResponse>("auth/ticket", { scope, target });
}

/** Revoke the current session server-side and clear its cookie. */
export async function logoutWebAuth(): Promise<void> {
  await api.post("auth/logout");
}

/**
 * API client with unwrapped response data
 */
const api = {
  async get<T>(url: string, options?: Options): Promise<T> {
    try {
      return await kyInstance.get(url, options).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async post<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.post(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async postMultipart<T>(url: string, formData: FormData, options?: Options): Promise<T> {
    try {
      return await kyInstance.post(url, { ...options, body: formData, timeout: 120_000 }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async put<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance
        .put(url, { ...options, json: data, timeout: options?.timeout ?? 60_000 })
        .json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async patch<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.patch(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async delete<T>(url: string, options?: Options): Promise<T> {
    try {
      const response = await kyInstance.delete(url, options);
      const text = await response.text();
      if (!text.trim()) return {} as T;
      return JSON.parse(text) as T;
    } catch (error) {
      return handleError(error);
    }
  },
};

/** Log in; the server answers with the session cookie. */
export async function requestWebAuthToken(password: string): Promise<WebAuthTokenResponse> {
  return api.post<WebAuthTokenResponse>("auth/token", { password });
}

export interface SSEEvent<T> {
  event: string;
  data: T;
  id?: string;
}

export interface SSECallbacks<T> {
  onMessage: (event: SSEEvent<T>) => void;
  onError?: (error: Error) => void;
  onOpen?: () => void;
  onClose?: () => void;
}

/**
 * Create an SSE connection using ky (supports auth headers)
 */
async function sse<T>(
  url: string,
  callbacks: SSECallbacks<T>,
  options?: Options & { signal?: AbortSignal },
): Promise<void> {
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;

  try {
    const response = await kyInstance.get(url, {
      ...options,
      headers: {
        ...options?.headers,
        Accept: "text/event-stream",
      },
      timeout: false,
      // SSE streams must not be retried transparently; reconnects are driven by callers.
      retry: 0,
    });

    callbacks.onOpen?.();

    reader = response.body?.getReader();
    if (!reader) {
      throw new ApiError("Response body is not readable", 0);
    }

    const decoder = new TextDecoder();
    let buffer = "";
    let currentEvent = "message";
    let currentData = "";
    let currentId: string | undefined;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        const trimmedLine = line.replace(/\r$/, "");
        if (trimmedLine.startsWith("event:")) {
          currentEvent = trimmedLine.slice(6).trim();
        } else if (trimmedLine.startsWith("data:")) {
          currentData += (currentData ? "\n" : "") + trimmedLine.slice(5).trim();
        } else if (trimmedLine.startsWith("id:")) {
          currentId = trimmedLine.slice(3).trim();
        } else if (trimmedLine === "") {
          if (currentData) {
            try {
              const data = JSON.parse(currentData) as T;
              callbacks.onMessage({ event: currentEvent, data, id: currentId });
            } catch {
              // Ignore JSON parse error
            }
          }
          currentEvent = "message";
          currentData = "";
          currentId = undefined;
        }
      }
    }
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      // Ignored: intentional abort
    } else {
      try {
        await handleError(error);
      } catch (handledError) {
        callbacks.onError?.(
          handledError instanceof Error ? handledError : new Error(String(handledError)),
        );
      }
    }
  } finally {
    reader?.releaseLock();
    callbacks.onClose?.();
  }
}

export { sse };
export default api;
