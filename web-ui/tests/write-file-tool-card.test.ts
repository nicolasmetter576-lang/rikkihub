import test from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import type { ToolPart as UIToolPart } from "../app/types/parts";
import en from "../app/locales/en-US/message.json";
import zh from "../app/locales/zh-CN/message.json";

register("./helpers/svg-stub-loader.mjs", import.meta.url);
const { ToolPart } = await import("../app/components/message/parts/tool-part");

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { message: en }, "zh-CN": { message: zh } },
  interpolation: { escapeValue: false },
});

const text = Array.from({ length: 15 }, (_, i) => `line ${i + 1}`).join("\n");
const full = JSON.stringify({ path: "/workspace/novel/ch1.md", text });
const tool = (input: string, toolName = "workspace_write_file", done = false): UIToolPart => ({
  type: "tool", toolCallId: "w1", toolName, input, approvalState: { type: "auto" },
  output: done ? [{ type: "text", text: '{"ok":true}' }] : [],
});
const render = (part: UIToolPart, loading: boolean) => renderToStaticMarkup(createElement(ToolPart, { tool: part, loading }));

test("while the arguments stream, the card shows the path, a running count and the newest lines", () => {
  const prefix = full.slice(0, full.length - 3); // cut inside the text
  const html = render(tool(prefix), true);
  assert.ok(html.includes("Write: /workspace/novel/ch1.md"));
  assert.match(html, /\d+ characters so far/);
  assert.ok(html.includes("line 14"));
  assert.ok(!html.includes("line 1\n"));
  assert.ok(!html.includes("line 4\n"));
});

test("once executed, the card shows the first lines and no count", () => {
  const html = render(tool(full, "workspace_write_file", true), false);
  assert.ok(html.includes("Write: /workspace/novel/ch1.md"));
  assert.doesNotMatch(html, /characters so far/);
  assert.ok(html.includes("line 1\n"));
  assert.ok(html.includes("line 10"));
  assert.ok(!html.includes("line 11"));
});

test("other tools keep strict argument parsing while streaming", () => {
  const html = render(tool('{"path":"/a","text":"half', "workspace_read_file"), true);
  assert.ok(html.includes("workspace_read_file"));
  assert.doesNotMatch(html, /characters so far|<pre/);
});

test("both locales have the card strings", () => {
  for (const locale of [en, zh]) {
    assert.ok(locale.tool_part.write_file.includes("{{path}}"));
    assert.ok(locale.tool_part.write_file_default);
    assert.ok(locale.tool_part.streaming_chars.includes("{{count}}"));
  }
});
