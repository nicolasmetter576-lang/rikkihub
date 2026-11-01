/**
 * 助手记忆面板：路径与大小校验和服务端一致；范围标签、草稿切换全局记忆时的提示；做梦状态文案。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/assistant-memory-panel.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import {
  AssistantMemoryPanel,
  MEMORY_FILE_MAX_BYTES,
  dreamSummary,
  memoryContentError,
  memoryDirectoryOf,
  normalizeMemoryPath,
} from "../app/overlay/components/settings/assistant-memory-panel";
import en from "../app/overlay/locales/en-US/settings.json";
import zh from "../app/overlay/locales/zh-CN/settings.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { settings: en } },
  interpolation: { escapeValue: false },
});
const t = i18next.getFixedT("en-US", "settings");

test("paths follow the server rule: absolute .md paths without empty or hidden segments", () => {
  assert.equal(normalizeMemoryPath("topics/food.md"), "/topics/food.md");
  assert.equal(normalizeMemoryPath("  /profile.md "), "/profile.md");
  for (const bad of ["", "/notes.txt", "/a//b.md", "/.hidden.md", "/../x.md", "/a\\b.md", `/${"x".repeat(130)}.md`]) {
    assert.equal(normalizeMemoryPath(bad), null, bad);
  }
});

test("the size limit counts bytes, not characters", () => {
  assert.equal(MEMORY_FILE_MAX_BYTES, 48 * 1024);
  assert.equal(memoryContentError("x".repeat(MEMORY_FILE_MAX_BYTES)), null);
  assert.equal(memoryContentError("x".repeat(MEMORY_FILE_MAX_BYTES + 1)), "too_large");
  // 一个汉字 3 字节
  assert.equal(memoryContentError("记".repeat(MEMORY_FILE_MAX_BYTES / 3 + 1)), "too_large");
  assert.equal(memoryContentError("abcd", 3), "too_large");
});

test("files are grouped by their directory", () => {
  assert.equal(memoryDirectoryOf("/profile.md"), "/");
  assert.equal(memoryDirectoryOf("/topics/food.md"), "/topics");
  assert.equal(memoryDirectoryOf("/people/team/lin.md"), "/people/team");
});

test("the dream line says what is going on", () => {
  const base = { enabled: true, automatic: true, running: false, pendingTurns: 0 };
  assert.equal(dreamSummary({ ...base, running: true }, t), "Dreaming…");
  assert.equal(dreamSummary({ ...base, lastError: "boom" }, t), "Dreaming failed: boom");
  assert.equal(dreamSummary(base, t), "Organizes chats into memory once they pause");
  assert.equal(dreamSummary({ ...base, automatic: false }, t), "Automatic dreaming is off; tap Dream now to organize");
  assert.equal(
    dreamSummary({ ...base, pendingTurns: 2, lastFinishedAt: 1, lastChangedPaths: ["/a.md"], lastDeletedPaths: ["/b.md"] }, t),
    "New turns waiting: 2 · Last dream changed 2 file(s)",
  );
  assert.equal(dreamSummary({ ...base, lastFinishedAt: 1 }, t), "Last dream found nothing new");
});

function render(savedUseGlobal: boolean, draftUseGlobal: boolean): string {
  return renderToStaticMarkup(
    createElement(AssistantMemoryPanel, { assistantId: "a1", savedUseGlobal, draftUseGlobal }),
  );
}

test("scope label follows the saved setting", () => {
  assert.match(render(false, false), /This assistant/);
  assert.match(render(true, true), /Global/);
  assert.doesNotMatch(render(false, false), /After saving/);
});

test("an unsaved global-memory toggle explains that the list switches after saving", () => {
  assert.match(render(false, true), /After saving, this list switches to the global memory store\./);
  assert.match(render(true, false), /After saving, this list switches to this assistant/);
});

test("memory strings exist in both languages", () => {
  assert.deepEqual(Object.keys(zh.assistants.memory).sort(), Object.keys(en.assistants.memory).sort());
  assert.ok(en.assistants.fields.auto_consolidate_memory);
  assert.ok(zh.assistants.fields.auto_consolidate_memory);
});
