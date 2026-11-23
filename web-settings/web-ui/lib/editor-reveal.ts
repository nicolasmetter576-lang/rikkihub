/** 打开文件后要选中的原文：打开前记下，对应的编辑器拿到正文后取走一次。 */
const pending = new Map<string, string>();

export function revealOnOpen(editorKey: string, text: string): void {
  if (text) pending.set(editorKey, text);
}

/** [doc] 里的位置；没有要找的、或还没加载出正文时为 null（后者下次再试）。 */
export function takeReveal(editorKey: string | undefined, doc: string): { from: number; to: number } | null {
  const text = editorKey ? pending.get(editorKey) : undefined;
  if (!editorKey || !text || !doc) return null;
  pending.delete(editorKey);
  const from = doc.indexOf(text);
  return from < 0 ? null : { from, to: from + text.length };
}
