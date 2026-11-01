import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { LOCAL_TOOL_TYPES, normalizeLocalTools, hasLocalTool, toggleLocalToolOption } from "../app/overlay/lib/settings";
import type { LocalToolType } from "../app/overlay/types/settings";
import en from "../app/overlay/locales/en-US/settings.json";
import zh from "../app/overlay/locales/zh-CN/settings.json";

const chart: LocalToolType = "chart_display";
const source = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");

test("chart is the eighth opt-in local option with no implicit defaults", () => {
  assert.deepEqual(LOCAL_TOOL_TYPES, ["javascript_engine", "time_info", "clipboard", "tts", "ask_user", "screen_time", "calendar", chart]);
  assert.equal(normalizeLocalTools(undefined), undefined);
  assert.deepEqual(normalizeLocalTools([]), []);
  assert.equal(hasLocalTool(undefined, chart), false);
  assert.equal(hasLocalTool([{ type: "time_info" }], chart), false);
});

test("chart toggle preserves prior tools, unknown future options and order", () => {
  const prior = [{ type: "time_info" }, { type: "future_tool", future: "preserved" }];
  const enabled = toggleLocalToolOption(prior, chart, true);
  assert.deepEqual(enabled, [...prior, { type: chart }]);
  assert.deepEqual(prior, [{ type: "time_info" }, { type: "future_tool", future: "preserved" }]);
  assert.equal(toggleLocalToolOption(enabled, chart, true), enabled);
  assert.deepEqual(toggleLocalToolOption(enabled, chart, false), prior);
  assert.deepEqual(toggleLocalToolOption([{ type: chart }], chart, false), []);
});

test("chart roundtrip normalization keeps sealed wire discriminator and clear", () => {
  const raw = JSON.parse(JSON.stringify([{ type: chart }, { type: "calendar" }, { type: chart }]));
  assert.deepEqual(normalizeLocalTools(raw), [{ type: chart }, { type: "calendar" }]);
  assert.deepEqual(normalizeLocalTools([{ type: chart, unexpected: 1 }]), [{ type: chart }]);
  assert.deepEqual(normalizeLocalTools([null, "chart_display", { type: 1 }, { type: "" }]), []);
  assert.deepEqual(normalizeLocalTools([]) ?? [], []);
});

test("both locales explicitly describe Android-native and manual export scope", () => {
  for (const locale of [en, zh]) {
    for (const tool of LOCAL_TOOL_TYPES) {
      assert.ok(locale.assistants.local_tools[tool].label);
      assert.ok(locale.assistants.local_tools[tool].desc);
    }
  }
  assert.match(en.assistants.local_tools.chart_display.desc, /Android app/);
  assert.match(en.assistants.local_tools.chart_display.desc, /manual app action/);
  assert.match(zh.assistants.local_tools.chart_display.desc, /手动操作/);
  assert.match(zh.assistants.local_tools.chart_display.desc, /不新增浏览器图表渲染/);
});

test("existing assistant detail uses shared list and explicit assistant PUT only", () => {
  const route = source("../app/overlay/routes/settings.assistants.$id.tsx");
  assert.ok(route.includes("LOCAL_TOOL_TYPES.map((toolType)"));
  assert.ok(route.includes("checked={enabled}"));
  assert.ok(route.includes("localTools: toggleLocalToolOption(draft.localTools, toolType, checked === true)"));
  assert.ok(route.includes("localTools: normalizeLocalTools(assistant.localTools)"));
  assert.ok(route.includes("localTools: normalizeLocalTools(draft.localTools) ?? []"));
  assert.ok(route.includes("api.put<AssistantProfile>(`assistants/${assistant.id}`"));
  assert.doesNotMatch(route, /api\.put[^\n]*["`]settings/);
});
