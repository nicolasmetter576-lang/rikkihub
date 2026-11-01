/**
 * 再造会话缓存：面板收起不丢弃（还在生成的继续生成），再框选同一段原文回到原来的对话。
 * 只在内存里（模块级），刷新页面即清空。键 = 文件身份 + 规整（去两端空白）后的原文；位置可以变。
 * 每个文件保留最近 [ZAIZAO_CACHE_PER_FILE] 个、总共 [ZAIZAO_CACHE_TOTAL] 个会话，淘汰的中止其请求。
 * 与 App WorkspaceZaizaoCache 同一套规则：每轮建在底稿上（「接着改这版」选定的稿，否则最新一稿；没有就是原文），
 * 换一版是同一底稿、类型、要求的又一稿；同一会话同时只写一稿。
 */

import {
  streamZaizao,
  ZAIZAO_MAX_REQUIREMENT_CHARS,
  ZAIZAO_MAX_ROUNDS,
  type StreamZaizaoOptions,
  type ZaizaoEvent,
  type ZaizaoRequest,
  type ZaizaoTurn,
  type ZaizaoType,
} from "@overlay/lib/zaizao";
import { tuiqiaoNormalize } from "@overlay/lib/tuiqiao-cache";

export const ZAIZAO_CACHE_PER_FILE = 10;
export const ZAIZAO_CACHE_TOTAL = 50;

export type ZaizaoStatus =
  | { kind: "loading" }
  | { kind: "done" }
  | { kind: "stopped" }
  | { kind: "error"; code: string; message: string };

export interface ZaizaoDraft {
  /** Creation order within the session (also the index in `drafts`). */
  id: number;
  round: number;
  number: number;
  reasoning: string;
  plan: string;
  result: string;
  output: string;
  status: ZaizaoStatus;
}

export interface ZaizaoRound {
  number: number;
  type: ZaizaoType;
  requirement: string;
  /** The draft it builds on; null = the original. */
  baseId: number | null;
  draftIds: number[];
}

export interface ZaizaoEntryState {
  fileName: string;
  /** The document as the first round sent it (later rounds replay it); follows the editor until then. */
  source: string;
  start: number;
  end: number;
  original: string;
  /** Where the original is in the editor now, for replacing. */
  liveStart: number;
  liveEnd: number;
  includeAfter: boolean;
  type: ZaizaoType;
  draftText: string;
  /** Picked with 接着改这版; null = follow the newest draft. */
  pinnedBase: number | null;
  rounds: ZaizaoRound[];
  drafts: ZaizaoDraft[];
}

export type ZaizaoSendError = "EMPTY_REQUIREMENT" | "REQUIREMENT_TOO_LONG" | "TOO_MANY_ROUNDS";

type Stream = (request: ZaizaoRequest, onEvent: (event: ZaizaoEvent) => void, options?: StreamZaizaoOptions) => Promise<void>;

/** ① … ⑳ for round numbers. */
export function zaizaoRoundMark(number: number): string {
  return number >= 1 && number <= 20 ? String.fromCharCode(0x2460 + number - 1) : `(${number})`;
}

export function zaizaoDraftLabel(draft: Pick<ZaizaoDraft, "round" | "number">): string {
  return `${zaizaoRoundMark(draft.round)}-${draft.number}`;
}

/** Has text and is not being written: can be copied, replaced or continued from. */
export function zaizaoUsable(draft: ZaizaoDraft): boolean {
  return draft.result.trim() !== "" && draft.status.kind !== "loading";
}

export function zaizaoBusy(state: ZaizaoEntryState): boolean {
  return state.drafts.some(draft => draft.status.kind === "loading");
}

/**
 * What the next round builds on: the picked draft, else the newest one (also while it is being written), else,
 * when the newest failed, the newest usable one; null = the original.
 */
export function zaizaoBase(state: ZaizaoEntryState): ZaizaoDraft | null {
  const pinned = state.pinnedBase === null ? undefined : state.drafts[state.pinnedBase];
  if (pinned && zaizaoUsable(pinned)) return pinned;
  const newest = state.drafts.at(-1);
  if (newest && (zaizaoUsable(newest) || newest.status.kind === "loading")) return newest;
  for (let i = state.drafts.length - 1; i >= 0; i--) if (zaizaoUsable(state.drafts[i])) return state.drafts[i];
  return null;
}

/** The rounds from the original up to [baseId], as history for the model. */
export function zaizaoHistory(state: ZaizaoEntryState, baseId: number | null): ZaizaoTurn[] {
  const turns: ZaizaoTurn[] = [];
  let id = baseId;
  while (id !== null) {
    const draft = state.drafts[id];
    const round = state.rounds[draft.round - 1];
    turns.push({ type: round.type, requirement: round.requirement, output: draft.output });
    id = round.baseId;
  }
  return turns.reverse();
}

/** One 再造 session of one selection. The state is an immutable snapshot, ready for useSyncExternalStore. */
export class ZaizaoEntry {
  state: ZaizaoEntryState;
  private readonly listeners = new Set<() => void>();
  private readonly controllers = new Map<number, AbortController>();

  constructor(
    readonly fileKey: string,
    init: { fileName: string; source: string; start: number; end: number; includeAfter: boolean; type: ZaizaoType },
    private readonly stream: Stream,
  ) {
    this.state = {
      fileName: init.fileName,
      source: init.source,
      start: init.start,
      end: init.end,
      original: init.source.slice(init.start, init.end),
      liveStart: init.start,
      liveEnd: init.end,
      includeAfter: init.includeAfter,
      type: init.type,
      draftText: "",
      pinnedBase: null,
      rounds: [],
      drafts: [],
    };
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  getState = () => this.state;

  get started(): boolean {
    return this.state.rounds.length > 0;
  }

  get busy(): boolean {
    return zaizaoBusy(this.state);
  }

  private update(next: Partial<ZaizaoEntryState>) {
    this.state = { ...this.state, ...next };
    this.listeners.forEach(listener => listener());
  }

  private patchDraft(id: number, next: Partial<ZaizaoDraft>) {
    const drafts = this.state.drafts.slice();
    drafts[id] = { ...drafts[id], ...next };
    this.update({ drafts });
  }

  setDraftText(draftText: string) {
    this.update({ draftText: draftText.slice(0, ZAIZAO_MAX_REQUIREMENT_CHARS) });
  }

  setType(type: ZaizaoType) {
    this.update({ type });
  }

  /** Only before the first round (later rounds replay the first message). */
  setIncludeAfter(includeAfter: boolean) {
    if (!this.started) this.update({ includeAfter });
  }

  continueFrom(draftId: number) {
    const draft = this.state.drafts[draftId];
    if (draft && zaizaoUsable(draft)) this.update({ pinnedBase: draftId });
  }

  /** Whether `doc[liveStart, liveEnd)` is still the original. */
  matches(doc: string): boolean {
    return this.state.liveEnd <= doc.length && doc.slice(this.state.liveStart, this.state.liveEnd) === this.state.original;
  }

  /** The same text found again: replace there from now on; before the first round also follow the editor's text. */
  moveTo(source: string, start: number, end: number) {
    if (!this.started) this.update({ source, start, end, liveStart: start, liveEnd: end });
    else if (this.state.liveStart !== start || this.state.liveEnd !== end) this.update({ liveStart: start, liveEnd: end });
  }

  /** Sends [requirement] as a new round on the current base; returns why not (nothing happens while busy). */
  send(requirement: string): ZaizaoSendError | null {
    if (this.busy) return null;
    const text = requirement.trim();
    if (!text) return "EMPTY_REQUIREMENT";
    if (text.length > ZAIZAO_MAX_REQUIREMENT_CHARS) return "REQUIREMENT_TOO_LONG";
    const base = zaizaoBase(this.state);
    if (zaizaoHistory(this.state, base?.id ?? null).length + 1 > ZAIZAO_MAX_ROUNDS) return "TOO_MANY_ROUNDS";
    const round: ZaizaoRound = { number: this.state.rounds.length + 1, type: this.state.type, requirement: text, baseId: base?.id ?? null, draftIds: [] };
    this.update({ rounds: [...this.state.rounds, round], draftText: "", pinnedBase: null });
    this.run(this.newDraft(round.number), [], true);
    return null;
  }

  /** 换一版: another draft of round [number], told to differ from the drafts so far. */
  another(number: number) {
    const round = this.state.rounds[number - 1];
    if (this.busy || !round) return;
    const avoid = round.draftIds.map(id => this.state.drafts[id]).filter(zaizaoUsable).map(draft => draft.result);
    this.run(this.newDraft(number), avoid, false);
  }

  /** Writes a failed or stopped draft again. */
  retry(draftId: number) {
    const draft = this.state.drafts[draftId];
    if (this.busy || !draft) return;
    const round = this.state.rounds[draft.round - 1];
    const avoid = round.draftIds.filter(id => id !== draftId).map(id => this.state.drafts[id]).filter(zaizaoUsable).map(d => d.result);
    this.patchDraft(draftId, { reasoning: "", plan: "", result: "", output: "", status: { kind: "loading" } });
    this.run(draftId, avoid, false);
  }

  /** Stops what is being written; what was written so far stays. */
  stop() {
    for (const draft of this.state.drafts) {
      if (draft.status.kind !== "loading") continue;
      this.controllers.get(draft.id)?.abort();
      this.controllers.delete(draft.id);
      this.patchDraft(draft.id, { status: { kind: "stopped" } });
    }
  }

  abort() {
    this.controllers.forEach(controller => controller.abort());
    this.controllers.clear();
  }

  private newDraft(roundNumber: number): number {
    const id = this.state.drafts.length;
    const rounds = this.state.rounds.slice();
    const round = rounds[roundNumber - 1];
    rounds[roundNumber - 1] = { ...round, draftIds: [...round.draftIds, id] };
    const draft: ZaizaoDraft = {
      id, round: roundNumber, number: round.draftIds.length + 1,
      reasoning: "", plan: "", result: "", output: "", status: { kind: "loading" },
    };
    this.update({ rounds, drafts: [...this.state.drafts, draft] });
    return id;
  }

  private run(draftId: number, avoid: string[], record: boolean) {
    const draft = this.state.drafts[draftId];
    const round = this.state.rounds[draft.round - 1];
    const controller = new AbortController();
    this.controllers.set(draftId, controller);
    const { fileName, source, start, end, includeAfter } = this.state;
    void this.stream(
      {
        fileName, source, start, end, includeAfter,
        history: zaizaoHistory(this.state, round.baseId),
        type: round.type,
        requirement: round.requirement,
        avoid,
        record,
      },
      event => {
        if (controller.signal.aborted) return;
        switch (event.type) {
          case "update":
            this.patchDraft(draftId, { reasoning: event.reasoning, plan: event.plan, result: event.result });
            break;
          case "done":
            this.controllers.delete(draftId);
            this.patchDraft(draftId, { reasoning: event.reasoning, plan: event.plan, result: event.result, output: event.output, status: { kind: "done" } });
            break;
          case "error":
            this.controllers.delete(draftId);
            this.patchDraft(draftId, { status: { kind: "error", code: event.code, message: event.message } });
            break;
          default:
            break;
        }
      },
      { signal: controller.signal },
    );
  }
}

export class ZaizaoCache {
  /** Insertion order = least recently used first. */
  private readonly entries = new Map<string, ZaizaoEntry>();

  constructor(private readonly stream: Stream = streamZaizao) {}

  get size(): number {
    return this.entries.size;
  }

  private static key(fileKey: string, original: string) {
    return `${fileKey}\u0000${original}`;
  }

  find(fileKey: string, original: string): ZaizaoEntry | null {
    return this.entries.get(ZaizaoCache.key(fileKey, original)) ?? null;
  }

  /** The kept session for `source[from, to)` (trimmed like a request is), moved to where that text now is; else null. */
  lookup(fileKey: string, source: string, from: number, to: number): ZaizaoEntry | null {
    const range = tuiqiaoNormalize(source, from, to);
    if (!range) return null;
    const key = ZaizaoCache.key(fileKey, range.original);
    const entry = this.entries.get(key);
    if (!entry) return null;
    this.entries.delete(key);
    this.entries.set(key, entry);
    entry.moveTo(source, range.start, range.end);
    return entry;
  }

  /** A new (not yet started) session for the already trimmed `source[start, end)`, replacing a kept one for the same text. */
  create(fileKey: string, init: { fileName: string; source: string; start: number; end: number; includeAfter: boolean; type: ZaizaoType }): ZaizaoEntry {
    const entry = new ZaizaoEntry(fileKey, init, this.stream);
    const key = ZaizaoCache.key(fileKey, entry.state.original);
    this.entries.get(key)?.abort();
    this.entries.delete(key);
    this.entries.set(key, entry);
    this.evict(fileKey);
    return entry;
  }

  /** Drops [entry] (a narrower selection expanded before its first round), aborting its drafts. */
  remove(entry: ZaizaoEntry) {
    const key = ZaizaoCache.key(entry.fileKey, entry.state.original);
    if (this.entries.get(key) === entry) this.entries.delete(key);
    entry.abort();
  }

  private evict(fileKey: string) {
    const sameFile = [...this.entries].filter(([, value]) => value.fileKey === fileKey);
    for (const [key] of sameFile.slice(0, Math.max(0, sameFile.length - ZAIZAO_CACHE_PER_FILE))) this.drop(key);
    for (const key of [...this.entries.keys()].slice(0, Math.max(0, this.entries.size - ZAIZAO_CACHE_TOTAL))) this.drop(key);
  }

  private drop(key: string) {
    this.entries.get(key)?.abort();
    this.entries.delete(key);
  }
}

/** 当前页面共用的缓存。 */
export const zaizaoCache = new ZaizaoCache();
