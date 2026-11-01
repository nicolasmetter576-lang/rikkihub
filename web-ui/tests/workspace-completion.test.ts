import test from "node:test";
import assert from "node:assert/strict";

import {
  applyWorkspaceCompletion,
  buildWorkspaceCompletionItems,
  completeWorkspaceMention,
  findWorkspaceMention,
  isWorkspaceAbsoluteQuery,
  loadWorkspaceCompletionIndex,
  normalizeWorkspaceQuery,
  toWorkspaceRelativePath,
  WorkspaceCompletionIndexCache,
  WorkspaceIgnoreMatcher,
  type WorkspaceCompletionEntry,
} from "../../web-settings/web-ui/lib/workspace-completion";

function entry(path: string, isDirectory = false): WorkspaceCompletionEntry {
  return { path, name: path.split("/").pop() ?? path, isDirectory };
}

// ---------------------------------------------------------------------------
// findWorkspaceMention：边界 / email / 空格 / 光标
// ---------------------------------------------------------------------------

test("findWorkspaceMention matches a bare @token before cursor", () => {
  const m = findWorkspaceMention("@src", 4);
  assert.deepEqual(m, { query: "src", start: 0, end: 4 });
});

test("findWorkspaceMention allows @ after whitespace and brackets", () => {
  assert.equal(findWorkspaceMention("hello @foo", 10)?.query, "foo");
  assert.equal(findWorkspaceMention("(@dir", 5)?.query, "dir");
  assert.equal(findWorkspaceMention('"@x', 3)?.query, "x");
});

test("findWorkspaceMention rejects emails and code-like a@b", () => {
  assert.equal(findWorkspaceMention("user@mail.com", 13), null);
  assert.equal(findWorkspaceMention("foo(a@b", 7), null);
  assert.equal(findWorkspaceMention("a@b @ok", 7)?.query, "ok");
});

test("findWorkspaceMention rejects queries containing whitespace", () => {
  // 光标在 token 后的文本里：最后一个 @ 的 query 带空格 → 不触发
  assert.equal(findWorkspaceMention("@foo bar", 8), null);
  // 但光标停在 @token 内部时仍有效
  assert.deepEqual(findWorkspaceMention("@foo bar", 4), {
    query: "foo",
    start: 0,
    end: 4,
  });
});

test("findWorkspaceMention respects cursor position and edge cases", () => {
  assert.equal(findWorkspaceMention("@abcdef", 3)?.query, "ab");
  assert.equal(findWorkspaceMention("", 0), null);
  assert.equal(findWorkspaceMention("@abcdef", -1), null);
  assert.equal(findWorkspaceMention("@abcdef", 99), null);
  // 只有 @：query 为空字符串，合法（列出 top 候选）
  assert.deepEqual(findWorkspaceMention("@", 1), { query: "", start: 0, end: 1 });
});

// ---------------------------------------------------------------------------
// query / cwd 规范化
// ---------------------------------------------------------------------------

test("normalizeWorkspaceQuery strips workspace prefixes and slashes", () => {
  assert.equal(normalizeWorkspaceQuery("src/main"), "src/main");
  assert.equal(normalizeWorkspaceQuery("/workspace/src"), "src");
  assert.equal(normalizeWorkspaceQuery("workspace/src"), "src");
  assert.equal(normalizeWorkspaceQuery("/src"), "src");
  assert.equal(normalizeWorkspaceQuery("\\workspace\\src"), "src");
  assert.equal(normalizeWorkspaceQuery("  /workspace/a"), "a");
});

test("isWorkspaceAbsoluteQuery detects absolute / workspace-rooted queries", () => {
  assert.ok(isWorkspaceAbsoluteQuery("/workspace/s"));
  assert.ok(isWorkspaceAbsoluteQuery("/s"));
  assert.ok(isWorkspaceAbsoluteQuery("workspace"));
  assert.ok(isWorkspaceAbsoluteQuery("workspace/s"));
  assert.ok(!isWorkspaceAbsoluteQuery("s"));
  assert.ok(!isWorkspaceAbsoluteQuery("a/b"));
});

test("toWorkspaceRelativePath normalizes cwd", () => {
  assert.equal(toWorkspaceRelativePath("/workspace/a/b/"), "a/b");
  assert.equal(toWorkspaceRelativePath("/workspace"), "");
  assert.equal(toWorkspaceRelativePath(null), "");
  assert.equal(toWorkspaceRelativePath(undefined), "");
  assert.equal(toWorkspaceRelativePath("  "), "");
  assert.equal(toWorkspaceRelativePath("a\\b"), "a/b");
});

// ---------------------------------------------------------------------------
// 排序 / cwd 优先 / 目录加成 / 上界
// ---------------------------------------------------------------------------

test("buildWorkspaceCompletionItems ranks cwd entries first", () => {
  const entries = [entry("a/x.txt"), entry("b/x.txt"), entry("c/y.txt")];
  const mention = { query: "x", start: 0, end: 1 };
  const items = buildWorkspaceCompletionItems(entries, mention, "a");
  assert.equal(items[0].label, "/workspace/a/x.txt");
});

test("buildWorkspaceCompletionItems: insertText conventions", () => {
  const mention = { query: "", start: 0, end: 0 };
  const items = buildWorkspaceCompletionItems(
    [entry("docs", true), entry("docs/readme.md")],
    mention,
    "",
  );
  const dir = items.find((i) => i.isDirectory);
  const file = items.find((i) => !i.isDirectory);
  assert.equal(dir?.insertText, "@/workspace/docs/");
  assert.equal(file?.insertText, "@/workspace/docs/readme.md ");
  // 目录加成：同 query 下目录排在文件前
  assert.ok((dir?.sortScore ?? 0) > (file?.sortScore ?? 0));
});

test("absolute query skips the cwd bonus", () => {
  const entries = [entry("a/x.txt"), entry("b/x.txt")];
  const relative = buildWorkspaceCompletionItems(
    entries,
    { query: "x", start: 0, end: 1 },
    "a",
  );
  const absolute = buildWorkspaceCompletionItems(
    entries,
    { query: "/x", start: 0, end: 2 },
    "a",
  );
  assert.equal(relative[0].label, "/workspace/a/x.txt");
  // 绝对查询无 cwd 加分：两者 globalScore 相同，按 label 长度/字典序排 → a/x 仍在前
  assert.equal(absolute[0].label, "/workspace/a/x.txt");
  // 但加分差异可验：relative 的 a/x 比 absolute 的 a/x 分高 CWD_SCORE_BONUS
  assert.ok(
    relative[0].sortScore > absolute[0].sortScore,
    "relative cwd query should boost score",
  );
});

test("buildWorkspaceCompletionItems respects the 8-item cap and ordering", () => {
  const entries = Array.from({ length: 30 }, (_, i) => entry(`dir/f${String(i).padStart(2, "0")}.txt`));
  const items = buildWorkspaceCompletionItems(
    entries,
    { query: "f", start: 0, end: 1 },
    "",
  );
  assert.equal(items.length, 8);
  // 已按 sortScore 降序
  for (let i = 1; i < items.length; i += 1) {
    assert.ok(items[i - 1].sortScore >= items[i].sortScore);
  }
});

test("no match → filtered out (non-fuzzy garbage query)", () => {
  const items = buildWorkspaceCompletionItems(
    [entry("a/readme.md")],
    { query: "zzzzqqqq", start: 0, end: 8 },
    "",
  );
  assert.equal(items.length, 0);
});

// ---------------------------------------------------------------------------
// applyWorkspaceCompletion：纯文本插入 + 光标
// ---------------------------------------------------------------------------

test("applyWorkspaceCompletion replaces the mention range only", () => {
  const { text, cursor } = applyWorkspaceCompletion(
    "please check @sr please",
    13,
    16,
    "@/workspace/src/ ",
  );
  // insertText 尾部空格 + 原有空格 → 双空格，光标在插入文本末尾
  assert.equal(text, "please check @/workspace/src/  please");
  assert.equal(cursor, 13 + "@/workspace/src/ ".length);
});

test("applyWorkspaceCompletion file suffix keeps trailing content", () => {
  const { text, cursor } = applyWorkspaceCompletion("@re", 0, 3, "@/workspace/req.md ");
  assert.equal(text, "@/workspace/req.md ");
  assert.equal(cursor, text.length);
});

// ---------------------------------------------------------------------------
// WorkspaceIgnoreMatcher：默认规则 + gitignore 语义
// ---------------------------------------------------------------------------

test("WorkspaceIgnoreMatcher applies default rules", () => {
  const matcher = WorkspaceIgnoreMatcher.defaults();
  assert.ok(matcher.isIgnored(".git", true));
  // 目录型规则（.git/）不匹配目录内文件本身 —— 与 gitignore/App 语义一致；
  // BFS 因目录被忽略而不会进入，文件永远拿不到，无需逐文件匹配
  assert.ok(!matcher.isIgnored(".git/config", false));
  assert.ok(matcher.isIgnored("node_modules", true));
  assert.ok(matcher.isIgnored("frontend/node_modules", true));
  assert.ok(matcher.isIgnored("dist", true));
  assert.ok(matcher.isIgnored("project/build", true));
  assert.ok(matcher.isIgnored("rust/target", true));
  assert.ok(!matcher.isIgnored("src/build.gradle", false));
  assert.ok(!matcher.isIgnored("docs", true));
});

test("WorkspaceIgnoreMatcher honors .gitignore lines (negation, dir-only, anchored)", () => {
  const matcher = WorkspaceIgnoreMatcher.defaults().withGitignore(
    "",
    "logs/\n*.log\n!keep.log\n/secret.txt\n*.tmp\n",
  );
  assert.ok(matcher.isIgnored("logs", true));
  // logs/ 目录型规则不匹配内部文件
  assert.ok(!matcher.isIgnored("logs/a.txt", false));
  // *.log 任意层级生效；取反恢复
  assert.ok(matcher.isIgnored("a/debug.log", false));
  assert.ok(!matcher.isIgnored("a/keep.log", false));
  // anchored：仅根目录
  assert.ok(matcher.isIgnored("secret.txt", false));
  assert.ok(!matcher.isIgnored("sub/secret.txt", false));
  // 非锚定 glob：任意层级
  assert.ok(matcher.isIgnored("sub/deep/x.tmp", false));
  assert.ok(!matcher.isIgnored("src/index.ts", false));
});

test("WorkspaceIgnoreMatcher scopes subdirectory .gitignore", () => {
  const matcher = WorkspaceIgnoreMatcher.defaults().withGitignore("a", "*.log");
  assert.ok(matcher.isIgnored("a/debug.log", false));
  assert.ok(matcher.isIgnored("a/nested/x.log", false));
  assert.ok(!matcher.isIgnored("b/debug.log", false));
});

test("WorkspaceIgnoreMatcher handles comments and blank lines", () => {
  const matcher = WorkspaceIgnoreMatcher.defaults().withGitignore(
    "",
    "# comment\n\n   \n*.md\n",
  );
  assert.ok(matcher.isIgnored("readme.md", false));
});

// ---------------------------------------------------------------------------
// loadWorkspaceCompletionIndex：BFS / cwd 优先 / 上界 / gitignore / abort
// ---------------------------------------------------------------------------

function fakeSource(
  tree: Record<string, WorkspaceCompletionEntry[]>,
  gitignores: Record<string, string> = {},
) {
  const calls: string[] = [];
  return {
    calls,
    source: {
      async listFiles(path: string): Promise<WorkspaceCompletionEntry[]> {
        calls.push(path);
        return tree[path] ?? [];
      },
      async readTextFile(path: string): Promise<string | null> {
        return gitignores[path] ?? null;
      },
    },
  };
}

test("loadWorkspaceCompletionIndex explores cwd first, then root BFS", async () => {
  const { calls, source } = fakeSource({
    "src/app": [entry("src/app/main.ts"), entry("src/app/deep", true)],
    "src/app/deep": [entry("src/app/deep/x.ts")],
    "": [entry("src", true), entry("top.txt")],
    src: [entry("src/app", true), entry("src/other.ts")],
  });
  const entries = await loadWorkspaceCompletionIndex({ cwd: "/workspace/src/app", source });
  // cwd 目录被首先访问
  assert.equal(calls[0], "src/app");
  assert.ok(calls.indexOf("") > 0);
  const paths = entries.map((e) => e.path);
  assert.ok(paths.includes("src/app/main.ts"));
  assert.ok(paths.includes("src/app/deep/x.ts"));
  assert.ok(paths.includes("top.txt"));
  assert.ok(paths.includes("src/other.ts"));
  // 去重：src/app 既从 cwd 访问又被 root BFS 碰到 → 只出现一次
  assert.equal(paths.filter((p) => p === "src/app").length, 1);
});

test("loadWorkspaceCompletionIndex skips ignored dirs and applies .gitignore", async () => {
  const { source } = fakeSource(
    {
      "": [
        entry(".git", true),
        entry("node_modules", true),
        entry("src", true),
        entry("ignored.txt"),
      ],
      ".git": [entry(".git/HEAD")],
      node_modules: [entry("node_modules/pkg", true)],
      src: [entry("src/keep.ts"), entry("src/skip.log")],
    },
    { ".gitignore": "ignored.txt\n", "src/.gitignore": "*.log\n" },
  );
  const entries = await loadWorkspaceCompletionIndex({ cwd: "", source });
  const paths = entries.map((e) => e.path);
  assert.ok(paths.includes("src/keep.ts"));
  assert.ok(!paths.some((p) => p.startsWith(".git")));
  assert.ok(!paths.some((p) => p.startsWith("node_modules")));
  assert.ok(!paths.includes("ignored.txt"));
  assert.ok(!paths.includes("src/skip.log"));
});

test("loadWorkspaceCompletionIndex tolerates a failing directory listing", async () => {
  const source = {
    async listFiles(path: string): Promise<WorkspaceCompletionEntry[]> {
      if (path === "boom") throw new Error("io");
      if (path === "") return [entry("boom", true), entry("ok.txt")];
      return [];
    },
    async readTextFile(): Promise<string | null> {
      return null;
    },
  };
  const entries = await loadWorkspaceCompletionIndex({ cwd: "", source });
  // 坏目录自身作为条目仍在索引里（它只是列不出子项），ok.txt 正常收录
  assert.deepEqual(entries.map((e) => e.path), ["boom", "ok.txt"]);
});

test("loadWorkspaceCompletionIndex aborts promptly", async () => {
  const controller = new AbortController();
  const source = {
    async listFiles(path: string): Promise<WorkspaceCompletionEntry[]> {
      if (path === "") {
        controller.abort();
        return [entry("a", true)];
      }
      return [entry(`${path}/x`)];
    },
    async readTextFile(): Promise<string | null> {
      return null;
    },
  };
  await assert.rejects(
    loadWorkspaceCompletionIndex({ cwd: "", source, signal: controller.signal }),
    (error: unknown) => error instanceof Error && error.name === "AbortError",
  );
});

test("loadWorkspaceCompletionIndex stops at the directory bound", async () => {
  // 每个目录再生成一个子目录，BFS 应在 80 目录处停住而不是无限扫
  const source = {
    async listFiles(path: string): Promise<WorkspaceCompletionEntry[]> {
      const depth = path === "" ? 0 : path.split("/").length;
      return [entry(`${path === "" ? "d0" : `${path}/d${depth}`}`, true)];
    },
    async readTextFile(): Promise<string | null> {
      return null;
    },
  };
  const entries = await loadWorkspaceCompletionIndex({ cwd: "", source });
  assert.ok(entries.length <= 80, `expected bounded traversal, got ${entries.length}`);
});

// ---------------------------------------------------------------------------
// completeWorkspaceMention 端到端（纯）
// ---------------------------------------------------------------------------

test("completeWorkspaceMention returns replacement range and sorted items", () => {
  const result = completeWorkspaceMention(
    "see @ma for details",
    7,
    [entry("src/main.ts"), entry("docs/manual.md"), entry("src/util.ts")],
    "",
  );
  assert.ok(result);
  assert.equal(result.providerId, "workspace_files");
  assert.equal(result.start, 4);
  assert.equal(result.end, 7);
  // "ma" 对 main.ts(893) 高于 manual.md(891)：短词前缀分更高
  const labels = result.items.map((item) => item.label);
  assert.deepEqual(labels, ["/workspace/src/main.ts", "/workspace/docs/manual.md"]);
  assert.equal(result.items[0].insertText, "@/workspace/src/main.ts ");
  assert.equal(completeWorkspaceMention("email a@b.com", 13, [entry("b.txt")], ""), null);
});

// ---------------------------------------------------------------------------
// WorkspaceCompletionIndexCache：workspace + cwd 隔离
// ---------------------------------------------------------------------------

test("index cache is isolated per workspace and per cwd", () => {
  const cache = new WorkspaceCompletionIndexCache();
  cache.set("ws1", "/workspace/a", [entry("a.txt")]);
  assert.equal(cache.get("ws1", "/workspace/a")?.length, 1);
  assert.equal(cache.get("ws1", "/workspace/b"), null, "cwd 切换不应命中");
  assert.equal(cache.get("ws2", "/workspace/a"), null, "workspace 切换不应命中");
  cache.set("ws2", "/workspace/a", [entry("b.txt"), entry("c.txt")]);
  assert.equal(cache.get("ws2", "/workspace/a")?.length, 2);
  assert.equal(cache.get("ws1", "/workspace/a"), null, "单槽缓存被 ws2 覆盖");
  cache.invalidate();
  assert.equal(cache.get("ws2", "/workspace/a"), null);
});
