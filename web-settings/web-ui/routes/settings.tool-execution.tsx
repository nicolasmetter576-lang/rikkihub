import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Slider } from "~/components/ui/slider";
import { Switch } from "~/components/ui/switch";
import { extractErrorMessage } from "~/lib/error";
import { SettingsPageHeader } from "@overlay/components/settings/settings-ui";
import {
  builtinParallelPatch,
  fetchToolExecution,
  groupBuiltinTools,
  groupMcpTools,
  mcpParallelPatch,
  mcpParallelSourceKey,
  updateToolExecution,
} from "@overlay/lib/tool-execution";
import type { ToolExecutionPatch, ToolExecutionState } from "@overlay/types/settings";

const CATEGORY_KEYS: Record<string, string> = {
  LOCAL: "tool_execution.category_local",
  WORKSPACE: "tool_execution.category_workspace",
  SEARCH: "tool_execution.category_search",
  MEMORY: "tool_execution.category_memory",
  CONVERSATION: "tool_execution.category_conversation",
  SKILLS: "tool_execution.category_skills",
  CREATOR: "tool_execution.category_creator",
};

export default function SettingsToolExecutionPage() {
  const { t } = useTranslation("settings");
  const [state, setState] = React.useState<ToolExecutionState | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [limitDraft, setLimitDraft] = React.useState<number | null>(null);

  React.useEffect(() => {
    let mounted = true;
    fetchToolExecution()
      .then((value) => {
        if (mounted) setState(value);
      })
      .catch((error) => {
        if (mounted) toast.error(extractErrorMessage(error, t("common.save_failed")));
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [t]);

  const apply = React.useCallback(
    (patch: ToolExecutionPatch) => {
      void updateToolExecution(patch)
        .then(setState)
        .catch((error) => toast.error(extractErrorMessage(error, t("common.save_failed"))));
    },
    [t],
  );

  if (loading || !state) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("tool_execution.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  const limit = limitDraft ?? state.maxParallel;

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("tool_execution.title")} description={t("tool_execution.description")} />

      <div className="space-y-3 rounded-xl border p-4">
        <div className="flex items-center justify-between gap-3 rounded-md border px-3 py-2">
          <div className="space-y-0.5">
            <p className="text-sm">{t("tool_execution.parallel")}</p>
            <p className="text-xs text-muted-foreground">{t("tool_execution.parallel_desc")}</p>
          </div>
          <Switch checked={state.parallel} onCheckedChange={(checked) => apply({ parallel: checked })} />
        </div>
        {state.parallel ? (
          <div className="space-y-2 rounded-md border px-3 py-3">
            <p className="text-sm">{t("tool_execution.max_parallel", { count: limit })}</p>
            <Slider
              min={1}
              max={state.maxParallelLimit}
              step={1}
              value={[limit]}
              onValueChange={(values) => setLimitDraft(values[0] ?? limit)}
              onValueCommit={(values) => {
                const next = values[0] ?? limit;
                setLimitDraft(null);
                if (next !== state.maxParallel) apply({ maxParallel: next });
              }}
            />
          </div>
        ) : null}
      </div>

      <section className="space-y-3">
        <h2 className="text-base font-semibold">{t("tool_execution.builtin_title")}</h2>
        {groupBuiltinTools(state.builtinTools).map((group) => (
          <div key={group.category} className="space-y-2 rounded-xl border p-4">
            <p className="text-sm font-medium text-muted-foreground">
              {t(CATEGORY_KEYS[group.category] ?? group.category)}
            </p>
            {group.tools.map((tool) => (
              <div
                key={tool.id}
                className="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
              >
                <div className="min-w-0 space-y-0.5">
                  <p className="truncate font-mono text-sm">{tool.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {t(tool.defaultParallel ? "tool_execution.default_on" : "tool_execution.default_off")}
                    {tool.argumentLimited ? ` · ${t("tool_execution.view_only")}` : ""}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  {tool.overridden ? (
                    <Badge variant="secondary">{t("tool_execution.overridden")}</Badge>
                  ) : null}
                  <Switch
                    checked={tool.parallel}
                    disabled={!state.parallel}
                    onCheckedChange={(checked) => apply(builtinParallelPatch(tool, checked))}
                  />
                </div>
              </div>
            ))}
          </div>
        ))}
      </section>

      <section className="space-y-3">
        <h2 className="text-base font-semibold">{t("tool_execution.mcp_title")}</h2>
        {state.mcpTools.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("tool_execution.mcp_empty")}</p>
        ) : (
          groupMcpTools(state.mcpTools).map((group) => (
            <div key={group.serverId} className="space-y-2 rounded-xl border p-4">
              <p className="text-sm font-medium text-muted-foreground">{group.serverName}</p>
              {group.tools.map((tool) => (
                <div
                  key={tool.tool}
                  className="flex items-center justify-between gap-3 rounded-md border px-3 py-2"
                >
                  <div className="min-w-0 space-y-0.5">
                    <p className="truncate font-mono text-sm">{tool.tool}</p>
                    <p className="text-xs text-muted-foreground">
                      {t(mcpParallelSourceKey(tool))}
                      {tool.enabled ? "" : ` · ${t("tool_execution.mcp_disabled")}`}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    {tool.override !== null && tool.override !== undefined ? (
                      <Button variant="ghost" size="sm" onClick={() => apply(mcpParallelPatch(tool, null))}>
                        {t("tool_execution.reset")}
                      </Button>
                    ) : null}
                    <Switch
                      checked={tool.parallel}
                      disabled={!state.parallel}
                      onCheckedChange={(checked) => apply(mcpParallelPatch(tool, checked))}
                    />
                  </div>
                </div>
              ))}
            </div>
          ))
        )}
      </section>
    </div>
  );
}
