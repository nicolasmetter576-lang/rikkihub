import { EditorSelection, Prec, StateEffect, StateField, type EditorState, type Extension, type Transaction, type TransactionSpec } from "@codemirror/state";
import { Decoration, EditorView, WidgetType, keymap, type DecorationSet } from "@codemirror/view";

import { isStatsSpace } from "@overlay/lib/writing-stats";

/**
 * Auto-closing pairs with a fill box, the web editor's version of the App's `WorkspacePairs` rules:
 *
 * - typing an opener puts the closer after the caret (not right before a letter, digit or CJK character,
 *   and a straight quote not right after one) and opens a box; with a selection the pair wraps it;
 * - typing the box's closer right before it steps over it; Backspace in an empty box deletes both;
 * - Enter inside a box moves the caret past its closer instead of breaking the line;
 * - boxes follow typing inside them and disappear when an edit touches a symbol, the caret leaves, or the
 *   text changes any other way (undo, toolbar, replace).
 *
 * Boxes are a stack, outermost first; only the innermost is drawn. Replaces basicSetup's closeBrackets.
 */

export const PAIRS: Readonly<Record<string, string>> = {
  "（": "）", "“": "”", "‘": "’", "「": "」", "『": "』", "《": "》", "〈": "〉",
  "【": "】", "〔": "〕", "〖": "〗", "(": ")", "[": "]", "{": "}", '"': '"', "'": "'",
};
const CLOSERS = new Set(Object.values(PAIRS));

export interface PairBox {
  /** Positions of the opening and closing symbols; the box is the text between them. */
  open: number;
  close: number;
}

const addBox = StateEffect.define<PairBox>();
const dropInnermost = StateEffect.define<null>();

const PUNCTUATION = /^\p{P}$/u;
const LETTER_OR_DIGIT = /^[\p{L}\p{Nd}]$/u;

function mapBoxes(boxes: readonly PairBox[], tr: Transaction): PairBox[] {
  const result: PairBox[] = [];
  for (const box of boxes) {
    let hit = false;
    tr.changes.iterChangedRanges((fromA, toA) => {
      // A change that removes or replaces one of the two symbols ends the box.
      if (fromA < box.open + 1 && toA > box.open || fromA < box.close + 1 && toA > box.close) hit = true;
    });
    if (!hit) result.push({ open: tr.changes.mapPos(box.open, 1), close: tr.changes.mapPos(box.close, 1) });
  }
  return result;
}

/** The boxes still standing: both symbols in place and the selection inside. */
export function validBoxes(state: EditorState, boxes: readonly PairBox[]): PairBox[] {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  return boxes.filter(box =>
    box.open >= 0 && box.close < doc.length && box.open < box.close &&
    PAIRS[doc.sliceString(box.open, box.open + 1)] === doc.sliceString(box.close, box.close + 1) &&
    from > box.open && to <= box.close);
}

export const pairBoxes = StateField.define<PairBox[]>({
  create: () => [],
  update(boxes, tr) {
    let next: PairBox[] = boxes as PairBox[];
    if (tr.docChanged) {
      // Only the user's own typing moves boxes along; any other change (undo, toolbar, replace) ends them.
      next = boxes.length && (tr.isUserEvent("input") || tr.isUserEvent("delete")) ? mapBoxes(boxes, tr) : [];
    }
    for (const effect of tr.effects) {
      if (effect.is(addBox)) next = [...next, effect.value];
      else if (effect.is(dropInnermost)) next = next.slice(0, -1);
    }
    if (!next.length) return next === boxes ? boxes : next;
    const valid = validBoxes(tr.state, next);
    return valid.length === boxes.length && valid.every((box, i) => box.open === boxes[i].open && box.close === boxes[i].close) ? boxes : valid;
  },
  provide: field => EditorView.decorations.from(field, boxes => boxDecorations(boxes)),
});

class SlotWidget extends WidgetType {
  eq() {
    return true;
  }
  toDOM() {
    const slot = document.createElement("span");
    slot.className = "cm-pair-slot";
    slot.setAttribute("aria-hidden", "true");
    return slot;
  }
  ignoreEvent() {
    return false;
  }
}

function boxDecorations(boxes: readonly PairBox[]): DecorationSet {
  const box = boxes[boxes.length - 1];
  if (!box) return Decoration.none;
  if (box.close === box.open + 1) return Decoration.set([Decoration.widget({ widget: new SlotWidget(), side: 1 }).range(box.close)]);
  return Decoration.set([Decoration.mark({ class: "cm-pair-box" }).range(box.open + 1, box.close)]);
}

function innermostAt(state: EditorState, pos: number): PairBox | undefined {
  const boxes = state.field(pairBoxes, false) ?? [];
  for (let i = boxes.length - 1; i >= 0; i--) if (boxes[i].open < pos && pos <= boxes[i].close) return boxes[i];
  return undefined;
}

function opensHere(state: EditorState, from: number, to: number, opener: string, closer: string): boolean {
  const next = state.doc.sliceString(to, to + 1);
  if (next && !isStatsSpace(next.codePointAt(0)!) && !CLOSERS.has(next) && !PUNCTUATION.test(next)) return false;
  // A straight quote after a letter or digit is an apostrophe or a closing quote.
  if (opener === closer) {
    const previous = state.doc.sliceString(from - 1, from);
    if (previous && LETTER_OR_DIGIT.test(previous)) return false;
  }
  return true;
}

/**
 * What typing [text] over [from, to) becomes, or null for the default insert. Pure (state in, transaction out)
 * so the rules are tested without a DOM.
 */
export function pairInput(state: EditorState, from: number, to: number, text: string): TransactionSpec | null {
  if (state.readOnly || [...text].length !== 1 || state.selection.ranges.length > 1) return null;
  const selection = state.selection.main;
  const closer = PAIRS[text];
  // A selection replaced by an opener: wrap it.
  if (closer && from !== to && selection.from === from && selection.to === to && !state.doc.sliceString(from, to).includes("\n")) {
    return {
      changes: [{ from, insert: text }, { from: to, insert: closer }],
      selection: EditorSelection.cursor(to + 1),
      effects: addBox.of({ open: from, close: to + 1 }),
      userEvent: "input.type",
      scrollIntoView: true,
    };
  }
  if (from !== to) return null;
  // The box's own closer typed right before it: step over.
  const inner = innermostAt(state, from);
  if (inner && inner.close === from && CLOSERS.has(text) && state.doc.sliceString(from, from + 1) === text) {
    return { selection: EditorSelection.cursor(from + 1), effects: dropInnermost.of(null), userEvent: "select", scrollIntoView: true };
  }
  if (closer && opensHere(state, from, to, text, closer)) {
    return {
      changes: { from, insert: text + closer },
      selection: EditorSelection.cursor(from + 1),
      effects: addBox.of({ open: from, close: from + 1 }),
      userEvent: "input.type",
      scrollIntoView: true,
    };
  }
  return null;
}

/** Enter inside a box leaves it (past its closer) instead of breaking the line. */
export function leaveBox(state: EditorState): TransactionSpec | null {
  const selection = state.selection.main;
  const inner = innermostAt(state, selection.head);
  if (!inner || selection.from <= inner.open) return null;
  return { selection: EditorSelection.cursor(inner.close + 1), effects: dropInnermost.of(null), userEvent: "select", scrollIntoView: true };
}

/** Backspace in an empty box deletes both symbols. */
export function deleteEmptyPair(state: EditorState): TransactionSpec | null {
  const selection = state.selection.main;
  if (!selection.empty) return null;
  const inner = innermostAt(state, selection.head);
  if (!inner || inner.close !== inner.open + 1 || selection.head !== inner.close) return null;
  return { changes: { from: inner.open, to: inner.close + 1 }, userEvent: "delete.backward", scrollIntoView: true };
}

const inputHandler = EditorView.inputHandler.of((view, from, to, text) => {
  if (view.composing) return false;
  const spec = pairInput(view.state, from, to, text);
  if (!spec) return false;
  view.dispatch(spec);
  return true;
});

function command(build: (state: EditorState) => TransactionSpec | null) {
  return (view: EditorView) => {
    const spec = build(view.state);
    if (!spec) return false;
    view.dispatch(spec);
    return true;
  };
}

/** Dark blue outline over a light blue tint (user decision); a brighter outline on dark themes. */
function pairTheme(dark: boolean): Extension {
  const stroke = dark ? "#60a5fa" : "#1e3a8a";
  const fill = dark ? "rgba(30, 58, 138, 0.4)" : "rgba(30, 64, 175, 0.14)";
  const box = { boxShadow: `0 0 0 1.5px ${stroke}`, backgroundColor: fill, borderRadius: "4px" };
  return EditorView.theme({
    ".cm-pair-box": { ...box, boxDecorationBreak: "clone", WebkitBoxDecorationBreak: "clone" },
    // An empty box: a narrow slot centred on the caret, taking no width so the text does not move.
    ".cm-pair-slot": { ...box, display: "inline-block", width: "0.3em", height: "1.15em", margin: "0 -0.15em", verticalAlign: "text-bottom" },
  }, { dark });
}

export function writingPairs(dark: boolean): Extension {
  return [
    pairBoxes,
    inputHandler,
    Prec.highest(keymap.of([{ key: "Enter", run: command(leaveBox) }, { key: "Backspace", run: command(deleteEmptyPair) }])),
    pairTheme(dark),
  ];
}
