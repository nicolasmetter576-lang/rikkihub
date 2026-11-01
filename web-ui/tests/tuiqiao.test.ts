import test from "node:test";
import assert from "node:assert/strict";

import {
  parseTuiqiaoEvent,
  streamTuiqiao,
  tuiqiaoChange,
  tuiqiaoEnabledFor,
  tuiqiaoRolesPatch,
  tuiqiaoSelectionUsable,
  TUIQIAO_DEFAULT_EXTENSIONS,
  TUIQIAO_MAX_SELECTION_CHARS,
  type TuiqiaoEvent,
} from "../app/overlay/lib/tuiqiao";

test("parse: prepared, candidate, slot_error, done and error events", () => {
  assert.deepEqual(parseTuiqiaoEvent("prepared", '{"start":3,"end":4,"original":"推","mode":"WORD"}'), {
    type: "prepared", start: 3, end: 4, original: "推", mode: "WORD", format: "MARKDOWN",
    slots: [{ slot: "default", kind: "default" }],
  });
  assert.deepEqual(parseTuiqiaoEvent("prepared",
    '{"start":0,"end":5,"original":"他推开门。","mode":"SENTENCE","format":"PLAIN","slots":[' +
    '{"slot":"r1","kind":"role","roleId":"r1","name":"幽默旁白"},{"slot":"default","kind":"default","roleId":null,"name":null},{"bad":1}]}'), {
    type: "prepared", start: 0, end: 5, original: "他推开门。", mode: "SENTENCE", format: "PLAIN",
    slots: [
      { slot: "r1", kind: "role", roleId: "r1", name: "幽默旁白" },
      { slot: "default", kind: "default", roleId: null, name: null },
    ],
  });
  assert.deepEqual(parseTuiqiaoEvent("candidate", '{"slot":"r1","text":"敲","tone":"清脆","why":"有声"}'), {
    type: "candidate", slot: "r1", text: "敲", tone: "清脆", why: "有声",
  });
  assert.deepEqual(parseTuiqiaoEvent("candidate", '{"text":"叩"}'), { type: "candidate", slot: "default", text: "叩", tone: "", why: "" });
  assert.deepEqual(parseTuiqiaoEvent("slot_error", '{"slot":"r1","code":"MARKUP_BROKEN","message":"MARKUP_BROKEN"}'), {
    type: "slot_error", slot: "r1", code: "MARKUP_BROKEN", message: "MARKUP_BROKEN",
  });
  assert.equal(parseTuiqiaoEvent("slot_error", '{"code":"X"}'), undefined);
  assert.deepEqual(parseTuiqiaoEvent("done", "{}"), { type: "done" });
  assert.deepEqual(parseTuiqiaoEvent("error", '{"code":"NO_MODEL","message":"NO_MODEL"}'), {
    type: "error", code: "NO_MODEL", message: "NO_MODEL",
  });
  assert.deepEqual(parseTuiqiaoEvent("error", "{}"), { type: "error", code: "MODEL_ERROR", message: "" });
});

test("parse: rejects unknown events, bad JSON, empty text and bad modes", () => {
  assert.equal(parseTuiqiaoEvent("text", '{"text":"x"}'), undefined);
  assert.equal(parseTuiqiaoEvent("candidate", "{oops"), undefined);
  assert.equal(parseTuiqiaoEvent("candidate", '{"text":""}'), undefined);
  assert.equal(parseTuiqiaoEvent("prepared", '{"start":0,"end":1,"original":"a","mode":"LINE"}'), undefined);
  assert.equal(parseTuiqiaoEvent("prepared", '{"start":"0","end":1,"original":"a","mode":"WORD"}'), undefined);
});

test("enabled for: default list, custom list, case and paths", () => {
  assert.ok(tuiqiaoEnabledFor("小说.txt"));
  assert.ok(tuiqiaoEnabledFor("dir/第一章.MD"));
  assert.ok(tuiqiaoEnabledFor("ep1.srt", undefined));
  assert.ok(!tuiqiaoEnabledFor("data.json"));
  assert.ok(!tuiqiaoEnabledFor("README"));
  assert.ok(!tuiqiaoEnabledFor(undefined));
  assert.ok(!tuiqiaoEnabledFor("a.md.bak/notes"));
  assert.ok(tuiqiaoEnabledFor("data.json", ["json"]));
  assert.ok(!tuiqiaoEnabledFor("a.md", ["json"]));
  assert.ok(tuiqiaoEnabledFor("a.md", null));
  assert.ok(TUIQIAO_DEFAULT_EXTENSIONS.includes("fountain"));
});

test("roles patch drops the read-only modelMissing flag", () => {
  assert.deepEqual(
    tuiqiaoRolesPatch([{ id: "r1", name: "旁白", prompt: "p", modes: ["WORD"], modelId: null, enabled: true, modelMissing: true }]),
    [{ id: "r1", name: "旁白", prompt: "p", modes: ["WORD"], modelId: null, enabled: true }],
  );
});

test("selection usable: trimmed, non-empty and within the limit", () => {
  assert.ok(tuiqiaoSelectionUsable("僧推月下门", 1, 2));
  assert.ok(!tuiqiaoSelectionUsable("a   b", 1, 4));
  assert.ok(!tuiqiaoSelectionUsable("abc", 2, 2));
  assert.ok(!tuiqiaoSelectionUsable("abc", 0, 4));
  const long = "长".repeat(TUIQIAO_MAX_SELECTION_CHARS + 1);
  assert.ok(!tuiqiaoSelectionUsable(long, 0, long.length));
  assert.ok(tuiqiaoSelectionUsable(` ${long.slice(1)} `, 0, long.length + 1));
});

test("change: replaces only when the original is still there", () => {
  const prepared = { start: 1, end: 2, original: "推" };
  assert.deepEqual(tuiqiaoChange("僧推月下门", prepared, "敲"), { from: 1, to: 2, insert: "敲" });
  assert.equal(tuiqiaoChange("僧敲月下门", prepared, "叩"), null);
  assert.equal(tuiqiaoChange("僧", prepared, "叩"), null);
});

function sseResponse(body: string): Response {
  const bytes = new TextEncoder().encode(body);
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      // Split mid-character to exercise the streaming decoder.
      controller.enqueue(bytes.slice(0, 7));
      controller.enqueue(bytes.slice(7));
      controller.close();
    },
  });
  return new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

test("stream: forwards events in order and sends the request body", async () => {
  let sent: unknown;
  const fetchImpl = (async (_url: string, init: RequestInit) => {
    sent = JSON.parse(String(init.body));
    return sseResponse(
      ': hb\n\nevent: prepared\ndata: {"start":1,"end":2,"original":"推","mode":"WORD"}\n\n' +
        'event: candidate\ndata: {"text":"敲","tone":"清脆","why":""}\n\nevent: done\ndata: {}\n\n',
    );
  }) as unknown as typeof fetch;
  const events: TuiqiaoEvent[] = [];
  await streamTuiqiao({ fileName: "a.md", source: "僧推月下门", start: 1, end: 2, expand: "SENTENCE", slot: "r1" }, e => events.push(e), { fetchImpl });
  assert.deepEqual(sent, { fileName: "a.md", source: "僧推月下门", start: 1, end: 2, expand: "SENTENCE", slot: "r1" });
  assert.deepEqual(events.map(e => e.type), ["prepared", "candidate", "done"]);
});

test("stream: HTTP errors and early end become error events", async () => {
  const bad = (async () => new Response(JSON.stringify({ error: "Selection is empty" }), { status: 400 })) as unknown as typeof fetch;
  const events: TuiqiaoEvent[] = [];
  await streamTuiqiao({ fileName: "a.md", source: "x", start: 0, end: 1 }, e => events.push(e), { fetchImpl: bad });
  assert.deepEqual(events, [{ type: "error", code: "HTTP_400", message: "Selection is empty" }]);

  const cut = (async () => sseResponse('event: candidate\ndata: {"text":"敲"}\n\n')) as unknown as typeof fetch;
  const more: TuiqiaoEvent[] = [];
  await streamTuiqiao({ fileName: "a.md", source: "x", start: 0, end: 1 }, e => more.push(e), { fetchImpl: cut });
  assert.deepEqual(more.map(e => e.type), ["candidate", "error"]);
});

test("stream: abort is silent", async () => {
  const controller = new AbortController();
  controller.abort();
  const fetchImpl = (async () => {
    throw Object.assign(new Error("aborted"), { name: "AbortError" });
  }) as unknown as typeof fetch;
  const events: TuiqiaoEvent[] = [];
  await streamTuiqiao({ fileName: "a.md", source: "x", start: 0, end: 1 }, e => events.push(e), { fetchImpl, signal: controller.signal });
  assert.deepEqual(events, []);
});
