import assert from "node:assert/strict";
import { test } from "node:test";

import { turnBlockSelectable, turnCards, turnPicks, type BookTurnSummary, type TurnFile, type TurnReview } from "../app/overlay/lib/book";

function turn(messageId: string, finishedAt: number, extra: Partial<BookTurnSummary> = {}): BookTurnSummary {
  return {
    conversationId: "c",
    messageId,
    finishedAt,
    status: "PENDING",
    files: 1,
    deleted: 0,
    onlyCreated: false,
    reverted: 0,
    keys: ["k1"],
    cleaned: false,
    ...extra,
  };
}

const node = (...ids: string[]) => ({ messages: ids.map((id) => ({ id })), selectIndex: ids.length - 1 });

test("only the latest pending reply gets the full row; older ones get a small line", () => {
  const cards = turnCards([turn("m1", 1), turn("m2", 2)], false, [node("m1"), node("m2")]);
  assert.equal(cards.get("m2")?.full, true);
  assert.equal(cards.get("m1")?.full, false);
});

test("a muted conversation shows only small lines, except turns that deleted files", () => {
  const cards = turnCards([turn("m1", 1, { deleted: 1 }), turn("m2", 2)], true, [node("m1"), node("m2")]);
  assert.equal(cards.get("m2")?.full, false);
  assert.equal(cards.get("m1")?.full, true);
});

test("a turn that only created files is a small line; archived turns and other branches show nothing", () => {
  const cards = turnCards(
    [turn("m1", 1, { status: "ARCHIVED" }), turn("m2", 2, { onlyCreated: true }), turn("hidden", 3)],
    false,
    [node("m1"), node("hidden", "m2")],
  );
  assert.equal(cards.has("m1"), false);
  assert.equal(cards.get("m2")?.full, false);
  // 不在当前分支上的回复不算“最新一条”，也不显示
  assert.equal(cards.has("hidden"), false);
  assert.equal(cards.get("m2")?.sibling?.messageId, "hidden");
});

test("a regenerated reply points at the latest pending sibling that touched the same files", () => {
  const cards = turnCards(
    [turn("old", 1), turn("older", 0), turn("other", 3, { keys: ["k9"] }), turn("new", 4)],
    false,
    [node("older", "old", "other", "new")],
  );
  assert.equal(cards.get("new")?.sibling?.messageId, "old");
});

test("picks mark untouched files done and reject whole created or deleted files by block 0", () => {
  const files: TurnFile[] = [
    { key: "a", path: "a.md", kind: "changed", revision: "r", before: "b", after: "f" },
    { key: "n", path: "n.md", kind: "created", revision: "r2" },
    { key: "d", path: "d.md", kind: "deleted", gone: true },
    { key: "x", path: "x.md", kind: "changed", done: true },
  ];
  const review: TurnReview = { conversationId: "c", messageId: "m", finishedAt: 1, status: "PENDING", reverted: 0, files };
  const picks = turnPicks(review, { a: [1, 2], n: [0] });
  assert.deepEqual(
    picks.map((pick) => [pick.key, pick.reject, pick.whole]),
    [
      ["a", [1, 2], false],
      ["n", [], true],
      ["d", [], false],
    ],
  );
});

test("only pending blocks of reviewable files can be toggled", () => {
  const block = { index: 0, old: ["a"], new: ["b"], state: "pending" as const };
  const changed: TurnFile = { key: "a", path: "a.md", kind: "changed" };
  assert.equal(turnBlockSelectable(changed, block), true);
  assert.equal(turnBlockSelectable({ ...changed, chapterLocked: true }, block), false);
  assert.equal(turnBlockSelectable({ ...changed, whole: true }, block), false);
  assert.equal(turnBlockSelectable(changed, { ...block, state: "changed_later" }), false);
  // 新建的文件之后又改过也能拒绝；删掉的文件已经放回了就不能再选
  assert.equal(turnBlockSelectable({ ...changed, kind: "created" }, { ...block, state: "changed_later" }), true);
  assert.equal(turnBlockSelectable({ ...changed, kind: "deleted", gone: false }, block), false);
  assert.equal(turnBlockSelectable({ ...changed, kind: "deleted", gone: true, unrecoverable: true }, block), false);
});
