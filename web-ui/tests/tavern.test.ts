import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  activationsOfParts, contextUnlimited, decodeActivations, entryDepth, entryMatchesQuery, entryOrder, entryPlacement, entryTitle, entryTriggers,
  formatKeyList, normalizeFindRegex, optionsOf, parseKeyList, presetLayout, presetRegexScripts, presetTextEntries, promptPreviewText,
  splitRegexLiteral, toggleEntryTrigger, toggleTrigger, triStateOf, triStateValue, variableDisplay, withContextSize, withEntryAdded,
  withEntryEnabled, withEntryMoved, withEntryMovedTo, withEntryRemoved, withPlacement, withPresetRegexScripts, withPrimaryKeys,
  withProbability, withScriptPlacement, withSecondaryKeys, withTriggerSet, withTriggers, newLibraryEntry, newPromptTextEntry,
  newRegexScript, withLibraryMeta, libraryEnabled, libraryAnchor, MARKER_RIKKIHUB_CONTEXT, applyDisplay, displayScriptsSignature, diffObjects, isUuid, isPlainPromptEntry,
} from "../app/overlay/lib/tavern";
import type { PromptEntry, PromptPreset, RegexScript, WorldInfoEntry } from "../app/overlay/types/tavern";

/** The App's unit tests run the same file (`TavernWebCasesTest`). */
const cases = JSON.parse(
  readFileSync(new URL("../../app/src/test/resources/tavern/web-cases.json", import.meta.url), "utf8"),
) as Array<Record<string, any>>;

/** Every key in [expected] must match; null means "absent or null". */
function assertSubset(name: string, expected: Record<string, unknown>, actual: Record<string, unknown>) {
  for (const [key, value] of Object.entries(expected)) {
    const got = actual[key];
    if (value === null) assert.ok(got === undefined || got === null, `${name}: ${key} should be absent but was ${JSON.stringify(got)}`);
    else assert.deepEqual(got, value, `${name}: ${key}`);
  }
}

const pairs = (preset: PromptPreset) => presetLayout(preset).map(({ entry, enabled }) => [entry.identifier, enabled]);

test("the cases shared with the App", () => {
  assert.ok(cases.length >= 90);
  for (const c of cases) {
    const name = `${c.kind}: ${c.name}`;
    switch (c.kind) {
      case "parseKeyList": assert.deepEqual(parseKeyList(c.text), c.expected, name); break;
      case "formatKeyList": assert.equal(formatKeyList(c.keys), c.expected, name); break;
      case "normalizeFindRegex": assert.equal(normalizeFindRegex(c.input), c.expected, name); break;
      case "splitRegexLiteral": assert.deepEqual(splitRegexLiteral(c.input), c.expected, name); break;
      case "withPlacement":
        assertSubset(name, c.expected, withPlacement(c.entry, c.placement, { depth: c.depth, order: c.order }));
        break;
      case "entryPlacement": assert.equal(entryPlacement(c.entry), c.expected, name); break;
      case "withTriggers": assertSubset(name, c.expected, withTriggers(c.entry, c.selected)); break;
      case "toggleTrigger": assertSubset(name, c.expected, toggleTrigger(c.entry, c.type)); break;
      case "wi.primaryKeys": assertSubset(name, c.expected, withPrimaryKeys(c.entry, c.text)); break;
      case "wi.secondaryKeys": assertSubset(name, c.expected, withSecondaryKeys(c.entry, c.text)); break;
      case "wi.probability": assertSubset(name, c.expected, withProbability(c.entry, c.percent)); break;
      case "wi.triggers": assertSubset(name, c.expected, withTriggerSet(c.entry, c.selected)); break;
      case "wi.toggleTrigger": assertSubset(name, c.expected, toggleEntryTrigger(c.entry, c.type)); break;
      case "wi.title": assert.equal(entryTitle(c.entry), c.expected, name); break;
      case "wi.matches": assert.equal(entryMatchesQuery(c.entry as WorldInfoEntry, c.query), c.expected, name); break;
      case "layout": {
        let preset = c.preset as PromptPreset;
        for (const op of c.ops as Array<Record<string, any>>) {
          if (op.op === "move") preset = withEntryMoved(preset, op.id, op.delta);
          else if (op.op === "moveTo") preset = withEntryMovedTo(preset, op.id, op.target);
          else if (op.op === "enabled") preset = withEntryEnabled(preset, op.id, op.enabled);
          else if (op.op === "remove") preset = withEntryRemoved(preset, op.id);
          else if (op.op === "add") preset = withEntryAdded(preset, op.entry, op.before);
          else assert.fail(`unknown op ${op.op}`);
        }
        assert.deepEqual(pairs(preset), c.expected, name);
        break;
      }
      case "textEntries": assert.deepEqual(presetTextEntries(c.preset).map(({ entry }) => entry.identifier), c.expected, name); break;
      case "previewText": assert.equal(promptPreviewText(c.preset), c.expected, name); break;
      case "options": {
        let preset = c.preset as PromptPreset;
        for (const op of c.ops as Array<Record<string, any>>) {
          if (op.op === "contextSize") preset = withContextSize(preset, op.tokens);
          else assert.fail(`unknown op ${op.op}`);
        }
        const o = optionsOf(preset);
        const view = {
          contextSize: o.contextSize, responseTokens: o.responseTokens, squash: o.squashSystemMessages,
          newChatPrompt: o.newChatPrompt, wiFormat: o.wiFormat, unlimited: contextUnlimited(preset),
        };
        assertSubset(name, c.expected, { ...preset, ...view });
        break;
      }
      case "regex.placement": assert.deepEqual(withScriptPlacement(c.script, c.point, c.on).placement, c.expected, name); break;
      case "regex.presetScripts":
        if ("set" in c) assertSubset(name, c.expected, withPresetRegexScripts(c.preset, c.set as RegexScript[]));
        else assert.deepEqual(presetRegexScripts(c.preset).map((s) => s.id), c.expected, name);
        break;
      case "variableDisplay": assert.equal(variableDisplay(c.value), c.expected, name); break;
      case "activations": assert.deepEqual(decodeActivations(c.record), c.expected, name); break;
      default: assert.fail(`unknown case kind ${c.kind}`);
    }
  }
});

test("a new library entry is a relative custom entry after the system prompt", () => {
  const entry = newLibraryEntry("note");
  assert.equal(entry.name, "note");
  assert.equal(entry.system_prompt, false);
  assert.equal(entry.marker, false);
  assert.equal(entryPlacement(entry), "relative");
  assert.equal(libraryEnabled(entry), true);
  assert.equal(libraryAnchor(entry), "after_system");
  assert.match(entry.identifier, /^[0-9a-f-]{36}$/);
  const off = withLibraryMeta(entry, { enabled: false });
  assert.equal(libraryEnabled(off), false);
  assert.equal(libraryAnchor(off), "after_system");
  assert.equal(libraryAnchor(withLibraryMeta(off, { anchor: "before_history" })), "before_history");
});

test("a new text entry and a new regex script have the App's defaults", () => {
  const text = newPromptTextEntry("t");
  assert.deepEqual([text.role, text.content, text.system_prompt, text.marker], ["system", "", false, false]);
  assert.equal(entryDepth(text), 4);
  assert.equal(entryOrder(text), 100);
  assert.deepEqual(entryTriggers(text), []);
  const script = newRegexScript("s");
  assert.deepEqual(script.placement, [2, 6]);
  assert.equal(script.findRegex, "");
  assert.equal(script.substituteRegex, 0);
});

test("adding an entry twice is refused", () => {
  const preset = { prompts: [], prompt_order: [] } as PromptPreset;
  const entry = newPromptTextEntry("x");
  const once = withEntryAdded(preset, entry);
  assert.throws(() => withEntryAdded(once, entry));
});

test("tri-state fields map to the data and back", () => {
  assert.equal(triStateOf(null), "inherit");
  assert.equal(triStateOf(undefined), "inherit");
  assert.equal(triStateOf(true), "on");
  assert.equal(triStateOf(false), "off");
  assert.equal(triStateValue("inherit"), null);
  assert.equal(triStateValue("on"), true);
  assert.equal(triStateValue("off"), false);
});

test("the activation record is found on the first text part that has one", () => {
  const record = { v: 1, more: 0, entries: [{ book: "b", bookName: "B", uid: 1, title: "t", position: 0, order: 5 }] };
  const parts = [
    { type: "reasoning" },
    { type: "text", metadata: { other: 1 } },
    { type: "text", metadata: { tavernActivations: record } },
  ];
  assert.equal(activationsOfParts(parts)?.total, 1);
  assert.equal(activationsOfParts([{ type: "text" }]), null);
  assert.equal(activationsOfParts([{ type: "image", metadata: { tavernActivations: record } }]), null);
});

test("a library entry patch keeps unknown fields", () => {
  const entry: PromptEntry = { identifier: "e", future: { a: 1 } };
  assert.deepEqual(withPlacement(entry, "in_chat").future, { a: 1 });
  assert.deepEqual(withTriggers(entry, ["normal"]).future, { a: 1 });
});

test("display text replaces text and reasoning parts by index and leaves the rest alone", () => {
  const parts = [
    { type: "reasoning", reasoning: "think" },
    { type: "text", text: "raw <b>" },
    { type: "image", url: "u" },
    { type: "text", text: "same" },
  ];
  const shown = applyDisplay(parts, { "0": "thought", "1": "shown", "2": "ignored", "3": "same" });
  assert.deepEqual(shown.map((part) => part.reasoning ?? part.text ?? part.url), ["thought", "shown", "u", "same"]);
  assert.equal(shown[2], parts[2]);
  assert.equal(shown[3], parts[3]);
  assert.equal(parts[1].text, "raw <b>"); // the input is not changed
  assert.equal(applyDisplay(parts, null), parts);
  assert.equal(applyDisplay(parts, {}), parts);
  assert.equal(applyDisplay(parts, { "3": "same", "9": "x" }), parts);
});

test("the display signature is empty without display scripts and follows what they depend on", () => {
  const script = (patch: Record<string, unknown>) => ({ ...newRegexScript("s"), markdownOnly: true, ...patch });
  const settings = (scripts: unknown[], assistantScripts: unknown[] = [], vars: Record<string, unknown> = {}) => ({
    tavern: { regexScripts: scripts as never, globalVariables: vars },
    assistants: [{ id: "a", promptPreset: { prompts: [], prompt_order: [], extensions: { regex_scripts: assistantScripts as never } } }],
    displaySetting: { userNickname: "me" },
  });
  assert.equal(displayScriptsSignature(null, "a"), "");
  assert.equal(displayScriptsSignature(settings([]), "a"), "");
  // only active display-only scripts count
  assert.equal(displayScriptsSignature(settings([newRegexScript("plain"), script({ disabled: true })]), "a"), "");
  const base = script({});
  const one = displayScriptsSignature(settings([base]), "a");
  assert.notEqual(one, "");
  assert.equal(displayScriptsSignature(settings([{ ...base }]), "a"), one);
  assert.notEqual(displayScriptsSignature(settings([{ ...base, replaceString: "x" }]), "a"), one);
  assert.notEqual(displayScriptsSignature(settings([base], [], { v: 1 }), "a"), one);
  // the assistant's own scripts count only for that assistant
  const own = settings([], [base]);
  assert.notEqual(displayScriptsSignature(own, "a"), "");
  assert.equal(displayScriptsSignature(own, "other"), "");
});

test("a patch holds only the changed fields; removed fields become null", () => {
  assert.deepEqual(diffObjects({ a: 1, b: [1, 2], c: { d: 1 } }, { a: 1, b: [1, 2], c: { d: 1 } }), {});
  assert.deepEqual(diffObjects({ a: 1, b: [1, 2] }, { a: 2, b: [1, 2], e: "x" }), { a: 2, e: "x" });
  assert.deepEqual(diffObjects({ a: 1, b: 2 }, { a: 1 }), { b: null });
  assert.deepEqual(diffObjects({ a: 1, b: undefined }, { a: 1 }), {});
  assert.deepEqual(diffObjects({ list: ["a"] }, { list: ["a", "b"] }), { list: ["a", "b"] });
});

test("only UUIDs can be picked from the library", () => {
  assert.equal(isUuid("0b9c6f0e-3f3c-4d3e-9e53-0a8f6f2f6f11"), true);
  assert.equal(isUuid("main"), false);
  assert.equal(isUuid(""), false);
});

test("isPlainPromptEntry: the main prompt and user entries, not markers, auxiliary prompts or the post-history instructions", () => {
  const entry = (identifier: string, marker = false): PromptEntry => ({ identifier, marker });
  assert.ok(isPlainPromptEntry(entry("main")));
  assert.ok(isPlainPromptEntry(entry("0f0e-user-entry")));
  for (const id of ["jailbreak", "nsfw", "enhanceDefinitions"]) assert.ok(!isPlainPromptEntry(entry(id)), id);
  for (const id of ["chatHistory", "worldInfoBefore", MARKER_RIKKIHUB_CONTEXT, "charDescription"]) assert.ok(!isPlainPromptEntry(entry(id, true)), id);
});
