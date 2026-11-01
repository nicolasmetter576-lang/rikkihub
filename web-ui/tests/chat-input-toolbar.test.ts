/**
 * 输入框工具栏：桌面聊天栏按自身宽度排版（容器查询），拉窄时发送区整组换到第二行，
 * 工具图标不再被挤成一竖列；手机保持原来按视口断点的一行。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/chat-input-toolbar.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import { ChatInput } from "../app/components/input/chat-input";
import { workspaceQueryKeys } from "../app/overlay/lib/workspace";
import { useSettingsStore } from "../app/stores";
import enInput from "../app/locales/en-US/input.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  defaultNS: "input",
  resources: { "en-US": { input: enInput } },
  interpolation: { escapeValue: false },
});

const assistantId = "11111111-1111-4111-8111-111111111111";

useSettingsStore.setState({
  settings: {
    assistantId,
    chatModelId: "m1",
    displaySetting: { sendOnEnter: true },
    assistants: [{ id: assistantId, name: "A", workspaceId: "w1", chatModelId: "m1" }],
    providers: [
      {
        id: "p1",
        type: "claude",
        enabled: true,
        name: "Anthropic",
        models: [
          {
            id: "m1",
            modelId: "claude-opus-5-5",
            displayName: "Claude Opus 5.5 High",
            type: "CHAT",
            abilities: ["TOOL", "REASONING"],
          },
        ],
      },
    ],
    quickMessages: [],
    modeInjections: [],
    lorebooks: [],
    mcpServers: [],
    searchServices: [],
  },
} as never);
// renderToStaticMarkup reads zustand's server snapshot (the initial-state object, returned by reference).
Object.assign(useSettingsStore.getInitialState(), { settings: useSettingsStore.getState().settings });

function renderInput(adaptiveToolbar: boolean): string {
  const client = new QueryClient();
  client.setQueryData(workspaceQueryKeys.list, [
    { id: "w1", name: "Draft", root: "r", shellStatus: "READY", createdAt: 0, updatedAt: 0 },
  ]);
  return renderToStaticMarkup(
    createElement(
      QueryClientProvider,
      { client },
      createElement(
        MemoryRouter,
        {},
        createElement(ChatInput, {
          value: "",
          attachments: [],
          adaptiveToolbar,
          onValueChange: () => {},
          onAddParts: () => {},
          onRemovePart: () => {},
          onSend: () => {},
          onSendNoAnswer: () => {},
        }),
      ),
    ),
  );
}

test("desktop toolbar sizes itself by the pane, not the viewport", () => {
  const html = renderInput(true);
  assert.match(html, /@container\/composer/);
  // Send cluster: its own container, grows into the row and wraps as a whole below its basis.
  assert.match(html, /@container\/send grow basis-76/);
  // The model name is the only part that shrinks; labels follow the pane / cluster width.
  assert.match(html, /max-w-56 shrink/);
  assert.match(html, /@\[25rem\]\/send:block/);
  assert.match(html, /@2xl\/composer:inline[^"]*">Draft</);
  // The tool icons never shrink into a column.
  assert.match(html, /flex flex-wrap items-center gap-0\.5 max-w-full shrink-0/);
  assert.doesNotMatch(html, /max-w-\[120px\]/);
  assert.doesNotMatch(html, /sm:block|sm:inline/);
});

test("mobile toolbar keeps the viewport-based single row", () => {
  const html = renderInput(false);
  assert.doesNotMatch(html, /@container\//);
  assert.match(html, /justify-between gap-2/);
  assert.match(html, /flex flex-wrap items-center gap-0\.5 min-w-0/);
  assert.match(html, /max-w-\[120px\] sm:max-w-56/);
  assert.match(html, /hidden sm:block/);
  assert.match(html, /sm:inline[^"]*">Draft</);
});
