import type { CacheWarmState } from "@overlay/lib/cache-keep-alive";
import type { SSECallbacks } from "../services/api";
import type {
  CompressStateDto,
  CompressSuggestionEventDto,
  ConversationErrorDto,
  ConversationErrorEventDto,
  ConversationErrorsEventDto,
  ConversationDto,
  ConversationNodeUpdateEventDto,
  ConversationQueueEventDto,
  ConversationSnapshotEventDto,
  MessageQueueDto,
} from "../types";

export const NODE_UPDATE_FLUSH_MS = 30;
const RETRY_MIN_MS = 1_000;
const RETRY_MAX_MS = 30_000;

export type ConversationSyncStatus =
  | "idle"
  | "connecting"
  | "live"
  | "retrying"
  | "unauthorized"
  | "forbidden"
  | "not-found";

export interface ConversationStreamState {
  detail: ConversationDto | null;
  queue: MessageQueueDto | null;
  cacheWarm: CacheWarmState | null;
  compressState: CompressStateDto | null;
  /** "Notify only" hint: usage percent of the input budget, or null. */
  compressSuggestion: { percent: number; exact: boolean } | null;
  /** Stateful conversation-scoped errors replayed on connect/reconnect until dismissed or cleared. */
  generationErrors: ConversationErrorDto[];
  status: ConversationSyncStatus;
  error: string | null;
}

/** One array copy per batch, not per node. A structural mismatch needs a new snapshot. */
export function applyNodeUpdates(
  detail: ConversationDto,
  events: Iterable<ConversationNodeUpdateEventDto>,
  last: ConversationNodeUpdateEventDto,
): ConversationDto | null {
  const indices = new Map<string, number>();
  detail.messages.forEach((node, index) => indices.set(node.id, index));
  const messages = detail.messages.slice();
  for (const event of events) {
    const index = indices.get(event.nodeId) ?? messages.length;
    if (
      event.conversationId !== detail.id ||
      event.nodeId !== event.node.id ||
      event.nodeIndex !== index
    )
      return null;
    messages[index] = event.node;
    indices.set(event.nodeId, index);
  }
  return { ...detail, messages, updateAt: last.updateAt, isGenerating: last.isGenerating };
}

interface StreamDependencies {
  connect: (callbacks: SSECallbacks<unknown>, signal: AbortSignal) => void | Promise<void>;
  onSummary: (detail: ConversationDto) => void;
  onClock: (serverTime: number) => void;
  onGenerationError: (message: string, solution: string | null) => void;
}

/**
 * One controller per conversation *visit*, including A -> B -> A. No REST payload can enter it.
 * Transport is injected; state transitions and the batcher run without React or browser globals.
 */
export function createConversationStream(id: string | null, deps: StreamDependencies) {
  const empty = {
    detail: null,
    queue: null,
    cacheWarm: null,
    compressState: null,
    compressSuggestion: null,
    generationErrors: [] as ConversationErrorDto[],
    error: null,
  };
  let state: ConversationStreamState = { ...empty, status: id ? "connecting" : "idle" };
  const listeners = new Set<() => void>();
  const publish = (patch: Partial<ConversationStreamState>) => {
    state = { ...state, ...patch };
    listeners.forEach((listener) => listener());
  };

  let running = false;
  let generation = 0;
  let abort: AbortController | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let retries = 0;
  let sequence = 0;
  let hasBaseline = false;
  const pending = new Map<string, ConversationNodeUpdateEventDto>();
  let last: ConversationNodeUpdateEventDto | null = null;
  let flushTimer: ReturnType<typeof setTimeout> | null = null;
  let batch = 0;
  let displayBasis: { assistantId: string | null; signature: string } | null = null;

  const clearBatch = () => {
    batch += 1;
    if (flushTimer !== null) clearTimeout(flushTimer);
    flushTimer = null;
    pending.clear();
    last = null;
  };
  const disconnect = () => {
    // Invalidate callbacks BEFORE abort: onClose may run as a result of aborting.
    generation += 1;
    abort?.abort();
    abort = null;
    if (retryTimer !== null) clearTimeout(retryTimer);
    retryTimer = null;
    clearBatch();
    hasBaseline = false;
    sequence = 0;
  };

  const fail = (error?: Error) => {
    const code = (error as (Error & { code?: number }) | undefined)?.code;
    const terminal =
      code === 401
        ? "unauthorized"
        : code === 403
          ? "forbidden"
          : code === 404
            ? "not-found"
            : null;
    disconnect();
    // Keep the last displayed tree on network failure. Auth/deletion must not leave it visible.
    publish(
      terminal
        ? { ...empty, status: terminal, error: error?.message ?? null }
        : { status: "retrying", error: error?.message ?? null },
    );
    if (terminal) return;
    const attempt = generation;
    const delay = Math.min(RETRY_MAX_MS, RETRY_MIN_MS * 2 ** Math.min(retries++, 5));
    retryTimer = setTimeout(() => {
      if (!running || generation !== attempt) return;
      retryTimer = null;
      connect();
    }, delay);
  };

  const connect = () => {
    if (!running || !id) return;
    disconnect();
    const attempt = generation;
    const current = () => running && generation === attempt;
    abort = new AbortController();
    publish({ status: "connecting", error: null, cacheWarm: null, compressState: null, compressSuggestion: null });

    const schedule = (event: ConversationNodeUpdateEventDto) => {
      pending.set(event.nodeId, event);
      last = event; // Map replacement does NOT change insertion order; track reception order separately.
      if (flushTimer !== null) return;
      const scheduledBatch = batch;
      flushTimer = setTimeout(() => {
        if (!current() || scheduledBatch !== batch) return;
        const previous = state.detail;
        const latest = last;
        // Validate seq on receipt, never again here: different nodes in this batch are all valid.
        const next =
          previous && latest ? applyNodeUpdates(previous, pending.values(), latest) : null;
        clearBatch();
        if (!next || !latest) {
          fail();
          return;
        }
        publish({ detail: next });
        deps.onClock(latest.serverTime);
        if (previous?.isGenerating !== next.isGenerating) deps.onSummary(next);
      }, NODE_UPDATE_FLUSH_MS);
    };

    void deps.connect(
      {
        onMessage: ({ event, data }) => {
          if (!current() || !data || typeof data !== "object") return;
          if (event === "snapshot" || event === "node_update") {
            const payload = data as ConversationSnapshotEventDto | ConversationNodeUpdateEventDto;
            if (payload.type !== event) return;
            const conversationId =
              payload.type === "snapshot" ? payload.conversation.id : payload.conversationId;
            if (conversationId !== id) return;
            if (!Number.isSafeInteger(payload.seq) || payload.seq <= 0) {
              fail();
              return;
            }
            if (payload.seq <= sequence) return;
            if (payload.type === "snapshot") {
              // A full snapshot can itself repair a sequence gap. It is a barrier to every older batch.
              clearBatch();
              sequence = payload.seq;
              hasBaseline = true;
              retries = 0; // Opening HTTP alone isn't a successful connection; receiving a baseline is.
              publish({ detail: payload.conversation, status: "live", error: null });
              deps.onClock(payload.serverTime);
              deps.onSummary(payload.conversation);
            } else {
              if (!hasBaseline || payload.seq !== sequence + 1) {
                fail();
                return;
              }
              sequence = payload.seq;
              schedule(payload);
            }
            return;
          }
          if (event === "queue_update") {
            const payload = data as ConversationQueueEventDto;
            if (payload.type === event && payload.conversationId === id)
              publish({ queue: payload.queue });
          } else if (event === "compress_state") {
            const payload = data as CompressStateDto;
            if (payload.conversationId === id) publish({ compressState: payload });
          } else if (event === "compress_suggestion") {
            const payload = data as CompressSuggestionEventDto;
            if (payload.type !== event || payload.conversationId !== id) return;
            publish({
              compressSuggestion:
                typeof payload.percent === "number" ? { percent: payload.percent, exact: payload.exact } : null,
            });
          } else if (event === "cache_warm") {
            const payload = data as CacheWarmState & { type: string; conversationId: string };
            if (payload.type !== event || payload.conversationId !== id) return;
            if (!["inactive", "scheduled", "refreshing"].includes(payload.state)) return;
            const offset =
              typeof payload.serverTime === "number" ? payload.serverTime - Date.now() : 0;
            publish({
              cacheWarm: {
                state: payload.state,
                nextWarmAt:
                  typeof payload.nextWarmAt === "number" ? payload.nextWarmAt - offset : null,
                serverTime: payload.serverTime ?? Date.now(),
              },
            });
          } else if (event === "errors") {
            const payload = data as ConversationErrorsEventDto;
            if (payload.type === event && payload.conversationId === id && Array.isArray(payload.errors)) {
              publish({ generationErrors: payload.errors });
            }
          } else if (event === "error") {
            const payload = data as ConversationErrorEventDto;
            if (payload.type === event) deps.onGenerationError(payload.message, payload.solution ?? null);
          }
        },
        onError: (error) => {
          if (current()) fail(error);
        },
        onClose: () => {
          if (current()) fail();
        },
      },
      abort.signal,
    );
  };

  return {
    getSnapshot: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    start: () => {
      if (running) return;
      running = true;
      connect();
    },
    stop: () => {
      running = false;
      disconnect();
    },
    setDisplaySignature: (assistantId: string | null, signature: string) => {
      const previous = displayBasis;
      displayBasis = { assistantId, signature };
      // Learning the assistant from the first snapshot isn't a settings change.
      if (!previous || previous.assistantId !== assistantId || previous.signature === signature)
        return;
      if (
        state.status === "unauthorized" ||
        state.status === "forbidden" ||
        state.status === "not-found"
      )
        return;
      connect();
    },
  };
}
