/**
 * Web UI P1: CLI message contextLength fallback, modelSnapshot display fallback,
 * and incomplete usage stats in getNerdStats / ChatMessage.
 *
 * Run:
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs --test tests/cli-p1.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import type { MessageDto, MessageNodeDto, Settings, UIMessage } from "../app/types";
import enCommon from "../app/locales/en-US/common.json";
import enMessage from "../app/locales/en-US/message.json";
import enPage from "../app/locales/en-US/page.json";

register("./helpers/svg-stub-loader.mjs", import.meta.url);

const { ChatMessage, getNerdStats } = await import("../app/components/message/chat-message");
const { useSettingsStore } = await import("../app/stores");

await i18next.use(initReactI18next).init({
  lng: "en-US",
  fallbackLng: "en-US",
  resources: {
    "en-US": {
      common: enCommon,
      message: enMessage,
      page: enPage,
    },
  },
  interpolation: { escapeValue: false },
});

const t = i18next.getFixedT("en-US", "message");

function installDisplaySettings() {
  const nextSettings = {
    displaySetting: {
      showModelIcon: true,
      showModelName: true,
      showTokenUsage: true,
      showUserAvatar: false,
      userNickname: "User",
      userAvatar: null,
    },
  } as unknown as Settings;

  useSettingsStore.setState({ settings: nextSettings });
  Object.assign(useSettingsStore.getInitialState(), { settings: nextSettings });
}

installDisplaySettings();

function makeCliMessage(overrides: Partial<MessageDto> = {}): MessageDto {
  return {
    id: "msg-cli-1",
    role: "ASSISTANT",
    parts: [{ type: "text", text: "CLI response" }],
    annotations: [],
    createdAt: "2026-11-18T08:00:00Z",
    finishedAt: "2026-11-18T08:00:02Z",
    modelId: null,
    modelSnapshot: {
      modelId: "claude-sonnet-4-5",
      displayName: "Claude Sonnet 4.5",
    },
    contextLength: 200_000,
    usage: {
      promptTokens: 50_000,
      completionTokens: 1_000,
      cachedTokens: 0,
      totalTokens: 51_000,
    },
    lastUsage: {
      promptTokens: 50_000,
      completionTokens: 1_000,
      cachedTokens: 0,
      totalTokens: 51_000,
    },
    usageComplete: true,
    stepCount: 1,
    ...overrides,
  };
}

test("getNerdStats uses message.contextLength to format context window percentage", () => {
  const message = makeCliMessage();
  // Verify UIMessage also accepts contextLength at compile time.
  const uiMessage: UIMessage = { ...message, role: "assistant", annotations: [] };
  const stats = getNerdStats(message, uiMessage.contextLength, t);

  assert.ok(stats.details.includes("Context: 51,000 / 200,000 (26%)"));
});

test("getNerdStats marks usageComplete === false as incomplete and hides unreported output tokens", () => {
  const message = makeCliMessage({
    usageComplete: false,
    usage: {
      promptTokens: 0,
      completionTokens: 0,
      cachedTokens: 0,
      totalTokens: 1_234,
    },
    lastUsage: null,
  });

  const stats = getNerdStats(message, message.contextLength, t);
  const byKey = Object.fromEntries(stats.items.map((item) => [item.key, item.label]));

  assert.equal(byKey.status, "Reported · incomplete");
  assert.equal(byKey.completion, "Not reported out");
  assert.ok(stats.details.includes("Reported · incomplete"));
});

test("ChatMessage falls back to message.contextLength and modelSnapshot when model is null", () => {
  const message = makeCliMessage();
  const node: MessageNodeDto = {
    id: "node-1",
    messages: [message],
    selectIndex: 0,
  };

  const html = renderToStaticMarkup(
    createElement(ChatMessage, {
      node,
      message,
      model: null,
      isCliSession: true,
    }),
  );

  assert.ok(html.includes("Claude Sonnet 4.5"));
  assert.ok(html.includes("Context: 51,000 / 200,000 (26%)"));

  const fallbackMessage = makeCliMessage({
    modelSnapshot: { modelId: "codex-cli", displayName: "  " },
  });
  const fallbackHtml = renderToStaticMarkup(
    createElement(ChatMessage, {
      node: { id: "node-2", messages: [fallbackMessage], selectIndex: 0 },
      message: fallbackMessage,
      model: null,
      isCliSession: true,
    }),
  );

  assert.ok(fallbackHtml.includes("codex-cli"));
});
