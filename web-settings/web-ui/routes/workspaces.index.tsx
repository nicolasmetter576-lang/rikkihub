import * as React from "react";

import { useNavigate } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderKanban, Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import {
  ConfirmDeleteDialog,
  CreateNameDialog,
  SettingsListCard,
  SettingsPageHeader,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { workspaceApi, workspaceQueryKeys } from "@overlay/lib/workspace";
import type { Workspace } from "@overlay/types/workspace";

export default function WorkspacesIndexPage() {
  const { t } = useTranslation("workspace");
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const [harnessMinimal, setHarnessMinimal] = React.useState(false);
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
        <div className="space-y-3">
          {workspaces.map((workspace) => (
            <SettingsListCard
              key={workspace.id}
              title={workspace.name}
              description={
                workspace.harnessMinimal
                  ? `${t(`status.${workspace.shellStatus}`)} · ${t("list.harness_minimal_badge")}`
                  : t(`status.${workspace.shellStatus}`)
              }
              badges={
                <>
                  <Badge variant={workspace.shellStatus === "READY" ? "default" : "secondary"}>
                    {t(`status.${workspace.shellStatus}`)}
                  </Badge>
                  {workspace.harnessMinimal ? (
                    <Badge variant="outline">{t("list.harness_minimal_badge")}</Badge>
                  ) : null}
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
        </div>
      )}

      <CreateNameDialog
        open={createOpen}
        onOpenChange={(open) => {
          setCreateOpen(open);
          if (!open) setHarnessMinimal(false);
        }}
        title={t("list.new")}
        placeholder={t("list.create_name_placeholder")}
        extra={
          <label className="flex items-start gap-3 rounded-lg border px-3 py-2 text-sm">
            <Checkbox
              className="mt-1"
              checked={harnessMinimal}
              onCheckedChange={(checked) => setHarnessMinimal(checked === true)}
            />
            <span>
              <span className="block font-medium">{t("list.harness_minimal")}</span>
              <span className="mt-1 block text-muted-foreground">{t("list.harness_minimal_desc")}</span>
            </span>
          </label>
        }
        loading={creating}
        onSubmit={async (name) => {
          setCreating(true);
          try {
            const workspace = await workspaceApi.create(name.trim() || undefined, harnessMinimal);
            toast.success(t("list.created"));
            setCreateOpen(false);
            setHarnessMinimal(false);
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
