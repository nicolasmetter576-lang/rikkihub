import * as React from "react";

import { EditorView } from "@codemirror/view";
import { CaseSensitive, ChevronDown, ChevronUp, Replace, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { cn } from "~/lib/utils";
import { FIND_LIMIT, findCurrent, findMatches, setFindHighlight } from "@overlay/lib/writing-find";

export interface FindPanelRequest {
  /** Bumped by the toolbar button / Ctrl+F to focus the query field again. */
  seq: number;
  /** The selection to search for, when it was a short single line. */
  prefill: string | null;
  /** Ctrl+H opens with the replace row. */
  replace: boolean;
}

/**
 * The editor's find bar, laid out like the App's: query with a "3/12" count, previous / next, match case,
 * replace row, close. Matches follow the document; only a new query or option, or a replacement, moves the
 * selection to a match, so typing in the editor while the bar is open never jumps the caret. Each
 * replacement (and Replace all) is one undo step.
 */
export function WorkspaceFindPanel({
  view,
  doc,
  readOnly,
  request,
  onClose,
}: {
  view: EditorView | null;
  doc: string;
  readOnly: boolean;
  request: FindPanelRequest;
  onClose: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [query, setQuery] = React.useState(request.prefill ?? "");
  const [replacement, setReplacement] = React.useState("");
  const [caseSensitive, setCaseSensitive] = React.useState(false);
  const [replaceOpen, setReplaceOpen] = React.useState(request.replace && !readOnly);
  const [matches, setMatches] = React.useState<number[]>([]);
  const [current, setCurrent] = React.useState(-1);
  // The query (and case option) the shown matches belong to: no count while a new query is being searched.
  const [matchedFor, setMatchedFor] = React.useState<string | null>(null);
  const queryRef = React.useRef<HTMLInputElement | null>(null);
  const last = React.useRef<{ query: string | null; caseSensitive: boolean; pendingAnchor: number | null; current: number; matches: number[] }>(
    { query: null, caseSensitive: false, pendingAnchor: null, current: -1, matches: [] },
  );

  const show = React.useCallback((list: number[], index: number, length: number, reveal: boolean) => {
    if (!view) return;
    const start = list[index];
    view.dispatch({
      effects: [
        setFindHighlight.of({ matches: list, length, current: index }),
        ...(reveal && start !== undefined ? [EditorView.scrollIntoView(start, { y: "center" })] : []),
      ],
      ...(reveal && start !== undefined ? { selection: { anchor: start, head: start + length } } : {}),
    });
  }, [view]);

  React.useEffect(() => {
    // A query taken from the selection starts at that occurrence.
    if (request.prefill !== null) {
      const from = view?.state.selection.main.from ?? 0;
      const state = last.current;
      if (state.query === request.prefill) {
        // Same query: just move to the selected occurrence.
        const index = findCurrent(state.matches, from);
        last.current = { ...state, current: index };
        setCurrent(index);
        show(state.matches, index, request.prefill.length, true);
      } else {
        last.current = { ...state, pendingAnchor: from };
        setQuery(request.prefill);
      }
    }
    if (request.replace && !readOnly) setReplaceOpen(true);
    queryRef.current?.focus();
    queryRef.current?.select();
  }, [request, readOnly, view, show]);

  // Recompute when the text, query or option changes (debounced while typing).
  React.useEffect(() => {
    const timer = window.setTimeout(() => {
      const state = last.current;
      const queryChanged = query !== state.query || caseSensitive !== state.caseSensitive;
      const list = findMatches(doc, query, caseSensitive);
      const previousStart = state.matches[state.current];
      const selectionFrom = view?.state.selection.main.from ?? 0;
      const anchor = state.pendingAnchor ?? (queryChanged ? (previousStart ?? selectionFrom) : (previousStart ?? 0));
      const reveal = queryChanged || state.pendingAnchor !== null;
      const index = findCurrent(list, anchor);
      last.current = { query, caseSensitive, pendingAnchor: null, current: index, matches: list };
      setMatches(list);
      setCurrent(index);
      setMatchedFor(`${caseSensitive}:${query}`);
      show(list, index, query.length, reveal);
    }, last.current.query === null ? 0 : 80);
    return () => window.clearTimeout(timer);
  }, [doc, query, caseSensitive, show, view]);

  // Closing the bar clears the highlights.
  React.useEffect(() => () => {
    view?.dispatch({ effects: setFindHighlight.of(null) });
  }, [view]);

  // Reads the ref, not the rendered state: key repeat can fire several steps before the next render.
  const step = (delta: number) => {
    const { matches: list, current: at } = last.current;
    if (!list.length) return;
    const index = (at + delta + list.length) % list.length;
    last.current = { ...last.current, current: index };
    setCurrent(index);
    show(list, index, query.length, true);
  };

  const sameText = (text: string) => caseSensitive ? text === query : text.toLowerCase() === query.toLowerCase();

  const replaceCurrent = () => {
    const start = last.current.matches[last.current.current];
    if (!view || readOnly || start === undefined || !query) return;
    if (!sameText(view.state.doc.sliceString(start, start + query.length))) return;
    last.current = { ...last.current, pendingAnchor: start + replacement.length };
    view.dispatch({
      changes: { from: start, to: start + query.length, insert: replacement },
      selection: { anchor: start, head: start + replacement.length },
      userEvent: "input.replace",
    });
  };

  const replaceAll = () => {
    if (!view || readOnly || !query) return;
    const list = findMatches(view.state.doc.toString(), query, caseSensitive, Number.MAX_SAFE_INTEGER);
    if (!list.length) return;
    // One transaction: one undo step for the whole replacement.
    view.dispatch({
      changes: list.map(start => ({ from: start, to: start + query.length, insert: replacement })),
      userEvent: "input.replace.all",
    });
    toast.success(t("writing.replaced", { count: list.length }));
  };

  const close = () => {
    onClose();
    view?.focus();
  };
  const panelRef = React.useRef<HTMLDivElement | null>(null);
  const closeRef = React.useRef(close);
  closeRef.current = close;
  // Esc closes only the bar. Dialogs around the editor (Radix) listen for Escape on the document in the capture
  // phase, before this element sees it; a window capture listener runs first and marks it handled for them.
  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || !(event.target instanceof Node) || !panelRef.current?.contains(event.target)) return;
      event.preventDefault();
      closeRef.current();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, []);

  const count = !query || matchedFor !== `${caseSensitive}:${query}` ? "" : matches.length === 0 ? t("writing.find_none")
    : t("writing.find_count", { current: current + 1, total: matches.length }) + (matches.length >= FIND_LIMIT ? "+" : "");
  const iconButton = "inline-flex size-7 shrink-0 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-40";
  const toggled = "bg-muted text-foreground";
  const field = "h-7 w-full min-w-0 rounded-md border bg-background px-2 text-xs outline-none focus-visible:ring-1 focus-visible:ring-ring";

  return (
    <div ref={panelRef} data-testid="find-panel" className="flex shrink-0 flex-col gap-1 border-b bg-muted/30 px-2 py-1">
      <div className="flex items-center gap-1">
        <div className="relative min-w-0 flex-1">
          <input ref={queryRef} data-testid="find-query" className={cn(field, "pr-16")} value={query} placeholder={t("writing.find_placeholder")}
            aria-label={t("writing.find_placeholder")} onChange={event => setQuery(event.target.value)}
            onKeyDown={event => {
              if (event.key === "Enter") {
                event.preventDefault();
                step(event.shiftKey ? -1 : 1);
              }
            }} />
          {count ? <span data-testid="find-count" className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-[11px] tabular-nums text-muted-foreground">{count}</span> : null}
        </div>
        <button type="button" className={iconButton} disabled={!matches.length} title={t("writing.find_previous")} aria-label={t("writing.find_previous")}
          onMouseDown={event => event.preventDefault()} onClick={() => step(-1)}><ChevronUp className="size-4" /></button>
        <button type="button" className={iconButton} disabled={!matches.length} title={t("writing.find_next")} aria-label={t("writing.find_next")}
          onMouseDown={event => event.preventDefault()} onClick={() => step(1)}><ChevronDown className="size-4" /></button>
        <button type="button" className={cn(iconButton, caseSensitive && toggled)} aria-pressed={caseSensitive} title={t("writing.find_case")}
          aria-label={t("writing.find_case")} onMouseDown={event => event.preventDefault()} onClick={() => setCaseSensitive(value => !value)}>
          <CaseSensitive className="size-4" />
        </button>
        {readOnly ? null : (
          <button type="button" data-testid="find-toggle-replace" className={cn(iconButton, replaceOpen && toggled)} aria-pressed={replaceOpen}
            title={t("writing.find_toggle_replace")} aria-label={t("writing.find_toggle_replace")}
            onMouseDown={event => event.preventDefault()} onClick={() => setReplaceOpen(value => !value)}>
            <Replace className="size-4" />
          </button>
        )}
        <button type="button" className={iconButton} title={t("writing.find_close")} aria-label={t("writing.find_close")} onClick={close}>
          <X className="size-4" />
        </button>
      </div>
      {replaceOpen && !readOnly ? (
        <div className="flex items-center gap-1">
          <input data-testid="find-replacement" className={cn(field, "flex-1")} value={replacement} placeholder={t("writing.replace_placeholder")}
            aria-label={t("writing.replace_placeholder")} onChange={event => setReplacement(event.target.value)}
            onKeyDown={event => {
              if (event.key === "Enter") {
                event.preventDefault();
                replaceCurrent();
              }
            }} />
          <button type="button" data-testid="find-replace" className="h-7 shrink-0 rounded px-2 text-xs font-medium text-primary hover:bg-muted disabled:opacity-40"
            disabled={current < 0} onMouseDown={event => event.preventDefault()} onClick={replaceCurrent}>{t("writing.replace")}</button>
          <button type="button" data-testid="find-replace-all" className="h-7 shrink-0 rounded px-2 text-xs font-medium text-primary hover:bg-muted disabled:opacity-40"
            disabled={!matches.length} onMouseDown={event => event.preventDefault()} onClick={replaceAll}>{t("writing.replace_all")}</button>
        </div>
      ) : null}
    </div>
  );
}
