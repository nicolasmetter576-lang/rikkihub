import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { register } from "node:module";
import i18next from "i18next";
import { messageUsageStats, reportedTokenCount, type UsageMessage } from "../app/lib/message-usage";
import en from "../app/locales/en-US/message.json";
import zh from "../app/locales/zh-CN/message.json";

register("./helpers/svg-stub-loader.mjs", import.meta.url);
const { getNerdStats } = await import("../app/components/message/chat-message");

interface Case {
  name: string;
  message: UsageMessage;
  expected: Omit<ReturnType<typeof messageUsageStats>, "lastUsage">;
}
const cases: Case[] = JSON.parse(readFileSync(
  new URL("../../app/src/test/resources/usage/display-cases.json", import.meta.url), "utf8",
));
const i18n = i18next.createInstance();
await i18n.init({
  lng: "en-US",
  resources: { "en-US": { message: en }, "zh-CN": { message: zh } },
  interpolation: { escapeValue: false },
});
const t = i18n.getFixedT("en-US", "message");
const messageFor = (name: string) => structuredClone(cases.find((item) => item.name === name)!.message);
function snapshot(message: UsageMessage, language = "en-US") {
  const { items, details } = getNerdStats(message, null, i18n.getFixedT(language, "message"));
  return { items: Object.fromEntries(items.map((item) => [item.key, item.label])), details: details.split("\n") };
}

for (const { name, message, expected } of cases) {
  test(`shared App/Web numeric values and eligibility: ${name}`, () => {
    const { lastUsage: _lastUsage, ...stats } = messageUsageStats(message);
    assert.deepEqual(stats, expected);
    assert.equal(snapshot(message).items.speed !== undefined, expected.tps !== null);
  });
}

test("Chatbox-shaped imports never acquire an end time, and viewing time cannot alter any statistics", (ctx) => {
  const original = structuredClone(cases);
  ctx.mock.method(Date, "now", () => Date.parse("2026-11-02T10:00:02Z"));
  const first = cases.map(({ message }) => ({ stats: messageUsageStats(message), display: snapshot(message) }));
  ctx.mock.method(Date, "now", () => Date.parse("2036-11-02T10:00:02Z"));
  const later = cases.map(({ message }) => ({ stats: messageUsageStats(message), display: snapshot(message) }));
  assert.deepEqual(later, first);
  assert.deepEqual(cases, original);
  const imported = first[0];
  assert.equal(imported.stats.tps, null);
  assert.equal(imported.stats.durationMs, null);
  assert.equal(imported.display.items.speed, undefined);
  assert.ok(imported.display.details.every((line) => !/^Observed time:|^Time \(legacy/.test(line)));
});

test("invalid/non-finite durations and invalid end dates do not become a rate or a fallback", () => {
  const legacy = messageFor("legacy-estimate");
  for (const generationTimeMs of [0, -1, NaN, Infinity, -Infinity]) {
    const stats = messageUsageStats({ ...legacy, generationTimeMs });
    assert.equal(stats.durationMs, null);
    assert.equal(stats.tps, null);
    assert.equal(stats.estimated, false);
  }
  for (const finishedAt of [null, "", "bad", "2026-02-30T10:00:02", "2026-11-02T25:00:00"]) {
    assert.equal(messageUsageStats({ ...legacy, finishedAt, generationTimeMs: 500 }).tps, null);
  }
  for (const createdAt of ["bad", "2026-02-30T10:00:00"]) {
    assert.equal(messageUsageStats({ ...legacy, createdAt }).durationMs, null);
  }
  assert.equal(messageUsageStats({ ...legacy, generationTimeMs: Number.MIN_VALUE }).tps, null);
});

test("local timestamp estimates do not inherit browser timezone or DST, and nanoseconds are accepted", () => {
  const legacy = messageFor("legacy-estimate");
  assert.equal(messageUsageStats({
    ...legacy, createdAt: "2026-03-08T01:59:59.123456789", finishedAt: "2026-03-08T03:00:01.123456789",
  }).durationMs, 3_602_000);
});

test("unknown and invalid counts are not exact zero and cannot produce speed", () => {
  for (const marker of [undefined, null, false]) assert.equal(reportedTokenCount(0, marker), null);
  assert.equal(reportedTokenCount(0, true), 0);
  const message = messageFor("complete-two-steps");
  for (const count of [-1, NaN, Infinity]) {
    assert.equal(reportedTokenCount(count, true), null);
    assert.equal(messageUsageStats({ ...message, usage: { ...message.usage!, completionTokens: count } }).tps, null);
  }
});

test("compact snapshots: exact zero/negative, legacy estimate, partial and no-current", () => {
  for (const name of ["legacy-exact-zero-no-fallback", "legacy-exact-negative-no-fallback"]) {
    const result = snapshot(messageFor(name));
    assert.deepEqual(result.items, { context: "150 ctx (30 cached)", completion: "50 out", status: "Legacy · completeness unknown" });
    assert.deepEqual(result.details.slice(0, -2), [
      "Legacy · completeness unknown", "Context: 150", "Latest logical step: input 100 (cached 30), output 50",
      "Total: input 100 (cached 30), output 50", "Reported total tokens: 180",
    ]);
  }
  const legacy = snapshot(messageFor("legacy-estimate"));
  assert.deepEqual(legacy.items, { context: "150 ctx (30 cached)", completion: "50 out", speed: "Est. 25.0 tok/s", status: "Legacy · completeness unknown" });
  assert.ok(legacy.details.includes("Time (legacy estimate): 2.0s"));
  const partial = snapshot(messageFor("partial-last-step-unreported-no-current-snapshot"));
  assert.deepEqual(partial.items, { steps: "2 steps", context: "Not reported ctx", completion: "50 out", status: "Reported · incomplete" });
  assert.deepEqual(partial.details.slice(0, -2), [
    "Reported · incomplete", "The latest model step has no complete usage snapshot; context and cache are unknown.",
    "Steps: 2", "Total: input 100 (cached 30), output 50", "Reported total tokens: 180", "Observed time: 5.0s",
  ]);
  const noCurrent = snapshot(messageFor("new-null-current-never-falls-back-to-total"));
  assert.deepEqual(noCurrent.items, { context: "Not reported ctx", completion: "50 out", speed: "100.0 tok/s" });
  const totalOnly = snapshot(messageFor("partial-total-only-is-not-output-zero"));
  assert.deepEqual(totalOnly.items, { context: "Not reported ctx", completion: "Not reported out", status: "Reported · incomplete" });
  assert.ok(totalOnly.details.includes("Total: input Not reported (cached Not reported), output Not reported"));
  assert.ok(totalOnly.details.includes("Reported total tokens: 180"));
});

test("complete two-step totals stay separate from logical-step context (not independent total)", () => {
  const result = getNerdStats(messageFor("complete-two-steps"), 1000, t);
  assert.ok(result.details.includes("Context: 230 / 1,000 (23%)"));
  assert.ok(result.details.includes("Total: input 300 (cached 30), output 50"));
  assert.ok(result.details.includes("Reported total tokens: 400"));
  assert.ok(result.details.includes("not the exact capacity of the next request"));
  assert.ok(result.details.includes("non-streaming includes request waiting"));
});

test("both Web locales expose completeness, estimates and context limitations", () => {
  assert.deepEqual(Object.keys(en.chat_message).sort(), Object.keys(zh.chat_message).sort());
  const partial = snapshot(messageFor("partial-total-only-is-not-output-zero"), "zh-CN");
  assert.deepEqual(partial.items, { context: "上下文 未报告", completion: "输出 未报告", status: "已报告 · 不完整" });
  assert.ok(partial.details.includes("上下文仅为最近逻辑模型步骤的用量参考，不是下一次请求的精确容量。"));
  const legacy = snapshot(messageFor("legacy-estimate"), "zh-CN");
  assert.equal(legacy.items.speed, "约 25.0 tok/s");
  assert.equal(legacy.items.status, "旧数据 · 完整性未知");
});
