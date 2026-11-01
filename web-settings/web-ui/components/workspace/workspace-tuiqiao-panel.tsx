import * as React from "react";

import { ChevronDown, ChevronUp, Settings2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "~/components/ui/sheet";
import { copyTextToClipboard } from "~/lib/clipboard";
import { cn } from "~/lib/utils";
import { createLongPress } from "@overlay/lib/long-press";
import { tuiqiaoCache, type TuiqiaoEntry, type TuiqiaoEntryState, type TuiqiaoStatus } from "@overlay/lib/tuiqiao-cache";
import {
  fetchTuiqiaoConfig,
  TUIQIAO_EXPLANATIONS,
  TUIQIAO_MODES,
  updateTuiqiaoConfig,
  type TuiqiaoCandidate,
  type TuiqiaoConfig,
  type TuiqiaoConfigPatch,
  type TuiqiaoMode,
  type TuiqiaoPrepared,
  type TuiqiaoRequest,
} from "@overlay/lib/tuiqiao";

const EMPTY_STATE: TuiqiaoEntryState = { prepared: null, items: [], slotStatus: {}, overall: { kind: "done" } };
const noSubscribe = () => () => undefined;

export interface TuiqiaoPanelRequest {
  /** 文件身份（工作区 + 区域 + 路径之类）：结果按它和原文缓存。 */
  fileKey: string;
  fileName: string;
  /** Reads the current document (the request always sends the latest text). */
  getSource: () => string;
  start: number;
  end: number;
}

/**
 * 推敲面板：原文、档位（可切）、扩到整句/整段、角色卡片（占位 → 结果；失败的单独重试）、
 * 流式常规候选（点按复制、长按替换，展开看理由）、换一批、两项快捷设置与「全部推敲设置」链接。
 * 结果放在 [tuiqiaoCache]：收起面板不丢、还在生成的继续生成；同一处文字再推敲先显示旧结果。
 * 选区规整、档位判定、角色分配、扩展、提示词都由服务端完成；替换由 [onPick] 在编辑器里做（进入编辑器历史）。
 */
export function WorkspaceTuiqiaoPanel({
  request,
  onClose,
  onPick,
}: {
  request: TuiqiaoPanelRequest | null;
  onClose: () => void;
  /** 返回 false = 原文已变，未替换（面板保持打开）。 */
  onPick: (prepared: TuiqiaoPrepared, candidate: TuiqiaoCandidate) => boolean;
}) {
  const { t } = useTranslation("workspace");
  const [entry, setEntry] = React.useState<TuiqiaoEntry | null>(null);
  const { prepared, items, slotStatus, overall } = React.useSyncExternalStore(
    entry?.subscribe ?? noSubscribe,
    entry?.getState ?? (() => EMPTY_STATE),
    entry?.getState ?? (() => EMPTY_STATE),
  );
  const [config, setConfig] = React.useState<TuiqiaoConfig | null>(null);
  const [showSettings, setShowSettings] = React.useState(false);
  const [originalExpanded, setOriginalExpanded] = React.useState(false);

  React.useEffect(() => {
    if (!request) return;
    const source = request.getSource();
    setEntry(
      tuiqiaoCache.lookup(request.fileKey, source, request.start, request.end) ??
        tuiqiaoCache.start(request.fileKey, { fileName: request.fileName, source, start: request.start, end: request.end }),
    );
    setOriginalExpanded(false);
    if (!config) fetchTuiqiaoConfig().then(setConfig).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request]);

  /** A fresh request for the shown range; with [onlySlot] it re-runs that one slot of the shown entry. */
  const rerun = (extra: { mode?: TuiqiaoMode; expand?: "SENTENCE" | "PARAGRAPH" }, onlySlot?: string) => {
    if (!request) return;
    const range = prepared ?? request;
    const body: TuiqiaoRequest = { fileName: request.fileName, source: request.getSource(), start: range.start, end: range.end, ...extra };
    if (onlySlot && entry) {
      tuiqiaoCache.retrySlot(entry, body, onlySlot);
      return;
    }
    setOriginalExpanded(false);
    setEntry(tuiqiaoCache.start(request.fileKey, body));
  };

  const copy = async (candidate: TuiqiaoCandidate) => {
    try {
      await copyTextToClipboard(candidate.text);
      toast.success(t("tuiqiao.copied"));
    } catch {
      toast.error(t("files.copy_failed"));
    }
  };

  const replace = (candidate: TuiqiaoCandidate) => {
    if (!prepared || !onPick(prepared, candidate)) return;
    if (entry) tuiqiaoCache.remove(entry);
    setEntry(null);
    onClose();
  };

  const saveConfig = (patch: Pick<TuiqiaoConfigPatch, "candidateCount" | "explanation">) => {
    if (config) setConfig({ ...config, ...patch });
    updateTuiqiaoConfig(patch).then(setConfig).catch(() => undefined);
  };

  const errorText = (code: string, message: string) => {
    const known = ["EMPTY_SELECTION", "SELECTION_TOO_LONG", "NO_MODEL", "NO_CANDIDATES", "MARKUP_BROKEN", "TIMEOUT"];
    if (known.includes(code)) return t(`tuiqiao.errors.${code}`);
    if (code === "HTTP_400" && /too long/i.test(message)) return t("tuiqiao.errors.SELECTION_TOO_LONG");
    if (code === "HTTP_400" && /empty/i.test(message)) return t("tuiqiao.errors.EMPTY_SELECTION");
    return message || t("tuiqiao.errors.MODEL_ERROR");
  };

  const loading = overall.kind === "loading" || Object.values(slotStatus).some(status => status.kind === "loading");
  const status: TuiqiaoStatus = loading ? { kind: "loading" } : overall;

  return (
    <Sheet open={request !== null} onOpenChange={open => { if (!open) onClose(); }}>
      <SheetContent side="bottom" className="max-h-[85vh] overflow-y-auto" data-testid="tuiqiao-panel">
        <div className="mx-auto flex w-full max-w-2xl flex-col gap-3 px-4 pb-4">
          <SheetHeader className="flex-row items-center gap-2 px-0 pr-10 pb-0">
            <SheetTitle className="flex-1">{t("tuiqiao.title")}</SheetTitle>
            <div role="radiogroup" aria-label={t("tuiqiao.mode")} className="flex overflow-hidden rounded-md border">
              {TUIQIAO_MODES.map(mode => (
                <button
                  key={mode}
                  type="button"
                  role="radio"
                  aria-checked={prepared?.mode === mode}
                  data-testid={`tuiqiao-mode-${mode}`}
                  disabled={!prepared || loading}
                  className={cn(
                    "px-2.5 py-1 text-xs disabled:opacity-50",
                    prepared?.mode === mode ? "bg-primary text-primary-foreground" : "hover:bg-muted",
                  )}
                  onClick={() => { if (prepared?.mode !== mode) rerun({ mode }); }}
                >
                  {t(`tuiqiao.modes.${mode}`)}
                </button>
              ))}
            </div>
            <Button variant="ghost" size="icon" aria-label={t("tuiqiao.settings")} onClick={() => setShowSettings(v => !v)}>
              <Settings2 className="size-4" />
            </Button>
          </SheetHeader>
          <SheetDescription className="sr-only">{t("tuiqiao.description")}</SheetDescription>

          {showSettings ? (
            <div className="flex flex-col gap-2 rounded-md border p-3 text-sm" data-testid="tuiqiao-settings">
              <div className="flex flex-wrap items-center gap-2">
                <span className="w-20 text-muted-foreground">{t("tuiqiao.count")}</span>
                {[3, 4, 5].map(count => (
                  <Button key={count} size="sm" variant={config?.candidateCount === count ? "default" : "outline"}
                    onClick={() => saveConfig({ candidateCount: count })}>{count}</Button>
                ))}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <span className="w-20 text-muted-foreground">{t("tuiqiao.explanation")}</span>
                {TUIQIAO_EXPLANATIONS.map(explanation => (
                  <Button key={explanation} size="sm" variant={config?.explanation === explanation ? "default" : "outline"}
                    onClick={() => saveConfig({ explanation })}>{t(`tuiqiao.explanations.${explanation}`)}</Button>
                ))}
              </div>
              <p className="text-xs text-muted-foreground">{t("tuiqiao.settings_hint")}</p>
              <Link to="/settings/tuiqiao" className="text-xs text-primary hover:underline" data-testid="tuiqiao-open-settings">
                {t("tuiqiao.settings_all")}
              </Link>
            </div>
          ) : null}

          <button
            type="button"
            className="rounded-md bg-muted px-3 py-2 text-left"
            onClick={() => setOriginalExpanded(v => !v)}
          >
            <div className="text-xs text-muted-foreground">{t("tuiqiao.original")}</div>
            <div className={cn("whitespace-pre-wrap text-sm", !originalExpanded && "line-clamp-4")} data-testid="tuiqiao-original">
              {prepared?.original ?? ""}
            </div>
          </button>

          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={!prepared || loading} onClick={() => rerun({ expand: "SENTENCE" })}
              data-testid="tuiqiao-expand-sentence">{t("tuiqiao.expand_sentence")}</Button>
            <Button variant="outline" size="sm" disabled={!prepared || loading} onClick={() => rerun({ expand: "PARAGRAPH" })}
              data-testid="tuiqiao-expand-paragraph">{t("tuiqiao.expand_paragraph")}</Button>
          </div>

          {loading ? (
            <div className="flex flex-col gap-1">
              <div className="h-1 w-full overflow-hidden rounded bg-muted">
                <div className="h-full w-1/3 animate-pulse rounded bg-primary" />
              </div>
              {!prepared || overall.kind === "loading" ? <p className="text-xs text-muted-foreground">{t("tuiqiao.loading")}</p> : null}
            </div>
          ) : null}

          {items.length > 0 ? (
            <p className="text-xs text-muted-foreground" data-testid="tuiqiao-pick-hint">{t("tuiqiao.pick_hint")}</p>
          ) : null}

          {(overall.kind === "loading" ? [] : prepared?.slots ?? []).map(slot => {
            const slotItems = items.filter(item => item.slot === slot.slot);
            const state: TuiqiaoStatus = slotStatus[slot.slot] ?? { kind: "done" };
            const roleName = slot.kind === "role" ? slot.name ?? "" : null;
            return (
              <React.Fragment key={slot.slot}>
                {slotItems.map(item => {
                  const index = items.indexOf(item);
                  return (
                    <CandidateCard
                      key={`${index}:${item.candidate.text}`}
                      candidate={item.candidate}
                      roleName={roleName}
                      index={index}
                      whyLabel={t("tuiqiao.why")}
                      pressingLabel={t("tuiqiao.release_to_replace")}
                      onCopy={() => void copy(item.candidate)}
                      onReplace={() => replace(item.candidate)}
                    />
                  );
                })}
                {slotItems.length === 0 && state.kind === "loading" && roleName !== null ? (
                  <SlotPlaceholder roleName={roleName} text={t("tuiqiao.slot_loading")} />
                ) : null}
                {slotItems.length === 0 && state.kind === "loading" && roleName === null ? (
                  <p className="text-xs text-muted-foreground">{t("tuiqiao.loading")}</p>
                ) : null}
                {state.kind === "error" && overall.kind !== "error" ? (
                  <SlotPlaceholder roleName={roleName} text={errorText(state.code, state.message)} error
                    retryLabel={t("tuiqiao.retry")} onRetry={() => rerun({ mode: prepared?.mode }, slot.slot)} />
                ) : null}
              </React.Fragment>
            );
          })}

          {overall.kind === "error" ? (
            <p role="alert" className="text-sm text-destructive" data-testid="tuiqiao-error">{errorText(overall.code, overall.message)}</p>
          ) : null}

          {!loading ? (
            <div className="flex justify-end">
              <Button variant="ghost" size="sm" data-testid="tuiqiao-regenerate"
                onClick={() => rerun(prepared ? { mode: prepared.mode } : {})}>
                {status.kind === "error" ? t("tuiqiao.retry") : t("tuiqiao.regenerate")}
              </Button>
            </div>
          ) : null}
        </div>
      </SheetContent>
    </Sheet>
  );
}

function RoleBadge({ name }: { name: string }) {
  return <span className="rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-900 dark:bg-amber-900/40 dark:text-amber-100" data-testid="tuiqiao-role-badge">{name}</span>;
}

/** A slot without a result: a role still writing, or a failed slot with its own retry. */
function SlotPlaceholder({ roleName, text, error = false, retryLabel, onRetry }: {
  roleName: string | null;
  text: string;
  error?: boolean;
  retryLabel?: string;
  onRetry?: () => void;
}) {
  return (
    <div className="rounded-md border border-dashed" data-testid={error ? "tuiqiao-slot-failed" : "tuiqiao-slot-loading"}>
      <p className={cn("px-3 pt-2 text-xs", error ? "text-destructive" : "text-muted-foreground")}>{text}</p>
      <div className="flex items-center gap-2 px-3 pb-2 pt-1">
        {roleName !== null ? <RoleBadge name={roleName} /> : null}
        <span className="flex-1" />
        {onRetry ? (
          <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" data-testid="tuiqiao-slot-retry" onClick={onRetry}>{retryLabel}</Button>
        ) : null}
      </div>
    </div>
  );
}

function CandidateCard({ candidate, roleName, index, whyLabel, pressingLabel, onCopy, onReplace }: {
  candidate: TuiqiaoCandidate;
  roleName: string | null;
  index: number;
  whyLabel: string;
  pressingLabel: string;
  onCopy: () => void;
  onReplace: () => void;
}) {
  const [expanded, setExpanded] = React.useState(false);
  const [pressing, setPressing] = React.useState(false);
  const latest = React.useRef({ onClick: onCopy, onLongPress: onReplace, onPressingChange: setPressing });
  latest.current = { onClick: onCopy, onLongPress: onReplace, onPressingChange: setPressing };
  const press = React.useMemo(() => createLongPress(() => latest.current), []);
  React.useEffect(() => press.cancel, [press]);
  return (
    <div
      className={cn(
        "rounded-md border transition-colors hover:border-primary",
        pressing && "border-primary bg-primary/5 duration-500",
      )}
      data-testid={`tuiqiao-candidate-${index}`}
    >
      {/* 点按复制、长按替换：屏蔽系统长按菜单与文字选择；键盘 Enter 复制、Shift+Enter 替换。 */}
      <button
        type="button"
        className="w-full select-none px-3 pt-2 text-left text-sm whitespace-pre-wrap [-webkit-touch-callout:none]"
        data-testid={`tuiqiao-candidate-text-${index}`}
        onPointerDown={event => press.down(event)}
        onPointerMove={event => press.move(event)}
        onPointerUp={() => press.up()}
        onPointerCancel={press.cancel}
        onPointerLeave={press.cancel}
        onContextMenu={event => event.preventDefault()}
        onKeyDown={event => {
          if (event.key !== "Enter" && event.key !== " ") return;
          event.preventDefault();
          if (event.key === "Enter" && event.shiftKey) onReplace(); else onCopy();
        }}
      >
        {candidate.text}
      </button>
      <div className="flex items-center gap-2 px-3 pb-2 pt-1">
        {roleName !== null ? <RoleBadge name={roleName} /> : candidate.tone ? (
          <span className="rounded bg-secondary px-2 py-0.5 text-xs text-secondary-foreground">{candidate.tone}</span>
        ) : null}
        <span className="flex-1" />
        {pressing ? <span className="text-xs text-primary">{pressingLabel}</span> : null}
        {candidate.why ? (
          <button type="button" className="flex items-center gap-0.5 text-xs text-muted-foreground hover:text-foreground"
            aria-expanded={expanded} onClick={() => setExpanded(v => !v)}>
            {whyLabel}
            {expanded ? <ChevronUp className="size-3" /> : <ChevronDown className="size-3" />}
          </button>
        ) : null}
      </div>
      {expanded ? <p className="px-3 pb-2 text-xs text-muted-foreground" data-testid={`tuiqiao-why-${index}`}>{candidate.why}</p> : null}
    </div>
  );
}
