import test from "node:test";
import assert from "node:assert/strict";
import * as React from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { Streamdown, type StreamdownProps } from "streamdown";
import {
  canPreviewCodeLanguage,
  markdownRehypePlugins,
  markdownUrlTransform,
  REMARK_PLUGINS,
  safeMarkdownUrlTransform,
} from "../app/lib/markdown-pipeline";

// 与 markdown.tsx 同款的最小渲染器（不引入 CodeBlock/shiki/css，不渲染消息组件）。
// linkSafety 关闭仅为了让断言能看到真实 href（urlTransform 在 hast 层已经生效）。
function renderMarkdown(content: string, allowRawHtml: boolean): string {
  const element = React.createElement(
    Streamdown,
    {
      mode: "static",
      linkSafety: { enabled: false },
      remarkPlugins: REMARK_PLUGINS,
      rehypePlugins: markdownRehypePlugins(allowRawHtml),
      urlTransform: markdownUrlTransform(allowRawHtml),
      // 去掉 shiki code 高亮组件，避免 wasm 依赖
      components: {
        code: (props: React.ComponentProps<"code">) =>
          React.createElement("code", null, props.children),
      } as StreamdownProps["components"],
    },
    content,
  );
  return renderToStaticMarkup(element);
}

// ---------------------------------------------------------------------------
// 静态断言：插件链构成
// ---------------------------------------------------------------------------

test("safe mode drops rehype-raw and keeps katex with trust=false", () => {
  const safe = markdownRehypePlugins(false);
  const names = safe.map((entry) => {
    const fn = (Array.isArray(entry) ? entry[0] : entry) as { name?: string };
    return fn?.name ?? "";
  });
  assert.ok(!names.includes("rehypeRaw"), "safe 链不得含 rehype-raw");
  assert.equal(safe.length, 1);

  const katexEntry = safe[0];
  assert.ok(Array.isArray(katexEntry));
  assert.equal(katexEntry[1]?.trust, false, "安全模式 KaTeX trust 必须为 false");

  const normal = markdownRehypePlugins(true);
  const normalNames = normal.map((entry) =>
    Array.isArray(entry) ? (entry[0] as { name?: string })?.name : (entry as { name?: string })?.name,
  );
  assert.ok(normalNames.includes("rehypeRaw"), "默认链保留 rehype-raw");
  const normalKatex = normal[0];
  assert.ok(Array.isArray(normalKatex));
  assert.equal(normalKatex[1]?.trust, true, "默认模式保持既有 trust=true");
});

test("markdownUrlTransform: identity in default mode, filter in safe mode", () => {
  assert.equal(markdownUrlTransform(true), undefined);
  assert.equal(markdownUrlTransform(false), safeMarkdownUrlTransform);
});

test("safeMarkdownUrlTransform blocks dangerous schemes", () => {
  const node = {} as never;
  assert.equal(safeMarkdownUrlTransform("javascript:alert(1)", "href", node), "");
  assert.equal(safeMarkdownUrlTransform("java\tscript:alert(1)", "href", node), "");
  assert.equal(safeMarkdownUrlTransform("vbscript:x", "href", node), "");
  assert.equal(safeMarkdownUrlTransform("file:///etc/passwd", "href", node), "");
  assert.equal(safeMarkdownUrlTransform("data:text/html,<script>1</script>", "src", node), "");
  assert.equal(safeMarkdownUrlTransform("data:image/svg+xml;base64,AAA", "src", node), "");
  assert.equal(safeMarkdownUrlTransform("data:image/png;base64,AAA", "href", node), "");
});

test("safeMarkdownUrlTransform keeps benign urls", () => {
  const node = {} as never;
  assert.equal(
    safeMarkdownUrlTransform("https://a.b/c?d=e#f", "href", node),
    "https://a.b/c?d=e#f",
  );
  assert.equal(safeMarkdownUrlTransform("mailto:a@b.c", "href", node), "mailto:a@b.c");
  assert.equal(safeMarkdownUrlTransform("/files/x.png", "src", node), "/files/x.png");
  assert.equal(safeMarkdownUrlTransform("./rel/y.md", "href", node), "./rel/y.md");
  assert.equal(safeMarkdownUrlTransform("#anchor", "href", node), "#anchor");
  assert.equal(
    safeMarkdownUrlTransform("data:image/png;base64,AAA", "src", node),
    "data:image/png;base64,AAA",
  );
});

test("canPreviewCodeLanguage blocks html/svg preview in safe mode only", () => {
  assert.ok(!canPreviewCodeLanguage("html", false));
  assert.ok(!canPreviewCodeLanguage("htm", false));
  assert.ok(!canPreviewCodeLanguage("svg", false));
  assert.ok(!canPreviewCodeLanguage("xml", false));
  assert.ok(canPreviewCodeLanguage("markdown", false));
  assert.ok(canPreviewCodeLanguage("mermaid", false));
  assert.ok(canPreviewCodeLanguage("html", true));
  assert.ok(canPreviewCodeLanguage("svg", true));
});

// ---------------------------------------------------------------------------
// 真实渲染断言（renderToStaticMarkup + streamdown 静态模式）
// ---------------------------------------------------------------------------

test("safe mode renders raw html as escaped text, not DOM nodes", () => {
  const html = renderMarkdown('<img src=x onerror="alert(1)"><script>alert(1)</script>', false);
  assert.ok(!html.includes("<img"), `raw <img> must not reach DOM: ${html}`);
  assert.ok(!html.includes("<script"), `raw <script> must not reach DOM: ${html}`);
  // 内容以转义文本形式保留（不静默丢失）
  assert.ok(html.includes("&lt;img"), html);
  assert.ok(html.includes("&lt;script"), html);
});

test("safe mode strips javascript: href on markdown links", () => {
  const html = renderMarkdown("[click me](javascript:alert(1))", false);
  assert.ok(
    !/<a[^>]*javascript:/i.test(html),
    `javascript: href must be stripped: ${html}`,
  );
  assert.ok(html.includes("click me"));
});

test("safe mode keeps normal links and images", () => {
  const html = renderMarkdown("[ok](https://example.com) ![pic](/files/a.png)", false);
  assert.ok(html.includes('href="https://example.com"'), html);
  assert.ok(html.includes("<img"), html);
});

test("safe mode KaTeX trust=false does not emit href from \\href", () => {
  const html = renderMarkdown("$\\href{javascript:alert(1)}{x}$", false);
  assert.ok(
    !/<a[^>]*javascript:/i.test(html),
    `katex \\href must not create javascript link: ${html}`,
  );
});

test("default mode still renders raw html (behavior unchanged)", () => {
  const html = renderMarkdown("<b>bold</b>", true);
  assert.ok(html.includes("<b>bold</b>"), `default mode must keep raw html: ${html}`);
});
