/**
 * 创造助手的提案面板（网页端）：排序与状态规则、文案两种语言键齐全、按钮在初始状态下的渲染。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/creator-proposals.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import { CreatorProposalsButton } from "../app/components/creator-proposals";
import {
  CREATOR_ASSISTANT_ID,
  type CreatorProposal,
  isCreatorConversation,
  orderProposals,
  pendingProposals,
  shortPath,
  statusKey,
  summarizeApproval,
} from "../app/lib/creator-proposals";
import en from "../app/locales/en-US/common.json";
import zh from "../app/locales/zh-CN/common.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  defaultNS: "common",
  resources: { "en-US": { common: en } },
  interpolation: { escapeValue: false },
});

const NOW = 1_000_000;

function proposal(id: string, status: string, createdAt: number, expiresAt = NOW + 60_000): CreatorProposal {
  return {
    id, conversationId: "c", status, risk: "medium", requiresEnhancedConfirm: false, domains: ["QUICK_MESSAGE"],
    createdAt, expiresAt, digest: `d-${id}`, diff: [], omittedDiffLines: 0, createdIds: {},
  };
}

test("the creator conversation is recognised by the fixed assistant id", () => {
  assert.equal(CREATOR_ASSISTANT_ID, "c0ae7a11-7a3d-4b2e-9f1a-2c4e5d6b7a90");
  assert.ok(isCreatorConversation(CREATOR_ASSISTANT_ID));
  assert.ok(!isCreatorConversation("other"));
  assert.ok(!isCreatorConversation(null));
});

test("pending proposals come first, oldest first; the rest newest first", () => {
  const list = [
    proposal("applied-old", "applied", 1),
    proposal("pending-new", "pending_approval", 30),
    proposal("rejected-new", "rejected", 40),
    proposal("pending-old", "pending_approval", 10),
  ];
  assert.deepEqual(orderProposals(list, NOW).map((p) => p.id), ["pending-old", "pending-new", "rejected-new", "applied-old"]);
  assert.deepEqual(pendingProposals(list, NOW).map((p) => p.id), ["pending-new", "pending-old"]);
});

test("a pending proposal past its lifetime reads as expired and is not waiting for the user", () => {
  const lapsed = proposal("late", "pending_approval", 1, NOW - 1);
  assert.equal(statusKey(lapsed, NOW), "expired");
  assert.deepEqual(pendingProposals([lapsed], NOW), []);
  assert.equal(statusKey(proposal("x", "applied", 1), NOW), "applied");
  assert.equal(statusKey(proposal("x", "something_new", 1), NOW), "other");
});

test("diff paths are shortened for display", () => {
  assert.equal(
    shortPath("settings.lorebooks.0d3b2c1e-1111-2222-3333-444455556666.entries[3].content"),
    "lorebooks.…6666.entries[3].content",
  );
  assert.equal(shortPath("conversation[abc].title"), "conversation[abc].title");
});

test("an approve-all summary counts what was applied and reports the first error", () => {
  const summary = summarizeApproval([
    { proposalId: "1", status: "applied" },
    { proposalId: "2", status: "conflict" },
    { error: "boom" },
  ]);
  assert.deepEqual(summary, { applied: 1, failed: 2, firstError: "boom" });
});

test("both languages have every key, and a label for every management domain and status the server can send", () => {
  const keys = (obj: unknown, prefix = ""): string[] =>
    Object.entries(obj as Record<string, unknown>).flatMap(([key, value]) =>
      value && typeof value === "object" ? keys(value, `${prefix}${key}.`) : [`${prefix}${key}`],
    );
  assert.deepEqual(keys(zh.creator_proposals).sort(), keys(en.creator_proposals).sort());
  for (const domain of ["QUICK_MESSAGE", "MODE_INJECTION", "WORLD_BOOK", "ASSISTANT", "MODEL_AND_PROMPT_DEFAULTS", "SUBAGENT_PRESET", "USER_SKILL", "CONVERSATION"]) {
    assert.ok((en.creator_proposals.domain as Record<string, string>)[domain], `domain ${domain}`);
  }
  for (const status of ["pending_approval", "approved", "applying", "applied", "rejected", "expired", "cancelled", "conflict", "failed", "needs_recovery", "other"]) {
    assert.ok((en.creator_proposals.status as Record<string, string>)[status], `status ${status}`);
  }
});

test("the proposals button renders before anything is loaded", () => {
  const html = renderToStaticMarkup(createElement(CreatorProposalsButton, { conversationId: "c" }));
  assert.match(html, /Proposals/);
  assert.match(html, /aria-label="Review proposals"/);
});
