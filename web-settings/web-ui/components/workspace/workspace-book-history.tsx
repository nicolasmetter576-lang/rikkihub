import * as React from "react";

import { ArrowLeft } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { extractErrorMessage } from "~/lib/error";
import { ApiError } from "~/services/api";
import {
  beforeAiEdits,
  bookApi,
  type BookHistory,
  type BookHistoryComparison,
  type DiffBlock,
  type DiffSpan,
  type HistoryEntry,
  type HistorySource,
  type LockNotice,
  type TrashedFile,
} from "@overlay/lib/book";

type VersionEntry = Extract<HistoryEntry, { type: "version" }>;

function formatTime(at: number) {
  return new Date(at).toLocaleString(undefined, { dateStyle: "short", timeStyle: "short" });
}

function useSourceLabel(conversations: Record<string, string>) {
  const { t } = useTranslation("workspace");
  return (source: HistorySource) => {
    switch (source.type) {
      case "manual":
        return t("book.history_source_manual");
      case "ai": {
        const title = conversations[source.conversationId];
        return title ? t("book.history_source_ai_in", { title }) : t("book.history_source_ai");
      }
      case "replace":
        return t("book.history_source_replace");
      case "external_app":
        return t("book.history_source_external_app");
      case "external":
        return t("book.history_source_external");
      case "initial":
        return t("book.history_source_initial");
      case "restore":
        return t("book.history_source_restore");
    }
  };
}

/** 一个文件的版本历史：列表 → 与现在比较（宽屏左右并排），取回一段或整章还原。删版本、清空只在 App 里。 */
export function WorkspaceBookHistory({
  workspaceId,
  path,
  noticeId,
  onClose,
  onChanged,
}: {
  workspaceId: string;
  path: string;
  /** 从“改了锁定内容”的提醒进来：直接比较 AI 改之前那一版，顶部给“放回” */
  noticeId?: string;
  onClose: () => void;
  /** 稿子被还原或取回过：编辑器要重新读 */
  onChanged?: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [history, setHistory] = React.useState<BookHistory | null>(null);
  const [onlyBeforeAi, setOnlyBeforeAi] = React.useState(false);
  const [selected, setSelected] = React.useState<VersionEntry | null>(null);
  const [comparison, setComparison] = React.useState<BookHistoryComparison | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [confirmRestore, setConfirmRestore] = React.useState(false);
  const [notice, setNotice] = React.useState<LockNotice | null>(null);
  const label = useSourceLabel(history?.conversations ?? {});

  const report = React.useCallback(
    (error: unknown) => {
      const code = error instanceof ApiError ? error.errorCode : undefined;
      if (code === "FILE_REVISION_CONFLICT") toast.error(t("book.history_conflict"));
      else if (code === "HISTORY_VERSION_MISSING") toast.error(t("book.history_missing"));
      else toast.error(extractErrorMessage(error, t("book.history_failed")));
    },
    [t],
  );
  const load = React.useCallback(async () => {
    setHistory(await bookApi.history(workspaceId, path));
  }, [workspaceId, path]);
  const compare = React.useCallback(
    async (version: VersionEntry) => {
      setComparison(null);
      setComparison(await bookApi.compareVersion(workspaceId, path, version.sha));
    },
    [workspaceId, path],
  );
  React.useEffect(() => {
    load().catch(report);
  }, [load, report]);
  React.useEffect(() => {
    if (!noticeId) return;
    bookApi
      .locks(workspaceId)
      .then(({ notices }) => setNotice(notices.find((item) => item.id === noticeId && (item.kind === "changed" || item.kind === "not_restored")) ?? null))
      .catch(() => undefined);
  }, [workspaceId, noticeId]);
  // 第一次拿到历史和提醒：直接比较提醒里 AI 改之前那一版
  const opened = React.useRef(false);
  React.useEffect(() => {
    if (opened.current || !history || !notice?.beforeSha) return;
    opened.current = true;
    const version = history.entries.find((entry): entry is VersionEntry => entry.type === "version" && entry.sha === notice.beforeSha);
    if (version) setSelected(version);
  }, [history, notice]);
  React.useEffect(() => {
    if (selected) compare(selected).catch(report);
  }, [selected, compare, report]);

  const run = async (block: () => Promise<void>) => {
    setBusy(true);
    try {
      await block();
    } catch (error) {
      report(error);
    } finally {
      setBusy(false);
    }
    await load().catch(report);
    if (selected) await compare(selected).catch(report);
  };

  const entries = history ? (onlyBeforeAi ? beforeAiEdits(history.entries, history.currentWriter) : history.entries) : [];
  const changed = comparison?.diff.blocks.some((block) => block.type === "changed") ?? false;

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[90vh] flex-col sm:max-w-4xl" data-testid="book-history">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {selected ? (
              <Button size="icon" variant="ghost" className="size-7" aria-label={t("book.history_back")} onClick={() => setSelected(null)}>
                <ArrowLeft className="size-4" />
              </Button>
            ) : null}
            <span className="truncate">
              {t("book.history_title")} · {path.split("/").pop()}
            </span>
          </DialogTitle>
        </DialogHeader>
        {history?.paused ? <p className="text-xs text-destructive">{t("book.history_paused")}</p> : null}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {!history ? (
            <p className="text-sm text-muted-foreground">{t("list.loading")}</p>
          ) : selected ? (
            <div className="space-y-2">
              {notice && comparison ? (
                <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm" data-testid="book-history-notice">
                  <span className="min-w-0 flex-1">{t("book.lock.compare_notice")}</span>
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() =>
                      run(async () => {
                        const { done } = await bookApi.putBackNotice(workspaceId, notice.id, { path, revision: comparison.revision });
                        if (done) {
                          setNotice(null);
                          toast.success(t("book.lock.put_back_done"));
                          onChanged?.();
                        } else {
                          toast.error(t("book.lock.put_back_failed"));
                        }
                      })
                    }
                  >
                    {t("book.lock.put_back")}
                  </Button>
                </div>
              ) : null}
              <p className="text-xs text-muted-foreground">
                {t("book.history_this_version")} · {label(selected.source)} · {formatTime(selected.at)}
              </p>
              {!comparison ? (
                <p className="text-sm text-muted-foreground">{t("list.loading")}</p>
              ) : !changed ? (
                <p className="text-sm">{t("book.history_same")}</p>
              ) : comparison.diff.rewritten ? (
                <>
                  <p className="text-sm text-muted-foreground">{t("book.history_rewritten")}</p>
                  <pre className="whitespace-pre-wrap rounded-lg border p-3 font-sans text-sm">{comparison.old}</pre>
                </>
              ) : (
                <>
                  <div className="hidden grid-cols-2 gap-3 text-xs text-muted-foreground md:grid">
                    <span>{t("book.history_this_version")}</span>
                    <span>{t("book.history_now")}</span>
                  </div>
                  {comparison.diff.blocks.map((block, i) => (
                    <DiffBlockView
                      key={i}
                      block={block}
                      busy={busy}
                      onTakeBack={(index) =>
                        run(async () => {
                          await bookApi.takeBack(workspaceId, { path, sha: selected.sha, revision: comparison.revision, index });
                          toast.success(t("book.history_taken_back"));
                          onChanged?.();
                        })
                      }
                    />
                  ))}
                </>
              )}
            </div>
          ) : (
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={onlyBeforeAi} onChange={(event) => setOnlyBeforeAi(event.target.checked)} />
                {t("book.history_only_before_ai")}
              </label>
              {entries.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("book.history_empty")}</p>
              ) : (
                <div className="divide-y overflow-hidden rounded-xl border">
                  {entries.map((entry, i) =>
                    entry.type === "version" ? (
                      <button
                        key={i}
                        type="button"
                        className="flex w-full items-baseline justify-between gap-3 px-3 py-2 text-left hover:bg-muted/50"
                        onClick={() => setSelected(entry)}
                      >
                        <span className="min-w-0 truncate text-sm">{label(entry.source)}</span>
                        <span className="shrink-0 text-xs text-muted-foreground">{formatTime(entry.at)}</span>
                      </button>
                    ) : (
                      <div key={i} className="flex items-baseline justify-between gap-3 px-3 py-1.5 text-xs text-muted-foreground">
                        <span className="min-w-0 truncate">
                          {entry.type === "deleted"
                            ? `${t("book.history_deleted")} · ${label(entry.source)}`
                            : t("book.history_renamed", { from: entry.from })}
                        </span>
                        <span className="shrink-0">{formatTime(entry.at)}</span>
                      </div>
                    ),
                  )}
                </div>
              )}
            </div>
          )}
        </div>
        {selected && comparison && changed ? (
          <DialogFooter>
            {confirmRestore ? (
              <div className="flex w-full flex-wrap items-center justify-end gap-2">
                <span className="text-sm text-muted-foreground">{t("book.history_restore_confirm")}</span>
                <Button variant="outline" onClick={() => setConfirmRestore(false)}>
                  {t("book.cancel")}
                </Button>
                <Button
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      setConfirmRestore(false);
                      await bookApi.restoreVersion(workspaceId, { path, sha: selected.sha, revision: comparison.revision });
                      toast.success(t("book.history_restored"));
                      onChanged?.();
                    })
                  }
                >
                  {t("book.history_restore")}
                </Button>
              </div>
            ) : (
              <Button disabled={busy} onClick={() => setConfirmRestore(true)}>
                {t("book.history_restore")}
              </Button>
            )}
          </DialogFooter>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

function Spans({ spans, keep, strike = true }: { spans: DiffSpan[]; keep: "removed" | "added"; strike?: boolean }) {
  return (
    <>
      {spans.map((span, i) =>
        span.kind === "same" ? (
          <span key={i}>{span.text}</span>
        ) : span.kind === keep ? (
          <span key={i} className={keep === "removed" ? (strike ? "bg-destructive/20 line-through" : "bg-destructive/20") : "bg-primary/20"}>
            {span.text}
          </span>
        ) : null,
      )}
    </>
  );
}

/**
 * 一处改动的两稿（不带按钮，历史比较与逐处审阅共用）：左边旧稿（划线）、右边新稿（底色），窄屏上下排。
 * rejected：选了拒绝，旧稿转正常、新稿整段划线，所见即结果。
 */
export function DiffLinesView({
  old,
  new: added,
  spans,
  rejected = false,
}: {
  old: string[];
  new: string[];
  spans?: DiffSpan[][] | null;
  rejected?: boolean;
}) {
  return (
    <div className="grid gap-2 md:grid-cols-2">
      <div className="space-y-1">
        {spans
          ? spans.map((line, i) => (
              <p key={i}>
                <Spans spans={line} keep="removed" strike={!rejected} />
              </p>
            ))
          : old.filter((line) => line.trim() !== "").map((line, i) => (
              <p key={i} className={rejected ? "bg-destructive/20" : "bg-destructive/20 line-through"}>
                {line || " "}
              </p>
            ))}
      </div>
      <div className={rejected ? "space-y-1 text-muted-foreground line-through" : "space-y-1"}>
        {spans
          ? spans.map((line, i) => (
              <p key={i}>
                <Spans spans={line} keep="added" />
              </p>
            ))
          : added.filter((line) => line.trim() !== "").map((line, i) => (
              <p key={i} className="bg-primary/20">
                {line || " "}
              </p>
            ))}
      </div>
    </div>
  );
}

/** 一块：没变的只露头尾；改过的左边这一版（划线）、右边现在（底色），窄屏上下排。 */
function DiffBlockView({ block, busy, onTakeBack }: { block: DiffBlock; busy: boolean; onTakeBack: (index: number) => void }) {
  const { t } = useTranslation("workspace");
  if (block.type === "same") {
    const lines = block.lines.filter((line) => line.trim() !== "");
    if (lines.length === 0) return null;
    return (
      <div className="space-y-1 text-sm text-muted-foreground">
        {lines.length <= 2 ? (
          lines.map((line, i) => <p key={i}>{line || " "}</p>)
        ) : (
          <>
            <p className="line-clamp-2">{lines[0] || " "}</p>
            <p className="text-xs text-muted-foreground/70">{t("book.history_unchanged", { count: lines.length - 2 })}</p>
            <p className="line-clamp-2">{lines[lines.length - 1] || " "}</p>
          </>
        )}
      </div>
    );
  }
  return (
    <div className="space-y-2 rounded-lg border bg-muted/30 p-2 text-sm">
      <DiffLinesView old={block.old} new={block.new} spans={block.spans} />
      <div className="flex justify-end">
        <Button size="sm" variant="ghost" disabled={busy} onClick={() => onTakeBack(block.index)}>
          {t("book.history_take_back")}
        </Button>
      </div>
    </div>
  );
}

/** 回收站：放回原处。彻底删除、清空回收站、清空历史只在 App 里。 */
export function WorkspaceBookTrash({
  workspaceId,
  onClose,
  onChanged,
}: {
  workspaceId: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [items, setItems] = React.useState<TrashedFile[] | null>(null);
  const [busy, setBusy] = React.useState(false);
  const load = React.useCallback(async () => {
    setItems((await bookApi.trash(workspaceId)).items);
  }, [workspaceId]);
  React.useEffect(() => {
    load().catch((error) => toast.error(extractErrorMessage(error, t("book.history_failed"))));
  }, [load, t]);
  const putBack = async (item: TrashedFile) => {
    setBusy(true);
    try {
      const { path } = await bookApi.restoreTrashed(workspaceId, item);
      toast.success(t("book.trash_restored", { path }));
      onChanged();
    } catch (error) {
      toast.error(extractErrorMessage(error, t("book.history_failed")));
    } finally {
      setBusy(false);
      await load().catch(() => undefined);
    }
  };
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[85vh] flex-col sm:max-w-xl" data-testid="book-trash">
        <DialogHeader>
          <DialogTitle>{t("book.trash_title")}</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {!items ? (
            <p className="text-sm text-muted-foreground">{t("list.loading")}</p>
          ) : items.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("book.trash_empty_state")}</p>
          ) : (
            <div className="divide-y overflow-hidden rounded-xl border">
              {items.map((item) => (
                <div key={`${item.key}/${item.name}`} className="flex items-center gap-2 px-3 py-1.5">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm">{item.path.split("/").pop()}</p>
                    <p className="truncate text-xs text-muted-foreground">
                      {[item.path.includes("/") ? item.path.slice(0, item.path.lastIndexOf("/")) : null, formatTime(item.at)]
                        .filter(Boolean)
                        .join(" · ")}
                    </p>
                  </div>
                  <Button size="sm" variant="ghost" disabled={busy} onClick={() => void putBack(item)}>
                    {t("book.trash_restore")}
                  </Button>
                </div>
              ))}
            </div>
          )}
        </div>
        <p className="text-xs text-muted-foreground">{t("book.trash_app_only")}</p>
      </DialogContent>
    </Dialog>
  );
}
