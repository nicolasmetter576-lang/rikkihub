import { isCjk } from "@overlay/lib/writing-format";

/**
 * The editor status bar's counts, a port of the App's `workspaceTextStats` (same cases in
 * `app/src/test/resources/writing/stats-cases.json`). chars: code points that are not whitespace. words:
 * every Han / kana character is one word, every run of letters and digits is one (an apostrophe or hyphen
 * inside it, and a `.` or `,` between digits, keep it going); punctuation and symbols are not words.
 */
export interface TextStats {
  chars: number;
  words: number;
}

const LETTER_OR_DIGIT = /^[\p{L}\p{Nd}]$/u;
const DIGIT = /^\p{Nd}$/u;
const MARK = /^\p{M}$/u;

/** Explicit so it matches the App exactly (Java's and JavaScript's whitespace sets differ). */
export function isStatsSpace(cp: number): boolean {
  return cp >= 0x09 && cp <= 0x0d || cp === 0x20 || cp >= 0x1c && cp <= 0x1f || cp === 0x85 || cp === 0xa0 || cp === 0x1680 ||
    cp >= 0x2000 && cp <= 0x200b || cp === 0x2028 || cp === 0x2029 || cp === 0x202f || cp === 0x205f || cp === 0x3000 || cp === 0xfeff;
}

export function textStats(text: string, start = 0, end = text.length): TextStats {
  const slice = text.slice(Math.max(0, start), Math.max(start, Math.min(end, text.length)));
  const chars = [...slice];
  let count = 0;
  let words = 0;
  let inWord = false;
  let previousDigit = false;
  for (let i = 0; i < chars.length; i++) {
    const char = chars[i];
    const cp = char.codePointAt(0)!;
    if (isStatsSpace(cp)) {
      inWord = false;
    } else {
      count++;
      if (isCjk(cp)) {
        words++;
        inWord = false;
      } else if (LETTER_OR_DIGIT.test(char)) {
        if (!inWord) words++;
        inWord = true;
      } else if (inWord && MARK.test(char)) {
        // a combining mark stays in its word
      } else if (inWord && i + 1 < chars.length && isJoiner(char, previousDigit, chars[i + 1])) {
        // don't, well-known, 3.14
      } else {
        inWord = false;
      }
    }
    previousDigit = DIGIT.test(char);
  }
  return { chars: count, words };
}

function isJoiner(char: string, previousDigit: boolean, next: string): boolean {
  if (char === "'" || char === "’" || char === "-") return LETTER_OR_DIGIT.test(next) && !isCjk(next.codePointAt(0)!);
  if (char === "." || char === ",") return previousDigit && DIGIT.test(next);
  return false;
}
