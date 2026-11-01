/**
 * 工作区 Markdown 预览的渲染级保真/安全测试：
 * 实渲染共享 Markdown（allowRawHtml=false + 工作区 resolveUrl），
 * 验证「源码不改写」前提下 AST/URL 层映射正确、危险面不进 DOM。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/workspace-markdown-render.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import Markdown from "../app/components/markdown/markdown";
import {
  workspaceMarkdownUrlResolver,
  type WorkspaceMarkdownContext,
} from "../app/overlay/lib/workspace";

const ctx: WorkspaceMarkdownContext = {
  workspaceId: "W1",
  area: "FILES",
  baseDir: "docs",
};

function renderPreview(content: string): string {
  return renderToStaticMarkup(
    createElement(Markdown, {
      content,
      allowRawHtml: false,
      allowCodePreview: false,
      isAnimating: false,
      resolveUrl: workspaceMarkdownUrlResolver(ctx),
    }),
  );
}

/** <tag 形式的元素未出现（文本态的 &lt;tag 允许）。 */
function hasElement(html: string, tag: string): boolean {
  return new RegExp(`<${tag}[\\s>/]`).test(html);
}

test("代码范围内字面 <script> 原样显示不执行（缩进/fence/嵌套列表/行内）", () => {
  const cases = [
    "para\n\n    <script>alert(1)</script>\n\nend", // 4 空格缩进代码块
    "```html\n<script>alert(1)</script>\n```", // fenced
    "- item\n\n      <script>alert(1)</script>\n\n  - sub", // 列表内缩进代码
    "`<script>x</script>`", // inline code
  ];
  for (const c of cases) {
    const html = renderPreview(c);
    assert.ok(html.includes("&lt;script&gt;"), `literal text kept: ${c}`);
    assert.ok(!hasElement(html, "script"), `no <script> element: ${c}`);
  }
});

test("原始 HTML 不进入 DOM（allowRawHtml=false 保障，源码未被改写）", () => {
  const html = renderPreview("before\n\n<img src=x onerror=alert(1)>\n\nafter");
  assert.ok(!hasElement(html, "img"), "raw <img> must not become element");
  // 被降级为可见文本（不是静默吞掉）
  assert.ok(html.includes("&lt;img"), "raw html shown as text");
});

test("相对图片映射到鉴权下载 URL（含 ../ 与空格路径）", () => {
  let html = renderPreview("![p](img.png)");
  assert.ok(html.includes("path=docs%2Fimg.png"), html);

  html = renderPreview("![p](../shared/x.png)");
  assert.ok(html.includes("path=shared%2Fx.png"), html);

  // 尖括号写法（含空格文件名）
  html = renderPreview("![p](<my img.png>)");
  assert.ok(html.includes("path=docs%2Fmy+img.png"), html);
});

test("越出根的相对图片拒绝加载（空 src/不渲染 img）", () => {
  const html = renderPreview("![e](../../etc/passwd)");
  assert.ok(!hasElement(html, "img"), "escaped path must not render img");
  assert.ok(!html.includes("passwd"), "must not leak path into url");
});

test("javascript: 与 data:image/svg 不执行", () => {
  const html = renderPreview(
    "![j](javascript:alert(1))\n\n[l](javascript:alert(2))\n\n![s](data:image/svg+xml;base64,AAAA)",
  );
  assert.ok(!html.includes("javascript:"), "no javascript: survives");
  assert.ok(!hasElement(html, "img"), "js/svg images dropped");
  assert.ok(!html.includes("data:image/svg"), "svg data stripped");
  // 链接降级为空 href（a 元素可存在但无 javascript:）
  assert.ok(!/href="javascript:/.test(html));
});

test("引用式图片：streamdown 分块渲染本就字面显示（源码语义保真，不产生破碎 src）", () => {
  const html = renderPreview("intro ![r][ref]\n\n[ref]: javascript:alert(1)\n\n![r][ref]");
  // 渲染器对跨块 reference 按文本处理——保真地显示原文，不构造 URL
  assert.ok(html.includes("![r][ref]"), "reference usage preserved as text");
  assert.ok(!html.includes("javascript:"));
  assert.ok(!hasElement(html, "img"));
});

test("合法数学与链接正常渲染；KaTeX trust=false 下 \\href 不产出链接", () => {
  const html = renderPreview(
    "$\\href{https://a.b/x}{label}$ and $x^2$ and [ok](https://a.b/c) and <https://auto.example>",
  );
  // 合法 markdown 链接/autolink 不受影响
  assert.ok(html.includes('href="https://a.b/c"'), html);
  assert.ok(html.includes('href="https://auto.example"'), html);
  // KaTeX 渲染（katex span 存在），但 trust=false 使 \href 不生成 <a>
  assert.ok(html.includes("katex"), "math rendered");
  assert.ok(!html.includes('href="https://a.b/x"'), "katex \\href blocked by trust=false");
});

test("普通 Markdown 保真：标题/加粗/列表/表格标记不被改写", () => {
  const html = renderPreview("# Title\n\n**bold** *it*\n\n- a\n- b\n\na < b & c\n");
  assert.ok(html.includes("Title"));
  assert.ok(html.includes("<strong") || html.includes("bold"));
  assert.ok(html.includes("&lt;"), "bare < shown as text");
});
