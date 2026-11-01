import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import en from "../app/overlay/locales/en-US/settings.json";
import zh from "../app/overlay/locales/zh-CN/settings.json";
import {
  composeSystemPrompt, movePromptSection, newPromptSection, promptSegmentsOf, remapPromptSegments,
  switchSystemPromptMode, systemPromptModeOf, withPromptSections, withPromptSegments,
} from "../app/overlay/lib/settings";
import { register } from "node:module";
register("./helpers/preset-overlay-loader.mjs", import.meta.url);
const { AssistantSystemPromptEditor } = await import("../app/overlay/components/settings/assistant-system-prompt-editor");
import type { PromptSection } from "../app/overlay/types/settings";

await i18next.use(initReactI18next).init({ lng: "en-US", resources: { "en-US": { settings: en }, "zh-CN": { settings: zh } }, interpolation: { escapeValue: false } });

const section = (id: string, content: string, enabled = true, title = ""): PromptSection => ({ id, title, content, enabled });

test("composition mirrors the app: whole without sections, otherwise enabled non-blank sections in order", () => {
  assert.equal(systemPromptModeOf({}), "whole");
  assert.equal(systemPromptModeOf({ systemPromptMode: "bogus" as never }), "whole");
  assert.equal(composeSystemPrompt({ systemPrompt: "w" }), "w");
  const sections = [section("a", "alpha", true, "T"), section("b", "beta", false), section("c", "  "), section("d", "delta")];
  // the editor view does not matter: with sections the prompt is their join
  assert.equal(composeSystemPrompt({ systemPrompt: "w", promptSections: sections }), "alpha\n\ndelta");
  assert.equal(composeSystemPrompt({ systemPrompt: "w", promptSections: sections.slice(0, 1) }), "alpha");
  assert.equal(composeSystemPrompt({ promptSections: [] }), "");
  assert.deepEqual(withPromptSections(sections).systemPrompt, "alpha\n\ndelta");
  assert.equal(withPromptSections([]).systemPrompt, "");
});

const texts = (segments: { text: string }[]) => segments.map((segment) => segment.text);

test("whole view edits land in the matching sections (parity with the app)", () => {
  const two = [{ sectionId: "a", text: "A" }, { sectionId: "c", text: "C" }];
  assert.deepEqual(texts(remapPromptSegments(two, "Ax\n\nC").segments), ["Ax", "C"]);
  assert.deepEqual(texts(remapPromptSegments(two, "A\n\nxC").segments), ["A", "xC"]);
  const merged = remapPromptSegments(two, "A\nC");
  assert.deepEqual(merged.segments, [{ sectionId: "a", text: "A\nC" }]);
  assert.deepEqual(merged.cleared, ["c"]);
  const three = [{ sectionId: "a", text: "A" }, { sectionId: "b", text: "B" }, { sectionId: "c", text: "C" }];
  const middle = remapPromptSegments(three, "A\n\nC");
  assert.deepEqual(middle.segments.map((segment) => segment.sectionId), ["a", "c"]);
  assert.deepEqual(middle.cleared, ["b"]);
  assert.deepEqual(remapPromptSegments(three, "B\n\nC").cleared, ["a"]);
  const emptied = remapPromptSegments(two, "\n\nC");
  assert.deepEqual(texts(emptied.segments), ["", "C"]);
  assert.deepEqual(texts(remapPromptSegments(emptied.segments, "Z\n\nC").segments), ["Z", "C"]);
  for (const edit of ["", "x", "A\n\nB\n\nC\n\n", "AB\nC", "C\n\nB\n\nA"]) {
    assert.equal(texts(remapPromptSegments(three, edit).segments).join("\n\n"), edit);
  }
});

test("writing segments back keeps disabled sections and the whole prompt in sync", () => {
  const sections = [section("a", "alpha"), section("b", "beta", false), section("c", "gamma")];
  const segments = promptSegmentsOf(sections, "new");
  assert.deepEqual(segments.map((segment) => segment.sectionId), ["a", "c"]);
  const remap = remapPromptSegments(segments, "alpha!\n\ngamma");
  const next = withPromptSegments(sections, remap.segments, remap.cleared);
  assert.deepEqual(next.promptSections?.map((s) => s.content), ["alpha!", "beta", "gamma"]);
  assert.equal(next.systemPrompt, "alpha!\n\ngamma");
  // nothing visible: the first enabled section (or a new one) takes the text
  assert.deepEqual(promptSegmentsOf([section("b", "beta", false), section("e", " ")], "new"), [{ sectionId: "e", text: "" }]);
  const appended = withPromptSegments([section("b", "beta", false)], [{ sectionId: "new", text: "fresh" }], []);
  assert.deepEqual(appended.promptSections?.map((s) => s.id), ["b", "new"]);
  assert.equal(appended.systemPrompt, "fresh");
});

test("switching to sections seeds the whole prompt once and keeps both modes' data", () => {
  const seeded = switchSystemPromptMode({ systemPrompt: "persona" }, "sections", "Original");
  assert.equal(seeded.systemPromptMode, "sections");
  assert.deepEqual(seeded.promptSections?.map(({ title, content, enabled }) => ({ title, content, enabled })),
    [{ title: "Original", content: "persona", enabled: true }]);
  assert.equal(composeSystemPrompt({ systemPrompt: "persona", ...seeded }), "persona");

  const existing = [section("a", "alpha")];
  assert.equal(switchSystemPromptMode({ systemPrompt: "persona", promptSections: existing }, "sections", "x").promptSections, existing);
  assert.deepEqual(switchSystemPromptMode({ systemPrompt: " " }, "sections", "x").promptSections, []);
  assert.deepEqual(switchSystemPromptMode({ systemPrompt: "p", promptSections: existing }, "whole", "x"),
    { systemPromptMode: "whole", promptSections: existing });
});

test("moving sections is bounded and does not mutate the input", () => {
  const list = [section("a", "1"), section("b", "2"), section("c", "3")];
  assert.deepEqual(movePromptSection(list, 0, 1).map((s) => s.id), ["b", "a", "c"]);
  assert.deepEqual(movePromptSection(list, 2, -2).map((s) => s.id), ["c", "a", "b"]);
  assert.equal(movePromptSection(list, 0, -1), list);
  assert.equal(movePromptSection(list, 2, 1), list);
  assert.deepEqual(list.map((s) => s.id), ["a", "b", "c"]);
  const fresh = newPromptSection();
  assert.equal(fresh.enabled, true);
  assert.notEqual(fresh.id, newPromptSection().id);
});

test("editor renders the joined sections in the whole view or one block per section", () => {
  const plain = renderToStaticMarkup(createElement(AssistantSystemPromptEditor, {
    assistant: { systemPrompt: "WHOLE_TEXT" }, onChange: () => {},
  }));
  assert.ok(plain.includes("WHOLE_TEXT"));
  assert.ok(!plain.includes(en.assistants.prompt_sections.whole_merged_hint));

  const whole = renderToStaticMarkup(createElement(AssistantSystemPromptEditor, {
    assistant: { systemPrompt: "STALE", promptSections: [section("a", "FIRST"), section("b", "OFF", false), section("c", "SECOND")] },
    onChange: () => {},
  }));
  assert.ok(whole.includes("FIRST\n\nSECOND"));
  assert.ok(!whole.includes("OFF"));
  assert.ok(whole.includes(en.assistants.prompt_sections.whole_merged_hint));

  const sections = renderToStaticMarkup(createElement(AssistantSystemPromptEditor, {
    assistant: { systemPrompt: "WHOLE_TEXT", systemPromptMode: "sections", promptSections: [section("a", "FIRST"), section("b", "SECOND")] },
    onChange: () => {},
  }));
  assert.ok(!sections.includes("WHOLE_TEXT"));
  assert.equal(sections.match(/data-prompt-section=/g)?.length, 2);
  assert.ok(sections.indexOf("FIRST") < sections.indexOf("SECOND"));
  assert.ok(sections.includes(en.assistants.prompt_sections.desc));
  assert.ok(!/preview/i.test(sections));
  // first block cannot move up, last cannot move down
  assert.equal(sections.match(/aria-label="Move up"[^>]*disabled=""|disabled=""[^>]*aria-label="Move up"/g)?.length, 1);
  assert.equal(sections.match(/aria-label="Move down"[^>]*disabled=""|disabled=""[^>]*aria-label="Move down"/g)?.length, 1);
});

test("both locales carry every prompt section key", () => {
  assert.deepEqual(Object.keys(zh.assistants.prompt_sections).sort(), Object.keys(en.assistants.prompt_sections).sort());
});
