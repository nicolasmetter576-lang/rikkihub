import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import en from "../app/overlay/locales/en-US/settings.json";
import zh from "../app/overlay/locales/zh-CN/settings.json";
import {
  buildApiKeysPayload,
  buildServiceAccountsPayload,
  maskApiKeyPreview,
  mergeImportedServiceAccounts,
  parseBatchApiKeys,
  parseVertexServiceAccountJsons,
  toApiKeyDraftItems,
  toServiceAccountDraftItems,
} from "../app/overlay/lib/provider-credentials";

const providerRouteSource = readFileSync(
  new URL("../app/overlay/routes/settings.providers.$id.tsx", import.meta.url),
  "utf8",
);

test("parseBatchApiKeys splits whitespace/commas and deduplicates", () => {
  assert.deepEqual(
    parseBatchApiKeys("sk-1, sk-2\nsk-3\tsk-1  sk-4 "),
    ["sk-1", "sk-2", "sk-3", "sk-4"],
  );
  assert.deepEqual(parseBatchApiKeys("   \n\t "), []);
});

test("maskApiKeyPreview hides prefix and short keys cleanly", () => {
  assert.equal(maskApiKeyPreview(""), "");
  assert.equal(maskApiKeyPreview("short-key"), "••••");
  assert.equal(maskApiKeyPreview("sk-1234567890abcd"), "••••abcd");
});

test("parseVertexServiceAccountJsons handles single object, array, and concatenated objects", () => {
  const obj1 = JSON.stringify({
    project_id: "proj-1",
    client_email: "sa1@example.invalid",
    private_key: "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
  });
  const obj2 = JSON.stringify({
    projectId: "proj-2",
    serviceAccountEmail: "sa2@example.invalid",
    privateKey: "pem-2",
    location: "global",
    label: "Edge",
  });
  const bad = JSON.stringify({ project_id: "only-project" });

  const single = parseVertexServiceAccountJsons(obj1);
  assert.equal(single.accounts.length, 1);
  assert.equal(single.skippedCount, 0);
  assert.equal(single.accounts[0].projectId, "proj-1");

  const array = parseVertexServiceAccountJsons(`[${obj1},${bad},${obj2}]`);
  assert.equal(array.accounts.length, 2);
  assert.equal(array.skippedCount, 1);
  assert.equal(array.accounts[1].location, "global");
  assert.equal(array.accounts[1].label, "Edge");

  const concat = parseVertexServiceAccountJsons(`${obj1}\n${obj2}`);
  assert.equal(concat.accounts.length, 2);
  assert.equal(concat.accounts[1].serviceAccountEmail, "sa2@example.invalid");
});

test("mergeImportedServiceAccounts deduplicates identical project/email/location and allows different locations", () => {
  const existing = toServiceAccountDraftItems([
    {
      id: "existing-1",
      projectId: "p1",
      serviceAccountEmail: "e1",
      location: "us-central1",
      privateKeyConfigured: true,
    },
  ]);
  const res = mergeImportedServiceAccounts(existing, [
    ...parseVertexServiceAccountJsons(
      JSON.stringify({ project_id: "p1", client_email: "e1", private_key: "pem", location: "us-central1" }),
    ).accounts,
    ...parseVertexServiceAccountJsons(
      JSON.stringify({ project_id: "p1", client_email: "e1", private_key: "pem", location: "global" }),
    ).accounts,
  ]);
  assert.equal(res.addedCount, 1);
  assert.equal(res.merged.length, 2);
  assert.equal(res.merged[1].location, "global");
});

test("payload builders preserve masked credentials unless touched", () => {
  const keyDrafts = toApiKeyDraftItems([
    { id: "k1", label: "Keep", enabled: true, key: "", keyConfigured: true, keyPreview: "••••1234" },
  ]);
  keyDrafts.push({
    id: "k2",
    label: "New",
    enabled: false,
    key: "sk-new-secret",
    keyConfigured: false,
    keyPreview: "••••cret",
    keyTouched: true,
  });
  assert.deepEqual(buildApiKeysPayload(keyDrafts), [
    { id: "k1", label: "Keep", enabled: true, key: "", keyConfigured: true },
    { id: "k2", label: "New", enabled: false, key: "sk-new-secret" },
  ]);

  const saDrafts = toServiceAccountDraftItems([
    {
      id: "sa1",
      label: "Primary",
      projectId: "p1",
      serviceAccountEmail: "e1",
      location: "",
      enabled: true,
      privateKey: "",
      privateKeyConfigured: true,
    },
  ]);
  assert.deepEqual(buildServiceAccountsPayload(saDrafts), [
    {
      id: "sa1",
      label: "Primary",
      projectId: "p1",
      serviceAccountEmail: "e1",
      location: "",
      enabled: true,
      privateKey: "",
      privateKeyConfigured: true,
    },
  ]);
});

test("bilingual credential locale keys exist and route sends structured pool only when touched", () => {
  for (const locale of [en, zh]) {
    assert.ok(locale.providers.credentials.rotation_strategy);
    assert.ok(locale.providers.credentials.api_key_pool_title);
    assert.ok(locale.providers.credentials.sa_pool_title);
    assert.ok(locale.providers.credentials.reset_cooldown);
  }
  assert.ok(providerRouteSource.includes("draft.apiKeysTouched"));
  assert.ok(providerRouteSource.includes("draft.serviceAccountsTouched"));
});
