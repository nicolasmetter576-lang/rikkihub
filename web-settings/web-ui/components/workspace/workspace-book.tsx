import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Lock, MoreVertical, Plus, X } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import { ApiError } from "~/services/api";
import {
  ConfirmDeleteDialog,
  CreateNameDialog,
  SettingsField,
  SettingsSelectField,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { WorkspaceBookHistory, WorkspaceBookTrash } from "@overlay/components/workspace/workspace-book-history";
import { WorkspaceBookReview } from "@overlay/components/workspace/workspace-book-review";
import { WorkspaceBookOutline } from "@overlay/components/workspace/workspace-book-outline";
import { WorkspaceBookReplace } from "@overlay/components/workspace/workspace-book-replace";
import { WorkspaceBookExport, WorkspaceBookImport } from "@overlay/components/workspace/workspace-book-transfer";
import {
  CHAPTER_STATUSES,
  bookApi,
  bookLocksQueryKey,
  bookTurnLatestQueryKey,
  bookQueryKey,
  dismissedNotices,
  movedOrder,
  newChapterPath,
  type Book,
  type BookEdit,
  type BookItem,
  type ChapterStatus,
  type LockNotice,
} from "@overlay/lib/book";
import { revealOnOpen } from "@overlay/lib/editor-reveal";

/** 作品模式的「作品」页：目录、字数、状态；正文仍在「文件」里编辑。 */
export function WorkspaceBook({
  workspaceId,
  onOpenFile,
}: {
  workspaceId: string;
  onOpenFile: (path: string) => void;
}) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const key = bookQueryKey(workspaceId);
  const query = useQuery({ queryKey: key, queryFn: () => bookApi.get(workspaceId), retry: false });
  const [creating, setCreating] = React.useState(false);
  const [newVolume, setNewVolume] = React.useState(false);
  const [renamingVolume, setRenamingVolume] = React.useState<BookItem | null>(null);
  const [editing, setEditing] = React.useState<BookItem | null>(null);
  const [deleting, setDeleting] = React.useState<BookItem | null>(null);
  const [relinking, setRelinking] = React.useState<BookItem | null>(null);
  const [infoOpen, setInfoOpen] = React.useState(false);
  const [replacing, setReplacing] = React.useState(false);
  const [outlining, setOutlining] = React.useState(false);
  const [transfer, setTransfer] = React.useState<"import" | "export" | null>(null);
  const [historyPath, setHistoryPath] = React.useState<string | null>(null);
  const [historyNotice, setHistoryNotice] = React.useState<string | undefined>(undefined);
  const [reviewing, setReviewing] = React.useState<{ conversationId: string; messageId: string } | null>(null);
  const [recentOpen, setRecentOpen] = React.useState(false);
  const latestTurn = useQuery({ queryKey: bookTurnLatestQueryKey(workspaceId), queryFn: () => bookApi.latestTurn(workspaceId), retry: false });
  const recentTurns = useQuery({
    queryKey: ["workspaces", workspaceId, "book", "turns", "recent"],
    queryFn: () => bookApi.recentTurns(workspaceId),
    enabled: recentOpen,
    retry: false,
  });
  const refreshTurns = () => void queryClient.invalidateQueries({ queryKey: ["workspaces", workspaceId, "book", "turns"] });
  const [trashOpen, setTrashOpen] = React.useState(false);
  const [noticesOpen, setNoticesOpen] = React.useState(false);
  const [dismissed, setDismissed] = React.useState(() => dismissedNotices.read());
  const locksQuery = useQuery({ queryKey: bookLocksQueryKey(workspaceId), queryFn: () => bookApi.locks(workspaceId), retry: false });
  const notices = (locksQuery.data?.notices ?? []).filter((notice) => !dismissed.has(notice.id));
  const refreshLocks = () => {
    void queryClient.invalidateQueries({ queryKey: bookLocksQueryKey(workspaceId) });
    void queryClient.invalidateQueries({ queryKey: key });
  };
  // 网页端可以加锁；解锁、“保留这次修改”只在 App 里（防工作区里的命令经本机接口解锁）
  const lockChapter = useMutation({
    mutationFn: async (path: string) => {
      const current = await bookApi.fileLocks(workspaceId, path);
      return bookApi.lockChapter(workspaceId, path, current.revision ?? "");
    },
    onSuccess: refreshLocks,
    onError: (error) => reportSettingsError(error, t("book.lock.failed")),
  });
  const putBack = useMutation({
    mutationFn: (notice: LockNotice) => bookApi.putBackNotice(workspaceId, notice.id),
    onSuccess: (result) => {
      if (!result.done) reportSettingsError(null, t("book.lock.put_back_failed"));
      refreshLocks();
    },
    onError: (error) => reportSettingsError(error, t("book.lock.put_back_failed")),
  });

  const mutation = useMutation({
    mutationFn: (run: () => Promise<Book>) => run(),
    onSuccess: (book) => queryClient.setQueryData(key, book),
    onError: (error) => {
      reportSettingsError(error, t("book.save_failed"));
      void queryClient.invalidateQueries({ queryKey: key });
    },
  });
  const edit = (...operations: BookEdit[]) =>
    mutation.mutate(() => bookApi.edit(workspaceId, operations));

  if (query.isError) {
    const code = query.error instanceof ApiError ? query.error.errorCode : undefined;
    return (
      <div className="space-y-3 rounded-xl border p-4 text-sm" data-testid="book-error">
        <p className="text-muted-foreground">
          {code === "BOOK_CORRUPT"
            ? t("book.corrupt")
            : code === "BOOK_NEWER_VERSION"
              ? t("book.newer")
              : t("book.load_failed")}
        </p>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => void query.refetch()}>
            {t("book.retry")}
          </Button>
          {code === "BOOK_CORRUPT" ? (
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                mutation.mutate(() => bookApi.rebuild(workspaceId), {
                  onSuccess: () => void query.refetch(),
                })
              }
            >
              {t("book.rebuild")}
            </Button>
          ) : null}
        </div>
      </div>
    );
  }
  const book = query.data;
  if (!book) return <p className="text-sm text-muted-foreground">{t("list.loading")}</p>;

  const chapters = book.items.filter((item) => item.type === "chapter");
  const volumes = book.items.filter((item) => item.type === "volume");
  const number = (value: number | null | undefined) => (value ?? 0).toLocaleString();

  return (
    <div className="space-y-3" data-testid="workspace-book">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-base font-semibold">{book.title || t("book.untitled")}</h2>
          <p className="text-xs text-muted-foreground">
            {t("book.summary", {
              words: number(book.words),
              chapters: chapters.length,
              final: chapters.filter((item) => item.status === "final").length,
            })}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus className="size-4" />
            {t("book.new_chapter")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setOutlining(true)}>
            {t("book.outline_title")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setReplacing(true)}>
            {t("book.replace_title")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setTransfer("import")}>
            {t("book.import_title")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setTransfer("export")}>
            {t("book.export_title")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setNewVolume(true)}>
            {t("book.new_volume")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setInfoOpen(true)}>
            {t("book.info")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setRecentOpen(true)}>
            {t("book.turn.recent")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setTrashOpen(true)}>
            {t("book.trash_title")}
          </Button>
        </div>
      </div>

      {locksQuery.data?.broken ? (
        <p className="rounded-lg border border-destructive/40 px-3 py-2 text-xs text-destructive">{t("book.lock.broken")}</p>
      ) : notices.length > 0 ? (
        <button
          type="button"
          className="flex w-full items-center gap-2 rounded-lg border border-destructive/40 px-3 py-2 text-left text-xs text-destructive"
          onClick={() => setNoticesOpen(true)}
          data-testid="book-lock-banner"
        >
          <Lock className="size-3.5" />
          <span className="flex-1">{t("book.lock.banner", { count: notices.length })}</span>
          <span className="text-primary">{t("book.lock.view")}</span>
        </button>
      ) : null}
      {latestTurn.data?.turn ? (
        <div className="flex w-full items-center gap-2 rounded-lg border px-3 py-1.5 text-xs text-primary" data-testid="book-turn-banner">
          <button
            type="button"
            className="min-w-0 flex-1 truncate text-left"
            onClick={() => latestTurn.data?.turn && setReviewing(latestTurn.data.turn)}
          >
            {latestTurn.data.title
              ? t("book.turn.banner", { title: latestTurn.data.title, count: latestTurn.data.turn.files })
              : t("book.turn.banner_untitled", { count: latestTurn.data.turn.files })}
          </button>
          <Button size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={() => latestTurn.data?.turn && setReviewing(latestTurn.data.turn)}>
            {t("book.turn.review")}
          </Button>
          <Button
            size="icon"
            variant="ghost"
            className="size-6"
            aria-label={t("book.turn.banner_dismiss")}
            onClick={() => {
              const turn = latestTurn.data?.turn;
              if (turn) void bookApi.turnSeen(workspaceId, turn.conversationId, turn.messageId).then(refreshTurns);
            }}
          >
            <X className="size-3.5" />
          </Button>
        </div>
      ) : null}

      {book.items.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {t("book.empty")}
        </p>
      ) : (
        <div className="divide-y overflow-hidden rounded-xl border">
          {book.items.map((item) => (
            <div key={item.id} className="flex min-h-11 items-center gap-2 px-3 py-1">
              {item.type === "volume" ? (
                <p className="min-w-0 flex-1 truncate text-sm font-medium text-primary">{item.title}</p>
              ) : (
                <button
                  type="button"
                  className="flex min-w-0 flex-1 items-baseline gap-3 py-1 text-left"
                  onClick={() => {
                    if (item.missing) setRelinking(item);
                    else if (item.path) onOpenFile(item.path);
                  }}
                >
                  <span className="w-8 shrink-0 text-xs text-muted-foreground">{item.number}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm">{item.title}</span>
                    {item.synopsis ? (
                      <span className="line-clamp-2 block text-xs text-muted-foreground">
                        {item.synopsis}
                      </span>
                    ) : null}
                  </span>
                  <span
                    className={
                      item.missing
                        ? "shrink-0 text-xs text-destructive"
                        : "shrink-0 text-xs text-muted-foreground"
                    }
                  >
                    {item.missing
                      ? t("book.missing")
                      : item.targetWords
                        ? `${number(item.words)}/${number(item.targetWords)}`
                        : number(item.words)}
                  </span>
                  <span className="flex w-16 shrink-0 items-center justify-end gap-1 text-xs text-muted-foreground">
                    {item.lock ? (
                      <Lock
                        className={item.lock.chapter ? "size-3 text-primary" : "size-3 text-muted-foreground"}
                        aria-label={item.lock.chapter ? t("book.lock.locked") : t("book.lock.has_passages", { count: item.lock.passages })}
                        data-testid="book-lock-mark"
                      />
                    ) : null}
                    {t(`book.status_${item.status ?? "draft"}`)}
                  </span>
                </button>
              )}
              {([-1, 1] as const).map((delta) => {
                const order = movedOrder(book.items, item.id, delta);
                return (
                  <Button
                    key={delta}
                    size="icon"
                    variant="ghost"
                    className="size-7"
                    disabled={order == null || mutation.isPending}
                    aria-label={t(delta < 0 ? "book.move_up" : "book.move_down")}
                    onClick={() => order && edit({ op: "reorder", order })}
                  >
                    {delta < 0 ? <ArrowUp className="size-4" /> : <ArrowDown className="size-4" />}
                  </Button>
                );
              })}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button size="icon" variant="ghost" className="size-7" aria-label={t("book.more")}>
                    <MoreVertical className="size-4" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {item.type === "volume" ? (
                    <>
                      <DropdownMenuItem onSelect={() => setRenamingVolume(item)}>
                        {t("book.rename_volume")}
                      </DropdownMenuItem>
                      <DropdownMenuItem onSelect={() => edit({ op: "remove", ids: [item.id] })}>
                        {t("book.remove_volume")}
                      </DropdownMenuItem>
                    </>
                  ) : (
                    <>
                      <DropdownMenuItem onSelect={() => setEditing(item)}>
                        {t("book.edit_chapter")}
                      </DropdownMenuItem>
                      {item.missing ? (
                        <DropdownMenuItem onSelect={() => setRelinking(item)}>
                          {t("book.relink")}
                        </DropdownMenuItem>
                      ) : item.path ? (
                        <>
                          <DropdownMenuItem onSelect={() => setHistoryPath(item.path ?? null)}>
                            {t("book.history_title")}
                          </DropdownMenuItem>
                          {item.lock?.chapter ? (
                            <DropdownMenuItem disabled>{t("book.lock.unlock_in_app")}</DropdownMenuItem>
                          ) : (
                            <DropdownMenuItem onSelect={() => item.path && lockChapter.mutate(item.path)}>
                              {t("book.lock.lock_chapter")}
                            </DropdownMenuItem>
                          )}
                        </>
                      ) : null}
                      {volumes.map((volume) => (
                        <DropdownMenuItem
                          key={volume.id}
                          onSelect={() =>
                            edit({ op: "moveToVolume", ids: [item.id], volumeId: volume.id })
                          }
                        >
                          {t("book.move_to", { title: volume.title })}
                        </DropdownMenuItem>
                      ))}
                      <DropdownMenuItem onSelect={() => edit({ op: "remove", ids: [item.id] })}>
                        {t("book.remove_chapter")}
                      </DropdownMenuItem>
                      {item.missing ? null : (
                        <DropdownMenuItem variant="destructive" onSelect={() => setDeleting(item)}>
                          {t("book.delete_chapter")}
                        </DropdownMenuItem>
                      )}
                    </>
                  )}
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          ))}
        </div>
      )}

      {book.unlisted.length > 0 ? (
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium">
              {t("book.unlisted", { count: book.unlisted.length })}
            </h3>
            <Button size="sm" variant="ghost" onClick={() => edit({ op: "add", paths: book.unlisted })}>
              {t("book.add_all")}
            </Button>
          </div>
          <div className="divide-y overflow-hidden rounded-xl border">
            {book.unlisted.map((path) => (
              <div key={path} className="flex items-center gap-2 px-3 py-1">
                <button
                  type="button"
                  className="min-w-0 flex-1 truncate py-1 text-left text-sm"
                  onClick={() => onOpenFile(path)}
                >
                  {path}
                </button>
                <Button size="sm" variant="ghost" onClick={() => edit({ op: "add", paths: [path] })}>
                  {t("book.add")}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => edit({ op: "ignore", paths: [path] })}>
                  {t("book.ignore")}
                </Button>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {historyPath ? (
        <WorkspaceBookHistory
          workspaceId={workspaceId}
          path={historyPath}
          noticeId={historyNotice}
          onClose={() => {
            setHistoryPath(null);
            setHistoryNotice(undefined);
          }}
          onChanged={refreshLocks}
        />
      ) : null}
      {reviewing ? (
        <WorkspaceBookReview
          workspaceId={workspaceId}
          conversationId={reviewing.conversationId}
          messageId={reviewing.messageId}
          onClose={() => {
            setReviewing(null);
            refreshTurns();
          }}
          onChanged={() => {
            refreshTurns();
            void queryClient.invalidateQueries({ queryKey: key });
          }}
        />
      ) : null}
      <Dialog open={recentOpen} onOpenChange={setRecentOpen}>
        <DialogContent className="flex max-h-[85vh] flex-col sm:max-w-xl" data-testid="book-turn-recent">
          <DialogHeader>
            <DialogTitle>{t("book.turn.recent")}</DialogTitle>
          </DialogHeader>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {!recentTurns.data ? (
              <p className="text-sm text-muted-foreground">{t("list.loading")}</p>
            ) : recentTurns.data.turns.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("book.turn.recent_empty")}</p>
            ) : (
              <div className="divide-y overflow-hidden rounded-xl border">
                {recentTurns.data.turns.map((turn) => (
                  <button
                    key={`${turn.conversationId}/${turn.messageId}`}
                    type="button"
                    className="block w-full px-3 py-2 text-left hover:bg-muted/50"
                    onClick={() => {
                      setRecentOpen(false);
                      setReviewing(turn);
                    }}
                  >
                    <p className="truncate text-sm">{recentTurns.data.titles[turn.conversationId] ?? t("book.turn.untitled")}</p>
                    <p className="truncate text-xs text-muted-foreground">
                      {[
                        new Date(turn.finishedAt).toLocaleString(undefined, { dateStyle: "short", timeStyle: "short" }),
                        t("book.turn.card", { count: turn.files }),
                        turn.status === "PENDING"
                          ? t("book.turn.status_pending")
                          : turn.status === "REVIEWED"
                            ? t("book.turn.reviewed_summary", { count: turn.reverted })
                            : t("book.turn.status_archived"),
                        turn.cleaned ? t("book.turn.cleaned") : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </p>
                  </button>
                ))}
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>
      {trashOpen ? (
        <WorkspaceBookTrash
          workspaceId={workspaceId}
          onClose={() => setTrashOpen(false)}
          onChanged={() => void queryClient.invalidateQueries({ queryKey: key })}
        />
      ) : null}
      {transfer === "import" ? (
        <WorkspaceBookImport
          workspaceId={workspaceId}
          onClose={() => setTransfer(null)}
          onImported={() => void queryClient.invalidateQueries({ queryKey: key })}
        />
      ) : null}
      {transfer === "export" ? (
        <WorkspaceBookExport workspaceId={workspaceId} book={book} onClose={() => setTransfer(null)} />
      ) : null}
      {outlining ? (
        <WorkspaceBookOutline
          workspaceId={workspaceId}
          book={book}
          onClose={() => setOutlining(false)}
          onOpenQuote={(path, quote) => {
            revealOnOpen(`${workspaceId}:FILES:${path}`, quote);
            setOutlining(false);
            onOpenFile(path);
          }}
        />
      ) : null}
      {replacing ? (
        <WorkspaceBookReplace
          workspaceId={workspaceId}
          book={book}
          onClose={() => setReplacing(false)}
          onChanged={() => void queryClient.invalidateQueries({ queryKey: key })}
        />
      ) : null}
      <CreateNameDialog
        open={creating}
        onOpenChange={setCreating}
        title={t("book.new_chapter")}
        placeholder={t("book.file_name")}
        loading={mutation.isPending}
        onSubmit={(name) => {
          if (!name.trim()) return;
          const path = newChapterPath(book.items, name);
          mutation.mutate(() => bookApi.createChapter(workspaceId, path, chapters.at(-1)?.id), {
            onSuccess: () => setCreating(false),
          });
        }}
      />
      <CreateNameDialog
        open={newVolume}
        onOpenChange={setNewVolume}
        title={t("book.new_volume")}
        placeholder={t("book.volume_title")}
        onSubmit={(title) => {
          if (!title.trim()) return;
          edit({ op: "addVolume", title });
          setNewVolume(false);
        }}
      />
      <CreateNameDialog
        open={renamingVolume != null}
        onOpenChange={(open) => {
          if (!open) setRenamingVolume(null);
        }}
        title={t("book.rename_volume")}
        placeholder={t("book.volume_title")}
        initialName={renamingVolume?.title}
        submitLabel={t("files.confirm")}
        onSubmit={(title) => {
          if (renamingVolume && title.trim()) edit({ op: "renameVolume", id: renamingVolume.id, title });
          setRenamingVolume(null);
        }}
      />
      <Dialog open={noticesOpen} onOpenChange={setNoticesOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("book.lock.notices_title")}</DialogTitle>
          </DialogHeader>
          <div className="max-h-[60vh] space-y-3 overflow-y-auto">
            {notices.length === 0 ? <p className="text-sm text-muted-foreground">{t("book.lock.notices_empty")}</p> : null}
            {[...notices].sort((a, b) => b.at - a.at).map((notice) => (
              <div key={notice.id} className="space-y-1 rounded-lg border p-3 text-sm" data-testid="book-lock-notice">
                <p>{t(`book.lock.notice_${notice.kind}`, { name: notice.path.split("/").pop() ?? notice.path })}</p>
                {notice.earlier ? (
                  <p className="text-xs text-muted-foreground">{t("book.lock.notice_earlier", { count: notice.earlier })}</p>
                ) : null}
                {notice.kind === "restored" ? (
                  <p className="text-xs text-muted-foreground">{t("book.lock.keep_in_app")}</p>
                ) : null}
                <div className="flex flex-wrap gap-2 pt-1">
                  {notice.kind !== "deleted" ? (
                    <Button size="sm" variant="outline" onClick={() => {
                      setNoticesOpen(false);
                      setHistoryPath(notice.path);
                      if (notice.kind === "changed" || notice.kind === "not_restored") setHistoryNotice(notice.id);
                    }}>
                      {t("book.lock.view")}
                    </Button>
                  ) : null}
                  {["not_restored", "no_space", "changed", "deleted"].includes(notice.kind) ? (
                    <Button size="sm" variant="outline" disabled={putBack.isPending} onClick={() => putBack.mutate(notice)}>
                      {t("book.lock.put_back")}
                    </Button>
                  ) : null}
                  <Button size="sm" variant="ghost" onClick={() => {
                    dismissedNotices.add(notice.id);
                    setDismissed(dismissedNotices.read());
                  }}>
                    {t("book.lock.dismiss_here")}
                  </Button>
                </div>
              </div>
            ))}
          </div>
        </DialogContent>
      </Dialog>
      <ConfirmDeleteDialog
        open={deleting != null}
        onOpenChange={(open) => {
          if (!open) setDeleting(null);
        }}
        title={t("book.delete_chapter")}
        description={
          t("book.delete_confirm", { name: deleting?.path ?? "" }) +
          (deleting?.lock ? "\n" + t("book.lock.delete_note") : "")
        }
        onConfirm={async () => {
          const target = deleting;
          setDeleting(null);
          if (target) mutation.mutate(() => bookApi.deleteChapters(workspaceId, [target.id]));
        }}
      />
      {editing ? (
        <ChapterDialog
          key={editing.id}
          item={editing}
          onClose={() => setEditing(null)}
          onSave={(patch) => {
            edit({ op: "updateChapter", id: editing.id, ...patch });
            setEditing(null);
          }}
        />
      ) : null}
      {infoOpen ? (
        <InfoDialog
          book={book}
          onClose={() => setInfoOpen(false)}
          onSave={(title, author) => {
            edit({ op: "info", title, author });
            setInfoOpen(false);
          }}
        />
      ) : null}
      <Dialog open={relinking != null} onOpenChange={(open) => !open && setRelinking(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("book.relink")}</DialogTitle>
          </DialogHeader>
          {book.unlisted.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("book.relink_none")}</p>
          ) : (
            <div className="max-h-80 divide-y overflow-auto rounded-xl border">
              {book.unlisted.map((path) => (
                <button
                  key={path}
                  type="button"
                  className="block w-full truncate px-3 py-2 text-left text-sm"
                  onClick={() => {
                    const target = relinking;
                    setRelinking(null);
                    if (target) mutation.mutate(() => bookApi.relink(workspaceId, target.id, path));
                  }}
                >
                  {path}
                </button>
              ))}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ChapterDialog({
  item,
  onClose,
  onSave,
}: {
  item: BookItem;
  onClose: () => void;
  onSave: (patch: {
    title: string;
    synopsis: string;
    status: ChapterStatus;
    targetWords: number;
  }) => void;
}) {
  const { t } = useTranslation("workspace");
  const [title, setTitle] = React.useState(item.customTitle ?? "");
  const [synopsis, setSynopsis] = React.useState(item.synopsis ?? "");
  const [status, setStatus] = React.useState<ChapterStatus>(item.status ?? "draft");
  const [target, setTarget] = React.useState(item.targetWords ? String(item.targetWords) : "");
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("book.edit_chapter")}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <SettingsField label={t("book.chapter_title")}>
            <Input value={title} placeholder={item.title} onChange={(event) => setTitle(event.target.value)} />
          </SettingsField>
          <SettingsField label={t("book.synopsis")}>
            <Textarea rows={3} value={synopsis} onChange={(event) => setSynopsis(event.target.value)} />
          </SettingsField>
          <SettingsField label={t("book.target_words")}>
            <Input
              inputMode="numeric"
              value={target}
              onChange={(event) => setTarget(event.target.value.replace(/\D/g, "").slice(0, 6))}
            />
          </SettingsField>
          <SettingsSelectField
            label={t("book.status")}
            value={status}
            onValueChange={(value) => setStatus(value as ChapterStatus)}
            options={CHAPTER_STATUSES.map((value) => ({ value, label: t(`book.status_${value}`) }))}
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("files.cancel")}
          </Button>
          <Button onClick={() => onSave({ title, synopsis, status, targetWords: Number(target) || 0 })}>
            {t("files.confirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function InfoDialog({
  book,
  onClose,
  onSave,
}: {
  book: Book;
  onClose: () => void;
  onSave: (title: string, author: string) => void;
}) {
  const { t } = useTranslation("workspace");
  const [title, setTitle] = React.useState(book.title);
  const [author, setAuthor] = React.useState(book.author);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("book.info")}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <SettingsField label={t("book.title")}>
            <Input value={title} onChange={(event) => setTitle(event.target.value)} />
          </SettingsField>
          <SettingsField label={t("book.author")}>
            <Input value={author} onChange={(event) => setAuthor(event.target.value)} />
          </SettingsField>
          <p className="text-xs text-muted-foreground">{t("book.backup_note")}</p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("files.cancel")}
          </Button>
          <Button onClick={() => onSave(title, author)}>{t("files.confirm")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
