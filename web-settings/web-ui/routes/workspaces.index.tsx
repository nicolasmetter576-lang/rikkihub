import * as React from "react";

import { useNavigate } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderKanban, Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  ConfirmDeleteDialog,
  CreateNameDialog,
  SettingsList,
  SettingsListCard,
  SettingsPageHeader,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { workspaceApi, workspaceMode, workspaceQueryKeys } from "@overlay/lib/workspace";
import type { Workspace, WorkspaceMode } from "@overlay/types/workspace";

const MODES: readonly WorkspaceMode[] = ["normal", "book", "minimal"];

export default function WorkspacesIndexPage() {
  const { t, i18n } = useTranslation("workspace");
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const [mode, setMode] = React.useState<WorkspaceMode>("normal");
  const [renameTarget, setRenameTarget] = React.useState<Workspace | null>(null);
  const [renaming, setRenaming] = React.useState(false);
  const [deleteTarget, setDeleteTarget] = React.useState<Workspace | null>(null);
  const [deleting, setDeleting] = React.useState(false);

  const invalidateWorkspaces = React.useCallback(
    () => queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.list }),
    [queryClient],
  );

  // React Query 承载列表；rootfs 安装中每 2s 静默轮询，装完自动变 READY；
  // workspace_update SSE 事件经 useWorkspaceUpdates 失效这个 key（跨端修改可见）。
  const listQuery = useQuery({
    queryKey: workspaceQueryKeys.list,
    queryFn: () => workspaceApi.list(),
    refetchInterval: (query) =>
      query.state.data?.some((workspace) => workspace.shellStatus === "INSTALLING")
        ? 2000
        : false,
  });
  const workspaces = listQuery.data ?? [];
  const loading = listQuery.isPending;

  React.useEffect(() => {
    if (listQuery.isError) {
      reportSettingsError(listQuery.error, t("files.load_failed"));
    }
  }, [listQuery.isError, listQuery.error, t]);

  const deleteMutation = useMutation({
    mutationFn: (workspaceId: string) => workspaceApi.delete(workspaceId),
  });

  return (
    <div className="space-y-6">
      <SettingsPageHeader
        title={t("list.title")}
        description={t("list.description")}
        actions={
          <Button type="button" onClick={() => setCreateOpen(true)}>
            <Plus className="size-4" />
            {t("list.new")}
          </Button>
        }
      />

      {loading ? (
        <p className="text-sm text-muted-foreground">{t("list.loading")}</p>
      ) : workspaces.length === 0 ? (
        <div className="rounded-xl border border-dashed px-4 py-16 text-center">
          <FolderKanban className="mx-auto mb-3 size-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">{t("list.empty")}</p>
        </div>
      ) : (
        <SettingsList>
          {workspaces.map((workspace) => (
            <SettingsListCard
              key={workspace.id}
              title={workspace.name}
              description={
                workspaceMode(workspace) === "normal"
                  ? t(`status.${workspace.shellStatus}`)
                  : `${t(`status.${workspace.shellStatus}`)} · ${t(`list.mode_badge.${workspaceMode(workspace)}`)}`
              }
              badges={
                <>
                  <Badge variant={workspace.shellStatus === "READY" ? "default" : "secondary"}>
                    {t(`status.${workspace.shellStatus}`)}
                  </Badge>
                  {workspaceMode(workspace) === "normal" ? null : (
                    <Badge variant="outline">{t(`list.mode_badge.${workspaceMode(workspace)}`)}</Badge>
                  )}
                </>
              }
              actions={
                <>
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => void navigate(`/workspaces/${workspace.id}`)}
                  >
                    {t("list.open")}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    onClick={() => setRenameTarget(workspace)}
                  >
                    {t("list.rename")}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    onClick={() => setDeleteTarget(workspace)}
                  >
                    {t("list.delete")}
                  </Button>
                </>
              }
            />
          ))}
        </SettingsList>
      )}

      <CreateNameDialog
        open={createOpen}
        onOpenChange={(open) => {
          setCreateOpen(open);
          if (!open) setMode("normal");
        }}
        title={t("list.new")}
        placeholder={t("list.create_name_placeholder")}
        extra={
          <div className="divide-y rounded-lg border" role="radiogroup">
            {MODES.map((value) => (
              <label key={value} className="flex items-start gap-3 px-3 py-2 text-sm">
                <input
                  type="radio"
                  name="workspace-mode"
                  className="mt-1"
                  checked={mode === value}
                  onChange={() => setMode(value)}
                />
                <span>
                  <span className="block font-medium">{t(`list.mode.${value}`)}</span>
                  <span className="mt-1 block text-muted-foreground">{t(`list.mode_desc.${value}`)}</span>
                </span>
              </label>
            ))}
          </div>
        }
        loading={creating}
        onSubmit={async (name) => {
          setCreating(true);
          try {
            const workspace = await workspaceApi.create(
              name.trim() || undefined,
              mode,
              i18n.language.startsWith("zh") ? "zh" : "en",
            );
            toast.success(t("list.created"));
            setCreateOpen(false);
            setMode("normal");
            void navigate(`/workspaces/${workspace.id}`);
          } catch (error) {
            reportSettingsError(error, t("list.create_failed"));
          } finally {
            setCreating(false);
          }
        }}
      />

      <CreateNameDialog
        open={renameTarget != null}
        onOpenChange={(open) => {
          if (!open) setRenameTarget(null);
        }}
        title={t("list.rename")}
        placeholder={t("list.create_name_placeholder")}
        initialName={renameTarget?.name}
        submitLabel={t("files.confirm")}
        submittingLabel={t("files.saving")}
        loading={renaming}
        onSubmit={async (name) => {
          if (!renameTarget) return;
          setRenaming(true);
          try {
            await workspaceApi.rename(renameTarget.id, name.trim() || renameTarget.name);
            setRenameTarget(null);
            await invalidateWorkspaces();
          } catch (error) {
            reportSettingsError(error, t("list.rename_failed"));
          } finally {
            setRenaming(false);
          }
        }}
      />

      <ConfirmDeleteDialog
        open={deleteTarget != null}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
        title={t("list.delete")}
        description={t("list.delete_confirm", { name: deleteTarget?.name ?? "" })}
        loading={deleting}
        onConfirm={async () => {
          if (!deleteTarget) return;
          setDeleting(true);
          try {
            await deleteMutation.mutateAsync(deleteTarget.id);
            toast.success(t("files.deleted"));
            setDeleteTarget(null);
            await invalidateWorkspaces();
          } catch (error) {
            reportSettingsError(error, t("files.delete_failed"));
          } finally {
            setDeleting(false);
          }
        }}
      />
    </div>
  );
}
