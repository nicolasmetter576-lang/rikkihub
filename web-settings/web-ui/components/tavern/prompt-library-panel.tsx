import * as React from "react";

import { ChevronDown, ChevronUp, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Switch } from "~/components/ui/switch";
import api from "~/services/api";
import { useAutosaved } from "../../hooks/use-autosaved";
import {
  entryDepth,
  entryOrder,
  entryPlacement,
  isUuid,
  libraryAnchor,
  libraryEnabled,
  newLibraryEntry,
  withLibraryMeta,
} from "../../lib/tavern";
import type { PromptEntry } from "../../types/tavern";
import { ConfirmDeleteDialog, reportSettingsError } from "../settings/settings-ui";
import { PromptEntryFields } from "./prompt-entry-fields";

/**
 * The prompt library: entries any assistant or conversation can pick (the old "mode injections"). Entries are
 * edited in place and every change is saved at once, as the App does.
 */
export function PromptLibraryPanel({ entries }: { entries: PromptEntry[] }) {
  const { t } = useTranslation("tavern");
  const [creating, setCreating] = React.useState(false);
  const [deleting, setDeleting] = React.useState<PromptEntry | null>(null);
  const [deleteBusy, setDeleteBusy] = React.useState(false);
  // Entries with an identifier that is not a UUID (imported from elsewhere) cannot be picked; they are left alone.
  const usable = entries.filter((entry) => isUuid(entry.identifier));
  return (
    <div className="space-y-4">
      <Button
        type="button"
        disabled={creating}
        onClick={async () => {
          setCreating(true);
          try {
            await api.post("prompt-library", newLibraryEntry(t("library.newEntry")));
            toast.success(t("common.created"));
          } catch (error) {
            reportSettingsError(error, t("common.createFailed"));
          } finally {
            setCreating(false);
          }
        }}
      >
        <Plus className="size-4" />
        {t("library.add")}
      </Button>
      {usable.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {t("library.empty")}
        </p>
      ) : (
        usable.map((entry) => <LibraryEntryCard key={entry.identifier} entry={entry} onDelete={() => setDeleting(entry)} />)
      )}
      <ConfirmDeleteDialog
        open={deleting !== null}
        onOpenChange={(open) => {
          if (!open) setDeleting(null);
        }}
        title={t("library.deleteTitle")}
        description={t("library.deleteMessage", { p1: deleting?.name || t("library.unnamed") })}
        loading={deleteBusy}
        onConfirm={async () => {
          if (!deleting) return;
          setDeleteBusy(true);
          try {
            await api.delete(`prompt-library/${deleting.identifier}`);
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

function LibraryEntryCard({ entry, onDelete }: { entry: PromptEntry; onDelete: () => void }) {
  const { t } = useTranslation("tavern");
  const [expanded, setExpanded] = React.useState(false);
  const { value, change } = useAutosaved<PromptEntry>(
    entry,
    (patch) => api.put<PromptEntry>(`prompt-library/${entry.identifier}`, patch),
    (error) => reportSettingsError(error, t("common.saveFailed")),
  );
  const title = value.name?.trim() ? value.name : t("library.unnamed");
  return (
    <div className="rounded-xl border" data-library-entry={value.identifier}>
      <div className="flex items-center gap-2 px-4 py-3">
        <button type="button" className="min-w-0 flex-1 text-left" onClick={() => setExpanded((open) => !open)}>
          <p className="truncate font-medium">{title}</p>
          <p className="truncate text-xs text-muted-foreground">{librarySummary(value, t)}</p>
        </button>
        <Switch
          checked={libraryEnabled(value)}
          aria-label={title}
          onCheckedChange={(enabled) => change((current) => withLibraryMeta(current, { enabled }))}
        />
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          aria-label={t(expanded ? "layout.collapse" : "layout.expand")}
          onClick={() => setExpanded((open) => !open)}
        >
          {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
        </Button>
      </div>
      {expanded ? (
        <div className="flex flex-col gap-3 px-4 pb-4">
          <PromptEntryFields entry={value} scope="library" onChange={change} />
          <div>
            <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
              <Trash2 className="size-4" />
              {t("layout.delete")}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** Where the entry goes: in chat at a depth, or before/after the prompt or before the chat. */
function librarySummary(entry: PromptEntry, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (entryPlacement(entry) === "in_chat") {
    return `${t("library.inChatDepth", { p1: entryDepth(entry) })} · ${t("library.orderFormat", { p1: entryOrder(entry) })}`;
  }
  const anchor = libraryAnchor(entry) ?? "after_system";
  const key = anchor === "before_system" ? "layout.anchorBeforeSystem" : anchor === "before_history" ? "layout.anchorBeforeHistory" : "layout.anchorAfterSystem";
  return `${t(key)} · ${t("library.orderFormat", { p1: entryOrder(entry) })}`;
}
