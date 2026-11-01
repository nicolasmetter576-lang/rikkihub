import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import { register } from "node:module";
import en from "../app/overlay/locales/en-US/workspace.json";
import zh from "../app/overlay/locales/zh-CN/workspace.json";
import settingsEn from "../app/overlay/locales/en-US/settings.json";
import settingsZh from "../app/overlay/locales/zh-CN/settings.json";
import api from "../app/services/api";
import { workspaceQueryKeys } from "../app/overlay/lib/workspace";
import { newSubagentPresetDraft, subagentPresetDraft, subagentPresetDraftIsStale, validateSubagentPresetDraft } from "../app/overlay/lib/subagent-presets";
import { invalidateSubagentPresetLibrary, isLibraryPresetAssigned, libraryPresetAssignmentBlock, subagentPresetLibraryApi, subagentPresetLibraryQueryKey } from "../app/overlay/lib/subagent-preset-library";
import type { LibrarySubagentPreset, WorkspaceSubagentPreset } from "../app/overlay/types/subagent-preset";
register("./helpers/preset-overlay-loader.mjs", import.meta.url);
const { WorkspaceSubagentLibrary } = await import("../app/overlay/components/workspace/workspace-subagent-library");
const { WorkspaceSubagentPresets, SubagentPresetEditor } = await import("../app/overlay/components/workspace/workspace-subagent-presets");
const { default: LibraryPage } = await import("../app/overlay/routes/settings.subagent-presets");
await i18next.use(initReactI18next).init({ lng: "en-US", resources: { "en-US": { workspace: en, settings: settingsEn }, "zh-CN": { workspace: zh, settings: settingsZh } }, interpolation: { escapeValue: false } });
const role: LibrarySubagentPreset = {
  ...newSubagentPresetDraft(), id: "global-role", key: "reviewer", name: "Review role", description: "Review code",
  systemPrompt: "PRIVATE_GLOBAL_PROMPT", version: 4, executionRevision: 2, sortOrder: 0, libraryPresetId: null, workspaceIds: ["ws"],
};
const linked = { ...role, id: "linked-copy", libraryPresetId: role.id, enabled: false };

function render(component: ReturnType<typeof createElement>, library: LibrarySubagentPreset[] = [role], locals: WorkspaceSubagentPreset[] = [linked], corrupt = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(subagentPresetLibraryQueryKey, { presets: corrupt ? null : library, corrupt });
  client.setQueryData(workspaceQueryKeys.presets("ws"), { presets: locals, corrupt: false });
  client.setQueryData(workspaceQueryKeys.list, [{ id: "ws", name: "Workspace One" }]);
  client.setQueryData(["subagent-preset-templates"], [role]);
  return renderToStaticMarkup(createElement(MemoryRouter, {}, createElement(QueryClientProvider, { client }, component)));
}

test("120 steps accepted; 121 rejected; editor explicitly selects target workspaces", () => {
  assert.equal(validateSubagentPresetDraft({ ...subagentPresetDraft(role), maxSteps: 120 }, []), null);
  assert.equal(validateSubagentPresetDraft({ ...subagentPresetDraft(role), maxSteps: 121 }, []), "steps_invalid");
  const html = renderToStaticMarkup(createElement(SubagentPresetEditor, {
    draft: { ...subagentPresetDraft(role), maxSteps: 120 }, immutableKey: true, models: [], busy: false, invalidModel: false,
    workspaceAllowsContext: true, onSave: () => {}, onCancel: () => {}, onChange: () => {}, workspaceIds: ["ws"],
    workspaceChoices: [{ id: "ws", name: "Workspace One" }, { id: "conflict", name: "Conflicting Workspace", disabledReason: en.library.blocks.key_conflict }], onWorkspaceIdsChange: () => {},
  }));
  assert.ok(html.includes('max="120"'));
  assert.ok(html.includes('checked=""'));
  assert.ok(html.includes("It will never be overwritten"));
  assert.ok(html.includes("Save role"));
});

test("saving locks all editable fields including workspace selection", () => {
  const html = renderToStaticMarkup(createElement(SubagentPresetEditor, {
    draft: subagentPresetDraft(role), immutableKey: true, models: [], busy: true, invalidModel: false,
    workspaceAllowsContext: true, onSave: () => {}, onCancel: () => {}, onChange: () => {}, workspaceIds: ["ws"],
    workspaceChoices: [{ id: "ws", name: "Workspace One" }], onWorkspaceIdsChange: () => {},
  }));
  assert.match(html, /<form[^>]*><fieldset[^>]*disabled=""/);
  assert.match(html, /<button[^>]*type="submit"[^>]*disabled=""/);
});

test("selectors retain disabled assignments, reject locals/corruption/capacity, allow unassignment", () => {
  const disabled = { ...role, enabled: false };
  assert.equal(isLibraryPresetAssigned(disabled, "ws", { presets: [linked], corrupt: false }), true);
  assert.equal(isLibraryPresetAssigned(role, "ws", { presets: [{ ...linked, libraryPresetId: null }], corrupt: false }), false);
  assert.equal(libraryPresetAssignmentBlock(role, { presets: [linked], corrupt: false }), undefined);
  assert.equal(libraryPresetAssignmentBlock(role, { presets: [{ ...linked, libraryPresetId: null }], corrupt: false }), "key_conflict");
  assert.equal(libraryPresetAssignmentBlock(role, { presets: null, corrupt: true }), "corrupt");
  assert.equal(libraryPresetAssignmentBlock(role), "loading");
  const full = Array.from({ length: 16 }, (_, index) => ({ ...linked, id: `local-${index}`, key: `local_${index}`, libraryPresetId: null }));
  assert.equal(libraryPresetAssignmentBlock(role, { presets: full, corrupt: false }), "full");
  assert.equal(libraryPresetAssignmentBlock(role, { presets: [...full.slice(1), linked], corrupt: false }), undefined);
});

test("workspace checkbox points to global editor and disabled definitions remain visibly assigned", () => {
  const html = render(createElement(WorkspaceSubagentLibrary, { workspaceId: "ws" }), [{ ...role, enabled: false }]);
  assert.ok(html.includes('checked=""'));
  assert.ok(html.includes("Globally disabled"));
  assert.ok(html.includes("Built-in: Reviewer"));
  assert.ok(html.includes("/settings/subagent-presets?edit=global-role"));
  assert.ok(!html.includes("PRIVATE_GLOBAL_PROMPT"));
  const conflict = render(createElement(WorkspaceSubagentLibrary, { workspaceId: "ws" }), [role], [{ ...linked, libraryPresetId: null }]);
  assert.ok(conflict.includes("It will never be overwritten"));
  assert.ok(conflict.match(/<input[^>]*disabled=""/));
  assert.ok(!conflict.includes('checked=""'));
});

test("legacy card excludes all shared snapshots rather than exposing old edit/delete actions", () => {
  const html = render(createElement(WorkspaceSubagentPresets, { workspaceId: "ws", workspaceAllowsContext: true }));
  assert.ok(html.includes("Independent legacy / local roles"));
  assert.ok(!html.includes("Review role"));
  assert.ok(!html.includes("PRIVATE_GLOBAL_PROMPT"));
  const local = { ...linked, libraryPresetId: null };
  assert.ok(render(createElement(WorkspaceSubagentPresets, { workspaceId: "ws", workspaceAllowsContext: true }), [role], [local]).includes("Review role"));
});

test("global library lists built-ins, targets, edit/delete/clone and corrupt-only reset", () => {
  const html = render(createElement(LibraryPage));
  assert.ok(html.includes("Global subagent presets"));
  assert.ok(html.includes("Built-in"));
  assert.ok(html.includes("Workspace One"));
  assert.ok(html.includes("Clone"));
  assert.ok(html.includes("Delete"));
  assert.ok(!html.includes("PRIVATE_GLOBAL_PROMPT"));
  const corrupt = render(createElement(LibraryPage), [], [], true);
  assert.ok(corrupt.includes("global preset library is corrupt"));
  assert.ok(!corrupt.includes("New role"));
});

test("global API targets have absent-preserve / empty-clear semantics, exact CAS, no projection metadata", async () => {
  const original = { get: api.get, post: api.post, put: api.put, delete: api.delete };
  const calls: Array<{ method: string; url: string; body?: unknown }> = [];
  const stub = (method: string) => async <T>(url: string, body?: unknown): Promise<T> => { calls.push({ method, url, body }); return {} as T; };
  api.get = stub("GET"); api.post = stub("POST"); api.put = stub("PUT"); api.delete = stub("DELETE");
  try {
    await subagentPresetLibraryApi.list();
    await subagentPresetLibraryApi.create(role, ["target"]);
    await subagentPresetLibraryApi.update(role.id, role, 4);
    await subagentPresetLibraryApi.update(role.id, role, 4, []);
    await subagentPresetLibraryApi.setWorkspaceEnabled("ws", role.id, false, 4);
    await subagentPresetLibraryApi.delete(role.id, 4);
    await subagentPresetLibraryApi.reset();
    assert.equal(calls[0].url, "subagent-presets");
    assert.deepEqual((calls[1].body as { workspaceIds: string[] }).workspaceIds, ["target"]);
    for (const key of ["id", "version", "executionRevision", "sortOrder", "libraryPresetId"]) assert.ok(!(key in (calls[1].body as object)));
    assert.ok(!("workspaceIds" in (calls[2].body as object)));
    assert.deepEqual((calls[3].body as { workspaceIds: string[] }).workspaceIds, []);
    assert.deepEqual(calls[4], { method: "POST", url: "workspaces/ws/subagent-presets/global-role/enabled", body: { enabled: false, expectedVersion: 4 } });
    assert.deepEqual(calls[5].body, { json: { expectedVersion: 4 } });
    assert.deepEqual(calls[6].body, { confirm: true });
    assert.ok(calls.every((call) => !call.url.includes("settings")));
  } finally { Object.assign(api, original); }
});

test("SSE invalidation refreshes both library and workspace projections without overwriting a draft", () => {
  const invalidated: unknown[] = [];
  const draft = subagentPresetDraft(role); draft.name = "UNSAVED";
  invalidateSubagentPresetLibrary({ invalidateQueries: ({ queryKey }) => invalidated.push(queryKey) });
  assert.deepEqual(invalidated, [["subagent-preset-library"], ["workspaces"]]);
  assert.equal(subagentPresetDraftIsStale(role.version, { ...role, version: 5 }), true);
  assert.equal(draft.name, "UNSAVED");
  assert.ok(!("libraryPresetId" in draft));
  assert.ok(!("workspaceIds" in draft));
});

test("English/Chinese global library keys and Settings navigation are synchronized", () => {
  const leaves = (value: unknown, prefix = ""): string[] => typeof value === "object" && value !== null
    ? Object.entries(value).flatMap(([key, child]) => leaves(child, `${prefix}.${key}`)).sort() : [prefix];
  assert.deepEqual(leaves(en.library), leaves(zh.library));
  assert.ok(settingsEn.nav.subagent_presets);
  assert.ok(settingsZh.nav.subagent_presets);
});
