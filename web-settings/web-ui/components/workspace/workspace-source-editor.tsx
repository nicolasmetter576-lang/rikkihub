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
import { ChevronDown, FoldVertical, Indent, Lock, Space, UnfoldVertical } from "lucide-react";
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
import { takeReveal } from "@overlay/lib/editor-reveal";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { WorkspaceBeatsPanel } from "@overlay/components/workspace/workspace-beats-panel";
import { WorkspaceBookHistory } from "@overlay/components/workspace/workspace-book-history";
import { bookApi, bookLocksQueryKey, bookQueryKey, insertionChanges, type BeatInsertion } from "@overlay/lib/book";
import { lockedPassages, setLockedRanges, touchesLocked } from "@overlay/lib/book-locks";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import { workspaceApi, workspaceQueryKeys } from "@overlay/lib/workspace";


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
  /** 作品版本历史整章还原或取回一段后：重新读文件。 */
  onFileReplaced?: () => void;
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

/** A file of a workspace's own files area, from the editor's key. */
function workspaceFileOf(key?: string): { workspaceId: string; path: string } | null {
  const match = key ? /^([0-9a-f-]{36}):FILES:(.+)$/.exec(key) : null;
  return match ? { workspaceId: match[1], path: match[2] } : null;
}

function isMarkdownFileName(fileName?: string): boolean {
  const name = fileName?.toLowerCase() ?? "";
  return name.endsWith(".md") || name.endsWith(".markdown");
}

export function WorkspaceSourceEditor({
  value,
  onChange,
  readOnly: readOnlyProp = false,
  fileName,
  className,
  semantic = true,
  typography = WEB_EDITOR_TYPOGRAPHY_DEFAULT,
  centered = false,
  tuiqiaoKey,
  onSelectionChange, onFileReplaced,
}: WorkspaceSourceEditorProps) {
  const { t } = useTranslation("workspace");
  const viewRef = useRef<EditorView | null>(null);
  const dark = useDocumentDark();
  // 节拍: chapters of a book workspace.
  const workspaceFile = workspaceFileOf(tuiqiaoKey);
  const bookWorkspaceId = workspaceFile?.workspaceId ?? "";
  const workspace = useQuery({
    queryKey: workspaceQueryKeys.detail(bookWorkspaceId),
    queryFn: () => workspaceApi.get(bookWorkspaceId),
    enabled: workspaceFile !== null,
    retry: false,
  });
  const book = useQuery({
    queryKey: bookQueryKey(bookWorkspaceId),
    queryFn: () => bookApi.get(bookWorkspaceId),
    enabled: workspaceFile !== null && workspace.data?.bookMode === true,
    retry: false,
  });
  const chapter = workspaceFile ? book.data?.items.find(item => item.type === "chapter" && item.path === workspaceFile.path) : undefined;
  const inBook = workspaceFile !== null && workspace.data?.bookMode === true;
  // 定稿锁：整章锁定时只读（解锁只在 App 里）；段落锁画竖条，挡住会改到它的推敲、再造、节拍换稿
  const queryClient = useQueryClient();
  const locksKey = ["workspaces", bookWorkspaceId, "book", "locks", workspaceFile?.path ?? ""] as const;
  const fileLocks = useQuery({
    queryKey: locksKey,
    queryFn: () => bookApi.fileLocks(bookWorkspaceId, workspaceFile?.path ?? ""),
    enabled: inBook && workspaceFile !== null,
    retry: false,
    // 位置对应读到的那一版稿子，之后随编辑映射；中途重取会对不上没保存的修改
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
  const chapterLocked = fileLocks.data?.chapter === true;
  const readOnly = readOnlyProp || chapterLocked;
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
    if (touchesLocked(view.state, change.from, change.to)) {
      toast.info(t("book.lock.apply_blocked"));
      return false;
    }
    keepVersion();
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
    if (touchesLocked(view.state, change.from, change.to)) {
      toast.info(t("book.lock.apply_blocked"));
      return false;
    }
    // One transaction: one step in the editor history (Ctrl+Z / Undo button); the session autosaves it.
    keepVersion();
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
    const result: Extension[] = [EditorView.lineWrapping, createEditorTheme(dark, typography, centered), writingFind(dark), findKeys, lockedPassages];
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

  // Opened to show a passage: select it once both the view and the file's text are there.
  const revealPassage = (view: EditorView | null) => {
    const target = view ? takeReveal(tuiqiaoKey, view.state.doc.toString()) : null;
    if (!view || !target) return;
    view.dispatch({ selection: { anchor: target.from, head: target.to }, effects: EditorView.scrollIntoView(target.from, { y: "center" }) });
    view.focus();
  };
  useEffect(() => revealPassage(viewRef.current), [value, tuiqiaoKey]);

  // 锁读到了（打开、加锁之后）或编辑器刚建好：把段落位置交给编辑器，之后随编辑映射
  const applyLocks = (view: EditorView | null) => {
    const passages = fileLocks.data?.passages ?? [];
    view?.dispatch({
      effects: setLockedRanges.of(
        passages.flatMap((p) => (p.start != null && p.end != null ? [{ from: p.start, to: p.end }] : [])),
      ),
    });
  };
  useEffect(() => applyLocks(viewRef.current), [fileLocks.data]);
  const refreshLocks = () => {
    void queryClient.invalidateQueries({ queryKey: locksKey });
    void queryClient.invalidateQueries({ queryKey: bookLocksQueryKey(bookWorkspaceId) });
    void queryClient.invalidateQueries({ queryKey: bookQueryKey(bookWorkspaceId) });
  };
  // 网页端可以加锁（记“来自网页端”）；对着磁盘上的稿子，选中的文字还没保存时服务端拒绝
  const lockMutation = useMutation({
    mutationFn: async (kind: "chapter" | "passages") => {
      const path = workspaceFile?.path ?? "";
      const current = await bookApi.fileLocks(bookWorkspaceId, path);
      if (kind === "chapter") return bookApi.lockChapter(bookWorkspaceId, path, current.revision ?? "");
      const view = viewRef.current;
      const range = view?.state.selection.main;
      if (!view || !range) throw new Error("No editor");
      const line = view.state.doc.lineAt(range.from);
      const from = range.empty ? line.from : range.from;
      const to = range.empty ? line.to : range.to;
      return bookApi.lockPassages(bookWorkspaceId, path, current.revision ?? "", from, to, view.state.doc.sliceString(from, to));
    },
    onSuccess: refreshLocks,
    onError: (error) => {
      const code = error && typeof error === "object" && "errorCode" in error ? (error as { errorCode?: string }).errorCode : undefined;
      if (code === "LOCK_TEXT_CHANGED") toast.error(t("book.lock.save_first"));
      else if (code === "LOCK_TOO_SHORT") toast.error(t("book.lock.too_short"));
      else if (code === "LOCK_OVERLAP") toast.error(t("book.lock.overlap"));
      else reportSettingsError(error, t("book.lock.failed"));
    },
  });

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

  /** 作品里的文件：应用推敲、再造、节拍前把现在的稿子存一版（不等它，失败也不挡）。 */
  function keepVersion() {
    const view = viewRef.current;
    if (!view || !inBook || !workspaceFile) return;
    bookApi.checkpoint(bookWorkspaceId, workspaceFile.path, view.state.doc.toString()).catch(() => undefined);
  }
  const [historyOpen, setHistoryOpen] = useState(false);
  const [beatsOpen, setBeatsOpen] = useState(false);
  const insertBeats = (insertions: BeatInsertion[]) => {
    const view = viewRef.current;
    if (!view) return;
    const changes = insertionChanges(insertions, view.state.doc.length);
    const last = insertions[insertions.length - 1];
    keepVersion();
    view.dispatch({ changes, selection: { anchor: last.offset + last.text.trimEnd().length }, userEvent: "input.beats", scrollIntoView: true });
    toast.success(t("book.beats_inserted_done"));
  };
  const replaceBeat = (from: number, to: number, text: string) => {
    const view = viewRef.current;
    if (view && touchesLocked(view.state, from, to)) {
      toast.info(t("book.lock.apply_blocked"));
      return;
    }
    keepVersion();
    viewRef.current?.dispatch({ changes: { from, to, insert: text }, selection: { anchor: from + text.length }, userEvent: "input.beats", scrollIntoView: true });
    toast.success(t("tuiqiao.replaced"));
  };

  const toolButton = "rounded px-1.5 py-1 text-xs hover:bg-muted disabled:opacity-40";
  const historyButton = inBook ? (
    <button type="button" data-testid="history-toolbar" className={toolButton} title={t("book.history_title")}
      onMouseDown={event => event.preventDefault()} onClick={() => setHistoryOpen(true)}>{t("book.history_title")}</button>
  ) : null;
  const lockMenu = inBook && workspaceFile && !readOnlyProp ? (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button type="button" data-testid="lock-toolbar" className={cn(toolButton, "inline-flex items-center gap-0.5")}
          onMouseDown={event => event.preventDefault()}>
          <Lock className={cn("size-3", chapterLocked ? "text-primary" : "")} />{t("book.lock.menu")}<ChevronDown className="size-3" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" onCloseAutoFocus={event => { event.preventDefault(); viewRef.current?.focus(); }}>
        {chapterLocked ? (
          <DropdownMenuItem disabled>{t("book.lock.unlock_in_app")}</DropdownMenuItem>
        ) : (
          <>
            <DropdownMenuItem onSelect={() => lockMutation.mutate("chapter")}>{t("book.lock.lock_chapter")}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => lockMutation.mutate("passages")}>
              {t(viewRef.current?.state.selection.main.empty === false ? "book.lock.lock_selected" : "book.lock.lock_cursor")}
            </DropdownMenuItem>
            {(fileLocks.data?.passages.length ?? 0) > 0 ? (
              <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
                {t("book.lock.passages_here", { count: fileLocks.data?.passages.length ?? 0 })}
              </DropdownMenuLabel>
            ) : null}
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  ) : null;
  const findButton = (
    <button type="button" data-testid="find-toolbar" className={toolButton} title={`${t("writing.find")} (Ctrl+F)`}
      onMouseDown={event => event.preventDefault()} onClick={() => openFind(false)}>{t("writing.find")}</button>
  );
  const beatsButton = chapter ? (
    <button type="button" data-testid="beats-toolbar" className={cn(toolButton, "font-medium text-primary")} title={t("book.beats_hint")}
      onMouseDown={event => event.preventDefault()} onClick={() => setBeatsOpen(open => !open)}>{t("book.beats_title")}</button>
  ) : null;
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
        {beatsButton}
        {historyButton}
        {lockMenu}
      </div> : <div role="toolbar" aria-label={t("writing.find")} className="flex shrink-0 flex-wrap gap-1 border-b px-2 py-1">
        {findButton}
        {formatMenu}
        {tuiqiaoEnabled ? writingButtons : null}
        {beatsButton}
        {historyButton}
        {lockMenu}
      </div>}
      {chapterLocked ? (
        <p className="shrink-0 border-b px-3 py-1 text-xs text-muted-foreground" data-testid="chapter-locked-banner">{t("book.lock.read_only_banner")}</p>
      ) : null}
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
      onCreateEditor={view => { viewRef.current = view; trackSelection(view); revealPassage(view); applyLocks(view); }}
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
    {historyOpen && workspaceFile ? (
      <WorkspaceBookHistory workspaceId={bookWorkspaceId} path={workspaceFile.path} onClose={() => setHistoryOpen(false)} onChanged={onFileReplaced} />
    ) : null}
    {beatsOpen && chapter ? (
      <WorkspaceBeatsPanel
        key={chapter.id}
        workspaceId={bookWorkspaceId}
        chapterId={chapter.id}
        text={value}
        getText={() => viewRef.current?.state.doc.toString() ?? ""}
        getCursor={() => viewRef.current?.state.selection.main.head ?? 0}
        readOnly={readOnly}
        onInsert={insertBeats}
        onReplace={replaceBeat}
        onClose={() => setBeatsOpen(false)}
      />
    ) : null}
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
