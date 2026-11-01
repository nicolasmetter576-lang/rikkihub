import test from "node:test";
import assert from "node:assert/strict";

import {
  ZAIZAO_CACHE_PER_FILE,
  ZaizaoCache,
  zaizaoBase,
  zaizaoDraftLabel,
  zaizaoHistory,
  zaizaoRoundMark,
} from "../app/overlay/lib/zaizao-cache";
import { tuiqiaoNormalize } from "../app/overlay/lib/tuiqiao-cache";
import { ZAIZAO_MAX_ROUNDS, type ZaizaoEvent, type ZaizaoRequest } from "../app/overlay/lib/zaizao";

/** A fake server: answers 稿N for request N, now or when [release] is called. */
function fakeStream({ hold = false, fail = false } = {}) {
  const requests: ZaizaoRequest[] = [];
  const signals: AbortSignal[] = [];
  const pending: Array<() => void> = [];
  const stream = async (request: ZaizaoRequest, onEvent: (event: ZaizaoEvent) => void, options?: { signal?: AbortSignal }) => {
    requests.push(request);
    if (options?.signal) signals.push(options.signal);
    const n = requests.length;
    onEvent({ type: "update", reasoning: "想", plan: "", result: "半" });
    const answer = () => {
      if (options?.signal?.aborted) return;
      if (fail) onEvent({ type: "error", code: "NO_MODEL", message: "NO_MODEL" });
      else onEvent({ type: "done", reasoning: "想", plan: "", result: `稿${n}`, output: `<result>稿${n}</result>` });
    };
    if (hold) pending.push(answer); else answer();
  };
  return { stream, requests, signals, release: () => pending.splice(0).forEach(run => run()) };
}

const source = "第一句话。第二句话。\n\n另一段。";

function open(cache: ZaizaoCache, text = source, from = 0, to = 5, file = "f") {
  const range = tuiqiaoNormalize(text, from, to)!;
  return cache.lookup(file, text, from, to) ??
    cache.create(file, { fileName: "a.md", source: text, start: range.start, end: range.end, includeAfter: true, type: "MODIFY" });
}

test("round marks and labels", () => {
  assert.equal(zaizaoRoundMark(1), "①");
  assert.equal(zaizaoRoundMark(20), "⑳");
  assert.equal(zaizaoRoundMark(21), "(21)");
  assert.equal(zaizaoDraftLabel({ round: 2, number: 3 }), "②-3");
});

test("the same text selected again finds the kept session; before the first round it follows the editor", () => {
  const cache = new ZaizaoCache(fakeStream().stream);
  const entry = open(cache);
  assert.equal(entry.state.original, "第一句话。");
  const edited = `前言。${source}`;
  assert.equal(open(cache, edited, 3, 8), entry);
  assert.equal(entry.state.source, edited);
  assert.equal(entry.state.start, 3);
  assert.notEqual(open(cache, source, 5, 10), entry);
  assert.notEqual(open(cache, source, 0, 5, "g"), entry);
});

test("send needs a requirement, sends the first round and replays the chain later", () => {
  const fake = fakeStream();
  const cache = new ZaizaoCache(fake.stream);
  const entry = open(cache);
  assert.equal(entry.send("  "), "EMPTY_REQUIREMENT");
  assert.equal(entry.send("x".repeat(2001)), "REQUIREMENT_TOO_LONG");
  entry.setType("INCREMENT");
  entry.setDraftText(" 铲子上有三斤大便 ");
  assert.equal(entry.send(entry.state.draftText), null);
  assert.equal(entry.state.draftText, "");
  assert.deepEqual(fake.requests[0], {
    fileName: "a.md", source, start: 0, end: 5, includeAfter: true, history: [], type: "INCREMENT",
    requirement: "铲子上有三斤大便", avoid: [], record: true,
  });
  assert.deepEqual(entry.state.drafts[0].status, { kind: "done" });
  assert.equal(entry.state.drafts[0].result, "稿1");
  entry.setIncludeAfter(false); // locked after the first round
  assert.equal(entry.state.includeAfter, true);

  entry.setType("MODIFY");
  entry.send("乙");
  assert.deepEqual(fake.requests[1].history, [{ type: "INCREMENT", requirement: "铲子上有三斤大便", output: "<result>稿1</result>" }]);

  entry.another(1);
  assert.deepEqual(fake.requests[2].history, []);
  assert.deepEqual(fake.requests[2].avoid, ["稿1"]);
  assert.equal(fake.requests[2].record, false);
  assert.equal(zaizaoBase(entry.state)?.id, 2); // the newest draft (①-2)

  entry.continueFrom(1); // ②-1
  entry.send("丙");
  const third = entry.state.rounds[2];
  assert.equal(third.baseId, 1);
  assert.equal(entry.state.pinnedBase, null);
  assert.deepEqual(fake.requests[3].history?.map(turn => turn.output), ["<result>稿1</result>", "<result>稿2</result>"]);
  assert.deepEqual(zaizaoHistory(entry.state, 3).map(turn => turn.requirement), ["铲子上有三斤大便", "乙", "丙"]);
});

test("stop keeps what was written, retry writes the draft again, failures are not bases", () => {
  const held = fakeStream({ hold: true });
  const cache = new ZaizaoCache(held.stream);
  const entry = open(cache);
  entry.send("甲");
  assert.ok(entry.busy);
  assert.equal(zaizaoBase(entry.state)?.id, 0); // the draft being written is what the next round builds on
  assert.equal(entry.send("乙"), null); // ignored while writing
  assert.equal(entry.state.rounds.length, 1);
  entry.stop();
  assert.ok(held.signals[0].aborted);
  assert.deepEqual(entry.state.drafts[0].status, { kind: "stopped" });
  assert.equal(entry.state.drafts[0].result, "半");
  held.release(); // a late answer after stop is ignored
  assert.deepEqual(entry.state.drafts[0].status, { kind: "stopped" });
  entry.retry(0);
  held.release();
  assert.deepEqual(entry.state.drafts[0].status, { kind: "done" });
  assert.equal(entry.state.rounds[0].draftIds.length, 1);

  const failing = new ZaizaoCache(fakeStream({ fail: true }).stream);
  const other = open(failing);
  other.send("甲");
  assert.deepEqual(other.state.drafts[0].status, { kind: "error", code: "NO_MODEL", message: "NO_MODEL" });
  assert.equal(zaizaoBase({ ...other.state, drafts: other.state.drafts.map(d => ({ ...d, result: "" })) }), null);
});

test("the chain is capped and old sessions are evicted and aborted", () => {
  const cache = new ZaizaoCache(fakeStream().stream);
  const entry = open(cache);
  for (let i = 0; i < ZAIZAO_MAX_ROUNDS; i++) assert.equal(entry.send(`第${i}轮`), null);
  assert.equal(entry.send("再来"), "TOO_MANY_ROUNDS");
  entry.continueFrom(0);
  assert.equal(entry.send("从头"), null);

  const held = fakeStream({ hold: true });
  const small = new ZaizaoCache(held.stream);
  const texts = Array.from({ length: ZAIZAO_CACHE_PER_FILE + 1 }, (_, i) => `第${i}句。`);
  const all = texts.join("");
  let offset = 0;
  const entries = texts.map(text => {
    const next = open(small, all, offset, offset + text.length);
    offset += text.length;
    return next;
  });
  assert.equal(small.size, ZAIZAO_CACHE_PER_FILE);
  assert.equal(small.find("f", entries[0].state.original), null);
  entries[1].send("甲");
  open(small, `${all}新的一句。`, all.length, all.length + 5);
  assert.equal(small.find("f", entries[1].state.original), null);
  assert.ok(held.signals[0].aborted);
});
