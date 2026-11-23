import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import { SettingsSelectField } from "@overlay/components/settings/settings-ui";
import {
  bookApi,
  bookQueryKey,
  type Book,
  type BookBeat,
  type ChapterNotes,
  type OutlineProgress,
  type OutlineSection,
} from "@overlay/lib/book";

const STATE_COLOR = { written: "text-primary", changed: "text-amber-600", missing: "text-destructive" } as const;

/** 反向大纲：左边章节，右边计划和情节点并排。 */
export function WorkspaceBookOutline({
  workspaceId,
  book,
  onClose,
  onOpenQuote,
}: {
  workspaceId: string;
  book: Book;
  onClose: () => void;
  onOpenQuote: (path: string, quote: string) => void;
}) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const outlineKey = [...bookQueryKey(workspaceId), "outline"];
  const overview = useQuery({
    queryKey: outlineKey,
    queryFn: () => bookApi.outline(workspaceId),
    refetchInterval: (query) => (query.state.data?.progress?.current ? 1500 : false),
  });
  const [selected, setSelected] = React.useState<string | null>(null);
  const [importing, setImporting] = React.useState(false);
  const chapters = overview.data?.chapters ?? [];
  const current = chapters.find((chapter) => chapter.id === selected) ?? chapters[0];
  const progress = overview.data?.progress ?? null;
  const running = Boolean(progress?.current);
  const notesKey = [...bookQueryKey(workspaceId), "notes", current?.id ?? ""];
  const notes = useQuery({
    queryKey: notesKey,
    queryFn: () => bookApi.notes(workspaceId, current!.id),
    enabled: current != null,
  });
  // 每做完一章（或停下）刷新右边
  const marker = progress ? `${progress.done}:${progress.current ?? ""}` : "";
  React.useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: [...bookQueryKey(workspaceId), "notes"] });
    if (!marker) void queryClient.invalidateQueries({ queryKey: bookQueryKey(workspaceId), exact: true });
  }, [marker, queryClient, workspaceId]);

  const run = async (block: () => Promise<unknown>) => {
    try {
      await block();
    } catch (error) {
      toast.error(extractErrorMessage(error, t("book.outline_failed")));
    }
    await queryClient.invalidateQueries({ queryKey: outlineKey });
    await queryClient.invalidateQueries({ queryKey: notesKey });
  };
  const start = (type: "extract" | "compare", ids: string[]) =>
    void run(() => bookApi.outlineRun(workspaceId, ids.map((id) => ({ type, id }))));
  const pending = chapters.filter((chapter) => chapter.pending).map((chapter) => chapter.id);
  const comparePending = chapters.filter((chapter) => chapter.comparePending).map((chapter) => chapter.id);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex h-[88vh] flex-col sm:max-w-6xl" data-testid="book-outline">
        <DialogHeader>
          <DialogTitle>{t("book.outline_title")}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            disabled={running || pending.length === 0}
            onClick={() => {
              const words = book.items
                .filter((item) => pending.includes(item.id))
                .reduce((sum, item) => sum + (item.words ?? 0), 0);
              if (window.confirm(t("book.outline_confirm", { count: pending.length, words: words.toLocaleString() }))) {
                start("extract", pending);
              }
            }}
          >
            {t("book.outline_extract_count", { count: pending.length })}
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={running || comparePending.length === 0}
            onClick={() => start("compare", comparePending)}
          >
            {t("book.outline_compare_count", { count: comparePending.length })}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setImporting(true)}>
            {t("book.outline_import")}
          </Button>
          <Progress
            progress={progress}
            title={(id) => chapters.find((chapter) => chapter.id === id)?.title ?? ""}
            onStop={() => void run(() => bookApi.outlineStop(workspaceId))}
          />
        </div>
        <div className="flex min-h-0 flex-1 flex-col gap-3 md:flex-row">
          <div className="max-h-40 shrink-0 overflow-auto rounded-xl border md:max-h-none md:w-60">
            {chapters.map((chapter) => (
              <button
                key={chapter.id}
                type="button"
                className={cn(
                  "flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-muted",
                  chapter.id === current?.id && "bg-muted",
                )}
                onClick={() => setSelected(chapter.id)}
              >
                <span className="text-xs text-muted-foreground">{chapter.number}</span>
                <span className="min-w-0 flex-1 truncate">{chapter.title}</span>
                <span className={cn("text-xs", chapter.stale ? "text-destructive" : "text-muted-foreground")}>
                  {chapter.stale
                    ? t("book.outline_stale")
                    : chapter.extracted
                      ? `${chapter.beats}/${chapter.points}`
                      : chapter.pending
                        ? t("book.outline_not_extracted")
                        : ""}
                </span>
              </button>
            ))}
          </div>
          {current && notes.data ? (
            <ChapterDetail
              key={current.id}
              workspaceId={workspaceId}
              chapterId={current.id}
              notes={notes.data}
              running={running}
              canExtract={chapters.find((c) => c.id === current.id) != null}
              synopsis={book.items.find((item) => item.id === current.id)?.synopsis ?? ""}
              onRun={run}
              onStart={start}
              onOpenQuote={(quote) => onOpenQuote(current.path, quote)}
            />
          ) : null}
        </div>
        {importing ? (
          <ImportDialog
            workspaceId={workspaceId}
            book={book}
            onClose={() => setImporting(false)}
            onImported={() => void run(async () => undefined)}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

function Progress({
  progress,
  title,
  onStop,
}: {
  progress: OutlineProgress | null;
  title: (id: string) => string;
  onStop: () => void;
}) {
  const { t } = useTranslation("workspace");
  if (!progress) return null;
  if (!progress.current) {
    const reason = progress.aiError
      ? t(progress.aiError === "NO_MODEL" ? "book.ai_no_model" : "book.ai_empty")
      : (progress.error ?? "");
    return (
      <span className="text-sm text-destructive">
        {t("book.outline_stopped", { done: progress.done, total: progress.total, reason })}
      </span>
    );
  }
  return (
    <span className="flex items-center gap-2 text-sm text-muted-foreground">
      {t(progress.task === "compare" ? "book.outline_comparing" : "book.outline_extracting", {
        done: progress.done + 1,
        total: progress.total,
      })}{" "}
      {title(progress.current)}
      <Button size="sm" variant="ghost" onClick={onStop}>
        {t("book.outline_stop")}
      </Button>
    </span>
  );
}

function ChapterDetail({
  workspaceId,
  chapterId,
  notes,
  running,
  canExtract,
  synopsis,
  onRun,
  onStart,
  onOpenQuote,
}: {
  workspaceId: string;
  chapterId: string;
  notes: ChapterNotes;
  running: boolean;
  canExtract: boolean;
  synopsis: string;
  onRun: (block: () => Promise<unknown>) => Promise<void>;
  onStart: (type: "extract" | "compare", ids: string[]) => void;
  onOpenQuote: (quote: string) => void;
}) {
  const { t } = useTranslation("workspace");
  const [editing, setEditing] = React.useState<Partial<BookBeat> | null>(null);
  const fresh = notes.compared && !notes.compareStale;
  const matches = new Map(fresh ? notes.matches.map((match) => [match.beat, match]) : []);
  const saveBeats = (beats: Array<{ id?: string; text: string; words: number }>) =>
    onRun(() => bookApi.saveBeats(workspaceId, chapterId, beats));
  const plain = notes.beats.map((beat) => ({ id: beat.id, text: beat.text, words: beat.words }));
  const move = (index: number, delta: number) => {
    const next = [...plain];
    const target = index + delta;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    void saveBeats(next);
  };

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" disabled={running || !canExtract} onClick={() => onStart("extract", [chapterId])}>
          {t(notes.extracted ? "book.outline_extract_again" : "book.outline_extract")}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={running || !notes.extracted || notes.beats.length === 0}
          onClick={() => onStart("compare", [chapterId])}
        >
          {t("book.outline_compare")}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={notes.points.length === 0}
          onClick={() => void onRun(() => bookApi.planFromPoints(workspaceId, chapterId))}
        >
          {t("book.outline_as_plan")}
        </Button>
        {notes.stale ? <span className="text-sm text-destructive">{t("book.outline_stale_hint")}</span> : null}
      </div>
      {notes.synopsis ? (
        <p className="text-sm">
          {notes.synopsis}{" "}
          {notes.synopsis !== synopsis ? (
            <Button
              size="sm"
              variant="ghost"
              onClick={() =>
                void onRun(() =>
                  bookApi.edit(workspaceId, [{ op: "updateChapter", id: chapterId, synopsis: notes.synopsis }]),
                )
              }
            >
              {t("book.outline_adopt")}
            </Button>
          ) : null}
        </p>
      ) : null}
      <div className="grid min-h-0 flex-1 gap-3 overflow-auto md:grid-cols-2">
        <section>
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium text-primary">{t("book.outline_plan")}</h3>
            <Button size="icon" variant="ghost" aria-label={t("book.beat_add")} onClick={() => setEditing({})}>
              <Plus className="size-4" />
            </Button>
          </div>
          {notes.beats.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("book.outline_plan_empty")}</p>
          ) : null}
          {notes.beats.map((beat, index) => {
            const match = matches.get(beat.id);
            return (
              <div key={beat.id} className="flex items-start gap-2 border-b py-2 text-sm">
                <span className="text-xs text-muted-foreground">{index + 1}</span>
                <button type="button" className="min-w-0 flex-1 text-left" onClick={() => setEditing(beat)}>
                  {beat.text}
                  {match?.note ? <span className="block text-xs text-muted-foreground">{match.note}</span> : null}
                </button>
                {match ? (
                  <span className={cn("shrink-0 text-xs", STATE_COLOR[match.state])}>
                    {t(`book.beat_${match.state}`)}
                  </span>
                ) : null}
                <Button
                  size="icon"
                  variant="ghost"
                  className="size-6"
                  disabled={index === 0}
                  aria-label={t("book.move_up")}
                  onClick={() => move(index, -1)}
                >
                  <ArrowUp className="size-3.5" />
                </Button>
                <Button
                  size="icon"
                  variant="ghost"
                  className="size-6"
                  disabled={index === notes.beats.length - 1}
                  aria-label={t("book.move_down")}
                  onClick={() => move(index, 1)}
                >
                  <ArrowDown className="size-3.5" />
                </Button>
              </div>
            );
          })}
        </section>
        <section>
          <h3 className="text-sm font-medium text-primary">{t("book.outline_points")}</h3>
          {notes.points.map((point) => {
            const numbers = notes.beats
              .map((beat, index) => (matches.get(beat.id)?.points.includes(point.id) ? index + 1 : 0))
              .filter((number) => number > 0);
            return (
              <button
                key={point.id}
                type="button"
                disabled={!point.quote}
                className="flex w-full items-start gap-2 border-b py-2 text-left text-sm enabled:hover:bg-muted"
                onClick={() => onOpenQuote(point.quote)}
              >
                <span className="min-w-0 flex-1">
                  {point.text}
                  <span className="block truncate text-xs text-muted-foreground">
                    {point.quote || t("book.outline_unlocated")}
                  </span>
                </span>
                {fresh ? (
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {numbers.length > 0 ? `← ${numbers.join("、")}` : t("book.outline_extra")}
                  </span>
                ) : null}
              </button>
            );
          })}
        </section>
      </div>
      {editing ? (
        <BeatEditor
          beat={editing}
          onClose={() => setEditing(null)}
          onSave={(text, words) => {
            const next = editing.id
              ? plain.map((beat) => (beat.id === editing.id ? { ...beat, text, words } : beat))
              : [...plain, { text, words }];
            setEditing(null);
            void saveBeats(next);
          }}
          onRemove={() => {
            setEditing(null);
            void saveBeats(plain.filter((beat) => beat.id !== editing.id));
          }}
        />
      ) : null}
    </div>
  );
}

export function BeatEditor({
  beat,
  onClose,
  onSave,
  onRemove,
}: {
  beat: Partial<BookBeat>;
  onClose: () => void;
  onSave: (text: string, words: number) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [text, setText] = React.useState(beat.text ?? "");
  const [words, setWords] = React.useState(beat.words ? String(beat.words) : "");
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("book.beat_title")}</DialogTitle>
        </DialogHeader>
        <Textarea value={text} rows={4} placeholder={t("book.beat_text")} onChange={(e) => setText(e.target.value)} />
        <Input
          type="number"
          min={0}
          value={words}
          placeholder={t("book.beat_words")}
          onChange={(e) => setWords(e.target.value)}
        />
        {beat.drafts?.length ? (
          <p className="text-xs text-muted-foreground">{t("book.beat_remove_drafts", { count: beat.drafts.length })}</p>
        ) : null}
        <DialogFooter>
          {beat.id ? (
            <Button variant="ghost" className="mr-auto text-destructive" onClick={onRemove}>
              <Trash2 className="size-4" />
              {t("files.delete")}
            </Button>
          ) : null}
          <Button disabled={!text.trim()} onClick={() => onSave(text.trim(), Number.parseInt(words, 10) || 0)}>
            {t("book.beat_save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const SKIP = "skip";

function ImportDialog({
  workspaceId,
  book,
  onClose,
  onImported,
}: {
  workspaceId: string;
  book: Book;
  onClose: () => void;
  onImported: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [sections, setSections] = React.useState<OutlineSection[] | null>(null);
  const report = (error: unknown) => toast.error(extractErrorMessage(error, t("book.outline_failed")));
  const chapters = book.items.filter((item) => item.type === "chapter");
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[80vh] flex-col">
        <DialogHeader>
          <DialogTitle>{t("book.outline_import")}</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-2 overflow-auto">
          {book.unlisted.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("book.outline_no_file")}</p>
          ) : (
            <SettingsSelectField
              label={t("book.outline_file")}
              value=""
              onValueChange={(path) => {
                bookApi
                  .outlineFile(workspaceId, path)
                  .then((result) => setSections(result.sections))
                  .catch(report);
              }}
              options={book.unlisted.map((path) => ({ value: path, label: path }))}
            />
          )}
          {sections ? (
            <p className="text-xs text-muted-foreground">
              {t(sections.length === 0 ? "book.outline_no_sections" : "book.outline_import_hint")}
            </p>
          ) : null}
          {sections?.map((section, index) => (
            <SettingsSelectField
              key={index}
              label={`${section.title}（${section.beats.length}）`}
              value={section.chapterId ?? SKIP}
              onValueChange={(value) =>
                setSections(
                  sections.map((item, i) =>
                    i === index ? { ...item, chapterId: value === SKIP ? null : value } : item,
                  ),
                )
              }
              options={[
                { value: SKIP, label: t("book.outline_skip") },
                ...chapters.map((item) => ({ value: item.id, label: `${item.number}  ${item.title}` })),
              ]}
            />
          ))}
        </div>
        <DialogFooter>
          <Button
            disabled={!sections?.some((section) => section.chapterId)}
            onClick={() => {
              bookApi
                .outlineImport(workspaceId, sections ?? [])
                .then(() => {
                  onImported();
                  onClose();
                })
                .catch(report);
            }}
          >
            {t("book.beat_save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
