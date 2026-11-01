import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { register } from "node:module";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import { ServerToolPart } from "../app/components/message/parts/server-tool-part";
import { isToolPart } from "../app/types/helpers";
import type { ServerToolPart as ServerToolPartData, UIMessagePart } from "../app/types/parts";
import en from "../app/locales/en-US/message.json";
import zh from "../app/locales/zh-CN/message.json";

register("./helpers/svg-stub-loader.mjs", import.meta.url);
const { MessageParts, groupMessageParts } = await import("../app/components/message/message-part");

await i18next.use(initReactI18next).init({ lng: "en-US", resources: { "en-US": { message: en }, "zh-CN": { message: zh } }, interpolation: { escapeValue: false } });
const source = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");
const serverTool: ServerToolPartData = {
  type: "server_tool", toolCallId: "server-1", toolName: '<script>alert("name")</script>', status: "completed",
  input: { type: "google_search_call", queries: ['<img src=x onerror="alert(1)">'] },
  output: { type: "google_search_result", result: '[click](javascript:alert(1)) <iframe src="bad">', arbitrary: [false, {}, 1] },
  metadata: { protocol: "google_interactions", rawCall: { custom: null }, rawResult: { opaque: true }, callIndex: 1, resultIndex: 3,
    interactions_signature: "opaque-secret", future_metadata: { nested: [1, "x"] } },
};

test("server JSON and text are escaped plain display, not HTML Markdown links or client actions", () => {
  const html = renderToStaticMarkup(createElement(ServerToolPart, { part: serverTool }));
  assert.ok(html.includes("&lt;script&gt;"));
  assert.ok(html.includes("&lt;img"));
  assert.ok(html.includes("&lt;iframe"));
  assert.ok(html.includes("google_search_result"));
  assert.doesNotMatch(html, /<script|<img|<iframe|<a\b|<button|<form|onclick=/i);
  assert.ok(!html.includes("opaque-secret"));
  const text = renderToStaticMarkup(createElement(ServerToolPart, { part: { ...serverTool, input: "<b>raw</b>", output: "<svg onload=alert(1)>" } }));
  assert.ok(text.includes("&lt;b&gt;raw&lt;/b&gt;"));
  assert.doesNotMatch(text, /<b>|<svg/);
});

test("server call results and all opaque metadata survive rendering and JSON roundtrip unchanged", () => {
  const before = JSON.stringify(serverTool);
  renderToStaticMarkup(createElement(ServerToolPart, { part: serverTool }));
  assert.equal(JSON.stringify(serverTool), before);
  assert.deepEqual(JSON.parse(before), serverTool);
  const renderer = source("../app/components/message/parts/server-tool-part.tsx");
  assert.doesNotMatch(renderer, /dangerouslySetInnerHTML|<Markdown|onToolApproval|localBinding|api\./);
  assert.equal(isToolPart(serverTool), false);
});

test("dispatch keeps server tools outside client approval thinking steps and in original order", () => {
  const parts: UIMessagePart[] = [{ type: "reasoning", reasoning: "think", finishedAt: "done" }, serverTool, { type: "text", text: "after" }];
  const grouped = groupMessageParts(parts);
  assert.deepEqual(grouped.map((block) => block.type), ["thinking", "content", "content"]);
  assert.equal(grouped[1].type === "content" && grouped[1].part, serverTool);
  let approvals = 0;
  const html = renderToStaticMarkup(createElement(MessageParts, { parts: [serverTool], onToolApproval: () => { approvals++; } }));
  assert.ok(html.includes("Executed by the provider"));
  assert.equal(approvals, 0);
  assert.doesNotMatch(html, /<button|<form/);
  assert.match(source("../app/components/message/message-part.tsx"), /case "server_tool":\s+return <ServerToolPart part=\{part\} \/>;/);
});

test("all server statuses and read-only explanation are localized in both languages", async () => {
  for (const [language, locale] of [["en-US", en], ["zh-CN", zh]] as const) {
    await i18next.changeLanguage(language);
    for (const status of ["in_progress", "completed", "failed"] as const) {
      const html = renderToStaticMarkup(createElement(ServerToolPart, { part: { ...serverTool, status, input: 0, output: false } }));
      assert.ok(html.includes(locale.server_tool.display_only));
      assert.ok(html.includes(locale.server_tool.status[status]));
      assert.ok(html.includes(">0</pre>"));
      assert.ok(html.includes(">false</pre>"));
      assert.doesNotMatch(html, /server_tool\./);
    }
  }
  assert.deepEqual(Object.keys(en.server_tool), Object.keys(zh.server_tool));
});
