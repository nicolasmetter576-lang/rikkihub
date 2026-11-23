import test, { type TestContext } from "node:test";
import assert from "node:assert/strict";
import {
  applyNodeUpdates,
  createConversationStream,
  NODE_UPDATE_FLUSH_MS,
} from "../app/lib/conversation-stream";
import { sse, type SSECallbacks } from "../app/services/api";
import { setImmediate as settle } from "node:timers/promises";
import type { ConversationDto } from "../app/types";
import { conversation, node, snapshot, update } from "./helpers/conversation-fixtures";

function harness(t: TestContext, id: string | null = "A") {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 1000 });
  const connections: Array<{ callbacks: SSECallbacks<unknown>; signal: AbortSignal }> = [];
  const summaries: ConversationDto[] = [];
  const errors: string[] = [];
  const clocks: number[] = [];
  let notifications = 0;
  const stream = createConversationStream(id, {
    connect: (callbacks, signal) => {
      connections.push({ callbacks, signal });
    },
    onSummary: (detail) => summaries.push(detail),
    onClock: (time) => clocks.push(time),
    onGenerationError: (message) => errors.push(message),
  });
  stream.subscribe(() => notifications++);
  stream.start();
  t.after(stream.stop);
  const emit = <T extends { type: string }>(data: T, connection = connections.length - 1) => {
    connections[connection].callbacks.onMessage({ event: data.type, data });
  };
  return {
    stream,
    connections,
    summaries,
    errors,
    clocks,
    emit,
    notifications: () => notifications,
  };
}

const completion = (detail: ConversationDto | null, index = 0) =>
  detail?.messages[index].messages[0].usage?.completionTokens;

test("30ms batch coalesces A/B/A, accepts usage-only changes, and uses last RECEIVED flags", (t) => {
  const h = harness(t);
  h.emit(snapshot());
  const before = h.stream.getSnapshot().detail!;
  const count = h.notifications();
  h.emit(update(2, "n1", 0, 20));
  h.emit(update(3, "n2", 1, 30));
  h.emit({ ...update(4, "n1", 0, 40), isGenerating: false });
  t.mock.timers.tick(29);
  assert.equal(h.stream.getSnapshot().detail, before);
  t.mock.timers.tick(1);
  const after = h.stream.getSnapshot().detail!;
  assert.equal(completion(after), 40);
  assert.equal(
    completion(after, 1),
    30,
    "earlier valid nodes are not filtered by the highest received seq at flush",
  );
  assert.equal(after.updateAt, 4);
  assert.equal(after.isGenerating, false, "Map iteration ends at B3, but the last frame was A4");
  assert.equal(h.notifications() - count, 1, "one publication per batch");
  assert.equal(h.clocks.at(-1), 1004);
  assert.equal(h.summaries.at(-1), after);
  assert.equal(completion(before), 10, "no mutation of the baseline");
});

test("batch copies the node array once and retains untouched node identities", () => {
  const before = conversation();
  const untouched = node("untouched");
  before.messages.push(untouched);
  let copies = 0;
  before.messages.slice = function (...args) {
    copies++;
    return Array.prototype.slice.apply(this, args);
  };
  const events = [update(2), update(3, "n2", 1), update(4, "n4", 3)];
  const next = applyNodeUpdates(before, events, events[2])!;
  assert.equal(copies, 1);
  assert.notEqual(next.messages, before.messages);
  assert.equal(next.messages[2], untouched);
  assert.equal(next.messages[3].id, "n4");
  assert.equal(before.messages.length, 3);
});

test("snapshot is a barrier: an older pending node cannot erase final usage, translation or favorite", (t) => {
  const h = harness(t);
  h.emit(snapshot());
  const stale = update(2);
  stale.node.messages[0].usage = null;
  stale.node.messages[0].finishedAt = null;
  h.emit(stale);
  const final = conversation("A", 100);
  final.title = "metadata from SSE";
  final.isGenerating = false;
  final.messages[0].isFavorite = true;
  final.messages[0].messages[0].translation = "translated";
  h.emit(snapshot(3, final));
  t.mock.timers.tick(NODE_UPDATE_FLUSH_MS);
  assert.equal(h.stream.getSnapshot().detail, final);
  assert.equal(completion(final), 100);
  assert.equal(final.messages[0].messages[0].translation, "translated");
  assert.ok(final.messages[0].messages[0].finishedAt);
  // New batch after the barrier still works; lastEvent was cleared too.
  h.emit({ ...update(4, "n2", 1), isGenerating: false });
  t.mock.timers.tick(NODE_UPDATE_FLUSH_MS);
  assert.equal(completion(h.stream.getSnapshot().detail), 100);
});

test("same-connection stale/duplicate seq is ignored; full snapshots repair gaps", (t) => {
  const h = harness(t);
  h.emit(snapshot(5));
  h.emit(snapshot(4, conversation("A", 1)));
  h.emit(update(5, "n1", 0, 2));
  h.emit(snapshot(20, conversation("A", 200)));
  t.mock.timers.tick(30);
  assert.equal(completion(h.stream.getSnapshot().detail), 200);
  assert.equal(h.connections.length, 1);
});

test("disconnect retains displayed messages, discards pending nodes, and new connection resets seq", (t) => {
  const h = harness(t);
  h.emit(snapshot(90, conversation("A", 100)));
  h.emit(update(91, "n1", 0, 1));
  h.connections[0].callbacks.onClose?.();
  assert.equal(h.stream.getSnapshot().status, "retrying");
  assert.equal(completion(h.stream.getSnapshot().detail), 100);
  t.mock.timers.tick(1000);
  assert.equal(h.connections.length, 2);
  assert.ok(h.connections[0].signal.aborted);
  assert.equal(h.stream.getSnapshot().status, "connecting");
  h.emit(snapshot(1, conversation("A", 110)));
  h.emit(update(2, "n1", 0, 120));
  h.emit(snapshot(999, conversation("A", 2)), 0);
  h.connections[0].callbacks.onClose?.();
  t.mock.timers.tick(30);
  assert.equal(completion(h.stream.getSnapshot().detail), 120);
  assert.equal(h.stream.getSnapshot().status, "live");
  t.mock.timers.tick(30000);
  assert.equal(h.connections.length, 2, "late close cannot schedule a parallel retry");
});

test("nodes require this connection's snapshot, even with a retained tree from the prior connection", (t) => {
  const h = harness(t);
  h.emit(snapshot());
  h.connections[0].callbacks.onClose?.();
  t.mock.timers.tick(1000);
  h.emit(update(1));
  assert.equal(h.stream.getSnapshot().status, "retrying");
  assert.equal(completion(h.stream.getSnapshot().detail), 10);
  t.mock.timers.tick(2000);
  h.emit(snapshot());
  assert.equal(h.stream.getSnapshot().status, "live");
});

test("node seq gaps and impossible node indices reconnect instead of silently overwriting/dropping", (t) => {
  const h = harness(t);
  h.emit(snapshot());
  h.emit(update(3));
  assert.equal(h.stream.getSnapshot().status, "retrying");
  t.mock.timers.tick(1000);
  h.emit(snapshot());
  h.emit(update(2, "missing", 8));
  t.mock.timers.tick(30);
  assert.equal(h.stream.getSnapshot().status, "retrying");
  assert.equal(h.stream.getSnapshot().detail?.messages.length, 2);
  t.mock.timers.tick(1000);
  h.emit(snapshot());
  h.emit(update(2, "wrong-id-at-existing-index", 0));
  t.mock.timers.tick(30);
  assert.equal(h.stream.getSnapshot().status, "retrying");
  assert.equal(h.stream.getSnapshot().detail?.messages[0].id, "n1");
});

for (const [code, status] of [
  [401, "unauthorized"],
  [403, "forbidden"],
  [404, "not-found"],
] as const) {
  test(`${code} clears private/deleted detail and queue, stops retries and ignores late callbacks`, (t) => {
    const h = harness(t);
    h.emit(snapshot());
    h.emit({ type: "queue_update", conversationId: "A", queue: { messages: [], paused: true } });
    h.emit(update(2));
    h.connections[0].callbacks.onError?.(Object.assign(new Error(`HTTP ${code}`), { code }));
    h.connections[0].callbacks.onClose?.();
    h.emit(snapshot(3));
    t.mock.timers.tick(60000);
    assert.equal(h.stream.getSnapshot().detail, null);
    assert.equal(h.stream.getSnapshot().queue, null);
    assert.equal(h.stream.getSnapshot().status, status);
    assert.equal(h.stream.getSnapshot().error, `HTTP ${code}`);
    assert.equal(h.connections.length, 1);
  });
}

test("first-connect failure stops loading, retries with backoff, and recovers only on snapshot", (t) => {
  const h = harness(t);
  h.connections[0].callbacks.onError?.(new Error("offline"));
  h.connections[0].callbacks.onClose?.();
  assert.equal(h.stream.getSnapshot().status, "retrying");
  assert.equal(h.stream.getSnapshot().error, "offline");
  assert.equal(h.stream.getSnapshot().detail, null);
  t.mock.timers.tick(999);
  assert.equal(h.connections.length, 1);
  t.mock.timers.tick(1);
  h.connections[1].callbacks.onOpen?.();
  h.connections[1].callbacks.onClose?.();
  t.mock.timers.tick(1000);
  assert.equal(h.connections.length, 2, "opening without a baseline must not reset retry backoff");
  t.mock.timers.tick(1000);
  assert.equal(h.connections.length, 3);
  h.emit(snapshot());
  assert.equal(h.stream.getSnapshot().status, "live");
  assert.equal(h.stream.getSnapshot().error, null);
});

test("display signature changes reconnect once and reproject from live SSE, never from REST", (t) => {
  const h = harness(t);
  h.stream.setDisplaySignature(null, "global");
  h.emit(snapshot(40, conversation("A", 100)));
  h.stream.setDisplaySignature("assistant", "v1");
  h.stream.setDisplaySignature("assistant", "v1");
  assert.equal(
    h.connections.length,
    1,
    "learning assistant and identical signatures do not reconnect",
  );
  h.emit(update(41, "n1", 0, 1));
  h.stream.setDisplaySignature("assistant", "v2");
  assert.equal(h.connections.length, 2);
  assert.ok(h.connections[0].signal.aborted);
  const projected = conversation("A", 100);
  projected.messages[0].messages[0].display = { "0": "new display" };
  h.emit(snapshot(1, projected));
  h.emit(snapshot(42, conversation("A", 1)), 0);
  t.mock.timers.tick(30);
  assert.equal(h.stream.getSnapshot().detail, projected);
  assert.equal(completion(projected), 100);
  h.stream.setDisplaySignature("assistant", "v2");
  assert.equal(h.connections.length, 2);
});

test("queue, cache, compression and generation errors are connection/identity scoped", (t) => {
  const h = harness(t);
  const queue = {
    messages: [{ id: "q", parts: [], answer: true, isEditing: false }],
    paused: true,
  };
  // These events are merged independently by the server and can precede its first conversation snapshot.
  h.emit({ type: "queue_update", conversationId: "A", queue });
  assert.equal(h.stream.getSnapshot().queue, queue);
  h.emit(snapshot());
  h.emit({ type: "queue_update", conversationId: "B", queue: { messages: [], paused: false } });
  assert.equal(h.stream.getSnapshot().queue, queue);
  h.emit({
    type: "compress_state",
    conversationId: "A",
    status: "running",
    startedAt: 100,
    origin: "web",
    createNewConversation: true,
  });
  h.emit({
    type: "cache_warm",
    conversationId: "A",
    state: "scheduled",
    nextWarmAt: 2000,
    serverTime: 1000,
  });
  assert.equal(h.stream.getSnapshot().compressState?.status, "running");
  assert.equal(h.stream.getSnapshot().compressState?.createNewConversation, true);
  h.emit({
    type: "compress_state",
    conversationId: "A",
    status: "succeeded",
    startedAt: 100,
    finishedAt: 120,
    origin: "web",
    createNewConversation: true,
    targetConversationId: "new-conv-1",
  });
  assert.equal(h.stream.getSnapshot().compressState?.targetConversationId, "new-conv-1");
  assert.equal(h.stream.getSnapshot().cacheWarm?.nextWarmAt, 2000);
  h.emit({ type: "error", message: "generation failed" });
  assert.deepEqual(h.errors, ["generation failed"]);
  assert.equal(
    h.stream.getSnapshot().status,
    "live",
    "generation errors aren't transport failures",
  );
  h.connections[0].callbacks.onClose?.();
  t.mock.timers.tick(1000);
  h.emit({ type: "queue_update", conversationId: "A", queue: { messages: [], paused: false } });
  h.emit({ type: "queue_update", conversationId: "A", queue }, 0);
  h.emit({ type: "error", message: "late error" }, 0);
  assert.equal(h.stream.getSnapshot().queue?.messages.length, 0);
  assert.deepEqual(h.errors, ["generation failed"]);
  assert.equal(h.stream.getSnapshot().compressState, null);
  assert.equal(h.stream.getSnapshot().cacheWarm, null);
});

test("compress suggestions are scoped and cleared, and errors carry their solution", (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 1000 });
  const connections: Array<{ callbacks: SSECallbacks<unknown> }> = [];
  const errors: Array<[string, string | null]> = [];
  const stream = createConversationStream("A", {
    connect: (callbacks) => {
      connections.push({ callbacks });
    },
    onSummary: () => {},
    onClock: () => {},
    onGenerationError: (message, solution) => errors.push([message, solution]),
  });
  stream.start();
  t.after(stream.stop);
  const emit = <T extends { type: string }>(data: T) =>
    connections.at(-1)!.callbacks.onMessage({ event: data.type, data });
  emit(snapshot());
  emit({ type: "compress_suggestion", conversationId: "B", percent: 90, exact: true });
  assert.equal(stream.getSnapshot().compressSuggestion, null, "another conversation's hint is ignored");
  emit({ type: "compress_suggestion", conversationId: "A", percent: 83, exact: false });
  assert.deepEqual(stream.getSnapshot().compressSuggestion, { percent: 83, exact: false });
  emit({ type: "compress_suggestion", conversationId: "A", percent: null, exact: false });
  assert.equal(stream.getSnapshot().compressSuggestion, null);
  emit({ type: "error", message: "too long", solution: "compress_and_retry" });
  emit({ type: "error", message: "plain" });
  assert.deepEqual(errors, [["too long", "compress_and_retry"], ["plain", null]]);
  emit({ type: "compress_suggestion", conversationId: "A", percent: 85, exact: true });
  connections[0].callbacks.onClose?.();
  t.mock.timers.tick(1000);
  assert.equal(stream.getSnapshot().compressSuggestion, null, "a reconnect starts without a stale hint");
});

test("legitimate regeneration, branch selection and rollback may decrease usage", (t) => {
  const h = harness(t);
  h.emit(snapshot(1, conversation("A", 100)));
  const branch = update(2, "n1", 0, 8);
  branch.node.messages.unshift(node("other-branch", 1).messages[0]);
  branch.node.selectIndex = 1;
  h.emit(branch);
  t.mock.timers.tick(30);
  assert.equal(h.stream.getSnapshot().detail?.messages[0].selectIndex, 1);
  assert.equal(h.stream.getSnapshot().detail?.messages[0].messages[1].usage?.completionTokens, 8);
  h.emit(snapshot(3, conversation("A", 2)));
  assert.equal(
    completion(h.stream.getSnapshot().detail),
    2,
    "no max() guard or statistics field merging",
  );
});

test("stop invalidates both pending flush and retry; strict-effect restart gets a new baseline", (t) => {
  const h = harness(t);
  h.emit(snapshot(50));
  h.emit(update(51, "n1", 0, 99));
  h.stream.stop();
  h.connections[0].callbacks.onClose?.();
  t.mock.timers.tick(30000);
  assert.equal(completion(h.stream.getSnapshot().detail), 10);
  assert.equal(h.connections.length, 1);
  h.stream.start();
  h.emit(snapshot(1, conversation("A", 20)));
  h.emit(snapshot(100, conversation("A", 1)), 0);
  assert.equal(completion(h.stream.getSnapshot().detail), 20);
  h.connections[1].callbacks.onClose?.();
  h.stream.stop();
  t.mock.timers.tick(30000);
  assert.equal(h.connections.length, 2);
});

test("home has no stream or loading state", (t) => {
  const h = harness(t, null);
  assert.equal(h.connections.length, 0);
  assert.equal(h.stream.getSnapshot().status, "idle");
});

test("real ky/SSE decoder -> controller: fragmented frames, snapshot barrier, EOF retry and HTTP 404", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let pipe!: ReadableStreamDefaultController<Uint8Array>;
  let calls = 0;
  const stream = createConversationStream("A", {
    connect: (callbacks, signal) =>
      sse("conversations/A/stream", callbacks, {
        signal,
        prefixUrl: "http://offline.test/api",
        fetch: async () => {
          if (++calls > 1)
            return new Response(JSON.stringify({ error: "deleted", code: 404 }), { status: 404 });
          return new Response(
            new ReadableStream<Uint8Array>({
              start: (controller) => {
                pipe = controller;
              },
            }),
            {
              headers: { "Content-Type": "text/event-stream" },
            },
          );
        },
      }),
    onSummary: () => {},
    onClock: () => {},
    onGenerationError: () => {},
  });
  t.after(stream.stop);
  stream.start();
  await settle();
  const frame = (data: { type: string }) =>
    `event: ${data.type}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`;
  const initial = frame(snapshot());
  pipe.enqueue(new TextEncoder().encode(initial.slice(0, 10)));
  pipe.enqueue(new TextEncoder().encode(initial.slice(10)));
  await settle();
  assert.equal(stream.getSnapshot().status, "live");
  pipe.enqueue(
    new TextEncoder().encode(
      frame(update(2, "n1", 0, 1)) + frame(snapshot(3, conversation("A", 100))),
    ),
  );
  await settle();
  t.mock.timers.tick(30);
  assert.equal(completion(stream.getSnapshot().detail), 100);
  pipe.close();
  await settle();
  assert.equal(stream.getSnapshot().status, "retrying");
  assert.equal(completion(stream.getSnapshot().detail), 100);
  t.mock.timers.tick(1000);
  await settle();
  assert.equal(stream.getSnapshot().status, "not-found");
  assert.equal(stream.getSnapshot().detail, null);
  t.mock.timers.tick(60000);
  assert.equal(calls, 2);
});

test("real SSE HTTP 401 becomes terminal rather than an endless loading/retry loop", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let calls = 0;
  const stream = createConversationStream("A", {
    connect: (callbacks, signal) =>
      sse("conversations/A/stream", callbacks, {
        signal,
        prefixUrl: "http://offline.test/api",
        fetch: async () => {
          calls++;
          return new Response(JSON.stringify({ error: "login required", code: 401 }), {
            status: 401,
          });
        },
      }),
    onSummary: () => {},
    onClock: () => {},
    onGenerationError: () => {},
  });
  t.after(stream.stop);
  stream.start();
  await settle();
  assert.equal(stream.getSnapshot().status, "unauthorized");
  assert.equal(stream.getSnapshot().error, "login required");
  t.mock.timers.tick(60000);
  assert.equal(calls, 1);
});

test("stateful errors event replays offline errors across reconnect, clears on empty list, and isolates conversations", (t) => {
  const h = harness(t);
  const offlineError = {
    id: "err-1",
    message: "upstream 502 bad gateway",
    title: "Generate failed",
    solution: "compress_and_retry",
    timestamp: 1234,
    affectsReply: true,
  };
  h.emit({ type: "errors", conversationId: "B", errors: [offlineError] });
  assert.deepEqual(h.stream.getSnapshot().generationErrors, []);

  h.emit({ type: "errors", conversationId: "A", errors: [offlineError] });
  assert.deepEqual(h.stream.getSnapshot().generationErrors, [offlineError]);

  h.emit(snapshot());
  // Transient disconnect & reconnect must not clear existing errors before the server replays its state.
  h.connections[0].callbacks.onClose?.();
  t.mock.timers.tick(1000);
  assert.deepEqual(h.stream.getSnapshot().generationErrors, [offlineError]);

  // Server sends empty list once errors are dismissed or cleared on next turn.
  h.emit({ type: "errors", conversationId: "A", errors: [] });
  assert.deepEqual(h.stream.getSnapshot().generationErrors, []);

  // Re-populate and verify terminal 404 clears generationErrors along with detail.
  h.emit({ type: "errors", conversationId: "A", errors: [offlineError] });
  h.connections[1].callbacks.onError?.(Object.assign(new Error("gone"), { code: 404 }));
  assert.deepEqual(h.stream.getSnapshot().generationErrors, []);
});
