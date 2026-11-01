import test from "node:test";
import assert from "node:assert/strict";
import { EditorState, EditorSelection, Compartment, type Transaction } from "@codemirror/state";
import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { GFM } from "@lezer/markdown";
import { ensureSyntaxTree } from "@codemirror/language";
import { history, undo, redo, undoDepth } from "@codemirror/commands";
import { markdownStyleRanges, markdownStylesDegraded, MARKDOWN_SEMANTIC_LIMIT, markdownSemanticTheme, markdownSizeTheme } from "../app/overlay/lib/workspace-markdown-styles";
import { formatWorkspaceSource } from "../app/overlay/lib/workspace-source-format";
import { readFileSync } from "node:fs";

const fixture = "### 中文 😀 **bold *italic*** ~~gone~~ `code`\n\n> - [x] task\n> 1. ordered\n\n![alt](https://remote/img.png) [link](url)\n\n```js\nconst x = '<img>'\n```\n\nTitle\n=====\n\n| a | b |\n| - | - |\n| x | y |\n\n\\*escaped\\*\n";
function stateFor(doc: string) { const state = EditorState.create({ doc, extensions: [markdown({ base: markdownLanguage, extensions: GFM })] }); ensureSyntaxTree(state, state.doc.length, 1000); return state; }

test("existing CM Markdown parser styles UTF-16 offsets without source transformation", () => {
  const state = stateFor(fixture); const ranges = markdownStyleRanges(state, 0, state.doc.length);
  for (const r of ranges) { assert.ok(r.from >= 0 && r.to <= fixture.length && r.to >= r.from); }
  assert.equal(state.doc.toString(), fixture);
  const strong = ranges.find(r => r.className === "md-strong")!;
  assert.equal(fixture.slice(strong.from, strong.to), "**bold *italic***");
  const italic = ranges.find(r => r.className === "md-emphasis")!;
  assert.equal(fixture.slice(italic.from, italic.to), "*italic*");
  const strike = ranges.find(r => r.className === "md-strike")!;
  assert.equal(fixture.slice(strike.from, strike.to), "~~gone~~");
  const markers = ranges.filter(r => r.className === "md-marker").map(r => fixture.slice(r.from, r.to));
  for (const marker of ["###", "**", "*", "~~", "`", ">", "-", "[x]", "!", "```", "====="]) assert.ok(markers.some(m => m.includes(marker)), marker);
  for (const kind of ["md-fence", "md-quote", "md-list", "md-task", "md-image-source", "md-link", "md-table"]) assert.ok(ranges.some(r => r.className === kind), kind);
});

test("GFM table: header line, dimmed pipes and delimiter row, cells keep inline styles", () => {
  const doc = "| **h** | i |\n| :-- | --: |\n| x | `y` |\n";
  const state = stateFor(doc); const ranges = markdownStyleRanges(state, 0, state.doc.length);
  const lineClasses = (cls: string) => ranges.filter(r => r.line && r.className === cls).map(r => state.doc.lineAt(r.from).number);
  assert.deepEqual(lineClasses("md-table-header"), [1]);
  assert.deepEqual(lineClasses("md-table"), [1, 2, 3]);
  const markers = ranges.filter(r => r.className === "md-marker").map(r => doc.slice(r.from, r.to));
  assert.ok(markers.includes("|"));
  assert.ok(markers.some(m => m.includes(":--")), "delimiter row is dimmed");
  assert.ok(ranges.some(r => r.className === "md-strong" && doc.slice(r.from, r.to) === "**h**"));
  assert.ok(ranges.some(r => r.className === "md-code" && doc.slice(r.from, r.to) === "`y`"));
});

test("viewport clipping bounds fence line decorations, no per-selection full doc scan", () => {
  const doc = '```\n' + 'code\n'.repeat(5000) + '```'; const state = stateFor(doc);
  const from = state.doc.line(100).from, to = state.doc.line(105).to;
  const ranges = markdownStyleRanges(state, from, to);
  const lines = ranges.filter(r => r.line);
  assert.ok(lines.length <= 6); assert.ok(lines.every(r => r.from >= from && r.from <= to));
  const src = readFileSync(new URL('../../web-settings/web-ui/lib/workspace-markdown-styles.ts', import.meta.url), 'utf8');
  assert.ok(!src.includes('selectionSet'));
  assert.ok(!/Decoration\.(replace|widget)|fetch\(/.test(src));
});

test("explicit large-document source degradation (workspace 512K/device 1M), font sizes only opt-in", () => {
  assert.equal(markdownStylesDegraded('x'.repeat(MARKDOWN_SEMANTIC_LIMIT)), false);
  for (const size of [MARKDOWN_SEMANTIC_LIMIT + 1, 512 * 1024, 1024 * 1024]) assert.equal(markdownStylesDegraded('x'.repeat(size)), true);
  const src = readFileSync(new URL('../../web-settings/web-ui/components/workspace/workspace-source-editor.tsx', import.meta.url), 'utf8');
  assert.match(src, /workspaceMarkdownHeadingSizes === true/);
  assert.match(src, /if \(sizes\) result.push\(markdownSizeTheme\)/);
  assert.match(src, /semantic && !degraded && isMarkdownFileName/);
  const styles = readFileSync(new URL('../../web-settings/web-ui/lib/workspace-markdown-styles.ts', import.meta.url), 'utf8');
  assert.ok(!styles.slice(styles.indexOf('markdownSemanticTheme'), styles.indexOf('markdownSizeTheme')).includes('fontSize'));
});

test("toolbar edits and typing share CM undo/redo; styling/size/tab reconfiguration never enters history", () => {
  const display = new Compartment();
  let state = EditorState.create({ doc: 'body', selection: EditorSelection.range(0, 4), extensions: [history(), display.of([markdown({ base: markdownLanguage }), markdownSemanticTheme])] });
  const target = { get state() { return state; }, dispatch(transaction: Transaction) { state = transaction.state; } };
  state = state.update(formatWorkspaceSource(state, 'bold')).state;
  assert.equal(state.doc.toString(), '**body**'); assert.equal(undoDepth(state), 1);
  const selection = state.selection;
  // Equivalent extension transitions used by pure source, styled source, preview/settings.
  for (const extension of [[], [markdown({ base: markdownLanguage }), markdownSemanticTheme], [markdown({ base: markdownLanguage }), markdownSemanticTheme, markdownSizeTheme]]) {
    const update = state.update({ effects: display.reconfigure(extension) });
    assert.equal(update.docChanged, false); state = update.state;
    assert.equal(undoDepth(state), 1); assert.deepEqual(state.selection.toJSON(), selection.toJSON());
  }
  assert.equal(undo(target), true); assert.equal(state.doc.toString(), 'body');
  assert.equal(redo(target), true); assert.equal(state.doc.toString(), '**body**');
  state = state.update({ changes: { from: 4, insert: 'X' } }).state;
  assert.equal(undo(target), true); assert.equal(state.doc.toString(), '**body**');
});
