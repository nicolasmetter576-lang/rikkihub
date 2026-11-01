/**
 * Mermaid preview: the diagram source never runs in the app's origin. The local
 * mermaid bundle and the source (as an escaped JS string literal) go into the same
 * opaque-origin preview iframe as HTML previews, so rendering works offline and any
 * markup a diagram smuggles in stays inside the sandbox.
 */

import mermaidRuntime from "mermaid/dist/mermaid.min.js?raw";

/**
 * 把任意图源编成安全内联 JS 字符串字面量：JSON.stringify 之外额外转义
 * < >（防 </script>/<! 之类终结序列）与 U+2028/U+2029。图源从不被当成
 * HTML/脚本解析，只作为 mermaid.render 的文本参数。
 */
export function escapeInlineScriptSource(source: string): string {
  return JSON.stringify(source)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");
}

const DOCTYPE_PATTERN = /^\s*<!doctype\b[^>]*>/i;

/**
 * 构造 mermaid 预览 iframe 的完整 srcDoc。
 * 文档结构（全部可信节点，图源仅是字符串字面量）：
 *   <!doctype html><head><style>…</style></head>
 *   <body><div id=diagram></div><pre id=error></pre>
 *   <script>本地 mermaid standalone bundle</script>
 *   <script>bootstrap: initialize(strict) + render(source)</script>
 * errorText 用父侧已本地化的文案前缀，渲染失败时在 frame 内显示。
 */
export function buildMermaidPreviewDoc(source: string, errorText: string): string {
  const sourceLiteral = escapeInlineScriptSource(source.replace(DOCTYPE_PATTERN, ""));
  const errorLiteral = escapeInlineScriptSource(errorText);
  const bootstrap = `(async () => {
  const el = document.getElementById('diagram');
  const err = document.getElementById('error');
  try {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      suppressErrorRendering: true,
    });
    const out = await mermaid.render('mermaid-preview', ${sourceLiteral});
    el.innerHTML = out.svg;
  } catch (e) {
    err.style.display = 'block';
    err.textContent = ${errorLiteral} + '\\n' + (e && e.message ? e.message : String(e));
  }
})();`;
  return (
    `<!doctype html><head>` +
    `<style>` +
    `html,body{margin:0;padding:0;background:#fff}` +
    `body{display:flex;align-items:flex-start;justify-content:center;padding:16px;box-sizing:border-box;min-height:100vh}` +
    `#diagram svg{max-width:100%;height:auto}` +
    `#error{display:none;white-space:pre-wrap;color:#b91c1c;font:12px/1.5 monospace}` +
    `</style></head>` +
    `<body><div id="diagram"></div><pre id="error"></pre>` +
    `<script>${mermaidRuntime}</script>` +
    `<script>${bootstrap}</script>` +
    `</body>`
  );
}
