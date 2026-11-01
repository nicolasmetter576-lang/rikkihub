import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { applyFormat, formatKindOf, FORMAT_COMMANDS, type FormatCommand } from "../app/overlay/lib/writing-format";
import { textStats } from "../app/overlay/lib/writing-stats";

/** The App's unit tests run the same files (`WorkspaceWritingFormatTest`, `WorkspaceWritingStatsTest`). */
const fixture = (name: string) => JSON.parse(readFileSync(new URL(`../../app/src/test/resources/writing/${name}`, import.meta.url), "utf8"));

interface FormatCase {
  name: string;
  kind: "markdown" | "plain";
  command: FormatCommand;
  source: string;
  selection?: [number, number];
  expected: string;
}

test("format: the cases shared with the App", () => {
  const cases = fixture("format-cases.json") as FormatCase[];
  assert.ok(cases.length >= 20);
  for (const c of cases) {
    assert.ok(FORMAT_COMMANDS.includes(c.command), c.name);
    const [start, end] = c.selection ?? [0, 0];
    const edit = applyFormat(c.source, start, end, c.command, c.kind);
    if (c.expected === c.source) {
      assert.equal(edit, null, c.name);
    } else {
      assert.ok(edit, c.name);
      assert.equal(c.source.slice(0, edit.from) + edit.insert + c.source.slice(edit.to), c.expected, c.name);
    }
  }
});

test("format: caret stays on its line, a selection keeps covering the formatted lines", () => {
  const source = "一\n二\n三";
  const caret = source.indexOf("二") + 1;
  const edit = applyFormat(source, caret, caret, "indent", "plain")!;
  const result = source.slice(0, edit.from) + edit.insert + source.slice(edit.to);
  assert.equal(result, "　　一\n　　二\n　　三");
  assert.equal(result.slice(result.indexOf("\n") + 1, edit.selectionStart), "　　二");
  const removed = applyFormat("一\n\n二", 2, 2, "remove_blank_lines", "plain")!;
  assert.equal(removed.selectionStart, "一\n".length);
  const lines = "零\n一\n二\n三";
  const selected = applyFormat(lines, 2, 5, "indent", "plain")!;
  const after = lines.slice(0, selected.from) + selected.insert + lines.slice(selected.to);
  assert.equal(after.slice(selected.selectionStart, selected.selectionEnd), "　　一\n　　二");
});

test("format: offered for Markdown and plain text only", () => {
  assert.equal(formatKindOf("a.MD"), "markdown");
  assert.equal(formatKindOf("a.markdown"), "markdown");
  assert.equal(formatKindOf("a.txt"), "plain");
  assert.equal(formatKindOf("a.text"), "plain");
  assert.equal(formatKindOf("a.srt"), null);
  assert.equal(formatKindOf("README"), null);
  assert.equal(formatKindOf(undefined), null);
});

test("stats: the cases shared with the App", () => {
  for (const c of fixture("stats-cases.json") as { text: string; chars: number; words: number }[]) {
    assert.deepEqual(textStats(c.text), { chars: c.chars, words: c.words }, JSON.stringify(c.text));
  }
  const text = "前文 hello 世界 后文";
  const start = text.indexOf("hello");
  assert.deepEqual(textStats(text, start, text.indexOf("世界") + 2), { chars: 7, words: 3 });
});

test("find: non-overlapping matches, optional case folding, the current match follows", async () => {
  const { findMatches, findCurrent } = await import("../app/overlay/lib/writing-find");
  assert.deepEqual(findMatches("aaaa", "aa", false), [0, 2]);
  assert.deepEqual(findMatches("Cat cat", "cat", false), [0, 4]);
  assert.deepEqual(findMatches("Cat cat", "cat", true), [4]);
  assert.deepEqual(findMatches("abc", "", false), []);
  assert.equal(findMatches("x".repeat(10), "x", true, 3).length, 3);
  assert.equal(findCurrent([2, 8, 15], 8), 1);
  assert.equal(findCurrent([2, 8, 15], 9), 2);
  assert.equal(findCurrent([2, 8, 15], 99), 0);
  assert.equal(findCurrent([], 0), -1);
});
