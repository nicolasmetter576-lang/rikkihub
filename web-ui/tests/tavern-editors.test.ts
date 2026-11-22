import test from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import { register } from "node:module";
import en from "../app/overlay/locales/en-US/tavern.json";
import zh from "../app/overlay/locales/zh-CN/tavern.json";

register("./helpers/preset-overlay-loader.mjs", import.meta.url);
const { PromptLayoutEditor } = await import("../app/overlay/components/tavern/prompt-layout-editor");
const { PromptEntryFields } = await import("../app/overlay/components/tavern/prompt-entry-fields");
const { WorldInfoEntryEditor } = await import("../app/overlay/components/tavern/world-info-entry-editor");
const { reorderScripts } = await import("../app/overlay/components/tavern/regex-script-list");
import { newLibraryEntry, newPromptTextEntry, newRegexScript, withEntryAdded } from "../app/overlay/lib/tavern";
import type { PromptPreset, WorldInfoEntry } from "../app/overlay/types/tavern";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { tavern: en }, "zh-CN": { tavern: zh } },
  ns: ["tavern"],
  defaultNS: "tavern",
  interpolation: { escapeValue: false },
});

const preset = (): PromptPreset => ({
  prompts: [
    { identifier: "worldInfoBefore", name: "World Info (before)", marker: true, system_prompt: true },
    { identifier: "main", name: "Main Prompt", role: "system", content: "MAIN_TEXT", system_prompt: true, marker: false },
    { identifier: "style", name: "Style", role: "user", content: "STYLE_TEXT", system_prompt: false, marker: false },
    { identifier: "late", name: "Late", role: "system", content: "", system_prompt: false, marker: false, injection_position: 1, injection_depth: 2, injection_order: 7 },
    { identifier: "chatHistory", name: "Chat History", marker: true, system_prompt: true },
  ],
  prompt_order: [{
    character_id: 100001,
    order: [
      { identifier: "worldInfoBefore", enabled: true }, { identifier: "main", enabled: true }, { identifier: "style", enabled: false },
      { identifier: "late", enabled: true }, { identifier: "chatHistory", enabled: true },
    ],
  }],
});

const render = (element: ReturnType<typeof createElement>) => renderToStaticMarkup(element);

test("the layout editor lists entries in layout order with translated built-in names", () => {
  const html = render(createElement(PromptLayoutEditor, { preset: preset(), onChange: () => {} }));
  const order = ["World info (before)", "Main prompt", "Style", "Late", "Chat history"].map((title) => html.indexOf(`>${title}<`));
  assert.ok(order.every((index) => index >= 0), JSON.stringify(order));
  assert.deepEqual([...order].sort((a, b) => a - b), order);
  assert.equal(html.match(/data-layout-entry=/g)?.length, 5);
  // markers read "Filled in automatically", text entries show where they go and a preview of their text
  assert.ok(html.includes(en.layout.marker));
  assert.ok(html.includes("User · in order"));
  assert.ok(html.includes("STYLE_TEXT"));
  assert.ok(html.includes("System · in chat, depth 2, order 7"));
  // one switch per entry; the disabled one is off
  assert.equal(html.match(/role="switch"/g)?.length, 5);
  assert.equal(html.match(/aria-checked="false"/g)?.length, 1);
  assert.ok(html.includes(en.layout.add));
  assert.ok(html.includes(en.layout.optionsTitle));
});

test("with SillyTavern mode off the editor lists only the main prompt and the user's entries, without options", () => {
  const withJailbreak: PromptPreset = {
    ...preset(),
    prompts: [...preset().prompts, { identifier: "jailbreak", name: "Post-History Instructions", role: "system", content: "PHI", system_prompt: true, marker: false }],
    prompt_order: [{ character_id: 100001, order: [...preset().prompt_order[0].order, { identifier: "jailbreak", enabled: true }] }],
  };
  const html = render(createElement(PromptLayoutEditor, { preset: withJailbreak, onChange: () => {}, tavernMode: false }));
  assert.equal(html.match(/data-layout-entry=/g)?.length, 3);
  for (const hidden of ["World info (before)", "Chat history", "Post-history instructions"]) assert.ok(!html.includes(`>${hidden}<`), hidden);
  assert.ok(html.includes(en.layout.hintPlain));
  assert.ok(!html.includes(en.layout.optionsTitle));
  // in SillyTavern mode the same preset shows everything
  const full = render(createElement(PromptLayoutEditor, { preset: withJailbreak, onChange: () => {}, tavernMode: true }));
  assert.equal(full.match(/data-layout-entry=/g)?.length, 6);
  assert.ok(full.includes(en.layout.optionsTitle));
});

test("an entry card starts collapsed with an expand arrow and an advanced-settings button; markers have no arrow", () => {
  const html = render(createElement(PromptLayoutEditor, { preset: preset(), onChange: () => {} }));
  assert.ok(html.includes(en.layout.gestureHint));
  // 5 entries each get the advanced button; only the 3 text entries get an expand arrow
  assert.equal(html.match(new RegExp(`aria-label="${en.layout.advanced}"`, "g"))?.length, 5);
  assert.equal(html.match(new RegExp(`aria-label="${en.layout.expand}"`, "g"))?.length, 3);
  // collapsed: no content box, no field editors
  assert.ok(!html.includes("<textarea"));
  assert.ok(!html.includes(en.layout.entryName));
});

test("the advanced dialog fields leave the content out, and keep-original only in SillyTavern mode", () => {
  const noop = () => {};
  const main = preset().prompts[1];
  const advanced = render(createElement(PromptEntryFields, { entry: main, scope: "layout", onChange: noop, showContent: false }));
  assert.ok(advanced.includes(en.layout.entryName) && advanced.includes(en.layout.position) && advanced.includes(en.layout.triggers));
  assert.ok(advanced.includes(en.layout.forbidOverrides));
  assert.ok(!advanced.includes(en.layout.entryContent));
  const plain = render(createElement(PromptEntryFields, { entry: main, scope: "layout", onChange: noop, showContent: false, showForbidOverrides: false }));
  assert.ok(!plain.includes(en.layout.forbidOverrides));
});

test("adding an entry through the editor's rule puts it before the memory marker", () => {
  const next = withEntryAdded(
    { ...preset(), prompts: [...preset().prompts, { identifier: "rikkihubContext", marker: true }], prompt_order: [{ character_id: 100001, order: [{ identifier: "main", enabled: true }, { identifier: "rikkihubContext", enabled: true }] }] },
    newPromptTextEntry("x"),
  );
  assert.deepEqual(next.prompt_order[0].order.map((item) => item.identifier).slice(-2), [next.prompts.at(-1)!.identifier, "rikkihubContext"]);
});

test("entry fields: in chat shows depth and order, library entries show the anchor and priority", () => {
  const noop = () => {};
  const relative = render(createElement(PromptEntryFields, { entry: newLibraryEntry("n"), scope: "library", onChange: noop }));
  assert.ok(relative.includes(en.layout.anchor));
  assert.ok(relative.includes(en.layout.priority));
  assert.ok(!relative.includes(en.layout.depthHint));

  const inChat = render(createElement(PromptEntryFields, {
    entry: { ...newLibraryEntry("n"), injection_position: 1, injection_depth: 9, injection_order: 3 }, scope: "library", onChange: noop,
  }));
  assert.ok(inChat.includes(en.layout.depthHint));
  assert.ok(inChat.includes('value="9"'));
  assert.ok(!inChat.includes(en.layout.anchor));

  // "Keep this text" only for built-in (system prompt) entries in a layout
  const builtin = { identifier: "main", name: "Main Prompt", system_prompt: true, marker: false };
  assert.ok(render(createElement(PromptEntryFields, { entry: builtin, scope: "layout", onChange: noop })).includes(en.layout.forbidOverrides));
  assert.ok(!render(createElement(PromptEntryFields, { entry: builtin, scope: "library", onChange: noop })).includes(en.layout.forbidOverrides));
  assert.ok(!render(createElement(PromptEntryFields, { entry: { ...builtin, system_prompt: false }, scope: "layout", onChange: noop })).includes(en.layout.forbidOverrides));
});

const worldEntry = (patch: Partial<WorldInfoEntry> = {}): WorldInfoEntry => ({
  uid: 1, key: ["dragon", "wyrm"], keysecondary: [], comment: "Dragons", content: "BODY", constant: false, vectorized: false, selective: false,
  selectiveLogic: 0, addMemo: false, order: 100, position: 0, disable: false, ignoreBudget: false, excludeRecursion: false, preventRecursion: false,
  matchPersonaDescription: false, matchCharacterDescription: false, matchCharacterPersonality: false, matchCharacterDepthPrompt: false,
  matchScenario: false, matchCreatorNotes: false, delayUntilRecursion: 0, probability: 100, useProbability: true, depth: 4, outletName: "",
  group: "", groupOverride: false, groupWeight: 100, scanDepth: null, caseSensitive: null, matchWholeWords: null, useGroupScoring: null,
  automationId: "", role: 0, sticky: null, cooldown: null, delay: null, triggers: [], ...patch,
});

test("world info entry editor: seven groups, conditional fields appear with their values", () => {
  const noop = () => {};
  const base = render(createElement(WorldInfoEntryEditor, { entry: worldEntry(), onChange: noop }));
  for (const key of ["sectionBasic", "sectionTrigger", "sectionPosition", "sectionRecursion", "sectionGroup", "sectionTiming", "sectionFilter"] as const) {
    assert.ok(base.includes(en.worldinfo[key]), key);
  }
  // only the first group is open: its fields are there, the others' are folded away
  assert.ok(base.includes('value="dragon, wyrm"') || base.includes(">dragon, wyrm<"));
  assert.ok(base.includes("BODY"));
  assert.ok(!base.includes(en.worldinfo.logic));
  assert.ok(!base.includes(en.worldinfo.probability));
  // the secondary rule only matters once there are secondary keywords
  assert.ok(render(createElement(WorldInfoEntryEditor, { entry: worldEntry({ keysecondary: ["cave"] }), onChange: noop })).includes(en.worldinfo.logic));
});

test("regex scripts reorder by taking the target's place", () => {
  const [a, b, c] = ["a", "b", "c"].map((name) => ({ ...newRegexScript(name), id: name }));
  assert.deepEqual(reorderScripts([a, b, c], "c", "a").map((s) => s.id), ["c", "a", "b"]);
  assert.deepEqual(reorderScripts([a, b, c], "a", "c").map((s) => s.id), ["b", "c", "a"]);
  const same = [a, b, c];
  assert.equal(reorderScripts(same, "a", "a"), same);
  assert.equal(reorderScripts(same, "a", "zzz"), same);
});

function flatten(tree: unknown, prefix = ""): string[] {
  if (typeof tree !== "object" || tree === null) return [prefix];
  return Object.entries(tree).flatMap(([key, value]) => flatten(value, prefix ? `${prefix}.${key}` : key));
}

test("both locales carry the same tavern keys", () => {
  assert.deepEqual(flatten(zh).sort(), flatten(en).sort());
});

test("every tavern key a component asks for exists", () => {
  const roots = ["../../web-settings/web-ui/components/tavern", "../../web-settings/web-ui/routes", "../app/components"];
  const have = new Set(flatten(en));
  const missing: string[] = [];
  const walk = (dir: URL) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const url = new URL(entry.name + (entry.isDirectory() ? "/" : ""), dir);
      if (entry.isDirectory()) { walk(url); continue; }
      if (!/\.tsx$/.test(entry.name)) continue;
      const source = readFileSync(url, "utf8");
      // Files that also read another namespace (the extensions page) are checked by their own keys
      if (!source.includes('useTranslation("tavern")') || source.includes('useTranslation("settings")')) continue;
      for (const match of source.matchAll(/\bt\(\s*["`]([A-Za-z0-9_.]+)["`]/g)) {
        if (!have.has(match[1])) missing.push(`${entry.name}: ${match[1]}`);
      }
    }
  };
  for (const root of roots) walk(new URL(root + "/", import.meta.url));
  assert.deepEqual(missing, []);
});

test("literal macro examples in the texts survive interpolation", () => {
  assert.ok(i18next.t("regex.replaceHint").includes("{{macros}}"));
  assert.ok(i18next.t("worldinfo.outletHint").includes("{{outlet::name}}"));
});
