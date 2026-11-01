import * as React from "react";

import { useTranslation } from "react-i18next";

import Markdown from "~/components/markdown/markdown";
import { markdownStylesDegraded } from "@overlay/lib/workspace-markdown-styles";
import { cn } from "~/lib/utils";
import { WorkspaceSourceEditor } from "@overlay/components/workspace/workspace-source-editor";
import { WorkspaceStatusBar } from "@overlay/components/workspace/workspace-status-bar";
import { WorkspaceTypographyButton, useEditorTypographyWithFont } from "@overlay/components/workspace/workspace-typography";
import { editorFocusMaxWidth, editorTypographyStyle } from "@overlay/lib/editor-typography";
import {
  isWorkspaceMarkdownFile,
  WORKSPACE_MARKDOWN_SAFE_PROPS,
  workspaceMarkdownUrlResolver,
} from "@overlay/lib/workspace";
import type { WorkspaceStorageArea } from "@overlay/types/workspace";

export function isMarkdownFileName(fileName?: string): boolean {
  return isWorkspaceMarkdownFile(fileName);
}



export interface WorkspaceTextEditorProps {
  value: string;
  onChange: (value: string) => void;
  readOnly?: boolean;
  fileName?: string;
  className?: string;
  documentLimit?: "workspace" | "device";
  /** Text shown in the tab bar when read-only; defaults to the Linux-area notice, null hides it. */
  readOnlyNotice?: string | null;
  /** Markdown 预览中相对图片的解析上下文；缺省则相对资源不加载。 */
  previewContext?: {
    workspaceId: string;
    area: WorkspaceStorageArea;
    baseDir: string;
  } | null;
  /** 专注模式：正文限宽居中 */
  centered?: boolean;
  /** 文件身份，推敲结果按它缓存；缺省用文件名。 */
  tuiqiaoKey?: string;
}

/**
 * 工作区文本编辑统一入口：非 Markdown 文件 = 纯源码编辑器；
 * .md/.markdown 文件 = 「纯源码 / 语义源码 / 完整预览」三个明确 tab，共用同一份内存草稿，
 * 切预览不丢未保存修改、不触发任何写盘。
 *
 * 预览安全：原文直传共享 Markdown（不改源码），allowRawHtml=false /
 * allowCodePreview=false / isAnimating=false —— raw HTML 不入 DOM、
 * KaTeX trust=false、URL 协议白名单；相对图片经 resolveUrl 在渲染层
 * 映射为带鉴权的工作区下载 URL。不使用 iframe/srcdoc。
 */
export function WorkspaceTextEditor({
  value,
  onChange,
  readOnly = false,
  fileName,
  className,
  previewContext = null,
  documentLimit = "workspace",
  readOnlyNotice,
  centered = false,
  tuiqiaoKey,
}: WorkspaceTextEditorProps) {
  const { t } = useTranslation("workspace");
  // 字体/字号/间距（Web 那一套），编辑与预览共用
  const { typography } = useEditorTypographyWithFont();
  const markdown = isMarkdownFileName(fileName);
  const [tab, setTab] = React.useState<"source" | "semantic" | "preview">("semantic");
  // The editor's selection, for the status bar's 选中 counts.
  const [selection, setSelection] = React.useState<{ from: number; to: number } | null>(null);

  // 换文件时回到编辑 tab
  const fileKey = `${previewContext?.workspaceId ?? ""}:${previewContext?.area ?? ""}:${previewContext?.baseDir ?? ""}:${fileName ?? ""}`;
  const lastFileKeyRef = React.useRef(fileKey);
  if (lastFileKeyRef.current !== fileKey) {
    lastFileKeyRef.current = fileKey;
    if (tab !== "semantic") setTab("semantic");
    if (selection) setSelection(null);
  }

  // 相对图片 → 鉴权下载 URL 的渲染层解析器（源码不改）
  const resolvePreviewUrl = React.useMemo(
    () => workspaceMarkdownUrlResolver(previewContext),
    [previewContext],
  );

  if (!markdown) {
    return (
      <div className={cn("relative flex min-h-0 flex-col", className)}>
        <div className="absolute right-3 top-1 z-10">
          <WorkspaceTypographyButton className="rounded border bg-background/90 px-1.5 py-1 text-xs text-muted-foreground shadow-sm hover:bg-muted hover:text-foreground" />
        </div>
        <WorkspaceSourceEditor
          value={value}
          onChange={onChange}
          readOnly={readOnly}
          fileName={fileName}
          className="min-h-0 flex-1"
          typography={typography}
          centered={centered}
          tuiqiaoKey={tuiqiaoKey}
          onSelectionChange={setSelection}
        />
        <WorkspaceStatusBar text={value} selection={selection} />
      </div>
    );
  }

  return (
    <div className={cn("flex min-h-0 flex-col", className)}>
      <div
        role="tablist"
        aria-label={t("markdown_preview.title")}
        className="flex shrink-0 items-center gap-1 border-b px-2 py-1"
      >
        {(
          [
            ["source", t("markdown_preview.source")],
            ["semantic", t("markdown_preview.semantic")],
            ["preview", t("markdown_preview.preview")],
          ] as const
        ).map(([value_, label]) => (
          <button
            key={value_}
            type="button"
            role="tab"
            aria-selected={tab === value_}
            data-tab={value_}
            className={cn(
              "rounded-md px-2.5 py-1 text-xs font-medium transition-colors",
              tab === value_
                ? "bg-muted text-foreground"
                : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
            )}
            onClick={() => setTab(value_)}
          >
            {label}
          </button>
        ))}
        {readOnly && readOnlyNotice !== null ? (
          <span className="ml-auto text-xs text-muted-foreground">
            {readOnlyNotice ?? t("files.linux_readonly")}
          </span>
        ) : null}
        <span className={cn(!(readOnly && readOnlyNotice !== null) && "ml-auto")}>
          <WorkspaceTypographyButton />
        </span>
      </div>
      {markdownStylesDegraded(value) ? <p role="status" className="px-3 py-1 text-xs text-muted-foreground">{t("markdown_preview.degraded", { limit: documentLimit === "device" ? "1 MiB" : "512 KiB" })}</p> : null}
      <div className="min-h-0 flex-1">
        <div className={cn("h-full", tab === "preview" && "hidden")} aria-hidden={tab === "preview"}>
          <WorkspaceSourceEditor
            value={value}
            onChange={onChange}
            readOnly={readOnly}
            fileName={fileName}
            className="h-full"
            semantic={tab !== "source"}
            typography={typography}
            centered={centered}
            tuiqiaoKey={tuiqiaoKey}
            onSelectionChange={setSelection}
          />
        </div>
        {tab === "preview" ? (
          <div className="h-full overflow-auto px-4 py-3" data-testid="workspace-md-preview">
            <div
              className="workspace-md-preview-body"
              style={{ ...editorTypographyStyle(typography), ...(centered ? { maxWidth: editorFocusMaxWidth(typography), margin: "0 auto" } : {}) }}
            >
            {value.trim() ? (
              <Markdown
                content={value}
                allowRawHtml={WORKSPACE_MARKDOWN_SAFE_PROPS.allowRawHtml}
                allowCodePreview={WORKSPACE_MARKDOWN_SAFE_PROPS.allowCodePreview}
                isAnimating={WORKSPACE_MARKDOWN_SAFE_PROPS.isAnimating}
                resolveUrl={resolvePreviewUrl}
              />
            ) : (
              <p className="text-sm text-muted-foreground">
                {t("markdown_preview.empty")}
              </p>
            )}
            </div>
          </div>
        ) : null}
      </div>
      <WorkspaceStatusBar text={value} selection={tab === "preview" ? null : selection} />
    </div>
  );
}
