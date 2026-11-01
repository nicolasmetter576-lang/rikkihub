import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { WorkspaceEditorSession, validFileRevision } from "../app/overlay/lib/workspace-editor-session";
import { workspaceEditorErrorKey } from "../app/overlay/lib/workspace-editor-errors";
const base = "a".repeat(64), next = "b".repeat(64);
const read = { path: "test.md", content: "### Original\n", editable: true, revision: base };
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (error: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }

test("baseline from GET, no-op source identity does not write, dirty follows actual text", async () => {
  const calls: unknown[][] = [];
  const s = new WorkspaceEditorSession(async (...args) => { calls.push(args); return { revision: next }; });
  s.load(read); assert.equal(s.dirty, false);
  assert.equal(await s.save(), true); assert.equal(calls.length, 0);
  s.change(read.content); assert.equal(s.dirty, false);
  s.change("edited"); assert.equal(s.dirty, true);
  s.change(read.content); assert.equal(s.dirty, false);
  s.change("edited"); assert.equal(await s.save(), true);
  assert.deepEqual(calls, [[read.path, "edited", base, false]]);
  assert.equal(s.original, "edited"); assert.equal(s.revision, next); assert.equal(s.dirty, false);
});

test("missing/invalid/uppercase revision disables legacy unsafe saves", async () => {
  for (const revision of [undefined, "", "revision", "A".repeat(64)]) {
    const s = new WorkspaceEditorSession(async () => { throw new Error("must not write"); });
    s.load({ ...read, revision }); assert.equal(s.status, "missingRevision");
    s.change("edited"); assert.equal(s.source, read.content); assert.equal(await s.save(), false);
  }
  assert.equal(validFileRevision(base), true);
});

test("in-flight edits keep dirty; only confirmed response updates revision; follow-up uses new baseline", async () => {
  const request = deferred<{ revision: string }>(); const calls: unknown[][] = [];
  const s = new WorkspaceEditorSession(async (...args) => { calls.push(args); return calls.length === 1 ? request.promise : { revision: "c".repeat(64) }; });
  s.load(read); s.change("one"); const save = s.save(); await Promise.resolve();
  s.change("two"); assert.equal(s.status, "saving");
  assert.equal(s.load({ ...read, revision: next, content: "external" }), false);
  request.resolve({ revision: next }); assert.equal(await save, false);
  assert.equal(s.original, "one"); assert.equal(s.source, "two"); assert.equal(s.dirty, true);
  assert.equal(await s.save(), true); assert.equal(calls[1][2], next);
});

test("conflicts preserve draft and baseline, no manual/pagehide/auto overwrite retries", async () => {
  let calls = 0;
  const s = new WorkspaceEditorSession(async () => { calls++; throw { code: 409, errorCode: "FILE_REVISION_CONFLICT" }; });
  s.load(read); s.change("draft"); assert.equal(await s.save(), false);
  assert.equal(s.status, "conflict"); assert.equal(s.source, "draft"); assert.equal(s.revision, base);
  assert.equal(s.canAutoSave, false); assert.equal(await s.save(true), false); assert.equal(await s.save(), false);
  assert.equal(calls, 1); assert.equal(s.load({ ...read, content: "external" }), false);
});

test("timeout, disconnected response, missing write revision stay unknown/dirty and never retry", async () => {
  for (const writer of [async () => { throw new Error("network"); }, async () => ({}), async () => ({ revision: "bad" })]) {
    const s = new WorkspaceEditorSession(writer); s.load(read); s.change("draft");
    await s.save(true); assert.equal(s.status, "unknown"); assert.equal(s.dirty, true); assert.equal(s.revision, base);
    s.change(read.content); assert.equal(s.dirty, true, "uncertain result stays dirty even after source revert");
    assert.equal(s.canAutoSave, false); assert.equal(await s.save(), false);
  }
});

test("known rejected background save stops auto saves; code-only atomic-save message", async () => {
  const s = new WorkspaceEditorSession(async () => { throw { code: 400, errorCode: "FILE_ATOMIC_SAVE_UNSUPPORTED", message: "/private/host/path" }; });
  s.load(read); s.change("draft"); await s.save(true);
  assert.equal(s.status, "error"); assert.equal(s.dirty, true); assert.equal(s.canAutoSave, false);
  assert.equal(s.errorKey, "editor_state.atomic_unsupported");
  assert.equal(workspaceEditorErrorKey(new Error("FILE_NOT_UTF8 /private"), "fallback"), "fallback");
  assert.equal(workspaceEditorErrorKey({ errorCode: "FILE_NOT_TEXT" }, "fallback"), "editor_state.not_text");
});

test("pagehide/in-flight save serialized, late result cannot rebase another file", async () => {
  const request = deferred<{ revision: string }>(); let calls = 0;
  const s = new WorkspaceEditorSession(async () => { calls++; return request.promise; });
  s.load(read); s.change("one"); const save = s.save(); const background = s.save(true); await Promise.resolve();
  assert.equal(calls, 1); s.discard(); s.load({ ...read, path: "other.md", content: "other" });
  request.resolve({ revision: next }); await save; await background;
  assert.equal(s.path, "other.md"); assert.equal(s.source, "other"); assert.equal(s.revision, base);
});

test("parent close/tab/router guards use save-discard-cancel; style/tab changes have no autosave path", () => {
  const text = readFileSync(new URL("../../web-settings/web-ui/components/workspace/workspace-text-editor.tsx", import.meta.url), "utf8");
  const hook = readFileSync(new URL("../../web-settings/web-ui/components/workspace/use-workspace-editor-session.tsx", import.meta.url), "utf8");
  assert.match(text, /onClick=\{\(\) => setTab\(value_\)\}/);
  assert.match(text, /tab === "preview" && "hidden"/, "CM kept mounted during preview");
  assert.ok(!text.includes('tab === "edit" ?'));
  assert.match(hook, /800/); assert.match(hook, /session\.canAutoSave/); assert.match(hook, /session\.save\(true\)/);
  assert.match(hook, /useBlocker/); assert.match(hook, /beforeunload/); assert.match(hook, /session\.discard/);
});
