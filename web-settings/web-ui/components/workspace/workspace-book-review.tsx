import * as React from "react";

import { Lock } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { extractErrorMessage } from "~/lib/error";
import { DiffLinesView, WorkspaceBookHistory } from "@overlay/components/workspace/workspace-book-history";
import {
  bookApi,
  turnBlockSelectable,
  turnPicks,
  type TurnBlock,
  type TurnFile,
  type TurnFileOutcome,
  type TurnReview,
} from "@overlay/lib/book";

/**
 * 一轮改动的审阅（App 同一套读写）：按文件列出这一轮改了什么，每块选接受或拒绝，选好后“完成”一次写回。
 * 已审阅的只读，顶部可以撤销。整章锁的文件到 App 里解锁后再审阅。
 */
export function WorkspaceBookReview({
  workspaceId,
  conversationId,
  messageId,
  onClose,
  onChanged,
}: {
  workspaceId: string;
  conversationId: string;
  messageId: string;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [review, setReview] = React.useState<TurnReview | null | undefined>(undefined);
  const [rejected, setRejected] = React.useState<Record<string, number[]>>({});
  const [outcomes, setOutcomes] = React.useState<Record<string, TurnFileOutcome>>({});
  const [busy, setBusy] = React.useState(false);
  const [confirm, setConfirm] = React.useState<"complete" | "discard" | { restore: TurnFile } | null>(null);
  const [historyPath, setHistoryPath] = React.useState<string | null>(null);

  const load = React.useCallback(async () => {
    try {
      const next = await bookApi.turn(workspaceId, conversationId, messageId);
      setReview(next);
      // 记录变了（续写重开、文件没了）：选择只留还能选的块
      setRejected((current) =>
        Object.fromEntries(
          Object.entries(current)
            .map(([key, indices]) => {
              const file = next.files.find((item) => item.key === key);
              const kept = file ? indices.filter((index) => file.blocks?.[index] && turnBlockSelectable(file, file.blocks[index])) : [];
              return [key, kept] as const;
            })
            .filter(([, indices]) => indices.length > 0),
        ),
      );
    } catch {
      setReview(null);
    }
  }, [workspaceId, conversationId, messageId]);
  React.useEffect(() => {
    void load();
  }, [load]);

  const rejectCount = Object.values(rejected).reduce((sum, indices) => sum + indices.length, 0);
  const rejectFiles = Object.values(rejected).filter((indices) => indices.length > 0).length;
  const reviewing = review?.status === "PENDING";

  const toggle = (file: TurnFile, block: TurnBlock) => {
    if (!turnBlockSelectable(file, block)) return;
    const partner = block.partner != null && file.blocks?.[block.partner] && turnBlockSelectable(file, file.blocks[block.partner]) ? block.partner : null;
    setRejected((current) => {
      const indices = current[file.key] ?? [];
      const both = partner == null ? [block.index] : [block.index, partner];
      const next = indices.includes(block.index) ? indices.filter((index) => !both.includes(index)) : [...new Set([...indices, ...both])];
      return { ...current, [file.key]: next };
    });
  };

  const rejectAll = (file: TurnFile) => {
    const pending = (file.blocks ?? []).filter((block) => block.state === "pending");
    const picked = pending.filter((block) => turnBlockSelectable(file, block) && !block.locked).map((block) => block.index);
    setRejected((current) => ({ ...current, [file.key]: [...new Set([...(current[file.key] ?? []), ...picked])] }));
    if (pending.length > picked.length) toast.info(t("book.turn.reject_all_skipped", { count: pending.length - picked.length }));
  };

  const run = async (block: () => Promise<void>) => {
    setBusy(true);
    try {
      await block();
    } catch (error) {
      toast.error(extractErrorMessage(error, t("book.history_failed")));
    } finally {
      setBusy(false);
    }
    await load();
  };

  const undo = () =>
    run(async () => {
      const { results } = await bookApi.undoTurn(workspaceId, conversationId, messageId);
      setRejected({});
      setOutcomes({});
      const skipped = results.filter((result) => result.outcome !== "ok").length;
      if (skipped === 0) toast.success(t("book.turn.undone"));
      else toast.warning(t("book.turn.undone_skipped", { count: skipped }));
      onChanged?.();
    });

  const complete = () =>
    run(async () => {
      if (!review) return;
      const { results } = await bookApi.completeTurn(workspaceId, conversationId, messageId, turnPicks(review, rejected));
      const failed = results.filter((result) => result.outcome !== "ok");
      const reverted = results.reduce((sum, result) => sum + (result.reverted ?? 0), 0);
      onChanged?.();
      // 写失败的文件留着选择；冲突的文件稿子变了，重新选
      const retry = new Set(failed.filter((result) => result.outcome === "failed").map((result) => result.key));
      setOutcomes(Object.fromEntries(failed.map((result) => [result.key, result])));
      setRejected((current) => Object.fromEntries(Object.entries(current).filter(([key]) => retry.has(key))));
      if (failed.length > 0) {
        toast.warning(t("book.turn.partly"));
        return;
      }
      if (reverted > 0) {
        toast.success(t("book.turn.reverted_toast", { count: reverted }), {
          duration: 8000,
          action: {
            label: t("book.turn.undo"),
            onClick: () => {
              void bookApi
                .undoTurn(workspaceId, conversationId, messageId)
                .then(({ results: undone }) => {
                  const skipped = undone.filter((result) => result.outcome !== "ok").length;
                  if (skipped === 0) toast.success(t("book.turn.undone"));
                  else toast.warning(t("book.turn.undone_skipped", { count: skipped }));
                  onChanged?.();
                })
                .catch((error) => toast.error(extractErrorMessage(error, t("book.history_failed"))));
            },
          },
        });
      } else {
        toast.success(t("book.turn.reviewed_toast"));
      }
      onClose();
    });

  const restoreWhole = (file: TurnFile) =>
    run(async () => {
      const { results } = await bookApi.completeTurn(workspaceId, conversationId, messageId, [
        { key: file.key, revision: file.revision, before: file.before, after: file.after, reject: [], whole: true },
      ]);
      const result = results[0];
      setOutcomes((current) => {
        const next = { ...current };
        if (result.outcome === "ok") delete next[file.key];
        else next[file.key] = result;
        return next;
      });
      setRejected((current) => {
        const next = { ...current };
        delete next[file.key];
        return next;
      });
      onChanged?.();
    });

  const close = () => (rejectCount > 0 ? setConfirm("discard") : onClose());
  const pendingCount = review?.files.reduce((sum, file) => sum + (file.blocks ?? []).filter((block) => block.state === "pending").length, 0) ?? 0;

  return (
    <Dialog open onOpenChange={(open) => !open && close()}>
      <DialogContent className="flex max-h-[90vh] flex-col sm:max-w-4xl" data-testid="book-turn-review">
        <DialogHeader>
          <DialogTitle className="truncate">
            {t("book.turn.title")}
            {review ? <span className="ml-2 text-xs font-normal text-muted-foreground">{new Date(review.finishedAt).toLocaleString(undefined, { dateStyle: "short", timeStyle: "short" })}</span> : null}
          </DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto">
          {review === undefined ? (
            <p className="text-sm text-muted-foreground">{t("list.loading")}</p>
          ) : review === null ? (
            <p className="text-sm text-muted-foreground">{t("book.turn.not_found")}</p>
          ) : (
            <>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="flex-1">
                  {reviewing
                    ? t("book.turn.summary", { count: pendingCount, rejected: rejectCount })
                    : t("book.turn.reviewed_summary", { count: review.reverted })}
                </span>
                {review.files.some((file) => file.canUndo) ? (
                  <Button size="sm" variant="ghost" disabled={busy} onClick={() => void undo()} data-testid="book-turn-undo">
                    {t("book.turn.undo")}
                  </Button>
                ) : null}
              </div>
              {review.unattributed ? <p className="text-xs text-muted-foreground">{t(`book.turn.unattributed_${review.unattributed}`)}</p> : null}
              {review.files.map((file) => (
                <TurnFileView
                  key={file.key}
                  file={file}
                  outcome={outcomes[file.key]}
                  rejected={rejected[file.key] ?? []}
                  busy={busy}
                  onToggle={(block) => toggle(file, block)}
                  onRejectAll={() => rejectAll(file)}
                  onRestoreWhole={() => setConfirm({ restore: file })}
                  onHistory={() => setHistoryPath(file.path)}
                />
              ))}
            </>
          )}
        </div>
        {confirm ? (
          <DialogFooter>
            <div className="flex w-full flex-wrap items-center justify-end gap-2">
              <span className="min-w-0 flex-1 text-sm text-muted-foreground">
                {confirm === "complete"
                  ? t("book.turn.complete_confirm", { count: rejectCount, files: rejectFiles })
                  : confirm === "discard"
                    ? t("book.turn.discard")
                    : confirm.restore.changesSince == null
                      ? t("book.turn.restore_whole_confirm_unknown")
                      : confirm.restore.changesSince > 0
                        ? t("book.turn.restore_whole_confirm", { count: confirm.restore.changesSince })
                        : t("book.turn.restore_whole_confirm_none")}
              </span>
              <Button variant="outline" onClick={() => setConfirm(null)}>
                {t("book.cancel")}
              </Button>
              <Button
                disabled={busy}
                variant={confirm !== "complete" ? "destructive" : "default"}
                onClick={() => {
                  const action = confirm;
                  setConfirm(null);
                  if (action === "complete") void complete();
                  else if (action === "discard") onClose();
                  else void restoreWhole(action.restore);
                }}
              >
                {t("book.turn.confirm")}
              </Button>
            </div>
          </DialogFooter>
        ) : reviewing ? (
          <DialogFooter>
            <Button disabled={busy} onClick={() => (rejectCount > 0 ? setConfirm("complete") : void complete())} data-testid="book-turn-done">
              {t("book.turn.done")}
            </Button>
          </DialogFooter>
        ) : null}
        {historyPath ? (
          <WorkspaceBookHistory workspaceId={workspaceId} path={historyPath} onClose={() => setHistoryPath(null)} onChanged={() => void load()} />
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

function TurnFileView({
  file,
  outcome,
  rejected,
  busy,
  onToggle,
  onRejectAll,
  onRestoreWhole,
  onHistory,
}: {
  file: TurnFile;
  outcome?: TurnFileOutcome;
  rejected: number[];
  busy: boolean;
  onToggle: (block: TurnBlock) => void;
  onRejectAll: () => void;
  onRestoreWhole: () => void;
  onHistory: () => void;
}) {
  const { t } = useTranslation("workspace");
  const blocks = file.blocks ?? [];
  const notes = [
    outcome?.outcome === "conflict" ? t("book.turn.outcome_conflict") : null,
    outcome?.outcome === "locked" ? t("book.turn.chapter_locked") : null,
    outcome?.outcome === "failed" ? t("book.turn.outcome_failed", { count: Math.max(1, outcome.failed?.length ?? 0) }) : null,
    outcome?.outcome === "missing" ? t("book.turn.outcome_missing") : null,
  ].filter(Boolean);
  const info = [
    file.gone && file.kind !== "deleted" ? t("book.turn.file_gone") : null,
    file.chapterLocked && !file.done && outcome?.outcome !== "locked" ? t("book.turn.chapter_locked") : null,
    file.mixed ? t("book.turn.mixed") : null,
    file.missing ? t(`book.turn.missing_${file.missing}`) : null,
    file.kind === "created" && blocks.some((block) => block.state === "changed_later") ? t("book.turn.created_changed") : null,
    file.whole ? t("book.turn.whole_only") : null,
    file.layoutOnly ? t("book.turn.layout_only") : null,
    file.layout ? t("book.turn.layout_more", { count: file.layout }) : null,
  ].filter(Boolean);
  const kind =
    file.kind === "created"
      ? t("book.turn.kind_created")
      : file.kind === "deleted"
        ? file.unrecoverable
          ? t("book.turn.deleted_unrecoverable")
          : t("book.turn.kind_deleted")
        : file.kind === "renamed"
          ? t("book.turn.renamed_note")
          : null;
  const modifiable = file.kind === "changed" || file.kind === "renamed";
  return (
    <section className="space-y-2" data-testid="book-turn-file">
      <div className="flex flex-wrap items-center gap-2">
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium">{file.kind === "renamed" ? `${file.renamedFrom} → ${file.path}` : file.path}</p>
          {kind ? <p className="text-xs text-muted-foreground">{kind}</p> : null}
        </div>
        {file.chapterLocked ? <Lock className="size-4 text-primary" aria-label={t("book.turn.chapter_locked")} /> : null}
        {blocks.some((block) => turnBlockSelectable(file, block)) ? (
          <Button size="sm" variant="ghost" disabled={busy} onClick={onRejectAll}>
            {t("book.turn.reject_all")}
          </Button>
        ) : null}
        {modifiable && !file.done && !file.gone && !file.chapterLocked && file.before && file.missing !== "before" ? (
          <Button size="sm" variant="ghost" className="text-destructive" disabled={busy} onClick={onRestoreWhole}>
            {t("book.turn.restore_whole")}
          </Button>
        ) : null}
        {!file.gone ? (
          <Button size="sm" variant="ghost" onClick={onHistory}>
            {t("book.turn.open_history")}
          </Button>
        ) : null}
      </div>
      {notes.map((note, i) => (
        <p key={`n${i}`} className="text-xs text-destructive">
          {note}
        </p>
      ))}
      {info.map((note, i) => (
        <p key={`i${i}`} className="text-xs text-muted-foreground">
          {note}
        </p>
      ))}
      {blocks.map((block) => {
        const selected = rejected.includes(block.index);
        const selectable = turnBlockSelectable(file, block);
        return (
          <div key={block.index} className="space-y-2 rounded-lg border bg-muted/30 p-2 text-sm" data-testid="book-turn-block">
            {block.locked ? (
              <p className="flex items-center gap-1 text-xs text-primary">
                <Lock className="size-3" />
                {selected ? t("book.turn.locked_reject_hint") : t("book.turn.block_locked")}
              </p>
            ) : null}
            <DiffLinesView old={block.old} new={block.new} spans={block.spans} rejected={selected || block.state === "reverted"} />
            <div className="flex items-center justify-end gap-2">
              {selectable ? (
                <div className="inline-flex overflow-hidden rounded-md border text-xs" role="radiogroup">
                  <button
                    type="button"
                    role="radio"
                    aria-checked={!selected}
                    disabled={busy}
                    className={`h-7 px-3 ${selected ? "" : "bg-primary text-primary-foreground"}`}
                    onClick={() => selected && onToggle(block)}
                  >
                    {t("book.turn.accept")}
                  </button>
                  <button
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    disabled={busy}
                    className={`h-7 border-l px-3 ${selected ? "bg-destructive text-destructive-foreground" : ""}`}
                    onClick={() => !selected && onToggle(block)}
                    data-testid="book-turn-reject"
                  >
                    {t("book.turn.reject")}
                  </button>
                </div>
              ) : (
                <>
                  {block.state !== "pending" ? <span className="text-xs text-muted-foreground">{t(`book.turn.state_${block.state}`)}</span> : null}
                  {block.state === "changed_later" && !file.gone ? (
                    <Button size="sm" variant="ghost" className="h-7" onClick={onHistory}>
                      {t("book.turn.open_history")}
                    </Button>
                  ) : null}
                </>
              )}
            </div>
          </div>
        );
      })}
    </section>
  );
}
