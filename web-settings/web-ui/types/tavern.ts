/**
 * Tavern (SillyTavern-compatible) data as the server sends it: prompt presets, prompt entries, regex scripts and
 * world book entries keep SillyTavern's own field names, and unknown fields round-trip untouched.
 * @see app/src/main/java/me/rerere/rikkihub/data/model/TavernModel.kt
 * @see tavern/src/main/kotlin/me/rerere/tavern
 */

export type PromptRole = "system" | "user" | "assistant";

/** One prompt in a preset or in the prompt library (`prompts[]` element of a SillyTavern preset). */
export interface PromptEntry {
  identifier: string;
  name?: string | null;
  role?: PromptRole | null;
  content?: string | null;
  system_prompt?: boolean | null;
  marker?: boolean | null;
  /** 0 = relative (by layout order), 1 = in chat (by depth and order). */
  injection_position?: number | null;
  injection_depth?: number | null;
  injection_order?: number | null;
  injection_trigger?: string[] | null;
  forbid_overrides?: boolean | null;
  /** rikkihub's own library data (usable switch, anchor); present on prompt library entries. */
  rikkihub?: { enabled?: boolean; anchor?: LibraryAnchor };
  [key: string]: unknown;
}

export type LibraryAnchor = "before_system" | "after_system" | "before_history";

export interface PromptOrderEntry {
  identifier: string;
  enabled?: boolean | null;
  [key: string]: unknown;
}

export interface PromptOrderList {
  character_id: string | number;
  order: PromptOrderEntry[];
  [key: string]: unknown;
}

/** An assistant's preset. Option keys are only present when they differ from the defaults (see `lib/tavern.ts`). */
export interface PromptPreset {
  prompts: PromptEntry[];
  prompt_order: PromptOrderList[];
  openai_max_context?: number;
  openai_max_tokens?: number;
  squash_system_messages?: boolean;
  new_chat_prompt?: string;
  wi_format?: string;
  /** Assistant-scoped regex scripts live in `extensions.regex_scripts`. */
  extensions?: { regex_scripts?: RegexScript[]; [key: string]: unknown };
  [key: string]: unknown;
}

export interface RegexScript {
  id: string;
  scriptName: string;
  findRegex: string;
  replaceString: string;
  trimStrings: string[];
  /** 1 user input, 2 AI output, 5 world info, 6 reasoning. */
  placement: number[];
  disabled: boolean;
  markdownOnly: boolean;
  promptOnly: boolean;
  runOnEdit: boolean;
  /** 0 none, 1 raw, 2 escaped. */
  substituteRegex: number;
  minDepth: number | null;
  maxDepth: number | null;
  [key: string]: unknown;
}

export interface WorldInfoCharacterFilter {
  isExclude?: boolean;
  names?: string[];
  tags?: string[];
}

/** A world book entry (SillyTavern `newWorldInfoEntryDefinition` fields). */
export interface WorldInfoEntry {
  uid: number;
  key: string[];
  keysecondary: string[];
  comment: string;
  content: string;
  constant: boolean;
  vectorized: boolean;
  selective: boolean;
  selectiveLogic: number;
  addMemo: boolean;
  order: number;
  position: number;
  disable: boolean;
  ignoreBudget: boolean;
  excludeRecursion: boolean;
  preventRecursion: boolean;
  matchPersonaDescription: boolean;
  matchCharacterDescription: boolean;
  matchCharacterPersonality: boolean;
  matchCharacterDepthPrompt: boolean;
  matchScenario: boolean;
  matchCreatorNotes: boolean;
  delayUntilRecursion: number;
  probability: number;
  useProbability: boolean;
  depth: number;
  outletName: string;
  group: string;
  groupOverride: boolean;
  groupWeight: number;
  scanDepth: number | null;
  caseSensitive: boolean | null;
  matchWholeWords: boolean | null;
  useGroupScoring: boolean | null;
  automationId: string;
  role: number | null;
  sticky: number | null;
  cooldown: number | null;
  delay: number | null;
  triggers: string[];
  displayIndex?: number | null;
  characterFilter?: WorldInfoCharacterFilter | null;
  [key: string]: unknown;
}

/** What the settings snapshot carries for a world book; entries come from `GET /api/lorebooks/{id}`. */
export interface LorebookSummary {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  entryCount: number;
  /** Fingerprint of the entries; changes when any entry changes. */
  rev: string;
}

export interface Lorebook extends LorebookSummary {
  entries: WorldInfoEntry[];
}

/** `world_info_*` keys of the global world info settings. */
export interface WorldInfoSettings {
  world_info_depth: number;
  world_info_min_activations: number;
  world_info_min_activations_depth_max: number;
  world_info_budget: number;
  world_info_include_names: boolean;
  world_info_recursive: boolean;
  world_info_overflow_alert: boolean;
  world_info_case_sensitive: boolean;
  world_info_match_whole_words: boolean;
  world_info_use_group_scoring: boolean;
  world_info_character_strategy: number;
  world_info_budget_cap: number;
  world_info_max_recursion_steps: number;
}

export interface AuthorsNoteDefaults {
  prompt: string;
  interval: number;
  depth: number;
  /** 0 after the prompt, 1 in chat, 2 before the prompt. */
  position: number;
  /** 0 system, 1 user, 2 assistant. */
  role: number;
}

export interface TavernSettings {
  worldInfo: WorldInfoSettings;
  authorsNote: AuthorsNoteDefaults;
  /** Values as SillyTavern stores them: scalars as-is, objects as JSON strings. */
  globalVariables: Record<string, unknown>;
  regexScripts: RegexScript[];
  regexAllowed: string[];
  /** The user's persona description (the nickname is `displaySetting.userNickname`). */
  personaDescription?: string;
}

/** A conversation's author's note and chat variables (`GET /api/conversations/{id}/tavern`). */
export interface ConversationTavernState {
  note: AuthorsNoteDefaults & { own: boolean };
  variables: Record<string, unknown>;
}

export interface RegexCheckResult {
  status: "empty" | "ok" | "skipped" | "invalid";
  message?: string;
}

/** One activated world book entry recorded on a reply. */
export interface RecordedActivation {
  bookId: string;
  bookName: string;
  uid: number;
  title: string;
  position: number;
  order: number;
}

export interface ActivationSummary {
  entries: RecordedActivation[];
  more: number;
  total: number;
}

/** The reply to adding or changing one entry: the entry as the server now has it, and the book's new fingerprint. */
export interface EntryResult {
  entry: WorldInfoEntry;
  rev: string;
}
