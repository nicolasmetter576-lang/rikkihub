/**
 * Web 添加模型时按型号自动识别能力（后端 GET /providers/model-capabilities）：
 * 识别结果怎么套到模型草稿上，以及什么时候该去识别。
 *
 * 运行：node --import tsx --import ./tests/helpers/register-css-stub.mjs --test tests/model-capabilities.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  applyModelCapabilities,
  newProviderModel,
  shouldDetectCapabilities,
} from "../app/overlay/lib/settings";
import type { ModelCapabilities } from "../app/overlay/types/settings";

const vision: ModelCapabilities = {
  inputModalities: ["TEXT", "IMAGE"],
  outputModalities: ["TEXT"],
  abilities: ["TOOL", "REASONING"],
  contextLength: 200000,
};

test("识别结果替换模态与能力，已知窗口替换上下文长度，其余字段不动", () => {
  const draft = { ...newProviderModel(), modelId: "acme-turbo", displayName: "我的名字", contextLength: 1_000_000 };
  const next = applyModelCapabilities(draft, vision);
  assert.deepEqual(next.inputModalities, ["TEXT", "IMAGE"]);
  assert.deepEqual(next.outputModalities, ["TEXT"]);
  assert.deepEqual(next.abilities, ["TOOL", "REASONING"]);
  assert.equal(next.contextLength, 200000);
  assert.equal(next.id, draft.id);
  assert.equal(next.displayName, "我的名字");
  assert.equal(next.modelId, "acme-turbo");
  assert.equal(draft.contextLength, 1_000_000, "不修改传入的对象");
});

test("窗口未知时保持当前上下文长度", () => {
  const draft = { ...newProviderModel(), modelId: "x", contextLength: 32000 };
  assert.equal(applyModelCapabilities(draft, { ...vision, contextLength: null }).contextLength, 32000);
  const untouched = newProviderModel();
  assert.equal("contextLength" in applyModelCapabilities(untouched, { ...vision, contextLength: null }), false);
});

test("不合法的窗口值被忽略", () => {
  const draft = { ...newProviderModel(), contextLength: 32000 };
  for (const bad of [0, -1, 1.5, 100_000_001, Number.NaN]) {
    assert.equal(applyModelCapabilities(draft, { ...vision, contextLength: bad }).contextLength, 32000, String(bad));
  }
  assert.equal(applyModelCapabilities(draft, { ...vision, contextLength: 100_000_000 }).contextLength, 100_000_000);
});

test("只在新建模型、有型号 id、且用户没手动改过时识别", () => {
  assert.equal(shouldDetectCapabilities(true, "gpt-4o", false), true);
  assert.equal(shouldDetectCapabilities(true, "  gpt-4o  ", false), true);
  assert.equal(shouldDetectCapabilities(false, "gpt-4o", false), false, "编辑已有模型不覆盖");
  assert.equal(shouldDetectCapabilities(true, "", false), false);
  assert.equal(shouldDetectCapabilities(true, "   ", false), false);
  assert.equal(shouldDetectCapabilities(true, "gpt-4o", true), false, "手动改过就不再覆盖");
});
