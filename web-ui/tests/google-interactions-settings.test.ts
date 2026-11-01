import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { register } from "node:module";
import en from "../app/overlay/locales/en-US/settings.json";
import zh from "../app/overlay/locales/zh-CN/settings.json";
import type { GoogleProviderProfile } from "../app/overlay/types/settings";

register("./helpers/preset-overlay-loader.mjs", import.meta.url);
const { connectionDraft } = await import("../app/overlay/routes/settings.providers.$id");
const source = readFileSync(new URL("../app/overlay/routes/settings.providers.$id.tsx", import.meta.url), "utf8");
const provider: GoogleProviderProfile = { type: "google", id: "google", name: "Google", enabled: true, models: [] };

test("old Google config drafts off; flag and old fields survive draft with Vertex", () => {
  assert.equal(connectionDraft(provider).useInteractionsApi, false);
  const saved = { ...provider, useInteractionsApi: true, vertexAI: true,
    apiKey: "key", baseUrl: "https://saved.invalid/v1beta", useServiceAccount: true,
    privateKey: "private", serviceAccountEmail: "email", projectId: "project", location: "location",
    customHeaders: [{ name: "X-Saved", value: "v" }], models: [{ id: "m", modelId: "m", displayName: "M", type: "CHAT" as const }],
  };
  const draft = connectionDraft(saved);
  assert.equal(draft.useInteractionsApi, true);
  assert.equal(draft.vertexAI, true);
  for (const field of ["apiKey", "baseUrl", "useServiceAccount", "privateKey", "serviceAccountEmail", "projectId", "location"] as const) {
    assert.equal(draft[field], saved[field]);
  }
  assert.equal(draft.models, saved.models);
  assert.deepEqual(JSON.parse(draft.providerHeadersJson), saved.customHeaders);
});

test("draft updates only models from SSE; explicit save sends Google flag, never whole settings/models", () => {
  const effect = source.slice(source.indexOf("React.useEffect(() =>"), source.indexOf("if (!id ||"));
  assert.match(effect, /if \(loadedIdRef.current !== provider.id\)[\s\S]*setDraft\(connectionDraft\(provider\)\)/);
  assert.ok(effect.includes("current ? { ...current, models: provider.models } : current"));
  assert.doesNotMatch(effect, /useInteractionsApi|vertexAI:|providerHeadersJson:/);
  const payload = source.slice(source.indexOf("const payload ="), source.indexOf("const saved = await api.put<ProviderProfile>"));
  assert.ok(payload.includes("useInteractionsApi: draft.useInteractionsApi === true"));
  assert.equal(payload.match(/useInteractionsApi:/g)?.length, 1);
  assert.doesNotMatch(payload, /useInteractionsApi[^\n]*vertexAI|models:|builtIn:|balanceOption:/);
  assert.ok(source.includes("...providerHeadersPayload(draft.providerHeadersJson)"));
  assert.ok(source.includes("onSave={() => void saveConfig()}"));
  assert.doesNotMatch(source, /api\.put[^\n]*["`]settings/);
});

test("Google-only UI keeps saved checked flag disabled with Vertex and explanatory bilingual text", () => {
  const block = source.slice(source.indexOf('{type === "google" ? (', source.indexOf('providers.fields.enabled')),
    source.indexOf('{type === "google" && draft.vertexAI ? ('));
  assert.ok(block.includes("checked={draft.useInteractionsApi}"));
  assert.ok(block.includes("disabled={draft.vertexAI || saving}"));
  assert.ok(block.includes("updateDraft({ vertexAI: checked })"));
  assert.ok(block.includes("updateDraft({ useInteractionsApi: checked })"));
  assert.doesNotMatch(block, /useInteractionsApi: false/);
  for (const locale of [en, zh]) {
    assert.ok(locale.providers.fields.use_interactions_api);
    assert.ok(locale.providers.interactions_hint);
    assert.ok(locale.providers.interactions_vertex_hint);
  }
  assert.match(en.providers.interactions_hint, /Off by default/);
  assert.match(en.providers.interactions_vertex_hint, /retained/);
});
