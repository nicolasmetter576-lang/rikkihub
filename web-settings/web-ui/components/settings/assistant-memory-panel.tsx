import * as React from "react";
import { ChevronDown, ChevronRight, Moon, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
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
}

export interface MemoryFilesResponse {
  scope: "assistant" | "global";
  maxBytes: number;
  files: MemoryFileSummary[];
  dream?: MemoryDreamStatus | null;
}

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
  const { t } = useTranslation("settings");
  const [data, setData] = React.useState<MemoryFilesResponse | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [expanded, setExpanded] = React.useState<Record<string, MemoryFileDetail | "loading">>({});
  const [editing, setEditing] = React.useState<Editing>(null);
  const [creating, setCreating] = React.useState<{ path: string; content: string } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [deleteTarget, setDeleteTarget] = React.useState<MemoryFileSummary | null>(null);
  const requestIdRef = React.useRef(0);
  const base = `assistants/${assistantId}/memories`;
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
  }, [load, savedUseGlobal]);

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

  const scope = data?.scope ?? (savedUseGlobal ? "global" : "assistant");
  const files = data?.files ?? [];
  const dream = data?.dream ?? null;

  return (
    <section className="space-y-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="space-y-0.5">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold">{t("assistants.memory.title")}</h2>
            <Badge variant="secondary">
              {scope === "global" ? t("assistants.memory.scope_global") : t("assistants.memory.scope_assistant")}
            </Badge>
          </div>
          <p className="text-xs text-muted-foreground">
            {scope === "global" ? t("assistants.memory.scope_global_hint") : t("assistants.memory.scope_assistant_hint")}
          </p>
        </div>
        <div className="flex items-center gap-1">
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

      {draftUseGlobal !== savedUseGlobal ? (
        <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
          {draftUseGlobal ? t("assistants.memory.switch_to_global_after_save") : t("assistants.memory.switch_to_assistant_after_save")}
        </p>
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
          <Button type="button" size="sm" variant="outline" disabled={busy || dream.running} onClick={() => void dreamNow()}>
            {dream.running
              ? t("assistants.memory.dreaming")
              : dream.lastError
                ? t("assistants.memory.dream_retry")
                : t("assistants.memory.dream_now")}
          </Button>
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
        description={deleteTarget ? deleteTarget.path : ""}
        onConfirm={remove}
        loading={busy}
      />
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
