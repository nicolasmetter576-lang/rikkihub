import type { StreamdownProps, UrlTransform } from "streamdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import rehypeRaw from "rehype-raw";

/**
 * Markdown remark/rehype 插件链（从 components/markdown/markdown.tsx 抽出，
 * 便于 node:test 静态断言）。
 *
 * 两种模式共享 remark 链；差异只在 rehype：
 * - 默认（allowRawHtml=true，聊天区现状）：rehype-katex(trust=true) + rehype-raw，
 *   原始 HTML 标签（<img>/<svg>/<details> 等）可以进 DOM —— 上游既有行为，不改。
 * - 安全（allowRawHtml=false）：rehype-katex(trust=false) 且不带 rehype-raw。
 *   streamdown 检测到自定义 rehypePlugins 不含 rehype-raw 时会自动把 mdast 的
 *   html 节点降级为 text，因此原始 HTML 以文本形式展示、绝不进 DOM；
 *   同时传入 urlTransform=defaultUrlTransform 拦截 javascript:/data: 等 URI。
 *
 * 数组必须是模块级常量：每次新建数组会破坏 streamdown 的 per-block memo 缓存键。
 */
type PluggableList = NonNullable<StreamdownProps["rehypePlugins"]>;

export const REMARK_PLUGINS: StreamdownProps["remarkPlugins"] = [remarkGfm, remarkMath];

/** 默认聊天渲染链（含 rehype-raw，raw HTML 生效） */
export const REHYPE_PLUGINS: PluggableList = [
  [rehypeKatex, { trust: true, strict: false }],
  rehypeRaw,
];

/** 安全渲染链（无 rehype-raw，KaTeX trust 关闭） */
export const REHYPE_PLUGINS_SAFE: PluggableList = [
  [rehypeKatex, { trust: false, strict: false }],
];

export function markdownRehypePlugins(allowRawHtml: boolean): PluggableList {
  return allowRawHtml ? REHYPE_PLUGINS : REHYPE_PLUGINS_SAFE;
}

const SAFE_URL_PROTOCOLS = new Set([
  "http:",
  "https:",
  "mailto:",
  "tel:",
  "irc:",
  "ircs:",
  "xmpp:",
]);

const URL_SCHEME_PATTERN = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;

/**
 * 安全模式的 URI 过滤（streamdown 自带的 defaultUrlTransform 是恒等函数，
 * 协议拦截原本由默认 rehype-harden 负责；我们换了自定义插件链，所以这里
 * 自己实现，语义对齐 react-markdown 的默认 transform）：
 * - 相对路径 / 锚点 / 查询串：放行
 * - 白名单协议：http/https/mailto/tel/irc/ircs/xmpp
 * - data: 只允许非 svg 的 image/* 且仅用于 src/srcSet
 * - javascript: / vbscript: / file: / blob: / 其它 data: → 剥掉
 */
export const safeMarkdownUrlTransform: UrlTransform = (url, key) => {
  // oxlint-disable-next-line no-control-regex -- 有意剥离控制字符与空白（协议混淆防护）
  const cleaned = url.replace(/[\u0000-\u0020]+/g, "").trim();
  const schemeMatch = URL_SCHEME_PATTERN.exec(cleaned);
  if (!schemeMatch) return url;
  const protocol = schemeMatch[0].toLowerCase();
  if (protocol === "data:") {
    const media = cleaned.slice("data:".length, cleaned.indexOf(";")).toLowerCase();
    if (
      (key === "src" || key === "srcSet") &&
      media.startsWith("image/") &&
      media !== "image/svg+xml"
    ) {
      return url;
    }
    return "";
  }
  return SAFE_URL_PROTOCOLS.has(protocol) ? url : "";
};

/**
 * 安全模式下透传给 Streamdown 的 urlTransform（作用于 hast 层所有 URL 属性，
 * 包括 markdown 链接/图片与（理论上）重新引入标签后的属性）。
 * 默认模式不传以保持现状。
 */
export function markdownUrlTransform(
  allowRawHtml: boolean,
): StreamdownProps["urlTransform"] | undefined {
  return allowRawHtml ? undefined : safeMarkdownUrlTransform;
}

/**
 * 安全模式下是否允许触发该语言的代码预览。
 * html/svg 预览会把用户源码直接渲染进 DOM，与 raw HTML 等价，安全模式屏蔽；
 * markdown/mermaid 由渲染管线生成 DOM，仍允许（受 allowCodePreview 控制）。
 */
export function canPreviewCodeLanguage(language: string, allowRawHtml: boolean): boolean {
  if (allowRawHtml) return true;
  const normalized = language.trim().toLowerCase();
  return normalized !== "html" && normalized !== "htm" && normalized !== "svg" && normalized !== "xml";
}
