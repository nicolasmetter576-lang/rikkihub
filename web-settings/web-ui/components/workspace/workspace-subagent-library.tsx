import * as React from "react";
import { Link } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Button } from "~/components/ui/button";
import { useSubagentPresetUpdates } from "@overlay/hooks/use-subagent-preset-updates";
import { workspaceQueryKeys, workspaceSubagentPresetsApi } from "@overlay/lib/workspace";
import { isSubagentPresetConflict } from "@overlay/lib/subagent-presets";
import { invalidateSubagentPresetLibrary, isLibraryPresetAssigned, libraryPresetAssignmentBlock, subagentPresetLibraryApi, subagentPresetLibraryQueryKey } from "@overlay/lib/subagent-preset-library";

export function WorkspaceSubagentLibrary({ workspaceId }: { workspaceId: string }) {
  const { t } = useTranslation("workspace");
  const queries = useQueryClient();
  useSubagentPresetUpdates();
  const library = useQuery({ queryKey: subagentPresetLibraryQueryKey, queryFn: subagentPresetLibraryApi.list });
  const local = useQuery({ queryKey: workspaceQueryKeys.presets(workspaceId), queryFn: () => workspaceSubagentPresetsApi.list(workspaceId) });
  const templates = useQuery({ queryKey: ["subagent-preset-templates"], queryFn: workspaceSubagentPresetsApi.templates });
  const builtinKeys = new Set((templates.data ?? []).map((template) => template.key));
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const change = async (id: string, enabled: boolean, version: number) => {
    setBusy(true); setError(null);
    try { await subagentPresetLibraryApi.setWorkspaceEnabled(workspaceId, id, enabled, version); }
    catch (caught) { setError(isSubagentPresetConflict(caught) ? t("presets.conflict") : caught instanceof Error ? caught.message : t("presets.save_failed")); }
    finally { invalidateSubagentPresetLibrary(queries); setBusy(false); }
  };
  return <section className="space-y-3 rounded-lg border p-4">
    <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="text-sm font-medium">{t("library.workspace_title")}</h2>
      <Button variant="outline" size="sm" asChild><Link to="/settings/subagent-presets">{t("library.manage")}</Link></Button>
    </div>
    <p className="text-xs text-muted-foreground">{t("library.workspace_help")}</p>
    {library.isPending || local.isPending ? <p className="text-sm">{t("list.loading")}</p> : null}
    {library.isError || local.isError ? <div role="alert" className="text-sm text-destructive">{t("presets.load_failed")} <Button variant="outline" onClick={() => { void library.refetch(); void local.refetch(); }}>{t("presets.retry")}</Button></div> : null}
    {library.data?.corrupt || (library.data && library.data.presets === null) ? <p role="alert" className="text-sm text-destructive">{t("library.corrupt")}</p> : null}
    <ul className="space-y-2">{(library.data?.presets ?? []).map((preset) => {
      const assigned = isLibraryPresetAssigned(preset, workspaceId, local.data);
      const block = libraryPresetAssignmentBlock(preset, local.data);
      return <li key={preset.id} className="rounded border p-3 text-sm">
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex min-w-0 max-w-full items-center gap-2"><input type="checkbox" checked={assigned} disabled={busy || !!block || library.data?.corrupt}
            onChange={(event) => void change(preset.id, event.target.checked, preset.version)} />
            <span className="min-w-0 break-words">{preset.name} <code className="break-all text-xs text-muted-foreground">{preset.key}</code></span>
          </label>
          {builtinKeys.has(preset.key) ? <span className="text-xs text-muted-foreground">{t("library.builtin")}: {t(`presets.templates.${preset.key}`, { defaultValue: preset.name })}</span> : null}
          <Button variant="link" size="sm" asChild><Link to={`/settings/subagent-presets?edit=${encodeURIComponent(preset.id)}`}>{t("library.edit_global")}</Link></Button>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">{preset.description}</p>
        {!preset.enabled ? <p className="text-xs text-muted-foreground">{t("library.definition_disabled")}</p> : null}
        {block ? <p role="status" className="text-xs text-destructive">{t(`library.blocks.${block}`)}</p> : null}
      </li>;
    })}</ul>
    {error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
  </section>;
}
