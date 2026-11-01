/**
 * 收藏弹窗：过滤规则，以及侧边栏顶部入口在展开/图标栏两种状态下的渲染。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/conversation-favorites.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import {
  ConversationFavoritesButton,
  filterFavorites,
} from "../app/components/conversation-favorites-button";
import { SidebarProvider } from "../app/components/ui/sidebar";
import type { FavoriteItemDto } from "../app/types";
import en from "../app/locales/en-US/common.json";
import zh from "../app/locales/zh-CN/common.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  defaultNS: "common",
  resources: { "en-US": { common: en } },
  interpolation: { escapeValue: false },
});

function item(id: string, title: string, preview: string): FavoriteItemDto {
  return {
    id,
    conversationId: `c-${id}`,
    nodeId: `n-${id}`,
    assistantId: "a",
    title,
    preview,
    createdAt: 0,
    status: "ok",
  };
}

test("filter matches title or preview, case-insensitively; blank keeps all", () => {
  const items = [
    item("1", "Kotlin Coroutines", "launch vs async"),
    item("2", "Travel", "Book the HOTEL"),
  ];
  assert.equal(filterFavorites(items, "   ").length, 2);
  assert.deepEqual(
    filterFavorites(items, "coroutine").map((it) => it.id),
    ["1"],
  );
  assert.deepEqual(
    filterFavorites(items, "hotel").map((it) => it.id),
    ["2"],
  );
  assert.deepEqual(filterFavorites(items, "nothing"), []);
});

function renderTrigger(open: boolean): string {
  return renderToStaticMarkup(
    createElement(
      SidebarProvider,
      { open, onOpenChange: () => {} },
      createElement(ConversationFavoritesButton, { onSelect: () => {} }),
    ),
  );
}

test("sidebar entry shows its label expanded and shrinks to an icon in the rail", () => {
  const html = renderTrigger(true);
  assert.match(html, /aria-label="Favorites"/);
  assert.match(html, /<span class="group-data-\[collapsible=icon\]:hidden">Favorites<\/span>/);
  const button = html.match(/<button[^>]*aria-label="Favorites"[^>]*>/)?.[0] ?? "";
  assert.ok(button.includes("group-data-[collapsible=icon]:size-8!"), button);
});

test("every status has a label in both languages", () => {
  for (const locale of [en, zh]) {
    const strings = locale.conversation_favorites as Record<string, string>;
    for (const status of ["conversation_deleted", "node_missing"]) {
      assert.ok(strings[`status_${status}`], `missing status_${status}`);
    }
    assert.deepEqual(Object.keys(strings).sort(), Object.keys(en.conversation_favorites).sort());
  }
});
