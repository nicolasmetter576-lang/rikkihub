import { StateEffect, StateField, type EditorState, type Extension } from "@codemirror/state";
import { Decoration, EditorView, type DecorationSet } from "@codemirror/view";

/** 段落锁在编辑器里的位置：服务端按读到的稿子给出，之后随编辑映射（改锁定段时竖条跟着走）。 */
export const setLockedRanges = StateEffect.define<ReadonlyArray<{ from: number; to: number }>>();

const lockedMark = Decoration.mark({});
const lockedLine = Decoration.line({ class: "cm-locked-passage" });

interface LockedState {
  marks: DecorationSet;
  lines: DecorationSet;
}

function linesOf(state: EditorState, marks: DecorationSet): DecorationSet {
  const starts = new Set<number>();
  const cursor = marks.iter();
  while (cursor.value) {
    const first = state.doc.lineAt(cursor.from).number;
    const last = state.doc.lineAt(Math.max(cursor.from, cursor.to - 1)).number;
    for (let n = first; n <= last; n++) starts.add(state.doc.line(n).from);
    cursor.next();
  }
  return Decoration.set([...starts].sort((a, b) => a - b).map((from) => lockedLine.range(from)));
}

const lockedField = StateField.define<LockedState>({
  create: () => ({ marks: Decoration.none, lines: Decoration.none }),
  update(value, tr) {
    let marks = value.marks.map(tr.changes);
    let changed = tr.docChanged;
    for (const effect of tr.effects) {
      if (!effect.is(setLockedRanges)) continue;
      const length = tr.state.doc.length;
      marks = Decoration.set(
        effect.value
          .filter((range) => range.from < range.to && range.to <= length)
          .map((range) => lockedMark.range(range.from, range.to)),
        true,
      );
      changed = true;
    }
    return changed ? { marks, lines: linesOf(tr.state, marks) } : value;
  },
  provide: (field) => EditorView.decorations.from(field, (value) => value.lines),
});

/** 左侧 3px 竖条（不用底色，免得和选区、查找、八股的底色混淆）。 */
const lockedTheme = EditorView.baseTheme({
  ".cm-locked-passage": { boxShadow: "inset 3px 0 0 color-mix(in srgb, currentColor 35%, transparent)" },
});

export const lockedPassages: Extension = [lockedField, lockedTheme];

/** [from, to) 碰不碰锁定段落（推敲、再造、节拍换稿应用前检查）。 */
export function touchesLocked(state: EditorState, from: number, to: number): boolean {
  const value = state.field(lockedField, false);
  if (!value) return false;
  let hit = false;
  value.marks.between(from, Math.max(to, from + 1), () => {
    hit = true;
    return false;
  });
  return hit;
}
