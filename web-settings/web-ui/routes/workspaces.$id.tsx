import { requestWorkspaceEditorLeave } from "@overlay/components/workspace/use-workspace-editor-session";
import * as React from "react";

import { Link, useParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

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
import { Switch } from "~/components/ui/switch";
import {
  SettingsSegmented,
  SettingsSelectField,
  SettingsSwitchRow,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { WorkspaceDevicePanel } from "@overlay/components/workspace/workspace-device-panel";
import { WorkspaceSubagentPresets } from "@overlay/components/workspace/workspace-subagent-presets";
import { WorkspaceSubagentLibrary } from "@overlay/components/workspace/workspace-subagent-library";
import { WorkspaceFileBrowser } from "@overlay/components/workspace/workspace-file-browser";
import { WorkspaceTerminal } from "@overlay/components/workspace/workspace-terminal";
import { WorkspaceRootfsInstall } from "@overlay/components/workspace/workspace-rootfs-install";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";

import {
  WORKSPACE_TOOL_ENABLEMENT_UI_ENABLED,
  resolveWorkspaceToolWireName,
  workspaceApi,
  workspaceQueryKeys,
  workspaceToolEnablementIds,
} from "@overlay/lib/workspace";
import { useSettingsStore } from "~/stores/settings";

/** 首条消息文件列表层数选项（服务端范围 1..10，WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH）。 */
const WORKSPACE_SNAPSHOT_DEPTHS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

// 对照 App WorkspaceToolDefaultApprovals (WorkspaceTools.kt): 工具全集 + 默认 needsApproval
const TOOL_APPROVAL_DEFAULTS: ReadonlyArray<readonly [string, boolean]> = [
  ["workspace_read_file", false],
  ["workspace_write_file", false],
  ["workspace_edit_file", false],
  ["workspace_shell", false],
  ["bash", false],
  ["str_replace_editor", false],
  ["subagent", false],
];

// 对照 App WorkspaceExposedMountsCard: field -> rootfs 挂载目标
const EXPOSED_MOUNT_ROWS = [
  {
    field: "exposeUpload",
    target: "/upload",
    toPatch: (checked: boolean) => ({ exposeUpload: checked }),
  },
  {
    field: "exposeSkills",
    target: "/skills",
    toPatch: (checked: boolean) => ({ exposeSkills: checked }),
  },
  {
    field: "exposeToolOutputs",
    target: "/tool_outputs",
    toPatch: (checked: boolean) => ({ exposeToolOutputs: checked }),
  },
] as const;

export default function WorkspaceDetailPage() {
  const { t } = useTranslation("workspace");
  const params = useParams();
  const id = params.id ?? "";
  const queryClient = useQueryClient();
  const [tab, setTab] = React.useState<"files" | "terminal" | "device">("files");
  const [resetEnablementOpen, setResetEnablementOpen] = React.useState(false);
  const toolOverrides = useSettingsStore((state) => state.settings?.toolOverrides);
  const providers = useSettingsStore((state) => state.settings?.providers);
  const toolModels = React.useMemo(() => {
    const models: Array<{ value: string; label: string }> = [];
    for (const provider of providers ?? []) {
      if (!provider.enabled) continue;
      for (const model of provider.models ?? []) {
        if (model.type !== "CHAT") continue;
        if (!(model.abilities ?? []).includes("TOOL")) continue;
        models.push({
          value: model.id,
          label: `${provider.name} / ${model.displayName || model.modelId}`,
        });
      }
    }
    return models;
  }, [providers]);

  // React Query 承载 detail；workspace_update 事件触发 invalidate，跨 App/Web 修改自动可见。
  // rootfs 安装中每 2s 轮询，装完自动变 READY（终端即可用）。
  const workspaceQuery = useQuery({
    queryKey: workspaceQueryKeys.detail(id),
    queryFn: () => workspaceApi.get(id),
    enabled: id.length > 0,
    refetchInterval: (query) =>
      query.state.data?.shellStatus === "INSTALLING" ? 2000 : false,
  });
  const workspace = workspaceQuery.data ?? null;

  const updateMutation = useMutation({
    mutationFn: (patch: Parameters<typeof workspaceApi.update>[1]) =>
      workspaceApi.update(id, patch),
    onSuccess: (next) => {
      // 响应即最新实体：直接回填 detail 缓存，并失效列表（名称/状态可能变化）
      queryClient.setQueryData(workspaceQueryKeys.detail(id), next);
      void queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.list });
    },
    onError: (caught) => {
      reportSettingsError(caught, t("detail.save_failed"));
    },
  });

  const applyPatch = (patch: Parameters<typeof workspaceApi.update>[1]) => {
    updateMutation.mutate(patch);
  };

  if (workspaceQuery.isError) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-muted-foreground">{t("detail.not_found")}</p>
        <Button asChild variant="outline">
          <Link to="/workspaces">{t("nav.title")}</Link>
        </Button>
      </div>
    );
  }

  if (!workspace) {
    return <p className="text-sm text-muted-foreground">{t("list.loading")}</p>;
  }

  // 损坏配置 fail-closed：服务端视为全部禁用，UI 显式受限 + 显式重置入口
  const enablementCorrupt =
    workspace.toolEnablementCorrupt === true || workspace.toolEnablement == null;
  // 子代理设置与预设只在子代理工具开着时显示（关着时模型拿不到子代理工具，这些设置不起作用）
  const subagentEnabled = !enablementCorrupt && workspace.toolEnablement?.subagent !== false;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-xl font-semibold">{workspace.name}</h1>
            <Badge variant={workspace.shellStatus === "READY" ? "default" : "secondary"}>
              {t(`status.${workspace.shellStatus}`)}
            </Badge>
            {workspace.harnessMinimal ? (
              <Badge variant="outline">{t("list.harness_minimal_badge")}</Badge>
            ) : null}
          </div>
          <p className="font-mono text-xs text-muted-foreground">{workspace.id}</p>
          <WorkspaceRootfsInstall workspace={workspace} className="pt-1" />
        </div>
        <SettingsSegmented
          value={tab}
          onChange={next => requestWorkspaceEditorLeave(() => setTab(next))}
          options={[
            { value: "files", label: t("detail.files") },
            { value: "terminal", label: t("detail.terminal") },
            { value: "device", label: t("detail.device") },
          ]}
        />
      </div>

      <div className="space-y-3 rounded-lg border p-4">
        <div className="flex items-center justify-between gap-3">
          <div className="space-y-1">
            <h2 className="text-sm font-medium">{t("detail.first_message_snapshot")}</h2>
            <p className="text-xs text-muted-foreground">{t("detail.first_message_snapshot_desc")}</p>
          </div>
          <Switch
            checked={workspace.firstMessageFileSnapshot === true}
            onCheckedChange={(checked) => {
              applyPatch({ firstMessageFileSnapshot: checked });
            }}
          />
        </div>
        {workspace.firstMessageFileSnapshot === true ? (
          <div className="flex items-center justify-between gap-3" data-testid="snapshot-depth">
            <div className="space-y-1">
              <h3 className="text-sm">{t("detail.first_message_snapshot_depth")}</h3>
              <p className="text-xs text-muted-foreground">{t("detail.first_message_snapshot_depth_desc")}</p>
            </div>
            <Select
              value={String(workspace.firstMessageFileSnapshotDepth ?? 1)}
              onValueChange={(value) => {
                applyPatch({ firstMessageFileSnapshotDepth: Number(value) });
              }}
            >
              <SelectTrigger className="w-28 shrink-0" aria-label={t("detail.first_message_snapshot_depth")}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {WORKSPACE_SNAPSHOT_DEPTHS.map((depth) => (
                  <SelectItem key={depth} value={String(depth)}>
                    {t("detail.first_message_snapshot_depth_value", { count: depth })}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        ) : null}
      </div>

      <div className="space-y-3 rounded-lg border p-4">
        <div className="flex items-center justify-between gap-3">
          <div className="space-y-1">
            <h2 className="text-sm font-medium">{t("detail.compatibility_mode")}</h2>
            <p className="text-xs text-muted-foreground">{t("detail.compatibility_mode_desc")}</p>
          </div>
          <Switch
            checked={workspace.shellCompatibilityMode === true}
            onCheckedChange={(checked) => {
              applyPatch({ shellCompatibilityMode: checked });
            }}
          />
        </div>
      </div>

      {subagentEnabled ? (
        <>
          <div className="space-y-3 rounded-lg border p-4">
            <div>
              <h2 className="text-sm font-medium">{t("detail.subagent")}</h2>
              <p className="text-xs text-muted-foreground">{t("detail.subagent_desc")}</p>
            </div>
            <SettingsSelectField
              label={t("detail.subagent_model")}
              value={workspace.subagentModelId ?? "parent"}
              onValueChange={(value) => {
                applyPatch({
                  subagentModelId: value === "parent" ? null : value,
                });
              }}
              options={[
                { value: "parent", label: t("detail.subagent_parent_model") },
                ...toolModels,
              ]}
            />
            <p className="text-xs text-muted-foreground">{t("detail.subagent_model_desc")}</p>
            <SettingsSwitchRow
              label={t("detail.subagent_allow_context")}
              checked={workspace.subagentAllowContext === true}
              onCheckedChange={(checked) => {
                applyPatch({ subagentAllowContext: checked });
              }}
            />
            <p className="text-xs text-muted-foreground">{t("detail.subagent_allow_context_desc")}</p>
          </div>

          <WorkspaceSubagentLibrary key={`library-${workspace.id}`} workspaceId={workspace.id} />
          <WorkspaceSubagentPresets
            key={workspace.id}
            workspaceId={workspace.id}
            workspaceAllowsContext={workspace.subagentAllowContext === true}
            workspaceModelId={workspace.subagentModelId}
          />
        </>
      ) : null}

      <div className="space-y-3 rounded-lg border p-4">
        <div>
          <h2 className="text-sm font-medium">{t("detail.mounts")}</h2>
          <p className="text-xs text-muted-foreground">{t("detail.mounts_desc")}</p>
        </div>
        <div className="space-y-2">
          {EXPOSED_MOUNT_ROWS.map(({ field, target, toPatch }) => (
            <div
              key={target}
              className="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
            >
              <div className="min-w-0 space-y-0.5">
                <p className="font-mono text-sm">{target}</p>
                <p className="text-xs text-muted-foreground">{t(`detail.mount_desc.${field}`)}</p>
              </div>
              <Switch
                checked={workspace[field] === true}
                onCheckedChange={(checked) => {
                  applyPatch(toPatch(checked));
                }}
              />
            </div>
          ))}
        </div>
      </div>

      {WORKSPACE_TOOL_ENABLEMENT_UI_ENABLED ? (
        <div className="space-y-3 rounded-lg border p-4">
          <div>
            <h2 className="text-sm font-medium">{t("detail.tool_enablement")}</h2>
            <p className="text-xs text-muted-foreground">{t("detail.tool_enablement_desc")}</p>
          </div>
          {enablementCorrupt ? (
            <div className="space-y-3">
              <p className="text-xs text-destructive">
                {t("detail.tool_enablement_corrupt")}
              </p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setResetEnablementOpen(true)}
              >
                {t("detail.tool_enablement_reset")}
              </Button>
            </div>
          ) : (
            <div className="space-y-2">
              {workspaceToolEnablementIds(workspace.harnessMinimal === true).map((toolId) => {
                const enabled = workspace.toolEnablement?.[toolId] ?? true;
                // 显示 Override 后的当前 wire 名（未 Override 时 = 稳定 ID 本身）
                const wireName = resolveWorkspaceToolWireName(toolId, toolOverrides);
                return (
                  <div
                    key={toolId}
                    className="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
                  >
                    <div className="min-w-0 space-y-0.5">
                      <p className="text-sm">{t(`detail.tool_names.${toolId}`)}</p>
                      <p className="truncate font-mono text-xs text-muted-foreground">
                        {wireName}
                      </p>
                    </div>
                    <Switch
                      checked={enabled}
                      onCheckedChange={(next) => {
                        applyPatch({ toolEnablement: { [toolId]: next } });
                      }}
                    />
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ) : null}

      <div className="space-y-3 rounded-lg border p-4">
        <div>
          <h2 className="text-sm font-medium">{t("detail.tool_approval")}</h2>
          <p className="text-xs text-muted-foreground">{t("detail.tool_approval_desc")}</p>
        </div>
        <div className="space-y-2">
          {TOOL_APPROVAL_DEFAULTS.map(([toolName, defaultNeedsApproval]) => {
            const checked = workspace.toolApprovals?.[toolName] ?? defaultNeedsApproval;
            return (
              <div
                key={toolName}
                className="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
              >
                <div className="min-w-0 space-y-0.5">
                  <p className="text-sm">{t(`detail.tool_names.${toolName}`)}</p>
                  <p className="truncate font-mono text-xs text-muted-foreground">{toolName}</p>
                </div>
                <Switch
                  checked={checked}
                  onCheckedChange={(next) => {
                    applyPatch({ toolApprovals: { [toolName]: next } });
                  }}
                />
              </div>
            );
          })}
        </div>
      </div>

      {tab === "files" ? (
        <WorkspaceFileBrowser workspaceId={workspace.id} />
      ) : tab === "terminal" ? (
        <WorkspaceTerminal
          workspaceId={workspace.id}
          ready={workspace.shellStatus === "READY"}
          notReadyAction={<WorkspaceRootfsInstall workspace={workspace} className="text-left" />}
        />
      ) : (
        <WorkspaceDevicePanel workspaceId={workspace.id} />
      )}

      <Dialog open={resetEnablementOpen} onOpenChange={setResetEnablementOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("detail.tool_enablement_reset")}</DialogTitle>
            <DialogDescription>
              {t("detail.tool_enablement_reset_confirm")}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setResetEnablementOpen(false)}
            >
              {t("files.cancel")}
            </Button>
            <Button
              type="button"
              disabled={updateMutation.isPending}
              onClick={() => {
                updateMutation.mutate(
                  { resetToolEnablement: true },
                  {
                    onSuccess: () => setResetEnablementOpen(false),
                  },
                );
              }}
            >
              {t("files.confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
