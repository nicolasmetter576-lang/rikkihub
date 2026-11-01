import * as React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Save, X } from "lucide-react";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import { useSettingsStore } from "~/stores/settings";
import { workspaceApi, workspaceQueryKeys, workspaceSubagentPresetsApi } from "@overlay/lib/workspace";
import {
  cloneSubagentPreset, isSubagentPresetConflict, newSubagentPresetDraft, subagentPresetCataloguePreview,
  subagentPresetDraft, subagentPresetDraftIsStale, subagentPresetModels, subagentPresetModelConfigConflict, subagentPresetOrderRequest,
  subagentPresetWorkerPreview, validateSubagentPresetDraft,
} from "@overlay/lib/subagent-presets";
import type { SubagentPresetDraft, SubagentPresetModelSelection, SubagentPresetReasoningLevel, WorkspaceSubagentPreset } from "@overlay/types/subagent-preset";

const selectClass = "h-9 w-full rounded-md border bg-background px-3 text-sm";

/** Controlled explicit-save form; a refetch never replaces its draft. Also used for clones/copies. */
export function SubagentPresetEditor({ draft, immutableKey, models, onChange, onSave, onCancel, busy, invalidModel, modelConflict = false, error, workspaceAllowsContext, workspaceChoices, workspaceIds, onWorkspaceIdsChange }: {
  draft: SubagentPresetDraft;
  immutableKey: boolean;
  models: Array<{ value: string; label: string }>;
  onChange: (draft: SubagentPresetDraft) => void;
  onSave: () => void;
  onCancel: () => void;
  busy: boolean;
  invalidModel: boolean;
  modelConflict?: boolean;
  error?: string | null;
  workspaceAllowsContext: boolean;
  workspaceChoices?: Array<{ id: string; name: string; disabledReason?: string }>;
  workspaceIds?: string[];
  onWorkspaceIdsChange?: (ids: string[]) => void;
}) {
  const { t } = useTranslation("workspace");
  const field = (key: "key" | "name" | "description", maxLength: number) => (
    <label className="grid gap-1 text-sm">
      {t(`presets.${key}`)}
      <Input value={draft[key]} maxLength={maxLength} disabled={key === "key" && immutableKey} required
        onChange={(event) => onChange({ ...draft, [key]: event.target.value })} />
    </label>
  );
  const config = draft.generationConfig;
  return (
    <form className="rounded-md border p-4" onSubmit={(event) => { event.preventDefault(); if (!busy) onSave(); }}>
      <fieldset disabled={busy} className="min-w-0 space-y-4">
      <div className="grid gap-3 sm:grid-cols-2">{field("key", 32)}{field("name", 48)}</div>
      <p className="text-xs text-muted-foreground">{t("presets.key_help")}</p>
      {field("description", 240)}
      <label className="grid gap-1 text-sm">
        {t("presets.system_prompt")}
        <Textarea value={draft.systemPrompt} required maxLength={32768} rows={8}
          onChange={(event) => onChange({ ...draft, systemPrompt: event.target.value })} />
      </label>
      <p className="text-xs text-muted-foreground">{t("presets.prompt_help")}</p>
      <label className="grid gap-1 text-sm">
        {t("presets.model_mode")}
        <select className={selectClass} value={draft.modelSelection} onChange={(event) => {
          const mode = event.target.value as SubagentPresetModelSelection;
          onChange({ ...draft, modelSelection: mode, modelId: mode === "fixed" ? draft.modelId : null });
        }}>
          {(["workspace_default", "parent", "fixed"] as const).map((mode) => <option key={mode} value={mode}>{t(`presets.model_modes.${mode}`)}</option>)}
        </select>
      </label>
      {draft.modelSelection === "fixed" ? (
        <label className="grid gap-1 text-sm">
          {t("presets.model")}
          <select className={selectClass} value={draft.modelId ?? ""} onChange={(event) => onChange({ ...draft, modelId: event.target.value || null })}>
            <option value="">{t("presets.select_model")}</option>
            {invalidModel && draft.modelId ? <option value={draft.modelId}>{t("presets.unavailable_model")} ({draft.modelId})</option> : null}
            {models.map((model) => <option key={model.value} value={model.value}>{model.label}</option>)}
          </select>
        </label>
      ) : null}
      {invalidModel ? <p role="alert" className="text-xs text-destructive">{t("presets.invalid_model_help")}</p> : null}
      {modelConflict ? <p role="alert" className="text-xs text-destructive">{t("presets.model_config_conflict")}</p> : null}
      <div className="grid gap-3 sm:grid-cols-3">
        {(["temperature", "topP", "maxTokens"] as const).map((key) => (
          <label key={key} className="grid gap-1 text-sm">
            {t(`presets.${key}`)}
            <Input type="number" value={config[key] ?? ""} step={key === "maxTokens" ? 1 : "any"}
              min={key === "maxTokens" ? 1 : 0}
              max={key === "temperature" ? 2 : key === "topP" ? 1 : 2147483647}
              placeholder={t("presets.inherit")}
              onChange={(event) => onChange({ ...draft, generationConfig: { ...config, [key]: event.target.value === "" ? null : Number(event.target.value) } })} />
          </label>
        ))}
      </div>
      <label className="grid gap-1 text-sm">
        {t("presets.reasoning")}
        <select className={selectClass} value={config.reasoningLevel} onChange={(event) => onChange({ ...draft, generationConfig: { ...config, reasoningLevel: event.target.value as SubagentPresetReasoningLevel } })}>
          {(["off", "auto", "low", "medium", "high", "xhigh", "max"] as const).map((level) => <option key={level} value={level}>{t(`presets.reasoning_levels.${level}`)}</option>)}
        </select>
      </label>
      <div className="flex flex-wrap gap-5 text-sm">
        <label className="flex items-center gap-2"><input type="checkbox" checked={draft.enabled} onChange={(event) => onChange({ ...draft, enabled: event.target.checked })} />{t("presets.enabled")}</label>
        <label className="flex items-center gap-2"><input type="checkbox" checked={draft.acceptContext} onChange={(event) => onChange({ ...draft, acceptContext: event.target.checked })} />{t("presets.accept_context")}</label>
      </div>
      <p className="text-xs text-muted-foreground">{t(workspaceAllowsContext ? "presets.context_help" : "presets.context_blocked")}</p>
      <label className="grid gap-1 text-sm">
        {t("presets.max_steps")}
        <Input type="number" min={1} max={120} step={1} required value={draft.maxSteps}
          onChange={(event) => onChange({ ...draft, maxSteps: Number(event.target.value) })} />
      </label>
      {workspaceChoices && onWorkspaceIdsChange ? <fieldset className="min-w-0 space-y-2">
        <legend className="text-sm">{t("library.workspaces")}</legend>
        <p className="text-xs text-muted-foreground">{t("library.assignment_help")}</p>
        {workspaceChoices.map((workspace) => <label key={workspace.id} className="flex items-start gap-2 text-sm">
          <input type="checkbox" checked={workspaceIds?.includes(workspace.id) ?? false} disabled={busy || !!workspace.disabledReason}
            onChange={(event) => onWorkspaceIdsChange(event.target.checked ? [...(workspaceIds ?? []), workspace.id] : (workspaceIds ?? []).filter((id) => id !== workspace.id))} />
          <span className="min-w-0 break-words">{workspace.name}{workspace.disabledReason ? <span className="block text-xs text-destructive">{workspace.disabledReason}</span> : null}</span>
        </label>)}
      </fieldset> : null}
      <details className="text-xs"><summary className="cursor-pointer">{t("presets.worker_preview")}</summary>
        <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words rounded bg-muted p-3">{subagentPresetWorkerPreview(draft)}</pre>
      </details>
      {error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={busy}><Save className="size-4" />{t("presets.save")}</Button>
        <Button type="button" variant="outline" disabled={busy} onClick={onCancel}><X className="size-4" />{t("presets.cancel")}</Button>
      </div>
      </fieldset>
    </form>
  );
}

interface EditorState {
  draft: SubagentPresetDraft;
  presetId?: string;
  baseVersion?: number;
  initial: SubagentPresetDraft;
  /** Copy is an explicit create in another workspace, never an overwrite. */
  copyTo?: string;
}

export function WorkspaceSubagentPresets({ workspaceId, workspaceAllowsContext, workspaceModelId }: {
  workspaceId: string;
  workspaceAllowsContext: boolean;
  workspaceModelId?: string | null;
}) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const providers = useSettingsStore((state) => state.settings?.providers);
  const models = React.useMemo(() => subagentPresetModels(providers), [providers]);
  const [editor, setEditor] = React.useState<EditorState | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [copying, setCopying] = React.useState(false);
  const query = useQuery({ queryKey: workspaceQueryKeys.presets(workspaceId), queryFn: () => workspaceSubagentPresetsApi.list(workspaceId) });
  const targets = useQuery({ queryKey: workspaceQueryKeys.list, queryFn: () => workspaceApi.list() });
  const destination = editor?.copyTo || workspaceId;
  const destinationQuery = useQuery({
    queryKey: workspaceQueryKeys.presets(destination), queryFn: () => workspaceSubagentPresetsApi.list(destination),
    enabled: copying && destination !== workspaceId,
  });
  const destinationWorkspace = targets.data?.find((w) => w.id === destination);
  const allPresets = query.data?.presets ?? [];
  const presets = allPresets.filter((preset) => !preset.libraryPresetId);
  const editingPresets = copying ? (destinationQuery.data?.presets ?? []) : allPresets;
  const latest = presets.find((p) => p.id === editor?.presetId);
  const stale = editor && subagentPresetDraftIsStale(editor.baseVersion, latest);
  const changed = editor && JSON.stringify(editor.initial) !== JSON.stringify(editor.draft);

  const replaceEditor = (draft: SubagentPresetDraft | null, preset?: WorkspaceSubagentPreset, copy = false) => {
    if (changed && !window.confirm(t("presets.discard_confirm"))) return;
    setCopying(copy);
    setEditor(draft ? { draft, initial: subagentPresetDraft(draft), presetId: preset?.id, baseVersion: preset?.version, copyTo: copy ? "" : undefined } : null);
    setError(null);
  };
  const invalidate = async (id: string) => {
    await queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.detail(id) });
    await queryClient.invalidateQueries({ queryKey: workspaceQueryKeys.list });
  };
  const execute = async (operation: () => Promise<unknown>, after?: () => void, id = workspaceId) => {
    setBusy(true); setError(null);
    try { await operation(); after?.(); await invalidate(id); }
    catch (caught) {
      setError(isSubagentPresetConflict(caught) ? t("presets.conflict") : caught instanceof Error ? caught.message : t("presets.save_failed"));
      if (isSubagentPresetConflict(caught)) void invalidate(id);
    } finally { setBusy(false); }
  };
  const save = () => {
    if (!editor) return;
    if (copying && (!editor.copyTo || destinationQuery.isPending || destinationQuery.isError || destinationQuery.data?.corrupt)) {
      setError(t("presets.copy_target_required")); return;
    }
    const validation = validateSubagentPresetDraft(editor.draft, editingPresets, editor.presetId);
    if (validation) { setError(t(`presets.errors.${validation}`)); return; }
    const invalidRef = editor.draft.modelSelection === "fixed" && !models.some((m) => m.value === editor.draft.modelId);
    const referenceUnchanged = latest?.modelSelection === "fixed" && latest.modelId === editor.draft.modelId;
    if (invalidRef && (!editor.presetId || !referenceUnchanged)) { setError(t("presets.errors.model_invalid")); return; }
    const draft = subagentPresetDraft(editor.draft);
    void execute(() => editor.presetId && editor.baseVersion !== undefined
      ? workspaceSubagentPresetsApi.update(workspaceId, editor.presetId, draft, editor.baseVersion)
      : workspaceSubagentPresetsApi.create(destination, draft), () => { setEditor(null); setCopying(false); }, destination);
  };

  const corrupt = query.data?.corrupt === true || (query.data != null && query.data.presets == null);
  const inheritedInvalid = workspaceModelId != null && !models.some((m) => m.value === workspaceModelId);
  const catalogue = editor && !copying
    ? [...presets.filter((p) => p.id !== editor.presetId), editor.draft]
    : presets;

  return (
    <section className="space-y-4 rounded-lg border p-4">
      <div><h2 className="text-sm font-medium">{t("library.local_title")}</h2><p className="text-xs text-muted-foreground">{t("library.local_help")}</p></div>
      <p className="text-xs text-muted-foreground">{t("presets.execution_notice")}</p>
      {query.isPending ? <p className="text-sm">{t("list.loading")}</p> : null}
      {query.isError ? <div role="alert" className="text-sm text-destructive">{t("presets.load_failed")} <Button variant="outline" onClick={() => void query.refetch()}>{t("presets.retry")}</Button></div> : null}
      {inheritedInvalid ? <p role="alert" className="text-xs text-destructive">{t("presets.workspace_model_invalid")}</p> : null}
      {corrupt ? (
        <div className="space-y-2"><p role="alert" className="text-sm text-destructive">{t("presets.corrupt")}</p>
          <Button variant="destructive" disabled={busy} onClick={() => {
            if (window.confirm(t("presets.reset_confirm"))) void execute(() => workspaceSubagentPresetsApi.reset(workspaceId), () => { setEditor(null); setCopying(false); });
          }}>{t("presets.reset")}</Button>
        </div>
      ) : query.data ? (
        <>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" disabled={busy || allPresets.length >= 16} onClick={() => replaceEditor(newSubagentPresetDraft())}>{t("presets.create")}</Button>
          </div>
          {presets.length === 0 ? <p className="text-xs text-muted-foreground">{t("presets.empty")}</p> : null}
          <ul className="space-y-2">
            {presets.map((preset, index) => {
              const invalid = preset.modelSelection === "fixed" && !models.some((m) => m.value === preset.modelId);
              return <li key={preset.id} className="space-y-2 rounded-md border p-3">
                <div className="flex flex-wrap items-center gap-2"><strong className="text-sm">{preset.name}</strong><code className="text-xs text-muted-foreground">{preset.key}</code>
                  <span className="text-xs text-muted-foreground">{t(preset.enabled ? "presets.enabled" : "presets.disabled")}</span>
                  <span className="text-xs text-muted-foreground">v{preset.version} / r{preset.executionRevision}</span>
                </div>
                <p className="text-xs text-muted-foreground">{preset.description}</p>
                {invalid ? <p role="alert" className="text-xs text-destructive">{t("presets.invalid_model_help")}</p> : null}
                {subagentPresetModelConfigConflict(preset, providers, workspaceModelId) ? <p role="alert" className="text-xs text-destructive">{t("presets.model_config_conflict")}</p> : null}
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" variant="outline" disabled={busy} onClick={() => replaceEditor(subagentPresetDraft(preset), preset)}>{t("presets.edit")}</Button>
                  <Button size="sm" variant="outline" disabled={busy || allPresets.length >= 16} onClick={() => replaceEditor(cloneSubagentPreset(preset, allPresets))}>{t("presets.clone")}</Button>
                  <Button size="sm" variant="outline" disabled={busy} onClick={() => replaceEditor(subagentPresetDraft(preset), undefined, true)}>{t("presets.copy")}</Button>
                  <Button size="sm" variant="outline" disabled={busy || allPresets.length !== presets.length || index === 0} aria-label={t("presets.move_up")} onClick={() => void execute(() => workspaceSubagentPresetsApi.reorder(workspaceId, subagentPresetOrderRequest(allPresets, preset.id, -1)))}>↑</Button>
                  <Button size="sm" variant="outline" disabled={busy || allPresets.length !== presets.length || index === presets.length - 1} aria-label={t("presets.move_down")} onClick={() => void execute(() => workspaceSubagentPresetsApi.reorder(workspaceId, subagentPresetOrderRequest(allPresets, preset.id, 1)))}>↓</Button>
                  <Button size="sm" variant="destructive" disabled={busy} onClick={() => {
                    if (window.confirm(t("presets.delete_confirm", { name: preset.name }))) void execute(() => workspaceSubagentPresetsApi.delete(workspaceId, preset.id, preset.version));
                  }}>{t("presets.delete")}</Button>
                </div>
              </li>;
            })}
          </ul>
          <details className="text-xs"><summary className="cursor-pointer">{t("presets.catalogue_preview")}</summary>
            <p className="mt-2 text-muted-foreground">{t("presets.catalogue_help")}</p>
            <pre className="mt-2 whitespace-pre-wrap break-words rounded bg-muted p-3">{subagentPresetCataloguePreview(catalogue) || t("presets.empty")}</pre>
          </details>
          {editor ? (
            <div className="space-y-3">
              {copying ? <label className="grid gap-1 text-sm">{t("presets.copy_target")}
                <select className={selectClass} value={editor.copyTo ?? ""} onChange={(event) => { setEditor({ ...editor, copyTo: event.target.value }); setError(null); }}>
                  <option value="">{t("presets.copy_target_required")}</option>
                  {(targets.data ?? []).filter((w) => w.id !== workspaceId).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
                </select>
                <span className="text-xs text-muted-foreground">{t("presets.copy_help")}</span>
                {destinationQuery.data?.corrupt ? <span role="alert" className="text-xs text-destructive">{t("presets.target_corrupt")}</span> : null}
              </label> : null}
              {stale ? <div role="alert" className="space-y-2 text-sm text-destructive">{t("presets.conflict")}
                <Button type="button" variant="outline" disabled={busy} onClick={() => latest ? replaceEditor(subagentPresetDraft(latest), latest) : replaceEditor(null)}>{t("presets.reload")}</Button>
              </div> : null}
              <SubagentPresetEditor draft={editor.draft} immutableKey={editor.presetId != null} models={models} busy={busy}
                workspaceAllowsContext={copying ? destinationWorkspace?.subagentAllowContext === true : workspaceAllowsContext}
                invalidModel={editor.draft.modelSelection === "fixed" && !models.some((m) => m.value === editor.draft.modelId)}
                modelConflict={subagentPresetModelConfigConflict(editor.draft, providers, copying ? destinationWorkspace?.subagentModelId : workspaceModelId)} error={error}
                onChange={(draft) => { setEditor({ ...editor, draft }); setError(null); }} onSave={save} onCancel={() => replaceEditor(null)} />
            </div>
          ) : null}
        </>
      ) : null}
      {!editor && error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
    </section>
  );
}
