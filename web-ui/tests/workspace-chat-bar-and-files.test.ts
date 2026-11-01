/**
 * 聊天栏换绑工作区、扩展四页签、Web 装 Rootfs、首条消息列表层数、解压、复制路径、多选移动。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/workspace-chat-bar-and-files.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import api, { ApiError } from "../app/services/api";
import { useSettingsStore } from "../app/stores";
import {
  WORKSPACE_EXTRACT_ENCODINGS,
  isWorkspaceArchiveName,
  workspaceApi,
  workspaceDeviceGuestPath,
  workspaceExtractErrorKey,
  workspaceGuestPath,
  summarizeWorkspaceTransfer,
  workspaceQueryKeys,
  workspaceTransferAllowed,
  workspaceTransferConflictCount,
  workspaceTransferErrorKey,
} from "../app/overlay/lib/workspace";
import { WorkspaceSelectionBar } from "../app/overlay/components/workspace/workspace-file-selection";
import {
  WorkspaceRootfsInstall,
  rootfsProgressPercent,
  rootfsProgressText,
  rootfsQueryKey,
} from "../app/overlay/components/workspace/workspace-rootfs-install";
import { extensionTabFromSearch } from "../app/overlay/routes/settings.extensions";
import {
  EXTENSION_MANAGE_LINKS,
  EXTENSION_TABS,
  ExtensionPickerButton,
} from "../app/components/input/extension-picker";
import { WorkspacePickerButton, assistantWorkspaceBody } from "../app/components/input/workspace-picker";
import enWorkspace from "../app/overlay/locales/en-US/workspace.json";
import zhWorkspace from "../app/overlay/locales/zh-CN/workspace.json";
import enInput from "../app/locales/en-US/input.json";
import zhInput from "../app/locales/zh-CN/input.json";
import type { RootfsInstallState, WorkspaceFileEntry } from "../app/overlay/types/workspace";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { workspace: enWorkspace, input: enInput } },
  interpolation: { escapeValue: false },
});

const t = i18next.getFixedT("en-US", "workspace") as unknown as (key: string, options?: Record<string, unknown>) => string;

function entry(path: string, isDirectory = false): WorkspaceFileEntry {
  return { path, name: path.split("/").pop()!, isDirectory, sizeBytes: 1, updatedAt: 0 };
}

async function captureApi<T>(method: "get" | "post" | "put" | "delete", run: () => Promise<T>) {
  const calls: Array<{ url: string; body: unknown; options: unknown }> = [];
  const real = api[method];
  (api as unknown as Record<string, unknown>)[method] = async (url: string, body?: unknown, options?: unknown) => {
    calls.push({ url, body, options });
    return {};
  };
  try {
    await run();
  } finally {
    (api as unknown as Record<string, unknown>)[method] = real;
  }
  return calls;
}

// ---------------------------------------------------------------- copy path / archives / move

test("copy path gives what the model and terminal see", () => {
  assert.equal(workspaceGuestPath("FILES", ""), "/workspace");
  assert.equal(workspaceGuestPath("FILES", "src/a.kt"), "/workspace/src/a.kt");
  assert.equal(workspaceGuestPath("FILES", "/dir/"), "/workspace/dir");
  assert.equal(workspaceGuestPath("LINUX", "etc/os-release"), "/etc/os-release");
  assert.equal(workspaceDeviceGuestPath("photos", "2026/a.jpg"), "/device/photos/2026/a.jpg");
  assert.equal(workspaceDeviceGuestPath("photos", ""), "/device/photos");
});

test("extract is offered for the same names as the server hint", () => {
  for (const name of ["a.zip", "a.7z", "a.rar", "a.tar", "a.tar.gz", "a.TGZ", "a.tbz2", "a.tar.xz", "a.gz", "a.bz2", "a.xz"]) {
    assert.ok(isWorkspaceArchiveName(name), name);
  }
  for (const name of ["a.txt", ".zip", "zip", "a.zipx"]) {
    assert.ok(!isWorkspaceArchiveName(name), name);
  }
  assert.equal(WORKSPACE_EXTRACT_ENCODINGS[0], null);
  assert.ok(WORKSPACE_EXTRACT_ENCODINGS.includes("GB18030"));
});

test("extract errors map by code only", () => {
  const err = (code: string) => new ApiError("x", 422, code);
  assert.equal(workspaceExtractErrorKey(err("ARCHIVE_PASSWORD_REQUIRED")), "extract.password_required");
  assert.equal(workspaceExtractErrorKey(err("ARCHIVE_WRONG_PASSWORD")), "extract.wrong_password");
  assert.equal(workspaceExtractErrorKey(err("ARCHIVE_UNSUPPORTED")), "extract.unsupported");
  assert.equal(workspaceExtractErrorKey(err("ARCHIVE_CORRUPT")), "extract.corrupt");
  assert.equal(workspaceExtractErrorKey(new Error("ARCHIVE_WRONG_PASSWORD")), "extract.failed");
  for (const key of ["password_required", "wrong_password", "unsupported", "corrupt", "encoding_unsupported", "not_found", "failed"]) {
    assert.notEqual(t(`extract.${key}`), `extract.${key}`, key);
  }
});

test("extract request carries password and encoding only when given, without retry", async () => {
  const calls = await captureApi("post", async () => {
    await workspaceApi.extract("w1", "in/a.zip");
    await workspaceApi.extract("w1", "in/a.zip", "pa55", "GB18030");
  });
  assert.deepEqual(calls.map((c) => [c.url, c.body]), [
    ["workspaces/w1/files/extract", { path: "in/a.zip" }],
    ["workspaces/w1/files/extract", { path: "in/a.zip", password: "pa55", nameEncoding: "GB18030" }],
  ]);
  assert.deepEqual(calls[0].options, { retry: 0, timeout: false });
});

test("move/copy: where each entry may go, same as the App", () => {
  // 同一工作区：移动不能原地、文件夹不能进自己；复制可以落在原文件夹（出副本）
  assert.equal(workspaceTransferAllowed("a.txt", true, "dest", "move"), true);
  assert.equal(workspaceTransferAllowed("a.txt", true, "", "move"), false);
  assert.equal(workspaceTransferAllowed("docs/a.txt", true, "/docs/", "move"), false);
  assert.equal(workspaceTransferAllowed("docs/a.txt", true, "docs", "copy"), true);
  assert.equal(workspaceTransferAllowed("dir", true, "dir/sub", "move"), false);
  assert.equal(workspaceTransferAllowed("dir", true, "dir", "copy"), false);
  assert.equal(workspaceTransferAllowed("src", true, "src2", "move"), true);
  // 另一个工作区：任何文件夹都行
  assert.equal(workspaceTransferAllowed("dir", false, "dir/sub", "move"), true);
  assert.equal(workspaceTransferAllowed("a.txt", false, "", "copy"), true);
  assert.equal(workspaceTransferAllowed("", false, "x", "copy"), false);
});

test("move/copy: conflict preview and result summary", () => {
  assert.equal(workspaceTransferConflictCount(["a.md", "b.md", "dir"], new Set(["a.md", "dir", "z"])), 2);
  const summary = summarizeWorkspaceTransfer({
    targetWorkspaceId: "w2",
    targetDir: "x",
    items: [
      { source: "a.md", ok: true, target: "x/a.md" },
      { source: "b.md", ok: true, target: "x/b (1).md", renamed: true, skippedLinks: 1 },
      { source: "gone.md", ok: false, errorCode: "FILE_NOT_FOUND", error: "Does not exist" },
    ],
  });
  assert.equal(summary.succeeded, 2);
  assert.equal(summary.renamed, 1);
  assert.equal(summary.skippedLinks, 1);
  assert.deepEqual(summary.failures.map((item) => item.source), ["gone.md"]);
  assert.equal(workspaceTransferErrorKey("NOT_ENOUGH_SPACE"), "files.transfer_error.no_space");
  assert.equal(workspaceTransferErrorKey("SOMETHING_ELSE"), "files.transfer_error.failed");
  assert.equal(workspaceTransferErrorKey(null), "files.transfer_error.failed");
});

test("move/copy request: one call with every path, no retry, no timeout", async () => {
  const calls = await captureApi("post", async () => {
    await workspaceApi.transferFiles("w1", ["a.md", "dir"], "w2", "archive", "copy");
  });
  assert.deepEqual(calls.map((c) => [c.url, c.body, c.options]), [
    [
      "workspaces/w1/files/transfer",
      { paths: ["a.md", "dir"], targetWorkspaceId: "w2", targetDir: "archive", mode: "copy" },
      { retry: 0, timeout: false },
    ],
  ]);
});

test("selection bar shows Move and Copy only where they are possible", () => {
  const render = (onMove?: () => void, onCopy?: () => void) =>
    renderToStaticMarkup(
      createElement(WorkspaceSelectionBar, {
        entries: [entry("a"), entry("b")],
        selected: new Set(["a"]),
        busy: false,
        onToggleAll: () => {},
        onClear: () => {},
        onArchive: () => {},
        onDelete: () => {},
        onMove,
        onCopy,
      }),
    );
  assert.match(render(() => {}), /data-testid="selection-move"/);
  assert.match(render(() => {}), />Move</);
  assert.doesNotMatch(render(), /selection-move/);
  assert.match(render(() => {}, () => {}), /data-testid="selection-copy"/);
  assert.match(render(() => {}, () => {}), />Copy</);
  assert.doesNotMatch(render(() => {}), /selection-copy/);
});

// ---------------------------------------------------------------- rootfs

function installing(patch: Partial<RootfsInstallState> = {}): RootfsInstallState {
  return { workspaceId: "w1", status: "installing", url: "https://x/r.tar.gz", stage: "DOWNLOADING", bytesRead: 512 * 1024, totalBytes: 2 * 1024 * 1024, ...patch };
}

test("rootfs progress text and percent mirror the App", () => {
  assert.equal(rootfsProgressPercent(installing()), 25);
  assert.equal(rootfsProgressPercent(installing({ totalBytes: null })), null);
  assert.equal(rootfsProgressPercent(installing({ stage: "EXTRACTING" })), null);
  assert.match(rootfsProgressText(installing(), t), /^Downloading .+ \/ .+$/);
  assert.equal(rootfsProgressText(installing({ stage: "EXTRACTING", entriesExtracted: 42 }), t), "Extracting 42 items");
});

test("rootfs api: status, start with optional url, cancel", async () => {
  const gets = await captureApi("get", () => workspaceApi.rootfsStatus("w1"));
  const posts = await captureApi("post", async () => {
    await workspaceApi.installRootfs("w1");
    await workspaceApi.installRootfs("w1", "https://mirror/r.tar.gz");
  });
  const deletes = await captureApi("delete", () => workspaceApi.cancelRootfs("w1"));
  assert.deepEqual(gets.map((c) => c.url), ["workspaces/w1/rootfs"]);
  assert.deepEqual(posts.map((c) => c.body), [{}, { url: "https://mirror/r.tar.gz" }]);
  assert.deepEqual(deletes.map((c) => c.url), ["workspaces/w1/rootfs"]);
  assert.deepEqual(rootfsQueryKey("w1").slice(0, 2), workspaceQueryKeys.detail("w1"));
});

test("rootfs install control: install / reinstall button, progress with cancel, failure", () => {
  const render = (shellStatus: "DISABLED" | "READY" | "INSTALLING", state: RootfsInstallState | null) => {
    const client = new QueryClient();
    client.setQueryData(rootfsQueryKey("w1"), { state, defaultUrl: "https://x/r.tar.gz" });
    return renderToStaticMarkup(
      createElement(QueryClientProvider, { client }, createElement(WorkspaceRootfsInstall, { workspace: { id: "w1", name: "W", shellStatus } })),
    );
  };
  assert.match(render("DISABLED", null), />Install rootfs</);
  assert.match(render("READY", null), />Reinstall rootfs</);
  const running = render("INSTALLING", installing());
  assert.match(running, />Cancel install</);
  assert.match(running, /data-testid="rootfs-progress"/);
  assert.match(render("BROKEN" as "READY", { ...installing(), status: "failed", error: "HTTP 404" }), /Install failed: HTTP 404/);
});

// ---------------------------------------------------------------- chat bar

const assistantId = "11111111-1111-4111-8111-111111111111";

function setSettings(patch: Record<string, unknown> = {}) {
  useSettingsStore.setState({
    settings: {
      assistantId,
      assistants: [{ id: assistantId, name: "A", workspaceId: "w1", ...patch }],
      quickMessages: [],
      modeInjections: [],
      lorebooks: [],
    },
  } as never);
  // renderToStaticMarkup reads zustand's server snapshot (the initial-state object, returned by reference).
  Object.assign(useSettingsStore.getInitialState(), { settings: useSettingsStore.getState().settings });
}

test("extension picker is always there with the App's four tabs and manage links", () => {
  setSettings();
  const client = new QueryClient();
  client.setQueryData(["skills"], []);
  const html = renderToStaticMarkup(
    createElement(QueryClientProvider, { client }, createElement(MemoryRouter, {}, createElement(ExtensionPickerButton, {}))),
  );
  // Previously the button vanished when quick messages / injections / lorebooks / skills were all empty.
  assert.match(html, /aria-label="Extensions"/);
  assert.doesNotMatch(html, /disabled=""/);
  assert.deepEqual([...EXTENSION_TABS], ["quickmessages", "mode", "lorebook", "skills"]);
  assert.equal(EXTENSION_MANAGE_LINKS.quickmessages, "/settings/extensions?tab=quick");
  assert.equal(EXTENSION_MANAGE_LINKS.skills, "/settings/skills");
  assert.equal(extensionTabFromSearch("?tab=lorebook"), "lorebook");
  assert.equal(extensionTabFromSearch("?tab=mode"), "mode");
  assert.equal(extensionTabFromSearch("?tab=bogus"), "quick");
  assert.equal(extensionTabFromSearch(""), "quick");
  for (const tab of EXTENSION_TABS) assert.notEqual(i18next.t(`input:injection.tab_${tab}`), `injection.tab_${tab}`);
});

test("workspace picker shows the bound workspace and sends only workspaceId", () => {
  setSettings();
  const client = new QueryClient();
  client.setQueryData(workspaceQueryKeys.list, [
    { id: "w1", name: "Project", root: "r", shellStatus: "READY", createdAt: 0, updatedAt: 0 },
  ]);
  const html = renderToStaticMarkup(
    createElement(QueryClientProvider, { client }, createElement(MemoryRouter, {}, createElement(WorkspacePickerButton, {}))),
  );
  assert.match(html, /data-testid="workspace-picker"/);
  assert.match(html, />Project</);
  assert.match(html, /title="Workspace: Project"/);
  assert.deepEqual(assistantWorkspaceBody(null), { workspaceId: null });
  assert.deepEqual(assistantWorkspaceBody("w2"), { workspaceId: "w2" });
});

// ---------------------------------------------------------------- strings

test("new strings exist in both languages", () => {
  const keys = (value: unknown, prefix = ""): string[] =>
    value && typeof value === "object"
      ? Object.entries(value).flatMap(([key, child]) => keys(child, `${prefix}${key}.`))
      : [prefix];
  assert.deepEqual(keys(zhWorkspace.extract).sort(), keys(enWorkspace.extract).sort());
  assert.deepEqual(keys(zhWorkspace.rootfs).sort(), keys(enWorkspace.rootfs).sort());
  assert.deepEqual(keys(zhInput.workspace).sort(), keys(enInput.workspace).sort());
  for (const key of ["copy_path", "path_copied", "copy_failed", "more", "move_title_multi"]) {
    assert.ok(key in zhWorkspace.files && key in enWorkspace.files, key);
  }
  // 移动/复制到（可跨工作区）
  for (const key of [
    "copy_to", "copy_title", "copy_title_multi", "copy_here", "copying", "copy_failed_transfer",
    "transfer_workspace", "transfer_current", "transfer_conflicts", "transfer_moved", "transfer_copied",
    "transfer_moved_partial", "transfer_copied_partial", "transfer_renamed", "transfer_skipped_links",
  ]) {
    assert.ok(key in zhWorkspace.files && key in enWorkspace.files, key);
  }
  assert.deepEqual(keys(zhWorkspace.files.transfer_error).sort(), keys(enWorkspace.files.transfer_error).sort());
  assert.ok("copy" in zhWorkspace.selection && "copy" in enWorkspace.selection);
  for (const key of ["first_message_snapshot_depth", "first_message_snapshot_depth_desc", "first_message_snapshot_depth_value"]) {
    assert.ok(key in zhWorkspace.detail && key in enWorkspace.detail, key);
  }
  assert.equal(zhInput.injection.tab_lorebook, "世界书");
  assert.equal(zhInput.injection.tab_skills, "Skills");
  assert.ok("go_manage" in zhInput.injection && "go_manage" in enInput.injection);
});
