import { createId } from "./id";
import type {
  AssistantProfile,
  PromptSection,
  SystemPromptMode,
  BuiltInTool,
  LocalToolOption,
  LocalToolType,
  ClaudeProviderProfile,
  GoogleProviderProfile,
  InjectionPosition,
  InjectionRole,
  LorebookEntry,
  LorebookProfile,
  McpHeaderPair,
  McpServerConfig,
  ModeInjectionProfile,
  ModelAbility,
  ModelCapabilities,
  ModelModality,
  ModelType,
  OpenAIProviderProfile,
  ProviderModel,
  ProviderProfile,
  ProviderType,
  QuickMessage,
  ReasoningLevel,
  Settings,
} from "@overlay/types/settings";

export type { ProviderType };

export const DEFAULT_ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e";

export const DEFAULT_ASSISTANTS_IDS = [
  DEFAULT_ASSISTANT_ID,
  "3d47790c-c415-4b90-9388-751128adb0a0",
] as const;

export const BUILTIN_PROVIDER_IDS = [
  "1eeea727-9ee5-4cae-93e6-6fb01a4d051e",
  "7ce7e322-b995-4b0c-9d48-42e08dcfcdda",
  "19cdb024-3cd6-49d5-bcaf-c48dc48431b4",
  "3f9b2c71-6d4e-4a8b-9c15-7e2d0a6b8f43",
  "6ab18148-c138-4394-a46f-1cd8c8ceaa6d",
  "56a94d29-c88b-41c5-8e09-38a7612d6cf8",
  "f099ad5b-ef03-446d-8e78-7e36787f780b",
  "d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3",
  "d5734028-d39b-4d41-9841-fd648d65440e",
  "386e0f29-8228-4512-affe-8fd8add82d88",
  "da020a90-f7b3-4c29-b90e-c511a0630630",
  "f76cae46-069a-4334-ab8e-224e4979e58c",
  "3dfd6f9b-f9d9-417f-80c1-ff8d77184191",
  "3bc40dc1-b11a-46fa-863b-6306971223be",
  "f4f8870e-82d3-495b-9b64-d58e508b3b2c",
  "da93779f-3956-48cc-82ef-67bb482eaaf7",
  "ef5d149b-8e34-404b-818c-6ec242e5c3c5",
  "ff3cde7e-0f65-43d7-8fb2-6475c99f5990",
  "b4deabea-20fb-4101-a74c-65679c7e4754",
  "a2bafe83-eaf8-47bf-a8c7-3dd82d89f637",
] as const;

const BUILTIN_PROVIDER_ID_SET = new Set<string>(BUILTIN_PROVIDER_IDS);
const DEFAULT_ASSISTANT_ID_SET = new Set<string>(DEFAULT_ASSISTANTS_IDS);

export const REASONING_LEVELS: ReasoningLevel[] = [
  "off",
  "auto",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
];

export const INJECTION_POSITIONS: InjectionPosition[] = [
  "before_system_prompt",
  "after_system_prompt",
  "top_of_chat",
  "bottom_of_chat",
  "at_depth",
];

export const INJECTION_ROLES: InjectionRole[] = ["system", "user", "assistant", "tool"];

export const MODEL_TYPES: ModelType[] = ["CHAT", "IMAGE", "EMBEDDING"];
export const MODEL_MODALITIES: ModelModality[] = ["TEXT", "IMAGE"];
export const MODEL_ABILITIES: ModelAbility[] = ["TOOL", "REASONING"];
export const BUILTIN_TOOL_TYPES = ["search", "url_context", "image_generation"] as const;

/** Order mirrors LocalToolOption in LocalToolOption.kt; wire format is { type }. */
export const LOCAL_TOOL_TYPES: LocalToolType[] = [
  "javascript_engine",
  "time_info",
  "clipboard",
  "tts",
  "ask_user",
  "screen_time",
  "calendar",
  "chart_display",
];

export function normalizeLocalTools(tools: unknown): LocalToolOption[] | undefined {
  if (!Array.isArray(tools)) {
    return undefined;
  }
  const seen = new Set<string>();
  const normalized: LocalToolOption[] = [];
  for (const item of tools) {
    if (!item || typeof item !== "object") continue;
    const type = (item as { type?: unknown }).type;
    if (typeof type !== "string" || type.length === 0 || seen.has(type)) continue;
    seen.add(type);
    normalized.push({ type });
  }
  return normalized;
}

export function hasLocalTool(tools: LocalToolOption[] | undefined, type: string): boolean {
  return (tools ?? []).some((tool) => tool.type === type);
}

export function toggleLocalToolOption(
  tools: LocalToolOption[] | undefined,
  type: string,
  enabled: boolean,
): LocalToolOption[] {
  const current = tools ?? [];
  if (!enabled) {
    return current.filter((tool) => tool.type !== type);
  }
  if (hasLocalTool(current, type)) {
    return current;
  }
  return [...current, { type }];
}
export const PROVIDER_TYPES: ProviderType[] = ["openai", "google", "claude"];

export function isDefaultAssistant(id: string): boolean {
  return DEFAULT_ASSISTANT_ID_SET.has(id);
}

/** 创造助手（App 端 CreatorAssistantDefinition.ID）：不可删除/克隆，不能绑定工作区。 */
export const CREATOR_ASSISTANT_ID = "c0ae7a11-7a3d-4b2e-9f1a-2c4e5d6b7a90";

export function isCreatorAssistant(id: string): boolean {
  return id === CREATOR_ASSISTANT_ID;
}

export function isBuiltInProvider(id: string): boolean {
  return BUILTIN_PROVIDER_ID_SET.has(id);
}

export function getProviderType(provider: { type?: string } | null | undefined): ProviderType {
  switch (provider?.type) {
    case "google":
    case "claude":
    case "codex":
    case "devin":
    case "antigravity":
      return provider.type;
    default:
      return "openai";
  }
}

export function isOpenAIProvider(provider: ProviderProfile): provider is OpenAIProviderProfile {
  return getProviderType(provider) === "openai";
}

export function isGoogleProvider(provider: ProviderProfile): provider is GoogleProviderProfile {
  return getProviderType(provider) === "google";
}

export function isClaudeProvider(provider: ProviderProfile): provider is ClaudeProviderProfile {
  return getProviderType(provider) === "claude";
}

export function parseMcpHeaders(headers: unknown): McpHeaderPair[] {
  if (!Array.isArray(headers)) {
    return [];
  }

  return headers.flatMap((item): McpHeaderPair[] => {
    if (Array.isArray(item) && typeof item[0] === "string") {
      return [{ first: item[0], second: String(item[1] ?? "") }];
    }
    if (item && typeof item === "object") {
      const record = item as { first?: unknown; second?: unknown; valueConfigured?: unknown };
      if (typeof record.first === "string") {
        const valueConfigured = record.valueConfigured === true;
        // Masked projection echo: ""+configured → preserve marker (null second).
        if (valueConfigured && (record.second === "" || record.second == null)) {
          return [{ first: record.first, second: null, valueConfigured: true }];
        }
        return [{ first: record.first, second: String(record.second ?? "") }];
      }
    }
    return [];
  });
}

export function serializeMcpHeaders(headers: McpHeaderPair[]): McpHeaderPair[] {
  return headers.filter((header) => header.first.trim().length > 0);
}

export function listChatModels(settings: Settings | null | undefined): Array<{
  provider: ProviderProfile;
  model: ProviderModel;
}> {
  if (!settings) {
    return [];
  }
  return settings.providers
    .filter((provider) => provider.enabled)
    .flatMap((provider) =>
      provider.models
        .filter((model) => model.type === "CHAT")
        .map((model) => ({ provider, model })),
    );
}

export function newModeInjection(): ModeInjectionProfile {
  return {
    type: "mode",
    id: createId(),
    name: "",
    enabled: true,
    priority: 0,
    position: "after_system_prompt",
    content: "",
    injectDepth: 4,
    role: "user",
  };
}

export function newLorebook(): LorebookProfile {
  return {
    id: createId(),
    name: "",
    description: "",
    enabled: true,
    entries: [],
  };
}

export function newLorebookEntry(): LorebookEntry {
  return {
    type: "regex",
    id: createId(),
    name: "",
    enabled: true,
    priority: 0,
    position: "after_system_prompt",
    content: "",
    injectDepth: 4,
    role: "user",
    keywords: [],
    useRegex: false,
    caseSensitive: false,
    scanDepth: 4,
    constantActive: false,
  };
}

export function newQuickMessage(): QuickMessage {
  return {
    id: createId(),
    title: "",
    content: "",
  };
}

export function newPromptSection(): PromptSection {
  return {
    id: createId(),
    title: "",
    content: "",
    enabled: true,
  };
}

export function promptSectionsOf(assistant: Pick<AssistantProfile, "promptSections">): PromptSection[] {
  return Array.isArray(assistant.promptSections) ? assistant.promptSections : [];
}

export function systemPromptModeOf(assistant: Pick<AssistantProfile, "systemPromptMode">): SystemPromptMode {
  return assistant.systemPromptMode === "sections" ? "sections" : "whole";
}

const SECTION_SEPARATOR = "\n\n";

/** Enabled, non-blank sections joined in order with a blank line; titles are not sent. */
export function composePromptSections(sections: PromptSection[]): string {
  return sections
    .filter((section) => section.enabled && section.content.trim() !== "")
    .map((section) => section.content)
    .join(SECTION_SEPARATOR);
}

/**
 * The assistant's own system prompt as the app sends it (no conversation override). Whole and
 * sections are two views of one prompt: with sections, systemPrompt is their joined text.
 * Mirrors `Assistant.composedSystemPrompt()`.
 */
export function composeSystemPrompt(
  assistant: Pick<AssistantProfile, "systemPrompt" | "promptSections">,
): string {
  const sections = promptSectionsOf(assistant);
  return sections.length === 0 ? (assistant.systemPrompt ?? "") : composePromptSections(sections);
}

/** New sections; the whole prompt follows as their joined text. Mirrors `Assistant.withPromptSections()`. */
export function withPromptSections(sections: PromptSection[]): Pick<AssistantProfile, "systemPrompt" | "promptSections"> {
  return { promptSections: sections, systemPrompt: composePromptSections(sections) };
}

/** A piece of the whole view that belongs to one section; the whole text joins them with a blank line. */
export interface PromptSegment {
  sectionId: string;
  text: string;
}

/** Initial segments of the whole view. Mirrors `Assistant.systemPromptSegments()`. */
export function promptSegmentsOf(sections: PromptSection[], fallbackId: string): PromptSegment[] {
  const visible = sections.filter((section) => section.enabled && section.content.trim() !== "");
  if (visible.length > 0) return visible.map((section) => ({ sectionId: section.id, text: section.content }));
  return [{ sectionId: sections.find((section) => section.enabled)?.id ?? fallbackId, text: "" }];
}

/**
 * Re-splits the whole text after an edit: separators the edit did not touch stay, the edited piece goes
 * to its section, and pieces merged by a removed separator go to the first one (or the last when the first
 * was deleted entirely). Mirrors `remapSystemPromptSegments()`.
 */
export function remapPromptSegments(
  segments: PromptSegment[],
  newText: string,
): { segments: PromptSegment[]; cleared: string[] } {
  const oldText = segments.map((segment) => segment.text).join(SECTION_SEPARATOR);
  if (oldText === newText) return { segments, cleared: [] };
  const common = Math.min(oldText.length, newText.length);
  let prefix = 0;
  while (prefix < common && oldText[prefix] === newText[prefix]) prefix++;
  let suffix = 0;
  while (suffix < common - prefix && oldText[oldText.length - 1 - suffix] === newText[newText.length - 1 - suffix]) suffix++;
  const oldEnd = oldText.length - suffix;
  const delta = newText.length - oldText.length;
  const sep = SECTION_SEPARATOR.length;
  const starts: number[] = [];
  const ends: number[] = [];
  segments.forEach((segment, i) => {
    starts.push(i === 0 ? 0 : ends[i - 1] + sep);
    ends.push(starts[i] + segment.text.length);
  });
  const survives = (k: number) => !(prefix < ends[k] + sep && oldEnd > ends[k]);
  const result: PromptSegment[] = [];
  const cleared: string[] = [];
  let groupStart = 0;
  let textStart = 0;
  segments.forEach((_, k) => {
    const last = k === segments.length - 1;
    if (!last && !survives(k)) return;
    const textEnd = last ? newText.length : ends[k] + sep <= prefix ? ends[k] : ends[k] + delta;
    let keeper = groupStart;
    if (groupStart !== k) {
      const head = Math.min(prefix, ends[groupStart]) - starts[groupStart];
      const tail = ends[k] - Math.max(oldEnd, starts[k]);
      if (head <= 0 && tail > 0) keeper = k;
    }
    result.push({ ...segments[keeper], text: newText.slice(textStart, textEnd) });
    for (let m = groupStart; m <= k; m++) if (m !== keeper) cleared.push(segments[m].sectionId);
    textStart = textEnd + sep;
    groupStart = k + 1;
  });
  return { segments: result, cleared };
}

/** Writes segments back into the sections. Mirrors `Assistant.withSystemPromptSegments()`. */
export function withPromptSegments(
  sections: PromptSection[],
  segments: PromptSegment[],
  cleared: Iterable<string>,
): Pick<AssistantProfile, "systemPrompt" | "promptSections"> {
  const texts = new Map(segments.map((segment) => [segment.sectionId, segment.text]));
  const clearedIds = new Set(cleared);
  const updated = sections.map((section) =>
    texts.has(section.id)
      ? { ...section, content: texts.get(section.id)! }
      : clearedIds.has(section.id) ? { ...section, content: "" } : section,
  );
  const added = segments
    .filter((segment) => segment.text !== "" && !sections.some((section) => section.id === segment.sectionId))
    .map((segment) => ({ id: segment.sectionId, title: "", content: segment.text, enabled: true }));
  return withPromptSections([...updated, ...added]);
}

/**
 * Switches the editor view. Switching to sections with none yet seeds the whole prompt as the first
 * one, so the request stays the same. Mirrors `Assistant.switchSystemPromptMode()`.
 */
export function switchSystemPromptMode(
  assistant: Pick<AssistantProfile, "systemPrompt" | "systemPromptMode" | "promptSections">,
  mode: SystemPromptMode,
  seedTitle: string,
): Pick<AssistantProfile, "systemPromptMode" | "promptSections"> {
  const sections = promptSectionsOf(assistant);
  const whole = assistant.systemPrompt ?? "";
  const seeded =
    mode === "sections" && sections.length === 0 && whole.trim() !== ""
      ? [{ ...newPromptSection(), title: seedTitle, content: whole }]
      : sections;
  return { systemPromptMode: mode, promptSections: seeded };
}

/** Moves the section at [index] by [delta]; out-of-range moves return the same list. */
export function movePromptSection(sections: PromptSection[], index: number, delta: number): PromptSection[] {
  const target = index + delta;
  if (index < 0 || index >= sections.length || target < 0 || target >= sections.length) return sections;
  const next = [...sections];
  const [moved] = next.splice(index, 1);
  next.splice(target, 0, moved);
  return next;
}

export function newMcpServer(type: "sse" | "streamable_http"): McpServerConfig {
  return {
    id: createId(),
    type,
    url: "",
    commonOptions: {
      enable: true,
      name: "",
      headers: [],
      tools: [],
    },
  };
}

export function newProviderModel(): ProviderModel {
  return {
    id: createId(),
    modelId: "",
    displayName: "",
    type: "CHAT",
    inputModalities: ["TEXT"],
    outputModalities: ["TEXT"],
    abilities: [],
    tools: [],
  };
}

/** Same bounds as the app (Model.kt). */
const DETECTED_CONTEXT_MIN = 1;
const DETECTED_CONTEXT_MAX = 100_000_000;

/** Model with the capabilities recognised for its id; an unknown context window leaves the current one. */
export function applyModelCapabilities(model: ProviderModel, capabilities: ModelCapabilities): ProviderModel {
  const window = capabilities.contextLength;
  const knownWindow =
    typeof window === "number" && Number.isInteger(window) && window >= DETECTED_CONTEXT_MIN && window <= DETECTED_CONTEXT_MAX;
  return {
    ...model,
    inputModalities: capabilities.inputModalities,
    outputModalities: capabilities.outputModalities,
    abilities: capabilities.abilities,
    ...(knownWindow ? { contextLength: window } : {}),
  };
}

/**
 * Whether typing a model id should look up its capabilities: only for a model being created, with an id,
 * and until the user has set modalities, abilities or the window by hand.
 */
export function shouldDetectCapabilities(isNew: boolean, modelId: string, editedByHand: boolean): boolean {
  return isNew && !editedByHand && modelId.trim().length > 0;
}

export function toggleListValue<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}

export function toggleBuiltInTool(tools: BuiltInTool[] | undefined, type: string): BuiltInTool[] {
  const current = tools ?? [];
  const exists = current.some((tool) => tool.type === type);
  if (exists) {
    return current.filter((tool) => tool.type !== type);
  }
  return [...current, { type }];
}

export function hasBuiltInTool(tools: BuiltInTool[] | undefined, type: string): boolean {
  return (tools ?? []).some((tool) => tool.type === type);
}

export function parseOptionalNumber(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return null;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}
