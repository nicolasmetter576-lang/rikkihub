import type { ProviderProfile } from "../types/settings";
import type { SubagentPresetDraft, WorkspaceSubagentPreset } from "../types/subagent-preset";

export const SUBAGENT_WORKER_PREAMBLE = [
  "You are a workspace subagent executing a delegated task for a parent assistant.",
  "Finish the task using the available workspace tools, then stop with a concise report of what you did and which files you changed.",
  "Do not ask the user questions. You cannot dispatch further subagents.",
].join("\n");

export function newSubagentPresetDraft(): SubagentPresetDraft {
  return {
    key: "", name: "", description: "", systemPrompt: "", enabled: true,
    modelSelection: "workspace_default", modelId: null,
    generationConfig: { temperature: null, topP: null, maxTokens: null, reasoningLevel: "auto" },
    acceptContext: true, maxSteps: 24,
  };
}

/** Only editable fields leave the editor. Never copy IDs, CAS versions or provider credentials. */
export function subagentPresetDraft(preset: SubagentPresetDraft): SubagentPresetDraft {
  return {
    key: preset.key, name: preset.name, description: preset.description, systemPrompt: preset.systemPrompt,
    enabled: preset.enabled, modelSelection: preset.modelSelection, modelId: preset.modelId,
    generationConfig: {
      temperature: preset.generationConfig.temperature ?? null,
      topP: preset.generationConfig.topP ?? null,
      maxTokens: preset.generationConfig.maxTokens ?? null,
      reasoningLevel: preset.generationConfig.reasoningLevel ?? "auto",
    },
    acceptContext: preset.acceptContext, maxSteps: preset.maxSteps,
  };
}

export function uniqueSubagentPresetKey(key: string, presets: readonly WorkspaceSubagentPreset[]): string {
  const used = new Set(presets.map((p) => p.key));
  const base = key.replace(/[^a-z0-9_-]/g, "").replace(/^[^a-z]+/, "").slice(0, 32) || "role";
  if (!used.has(base) && base !== "default") return base;
  for (let index = 2; ; index++) {
    const suffix = `_${index}`;
    const candidate = `${base.slice(0, 32 - suffix.length)}${suffix}`;
    if (!used.has(candidate)) return candidate;
  }
}

export function cloneSubagentPreset(preset: WorkspaceSubagentPreset, presets: readonly WorkspaceSubagentPreset[]): SubagentPresetDraft {
  return { ...subagentPresetDraft(preset), key: uniqueSubagentPresetKey(preset.key, presets) };
}

/** No provider object (which can contain API keys) is retained in the model picker. */
export function subagentPresetModels(providers: readonly ProviderProfile[] = []): Array<{ value: string; label: string }> {
  return providers.filter((p) => p.enabled).flatMap((p) => p.models
    .filter((m) => m.type === "CHAT" && (m.abilities ?? []).includes("TOOL") && !disabledModelOverwrite(m.providerOverwrite))
    .map((m) => ({ value: m.id, label: `${p.name} / ${m.displayName || m.modelId}` })));
}

function disabledModelOverwrite(value: unknown): boolean {
  return typeof value === "object" && value !== null && "enabled" in value && value.enabled === false;
}

/** UI diagnostic for known references. Parent-selected models are validated authoritatively at dispatch. */
export function subagentPresetModelConfigConflict(
  draft: SubagentPresetDraft, providers: readonly ProviderProfile[] = [], workspaceModelId?: string | null,
): boolean {
  const id = draft.modelSelection === "fixed" ? draft.modelId
    : draft.modelSelection === "workspace_default" ? workspaceModelId : null;
  if (!id) return false;
  const model = providers.flatMap((provider) => provider.models).find((candidate) => candidate.id === id);
  const configured = new Set<string>();
  if (draft.generationConfig.temperature != null) configured.add("temperature");
  if (draft.generationConfig.topP != null) ["top_p", "topP"].forEach((key) => configured.add(key));
  if (draft.generationConfig.maxTokens != null) ["max_tokens", "max_completion_tokens", "max_output_tokens", "maxOutputTokens", "maxTokens"].forEach((key) => configured.add(key));
  const explicitReasoning = (draft.generationConfig.reasoningLevel ?? "auto") !== "auto";
  if (explicitReasoning) ["reasoning_effort", "reasoning", "thinking", "enable_thinking", "thinking_mode", "thinking_budget", "thinkingConfig", "thinking_config", "thinkingBudget", "thinkingLevel", "includeThoughts"].forEach((key) => configured.add(key));
  const roleFields = new Set(["messages", "system", "instructions", "input", "contents", "systemInstruction", "system_instruction"]);
  return (model?.customBodies ?? []).some((body) => {
    const path = body.key.split(/[./\[\]]/).filter(Boolean);
    if (roleFields.has(path[0] ?? "") || (body.key === "stream" && body.value !== true)) return true;
    const objectValue = typeof body.value === "object" && body.value !== null && !Array.isArray(body.value)
      ? body.value as Record<string, unknown> : null;
    if (explicitReasoning && ((body.key === "output_config" && (objectValue == null || "effort" in objectValue)) || body.key.startsWith("output_config.effort"))) return true;
    if (path.some((key) => configured.has(key))) return true;
    return configured.size > 0 && ["generationConfig", "generation_config"].includes(body.key)
      && (objectValue == null || Object.keys(objectValue).some((key) => configured.has(key)));
  });
}

export function validateSubagentPresetDraft(draft: SubagentPresetDraft, presets: readonly WorkspaceSubagentPreset[], editingId?: string): string | null {
  if (!/^[a-z][a-z0-9_-]{0,31}$/.test(draft.key) || draft.key === "default") return "key_invalid";
  if (presets.some((p) => p.key === draft.key && p.id !== editingId)) return "key_duplicate";
  if (!draft.name.trim() || draft.name.trim().length > 48) return "name_invalid";
  if (!draft.description.trim() || draft.description.trim().length > 240) return "description_invalid";
  if (!draft.systemPrompt.trim() || draft.systemPrompt.length > 32768) return "prompt_invalid";
  if (draft.modelSelection === "fixed" && !draft.modelId) return "model_invalid";
  const { temperature, topP, maxTokens } = draft.generationConfig;
  if (temperature != null && (!Number.isFinite(temperature) || temperature < 0 || temperature > 2)) return "params_invalid";
  if (topP != null && (!Number.isFinite(topP) || topP <= 0 || topP > 1)) return "params_invalid";
  if (maxTokens != null && (!Number.isSafeInteger(maxTokens) || maxTokens <= 0 || maxTokens > 2147483647)) return "params_invalid";
  if (!Number.isInteger(draft.maxSteps) || draft.maxSteps < 1 || draft.maxSteps > 120) return "steps_invalid";
  return null;
}

export function subagentPresetOrderRequest(presets: readonly WorkspaceSubagentPreset[], presetId: string, delta: -1 | 1) {
  const ordered = [...presets].sort((a, b) => a.sortOrder - b.sortOrder);
  const index = ordered.findIndex((p) => p.id === presetId);
  const target = index + delta;
  if (index >= 0 && target >= 0 && target < ordered.length) [ordered[index], ordered[target]] = [ordered[target], ordered[index]];
  return { presetIds: ordered.map((p) => p.id), expectedVersions: Object.fromEntries(ordered.map((p) => [p.id, p.version])) };
}

export function subagentPresetWorkerPreview(draft: SubagentPresetDraft): string {
  return `${SUBAGENT_WORKER_PREAMBLE}\n\n${draft.systemPrompt}`;
}

/** Informational parent catalogue preview: role prompts are deliberately excluded. */
export function subagentPresetCataloguePreview(presets: readonly SubagentPresetDraft[]): string {
  return presets.filter((p) => p.enabled).map((p) => `${p.key}: ${p.name} — ${p.description}`).join("\n");
}

/** Refetch never overwrites a local draft. A different server version is visible as a conflict. */
export function subagentPresetDraftIsStale(baseVersion: number | undefined, latest: WorkspaceSubagentPreset | undefined): boolean {
  return baseVersion !== undefined && latest?.version !== baseVersion;
}

export function isSubagentPresetConflict(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === 409;
}
