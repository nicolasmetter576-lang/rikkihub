import assert from "node:assert/strict";
import { test } from "node:test";

import { EditorState } from "@codemirror/state";

import { lockedPassages, setLockedRanges, touchesLocked } from "../app/overlay/lib/book-locks";

const locked = "画里的女人眉眼低垂，像是在看他。";
const doc = `前文。\n${locked}\n后文。`;
const from = doc.indexOf(locked);

function stateWithLock() {
  const state = EditorState.create({ doc, extensions: [lockedPassages] });
  return state.update({ effects: setLockedRanges.of([{ from, to: from + locked.length }]) }).state;
}

test("a locked passage blocks changes that touch it and lets the rest through", () => {
  const state = stateWithLock();
  assert.equal(touchesLocked(state, from + 2, from + 4), true);
  assert.equal(touchesLocked(state, 0, 2), false);
  assert.equal(touchesLocked(state, from + locked.length + 1, doc.length), false);
});

test("typing before the passage moves the lock with it", () => {
  const moved = stateWithLock().update({ changes: { from: 0, insert: "新加的开头。\n" } }).state;
  const start = moved.doc.toString().indexOf(locked);
  assert.equal(touchesLocked(moved, start + 1, start + 2), true);
  assert.equal(touchesLocked(moved, 0, 3), false);
});

test("ranges past the end of the text are ignored", () => {
  const state = EditorState.create({ doc, extensions: [lockedPassages] })
    .update({ effects: setLockedRanges.of([{ from: 0, to: doc.length + 10 }]) }).state;
  assert.equal(touchesLocked(state, 0, 1), false);
});
