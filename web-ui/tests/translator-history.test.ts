import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { mergeTranslatorHistory, type TranslatorHistoryItem } from "../app/overlay/lib/translator";

function item(id: string, createAt: number): TranslatorHistoryItem {
  return {
    id,
    sourceText: `src-${id}`,
    translatedText: `dst-${id}`,
    targetLanguage: "en",
    modelId: "m",
    createAt,
  };
}

describe("mergeTranslatorHistory", () => {
  it("appends the next page and skips ids already shown", () => {
    // 两次取页之间又新增了一条，第二页的第一条与第一页的最后一条重复
    const first = [item("c", 3), item("b", 2)];
    const second = [item("b", 2), item("a", 1)];
    assert.deepEqual(
      mergeTranslatorHistory(first, second).map((entry) => entry.id),
      ["c", "b", "a"],
    );
  });

  it("keeps the current list when the page is empty", () => {
    const current = [item("x", 1)];
    assert.deepEqual(mergeTranslatorHistory(current, []), current);
  });
});
