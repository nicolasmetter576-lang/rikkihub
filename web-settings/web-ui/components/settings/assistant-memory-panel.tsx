import * as React from "react";
import { ArrowRightLeft, ChevronDown, ChevronRight, History, Moon, Pencil, Plus, RefreshCw, Square, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";
import api, { ApiError } from "~/services/api";
import { ConfirmDeleteDialog, reportSettingsError } from "@overlay/components/settings/settings-ui";

/** Same limits as the server (MemoryFile.MAX_BYTES / normalizePath). */
export const MEMORY_FILE_MAX_BYTES = 48 * 1024;
const MEMORY_PATH_MAX_LENGTH = 128;
const NEW_FILE_VERSION = "new";
// While a dream runs the list is refreshed this often to pick up its changes.
const DREAM_POLL_MS = 3000;

export interface MemoryFileSummary {
  path: string;
  title: string;
  description: string;
  version: string;
  size: number;
  updatedAt: number;
}

export interface MemoryFileDetail {
  path: string;
  title: string;
  description: string;
  aliases: string[];
  version: string;
  updatedAt: number;
  content: string;
}

export interface MemoryDreamStatus {
  enabled: boolean;
  automatic: boolean;
  running: boolean;
  pendingTurns: number;
  lastFinishedAt?: number | null;
  lastError?: string | null;
  lastChangedPaths?: string[];
  lastDeletedPaths?: string[];
  canBackfill?: boolean;
}

export interface GlobalMemoryImportableAssistant {
  id: string;
  name: string;
  fileCount: number;
}

export interface MemoryFilesResponse {
  scope: "assistant" | "global";
  maxBytes: number;
  files: MemoryFileSummary[];
  dream?: MemoryDreamStatus | null;
  privateFileCount?: number | null;
  globalFileCount?: number | null;
  globalAssistantCount?: number | null;
  globalAssistantNames?: string[] | null;
  importableAssistants?: GlobalMemoryImportableAssistant[] | null;
}

export interface MemoryMigrationConflict {
  path: string;
  title: string;
  sourceBytes: number;
  targetBytes: number;
  mergedBytes: number;
  mergeExceedsLimit: boolean;
}

export interface MemoryMigrationPreview {
  sourceCount: number;
  newPaths: string[];
  identicalPaths: string[];
  conflicts: MemoryMigrationConflict[];
}

export interface MemoryMigrationResult {
  copied: number;
  merged: number;
  overwritten: number;
  skipped: number;
  failedOversized?: string[];
}

type ConflictStrategy = "merge" | "skip" | "overwrite";

/** Client mirror of MemoryFile.normalizePath: absolute, ends in .md, no empty/hidden segments. Null when invalid. */
export function normalizeMemoryPath(raw: string): string | null {
  const trimmed = raw.trim();
  const path = trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  if (path.length > MEMORY_PATH_MAX_LENGTH || !path.endsWith(".md")) return null;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f\\]/.test(path)) return null;
  const segments = path.slice(1).split("/");
  if (segments.some((segment) => !segment.trim() || segment !== segment.trim() || segment.startsWith("."))) {
    return null;
  }
  return path;
}

/** Content must fit the per-file byte limit. Returns the error key or null. */
export function memoryContentError(content: string, maxBytes = MEMORY_FILE_MAX_BYTES): "too_large" | null {
  return new TextEncoder().encode(content).length > maxBytes ? "too_large" : null;
}

/** Directory of a memory path; files at the root are in "/". */
export function memoryDirectoryOf(path: string): string {
  const index = path.lastIndexOf("/");
  return index <= 0 ? "/" : path.slice(0, index);
}

interface AssistantMemoryPanelProps {
  assistantId: string;
  /** Saved `useGlobalMemory`; decides which store the server returns. */
  savedUseGlobal: boolean;
  /** Draft value on the page; a mismatch means the list will switch after saving. */
  draftUseGlobal: boolean;
}

type Editing = { path: string; version: string; content: string } | null;

/**
 * Memory store of an assistant: Markdown files the model reads in chat and the background
 * "dreaming" keeps up to date. Every write carries the version it was based on, so an edit
 * never silently overwrites what dreaming changed in the meantime.
 */
export function AssistantMemoryPanel({ assistantId, savedUseGlobal, draftUseGlobal }: AssistantMemoryPanelProps) {
  return (
    <MemoryStorePanel
      mode="assistant"
      base={`assistants/${assistantId}/memories`}
      reloadKey={`${assistantId}:${savedUseGlobal}`}
      savedUseGlobal={savedUseGlobal}
      draftUseGlobal={draftUseGlobal}
    />
  );
}

/** Standalone manager for the shared global memory store (`__global__`). */
export function GlobalMemoryPanel() {
  return (
    <MemoryStorePanel
      mode="global"
      base="memories/global"
      reloadKey="global"
      savedUseGlobal={true}
      draftUseGlobal={true}
    />
  );
}

interface MemoryStorePanelProps {
  mode: "assistant" | "global";
  base: string;
  reloadKey: string;
  savedUseGlobal: boolean;
  draftUseGlobal: boolean;
}

function MemoryStorePanel({ mode, base, reloadKey, savedUseGlobal, draftUseGlobal }: MemoryStorePanelProps) {
  const { t } = useTranslation("settings");
  const [data, setData] = React.useState<MemoryFilesResponse | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [expanded, setExpanded] = React.useState<Record<string, MemoryFileDetail | "loading">>({});
  const [editing, setEditing] = React.useState<Editing>(null);
  const [creating, setCreating] = React.useState<{ path: string; content: string } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [deleteTarget, setDeleteTarget] = React.useState<MemoryFileSummary | null>(null);

  // Migration dialog state (assistant -> global, or global importing from a picked assistant)
  const [migrateSource, setMigrateSource] = React.useState<{ previewUrl: string; executeUrl: string; label: string } | null>(null);
  const [migratePreview, setMigratePreview] = React.useState<MemoryMigrationPreview | null>(null);
  const [migrateLoading, setMigrateLoading] = React.useState(false);
  const [migrateStrategy, setMigrateStrategy] = React.useState<ConflictStrategy>("merge");
  const [migrateSyncMarks, setMigrateSyncMarks] = React.useState(true);
  const [pickerOpen, setPickerOpen] = React.useState(false);

  // Backfill dialog state
  const [backfillOpen, setBackfillOpen] = React.useState(false);
  const [backfillDays, setBackfillDays] = React.useState<7 | 30>(30);
  const [backfillCount, setBackfillCount] = React.useState<{ candidates: number; maxPerRun: number } | null>(null);
  const [backfillCounting, setBackfillCounting] = React.useState(false);

  const requestIdRef = React.useRef(0);
  const maxBytes = data?.maxBytes ?? MEMORY_FILE_MAX_BYTES;

  const load = React.useCallback(() => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    api
      .get<MemoryFilesResponse>(base)
      .then((result) => {
        if (requestId !== requestIdRef.current) return;
        setData(result);
        // Expanded files that changed or disappeared are reloaded on demand.
        setExpanded((current) => {
          const versions = new Map(result.files.map((file) => [file.path, file.version]));
          return Object.fromEntries(
            Object.entries(current).filter(([path, detail]) => detail === "loading" || versions.get(path) === detail.version),
          );
        });
      })
      .catch((error) => {
        if (requestId === requestIdRef.current) reportSettingsError(error, t("assistants.memory.load_failed"));
      })
      .finally(() => {
        if (requestId === requestIdRef.current) setLoading(false);
      });
  }, [base, t]);

  React.useEffect(() => {
    load();
  }, [load, reloadKey]);

  React.useEffect(() => {
    const onFocus = () => load();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [load]);

  const dreaming = data?.dream?.running === true;
  React.useEffect(() => {
    if (!dreaming) return;
    const timer = window.setInterval(load, DREAM_POLL_MS);
    return () => window.clearInterval(timer);
  }, [dreaming, load]);

  React.useEffect(() => {
    if (!migrateSource) return;
    let active = true;
    setMigrateLoading(true);
    setMigratePreview(null);
    setMigrateStrategy("merge");
    setMigrateSyncMarks(true);
    api
      .get<MemoryMigrationPreview>(migrateSource.previewUrl)
      .then((preview) => {
        if (active) setMigratePreview(preview);
      })
      .catch((error) => {
        if (active) {
          reportSettingsError(error, t("assistants.memory.migrate_failed"));
          setMigrateSource(null);
        }
      })
      .finally(() => {
        if (active) setMigrateLoading(false);
      });
    return () => {
      active = false;
    };
  }, [migrateSource, t]);

  React.useEffect(() => {
    if (!backfillOpen) return;
    let active = true;
    setBackfillCounting(true);
    setBackfillCount(null);
    api
      .get<{ candidates: number; maxPerRun: number }>(`${base}/backfill/count?days=${backfillDays}`)
      .then((res) => {
        if (active) setBackfillCount(res);
      })
      .catch((error) => {
        if (active) reportSettingsError(error, t("assistants.memory.dream_failed_to_start"));
      })
      .finally(() => {
        if (active) setBackfillCounting(false);
      });
    return () => {
      active = false;
    };
  }, [backfillOpen, backfillDays, base, t]);

  const fileUrl = (path: string) => `${base}/file?path=${encodeURIComponent(path)}`;

  const fetchFile = async (path: string) => {
    setExpanded((current) => ({ ...current, [path]: "loading" }));
    try {
      const detail = await api.get<MemoryFileDetail>(fileUrl(path));
      setExpanded((current) => ({ ...current, [path]: detail }));
      return detail;
    } catch (error) {
      setExpanded(({ [path]: _, ...rest }) => rest);
      handleMutationError(error, t("assistants.memory.load_failed"));
      return null;
    }
  };

  const toggle = (path: string) => {
    if (expanded[path]) {
      setExpanded(({ [path]: _, ...rest }) => rest);
    } else {
      void fetchFile(path);
    }
  };

  // A file deleted elsewhere answers 404, one changed elsewhere 409: say so and resync.
  const handleMutationError = (error: unknown, fallback: string) => {
    if (error instanceof ApiError && error.code === 404) {
      toast.error(t("assistants.memory.gone"));
    } else if (error instanceof ApiError && error.code === 409) {
      toast.error(t("assistants.memory.changed"));
    } else {
      reportSettingsError(error, fallback);
    }
    load();
  };

  const startEdit = async (path: string) => {
    const current = expanded[path];
    const detail = current && current !== "loading" ? current : await fetchFile(path);
    if (detail) setEditing({ path: detail.path, version: detail.version, content: detail.content });
  };

  const save = async () => {
    if (!editing) return;
    if (memoryContentError(editing.content, maxBytes)) {
      toast.error(t("assistants.memory.error_too_large", { max: Math.floor(maxBytes / 1024) }));
      return;
    }
    setBusy(true);
    try {
      const saved = await api.put<MemoryFileDetail>(`${base}/file`, {
        path: editing.path,
        content: editing.content,
        ifVersion: editing.version,
      });
      setExpanded((current) => ({ ...current, [saved.path]: saved }));
      setEditing(null);
      load();
    } catch (error) {
      // Keep the draft open so nothing typed is lost; the user can copy it and retry.
      handleMutationError(error, t("common.save_failed"));
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    if (!creating) return;
    const path = normalizeMemoryPath(creating.path);
    if (!path) {
      toast.error(t("assistants.memory.error_path"));
      return;
    }
    if (memoryContentError(creating.content, maxBytes)) {
      toast.error(t("assistants.memory.error_too_large", { max: Math.floor(maxBytes / 1024) }));
      return;
    }
    setBusy(true);
    try {
      await api.put(`${base}/file`, { path, content: creating.content, ifVersion: NEW_FILE_VERSION });
      setCreating(null);
      load();
    } catch (error) {
      if (error instanceof ApiError && error.code === 409) {
        toast.error(t("assistants.memory.path_taken"));
      } else {
        reportSettingsError(error, t("common.create_failed"));
      }
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!deleteTarget) return;
    setBusy(true);
    try {
      await api.delete(`${fileUrl(deleteTarget.path)}&ifVersion=${encodeURIComponent(deleteTarget.version)}`);
      setDeleteTarget(null);
      setExpanded(({ [deleteTarget.path]: _, ...rest }) => rest);
      load();
    } catch (error) {
      setDeleteTarget(null);
      handleMutationError(error, t("common.delete_failed"));
    } finally {
      setBusy(false);
    }
  };

  const dreamNow = async () => {
    setBusy(true);
    try {
      await api.post(`${base}/dream`);
      load();
    } catch (error) {
      reportSettingsError(error, t("assistants.memory.dream_failed_to_start"));
    } finally {
      setBusy(false);
    }
  };

  const stopDream = async () => {
    setBusy(true);
    try {
      await api.delete(`${base}/dream`);
      load();
    } catch (error) {
      reportSettingsError(error, t("assistants.memory.dream_failed_to_start"));
    } finally {
      setBusy(false);
    }
  };

  const runBackfill = async () => {
    setBusy(true);
    try {
      await api.post(`${base}/backfill`, { days: backfillDays });
      setBackfillOpen(false);
      load();
    } catch (error) {
      reportSettingsError(error, t("assistants.memory.dream_failed_to_start"));
    } finally {
      setBusy(false);
    }
  };

  const runMigration = async () => {
    if (!migrateSource) return;
    setBusy(true);
    try {
      const result = await api.post<MemoryMigrationResult>(migrateSource.executeUrl, {
        strategy: migrateStrategy,
        syncMarks: migrateSyncMarks,
      });
      const summary = t("assistants.memory.migrate_result", {
        copied: result.copied,
        merged: result.merged,
        overwritten: result.overwritten,
        skipped: result.skipped,
      });
      if (result.failedOversized && result.failedOversized.length > 0) {
        toast.warning(`${summary} (${t("assistants.memory.migrate_oversized_short", { paths: result.failedOversized.join(", ") })})`);
      } else {
        toast.success(summary);
      }
      setMigrateSource(null);
      setMigratePreview(null);
      load();
    } catch (error) {
      reportSettingsError(error, t("assistants.memory.migrate_failed"));
    } finally {
      setBusy(false);
    }
  };

  const scope = data?.scope ?? (savedUseGlobal ? "global" : "assistant");
  const files = data?.files ?? [];
  const dream = data?.dream ?? null;
  const privateFileCount = data?.privateFileCount ?? 0;
  const importableAssistants = data?.importableAssistants ?? [];
  const globalAssistantNames = data?.globalAssistantNames ?? [];

  const openSelfMigrate = () =>
    setMigrateSource({
      previewUrl: `${base}/migrate-to-global/preview`,
      executeUrl: `${base}/migrate-to-global`,
      label: t("assistants.memory.scope_assistant"),
    });

  const oversizedOnMerge = migratePreview?.conflicts.filter((c) => c.mergeExceedsLimit).map((c) => c.path) ?? [];
  const canSubmitMigration =
    !!migratePreview &&
    !migrateLoading &&
    !busy &&
    (migratePreview.newPaths.length > 0 ||
      (migratePreview.conflicts.length > 0 && migrateStrategy !== "skip") ||
      (migrateSyncMarks && migratePreview.sourceCount > 0));

  return (
    <section className="space-y-3 rounded-xl border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="space-y-0.5">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold">
              {mode === "global" ? t("assistants.memory.global_title") : t("assistants.memory.title")}
            </h2>
            <Badge variant="secondary">
              {scope === "global" ? t("assistants.memory.scope_global") : t("assistants.memory.scope_assistant")}
            </Badge>
          </div>
          <p className="text-xs text-muted-foreground">
            {mode === "global"
              ? globalAssistantNames.length > 0
                ? t("assistants.memory.global_used_by", {
                    count: globalAssistantNames.length,
                    names: globalAssistantNames.slice(0, 4).join(", ") + (globalAssistantNames.length > 4 ? "…" : ""),
                  })
                : t("assistants.memory.global_no_assistants")
              : scope === "global"
                ? t("assistants.memory.scope_global_hint")
                : t("assistants.memory.scope_assistant_hint")}
          </p>
        </div>
        <div className="flex items-center gap-1">
          {mode === "assistant" && privateFileCount > 0 ? (
            <Button type="button" variant="outline" size="sm" onClick={openSelfMigrate}>
              <ArrowRightLeft className="size-3.5" />
              {t("assistants.memory.migrate_action")}
            </Button>
          ) : null}
          {mode === "global" && importableAssistants.length > 0 ? (
            <Button type="button" variant="outline" size="sm" onClick={() => setPickerOpen(true)}>
              <ArrowRightLeft className="size-3.5" />
              {t("assistants.memory.import_from_assistant")}
            </Button>
          ) : null}
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("assistants.memory.add")}
            title={t("assistants.memory.add")}
            onClick={() => setCreating({ path: "/topics/", content: "" })}
          >
            <Plus className="size-4" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("assistants.memory.refresh")}
            title={t("assistants.memory.refresh")}
            disabled={loading}
            onClick={load}
          >
            <RefreshCw className={cn("size-4", loading && "animate-spin")} />
          </Button>
        </div>
      </div>

      {mode === "assistant" && draftUseGlobal !== savedUseGlobal ? (
        <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
          {draftUseGlobal ? t("assistants.memory.switch_to_global_after_save") : t("assistants.memory.switch_to_assistant_after_save")}
        </p>
      ) : null}

      {mode === "assistant" && scope === "global" && privateFileCount > 0 ? (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted/60 px-3 py-2 text-xs text-muted-foreground">
          <span>{t("assistants.memory.private_preserved", { count: privateFileCount })}</span>
          <Button type="button" size="sm" variant="secondary" onClick={openSelfMigrate}>
            {t("assistants.memory.migrate_action")}
          </Button>
        </div>
      ) : null}

      {dream?.enabled ? (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-muted/60 px-3 py-2">
          <div className="min-w-0 space-y-0.5">
            <p className="flex items-center gap-1.5 text-sm font-medium">
              <Moon className="size-4" />
              {t("assistants.memory.dream_title")}
            </p>
            <p className="text-xs break-words text-muted-foreground">{dreamSummary(dream, t)}</p>
          </div>
          <div className="flex items-center gap-1.5">
            {!dream.running && dream.canBackfill !== false ? (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                disabled={busy}
                onClick={() => setBackfillOpen(true)}
              >
                <History className="size-3.5" />
                {t("assistants.memory.backfill_action")}
              </Button>
            ) : null}
            {dream.running ? (
              <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void stopDream()}>
                <Square className="size-3.5" />
                {t("assistants.memory.dream_stop")}
              </Button>
            ) : (
              <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void dreamNow()}>
                {dream.lastError ? t("assistants.memory.dream_retry") : t("assistants.memory.dream_now")}
              </Button>
            )}
          </div>
        </div>
      ) : null}

      {creating ? (
        <div className="space-y-2 rounded-lg border p-3">
          <Input
            value={creating.path}
            onChange={(event) => setCreating({ ...creating, path: event.target.value })}
            placeholder="/topics/food.md"
            aria-label={t("assistants.memory.path")}
          />
          <Textarea
            value={creating.content}
            onChange={(event) => setCreating({ ...creating, content: event.target.value })}
            placeholder={t("assistants.memory.add_placeholder")}
            rows={6}
            className="font-mono text-xs"
          />
          <div className="flex gap-2">
            <Button type="button" size="sm" disabled={busy || !creating.path.trim()} onClick={() => void create()}>
              {busy ? t("common.saving") : t("common.save")}
            </Button>
            <Button type="button" size="sm" variant="outline" onClick={() => setCreating(null)}>
              {t("common.cancel")}
            </Button>
          </div>
        </div>
      ) : null}

      {data && files.length === 0 ? <p className="text-sm text-muted-foreground">{t("assistants.memory.empty")}</p> : null}

      <ul className="space-y-2">
        {files.map((file, index) => {
          const directory = memoryDirectoryOf(file.path);
          const showDirectory = index === 0 || memoryDirectoryOf(files[index - 1].path) !== directory;
          const detail = expanded[file.path];
          const isEditing = editing?.path === file.path;
          return (
            <React.Fragment key={file.path}>
              {showDirectory ? (
                <li className="pt-1 text-xs font-medium text-muted-foreground">
                  {directory === "/" ? t("assistants.memory.section_root") : directory}
                </li>
              ) : null}
              <li className="rounded-lg border p-3">
                <div className="flex items-start gap-2">
                  <button
                    type="button"
                    className="flex min-w-0 flex-1 items-start gap-1.5 text-left"
                    title={t("assistants.memory.expand")}
                    onClick={() => toggle(file.path)}
                  >
                    {detail ? <ChevronDown className="mt-0.5 size-4 shrink-0" /> : <ChevronRight className="mt-0.5 size-4 shrink-0" />}
                    <span className="min-w-0 space-y-0.5">
                      <span className="block truncate text-sm font-medium">{file.title}</span>
                      <span className="block truncate font-mono text-xs text-muted-foreground">{file.path}</span>
                      {file.description ? (
                        <span className="block text-xs break-words text-muted-foreground">{file.description}</span>
                      ) : null}
                      <span className="block text-xs text-muted-foreground">
                        {t("assistants.memory.updated_at", { time: new Date(file.updatedAt).toLocaleString() })}
                      </span>
                    </span>
                  </button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label={t("assistants.memory.edit")}
                    title={t("assistants.memory.edit")}
                    onClick={() => void startEdit(file.path)}
                  >
                    <Pencil className="size-4" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label={t("common.delete")}
                    title={t("common.delete")}
                    onClick={() => setDeleteTarget(file)}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </div>
                {isEditing ? (
                  <div className="mt-2 space-y-2">
                    <p className="text-xs text-muted-foreground">{t("assistants.memory.edit_warning")}</p>
                    <Textarea
                      value={editing.content}
                      onChange={(event) => setEditing({ ...editing, content: event.target.value })}
                      rows={12}
                      className="font-mono text-xs"
                      autoFocus
                    />
                    <div className="flex gap-2">
                      <Button type="button" size="sm" disabled={busy} onClick={() => void save()}>
                        {busy ? t("common.saving") : t("common.save")}
                      </Button>
                      <Button type="button" size="sm" variant="outline" onClick={() => setEditing(null)}>
                        {t("common.cancel")}
                      </Button>
                    </div>
                  </div>
                ) : detail === "loading" ? (
                  <p className="mt-2 text-xs text-muted-foreground">{t("common.loading")}</p>
                ) : detail ? (
                  <pre className="mt-2 max-h-96 overflow-auto rounded-md bg-muted p-2 font-mono text-xs break-words whitespace-pre-wrap">
                    {detail.content}
                  </pre>
                ) : null}
              </li>
            </React.Fragment>
          );
        })}
      </ul>

      <ConfirmDeleteDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
        title={t("assistants.memory.delete_title")}
        description={
          deleteTarget
            ? scope === "global"
              ? `${deleteTarget.path} — ${t("assistants.memory.global_delete_warning")}`
              : deleteTarget.path
            : ""
        }
        onConfirm={remove}
        loading={busy}
      />

      {/* Pick source assistant on Global Memory page */}
      <Dialog open={pickerOpen} onOpenChange={setPickerOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("assistants.memory.import_pick_title")}</DialogTitle>
            <DialogDescription>{t("assistants.memory.migrate_preserve_source")}</DialogDescription>
          </DialogHeader>
          <div className="max-h-72 space-y-1 overflow-auto">
            {importableAssistants.map((item) => (
              <button
                key={item.id}
                type="button"
                className="flex w-full items-center justify-between rounded-md border px-3 py-2 text-left text-sm hover:bg-accent"
                onClick={() => {
                  setPickerOpen(false);
                  setMigrateSource({
                    previewUrl: `memories/global/import/${item.id}/preview`,
                    executeUrl: `memories/global/import/${item.id}`,
                    label: item.name || t("common.unnamed"),
                  });
                }}
              >
                <span className="truncate font-medium">{item.name || t("common.unnamed")}</span>
                <Badge variant="secondary">{item.fileCount}</Badge>
              </button>
            ))}
          </div>
        </DialogContent>
      </Dialog>

      {/* Migration preview & conflict strategy dialog */}
      <Dialog
        open={migrateSource !== null}
        onOpenChange={(open) => {
          if (!open && !busy) {
            setMigrateSource(null);
            setMigratePreview(null);
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("assistants.memory.migrate_dialog_title")}</DialogTitle>
            <DialogDescription>
              {migrateSource ? t("assistants.memory.migrate_dialog_source", { name: migrateSource.label }) : ""}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 text-sm">
            {migrateLoading || !migratePreview ? (
              <p className="text-muted-foreground">{t("common.loading")}</p>
            ) : migratePreview.sourceCount === 0 ? (
              <p className="text-muted-foreground">{t("assistants.memory.migrate_empty_source")}</p>
            ) : (
              <>
                <p>
                  {t("assistants.memory.migrate_summary", {
                    total: migratePreview.sourceCount,
                    added: migratePreview.newPaths.length,
                    identical: migratePreview.identicalPaths.length,
                    conflicts: migratePreview.conflicts.length,
                  })}
                </p>

                {migratePreview.conflicts.length > 0 ? (
                  <div className="space-y-2 rounded-md border p-3">
                    <p className="text-xs font-medium text-muted-foreground">
                      {t("assistants.memory.migrate_conflicts_label")}
                    </p>
                    <p className="font-mono text-xs text-muted-foreground">
                      {migratePreview.conflicts
                        .slice(0, 6)
                        .map((c) => c.path)
                        .join(", ")}
                      {migratePreview.conflicts.length > 6 ? ` +${migratePreview.conflicts.length - 6}` : ""}
                    </p>
                    <div className="space-y-1.5 pt-1">
                      {(["merge", "skip", "overwrite"] as const).map((opt) => (
                        <label key={opt} className="flex cursor-pointer items-start gap-2 text-xs">
                          <input
                            type="radio"
                            name="memory-migrate-strategy"
                            className="mt-0.5"
                            checked={migrateStrategy === opt}
                            onChange={() => setMigrateStrategy(opt)}
                          />
                          <span>
                            <span className={cn("block font-medium", opt === "overwrite" && migrateStrategy === "overwrite" && "text-destructive")}>
                              {t(`assistants.memory.migrate_strategy_${opt}`)}
                            </span>
                            <span className="block text-muted-foreground">
                              {t(`assistants.memory.migrate_strategy_${opt}_desc`)}
                            </span>
                          </span>
                        </label>
                      ))}
                    </div>
                    {migrateStrategy === "merge" && oversizedOnMerge.length > 0 ? (
                      <p className="text-xs text-destructive">
                        {t("assistants.memory.migrate_oversized_warn", { paths: oversizedOnMerge.join(", ") })}
                      </p>
                    ) : null}
                  </div>
                ) : null}

                <label className="flex cursor-pointer items-start gap-2 text-xs">
                  <Checkbox
                    checked={migrateSyncMarks}
                    onCheckedChange={(v) => setMigrateSyncMarks(v === true)}
                  />
                  <span>
                    <span className="block font-medium">{t("assistants.memory.migrate_sync_marks")}</span>
                    <span className="block text-muted-foreground">{t("assistants.memory.migrate_sync_marks_desc")}</span>
                  </span>
                </label>

                <p className="text-xs text-muted-foreground">{t("assistants.memory.migrate_preserve_source")}</p>
              </>
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => setMigrateSource(null)}>
              {t("common.cancel")}
            </Button>
            <Button type="button" disabled={!canSubmitMigration} onClick={() => void runMigration()}>
              {busy ? t("common.saving") : t("assistants.memory.migrate_confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Backfill dialog */}
      <Dialog open={backfillOpen} onOpenChange={setBackfillOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("assistants.memory.backfill_title")}</DialogTitle>
            <DialogDescription>
              {t("assistants.memory.backfill_desc", { max: backfillCount?.maxPerRun ?? 20 })}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 text-sm">
            <div className="flex gap-2">
              {([7, 30] as const).map((days) => (
                <Button
                  key={days}
                  type="button"
                  size="sm"
                  variant={backfillDays === days ? "default" : "outline"}
                  onClick={() => setBackfillDays(days)}
                >
                  {t("assistants.memory.backfill_days", { days })}
                </Button>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">
              {backfillCounting || !backfillCount
                ? t("common.loading")
                : backfillCount.candidates === 0
                  ? t("assistants.memory.backfill_none")
                  : t("assistants.memory.backfill_found", {
                      total: backfillCount.candidates,
                      limit: Math.min(backfillCount.candidates, backfillCount.maxPerRun),
                    })}
            </p>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => setBackfillOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              disabled={busy || backfillCounting || (backfillCount?.candidates ?? 0) === 0}
              onClick={() => void runBackfill()}
            >
              {t("assistants.memory.backfill_start")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}

/** One line about dreaming: running, last failure, pending turns and the last run, or what it does. */
export function dreamSummary(dream: MemoryDreamStatus, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (dream.running) return t("assistants.memory.dreaming");
  if (dream.lastError) return t("assistants.memory.dream_failed", { error: dream.lastError });
  const parts: string[] = [];
  if (dream.pendingTurns > 0) parts.push(t("assistants.memory.dream_pending", { count: dream.pendingTurns }));
  if (dream.lastFinishedAt) {
    const changed = (dream.lastChangedPaths?.length ?? 0) + (dream.lastDeletedPaths?.length ?? 0);
    parts.push(
      changed > 0
        ? t("assistants.memory.dream_last_changed", { count: changed })
        : t("assistants.memory.dream_last_nothing"),
    );
  }
  if (parts.length > 0) return parts.join(" · ");
  return dream.automatic ? t("assistants.memory.dream_hint") : t("assistants.memory.dream_manual_hint");
}
