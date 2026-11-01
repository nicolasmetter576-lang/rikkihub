import api from "~/services/api";
import { subagentPresetDraft } from "./subagent-presets";
import type { LibrarySubagentPreset, SubagentPresetDraft, SubagentPresetLibraryResponse, SubagentPresetsResponse, WorkspaceSubagentPreset } from "../types/subagent-preset";

export const subagentPresetLibraryQueryKey = ["subagent-preset-library"] as const;
const path = (id: string) => `subagent-presets/${encodeURIComponent(id)}`;
export const subagentPresetLibraryApi = {
  list: () => api.get<SubagentPresetLibraryResponse>("subagent-presets"),
  create: (draft: SubagentPresetDraft, workspaceIds: string[] = []) =>
    api.post<WorkspaceSubagentPreset>("subagent-presets", { ...subagentPresetDraft(draft), workspaceIds }),
  update: (id: string, draft: SubagentPresetDraft, expectedVersion: number, workspaceIds?: string[]) =>
    api.put<WorkspaceSubagentPreset>(path(id), { ...subagentPresetDraft(draft), expectedVersion, ...(workspaceIds === undefined ? {} : { workspaceIds }) }),
  delete: (id: string, expectedVersion: number) => api.delete<void>(path(id), { json: { expectedVersion } }),
  reset: () => api.post<SubagentPresetLibraryResponse>("subagent-presets/reset", { confirm: true }),
  setWorkspaceEnabled: (workspaceId: string, presetId: string, enabled: boolean, expectedVersion: number) =>
    api.post<WorkspaceSubagentPreset>(`workspaces/${encodeURIComponent(workspaceId)}/subagent-presets/${encodeURIComponent(presetId)}/enabled`, { enabled, expectedVersion }),
};

export function isLibraryPresetAssigned(preset: Pick<LibrarySubagentPreset, "id" | "workspaceIds">, workspaceId: string, response?: SubagentPresetsResponse): boolean {
  return response?.presets?.some((copy) => copy.libraryPresetId === preset.id) ?? preset.workspaceIds.includes(workspaceId);
}

/** Never replace a same-key local. Assigned roles remain removable even if disabled/full/corrupt. */
export function libraryPresetAssignmentBlock(preset: Pick<WorkspaceSubagentPreset, "id" | "key">, response?: SubagentPresetsResponse): "loading" | "corrupt" | "key_conflict" | "full" | undefined {
  if (!response) return "loading";
  if (response.corrupt || response.presets === null) return "corrupt";
  if (response.presets.some((copy) => copy.libraryPresetId === preset.id)) return undefined;
  if (response.presets.some((copy) => copy.key === preset.key)) return "key_conflict";
  if (response.presets.length >= 16) return "full";
  return undefined;
}

export function invalidateSubagentPresetLibrary(queries: { invalidateQueries(options: { queryKey: readonly string[] }): unknown }): void {
  void queries.invalidateQueries({ queryKey: subagentPresetLibraryQueryKey });
  void queries.invalidateQueries({ queryKey: ["workspaces"] });
}
