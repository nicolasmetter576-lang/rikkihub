import test from "node:test";
import assert from "node:assert/strict";

import { parsePartialJsonObject } from "../app/lib/partial-json";

// Same cases as the App's PartialToolInputTest.
test("every prefix of an arguments object parses and text only grows", () => {
  const text = '第一章 "引号" 与\\反斜杠\n换行\t制表 🔥 结尾';
  const full = `{"path":"/workspace/write/a.txt","text":${JSON.stringify(text)},"n":12,"ok":true,"none":null}`;
  let previous = "";
  for (let end = 0; end <= full.length; end++) {
    const current = String(parsePartialJsonObject(full.slice(0, end)).text ?? "");
    assert.ok(current.startsWith(previous), `text must grow at ${end}: '${previous}' -> '${current}'`);
    previous = current;
  }
  assert.equal(previous, text);
  assert.equal(parsePartialJsonObject(full).path, "/workspace/write/a.txt");
});

test("dangling key, colon, comma, escape and unicode escape are dropped", () => {
  assert.deepEqual(parsePartialJsonObject('{"path":"/a","te'), { path: "/a" });
  assert.deepEqual(parsePartialJsonObject('{"path":"/a",'), { path: "/a" });
  assert.equal(parsePartialJsonObject('{"text":"ab\\').text, "ab");
  assert.equal(parsePartialJsonObject('{"text":"ab\\u00').text, "ab");
  assert.equal(parsePartialJsonObject('{"text":"é').text, "é");
  assert.deepEqual(parsePartialJsonObject('{"path":"/a","text":'), { path: "/a" });
  assert.equal(parsePartialJsonObject('{"path":"/a","text":"').text, "");
  assert.deepEqual(parsePartialJsonObject('{"n":1'), { n: 1 });
  assert.deepEqual(parsePartialJsonObject('{"ok":tr'), { ok: null });
});

test("blank and unparsable input give an empty object", () => {
  assert.deepEqual(parsePartialJsonObject(""), {});
  assert.deepEqual(parsePartialJsonObject("not json"), {});
  assert.deepEqual(parsePartialJsonObject("[1,2"), {});
});
