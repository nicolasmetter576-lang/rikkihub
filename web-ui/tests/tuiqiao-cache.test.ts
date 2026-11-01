import test from "node:test";
import assert from "node:assert/strict";

import {
  TUIQIAO_CACHE_PER_FILE,
  TUIQIAO_CACHE_TOTAL,
  TuiqiaoCache,
  tuiqiaoNormalize,
} from "../app/overlay/lib/tuiqiao-cache";
import { createLongPress, type LongPressTimers } from "../app/overlay/lib/long-press";
import type { TuiqiaoEvent, TuiqiaoRequest } from "../app/overlay/lib/tuiqiao";

/** A fake server: answers `prepared` + one candidate per request, now or when [release] is called. */
function fakeStream({ hold = false } = {}) {
  const requests: TuiqiaoRequest[] = [];
  const pending: Array<() => void> = [];
  const signals: AbortSignal[] = [];
  const stream = async (request: TuiqiaoRequest, onEvent: (event: TuiqiaoEvent) => void, options?: { signal?: AbortSignal }) => {
    requests.push(request);
    if (options?.signal) signals.push(options.signal);
    const range = request.expand
      ? { start: 0, end: request.source.length, original: request.source }
      : tuiqiaoNormalize(request.source, request.start, request.end)!;
    const answer = () => {
      if (request.slot) {
        onEvent({ type: "candidate", slot: request.slot, text: `重试${requests.length}`, tone: "", why: "" });
      } else {
        onEvent({ type: "prepared", ...range, mode: request.mode ?? "SENTENCE", format: "MARKDOWN", slots: [{ slot: "default", kind: "default" }] });
        onEvent({ type: "candidate", slot: "default", text: `改写${requests.length}`, tone: "平实", why: "" });
      }
      onEvent({ type: "done" });
    };
    if (hold) pending.push(answer); else answer();
  };
  return { stream, requests, signals, release: () => pending.splice(0).forEach(run => run()) };
}

const source = "第一句话。第二句话。\n\n另一段。";
const body = (start: number, end: number, text = source): TuiqiaoRequest => ({ fileName: "a.md", source: text, start, end });

test("normalize trims like the server and rejects blank or out-of-range selections", () => {
  assert.deepEqual(tuiqiaoNormalize(" 推敲 ", 0, 4), { start: 1, end: 3, original: "推敲" });
  assert.equal(tuiqiaoNormalize("   ", 0, 3), null);
  assert.equal(tuiqiaoNormalize("abc", 2, 2), null);
  assert.equal(tuiqiaoNormalize("abc", 0, 9), null);
});

test("same text selected again shows the kept result without a new request, at the new position", () => {
  const fake = fakeStream();
  const cache = new TuiqiaoCache(fake.stream);
  const entry = cache.start("f", body(0, 5));
  assert.equal(entry.state.items[0].candidate.text, "改写1");
  const moved = " " + source;
  const again = cache.lookup("f", moved, 0, 6);
  assert.equal(again, entry);
  assert.equal(fake.requests.length, 1);
  assert.deepEqual([again!.state.prepared!.start, again!.state.prepared!.end], [1, 6]);
});

test("other text or other file misses", () => {
  const cache = new TuiqiaoCache(fakeStream().stream);
  cache.start("f", body(0, 5));
  assert.equal(cache.lookup("f", source, 5, 10), null);
  assert.equal(cache.lookup("g", source, 0, 5), null);
});

test("regenerate replaces the entry for that text and aborts the old request", () => {
  const fake = fakeStream({ hold: true });
  const cache = new TuiqiaoCache(fake.stream);
  const first = cache.start("f", body(0, 5));
  const second = cache.start("f", body(0, 5));
  assert.notEqual(first, second);
  assert.equal(fake.signals[0].aborted, true);
  fake.release();
  assert.equal(first.state.items.length, 0);
  assert.equal(second.state.items[0].candidate.text, "改写2");
  assert.equal(cache.lookup("f", source, 0, 5), second);
  assert.equal(cache.size, 1);
});

test("a request still running when the panel closes finishes into the entry", () => {
  const fake = fakeStream({ hold: true });
  const cache = new TuiqiaoCache(fake.stream);
  const entry = cache.start("f", body(0, 5));
  assert.equal(entry.loading, true);
  let notified = 0;
  entry.subscribe(() => notified++);
  fake.release();
  assert.ok(notified > 0);
  assert.equal(cache.lookup("f", source, 0, 5)!.state.items[0].candidate.text, "改写1");
  assert.equal(entry.loading, false);
});

test("expand requests register under the original the server reports", () => {
  const cache = new TuiqiaoCache(fakeStream().stream);
  const entry = cache.start("f", { ...body(0, 5), expand: "PARAGRAPH" });
  assert.equal(cache.lookup("f", source, 0, source.length), entry);
  assert.equal(cache.lookup("f", source, 0, 5), null);
});

test("retrying a slot keeps the entry and replaces only that slot's items", () => {
  const fake = fakeStream();
  const cache = new TuiqiaoCache(fake.stream);
  const entry = cache.start("f", body(0, 5));
  cache.retrySlot(entry, body(0, 5), "default");
  assert.deepEqual(entry.state.items.map(item => item.candidate.text), ["重试2"]);
  assert.equal(fake.requests[1].slot, "default");
  assert.equal(cache.lookup("f", source, 0, 5), entry);
});

test("removed entry is gone and its request aborted", () => {
  const fake = fakeStream({ hold: true });
  const cache = new TuiqiaoCache(fake.stream);
  const entry = cache.start("f", body(0, 5));
  cache.remove(entry);
  assert.equal(fake.signals[0].aborted, true);
  assert.equal(cache.lookup("f", source, 0, 5), null);
});

test("least recently used entries are evicted per file and overall", () => {
  const cache = new TuiqiaoCache(fakeStream().stream);
  const texts = Array.from({ length: TUIQIAO_CACHE_PER_FILE + 2 }, (_, i) => `第${i}句。`);
  const doc = texts.join("");
  const ranges: Array<[number, number]> = [];
  let offset = 0;
  for (const text of texts) { ranges.push([offset, offset + text.length]); offset += text.length; }
  ranges.forEach(([start, end], index) => {
    cache.start("f", body(start, end, doc));
    if (index === TUIQIAO_CACHE_PER_FILE - 1) assert.ok(cache.lookup("f", doc, ...ranges[0])); // touch the first
  });
  assert.equal(cache.size, TUIQIAO_CACHE_PER_FILE);
  assert.ok(cache.lookup("f", doc, ...ranges[0]));
  assert.equal(cache.lookup("f", doc, ...ranges[1]), null);
  assert.equal(cache.lookup("f", doc, ...ranges[2]), null);
  for (let i = 0; i < TUIQIAO_CACHE_TOTAL; i++) cache.start(`file${i}`, body(...ranges[0], doc));
  assert.equal(cache.size, TUIQIAO_CACHE_TOTAL);
  assert.equal(cache.lookup("f", doc, ...ranges[3]), null);
});

function fakeTimers() {
  let next: (() => void) | null = null;
  const timers: LongPressTimers = { set: callback => { next = callback; return 1; }, clear: () => { next = null; } };
  return { timers, fire: () => { const run = next; next = null; run?.(); } };
}

test("long press: a short press clicks, holding fires the long press once without a click", () => {
  const { timers, fire } = fakeTimers();
  const log: string[] = [];
  const press = createLongPress(() => ({
    onClick: () => log.push("click"),
    onLongPress: () => log.push("long"),
    onPressingChange: pressing => log.push(pressing ? "on" : "off"),
  }), { timers });
  press.down({ clientX: 0, clientY: 0 });
  press.up();
  assert.deepEqual(log.splice(0), ["on", "off", "click"]);
  press.down({ clientX: 0, clientY: 0 });
  fire();
  press.up();
  assert.deepEqual(log.splice(0), ["on", "off", "long"]);
});

test("long press: moving (scrolling), cancelling or a non-left button does nothing", () => {
  const { timers, fire } = fakeTimers();
  const log: string[] = [];
  const press = createLongPress(() => ({ onClick: () => log.push("click"), onLongPress: () => log.push("long") }), { timers });
  press.down({ clientX: 0, clientY: 0 });
  press.move({ clientX: 3, clientY: 4 }); // 5px: still a press
  press.move({ clientX: 0, clientY: 20 });
  fire();
  press.up();
  press.down({ clientX: 0, clientY: 0 });
  press.cancel();
  fire();
  press.up();
  press.down({ clientX: 0, clientY: 0, button: 2 });
  fire();
  press.up();
  assert.deepEqual(log, []);
});
