/**
 * 聊天框润色：SSE 客户端、按钮状态机（与 App ChatInputPolisher 同规则）、模型解析，
 * 以及输入框里润色按钮 / 快捷消息按钮的位置。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/prompt-polish.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import {
  createPromptPolishController,
  parsePromptPolishEvent,
  resolvePromptPolishModel,
  streamPromptPolish,
  type PromptPolishEvent,
  type PromptPolishState,
} from "../app/overlay/lib/prompt-polish";
import { ChatInput } from "../app/components/input/chat-input";
import { useSettingsStore } from "../app/stores";
import enInput from "../app/locales/en-US/input.json";

// ---------- SSE ----------

function sseResponse(body: string, status = 200): Response {
  return new Response(new TextEncoder().encode(body), {
    status,
    headers: { "Content-Type": status === 200 ? "text/event-stream" : "application/json" },
  });
}

test("event parsing", () => {
  assert.deepEqual(parsePromptPolishEvent("delta", '{"text":"a"}'), { type: "delta", text: "a" });
  assert.deepEqual(parsePromptPolishEvent("done", '{"text":"ab"}'), { type: "done", text: "ab" });
  assert.deepEqual(parsePromptPolishEvent("error", '{"code":"NO_MODEL","message":"NO_MODEL"}'),
    { type: "error", code: "NO_MODEL", message: "NO_MODEL" });
  assert.deepEqual(parsePromptPolishEvent("error", "{}"), { type: "error", code: "MODEL_ERROR", message: "" });
  assert.equal(parsePromptPolishEvent("delta", "{}"), undefined);
  assert.equal(parsePromptPolishEvent("other", '{"text":"a"}'), undefined);
  assert.equal(parsePromptPolishEvent("delta", "not json"), undefined);
});

test("stream posts text + conversation id and relays events", async () => {
  let sent: { url: string; body: unknown } | null = null;
  const events: PromptPolishEvent[] = [];
  await streamPromptPolish({ text: "原文", conversationId: "c1" }, (event) => events.push(event), {
    fetchImpl: (async (url: string, init: RequestInit) => {
      sent = { url, body: JSON.parse(String(init.body)) };
      return sseResponse(': hb\n\nevent: delta\ndata: {"text":"润"}\n\nevent: done\ndata: {"text":"润色"}\n\n');
    }) as typeof fetch,
  });
  assert.deepEqual(sent, { url: "/api/prompt-polish", body: { text: "原文", conversationId: "c1" } });
  assert.deepEqual(events, [{ type: "delta", text: "润" }, { type: "done", text: "润色" }]);
});

test("stream turns HTTP errors and early ends into error events", async () => {
  const http: PromptPolishEvent[] = [];
  await streamPromptPolish({ text: "x" }, (event) => http.push(event), {
    fetchImpl: (async () => sseResponse('{"error":"\'text\' is too long"}', 400)) as typeof fetch,
  });
  assert.deepEqual(http, [{ type: "error", code: "HTTP_400", message: "'text' is too long" }]);
  const early: PromptPolishEvent[] = [];
  await streamPromptPolish({ text: "x" }, (event) => early.push(event), {
    fetchImpl: (async () => sseResponse('event: delta\ndata: {"text":"a"}\n\n')) as typeof fetch,
  });
  assert.equal(early.at(-1)?.type, "error");
});

// ---------- controller ----------

function harness(script: (emit: (event: PromptPolishEvent) => void, signal: AbortSignal) => Promise<void>) {
  const box = { value: "原文" };
  const states: PromptPolishState[] = [];
  const errors: string[] = [];
  const commits: Array<[string, string]> = [];
  const controller = createPromptPolishController({
    read: () => box.value,
    write: (text) => { box.value = text; },
    commit: (original, polished) => { commits.push([original, polished]); box.value = polished; },
    stream: (_text, onEvent, signal) => script(onEvent, signal),
    onError: (code) => errors.push(code),
    onState: (state) => states.push(state),
  });
  return { box, states, errors, commits, controller };
}

test("polish streams into the box, commits the final text, undo restores", async () => {
  const h = harness(async (emit) => {
    emit({ type: "delta", text: "润" });
    assert.equal(h.box.value, "润");
    emit({ type: "done", text: "润色后" });
  });
  await h.controller.start();
  assert.deepEqual(h.commits, [["原文", "润色后"]]);
  assert.deepEqual(h.controller.state, { phase: "done", original: "原文", polished: "润色后" });
  h.controller.textChanged("润色后");
  assert.equal(h.controller.state.phase, "done");
  h.controller.undo();
  assert.equal(h.box.value, "原文");
  assert.equal(h.controller.state.phase, "idle");
});

test("editing after polishing drops undo; blank input does nothing", async () => {
  const h = harness(async (emit) => emit({ type: "done", text: "结果" }));
  await h.controller.start();
  h.controller.textChanged("结果。");
  assert.equal(h.controller.state.phase, "idle");
  const blank = harness(async () => assert.fail("should not stream"));
  blank.box.value = "   ";
  await blank.controller.start();
  assert.equal(blank.states.length, 0);
});

test("cancel restores the original; errors restore and report", async () => {
  const cancelled = harness((emit, signal) => new Promise((resolve) => {
    emit({ type: "delta", text: "写到一半" });
    signal.addEventListener("abort", () => resolve());
  }));
  const run = cancelled.controller.start();
  assert.equal(cancelled.controller.state.phase, "running");
  assert.equal(cancelled.box.value, "写到一半");
  cancelled.controller.cancel();
  await run;
  assert.equal(cancelled.box.value, "原文");
  assert.equal(cancelled.controller.state.phase, "idle");
  assert.deepEqual(cancelled.errors, []);

  const failed = harness(async (emit) => {
    emit({ type: "delta", text: "半" });
    emit({ type: "error", code: "MODEL_ERROR", message: "boom" });
  });
  await failed.controller.start();
  assert.equal(failed.box.value, "原文");
  assert.deepEqual(failed.errors, ["MODEL_ERROR"]);

  const empty = harness(async () => {});
  await empty.controller.start();
  assert.deepEqual(empty.errors, ["EMPTY_RESULT"]);
  assert.equal(empty.box.value, "原文");
});

test("detach before any output restores nothing", async () => {
  const h = harness((_emit, signal) => new Promise((resolve) => signal.addEventListener("abort", () => resolve())));
  const run = h.controller.start();
  const restored: string[] = [];
  h.controller.detach((original) => restored.push(original));
  await run;
  assert.deepEqual(restored, []);
  assert.equal(h.box.value, "原文");
});

test("detach stops without writing back (another draft is showing)", async () => {
  const h = harness((emit, signal) => new Promise((resolve) => {
    emit({ type: "delta", text: "半" });
    signal.addEventListener("abort", () => {
      emit({ type: "done", text: "迟到的结果" });
      resolve();
    });
  }));
  const run = h.controller.start();
  h.box.value = "另一个会话的草稿";
  const restored: string[] = [];
  h.controller.detach((original) => restored.push(original));
  await run;
  assert.equal(h.box.value, "另一个会话的草稿");
  assert.deepEqual(restored, ["原文"], "the half-written draft gets its original back");
  assert.equal(h.controller.state.phase, "idle");
  assert.deepEqual(h.commits, []);
});

test("model resolution follows the server: chosen → assistant → global", () => {
  const settings = {
    chatModelId: "g",
    providers: [{ models: [{ id: "g", displayName: "Global" }, { id: "a", displayName: "Assistant" }, { id: "p", displayName: "Polish" }] }],
    promptPolish: { modelId: "p" },
  };
  assert.equal(resolvePromptPolishModel(settings, "a")?.displayName, "Polish");
  assert.equal(resolvePromptPolishModel({ ...settings, promptPolish: { modelId: "gone" } }, "a")?.displayName, "Assistant");
  assert.equal(resolvePromptPolishModel({ ...settings, promptPolish: undefined }, null)?.displayName, "Global");
  assert.equal(resolvePromptPolishModel(null, null), null);
});

// ---------- composer markup ----------

await i18next.use(initReactI18next).init({
  lng: "en-US",
  defaultNS: "input",
  resources: { "en-US": { input: enInput } },
  interpolation: { escapeValue: false },
});

const assistantId = "11111111-1111-4111-8111-111111111111";
useSettingsStore.setState({
  settings: {
    assistantId,
    chatModelId: "m1",
    displaySetting: { sendOnEnter: true },
    assistants: [{ id: assistantId, name: "A", chatModelId: "m1", quickMessageIds: ["q1"] }],
    providers: [{
      id: "p1", type: "openai", enabled: true, name: "P",
      models: [
        { id: "m1", modelId: "chat-1", displayName: "Chat One", type: "CHAT", abilities: [] },
        { id: "m2", modelId: "polish-1", displayName: "Polisher", type: "CHAT", abilities: [] },
      ],
    }],
    promptPolish: { modelId: "m2" },
    quickMessages: [{ id: "q1", title: "Hi", content: "Hello" }],
    modeInjections: [],
    lorebooks: [],
    mcpServers: [],
    searchServices: [],
  },
} as never);
Object.assign(useSettingsStore.getInitialState(), { settings: useSettingsStore.getState().settings });

function renderInput(value: string): string {
  return renderToStaticMarkup(
    createElement(
      QueryClientProvider,
      { client: new QueryClient() },
      createElement(
        MemoryRouter,
        {},
        createElement(ChatInput, {
          value,
          attachments: [],
          onValueChange: () => {},
          onAddParts: () => {},
          onRemovePart: () => {},
          onSend: () => {},
          onSendNoAnswer: () => {},
        }),
      ),
    ),
  );
}

function polishButton(html: string): string {
  const match = html.match(/<button[^>]*data-testid="chat-polish-button"[^>]*>/);
  assert.ok(match, "polish button rendered");
  return match[0];
}

test("polish button sits right before send, names the polish model, is disabled when empty", () => {
  const empty = renderInput("");
  assert.match(polishButton(empty), /disabled=""/);
  const typed = renderInput("fix this");
  const button = polishButton(typed);
  assert.doesNotMatch(button, /disabled=""/);
  assert.match(button, /title="Polish \(Polisher\)"/);
  const polishAt = typed.indexOf('data-testid="chat-polish-button"');
  const sendAt = typed.indexOf(`aria-label="${enInput.chat.send}"`);
  assert.ok(polishAt > 0 && sendAt > polishAt, "polish comes before send");
  assert.ok(typed.indexOf("Chat One") < polishAt, "model picker stays left of polish");
});

test("quick messages moved from the toolbar into the input box", () => {
  const html = renderInput("x");
  const textareaAt = html.indexOf("<textarea");
  const zapAt = html.indexOf("lucide-zap");
  assert.ok(zapAt > 0 && zapAt < textareaAt, "quick message button precedes the textarea in the same row");
  assert.equal(html.split("lucide-zap").length - 1, 1, "only one quick message button");
});
