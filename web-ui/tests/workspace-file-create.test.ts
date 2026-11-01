import test from "node:test";
import assert from "node:assert/strict";
import api, { ApiError } from "../app/services/api";
import { detectWorkspaceFileKind, workspaceApi, workspaceDeviceApi } from "../app/overlay/lib/workspace";
import {
  createFileAndOpen,
  createFileSubmissionGate,
  fileCreationErrorKey,
  isUncertainFileCreationError,
  snapshotFileCreation,
  validateWorkspaceCreationName,
  validateWorkspaceFileName,
} from "../app/overlay/lib/workspace-file-create";
import en from "../app/overlay/locales/en-US/workspace.json";
import zh from "../app/overlay/locales/zh-CN/workspace.json";

const invalidNames = [
  ["", "filename_empty"], [".", "filename_dot"], ["..", "filename_dot"],
  [" name", "filename_whitespace"], ["name\u00a0", "filename_whitespace"],
  ["\ufeffname", "filename_whitespace"], ["name\u3000", "filename_whitespace"],
  ["a/b", "filename_separator"], ["a\\b", "filename_separator"],
  ["a\u0000b", "filename_control"], ["a\u001fb", "filename_control"],
  ["a\u007fb", "filename_control"], ["a\u0085b", "filename_control"],
  [".l2s.internal", "filename_reserved"], ["a".repeat(256), "filename_too_long"],
  ["a\ud800", "filename_unicode"], ["\udfffname", "filename_unicode"],
] as const;
for (const [name, reason] of invalidNames) {
  test(`filename rejects ${JSON.stringify(name)} without rewriting it`, () => {
    assert.equal(validateWorkspaceFileName(name), reason);
    assert.equal(validateWorkspaceCreationName(name, "md"), reason);
    assert.ok(en.files[reason]);
    assert.ok(zh.files[reason]);
  });
}

test("valid names retain spaces inside, Unicode spelling and case without normalization", () => {
  for (const name of ["README", "my notes", "笔记", "😀", "e\u0301", "é", ".hidden", ".L2S.note"]) {
    assert.equal(validateWorkspaceFileName(name), null);
  }
  const decomposed = snapshotFileCreation("ws", "FILES", "notes", "e\u0301", "md");
  assert.equal(decomposed.name, "e\u0301.md");
  assert.notEqual(decomposed.name, "é.md");
  // Creation may force a text editor without broadening ordinary file detection.
  assert.equal(detectWorkspaceFileKind("README"), "other");
});

const invalidExtensions = [
  [" md", "extension_whitespace"], ["md ", "extension_whitespace"],
  [" ", "extension_whitespace"], ["\ufeffmd", "extension_whitespace"],
  ["a/b", "extension_separator"], ["a\\b", "extension_separator"],
  ["a\u0000b", "extension_control"], ["a\u007fb", "extension_control"],
  ["\ud800", "extension_unicode"], ["md\udfff", "extension_unicode"],
  [".md", "extension_dot"], ["md.", "extension_dot"], [".", "extension_dot"],
] as const;
for (const [extension, reason] of invalidExtensions) {
  test(`extension rejects ${JSON.stringify(extension)} explicitly`, () => {
    assert.equal(validateWorkspaceCreationName("untitled", extension), reason);
    assert.ok(en.files[reason]);
    assert.ok(zh.files[reason]);
  });
}

test("empty suffix is a directory; nonempty suffix is combined exactly once; multipart allowed", () => {
  const file = snapshotFileCreation("ws", "FILES", "", "untitled", "md");
  assert.equal(file.path, "untitled.md");
  assert.equal(file.isDirectory, false);
  const folder = snapshotFileCreation("ws", "FILES", "notes", "folder", "");
  assert.equal(folder.path, "notes/folder");
  assert.equal(folder.isDirectory, true);
  assert.equal(validateWorkspaceCreationName("archive", "tar.gz"), null);
  assert.equal(snapshotFileCreation("ws", "FILES", "", "archive", "tar.gz").path, "archive.tar.gz");
  assert.equal(validateWorkspaceCreationName(".l2s", "md"), "filename_reserved");
});

test("UTF-8 limit counts the combined dot and extension, not JS string length", () => {
  assert.equal(validateWorkspaceFileName("界".repeat(85)), null);
  assert.equal(validateWorkspaceFileName("界".repeat(86)), "filename_too_long");
  assert.equal(validateWorkspaceCreationName("a".repeat(252), "md"), null);
  assert.equal(validateWorkspaceCreationName("a".repeat(253), "md"), "filename_too_long");
  assert.equal(validateWorkspaceCreationName("界".repeat(84), "md"), null);
  assert.equal(validateWorkspaceCreationName("界".repeat(85), "md"), "filename_too_long");
  assert.equal(validateWorkspaceCreationName("😀".repeat(63), "md"), null);
  assert.equal(validateWorkspaceCreationName("😀".repeat(64), ""), "filename_too_long");
});

test("snapshot freezes workspace, area, parent, base name, suffix and device view token", () => {
  const mount = { id: "mount-a", viewToken: "old-token" };
  const snapshot = snapshotFileCreation("ws-a", "DEVICE", "parent-a", "name", "md", mount);
  mount.id = "mount-b";
  mount.viewToken = "new-token";
  assert.deepEqual(snapshot.mount, { id: "mount-a", viewToken: "old-token" });
  assert.equal(snapshot.path, "parent-a/name.md");
  assert.equal(snapshot.workspaceId, "ws-a");
  assert.equal(snapshot.area, "DEVICE");
  assert.equal(snapshot.baseName, "name");
  assert.equal(snapshot.extension, "md");
  assert.ok(Object.isFrozen(snapshot));
  assert.ok(Object.isFrozen(snapshot.mount));
  assert.throws(() => snapshotFileCreation("ws", "FILES", "", "bad ", "md"), /filename_whitespace/);
});

test("localization is selected only by stable errorCode, never English or host paths", () => {
  const errors = {
    FILE_ALREADY_EXISTS: "file_already_exists", FILE_INVALID_PATH: "file_invalid_path",
    FILE_INVALID_NAME: "file_invalid_name", FILE_PARENT_MISSING: "file_parent_missing",
    FILE_PERMISSION_DENIED: "file_permission_denied", FILE_TOO_LARGE: "file_too_large",
    FILE_IO_ERROR: "file_io_error", FILE_SYMLINK_NOT_ALLOWED: "file_symlink_not_allowed",
  } as const;
  for (const [code, key] of Object.entries(errors)) {
    const error = new ApiError("PRIVATE_HOST_PATH should not appear", 409, code);
    assert.equal(error.errorCode, code);
    assert.equal(fileCreationErrorKey(error), key);
    assert.ok(en.files[key as keyof typeof en.files]);
    assert.ok(zh.files[key as keyof typeof zh.files]);
  }
  assert.equal(fileCreationErrorKey(new Error("File already exists")), "create_file_failed");
  assert.equal(fileCreationErrorKey(new ApiError("/private/host/path", 409)), "create_file_failed");
  assert.equal(fileCreationErrorKey({ errorCode: "toString" }), "create_file_failed");
  assert.equal(fileCreationErrorKey({ errorCode: "UNKNOWN" }), "create_file_failed");
});

test("success creates once, refreshes and opens in that order", async () => {
  const calls: string[] = [];
  const result = await createFileAndOpen({
    create: async () => { calls.push("create"); return "entry"; },
    refresh: async () => { calls.push("refresh"); return true; },
    open: async (entry) => { calls.push(`open:${entry}`); },
  });
  assert.deepEqual(calls, ["create", "refresh", "open:entry"]);
  assert.deepEqual(result, { status: "created", refreshFailed: false });
});

test("directory creation refreshes without any editor/read/open phase", async () => {
  const calls: string[] = [];
  const result = await createFileAndOpen({
    create: async () => { calls.push("mkdir-exclusive"); },
    refresh: async () => { calls.push("refresh"); return true; },
  });
  assert.deepEqual(calls, ["mkdir-exclusive", "refresh"]);
  assert.deepEqual(result, { status: "created", refreshFailed: false });
});

test("created file with failed opening is preserved; never attempt creation again", async () => {
  let creates = 0;
  const result = await createFileAndOpen({
    create: async () => { creates++; return {}; },
    refresh: async () => true,
    open: async () => { throw new Error("permission revoked"); },
  });
  assert.equal(creates, 1);
  assert.deepEqual(result, { status: "created_open_failed", refreshFailed: false });
});

test("failed refresh after known creation still attempts editor open; no retry/rollback", async () => {
  let creates = 0;
  let opens = 0;
  const result = await createFileAndOpen({
    create: async () => { creates++; },
    refresh: async () => { throw new Error("offline"); },
    open: async () => { opens++; },
  });
  assert.equal(creates, 1);
  assert.equal(opens, 1);
  assert.deepEqual(result, { status: "created", refreshFailed: true });
});

test("view changes prevent opening in a different workspace or folder", async () => {
  const result = await createFileAndOpen({
    create: async () => ({}), refresh: async () => true, open: async () => false,
  });
  assert.deepEqual(result, { status: "created_not_opened", refreshFailed: false });
});

test("conflict is a localized failed outcome, not an editor or automatic retry", async () => {
  const result = await createFileAndOpen({
    create: async () => { throw new ApiError("arbitrary language", 409, "FILE_ALREADY_EXISTS"); },
    refresh: async () => { assert.fail("not a transport failure"); },
    open: async () => { assert.fail("existing file must not be opened as newly created"); },
  });
  assert.deepEqual(result, { status: "failed", errorKey: "file_already_exists" });
});

for (const present of [true, false, null]) {
  test(`transport error refreshes to ascertain result (${present}) without retry or overwrite`, async () => {
    let creates = 0;
    let refreshes = 0;
    const result = await createFileAndOpen({
      create: async () => { creates++; throw new TypeError("Failed to fetch"); },
      refresh: async () => { refreshes++; if (present === null) throw new Error("offline"); return present; },
      open: async () => { assert.fail("presence is not proof of ownership"); },
    });
    assert.equal(creates, 1);
    assert.equal(refreshes, 1);
    assert.deepEqual(result, { status: "uncertain", present });
  });
}

test("timeouts and gateway failures can have an unknown result", () => {
  assert.ok(isUncertainFileCreationError(Object.assign(new Error(), { name: "TimeoutError" })));
  assert.ok(isUncertainFileCreationError(new ApiError("gateway", 504)));
  assert.equal(isUncertainFileCreationError(new ApiError("denied", 403)), false);
});

test("synchronous gate disables duplicate submits before a rerender; unlocks after completion", async () => {
  const gate = createFileSubmissionGate();
  let resolve!: () => void;
  let submits = 0;
  const first = gate(async () => { submits++; await new Promise<void>((done) => { resolve = done; }); });
  await gate(async () => { submits++; });
  assert.equal(submits, 1);
  resolve();
  await first;
  await gate(async () => { submits++; });
  assert.equal(submits, 2);
  await assert.rejects(gate(async () => { throw new Error("failure"); }));
  await gate(async () => { submits++; });
  assert.equal(submits, 3);
});

test("FILES/device API sends exclusive folder creation and non-overwriting file creation, with retries off", async () => {
  const originalPost = api.post;
  const originalPut = api.put;
  const calls: Array<{ method: string; url: string; body: unknown; options: unknown }> = [];
  const stub = (method: string) => async <T>(url: string, body?: unknown, options?: unknown): Promise<T> => {
    calls.push({ method, url, body, options }); return {} as T;
  };
  api.post = stub("POST");
  api.put = stub("PUT");
  try {
    await workspaceApi.createFile("ws", "notes/name.md");
    await workspaceApi.createDirectory("ws", "notes/folder");
    const mount = { id: "mount", viewToken: "current-view" };
    await workspaceDeviceApi.createFile("ws", mount, "notes/name.md");
    await workspaceDeviceApi.createDirectory("ws", mount, "notes/folder");
    for (const call of calls) {
      const parsed = new URL(call.url, "https://example.invalid/");
      assert.deepEqual(call.options, { retry: 0 });
      if (call.method === "PUT") assert.deepEqual(call.body, { content: "", overwrite: false });
      else assert.deepEqual(call.body, { path: "notes/folder", exclusive: true });
      if (call.url.includes("/device/")) {
        assert.equal(parsed.searchParams.get("mount"), "mount");
        assert.equal(parsed.searchParams.get("view"), "current-view");
      } else assert.equal(parsed.searchParams.get("area"), "FILES");
    }
    assert.equal(calls.length, 4);
    calls.length = 0;
    await workspaceApi.mkdir("ws", "FILES", "legacy/import/chain");
    await workspaceDeviceApi.mkdir("ws", mount, "legacy/import/chain");
    for (const call of calls) {
      assert.deepEqual(call.body, { path: "legacy/import/chain" });
    }
  } finally {
    api.post = originalPost;
    api.put = originalPut;
  }
});

test("HTTP 409 JSON errorCode survives the real API error handler", async () => {
  const NativeRequest = globalThis.Request;
  // Browser relative Request URL semantics, without any network or real server.
  globalThis.Request = class extends NativeRequest {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === "string" ? new URL(input, "https://example.invalid/") : input, init);
    }
  };
  try {
    await assert.rejects(api.put("workspaces/ws/files/content", { content: "", overwrite: false }, {
      retry: 0,
      fetch: async () => new Response(JSON.stringify({ error: "private message", code: 409, errorCode: "FILE_ALREADY_EXISTS" }), {
        status: 409, headers: { "content-type": "application/json" },
      }),
    }), (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.code, 409);
      assert.equal(error.errorCode, "FILE_ALREADY_EXISTS");
      assert.equal(fileCreationErrorKey(error), "file_already_exists");
      return true;
    });
  } finally { globalThis.Request = NativeRequest; }
});
