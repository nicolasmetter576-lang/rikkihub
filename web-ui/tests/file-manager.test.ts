/**
 * 文件管理器的纯函数：排序与隐藏文件、路径面包屑、Android/data 限制提示、按错误码本地化。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs --test tests/file-manager.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  downloadFileManagerZip,
  fileManagerErrorKey,
  isRestrictedDevicePath,
  pathCrumbs,
  visibleEntries,
} from "../app/overlay/lib/file-manager";

const entry = (name: string, isDirectory: boolean, sizeBytes: number, updatedAt: number) => ({
  path: name, name, isDirectory, sizeBytes, updatedAt,
});

const entries = [
  entry("b.txt", false, 10, 3),
  entry(".hidden", false, 1, 9),
  entry("zeta", true, 0, 1),
  entry("a.png", false, 30, 2),
  entry("alpha", true, 0, 5),
];

test("folders stay first in every sort order; hidden files only on request", () => {
  assert.deepEqual(visibleEntries(entries, "name", false).map((e) => e.name), ["alpha", "zeta", "a.png", "b.txt"]);
  assert.deepEqual(visibleEntries(entries, "time", false).map((e) => e.name), ["alpha", "zeta", "b.txt", "a.png"]);
  assert.deepEqual(visibleEntries(entries, "size", false).map((e) => e.name), ["alpha", "zeta", "a.png", "b.txt"]);
  assert.ok(visibleEntries(entries, "name", true).some((e) => e.name === ".hidden"));
});

test("breadcrumbs lead to every ancestor", () => {
  assert.deepEqual(pathCrumbs(""), []);
  assert.deepEqual(pathCrumbs("Download/a/b"), [
    { label: "Download", path: "Download" },
    { label: "a", path: "Download/a" },
    { label: "b", path: "Download/a/b" },
  ]);
});

test("Android/data and Android/obb are flagged only on phone roots", () => {
  assert.equal(isRestrictedDevicePath("device:primary", "Android/data"), true);
  assert.equal(isRestrictedDevicePath("device:primary", "android/OBB/com.x"), true);
  assert.equal(isRestrictedDevicePath("device:primary", "Android/media"), false);
  assert.equal(isRestrictedDevicePath("device:primary", "Android/database"), false);
  assert.equal(isRestrictedDevicePath("library", "Android/data"), false);
});

test("refused operations are localized by error code only", () => {
  assert.equal(fileManagerErrorKey({ errorCode: "FILE_MANAGER_READ_ONLY_FOLDER" }), "workspaces_read_only");
  assert.equal(fileManagerErrorKey({ errorCode: "FILE_PERMISSION_DENIED" }), "device_permission_required");
  assert.equal(fileManagerErrorKey({ errorCode: "SOMETHING_ELSE" }), undefined);
  assert.equal(fileManagerErrorKey({ errorCode: "toString" }), undefined);
  assert.equal(fileManagerErrorKey(new Error("FILE_MANAGER_READ_ONLY_FOLDER")), undefined);
});

test("a refused zip download carries the error code, not the raw response body", async () => {
  const realFetch = globalThis.fetch;
  let accept = "";
  globalThis.fetch = async (_input: RequestInfo | URL, init?: RequestInit) => {
    accept = new Headers(init?.headers).get("Accept") ?? "";
    return new Response(
      JSON.stringify({ error: "The workspaces folder and the workspace folders in it cannot be changed here", code: 403, errorCode: "FILE_MANAGER_READ_ONLY_FOLDER" }),
      { status: 403, headers: { "Content-Type": "application/json" } },
    );
  };
  try {
    await assert.rejects(downloadFileManagerZip("library", ["Workspaces"], "all.zip"), (error: unknown) => {
      assert.equal((error as { code?: number }).code, 403);
      assert.equal(fileManagerErrorKey(error), "workspaces_read_only");
      assert.ok(!(error as Error).message.includes("{"));
      return true;
    });
    // The server answers an Accept of zip only with 406 instead of the JSON error
    assert.match(accept, /application\/json/);
    globalThis.fetch = async () => new Response("<html>gateway</html>", { status: 502 });
    await assert.rejects(downloadFileManagerZip("library", ["a"], "a.zip"), (error: unknown) => {
      assert.equal((error as Error).message, "HTTP 502");
      return true;
    });
  } finally {
    globalThis.fetch = realFetch;
  }
});
