import * as React from "react";

import { ArrowUp, Folder } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Spinner } from "~/components/ui/spinner";
import {
  workspaceApi,
  workspaceTransferAllowed,
  workspaceTransferConflictCount,
} from "@overlay/lib/workspace";
import type { Workspace, WorkspaceFileEntry, WorkspaceTransferMode } from "@overlay/types/workspace";

/**
 * 移动 / 复制：先选目标工作区（默认本工作区），再从它的 FILES 区逐级选文件夹，确认后以原名放进去；
 * 目标里已有同名项时自动改名为 "name (n)"，确认前提示有几项会改名。
 * 本工作区内：被选中的文件夹不能进入/作为落点；移动不能落在原文件夹。
 */
export function WorkspaceMoveDialog({
  workspaceId,
  entries,
  mode,
  loading,
  onOpenChange,
  onSubmit,
}: {
  workspaceId: string;
  /** null = 关闭 */
  entries: ReadonlyArray<WorkspaceFileEntry> | null;
  mode: WorkspaceTransferMode;
  loading: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (targetWorkspaceId: string, destinationDir: string) => void | Promise<void>;
}) {
  const list = entries ?? [];
  const first = list[0] ?? null;
  const { t } = useTranslation("workspace");
  const [targetWorkspaceId, setTargetWorkspaceId] = React.useState(workspaceId);
  const [workspaces, setWorkspaces] = React.useState<Workspace[]>([]);
  const [browsePath, setBrowsePath] = React.useState("");
  const [dirs, setDirs] = React.useState<WorkspaceFileEntry[]>([]);
  const [names, setNames] = React.useState<ReadonlySet<string>>(new Set());
  const [listing, setListing] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const sameWorkspace = targetWorkspaceId === workspaceId;

  const sourceParent = React.useMemo(() => {
    if (!first) return "";
    const slash = first.path.lastIndexOf("/");
    return slash < 0 ? "" : first.path.slice(0, slash);
  }, [first]);

  // 每次打开都从本工作区、源所在文件夹开始
  React.useEffect(() => {
    if (!first) return;
    setTargetWorkspaceId(workspaceId);
    setBrowsePath(sourceParent);
  }, [first, sourceParent, workspaceId]);

  React.useEffect(() => {
    if (!first) return;
    let cancelled = false;
    workspaceApi
      .list()
      .then((result) => {
        if (!cancelled) setWorkspaces(result);
      })
      .catch(() => {
        if (!cancelled) setWorkspaces([]);
      });
    return () => {
      cancelled = true;
    };
  }, [first]);

  React.useEffect(() => {
    if (!first) return;
    const sources = new Set((entries ?? []).map((item) => item.path));
    let cancelled = false;
    setListing(true);
    workspaceApi
      .listFiles(targetWorkspaceId, "FILES", browsePath)
      .then((result) => {
        if (cancelled) return;
        // 本工作区里，被选中的文件夹本身不能作为落点，也不让进入
        setDirs(
          result.entries
            .filter((item) => item.isDirectory && !(sameWorkspace && sources.has(item.path)))
            .sort((a, b) => a.name.localeCompare(b.name)),
        );
        setNames(new Set(result.entries.map((item) => item.name)));
        setError(null);
      })
      .catch((caught: unknown) => {
        if (cancelled) return;
        setDirs([]);
        setNames(new Set());
        setError(caught instanceof Error ? caught.message : String(caught));
      })
      .finally(() => {
        if (!cancelled) setListing(false);
      });
    return () => {
      cancelled = true;
    };
  }, [browsePath, entries, first, sameWorkspace, targetWorkspaceId]);

  const allowed = list.length > 0 && list.every((item) => workspaceTransferAllowed(item.path, sameWorkspace, browsePath, mode));
  const canConfirm = allowed && !listing && error == null && !loading;
  // 只在能放进去时预告改名：移动落在原文件夹（禁用）时源自己也在列表里，不该算重名
  const conflicts = allowed && !listing && error == null
    ? workspaceTransferConflictCount(list.map((item) => item.name), names)
    : 0;
  // 本工作区排第一，其余按名字
  const orderedWorkspaces = React.useMemo(() => {
    const own = workspaces.filter((item) => item.id === workspaceId);
    const others = workspaces
      .filter((item) => item.id !== workspaceId)
      .sort((a, b) => a.name.localeCompare(b.name));
    return [...own, ...others];
  }, [workspaceId, workspaces]);

  const title = mode === "move"
    ? list.length > 1
      ? t("files.move_title_multi", { count: list.length })
      : t("files.move_title", { name: first?.name ?? "" })
    : list.length > 1
      ? t("files.copy_title_multi", { count: list.length })
      : t("files.copy_title", { name: first?.name ?? "" });

  return (
    <Dialog
      open={first != null}
      onOpenChange={(open) => {
        if (!loading) onOpenChange(open);
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="break-all">{title}</DialogTitle>
          <DialogDescription>{t("files.move_desc")}</DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2">
          <span className="shrink-0 text-sm text-muted-foreground">{t("files.transfer_workspace")}</span>
          <Select
            value={targetWorkspaceId}
            disabled={loading}
            onValueChange={(id) => {
              if (id === targetWorkspaceId) return;
              setTargetWorkspaceId(id);
              setBrowsePath(id === workspaceId ? sourceParent : "");
            }}
          >
            <SelectTrigger className="min-w-0 flex-1" aria-label={t("files.transfer_workspace")} data-testid="transfer-workspace">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {orderedWorkspaces.length === 0 ? (
                <SelectItem value={workspaceId}>{t("files.transfer_current", { name: "" })}</SelectItem>
              ) : (
                orderedWorkspaces.map((item) => (
                  <SelectItem key={item.id} value={item.id}>
                    {item.id === workspaceId ? t("files.transfer_current", { name: item.name }) : item.name}
                  </SelectItem>
                ))
              )}
            </SelectContent>
          </Select>
        </div>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            disabled={!browsePath || loading}
            title={t("files.up")}
            onClick={() => {
              const slash = browsePath.lastIndexOf("/");
              setBrowsePath(slash < 0 ? "" : browsePath.slice(0, slash));
            }}
          >
            <ArrowUp className="size-4" />
          </Button>
          <span className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground">/{browsePath}</span>
          {listing ? <Spinner className="size-4" /> : null}
        </div>
        <ul className="max-h-72 min-h-24 overflow-auto rounded-md border">
          {dirs.map((dir) => (
            <li key={dir.path}>
              <button
                type="button"
                className="flex w-full min-w-0 items-center gap-2.5 px-3 py-2 text-left hover:bg-muted/60 disabled:opacity-50"
                disabled={loading}
                onClick={() => setBrowsePath(dir.path)}
              >
                <Folder className="size-4 shrink-0 text-primary" />
                <span className="min-w-0 flex-1 truncate text-sm">{dir.name}</span>
              </button>
            </li>
          ))}
          {!listing && dirs.length === 0 ? (
            <li className={error ? "px-3 py-2 text-sm text-destructive" : "px-3 py-2 text-sm text-muted-foreground"}>
              {error ?? t("files.move_no_folders")}
            </li>
          ) : null}
        </ul>
        {conflicts > 0 ? (
          <p className="text-xs text-muted-foreground" data-testid="transfer-conflicts">
            {t("files.transfer_conflicts", { count: conflicts })}
          </p>
        ) : null}
        <DialogFooter>
          <Button type="button" variant="outline" disabled={loading} onClick={() => onOpenChange(false)}>
            {t("files.cancel")}
          </Button>
          <Button
            type="button"
            disabled={!canConfirm}
            onClick={() => {
              if (canConfirm) void onSubmit(targetWorkspaceId, browsePath);
            }}
          >
            {mode === "move"
              ? loading ? t("files.moving") : t("files.move_here")
              : loading ? t("files.copying") : t("files.copy_here")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
