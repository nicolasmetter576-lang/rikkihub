import { EditorSelection, type EditorState, type TransactionSpec, Transaction } from "@codemirror/state";
export type SourceFormat = "bold" | "italic" | "strike" | "heading" | "list" | "quote" | "task" | "link" | "code";
const wraps: Partial<Record<SourceFormat, [string, string]>> = {
  bold: ["**", "**"], italic: ["*", "*"], strike: ["~~", "~~"], link: ["[", "](url)"], code: ["`", "`"],
};
const prefixes: Partial<Record<SourceFormat, string>> = { heading: "### ", list: "- ", quote: "> ", task: "- [ ] " };
/** Source transactions, shared with normal CodeMirror typing/history. */
export function formatWorkspaceSource(state: EditorState, format: SourceFormat): TransactionSpec {
  const { from, to } = state.selection.main;
  const wrap = wraps[format];
  if (wrap) {
    const selected = state.doc.sliceString(from, to);
    return { changes: { from, to, insert: wrap[0] + selected + wrap[1] },
      selection: EditorSelection.range(from + wrap[0].length, to + wrap[0].length),
      annotations: Transaction.userEvent.of("input.format"), scrollIntoView: true };
  }
  const prefix = prefixes[format]!;
  const start = state.doc.lineAt(from).from;
  return { changes: { from: start, insert: prefix },
    selection: EditorSelection.range(from + prefix.length, to + prefix.length),
    annotations: Transaction.userEvent.of("input.format"), scrollIntoView: true };
}
