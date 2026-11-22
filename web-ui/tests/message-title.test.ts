import test from "node:test";
import assert from "node:assert/strict";

import { stripMessageTitle, stripTitleFromParts } from "../app/lib/message-title";

// Same cases as the App's MessageTitleTest.
test("title on the last line is stripped, inline at the end too", () => {
  assert.equal(stripMessageTitle("正文第一段。\n\n<rk-title>天气查询</rk-title>\n"), "正文第一段。");
  assert.equal(stripMessageTitle("好的。 <rk-title>问候</rk-title>"), "好的。");
});

test("a tag that is not on the last line is left alone", () => {
  const text = "格式是 <rk-title>标题</rk-title>，写在最后。\n下一行";
  assert.equal(stripMessageTitle(text), text);
  assert.equal(stripMessageTitle(text, true), text);
});

test("half-written tag is hidden only while streaming", () => {
  for (const tail of ["<", "<rk-ti", "<rk-title>", "<rk-title>部分标", "<rk-title>部分</rk-"]) {
    const text = `回复正文\n${tail}`;
    assert.equal(stripMessageTitle(text, true), "回复正文", tail);
    assert.equal(stripMessageTitle(text), text, tail);
  }
});

test("other angle brackets on the last line survive streaming", () => {
  assert.equal(stripMessageTitle("a < b", true), "a < b");
  assert.equal(stripMessageTitle("<div>x</div>", true), "<div>x</div>");
});

test("only assistant text parts are stripped", () => {
  const parts = [{ type: "text" as const, text: "hi\n<rk-title>t</rk-title>" }];
  assert.equal((stripTitleFromParts(parts, "ASSISTANT")[0] as { text: string }).text, "hi");
  assert.equal(stripTitleFromParts(parts, "USER"), parts);
});
