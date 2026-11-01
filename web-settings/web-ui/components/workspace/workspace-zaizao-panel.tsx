import * as React from "react";

import { ChevronDown, ChevronUp, SendHorizontal, Settings2, Square, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "~/components/ui/sheet";
import { Switch } from "~/components/ui/switch";
import { copyTextToClipboard } from "~/lib/clipboard";
import { cn } from "~/lib/utils";
import { useSettingsStore } from "~/stores/settings";
import { createLongPress } from "@overlay/lib/long-press";
import { tuiqiaoNormalize } from "@overlay/lib/tuiqiao-cache";
import {
  fetchZaizaoRange,
  updateZaizaoConfig,
  ZAIZAO_MAX_REQUIREMENT_CHARS,
  ZAIZAO_TYPES,
  type ZaizaoType,
} from "@overlay/lib/zaizao";
import {
  zaizaoBase,
  zaizaoBusy,
  zaizaoCache,
  zaizaoDraftLabel,
  zaizaoRoundMark,
  zaizaoUsable,
  type ZaizaoDraft,
  type ZaizaoEntry,
  type ZaizaoEntryState,
  type ZaizaoRound,
  type ZaizaoSendError,
} from "@overlay/lib/zaizao-cache";

const noSubscribe = () => () => undefined;
const nullState = () => null;

export interface ZaizaoPanelRequest {
  /** 文件身份（工作区 + 区域 + 路径之类）：会话按它和原文缓存。 */
  fileKey: string;
  fileName: string;
  /** Reads the current document. */
  getSource: () => string;
  start: number;
  end: number;
}

/**
 * 再造面板：原文（第一轮之前可扩到整句 / 整段、切换带后文）、各轮与各稿（点按复制、长按替换；「接着改这版」
 * 选定下一轮的底稿；重写的要点与思考内容可展开）、底部输入区（类型、最近用过的要求、补充要求）。
 * 会话放在 [zaizaoCache]：收起面板不丢、还在写的继续写；再框选同一段原文回到原来的对话。
 * 替换由 [onPick] 在编辑器里做（进入编辑器历史）；替换后会话保留。
 */
export function WorkspaceZaizaoPanel({
  request,
  onClose,
  onPick,
}: {
  request: ZaizaoPanelRequest | null;
  onClose: () => void;
  /** 返回 false = 原文已变，未替换（面板保持打开）。 */
  onPick: (range: { start: number; end: number; original: string }, text: string) => boolean;
}) {
  const { t } = useTranslation("workspace");
  const settings = useSettingsStore(s => s.settings?.zaizao);
  const recent = settings?.recentRequirements ?? [];
  const [entry, setEntry] = React.useState<ZaizaoEntry | null>(null);
  const state = React.useSyncExternalStore(entry?.subscribe ?? noSubscribe, entry?.getState ?? nullState, entry?.getState ?? nullState);
  const [originalExpanded, setOriginalExpanded] = React.useState(false);
  const [expanding, setExpanding] = React.useState(false);
  const inputRef = React.useRef<HTMLTextAreaElement | null>(null);
  const scrollRef = React.useRef<HTMLDivElement | null>(null);
  const stickToBottom = React.useRef(true);
  const draftCount = React.useRef(0);

  React.useEffect(() => {
    if (!request) return;
    const source = request.getSource();
    const range = tuiqiaoNormalize(source, request.start, request.end);
    if (!range) return;
    const next = zaizaoCache.lookup(request.fileKey, source, request.start, request.end) ??
      zaizaoCache.create(request.fileKey, {
        fileName: request.fileName,
        source,
        start: range.start,
        end: range.end,
        includeAfter: settings?.includeAfter !== false,
        type: ZAIZAO_TYPES.includes(settings?.defaultType as ZaizaoType) ? settings?.defaultType as ZaizaoType : "MODIFY",
      });
    setEntry(next);
    setOriginalExpanded(false);
    stickToBottom.current = true;
    draftCount.current = 0;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request]);

  React.useEffect(() => {
    if (entry && !entry.started) requestAnimationFrame(() => inputRef.current?.focus());
  }, [entry]);

  // Follow the bottom while text streams in unless the user scrolled up; a new draft (send, 换一版) jumps to it.
  React.useLayoutEffect(() => {
    const element = scrollRef.current;
    const count = state?.drafts.length ?? 0;
    if (count > draftCount.current) stickToBottom.current = true;
    draftCount.current = count;
    if (element && stickToBottom.current) element.scrollTop = element.scrollHeight;
  }, [state]);

  const errorText = (code: string, message: string) => {
    const known = ["EMPTY_RESULT", "NO_MODEL", "TIMEOUT", "EMPTY_REQUIREMENT", "REQUIREMENT_TOO_LONG", "TOO_MANY_ROUNDS", "SELECTION_TOO_LONG", "EMPTY_SELECTION"];
    if (known.includes(code)) return t(`zaizao.errors.${code}`);
    if (code === "HTTP_400" && /selection is too long/i.test(message)) return t("zaizao.errors.SELECTION_TOO_LONG");
    if (code === "HTTP_400" && /too many rounds/i.test(message)) return t("zaizao.errors.TOO_MANY_ROUNDS");
    return message || t("zaizao.errors.MODEL_ERROR");
  };

  const reportSend = (error: ZaizaoSendError | null) => {
    if (error) toast.error(t(`zaizao.errors.${error}`));
  };

  const expand = async (kind: "SENTENCE" | "PARAGRAPH") => {
    if (!request || !entry || entry.started || expanding) return;
    const source = request.getSource();
    if (!entry.matches(source)) {
      toast.error(t("tuiqiao.changed"));
      return;
    }
    setExpanding(true);
    try {
      const current = entry.getState();
      const range = await fetchZaizaoRange({ fileName: current.fileName, source, start: current.liveStart, end: current.liveEnd, expand: kind });
      if (range.start === current.liveStart && range.end === current.liveEnd) return;
      const latest = entry.getState();
      const next = zaizaoCache.lookup(request.fileKey, source, range.start, range.end) ??
        zaizaoCache.create(request.fileKey, {
          fileName: latest.fileName, source, start: range.start, end: range.end, includeAfter: latest.includeAfter, type: latest.type,
        });
      if (!next.started && !next.getState().draftText) next.setDraftText(latest.draftText);
      zaizaoCache.remove(entry);
      setEntry(next);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      toast.error(/too long/i.test(message) ? t("zaizao.errors.SELECTION_TOO_LONG") : t("zaizao.errors.EMPTY_SELECTION"));
    } finally {
      setExpanding(false);
    }
  };

  const copy = async (draft: ZaizaoDraft) => {
    try {
      await copyTextToClipboard(draft.result);
      toast.success(t("tuiqiao.copied"));
    } catch {
      toast.error(t("files.copy_failed"));
    }
  };

  const replace = (draft: ZaizaoDraft) => {
    if (!state || !onPick({ start: state.liveStart, end: state.liveEnd, original: state.original }, draft.result)) return;
    onClose();
  };

  const forget = (requirement: string) => {
    updateZaizaoConfig({ recentRequirements: recent.filter(item => item !== requirement) }).catch(() => toast.error(t("zaizao.errors.MODEL_ERROR")));
  };

  const busy = state ? zaizaoBusy(state) : false;
  const base = state ? zaizaoBase(state) : null;
  const started = (state?.rounds.length ?? 0) > 0;

  return (
    <Sheet open={request !== null && state !== null} onOpenChange={open => { if (!open) onClose(); }}>
      <SheetContent side="bottom" className="flex max-h-[88vh] flex-col gap-0" data-testid="zaizao-panel">
        <div className="mx-auto flex min-h-0 w-full max-w-2xl flex-1 flex-col px-4 pb-3">
          <SheetHeader className="flex-row items-center gap-2 px-0 pr-10 pb-2">
            <SheetTitle className="flex-1">{t("zaizao.title")}</SheetTitle>
            <Button variant="ghost" size="icon" asChild aria-label={t("zaizao.settings")}>
              <Link to="/settings/zaizao" data-testid="zaizao-open-settings"><Settings2 className="size-4" /></Link>
            </Button>
          </SheetHeader>
          <SheetDescription className="sr-only">{t("zaizao.description")}</SheetDescription>

          {state ? (
            <>
              <div
                ref={scrollRef}
                className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto pb-2"
                onScroll={event => {
                  const element = event.currentTarget;
                  stickToBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 64;
                }}
              >
                <button type="button" className="rounded-md bg-muted px-3 py-2 text-left" onClick={() => setOriginalExpanded(v => !v)}>
                  <div className="text-xs text-muted-foreground">{t("tuiqiao.original")}</div>
                  <div className={cn("whitespace-pre-wrap text-sm", !originalExpanded && "line-clamp-3")} data-testid="zaizao-original">
                    {state.original}
                  </div>
                </button>

                {!started ? (
                  <div className="flex flex-wrap items-center gap-2">
                    <Button variant="outline" size="sm" disabled={expanding} onClick={() => void expand("SENTENCE")}
                      data-testid="zaizao-expand-sentence">{t("tuiqiao.expand_sentence")}</Button>
                    <Button variant="outline" size="sm" disabled={expanding} onClick={() => void expand("PARAGRAPH")}
                      data-testid="zaizao-expand-paragraph">{t("tuiqiao.expand_paragraph")}</Button>
                    <label className="ml-auto flex items-center gap-2 text-sm">
                      {t("zaizao.include_after")}
                      <Switch checked={state.includeAfter} onCheckedChange={checked => entry?.setIncludeAfter(checked)}
                        data-testid="zaizao-include-after" />
                    </label>
                  </div>
                ) : null}

                {state.drafts.some(zaizaoUsable) ? (
                  <p className="text-xs text-muted-foreground" data-testid="zaizao-pick-hint">{t("zaizao.pick_hint")}</p>
                ) : null}

                {state.rounds.map(round => (
                  <RoundView
                    key={round.number}
                    round={round}
                    state={state}
                    baseId={base?.id ?? null}
                    busy={busy}
                    errorText={errorText}
                    onAnother={() => entry?.another(round.number)}
                    onRetry={id => entry?.retry(id)}
                    onContinue={id => entry?.continueFrom(id)}
                    onCopy={draft => void copy(draft)}
                    onReplace={replace}
                  />
                ))}
              </div>

              <div className="flex flex-col gap-2 border-t pt-2">
                {started ? (
                  <p className="text-xs text-muted-foreground" data-testid="zaizao-base">
                    {t("zaizao.base", { label: base ? zaizaoDraftLabel(base) : t("zaizao.base_original") })}
                  </p>
                ) : null}
                <div role="radiogroup" aria-label={t("zaizao.type")} className="flex overflow-hidden rounded-md border">
                  {ZAIZAO_TYPES.map(type => (
                    <button
                      key={type}
                      type="button"
                      role="radio"
                      aria-checked={state.type === type}
                      title={t(`zaizao.type_hints.${type}`)}
                      data-testid={`zaizao-type-${type}`}
                      className={cn("flex-1 px-2.5 py-1 text-xs", state.type === type ? "bg-primary text-primary-foreground" : "hover:bg-muted")}
                      onClick={() => entry?.setType(type)}
                    >
                      {t(`zaizao.types.${type}`)}
                    </button>
                  ))}
                </div>
                {recent.length > 0 ? (
                  <div className="flex gap-1.5 overflow-x-auto pb-0.5" data-testid="zaizao-recent">
                    {recent.map((text, index) => (
                      <span key={text} className="group flex shrink-0 items-center rounded-md bg-secondary text-xs text-secondary-foreground">
                        <button type="button" className="max-w-56 truncate py-1 pl-2.5 pr-1" title={text}
                          data-testid={`zaizao-recent-${index}`} onClick={() => { entry?.setDraftText(text); inputRef.current?.focus(); }}>
                          {text}
                        </button>
                        <button type="button" className="rounded p-1 opacity-60 hover:opacity-100" aria-label={t("zaizao.recent_remove")}
                          data-testid={`zaizao-recent-remove-${index}`} onClick={() => forget(text)}>
                          <X className="size-3" />
                        </button>
                      </span>
                    ))}
                  </div>
                ) : null}
                <div className="flex items-end gap-2">
                  <textarea
                    ref={inputRef}
                    value={state.draftText}
                    maxLength={ZAIZAO_MAX_REQUIREMENT_CHARS}
                    rows={2}
                    placeholder={started ? t("zaizao.requirement_next") : t("zaizao.requirement")}
                    className="min-h-[2.5rem] flex-1 resize-y rounded-md border bg-background px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    data-testid="zaizao-input"
                    onChange={event => entry?.setDraftText(event.target.value)}
                    onKeyDown={event => {
                      if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
                        event.preventDefault();
                        if (entry && !busy) reportSend(entry.send(entry.getState().draftText));
                      }
                    }}
                  />
                  {busy ? (
                    <Button variant="outline" size="icon" aria-label={t("zaizao.stop")} data-testid="zaizao-stop" onClick={() => entry?.stop()}>
                      <Square className="size-4" />
                    </Button>
                  ) : (
                    <Button size="icon" aria-label={t("zaizao.send")} data-testid="zaizao-send" disabled={!state.draftText.trim()}
                      title={t("zaizao.send_hint")} onClick={() => entry && reportSend(entry.send(entry.getState().draftText))}>
                      <SendHorizontal className="size-4" />
                    </Button>
                  )}
                </div>
              </div>
            </>
          ) : null}
        </div>
      </SheetContent>
    </Sheet>
  );
}

function RoundView({ round, state, baseId, busy, errorText, onAnother, onRetry, onContinue, onCopy, onReplace }: {
  round: ZaizaoRound;
  state: ZaizaoEntryState;
  baseId: number | null;
  busy: boolean;
  errorText: (code: string, message: string) => string;
  onAnother: () => void;
  onRetry: (draftId: number) => void;
  onContinue: (draftId: number) => void;
  onCopy: (draft: ZaizaoDraft) => void;
  onReplace: (draft: ZaizaoDraft) => void;
}) {
  const { t } = useTranslation("workspace");
  const drafts = round.draftIds.map(id => state.drafts[id]);
  const baseDraft = round.baseId === null ? null : state.drafts[round.baseId];
  return (
    <div className="flex flex-col gap-2" data-testid={`zaizao-round-${round.number}`}>
      <div className="flex items-start gap-2 pt-1">
        <div className="min-w-0 flex-1">
          <p className="line-clamp-3 text-sm font-medium">
            {zaizaoRoundMark(round.number)} {t(`zaizao.types.${round.type}`)} · {round.requirement}
          </p>
          {round.number > 1 ? (
            <p className="text-xs text-muted-foreground">
              {t("zaizao.based_on", { label: baseDraft ? zaizaoDraftLabel(baseDraft) : t("zaizao.base_original") })}
            </p>
          ) : null}
        </div>
        {!busy && drafts.some(zaizaoUsable) ? (
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" data-testid={`zaizao-another-${round.number}`} onClick={onAnother}>
            {t("zaizao.another")}
          </Button>
        ) : null}
      </div>
      {drafts.map(draft => (
        <DraftCard
          key={draft.id}
          draft={draft}
          isBase={draft.id === baseId}
          busy={busy}
          errorText={errorText}
          onRetry={() => onRetry(draft.id)}
          onContinue={() => onContinue(draft.id)}
          onCopy={() => onCopy(draft)}
          onReplace={() => onReplace(draft)}
        />
      ))}
    </div>
  );
}

function DraftCard({ draft, isBase, busy, errorText, onRetry, onContinue, onCopy, onReplace }: {
  draft: ZaizaoDraft;
  isBase: boolean;
  busy: boolean;
  errorText: (code: string, message: string) => string;
  onRetry: () => void;
  onContinue: () => void;
  onCopy: () => void;
  onReplace: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [planOpen, setPlanOpen] = React.useState(false);
  const [thinkingOpen, setThinkingOpen] = React.useState(false);
  const [pressing, setPressing] = React.useState(false);
  const usable = zaizaoUsable(draft);
  const latest = React.useRef({ onClick: onCopy, onLongPress: onReplace, onPressingChange: setPressing });
  latest.current = { onClick: onCopy, onLongPress: onReplace, onPressingChange: setPressing };
  const press = React.useMemo(() => createLongPress(() => latest.current), []);
  React.useEffect(() => press.cancel, [press]);
  const status = draft.status;
  const canContinue = usable && !isBase;
  const canRetry = !busy && (status.kind === "error" || status.kind === "stopped");
  const hasActions = canContinue || canRetry || draft.plan !== "" || draft.reasoning !== "";
  const note = status.kind === "loading"
    ? t(draft.result ? "zaizao.status.writing" : draft.plan ? "zaizao.status.planning" : draft.reasoning ? "zaizao.status.thinking" : "zaizao.status.writing")
    : status.kind === "stopped" ? t("zaizao.status.stopped")
      : status.kind === "error" ? errorText(status.code, status.message)
        : isBase ? t("zaizao.current_base") : "";
  return (
    <div
      className={cn(
        "rounded-md border transition-colors",
        isBase && "border-2 border-primary",
        usable && "hover:border-primary",
        pressing && "border-primary bg-primary/5 duration-500",
      )}
      data-testid={`zaizao-draft-${draft.round}-${draft.number}`}
    >
      <div className="flex items-center gap-2 px-3 pt-2 text-xs">
        <span className="font-medium text-primary">{zaizaoDraftLabel(draft)}</span>
        {note ? <span className={cn(status.kind === "error" ? "text-destructive" : "text-muted-foreground")} data-testid="zaizao-draft-status">{note}</span> : null}
        <span className="flex-1" />
        {pressing ? <span className="text-primary">{t("tuiqiao.release_to_replace")}</span> : null}
      </div>
      {draft.result ? (
        // 点按复制、长按替换：屏蔽系统长按菜单与文字选择；键盘 Enter 复制、Shift+Enter 替换。
        <button
          type="button"
          disabled={!usable}
          className="w-full select-none px-3 pt-1 text-left text-sm whitespace-pre-wrap [-webkit-touch-callout:none] disabled:cursor-default"
          data-testid="zaizao-draft-text"
          onPointerDown={event => { if (usable) press.down(event); }}
          onPointerMove={event => press.move(event)}
          onPointerUp={() => press.up()}
          onPointerCancel={press.cancel}
          onPointerLeave={press.cancel}
          onContextMenu={event => event.preventDefault()}
          onKeyDown={event => {
            if (!usable || (event.key !== "Enter" && event.key !== " ")) return;
            event.preventDefault();
            if (event.key === "Enter" && event.shiftKey) onReplace(); else onCopy();
          }}
        >
          {draft.result}
        </button>
      ) : status.kind === "loading" ? (
        <div className="mx-3 mt-2 h-1 overflow-hidden rounded bg-muted"><div className="h-full w-1/3 animate-pulse rounded bg-primary" /></div>
      ) : null}
      {!hasActions ? <div className="h-2" /> : (
      <div className="flex flex-wrap items-center gap-1 px-2 pb-1.5 pt-1">
        {canContinue ? (
          <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" data-testid="zaizao-continue" onClick={onContinue}>{t("zaizao.continue_this")}</Button>
        ) : null}
        {draft.plan ? <Toggle label={t("zaizao.plan")} open={planOpen} onClick={() => setPlanOpen(v => !v)} /> : null}
        {draft.reasoning ? <Toggle label={t("zaizao.thinking")} open={thinkingOpen} onClick={() => setThinkingOpen(v => !v)} /> : null}
        <span className="flex-1" />
        {canRetry ? (
          <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" data-testid="zaizao-retry" onClick={onRetry}>{t("tuiqiao.retry")}</Button>
        ) : null}
      </div>
      )}
      {planOpen ? <p className="whitespace-pre-wrap px-3 pb-2 text-xs text-muted-foreground" data-testid="zaizao-plan">{draft.plan}</p> : null}
      {thinkingOpen ? <p className="whitespace-pre-wrap px-3 pb-2 text-xs text-muted-foreground" data-testid="zaizao-thinking">{draft.reasoning}</p> : null}
    </div>
  );
}

function Toggle({ label, open, onClick }: { label: string; open: boolean; onClick: () => void }) {
  return (
    <button type="button" className="flex items-center gap-0.5 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:text-foreground"
      aria-expanded={open} onClick={onClick}>
      {label}
      {open ? <ChevronUp className="size-3" /> : <ChevronDown className="size-3" />}
    </button>
  );
}
