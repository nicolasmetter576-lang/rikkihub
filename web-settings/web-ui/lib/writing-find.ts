import { StateEffect, StateField, type Extension } from "@codemirror/state";
import { Decoration, EditorView, type DecorationSet } from "@codemirror/view";

/**
 * Find for the workspace editor, the same plain-text rule as the App's find bar: non-overlapping matches,
 * optional case folding, at most [FIND_LIMIT] of them. The panel owns the query; the editor only draws the
 * highlights it is given (all matches tinted, the current one strong).
 */
export const FIND_LIMIT = 10_000;
/** Far-away matches of a huge result set are left plain: thousands of marks would slow every redraw. */
const MAX_HIGHLIGHTED = 1_000;

export function findMatches(text: string, query: string, caseSensitive: boolean, limit = FIND_LIMIT): number[] {
  if (!query) return [];
  const haystack = caseSensitive ? text : text.toLowerCase();
  const needle = caseSensitive ? query : query.toLowerCase();
  // Lower-casing can change lengths for a few scripts; fall back to exact matching then.
  if (haystack.length !== text.length || needle.length !== query.length) return findMatches(text, query, true, limit);
  const starts: number[] = [];
  let at = haystack.indexOf(needle);
  while (at >= 0 && starts.length < limit) {
    starts.push(at);
    at = haystack.indexOf(needle, at + needle.length);
  }
  return starts;
}

/** The match to show after the matches changed: the first one at or after [anchor], else the first. */
export function findCurrent(matches: number[], anchor: number): number {
  if (!matches.length) return -1;
  const index = matches.findIndex(start => start >= anchor);
  return index >= 0 ? index : 0;
}

export interface FindHighlight {
  matches: number[];
  length: number;
  current: number;
}

const EMPTY: FindHighlight = { matches: [], length: 0, current: -1 };
export const setFindHighlight = StateEffect.define<FindHighlight | null>();

const findField = StateField.define<FindHighlight>({
  create: () => EMPTY,
  update(value, tr) {
    for (const effect of tr.effects) if (effect.is(setFindHighlight)) return effect.value ?? EMPTY;
    if (!tr.docChanged || !value.matches.length) return value;
    // Until the panel recomputes, keep the matches the edit did not touch where they moved to.
    const matches: number[] = [];
    let current = -1;
    value.matches.forEach((start, i) => {
      let hit = false;
      tr.changes.iterChangedRanges((fromA, toA) => {
        if (fromA < start + value.length && toA > start) hit = true;
      });
      if (hit) return;
      if (i === value.current) current = matches.length;
      matches.push(tr.changes.mapPos(start, 1));
    });
    return { matches, length: value.length, current };
  },
  provide: field => EditorView.decorations.from(field, value => findDecorations(value)),
});

const matchMark = Decoration.mark({ class: "cm-find-match" });
const currentMark = Decoration.mark({ class: "cm-find-current" });

function findDecorations({ matches, length, current }: FindHighlight): DecorationSet {
  if (!matches.length || length <= 0) return Decoration.none;
  let first = 0;
  let last = matches.length;
  if (matches.length > MAX_HIGHLIGHTED) {
    first = Math.max(0, current - MAX_HIGHLIGHTED / 2);
    last = Math.min(matches.length, first + MAX_HIGHLIGHTED);
  }
  const ranges = [];
  for (let i = first; i < last; i++) ranges.push((i === current ? currentMark : matchMark).range(matches[i], matches[i] + length));
  return Decoration.set(ranges, true);
}

export function writingFind(dark: boolean): Extension {
  return [
    findField,
    EditorView.theme({
      ".cm-find-match": { backgroundColor: dark ? "rgba(250, 204, 21, 0.28)" : "rgba(250, 204, 21, 0.4)", borderRadius: "2px" },
      ".cm-find-current": { backgroundColor: dark ? "#ca8a04" : "#f59e0b", color: dark ? "#0a0a0a" : "inherit", borderRadius: "2px" },
    }, { dark }),
  ];
}
