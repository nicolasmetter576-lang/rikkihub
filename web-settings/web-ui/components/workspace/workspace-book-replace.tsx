import * as React from "react";

import { ArrowRight, Lock, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { extractErrorMessage } from "~/lib/error";
import {
  bookApi,
  type Book,
  type BookReplacePair,
  type BookReplacePreview,
  type BookReplaceResult,
  type BookUndoEntry,
} from "@overlay/lib/book";

const hitKey = (path: string, start: number) => `${path}\u0000${start}`;

/** 全书查找替换：先列出全部命中再勾选，逐章带版本校验写入，可撤销。 */
export function WorkspaceBookReplace({
  workspaceId,
  book,
  onClose,
  onChanged,
}: {
  workspaceId: string;
  book: Book;
  onClose: () => void;
  onChanged: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [pairs, setPairs] = React.useState<BookReplacePair[]>([{ find: "", replace: "" }]);
  const [caseSensitive, setCaseSensitive] = React.useState(false);
  const [includeUnlisted, setIncludeUnlisted] = React.useState(false);
  const [preview, setPreview] = React.useState<BookReplacePreview | null>(null);
  const [unselected, setUnselected] = React.useState<ReadonlySet<string>>(new Set());
  const [result, setResult] = React.useState<BookReplaceResult | null>(null);
  const [history, setHistory] = React.useState<BookUndoEntry[]>([]);
  const [busy, setBusy] = React.useState(false);

  const query = {
    pairs: pairs.filter((pair) => pair.find.trim()),
    ignoreCase: !caseSensitive,
    includeUnlisted,
  };
  const titles = new Map(book.items.filter((item) => item.path).map((item) => [item.path, item]));
  const total = preview?.files.reduce((sum, file) => sum + file.hits.length, 0) ?? 0;
  // 整章定稿锁定的章节不替换，命中不可勾选；锁定段落里的命中默认勾上，可以一键取消
  const selectable = preview?.files.filter((file) => !file.locked) ?? [];
  const selected = selectable.reduce(
    (sum, file) => sum + file.hits.filter((hit) => !unselected.has(hitKey(file.path, hit.start))).length,
    0,
  );
  const lockedHits = selectable.flatMap((file) => file.hits.filter((hit) => hit.locked).map((hit) => hitKey(file.path, hit.start)));

  const reset = () => {
    setPreview(null);
    setResult(null);
    setUnselected(new Set());
  };
  const run = async (block: () => Promise<void>) => {
    setBusy(true);
    try {
      await block();
    } catch (error) {
      toast.error(extractErrorMessage(error, t("book.replace_failed")));
    } finally {
      setBusy(false);
    }
  };
  const loadHistory = React.useCallback(async () => {
    setHistory((await bookApi.replaceHistory(workspaceId)).entries);
  }, [workspaceId]);
  React.useEffect(() => {
    loadHistory().catch(() => undefined);
  }, [loadHistory]);

  const toggle = (keys: string[]) =>
    setUnselected((current) => {
      const next = new Set(current);
      const turnOn = keys.some((key) => next.has(key));
      for (const key of keys) {
        if (turnOn) next.delete(key);
        else next.add(key);
      }
      return next;
    });

  const apply = () =>
    run(async () => {
      if (!preview) return;
      const deleting = query.pairs.filter((pair) => !pair.replace).map((pair) => pair.find);
      if (deleting.length > 0 && !window.confirm(t("book.replace_delete_confirm", { words: deleting.join("、") }))) return;
      const files = selectable
        .map((file) => ({
          path: file.path,
          revision: file.revision,
          starts: file.hits.map((hit) => hit.start).filter((start) => !unselected.has(hitKey(file.path, start))),
        }))
        .filter((file) => file.starts.length > 0);
      setResult(await bookApi.replaceApply(workspaceId, { ...query, files }));
      setPreview(null);
      await loadHistory();
      onChanged();
    });

  const undo = (id: string) =>
    run(async () => {
      const undone = await bookApi.replaceUndo(workspaceId, id);
      setResult(null);
      setPreview(null);
      toast.success(t("book.replace_undone", { count: undone.restored.length }));
      if (undone.changedSince.length > 0)
        toast.warning(t("book.replace_undo_changed", { files: undone.changedSince.join("、") }));
      if (undone.locked?.length) toast.warning(t("book.lock.undo_locked", { files: undone.locked.join("、") }));
      await loadHistory();
      onChanged();
    });

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[85vh] flex-col sm:max-w-2xl" data-testid="book-replace">
        <DialogHeader>
          <DialogTitle>{t("book.replace_title")}</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-3 overflow-auto">
          {pairs.map((pair, index) => (
            <div key={index} className="flex items-center gap-2">
              <Input
                value={pair.find}
                placeholder={t("book.replace_find")}
                onChange={(event) => {
                  reset();
                  setPairs(pairs.map((item, i) => (i === index ? { ...item, find: event.target.value } : item)));
                }}
              />
              <ArrowRight className="size-4 shrink-0" />
              <Input
                value={pair.replace}
                placeholder={t("book.replace_with")}
                onChange={(event) => {
                  reset();
                  setPairs(pairs.map((item, i) => (i === index ? { ...item, replace: event.target.value } : item)));
                }}
              />
              <Button
                size="icon"
                variant="ghost"
                aria-label={t("files.delete")}
                disabled={pairs.length === 1}
                onClick={() => {
                  reset();
                  setPairs(pairs.filter((_, i) => i !== index));
                }}
              >
                <X className="size-4" />
              </Button>
            </div>
          ))}
          <div className="flex flex-wrap items-center gap-4 text-sm">
            <Button
              size="sm"
              variant="outline"
              disabled={pairs.length >= 20}
              onClick={() => setPairs([...pairs, { find: "", replace: "" }])}
            >
              {t("book.replace_add_pair")}
            </Button>
            <label className="flex items-center gap-2">
              <Checkbox
                checked={caseSensitive}
                onCheckedChange={(value) => {
                  reset();
                  setCaseSensitive(value === true);
                }}
              />
              {t("book.replace_case")}
            </label>
            {book.unlisted.length > 0 ? (
              <label className="flex items-center gap-2">
                <Checkbox
                  checked={includeUnlisted}
                  onCheckedChange={(value) => {
                    reset();
                    setIncludeUnlisted(value === true);
                  }}
                />
                {t("book.replace_unlisted")}
              </label>
            ) : null}
          </div>
          {result ? (
            <div className="space-y-1 rounded-xl border p-3 text-sm">
              <p>{t("book.replace_done", { count: result.replaced, files: result.changed.length })}</p>
              {result.conflicts.length > 0 ? (
                <p className="text-muted-foreground">
                  {t("book.replace_conflicts", { files: result.conflicts.join("、") })}
                </p>
              ) : null}
              {result.failed.length > 0 ? (
                <p className="text-destructive">
                  {t("book.replace_failed_files", { files: result.failed.join("、") })}
                </p>
              ) : null}
              {result.locked?.length ? (
                <p className="text-muted-foreground">{t("book.lock.replace_skipped", { files: result.locked.join("、") })}</p>
              ) : null}
            </div>
          ) : null}
          {preview ? (
            preview.files.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("book.replace_none")}</p>
            ) : (
              <div className="space-y-2">
                <div className="flex items-center justify-between text-sm">
                  <span>{t("book.replace_found", { count: total, files: preview.files.length })}</span>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      toggle(selectable.flatMap((file) => file.hits.map((hit) => hitKey(file.path, hit.start))))
                    }
                  >
                    {t(unselected.size > 0 ? "book.replace_select_all" : "book.replace_select_none")}
                  </Button>
                </div>
                {preview.files.filter((file) => file.locked).map((file) => (
                  <p key={`locked:${file.path}`} className="flex items-center gap-1 text-xs text-muted-foreground">
                    <Lock className="size-3 text-primary" />
                    {t("book.lock.replace_locked_chapter", { name: titles.get(file.path)?.title ?? file.path })}
                  </p>
                ))}
                {lockedHits.length > 0 ? (
                  <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
                    <span className="flex items-center gap-1">
                      <Lock className="size-3" />
                      {t("book.lock.replace_locked_hits", { count: lockedHits.length })}
                    </span>
                    <Button size="sm" variant="ghost" onClick={() => toggle(lockedHits)}>
                      {t(lockedHits.some((key) => unselected.has(key)) ? "book.replace_select_all" : "book.replace_select_none")}
                    </Button>
                  </div>
                ) : null}
                {preview.files.map((file) => {
                  const keys = file.hits.map((hit) => hitKey(file.path, hit.start));
                  const item = titles.get(file.path);
                  return (
                    <div key={file.path} className="rounded-xl border">
                      <label className="flex items-center gap-2 border-b px-3 py-2 text-sm font-medium">
                        <Checkbox
                          checked={!file.locked && keys.every((key) => !unselected.has(key))}
                          disabled={file.locked}
                          onCheckedChange={() => toggle(keys)}
                        />
                        <span className="min-w-0 flex-1 truncate">
                          {item ? `${item.number}  ${item.title}` : file.path}
                        </span>
                        {file.locked ? <Lock className="size-3 text-primary" aria-label={t("book.lock.locked")} /> : null}
                        <span className="text-xs text-muted-foreground">{file.hits.length}</span>
                      </label>
                      {file.hits.map((hit) => (
                        <label key={hit.start} className="flex items-start gap-2 px-3 py-1 text-sm">
                          <Checkbox
                            className="mt-1"
                            checked={!file.locked && !unselected.has(hitKey(file.path, hit.start))}
                            disabled={file.locked}
                            onCheckedChange={() => toggle([hitKey(file.path, hit.start)])}
                          />
                          {hit.locked ? <Lock className="mt-1.5 size-3 shrink-0 text-muted-foreground" aria-label={t("book.lock.locked")} /> : null}
                          <span className="min-w-0 break-words">
                            {hit.before}
                            <del className="text-destructive">{hit.match}</del>
                            <ins className="font-medium text-primary no-underline">
                              {query.pairs[hit.pair]?.replace}
                            </ins>
                            {hit.after}
                          </span>
                        </label>
                      ))}
                    </div>
                  );
                })}
              </div>
            )
          ) : null}
          {history.length > 0 ? (
            <div className="space-y-1 text-sm">
              <h3 className="font-medium">{t("book.replace_history")}</h3>
              {history.map((entry) => (
                <div key={entry.id} className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate text-muted-foreground">
                    {new Date(entry.time).toLocaleString()} · {entry.summary}
                  </span>
                  <Button size="sm" variant="ghost" disabled={busy} onClick={() => void undo(entry.id)}>
                    {t("book.replace_undo")}
                  </Button>
                </div>
              ))}
            </div>
          ) : null}
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            disabled={busy || query.pairs.length === 0}
            onClick={() =>
              void run(async () => {
                setResult(null);
                setUnselected(new Set());
                setPreview(await bookApi.replacePreview(workspaceId, query));
              })
            }
          >
            {t("book.replace_search")}
          </Button>
          <Button disabled={busy || selected === 0} onClick={() => void apply()}>
            {t("book.replace_apply", { count: selected })}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
