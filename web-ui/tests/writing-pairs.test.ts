import test from "node:test";
import assert from "node:assert/strict";

import { EditorSelection, EditorState } from "@codemirror/state";

import { deleteEmptyPair, leaveBox, pairBoxes, pairInput, type PairBox } from "../app/overlay/lib/writing-pairs";

/** A tiny editor: the same rules as the App's `WorkspacePairsTest`, driven through CodeMirror state only. */
class Editor {
  constructor(public state: EditorState) {}

  static of(text: string, anchor = text.length, head = anchor) {
    return new Editor(EditorState.create({ doc: text, selection: EditorSelection.single(anchor, head), extensions: [pairBoxes] }));
  }

  get text() { return this.state.doc.toString(); }
  get caret() { return this.state.selection.main.head; }
  get boxes(): PairBox[] { return this.state.field(pairBoxes); }

  /** Typing goes through the pair rules first, like the input handler; otherwise a plain insert. */
  type(chars: string): Editor {
    for (const char of chars) {
      const { from, to } = this.state.selection.main;
      if (char === "\n") {
        const spec = leaveBox(this.state);
        this.state = this.state.update(spec ?? { changes: { from, to, insert: "\n" }, selection: { anchor: from + 1 }, userEvent: "input" }).state;
        continue;
      }
      const spec = pairInput(this.state, from, to, char);
      this.state = this.state.update(spec ?? { changes: { from, to, insert: char }, selection: { anchor: from + char.length }, userEvent: "input.type" }).state;
    }
    return this;
  }

  backspace(): Editor {
    const spec = deleteEmptyPair(this.state);
    const head = this.caret;
    this.state = this.state.update(spec ?? { changes: { from: head - 1, to: head }, userEvent: "delete.backward" }).state;
    return this;
  }

  moveTo(at: number): Editor {
    this.state = this.state.update({ selection: { anchor: at }, userEvent: "select" }).state;
    return this;
  }
}

test("pairs: opener closes, text fills the box, Enter jumps out", () => {
  const e = Editor.of("他说：").type("（");
  assert.equal(e.text, "他说：（）");
  assert.equal(e.caret, 4);
  assert.deepEqual(e.boxes, [{ open: 3, close: 4 }]);
  e.type("好的");
  assert.equal(e.text, "他说：（好的）");
  assert.deepEqual(e.boxes, [{ open: 3, close: 6 }]);
  e.type("\n");
  assert.equal(e.text, "他说：（好的）");
  assert.equal(e.caret, 7);
  assert.deepEqual(e.boxes, []);
  assert.equal(e.type("\n").text, "他说：（好的）\n");
});

test("pairs: closer steps over, Backspace in an empty box removes both", () => {
  const stepped = Editor.of("").type("「」");
  assert.equal(stepped.text, "「」");
  assert.equal(stepped.caret, 2);
  assert.equal(Editor.of("").type("《").backspace().text, "");
  const filled = Editor.of("").type("《书").backspace();
  assert.equal(filled.text, "《》");
  assert.deepEqual(filled.boxes, [{ open: 0, close: 1 }]);
});

test("pairs: nested boxes leave from the inside out", () => {
  const e = Editor.of("").type("「『");
  assert.equal(e.text, "「『』」");
  assert.deepEqual(e.boxes, [{ open: 0, close: 3 }, { open: 1, close: 2 }]);
  e.type("书名\n");
  assert.equal(e.caret, 5);
  assert.deepEqual(e.boxes, [{ open: 0, close: 5 }]);
  e.type("\n");
  assert.equal(e.caret, 6);
  assert.deepEqual(e.boxes, []);
});

test("pairs: a selection gets wrapped", () => {
  const e = Editor.of("说了abc。", 2, 5).type("“");
  assert.equal(e.text, "说了“abc”。");
  assert.equal(e.caret, 6);
  assert.deepEqual(e.boxes, [{ open: 2, close: 6 }]);
});

test("pairs: no closer before a word, no straight quote after a letter", () => {
  assert.equal(Editor.of("abc", 0).type("(").text, "(abc");
  assert.equal(Editor.of("中文", 0).type("「").text, "「中文");
  assert.equal(Editor.of("don").type("'").text, "don'");
  assert.equal(Editor.of("say ").type('"').text, 'say ""');
  assert.equal(Editor.of("，", 0).type("（").text, "（），");
  assert.equal(Editor.of("「」", 1).type("（").text, "「（）」");
  assert.equal(Editor.of("").type('"hi"').text, '"hi"');
});

test("pairs: leaving the box or another edit ends it", () => {
  const e = Editor.of("前").type("（字");
  assert.deepEqual(e.boxes, [{ open: 1, close: 3 }]);
  assert.deepEqual(e.moveTo(2).boxes, [{ open: 1, close: 3 }]);
  assert.deepEqual(e.moveTo(0).boxes, []);
  const edited = Editor.of("前").type("（字");
  // Not the user's typing (undo, toolbar, replace): boxes end.
  edited.state = edited.state.update({ changes: { from: 0, insert: "x" }, userEvent: "format" }).state;
  assert.deepEqual(edited.boxes, []);
  // Typing before the box moves it along.
  const moved = Editor.of("前").type("（字");
  moved.state = moved.state.update({ changes: { from: 0, insert: "ab" }, userEvent: "input.type" }).state;
  assert.deepEqual(moved.boxes, [{ open: 3, close: 5 }]);
});
