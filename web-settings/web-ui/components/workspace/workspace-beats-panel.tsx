import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Lock, LockOpen, Plus, Settings2, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "~/components/ui/sheet";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { getModelDisplayName } from "~/lib/display";
import { cn } from "~/lib/utils";
import { useSettingsStore } from "~/stores/settings";
import { SettingsSelectField } from "@overlay/components/settings/settings-ui";
import { BeatEditor } from "@overlay/components/workspace/workspace-book-outline";
import {
  bookApi,
  bookQueryKey,
  type BeatInsertion,
  type BeatPresence,
  type BeatProposal,
  type BookBeat,
} from "@overlay/lib/book";
import { listChatModels } from "@overlay/lib/settings";

const DEFAULT_MODEL = "default";

/** 节拍面板：一章拆成节拍逐个生成、逐个重写，满意了写进正文。改正文由 [onInsert] / [onReplace] 在编辑器里做，可撤销。 */
export function WorkspaceBeatsPanel({
  workspaceId,
  chapterId,
  text,
  getText,
  getCursor,
  readOnly,
  onInsert,
  onReplace,
  onClose,
}: {
  workspaceId: string;
  chapterId: string;
  /** 现在的正文，用来看写进去的那段还在不在。 */
  text: string;
  getText: () => string;
  getCursor: () => number;
  readOnly: boolean;
  onInsert: (insertions: BeatInsertion[]) => void;
  onReplace: (from: number, to: number, text: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const notesKey = [...bookQueryKey(workspaceId), "notes", chapterId];
  const notes = useQuery({ queryKey: notesKey, queryFn: () => bookApi.notes(workspaceId, chapterId) });
  const writing = useQuery({
    queryKey: [...notesKey, "writing"],
    queryFn: () => bookApi.writing(workspaceId, chapterId),
    refetchInterval: (query) => (query.state.data?.running ? 700 : false),
  });
  const job = writing.data;
  const beats = notes.data?.beats ?? [];
  const [presence, setPresence] = React.useState<Record<string, BeatPresence>>({});
  const [editing, setEditing] = React.useState<Partial<BookBeat> | null>(null);
  const [requesting, setRequesting] = React.useState<string | null>(null);
  const [inserting, setInserting] = React.useState(false);

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: notesKey });
  };
  const run = async (block: () => Promise<unknown>) => {
    try {
      await block();
    } catch (error) {
      toast.error(extractErrorMessage(error, t("book.beats_failed")));
    }
    await refresh();
  };
  // 写完一个（或停下）就取一次新稿
  const marker = job ? `${job.current ?? ""}:${job.splitting}` : "";
  React.useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: notesKey, exact: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [marker]);

  const inserted = beats
    .filter((beat) => beat.inserted)
    .map((beat) => `${beat.id}:${beat.chosen}:${beat.inserted.length}`)
    .join();
  React.useEffect(() => {
    if (!inserted) {
      setPresence({});
      return;
    }
    let stale = false;
    const timer = window.setTimeout(() => {
      bookApi
        .beatPresence(workspaceId, chapterId, text)
        .then((result) => {
          if (!stale) setPresence(result.beats);
        })
        .catch(() => undefined);
    }, 500);
    return () => {
      stale = true;
      window.clearTimeout(timer);
    };
  }, [inserted, text, workspaceId, chapterId]);

  const plain = beats.map((beat) => ({ id: beat.id, text: beat.text, words: beat.words }));
  const saveBeats = (next: Array<{ id?: string; text: string; words: number }>) =>
    run(() => bookApi.saveBeats(workspaceId, chapterId, next));
  const write = (ids: string[], note = "", modelId?: string) =>
    run(() => bookApi.writeBeats(workspaceId, chapterId, { beats: ids, note, modelId }));
  const insert = (ids: string[], cursor?: number) =>
    run(async () => {
      const source = getText();
      const { insertions } = await bookApi.placeBeats(workspaceId, chapterId, { text: source, beats: ids, cursor });
      if (insertions.length === 0) return;
      if (getText() !== source) {
        toast.error(t("book.beats_text_changed"));
        return;
      }
      onInsert(insertions);
      await bookApi.markInserted(
        workspaceId,
        chapterId,
        Object.fromEntries(insertions.map((item) => [item.beat, item.block])),
      );
    });
  const replace = (beat: BookBeat) =>
    run(async () => {
      const source = getText();
      const target = (await bookApi.beatPresence(workspaceId, chapterId, source)).beats[beat.id]?.replacement;
      if (!target || getText() !== source) {
        toast.error(t("book.beats_text_changed"));
        return;
      }
      onReplace(target.from, target.to, target.text);
      await bookApi.markInserted(workspaceId, chapterId, { [beat.id]: target.text });
    });
  const running = job?.running === true;
  const pending = beats.filter((beat) => !beat.inserted && beat.chosen);
  const jobError = job?.aiError
    ? t(job.aiError === "NO_MODEL" ? "book.ai_no_model" : "book.ai_empty")
    : (job?.error ?? null);

  return (
    <Sheet open onOpenChange={(open) => !open && onClose()} modal={false}>
      <SheetContent
        side="right"
        className="flex w-full flex-col gap-3 sm:max-w-md"
        data-testid="beats-panel"
        onInteractOutside={(event) => event.preventDefault()}
      >
        <SheetHeader className="pb-0">
          <SheetTitle className="flex items-center gap-2">
            {t("book.beats_title")}
            <Button size="icon" variant="ghost" className="size-7" asChild>
              <Link to="/settings/book" aria-label={t("book.beats_settings")}>
                <Settings2 className="size-4" />
              </Link>
            </Button>
          </SheetTitle>
          <SheetDescription>
            {t("book.beats_count", { count: beats.length, words: beats.reduce((sum, beat) => sum + beat.words, 0) })}
          </SheetDescription>
        </SheetHeader>
        <div className="flex flex-wrap gap-2 px-4">
          <Button size="sm" variant="outline" onClick={() => setEditing({})}>
            <Plus className="size-4" />
            {t("book.beat_add")}
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={running}
            onClick={() => void run(() => bookApi.splitBeats(workspaceId, chapterId))}
          >
            {t("book.beats_split")}
          </Button>
        </div>
        {running ? (
          <p className="px-4 text-sm text-muted-foreground">
            {job?.splitting
              ? t("book.beats_splitting")
              : t("book.beats_writing", { number: beats.findIndex((beat) => beat.id === job?.current) + 1 })}
          </p>
        ) : jobError ? (
          <p className="px-4 text-sm text-destructive">{jobError}</p>
        ) : null}
        {job?.proposal ? (
          <Proposal
            proposal={job.proposal}
            canReplace={beats.length > 0}
            onCancel={() => void run(() => bookApi.dismissWriting(workspaceId, chapterId))}
            onUse={(items, replaceAll) => void run(() => bookApi.useProposal(workspaceId, chapterId, items, replaceAll))}
          />
        ) : null}
        <div className="min-h-0 flex-1 space-y-2 overflow-auto px-4">
          {beats.length === 0 ? <p className="text-sm text-muted-foreground">{t("book.beats_empty")}</p> : null}
          {beats.map((beat, index) => {
            const draftIndex = beat.drafts.findIndex((draft) => draft.id === beat.chosen);
            const draft = beat.drafts[draftIndex];
            const live = job?.current === beat.id ? job.text : null;
            const state = presence[beat.id];
            const status =
              live !== null
                ? "writing_now"
                : job?.queue.includes(beat.id)
                  ? "queued"
                  : !draft
                    ? "unwritten"
                    : !beat.inserted
                      ? null
                      : state && !state.found
                        ? "edited"
                        : state?.replacement
                          ? "inserted_other"
                          : "inserted";
            const op = (name: string, draftId?: string) =>
              void run(() => bookApi.beatOp(workspaceId, chapterId, { op: name, beat: beat.id, draft: draftId }));
            return (
              <div key={beat.id} className="space-y-1 rounded-xl border p-2 text-sm">
                <button type="button" className="w-full text-left font-medium" onClick={() => setEditing(beat)}>
                  {index + 1} {beat.text}
                  {beat.words > 0 ? (
                    <span className="ml-2 text-xs font-normal text-muted-foreground">{beat.words}</span>
                  ) : null}
                </button>
                {live !== null || draft ? (
                  <p className="max-h-40 overflow-auto whitespace-pre-wrap text-sm">
                    {live !== null ? live.slice(-240) : draft?.text}
                  </p>
                ) : null}
                <div className="flex flex-wrap items-center gap-1">
                  {beat.drafts.length > 1 ? (
                    <>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-7"
                        aria-label={t("book.beats_previous_draft")}
                        disabled={beat.locked || draftIndex <= 0}
                        onClick={() => op("choose", beat.drafts[draftIndex - 1].id)}
                      >
                        <ChevronLeft className="size-4" />
                      </Button>
                      <span className="text-xs">
                        {draftIndex + 1}/{beat.drafts.length}
                      </span>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-7"
                        aria-label={t("book.beats_next_draft")}
                        disabled={beat.locked || draftIndex >= beat.drafts.length - 1}
                        onClick={() => op("choose", beat.drafts[draftIndex + 1].id)}
                      >
                        <ChevronRight className="size-4" />
                      </Button>
                    </>
                  ) : null}
                  <span className="min-w-0 flex-1 text-xs text-muted-foreground">
                    {status ? t(`book.beats_${status}`) : ""}
                    {draft?.outdated && live === null ? (
                      <span className="ml-1 text-amber-600">{t("book.beats_outdated")}</span>
                    ) : null}
                  </span>
                  {state?.replacement && !readOnly ? (
                    <Button size="sm" variant="ghost" onClick={() => void replace(beat)}>
                      {t("book.beats_replace")}
                    </Button>
                  ) : null}
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={running || beat.locked}
                    onClick={() => (beat.drafts.length === 0 ? void write([beat.id]) : setRequesting(beat.id))}
                  >
                    {t(beat.drafts.length === 0 ? "book.beats_write" : "book.beats_rewrite")}
                  </Button>
                  <Button
                    size="icon"
                    variant="ghost"
                    className={cn("size-7", beat.locked && "text-primary")}
                    aria-label={t(beat.locked ? "book.beats_unlock" : "book.beats_lock")}
                    onClick={() => op(beat.locked ? "unlock" : "lock")}
                  >
                    {beat.locked ? <Lock className="size-4" /> : <LockOpen className="size-4" />}
                  </Button>
                  {draft ? (
                    <Button
                      size="icon"
                      variant="ghost"
                      className="size-7"
                      aria-label={t("book.beats_remove_draft")}
                      disabled={beat.locked}
                      onClick={() => op("removeDraft", draft.id)}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  ) : null}
                  {status === "edited" ? (
                    <Button size="sm" variant="ghost" onClick={() => op("forget")}>
                      {t("book.beats_forget")}
                    </Button>
                  ) : null}
                </div>
                {requesting === beat.id ? (
                  <RequestForm
                    onCancel={() => setRequesting(null)}
                    onWrite={(note, modelId) => {
                      setRequesting(null);
                      void write([beat.id], note, modelId);
                    }}
                  />
                ) : null}
              </div>
            );
          })}
        </div>
        <div className="space-y-2 border-t p-4">
          {inserting ? (
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setInserting(false);
                  void insert([]);
                }}
              >
                {t("book.beats_insert_in_order")}
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setInserting(false);
                  void insert([], getCursor());
                }}
              >
                {t("book.beats_insert_at_cursor")}
              </Button>
            </div>
          ) : null}
          <div className="flex gap-2">
            {running ? (
              <Button
                className="flex-1"
                variant="outline"
                onClick={() => void run(() => bookApi.stopWriting(workspaceId, chapterId))}
              >
                {t("book.outline_stop")}
              </Button>
            ) : (
              <Button
                className="flex-1"
                variant="outline"
                disabled={!beats.some((beat) => beat.drafts.length === 0 && !beat.locked)}
                onClick={() => void write([])}
              >
                {t("book.beats_write_all")}
              </Button>
            )}
            <Button className="flex-1" disabled={readOnly || pending.length === 0} onClick={() => setInserting(!inserting)}>
              {t("book.beats_insert", { count: pending.length })}
            </Button>
          </div>
        </div>
        {editing ? (
          <BeatEditor
            beat={editing}
            onClose={() => setEditing(null)}
            onSave={(value, words) => {
              setEditing(null);
              void saveBeats(
                editing.id
                  ? plain.map((beat) => (beat.id === editing.id ? { ...beat, text: value, words } : beat))
                  : [...plain, { text: value, words }],
              );
            }}
            onRemove={() => {
              setEditing(null);
              void saveBeats(plain.filter((beat) => beat.id !== editing.id));
            }}
          />
        ) : null}
      </SheetContent>
    </Sheet>
  );
}

function RequestForm({
  onCancel,
  onWrite,
}: {
  onCancel: () => void;
  onWrite: (note: string, modelId?: string) => void;
}) {
  const { t } = useTranslation("workspace");
  const settings = useSettingsStore((s) => s.settings);
  const [note, setNote] = React.useState("");
  const [model, setModel] = React.useState(DEFAULT_MODEL);
  return (
    <div className="space-y-2 border-t pt-2">
      <Textarea value={note} rows={2} placeholder={t("book.beats_note")} onChange={(e) => setNote(e.target.value)} />
      <SettingsSelectField
        label={t("book.beats_model")}
        value={model}
        onValueChange={setModel}
        options={[
          { value: DEFAULT_MODEL, label: t("book.beats_model_default") },
          ...(settings ? listChatModels(settings) : []).map(({ provider, model: item }) => ({
            value: item.id,
            label: `${provider.name} / ${getModelDisplayName(item.displayName, item.modelId)}`,
          })),
        ]}
      />
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={onCancel}>
          {t("book.cancel")}
        </Button>
        <Button size="sm" onClick={() => onWrite(note, model === DEFAULT_MODEL ? undefined : model)}>
          {t("book.beats_write")}
        </Button>
      </div>
    </div>
  );
}

function Proposal({
  proposal,
  canReplace,
  onCancel,
  onUse,
}: {
  proposal: BeatProposal[];
  canReplace: boolean;
  onCancel: () => void;
  onUse: (items: BeatProposal[], replace: boolean) => void;
}) {
  const { t } = useTranslation("workspace");
  const [items, setItems] = React.useState(proposal);
  const kept = items.filter((item) => item.text.trim());
  return (
    <div className="mx-4 space-y-2 rounded-xl border p-3" data-testid="beats-proposal">
      <h3 className="text-sm font-medium">{t("book.beats_proposal")}</h3>
      {items.map((item, index) => (
        <Input
          key={index}
          value={item.text}
          onChange={(event) =>
            setItems(items.map((old, i) => (i === index ? { ...old, text: event.target.value } : old)))
          }
        />
      ))}
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={onCancel}>
          {t("book.cancel")}
        </Button>
        {canReplace ? (
          <Button size="sm" variant="outline" disabled={kept.length === 0} onClick={() => onUse(kept, false)}>
            {t("book.beats_proposal_append")}
          </Button>
        ) : null}
        <Button size="sm" disabled={kept.length === 0} onClick={() => onUse(kept, true)}>
          {t(canReplace ? "book.beats_proposal_replace" : "book.beats_proposal_use")}
        </Button>
      </div>
    </div>
  );
}
