import test from "node:test";
import assert from "node:assert/strict";

import { bookExportHref, insertionChanges, movedOrder, newChapterPath, type BookItem } from "../app/overlay/lib/book";

const items: BookItem[] = [
  { type: "chapter", id: "a", title: "a", path: "a.md" },
  { type: "volume", id: "v", title: "v" },
  { type: "chapter", id: "b", title: "b", path: "卷二/b.md" },
];

test("movedOrder: 与邻项交换，到头为 null", () => {
  assert.deepEqual(movedOrder(items, "a", 1), ["v", "a", "b"]);
  assert.deepEqual(movedOrder(items, "b", -1), ["a", "b", "v"]);
  assert.equal(movedOrder(items, "a", -1), null);
  assert.equal(movedOrder(items, "b", 1), null);
  assert.equal(movedOrder(items, "gone", 1), null);
});

test("newChapterPath: 放进最后一章的文件夹，缺扩展名补 .md", () => {
  assert.equal(newChapterPath(items, " 第三章 "), "卷二/第三章.md");
  assert.equal(newChapterPath(items, "第三章.TXT"), "卷二/第三章.TXT");
  assert.equal(newChapterPath([], "第1.5章"), "第1.5章.md");
});

test("bookExportHref: 省掉没选的范围，章节用逗号连", () => {
  const base = { format: "txt", finalOnly: false, volumeTitles: true, indent: true, blankLines: false } as const;
  assert.equal(
    bookExportHref("w", base),
    "/api/workspaces/w/book/export?format=txt&finalOnly=false&volumeTitles=true&indent=true&blankLines=false",
  );
  assert.match(bookExportHref("w", { ...base, chapters: ["a", "b"] }), /chapters=a%2Cb$/);
});

test("insertionChanges: 依次插入换成对原文的一组改动", () => {
  const apply = (text: string, items: Array<{ offset: number; text: string }>) =>
    items.reduce((current, item) => current.slice(0, item.offset) + item.text + current.slice(item.offset), text);
  const once = (text: string, changes: Array<{ from: number; insert: string }>) =>
    [...changes].reverse().reduce((current, c) => current.slice(0, c.from) + c.insert + current.slice(c.from), text);
  const cases: Array<[string, Array<{ offset: number; text: string }>]> = [
    ["abc", [{ offset: 3, text: "X" }, { offset: 4, text: "Y" }]],
    ["abc", [{ offset: 1, text: "XX" }, { offset: 5, text: "Y" }]],
    ["abc", [{ offset: 2, text: "XX" }, { offset: 0, text: "Y" }]],
    ["abcdef", [{ offset: 4, text: "QQQ" }, { offset: 1, text: "W" }, { offset: 6, text: "E" }, { offset: 2, text: "R" }]],
    ["", [{ offset: 0, text: "X" }, { offset: 1, text: "Y" }]],
  ];
  for (const [text, items] of cases) {
    const insertions = items.map((item, i) => ({ beat: String(i), block: "", ...item }));
    const changes = insertionChanges(insertions, text.length);
    assert.equal(once(text, changes), apply(text, items));
    assert.equal(new Set(changes.map((c) => c.from)).size, changes.length);
  }
});
