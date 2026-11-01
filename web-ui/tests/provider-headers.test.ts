import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { register } from "node:module";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import { COMMON_PROVIDER_HEADER_NAMES, parseProviderHeaders, providerHeadersDraft, providerHeadersPayload } from "../app/overlay/lib/provider-headers";
import en from "../app/overlay/locales/en-US/settings.json";
import zh from "../app/overlay/locales/zh-CN/settings.json";
register("./helpers/preset-overlay-loader.mjs", import.meta.url);
const { ProviderHeadersEditor } = await import("../app/overlay/components/settings/provider-headers-editor");
await i18next.use(initReactI18next).init({ lng: "en-US", resources: { "en-US": { settings: en }, "zh-CN": { settings: zh } }, interpolation: { escapeValue: false } });
const source = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");
const parse = (name: unknown, value: unknown) => parseProviderHeaders(JSON.stringify([{ name, value }]));

test("old providers draft to empty; empty/[] explicitly clear with header-only payload", () => {
  assert.equal(providerHeadersDraft({}), "[]");
  for (const text of ["", " \n\t", "[]"]) {
    assert.deepEqual(providerHeadersPayload(text), { customHeaders: [] });
  }
  const headers = [{ name: "X-Custom", value: "  untrimmed\t " }];
  assert.deepEqual(providerHeadersPayload(providerHeadersDraft({ customHeaders: headers })), { customHeaders: headers });
});

test("literal shape validation rejects malformed JSON, containers and coercion", () => {
  assert.deepEqual(parseProviderHeaders("{"), { ok: false, error: "json" });
  for (const text of ["null", "{}", "1", '[null]', '[[]]', '[{}]']) {
    assert.deepEqual(parseProviderHeaders(text), { ok: false, error: "shape" });
  }
  for (const [name, value] of [[1, "v"], ["X", 1], [true, ""], ["X", {}]]) {
    assert.deepEqual(parse(name, value), { ok: false, error: "shape" });
  }
  assert.throws(() => providerHeadersPayload("null"), /provider_headers_shape/);
});

test("masked projection echo and null values carry the preserve (not clear) semantics", () => {
  // Wire contract: `value: null` (or `""`+valueConfigured) echoes
  // "keep the stored secret"; only a literal string sets, only "" clears.
  const masked = providerHeadersPayload(JSON.stringify([
    { name: "Authorization", value: "", valueConfigured: true },
    { name: "X-Keep", value: null },
    { name: "X-Clear", value: "" },
    { name: "X-Set", value: "v" },
  ]));
  assert.deepEqual(masked, {
    customHeaders: [
      { name: "Authorization", value: null, valueConfigured: true },
      { name: "X-Keep", value: null, valueConfigured: true },
      { name: "X-Clear", value: "" },
      { name: "X-Set", value: "v" },
    ],
  });
  // Drafting projected headers keeps them in the preserve form on re-save.
  const drafted = providerHeadersPayload(providerHeadersDraft({
    customHeaders: [{ name: "Authorization", value: "", valueConfigured: true }],
  }));
  assert.deepEqual(drafted, {
    customHeaders: [{ name: "Authorization", value: null, valueConfigured: true }],
  });
});

test("RFC7230 tokens, blank ignored names, and value byte boundaries", () => {
  for (const name of ["", " \t", "!#$%&'*+-.^_`|~09AZaz", "Authorization"]) {
    assert.equal(parse(name, "").ok, true);
  }
  for (const name of [" X", "X ", "X:Y", "X/Y", "X\r\n", "\r\n", "\n", "\uFEFF", "头", "X\u007f"]) {
    assert.deepEqual(parse(name, ""), { ok: false, error: "name" });
  }
  for (let code = 0; code <= 127; code++) {
    assert.equal(parse("X", String.fromCharCode(code)).ok, code === 9 || (code >= 32 && code <= 126), `value byte ${code}`);
  }
  for (const value of ["中", "é", "😀", "ok\r\nInjected: yes", "\u0080"]) {
    assert.deepEqual(parse("X", value), { ok: false, error: "value" });
  }
  assert.deepEqual(parse("", "\n"), { ok: false, error: "value" });
});

test("case, duplicate names and value whitespace are preserved, not silently merged", () => {
  const headers = [{ name: "X-Test", value: " first " }, { name: "x-test", value: "\tsecond\t" }];
  assert.deepEqual(providerHeadersPayload(JSON.stringify(headers)), { customHeaders: headers });
});

test("UI renders names without values, localized validation, and explicit save guidance", async () => {
  for (const [language, locale] of [["en-US", en], ["zh-CN", zh]] as const) {
    await i18next.changeLanguage(language);
    const html = renderToStaticMarkup(createElement(ProviderHeadersEditor, { value: "[]", disabled: false, onChange: () => {} }));
    for (const name of COMMON_PROVIDER_HEADER_NAMES) assert.ok(html.includes(name));
    assert.match(html, /autocomplete="off"/i);
    assert.ok(html.includes(locale.providers.headers.save_hint));
    assert.ok(html.includes(locale.providers.headers.priority_hint));
    const invalid = renderToStaticMarkup(createElement(ProviderHeadersEditor, { value: "null", disabled: true, onChange: () => {} }));
    assert.ok(invalid.includes('role="alert"'));
    assert.ok(invalid.includes(locale.providers.headers.errors.shape));
    assert.match(invalid, /<fieldset[^>]*disabled=""/);
    assert.ok(!html.includes("providers.headers."));
  }
  assert.deepEqual(Object.keys(en.providers.headers), Object.keys(zh.providers.headers));
  assert.deepEqual(Object.keys(en.providers.headers.errors), Object.keys(zh.providers.headers.errors));
});

test("route guards all six variants, draft isolation, field-only PUT and existing model editor", () => {
  const route = source("../app/overlay/routes/settings.providers.$id.tsx");
  assert.ok(route.includes("providerHeadersJson: providerHeadersDraft(provider)"));
  assert.match(route, /if \(loadedIdRef.current !== provider.id\)[\s\S]*setDraft\(connectionDraft\(provider\)\)/);
  assert.ok(route.includes("current ? { ...current, models: provider.models } : current"));
  assert.ok(route.includes("...providerHeadersPayload(draft.providerHeadersJson)"));
  assert.ok(route.indexOf("parseProviderHeaders(draft.providerHeadersJson)") < route.indexOf("setSaving(true)"));
  assert.ok(route.includes("onSave={() => void saveConfig()}"));
  assert.ok(route.includes("setModelHeadersJson(JSON.stringify(model.customHeaders ?? [], null, 2))"));
  const connectionPayload = route.slice(route.indexOf("const payload ="), route.indexOf("const saved = await api.put<ProviderProfile>"));
  for (const type of ["openai", "google", "claude", "codex", "devin", "antigravity"]) assert.ok(connectionPayload.includes(`type: "${type}"`));
  assert.doesNotMatch(connectionPayload, /models:|deviceSeed:|oauth|accessToken|refreshToken/);
  assert.doesNotMatch(route, /api\.put[^\n]*["`]settings/);
  const types = source("../app/overlay/types/settings.ts");
  assert.match(types, /interface ProviderProfileBase \{\s+customHeaders\?: Array<\{ name: string; value: string \| null; valueConfigured\?: boolean \}>;/);
  for (const variant of ["OpenAI", "Google", "Claude", "Codex", "Devin", "Antigravity"]) {
    assert.ok(types.includes(`interface ${variant}ProviderProfile extends ProviderProfileBase`));
  }
});
