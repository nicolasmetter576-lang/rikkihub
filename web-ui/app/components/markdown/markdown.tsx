import * as React from "react";
import { useTranslation } from "react-i18next";
import { Streamdown, type StreamdownProps, type UrlTransform } from "streamdown";
import { cjk } from "@streamdown/cjk";
import { cn } from "~/lib/utils";
import {
  canPreviewCodeLanguage,
  markdownRehypePlugins,
  markdownUrlTransform,
  REMARK_PLUGINS,
  safeMarkdownUrlTransform,
} from "~/lib/markdown-pipeline";
import { getCodePreviewLanguage } from "~/components/workbench/code-preview-language";
import { useOptionalWorkbench } from "~/components/workbench/workbench-context";
import { useSettingsStore } from "~/stores";
import { workspaceImageSrc } from "~/lib/workspace-image";
import { useWorkspaceImages } from "./workspace-image-context";
import { CodeBlock } from "./code-block";
import "katex/dist/katex.min.css";
// mhchem 扩展: 支持 \ce{} 等化学公式语法
import "katex/dist/contrib/mhchem.mjs";
import "./markdown.css";
import "streamdown/styles.css";

// Regex patterns for preprocessing
const INLINE_LATEX_REGEX = /\\\((.+?)\\\)/g;
const BLOCK_LATEX_REGEX = /\\\[(.+?)\\\]/gs;
const CODE_BLOCK_REGEX = /```[\s\S]*?```|`[^`\n]*`/g;

// Cheap gate: preProcess only rewrites \( \) / \[ \] math (and historically <think> markup),
// so content without those tokens can skip the whole scan.
const PREPROCESS_MARKER_REGEX = /\\\(|\\\[|<think>/;

// Stable Streamdown props: fresh arrays/objects would defeat streamdown's per-block memo.
// remark/rehype 插件链在 ~/lib/markdown-pipeline（含 allowRawHtml 安全变体）。
const STREAMDOWN_PLUGINS: StreamdownProps["plugins"] = { cjk };
const STREAMDOWN_CONTROLS: StreamdownProps["controls"] = { code: false, mermaid: false };
const STREAMDOWN_ANIMATED: StreamdownProps["animated"] = {
  animation: "fadeIn",
  sep: "word",
  duration: 150,
};

// Preprocess markdown content
function preProcess(content: string): string {
  if (!PREPROCESS_MARKER_REGEX.test(content)) {
    return content;
  }
  // Find all code block positions
  const codeBlocks: { start: number; end: number }[] = [];
  let match;
  const codeBlockRegex = new RegExp(CODE_BLOCK_REGEX.source, "g");
  while ((match = codeBlockRegex.exec(content)) !== null) {
    codeBlocks.push({ start: match.index, end: match.index + match[0].length });
  }

  // Check if position is inside a code block
  const isInCodeBlock = (position: number): boolean => {
    return codeBlocks.some((range) => position >= range.start && position < range.end);
  };

  // Replace inline formulas \( ... \) to $ ... $, skip code blocks
  let result = content.replace(
    new RegExp(INLINE_LATEX_REGEX.source, "g"),
    (match, group1, offset) => {
      if (isInCodeBlock(offset)) {
        return match;
      }
      return `$${group1}$`;
    },
  );

  // Replace block formulas \[ ... \] to $$ ... $$, skip code blocks
  result = result.replace(new RegExp(BLOCK_LATEX_REGEX.source, "gs"), (match, group1, offset) => {
    if (isInCodeBlock(offset)) {
      return match;
    }
    return `$$${group1}$$`;
  });

  return result;
}

type MarkdownProps = {
  content: string;
  className?: string;
  onClickCitation?: (id: string) => void;
  allowCodePreview?: boolean;
  /**
   * 允许原始 HTML（rehype-raw）进入 DOM。
   * 默认 true 保持聊天区既有行为；false 时：
   * - raw HTML（<img onerror>/<script>/<svg> 等）不进入 DOM，按文本展示
   * - KaTeX trust=false，\href 等不再产出可点链接
   * - markdown 链接/图片 URI 经 safeMarkdownUrlTransform 过滤（javascript: 等被剥掉）
   * - html/svg 代码块的预览入口被屏蔽（预览等价于渲染 raw HTML）
   */
  allowRawHtml?: boolean;
  isAnimating?: boolean;
  /**
   * 仅在 allowRawHtml=false（安全模式）下生效的 URL 预解析钩子（B 工作区
   * 预览用：相对图片映射到鉴权下载 URL）。返回值先经
   * safeMarkdownUrlTransform 协议白名单过滤，不能绕过 javascript:/data:
   * 防护；返回 undefined/null 表示不干预（交回 safe transform）。
   * 默认模式（聊天区）完全忽略此 prop，渲染行为不变。
   */
  resolveUrl?: UrlTransform;
};

function getNodeText(node: React.ReactNode): string {
  if (node == null || typeof node === "boolean") return "";
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(getNodeText).join("");
  if (React.isValidElement<{ children?: React.ReactNode }>(node)) {
    return getNodeText(node.props.children);
  }
  return "";
}

export default function Markdown({
  content,
  className,
  onClickCitation,
  allowCodePreview = true,
  allowRawHtml = true,
  isAnimating = false,
  resolveUrl,
}: MarkdownProps) {
  const { t } = useTranslation("markdown");
  const workbench = useOptionalWorkbench();
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);
  const processedContent = React.useMemo(() => preProcess(content), [content]);
  const workspaceImages = useWorkspaceImages();
  const urlTransform = React.useMemo<StreamdownProps["urlTransform"]>(() => {
    if (resolveUrl && !allowRawHtml) {
      return (url, key, node) =>
        safeMarkdownUrlTransform(resolveUrl(url, key, node) ?? url, key, node);
    }
    // 聊天区：模型写的工作区图片地址（/workspace/out.png 等）改到工作区取图接口，其余原样
    if (allowRawHtml && workspaceImages) {
      return (url, key, node) =>
        key === "src" && node.tagName === "img" ? workspaceImageSrc(workspaceImages, url) : url;
    }
    return markdownUrlTransform(allowRawHtml);
  }, [allowRawHtml, resolveUrl, workspaceImages]);
  const handlePreviewCode = React.useCallback(
    (language: string, code: string) => {
      if (!allowCodePreview || !workbench) return;
      // 安全模式：html/svg 预览等价于渲染 raw HTML，屏蔽
      if (!canPreviewCodeLanguage(language, allowRawHtml)) return;

      const previewLanguage = getCodePreviewLanguage(language);
      if (!previewLanguage) return;

      workbench.openPanel({
        type: "code-preview",
        title: t("markdown.code_preview_title", {
          language: previewLanguage.toUpperCase(),
        }),
        payload: {
          language: previewLanguage,
          code,
        },
      });
    },
    [allowCodePreview, allowRawHtml, t, workbench],
  );

  const components = React.useMemo<NonNullable<StreamdownProps["components"]>>(
    () => ({
      pre: ({ children }) => <>{children}</>,
      code: ({ className, children, ...props }) => {
        const match = /language-([A-Za-z0-9_-]+)/.exec(className || "");
        const code = String(children).replace(/\n$/, "");
        const isBlock = code.includes("\n");

        if (match || isBlock) {
          const language = match?.[1] || "";
          return (
            <CodeBlock
              language={language}
              code={code}
              showLineNumbers={displaySetting?.showLineNumbers ?? false}
              wrapLines={displaySetting?.codeBlockAutoWrap ?? false}
              isAnimating={isAnimating}
              onPreview={
                allowCodePreview && workbench && canPreviewCodeLanguage(language, allowRawHtml)
                  ? () => {
                      handlePreviewCode(language, code);
                    }
                  : undefined
              }
            />
          );
        }

        return (
          <code className="inline-code" {...props}>
            {children}
          </code>
        );
      },
      a: ({ href, children, ...props }) => {
        const childText = getNodeText(children).trim();

        // Citation format: [citation,domain](id)
        if (childText.startsWith("citation,")) {
          const domain = childText.substring("citation,".length);
          const id = (href || "").trim();

          if (id.length === 6) {
            return (
              <span className="citation-badge" onClick={() => onClickCitation?.(id)} title={domain}>
                {domain}
              </span>
            );
          }

          if (href) {
            return (
              <a
                className="citation-badge"
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                title={domain}
                {...props}
              >
                {domain}
              </a>
            );
          }
        }

        return (
          <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
            {children}
          </a>
        );
      },
    }),
    [
      allowCodePreview,
      allowRawHtml,
      displaySetting?.codeBlockAutoWrap,
      displaySetting?.showLineNumbers,
      handlePreviewCode,
      isAnimating,
      onClickCitation,
      workbench,
    ],
  );

  return (
    <div className={cn("markdown", className)}>
      <Streamdown
        remarkPlugins={REMARK_PLUGINS}
        rehypePlugins={markdownRehypePlugins(allowRawHtml)}
        urlTransform={urlTransform}
        plugins={STREAMDOWN_PLUGINS}
        animated={STREAMDOWN_ANIMATED}
        isAnimating={isAnimating}
        controls={STREAMDOWN_CONTROLS}
        components={components}
      >
        {processedContent}
      </Streamdown>
    </div>
  );
}
