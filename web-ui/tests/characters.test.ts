import assert from "node:assert/strict";
import test from "node:test";

import {
  AVATAR_MAX_BYTES,
  CHARACTER_FILE_MAX_BYTES,
  CHARACTER_PAGE_SIZE,
  QUICK_CHARACTER_LIMIT,
  characterExportHref,
  checkAvatarFile,
  checkCharacterFile,
  inOrder,
  keepOrder,
  pageOf,
  presetExportHref,
  quickCharacters,
  sortCharacters,
  tagsToText,
  textToTags,
} from "../app/overlay/lib/characters";

const file = (size: number, type = "") => ({ size, type }) as unknown as File;

test("tags are split on commas, trimmed and de-duplicated in order", () => {
  assert.deepEqual(textToTags(" harbor, maps ,harbor,,，night "), ["harbor", "maps", "night"]);
  assert.deepEqual(textToTags(""), []);
  assert.deepEqual(textToTags(" , ，"), []);
  assert.equal(tagsToText(["a", "b"]), "a, b");
  assert.deepEqual(textToTags(tagsToText(["x y", "z"])), ["x y", "z"]);
});

test("favourites sort first, then names without regard to case", () => {
  const list = [
    { id: "1", name: "tobias", fav: false },
    { id: "2", name: "Zed", fav: true },
    { id: "3", name: "Ines", fav: false },
    { id: "4", name: "adam", fav: true },
  ];
  assert.deepEqual(sortCharacters(list).map((c) => c.id), ["4", "2", "3", "1"]);
  // the input is left alone
  assert.deepEqual(list.map((c) => c.id), ["1", "2", "3", "4"]);
});

test("files are checked by size before they are sent", () => {
  assert.equal(checkCharacterFile(file(0)), "empty");
  assert.equal(checkCharacterFile(file(1024)), "ok");
  assert.equal(checkCharacterFile(file(CHARACTER_FILE_MAX_BYTES)), "ok");
  assert.equal(checkCharacterFile(file(CHARACTER_FILE_MAX_BYTES + 1)), "too_large");
  assert.equal(checkAvatarFile(file(10, "text/html")), "not_image");
  assert.equal(checkAvatarFile(file(10, "")), "not_image");
  assert.equal(checkAvatarFile(file(AVATAR_MAX_BYTES + 1, "image/png")), "too_large");
  assert.equal(checkAvatarFile(file(AVATAR_MAX_BYTES, "image/webp")), "ok");
});

test("download addresses carry the id and format", () => {
  assert.equal(characterExportHref("abc", "charx"), "/api/characters/abc/export?format=charx");
  assert.equal(presetExportHref("a b"), "/api/assistants/a%20b/export-preset");
});

test("the quick starts are the favourites only, in list order, and stop at the limit", () => {
  const many = Array.from({ length: 300 }, (_, i) => ({
    id: String(i),
    name: `c${String(i).padStart(3, "0")}`,
    fav: i % 5 === 0,
  }));
  const quick = quickCharacters(many);
  assert.equal(quick.length, QUICK_CHARACTER_LIMIT);
  assert.ok(quick.every((c) => c.fav));
  assert.deepEqual(quick.slice(0, 3).map((c) => c.name), ["c000", "c005", "c010"]);
  assert.equal(quickCharacters(many, 2).length, 2);
  // no favourite, nothing to show; the input is left alone
  assert.deepEqual(quickCharacters([{ id: "1", name: "a", fav: false }]), []);
  assert.deepEqual(quickCharacters([]), []);
  assert.equal(many[1].name, "c001");
});

test("the picker draws a page at a time and never shows less than one page", () => {
  const list = Array.from({ length: 300 }, (_, i) => i);
  assert.equal(CHARACTER_PAGE_SIZE, 40);
  assert.equal(pageOf(list, 1).length, 40);
  assert.equal(pageOf(list, 2).length, 80);
  assert.equal(pageOf(list, 8).length, 300);
  assert.equal(pageOf(list, 99).length, 300);
  assert.equal(pageOf(list, 0).length, 40);
  assert.equal(pageOf(list, -3).length, 40);
  assert.deepEqual(pageOf([1, 2, 3], 1), [1, 2, 3]);
  assert.deepEqual(pageOf([], 1), []);
  // the page boundary is where the next page starts
  assert.equal(pageOf(list, 2)[40], 40);
});

test("a star does not move the rows; a new search or a different set of characters does", () => {
  const a = { id: "a", name: "A", fav: false };
  const b = { id: "b", name: "B", fav: false };
  const c = { id: "c", name: "C", fav: false };
  const first = keepOrder(sortCharacters([a, b, c]), "", null);
  assert.deepEqual(first.ids, ["a", "b", "c"]);

  // C is starred: the sorted list now has it first, the order taken before is kept (same object)
  const starred = sortCharacters([a, b, { ...c, fav: true }]);
  assert.deepEqual(starred.map((x) => x.id), ["c", "a", "b"]);
  const kept = keepOrder(starred, "", first);
  assert.equal(kept, first);
  const shown = inOrder(kept.ids, starred);
  assert.deepEqual(shown.map((x) => x.id), ["a", "b", "c"]);
  assert.equal(shown[2].fav, true, "the row shows the new star");

  // another search word, or a character added or removed: a new order from the list
  assert.notEqual(keepOrder(starred, "ines", first), first);
  assert.deepEqual(keepOrder(starred, "ines", first).ids, ["c", "a", "b"]);
  assert.deepEqual(keepOrder(sortCharacters([a, b]), "", first).ids, ["a", "b"]);
});

test("ordering by ids skips what is gone and puts what is new last", () => {
  const items = [{ id: "b" }, { id: "x" }];
  assert.deepEqual(inOrder(["a", "b"], items).map((x) => x.id), ["b", "x"]);
  assert.deepEqual(inOrder(["a"], []), []);
});
