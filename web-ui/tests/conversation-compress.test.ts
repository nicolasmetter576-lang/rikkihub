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
  compressBlockedKey,
  compressErrorKey,
  compressionPreviewRange,
  formatCompressResultMeta,
  stepCarryCount,
  stepKeepRecent,
} from "../app/components/compress-dialog";
import { ConversationNotices } from "../app/components/conversation-notices";
import { ChatMessageCheckpoint } from "../app/components/message/chat-message-checkpoint";
import {
  affectedCheckpointsForMessage,
  laterCheckpointsForCheckpointMessage,
} from "../../web-settings/web-ui/lib/compression";
import {
  LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD,
  MESSAGE_NODE_WARNING_THRESHOLD,
  conversationSizeInfo,
  lastContextTokens,
} from "../app/lib/conversation-size";
import type { CompressStateDto, MessageNodeDto } from "../app/types";
import en from "../app/locales/en-US/page.json";
import zh from "../app/locales/zh-CN/page.json";
import enMessage from "../app/locales/en-US/message.json";
import zhMessage from "../app/locales/zh-CN/message.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { page: en, message: enMessage } },
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

test("new replies with no current usage never fall back to accumulated or older snapshots", () => {
  const many = Array.from({ length: MESSAGE_NODE_WARNING_THRESHOLD + 1 }, () => node("USER"));
  const old = node("ASSISTANT", 400_000);
  const latest = node("ASSISTANT", 900_000);
  for (const usageComplete of [false, true]) {
    latest.messages[0].usageComplete = usageComplete;
    latest.messages[0].lastUsage = null;
    const nodes = [old, ...many, latest];
    assert.equal(lastContextTokens(nodes), null);
    assert.equal(conversationSizeInfo(nodes).showWarning, false);
    assert.equal(conversationSizeInfo(nodes).lastAssistantInputTokens, 0);
  }
  // Explicit legacy null keeps the compatibility fallback; it is not a new missing snapshot.
  latest.messages[0].usageComplete = null;
  assert.equal(lastContextTokens([latest]), 900_000);
  // Earlier incomplete steps do not invalidate an available current logical-step snapshot.
  latest.messages[0].usageComplete = false;
  latest.messages[0].lastUsage = { promptTokens: 100, completionTokens: 30, cachedTokens: 10, totalTokens: 180 };
  assert.equal(lastContextTokens([old, latest]), 130);
  assert.equal(conversationSizeInfo([old, ...many, latest]).lastAssistantInputTokens, 100);
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
    "REQUEST_LIMIT_EXCEEDED",
    "REQUEST_TIMEOUT",
    "CONVERSATION_CHANGED",
    "CLI_CONVERSATION",
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

test("generationErrors render persistent error and warning notices with compress_and_retry action", () => {
  const html = renderNotices({
    generationErrors: [
      {
        id: "err-1",
        title: "Generation failed",
        message: "context_length_exceeded: 200k tokens",
        solution: "compress_and_retry",
        timestamp: 1000,
        affectsReply: true,
      },
      {
        id: "err-2",
        title: null,
        message: "Title generation failed",
        solution: null,
        timestamp: 1001,
        affectsReply: false,
      },
    ],
    onDismissGenerationError: () => {},
    onCompressAndRetry: () => {},
  });
  assert.match(html, /role="alert"/);
  assert.match(html, /Generation failed/);
  assert.match(html, /context_length_exceeded: 200k tokens/);
  assert.match(html, /Compress and retry/);
  assert.match(html, /Title generation failed/);

  // Hide compress_and_retry button while compression is already running.
  const whileRunning = renderNotices({
    compressState: state("running"),
    generationErrors: [
      {
        id: "err-1",
        message: "overflow",
        solution: "compress_and_retry",
        timestamp: 1000,
        affectsReply: true,
      },
    ],
    onDismissGenerationError: () => {},
    onCompressAndRetry: () => {},
  });
  assert.match(whileRunning, /overflow/);
  assert.doesNotMatch(whileRunning, /Compress and retry/);
});

test("preview covers the messages after the last checkpoint, minus the kept ones", () => {
  const nodes = [node("USER"), node("ASSISTANT"), node("USER"), node("ASSISTANT"), node("USER")];
  assert.equal(compressionPreviewRange(nodes, 0).length, 5);
  assert.equal(compressionPreviewRange(nodes, 2).length, 3);
  assert.equal(compressionPreviewRange(nodes, 5).length, 0);
  nodes[2].messages[0].isContextCheckpoint = true;
  assert.equal(compressionPreviewRange(nodes, 0).length, 2, "the checkpoint and what precedes it are not summarized again");
  assert.equal(compressionPreviewRange(nodes, 2).length, 0);
});

test("the dialog says why there is nothing to compress, matching the server's two rejections", () => {
  const nodes = [node("USER"), node("ASSISTANT"), node("USER"), node("ASSISTANT")];
  assert.equal(compressBlockedKey(nodes, 0), null);
  assert.equal(compressBlockedKey(nodes, 3), null);
  assert.equal(compressBlockedKey(nodes, 4), "conversations.compress.error_not_enough");
  nodes.push(node("USER"));
  nodes[4].messages[0].isContextCheckpoint = true;
  assert.equal(compressBlockedKey(nodes, 0), "conversations.compress.error_nothing", "only a checkpoint at the end");
  assert.equal(compressBlockedKey(nodes, 2), "conversations.compress.error_nothing");
  assert.equal(compressBlockedKey([], 0), "conversations.compress.error_nothing");
});

test("the notify-only hint offers compress and dismiss, and hides while compressing", () => {
  const hint = renderNotices({ compressSuggestion: { percent: 83, exact: true } });
  assert.match(hint, /Context at 83%/);
  assert.match(hint, /compress-suggestion-open/);
  assert.match(renderNotices({ compressSuggestion: { percent: 83, exact: false } }), /≈ Context at 83%/);
  assert.doesNotMatch(
    renderNotices({ compressSuggestion: { percent: 83, exact: true }, compressState: state("running") }),
    /Context at/,
  );
});

test("a summary waiting for review offers review and discard; automatic runs say so", () => {
  const waiting = renderNotices({ compressState: state("awaiting_review", { reviewSummary: "S" }) });
  assert.match(waiting, /Summary ready/);
  assert.match(waiting, /compress-review-open/);
  assert.match(waiting, /Discard/);
  assert.match(renderNotices({ compressState: state("running", { origin: "auto" }) }), /automatically/);
});

test("batch 2 handles fromOriginal ranges, chunked running progress, carry stepper, and result summary", () => {
  const n0 = node("USER");
  const n1 = node("ASSISTANT");
  const cp = node("USER");
  cp.messages[0].isContextCheckpoint = true;
  const n2 = node("USER");
  const list = [n0, n1, cp, n2];

  assert.equal(compressionPreviewRange(list, 0, true).length, 3);
  assert.equal(compressionPreviewRange(list, 1, true).length, 2);
  assert.equal(compressionPreviewRange(list, 2, true).length, 0);
  assert.equal(compressBlockedKey(list, 1, true), null);
  assert.equal(compressBlockedKey(list, 2, true), "conversations.compress.error_not_enough");
  assert.equal(compressBlockedKey([n0, cp], 0, true), null);

  assert.equal(stepCarryCount(1, -1), 1);
  assert.equal(stepCarryCount(3, 2), 5);
  assert.equal(stepCarryCount(50, 5), 50);

  const runningChunked = renderNotices({
    compressState: state("running", { chunked: true, completedChunks: 1, totalChunks: 4 }),
  });
  assert.match(runningChunked, /Compressing in chunks \(2\/4\)/);

  const meta = formatCompressResultMeta(
    state("succeeded", {
      requestCount: 3,
      chunked: true,
      cacheBypassed: true,
      carriedCount: 2,
      totalUsage: { promptTokens: 400, completionTokens: 100, cachedTokens: 0, totalTokens: 500 },
    }),
    (k, opts) => i18next.t(`page:${k}`, opts),
  );
  assert.ok(meta);
  assert.match(meta, /3 request/);
  assert.match(meta, /chunked/);
  assert.match(meta, /cache bypassed/);
  assert.match(meta, /2 carried verbatim/);
  assert.match(meta, /500 tokens/);
});

test("batch 3 renders clear checkpoint without body/edit, tracks stale messages, and keeps locale keys in sync", () => {
  const clearHtml = renderToStaticMarkup(
    createElement(ChatMessageCheckpoint, {
      message: {
        id: "cp-clear",
        role: "USER",
        parts: [],
        createdAt: "",
        isContextCheckpoint: true,
        checkpointMeta: { kind: "clear", summarizedCount: 3 },
      },
      onEdit: () => {},
      onDelete: () => {},
    }),
  );
  assert.match(clearHtml, /data-context-checkpoint-clear="true"/);
  assert.match(clearHtml, /Context cleared/);

  const n1 = node("USER");
  const n2 = node("ASSISTANT");
  const cp1: MessageNodeDto = {
    id: "node-cp1",
    selectIndex: 0,
    messages: [{ id: "m-cp1", role: "USER", parts: [{ type: "text", text: "sum" }], createdAt: "", isContextCheckpoint: true }],
  };
  const n3 = node("USER");
  const cpClear: MessageNodeDto = {
    id: "node-cp-clear",
    selectIndex: 0,
    messages: [{ id: "m-cp-clear", role: "USER", parts: [], createdAt: "", isContextCheckpoint: true, checkpointMeta: { kind: "clear" } }],
  };
  const nodes = [n1, n2, cp1, n3, cpClear];
  assert.deepEqual(
    affectedCheckpointsForMessage(nodes, n1.messages[0].id).map((n) => n.id),
    ["node-cp1", "node-cp-clear"],
  );
  assert.deepEqual(
    affectedCheckpointsForMessage(nodes, n3.messages[0].id).map((n) => n.id),
    ["node-cp-clear"],
  );
  assert.deepEqual(affectedCheckpointsForMessage(nodes, "m-cp1"), []);
  assert.deepEqual(laterCheckpointsForCheckpointMessage(nodes, "m-cp1"), {
    nodeId: "node-cp1",
    laterCount: 1,
  });

  for (const section of ["compress", "context_records", "context_usage", "stale"] as const) {
    assert.deepEqual(
      Object.keys(zh.conversations[section]).sort(),
      Object.keys(en.conversations[section]).sort(),
    );
  }
  assert.deepEqual(Object.keys(zhMessage.chat_message).sort(), Object.keys(enMessage.chat_message).sort());
});

