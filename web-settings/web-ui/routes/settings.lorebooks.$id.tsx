import * as React from "react";

import { ArrowLeft, ChevronDown, ChevronUp, Download, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import { ConfirmDeleteDialog, reportSettingsError } from "@overlay/components/settings/settings-ui";
import { CommitInput } from "@overlay/components/settings/commit-fields";
import { FieldLabel, SwitchField } from "@overlay/components/tavern/tavern-ui";
import { WorldInfoEntryEditor } from "@overlay/components/tavern/world-info-entry-editor";
import { exportBook } from "@overlay/components/tavern/lorebooks-panel";
import { diffObjects, entryMatchesQuery, entryTitle, formatKeyList } from "@overlay/lib/tavern";
import type { EntryResult, Lorebook, WorldInfoEntry } from "@overlay/types/tavern";

/** Entries shown at once; the rest come with "show more" (a book can have thousands). */
const PAGE_SIZE = 100;

/**
 * One world book: its name, description and switch, a searchable list of entries, and the seven-group entry editor.
 * The entries are fetched as a whole (the settings snapshot carries only a summary); every change goes out as a patch
 * of the changed fields and the book here is updated from the server's reply. When the book's fingerprint in the
 * settings snapshot differs from the one this page knows, the book was changed elsewhere and is fetched again.
 */
export default function SettingsLorebookPage() {
  const { t } = useTranslation("tavern");
  const { id } = useParams();
  const navigate = useNavigate();
  const remoteRev = useSettingsStore((state) => state.settings?.lorebooks?.find((book) => book.id === id)?.rev);
  const [book, setBook] = React.useState<Lorebook | null>(null);
  const [missing, setMissing] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const [limit, setLimit] = React.useState(PAGE_SIZE);
  const [open, setOpen] = React.useState<ReadonlySet<number>>(new Set());
  const [deleteBook, setDeleteBook] = React.useState(false);
  const [deletingEntry, setDeletingEntry] = React.useState<WorldInfoEntry | null>(null);
  const [busy, setBusy] = React.useState(false);
  const inflight = React.useRef(0);
  const chain = React.useRef<Promise<unknown>>(Promise.resolve());
  const knownRev = React.useRef<string | null>(null);

  const load = React.useCallback(async () => {
    if (!id) return;
    try {
      const loaded = await api.get<Lorebook>(`lorebooks/${id}`);
      knownRev.current = loaded.rev;
      setBook(loaded);
      setMissing(false);
    } catch {
      setMissing(true);
    }
  }, [id]);

  React.useEffect(() => {
    setBook(null);
    knownRev.current = null;
    void load();
  }, [load]);

  // Changed elsewhere (the App, another tab): the fingerprint in the settings snapshot is not the one we know.
  React.useEffect(() => {
    if (remoteRev !== undefined && knownRev.current !== null && remoteRev !== knownRev.current && inflight.current === 0) {
      void load();
    }
  }, [remoteRev, load]);

  /** Run [work] after the earlier edits were answered, so replies cannot arrive out of order. */
  const enqueue = React.useCallback(
    <T,>(work: () => Promise<T>, onDone: (result: T) => void) => {
      inflight.current += 1;
      chain.current = chain.current
        .then(work)
        .then(onDone, (error: unknown) => {
          reportSettingsError(error, t("common.saveFailed"));
          void load();
        })
        .finally(() => {
          inflight.current -= 1;
        });
    },
    [load, t],
  );

  const entries = book?.entries;
  const shown = React.useMemo(() => (entries ?? []).filter((entry) => entryMatchesQuery(entry, query)), [entries, query]);

  if (missing || !id) {
    return <p className="text-sm text-muted-foreground">{t("worldinfo.bookNotFound")}</p>;
  }
  if (!book) {
    return <p className="text-sm text-muted-foreground">{t("common.loading")}</p>;
  }

  const toggleOpen = (uid: number) =>
    setOpen((current) => {
      const next = new Set(current);
      if (!next.delete(uid)) next.add(uid);
      return next;
    });

  const updateEntry = (uid: number, transform: (entry: WorldInfoEntry) => WorldInfoEntry) => {
    const before = book.entries.find((entry) => entry.uid === uid);
    if (!before) return;
    const after = transform(before);
    const patch = diffObjects(before, after);
    if (Object.keys(patch).length === 0) return;
    setBook((current) => current && { ...current, entries: current.entries.map((entry) => (entry.uid === uid ? after : entry)) });
    enqueue(
      () => api.put<EntryResult>(`lorebooks/${id}/entries/${uid}`, patch),
      (result) => {
        knownRev.current = result.rev;
        // The edited value is what is shown while more edits are on their way; the reply replaces it once idle.
        if (inflight.current <= 1) {
          setBook((current) => current && { ...current, rev: result.rev, entries: current.entries.map((entry) => (entry.uid === uid ? result.entry : entry)) });
        }
      },
    );
  };

  const patchBook = (patch: { name?: string; description?: string; enabled?: boolean }) => {
    setBook((current) => current && { ...current, ...patch });
    enqueue(() => api.put(`lorebooks/${id}`, patch), () => undefined);
  };

  const addEntry = async () => {
    try {
      const result = await api.post<EntryResult>(`lorebooks/${id}/entries`, {});
      knownRev.current = result.rev;
      setBook((current) => current && { ...current, rev: result.rev, entryCount: current.entryCount + 1, entries: [...current.entries, result.entry] });
      setQuery("");
      setLimit((current) => Math.max(current, book.entries.length + 1));
      setOpen((current) => new Set(current).add(result.entry.uid));
    } catch (error) {
      reportSettingsError(error, t("common.createFailed"));
    }
  };

  const removeEntry = async (entry: WorldInfoEntry) => {
    try {
      const result = await api.delete<{ rev: string }>(`lorebooks/${id}/entries/${entry.uid}`);
      knownRev.current = result.rev;
      setBook((current) => current && { ...current, rev: result.rev, entryCount: current.entryCount - 1, entries: current.entries.filter((item) => item.uid !== entry.uid) });
    } catch (error) {
      reportSettingsError(error, t("common.deleteFailed"));
    }
  };

  const setAllEnabled = async (enabled: boolean) => {
    // With a search active only the entries it found change; otherwise the whole book.
    const uids = query.trim() === "" ? null : shown.map((entry) => entry.uid);
    try {
      const result = await api.put<{ rev: string }>(`lorebooks/${id}/entries`, uids ? { enabled, uids } : { enabled });
      knownRev.current = result.rev;
      setBook((current) => current && {
        ...current,
        rev: result.rev,
        entries: current.entries.map((entry) => (uids === null || uids.includes(entry.uid) ? { ...entry, disable: !enabled } : entry)),
      });
    } catch (error) {
      reportSettingsError(error, t("common.saveFailed"));
    }
  };

  const visible = shown.slice(0, limit);
  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <Button variant="ghost" size="sm" asChild>
          <Link to="/settings/extensions?tab=lorebook">
            <ArrowLeft className="size-4" />
            {t("worldinfo.backToBooks")}
          </Link>
        </Button>
        <div className="grid gap-3 md:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("worldinfo.bookName")}</FieldLabel>
            <CommitInput value={book.name} onCommit={(name) => patchBook({ name })} />
          </div>
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("worldinfo.bookDescription")}</FieldLabel>
            <CommitInput value={book.description} onCommit={(description) => patchBook({ description })} />
          </div>
        </div>
        <SwitchField title={t("common.enabled")} checked={book.enabled} onCheckedChange={(enabled) => patchBook({ enabled })} />
      </div>

      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <Input
            className="max-w-xs"
            placeholder={t("worldinfo.search")}
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setLimit(PAGE_SIZE);
            }}
          />
          <Button type="button" onClick={() => void addEntry()}>
            <Plus className="size-4" />
            {t("worldinfo.addEntry")}
          </Button>
          <Button type="button" variant="outline" onClick={() => void setAllEnabled(true)}>
            {t("worldinfo.enableAll")}
          </Button>
          <Button type="button" variant="outline" onClick={() => void setAllEnabled(false)}>
            {t("worldinfo.disableAll")}
          </Button>
          <span className="flex-1" />
          <Button type="button" variant="outline" onClick={() => void exportBook(book, "st", t)}>
            <Download className="size-4" />
            {t("worldinfo.exportTavern")}
          </Button>
          <Button type="button" variant="outline" onClick={() => void exportBook(book, "native", t)}>
            <Download className="size-4" />
            {t("worldinfo.exportNative")}
          </Button>
          <Button type="button" variant="ghost" onClick={() => setDeleteBook(true)}>
            <Trash2 className="size-4" />
            {t("worldinfo.deleteBook")}
          </Button>
        </div>
        <p className="text-sm text-muted-foreground">{t("worldinfo.entriesCount", { p1: book.entries.length })}</p>

        {book.entries.length === 0 ? (
          <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">{t("worldinfo.emptyEntries")}</p>
        ) : shown.length === 0 ? (
          <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">{t("worldinfo.noMatch")}</p>
        ) : (
          <ul className="space-y-2">
            {visible.map((entry) => (
              <EntryRow
                key={entry.uid}
                entry={entry}
                expanded={open.has(entry.uid)}
                onToggle={() => toggleOpen(entry.uid)}
                onChange={(transform) => updateEntry(entry.uid, transform)}
                onDelete={() => setDeletingEntry(entry)}
              />
            ))}
          </ul>
        )}
        {shown.length > visible.length ? (
          <Button type="button" variant="outline" onClick={() => setLimit((current) => current + PAGE_SIZE)}>
            {t("worldinfo.showMore", { p1: shown.length - visible.length })}
          </Button>
        ) : null}
      </div>

      <ConfirmDeleteDialog
        open={deleteBook}
        onOpenChange={setDeleteBook}
        title={t("worldinfo.deleteBookTitle")}
        description={t("worldinfo.deleteBookMessage", { p1: book.name || t("worldinfo.unnamedBook") })}
        loading={busy}
        onConfirm={async () => {
          setBusy(true);
          try {
            await api.delete(`lorebooks/${id}`);
            toast.success(t("common.deleted"));
            navigate("/settings/extensions?tab=lorebook");
          } catch (error) {
            reportSettingsError(error, t("common.deleteFailed"));
          } finally {
            setBusy(false);
          }
        }}
      />
      <ConfirmDeleteDialog
        open={deletingEntry !== null}
        onOpenChange={(next) => {
          if (!next) setDeletingEntry(null);
        }}
        title={t("worldinfo.deleteEntryTitle")}
        description={t("worldinfo.deleteEntryMessage", { p1: deletingEntry ? entryTitle(deletingEntry) || `#${deletingEntry.uid}` : "" })}
        loading={false}
        onConfirm={() => {
          if (deletingEntry) void removeEntry(deletingEntry);
          setDeletingEntry(null);
        }}
      />
    </div>
  );
}

function EntryRow({
  entry,
  expanded,
  onToggle,
  onChange,
  onDelete,
}: {
  entry: WorldInfoEntry;
  expanded: boolean;
  onToggle: () => void;
  onChange: (transform: (entry: WorldInfoEntry) => WorldInfoEntry) => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation("tavern");
  const title = entryTitle(entry) || t("worldinfo.unnamedEntry");
  return (
    <li className="rounded-lg border" data-entry-uid={entry.uid}>
      <div className="flex items-center gap-2 px-3 py-2">
        <button type="button" className="min-w-0 flex-1 text-left" onClick={onToggle}>
          <div className="flex flex-wrap items-center gap-2">
            <p className="truncate text-sm font-medium">{title}</p>
            {entry.constant ? <Badge variant="outline">{t("worldinfo.constantBadge")}</Badge> : null}
          </div>
          <p className="truncate text-xs text-muted-foreground">
            {formatKeyList(entry.key) || t("worldinfo.noKeywords")}
          </p>
        </button>
        <Switch
          checked={!entry.disable}
          aria-label={title}
          onCheckedChange={(on) => onChange((current) => ({ ...current, disable: !on }))}
        />
        <Button type="button" size="icon-sm" variant="ghost" aria-label={t(expanded ? "layout.collapse" : "layout.expand")} onClick={onToggle}>
          {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
        </Button>
      </div>
      {expanded ? (
        <div className="px-4 pb-3">
          <WorldInfoEntryEditor entry={entry} onChange={onChange} />
          <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
            <Trash2 className="size-4" />
            {t("layout.delete")}
          </Button>
        </div>
      ) : null}
    </li>
  );
}
