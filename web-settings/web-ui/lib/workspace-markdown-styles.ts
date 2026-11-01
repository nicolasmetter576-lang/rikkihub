import { syntaxTree } from "@codemirror/language";
import { Decoration, EditorView, ViewPlugin, type DecorationSet, type ViewUpdate } from "@codemirror/view";
import type { EditorState } from "@codemirror/state";

// Styling only. Every offset is the parser's UTF-16 source offset; never serialize a tree,
// replace a marker, insert a widget, fetch an image, or dispatch a document transaction.
export interface MarkdownStyleRange { from: number; to: number; className: string; line?: boolean }
const inline: Record<string, string> = {
  StrongEmphasis: "md-strong", Emphasis: "md-emphasis", Strikethrough: "md-strike",
  InlineCode: "md-code", Link: "md-link", Image: "md-image-source", URL: "md-url",
};
const blocks: Record<string, string> = {
  FencedCode: "md-fence", CodeBlock: "md-fence", Blockquote: "md-quote",
  ListItem: "md-list", Task: "md-task", HorizontalRule: "md-rule", Table: "md-table",
  TableHeader: "md-table-header",
};
export function markdownStyleRanges(state: EditorState, from: number, to: number): MarkdownStyleRange[] {
  const ranges: MarkdownStyleRange[] = [];
  const lines = new Set<string>();
  syntaxTree(state).iterate({ from, to, enter(node) {
    const name = node.name;
    if (inline[name]) ranges.push({ from: node.from, to: node.to, className: inline[name] });
    // TableDelimiter covers both the `|` separators and the `| --- | :-: |` row (Markor/Obsidian dim them).
    if (name.endsWith("Mark") || name === "TaskMarker" || name === "CodeInfo" || name === "TableDelimiter") {
      ranges.push({ from: node.from, to: node.to, className: "md-marker" });
    }
    const heading = /^(?:ATX|Setext)Heading([1-6])$/.exec(name);
    const block = heading ? `md-heading md-h${heading[1]}` : blocks[name];
    if (block) {
      let pos = state.doc.lineAt(Math.max(from, node.from)).from;
      const end = Math.min(to, node.to);
      while (pos <= end) {
        const key = `${pos}:${block}`;
        if (!lines.has(key)) { lines.add(key); ranges.push({ from: pos, to: pos, className: block, line: true }); }
        const line = state.doc.lineAt(pos);
        if (line.to >= state.doc.length) break;
        pos = line.to + 1;
      }
    }
  } });
  return ranges;
}
function decorations(view: EditorView): DecorationSet {
  const ranges = view.visibleRanges.flatMap(({ from, to }) => markdownStyleRanges(view.state, from, to));
  return Decoration.set(ranges.filter(r => r.line || r.to > r.from).map(r => r.line
    ? Decoration.line({ class: r.className }).range(r.from)
    : Decoration.mark({ class: r.className }).range(r.from, r.to)), true);
}
export const workspaceMarkdownStyles = ViewPlugin.fromClass(class {
  decorations: DecorationSet;
  constructor(view: EditorView) { this.decorations = decorations(view); }
  update(update: ViewUpdate) {
    // Selection changes do not parse or rebuild decorations.
    if (update.docChanged || update.viewportChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) {
      this.decorations = decorations(update.view);
    }
  }
}, { decorations: plugin => plugin.decorations });

export const markdownSemanticTheme = EditorView.baseTheme({
  ".md-heading": { fontWeight: "700" },
  ".md-strong": { fontWeight: "700" },
  ".md-emphasis": { fontStyle: "italic" },
  ".md-strike": { textDecoration: "line-through" },
  ".md-code, .md-fence": { fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", backgroundColor: "color-mix(in oklab, var(--muted) 70%, transparent)" },
  ".md-quote": { borderLeft: "3px solid var(--primary)", backgroundColor: "color-mix(in oklab, var(--muted) 40%, transparent)" },
  ".md-list": { borderLeft: "1px dotted var(--muted-foreground)" },
  ".md-task": { backgroundColor: "color-mix(in oklab, var(--primary) 6%, transparent)" },
  ".md-link, .md-url": { color: "var(--primary)", textDecoration: "underline" },
  ".md-image-source": { backgroundColor: "color-mix(in oklab, var(--primary) 10%, transparent)", borderRadius: "3px" },
  ".md-rule": { color: "var(--muted-foreground)" },
  ".md-table": { fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace" },
  ".md-table-header": { fontWeight: "700", backgroundColor: "color-mix(in oklab, var(--primary) 10%, transparent)" },
  ".md-marker": { color: "#2563eb", fontWeight: "400", fontStyle: "normal", textDecoration: "none" },
  "&dark .md-marker": { color: "#93c5fd" },
});
export const markdownSizeTheme = EditorView.theme({
  ".md-h1": { fontSize: "1.5em" }, ".md-h2": { fontSize: "1.3em" }, ".md-h3": { fontSize: "1.15em" },
  ".md-code, .md-fence": { fontSize: "0.9em" },
});
// Bounded, explicit plain-source fallback well below API read limits. Avoid parser startup
// on documents near the 512KiB workspace / 1MiB device limits (UTF-16 length is O(1)).
export const MARKDOWN_SEMANTIC_LIMIT = 128 * 1024;
export function markdownStylesDegraded(value: string): boolean { return value.length > MARKDOWN_SEMANTIC_LIMIT; }
