export type SubagentPresetModelSelection = "workspace_default" | "parent" | "fixed";
export type SubagentPresetReasoningLevel = "off" | "auto" | "low" | "medium" | "high" | "xhigh" | "max";
export interface SubagentPresetGenerationConfig {
  temperature: number | null;
  topP: number | null;
  maxTokens: number | null;
  reasoningLevel: SubagentPresetReasoningLevel;
}
export interface WorkspaceSubagentPreset {
  id: string;
  key: string;
  name: string;
  description: string;
  systemPrompt: string;
  enabled: boolean;
  modelSelection: SubagentPresetModelSelection;
  modelId: string | null;
  generationConfig: SubagentPresetGenerationConfig;
  acceptContext: boolean;
  maxSteps: number;
  sortOrder: number;
  version: number;
  executionRevision: number;
  /** Server-owned link. Missing/null means an independent local or canonical definition. */
  libraryPresetId?: string | null;
}
export type SubagentPresetDraft = Omit<WorkspaceSubagentPreset, "id" | "version" | "executionRevision" | "sortOrder" | "libraryPresetId">;
export interface LibrarySubagentPreset extends WorkspaceSubagentPreset {
  workspaceIds: string[];
}
export interface SubagentPresetLibraryResponse {
  presets: LibrarySubagentPreset[] | null;
  corrupt: boolean;
}
export interface SubagentPresetsUpdateEvent { timestamp: number; }
export interface SubagentPresetsResponse {
  presets: WorkspaceSubagentPreset[] | null;
  corrupt: boolean;
}
