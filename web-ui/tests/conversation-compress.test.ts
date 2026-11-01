/**
 * 网页端压缩历史：上下文过大判断与 App 一致、错误码文案、提示条在各状态下的渲染。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/conversation-compress.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import {
  COMPRESS_MAX_KEEP_RECENT,
  compressErrorKey,
  stepKeepRecent,
} from "../app/components/compress-dialog";
import { ConversationNotices } from "../app/components/conversation-notices";
import {
  LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD,
  MESSAGE_NODE_WARNING_THRESHOLD,
  conversationSizeInfo,
  lastContextTokens,
} from "../app/lib/conversation-size";
import type { CompressStateDto, MessageNodeDto } from "../app/types";
import en from "../app/locales/en-US/page.json";
import zh from "../app/locales/zh-CN/page.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { page: en } },
  interpolation: { escapeValue: false },
});

function node(role: string, promptTokens?: number): MessageNodeDto {
  return {
    id: crypto.randomUUID(),
    selectIndex: 0,
    messages: [
      {
        id: crypto.randomUUID(),
        role,
        parts: [],
        createdAt: "",
        usage:
          promptTokens == null
            ? null
            : { promptTokens, completionTokens: 0, cachedTokens: 0, totalTokens: 0 },
      },
    ],
  };
}

test("size warning needs both thresholds, like the app", () => {
  const many = Array.from({ length: MESSAGE_NODE_WARNING_THRESHOLD + 1 }, () => node("USER"));
  const big = LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD + 1;

  assert.equal(conversationSizeInfo([...many, node("ASSISTANT", big)]).showWarning, true);
  // Only one of the two conditions: no warning.
  assert.equal(conversationSizeInfo([node("USER"), node("ASSISTANT", big)]).showWarning, false);
  assert.equal(conversationSizeInfo([...many, node("ASSISTANT", 10)]).showWarning, false);
  // The last assistant message counts, not an earlier larger one.
  const info = conversationSizeInfo([
    node("ASSISTANT", big),
    ...many,
    node("ASSISTANT", 5),
    node("USER"),
  ]);
  assert.equal(info.lastAssistantInputTokens, 5);
  assert.equal(info.showWarning, false);
  assert.equal(conversationSizeInfo([]).showWarning, false);
});

test("a checkpoint after the last reply resets the input size until the next reply", () => {
  const many = Array.from({ length: MESSAGE_NODE_WARNING_THRESHOLD + 1 }, () => node("USER"));
  const big = LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD + 1;
  const checkpoint = node("USER");
  checkpoint.messages[0].isContextCheckpoint = true;
  const compressed = conversationSizeInfo([...many, node("ASSISTANT", big), checkpoint]);
  assert.equal(compressed.lastAssistantInputTokens, 0);
  assert.equal(compressed.showWarning, false);
  // The next reply after the checkpoint counts again; multi-step replies use the last request.
  const reply = node("ASSISTANT", 999_999);
  reply.messages[0].lastUsage = { promptTokens: big, completionTokens: 0, cachedTokens: 0, totalTokens: big };
  assert.equal(conversationSizeInfo([...many, checkpoint, reply]).lastAssistantInputTokens, big);
});

function stamped(n: MessageNodeDto, createdAt: string, patch: Partial<MessageNodeDto["messages"][0]> = {}) {
  n.messages[0].createdAt = createdAt;
  Object.assign(n.messages[0], patch);
  return n;
}

test("recent messages kept after a checkpoint were generated before it, so their usage is out of date", () => {
  const big = LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD + 1;
  const many = Array.from({ length: MESSAGE_NODE_WARNING_THRESHOLD + 1 }, () => node("USER"));
  const oldReply = stamped(node("ASSISTANT", big), "2026-10-26T10:00:00");
  const checkpoint = stamped(node("USER"), "2026-10-26T11:00:00.5", { isContextCheckpoint: true });
  const keptUser = stamped(node("USER"), "2026-10-26T10:00:01");

  // Checkpoint inserted before the kept reply: the reply's usage still describes the old context.
  const keptReply = stamped(node("ASSISTANT", big), "2026-10-26T10:59:59");
  const afterKeep = [...many, checkpoint, keptUser, keptReply];
  assert.equal(conversationSizeInfo(afterKeep).lastAssistantInputTokens, 0);
  assert.equal(conversationSizeInfo(afterKeep).showWarning, false);
  assert.equal(lastContextTokens(afterKeep), null);

  // A reply generated after the compression counts again, whatever precedes it.
  const fresh = stamped(node("ASSISTANT", 123), "2026-10-26T11:05:00");
  fresh.messages[0].usage = { promptTokens: 123, completionTokens: 7, cachedTokens: 0, totalTokens: 130 };
  assert.equal(lastContextTokens([...many, oldReply, checkpoint, keptUser, keptReply, fresh]), 130);
  assert.equal(conversationSizeInfo([...many, checkpoint, keptUser, keptReply, fresh]).lastAssistantInputTokens, 123);

  // Without any checkpoint nothing is out of date, and a reply before the checkpoint stays out of date.
  assert.equal(lastContextTokens([oldReply]), big + 0);
  assert.equal(lastContextTokens([oldReply, checkpoint]), null);
  // Missing timestamps are treated as unknown, not as out of date.
  assert.equal(lastContextTokens([checkpoint, node("ASSISTANT", 5)]), 5);
});

test("keep-recent stepper never goes below 0 or above the server bound", () => {
  assert.equal(stepKeepRecent(0, -1), 0);
  assert.equal(stepKeepRecent(0, 1), 1);
  assert.equal(stepKeepRecent(5, -1), 4);
  assert.equal(stepKeepRecent(COMPRESS_MAX_KEEP_RECENT, 1), COMPRESS_MAX_KEEP_RECENT);
});

test("every error code has a message in both languages", () => {
  const codes = [
    "CONVERSATION_BUSY",
    "ALREADY_COMPRESSING",
    "NOTHING_TO_COMPRESS",
    "NOT_ENOUGH_MESSAGES",
    "PENDING_TOOLS",
    "TOOL_CALL_RESPONSE",
    "CONVERSATION_CHANGED",
    "COMPRESS_FAILED",
    undefined,
  ];
  for (const code of codes) {
    const key = compressErrorKey(code).replace("conversations.compress.", "");
    assert.ok((en.conversations.compress as Record<string, string>)[key], `en ${key}`);
    assert.ok((zh.conversations.compress as Record<string, string>)[key], `zh ${key}`);
  }
  assert.deepEqual(
    Object.keys(zh.conversations.compress).sort(),
    Object.keys(en.conversations.compress).sort(),
  );
});

const state = (
  status: CompressStateDto["status"],
  extra: Partial<CompressStateDto> = {},
): CompressStateDto => ({
  conversationId: "c1",
  status,
  origin: "web",
  startedAt: 1,
  ...extra,
});

function renderNotices(props: Partial<Parameters<typeof ConversationNotices>[0]> = {}): string {
  return renderToStaticMarkup(
    createElement(ConversationNotices, {
      compressState: null,
      compressFailure: null,
      sizeInfo: { nodeCount: 10, lastAssistantInputTokens: 0, showWarning: false },
      sizeWarningDismissed: false,
      cancelling: false,
      onCancelCompression: () => {},
      onOpenCompress: () => {},
      onDismissFailure: () => {},
      onDismissSizeWarning: () => {},
      onNewConversation: () => {},
      ...props,
    }),
  );
}

test("running compression shows progress with cancel, and says when the app started it", () => {
  assert.equal(renderNotices(), "");
  const web = renderNotices({ compressState: state("running") });
  assert.match(web, /Compressing history…/);
  assert.match(web, /Cancel compression/);
  assert.match(
    renderNotices({ compressState: state("running", { origin: "app" }) }),
    /The app is compressing/,
  );
});

test("a failure explains the reason and offers retry; the size warning hides while running", () => {
  const failed = renderNotices({
    compressState: state("failed"),
    compressFailure: state("failed", { errorCode: "CONVERSATION_CHANGED" }),
  });
  assert.match(failed, /Compression did not finish/);
  assert.match(failed, /changed during compression/);
  assert.match(failed, /Retry/);

  const big = { nodeCount: 900, lastAssistantInputTokens: 400_000, showWarning: true };
  assert.match(renderNotices({ sizeInfo: big }), /very large/);
  assert.doesNotMatch(renderNotices({ sizeInfo: big, sizeWarningDismissed: true }), /very large/);
  assert.doesNotMatch(
    renderNotices({ sizeInfo: big, compressState: state("running") }),
    /very large/,
  );
});
