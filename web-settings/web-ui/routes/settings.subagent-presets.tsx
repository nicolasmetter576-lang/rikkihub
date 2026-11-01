import * as React from "react";
import { useQuery, useQueries, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Copy, Pencil, Plus, Trash2 } from "lucide-react";
import { Button } from "~/components/ui/button";
import { useSettingsStore } from "~/stores/settings";
import { SubagentPresetEditor } from "@overlay/components/workspace/workspace-subagent-presets";
import { useSubagentPresetUpdates } from "@overlay/hooks/use-subagent-preset-updates";
import { workspaceApi, workspaceQueryKeys, workspaceSubagentPresetsApi } from "@overlay/lib/workspace";
import { invalidateSubagentPresetLibrary, libraryPresetAssignmentBlock, subagentPresetLibraryApi, subagentPresetLibraryQueryKey } from "@overlay/lib/subagent-preset-library";
import { cloneSubagentPreset, isSubagentPresetConflict, newSubagentPresetDraft, subagentPresetDraft, subagentPresetDraftIsStale, subagentPresetModels, subagentPresetModelConfigConflict, uniqueSubagentPresetKey, validateSubagentPresetDraft } from "@overlay/lib/subagent-presets";
import type { LibrarySubagentPreset, SubagentPresetDraft } from "@overlay/types/subagent-preset";

interface EditorState {
  draft: SubagentPresetDraft;
  workspaceIds: string[];
  presetId?: string;
  baseVersion?: number;
  initial: string;
}

export default function SubagentPresetLibraryPage() {
  const { t } = useTranslation("workspace");
  const [params] = useSearchParams();
  const queries = useQueryClient();
  useSubagentPresetUpdates();
  const providers = useSettingsStore((state) => state.settings?.providers);
  const models = React.useMemo(() => subagentPresetModels(providers), [providers]);
  const library = useQuery({ queryKey: subagentPresetLibraryQueryKey, queryFn: subagentPresetLibraryApi.list });
  const workspaces = useQuery({ queryKey: workspaceQueryKeys.list, queryFn: workspaceApi.list });
  const targets = workspaces.data ?? [];
  const targetQueries = useQueries({ queries: targets.map((workspace) => ({ queryKey: workspaceQueryKeys.presets(workspace.id), queryFn: () => workspaceSubagentPresetsApi.list(workspace.id) })) });
  const templates = useQuery({ queryKey: ["subagent-preset-templates"], queryFn: workspaceSubagentPresetsApi.templates });
  const [editor, setEditor] = React.useState<EditorState | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  const presets = library.data?.presets ?? [];
  const latest = presets.find((preset) => preset.id === editor?.presetId);
  const stale = !!editor && subagentPresetDraftIsStale(editor.baseVersion, latest);
  const changed = !!editor && editor.initial !== JSON.stringify([editor.draft, editor.workspaceIds]);
  const open = (draft: SubagentPresetDraft | null, preset?: LibrarySubagentPreset) => {
    if (changed && !window.confirm(t("presets.discard_confirm"))) return;
    const workspaceIds = [...(preset?.workspaceIds ?? [])];
    setEditor(draft ? { draft, workspaceIds, presetId: preset?.id, baseVersion: preset?.version, initial: JSON.stringify([draft, workspaceIds]) } : null);
    setError(null);
  };
  // A navigation deep-link opens once. Background updates never overwrite a pending draft.
  const handledLink = React.useRef<string | null>(null);
  const requestedId = params.get("edit");
  React.useEffect(() => {
    if (!requestedId || handledLink.current === requestedId || !library.data) return;
    handledLink.current = requestedId;
    const preset = library.data.presets?.find((entry) => entry.id === requestedId);
    if (preset) {
      const draft = subagentPresetDraft(preset);
      const workspaceIds = [...preset.workspaceIds];
      setEditor((current) => current ?? { draft, workspaceIds, presetId: preset.id, baseVersion: preset.version, initial: JSON.stringify([draft, workspaceIds]) });
    }
  }, [requestedId, library.data]);

  const execute = async (operation: () => Promise<unknown>, after?: () => void) => {
    setBusy(true); setError(null);
    try { await operation(); after?.(); invalidateSubagentPresetLibrary(queries); }
    catch (caught) {
      setError(isSubagentPresetConflict(caught) ? t("presets.conflict") : caught instanceof Error ? caught.message : t("presets.save_failed"));
      if (isSubagentPresetConflict(caught)) invalidateSubagentPresetLibrary(queries);
    } finally { setBusy(false); }
  };
  const save = () => {
    if (!editor) return;
    const validation = validateSubagentPresetDraft(editor.draft, presets, editor.presetId);
    if (validation) { setError(t(`presets.errors.${validation}`)); return; }
    if (editor.draft.modelSelection === "fixed" && !models.some((model) => model.value === editor.draft.modelId) &&
        !(editor.presetId && latest?.modelSelection === "fixed" && latest.modelId === editor.draft.modelId)) {
      setError(t("presets.errors.model_invalid")); return;
    }
    // Target validation is informative; core remains atomic authority for races and capacity.
    const blocked = targets.some((workspace, index) => editor.workspaceIds.includes(workspace.id) &&
      !!libraryPresetAssignmentBlock({ id: editor.presetId ?? "", key: editor.draft.key }, targetQueries[index].data));
    if (blocked || workspaces.isPending || workspaces.isError) { setError(t("library.targets_invalid")); return; }
    void execute(() => editor.presetId && editor.baseVersion !== undefined
      ? subagentPresetLibraryApi.update(editor.presetId, editor.draft, editor.baseVersion, editor.workspaceIds)
      : subagentPresetLibraryApi.create(editor.draft, editor.workspaceIds), () => setEditor(null));
  };
  const corrupt = library.data?.corrupt || (library.data != null && library.data.presets === null);
  const builtinKeys = new Set((templates.data ?? []).map((template) => template.key));
  return <section className="space-y-4">
    <h1 className="text-lg font-semibold">{t("library.title")}</h1>
    <p className="text-sm text-muted-foreground">{t("library.help")}</p>
    <p className="text-xs text-muted-foreground">{t("presets.execution_notice")}</p>
    {library.isPending ? <p>{t("list.loading")}</p> : null}
    {library.isError ? <div role="alert">{t("presets.load_failed")} <Button onClick={() => void library.refetch()}>{t("presets.retry")}</Button></div> : null}
    {corrupt ? <div className="space-y-2"><p role="alert" className="text-destructive">{t("library.corrupt")}</p>
      <Button variant="destructive" disabled={busy} onClick={() => { if (window.confirm(t("library.reset_confirm"))) void execute(subagentPresetLibraryApi.reset, () => setEditor(null)); }}>{t("presets.reset")}</Button>
    </div> : library.data ? <>
      {!editor ? <div className="flex flex-wrap gap-2">
        <Button disabled={busy || presets.length >= 64} onClick={() => open(newSubagentPresetDraft())}><Plus className="size-4" />{t("presets.create")}</Button>
        {(templates.data ?? []).map((template) => <Button key={template.key} variant="outline" size="sm" disabled={busy || presets.length >= 64}
          onClick={() => open({ ...subagentPresetDraft(template), key: uniqueSubagentPresetKey(template.key, presets) })}>
          {t("presets.from_template", { name: t(`presets.templates.${template.key}`, { defaultValue: template.name }) })}
        </Button>)}
      </div> : null}
      {presets.length === 0 && !editor ? <p className="text-sm text-muted-foreground">{t("library.empty")}</p> : null}
      {!editor ? <ul className="space-y-3">{presets.map((preset) => <li key={preset.id} className="space-y-2 rounded border p-3">
        <div className="flex flex-wrap items-center gap-2"><strong className="min-w-0 max-w-full break-words">{preset.name}</strong><code className="break-all text-xs">{preset.key}</code>
          <span className="text-xs text-muted-foreground">{t(builtinKeys.has(preset.key) ? "library.builtin" : "library.custom")}</span>
          <span className="text-xs text-muted-foreground">{t(preset.enabled ? "presets.enabled" : "presets.disabled")} · v{preset.version}</span>
        </div>
        <p className="text-sm text-muted-foreground">{preset.description}</p>
        <p className="text-xs">{t("library.assigned", { count: preset.workspaceIds.length })}: {preset.workspaceIds.map((id) => targets.find((workspace) => workspace.id === id)?.name ?? id).join(", ") || t("library.none")}</p>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" disabled={busy} onClick={() => open(subagentPresetDraft(preset), preset)}><Pencil className="size-4" />{t("presets.edit")}</Button>
          <Button size="sm" variant="outline" disabled={busy || presets.length >= 64} onClick={() => open(cloneSubagentPreset(preset, presets))}><Copy className="size-4" />{t("presets.clone")}</Button>
          <Button size="sm" variant="destructive" disabled={busy} onClick={() => { if (window.confirm(t("library.delete_confirm", { name: preset.name }))) void execute(() => subagentPresetLibraryApi.delete(preset.id, preset.version)); }}><Trash2 className="size-4" />{t("presets.delete")}</Button>
        </div>
      </li>)}</ul> : null}
      {editor ? <div className="space-y-3">
        <h2 className="break-words text-base font-medium">{editor.presetId ? editor.draft.name : t("presets.create")}</h2>
        {stale ? <div role="alert" className="text-sm text-destructive">{t("presets.conflict")} <Button variant="outline" disabled={busy} onClick={() => latest ? open(subagentPresetDraft(latest), latest) : open(null)}>{t("presets.reload")}</Button></div> : null}
        {workspaces.isError ? <p role="alert">{t("library.targets_invalid")} <Button onClick={() => void workspaces.refetch()}>{t("presets.retry")}</Button></p> : null}
        <SubagentPresetEditor draft={editor.draft} immutableKey={!!editor.presetId} models={models} busy={busy} error={error}
          invalidModel={editor.draft.modelSelection === "fixed" && !models.some((model) => model.value === editor.draft.modelId)}
          modelConflict={subagentPresetModelConfigConflict(editor.draft, providers)} workspaceAllowsContext={true}
          workspaceIds={editor.workspaceIds} workspaceChoices={[...targets.map((workspace, index) => {
            const reason = libraryPresetAssignmentBlock({ id: editor.presetId ?? "", key: editor.draft.key }, targetQueries[index].data);
            // Unchecking is always available; a selected unavailable target can be removed explicitly.
            return { id: workspace.id, name: workspace.name, disabledReason: reason && !editor.workspaceIds.includes(workspace.id) ? t(`library.blocks.${reason}`) : undefined };
          }), ...editor.workspaceIds.filter((id) => !targets.some((workspace) => workspace.id === id)).map((id) => ({ id, name: `${id} (${t("library.blocks.loading")})` }))]}
          onWorkspaceIdsChange={(workspaceIds) => { setEditor({ ...editor, workspaceIds }); setError(null); }}
          onChange={(draft) => { setEditor({ ...editor, draft }); setError(null); }} onSave={save} onCancel={() => open(null)} />
      </div> : null}
    </> : null}
    {!editor && error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
  </section>;
}
