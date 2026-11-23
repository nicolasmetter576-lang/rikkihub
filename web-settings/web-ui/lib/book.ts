import api from "~/services/api";

export type ChapterStatus = "draft" | "revising" | "final";

export const CHAPTER_STATUSES: readonly ChapterStatus[] = ["draft", "revising", "final"];

export interface BookItem {
  type: "volume" | "chapter";
  id: string;
  title: string;
  number?: number | null;
  path?: string | null;
  /** 用户起的章名；空 = title 来自正文标题或文件名 */
  customTitle?: string | null;
  status?: ChapterStatus | null;
  synopsis?: string | null;
  targetWords?: number | null;
  words?: number | null;
  missing?: boolean | null;
  updatedAt?: number | null;
}

export interface Book {
  title: string;
  author: string;
  words: number;
  items: BookItem[];
  unlisted: string[];
}

export type BookEdit =
  | { op: "add"; paths: string[]; afterId?: string }
  | { op: "addVolume"; title: string; afterId?: string }
  | { op: "renameVolume"; id: string; title: string }
  | {
      op: "updateChapter";
      id: string;
      title?: string;
      synopsis?: string;
      status?: ChapterStatus;
      targetWords?: number;
    }
  | { op: "setStatus"; ids: string[]; status: ChapterStatus }
  | { op: "remove"; ids: string[] }
  | { op: "reorder"; order: string[] }
  | { op: "moveToVolume"; ids: string[]; volumeId?: string | null }
  | { op: "ignore"; paths: string[] }
  | { op: "info"; title?: string; author?: string };

export interface BookReplacePair {
  find: string;
  replace: string;
}

export interface BookReplaceQuery {
  pairs: BookReplacePair[];
  ignoreCase: boolean;
  includeUnlisted: boolean;
}

export interface BookReplaceHit {
  start: number;
  pair: number;
  line: number;
  before: string;
  match: string;
  after: string;
}

export interface BookReplacePreview {
  files: Array<{ path: string; revision: string; hits: BookReplaceHit[] }>;
  unreadable: string[];
}

export interface BookReplaceResult {
  undoId?: string | null;
  replaced: number;
  changed: string[];
  conflicts: string[];
  failed: string[];
}

export interface BookUndoEntry {
  id: string;
  time: number;
  summary: string;
  replaced: number;
  files: number;
}

export const IMPORT_CHARSETS = ["UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE"] as const;
export const IMPORT_RULES = ["auto", "headings", "blank", "size", "prefix"] as const;

export type BookImportRule = (typeof IMPORT_RULES)[number];
export type BookImportIssue = "NUMBER_GAP" | "NUMBER_REPEAT" | "TOO_LONG" | "TOO_SHORT";

export interface BookImportOptions {
  charset?: string;
  rule: BookImportRule;
  count: number;
  prefix: string;
  /** 改过的标题和并到上一章的章，按拆分结果里的下标 */
  titles: Record<number, string>;
  merged: number[];
}

export interface BookImportPreview {
  isText: boolean;
  charset?: string | null;
  rows: Array<{ source: number; title: string; words: number; volume?: string | null; issues: BookImportIssue[] }>;
}

export type BookExportFormat = "txt" | "markdown" | "epub";

export interface BookExportQuery {
  format: BookExportFormat;
  volumeId?: string;
  chapters?: string[];
  finalOnly: boolean;
  volumeTitles: boolean;
  indent: boolean;
  blankLines: boolean;
}

export function bookExportHref(id: string, query: BookExportQuery): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) params.set(key, Array.isArray(value) ? value.join(",") : String(value));
  }
  return `/api/workspaces/${id}/book/export?${params.toString()}`;
}

const notesPath = (id: string, chapterId: string) => `workspaces/${id}/book/notes/${encodeURIComponent(chapterId)}`;

function importForm(file: File, options: BookImportOptions): FormData {
  const form = new FormData();
  form.append("options", JSON.stringify(options));
  form.append("file", file, file.name);
  return form;
}

export interface BeatDraft {
  id: string;
  text: string;
  model: string;
  note: string;
  at: number;
  /** 写它时前面的正文已经变了 */
  outdated: boolean;
}

export interface BookBeat {
  id: string;
  text: string;
  words: number;
  drafts: BeatDraft[];
  chosen?: string | null;
  locked: boolean;
  inserted: string;
}

export interface ChapterNotes {
  beats: BookBeat[];
  extracted: boolean;
  stale: boolean;
  synopsis: string;
  points: Array<{ id: string; text: string; quote: string }>;
  compared: boolean;
  compareStale: boolean;
  matches: Array<{ beat: string; state: "written" | "changed" | "missing"; points: string[]; note: string }>;
}

export interface OutlineChapter {
  id: string;
  number: number;
  title: string;
  path: string;
  beats: number;
  points: number;
  extracted: boolean;
  stale: boolean;
  pending: boolean;
  comparePending: boolean;
  changed: number;
  missing: number;
}

export interface OutlineProgress {
  total: number;
  done: number;
  current?: string | null;
  task?: "extract" | "compare" | null;
  error?: string | null;
  aiError?: string | null;
}

export interface OutlineSection {
  title: string;
  beats: string[];
  chapterId?: string | null;
}

export interface BeatProposal {
  text: string;
  words: number;
}

export interface BeatWriting {
  running: boolean;
  splitting: boolean;
  current?: string | null;
  text: string;
  queue: string[];
  proposal?: BeatProposal[] | null;
  error?: string | null;
  aiError?: string | null;
}

export interface BeatInsertion {
  beat: string;
  /** 前面几处都插好之后的位置 */
  offset: number;
  text: string;
  block: string;
}

export interface BeatPresence {
  found: boolean;
  replacement?: { from: number; to: number; text: string } | null;
}

export interface BookConfig {
  modelId?: string | null;
  modelMissing: boolean;
  reasoningLevel: string;
  temperature?: number | null;
}

export const fetchBookConfig = () => api.get<BookConfig>("book/config");

export const updateBookConfig = (patch: Partial<Pick<BookConfig, "modelId" | "reasoningLevel" | "temperature">>) =>
  api.put<BookConfig>("book/config", patch);

/** 依次插入（每处的位置以前面几处插好为准）换成对原文的一组改动，编辑器里算一步。 */
export function insertionChanges(insertions: readonly BeatInsertion[], length: number): { from: number; insert: string }[] {
  // 记下每段已插文字在新文里的区间，把新文里的位置换回原文的
  const placed: Array<{ from: number; insert: string }> = [];
  for (const item of insertions) {
    let shift = 0;
    let target: { from: number; insert: string } | null = null;
    let start = 0;
    for (const change of placed) {
      start = change.from + shift;
      if (item.offset < start) break;
      if (item.offset <= start + change.insert.length) {
        target = change;
        break;
      }
      shift += change.insert.length;
    }
    if (target) {
      const at = item.offset - start;
      target.insert = target.insert.slice(0, at) + item.text + target.insert.slice(at);
      continue;
    }
    placed.push({ from: Math.min(item.offset - shift, length), insert: item.text });
    placed.sort((a, b) => a.from - b.from);
  }
  return placed;
}

/** detail key 的前缀之下：workspace_update 事件失效 detail 时一起刷新 */
export const bookQueryKey = (id: string) => ["workspaces", id, "book"] as const;

export const bookApi = {
  get(id: string) {
    return api.get<Book>(`workspaces/${id}/book`);
  },
  edit(id: string, operations: BookEdit[]) {
    return api.post<Book>(`workspaces/${id}/book/edit`, { operations }, { retry: 0 });
  },
  createChapter(id: string, path: string, afterId?: string) {
    return api.post<Book>(`workspaces/${id}/book/chapters`, { path, afterId }, { retry: 0 });
  },
  deleteChapters(id: string, ids: string[]) {
    return api.post<Book>(`workspaces/${id}/book/delete`, { ids }, { retry: 0 });
  },
  relink(id: string, chapterId: string, path: string) {
    return api.post<Book>(`workspaces/${id}/book/relink`, { id: chapterId, path }, { retry: 0 });
  },
  outline(id: string) {
    return api.get<{ chapters: OutlineChapter[]; progress?: OutlineProgress | null }>(`workspaces/${id}/book/outline`);
  },
  outlineRun(id: string, tasks: Array<{ type: "extract" | "compare"; id: string }>) {
    return api.post<void>(`workspaces/${id}/book/outline/run`, { tasks }, { retry: 0 });
  },
  outlineStop(id: string) {
    return api.post<void>(`workspaces/${id}/book/outline/stop`, {});
  },
  outlineFile(id: string, path: string) {
    return api.post<{ sections: OutlineSection[] }>(`workspaces/${id}/book/outline/file`, { path });
  },
  outlineImport(id: string, sections: OutlineSection[]) {
    return api.post<void>(`workspaces/${id}/book/outline/import`, { sections }, { retry: 0 });
  },
  notes(id: string, chapterId: string) {
    return api.get<ChapterNotes>(`workspaces/${id}/book/notes/${encodeURIComponent(chapterId)}`);
  },
  saveBeats(id: string, chapterId: string, beats: Array<{ id?: string; text: string; words: number }>) {
    return api.put<ChapterNotes>(`workspaces/${id}/book/notes/${encodeURIComponent(chapterId)}/beats`, { beats });
  },
  planFromPoints(id: string, chapterId: string) {
    return api.post<ChapterNotes>(
      `workspaces/${id}/book/notes/${encodeURIComponent(chapterId)}/plan-from-points`,
      {},
      { retry: 0 },
    );
  },
  writing(id: string, chapterId: string) {
    return api.get<BeatWriting>(`${notesPath(id, chapterId)}/writing`);
  },
  writeBeats(id: string, chapterId: string, request: { beats: string[]; note?: string; modelId?: string }) {
    return api.post<void>(`${notesPath(id, chapterId)}/write`, request, { retry: 0 });
  },
  splitBeats(id: string, chapterId: string) {
    return api.post<void>(`${notesPath(id, chapterId)}/split`, {}, { retry: 0 });
  },
  stopWriting(id: string, chapterId: string) {
    return api.post<void>(`${notesPath(id, chapterId)}/writing/stop`, {});
  },
  dismissWriting(id: string, chapterId: string) {
    return api.post<void>(`${notesPath(id, chapterId)}/writing/dismiss`, {});
  },
  useProposal(id: string, chapterId: string, beats: BeatProposal[], replace: boolean) {
    return api.post<ChapterNotes>(`${notesPath(id, chapterId)}/proposal`, { beats, replace }, { retry: 0 });
  },
  beatOp(id: string, chapterId: string, request: { op: string; beat: string; draft?: string }) {
    return api.post<ChapterNotes>(`${notesPath(id, chapterId)}/beat`, request, { retry: 0 });
  },
  placeBeats(id: string, chapterId: string, request: { text: string; beats: string[]; cursor?: number }) {
    return api.post<{ insertions: BeatInsertion[] }>(`${notesPath(id, chapterId)}/place`, request);
  },
  beatPresence(id: string, chapterId: string, text: string) {
    return api.post<{ beats: Record<string, BeatPresence> }>(`${notesPath(id, chapterId)}/presence`, { text });
  },
  markInserted(id: string, chapterId: string, blocks: Record<string, string>) {
    return api.post<ChapterNotes>(`${notesPath(id, chapterId)}/inserted`, { blocks });
  },
  importPreview(id: string, file: File, options: BookImportOptions) {
    return api.postMultipart<BookImportPreview>(`workspaces/${id}/book/import/preview`, importForm(file, options), {
      retry: 0,
    });
  },
  importFile(id: string, file: File, options: BookImportOptions) {
    return api.postMultipart<Book>(`workspaces/${id}/book/import`, importForm(file, options), { retry: 0 });
  },
  replacePreview(id: string, query: BookReplaceQuery) {
    return api.post<BookReplacePreview>(`workspaces/${id}/book/replace/preview`, query, { retry: 0 });
  },
  replaceApply(
    id: string,
    request: BookReplaceQuery & { files: Array<{ path: string; revision: string; starts: number[] }> },
  ) {
    return api.post<BookReplaceResult>(`workspaces/${id}/book/replace/apply`, request, { retry: 0 });
  },
  replaceHistory(id: string) {
    return api.get<{ entries: BookUndoEntry[] }>(`workspaces/${id}/book/replace/history`);
  },
  replaceUndo(id: string, undoId: string) {
    return api.post<{ restored: string[]; changedSince: string[]; failed: string[] }>(
      `workspaces/${id}/book/replace/undo`,
      { id: undoId },
      { retry: 0 },
    );
  },
  rebuild(id: string) {
    return api.post<Book>(`workspaces/${id}/book/rebuild`, {}, { retry: 0 });
  },
};

/** 上移 / 下移一位后的完整顺序；到头了返回 null */
export function movedOrder(items: readonly BookItem[], id: string, delta: -1 | 1): string[] | null {
  const order = items.map((item) => item.id);
  const index = order.indexOf(id);
  const target = index + delta;
  if (index < 0 || target < 0 || target >= order.length) return null;
  [order[index], order[target]] = [order[target], order[index]];
  return order;
}

/** 新章文件默认放进最后一章所在的文件夹；没写扩展名的补 .md */
export function newChapterPath(items: readonly BookItem[], name: string): string {
  const trimmed = name.trim();
  const file = /\.(md|markdown|txt|text)$/i.test(trimmed) ? trimmed : `${trimmed}.md`;
  const last = items.filter((item) => item.type === "chapter").at(-1)?.path ?? "";
  const slash = last.lastIndexOf("/");
  return slash < 0 ? file : `${last.slice(0, slash + 1)}${file}`;
}
