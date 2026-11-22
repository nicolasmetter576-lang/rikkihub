import type { UIMessagePart } from "~/types";

/**
 * Per-turn titles: the model ends a reply with `<rk-title>title</rk-title>` on its last line. The stored text keeps
 * the tag (it goes back to the model as history); the server parses it into `title`. Showing, copying and exporting
 * hide it — same rules as the app's `stripMessageTitle`.
 */
const OPEN = "<rk-title>";
const CLOSE = "</rk-title>";
const TRAILING = /[ \t]*<rk-title>([^\n]*?)<\/rk-title>\s*$/;

/** While [streaming], a half-written tag at the end (`<rk-ti`, `<rk-title>unfinished`) is hidden too. */
export function stripMessageTitle(text: string, streaming = false): string {
  const match = TRAILING.exec(text);
  if (match) return text.slice(0, match.index).trimEnd();
  if (!streaming) return text;
  const lineStart = text.lastIndexOf("\n") + 1;
  const open = text.indexOf(OPEN, lineStart);
  if (open >= 0) {
    return text.indexOf(CLOSE, open) < 0 ? text.slice(0, open).trimEnd() : text;
  }
  const lt = text.lastIndexOf("<");
  return lt >= lineStart && OPEN.startsWith(text.slice(lt)) ? text.slice(0, lt).trimEnd() : text;
}

/** Assistant text parts with the title tag removed; other roles unchanged. */
export function stripTitleFromParts(
  parts: UIMessagePart[],
  role: string,
  streaming = false,
): UIMessagePart[] {
  if (role !== "ASSISTANT") return parts;
  return parts.map((part) =>
    part.type === "text" ? { ...part, text: stripMessageTitle(part.text, streaming) } : part,
  );
}
