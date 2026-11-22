/**
 * 输入框模型选择面板的搜索：按空格拆成多个关键词，忽略大小写，每个都要命中显示名或模型 ID，不要求顺序。
 *
 * 运行：node --import tsx --import ./tests/helpers/register-css-stub.mjs --test tests/model-list-search.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";

import { modelMatchesKeywords } from "../app/components/input/model-list";

const sonnet = { modelId: "relay-a/claude-sonnet", displayName: "relay-a/claude-sonnet" };
const renamed = { modelId: "relay-b/claude-opus", displayName: "Opus" };
const gpt = { modelId: "gpt-mini", displayName: "GPT Mini" };
const models = [sonnet, renamed, gpt];
const search = (query: string) => models.filter((model) => modelMatchesKeywords(model, query));

test("关键词不分顺序", () => {
  assert.deepEqual(search("claude sonnet"), [sonnet]);
  assert.deepEqual(search("sonnet claude"), [sonnet]);
});

test("每个关键词都要命中", () => {
  assert.deepEqual(search("claude mini"), []);
});

test("忽略大小写和多余空格", () => {
  assert.deepEqual(search("  CLAUDE   sonnet  "), [sonnet]);
});

test("改过名的模型仍能按模型 ID 找到，关键词可以分别命中 ID 和显示名", () => {
  assert.deepEqual(search("relay-b claude"), [renamed]);
  assert.deepEqual(search("relay-b opus"), [renamed]);
});

test("空查询保留全部", () => {
  assert.deepEqual(search(""), models);
  assert.deepEqual(search("   "), models);
});
