import { workspaceEditorErrorKey } from "@overlay/lib/workspace-editor-errors";
import { useWorkspaceEditorSession } from "@overlay/components/workspace/use-workspace-editor-session";
import { WorkspaceTextEditor } from "@overlay/components/workspace/workspace-text-editor";
import type { WorkspaceDeviceFileContent } from "@overlay/types/workspace";
import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import { copyTextToClipboard } from "~/lib/clipboard";
import {
  WorkspaceCreateFileDialog,
  reportFileCreationOutcome,
} from "@overlay/components/workspace/workspace-create-file-dialog";
import { createFileAndOpen, snapshotFileCreation } from "@overlay/lib/workspace-file-create";
import {
  useWorkspaceFileSelection,
  WorkspaceEntryCheckbox,
  WorkspaceSelectionBar,
} from "@overlay/components/workspace/workspace-file-selection";
import {
  isValidDeviceMountAlias,
  isValidDeviceSourceRelativePath,
  sortWorkspaceEntries,
  workspaceDeviceApi,
  workspaceDeviceFileUrl,
  workspaceDeviceGuestPath,
  parentWorkspacePath,
  formatWorkspaceBytes,
  joinWorkspacePath,
  pruneWorkspaceSelection,
  summarizeBatchDeleteResult,
  validateWorkspaceBatchPaths,
  downloadDeviceArchive,
  deviceImportTargetPath,
  nextDeviceImportRootName,
  isDeviceAbortError,
  saveBlobAs,
} from "@overlay/lib/workspace";
import type {
  WorkspaceDeviceMount,
  WorkspaceFileEntry,
} from "@overlay/types/workspace";

/**
 * 「设备目录」分页：连接管理（新增/启停/激活/解绑）+ 受控浏览 + 文件读写。
 *
 * - 授权（MANAGE_EXTERNAL_STORAGE）只能在 App 端发起：needsPermission 时
 *   Web 只渲染提示条，不提供任何授权按钮。
 * - mount 引用传给后端用 mountId（稳定）；文件面板的标题显示 alias。
 * - 任何宿主绝对路径都不会出现在 UI/日志里：后端只回 alias + 相对路径。
 */

const deviceQueryKeys = {
  availability: (id: string) => ["workspaces", id, "device", "availability"] as const,
  mounts: (id: string) => ["workspaces", id, "device", "mounts"] as const,
};

export function WorkspaceDevicePanel({ workspaceId }: { workspaceId: string }) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const [selectedMount, setSelectedMount] = React.useState<string | null>(null);
  const [editorTarget, setEditorTarget] = React.useState<{ mount: WorkspaceDeviceMount; file: WorkspaceDeviceFileContent } | null>(null);
  const editorTargetRef = React.useRef(editorTarget);
  editorTargetRef.current = editorTarget;
  const { session, requestLeave, notice, guard } = useWorkspaceEditorSession((path, source, revision) => {
    const target = editorTargetRef.current;
    if (!target) return Promise.reject(new Error("No opened device view"));
    return workspaceDeviceApi.writeFile(workspaceId, target.mount, path, source, revision);
  });
  const openDeviceEditor = (file: WorkspaceDeviceFileContent, mount: WorkspaceDeviceMount) => requestLeave(() => {
    session.discard();
    if (session.load(file)) setEditorTarget({ file, mount: { ...mount } });
  });
  const closeDeviceEditor = () => requestLeave(() => { session.discard(); setEditorTarget(null); });
  const [addOpen, setAddOpen] = React.useState(false);
  const [removeTarget, setRemoveTarget] = React.useState<WorkspaceDeviceMount | null>(null);

  const availabilityQuery = useQuery({
    queryKey: deviceQueryKeys.availability(workspaceId),
    queryFn: () => workspaceDeviceApi.availability(workspaceId),
    retry: false,
  });
  const availability = availabilityQuery.data ?? null;

  const mountsQuery = useQuery({
    queryKey: deviceQueryKeys.mounts(workspaceId),
    queryFn: () => workspaceDeviceApi.listMounts(workspaceId),
    enabled: availabilityQuery.isSuccess,
    retry: false,
  });
  const mounts = mountsQuery.data ?? [];

  const invalidateMounts = () =>
    queryClient.invalidateQueries({ queryKey: deviceQueryKeys.mounts(workspaceId) });

  const toggleMutation = useMutation({
    mutationFn: (mount: WorkspaceDeviceMount) =>
      workspaceDeviceApi.updateMount(workspaceId, mount.id, { enabled: !mount.enabled }),
    onSuccess: () => void invalidateMounts(),
    onError: (caught) => reportSettingsError(caught, t("device.toggle_failed")),
  });

  const deleteMutation = useMutation({
    mutationFn: (mountId: string) => workspaceDeviceApi.deleteMount(workspaceId, mountId),
    onSuccess: () => {
      toast.success(t("device.removed"));
      setRemoveTarget(null);
      void invalidateMounts();
    },
    onError: (caught) => reportSettingsError(caught, t("device.remove_failed")),
  });

  const selected = mounts.find((m) => m.id === selectedMount) ?? null;

  return (
    <div className="space-y-4">
      <div className="space-y-3 rounded-lg border p-4">
        <div className="flex items-center justify-between gap-3">
          <div className="space-y-1">
            <h2 className="text-sm font-medium">{t("device.title")}</h2>
            <p className="text-xs text-muted-foreground">{t("device.desc")}</p>
          </div>
          {availability ? (
            <Badge variant={availability.canBrowse ? "default" : "secondary"}>
              {t(`device.status.${availability.status}`)}
            </Badge>
          ) : null}
        </div>

        {availabilityQuery.isError ? (
          <p className="text-xs text-muted-foreground">{t("device.unavailable")}</p>
        ) : null}

        {availability && !availability.canBrowse ? (
          <div className="rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2">
            <p className="text-xs">
              {availability.needsPermission
                ? t("device.permission_required_app")
                : t(`device.status_hint.${availability.status}`)}
            </p>
          </div>
        ) : null}

        <div className="space-y-2">
          {mounts.map((mount) => (
            <div
              key={mount.id}
              className="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
            >
              <div className="min-w-0 space-y-0.5">
                <div className="flex items-center gap-2">
                  <p className="font-mono text-sm">{mount.alias}</p>
                  {mount.active ? (
                    <Badge variant="default">{t("device.badge_active")}</Badge>
                  ) : mount.enabled ? (
                    <Badge variant="outline">{t("device.badge_unavailable")}</Badge>
                  ) : (
                    <Badge variant="secondary">{t("device.badge_disabled")}</Badge>
                  )}
                </div>
                <p className="truncate font-mono text-xs text-muted-foreground">
                  {mount.sourceRelativePath} → {mount.guestPath}
                </p>
              </div>
              <div className="flex shrink-0 items-center gap-1.5">
                <Switch
                  checked={mount.enabled}
                  onCheckedChange={() => toggleMutation.mutate(mount)}
                  disabled={toggleMutation.isPending}
                  aria-label={t("device.toggle")}
                />
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => requestLeave(() => setSelectedMount(mount.id))}
                >
                  {t("device.browse_files")}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setRemoveTarget(mount)}
                >
                  {t("device.remove")}
                </Button>
              </div>
            </div>
          ))}
          {mountsQuery.isSuccess && mounts.length === 0 ? (
            <p className="text-xs text-muted-foreground">{t("device.empty")}</p>
          ) : null}
        </div>

        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={!availability?.canBrowse}
          onClick={() => setAddOpen(true)}
        >
          {t("device.add")}
        </Button>
        {!availability?.canBrowse ? (
          <p className="text-xs text-muted-foreground">{t("device.add_disabled_hint")}</p>
        ) : null}
      </div>

      {selected ? (
        <DeviceFileBrowser
          key={`${workspaceId}:${selected.id}:${selected.viewToken ?? ""}:${selected.active}`}
          workspaceId={workspaceId}
          mount={selected}
          onEdit={openDeviceEditor}
        />
      ) : null}

      {guard}
      <Dialog open={editorTarget !== null} onOpenChange={open => { if (!open) closeDeviceEditor(); }}>
        <DialogContent className="max-h-[85svh] sm:max-w-3xl">
          <DialogHeader><DialogTitle>{editorTarget?.file.path}</DialogTitle></DialogHeader>
          {notice}
          <WorkspaceTextEditor key={`${workspaceId}:${editorTarget?.mount.id}:${editorTarget?.mount.viewToken}:${editorTarget?.file.path}`} value={session.source} onChange={value => session.change(value)} readOnly={!session.editable || !session.revision} fileName={editorTarget?.file.path} tuiqiaoKey={`${workspaceId}:device:${editorTarget?.mount.id}:${editorTarget?.file.path}`} documentLimit="device" className="h-[55svh]" />
          <DialogFooter>
            <Button variant="outline" onClick={closeDeviceEditor}>{t("files.cancel")}</Button>
            <Button disabled={!session.canSave || !session.dirty || session.status === "saving"} onClick={async () => { if (await session.save()) { toast.success(t("files.saved")); setEditorTarget(null); } }}>{t("files.save")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AddDeviceMountDialog
        workspaceId={workspaceId}
        open={addOpen}
        onOpenChange={setAddOpen}
        onCreated={() => void invalidateMounts()}
      />

      <Dialog
        open={removeTarget != null}
        onOpenChange={(next) => {
          if (!next) setRemoveTarget(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("device.remove")}</DialogTitle>
            <DialogDescription>
              {t("device.remove_confirm", { alias: removeTarget?.alias ?? "" })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRemoveTarget(null)}>
              {t("files.cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => removeTarget && deleteMutation.mutate(removeTarget.id)}
            >
              {t("files.confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 新增连接：别名 + 受控浏览选择卷根相对路径                              */
/* ------------------------------------------------------------------ */

function AddDeviceMountDialog({
  workspaceId,
  open,
  onOpenChange,
  onCreated,
}: {
  workspaceId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [alias, setAlias] = React.useState("");
  const [browsePath, setBrowsePath] = React.useState("");

  const browseQuery = useQuery({
    queryKey: ["workspaces", workspaceId, "device", "browse", browsePath],
    queryFn: () => workspaceDeviceApi.browse(workspaceId, browsePath),
    enabled: open,
    retry: false,
  });

  const createMutation = useMutation({
    mutationFn: () =>
      workspaceDeviceApi.createMount(workspaceId, {
        alias: alias.trim(),
        sourceRelativePath: browsePath,
      }),
    onSuccess: () => {
      toast.success(t("device.created"));
      setAlias("");
      setBrowsePath("");
      onOpenChange(false);
      onCreated();
    },
    onError: (caught) => reportSettingsError(caught, t("device.create_failed")),
  });

  const aliasOk = isValidDeviceMountAlias(alias.trim());
  const sourceOk = isValidDeviceSourceRelativePath(browsePath);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("device.add")}</DialogTitle>
          <DialogDescription>{t("device.add_desc")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1">
            <p className="text-xs text-muted-foreground">{t("device.alias_label")}</p>
            <Input
              value={alias}
              onChange={(event) => setAlias(event.target.value)}
              placeholder={t("device.alias_placeholder")}
            />
            {alias.length > 0 && !aliasOk ? (
              <p className="text-xs text-destructive">{t("device.alias_invalid")}</p>
            ) : null}
          </div>
          <div className="space-y-1">
            <p className="text-xs text-muted-foreground">{t("device.source_label")}</p>
            <div className="rounded-md border">
              <div className="flex items-center justify-between gap-2 border-b px-3 py-1.5">
                <p className="truncate font-mono text-xs">
                  {browsePath || t("device.browse_root")}
                </p>
                {browsePath ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setBrowsePath(parentWorkspacePath(browsePath))}
                  >
                    {t("files.up")}
                  </Button>
                ) : null}
              </div>
              <div className="max-h-48 overflow-y-auto p-1">
                {browseQuery.isError ? (
                  <p className="px-2 py-1 text-xs text-destructive">
                    {t("device.browse_failed")}
                  </p>
                ) : (browseQuery.data?.entries ?? []).length === 0 ? (
                  <p className="px-2 py-1 text-xs text-muted-foreground">
                    {t("device.browse_empty")}
                  </p>
                ) : (
                  (browseQuery.data?.entries ?? []).map((entry) => (
                    <button
                      key={entry.relativePath}
                      type="button"
                      className="flex w-full items-center rounded px-2 py-1 text-left font-mono text-xs hover:bg-accent"
                      onClick={() => setBrowsePath(entry.relativePath)}
                    >
                      {entry.name}/
                    </button>
                  ))
                )}
              </div>
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("files.cancel")}
          </Button>
          <Button
            disabled={!aliasOk || !sourceOk || createMutation.isPending}
            onClick={() => createMutation.mutate()}
          >
            {t("files.confirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ */
/* 单个连接的文件浏览器（导航/上传/文件夹导入/新建/读写/多选/批删/ZIP）    */
/*                                                                     */
/* 直写宿主原目录（无 COW）：所有写/删都是高危操作，常驻警示条。          */
/* ------------------------------------------------------------------ */

function DeviceFileBrowser({
  workspaceId,
  mount: incomingMount,
  onEdit,
}: {
  workspaceId: string;
  mount: WorkspaceDeviceMount;
  onEdit: (file: WorkspaceDeviceFileContent, mount: WorkspaceDeviceMount) => void;
}) {
  // Parent remounts for every viewToken/activation change. All operations use this
  // current opened-view token; an in-flight operation can never redirect to a new source.
  const mount = React.useRef({ ...incomingMount }).current;
  const { t } = useTranslation("workspace");
  const [path, setPath] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<WorkspaceFileEntry | null>(null);
  const [mkdirOpen, setMkdirOpen] = React.useState(false);
  const [createFileOpen, setCreateFileOpen] = React.useState(false);
  const [creatingFile, setCreatingFile] = React.useState(false);
  const currentPathRef = React.useRef(path);
  currentPathRef.current = path;
  const mountedRef = React.useRef(true);
  React.useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);
  const [renameTarget, setRenameTarget] = React.useState<WorkspaceFileEntry | null>(null);
  const [renameName, setRenameName] = React.useState("");
  const [batchTargets, setBatchTargets] = React.useState<WorkspaceFileEntry[] | null>(null);
  const [batchBusy, setBatchBusy] = React.useState(false);
  const [folderImport, setFolderImport] = React.useState<{
    total: number;
    done: number;
    root: string;
  } | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const folderInputRef = React.useRef<HTMLInputElement | null>(null);
  const folderCancelRef = React.useRef<AbortController | null>(null);
  React.useEffect(() => () => { folderCancelRef.current?.abort(); }, []);

  const selection = useWorkspaceFileSelection(`${workspaceId}:device:${mount.id}:${path}`);

  const listQuery = useQuery({
    queryKey: ["workspaces", workspaceId, "device", "files", mount.id, mount.viewToken, path],
    queryFn: () => workspaceDeviceApi.listFiles(workspaceId, mount, path),
    enabled: mount.active,
    retry: false,
  });
  const entries = sortWorkspaceEntries(listQuery.data?.entries ?? []);

  const queryClient = useQueryClient();
  const invalidateFiles = () =>
    void queryClient.invalidateQueries({
      queryKey: ["workspaces", workspaceId, "device", "files", mount.id],
    });

  // 刷新后修剪已不存在的选中项
  React.useEffect(() => {
    if (listQuery.data?.entries) {
      selection.setSelected((prev) => pruneWorkspaceSelection(prev, listQuery.data.entries));
    }
    // selection.setSelected 是稳定引用
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listQuery.data]);

  const uploadMutation = useMutation({
    mutationFn: (file: File) => workspaceDeviceApi.upload(workspaceId, mount, path, file),
    onSuccess: () => {
      toast.success(t("files.uploaded"));
      invalidateFiles();
    },
    onError: (caught) => reportSettingsError(caught, t("files.upload_failed")),
  });

  const createFile = async (name: string, extension: string) => {
    const snapshot = snapshotFileCreation(workspaceId, "DEVICE", path, name, extension, mount);
    const view = snapshot.mount!;
    const isCurrent = () => mountedRef.current && currentPathRef.current === snapshot.parentPath;
    setCreatingFile(true);
    try {
      const outcome = await createFileAndOpen({
        create: () => snapshot.isDirectory
          ? workspaceDeviceApi.createDirectory(snapshot.workspaceId, view, snapshot.path)
          : workspaceDeviceApi.createFile(snapshot.workspaceId, view, snapshot.path),
        refresh: async () => {
          const result = await workspaceDeviceApi.listFiles(snapshot.workspaceId, view, snapshot.parentPath);
          queryClient.setQueryData(
            ["workspaces", snapshot.workspaceId, "device", "files", view.id, view.viewToken, snapshot.parentPath],
            result,
          );
          return result.entries.some((entry) => entry.path === snapshot.path);
        },
        open: snapshot.isDirectory ? undefined : async () => {
          if (!isCurrent()) return false;
          const content = await workspaceDeviceApi.readFile(snapshot.workspaceId, view, snapshot.path);
          if (!isCurrent()) return false;
          onEdit(content, { ...mount, ...view });
        },
      });
      if (outcome.status !== "failed" && mountedRef.current) {
        setCreateFileOpen(false);
        setMkdirOpen(false);
      }
      reportFileCreationOutcome(outcome, (key) => t(key), snapshot.isDirectory);
    } finally {
      if (mountedRef.current) setCreatingFile(false);
    }
  };

  const renameMutation = useMutation({
    mutationFn: (args: { source: string; target: string }) =>
      workspaceDeviceApi.moveFile(workspaceId, mount, args.source, args.target),
    onSuccess: () => {
      toast.success(t("files.renamed"));
      setRenameTarget(null);
      invalidateFiles();
    },
    onError: (caught) => reportSettingsError(caught, t("files.rename_failed")),
  });

  const deleteMutation = useMutation({
    mutationFn: (entry: WorkspaceFileEntry) =>
      workspaceDeviceApi.deleteFile(
        workspaceId,
        mount,
        entry.path,
        entry.isDirectory,
      ),
    onSuccess: () => {
      toast.success(t("files.deleted"));
      setDeleteTarget(null);
      invalidateFiles();
    },
    onError: (caught) => reportSettingsError(caught, t("files.delete_failed")),
  });

  const openForEdit = (entry: WorkspaceFileEntry) => {
    workspaceDeviceApi
      .readFile(workspaceId, mount, entry.path)
      .then((res) => onEdit(res, mount))
      .catch((caught) => toast.error(t(workspaceEditorErrorKey(caught, "chat_panel.read_failed"))));
  };

  /* ---------------- ZIP 下载 ---------------- */

  const handleArchive = React.useCallback(async () => {
    const paths = [...selection.selected];
    if (paths.length === 0 || batchBusy) return;
    setBatchBusy(true);
    try {
      const { blob, fileName } = await downloadDeviceArchive(workspaceId, mount, paths);
      saveBlobAs(blob, fileName);
      toast.success(t("files.archive_done", { count: paths.length }));
    } catch (error) {
      if (isDeviceAbortError(error)) return;
      reportSettingsError(error, t("files.archive_failed"));
    } finally {
      setBatchBusy(false);
    }
  }, [batchBusy, mount.id, selection.selected, t, workspaceId]);

  /* ---------------- 批量删除 ---------------- */

  const runBatchDelete = React.useCallback(async () => {
    const targets = batchTargets;
    if (!targets?.length || batchBusy) return;
    setBatchBusy(true);
    try {
      const result = await workspaceDeviceApi.batchDeleteFiles(
        workspaceId,
        mount,
        targets.map((entry) => entry.path),
      );
      const summary = summarizeBatchDeleteResult(result);
      if (summary.outcome === "all") {
        toast.success(t("files.batch_deleted", { count: summary.deletedCount }));
      } else {
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
      // 失败项保留选择，便于修正后重试
      selection.replace(summary.failedPaths);
      setBatchTargets(null);
      invalidateFiles();
    } catch (error) {
      reportSettingsError(error, t("files.delete_failed"));
    } finally {
      setBatchBusy(false);
    }
  }, [batchBusy, batchTargets, mount.id, selection, t, workspaceId]);

  /* ---------------- 文件夹导入 ---------------- */

  const handleFolderInput = React.useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(event.target.files ?? []);
      event.target.value = "";
      if (files.length === 0 || folderImport) return;

      const currentPath = path;
      // 所选目录的根名 = 第一个 relativePath 的第一段
      const firstRel = files[0].webkitRelativePath || files[0].name;
      const rootName = firstRel.split("/")[0] || "imported";
      // 目标目录里已有名字占坑，自动 -2/-3 递增，绝不覆盖既有目录
      const taken = new Set(entries.map((entry) => entry.name));
      const uniqueRoot = nextDeviceImportRootName(taken, rootName);
      const createdRoot = joinWorkspacePath(currentPath, uniqueRoot);

      // 受控路径规划：只用 relativePath 的目录结构 + basename
      const plans: { file: File; target: string }[] = [];
      let skipped = 0;
      for (const file of files) {
        const target = deviceImportTargetPath(
          createdRoot,
          file.webkitRelativePath || file.name,
          file.name,
        );
        if (!target) {
          skipped += 1;
          continue;
        }
        plans.push({ file, target });
      }
      if (plans.length === 0) {
        toast.error(t("files.folder_import_failed"));
        return;
      }

      // 唯一根目录只创建一次；取消时只回收这个本次新建的目录
      try {
        await workspaceDeviceApi.mkdir(workspaceId, mount, createdRoot);
      } catch (error) {
        reportSettingsError(error, t("files.mkdir_failed"));
        return;
      }

      const controller = new AbortController();
      folderCancelRef.current = controller;
      setFolderImport({ total: plans.length, done: 0, root: uniqueRoot });
      let uploaded = 0;
      let failed = 0;
      let cancelled = false;
      for (const plan of plans) {
        if (controller.signal.aborted) {
          cancelled = true;
          break;
        }
        try {
          await workspaceDeviceApi.importFile(
            workspaceId,
            mount,
            plan.target,
            plan.file,
            { signal: controller.signal },
          );
          uploaded += 1;
        } catch (error) {
          if (isDeviceAbortError(error)) {
            cancelled = true;
            break;
          }
          failed += 1;
        }
        setFolderImport({ total: plans.length, done: uploaded + failed, root: uniqueRoot });
      }
      folderCancelRef.current = null;
      setFolderImport(null);

      if (cancelled) {
        // Direct-write mode: stop further copies, retain files already imported. Never
        // delete an ordinary directory which another app may have changed meanwhile.
        toast.message(t("files.folder_import_cancelled"));
      } else if (failed > 0 || skipped > 0) {
        toast.error(
          t("files.folder_import_partial", {
            uploaded,
            failed: failed + skipped,
          }),
        );
      } else {
        toast.success(t("files.folder_import_done", { count: uploaded }));
      }
      invalidateFiles();
    },
    [entries, folderImport, mount.id, path, t, workspaceId],
  );

  if (!mount.active) {
    return (
      <div className="rounded-lg border p-4">
        <p className="text-xs text-muted-foreground">
          {t("device.files_inactive_hint", { alias: mount.alias })}
        </p>
      </div>
    );
  }

  const busy = batchBusy || folderImport != null;
  const batchPaths = (batchTargets ?? []).map((entry) => entry.path);

  return (
    <div className="space-y-3 rounded-lg border p-4">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <h2 className="text-sm font-medium">
            {t("device.files_title", { alias: mount.alias })}
          </h2>
          <p className="truncate font-mono text-xs text-muted-foreground">
            /{path}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          {path ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setPath(parentWorkspacePath(path))}
            >
              {t("files.up")}
            </Button>
          ) : null}
          <Button variant="outline" size="sm" onClick={() => setMkdirOpen(true)}>
            {t("files.new_folder")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setCreateFileOpen(true)}
          >
            {t("files.new_file")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => fileInputRef.current?.click()}
            disabled={busy || uploadMutation.isPending}
          >
            {t("files.upload")}
          </Button>
          {folderImport ? (
            <Button
              variant="outline"
              size="sm"
              onClick={() => folderCancelRef.current?.abort()}
            >
              {t("files.folder_import_cancel")}
            </Button>
          ) : (
            <Button
              variant="outline"
              size="sm"
              onClick={() => folderInputRef.current?.click()}
              disabled={busy}
            >
              {t("files.upload_folder")}
            </Button>
          )}
          <input
            ref={fileInputRef}
            type="file"
            className="hidden"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) uploadMutation.mutate(file);
              event.target.value = "";
            }}
          />
          <input
            ref={(el) => {
              folderInputRef.current = el;
              el?.setAttribute("webkitdirectory", "");
            }}
            type="file"
            multiple
            className="hidden"
            onChange={(event) => void handleFolderInput(event)}
          />
        </div>
      </div>

      <div className="rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2">
        <p className="text-xs">{t("device.direct_write_warning")}</p>
      </div>

      {folderImport ? (
        <p className="text-xs text-muted-foreground">
          {t("files.folder_import_progress", {
            done: folderImport.done,
            total: folderImport.total,
          })}
        </p>
      ) : null}

      {entries.length > 0 ? (
        <WorkspaceSelectionBar
          entries={entries}
          selected={selection.selected}
          busy={listQuery.isFetching}
          pending={busy}
          onToggleAll={(all) => selection.setAll(entries, all)}
          onClear={selection.clear}
          onArchive={() => void handleArchive()}
          onDelete={() => {
            const targets = entries.filter((entry) => selection.selected.has(entry.path));
            if (targets.length > 0) setBatchTargets(targets);
          }}
        />
      ) : null}

      <div className="rounded-md border">
        {listQuery.isError ? (
          <p className="px-3 py-2 text-xs text-destructive">{t("files.load_failed")}</p>
        ) : entries.length === 0 ? (
          <p className="px-3 py-2 text-xs text-muted-foreground">{t("files.empty")}</p>
        ) : (
          entries.map((entry) => (
            <div
              key={entry.path}
              className="flex items-center justify-between gap-2 border-b px-3 py-1.5 last:border-b-0"
            >
              <WorkspaceEntryCheckbox
                path={entry.path}
                name={entry.name}
                selected={selection.selected.has(entry.path)}
                disabled={busy}
                onToggle={selection.toggle}
              />
              <button
                type="button"
                className="min-w-0 flex-1 truncate text-left font-mono text-xs hover:underline"
                onClick={() => {
                  if (entry.isDirectory) {
                    setPath(entry.path);
                  } else {
                    openForEdit(entry);
                  }
                }}
              >
                {entry.name}
                {entry.isDirectory ? "/" : ""}
              </button>
              <div className="flex shrink-0 items-center gap-1">
                <span className="text-xs text-muted-foreground">
                  {entry.isDirectory ? "" : formatWorkspaceBytes(entry.sizeBytes)}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={async () => {
                    try {
                      await copyTextToClipboard(workspaceDeviceGuestPath(mount.alias, entry.path));
                      toast.success(t("files.path_copied"));
                    } catch {
                      toast.error(t("files.copy_failed"));
                    }
                  }}
                >
                  {t("files.copy_path")}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setRenameTarget(entry);
                    setRenameName(entry.name);
                  }}
                  disabled={busy}
                >
                  {t("files.rename")}
                </Button>
                {!entry.isDirectory ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    asChild
                  >
                    <a
                      href={workspaceDeviceFileUrl(workspaceId, mount, entry.path)}
                      download={entry.name}
                    >
                      {t("files.download")}
                    </a>
                  </Button>
                ) : null}
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setDeleteTarget(entry)}
                  disabled={busy}
                >
                  {t("files.delete")}
                </Button>
              </div>
            </div>
          ))
        )}
      </div>

      <WorkspaceCreateFileDialog
        open={mkdirOpen}
        onOpenChange={setMkdirOpen}
        parentPath={path}
        initialExtension=""
        loading={creatingFile}
        onSubmit={createFile}
      />

      <Dialog
        open={deleteTarget != null}
        onOpenChange={(next) => {
          if (!next) setDeleteTarget(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("files.delete")}</DialogTitle>
            <DialogDescription>
              {t("files.delete_confirm", { name: deleteTarget?.name ?? "" })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              {t("files.cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => deleteTarget && deleteMutation.mutate(deleteTarget)}
            >
              {t("files.confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <WorkspaceCreateFileDialog
        open={createFileOpen}
        onOpenChange={setCreateFileOpen}
        parentPath={path}
        loading={creatingFile}
        onSubmit={createFile}
      />

      <Dialog
        open={renameTarget != null}
        onOpenChange={(next) => {
          if (!next) setRenameTarget(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("files.rename")}</DialogTitle>
          </DialogHeader>
          <Input
            value={renameName}
            onChange={(event) => setRenameName(event.target.value)}
            placeholder={t("files.file_placeholder")}
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenameTarget(null)}>
              {t("files.cancel")}
            </Button>
            <Button
              disabled={
                !renameName.trim() ||
                renameName.trim() === renameTarget?.name ||
                renameMutation.isPending
              }
              onClick={() => {
                if (!renameTarget) return;
                const name = renameName.trim().replaceAll("\\", "/").replace(/^\/+|\/+$/g, "");
                if (!name) return;
                renameMutation.mutate({
                  source: renameTarget.path,
                  target: joinWorkspacePath(path, name),
                });
              }}
            >
              {t("files.confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={batchTargets != null}
        onOpenChange={(next) => {
          if (!next && !batchBusy) setBatchTargets(null);
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
              disabled={batchBusy}
              onClick={() => setBatchTargets(null)}
            >
              {t("files.cancel")}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={batchBusy || validateWorkspaceBatchPaths(batchPaths) != null}
              onClick={() => void runBatchDelete()}
            >
              {batchBusy
                ? t("files.deleting")
                : t("files.batch_delete_confirm", { count: batchTargets?.length ?? 0 })}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
