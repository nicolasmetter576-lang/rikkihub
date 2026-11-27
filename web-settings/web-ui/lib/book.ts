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
  /** 定稿锁概况；没有锁时为空 */
  lock?: { chapter: boolean; passages: number } | null;
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
  /** 落在锁定段落里（默认勾上，可取消） */
  locked?: boolean;
}

export interface BookReplacePreview {
  /** locked：整章定稿锁定，不会替换 */
  files: Array<{ path: string; revision: string; hits: BookReplaceHit[]; locked?: boolean }>;
  unreadable: string[];
}

export interface BookReplaceResult {
  undoId?: string | null;
  replaced: number;
  changed: string[];
  conflicts: string[];
  failed: string[];
  locked?: string[];
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

/** 一版内容是谁写的（与 App 的 HistorySource 同形）。 */
export type HistorySource =
  | { type: "manual" }
  | { type: "ai"; conversationId: string; messageId: string; toolCallId?: string | null }
  | { type: "replace" }
  | { type: "external_app" }
  | { type: "external" }
  | { type: "initial" }
  | { type: "restore" };

export type HistoryEntry =
  | { type: "version"; at: number; path: string; source: HistorySource; sha: string }
  | { type: "deleted"; at: number; path: string; source: HistorySource; trash: string }
  | { type: "renamed"; at: number; path: string; source: HistorySource; from: string };

export interface BookHistory {
  /** 新的在前 */
  entries: HistoryEntry[];
  currentWriter?: HistorySource | null;
  paused: boolean;
  conversations: Record<string, string>;
}

export type DiffSpan = { text: string; kind: "same" | "removed" | "added" };

export type DiffBlock =
  | { type: "same"; lines: string[] }
  | { type: "changed"; index: number; old: string[]; new: string[]; spans?: DiffSpan[][] | null };

export interface BookHistoryComparison {
  old: string;
  revision: string;
  diff: { blocks: DiffBlock[]; rewritten: boolean };
}

export interface TrashedFile {
  key: string;
  name: string;
  path: string;
  at: number;
  source: HistorySource;
  size: number;
}

/** 只看 AI 改之前：紧接着下一版（或现在的稿子）是 AI 写的那些。entries 新的在前。 */
export function beforeAiEdits(entries: readonly HistoryEntry[], currentWriter?: HistorySource | null): HistoryEntry[] {
  return entries.filter((entry, index) => {
    if (entry.type !== "version") return false;
    const newer = entries.slice(0, index).filter((item) => item.type === "version");
    const next = newer.length > 0 ? newer[newer.length - 1].source : currentWriter;
    return next?.type === "ai";
  });
}

export interface BookPassageLock {
  id: string;
  head: string;
  web: boolean;
  paused: boolean;
  /** 在 revision 那一版稿子里的位置（含头不含尾） */
  start?: number | null;
  end?: number | null;
}

export interface BookFileLocks {
  path: string;
  chapter: boolean;
  chapterWeb?: boolean;
  passages: BookPassageLock[];
  revision?: string | null;
}

export type LockNoticeKind = "restored" | "not_restored" | "no_space" | "changed" | "lost" | "base_missing" | "deleted";

export interface LockNotice {
  id: string;
  key: string;
  path: string;
  kind: LockNoticeKind;
  at: number;
  conversationId?: string | null;
  messageId?: string | null;
  /** 改之前的稿子（比较页从这一版开始） */
  beforeSha?: string | null;
  earlier?: number;
}

export interface BookLocks {
  locks: BookFileLocks[];
  notices: LockNotice[];
  broken: boolean;
}

export const bookLocksQueryKey = (id: string) => ["workspaces", id, "book", "locks"] as const;

// ---- 逐处审阅 ----

export type TurnStatus = "PENDING" | "REVIEWED" | "ARCHIVED";
export type Unattributed = "overlap" | "terminal";

export interface BookTurnSummary {
  conversationId: string;
  messageId: string;
  finishedAt: number;
  status: TurnStatus;
  /** 卡片上数的文件（不含只改了缩进、空行的） */
  files: number;
  deleted: number;
  onlyCreated: boolean;
  reverted: number;
  unattributed?: Unattributed | null;
  /** 碰过的文件（历史键） */
  keys: string[];
  cleaned: boolean;
}

export type TurnBlockState = "pending" | "reverted" | "gone" | "changed_later";

export interface TurnBlock {
  index: number;
  old: string[];
  new: string[];
  spans?: DiffSpan[][] | null;
  locked?: boolean;
  state: TurnBlockState;
  partner?: number | null;
}

export interface TurnFile {
  key: string;
  path: string;
  kind: "changed" | "created" | "deleted" | "renamed";
  renamedFrom?: string | null;
  revision?: string | null;
  before?: string | null;
  after?: string | null;
  chapterLocked?: boolean;
  whole?: boolean;
  layoutOnly?: boolean;
  layout?: number;
  missing?: "before" | "after" | "deleted_version" | null;
  mixed?: boolean;
  done?: boolean;
  changesSince?: number | null;
  gone?: boolean;
  unrecoverable?: boolean;
  canUndo?: boolean;
  blocks?: TurnBlock[];
}

export interface TurnReview {
  conversationId: string;
  messageId: string;
  finishedAt: number;
  status: TurnStatus;
  reverted: number;
  unattributed?: Unattributed | null;
  files: TurnFile[];
}

export interface TurnFilePick {
  key: string;
  revision?: string | null;
  before?: string | null;
  after?: string | null;
  reject: number[];
  whole?: boolean;
}

export interface TurnFileOutcome {
  key: string;
  path: string;
  outcome: "ok" | "conflict" | "locked" | "failed" | "missing";
  failed?: number[];
  reverted?: number;
}

export const bookTurnsQueryKey = (id: string, conversationId: string) => ["workspaces", id, "book", "turns", conversationId] as const;
export const bookTurnLatestQueryKey = (id: string) => ["workspaces", id, "book", "turns", "latest"] as const;

/** 这一块现在能不能单独切换接受 / 拒绝（与 App 的 selectable 同一规则）。 */
export function turnBlockSelectable(file: TurnFile, block: TurnBlock): boolean {
  if (file.done) return false;
  switch (file.kind) {
    case "deleted":
      return Boolean(file.gone) && !file.unrecoverable;
    case "created":
      return !file.gone && !file.chapterLocked && (block.state === "pending" || block.state === "changed_later");
    default:
      return (
        !file.gone && !file.chapterLocked && !file.whole && !file.layoutOnly && !file.missing && block.state === "pending"
      );
  }
}

/** 完成时每个文件的选择：没选拒绝的文件也带上（标为处理过）；新建、删除的文件整个文件是第 0 块。 */
export function turnPicks(review: TurnReview, rejected: Record<string, number[]>): TurnFilePick[] {
  return review.files
    .filter((file) => !file.done)
    .map((file) => {
      const reject = rejected[file.key] ?? [];
      const whole = file.kind === "created" || file.kind === "deleted";
      return {
        key: file.key,
        revision: file.revision,
        before: file.before,
        after: file.after,
        reject: whole ? [] : reject,
        whole: whole && reject.length > 0,
      };
    });
}

export interface TurnCard {
  summary: BookTurnSummary;
  /** 完整一行（卡片里）；否则一行小字 */
  full: boolean;
  sibling: BookTurnSummary | null;
}

interface TurnNode {
  messages: Array<{ id: string }>;
  selectIndex: number;
}

/**
 * 当前分支上每条回复的审阅提示（与 App 同一规则）：只有最新一条待审、没静音的回复显示完整一行；
 * 删了文件的轮次总是完整一行；只新建了文件的、更早的，只给一行小字。已归档不显示。
 */
export function turnCards(turns: readonly BookTurnSummary[], muted: boolean, nodes: readonly TurnNode[]): Map<string, TurnCard> {
  const byId = new Map(turns.map((turn) => [turn.messageId, turn]));
  const cards = new Map<string, TurnCard>();
  if (byId.size === 0) return cards;
  const current = nodes.flatMap((node) => {
    const message = node.messages[node.selectIndex];
    return message ? [{ node, id: message.id }] : [];
  });
  let latest: BookTurnSummary | null = null;
  for (const { id } of current) {
    const turn = byId.get(id);
    if (turn?.status === "PENDING" && (!latest || turn.finishedAt > latest.finishedAt)) latest = turn;
  }
  for (const { node, id } of current) {
    const summary = byId.get(id);
    if (!summary || summary.status === "ARCHIVED") continue;
    // 重新生成：同一节点上最近一个还有未审记录、碰过同一批文件的兄弟回复
    let sibling: BookTurnSummary | null = null;
    for (const message of node.messages) {
      const other = message.id === id ? undefined : byId.get(message.id);
      if (other?.status === "PENDING" && other.keys.some((key) => summary.keys.includes(key)) && (!sibling || other.finishedAt > sibling.finishedAt)) {
        sibling = other;
      }
    }
    const full = summary.status === "PENDING" && (summary.deleted > 0 || (summary === latest && !muted && !summary.onlyCreated));
    cards.set(id, { summary, full, sibling });
  }
  return cards;
}

const DISMISSED_NOTICES = "rikkihub.book.dismissedLockNotices";

/** 网页端的“知道了”只在这个浏览器里隐藏（App 里仍然显示），防止工作区里的程序经本机接口抹掉提醒。 */
export const dismissedNotices = {
  read(): Set<string> {
    try {
      return new Set(JSON.parse(localStorage.getItem(DISMISSED_NOTICES) ?? "[]") as string[]);
    } catch {
      return new Set();
    }
  },
  add(id: string) {
    const ids = [...dismissedNotices.read(), id].slice(-200);
    localStorage.setItem(DISMISSED_NOTICES, JSON.stringify(ids));
  },
};

export const bookApi = {
  locks(id: string) {
    return api.get<BookLocks>(`workspaces/${id}/book/locks`);
  },
  fileLocks(id: string, path: string) {
    return api.get<BookFileLocks>(`workspaces/${id}/book/locks?path=${encodeURIComponent(path)}`);
  },
  lockChapter(id: string, path: string, revision: string) {
    return api.post<BookFileLocks>(`workspaces/${id}/book/locks/chapter`, { path, revision }, { retry: 0 });
  },
  lockPassages(id: string, path: string, revision: string, start: number, end: number, text?: string) {
    return api.post<BookFileLocks>(`workspaces/${id}/book/locks/passages`, { path, revision, start, end, text }, { retry: 0 });
  },
  /** 比较页上放回带比较时的 path + revision：稿子变了 409 */
  putBackNotice(id: string, noticeId: string, compared?: { path: string; revision: string }) {
    return api.post<{ done: boolean }>(`workspaces/${id}/book/locks/notices/put-back`, { id: noticeId, ...compared }, { retry: 0 });
  },
  turns(id: string, conversationId: string) {
    return api.get<{ turns: BookTurnSummary[]; muted: boolean }>(
      `workspaces/${id}/book/turns?conversation=${encodeURIComponent(conversationId)}`,
    );
  },
  latestTurn(id: string) {
    return api.get<{ turn?: BookTurnSummary | null; title?: string | null }>(`workspaces/${id}/book/turns/latest`);
  },
  recentTurns(id: string) {
    return api.get<{ turns: BookTurnSummary[]; titles: Record<string, string> }>(`workspaces/${id}/book/turns/recent`);
  },
  turn(id: string, conversationId: string, messageId: string) {
    return api.get<TurnReview>(`${turnPath(id, conversationId, messageId)}`);
  },
  completeTurn(id: string, conversationId: string, messageId: string, files: TurnFilePick[]) {
    return api.post<{ results: TurnFileOutcome[] }>(`${turnPath(id, conversationId, messageId)}/complete`, { files }, { retry: 0 });
  },
  undoTurn(id: string, conversationId: string, messageId: string) {
    return api.post<{ results: TurnFileOutcome[] }>(`${turnPath(id, conversationId, messageId)}/undo`, {}, { retry: 0 });
  },
  turnSeen(id: string, conversationId: string, messageId: string) {
    return api.post<void>(`${turnPath(id, conversationId, messageId)}/seen`, {});
  },
  muteTurns(id: string, conversationId: string, muted: boolean) {
    return api.post<void>(`workspaces/${id}/book/turns/mute`, { conversation: conversationId, muted });
  },
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
  history(id: string, path: string) {
    return api.get<BookHistory>(`workspaces/${id}/book/history?path=${encodeURIComponent(path)}`);
  },
  compareVersion(id: string, path: string, sha: string) {
    return api.get<BookHistoryComparison>(
      `workspaces/${id}/book/history/compare?path=${encodeURIComponent(path)}&sha=${encodeURIComponent(sha)}`,
    );
  },
  restoreVersion(id: string, request: { path: string; sha: string; revision: string }) {
    return api.post<{ revision: string }>(`workspaces/${id}/book/history/restore`, request, { retry: 0 });
  },
  takeBack(id: string, request: { path: string; sha: string; revision: string; index: number }) {
    return api.post<{ revision: string }>(`workspaces/${id}/book/history/take-back`, request, { retry: 0 });
  },
  /** 编辑器应用推敲、再造、节拍之前：把应用前的稿子存一版（不是作品时服务端直接忽略） */
  checkpoint(id: string, path: string, text: string) {
    return api.post<void>(`workspaces/${id}/book/history/checkpoint`, { path, text }, { retry: 0 });
  },
  trash(id: string) {
    return api.get<{ items: TrashedFile[] }>(`workspaces/${id}/book/trash`);
  },
  restoreTrashed(id: string, item: TrashedFile) {
    return api.post<{ path: string }>(
      `workspaces/${id}/book/trash/restore`,
      { key: item.key, name: item.name, path: item.path },
      { retry: 0 },
    );
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
    return api.post<{ restored: string[]; changedSince: string[]; failed: string[]; locked?: string[] }>(
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

function turnPath(id: string, conversationId: string, messageId: string) {
  return `workspaces/${id}/book/turns/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}`;
}
