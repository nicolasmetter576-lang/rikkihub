/**
 * mermaid 预览文档构造（web-ui/app/lib/mermaid-renderer.ts）：图源只作为转义后的
 * JS 字符串字面量进入文档，本地运行时脚本先于 bootstrap。
 */
import test from "node:test";
import assert from "node:assert/strict";

import { buildMermaidPreviewDoc, escapeInlineScriptSource } from "../app/lib/mermaid-renderer";

test("escapeInlineScriptSource neutralizes script-breaking sequences", () => {
  const hostile = `</script><script>alert(1)</script><!-- -->\u2028\u2029`;
  const literal = escapeInlineScriptSource(hostile);
  // 是合法 JS 字符串字面量且不含未转义的 < 或 >
  assert.ok(literal.startsWith('"') && literal.endsWith('"'));
  assert.ok(!literal.includes("<"), literal);
  assert.ok(!literal.includes(">"), literal);
  assert.ok(!literal.includes(" ") && !literal.includes(" "));
  // 该字面量 eval 回来必须严格等于原串
  // eslint-disable-next-line no-eval
  assert.equal(eval(literal), hostile);
});

test("mermaid doc: runtime precedes bootstrap", () => {
  const doc = buildMermaidPreviewDoc("graph TD\nA-->B", "err:");
  assert.ok(doc.startsWith("<!doctype html><head>"));
  const runtimeIndex = doc.indexOf("<script>");
  const bootstrapIndex = doc.lastIndexOf("<script>");
  assert.ok(runtimeIndex >= 0 && runtimeIndex < bootstrapIndex, "bootstrap must come after runtime");
});

test("mermaid source never appears as parseable HTML/JS, only escaped literal", () => {
  const source = 'graph TD\nA["</script><img src=x onerror=alert(1)>"]';
  const doc = buildMermaidPreviewDoc(source, "err:");
  // 原始 payload 不得在文档里以未转义形态出现
  assert.ok(!doc.includes("</script><img"), "raw script-terminating payload leaked");
  assert.ok(doc.includes("\\u003c/script\\u003e"));
  // bootstrap 里图源是转义字面量
  const literal = escapeInlineScriptSource(source);
  assert.ok(doc.includes(`mermaid.render('mermaid-preview', ${literal})`));
});

test("mermaid doc enforces strict + no user-controlled script nodes", () => {
  const doc = buildMermaidPreviewDoc(
    '%%{init: {"securityLevel": "loose"}}%%\ngraph TD\nA-->B',
    "err:",
  );
  assert.ok(doc.includes("securityLevel: 'strict'"), "initialize must pin strict");
  assert.ok(doc.includes("suppressErrorRendering: true"));
  // 只有两个 <script> 节点：可信 runtime + 可信 bootstrap
  const scriptCount = (doc.match(/<script>/g) ?? []).length;
  assert.equal(scriptCount, 2, `expected exactly 2 script tags: ${scriptCount}`);
});

test("user doctype in mermaid source is stripped before escaping", () => {
  const doc = buildMermaidPreviewDoc("<!doctype html>graph TD\nA-->B", "err:");
  assert.equal(doc.toLowerCase().split("<!doctype").length - 1, 1);
});

test("error text is escaped literal too", () => {
  const doc = buildMermaidPreviewDoc("graph TD\nA-->B", "</script><x>");
  assert.ok(!doc.includes("err:</script><x>"));
  assert.ok(doc.includes(escapeInlineScriptSource("</script><x>")));
});
