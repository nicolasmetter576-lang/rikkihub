import { ApiError, sse } from "~/services/api";

/**
 * Multiplexed SSE client for `/api/events`.
 *
 * A single shared connection carries several event types (settings, conversation list
 * invalidation, ...). Consumers subscribe by event name; the connection is opened on the
 * first subscriber, reused by all, reconnected on drop, and closed when nobody listens.
 *
 * @see app/src/main/java/me/rerere/rikkihub/web/routes/EventsRoutes.kt
 */

export const EVENT_SETTINGS = "settings";
export const EVENT_CONVERSATION_LIST_INVALIDATE = "conversation_list_invalidate";
export const EVENT_FOLDERS = "folders";
export const EVENT_WORKSPACE_UPDATE = "workspace_update";
export const EVENT_SUBAGENT_PRESETS_UPDATE = "subagent_presets_update";
export interface SubagentPresetsUpdateEvent { timestamp: number; }

type EventListener = (data: unknown) => void;

const RECONNECT_DELAY_MIN_MS = 1_000;
const RECONNECT_DELAY_MAX_MS = 30_000;

const listeners = new Map<string, Set<EventListener>>();
let abortController: AbortController | null = null;
let running = false;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectAttempts = 0;
let authFailed = false;
let visibilityListenerInstalled = false;

function hasListeners(): boolean {
  for (const set of listeners.values()) {
    if (set.size > 0) return true;
  }
  return false;
}

function dispatch(event: string, data: unknown) {
  const set = listeners.get(event);
  if (!set) return;
  for (const listener of set) {
    try {
      listener(data);
    } catch (error) {
      console.error(`Events listener for "${event}" failed:`, error);
    }
  }
}

function handleVisibilityChange() {
  if (document.visibilityState === "visible" && hasListeners() && !running && !authFailed) {
    // Reconnect immediately instead of waiting out a pending backoff timer.
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    startConnection();
  }
}

function ensureVisibilityListener() {
  if (visibilityListenerInstalled || typeof document === "undefined") return;
  document.addEventListener("visibilitychange", handleVisibilityChange);
  visibilityListenerInstalled = true;
}

function removeVisibilityListener() {
  if (!visibilityListenerInstalled || typeof document === "undefined") return;
  document.removeEventListener("visibilitychange", handleVisibilityChange);
  visibilityListenerInstalled = false;
}

function isDocumentHidden(): boolean {
  return typeof document !== "undefined" && document.visibilityState === "hidden";
}

function scheduleReconnect() {
  if (reconnectTimer !== null || authFailed) return;
  if (isDocumentHidden()) {
    // Defer until the page becomes visible again; handleVisibilityChange restarts it.
    ensureVisibilityListener();
    return;
  }
  const delay = Math.min(RECONNECT_DELAY_MAX_MS, RECONNECT_DELAY_MIN_MS * 2 ** reconnectAttempts);
  reconnectAttempts += 1;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    startConnection();
  }, delay);
}

function startConnection() {
  if (running || !hasListeners() || authFailed || isDocumentHidden()) {
    if (!running && hasListeners() && isDocumentHidden()) {
      ensureVisibilityListener();
    }
    return;
  }
  running = true;
  ensureVisibilityListener();

  const controller = new AbortController();
  abortController = controller;

  void sse<unknown>(
    "events",
    {
      onMessage: ({ event, data }) => {
        dispatch(event, data);
      },
      onOpen: () => {
        reconnectAttempts = 0;
      },
      onError: (error) => {
        // 401 is terminal for this page session: the token was cleared and the
        // auth gate will reload once the user re-authenticates.
        if (error instanceof ApiError && error.code === 401) {
          authFailed = true;
        }
        console.error("Events SSE error:", error);
      },
      onClose: () => {
        running = false;
        if (abortController === controller) {
          abortController = null;
        }
        // Reconnect on unexpected drop while listeners remain; skip intentional aborts.
        if (!controller.signal.aborted && hasListeners() && !authFailed) {
          scheduleReconnect();
        }
      },
    },
    { signal: controller.signal },
  );
}

function stopIfIdle() {
  if (hasListeners()) return;
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  abortController?.abort();
  abortController = null;
  running = false;
  authFailed = false;
  reconnectAttempts = 0;
  removeVisibilityListener();
}

/**
 * Subscribe to a single event type on the shared `/api/events` connection.
 * Returns an unsubscribe function.
 */
export function subscribeToEvent<T>(eventType: string, listener: (data: T) => void): () => void {
  let set = listeners.get(eventType);
  if (!set) {
    set = new Set();
    listeners.set(eventType, set);
  }
  const wrapped = listener as EventListener;
  set.add(wrapped);
  startConnection();

  return () => {
    set.delete(wrapped);
    stopIfIdle();
  };
}
