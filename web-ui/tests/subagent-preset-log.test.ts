import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import en from "../app/locales/en-US/message.json";
import { hasSubagentMetadataShape, mergeSubagentMetadata, parseSubagentMetadata, parseSubagentRunLog } from "../app/lib/subagent-log";
import { ToolPart } from "../app/components/message/parts/tool-part";
import type { ToolPart as ToolPartType } from "../app/types/parts";

await i18next.use(initReactI18next).init({ lng: "en-US", resources: { "en-US": { message: en } }, interpolation: { escapeValue: false } });

const snake = { preset_id: "role-1", preset_key: "reviewer", preset_name: "Review role", preset_revision: 8 };

test("preset log parser accepts camel and snake optional metadata", () => {
  const camel = parseSubagentMetadata({ presetId: "role-1", presetKey: "reviewer", presetName: "Review role", presetRevision: 8 });
  const meta = parseSubagentMetadata(snake);
  assert.deepEqual(camel, meta);
  assert.equal(meta?.presetId, "role-1");
  assert.equal(meta?.presetRevision, 8);
  assert.equal(hasSubagentMetadataShape(snake), true);
  const invalid = parseSubagentMetadata({ presetId: 1, presetRevision: "8" });
  assert.equal(invalid, undefined);
});

test("live/final partial merging preserves role identity without overwriting task title", () => {
  const merged = mergeSubagentMetadata(
    parseSubagentMetadata({ ...snake, title: "Check parser", status: "running" }),
    parseSubagentMetadata({ status: "ok", summary: "done", presetName: null, presetRevision: null }),
  );
  assert.equal(merged?.presetName, "Review role");
  assert.equal(merged?.presetKey, "reviewer");
  assert.equal(merged?.presetRevision, 8);
  assert.equal(merged?.title, "Check parser");
  assert.equal(merged?.status, "ok");
});

test("persisted v2 log preserves optional role fields; old runs still parse", () => {
  const log = parseSubagentRunLog({ version: 2, status: "ok", ...snake, preamble: "startup role", messages: [] });
  assert.equal(log?.presetName, "Review role");
  assert.equal(log?.presetRevision, 8);
  assert.equal(log?.preamble, "startup role");
  const legacy = parseSubagentRunLog({ version: 1, status: "ok", transcript: [{ role: "assistant", text: "old result" }] });
  assert.equal(legacy?.presetName, undefined);
  assert.equal(legacy?.transcript[0].text, "old result");
});

function renderTool(metadata?: Record<string, unknown>, final = false): string {
  const tool: ToolPartType = {
    type: "tool", toolCallId: "call-1", toolName: "subagent", input: JSON.stringify({ prompt: "Review a parser", description: "Check parser" }),
    approvalState: { type: "auto" }, metadata,
    output: final ? [{ type: "text", text: JSON.stringify({ status: "ok", summary: "done", ...snake }) }] : [],
  };
  return renderToStaticMarkup(createElement(ToolPart, { tool, loading: !final, isFirst: true, isLast: true }));
}

test("tool render separates role name from task title in live and final outputs", () => {
  for (const html of [renderTool({ presetName: "Review role", presetKey: "reviewer", presetId: "role-1", status: "running" }), renderTool(undefined, true)]) {
    assert.ok(html.includes("data-subagent-role"));
    assert.ok(html.includes("Review role"));
    assert.ok(html.includes("Check parser"));
    assert.ok(!html.includes("startup role"));
  }
  const old = renderTool(undefined, false);
  assert.ok(old.includes("Check parser"));
  assert.ok(!old.includes("data-subagent-role"), "legacy output has no fabricated role name");
});
