/** Same shapes as CharacterWebSupport.kt. */

export interface CharacterSummary {
  id: string;
  name: string;
  /** Address of the avatar picture (the file name is its version), null when the character has none. */
  avatarUrl: string | null;
  fav: boolean;
  tags: string[];
  createdAt: number;
  updatedAt: number;
  revision: number;
}

export type DepthPromptRole = "system" | "user" | "assistant";

/** The card fields the page edits. */
export interface CharacterFields {
  name: string;
  description: string;
  personality: string;
  scenario: string;
  first_mes: string;
  mes_example: string;
  creator_notes: string;
  system_prompt: string;
  post_history_instructions: string;
  creator: string;
  character_version: string;
  alternate_greetings: string[];
  depth_prompt: { prompt: string; depth: number; role: DepthPromptRole };
}

export type CharacterTextField = Exclude<keyof CharacterFields, "alternate_greetings" | "depth_prompt">;

export interface CharacterBook {
  id: string;
  name: string;
  enabled: boolean;
  /** The world book that came inside the card. */
  embedded: boolean;
  entryCount: number;
}

/** A regex script that came with the card (shown for review; it only runs once the user allows it). */
export interface CharacterRegexScript {
  id: string;
  name: string;
  findRegex: string;
  replaceString: string;
  disabled: boolean;
}

export interface CharacterDetail extends CharacterSummary {
  sourceName: string;
  fields: CharacterFields;
  assetCount: number;
  regexAllowed: boolean;
  regexScripts: CharacterRegexScript[];
  embeddedBookId: string | null;
  extraBookIds: string[];
  books: CharacterBook[];
}

/** What a card brought along. */
export interface CharacterImportReport {
  container: string;
  cardFormat: string;
  book: { outcome: "created" | "reused"; bookId: string; name: string; entries?: number } | null;
  regexScripts: number;
  greetings: number;
  hasDepthPrompt: boolean;
  assets: number;
}

export interface CharacterImportResult {
  id: string;
  name: string;
  report: CharacterImportReport;
}

/** What a SillyTavern preset brought along (`POST /api/assistants/import-preset`). */
export interface PresetImportReport {
  promptCount: number;
  regexScriptsDisabled: number;
  samplingImported: string[];
  connectionKeysSkipped: string[];
}

export type CharacterExportFormat = "png" | "json" | "charx";
