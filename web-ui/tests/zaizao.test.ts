import test from "node:test";
import assert from "node:assert/strict";

import {
  parseZaizaoEvent,
  streamZaizao,
  zaizaoChange,
  zaizaoRemember,
  type ZaizaoEvent,
} from "../app/overlay/lib/zaizao";

test("parse: prepared, update, done and error events", () => {
  assert.deepEqual(parseZaizaoEvent("prepared", '{"start":4,"end":16,"original":"他抄起铁锹。","format":"PLAIN"}'), {
    type: "prepared", start: 4, end: 16, original: "他抄起铁锹。", format: "PLAIN",
  });
  assert.deepEqual(parseZaizaoEvent("prepared", '{"start":0,"end":1,"original":"a"}'), {
    type: "prepared", start: 0, end: 1, original: "a", format: "MARKDOWN",
  });
  assert.deepEqual(parseZaizaoEvent("update", '{"reasoning":"想","plan":"","result":"他抄"}'), {
    type: "update", reasoning: "想", plan: "", result: "他抄",
  });
  assert.deepEqual(parseZaizaoEvent("update", "{}"), { type: "update", reasoning: "", plan: "", result: "" });
  assert.deepEqual(parseZaizaoEvent("done", '{"reasoning":"","plan":"p","result":"他抄起","output":"<result>他抄起</result>"}'), {
    type: "done", reasoning: "", plan: "p", result: "他抄起", output: "<result>他抄起</result>",
  });
  assert.deepEqual(parseZaizaoEvent("error", '{"code":"NO_MODEL","message":"NO_MODEL"}'), { type: "error", code: "NO_MODEL", message: "NO_MODEL" });
  assert.deepEqual(parseZaizaoEvent("error", "{}"), { type: "error", code: "MODEL_ERROR", message: "" });
});

test("parse: rejects unknown events, bad JSON, empty results and bad ranges", () => {
  assert.equal(parseZaizaoEvent("candidate", '{"text":"x"}'), undefined);
  assert.equal(parseZaizaoEvent("update", "{oops"), undefined);
  assert.equal(parseZaizaoEvent("done", '{"result":""}'), undefined);
  assert.equal(parseZaizaoEvent("prepared", '{"start":"0","end":1}'), undefined);
});

test("change: replaces only while the original is still there", () => {
  const range = { start: 1, end: 3, original: "铁锹" };
  assert.deepEqual(zaizaoChange("抄铁锹走", range, "粪铲"), { from: 1, to: 3, insert: "粪铲" });
  assert.equal(zaizaoChange("抄锄头走", range, "粪铲"), null);
  assert.equal(zaizaoChange("抄", range, "粪铲"), null);
});

test("remember: newest first, trimmed, without duplicates, capped", () => {
  assert.deepEqual(zaizaoRemember(["乙", "甲"], " 甲 "), ["甲", "乙"]);
  assert.deepEqual(zaizaoRemember(["甲"], "  "), ["甲"]);
  assert.deepEqual(zaizaoRemember(["1", "2", "3"], "4", 3), ["4", "1", "2"]);
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
  let url = "";
  const fetchImpl = (async (target: string, init: RequestInit) => {
    url = target;
    sent = JSON.parse(String(init.body));
    return sseResponse(
      ': hb\n\nevent: prepared\ndata: {"start":0,"end":2,"original":"铁锹","format":"MARKDOWN"}\n\n' +
        'event: update\ndata: {"reasoning":"","plan":"","result":"粪"}\n\n' +
        'event: done\ndata: {"reasoning":"","plan":"","result":"粪铲","output":"<result>粪铲</result>"}\n\n',
    );
  }) as unknown as typeof fetch;
  const events: ZaizaoEvent[] = [];
  const request = {
    fileName: "a.md", source: "铁锹", start: 0, end: 2, includeAfter: false, type: "REWRITE" as const, requirement: "换",
    history: [{ type: "MODIFY" as const, requirement: "甲", output: "<result>一稿</result>" }], avoid: ["二稿"], record: false,
  };
  await streamZaizao(request, e => events.push(e), { fetchImpl });
  assert.equal(url, "/api/zaizao");
  assert.deepEqual(sent, request);
  assert.deepEqual(events.map(e => e.type), ["prepared", "update", "done"]);
});

test("stream: HTTP errors and early end become error events; abort is silent", async () => {
  const bad = (async () => new Response(JSON.stringify({ error: "'requirement' is empty" }), { status: 400 })) as unknown as typeof fetch;
  const events: ZaizaoEvent[] = [];
  await streamZaizao({ fileName: "a.md", source: "x", start: 0, end: 1, type: "MODIFY", requirement: "" }, e => events.push(e), { fetchImpl: bad });
  assert.deepEqual(events, [{ type: "error", code: "HTTP_400", message: "'requirement' is empty" }]);

  const cut = (async () => sseResponse('event: update\ndata: {"result":"半"}\n\n')) as unknown as typeof fetch;
  const more: ZaizaoEvent[] = [];
  await streamZaizao({ fileName: "a.md", source: "x", start: 0, end: 1, type: "MODIFY", requirement: "y" }, e => more.push(e), { fetchImpl: cut });
  assert.deepEqual(more.map(e => e.type), ["update", "error"]);

  const controller = new AbortController();
  controller.abort();
  const aborting = (async () => { throw Object.assign(new Error("aborted"), { name: "AbortError" }); }) as unknown as typeof fetch;
  const none: ZaizaoEvent[] = [];
  await streamZaizao({ fileName: "a.md", source: "x", start: 0, end: 1, type: "MODIFY", requirement: "y" }, e => none.push(e), { fetchImpl: aborting, signal: controller.signal });
  assert.deepEqual(none, []);
});
