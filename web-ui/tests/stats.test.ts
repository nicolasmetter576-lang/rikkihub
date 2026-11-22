import test from "node:test";
import assert from "node:assert/strict";
import {
  normalizeAmount, normalizeReport, formatTokens, formatCost, formatPercent,
  cacheHitRate, isFullyPriced, quotaLeft, buildHeatmapWeeks,
} from "../app/overlay/lib/stats";
import { parsePrice, pricingFromTexts, pricingTexts } from "../app/overlay/lib/model-pricing";

test("statistics normalize omitted defaults without dropping explicit zero", () => {
  const report = normalizeReport({ total: { totalTokens: 0, promptTokens: 100 } as never });
  assert.equal(report.total.totalTokens, 0);
  assert.equal(report.total.promptTokens, 100);
  assert.equal(report.total.cost, 0);
  assert.deepEqual(report.providers, []);
  assert.equal(report.range, "D30");
});

test("inconsistent cache counters show an unknown rate, not 106 percent or a fabricated 100 percent", () => {
  for (const [promptTokens, cachedTokens] of [[100, 106], [0, 1], [-1, 0], [100, -1], [100, NaN]]) {
    const amount = normalizeAmount({ promptTokens, cachedTokens });
    assert.equal(cacheHitRate(amount), null);
    assert.equal(formatPercent(cacheHitRate(amount)), "—");
  }
  assert.equal(cacheHitRate(normalizeAmount({ promptTokens: 100, cachedTokens: 40 })), 0.4);
  assert.equal(cacheHitRate(normalizeAmount({ promptTokens: 100, cachedTokens: 100 })), 1);
  assert.equal(cacheHitRate(normalizeAmount({})), 0);
});

test("pricing coverage follows billable input and output, not upstream total", () => {
  const amount = normalizeAmount({ promptTokens: 100, completionTokens: 20, totalTokens: 999, pricedTokens: 100 });
  assert.equal(isFullyPriced(amount), false);
  assert.equal(isFullyPriced({ ...amount, pricedTokens: 120 }), true);
  assert.equal(isFullyPriced(normalizeAmount({ totalTokens: 999 })), false);
});

test("model prices distinguish unknown, explicit free, invalid and decimal drafts", () => {
  assert.equal(parsePrice(""), null);
  assert.equal(parsePrice("0"), 0);
  assert.equal(parsePrice("1."), 1);
  assert.equal(parsePrice(".5"), 0.5);
  for (const value of ["-1", ".", "1.2.3", "NaN", "Infinity", "1000001", "1e3"]) {
    assert.equal(parsePrice(value), undefined, value);
  }
  assert.equal(pricingFromTexts({ input: "", output: "", cachedInput: "" }), null);
  assert.deepEqual(pricingFromTexts({ input: "0", output: "2.5", cachedInput: "" }), { input: 0, output: 2.5 });
  assert.equal(pricingFromTexts({ input: "2", output: "-1", cachedInput: "" }), undefined);
  assert.deepEqual(pricingTexts({ input: 1.25, output: 0 }), { input: "1.25", output: "0", cachedInput: "" });
});

test("remaining allowance comes from per-account estimates and expired data is not replenished", () => {
  const quota = normalizeReport({ providers: [{ quotas: [{ window: "daily", accounts: 2, remaining: 0.5 }] }] as never })
    .providers[0].quotas[0];
  assert.equal(quotaLeft(quota, { perFull: 1000, variation: null, remaining: 375 }), 375);
  assert.equal(quotaLeft(quota, { perFull: 1000, variation: null }), null);
  assert.equal(quotaLeft({ ...quota, stale: true }, { perFull: 1000, variation: null, remaining: 375 }), null);
});

test("heatmap has 53 full weeks, includes today and marks only later dates as future", () => {
  const days = buildHeatmapWeeks(new Date(2026, 10, 4)).flat();
  assert.equal(days.length, 371);
  assert.equal(new Set(days.map((day) => day.key)).size, 371);
  const today = days.findIndex((day) => day.key === "2026-11-04");
  assert.ok(today >= 364);
  assert.ok(days.slice(0, today + 1).every((day) => !day.future));
  assert.ok(days.slice(today + 1).every((day) => day.future));
});

test("statistics formatting preserves small costs and always uses dollars", () => {
  assert.equal(formatCost(0), "$0");
  assert.equal(formatCost(0.001), "<$0.01");
  assert.equal(formatCost(1.25), "$1.25");
  assert.equal(formatCost(1234.4), "$1,234");
  assert.equal(formatTokens(1_000_000), "1.00M");
  assert.equal(formatTokens(1150), "1.2K");
  assert.equal(formatPercent(0.05), "5.0%");
});
