import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, MoreVertical, Plus } from "lucide-react";
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
import { WorkspaceBookOutline } from "@overlay/components/workspace/workspace-book-outline";
import { WorkspaceBookReplace } from "@overlay/components/workspace/workspace-book-replace";
import { WorkspaceBookExport, WorkspaceBookImport } from "@overlay/components/workspace/workspace-book-transfer";
import {
  CHAPTER_STATUSES,
  bookApi,
  bookQueryKey,
  movedOrder,
  newChapterPath,
  type Book,
  type BookEdit,
  type BookItem,
  type ChapterStatus,
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
        </div>
      </div>

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
                  <span className="w-12 shrink-0 text-right text-xs text-muted-foreground">
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
      <ConfirmDeleteDialog
        open={deleting != null}
        onOpenChange={(open) => {
          if (!open) setDeleting(null);
        }}
        title={t("book.delete_chapter")}
        description={t("book.delete_confirm", { name: deleting?.path ?? "" })}
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
