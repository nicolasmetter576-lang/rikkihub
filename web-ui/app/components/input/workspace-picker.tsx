import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Codesandbox, Folder, LoaderCircle, Settings2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { AssistantProfile, ConversationDto } from "~/types";
import { Button } from "~/components/ui/button";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "~/components/ui/popover";
import { Switch } from "~/components/ui/switch";
import { WorkspaceCwdDialog } from "~/components/conversation-settings-menu";
import { workspaceApi, workspaceQueryKeys } from "@overlay/lib/workspace";
import type { Workspace } from "@overlay/types/workspace";

import { PickerErrorAlert } from "./picker-error-alert";

export interface WorkspacePickerButtonProps {
  disabled?: boolean;
  className?: string;
  conversation?: ConversationDto | null;
  /** Fresh conversation after a cwd / scope change (the stream also delivers it). */
  onConversationUpdated?: (conversation: ConversationDto) => void;
  /** Classes that reveal the bound workspace's name (hidden by default); viewport `sm:` unless overridden. */
  labelClassName?: string;
}

/** Request body of `PUT /api/assistants/{id}/workspace`. */
export function assistantWorkspaceBody(workspaceId: string | null): { workspaceId: string | null } {
  return { workspaceId };
}

/**
 * Chat-bar workspace switch, like the App's "+" panel row: picks the workspace bound to
 * the current assistant (so every conversation of that assistant follows), and for an existing
 * conversation on a ready workspace its working directory and "only this folder" switch.
 */
export function WorkspacePickerButton({
  disabled = false,
  className,
  conversation = null,
  onConversationUpdated,
  labelClassName = "sm:inline",
}: WorkspacePickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();
  const queryClient = useQueryClient();
  const canUse = Boolean(settings && currentAssistant && !disabled);
  const { error, setError, popoverProps, setOpen } = usePickerPopover(canUse);
  const [cwdOpen, setCwdOpen] = React.useState(false);

  const workspacesQuery = useQuery({
    queryKey: workspaceQueryKeys.list,
    queryFn: () => workspaceApi.list(),
    enabled: Boolean(currentAssistant),
    staleTime: 30_000,
  });
  const workspaces: Workspace[] = workspacesQuery.data ?? [];
  const boundId = currentAssistant?.workspaceId ?? null;
  const bound = workspaces.find((item) => item.id === boundId) ?? null;

  const updateConversation = React.useCallback(
    async (patch: Record<string, unknown>) => {
      if (!conversation) return;
      const next = await api.put<ConversationDto>(`conversations/${conversation.id}`, patch);
      onConversationUpdated?.(next);
    },
    [conversation, onConversationUpdated],
  );

  const bindMutation = useMutation({
    mutationFn: async (workspaceId: string | null) => {
      const assistant = currentAssistant!;
      const updated = await api.put<AssistantProfile>(
        `assistants/${assistant.id}/workspace`,
        assistantWorkspaceBody(workspaceId),
      );
      // Same as the App: the old working directory belongs to the old workspace.
      if (conversation?.workspaceCwd) await updateConversation({ workspaceCwd: null });
      return updated;
    },
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.list });
    },
    onError: (caught) => setError(extractErrorMessage(caught, t("workspace.update_failed"))),
  });

  const scopeMutation = useMutation({
    mutationFn: (scoped: boolean) => updateConversation({ workspaceCwdScoped: scoped }),
    onSuccess: () => setError(null),
    onError: (caught) => setError(extractErrorMessage(caught, t("workspace.update_failed"))),
  });

  const busy = bindMutation.isPending || scopeMutation.isPending;
  const showCwd = Boolean(conversation && bound && bound.shellStatus === "READY");

  return (
    <>
      <Popover {...popoverProps}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            aria-label={t("workspace.title")}
            title={bound ? t("workspace.bound_to", { name: bound.name }) : t("workspace.title")}
            variant="ghost"
            size="sm"
            disabled={!canUse || busy}
            data-testid="workspace-picker"
            className={cn(
              "h-8 max-w-40 rounded-full px-2 text-muted-foreground hover:text-foreground",
              bound && "text-primary hover:bg-primary/10",
              className,
            )}
          >
            {busy ? <LoaderCircle className="size-4 animate-spin" /> : <Codesandbox className="size-4" />}
            {bound ? (
              <span className={cn("hidden truncate text-xs", labelClassName)}>{bound.name}</span>
            ) : null}
          </Button>
        </PopoverTrigger>
        <PopoverContent
          side="top"
          align="start"
          sideOffset={8}
          className="w-[min(92vw,20rem)] max-h-[var(--radix-popover-content-available-height)] space-y-3 overflow-y-auto p-4"
        >
          <PopoverTitle className="text-sm">{t("workspace.title")}</PopoverTitle>
          <p className="text-xs text-muted-foreground">{t("workspace.hint")}</p>
          <PickerErrorAlert error={error} />

          <div className="max-h-56 space-y-1 overflow-y-auto" role="listbox" aria-label={t("workspace.title")}>
            {[null, ...workspaces].map((item) => {
              const id = item?.id ?? null;
              const selected = id === boundId;
              const switching = bindMutation.isPending && bindMutation.variables === id;
              return (
                <button
                  key={id ?? "none"}
                  type="button"
                  role="option"
                  aria-selected={selected}
                  disabled={busy}
                  className={cn(
                    "flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm transition hover:bg-muted disabled:opacity-60",
                    selected && "bg-primary/5",
                  )}
                  onClick={() => {
                    if (selected) return;
                    bindMutation.mutate(id);
                  }}
                >
                  <span className="flex size-4 shrink-0 items-center justify-center">
                    {switching ? (
                      <LoaderCircle className="size-4 animate-spin" />
                    ) : selected ? (
                      <Check className="size-4 text-primary" />
                    ) : null}
                  </span>
                  <span className="min-w-0 flex-1 truncate">{item ? item.name : t("workspace.none")}</span>
                  {item ? (
                    <span className="shrink-0 text-[10px] text-muted-foreground">
                      {t(`workspace.status.${item.shellStatus}`)}
                    </span>
                  ) : null}
                </button>
              );
            })}
            {workspacesQuery.isLoading ? (
              <div className="px-2 py-2 text-xs text-muted-foreground">{t("workspace.loading")}</div>
            ) : null}
            {boundId != null && bound == null && !workspacesQuery.isLoading ? (
              <div className="px-2 py-2 text-xs text-destructive">{t("workspace.missing")}</div>
            ) : null}
          </div>

          {showCwd && conversation ? (
            <div className="space-y-2 border-t pt-3" data-testid="workspace-cwd">
              <button
                type="button"
                className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-muted disabled:opacity-60"
                disabled={busy}
                onClick={() => {
                  setOpen(false);
                  setCwdOpen(true);
                }}
              >
                <Folder className="size-4 shrink-0" />
                <span className="min-w-0 flex-1 truncate font-mono text-xs">
                  {conversation.workspaceCwd ?? "/workspace"}
                </span>
              </button>
              <label className="flex items-center justify-between gap-2 px-2 text-sm">
                <span className={cn(!conversation.workspaceCwd && "text-muted-foreground")}>
                  {t("workspace.scoped")}
                </span>
                <Switch
                  size="sm"
                  checked={conversation.workspaceCwdScoped !== false}
                  disabled={busy}
                  onCheckedChange={(checked) => scopeMutation.mutate(checked)}
                />
              </label>
              <p className="px-2 text-[11px] text-muted-foreground">{t("workspace.scoped_hint")}</p>
            </div>
          ) : null}

          <div className="flex items-center justify-between gap-2 border-t pt-3 text-xs">
            <Link to="/workspaces" className="text-muted-foreground hover:text-foreground" onClick={() => setOpen(false)}>
              {t("workspace.manage")}
            </Link>
            {bound ? (
              <Link
                to={`/workspaces/${bound.id}`}
                className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground"
                onClick={() => setOpen(false)}
              >
                <Settings2 className="size-3.5" />
                {t("workspace.open_settings")}
              </Link>
            ) : null}
          </div>
        </PopoverContent>
      </Popover>

      {showCwd && conversation && bound ? (
        <WorkspaceCwdDialog
          open={cwdOpen}
          workspaceId={bound.id}
          currentCwd={conversation.workspaceCwd}
          onOpenChange={setCwdOpen}
          onSave={(cwd) => updateConversation({ workspaceCwd: cwd })}
        />
      ) : null}
    </>
  );
}
