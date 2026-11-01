/**
 * 排版 for the workspace editor: a line-for-line port of the App's `WorkspaceWritingFormat.kt`. Both run the
 * cases in `app/src/test/resources/writing/format-cases.json`, so the two editors format identically.
 *
 * Paragraphs are lines: in plain text every non-blank line, in Markdown only prose lines — code fences, front
 * matter, math blocks, headings, lists (and their continuation lines), quotes, tables, HTML blocks, link
 * definitions, rules and indented code stay as they are. A command touches the lines the selection covers,
 * or the whole document without a selection, and is ONE edit (one undo step).
 */

export type FormatCommand = "blank_line_between" | "remove_blank_lines" | "indent" | "cjk_spacing";
export type FormatKind = "markdown" | "plain";

export const FORMAT_COMMANDS: FormatCommand[] = ["blank_line_between", "remove_blank_lines", "indent", "cjk_spacing"];

/** Two ideographic spaces: Markdown would turn four ASCII spaces into a code block. */
export const FORMAT_INDENT = "\u3000\u3000";

export function formatKindOf(fileName?: string): FormatKind | null {
  const ext = (fileName ?? "").toLowerCase().split(".").pop() ?? "";
  if (!fileName?.includes(".")) return null;
  if (ext === "md" || ext === "markdown") return "markdown";
  if (ext === "txt" || ext === "text") return "plain";
  return null;
}

/** The changed span only (common prefix and suffix trimmed), plus where the selection goes. */
export interface FormatEdit {
  from: number;
  to: number;
  insert: string;
  selectionStart: number;
  selectionEnd: number;
}

interface Line {
  start: number;
  text: string;
  ending: string;
  /** The line's number in the original text (-1 for a line a command inserted). */
  index: number;
}

type LineKind = "blank" | "prose" | "heading" | "list" | "quote" | "table" | "verbatim";
interface LineClass {
  kind: LineKind;
  /** Lines that must stay together (one list, one quote, one fence…); null = a line of its own. */
  block: number | null;
}

const SPACED: Record<LineKind, boolean> = {
  blank: false, prose: true, heading: true, list: true, quote: true, table: true, verbatim: false,
};

export function applyFormat(source: string, selectionStart: number, selectionEnd: number, command: FormatCommand, kind: FormatKind): FormatEdit | null {
  const lines = splitLines(source);
  const kinds = classify(lines.map(line => line.text), kind);
  const start = clamp(Math.min(selectionStart, selectionEnd), 0, source.length);
  const end = clamp(Math.max(selectionStart, selectionEnd), start, source.length);
  let first = 0;
  let last = lines.length - 1;
  if (start !== end) {
    // A selection ending at column zero does not take in the following line.
    const endAt = source[end - 1] === "\n" ? end - 1 : end;
    first = lineIndexAt(lines, start);
    last = lineIndexAt(lines, endAt);
  }
  const newline = source.includes("\r\n") ? "\r\n" : "\n";
  let out: Line[];
  switch (command) {
    case "blank_line_between": out = blankLineBetween(lines, kinds, first, last, newline); break;
    case "remove_blank_lines": out = removeBlankLines(lines, kinds, first, last); break;
    case "indent":
      out = lines.map((line, i) => i >= first && i <= last && kinds[i].kind === "prose" ? { ...line, text: indent(line.text) } : line);
      break;
    case "cjk_spacing":
      out = lines.map((line, i) => i >= first && i <= last && SPACED[kinds[i].kind] ? { ...line, text: cjkSpacing(line.text, kind) } : line);
      break;
  }
  const result = out.map(line => line.text + line.ending).join("");
  if (result === source) return null;
  let prefix = 0;
  const maxPrefix = Math.min(source.length, result.length);
  while (prefix < maxPrefix && source[prefix] === result[prefix]) prefix++;
  let suffix = 0;
  while (suffix < Math.min(source.length, result.length) - prefix &&
    source[source.length - 1 - suffix] === result[result.length - 1 - suffix]) suffix++;
  const insert = result.slice(prefix, result.length - suffix);
  let selStart: number;
  let selEnd: number;
  if (start !== end) {
    selStart = lines[first].start;
    selEnd = lines[last].start + lines[last].text.length + (result.length - source.length);
  } else {
    selStart = selEnd = mapCaret(lines, out, start);
  }
  return { from: prefix, to: source.length - suffix, insert, selectionStart: selStart, selectionEnd: selEnd };
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function splitLines(source: string): Line[] {
  const lines: Line[] = [];
  let at = 0;
  for (;;) {
    const nl = source.indexOf("\n", at);
    if (nl < 0) {
      lines.push({ start: at, text: source.slice(at), ending: "", index: lines.length });
      return lines;
    }
    const cr = nl > at && source[nl - 1] === "\r";
    lines.push({ start: at, text: source.slice(at, cr ? nl - 1 : nl), ending: cr ? "\r\n" : "\n", index: lines.length });
    at = nl + 1;
  }
}

function lineIndexAt(lines: Line[], offset: number): number {
  let lo = 0;
  let hi = lines.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (lines[mid].start <= offset) lo = mid; else hi = mid - 1;
  }
  return lo;
}

/** The caret's line in [out] (or the next kept line when its own was removed), at the same distance from the line end. */
function mapCaret(lines: Line[], out: Line[], caret: number): number {
  const old = lineIndexAt(lines, caret);
  const column = caret - lines[old].start;
  let offset = 0;
  for (const line of out) {
    if (line.index === old) {
      const fromEnd = Math.max(0, lines[old].text.length - column);
      return offset + (column === 0 ? 0 : clamp(line.text.length - fromEnd, 0, line.text.length));
    }
    if (line.index > old) return offset;
    offset += line.text.length + line.ending.length;
  }
  return offset;
}

const LAYOUT_SPACES = new Set([" ", "\t", "\u3000", "\u00A0", "\u000B", "\u000C"]);
const isBlank = (text: string) => [...text].every(c => LAYOUT_SPACES.has(c));

const FENCE = /^ {0,3}(`{3,}|~{3,})/;
const HEADING = /^ {0,3}#{1,6}(?:[ \t]|$)/;
const LIST = /^[ \t]*(?:[-*+]|\d{1,9}[.)])(?:[ \t]|$)/;
const QUOTE = /^ {0,3}>/;
const RULE = /^ {0,3}(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$/;
const SETEXT = /^ {0,3}(?:=+|-+)[ \t]*$/;
const TABLE_DELIMITER = /^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;
const HTML = /^ {0,3}<(?:[A-Za-z][A-Za-z0-9-]*|\/[A-Za-z]|!--|!\[CDATA\[|\?)/;
const LINK_DEFINITION = /^ {0,3}\[[^\]]+\]:/;
const CJK = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Bopomofo}]/u;

function classify(lines: string[], kind: FormatKind): LineClass[] {
  if (kind === "plain") return lines.map(line => ({ kind: isBlank(line) ? "blank" : "prose", block: null }));
  const result: LineClass[] = lines.map(() => ({ kind: "blank", block: null }));
  let block = 0;
  let i = 0;
  // Front matter: `---` on the very first line up to the next `---` / `...`.
  if (lines.length > 0 && lines[0].trimEnd() === "---") {
    let close = -1;
    for (let j = 1; j < lines.length; j++) {
      const t = lines[j].trimEnd();
      if (t === "---" || t === "...") { close = j; break; }
    }
    if (close >= 0) {
      block++;
      for (let j = 0; j <= close; j++) result[j] = { kind: "verbatim", block };
      i = close + 1;
    }
  }
  let listBlock: number | null = null; // open list: indented lines and lazy continuations still belong to it
  while (i < lines.length) {
    const line = lines[i];
    const fence = FENCE.exec(line);
    if (fence) {
      const marker = fence[1];
      block++;
      result[i] = { kind: "verbatim", block };
      let j = i + 1;
      while (j < lines.length) {
        result[j] = { kind: "verbatim", block };
        const trimmed = lines[j].replace(/^ +/, "");
        if (lines[j].length - trimmed.length <= 3 && trimmed.startsWith(marker) &&
          [...trimmed.trimEnd()].every(c => c === marker[0])) break;
        j++;
      }
      listBlock = null;
      i = j + 1;
      continue;
    }
    if (line.trim() === "$$") {
      block++;
      result[i] = { kind: "verbatim", block };
      let j = i + 1;
      while (j < lines.length) {
        result[j] = { kind: "verbatim", block };
        if (lines[j].trim() === "$$") break;
        j++;
      }
      listBlock = null;
      i = j + 1;
      continue;
    }
    const previous: LineClass | undefined = result[i - 1];
    if (isBlank(line)) {
      result[i] = { kind: "blank", block: null };
    } else if (listBlock !== null && (line.startsWith(" ") || line.startsWith("\t")) && !LIST.test(line)) {
      result[i] = { kind: "list", block: listBlock };
    } else if (listBlock !== null && previous?.kind === "list" && !startsBlock(lines, i)) {
      result[i] = { kind: "list", block: listBlock }; // lazy continuation of the item above
    } else if (LIST.test(line) && !RULE.test(line)) {
      const id: number = previous?.kind === "list" ? previous.block! : (listBlock ?? ++block);
      result[i] = { kind: "list", block: id };
      listBlock = id;
      i++;
      continue;
    } else if (SETEXT.test(line) && i > 0 && previous?.kind === "prose") {
      // `Title` + `===` / `---` is a heading: both lines stay together.
      block++;
      result[i - 1] = { kind: "heading", block };
      result[i] = { kind: "verbatim", block };
    } else if (RULE.test(line)) {
      result[i] = { kind: "verbatim", block: null };
    } else if (HEADING.test(line)) {
      result[i] = { kind: "heading", block: null };
    } else if (QUOTE.test(line)) {
      result[i] = { kind: "quote", block: previous?.kind === "quote" ? previous.block : ++block };
    } else if (isTableLine(lines, i) || line.includes("|") && previous?.kind === "table") {
      result[i] = { kind: "table", block: previous?.kind === "table" ? previous.block : ++block };
    } else if (HTML.test(line)) {
      result[i] = {
        kind: "verbatim",
        block: previous?.kind === "verbatim" && previous.block !== null && HTML.test(lines[i - 1]) ? previous.block : ++block,
      };
    } else if (LINK_DEFINITION.test(line)) {
      result[i] = { kind: "verbatim", block: null };
    } else if (isIndentedCode(lines, result, i)) {
      result[i] = {
        kind: "verbatim",
        block: previous?.kind === "verbatim" && previous.block !== null && isIndented(lines[i - 1]) ? previous.block : ++block,
      };
    } else {
      result[i] = { kind: "prose", block: null };
    }
    if (result[i].kind !== "blank" && result[i].kind !== "list") listBlock = null;
    i++;
  }
  return result;
}

/** A line that would start a new block even right under a list item (so it is no lazy continuation). */
function startsBlock(lines: string[], i: number): boolean {
  const line = lines[i];
  return HEADING.test(line) || QUOTE.test(line) || RULE.test(line) || FENCE.test(line) || HTML.test(line) || isTableLine(lines, i);
}

function isTableLine(lines: string[], i: number): boolean {
  const line = lines[i];
  if (!line.includes("|")) return false;
  if (line.trimStart().startsWith("|") || TABLE_DELIMITER.test(line) && line.includes("-")) return true;
  // A header row without the leading pipe: the next line is the delimiter row.
  const next = lines[i + 1];
  return next !== undefined && TABLE_DELIMITER.test(next) && next.includes("|") && next.includes("-");
}

const isIndented = (line: string) => line.startsWith("\t") || line.startsWith("    ");

/** Four spaces / a tab after a blank line or more code, with no CJK in it: Markdown's indented code. */
function isIndentedCode(lines: string[], kinds: LineClass[], i: number): boolean {
  const line = lines[i];
  if (!isIndented(line) || CJK.test(line)) return false;
  const previous = kinds[i - 1];
  if (!previous) return true;
  return previous.kind === "blank" || previous.kind === "verbatim" && isIndented(lines[i - 1]);
}

const separable = (a: LineClass, b: LineClass) => a.block === null || a.block !== b.block;

function blankLineBetween(lines: Line[], kinds: LineClass[], first: number, last: number, newline: string): Line[] {
  const out: Line[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    out.push(line);
    if (i < first || i >= last || kinds[i].kind === "blank") {
      i++;
      continue;
    }
    // Next non-blank line within the range: keep exactly one blank line before it.
    let j = i + 1;
    while (j <= last && kinds[j].kind === "blank") j++;
    if (j > last) {
      i++;
      continue;
    }
    if (!separable(kinds[i], kinds[j]) && j === i + 1) {
      i++;
      continue;
    }
    const ending = line.ending || newline;
    if (j === i + 1) {
      out[out.length - 1] = { ...line, ending };
      out.push({ start: -1, text: "", ending, index: -1 });
    } else {
      out.push({ ...lines[i + 1], text: "" }); // the first blank line stays (emptied of any spaces)
    }
    i = j;
  }
  return out;
}

function removeBlankLines(lines: Line[], kinds: LineClass[], first: number, last: number): Line[] {
  const drop = new Set<number>();
  let i = first;
  while (i <= last) {
    if (kinds[i].kind !== "blank") {
      i++;
      continue;
    }
    let j = i;
    while (j <= last && kinds[j].kind === "blank") j++;
    const before = i - 1;
    if (before >= first && j <= last && kinds[before].kind === "prose" && kinds[j].kind === "prose") {
      for (let k = i; k < j; k++) drop.add(k);
    }
    i = j;
  }
  return lines.filter((_, index) => !drop.has(index));
}

function indent(text: string): string {
  let at = 0;
  while (at < text.length && LAYOUT_SPACES.has(text[at])) at++;
  return FORMAT_INDENT + text.slice(at);
}

// ---- 中英文间加空格 --------------------------------------------------------------------------

/** Java's default `\s` (ASCII only), so both ports cut the same pieces. */
const S = "[ \\t\\n\\x0B\\f\\r]";
/** A web address ends at whitespace, CJK text or full-width punctuation. */
const URL = `(?:(?:https?|ftp)://|www\\.)[^ \\t\\n\\x0B\\f\\r\\u3000-\\u303F\\uFF00-\\uFFEF\\p{Script=Han}\\p{Script=Hiragana}\\p{Script=Katakana}]+`;
const MARKDOWN_PROTECTED = new RegExp(
  "(`+).+?\\1" + // inline code
  `|\\]\\([^)${S.slice(1, -1)}]*(?:${S}+"[^"]*")?\\)` + // link destination
  "|\\]\\[[^\\]]*\\]" + // reference link label
  "|<[A-Za-z/!][^>]*>" + // HTML tag or autolink
  `|\\$[^$${S.slice(1, -1)}](?:[^$]*[^$${S.slice(1, -1)}])?\\$(?!\\d)` + // inline math (not「$5 和 $10」)
  `|${URL}`,
  "gu",
);
const PLAIN_PROTECTED = new RegExp(`${URL}|[\\w.+\\-]+@[\\w\\-]+\\.[A-Za-z][\\w.\\-]*`, "gu");

export function cjkSpacing(text: string, kind: FormatKind): string {
  const pattern = kind === "markdown" ? MARKDOWN_PROTECTED : PLAIN_PROTECTED;
  let out = "";
  // Spaces go inside plain runs and at their borders with a protected piece (「用 https://… 打开」), never inside one.
  const add = (piece: string, spaced: boolean) => {
    if (!piece) return;
    if (out && needsSpace(lastCodePoint(out), piece.codePointAt(0)!)) out += " ";
    out += spaced ? spaceRun(piece) : piece;
  };
  let at = 0;
  pattern.lastIndex = 0;
  for (const match of text.matchAll(pattern)) {
    add(text.slice(at, match.index), true);
    add(match[0], false);
    at = match.index + match[0].length;
  }
  add(text.slice(at), true);
  return out;
}

function lastCodePoint(text: string): number {
  const last = text.charCodeAt(text.length - 1);
  if (last >= 0xdc00 && last <= 0xdfff && text.length > 1) return text.codePointAt(text.length - 2)!;
  return last;
}

function spaceRun(text: string): string {
  if (text.length < 2) return text;
  let out = "";
  let previous = -1;
  for (const char of text) {
    const cp = char.codePointAt(0)!;
    if (previous >= 0 && needsSpace(previous, cp)) out += " ";
    out += char;
    previous = cp;
  }
  return out;
}

const PERCENT = "%".codePointAt(0)!;
const needsSpace = (left: number, right: number) =>
  isCjk(left) && isLatinWord(right) || (isLatinWord(left) || left === PERCENT) && isCjk(right);

const CJK_CHAR = /^[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Bopomofo}]$/u;
export const isCjk = (cp: number) => CJK_CHAR.test(String.fromCodePoint(cp));

const LATIN_LETTER = /^[\p{Script=Latin}\p{Script=Greek}\p{Script=Cyrillic}]$/u;
const LETTER = /^\p{L}$/u;
function isLatinWord(cp: number): boolean {
  if (cp >= 0x41 && cp <= 0x5a || cp >= 0x61 && cp <= 0x7a || cp >= 0x30 && cp <= 0x39) return true;
  const char = String.fromCodePoint(cp);
  return LETTER.test(char) && LATIN_LETTER.test(char);
}
