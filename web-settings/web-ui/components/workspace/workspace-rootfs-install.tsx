import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, X } from "lucide-react";
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
import { Input } from "~/components/ui/input";
import { Progress } from "~/components/ui/progress";
import { cn } from "~/lib/utils";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import { formatWorkspaceBytes, workspaceApi, workspaceQueryKeys } from "@overlay/lib/workspace";
import type { RootfsInstallState, Workspace } from "@overlay/types/workspace";

export const rootfsQueryKey = (workspaceId: string) => ["workspaces", workspaceId, "rootfs"] as const;

/** Text under the progress bar, mirroring the App's RootfsProgress. */
export function rootfsProgressText(
  state: RootfsInstallState,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  if (state.stage === "EXTRACTING") {
    return t("rootfs.extracting", { count: state.entriesExtracted ?? 0 });
  }
  if (state.stage === "INSTALLED") return t("rootfs.finishing");
  const read = formatWorkspaceBytes(state.bytesRead ?? 0);
  return state.totalBytes
    ? t("rootfs.downloading_of", { read, total: formatWorkspaceBytes(state.totalBytes) })
    : t("rootfs.downloading", { read });
}

/** Download fraction 0..100, or null when unknown (extracting / no Content-Length). */
export function rootfsProgressPercent(state: RootfsInstallState): number | null {
  if (state.stage !== "DOWNLOADING" || !state.totalBytes) return null;
  return Math.max(0, Math.min(100, Math.round(((state.bytesRead ?? 0) / state.totalBytes) * 100)));
}

/**
 * Install / reinstall the workspace rootfs from the Web. The install runs as a
 * service-level task shared with the App page: leaving the page does not stop it and either side
 * can cancel. Polls once a second while installing.
 */
export function WorkspaceRootfsInstall({
  workspace,
  className,
}: {
  workspace: Pick<Workspace, "id" | "name" | "shellStatus">;
  className?: string;
}) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = React.useState(false);
  const [url, setUrl] = React.useState("");

  const statusQuery = useQuery({
    queryKey: rootfsQueryKey(workspace.id),
    queryFn: () => workspaceApi.rootfsStatus(workspace.id),
    refetchInterval: (query) =>
      query.state.data?.state?.status === "installing" || workspace.shellStatus === "INSTALLING" ? 1000 : false,
  });
  const state = statusQuery.data?.state ?? null;
  const installing = state?.status === "installing" || workspace.shellStatus === "INSTALLING";

  // An install that just finished (from either side): the workspace row changed to READY/BROKEN.
  const wasInstalling = React.useRef(installing);
  React.useEffect(() => {
    if (wasInstalling.current && !installing) {
      void queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.detail(workspace.id) });
      void queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.list });
    }
    wasInstalling.current = installing;
  }, [installing, queryClient, workspace.id]);

  const refreshAll = () => {
    void queryClient.invalidateQueries({ queryKey: rootfsQueryKey(workspace.id) });
    void queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.detail(workspace.id) });
  };

  const startMutation = useMutation({
    mutationFn: (installUrl: string) => workspaceApi.installRootfs(workspace.id, installUrl.trim() || null),
    onSuccess: (next) => {
      queryClient.setQueryData(rootfsQueryKey(workspace.id), next);
      setDialogOpen(false);
      refreshAll();
    },
    onError: (error) => reportSettingsError(error, t("rootfs.start_failed")),
  });

  const cancelMutation = useMutation({
    mutationFn: () => workspaceApi.cancelRootfs(workspace.id),
    onSuccess: (next) => {
      queryClient.setQueryData(rootfsQueryKey(workspace.id), next);
      refreshAll();
    },
    onError: (error) => reportSettingsError(error, t("rootfs.cancel_failed")),
  });

  const openDialog = () => {
    setUrl(statusQuery.data?.defaultUrl ?? "");
    setDialogOpen(true);
  };

  const percent = state?.status === "installing" ? rootfsProgressPercent(state) : null;

  return (
    <div className={cn("space-y-2", className)} data-testid="rootfs-install">
      <div className="flex flex-wrap items-center gap-2">
        {installing ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={cancelMutation.isPending || state?.status !== "installing"}
            onClick={() => cancelMutation.mutate()}
          >
            <X className="size-4" />
            {t("rootfs.cancel")}
          </Button>
        ) : (
          <Button type="button" size="sm" variant="outline" onClick={openDialog} disabled={startMutation.isPending}>
            <Download className="size-4" />
            {workspace.shellStatus === "READY" ? t("rootfs.reinstall") : t("rootfs.install")}
          </Button>
        )}
      </div>
      {state?.status === "installing" ? (
        <div className="space-y-1" data-testid="rootfs-progress">
          {percent != null ? (
            <Progress value={percent} />
          ) : (
            <div className="h-2 w-full overflow-hidden rounded-full bg-primary/20">
              <div className="h-full w-1/3 animate-pulse rounded-full bg-primary" />
            </div>
          )}
          <p className="text-xs text-muted-foreground">{rootfsProgressText(state, t)}</p>
        </div>
      ) : state?.status === "failed" ? (
        <p className="text-xs text-destructive" role="alert" data-testid="rootfs-error">
          {t("rootfs.failed", { error: state.error ?? "" })}
        </p>
      ) : null}

      <Dialog open={dialogOpen} onOpenChange={(open) => !startMutation.isPending && setDialogOpen(open)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{workspace.shellStatus === "READY" ? t("rootfs.reinstall") : t("rootfs.install")}</DialogTitle>
            <DialogDescription>
              {workspace.shellStatus === "READY"
                ? t("rootfs.reinstall_desc", { name: workspace.name })
                : t("rootfs.install_desc", { name: workspace.name })}
            </DialogDescription>
          </DialogHeader>
          <form
            className="space-y-3"
            onSubmit={(event) => {
              event.preventDefault();
              startMutation.mutate(url);
            }}
          >
            <div className="space-y-1.5">
              <label className="text-sm font-medium" htmlFor="rootfs-url">
                {t("rootfs.url")}
              </label>
              <Input
                id="rootfs-url"
                value={url}
                placeholder={statusQuery.data?.defaultUrl}
                onChange={(event) => setUrl(event.target.value)}
                disabled={startMutation.isPending}
                className="font-mono text-xs"
              />
              <p className="text-xs text-muted-foreground">{t("rootfs.url_hint")}</p>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" disabled={startMutation.isPending} onClick={() => setDialogOpen(false)}>
                {t("files.cancel")}
              </Button>
              <Button type="submit" disabled={startMutation.isPending}>
                {t("rootfs.start")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
