import * as React from "react";

import { Plus, SlidersHorizontal, Trash2, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Switch } from "~/components/ui/switch";
import api from "~/services/api";
import { downloadJson, readJsonFile } from "../../lib/lorebook-files";
import type { Lorebook, LorebookSummary } from "../../types/tavern";
import { ConfirmDeleteDialog, SettingsList, reportSettingsError } from "../settings/settings-ui";

/** The world books: create, import a SillyTavern world info file or an app export, open one to edit its entries. */
export function LorebooksPanel({ books }: { books: LorebookSummary[] }) {
  const { t } = useTranslation("tavern");
  const navigate = useNavigate();
  const fileInput = React.useRef<HTMLInputElement>(null);
  const [busy, setBusy] = React.useState(false);
  const [deleting, setDeleting] = React.useState<LorebookSummary | null>(null);
  const [deleteBusy, setDeleteBusy] = React.useState(false);

  const create = async () => {
    setBusy(true);
    try {
      const created = await api.post<Lorebook>("lorebooks", { name: t("worldinfo.newBook") });
      navigate(`/settings/lorebooks/${created.id}`);
    } catch (error) {
      reportSettingsError(error, t("common.createFailed"));
    } finally {
      setBusy(false);
    }
  };

  const importFile = async (file: File) => {
    setBusy(true);
    try {
      const parsed = await readJsonFile(file);
      const imported = await api.post<Lorebook>("lorebooks/import", parsed, { searchParams: { name: file.name } });
      toast.success(t("worldinfo.imported", { p1: imported.name, p2: imported.entryCount }));
    } catch (error) {
      reportSettingsError(error, t("worldinfo.importFailed"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={busy} onClick={() => void create()}>
          <Plus className="size-4" />
          {t("worldinfo.addBook")}
        </Button>
        <Button type="button" variant="outline" disabled={busy} onClick={() => fileInput.current?.click()}>
          <Upload className="size-4" />
          {t("worldinfo.import")}
        </Button>
        <Button type="button" variant="outline" asChild>
          <Link to="/settings/world-info">
            <SlidersHorizontal className="size-4" />
            {t("worldinfo.globalSettings")}
          </Link>
        </Button>
        <input
          ref={fileInput}
          type="file"
          accept=".json,application/json"
          className="hidden"
          data-testid="lorebook-import"
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            if (file) void importFile(file);
          }}
        />
      </div>
      {books.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {t("worldinfo.emptyBooks")}
        </p>
      ) : (
        <SettingsList>
          {books.map((book) => (
            <BookCard key={book.id} book={book} onDelete={() => setDeleting(book)} />
          ))}
        </SettingsList>
      )}
      <ConfirmDeleteDialog
        open={deleting !== null}
        onOpenChange={(open) => {
          if (!open) setDeleting(null);
        }}
        title={t("worldinfo.deleteBookTitle")}
        description={t("worldinfo.deleteBookMessage", { p1: deleting?.name || t("worldinfo.unnamedBook") })}
        loading={deleteBusy}
        onConfirm={async () => {
          if (!deleting) return;
          setDeleteBusy(true);
          try {
            await api.delete(`lorebooks/${deleting.id}`);
            toast.success(t("common.deleted"));
            setDeleting(null);
          } catch (error) {
            reportSettingsError(error, t("common.deleteFailed"));
          } finally {
            setDeleteBusy(false);
          }
        }}
      />
    </div>
  );
}

function BookCard({ book, onDelete }: { book: LorebookSummary; onDelete: () => void }) {
  const { t } = useTranslation("tavern");
  const [enabled, setEnabled] = React.useState(book.enabled);
  React.useEffect(() => setEnabled(book.enabled), [book.enabled]);
  const name = book.name.trim() ? book.name : t("worldinfo.unnamedBook");
  return (
    <div className="flex min-h-12 items-center gap-3 px-3 py-2" data-lorebook={book.id}>
      <Link to={`/settings/lorebooks/${book.id}`} className="min-w-0 flex-1 space-y-0.5">
        <div className="flex flex-wrap items-center gap-2">
          <p className="truncate font-medium">{name}</p>
          <Badge variant="secondary">{t("worldinfo.entriesCount", { p1: book.entryCount })}</Badge>
        </div>
        {book.description.trim() ? <p className="line-clamp-2 text-sm text-muted-foreground">{book.description}</p> : null}
      </Link>
      <div className="flex shrink-0 items-center gap-1">
        <Switch
          checked={enabled}
          aria-label={name}
          onCheckedChange={async (next) => {
            setEnabled(next);
            try {
              await api.put(`lorebooks/${book.id}`, { enabled: next });
            } catch (error) {
              setEnabled(book.enabled);
              reportSettingsError(error, t("common.saveFailed"));
            }
          }}
        />
        <Button type="button" size="icon-sm" variant="ghost" aria-label={t("layout.delete")} onClick={onDelete}>
          <Trash2 className="size-4" />
        </Button>
      </div>
    </div>
  );
}

/** Download a book as a file: [format] "st" is a SillyTavern world info file, "native" the app's own export. */
export async function exportBook(
  book: { id: string; name: string },
  format: "st" | "native",
  t: (key: string) => string,
) {
  try {
    const data = await api.get<unknown>(`lorebooks/${book.id}/export`, { searchParams: { format } });
    downloadJson(`${book.name.trim() || t("worldinfo.unnamedBook")}.json`, data);
  } catch (error) {
    reportSettingsError(error, t("worldinfo.exportFailed"));
  }
}
