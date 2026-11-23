import test, { type TestContext } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import ts from "typescript";
import { createConversationStream } from "../app/lib/conversation-stream";
import { displayScriptsSignature } from "../app/overlay/lib/tavern";
import type { SSECallbacks } from "../app/services/api";
import { conversation, deferred, snapshot, update } from "./helpers/conversation-fixtures";

const route = "routes/conversations.tsx";
const settingsMenu = "components/conversation-settings-menu.tsx";
const workspacePicker = "components/input/workspace-picker.tsx";
const queuePanel = "components/conversation-queue-panel.tsx";

/**
 * Execute the actual production hook/callback bodies, not copies or source-string assertions.
 * A small deterministic effect-boundary runner avoids adding a DOM/test-renderer dependency.
 * This tests wiring, not browser layout; the real stream controller is imported above.
 */
function executable(path: string, name: string, bindings: Record<string, unknown>) {
  const source = ts.createSourceFile(
    path,
    readFileSync(new URL(`../app/${path}`, import.meta.url), "utf8"),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX,
  );
  const matches: ts.Node[] = [];
  const visit = (node: ts.Node) => {
    if (ts.isFunctionDeclaration(node) && node.name?.text === name) matches.push(node);
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === name)
      matches.push(node);
    ts.forEachChild(node, visit);
  };
  visit(source);
  assert.equal(matches.length, 1, `${path}: unique executable ${name}`);
  const node = matches[0];
  const code = ts.isVariableDeclaration(node)
    ? `const ${name} = ${node.initializer!.getText(source)};`
    : node.getText(source);
  const compiled = ts.transpileModule(`${code}\nreturn ${name};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText;
  return new Function(...Object.keys(bindings), compiled)(...Object.values(bindings));
}

function hookRuntime() {
  const slots: Array<{ value?: any; deps?: unknown[]; cleanup?: () => void }> = [];
  let cursor = 0;
  let effects: Array<() => void> = [];
  const changed = (old: unknown[] | undefined, deps: unknown[]) =>
    !old || old.length !== deps.length || deps.some((value, i) => !Object.is(value, old[i]));
  const React = {
    useRef: (value: unknown) => {
      const index = cursor++;
      slots[index] ??= { value: { current: value } };
      return slots[index].value;
    },
    useMemo: (fn: () => unknown, deps: unknown[]) => {
      const index = cursor++;
      if (changed(slots[index]?.deps, deps)) slots[index] = { value: fn(), deps };
      return slots[index].value;
    },
    useCallback: (fn: unknown) => fn,
    useSyncExternalStore: (_subscribe: unknown, getSnapshot: () => unknown) => getSnapshot(),
    useEffect: (fn: () => (() => void) | void, deps: unknown[]) => {
      const index = cursor++;
      const previous = slots[index];
      if (!changed(previous?.deps, deps)) return;
      slots[index] = { deps, cleanup: previous?.cleanup };
      effects.push(() => {
        previous?.cleanup?.();
        slots[index].cleanup = fn() || undefined;
      });
    },
  };
  return {
    React,
    begin: () => {
      cursor = 0;
    },
    commit: () => {
      const pending = effects;
      effects = [];
      pending.forEach((fn) => fn());
    },
    unmount: () => slots.forEach((slot) => slot.cleanup?.()),
  };
}

function hookHarness(t: TestContext) {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const hooks = hookRuntime();
  const connections: Array<{ url: string; callbacks: SSECallbacks<unknown>; signal: AbortSignal }> =
    [];
  const gets: string[] = [];
  const oldGet = deferred<unknown>();
  const summaries: unknown[] = [];
  const errors: string[] = [];
  const settings: any = { tavern: { regexScripts: [] }, assistants: [] };
  const translate = (key: string) => key;
  const hook = executable(route, "useConversationDetail", {
    React: hooks.React,
    createConversationStream,
    displayScriptsSignature,
    useTranslation: () => ({ t: translate }),
    useSettingsStore: (select: (state: unknown) => unknown) => select({ settings }),
    useAppStore: { getState: () => ({ setClockOffset: () => {} }) },
    toast: { error: (message: string) => errors.push(message) },
    toConversationSummaryUpdate: (detail: unknown) => detail,
    api: {
      get: (url: string) => {
        gets.push(url);
        return oldGet.promise;
      },
    },
    sse: (url: string, callbacks: SSECallbacks<unknown>, { signal }: { signal: AbortSignal }) => {
      connections.push({ url, callbacks, signal });
      return Promise.resolve();
    },
  });
  let activeId: string | null = "A";
  const render = (id = activeId, commit = true) => {
    activeId = id;
    hooks.begin();
    const result = hook(id, (summary: unknown) => summaries.push(summary));
    if (commit) hooks.commit();
    return result;
  };
  const emit = <T extends { type: string }>(data: T, index = connections.length - 1) =>
    connections[index].callbacks.onMessage({ event: data.type, data });
  t.after(hooks.unmount);
  return { ...hooks, connections, gets, oldGet, summaries, errors, settings, render, emit };
}

test("actual hook loads/reconnects solely from SSE; delayed REST success/failure cannot roll back final usage", async (t) => {
  const h = hookHarness(t);
  assert.equal(h.render().detailLoading, true);
  h.emit(snapshot(1, conversation("A", 100)));
  const final = h.render().detail;
  assert.equal(final.messages[0].messages[0].usage.completionTokens, 100);
  h.oldGet.resolve(conversation("A", 10));
  await Promise.resolve();
  assert.equal(h.render().detail, final);
  assert.deepEqual(h.gets, [], "there is no initial REST detail OR queue request to compete");
  h.connections[0].callbacks.onClose?.();
  const disconnected = h.render();
  assert.equal(disconnected.detail, final);
  assert.equal(
    disconnected.detailError,
    null,
    "a reconnect must not hide the last displayed messages",
  );
  assert.equal(disconnected.syncing, true);
  assert.ok(disconnected.syncNotice);
  t.mock.timers.tick(1000);
  h.emit(snapshot(1, conversation("A", 110)));
  assert.equal(h.render().detail.messages[0].messages[0].usage.completionTokens, 110);
  assert.deepEqual(h.gets, [], "reconnect also has no REST request");
  assert.equal("setDetail" in h.render(), false);
  assert.equal("setQueue" in h.render(), false);
});

test("old GET failure cannot clear an SSE baseline (no fallback request exists)", async (t) => {
  const h = hookHarness(t);
  h.render();
  h.emit(snapshot(1, conversation("A", 100)));
  const final = h.render().detail;
  h.oldGet.reject(new Error("old database request failed"));
  await Promise.resolve();
  assert.equal(h.render().detail, final);
  assert.equal(h.render().detailError, null);
  assert.deepEqual(h.gets, []);
});

test("hook masks old detail DURING render on A -> B -> A, and invalidates every old connection callback", (t) => {
  const h = hookHarness(t);
  h.render("A");
  h.emit(snapshot(1, conversation("A", 100)));
  h.emit(update(2, "n1", 0, 1));
  const b = h.render("B", false);
  assert.equal(b.detail, null, "not waiting for useEffect to hide A");
  assert.equal(b.queue, null);
  h.commit();
  assert.ok(h.connections[0].signal.aborted);
  h.emit(snapshot(1, conversation("B", 200)));
  const a = h.render("A", false);
  assert.equal(a.detail, null, "a new visit must not revive A's old cached tree");
  h.commit();
  h.emit(snapshot(1, conversation("A", 300)));
  const current = h.render().detail;
  const summaryCount = h.summaries.length;
  h.emit(snapshot(1000, conversation("A", 10)), 0);
  h.connections[0].callbacks.onError?.(Object.assign(new Error("old auth"), { code: 401 }));
  h.connections[1].callbacks.onClose?.();
  t.mock.timers.tick(30000);
  assert.equal(h.render().detail, current);
  assert.equal(h.summaries.length, summaryCount);
  assert.equal(h.connections.length, 3);
});

test("actual hook watches display signatures, not arbitrary settings identity or SSE detail updates", (t) => {
  const h = hookHarness(t);
  h.render();
  h.emit(snapshot(1, conversation("A", 100)));
  h.render();
  h.settings.unrelatedPreference = true;
  h.render();
  assert.equal(h.connections.length, 1);
  h.settings.tavern.regexScripts = [
    {
      id: "r",
      markdownOnly: true,
      disabled: false,
      findRegex: "reply",
      replaceString: "projected",
    },
  ];
  h.render();
  assert.equal(h.connections.length, 2);
  const projected = conversation("A", 100);
  projected.messages[0].messages[0].display = { "0": "projected" };
  h.emit(snapshot(1, projected));
  assert.equal(h.render().detail, projected);
  h.render();
  assert.equal(h.connections.length, 2);
  assert.deepEqual(h.gets, []);
});

test("actual hook distinguishes first-connect failure and terminal auth/not-found from loading", (t) => {
  const h = hookHarness(t);
  h.render();
  h.connections[0].callbacks.onError?.(new Error("offline"));
  let view = h.render();
  assert.equal(view.detailLoading, false);
  assert.equal(view.detailError, "offline");
  t.mock.timers.tick(1000);
  h.connections[1].callbacks.onError?.(Object.assign(new Error("not found"), { code: 404 }));
  view = h.render();
  assert.equal(view.detailLoading, false);
  assert.equal(view.detail, null);
  assert.equal(view.syncStatus, "not-found");
  t.mock.timers.tick(30000);
  assert.equal(h.connections.length, 2);
});

test("useConversationDetail exposes stateful generationErrors and clears them across A -> B -> A", (t) => {
  const h = hookHarness(t);
  h.render("A");
  const err = {
    id: "err-offline",
    message: "provider timeout",
    title: "Reply failed",
    solution: null,
    timestamp: 500,
    affectsReply: true,
  };
  h.emit({ type: "errors", conversationId: "A", errors: [err] });
  assert.deepEqual(h.render("A").generationErrors, [err]);
  const b = h.render("B", false);
  assert.deepEqual(b.generationErrors, []);
  h.commit();
  const aAgain = h.render("A", false);
  assert.deepEqual(aAgain.generationErrors, []);
});

for (const [path, name, args, response] of [
  [settingsMenu, "updateConversation", [{ customSystemPrompt: "new" }], conversation("A", 10)],
  [
    workspacePicker,
    "updateConversation",
    [{ workspaceCwd: "/workspace/new" }],
    conversation("A", 10),
  ],
  [route, "handleToggleFavorite", ["n1"], { isFavorite: false }],
  [route, "handleClearTranslation", ["m-n1"], {}],
] as const) {
  test(`${path} ${name}: delayed mutation acknowledgements never overwrite SSE or a later A visit`, async (t) => {
    const h = hookHarness(t);
    h.render();
    h.emit(snapshot());
    const responseGate = deferred<unknown>();
    const calls: unknown[] = [];
    const forbiddenWrite = () => {
      throw new Error("mutation tried to write conversation state");
    };
    const command = executable(path, name, {
      React: h.React,
      activeId: "A",
      conversation: conversation("A", 10),
      api: Object.fromEntries(
        ["put", "post", "delete"].map((method) => [
          method,
          (...params: unknown[]) => {
            calls.push([method, ...params]);
            return responseGate.promise;
          },
        ]),
      ),
      t: (key: string) => key,
      toast: { error: forbiddenWrite },
      clearTranslating: () => {},
      setDetail: forbiddenWrite,
      onUpdated: forbiddenWrite,
      onConversationUpdated: forbiddenWrite,
    });
    const pending = command(...args);
    h.render("B");
    h.emit(snapshot(1, conversation("B", 200)));
    h.render("A");
    const latest = conversation("A", 300);
    latest.messages[0].isFavorite = true;
    latest.messages[0].messages[0].translation = "a newer translation";
    h.emit(snapshot(1, latest));
    responseGate.resolve(response);
    await pending;
    assert.equal(h.render().detail, latest);
    assert.equal(calls.length, 1, "command still reaches the server exactly once");
  });
}

for (const [name, args] of [
  ["handleRemove", ["q"]],
  ["handleResume", []],
  ["handleBeginEdit", ["q"]],
  ["unlock", [null]],
] as const) {
  test(`queue ${name}: only SSE changes the displayed queue; returned data is at most an editing draft`, async (t) => {
    const h = hookHarness(t);
    h.render();
    h.emit(snapshot());
    const oldQueue = {
      messages: [{ id: "q", parts: [], answer: true, isEditing: true }],
      paused: true,
    };
    const newQueue = {
      messages: [{ id: "new", parts: [], answer: true, isEditing: false }],
      paused: false,
    };
    const gate = deferred<unknown>();
    let draft: unknown = null;
    const forbiddenWrite = () => {
      throw new Error("queue REST writeback");
    };
    const command = executable(queuePanel, name, {
      React: h.React,
      conversationId: "A",
      message: oldQueue.messages[0],
      queue: oldQueue,
      disabled: false,
      pendingAction: null,
      mounted: { current: true },
      unlockedRef: { current: false },
      api: { post: () => gate.promise, delete: () => gate.promise },
      setPendingAction: () => {},
      setEditingMessage: (message: unknown) => {
        draft = message;
      },
      onQueueChange: forbiddenWrite,
      onFinished: forbiddenWrite,
      refreshQueue: forbiddenWrite,
      t: (key: string) => key,
      toast: { error: forbiddenWrite },
    });
    const pending = command(...args);
    h.emit({ type: "queue_update", conversationId: "A", queue: newQueue });
    gate.resolve(oldQueue);
    await pending;
    assert.equal(h.render().queue, newQueue);
    if (name === "handleBeginEdit") assert.equal(draft, oldQueue.messages[0]);
  });
}

test("leaving during queue begin-edit releases the server lock without opening an old dialog", async () => {
  const gate = deferred<unknown>();
  const mounted = { current: true };
  const calls: Array<[string, unknown]> = [];
  const forbidden = () => {
    throw new Error("late queue write");
  };
  const beginEdit = executable(queuePanel, "handleBeginEdit", {
    React: { useCallback: (fn: unknown) => fn },
    conversationId: "A",
    disabled: false,
    pendingAction: null,
    mounted,
    api: {
      post: (url: string, body?: unknown) => {
        calls.push([url, body]);
        return calls.length === 1 ? gate.promise : Promise.resolve({});
      },
    },
    setPendingAction: () => {},
    setEditingMessage: forbidden,
    onQueueChange: forbidden,
    t: (key: string) => key,
    toast: { error: forbidden },
  });
  const pending = beginEdit("q");
  mounted.current = false;
  gate.resolve({ messages: [{ id: "q", parts: [], answer: true, isEditing: true }], paused: true });
  await pending;
  assert.deepEqual(calls, [
    ["conversations/A/queue/q/begin-edit", undefined],
    ["conversations/A/queue/q/finish-edit", { parts: null }],
  ]);
});
