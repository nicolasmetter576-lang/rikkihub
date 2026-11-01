import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import {
  archiveFilenameFromDisposition,
  buildArchiveRequest,
  buildBatchDeleteRequest,
  downloadWorkspaceArchive,
  inspectArchiveResponseHeaders,
  isSelectableWorkspaceEntry,
  isWorkspaceMarkdownFile,
  isWorkspaceSelectionAll,
  nextSelectionForScope,
  pruneWorkspaceSelection,
  resolveWorkspaceRelativePath,
  setWorkspaceSelectionAll,
  summarizeBatchDeleteResult,
  toggleWorkspaceSelection,
  validateWorkspaceBatchPaths,
  WORKSPACE_ARCHIVE_MAX_BYTES,
  WORKSPACE_BATCH_MAX_PATHS,
  WORKSPACE_MARKDOWN_SAFE_PROPS,
  WORKSPACE_MAX_PATH_CHARS,
  WorkspaceArchiveError,
  workspaceMarkdownUrlResolver,
  workspaceTransferAllowed,
} from "../app/overlay/lib/workspace";

const E = (name: string, path = name, isDirectory = false) => ({
  name,
  path,
  isDirectory,
  sizeBytes: 0,
  updatedAt: 0,
});

/* ---------- 多选纯函数 ---------- */

test("selection: toggle / select-all / .. 不可选", () => {
  const entries = [E("..", ".."), E("dir", "dir", true), E("a.txt"), E("b.txt")];
  let sel = toggleWorkspaceSelection(new Set(), "a.txt");
  assert.ok(sel.has("a.txt"));
  sel = toggleWorkspaceSelection(sel, "a.txt");
  assert.ok(!sel.has("a.txt"));

  // '..' 不进全选
  const all = setWorkspaceSelectionAll(entries, true);
  assert.deepEqual([...all].sort(), ["a.txt", "b.txt", "dir"]);
  assert.ok(isWorkspaceSelectionAll(all, entries));
  assert.ok(!isWorkspaceSelectionAll(new Set(["a.txt"]), entries));
  assert.ok(!isWorkspaceSelectionAll(new Set(), entries)); // 无可选 → false

  // 取消全选清空
  assert.equal(setWorkspaceSelectionAll(entries, false).size, 0);
  assert.ok(!isSelectableWorkspaceEntry(E("..")));
  assert.ok(isSelectableWorkspaceEntry(E("x")));
});

test("selection: 换目录/area（scopeKey 变化）清空，同 scope 保留", () => {
  const sel = new Set(["a.txt"]);
  const s1 = { scopeKey: "w:FILES:dir", selected: sel as ReadonlySet<string> };
  assert.equal(nextSelectionForScope(s1, "w:FILES:dir"), s1); // 同目录保留
  assert.equal(nextSelectionForScope(s1, "w:FILES:other").selected.size, 0); // 换目录
  assert.equal(nextSelectionForScope(s1, "w:LINUX:dir").selected.size, 0); // 换 area
  assert.equal(nextSelectionForScope(s1, "w2:FILES:dir").selected.size, 0); // 换 workspace
});

test("selection: 刷新后 prune 只留仍存在的路径", () => {
  const sel = new Set(["a.txt", "gone.txt", "dir"]);
  const next = pruneWorkspaceSelection(sel, [E("a.txt"), E("dir", "dir", true)]);
  assert.deepEqual([...next].sort(), ["a.txt", "dir"]);
});

test("validateWorkspaceBatchPaths: 200/1024 边界", () => {
  assert.equal(validateWorkspaceBatchPaths([]), "empty");
  assert.equal(validateWorkspaceBatchPaths(Array(WORKSPACE_BATCH_MAX_PATHS).fill("p")), null);
  assert.equal(
    validateWorkspaceBatchPaths(Array(WORKSPACE_BATCH_MAX_PATHS + 1).fill("p")),
    "too_many",
  );
  assert.equal(validateWorkspaceBatchPaths(["x".repeat(WORKSPACE_MAX_PATH_CHARS)]), null);
  assert.equal(
    validateWorkspaceBatchPaths(["x".repeat(WORKSPACE_MAX_PATH_CHARS + 1)]),
    "path_too_long",
  );
});

/* ---------- 请求契约（冻结，不改路径） ---------- */

test("batch-delete 契约: POST files/batch-delete?area= body {paths}", () => {
  const req = buildBatchDeleteRequest("W1", "LINUX", ["a.txt", "dir/b.txt"]);
  assert.equal(req.url, "workspaces/W1/files/batch-delete?area=LINUX");
  assert.deepEqual(req.body, { paths: ["a.txt", "dir/b.txt"] });
  assert.deepEqual(Object.keys(req.body), ["paths"]); // 严格 schema 只允许 paths
});

test("archive 契约: POST files/archive?area= body {paths}", () => {
  const req = buildArchiveRequest("W1", "FILES", ["docs"]);
  assert.equal(req.path, "/api/workspaces/W1/files/archive?area=FILES");
  assert.deepEqual(JSON.parse(req.body), { paths: ["docs"] });
});

/* ---------- 批删 partial 反馈（两面板共用 helper） ---------- */

test("summarizeBatchDeleteResult: all/partial/failed", () => {
  assert.deepEqual(
    summarizeBatchDeleteResult({ deletedPaths: ["a"], failures: [] }),
    { outcome: "all", deletedCount: 1, failedPaths: [] },
  );
  const partial = summarizeBatchDeleteResult({
    deletedPaths: ["a"],
    failures: [
      { path: "locked", error: "busy" },
      { path: "ro", error: "denied" },
    ],
  });
  assert.equal(partial.outcome, "partial");
  assert.deepEqual(partial.failedPaths, ["locked", "ro"]); // 失败项用于保留选择
  assert.equal(
    summarizeBatchDeleteResult({ deletedPaths: [], failures: [{ path: "x", error: "e" }] })
      .outcome,
    "failed",
  );
});

/* ---------- ZIP 响应校验（错误 JSON 绝不落盘） ---------- */

const headersOf = (h: Record<string, string>) => ({
  get: (name: string) => h[name.toLowerCase()] ?? null,
});

test("archive headers: 非 zip content-type 拒绝，含错误 JSON 的 200", () => {
  assert.throws(
    () => inspectArchiveResponseHeaders(headersOf({ "content-type": "application/json" })),
    (e: unknown) => e instanceof WorkspaceArchiveError && e.code === "content_type",
  );
  const ok = inspectArchiveResponseHeaders(
    headersOf({
      "content-type": "application/zip",
      "content-disposition": "attachment; filename*=UTF-8''my%20ws.zip",
    }),
  );
  assert.equal(ok.fileName, "my ws.zip");
  // content-length 超上限直接拒（不消费 body）
  assert.throws(
    () =>
      inspectArchiveResponseHeaders(
        headersOf({
          "content-type": "application/zip",
          "content-length": String(WORKSPACE_ARCHIVE_MAX_BYTES + 1),
        }),
      ),
    (e: unknown) => e instanceof WorkspaceArchiveError && e.code === "too_large",
  );
});

test("archiveFilenameFromDisposition: 安全文件名/路径注入过滤", () => {
  assert.equal(
    archiveFilenameFromDisposition("attachment; filename=\"ws.zip\""),
    "ws.zip",
  );
  assert.equal(
    archiveFilenameFromDisposition("attachment; filename*=UTF-8''..%2F..%2Fetc%2Fpasswd"),
    "passwd", // 剥目录 + 去非法字符
  );
  assert.equal(archiveFilenameFromDisposition("attachment"), undefined);
  assert.equal(archiveFilenameFromDisposition(null), undefined);
  // 引号内的 ; 不截断，非法字符剥除
  assert.equal(
    archiveFilenameFromDisposition("attachment; filename=\"evil;rm -rf.zip\""),
    "evilrm -rf.zip",
  );
});

test("downloadWorkspaceArchive: 404/JSON 错误体不落盘、fetch 契约正确", async () => {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async (url: unknown, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    return new Response(JSON.stringify({ error: "nope", code: 404 }), {
      status: 404,
      headers: { "content-type": "application/json" },
    });
  }) as typeof fetch;
  try {
    await assert.rejects(
      () => downloadWorkspaceArchive("W1", "FILES", ["a.txt"]),
      (e: unknown) => e instanceof WorkspaceArchiveError && e.code === "http" && e.status === 404,
    );
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/workspaces/W1/files/archive?area=FILES");
    assert.equal(calls[0].init.method, "POST");
    assert.equal(calls[0].init.body, JSON.stringify({ paths: ["a.txt"] }));
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("downloadWorkspaceArchive: 200 + JSON content-type 拒绝", async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async () =>
    new Response("{\"deletedPaths\":[]}", {
      status: 200,
      headers: { "content-type": "application/json" },
    })) as typeof fetch;
  try {
    await assert.rejects(
      () => downloadWorkspaceArchive("W1", "FILES", ["a"]),
      (e: unknown) => e instanceof WorkspaceArchiveError && e.code === "content_type",
    );
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("downloadWorkspaceArchive: 正常 zip 返回 blob+文件名", async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async () =>
    new Response(new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2, 3]), {
      status: 200,
      headers: {
        "content-type": "application/zip",
        "content-disposition": "attachment; filename*=UTF-8''demo.zip",
      },
    })) as typeof fetch;
  try {
    const { blob, fileName } = await downloadWorkspaceArchive("W1", "LINUX", ["etc/x"]);
    assert.equal(fileName, "demo.zip");
    assert.equal(blob.size, 7);
  } finally {
    globalThis.fetch = realFetch;
  }
});

/* ---------- Markdown 预览：URL resolver（渲染层，不改源码） ---------- */

test("isWorkspaceMarkdownFile 只认 .md/.markdown", () => {
  assert.ok(isWorkspaceMarkdownFile("a.md"));
  assert.ok(isWorkspaceMarkdownFile("a.MARKDOWN"));
  assert.ok(!isWorkspaceMarkdownFile("a.mdx"));
  assert.ok(!isWorkspaceMarkdownFile("a.txt"));
  assert.ok(!isWorkspaceMarkdownFile(undefined));
});

test("WORKSPACE_MARKDOWN_SAFE_PROPS 契约", () => {
  assert.deepEqual(WORKSPACE_MARKDOWN_SAFE_PROPS, {
    allowRawHtml: false,
    allowCodePreview: false,
    isAnimating: false,
  });
});

test("resolveWorkspaceRelativePath 归一化", () => {
  assert.equal(resolveWorkspaceRelativePath("docs", "a/b.png"), "docs/a/b.png");
  assert.equal(resolveWorkspaceRelativePath("docs", "../x.png"), "x.png");
  assert.equal(resolveWorkspaceRelativePath("", "../x.png"), null); // 越出根
  assert.equal(resolveWorkspaceRelativePath("docs", "/top.png"), "top.png");
  assert.equal(resolveWorkspaceRelativePath("docs", "https://a/x"), null);
  assert.equal(resolveWorkspaceRelativePath("docs", "a/./b.png"), "docs/a/b.png");
});

test("workspaceMarkdownUrlResolver: 相对 src → 鉴权下载 URL", () => {
  const ctx = { workspaceId: "W1", area: "FILES" as const, baseDir: "docs" };
  const resolve = workspaceMarkdownUrlResolver(ctx);
  const node = {} as never;
  // 相对图片 src 映射
  assert.equal(
    resolve("img.png", "src", node),
    "/api/workspaces/W1/files/download?area=FILES&path=docs%2Fimg.png",
  );
  // percent-encoded 先解码（<my img.png> → my%20img.png）
  assert.equal(
    resolve("my%20img.png", "src", node),
    "/api/workspaces/W1/files/download?area=FILES&path=docs%2Fmy+img.png",
  );
  // ../ 合法内路径
  assert.equal(
    resolve("../shared/x.png", "src", node),
    "/api/workspaces/W1/files/download?area=FILES&path=shared%2Fx.png",
  );
  // 越出根 → 空串（渲染为空 src）
  assert.equal(resolve("../../etc/passwd", "src", node), "");
  // 带 ?/# 后缀剥离
  assert.ok(resolve("a.png?v=1", "src", node)?.includes("path=docs%2Fa.png"));
  // Workspace preview blocks remote/absolute/anchor image sources (chat defaults unchanged).
  assert.equal(resolve("https://cdn/x.png", "src", node), "");
  assert.equal(resolve("/abs/x.png", "src", node), "");
  assert.equal(resolve("#frag", "src", node), "");
  assert.equal(resolve("javascript:x", "src", node), ""); // 有 scheme → safe transform 剥
  // 非 src/srcSet key 不干预（href 交给默认过滤）
  assert.equal(resolve("a.png", "href", node), undefined);
  // 无 ctx → ""
  assert.equal(workspaceMarkdownUrlResolver(null)("a.png", "src", node), "");
});

/* ---------- 静态核查：防抖保存未丢 / revoke / abort / lint 风险 ---------- */

const readSrc = (rel: string) =>
  readFileSync(fileURLToPath(new URL(`../../web-settings/web-ui/${rel}`, import.meta.url)), "utf8");

test("静态核查: 聊天面板每一栏仍走防抖/keepalive", () => {
  // 聊天面板的每一栏（双栏时两栏各一个）都由 workspace-file-pane 的 useWorkspaceFilePane 提供会话。
  const src = readSrc("components/workspace/workspace-file-pane.tsx");
  const hook = readSrc("components/workspace/use-workspace-editor-session.tsx");
  assert.match(hook, /setTimeout\(\(\) => void session\.save\(\), 800\)/, "800ms 防抖移入共用 CAS hook");
  assert.match(src, /session\.change\(value\)/);
  assert.match(src, /workspaceApi\.writeFile\(workspaceId, path, source, revision\)/);
  assert.match(src, /writeWorkspaceFileKeepalive/, "关闭 flush 仍在");
  assert.match(src, /<WorkspaceTextEditor[\s\S]*value=\{session\.source\}/, "编辑器绑定会话草稿");
  assert.match(src, /onChange=\{value => session\.change\(value\)\}/, "预览 tab 不改保存链路");
  const panel = readSrc("components/workspace/workspace-chat-panel.tsx");
  assert.match(panel, /useWorkspaceFilePane\(workspaceId\)[\s\S]*useWorkspaceFilePane\(workspaceId\)/, "两栏各一个会话");
});

test("静态核查: 预览传原文 value + 安全 props + resolveUrl", () => {
  const src = readSrc("components/workspace/workspace-text-editor.tsx");
  assert.match(src, /content=\{value\}/, "预览=当前草稿原文（不做源码改写）");
  assert.match(src, /value=\{value\}/, "编辑 tab 绑定同一 value");
  assert.match(src, /allowRawHtml=\{WORKSPACE_MARKDOWN_SAFE_PROPS\.allowRawHtml\}/);
  assert.match(src, /allowCodePreview=\{WORKSPACE_MARKDOWN_SAFE_PROPS\.allowCodePreview\}/);
  assert.match(src, /isAnimating=\{WORKSPACE_MARKDOWN_SAFE_PROPS\.isAnimating\}/);
  assert.match(src, /resolveUrl=\{resolvePreviewUrl\}/, "resolver 已接线");
  assert.match(src, /workspaceMarkdownUrlResolver\(previewContext\)/);
  // 代码层（不含注释）不得出现 iframe/srcdoc/dangerouslySetInnerHTML
  const code = src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, "");
  assert.ok(
    !/<iframe|srcdoc=|dangerouslySetInnerHTML/.test(code),
    "无 iframe/srcdoc/dangerous html",
  );
});

test("静态核查: ZIP 下载 revoke + abort signal + 不整包 PUT", () => {
  const src = readSrc("lib/workspace.ts");
  assert.match(src, /URL\.revokeObjectURL\(url\)/, "objectURL 有回收");
  assert.match(src, /signal: options\.signal/, "fetch 带 abort signal");
  assert.match(src, /setTimeout\(\(\) => URL\.revokeObjectURL/, "延迟 revoke");
  assert.ok(!/iframe|srcdoc/.test(src));
});

test("静态核查: markdown.tsx 默认模式不受 resolveUrl 影响", () => {
  const src = readFileSync(
    fileURLToPath(new URL("../../web-ui/app/components/markdown/markdown.tsx", import.meta.url)),
    "utf8",
  );
  // resolveUrl 仅在 allowRawHtml=false 分支生效；默认传 undefined
  assert.match(src, /resolveUrl && !allowRawHtml/, "resolveUrl 门控在安全模式");
  assert.match(src, /markdownUrlTransform\(allowRawHtml\)/, "默认链保持现状");
  assert.match(src, /safeMarkdownUrlTransform\(resolveUrl/, "resolver 产物再过安全过滤");
});

test("workspaceTransferAllowed refuses no-op moves and folders into themselves", () => {
  assert.equal(workspaceTransferAllowed("a.md", true, "docs", "move"), true);
  assert.equal(workspaceTransferAllowed("src/notes", true, "docs/sub", "move"), true);
  assert.equal(workspaceTransferAllowed("docs/a.md", true, "", "move"), true);
  assert.equal(workspaceTransferAllowed("src", true, "src2", "move"), true);
  // 同一父目录 = 没动
  assert.equal(workspaceTransferAllowed("a.md", true, "", "move"), false);
  assert.equal(workspaceTransferAllowed("docs/a.md", true, "/docs/", "move"), false);
  // 文件夹不能移进自身或子孙
  assert.equal(workspaceTransferAllowed("src", true, "src", "move"), false);
  assert.equal(workspaceTransferAllowed("src", true, "src/inner", "move"), false);
  assert.equal(workspaceTransferAllowed("", true, "docs", "move"), false);
});
