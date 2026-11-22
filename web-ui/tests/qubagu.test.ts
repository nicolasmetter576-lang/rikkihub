import assert from "node:assert/strict";
import test from "node:test";

import {
  javaStringHash,
  parseQuotaWords,
  parseReplacements,
  qubaguOnOneLine,
  qubaguRecordMatches,
  qubaguRecordsOfParts,
  mapPack,
  movePack,
  newQuotaRule,
  newReplaceRule,
  newRulePack,
  qubaguRuleLabelKey,
  qubaguRuleName,
  type QubaguConfig,
  type QubaguEdit,
} from "../../web-settings/web-ui/lib/qubagu";

// 原文「甲乙丙丁戊己庚辛」：乙→BB（已应用）、丁戊 删掉（已应用）、庚→G（未应用）
const WIRE = {
  v: 1,
  edits: [
    { s: 1, b: "乙", a: "BB", r: "custom:a", on: true },
    { s: 3, b: "丁戊", a: "", r: "quota" },
    { s: 6, b: "庚", a: "G", r: "pack:phys_pupil_quake", on: false },
  ],
};
const CURRENT = "甲BB丙己庚辛";
Object.assign(WIRE, { h: javaStringHash(CURRENT) });

test("the text fingerprint is Java's String.hashCode", () => {
  assert.equal(javaStringHash(""), 0);
  assert.equal(javaStringHash("a"), 97);
  assert.equal(javaStringHash("hello"), 99162322);
  assert.equal(javaStringHash("去八股"), "去八股".split("").reduce((h, c) => (Math.imul(31, h) + c.charCodeAt(0)) | 0, 0));
  assert.equal(javaStringHash("The quick brown fox jumps over the lazy dog"), -609428141);
});

test("reads the record from a text part and defaults `on` to applied", () => {
  const records = qubaguRecordsOfParts([
    { type: "reasoning", text: "x" },
    { type: "text", text: CURRENT, metadata: { tavernActivations: {}, qubagu: WIRE } },
    { type: "text", text: "没有记录" },
  ]);
  assert.equal(records.length, 1);
  assert.equal(records[0].part, 1);
  assert.deepEqual(records[0].edits.map(edit => [edit.start, edit.before, edit.after, edit.rule, edit.applied]), [
    [1, "乙", "BB", "custom:a", true],
    [3, "丁戊", "", "quota", true],
    [6, "庚", "G", "pack:phys_pupil_quake", false],
  ]);
});

test("a record that no longer matches the text is dropped", () => {
  assert.deepEqual(qubaguRecordsOfParts([{ type: "text", text: "甲XX丙己庚辛", metadata: { qubagu: WIRE } }]), []);
  assert.deepEqual(qubaguRecordsOfParts([{ type: "text", text: "甲", metadata: { qubagu: WIRE } }]), []);
  assert.deepEqual(qubaguRecordsOfParts([{ type: "text", text: "", metadata: { qubagu: WIRE } }]), []);
  // 只有删除的记录位置上总对得上，靠指纹认出正文变过
  const deletion = { v: 1, h: javaStringHash("甲丙"), edits: [{ s: 1, b: "乙", a: "" , r: "quota" }] };
  assert.equal(qubaguRecordsOfParts([{ type: "text", text: "甲丙", metadata: { qubagu: deletion } }]).length, 1);
  assert.deepEqual(qubaguRecordsOfParts([{ type: "text", text: "甲丙丁", metadata: { qubagu: deletion } }]), []);
  assert.deepEqual(qubaguRecordsOfParts([{ type: "text", text: CURRENT, metadata: { qubagu: { ...WIRE, h: undefined } } }]), []);
});

test("matching follows the applied flag of every edit", () => {
  const edits: QubaguEdit[] = [
    { start: 1, before: "乙", after: "BB", rule: "x", applied: true },
    { start: 6, before: "庚", after: "G", rule: "x", applied: false },
  ];
  assert.ok(qubaguRecordMatches(edits, "甲BB丙丁戊己庚辛"));
  assert.ok(!qubaguRecordMatches(edits, "甲乙丙丁戊己庚辛"));
  assert.ok(qubaguRecordMatches(edits.map(edit => ({ ...edit, applied: !edit.applied })), "甲乙丙丁戊己G辛"));
  assert.ok(qubaguRecordMatches(edits.map(edit => ({ ...edit, applied: false })), "甲乙丙丁戊己庚辛"));
});

test("malformed records are ignored", () => {
  const h = javaStringHash(CURRENT);
  for (const qubagu of [null, 1, "x", {}, { h, edits: [] }, { h, edits: [null] }, { h, edits: [{ s: "1", b: "乙", a: "B", r: "x" }] }, { h, edits: [{ s: 1, b: "乙" }] }]) {
    assert.deepEqual(qubaguRecordsOfParts([{ type: "text", text: CURRENT, metadata: { qubagu } }]), [], JSON.stringify(qubagu));
  }
  assert.deepEqual(qubaguRecordsOfParts([{ type: "text", text: CURRENT, metadata: null }]), []);
  assert.deepEqual(qubaguRecordsOfParts([{ type: "image", metadata: { qubagu: WIRE } }]), []);
});

test("replacement and quota word inputs", () => {
  assert.deepEqual(parseReplacements(" 顿了顿 / 沉默片刻//顿了顿 / "), ["顿了顿", "沉默片刻"]);
  assert.deepEqual(parseReplacements("  "), []);
  assert.equal(parseReplacements(Array.from({ length: 40 }, (_, i) => `词${i}`).join("/")).length, 24);
  assert.deepEqual(parseQuotaWords("微微、缓缓，轻轻, 淡淡  微微\n悄悄"), ["微微", "缓缓", "轻轻", "淡淡", "悄悄"]);
  assert.deepEqual(parseQuotaWords(" "), []);
});

test("an edit is shown on one line", () => {
  assert.equal(qubaguOnOneLine("\n\n而属于他们的故事，才刚刚开始。"), "而属于他们的故事，才刚刚开始。");
  assert.equal(qubaguOnOneLine("七步。\n五步。\n\n三步。"), "七步。 ↵ 五步。 ↵ 三步。");
  assert.equal(qubaguOnOneLine(""), "");
});

test("rule labels and names", () => {
  assert.equal(qubaguRuleLabelKey("merge"), "qubagu.rule_label.merge");
  assert.equal(qubaguRuleLabelKey("ai"), "qubagu.rule_label.ai");
  assert.equal(qubaguRuleLabelKey("expression/phys_pupil_quake"), "qubagu.rule_label.deleted");
  const rule = { kind: "replace", id: "phys_pupil_quake", name: "眼神地震", sample: "", find: "瞳孔", regex: false, replacements: [], dialogue: false, keepFirst: false, atEnd: false, enabled: true, refinePrompt: "" } as const;
  const config = {
    packs: [
      { id: "expression", name: "神态", description: "", enabled: true, rules: [rule, { ...rule, id: "unnamed", name: "" }] },
      { id: "other", name: "", description: "", enabled: true, rules: [] },
    ],
  } as unknown as QubaguConfig;
  assert.equal(qubaguRuleName("expression/phys_pupil_quake", config), "眼神地震");
  assert.equal(qubaguRuleName("expression/unnamed", config), "unnamed");
  assert.equal(qubaguRuleName("other/phys_pupil_quake", config), null);
  assert.equal(qubaguRuleName("expression/gone", config), null);
  assert.equal(qubaguRuleName("phys_pupil_quake", config), null);
  assert.equal(qubaguRuleName("merge", config), null);
  assert.equal(qubaguRuleName("expression/phys_pupil_quake", null), null);
});

test("packs move and change in place", () => {
  const packs = ["a", "b", "c"].map(id => newRulePack(id, ""));
  assert.deepEqual(movePack(packs, packs[0].id, 1).map(p => p.name), ["b", "a", "c"]);
  assert.deepEqual(movePack(packs, packs[2].id, -2).map(p => p.name), ["c", "a", "b"]);
  assert.equal(movePack(packs, packs[0].id, -1), packs);
  assert.equal(movePack(packs, "missing", 1), packs);
  const renamed = mapPack(packs, packs[1].id, pack => ({ ...pack, name: "B" }));
  assert.deepEqual(renamed.map(p => p.name), ["a", "B", "c"]);
  assert.equal(renamed[0], packs[0]);
});

test("new rules match the server's defaults", () => {
  const replace = newReplaceRule();
  const quota = newQuotaRule();
  assert.notEqual(replace.id, newReplaceRule().id);
  assert.deepEqual({ ...replace, id: "" }, {
    kind: "replace", id: "", name: "", sample: "", find: "", regex: true, replacements: [],
    dialogue: false, keepFirst: false, atEnd: false, enabled: true, refinePrompt: "",
  });
  assert.deepEqual({ ...quota, id: "" }, { kind: "quota", id: "", name: "", words: [], max: 2, enabled: true, refinePrompt: "" });
  assert.equal(parseQuotaWords(Array.from({ length: 80 }, (_, i) => `词${i}`).join(" ")).length, 60);
});
