import api from "~/services/api";
import type {
  CharacterDetail,
  CharacterExportFormat,
  CharacterFields,
  CharacterImportResult,
  CharacterSummary,
  PresetImportReport,
} from "../types/character";
import type { AssistantProfile } from "../types/settings";

export const charactersQueryKey = ["characters"] as const;
export const characterQueryKey = (id: string) => ["characters", id] as const;

/** Same bounds as the server (CharacterRoutes.kt); checked first so a huge file is not sent. */
export const CHARACTER_FILE_MAX_BYTES = 64 * 1024 * 1024;
export const AVATAR_MAX_BYTES = 8 * 1024 * 1024;

/** A card edit: the fields it names change, the rest stay. `expectedRevision` makes the server refuse a stale base. */
export type CharacterPatch = Partial<CharacterFields> & { tags?: string[] | string; fav?: boolean; expectedRevision?: number };

export const charactersApi = {
  list: (query?: string) =>
    api.get<CharacterSummary[]>("characters", { searchParams: query?.trim() ? { query: query.trim() } : undefined }),
  get: (id: string) => api.get<CharacterDetail>(`characters/${id}`),
  create: (name: string) => api.post<CharacterDetail>("characters", { name }),
  /** The card file's bytes (PNG, CHARX or JSON; the server tells them apart by content). */
  import: (file: File) =>
    api.postBinary<CharacterImportResult>("characters/import", file, { searchParams: { name: file.name } }),
  patch: (id: string, patch: CharacterPatch) => api.put<CharacterDetail>(`characters/${id}`, patch),
  remove: (id: string, deleteBook: boolean) =>
    api.delete(`characters/${id}`, deleteBook ? { searchParams: { deleteBook: "true" } } : undefined),
  setAvatar: (id: string, file: Blob) => api.putBinary<CharacterDetail>(`characters/${id}/avatar`, file),
  setBooks: (id: string, body: { extraBookIds?: string[]; embeddedBookId?: null }) =>
    api.put<CharacterDetail>(`characters/${id}/books`, body),
  setRegexAllowed: (id: string, allowed: boolean) =>
    api.put<CharacterDetail>(`characters/${id}/regex-allowed`, { allowed }),
  /** A new conversation with the character, greeting already in it. */
  startChat: (id: string) => api.post<{ conversationId: string }>(`characters/${id}/chat`, {}),
};

export const personaApi = {
  save: (patch: { nickname?: string; description?: string }) =>
    api.put<{ nickname: string; description: string }>("tavern/persona", patch),
};

export const presetApi = {
  /** The preset file's text; it becomes a new assistant. */
  import: (file: File) => importPreset(file),
};

async function importPreset(file: File): Promise<{ assistant: AssistantProfile; report: PresetImportReport }> {
  const text = await file.text();
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error("Not a JSON file");
  }
  return api.post("assistants/import-preset", parsed, { searchParams: { name: file.name } });
}

/** Where the browser downloads the preset of an assistant (the server sends it as an attachment). */
export const presetExportHref = (assistantId: string) => `/api/assistants/${encodeURIComponent(assistantId)}/export-preset`;

export const characterExportHref = (id: string, format: CharacterExportFormat) =>
  `/api/characters/${encodeURIComponent(id)}/export?format=${format}`;

/** Start a download of [href] (same origin, the session cookie goes along). */
export function startDownload(href: string): void {
  const link = document.createElement("a");
  link.href = href;
  link.rel = "noopener";
  document.body.appendChild(link);
  link.click();
  link.remove();
}

/** The tag box shows comma separated text; the server takes the same text or an array. */
export function tagsToText(tags: readonly string[]): string {
  return tags.join(", ");
}

/** Comma (also the full-width one) separated tags: trimmed, blanks and repeats dropped, order kept. */
export function textToTags(text: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of text.split(/[,，]/)) {
    const tag = part.trim();
    if (tag && !seen.has(tag)) {
      seen.add(tag);
      out.push(tag);
    }
  }
  return out;
}

/** Favourites first, then by name (case-insensitive) — the order the server lists them in. */
export function sortCharacters<T extends Pick<CharacterSummary, "fav" | "name">>(list: readonly T[]): T[] {
  return [...list].sort((a, b) => Number(b.fav) - Number(a.fav) || a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
}

/** How many favourites the sidebar shows as quick starts (six 32px avatars fill one row of the narrow sidebar). */
export const QUICK_CHARACTER_LIMIT = 6;

/** The picker draws this many rows first and the next page each time the list is scrolled to its end. */
export const CHARACTER_PAGE_SIZE = 40;

/** The favourites among [list], in list order (favourites first, then by name), at most [limit]. */
export function quickCharacters<T extends Pick<CharacterSummary, "fav" | "name">>(
  list: readonly T[],
  limit: number = QUICK_CHARACTER_LIMIT,
): T[] {
  return sortCharacters(list.filter((character) => character.fav)).slice(0, limit);
}

/** The order the picker's rows were drawn in, and what it was taken from. */
export interface RowOrder {
  key: string;
  ids: string[];
}

/**
 * The row order to draw [list] in: the one in [previous] while only a star changed (the rows stay where they are, so a
 * character you just starred does not jump out of sight), a new one when [query] or the set of characters changes.
 */
export function keepOrder<T extends { id: string }>(list: readonly T[], query: string, previous: RowOrder | null): RowOrder {
  const key = `${query}\n${list.map((item) => item.id).sort().join(",")}`;
  return previous?.key === key ? previous : { key, ids: list.map((item) => item.id) };
}

/** [list] in the order of [ids]: ids that are gone are skipped, items [ids] does not know come last. */
export function inOrder<T extends { id: string }>(ids: readonly string[], list: readonly T[]): T[] {
  const byId = new Map(list.map((item) => [item.id, item] as const));
  const known = new Set(ids);
  const ordered: T[] = [];
  for (const id of ids) {
    const item = byId.get(id);
    if (item) ordered.push(item);
  }
  for (const item of list) if (!known.has(item.id)) ordered.push(item);
  return ordered;
}

/** The first [pages] pages of [list]; the rest is drawn only when the list is scrolled to its end. */
export function pageOf<T>(list: readonly T[], pages: number, pageSize: number = CHARACTER_PAGE_SIZE): T[] {
  return list.slice(0, Math.max(1, pages) * pageSize);
}

/** A character file the picker offered: right size, otherwise the server judges the content. */
export function checkCharacterFile(file: File): "ok" | "too_large" | "empty" {
  if (file.size === 0) return "empty";
  if (file.size > CHARACTER_FILE_MAX_BYTES) return "too_large";
  return "ok";
}

export function checkAvatarFile(file: File): "ok" | "too_large" | "not_image" {
  if (!file.type.startsWith("image/")) return "not_image";
  if (file.size > AVATAR_MAX_BYTES) return "too_large";
  return "ok";
}
