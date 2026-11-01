import { undo, redo } from "@codemirror/commands";
import { useTranslation } from "react-i18next";
import { formatWorkspaceSource, type SourceFormat } from "@overlay/lib/workspace-source-format";
import { cn } from "~/lib/utils";
import { useCallback, useEffect, useMemo, useState, useRef } from "react";
import { toast } from "sonner";
import CodeMirror from "@uiw/react-codemirror";
import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { GFM } from "@lezer/markdown";
import { markdownSemanticTheme, markdownSizeTheme, workspaceMarkdownStyles, markdownStylesDegraded } from "@overlay/lib/workspace-markdown-styles";
import { useSettingsStore } from "~/stores/settings";
import { Prec, type Extension } from "@codemirror/state";
import { EditorView, keymap, type ViewUpdate } from "@codemirror/view";
import { ChevronDown, FoldVertical, Indent, Space, UnfoldVertical } from "lucide-react";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger } from "~/components/ui/dropdown-menu";
import { applyFormat, formatKindOf, type FormatCommand, type FormatKind } from "@overlay/lib/writing-format";
import { writingFind } from "@overlay/lib/writing-find";
import { writingPairs } from "@overlay/lib/writing-pairs";
import { WorkspaceFindPanel, type FindPanelRequest } from "@overlay/components/workspace/workspace-find-panel";
import { tuiqiaoChange, tuiqiaoEnabledFor, tuiqiaoSelectionUsable, type TuiqiaoCandidate, type TuiqiaoPrepared } from "@overlay/lib/tuiqiao";
import { WorkspaceTuiqiaoPanel, type TuiqiaoPanelRequest } from "@overlay/components/workspace/workspace-tuiqiao-panel";
import { WorkspaceZaizaoPanel, type ZaizaoPanelRequest } from "@overlay/components/workspace/workspace-zaizao-panel";
import { ZAIZAO_MAX_SELECTION_CHARS, zaizaoChange } from "@overlay/lib/zaizao";
import { WEB_EDITOR_TYPOGRAPHY_DEFAULT, editorFocusMaxWidth, editorTypographyStyle, type EditorTypography } from "@overlay/lib/editor-typography";


export interface WorkspaceSourceEditorProps {
  value: string;
  onChange: (value: string) => void;
  readOnly?: boolean;
  /** 用于判断是否启用 markdown 渲染（.md/.markdown） */
  fileName?: string;
  className?: string;
  semantic?: boolean;
  /** 字体/字号/间距（缺省 = 加入排版设置之前的样子） */
  typography?: EditorTypography;
  /** 专注模式：正文限宽并水平居中 */
  centered?: boolean;
  /** 文件身份，推敲结果与再造会话按它和原文缓存；缺省用文件名。 */
  tuiqiaoKey?: string;
  /** 选区变化（状态条的「选中」统计）；没有选区时为 null。 */
  onSelectionChange?: (selection: { from: number; to: number } | null) => void;
}

/** 选区能否再造：去掉两端空白后非空且不超过再造的上限。 */
function zaizaoSelectionUsable(source: string, from: number, to: number): boolean {
  if (from < 0 || to > source.length || from >= to) return false;
  const trimmed = source.slice(from, to).trim();
  return trimmed.length > 0 && trimmed.length <= ZAIZAO_MAX_SELECTION_CHARS;
}

function useDocumentDark(): boolean {
  const [dark, setDark] = useState(() =>
    typeof document === "undefined" ? false : document.documentElement.classList.contains("dark"),
  );
  useEffect(() => {
    const root = document.documentElement;
    const sync = () => setDark(root.classList.contains("dark"));
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(root, { attributes: true, attributeFilter: ["class"] });
    return () => observer.disconnect();
  }, []);
  return dark;
}

export function createEditorTheme(dark: boolean, typography: EditorTypography = WEB_EDITOR_TYPOGRAPHY_DEFAULT, centered = false) {
  const style = editorTypographyStyle(typography);
  return EditorView.theme(
    {
      "&": {
        backgroundColor: "transparent",
        color: "var(--foreground)",
        height: "100%",
        fontSize: style.fontSize,
      },
      ".cm-scroller": {
        fontFamily: style.fontFamily,
        lineHeight: style.lineHeight,
        overflow: "auto",
      },
      ".cm-content": {
        fontFamily: style.fontFamily,
        letterSpacing: style.letterSpacing,
        color: "var(--foreground)",
        padding: centered ? "24px 0" : "12px 0",
        caretColor: "var(--foreground)",
        ...(centered ? { maxWidth: `${editorFocusMaxWidth(typography)}px`, margin: "0 auto", width: "100%" } : {}),
      },
      ".cm-line": {
        padding: "0 12px",
      },
      "&.cm-focused": {
        outline: "none",
      },
      ".cm-cursor, .cm-dropCursor": {
        borderLeftColor: "var(--foreground)",
      },
      ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": {
        backgroundColor: "color-mix(in oklab, var(--primary) 25%, transparent)",
      },
    },
    { dark },
  );
}

function isMarkdownFileName(fileName?: string): boolean {
  const name = fileName?.toLowerCase() ?? "";
  return name.endsWith(".md") || name.endsWith(".markdown");
}

export function WorkspaceSourceEditor({
  value,
  onChange,
  readOnly = false,
  fileName,
  className,
  semantic = true,
  typography = WEB_EDITOR_TYPOGRAPHY_DEFAULT,
  centered = false,
  tuiqiaoKey,
  onSelectionChange,
}: WorkspaceSourceEditorProps) {
  const { t } = useTranslation("workspace");
  const viewRef = useRef<EditorView | null>(null);
  const dark = useDocumentDark();
  const sizes = useSettingsStore(s => s.settings?.displaySetting?.workspaceMarkdownHeadingSizes === true);
  const degraded = markdownStylesDegraded(value);
  // 推敲: editable files whose type is enabled in the 推敲 settings. The floating button follows the selection's end.
  const tuiqiaoExtensions = useSettingsStore(s => s.settings?.tuiqiao?.extensions);
  const tuiqiaoEnabled = tuiqiaoEnabledFor(fileName, tuiqiaoExtensions) && !readOnly;
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const [selection, setSelection] = useState<{ from: number; to: number; top: number; left: number } | null>(null);
  const [tuiqiaoRequest, setTuiqiaoRequest] = useState<TuiqiaoPanelRequest | null>(null);
  // 再造 is offered wherever 推敲 is, each button with its own selection limit (再造 allows longer selections).
  const [zaizaoRequest, setZaizaoRequest] = useState<ZaizaoPanelRequest | null>(null);
  const [selectionKinds, setSelectionKinds] = useState({ tuiqiao: false, zaizao: false });
  const trackSelection = useCallback((view: EditorView) => {
    const range = view.state.selection.main;
    const wrapper = wrapperRef.current;
    const doc = view.state.doc.toString();
    const kinds = {
      tuiqiao: tuiqiaoEnabled && !range.empty && tuiqiaoSelectionUsable(doc, range.from, range.to),
      zaizao: tuiqiaoEnabled && !range.empty && zaizaoSelectionUsable(doc, range.from, range.to),
    };
    setSelectionKinds(previous => previous.tuiqiao === kinds.tuiqiao && previous.zaizao === kinds.zaizao ? previous : kinds);
    if (!wrapper || (!kinds.tuiqiao && !kinds.zaizao)) {
      setSelection(null);
      return;
    }
    const coords = view.coordsAtPos(range.to) ?? view.coordsAtPos(range.from);
    const scroller = view.scrollDOM.getBoundingClientRect();
    if (!coords || coords.bottom < scroller.top || coords.top > scroller.bottom) {
      setSelection(null);
      return;
    }
    const rect = wrapper.getBoundingClientRect();
    setSelection({
      from: range.from,
      to: range.to,
      top: Math.min(coords.bottom - rect.top + 6, rect.height - 36),
      left: Math.max(8, Math.min(coords.left - rect.left - 24, rect.width - 148)),
    });
  }, [tuiqiaoEnabled]);
  const selectionListener = useRef(onSelectionChange);
  selectionListener.current = onSelectionChange;
  const onEditorUpdate = useCallback((update: ViewUpdate) => {
    if (update.selectionSet || update.docChanged || update.geometryChanged || update.focusChanged) trackSelection(update.view);
    if (update.selectionSet || update.docChanged) {
      const range = update.state.selection.main;
      selectionListener.current?.(range.empty ? null : { from: range.from, to: range.to });
    }
  }, [trackSelection]);
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const onScroll = () => trackSelection(view);
    view.scrollDOM.addEventListener("scroll", onScroll, { passive: true });
    return () => view.scrollDOM.removeEventListener("scroll", onScroll);
  }, [trackSelection]);
  const openTuiqiao = () => {
    const view = viewRef.current;
    const range = view?.state.selection.main;
    if (!view || !range || !tuiqiaoEnabled || !tuiqiaoSelectionUsable(view.state.doc.toString(), range.from, range.to)) return;
    const name = fileName ?? "untitled.md";
    setTuiqiaoRequest({ fileKey: tuiqiaoKey ?? name, fileName: name, getSource: () => view.state.doc.toString(), start: range.from, end: range.to });
  };
  const openZaizao = () => {
    const view = viewRef.current;
    const range = view?.state.selection.main;
    if (!view || !range || !tuiqiaoEnabled || !zaizaoSelectionUsable(view.state.doc.toString(), range.from, range.to)) return;
    const name = fileName ?? "untitled.md";
    setZaizaoRequest({ fileKey: tuiqiaoKey ?? name, fileName: name, getSource: () => view.state.doc.toString(), start: range.from, end: range.to });
  };
  const pickZaizao = (range: { start: number; end: number; original: string }, text: string): boolean => {
    const view = viewRef.current;
    const change = view && !readOnly ? zaizaoChange(view.state.doc.toString(), range, text) : null;
    if (!view || !change) {
      toast.error(t("tuiqiao.changed"));
      return false;
    }
    view.dispatch({ changes: change, selection: { anchor: change.from + change.insert.length }, userEvent: "input.zaizao", scrollIntoView: true });
    toast.success(t("tuiqiao.replaced"));
    return true;
  };
  const pickTuiqiao = (prepared: TuiqiaoPrepared, candidate: TuiqiaoCandidate): boolean => {
    const view = viewRef.current;
    const change = view && !readOnly ? tuiqiaoChange(view.state.doc.toString(), prepared, candidate.text) : null;
    if (!view || !change) {
      toast.error(t("tuiqiao.changed"));
      return false;
    }
    // One transaction: one step in the editor history (Ctrl+Z / Undo button); the session autosaves it.
    view.dispatch({ changes: change, selection: { anchor: change.from + change.insert.length }, userEvent: "input.tuiqiao", scrollIntoView: true });
    toast.success(t("tuiqiao.replaced"));
    return true;
  };
  // Find bar (Ctrl+F, Ctrl+H with the replace row) and auto-closing pairs (the Web editor's own switch).
  const autoPair = useSettingsStore(s => s.settings?.displaySetting?.webWorkspaceEditorAutoPair !== false);
  const [findRequest, setFindRequest] = useState<FindPanelRequest | null>(null);
  const openFind = useCallback((replace: boolean) => {
    const view = viewRef.current;
    const range = view?.state.selection.main;
    const selected = view && range && !range.empty ? view.state.doc.sliceString(range.from, range.to) : "";
    const prefill = selected && selected.length <= 200 && !selected.includes("\n") ? selected : null;
    setFindRequest(previous => ({ seq: (previous?.seq ?? 0) + 1, prefill, replace }));
  }, []);
  const findKeys = useMemo(() => Prec.high(keymap.of([
    { key: "Mod-f", run: () => { openFind(false); return true; } },
    { key: "Mod-h", run: () => { openFind(!readOnly); return true; } },
  ])), [openFind, readOnly]);
  const formatKind = formatKindOf(fileName);
  const runFormat = (command: FormatCommand, kind: FormatKind) => {
    const view = viewRef.current;
    if (!view || readOnly) return;
    const range = view.state.selection.main;
    const edit = applyFormat(view.state.doc.toString(), range.from, range.to, command, kind);
    if (!edit) {
      toast.info(t("writing.format_unchanged"));
      return;
    }
    // One transaction (one undo step); not an "input" event, so any open fill box ends.
    view.dispatch({
      changes: { from: edit.from, to: edit.to, insert: edit.insert },
      selection: { anchor: edit.selectionStart, head: edit.selectionEnd },
      userEvent: "format",
      scrollIntoView: true,
    });
    view.focus();
    toast.success(t("writing.format_done"));
  };
  const extensions = useMemo<Extension[]>(() => {
    const result: Extension[] = [EditorView.lineWrapping, createEditorTheme(dark, typography, centered), writingFind(dark), findKeys];
    if (autoPair && !readOnly) result.push(writingPairs(dark));
    if (semantic && !degraded && isMarkdownFileName(fileName)) {
      // GFM extensions are bundled locally (@lezer/markdown), no CDN or new parser.
      result.push(markdown({ base: markdownLanguage, extensions: GFM }), workspaceMarkdownStyles, markdownSemanticTheme);
      if (sizes) result.push(markdownSizeTheme);
    }
    return result;
  }, [dark, fileName, semantic, sizes, degraded, typography, centered, findKeys, autoPair, readOnly]);
  // A web font that finishes loading changes glyph widths: let CodeMirror re-measure lines.
  useEffect(() => {
    const fonts = typeof document === "undefined" ? undefined : document.fonts;
    if (!fonts?.addEventListener) return;
    const remeasure = () => viewRef.current?.requestMeasure();
    fonts.addEventListener("loadingdone", remeasure);
    return () => fonts.removeEventListener("loadingdone", remeasure);
  }, []);

  const writingButtons = (
    <>
      <button type="button" disabled={!selectionKinds.tuiqiao} data-testid="tuiqiao-toolbar"
        className="rounded px-1.5 py-1 text-xs font-medium text-primary hover:bg-muted disabled:opacity-40"
        title={t("tuiqiao.hint")} onMouseDown={event => event.preventDefault()} onClick={openTuiqiao}>{t("tuiqiao.title")}</button>
      <button type="button" disabled={!selectionKinds.zaizao} data-testid="zaizao-toolbar"
        className="rounded px-1.5 py-1 text-xs font-medium text-primary hover:bg-muted disabled:opacity-40"
        title={t("zaizao.hint")} onMouseDown={event => event.preventDefault()} onClick={openZaizao}>{t("zaizao.title")}</button>
    </>
  );

  const toolButton = "rounded px-1.5 py-1 text-xs hover:bg-muted disabled:opacity-40";
  const findButton = (
    <button type="button" data-testid="find-toolbar" className={toolButton} title={`${t("writing.find")} (Ctrl+F)`}
      onMouseDown={event => event.preventDefault()} onClick={() => openFind(false)}>{t("writing.find")}</button>
  );
  const formatMenu = formatKind && !readOnly ? (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button type="button" data-testid="format-toolbar" className={cn(toolButton, "inline-flex items-center gap-0.5")}
          onMouseDown={event => event.preventDefault()}>
          {t("writing.format")}<ChevronDown className="size-3" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" onCloseAutoFocus={event => { event.preventDefault(); viewRef.current?.focus(); }}>
        <FormatScopeLabel view={viewRef.current} />
        {([
          ["blank_line_between", UnfoldVertical],
          ["remove_blank_lines", FoldVertical],
          ["indent", Indent],
          ["cjk_spacing", Space],
        ] as const).map(([command, Icon]) => (
          <DropdownMenuItem key={command} data-testid={`format-${command}`} onSelect={() => runFormat(command, formatKind)}>
            <Icon className="size-4" />{t(`writing.format_${command}`)}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  ) : null;

  return (
    <div className={cn("flex min-h-0 flex-col", className)}>
      {isMarkdownFileName(fileName) ? <div role="toolbar" aria-label={t("markdown_toolbar.title")} className="flex shrink-0 flex-wrap gap-1 border-b px-2 py-1">
        {(["undo", "redo", "heading", "bold", "italic", "strike", "list", "quote", "task", "link", "code"] as const).map(command => (
          <button key={command} type="button" disabled={readOnly} className="rounded px-1.5 py-1 text-xs hover:bg-muted disabled:opacity-40" aria-label={t(`markdown_toolbar.${command}`)} title={t(`markdown_toolbar.${command}`)} onMouseDown={event => event.preventDefault()} onClick={() => {
            const view = viewRef.current; if (!view || readOnly) return;
            if (command === "undo") undo(view); else if (command === "redo") redo(view);
            else view.dispatch(formatWorkspaceSource(view.state, command as SourceFormat));
            view.focus();
          }}>{t(`markdown_toolbar.${command}`)}</button>
        ))}
        {findButton}
        {formatMenu}
        {tuiqiaoEnabled ? writingButtons : null}
      </div> : <div role="toolbar" aria-label={t("writing.find")} className="flex shrink-0 flex-wrap gap-1 border-b px-2 py-1">
        {findButton}
        {formatMenu}
        {tuiqiaoEnabled ? writingButtons : null}
      </div>}
      {findRequest ? (
        <WorkspaceFindPanel view={viewRef.current} doc={value} readOnly={readOnly} request={findRequest} onClose={() => setFindRequest(null)} />
      ) : null}
    <div ref={wrapperRef} className="relative min-h-0 flex-1 overflow-hidden">
    <CodeMirror
      value={value}
      onChange={onChange}
      extensions={extensions}
      readOnly={readOnly}
      editable={!readOnly}
      theme="none"
      className="h-full overflow-hidden"
      onCreateEditor={view => { viewRef.current = view; trackSelection(view); }}
      onUpdate={onEditorUpdate}
      height="100%"
      basicSetup={{
        lineNumbers: false,
        foldGutter: false,
        highlightActiveLine: false,
        highlightActiveLineGutter: false,
        syntaxHighlighting: false,
        // Replaced by the writing pairs (CJK pairs, fill box) and the find bar above.
        closeBrackets: false,
        searchKeymap: false,
      }}
    />
      {selection && !tuiqiaoRequest && !zaizaoRequest ? (
        <div className="absolute z-10 flex overflow-hidden rounded-full border bg-background text-xs font-medium text-primary shadow-md"
          style={{ top: selection.top, left: selection.left }}>
          {selectionKinds.tuiqiao ? (
            <button type="button" data-testid="tuiqiao-floating" className="px-3 py-1 hover:bg-muted"
              onMouseDown={event => event.preventDefault()} onClick={openTuiqiao}>{t("tuiqiao.title")}</button>
          ) : null}
          {selectionKinds.tuiqiao && selectionKinds.zaizao ? <span className="my-1 w-px bg-border" /> : null}
          {selectionKinds.zaizao ? (
            <button type="button" data-testid="zaizao-floating" className="px-3 py-1 hover:bg-muted"
              onMouseDown={event => event.preventDefault()} onClick={openZaizao}>{t("zaizao.title")}</button>
          ) : null}
        </div>
      ) : null}
    </div>
    {tuiqiaoEnabled ? <WorkspaceTuiqiaoPanel request={tuiqiaoRequest} onClose={() => setTuiqiaoRequest(null)} onPick={pickTuiqiao} /> : null}
    {tuiqiaoEnabled ? <WorkspaceZaizaoPanel request={zaizaoRequest} onClose={() => setZaizaoRequest(null)} onPick={pickZaizao} /> : null}
    </div>
  );
}

/** 排版 menu header: what the commands will touch (the selected lines, or the whole file). */
function FormatScopeLabel({ view }: { view: EditorView | null }) {
  const { t } = useTranslation("workspace");
  const range = view?.state.selection.main;
  let label = t("writing.format_scope_all");
  if (view && range && !range.empty) {
    const end = view.state.doc.sliceString(range.to - 1, range.to) === "\n" ? range.to - 1 : range.to;
    const count = view.state.doc.lineAt(end).number - view.state.doc.lineAt(range.from).number + 1;
    label = t("writing.format_scope_lines", { count });
  }
  return <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">{label}</DropdownMenuLabel>;
}
