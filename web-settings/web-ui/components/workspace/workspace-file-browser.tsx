import { workspaceEditorErrorKey } from "@overlay/lib/workspace-editor-errors";
import { useWorkspaceEditorSession } from "@overlay/components/workspace/use-workspace-editor-session";
import * as React from "react";

import {
  ArrowUp,
  Copy,
  Download,
  FilePlus,
  FileText,
  Folder,
  FolderInput,
  FolderPlus,
  Image as ImageIcon,
  MoreHorizontal,
  PackageOpen,
  Pencil,
  RefreshCw,
  Trash2,
  Upload,
  CopyPlus,
} from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import {
  CreateNameDialog,
  SettingsSegmented,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
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
import { cn } from "~/lib/utils";
import { copyTextToClipboard } from "~/lib/clipboard";
import { useWorkspaceDragUpload } from "@overlay/components/workspace/use-workspace-drag-upload";
import { useWorkspaceFileDialogs } from "@overlay/components/workspace/workspace-file-dialogs";
import {
  useWorkspaceFileSelection,
  WorkspaceEntryCheckbox,
  WorkspaceSelectionBar,
} from "@overlay/components/workspace/workspace-file-selection";
import {
  detectWorkspaceFileKind,
  downloadWorkspaceArchive,
  formatWorkspaceBytes,
  isWorkspaceAbortError,
  isWorkspaceArchiveName,
  workspaceGuestPath,
  joinWorkspacePath,
  parentWorkspacePath,
  pruneWorkspaceSelection,
  saveBlobAs,
  sortWorkspaceEntries,
  workspaceApi,
  workspaceFileUrl,
} from "@overlay/lib/workspace";
import { WorkspaceTextEditor } from "@overlay/components/workspace/workspace-text-editor";
import type {
  WorkspaceFileContent,
  WorkspaceFileEntry,
  WorkspaceStorageArea,
} from "@overlay/types/workspace";

export function WorkspaceFileBrowser({ workspaceId }: { workspaceId: string }) {
  const { t } = useTranslation("workspace");
  const [area, setArea] = React.useState<WorkspaceStorageArea>("FILES");
  const [path, setPath] = React.useState("");
  const [entries, setEntries] = React.useState<WorkspaceFileEntry[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [mkdirOpen, setMkdirOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const [editor, setEditor] = React.useState<WorkspaceFileContent | null>(null);
  const { session, requestLeave, notice, guard } = useWorkspaceEditorSession((path, source, revision) => workspaceApi.writeFile(workspaceId, path, source, revision));
  const editorDraft = session.source;
  const setEditorDraft = (value: string) => session.change(value);
  const [saving, setSaving] = React.useState(false);
  const [previewUrl, setPreviewUrl] = React.useState<string | null>(null);
  const [batchBusy, setBatchBusy] = React.useState(false);

  const load = React.useCallback(async () => {
    setLoading(true);
    try {
      const result = await workspaceApi.listFiles(workspaceId, area, path);
      setEntries(result.entries);
      // 刷新后修剪已不存在的项；批删失败保留等语义由调用方维护
      selection.setSelected((prev) => pruneWorkspaceSelection(prev, result.entries));
    } catch (error) {
      toast.error(t(workspaceEditorErrorKey(error, "files.load_failed")));
    } finally {
      setLoading(false);
    }
    // selection.setSelected 是稳定引用
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [area, path, t, workspaceId]);

  React.useEffect(() => {
    void load();
  }, [load]);

  const openEntry = async (entry: WorkspaceFileEntry) => {
    if (entry.isDirectory) {
      setPath(entry.path);
      return;
    }
    const kind = detectWorkspaceFileKind(entry.name);
    if (kind === "text") {
      try {
        const content = await workspaceApi.readFile(workspaceId, area, entry.path);
        if (session.load(content)) setEditor(content);
      } catch (error) {
        toast.error(t(workspaceEditorErrorKey(error, "files.load_failed")));
      }
      return;
    }
    if (kind === "image") {
      setPreviewUrl(workspaceFileUrl(workspaceId, area, entry.path));
      return;
    }
    window.open(workspaceFileUrl(workspaceId, area, entry.path), "_blank", "noopener,noreferrer");
  };

  // 上传对两个区都开放: 后端 upload 路由不限 area (LINUX 区只是文本只读)
  const { dragActive, uploadRef, dragHandlers, handleFileInputChange } = useWorkspaceDragUpload({
    workspaceId,
    area,
    path,
    enabled: true,
    onUploaded: load,
  });

  const {
    dialogs: fileDialogs,
    openCreateFile,
    openCreateDirectory,
    openRename,
    openMove,
    openCopy,
    openDelete,
    openBatchDelete,
    batchDeleting,
    openBatchMove,
    openBatchCopy,
    batchMoving,
    openExtract,
  } = useWorkspaceFileDialogs({
    workspaceId,
    area,
    path,
    reload: load,
    onCreated: (content) => {
      if (session.load(content)) setEditor(content);
    },
  });

  const busy = batchBusy || batchDeleting || batchMoving;
  const selection = useWorkspaceFileSelection(`${workspaceId}:${area}:${path}`);

  const sortedEntries = React.useMemo(() => sortWorkspaceEntries(entries), [entries]);

  const selectedEntries = React.useMemo(
    () => sortedEntries.filter((entry) => selection.selected.has(entry.path)),
    [selection.selected, sortedEntries],
  );

  const handleArchive = React.useCallback(async () => {
    const paths = [...selection.selected];
    if (paths.length === 0 || busy) return;
    setBatchBusy(true);
    try {
      const { blob, fileName } = await downloadWorkspaceArchive(workspaceId, area, paths);
      saveBlobAs(blob, fileName);
      toast.success(t("files.archive_done", { count: paths.length }));
    } catch (error) {
      if (isWorkspaceAbortError(error)) return;
      reportSettingsError(error, t("files.archive_failed"));
    } finally {
      setBatchBusy(false);
    }
  }, [area, busy, selection.selected, t, workspaceId]);

  const handleBatchDelete = React.useCallback(() => {
    if (busy || selectedEntries.length === 0) return;
    openBatchDelete(selectedEntries, (failedPaths) => {
      // 成功项移出选择，失败项保留以便重试
      selection.replace(failedPaths);
    });
  }, [busy, openBatchDelete, selectedEntries, selection]);

  const handleBatchMove = React.useCallback(() => {
    if (busy || selectedEntries.length === 0) return;
    openBatchMove(selectedEntries, (failedPaths) => selection.replace(failedPaths));
  }, [busy, openBatchMove, selectedEntries, selection]);

  const handleBatchCopy = React.useCallback(() => {
    if (busy || selectedEntries.length === 0) return;
    openBatchCopy(selectedEntries, (failedPaths) => selection.replace(failedPaths));
  }, [busy, openBatchCopy, selectedEntries, selection]);

  const copyPath = async (entry: WorkspaceFileEntry) => {
    try {
      await copyTextToClipboard(workspaceGuestPath(area, entry.path));
      toast.success(t("files.path_copied"));
    } catch {
      toast.error(t("files.copy_failed"));
    }
  };

  return (
    <div
      className={cn(
        "relative space-y-4",
        dragActive && "rounded-xl border-primary/40 bg-primary/5 ring-2 ring-primary/30",
      )}
      {...dragHandlers}
    >
      {dragActive ? (
        <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center rounded-xl border-2 border-dashed border-primary/50 bg-background/80 px-4 text-center text-sm font-medium text-primary">
          {t("files.drop_to_upload")}
        </div>
      ) : null}
      <div className="flex flex-wrap items-center gap-2">
        <SettingsSegmented
          value={area}
          onChange={(next) => {
            setArea(next);
            setPath("");
          }}
          options={[
            { value: "FILES", label: t("files.area_files") },
            { value: "LINUX", label: t("files.area_linux") },
          ]}
        />
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <Button type="button" size="sm" variant="outline" onClick={() => void load()}>
            <RefreshCw className="size-4" />
            {t("detail.refresh")}
          </Button>
          <Button type="button" size="sm" variant="outline" onClick={() => {
            // Preserve the pre-existing LINUX mkdir UI; no new creation capability there.
            if (area === "FILES") openCreateDirectory();
            else setMkdirOpen(true);
          }}>
            <FolderPlus className="size-4" />
            {t("files.new_folder")}
          </Button>
          {area === "FILES" ? (
            <Button type="button" size="sm" variant="outline" onClick={openCreateFile}>
              <FilePlus className="size-4" />
              {t("files.new_file")}
            </Button>
          ) : null}
          <Button
            type="button"
            size="sm"
            onClick={() => uploadRef.current?.click()}
          >
            <Upload className="size-4" />
            {t("files.upload")}
          </Button>
          <input
            ref={uploadRef}
            type="file"
            multiple
            className="hidden"
            onChange={handleFileInputChange}
          />
        </div>
      </div>

      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={!path}
          onClick={() => setPath(parentWorkspacePath(path))}
        >
          <ArrowUp className="size-4" />
          {t("files.up")}
        </Button>
        <span className="truncate font-mono">/{path}</span>
      </div>

      {!loading && entries.length > 0 ? (
        <WorkspaceSelectionBar
          entries={sortedEntries}
          selected={selection.selected}
          busy={loading}
          pending={busy}
          onToggleAll={(all) => selection.setAll(sortedEntries, all)}
          onClear={selection.clear}
          onArchive={() => void handleArchive()}
          onDelete={handleBatchDelete}
          onMove={area === "FILES" ? handleBatchMove : undefined}
          onCopy={area === "FILES" ? handleBatchCopy : undefined}
        />
      ) : null}

      <div className="overflow-hidden rounded-xl border">
        {loading ? (
          <p className="px-4 py-10 text-center text-sm text-muted-foreground">{t("list.loading")}</p>
        ) : entries.length === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-muted-foreground">{t("files.empty")}</p>
        ) : (
          <ul>
            {sortedEntries.map((entry) => {
              const kind = detectWorkspaceFileKind(entry.name);
              const Icon = entry.isDirectory ? Folder : kind === "image" ? ImageIcon : FileText;
              return (
                <li key={`${entry.path}:${entry.isDirectory ? "dir" : "file"}`} className="border-b last:border-b-0">
                  <div className="flex items-center gap-3 px-3 py-2">
                    <WorkspaceEntryCheckbox
                      path={entry.path}
                      name={entry.name}
                      selected={selection.selected.has(entry.path)}
                      disabled={busy}
                      onToggle={selection.toggle}
                    />
                    <button
                      type="button"
                      className="flex min-w-0 flex-1 items-center gap-3 text-left"
                      onClick={() => void openEntry(entry)}
                    >
                      <Icon className={cn("size-4 shrink-0", entry.isDirectory ? "text-primary" : "text-muted-foreground")} />
                      <span className="min-w-0 flex-1 truncate text-sm">{entry.name}</span>
                      <span className="hidden text-xs text-muted-foreground sm:inline">
                        {entry.isDirectory ? "" : formatWorkspaceBytes(entry.sizeBytes)}
                      </span>
                    </button>
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button type="button" size="icon-sm" variant="ghost">
                          <MoreHorizontal className="size-4" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => void openEntry(entry)}>
                          {entry.isDirectory ? t("files.open") : kind === "text" ? t("files.edit") : t("files.preview")}
                        </DropdownMenuItem>
                        {area === "FILES" ? (
                          <>
                            <DropdownMenuItem onClick={() => openRename(entry)}>
                              <Pencil className="size-4" />
                              {t("files.rename")}
                            </DropdownMenuItem>
                            <DropdownMenuItem onClick={() => openMove(entry)}>
                              <FolderInput className="size-4" />
                              {t("files.move")}
                            </DropdownMenuItem>
                            <DropdownMenuItem onClick={() => openCopy(entry)} data-testid="workspace-copy-to">
                              <CopyPlus className="size-4" />
                              {t("files.copy_to")}
                            </DropdownMenuItem>
                          </>
                        ) : null}
                        <DropdownMenuItem onClick={() => void copyPath(entry)}>
                          <Copy className="size-4" />
                          {t("files.copy_path")}
                        </DropdownMenuItem>
                        {area === "FILES" && !entry.isDirectory && isWorkspaceArchiveName(entry.name) ? (
                          <DropdownMenuItem onClick={() => openExtract(entry)}>
                            <PackageOpen className="size-4" />
                            {t("extract.menu")}
                          </DropdownMenuItem>
                        ) : null}
                        {!entry.isDirectory ? (
                          <DropdownMenuItem
                            onClick={() => {
                              window.open(
                                workspaceFileUrl(workspaceId, area, entry.path),
                                "_blank",
                                "noopener,noreferrer",
                              );
                            }}
                          >
                            <Download className="size-4" />
                            {t("files.download")}
                          </DropdownMenuItem>
                        ) : null}
                        <DropdownMenuItem variant="destructive" onClick={() => openDelete(entry)}>
                          <Trash2 className="size-4" />
                          {t("files.delete")}
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <CreateNameDialog
        open={mkdirOpen}
        onOpenChange={setMkdirOpen}
        title={t("files.new_folder")}
        placeholder={t("files.folder_placeholder")}
        loading={creating}
        onSubmit={async (name) => {
          const folderName = name.trim();
          if (!folderName) return;
          setCreating(true);
          try {
            await workspaceApi.mkdir(workspaceId, area, joinWorkspacePath(path, folderName));
            toast.success(t("files.folder_created"));
            setMkdirOpen(false);
            await load();
          } catch (error) {
            reportSettingsError(error, t("files.mkdir_failed"));
          } finally {
            setCreating(false);
          }
        }}
      />

      {fileDialogs}

      <Dialog
        open={editor != null}
        onOpenChange={(open) => {
          if (!open) requestLeave(() => { session.discard(); setEditor(null); });
        }}
      >
        <DialogContent className="max-h-[85svh] sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>{editor?.path}</DialogTitle>
          </DialogHeader>
          {editor && !editor.editable ? (
            <p className="text-xs text-muted-foreground">{t("files.linux_readonly")}</p>
          ) : null}
          {notice}
          {guard}
          <WorkspaceTextEditor
            key={`${workspaceId}:${editor?.area}:${editor?.path}`}
            value={editorDraft}
            onChange={setEditorDraft}
            readOnly={!session.editable || !session.revision}
            fileName={editor?.path}
            tuiqiaoKey={`${workspaceId}:${editor?.area}:${editor?.path}`}
            className="h-[55svh]"
            previewContext={
              editor
                ? {
                    workspaceId,
                    area: editor.area,
                    baseDir: parentWorkspacePath(editor.path),
                  }
                : null
            }
          />
          <DialogFooter>
            {editor?.editable ? (
              <Button
                type="button"
                disabled={saving || !session.canSave || !session.dirty}
                onClick={async () => {
                  if (!editor) return;
                  setSaving(true);
                  try {
                    if (!await session.save()) return;
                    toast.success(t("files.saved"));
                    setEditor(null);
                    await load();
                  } catch (error) {
                    reportSettingsError(error, t("files.save_failed"));
                  } finally {
                    setSaving(false);
                  }
                }}
              >
                {saving ? t("files.saving") : t("files.save")}
              </Button>
            ) : null}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={previewUrl != null}
        onOpenChange={(open) => {
          if (!open) setPreviewUrl(null);
        }}
      >
        <DialogContent className="sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>{t("files.preview")}</DialogTitle>
          </DialogHeader>
          {previewUrl ? (
            <img src={previewUrl} alt="" className="max-h-[70svh] w-full rounded-md object-contain" />
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}
