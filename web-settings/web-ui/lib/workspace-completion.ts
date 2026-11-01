/**
 * `@workspace` 文件补全的纯逻辑层，移植自
 * `app/.../ui/components/ai/completion/WorkspaceCompletionProvider.kt` +
 * `WorkspaceIgnoreMatcher.kt`（保持评分 / 边界 / 忽略规则语义一致）。
 *
 * 本文件不含 React / DOM / 网络依赖：listFiles / readTextFile 均以函数注入，
 * 方便 node:test 用 fake 覆盖，也便于 hook 层做取消与缓存。
 */

export interface WorkspaceCompletionEntry {
  /** workspace FILES 区内的相对路径（不带前导 `/`），如 `src/main.ts` */
  path: string;
  name: string;
  isDirectory: boolean;
}

/** 光标前的 `@...` 片段；range 为 [start, end) 文本偏移（end = 光标位置） */
export interface WorkspaceMention {
  query: string;
  start: number;
  end: number;
}

export interface WorkspaceCompletionItem {
  /** `/workspace/<相对路径>` */
  label: string;
  /** 目录尾部 `/`，文件尾部空格；不读取内容、不附加附件 */
  insertText: string;
  isDirectory: boolean;
  sortScore: number;
}

export interface WorkspaceCompletionResult {
  providerId: "workspace_files" | "device_files";
  start: number;
  end: number;
  items: WorkspaceCompletionItem[];
}

export const WORKSPACE_COMPLETION_PROVIDER_ID = "workspace_files";
export const WORKSPACE_COMPLETION_MAX_ITEMS = 8;
export const WORKSPACE_COMPLETION_MAX_ENTRIES = 500;
export const WORKSPACE_COMPLETION_MAX_DIRS = 80;
export const WORKSPACE_COMPLETION_CACHE_TTL_MS = 5_000;

const DIRECTORY_SCORE_BONUS = 25;
const CWD_SCORE_BONUS = 1_500;
const MENTION_BOUNDARY_CHARS = "([{<\"'";

// ---------------------------------------------------------------------------
// mention 检测（对齐 findWorkspaceMention）
// ---------------------------------------------------------------------------

function isMentionBoundary(ch: string): boolean {
  return /\s/u.test(ch) || MENTION_BOUNDARY_CHARS.includes(ch);
}

/**
 * 仅匹配光标前的当前 `@token`：
 * - `@` 前必须是空白 / 起始 / `([{<"'` 边界 → `user@mail`、代码里的 `a@b` 不触发
 * - query 内不允许出现空白 → 路径含空格时不参与补全（与 App 一致）
 */
export function findWorkspaceMention(text: string, cursor: number): WorkspaceMention | null {
  if (cursor < 0 || cursor > text.length) return null;
  const prefix = text.slice(0, cursor);
  const start = prefix.lastIndexOf("@");
  if (start < 0) return null;
  if (start > 0 && !isMentionBoundary(prefix.charAt(start - 1))) return null;

  const query = prefix.slice(start + 1);
  if ([...query].some((ch) => /\s/u.test(ch))) return null;
  return { query, start, end: cursor };
}

// ---------------------------------------------------------------------------
// query / 路径规范化（对齐 normalizeWorkspaceQuery / isWorkspaceAbsoluteQuery /
// toWorkspaceRelativePath / removeWorkspacePrefix）
// ---------------------------------------------------------------------------

function removeWorkspacePrefix(input: string): string {
  if (input === "/workspace" || input === "workspace") return "";
  if (input.startsWith("/workspace/")) return input.slice("/workspace/".length);
  if (input.startsWith("workspace/")) return input.slice("workspace/".length);
  return input;
}

export function normalizeWorkspaceQuery(query: string): string {
  const normalized = query.replace(/\\/g, "/").replace(/^\s+/, "");
  return removeWorkspacePrefix(normalized).replace(/^\/+/, "");
}

/** 去掉前导空白后以 `/` 或 `workspace` 开头 → 视作绝对路径查询（不加 cwd 加分） */
export function isWorkspaceAbsoluteQuery(query: string): boolean {
  const normalized = query.replace(/\\/g, "/").replace(/^\s+/, "");
  return (
    normalized.startsWith("/") ||
    normalized === "workspace" ||
    normalized.startsWith("workspace/")
  );
}

export function toWorkspaceRelativePath(path: string | null | undefined): string {
  if (!path || !path.trim()) return "";
  return removeWorkspacePrefix(path.replace(/\\/g, "/").trim()).replace(/^\/+|\/+$/g, "");
}

// ---------------------------------------------------------------------------
// 评分（对齐 fuzzyScore / matchScore / matchCwdScore / relativeToCwdOrNull）
// ---------------------------------------------------------------------------

function fuzzyScore(target: string, query: string): number | null {
  if (query.length === 0) return 1;
  if (target === query) return 1000;
  if (target.startsWith(query)) return 900 - Math.min(target.length, 200);
  const containsIndex = target.indexOf(query);
  if (containsIndex >= 0) return 800 - Math.min(containsIndex, 200);

  let queryIndex = 0;
  let firstMatch = -1;
  let lastMatch = -1;
  for (let index = 0; index < target.length; index += 1) {
    if (queryIndex < query.length && target[index] === query[queryIndex]) {
      if (firstMatch < 0) firstMatch = index;
      lastMatch = index;
      queryIndex += 1;
    }
  }
  if (queryIndex !== query.length) return null;

  const span = Math.max(lastMatch - firstMatch + 1, query.length);
  return 500 - Math.min(span, 300) - Math.min(Math.max(firstMatch, 0), 100);
}

function relativeToCwdOrNull(path: string, relativeCwd: string): string | null {
  if (relativeCwd.length === 0) return path;
  if (path === relativeCwd) return "";
  const prefix = `${relativeCwd}/`;
  return path.startsWith(prefix) ? path.slice(prefix.length) : null;
}

function matchCwdScore(
  entry: WorkspaceCompletionEntry,
  query: string,
  relativeCwd: string,
): number | null {
  const cwdPath = relativeToCwdOrNull(entry.path, relativeCwd)?.toLowerCase();
  if (cwdPath == null) return null;
  const score =
    query.length === 0
      ? 1
      : Math.max(
          fuzzyScore(cwdPath, query) ?? -1,
          fuzzyScore(entry.name.toLowerCase(), query) ?? -1,
        );
  if (score < 0) return null;
  return score + CWD_SCORE_BONUS;
}

function matchEntryScore(
  entry: WorkspaceCompletionEntry,
  query: string,
  absoluteQuery: boolean,
  relativeCwd: string,
): number | null {
  const normalizedPath = entry.path.toLowerCase();
  const normalizedName = entry.name.toLowerCase();
  const globalScore =
    query.length === 0
      ? 1
      : (() => {
          const s = Math.max(
            fuzzyScore(normalizedPath, query) ?? -1,
            fuzzyScore(normalizedName, query) ?? -1,
          );
          return s >= 0 ? s : null;
        })();
  if (globalScore == null) return null;

  const cwdScore = absoluteQuery ? null : matchCwdScore(entry, query, relativeCwd);
  const score = Math.max(globalScore, cwdScore ?? -1);
  return score + (entry.isDirectory ? DIRECTORY_SCORE_BONUS : 0);
}

/**
 * 排序：score 降序 → label 长度升序 → label 小写字典序（对齐 App compareByDescending）。
 */
export function buildWorkspaceCompletionItems(
  entries: readonly WorkspaceCompletionEntry[],
  mention: WorkspaceMention,
  relativeCwd: string,
): WorkspaceCompletionItem[] {
  const query = normalizeWorkspaceQuery(mention.query);
  const absoluteQuery = isWorkspaceAbsoluteQuery(mention.query);

  const items = entries
    .map((entry): WorkspaceCompletionItem | null => {
      const score = matchEntryScore(entry, query.toLowerCase(), absoluteQuery, relativeCwd);
      if (score == null) return null;
      const path = `/workspace/${entry.path}`;
      return {
        label: path,
        insertText: entry.isDirectory ? `@${path}/` : `@${path} `,
        isDirectory: entry.isDirectory,
        sortScore: score,
      };
    })
    .filter((item): item is WorkspaceCompletionItem => item !== null)
    .sort((a, b) => {
      if (a.sortScore !== b.sortScore) return b.sortScore - a.sortScore;
      if (a.label.length !== b.label.length) return a.label.length - b.label.length;
      const al = a.label.toLowerCase();
      const bl = b.label.toLowerCase();
      return al < bl ? -1 : al > bl ? 1 : 0;
    })
    .slice(0, WORKSPACE_COMPLETION_MAX_ITEMS);
  return items;
}

export function completeWorkspaceMention(
  text: string,
  cursor: number,
  entries: readonly WorkspaceCompletionEntry[],
  relativeCwd: string,
): WorkspaceCompletionResult | null {
  const mention = findWorkspaceMention(text, cursor);
  if (!mention) return null;
  const items = buildWorkspaceCompletionItems(entries, mention, relativeCwd);
  if (items.length === 0) return null;
  return {
    providerId: WORKSPACE_COMPLETION_PROVIDER_ID,
    start: mention.start,
    end: mention.end,
    items,
  };
}

/**
 * 将候选插入文本：替换 [start,end) 为 insertText，返回新文本与光标位置。
 */
export function applyWorkspaceCompletion(
  text: string,
  start: number,
  end: number,
  insertText: string,
): { text: string; cursor: number } {
  const next = text.slice(0, start) + insertText + text.slice(end);
  return { text: next, cursor: start + insertText.length };
}

// ---------------------------------------------------------------------------
// gitignore 风格忽略匹配（对齐 WorkspaceIgnoreMatcher / Rule / toGitignoreRegex）
// ---------------------------------------------------------------------------

const REGEX_SPECIAL_CHARS = new Set(["\\", ".", "[", "]", "{", "}", "(", ")", "+", "-", "^", "$", "|"]);

function gitignorePatternToRegexSource(pattern: string): string {
  let result = "";
  let index = 0;
  while (index < pattern.length) {
    const char = pattern[index];
    if (char === "*") {
      const nextIsStar = pattern[index + 1] === "*";
      if (nextIsStar && pattern[index + 2] === "/") {
        result += "(?:.*/)?";
        index += 3;
      } else if (nextIsStar) {
        result += ".*";
        index += 2;
      } else {
        result += "[^/]*";
        index += 1;
      }
    } else if (char === "?") {
      result += "[^/]";
      index += 1;
    } else {
      if (REGEX_SPECIAL_CHARS.has(char)) result += "\\";
      result += char;
      index += 1;
    }
  }
  return `^${result}$`;
}

interface IgnoreRule {
  basePath: string;
  regex: RegExp;
  negated: boolean;
  directoryOnly: boolean;
  anchored: boolean;
  hasSlash: boolean;
}

function normalizeWorkspacePath(path: string): string {
  return path.replace(/\\/g, "/").trim().replace(/^\/+|\/+$/g, "");
}

function relativeToBaseOrNull(path: string, basePath: string): string | null {
  if (basePath.length === 0) return path;
  if (path === basePath) return "";
  const prefix = `${basePath}/`;
  return path.startsWith(prefix) ? path.slice(prefix.length) : null;
}

function parseIgnoreRule(basePath: string, rawLine: string): IgnoreRule | null {
  let line = rawLine.trim();
  if (line.length === 0 || line.startsWith("#")) return null;
  if (line.startsWith("\\#") || line.startsWith("\\!")) {
    line = line.slice(1);
  }

  const negated = line.startsWith("!");
  if (negated) line = line.slice(1).trim();
  if (line.length === 0) return null;

  const directoryOnly = line.endsWith("/");
  const anchored = line.startsWith("/");
  const pattern = line.replace(/^\/+|\/+$/g, "");
  if (pattern.length === 0) return null;

  return {
    basePath: normalizeWorkspacePath(basePath),
    regex: new RegExp(gitignorePatternToRegexSource(pattern)),
    negated,
    directoryOnly,
    anchored,
    hasSlash: pattern.includes("/"),
  };
}

function ruleMatches(rule: IgnoreRule, path: string, isDirectory: boolean): boolean {
  if (rule.directoryOnly && !isDirectory) return false;
  const relative = relativeToBaseOrNull(path, rule.basePath);
  if (relative == null || relative.length === 0) return false;
  const target =
    rule.anchored || rule.hasSlash ? relative : relative.slice(relative.lastIndexOf("/") + 1);
  return rule.regex.test(target);
}

// 与 App WorkspaceIgnoreMatcher.DEFAULT_RULES 一致，另加少量系统目录
// （App 依赖 .gitignore 才能盖掉的常见噪音目录，Web 端直接默认忽略）。
const DEFAULT_IGNORE_LINES = [
  "build/",
  ".gradle/",
  ".git/",
  "node_modules/",
  "dist/",
  "out/",
  ".idea/",
  ".vscode/",
  "__pycache__/",
  ".cache/",
  "target/",
];

export class WorkspaceIgnoreMatcher {
  private readonly rules: IgnoreRule[];

  private constructor(rules: IgnoreRule[]) {
    this.rules = rules;
  }

  static defaults(): WorkspaceIgnoreMatcher {
    return new WorkspaceIgnoreMatcher(
      DEFAULT_IGNORE_LINES.map((line) => parseIgnoreRule("", line)).filter(
        (rule): rule is IgnoreRule => rule !== null,
      ),
    );
  }

  withGitignore(directory: string, content: string): WorkspaceIgnoreMatcher {
    const basePath = normalizeWorkspacePath(directory);
    const parsed = content
      .split("\n")
      .map((line) => parseIgnoreRule(basePath, line))
      .filter((rule): rule is IgnoreRule => rule !== null);
    if (parsed.length === 0) return this;
    return new WorkspaceIgnoreMatcher([...this.rules, ...parsed]);
  }

  isIgnored(path: string, isDirectory: boolean): boolean {
    const normalized = normalizeWorkspacePath(path);
    let ignored = false;
    for (const rule of this.rules) {
      if (ruleMatches(rule, normalized, isDirectory)) {
        ignored = !rule.negated;
      }
    }
    return ignored;
  }
}

function directoryAncestors(path: string): string[] {
  if (path.length === 0) return [""];
  const parts = path.split("/").filter((part) => part.length > 0);
  const result = [""];
  let current = "";
  for (const part of parts) {
    current = current.length === 0 ? part : `${current}/${part}`;
    result.push(current);
  }
  return result;
}

// ---------------------------------------------------------------------------
// 索引构建（BFS、cwd 优先、有界 500 项 / 80 目录；source 注入便于测试）
// ---------------------------------------------------------------------------

export interface WorkspaceIndexSource {
  listFiles(path: string): Promise<WorkspaceCompletionEntry[]>;
  /** 读 `.gitignore`；文件不存在 / 读失败 → null */
  readTextFile?(path: string): Promise<string | null>;
}

function throwIfAborted(signal: AbortSignal | undefined): void {
  if (signal?.aborted) {
    throw new DOMException("Workspace completion request aborted", "AbortError");
  }
}

export async function loadWorkspaceCompletionIndex(options: {
  cwd: string;
  source: WorkspaceIndexSource;
  signal?: AbortSignal;
}): Promise<WorkspaceCompletionEntry[]> {
  const { cwd, source, signal } = options;
  const relativeCwd = toWorkspaceRelativePath(cwd);

  const result: WorkspaceCompletionEntry[] = [];
  const queue: string[] = [];
  const seenDirs = new Set<string>();
  const seenEntries = new Set<string>();
  const matcherCache = new Map<string, WorkspaceIgnoreMatcher>();
  // cwd 优先：先扫会话 cwd，再扫根目录 BFS（对齐 App loadEntries）
  if (relativeCwd.length > 0) {
    queue.push(relativeCwd);
    seenDirs.add(relativeCwd);
  }
  queue.push("");
  seenDirs.add("");

  const readGitignore = async (directory: string): Promise<string | null> => {
    if (!source.readTextFile) return null;
    const path = directory.length === 0 ? ".gitignore" : `${directory}/.gitignore`;
    try {
      return await source.readTextFile(path);
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw error;
      if (error instanceof Error && error.name === "AbortError") throw error;
      return null;
    }
  };

  const matcherForDirectory = async (directory: string): Promise<WorkspaceIgnoreMatcher> => {
    const normalized = toWorkspaceRelativePath(directory);
    const cached = matcherCache.get(normalized);
    if (cached) return cached;

    let matcher = WorkspaceIgnoreMatcher.defaults();
    for (const path of directoryAncestors(normalized)) {
      throwIfAborted(signal);
      const ancestorCached = matcherCache.get(path);
      if (ancestorCached) {
        matcher = ancestorCached;
        continue;
      }
      const gitignore = await readGitignore(path);
      if (gitignore != null) {
        matcher = matcher.withGitignore(path, gitignore);
      }
      matcherCache.set(path, matcher);
    }
    return matcher;
  };

  let visitedDirs = 0;
  while (
    queue.length > 0 &&
    result.length < WORKSPACE_COMPLETION_MAX_ENTRIES &&
    visitedDirs < WORKSPACE_COMPLETION_MAX_DIRS
  ) {
    throwIfAborted(signal);
    const path = queue.shift() as string;
    visitedDirs += 1;
    const ignoreMatcher = await matcherForDirectory(path);

    let entries: WorkspaceCompletionEntry[];
    try {
      entries = await source.listFiles(path);
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") throw error;
      entries = [];
    }

    for (const entry of entries) {
      if (result.length >= WORKSPACE_COMPLETION_MAX_ENTRIES) break;
      if (ignoreMatcher.isIgnored(entry.path, entry.isDirectory)) continue;
      if (seenEntries.has(entry.path)) continue;
      seenEntries.add(entry.path);
      result.push(entry);
      if (
        entry.isDirectory &&
        queue.length < WORKSPACE_COMPLETION_MAX_DIRS &&
        !seenDirs.has(entry.path)
      ) {
        seenDirs.add(entry.path);
        queue.push(entry.path);
      }
    }
  }

  return result;
}

// ---------------------------------------------------------------------------
// 键控 in-flight 请求（切 workspace/cwd 竞态防护；纯逻辑可单测）
// ---------------------------------------------------------------------------

export interface InflightRequest<T> {
  /** 请求所属键（如 `${workspaceId} ${relativeCwd}`） */
  readonly key: string;
  readonly controller: AbortController;
  /** 内部在完成时换绑清理链；调用方只读使用 */
  promise: Promise<T>;
}

/**
 * 单槽 in-flight 请求管理器：同一时刻最多一个未完成请求。
 *
 * - `start(key)`：同 key 且在飞 → 复用既有 promise；异 key → abort 旧请求后另起。
 * - task 的 resolve/reject/finally 只影响自己的 record（引用比较），
 *   晚到的旧请求不会清掉新 record、也不会污染返回值（调用方再按 key 校验结果）。
 * - `abort()` 供卸载/显式取消；abort 后 task 收到 AbortError（传播，不吞）。
 */
export class KeyedInflightRequests<T> {
  private current: InflightRequest<T> | null = null;

  /** 当前在飞请求（若无） */
  peek(): InflightRequest<T> | null {
    return this.current;
  }

  /** 同 key 在飞则返回其 promise；否则启动新请求 */
  get(key: string): Promise<T> | null {
    return this.current?.key === key ? this.current.promise : null;
  }

  start(key: string, task: (signal: AbortSignal) => Promise<T>): Promise<T> {
    const existing = this.current;
    if (existing && existing.key === key) {
      return existing.promise;
    }
    // 异 key：旧请求作废。先清槽再 abort，避免旧 finally 与本次 start 交错。
    if (existing) {
      this.current = null;
      existing.controller.abort();
    }

    const controller = new AbortController();
    const record: InflightRequest<T> = {
      key,
      controller,
      // 占位，task 返回的真实 promise 立刻覆盖；清理只按 record 身份比较
      promise: Promise.resolve(null as T),
    };
    // 用 then 双分支而非 finally：返回值链保持 Promise<T>，且清槽回调经该链返回
    record.promise = task(controller.signal).then(
      (value) => {
        if (this.current === record) {
          this.current = null;
        }
        return value;
      },
      (error: unknown) => {
        if (this.current === record) {
          this.current = null;
        }
        throw error;
      },
    );
    this.current = record;
    return record.promise;
  }

  /** 取消当前在飞请求（若有）；幂等 */
  abort(): void {
    const existing = this.current;
    if (!existing) return;
    this.current = null;
    existing.controller.abort();
  }
}

// ---------------------------------------------------------------------------
// 索引缓存（按 workspace + cwd 隔离，TTL 5s 对齐 App；单调时钟防串助手）
// ---------------------------------------------------------------------------

interface IndexCacheEntry {
  key: string;
  at: number;
  entries: WorkspaceCompletionEntry[];
}

export class WorkspaceCompletionIndexCache {
  private entry: IndexCacheEntry | null = null;
  private generation = 0;

  static keyFor(workspaceId: string, cwd: string): string {
    return `${workspaceId}${toWorkspaceRelativePath(cwd)}`;
  }

  get(workspaceId: string, cwd: string): WorkspaceCompletionEntry[] | null {
    const entry = this.entry;
    if (!entry) return null;
    if (entry.key !== WorkspaceCompletionIndexCache.keyFor(workspaceId, cwd)) return null;
    if (Date.now() - entry.at >= WORKSPACE_COMPLETION_CACHE_TTL_MS) return null;
    return entry.entries;
  }

  set(workspaceId: string, cwd: string, entries: WorkspaceCompletionEntry[]): void {
    this.entry = {
      key: WorkspaceCompletionIndexCache.keyFor(workspaceId, cwd),
      at: Date.now(),
      entries,
    };
  }

  /** 切 workspace / cwd 时调用，返回自增世代号用于丢弃过期请求结果 */
  invalidate(): number {
    this.entry = null;
    this.generation += 1;
    return this.generation;
  }

  currentGeneration(): number {
    return this.generation;
  }
}

export const workspaceCompletionIndexCache = new WorkspaceCompletionIndexCache();

// ---------------------------------------------------------------------------
// 设备目录补全（@/device/<alias>/<path>）
// ---------------------------------------------------------------------------
//
// 语义：
// - 只有 query 以 `/device` 开头才进入设备域；`device/...`（无前导斜杠）仍归
//   `/workspace` 域，避免把 workspace 里名为 device 的目录抢走，也绝不把
//   `/device` 前缀 strip 成 workspace 相对路径。
// - `/device` / `/device/<alias前缀>` → 列出可用挂载（alias 即一级目录）。
// - `/device/<alias>/<tail>` → 精确匹配 alias（unknown → 空，不做 fallback），
//   在**该挂载内**按已键入父目录做直接子项 listing，继而有界 BFS
//   （≤8 挂载 / ≤512 条目 / ≤64 目录，单挂载子树，绝不扫整部手机）。
// - label / insertText 一律是 guest 绝对路径 `/device/<alias>/<path>`。
// - 隐藏文件（`.` 开头 / `..`）客户端再过滤一遍；symlink 由服务端过滤。
// - 每次查询都重新拉 availability + mounts 并计算结构指纹（alias/id/source/
//   enabled/active），缓存按指纹隔离：授权撤回 / 停用 / 换 source / 同 alias
//   重映射 → 指纹变 → 旧建议一律不复用。
// ---------------------------------------------------------------------------

export const DEVICE_COMPLETION_PROVIDER_ID = "device_files";
export const DEVICE_COMPLETION_MAX_MOUNTS = 8;
export const DEVICE_COMPLETION_MAX_ENTRIES = 512;
export const DEVICE_COMPLETION_MAX_DIRS = 64;

/** 与后端 WorkspaceDeviceMount DTO 对齐的最小字段集（结构子集，便于注入 fake）。 */
export interface DeviceCompletionMount {
  id: string;
  alias: string;
  sourceRelativePath: string;
  enabled: boolean;
  /** 运行态：授权+激活+目录校验全部通过（DTO 字段名是 `active`） */
  active: boolean;
}

export type DeviceMentionQuery =
  | { kind: "mounts"; rest: string; aliasPrefix: string }
  | { kind: "files"; rest: string; alias: string; dir: string; namePrefix: string };

/** query 是否落在 `/device` 域（仅前导 `/` 的绝对写法；不影响 workspace 语义）。 */
export function isDeviceMentionQuery(query: string): boolean {
  const normalized = query.replace(/\\/g, "/").replace(/^\s+/, "");
  return normalized === "/device" || normalized.startsWith("/device/");
}

/**
 * 解析 `/device` 域 query。返回 null 表示不属于设备域。
 * rest = 去掉 `/device/` 后的部分；files 形态下 dir = 已键入父目录（挂载内相对路径），
 * namePrefix = 最后一段（用于评分的尾段）。含 `..` 组件 → null（拒绝越界）。
 */
export function parseDeviceMentionQuery(query: string): DeviceMentionQuery | null {
  if (!isDeviceMentionQuery(query)) return null;
  const normalized = query.replace(/\\/g, "/").replace(/^\s+/, "");
  const rest = normalized === "/device" ? "" : normalized.slice("/device/".length);

  const slash = rest.indexOf("/");
  if (slash < 0) {
    return { kind: "mounts", rest, aliasPrefix: rest };
  }

  const alias = rest.slice(0, slash);
  const tail = rest.slice(slash + 1);
  if (alias.length === 0 || alias === "..") return null;
  const lastSlash = tail.lastIndexOf("/");
  const dir = lastSlash < 0 ? "" : tail.slice(0, lastSlash);
  const namePrefix = lastSlash < 0 ? tail : tail.slice(lastSlash + 1);
  if (
    dir.split("/").some((part) => part === "..") ||
    namePrefix === ".."
  ) {
    return null;
  }
  return { kind: "files", rest, alias, dir, namePrefix };
}

/** 可用挂载：enabled && active，最多 MAX_MOUNTS，按 alias 排序保证指纹稳定。 */
export function usableDeviceMounts(
  mounts: readonly DeviceCompletionMount[],
): DeviceCompletionMount[] {
  return mounts
    .filter((mount) => mount.enabled && mount.active)
    .sort((a, b) => (a.alias < b.alias ? -1 : a.alias > b.alias ? 1 : 0))
    .slice(0, DEVICE_COMPLETION_MAX_MOUNTS);
}

/**
 * 挂载结构指纹：同一 alias 重映射到别的 mountId/source、停用、激活关闭都会变。
 * 设备补全缓存以此做 scope——任何身份变化都使旧条目不可命中。
 */
export function deviceMountsFingerprint(mounts: readonly DeviceCompletionMount[]): string {
  return usableDeviceMounts(mounts)
    .map(
      (mount) =>
        `${mount.alias}\u0001${mount.id}\u0001${mount.sourceRelativePath}\u0001${mount.enabled}\u0001${mount.active}`,
    )
    .join("\u0002");
}

/** 设备条目是否可入索引（拒隐藏名 / `..` / 空名）。 */
function isBrowsableDeviceEntry(entry: { name: string }): boolean {
  return entry.name.length > 0 && entry.name !== ".." && !entry.name.startsWith(".");
}

export interface DeviceIndexSource {
  /** availability.canBrowse；false / 抛错 → 整个设备域不可用 */
  isAvailable(): Promise<boolean>;
  /** 每次调用都应是新鲜数据（不得由调用方自己长期缓存） */
  listMounts(): Promise<DeviceCompletionMount[]>;
  /** mountId + 挂载内相对目录 → 直接子项（path 为挂载内相对路径） */
  listFiles(
    mountId: string,
    path: string,
  ): Promise<Array<{ path: string; name: string; isDirectory: boolean }>>;
}

export interface DeviceCompletionEntries {
  /** 本次条目所依据的挂载指纹；缓存/校验键的一部分 */
  fingerprint: string;
  /** 设备域路径（`<alias>/<rel>` 或挂载级的 `<alias>`），供 buildDeviceCompletionItems 用 */
  entries: WorkspaceCompletionEntry[];
}

/**
 * 加载设备域候选：
 * - mounts 形态 → 可用挂载按 alias 前缀过滤后作为目录条目；
 * - files 形态 → alias 必须精确命中可用挂载（否则空），在该挂载 dir 子树内
 *   有界 BFS（≤MAX_DIRS 目录 / ≤MAX_ENTRIES 条目），单点故障目录跳过不炸。
 */
export async function loadDeviceCompletionEntries(options: {
  query: DeviceMentionQuery;
  source: DeviceIndexSource;
  signal?: AbortSignal;
}): Promise<DeviceCompletionEntries> {
  const { query, source, signal } = options;
  const empty = { fingerprint: "", entries: [] as WorkspaceCompletionEntry[] };

  throwIfAborted(signal);
  let available = false;
  try {
    available = await source.isAvailable();
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") throw error;
    available = false;
  }
  throwIfAborted(signal);
  if (!available) return empty;

  const mounts = usableDeviceMounts(await source.listMounts());
  const fingerprint = deviceMountsFingerprint(mounts);
  throwIfAborted(signal);

  if (query.kind === "mounts") {
    const prefix = query.aliasPrefix.toLowerCase();
    return {
      fingerprint,
      entries: mounts
        .filter((mount) => mount.alias.toLowerCase().startsWith(prefix))
        .map((mount) => ({ path: mount.alias, name: mount.alias, isDirectory: true })),
    };
  }

  const mount = mounts.find((candidate) => candidate.alias === query.alias);
  if (!mount) return { fingerprint, entries: [] }; // unknown / 不可用 alias：无 fallback

  const result: WorkspaceCompletionEntry[] = [];
  const seen = new Set<string>();
  const queue = [query.dir];
  let visitedDirs = 0;
  while (
    queue.length > 0 &&
    result.length < DEVICE_COMPLETION_MAX_ENTRIES &&
    visitedDirs < DEVICE_COMPLETION_MAX_DIRS
  ) {
    throwIfAborted(signal);
    const dir = queue.shift() as string;
    visitedDirs += 1;
    let children: Array<{ path: string; name: string; isDirectory: boolean }>;
    try {
      children = await source.listFiles(mount.id, dir);
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") throw error;
      children = [];
    }
    for (const child of children.slice(0, DEVICE_COMPLETION_MAX_ENTRIES)) {
      if (result.length >= DEVICE_COMPLETION_MAX_ENTRIES) break;
      if (!isBrowsableDeviceEntry(child)) continue;
      // 信任服务端 path 相对挂载，但客户端再规范化一遍，拒绝对路径/`..`
      const rel = child.path.replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
      if (rel.length === 0 || rel.split("/").some((part) => part === "..")) continue;
      const path = `${query.alias}/${rel}`;
      if (seen.has(path)) continue;
      seen.add(path);
      result.push({ path, name: child.name, isDirectory: child.isDirectory });
      if (child.isDirectory && queue.length < DEVICE_COMPLETION_MAX_DIRS) {
        queue.push(rel);
      }
    }
  }
  return { fingerprint, entries: result };
}

function matchDeviceScore(entry: WorkspaceCompletionEntry, query: string): number | null {
  const score =
    query.length === 0
      ? 1
      : Math.max(
          fuzzyScore(entry.path.toLowerCase(), query) ?? -1,
          fuzzyScore(entry.name.toLowerCase(), query) ?? -1,
        );
  if (score < 0) return null;
  return score + (entry.isDirectory ? DIRECTORY_SCORE_BONUS : 0);
}

/** 设备域排序：同 /workspace 的分数语义，label/insertText 用 `/device/...`。 */
export function buildDeviceCompletionItems(
  entries: readonly WorkspaceCompletionEntry[],
  mention: WorkspaceMention,
): WorkspaceCompletionItem[] {
  const normalized = mention.query.replace(/\\/g, "/").replace(/^\s+/, "");
  const rest =
    normalized === "/device"
      ? ""
      : normalized.startsWith("/device/")
        ? normalized.slice("/device/".length)
        : normalized;
  const query = rest.toLowerCase();

  return entries
    .map((entry): WorkspaceCompletionItem | null => {
      const score = matchDeviceScore(entry, query);
      if (score == null) return null;
      const path = `/device/${entry.path}`;
      return {
        label: path,
        insertText: entry.isDirectory ? `@${path}/` : `@${path} `,
        isDirectory: entry.isDirectory,
        sortScore: score,
      };
    })
    .filter((item): item is WorkspaceCompletionItem => item !== null)
    .sort((a, b) => {
      if (a.sortScore !== b.sortScore) return b.sortScore - a.sortScore;
      if (a.label.length !== b.label.length) return a.label.length - b.label.length;
      const al = a.label.toLowerCase();
      const bl = b.label.toLowerCase();
      return al < bl ? -1 : al > bl ? 1 : 0;
    })
    .slice(0, WORKSPACE_COMPLETION_MAX_ITEMS);
}

// ---------------------------------------------------------------------------
// 设备条目缓存：scope = workspaceId + 挂载指纹，内部按目录分桶；TTL 同 workspace。
// 指纹由每次查询前的新鲜 mounts 算出 → 权限/激活/source 变化天然使缓存失效。
// ---------------------------------------------------------------------------

export class DeviceCompletionIndexCache {
  private scopeKey = "";
  private dirs = new Map<string, { at: number; entries: WorkspaceCompletionEntry[] }>();
  private generation = 0;

  static scopeKeyFor(workspaceId: string, fingerprint: string): string {
    return `${workspaceId}\u0000${fingerprint}`;
  }

  /** dir 用 `alias\u0000dir` 作为桶键（调用方负责拼装），便于区分不同挂载同目录。 */
  get(
    workspaceId: string,
    fingerprint: string,
    dirKey: string,
  ): WorkspaceCompletionEntry[] | null {
    if (this.scopeKey !== DeviceCompletionIndexCache.scopeKeyFor(workspaceId, fingerprint)) {
      return null;
    }
    const record = this.dirs.get(dirKey);
    if (!record) return null;
    if (Date.now() - record.at >= WORKSPACE_COMPLETION_CACHE_TTL_MS) return null;
    return record.entries;
  }

  set(
    workspaceId: string,
    fingerprint: string,
    dirKey: string,
    entries: WorkspaceCompletionEntry[],
  ): void {
    const scopeKey = DeviceCompletionIndexCache.scopeKeyFor(workspaceId, fingerprint);
    if (this.scopeKey !== scopeKey) {
      this.scopeKey = scopeKey;
      this.dirs.clear();
    }
    this.dirs.set(dirKey, { at: Date.now(), entries });
    // 桶数也收敛一下（最多 64 个目录桶，与遍历上界一致）
    if (this.dirs.size > DEVICE_COMPLETION_MAX_DIRS) {
      const oldest = this.dirs.keys().next().value;
      if (oldest !== undefined) this.dirs.delete(oldest);
    }
  }

  /** 切 workspace 时调用；返回自增世代号用于丢弃过期请求结果。 */
  invalidate(): number {
    this.scopeKey = "";
    this.dirs.clear();
    this.generation += 1;
    return this.generation;
  }

  currentGeneration(): number {
    return this.generation;
  }
}

export const deviceCompletionIndexCache = new DeviceCompletionIndexCache();
