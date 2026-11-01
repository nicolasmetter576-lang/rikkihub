/**
 * 推敲结果缓存：面板收起不丢弃（还在生成的继续生成），同一处文字再推敲先显示旧结果，「换一批」才重新请求。
 * 只在内存里（模块级），刷新页面即清空。键 = 文件身份 + 规整（去两端空白）后的原文；位置可以变。
 * 每个文件保留最近 [TUIQIAO_CACHE_PER_FILE] 条、总共 [TUIQIAO_CACHE_TOTAL] 条，淘汰的中止其请求。
 * 与 App WorkspaceTuiqiaoCache 同一套规则。
 */

import {
  streamTuiqiao,
  type StreamTuiqiaoOptions,
  type TuiqiaoCandidate,
  type TuiqiaoEvent,
  type TuiqiaoPrepared,
  type TuiqiaoRequest,
} from "@overlay/lib/tuiqiao";

export const TUIQIAO_CACHE_PER_FILE = 10;
export const TUIQIAO_CACHE_TOTAL = 50;

export type TuiqiaoStatus = { kind: "loading" } | { kind: "done" } | { kind: "error"; code: string; message: string };
export type TuiqiaoItem = { slot: string; candidate: TuiqiaoCandidate };

export interface TuiqiaoEntryState {
  prepared: TuiqiaoPrepared | null;
  items: TuiqiaoItem[];
  slotStatus: Record<string, TuiqiaoStatus>;
  /** Waiting for `prepared`, or an error before it / when every slot failed. */
  overall: TuiqiaoStatus;
}

type Stream = (
  request: TuiqiaoRequest,
  onEvent: (event: TuiqiaoEvent) => void,
  options?: StreamTuiqiaoOptions,
) => Promise<void>;

const WHITESPACE = /\s/;

/** 与 Tuiqiao.normalizeRange 一致：去掉选区两端空白；全是空白或越界时为 null。 */
export function tuiqiaoNormalize(source: string, from: number, to: number): { start: number; end: number; original: string } | null {
  if (from < 0 || to > source.length || from >= to) return null;
  let start = from;
  let end = to;
  while (start < end && WHITESPACE.test(source[start])) start++;
  while (end > start && WHITESPACE.test(source[end - 1])) end--;
  return start < end ? { start, end, original: source.slice(start, end) } : null;
}

/** 一次推敲（一处选区）的面板状态与进行中的请求。状态是不可变快照，可直接给 useSyncExternalStore。 */
export class TuiqiaoEntry {
  state: TuiqiaoEntryState = { prepared: null, items: [], slotStatus: {}, overall: { kind: "loading" } };
  /** Set once the original is known; the cache key. */
  original: string | null = null;
  private readonly listeners = new Set<() => void>();
  private readonly controllers = new Set<AbortController>();

  constructor(
    readonly fileKey: string,
    private readonly stream: Stream,
    /** Called when `prepared` reveals the original (an expand request only learns it then). */
    private readonly onOriginal: (entry: TuiqiaoEntry) => void,
  ) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  getState = () => this.state;

  get loading(): boolean {
    return this.state.overall.kind === "loading" || Object.values(this.state.slotStatus).some(status => status.kind === "loading");
  }

  private update(next: Partial<TuiqiaoEntryState>) {
    this.state = { ...this.state, ...next };
    this.listeners.forEach(listener => listener());
  }

  /** The same text found again elsewhere in the document: replace there from now on. */
  moveTo(start: number, end: number) {
    const prepared = this.state.prepared;
    if (prepared && (prepared.start !== start || prepared.end !== end)) this.update({ prepared: { ...prepared, start, end } });
  }

  abort() {
    this.controllers.forEach(controller => controller.abort());
    this.controllers.clear();
  }

  /** Runs a request; with [onlySlot] it re-runs that one slot and leaves the others alone. */
  run(body: TuiqiaoRequest, onlySlot?: string) {
    if (onlySlot) {
      if (this.state.slotStatus[onlySlot]?.kind === "loading") return;
      this.update({
        items: this.state.items.filter(item => item.slot !== onlySlot),
        slotStatus: { ...this.state.slotStatus, [onlySlot]: { kind: "loading" } },
      });
    }
    const controller = new AbortController();
    this.controllers.add(controller);
    let runSlots: string[] = onlySlot ? [onlySlot] : [];
    const finish = (next: TuiqiaoStatus) => {
      const copy = { ...this.state.slotStatus };
      for (const slot of runSlots) if (copy[slot]?.kind === "loading") copy[slot] = next;
      this.update({ slotStatus: copy });
    };
    void this.stream(
      onlySlot ? { ...body, slot: onlySlot } : body,
      event => {
        if (controller.signal.aborted) return;
        switch (event.type) {
          case "prepared":
            if (!onlySlot) {
              const slots = event.slots ?? [{ slot: "default", kind: "default" as const }];
              runSlots = slots.map(slot => slot.slot);
              this.update({
                prepared: { start: event.start, end: event.end, original: event.original, mode: event.mode, format: event.format, slots },
                slotStatus: Object.fromEntries(runSlots.map(slot => [slot, { kind: "loading" } as TuiqiaoStatus])),
                overall: { kind: "done" },
              });
              if (this.original !== event.original) {
                this.original = event.original;
                this.onOriginal(this);
              }
            }
            break;
          case "candidate":
            this.update({ items: [...this.state.items, { slot: event.slot, candidate: { text: event.text, tone: event.tone, why: event.why } }] });
            break;
          case "slot_error":
            this.update({ slotStatus: { ...this.state.slotStatus, [event.slot]: { kind: "error", code: event.code, message: event.message } } });
            break;
          case "done":
            finish({ kind: "done" });
            if (this.state.overall.kind === "loading") this.update({ overall: { kind: "done" } });
            this.controllers.delete(controller);
            break;
          case "error": {
            const failed: TuiqiaoStatus = { kind: "error", code: event.code, message: event.message };
            finish(failed);
            if (!onlySlot) this.update({ overall: failed });
            this.controllers.delete(controller);
            break;
          }
        }
      },
      { signal: controller.signal },
    );
  }
}

export class TuiqiaoCache {
  /** Insertion order = least recently used first. */
  private readonly entries = new Map<string, TuiqiaoEntry>();

  constructor(private readonly stream: Stream = streamTuiqiao) {}

  get size(): number {
    return this.entries.size;
  }

  private static key(fileKey: string, original: string) {
    return `${fileKey}\u0000${original}`;
  }

  /**
   * 选区 `source[from, to)`（按请求的规则去两端空白）以前推敲过时返回那次的结果，并移到这段文字现在的位置；
   * 否则 null。命中算一次使用。
   */
  lookup(fileKey: string, source: string, from: number, to: number): TuiqiaoEntry | null {
    const range = tuiqiaoNormalize(source, from, to);
    if (!range) return null;
    const key = TuiqiaoCache.key(fileKey, range.original);
    const entry = this.entries.get(key);
    if (!entry) return null;
    this.entries.delete(key);
    this.entries.set(key, entry);
    entry.moveTo(range.start, range.end);
    return entry;
  }

  /**
   * 新推敲 [body]（换一批、换档位、扩到整句/整段也走这里），替换同一段原文的旧结果。
   * 扩展请求的原文要等服务端 `prepared` 才知道，届时再登记。
   */
  start(fileKey: string, body: TuiqiaoRequest): TuiqiaoEntry {
    const entry = new TuiqiaoEntry(fileKey, this.stream, next => this.register(next));
    if (!body.expand) {
      const range = tuiqiaoNormalize(body.source, body.start, body.end);
      if (range) {
        entry.original = range.original;
        this.register(entry);
      }
    }
    entry.run(body);
    return entry;
  }

  retrySlot(entry: TuiqiaoEntry, body: TuiqiaoRequest, slot: string) {
    entry.run(body, slot);
  }

  /** 原文已被替换：丢掉这条结果并中止它的请求。 */
  remove(entry: TuiqiaoEntry) {
    for (const [key, value] of this.entries) if (value === entry) this.entries.delete(key);
    entry.abort();
  }

  private register(entry: TuiqiaoEntry) {
    for (const [key, value] of this.entries) if (value === entry) this.entries.delete(key);
    if (entry.original === null) return;
    const key = TuiqiaoCache.key(entry.fileKey, entry.original);
    const previous = this.entries.get(key);
    if (previous && previous !== entry) {
      previous.abort();
      this.entries.delete(key);
    }
    this.entries.set(key, entry);
    this.evict(entry.fileKey);
  }

  private evict(fileKey: string) {
    const sameFile = [...this.entries].filter(([, value]) => value.fileKey === fileKey);
    for (const [key] of sameFile.slice(0, Math.max(0, sameFile.length - TUIQIAO_CACHE_PER_FILE))) this.drop(key);
    for (const key of [...this.entries.keys()].slice(0, Math.max(0, this.entries.size - TUIQIAO_CACHE_TOTAL))) this.drop(key);
  }

  private drop(key: string) {
    this.entries.get(key)?.abort();
    this.entries.delete(key);
  }
}

/** 当前页面共用的缓存。 */
export const tuiqiaoCache = new TuiqiaoCache();
