import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import {
  ConfirmDeleteDialog,
  CreateNameDialog,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import {
  joinWorkspacePath,
  summarizeBatchDeleteResult,
  summarizeWorkspaceTransfer,
  validateWorkspaceBatchPaths,
  workspaceApi,
  workspaceTransferAllowed,
  workspaceTransferErrorKey,
} from "@overlay/lib/workspace";
import { WorkspaceExtractDialog } from "@overlay/components/workspace/workspace-extract-dialog";
import {
  WorkspaceCreateFileDialog,
  reportFileCreationOutcome,
} from "@overlay/components/workspace/workspace-create-file-dialog";
import { WorkspaceMoveDialog } from "@overlay/components/workspace/workspace-move-dialog";
import { createFileAndOpen, snapshotFileCreation, type FileCreationSnapshot } from "@overlay/lib/workspace-file-create";
import type {
  WorkspaceFileContent,
  WorkspaceFileEntry,
  WorkspaceStorageArea,
  WorkspaceTransferMode,
} from "@overlay/types/workspace";

/** 移动/复制对话框的发起快照；onDone 拿到失败项路径（多选时用来保留选择） */
interface PendingTransfer {
  entries: WorkspaceFileEntry[];
  mode: WorkspaceTransferMode;
  onDone: ((failedPaths: string[]) => void) | null;
}

/**
 * 新建文件 / 重命名 / 移动 / 删除 对话框，文件浏览器与聊天侧文件面板共用。
 * 新建与重命名始终落在 FILES 区当前目录；移动/复制只在 FILES 区之间（可以到另一个工作区）；
 * 删除走调用方传入的 area。
 */
export function useWorkspaceFileDialogs({
  workspaceId,
  area,
  path,
  reload,
  onCreated,
}: {
  workspaceId: string;
  area: WorkspaceStorageArea;
  path: string;
  reload: () => Promise<void> | void;
  onCreated: (content: WorkspaceFileContent, snapshot: FileCreationSnapshot) => void;
}) {
  const { t } = useTranslation("workspace");
  const [createFileOpen, setCreateFileOpen] = React.useState(false);
  const [initialExtension, setInitialExtension] = React.useState("md");
  const [creating, setCreating] = React.useState(false);
  const [renameTarget, setRenameTarget] = React.useState<WorkspaceFileEntry | null>(null);
  const [renaming, setRenaming] = React.useState(false);
  const [transfer, setTransfer] = React.useState<PendingTransfer | null>(null);
  const [transferring, setTransferring] = React.useState(false);
  const [deleteTarget, setDeleteTarget] = React.useState<WorkspaceFileEntry | null>(null);
  const [deleting, setDeleting] = React.useState(false);
  const [batchTargets, setBatchTargets] = React.useState<WorkspaceFileEntry[] | null>(null);
  const [batchDeleting, setBatchDeleting] = React.useState(false);
  const batchDoneRef = React.useRef<((failedPaths: string[]) => void) | null>(null);
  const [extractTarget, setExtractTarget] = React.useState<WorkspaceFileEntry | null>(null);

  const currentView = React.useRef({ workspaceId, area, path, reload, onCreated });
  currentView.current = { workspaceId, area, path, reload, onCreated };
  const mounted = React.useRef(true);
  React.useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const openCreateFile = React.useCallback(() => {
    if (currentView.current.area === "FILES") {
      setInitialExtension("md");
      setCreateFileOpen(true);
    }
  }, []);
  const openCreateDirectory = React.useCallback(() => {
    if (currentView.current.area === "FILES") {
      setInitialExtension("");
      setCreateFileOpen(true);
    }
  }, []);
  const openRename = React.useCallback((entry: WorkspaceFileEntry) => setRenameTarget(entry), []);
  const openMove = React.useCallback((entry: WorkspaceFileEntry) => {
    if (currentView.current.area === "FILES") setTransfer({ entries: [entry], mode: "move", onDone: null });
  }, []);
  const openCopy = React.useCallback((entry: WorkspaceFileEntry) => {
    if (currentView.current.area === "FILES") setTransfer({ entries: [entry], mode: "copy", onDone: null });
  }, []);
  const openDelete = React.useCallback((entry: WorkspaceFileEntry) => setDeleteTarget(entry), []);
  /** 多选移动（只在 FILES 区）；onDone 拿到移动失败的路径（用于保留选择）。 */
  const openBatchMove = React.useCallback(
    (entries: WorkspaceFileEntry[], onDone?: (failedPaths: string[]) => void) => {
      if (currentView.current.area !== "FILES" || entries.length === 0) return;
      setTransfer({ entries, mode: "move", onDone: onDone ?? null });
    },
    [],
  );
  /** 多选复制（只在 FILES 区）；onDone 同上。 */
  const openBatchCopy = React.useCallback(
    (entries: WorkspaceFileEntry[], onDone?: (failedPaths: string[]) => void) => {
      if (currentView.current.area !== "FILES" || entries.length === 0) return;
      setTransfer({ entries, mode: "copy", onDone: onDone ?? null });
    },
    [],
  );
  /** 解压（只在 FILES 区）。 */
  const openExtract = React.useCallback((entry: WorkspaceFileEntry) => {
    if (currentView.current.area === "FILES" && !entry.isDirectory) setExtractTarget(entry);
  }, []);
  /**
   * 打开批量删除确认框；onDone 拿到仍失败的路径集（用于保留选择）。
   */
  const openBatchDelete = React.useCallback(
    (entries: WorkspaceFileEntry[], onDone?: (failedPaths: string[]) => void) => {
      batchDoneRef.current = onDone ?? null;
      setBatchTargets(entries);
    },
    [],
  );

  const dialogs = (
    <>
      <WorkspaceCreateFileDialog
        open={createFileOpen && area === "FILES"}
        onOpenChange={setCreateFileOpen}
        parentPath={path}
        initialExtension={initialExtension}
        loading={creating}
        onSubmit={async (name, extension) => {
          if (area !== "FILES") return;
          const snapshot = snapshotFileCreation(workspaceId, "FILES", path, name, extension);
          const isCurrent = () => mounted.current &&
            currentView.current.workspaceId === snapshot.workspaceId &&
            currentView.current.area === snapshot.area &&
            currentView.current.path === snapshot.parentPath;
          setCreating(true);
          try {
            const outcome = await createFileAndOpen({
              create: () => snapshot.isDirectory
                ? workspaceApi.createDirectory(snapshot.workspaceId, snapshot.path)
                : workspaceApi.createFile(snapshot.workspaceId, snapshot.path),
              refresh: async () => {
                const result = await workspaceApi.listFiles(snapshot.workspaceId, "FILES", snapshot.parentPath);
                if (isCurrent()) await currentView.current.reload();
                return result.entries.some((entry) => entry.path === snapshot.path);
              },
              open: snapshot.isDirectory ? undefined : async () => {
                if (!isCurrent()) return false;
                // This particular newly created file is text, regardless of its suffix.
                const content = await workspaceApi.readFile(snapshot.workspaceId, "FILES", snapshot.path);
                if (!isCurrent()) return false;
                currentView.current.onCreated(content, snapshot);
              },
            });
            // Close for success OR unknown transport result: never repeat the creation
            // just because refreshing/opening failed. Explicit conflicts remain editable.
            if (outcome.status !== "failed" && mounted.current) setCreateFileOpen(false);
            reportFileCreationOutcome(outcome, (key) => t(key), snapshot.isDirectory);
          } finally {
            if (mounted.current) setCreating(false);
          }
        }}
      />

      <CreateNameDialog
        open={renameTarget != null}
        onOpenChange={(open) => {
          if (!open) setRenameTarget(null);
        }}
        title={t("files.rename")}
        placeholder={t("files.file_placeholder")}
        initialName={renameTarget?.name}
        submitLabel={t("files.confirm")}
        submittingLabel={t("files.saving")}
        footerStart={
          <Button
            type="button"
            variant="ghost"
            className="text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => {
              if (!renameTarget) return;
              setDeleteTarget(renameTarget);
              setRenameTarget(null);
            }}
          >
            {t("files.delete")}
          </Button>
        }
        loading={renaming}
        onSubmit={async (name) => {
          if (!renameTarget) return;
          const newName = name.trim();
          if (!newName || newName === renameTarget.name) {
            setRenameTarget(null);
            return;
          }
          setRenaming(true);
          try {
            await workspaceApi.moveFile(
              workspaceId,
              renameTarget.path,
              joinWorkspacePath(path, newName),
            );
            toast.success(t("files.renamed"));
            setRenameTarget(null);
            await reload();
          } catch (error) {
            reportSettingsError(error, t("files.rename_failed"));
          } finally {
            setRenaming(false);
          }
        }}
      />

      <WorkspaceMoveDialog
        workspaceId={workspaceId}
        entries={area === "FILES" ? transfer?.entries ?? null : null}
        mode={transfer?.mode ?? "move"}
        loading={transferring}
        onOpenChange={(open) => {
          if (!open) setTransfer(null);
        }}
        onSubmit={async (targetWorkspaceId, destinationDir) => {
          const pending = transfer;
          if (!pending) return;
          const sameWorkspace = targetWorkspaceId === workspaceId;
          const paths = pending.entries
            .filter((entry) => workspaceTransferAllowed(entry.path, sameWorkspace, destinationDir, pending.mode))
            .map((entry) => entry.path);
          if (paths.length === 0) return;
          setTransferring(true);
          try {
            const response = await workspaceApi.transferFiles(
              workspaceId, paths, targetWorkspaceId, destinationDir, pending.mode,
            );
            const summary = summarizeWorkspaceTransfer(response);
            const extra = [
              summary.renamed > 0 ? t("files.transfer_renamed", { count: summary.renamed }) : "",
              summary.skippedLinks > 0 ? t("files.transfer_skipped_links", { count: summary.skippedLinks }) : "",
            ].join("");
            if (summary.failures.length === 0) {
              toast.success(
                t(pending.mode === "move" ? "files.transfer_moved" : "files.transfer_copied", {
                  count: summary.succeeded,
                }) + extra,
              );
            } else {
              const reasons = summary.failures
                .slice(0, 3)
                .map((item) => `${item.source.split("/").pop()}: ${t(workspaceTransferErrorKey(item.errorCode))}`)
                .join("; ");
              toast.error(
                t(pending.mode === "move" ? "files.transfer_moved_partial" : "files.transfer_copied_partial", {
                  done: summary.succeeded,
                  failed: summary.failures.length,
                }) + extra,
                { description: reasons },
              );
            }
            pending.onDone?.(summary.failures.map((item) => item.source));
            setTransfer(null);
            await reload();
          } catch (error) {
            reportSettingsError(error, t(pending.mode === "move" ? "files.move_failed" : "files.copy_failed_transfer"));
          } finally {
            setTransferring(false);
          }
        }}
      />

      <WorkspaceExtractDialog
        workspaceId={workspaceId}
        entry={area === "FILES" ? extractTarget : null}
        onOpenChange={(open) => {
          if (!open) setExtractTarget(null);
        }}
        onExtracted={() => reload()}
      />

      <ConfirmDeleteDialog
        open={deleteTarget != null}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
        title={t("files.delete")}
        description={t("files.delete_confirm", { name: deleteTarget?.name ?? "" })}
        loading={deleting}
        onConfirm={async () => {
          if (!deleteTarget) return;
          setDeleting(true);
          try {
            await workspaceApi.deleteFile(
              workspaceId,
              area,
              deleteTarget.path,
              deleteTarget.isDirectory,
            );
            toast.success(t("files.deleted"));
            setDeleteTarget(null);
            await reload();
          } catch (error) {
            reportSettingsError(error, t("files.delete_failed"));
          } finally {
            setDeleting(false);
          }
        }}
      />

      <Dialog
        open={batchTargets != null}
        onOpenChange={(open) => {
          if (!open && !batchDeleting) setBatchTargets(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {t("files.batch_delete_title", { count: batchTargets?.length ?? 0 })}
            </DialogTitle>
            <DialogDescription>
              {t("files.batch_delete_desc", { count: batchTargets?.length ?? 0 })}
            </DialogDescription>
          </DialogHeader>
          <ul className="max-h-48 overflow-auto rounded-md border px-3 py-2 text-xs">
            {(batchTargets ?? []).map((entry) => (
              <li key={entry.path} className="truncate font-mono">
                {entry.path}
                {entry.isDirectory ? "/" : ""}
              </li>
            ))}
          </ul>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={batchDeleting}
              onClick={() => setBatchTargets(null)}
            >
              {t("files.cancel")}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={
                batchDeleting ||
                validateWorkspaceBatchPaths((batchTargets ?? []).map((entry) => entry.path)) != null
              }
              onClick={async () => {
                const targets = batchTargets;
                if (!targets?.length) return;
                setBatchDeleting(true);
                try {
                  const result = await workspaceApi.batchDeleteFiles(
                    workspaceId,
                    area,
                    targets.map((entry) => entry.path),
                  );
                  const summary = summarizeBatchDeleteResult(result);
                  if (summary.outcome === "all") {
                    toast.success(t("files.batch_deleted", { count: summary.deletedCount }));
                  } else {
                    // partial / 全失败：如实报告，绝不显示一律成功
                    toast.error(
                      t("files.batch_deleted_partial", {
                        deleted: summary.deletedCount,
                        failed: summary.failedPaths.length,
                      }),
                    );
                    for (const failure of (result.failures ?? []).slice(0, 3)) {
                      toast.error(`${failure.path}: ${failure.error}`);
                    }
                  }
                  // 失败项回给调用方保留选择（方便修正后重试）
                  batchDoneRef.current?.(summary.failedPaths);
                  setBatchTargets(null);
                  await reload();
                } catch (error) {
                  reportSettingsError(error, t("files.delete_failed"));
                } finally {
                  setBatchDeleting(false);
                }
              }}
            >
              {batchDeleting
                ? t("files.deleting")
                : t("files.batch_delete_confirm", { count: batchTargets?.length ?? 0 })}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );

  return {
    dialogs,
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
    batchMoving: transferring,
    openExtract,
  };
}
