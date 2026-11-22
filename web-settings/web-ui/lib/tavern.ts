import { createId } from "./id";
import type {
  ActivationSummary,
  LibraryAnchor,
  PromptEntry,
  PromptOrderEntry,
  PromptPreset,
  PromptRole,
  RecordedActivation,
  RegexScript,
  WorldInfoEntry,
} from "../types/tavern";

/*
 * Line-by-line ports of the App's editing rules, so both ends write the same data. The cases in
 * `app/src/test/resources/tavern/web-cases.json` run on both sides:
 *   TavernEditing.kt / WorldInfoEditing.kt / TavernModel.kt  ↔  this file.
 */

// ---------------------------------------------------------------------------------------------
// Prompt entries

/** `PromptMarkers`. */
export const MARKER_MAIN = "main";
export const MARKER_JAILBREAK = "jailbreak";
export const MARKER_RIKKIHUB_CONTEXT = "rikkihubContext";

/** `InjectionPosition`. */
export const INJECTION_RELATIVE = 0;
export const INJECTION_ABSOLUTE = 1;
export const DEFAULT_INJECTION_DEPTH = 4;
export const DEFAULT_INJECTION_ORDER = 100;

/** `GenerationType` wire names, in the engine's order. */
export const GENERATION_TYPES = ["normal", "continue", "impersonate", "swipe", "regenerate", "quiet"] as const;
export type GenerationTypeName = (typeof GENERATION_TYPES)[number];
/** The types rikkihub can actually produce; the others stay in the data when an entry already has them. */
export const VISIBLE_TRIGGERS: GenerationTypeName[] = ["normal", "regenerate", "quiet"];

export type EntryPlacement = "relative" | "in_chat";

export function entryPlacement(entry: PromptEntry): EntryPlacement {
  return entry.injection_position === INJECTION_ABSOLUTE ? "in_chat" : "relative";
}

export function entryDepth(entry: PromptEntry): number {
  return entry.injection_depth ?? DEFAULT_INJECTION_DEPTH;
}

export function entryOrder(entry: PromptEntry): number {
  return entry.injection_order ?? DEFAULT_INJECTION_ORDER;
}

/** Depth and order are always written, so switching in chat → relative → in chat keeps them. Depth is never negative. */
export function withPlacement(
  entry: PromptEntry,
  placement: EntryPlacement,
  options: { depth?: number; order?: number } = {},
): PromptEntry {
  const depth = options.depth ?? entryDepth(entry);
  const order = options.order ?? entryOrder(entry);
  return {
    ...entry,
    injection_position: placement === "in_chat" ? INJECTION_ABSOLUTE : INJECTION_RELATIVE,
    injection_depth: Math.max(depth, 0),
    injection_order: order,
  };
}

/** Selected trigger types; an empty list in the data means every type. */
export function entryTriggers(entry: PromptEntry): GenerationTypeName[] {
  const present = new Set(entry.injection_trigger ?? []);
  return GENERATION_TYPES.filter((type) => present.has(type));
}

/** None selected or all six selected store `[]` (every type); type names this app does not know stay at the end. */
export function withTriggers(entry: PromptEntry, selected: Iterable<string>): PromptEntry {
  const chosen = new Set(selected);
  const known = new Set<string>(GENERATION_TYPES);
  const unknown = (entry.injection_trigger ?? []).filter((wire) => !known.has(wire));
  const picked = GENERATION_TYPES.filter((type) => chosen.has(type)).length === GENERATION_TYPES.length
    ? []
    : GENERATION_TYPES.filter((type) => chosen.has(type));
  return { ...entry, injection_trigger: [...picked, ...unknown] };
}

/** Toggle one chip, the way the App does (`withTriggers(if (type in triggers) triggers - type else triggers + type)`). */
export function toggleTrigger(entry: PromptEntry, type: GenerationTypeName): PromptEntry {
  const current = entryTriggers(entry);
  return withTriggers(entry, current.includes(type) ? current.filter((t) => t !== type) : [...current, type]);
}

export function libraryEnabled(entry: PromptEntry): boolean {
  return entry.rikkihub?.enabled ?? true;
}

export function libraryAnchor(entry: PromptEntry): LibraryAnchor | undefined {
  return entry.rikkihub?.anchor;
}

export function withLibraryMeta(
  entry: PromptEntry,
  meta: { enabled?: boolean; anchor?: LibraryAnchor },
): PromptEntry {
  const enabled = meta.enabled ?? libraryEnabled(entry);
  const anchor = "anchor" in meta ? meta.anchor : libraryAnchor(entry);
  return { ...entry, rikkihub: anchor ? { enabled, anchor } : { enabled } };
}

/** A new library entry: relative, after the system prompt. The server assigns the real identifier. */
export function newLibraryEntry(name: string): PromptEntry {
  return withLibraryMeta(
    {
      identifier: createId(),
      name,
      role: "system",
      content: "",
      system_prompt: false,
      marker: false,
      injection_position: INJECTION_RELATIVE,
      injection_depth: DEFAULT_INJECTION_DEPTH,
      injection_order: 0,
    },
    { enabled: true, anchor: "after_system" },
  );
}

/** A new text entry for an assistant's layout. */
export function newPromptTextEntry(name: string): PromptEntry {
  return { identifier: createId(), name, role: "system", content: "", system_prompt: false, marker: false };
}

// ---------------------------------------------------------------------------------------------
// Preset layout

export const GLOBAL_ORDER_ID = 100001;

/** The order SillyTavern falls back to when a preset has none (`PromptPreset.defaultOrder`). */
const DEFAULT_ORDER: PromptOrderEntry[] = [
  ["main", true], ["worldInfoBefore", true], ["personaDescription", true], ["charDescription", true],
  ["charPersonality", true], ["scenario", true], ["enhanceDefinitions", false], ["nsfw", true],
  ["worldInfoAfter", true], ["dialogueExamples", true], ["chatHistory", true], ["jailbreak", true],
].map(([identifier, enabled]) => ({ identifier: identifier as string, enabled: enabled as boolean }));

export interface LayoutEntry {
  entry: PromptEntry;
  enabled: boolean;
}

function globalOrderList(preset: PromptPreset) {
  return (preset.prompt_order ?? []).find((list) => String(list.character_id) === String(GLOBAL_ORDER_ID));
}

/** The effective layout: the global order resolved against the prompts; unknown identifiers are dropped. */
export function presetLayout(preset: PromptPreset): LayoutEntry[] {
  const byId = new Map<string, PromptEntry>();
  for (const prompt of preset.prompts ?? []) if (!byId.has(prompt.identifier)) byId.set(prompt.identifier, prompt);
  const order = globalOrderList(preset)?.order;
  const list = order && order.length > 0 ? order : DEFAULT_ORDER;
  const out: LayoutEntry[] = [];
  for (const item of list) {
    const entry = byId.get(item.identifier);
    if (entry) out.push({ entry, enabled: item.enabled === true });
  }
  return out;
}

function currentOrder(preset: PromptPreset): PromptOrderEntry[] {
  const list = globalOrderList(preset);
  return list ? list.order : presetLayout(preset).map((item) => ({ identifier: item.entry.identifier, enabled: item.enabled }));
}

function withGlobalOrder(preset: PromptPreset, order: PromptOrderEntry[]): PromptPreset {
  const lists = preset.prompt_order ?? [];
  const exists = globalOrderList(preset) !== undefined;
  return {
    ...preset,
    prompt_order: exists
      ? lists.map((list) => (String(list.character_id) === String(GLOBAL_ORDER_ID) ? { ...list, order } : list))
      : [...lists, { character_id: GLOBAL_ORDER_ID, order }],
  };
}

/** Change one entry's content (the on/off switch lives in the order, see [withEntryEnabled]). The identifier cannot change. */
export function withEntryUpdated(
  preset: PromptPreset,
  identifier: string,
  transform: (entry: PromptEntry) => PromptEntry,
): PromptPreset {
  return {
    ...preset,
    prompts: preset.prompts.map((entry) => (entry.identifier === identifier ? { ...transform(entry), identifier } : entry)),
  };
}

export function withEntryEnabled(preset: PromptPreset, identifier: string, enabled: boolean): PromptPreset {
  return withGlobalOrder(
    preset,
    currentOrder(preset).map((item) => (item.identifier === identifier ? { ...item, enabled } : item)),
  );
}

/** Move an entry [delta] places in the global order (positive = later); stops at the ends. */
export function withEntryMoved(preset: PromptPreset, identifier: string, delta: number): PromptPreset {
  const order = [...currentOrder(preset)];
  const from = order.findIndex((item) => item.identifier === identifier);
  if (from < 0) return preset;
  const to = Math.min(Math.max(from + delta, 0), order.length - 1);
  if (to === from) return preset;
  order.splice(to, 0, order.splice(from, 1)[0]);
  return withGlobalOrder(preset, order);
}

/** Drag-and-drop: take the place of [targetIdentifier]; the others shift. */
export function withEntryMovedTo(preset: PromptPreset, identifier: string, targetIdentifier: string): PromptPreset {
  if (identifier === targetIdentifier) return preset;
  const order = [...currentOrder(preset)];
  const from = order.findIndex((item) => item.identifier === identifier);
  const to = order.findIndex((item) => item.identifier === targetIdentifier);
  if (from < 0 || to < 0) return preset;
  order.splice(to, 0, order.splice(from, 1)[0]);
  return withGlobalOrder(preset, order);
}

/** Markers (world info, chat history, ...) are the skeleton of the layout and cannot be removed. */
export function withEntryRemoved(preset: PromptPreset, identifier: string): PromptPreset {
  if (preset.prompts.find((entry) => entry.identifier === identifier)?.marker === true) return preset;
  return withGlobalOrder(
    { ...preset, prompts: preset.prompts.filter((entry) => entry.identifier !== identifier) },
    currentOrder(preset).filter((item) => item.identifier !== identifier),
  );
}

/**
 * The markers the app fills in after the prompt block: the persona and character card markers, or `rikkihubContext`
 * when the layout has none of them. A new text entry goes before the first of these.
 */
const AFTER_PROMPT_BLOCK = new Set([
  "personaDescription", "charDescription", "charPersonality", "scenario", MARKER_RIKKIHUB_CONTEXT,
]);

/**
 * Add a text entry before [beforeIdentifier]. Left out (`undefined`) it goes at the end of the prompt block, before the
 * first marker the app fills in after it; `null` or an unknown identifier appends at the very end.
 */
export function withEntryAdded(
  preset: PromptPreset,
  entry: PromptEntry,
  beforeIdentifier?: string | null,
): PromptPreset {
  if (preset.prompts.some((existing) => existing.identifier === entry.identifier)) {
    throw new Error(`duplicate identifier ${entry.identifier}`);
  }
  const order = [...currentOrder(preset)];
  const found =
    beforeIdentifier === null
      ? -1
      : beforeIdentifier === undefined
        ? order.findIndex((item) => AFTER_PROMPT_BLOCK.has(item.identifier))
        : order.findIndex((item) => item.identifier === beforeIdentifier);
  order.splice(found >= 0 ? found : order.length, 0, { identifier: entry.identifier, enabled: true });
  return withGlobalOrder({ ...preset, prompts: [...preset.prompts, entry] }, order);
}

const AUXILIARY_PROMPTS = new Set(["nsfw", "jailbreak", "enhanceDefinitions"]);

/**
 * What the layout shows and sends with SillyTavern mode off (`Assistant.tavernMode`): the main prompt and the user's own
 * text entries — not markers, not a preset's auxiliary prompts or the post-history instructions.
 */
export function isPlainPromptEntry(entry: PromptEntry): boolean {
  return entry.marker !== true && !AUXILIARY_PROMPTS.has(entry.identifier);
}

/** The assistant's prompt text entries: not markers, not auxiliary, relative position — in layout order. */
export function presetTextEntries(preset: PromptPreset): LayoutEntry[] {
  return presetLayout(preset).filter(
    ({ entry }) =>
      entry.marker !== true && !AUXILIARY_PROMPTS.has(entry.identifier) && (entry.injection_position ?? 0) === INJECTION_RELATIVE,
  );
}

/** Enabled, non-blank text entries joined by a blank line — what a list or card shows as the prompt preview. */
export function promptPreviewText(preset: PromptPreset | undefined): string {
  if (!preset) return "";
  return presetTextEntries(preset)
    .filter(({ entry, enabled }) => enabled && (entry.content ?? "").trim() !== "")
    .map(({ entry }) => entry.content ?? "")
    .join("\n\n");
}

// ---------------------------------------------------------------------------------------------
// Assembly options

/** `AssemblyOptions` defaults for the keys the editor shows (a key is only in the preset when it differs). */
export const OPTION_DEFAULTS = {
  openai_max_context: 4095,
  openai_max_tokens: 300,
  squash_system_messages: false,
  new_chat_prompt: "[Start a new Chat]",
  wi_format: "{0}",
} as const;

/** At or above this the history is not truncated by context size. */
export const UNLIMITED_CONTEXT = 1_000_000;

export interface OptionsView {
  contextSize: number;
  responseTokens: number;
  squashSystemMessages: boolean;
  newChatPrompt: string;
  wiFormat: string;
}

export function optionsOf(preset: PromptPreset): OptionsView {
  return {
    contextSize: preset.openai_max_context ?? OPTION_DEFAULTS.openai_max_context,
    responseTokens: preset.openai_max_tokens ?? OPTION_DEFAULTS.openai_max_tokens,
    squashSystemMessages: preset.squash_system_messages ?? OPTION_DEFAULTS.squash_system_messages,
    newChatPrompt: preset.new_chat_prompt ?? OPTION_DEFAULTS.new_chat_prompt,
    wiFormat: preset.wi_format ?? OPTION_DEFAULTS.wi_format,
  };
}

export function contextUnlimited(preset: PromptPreset): boolean {
  return optionsOf(preset).contextSize >= UNLIMITED_CONTEXT;
}

/** Context length; null means no limit. At least 1. */
export function withContextSize(preset: PromptPreset, tokens: number | null): PromptPreset {
  return { ...preset, openai_max_context: tokens === null ? UNLIMITED_CONTEXT : Math.max(tokens, 1) };
}

export function withResponseTokens(preset: PromptPreset, tokens: number): PromptPreset {
  return { ...preset, openai_max_tokens: tokens };
}

export function withSquashSystemMessages(preset: PromptPreset, on: boolean): PromptPreset {
  return { ...preset, squash_system_messages: on };
}

export function withNewChatPrompt(preset: PromptPreset, text: string): PromptPreset {
  return { ...preset, new_chat_prompt: text };
}

export function withWiFormat(preset: PromptPreset, text: string): PromptPreset {
  return { ...preset, wi_format: text };
}

// ---------------------------------------------------------------------------------------------
// Regex scripts

/** `RegexPlacement`. */
export const REGEX_USER_INPUT = 1;
export const REGEX_AI_OUTPUT = 2;
export const REGEX_WORLD_INFO = 5;
export const REGEX_REASONING = 6;
/** `SubstituteFindRegex`. */
export const SUBSTITUTE_NONE = 0;
export const SUBSTITUTE_RAW = 1;
export const SUBSTITUTE_ESCAPED = 2;

/** A new script: acts on AI output and reasoning, no find string yet. */
export function newRegexScript(name: string): RegexScript {
  return {
    id: createId(),
    scriptName: name,
    findRegex: "",
    replaceString: "",
    trimStrings: [],
    placement: [REGEX_AI_OUTPUT, REGEX_REASONING],
    disabled: false,
    markdownOnly: false,
    promptOnly: false,
    runOnEdit: false,
    substituteRegex: SUBSTITUTE_NONE,
    minDepth: null,
    maxDepth: null,
  };
}

export function scriptAffects(script: RegexScript, point: number): boolean {
  return script.placement.includes(point);
}

/** Switch one placement; the array stays sorted ascending so the same settings always store the same array. */
export function withScriptPlacement(script: RegexScript, point: number, on: boolean): RegexScript {
  const next = on ? [...new Set([...script.placement, point])] : script.placement.filter((value) => value !== point);
  return { ...script, placement: [...next].sort((a, b) => a - b) };
}

const REGEX_FLAGS = "dgimsuvy";

/** A find string written as `/pattern/flags` is used as it is; a bare pattern becomes `/pattern/g`. Blank stays empty. */
export function normalizeFindRegex(input: string): string {
  const text = input.trim();
  if (text === "") return text;
  const closing = text.lastIndexOf("/");
  const flags = text.slice(closing + 1);
  const looksLikeLiteral = text.startsWith("/") && closing > 0 && [...flags].every((flag) => REGEX_FLAGS.includes(flag));
  return looksLikeLiteral ? text : `/${text}/g`;
}

/** Split `/pattern/flags` into [pattern, flags]; null when it is not written that way. */
export function splitRegexLiteral(input: string): [string, string] | null {
  if (!input.startsWith("/")) return null;
  const closing = input.lastIndexOf("/");
  if (closing <= 0) return null;
  const flags = input.slice(closing + 1);
  if (![...flags].every((flag) => REGEX_FLAGS.includes(flag))) return null;
  return [input.slice(1, closing), flags];
}

/** Assistant-scoped scripts in a preset (`extensions.regex_scripts`). */
export function presetRegexScripts(preset: PromptPreset): RegexScript[] {
  const scripts = preset.extensions?.regex_scripts;
  return Array.isArray(scripts) ? scripts.filter((script): script is RegexScript => typeof script === "object" && script !== null) : [];
}

/** Replace the assistant-scoped scripts; an empty list removes `regex_scripts` (other `extensions` keys stay). */
export function withPresetRegexScripts(preset: PromptPreset, scripts: RegexScript[]): PromptPreset {
  const extensions = { ...preset.extensions };
  if (scripts.length === 0) delete extensions.regex_scripts;
  else extensions.regex_scripts = scripts;
  const next = { ...preset };
  if (Object.keys(extensions).length === 0) delete next.extensions;
  else next.extensions = extensions;
  return next;
}

// ---------------------------------------------------------------------------------------------
// World info

/** Kotlin's `Char.isWhitespace()` (Character.isWhitespace or a space separator), which differs from JS `\s` at U+FEFF and U+001C..1F. */
function isWhitespace(ch: string): boolean {
  const code = ch.charCodeAt(0);
  if (code >= 0x1c && code <= 0x1f) return true;
  if (code === 0xfeff) return false;
  return /\s/.test(ch);
}

/**
 * One line of keywords → list: comma separated, trimmed, blanks dropped. A regex key written `/pattern/flags` may
 * contain commas: it only ends where `/flags` is followed by a comma or the end of the text; without such an end it
 * is an ordinary keyword.
 */
export function parseKeyList(text: string): string[] {
  const keys: string[] = [];
  const n = text.length;
  let i = 0;
  while (i < n) {
    while (i < n && (text[i] === "," || isWhitespace(text[i]))) i += 1;
    if (i >= n) break;
    let end = -1;
    if (text[i] === "/") {
      let slash = text.indexOf("/", i + 1);
      while (slash >= 0) {
        let flagsEnd = slash + 1;
        while (flagsEnd < n && text[flagsEnd] >= "a" && text[flagsEnd] <= "z") flagsEnd += 1;
        let after = flagsEnd;
        while (after < n && isWhitespace(text[after])) after += 1;
        if (after >= n || text[after] === ",") {
          end = flagsEnd;
          break;
        }
        slash = text.indexOf("/", slash + 1);
      }
    }
    if (end < 0) {
      const comma = text.indexOf(",", i);
      end = comma < 0 ? n : comma;
    }
    const key = text.slice(i, end).trim();
    if (key !== "") keys.push(key);
    i = end;
  }
  return keys;
}

export function formatKeyList(keys: string[]): string {
  return keys.join(", ");
}

/** The title in a list: the comment, or the first keyword when there is none. */
export function entryTitle(entry: Pick<WorldInfoEntry, "comment" | "key">): string {
  return entry.comment.trim() !== "" ? entry.comment : (entry.key[0] ?? "");
}

/** Search: comment, keywords, secondary keywords or content contain [query] (case-insensitive); blank matches everything. */
export function entryMatchesQuery(entry: WorldInfoEntry, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === "") return true;
  const has = (text: string) => text.toLowerCase().includes(q);
  return has(entry.comment) || has(entry.content) || entry.key.some(has) || entry.keysecondary.some(has);
}

export function withPrimaryKeys(entry: WorldInfoEntry, text: string): WorldInfoEntry {
  return { ...entry, key: parseKeyList(text) };
}

/** Secondary keywords: writing some also turns `selective` on (the engine only uses them when it is on). */
export function withSecondaryKeys(entry: WorldInfoEntry, text: string): WorldInfoEntry {
  const keys = parseKeyList(text);
  return { ...entry, keysecondary: keys, selective: keys.length > 0 ? true : entry.selective };
}

/** Trigger probability, clamped to 0..100; setting it means the probability is used. */
export function withProbability(entry: WorldInfoEntry, percent: number): WorldInfoEntry {
  return { ...entry, probability: Math.min(Math.max(Math.trunc(percent), 0), 100), useProbability: true };
}

export function entryTriggerSet(entry: Pick<WorldInfoEntry, "triggers">): GenerationTypeName[] {
  const present = new Set(entry.triggers);
  return GENERATION_TYPES.filter((type) => present.has(type));
}

/** Same rule as [withTriggers] for the entry's own `triggers` list. */
export function withTriggerSet(entry: WorldInfoEntry, selected: Iterable<string>): WorldInfoEntry {
  const chosen = new Set(selected);
  const known = new Set<string>(GENERATION_TYPES);
  const unknown = entry.triggers.filter((wire) => !known.has(wire));
  const picked = GENERATION_TYPES.filter((type) => chosen.has(type));
  return { ...entry, triggers: [...(picked.length === GENERATION_TYPES.length ? [] : picked), ...unknown] };
}

export function toggleEntryTrigger(entry: WorldInfoEntry, type: GenerationTypeName): WorldInfoEntry {
  const current = entryTriggerSet(entry);
  return withTriggerSet(entry, current.includes(type) ? current.filter((t) => t !== type) : [...current, type]);
}

/** Inherit-from-global / on / off in the UI ↔ null / true / false in the data. */
export type TriState = "inherit" | "on" | "off";

export function triStateOf(value: boolean | null | undefined): TriState {
  return value === null || value === undefined ? "inherit" : value ? "on" : "off";
}

export function triStateValue(state: TriState): boolean | null {
  return state === "inherit" ? null : state === "on";
}

// ---------------------------------------------------------------------------------------------
// Variables

/** How a variable value reads in a list: scalars as their content, null as empty, anything else as JSON. */
export function variableDisplay(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

// ---------------------------------------------------------------------------------------------
// Activation record (`ActivationRecord.kt`)

export const ACTIVATION_KEY = "tavernActivations";

function recordText(value: unknown): string {
  return typeof value === "string" ? value : typeof value === "number" || typeof value === "boolean" ? String(value) : "";
}

function recordInt(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? Math.trunc(value) : 0;
}

/** Decode a world info activation record from a part's metadata; null when there is none. */
export function decodeActivations(record: unknown): ActivationSummary | null {
  if (typeof record !== "object" || record === null) return null;
  const raw = (record as { entries?: unknown }).entries;
  if (!Array.isArray(raw)) return null;
  const entries: RecordedActivation[] = [];
  for (const item of raw) {
    if (typeof item !== "object" || item === null) continue;
    const e = item as Record<string, unknown>;
    entries.push({
      bookId: recordText(e.book),
      bookName: recordText(e.bookName),
      uid: recordInt(e.uid),
      title: recordText(e.title),
      position: recordInt(e.position),
      order: recordInt(e.order),
    });
  }
  const more = Math.max(recordInt((record as { more?: unknown }).more), 0);
  return { entries, more, total: entries.length + more };
}

/** The activation record on a message: the first text part that carries one. */
export function activationsOfParts(parts: ReadonlyArray<{ type?: string; metadata?: unknown }>): ActivationSummary | null {
  for (const part of parts) {
    if (part.type !== "text") continue;
    const metadata = part.metadata;
    if (typeof metadata !== "object" || metadata === null) continue;
    const summary = decodeActivations((metadata as Record<string, unknown>)[ACTIVATION_KEY]);
    if (summary) return summary;
  }
  return null;
}

// ---------------------------------------------------------------------------------------------
// Role names

export const PROMPT_ROLES: PromptRole[] = ["system", "user", "assistant"];

// ---------------------------------------------------------------------------------------------
// Patches

function sameJson(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * The fields that differ between [before] and [after], as a patch the server merges into the latest object:
 * changed and added fields carry their new value, removed fields carry null. Equal objects give `{}`.
 */
export function diffObjects(before: object, after: object): Record<string, unknown> {
  const a = before as Record<string, unknown>;
  const b = after as Record<string, unknown>;
  const patch: Record<string, unknown> = {};
  for (const key of Object.keys(b)) {
    if (!sameJson(a[key], b[key])) patch[key] = b[key];
  }
  for (const key of Object.keys(a)) {
    if (!(key in b) && a[key] !== undefined) patch[key] = null;
  }
  return patch;
}

/** Library entries are picked by UUID; entries imported with other identifiers cannot be selected (as in the App). */
export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

// ---------------------------------------------------------------------------------------------
// Display regex

/**
 * Put the server's "display only" text into a message's parts. [display] maps a part's index to the text to show
 * (only text and reasoning parts, only the ones a script changed); the stored text stays untouched elsewhere
 * (editing, copying). Returns the same array when there is nothing to show.
 */
export function applyDisplay<P extends { type?: string }>(parts: P[], display: Record<string, string> | null | undefined): P[] {
  if (!display || Object.keys(display).length === 0) return parts;
  let changed = false;
  const next = parts.map((part, index) => {
    const shown = display[String(index)];
    if (shown === undefined) return part;
    if (part.type === "text" && (part as { text?: string }).text !== shown) {
      changed = true;
      return { ...part, text: shown };
    }
    if (part.type === "reasoning" && (part as { reasoning?: string }).reasoning !== shown) {
      changed = true;
      return { ...part, reasoning: shown };
    }
    return part;
  });
  return changed ? next : parts;
}

interface SettingsWithScripts {
  tavern?: { regexScripts?: RegexScript[]; globalVariables?: Record<string, unknown> };
  assistants?: Array<{ id: string; promptPreset?: PromptPreset }>;
  displaySetting?: { userNickname?: string };
}

/**
 * A fingerprint of everything that decides what the server shows for a conversation of [assistantId]: the active
 * "display only" scripts (global, then the assistant's), and what their replacements may expand (user name, global
 * variables). Empty when there is none, so conversations without such scripts never refetch.
 */
export function displayScriptsSignature(settings: SettingsWithScripts | null | undefined, assistantId: string | null | undefined): string {
  if (!settings) return "";
  const assistant = settings.assistants?.find((item) => item.id === assistantId);
  const scripts = [...(settings.tavern?.regexScripts ?? []), ...(assistant?.promptPreset ? presetRegexScripts(assistant.promptPreset) : [])]
    .filter((script) => script.markdownOnly && !script.disabled);
  if (scripts.length === 0) return "";
  return JSON.stringify([scripts, settings.tavern?.globalVariables ?? {}, settings.displaySetting?.userNickname ?? ""]);
}
