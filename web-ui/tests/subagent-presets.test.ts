import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import en from "../app/overlay/locales/en-US/workspace.json";
import zh from "../app/overlay/locales/zh-CN/workspace.json";
import {
  cloneSubagentPreset, isSubagentPresetConflict, newSubagentPresetDraft, subagentPresetCataloguePreview,
  subagentPresetDraft, subagentPresetDraftIsStale, subagentPresetModels, subagentPresetModelConfigConflict, subagentPresetOrderRequest,
  subagentPresetWorkerPreview, uniqueSubagentPresetKey, validateSubagentPresetDraft,
} from "../app/overlay/lib/subagent-presets";
import { workspaceQueryKeys, workspaceSubagentPresetsApi } from "../app/overlay/lib/workspace";
import api from "../app/services/api";
import { register } from "node:module";
register("./helpers/preset-overlay-loader.mjs", import.meta.url);
const { SubagentPresetEditor, WorkspaceSubagentPresets } = await import("../app/overlay/components/workspace/workspace-subagent-presets");
import type { WorkspaceSubagentPreset } from "../app/overlay/types/subagent-preset";
import type { ProviderProfile } from "../app/overlay/types/settings";

await i18next.use(initReactI18next).init({ lng: "en-US", resources: { "en-US": { workspace: en }, "zh-CN": { workspace: zh } }, interpolation: { escapeValue: false } });

const role: WorkspaceSubagentPreset = {
  ...newSubagentPresetDraft(), key: "reviewer", name: "Review role", description: "Review code",
  systemPrompt: "PRIVATE_ROLE_PROMPT: do not modify files", id: "role-1", version: 4, executionRevision: 2, sortOrder: 0,
};

test("editable projection/clone never includes server-owned fields", () => {
  const draft = subagentPresetDraft(role);
  for (const key of ["id", "version", "executionRevision", "sortOrder"]) assert.ok(!(key in draft));
  draft.generationConfig.temperature = 1;
  assert.equal(role.generationConfig.temperature, null, "deep enough copy to protect saved config");
  const clone = cloneSubagentPreset(role, [role]);
  assert.equal(clone.key, "reviewer_2");
  assert.equal(clone.name, role.name);
  assert.ok(!("id" in clone));
  assert.notEqual(uniqueSubagentPresetKey("default", []), "default");
  assert.ok(uniqueSubagentPresetKey("a".repeat(32), [{ ...role, key: "a".repeat(32) }]).length <= 32);
});

test("draft validation includes copy target key conflicts and typed numeric bounds", () => {
  assert.equal(validateSubagentPresetDraft(subagentPresetDraft(role), [role], role.id), null);
  assert.equal(validateSubagentPresetDraft(subagentPresetDraft(role), [role]), "key_duplicate");
  assert.equal(validateSubagentPresetDraft({ ...subagentPresetDraft(role), key: "default" }, []), "key_invalid");
  assert.equal(validateSubagentPresetDraft({ ...subagentPresetDraft(role), maxSteps: 1.5 }, []), "steps_invalid");
  for (const params of [{ temperature: Infinity }, { topP: 0 }, { maxTokens: 1.5 }, { maxTokens: 2147483648 }]) {
    assert.equal(validateSubagentPresetDraft({ ...subagentPresetDraft(role), generationConfig: { ...role.generationConfig, ...params } }, []), "params_invalid");
  }
});

test("order request has complete membership + CAS versions and no mutation", () => {
  const other = { ...role, id: "role-2", key: "developer", version: 7, sortOrder: 1 };
  assert.deepEqual(subagentPresetOrderRequest([role, other], role.id, 1), { presetIds: [other.id, role.id], expectedVersions: { [other.id]: 7, [role.id]: 4 } });
  assert.equal(role.sortOrder, 0);
});

test("model picker strips provider secrets, disables unsupported/disabled refs", () => {
  const provider: ProviderProfile = { id: "p", type: "openai", name: "Provider", enabled: true, apiKey: "TOP_SECRET", models: [
    { id: "m", modelId: "chat-tool", displayName: "Model", type: "CHAT", abilities: ["TOOL"] },
    { id: "bad", modelId: "chat", displayName: "Chat", type: "CHAT" },
  ] };
  assert.deepEqual(subagentPresetModels([provider]), [{ value: "m", label: "Provider / Model" }]);
  assert.deepEqual(subagentPresetModels([{ ...provider, enabled: false }]), []);
  assert.ok(!JSON.stringify(subagentPresetModels([provider])).includes("TOP_SECRET"));
});

test("explicit generation conflicts are visible and AUTO defers to model defaults", () => {
  const draft = { ...subagentPresetDraft(role), modelSelection: "fixed" as const, modelId: "m", generationConfig: { ...role.generationConfig, reasoningLevel: "low" as const } };
  const provider: ProviderProfile = { id: "p", type: "openai", name: "Provider", enabled: true, models: [
    { id: "m", modelId: "model", displayName: "Model", type: "CHAT", abilities: ["TOOL"], customBodies: [{ key: "reasoning_effort", value: "high" }] },
  ] };
  assert.equal(subagentPresetModelConfigConflict(draft, [provider]), true);
  assert.equal(subagentPresetModelConfigConflict({ ...draft, generationConfig: { ...draft.generationConfig, reasoningLevel: "auto" } }, [provider]), false);
  for (const customBody of [
    { key: "generationConfig", value: { thinkingConfig: { thinkingBudget: 8000 } } },
    { key: "output_config", value: { effort: "high" } },
    { key: "system", value: "replacement role" },
    { key: "stream", value: false },
  ]) {
    assert.equal(subagentPresetModelConfigConflict(draft, [{ ...provider, models: [{ ...provider.models[0], customBodies: [customBody] }] }]), true);
  }
  assert.equal(subagentPresetModelConfigConflict(draft, [{ ...provider, models: [{ ...provider.models[0], customBodies: [{ key: "output_config", value: { format: { type: "text" } } }] }] }]), false);
  assert.deepEqual(subagentPresetModels([{ ...provider, models: [{ ...provider.models[0], providerOverwrite: { enabled: false } }] }]), []);
  const html = renderToStaticMarkup(createElement(SubagentPresetEditor, {
    draft, immutableKey: true, models: [], onChange: () => {}, onSave: () => {}, onCancel: () => {},
    busy: false, invalidModel: false, modelConflict: true, workspaceAllowsContext: true,
  }));
  assert.ok(html.includes("custom request body overrides"));
});

test("previews separate parent catalogue from private worker role prompt", () => {
  const preview = subagentPresetCataloguePreview([role, { ...role, enabled: false, key: "disabled" }]);
  assert.ok(preview.includes("reviewer: Review role"));
  assert.ok(!preview.includes("PRIVATE_ROLE_PROMPT"));
  assert.ok(!preview.includes("disabled"));
  const worker = subagentPresetWorkerPreview(role);
  assert.ok(worker.startsWith("You are a workspace subagent"));
  assert.ok(worker.includes("You cannot dispatch further subagents."));
  assert.ok(worker.endsWith(role.systemPrompt));
});

test("workspace_update detail invalidation covers preset query; local draft retains original CAS", () => {
  assert.deepEqual(workspaceQueryKeys.presets("ws").slice(0, 2), workspaceQueryKeys.detail("ws"));
  const draft = subagentPresetDraft(role);
  draft.name = "Unsaved";
  assert.equal(subagentPresetDraftIsStale(role.version, { ...role, version: 5 }), true);
  assert.equal(subagentPresetDraftIsStale(role.version, undefined), true);
  assert.equal(subagentPresetDraftIsStale(undefined, undefined), false);
  assert.equal(draft.name, "Unsaved");
  assert.equal(isSubagentPresetConflict({ code: 409 }), true);
  assert.equal(isSubagentPresetConflict({ code: 400 }), false);
});

test("controlled role editor renders immutable key, model repair, explicit save and sandbox warning", () => {
  const html = renderToStaticMarkup(createElement(SubagentPresetEditor, {
    draft: { ...subagentPresetDraft(role), modelSelection: "fixed", modelId: "stale" },
    immutableKey: true, models: [], onChange: () => {}, onSave: () => {}, onCancel: () => {},
    busy: false, invalidModel: true, workspaceAllowsContext: false,
  }));
  const keyInput = html.match(/<input[^>]*value="reviewer"[^>]*>/)?.[0];
  assert.ok(keyInput?.includes('disabled=""'));
  assert.ok(html.includes("Save role"));
  assert.ok(html.includes("Unavailable model"));
  assert.ok(html.includes("not a hard sandbox"));
  assert.ok(html.includes("workspace currently blocks"));
  assert.ok(html.includes("PRIVATE_ROLE_PROMPT"), "prompt is visible only inside the deliberate editor/preview");
});

function renderManager(corrupt: boolean, presets: WorkspaceSubagentPreset[] | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(workspaceQueryKeys.presets("ws"), { presets, corrupt });
  client.setQueryData(["subagent-preset-templates"], []);
  client.setQueryData(workspaceQueryKeys.list, []);
  return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(WorkspaceSubagentPresets, { workspaceId: "ws", workspaceAllowsContext: true })));
}

test("manager empty catalogue has opt-in creation, corrupt collection requires explicit reset", () => {
  const empty = renderManager(false, []);
  assert.ok(empty.includes("No custom roles"));
  assert.ok(empty.includes("New role"));
  assert.ok(!empty.includes("Review role"));
  const corrupt = renderManager(true, null);
  assert.ok(corrupt.includes("Reset corrupt role configuration"));
  assert.ok(!corrupt.includes("New role"));
});

test("manager displays roles and actions but no full role prompt outside editor", () => {
  const html = renderManager(false, [role]);
  assert.ok(html.includes("Review role"));
  assert.ok(html.includes("Copy to another workspace"));
  assert.ok(html.includes("Clone"));
  assert.ok(!html.includes("PRIVATE_ROLE_PROMPT"));
});

test("API wrappers use target create, explicit CAS and dedicated collection endpoints (no live requests)", async () => {
  const original = { get: api.get, post: api.post, put: api.put, delete: api.delete };
  const calls: Array<{ method: string; url: string; body?: unknown }> = [];
  const stub = (method: string) => async <T>(url: string, body?: unknown): Promise<T> => {
    calls.push({ method, url, body }); return {} as T;
  };
  api.get = stub("GET"); api.post = stub("POST"); api.put = stub("PUT"); api.delete = stub("DELETE");
  try {
    await workspaceSubagentPresetsApi.list("source");
    await workspaceSubagentPresetsApi.templates();
    const draft = subagentPresetDraft(role);
    await workspaceSubagentPresetsApi.create("target", draft);
    await workspaceSubagentPresetsApi.update("source", role.id, draft, 4);
    await workspaceSubagentPresetsApi.delete("source", role.id, 4);
    await workspaceSubagentPresetsApi.reorder("source", subagentPresetOrderRequest([role], role.id, 1));
    await workspaceSubagentPresetsApi.reset("source");
    assert.equal(calls[1].url, "subagent-presets/templates");
    assert.equal(calls[2].url, "workspaces/target/subagent-presets");
    assert.ok(!("id" in (calls[2].body as object)));
    assert.equal((calls[3].body as { expectedVersion: number }).expectedVersion, 4);
    assert.equal(calls[4].url, "workspaces/source/subagent-presets/role-1?expectedVersion=4");
    assert.deepEqual(calls[6].body, { confirm: true });
    assert.ok(calls.every((call) => call.url.includes("subagent-presets")));
  } finally { Object.assign(api, original); }
});

test("English and Chinese preset locale key sets are identical", () => {
  const leaves = (value: unknown, prefix = ""): string[] => typeof value === "object" && value !== null
    ? Object.entries(value).flatMap(([key, entry]) => leaves(entry, `${prefix}.${key}`)).sort() : [prefix];
  assert.deepEqual(leaves(en.presets), leaves(zh.presets));
  assert.deepEqual(Object.keys(workspaceSubagentPresetsApi).sort(), ["create", "delete", "list", "reorder", "reset", "templates", "update"]);
});
