/**
 * 聊天页侧边栏：底部按钮排成一行（4 个跳转 + 显示菜单 + 收起），可收成图标栏，开合状态按浏览器记住。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/conversation-sidebar.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import {
  CONVERSATION_SIDEBAR_STYLE,
  CONVERSATION_SIDEBAR_WIDTH,
  ConversationSidebar,
  SIDEBAR_FOOTER_BUTTONS,
  type ConversationSidebarProps,
} from "../app/components/conversation-sidebar";
import { SidebarProvider } from "../app/components/ui/sidebar";
import {
  SIDEBAR_OPEN_STORAGE_KEY,
  readSidebarOpen,
  writeSidebarOpen,
} from "../app/lib/sidebar-state";
import en from "../app/locales/en-US/common.json";
import enSettings from "../app/overlay/locales/en-US/settings.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  defaultNS: "common",
  resources: { "en-US": { common: en, settings: enSettings } },
  interpolation: { escapeValue: false },
});

const baseProps: ConversationSidebarProps = {
  conversations: [],
  activeId: null,
  loading: false,
  error: null,
  hasMore: false,
  loadMore: () => {},
  userName: "User",
  assistants: [],
  assistantTags: [],
  currentAssistantId: null,
  onSelect: () => {},
  onAssistantChange: async () => {},
};

function renderSidebar(open: boolean, props: Partial<ConversationSidebarProps> = {}): string {
  return renderToStaticMarkup(
    createElement(
      MemoryRouter,
      null,
      createElement(
        SidebarProvider,
        { open, onOpenChange: () => {} },
        createElement(ConversationSidebar, { ...baseProps, ...props }),
      ),
    ),
  );
}

// 底部按钮行：从 data-testid 起到侧边栏边缘细条之前
function footerActions(html: string): string {
  const start = html.indexOf('data-testid="sidebar-footer-actions"');
  assert.ok(start >= 0, "footer actions row is rendered");
  const end = html.indexOf('data-sidebar="rail"', start);
  return html.slice(start, end < 0 ? undefined : end);
}

function ariaLabels(fragment: string): string[] {
  return [...fragment.matchAll(/aria-label="([^"]*)"/g)].map((match) => match[1]);
}

test("footer is a single row: four links, display menu, collapse button", () => {
  const row = footerActions(renderSidebar(true));
  assert.deepEqual(ariaLabels(row), [
    "Open settings",
    "Open workspaces",
    enSettings.nav.translator,
    enSettings.nav.storage,
    "Display",
    "Collapse sidebar",
  ]);
  assert.deepEqual(
    [...row.matchAll(/href="([^"]*)"/g)].map((match) => match[1]),
    ["/settings", "/workspaces", "/translator", "/storage"],
  );
  assert.doesNotMatch(row, /flex-wrap/);
});

test("web logout moves into the display menu instead of widening the row", () => {
  const html = renderSidebar(true, { webAuthEnabled: true });
  assert.equal(ariaLabels(footerActions(html)).length, SIDEBAR_FOOTER_BUTTONS);
  assert.doesNotMatch(html, /Clear web session|aria-label="Language/);
});

test("expanded sidebar shows the conversation list and labels", () => {
  const html = renderSidebar(true);
  assert.match(html, /data-state="expanded"/);
  assert.match(html, /data-collapsible=""/);
  assert.match(html, /aria-label="Collapse sidebar"[^>]*aria-expanded="true"/);
  assert.match(html, /title="Collapse sidebar"/);
  assert.ok(html.includes(">New conversation<"));
});

test("collapsed sidebar becomes an icon rail with the expand button", () => {
  const html = renderSidebar(false);
  assert.match(html, /data-state="collapsed"/);
  assert.match(html, /data-collapsible="icon"/);
  assert.match(html, /aria-label="Expand sidebar"[^>]*aria-expanded="false"/);
  assert.match(html, /title="Expand sidebar"/);
  // 对话列表整组在图标栏里隐藏；带文字的按钮缩成方块，文字隐藏
  assert.match(html, /data-sidebar="group" class="[^"]*group-data-\[collapsible=icon\]:hidden/);
  const newButton = html.match(/<button[^>]*aria-label="New conversation"[^>]*>/)?.[0] ?? "";
  assert.ok(newButton.includes("group-data-[collapsible=icon]:size-8!"), newButton);
  assert.match(
    html,
    /<span class="group-data-\[collapsible=icon\]:hidden">New conversation<\/span>/,
  );
  assert.match(footerActions(html), /group-data-\[collapsible=icon\]:flex-col/);
});

test("sidebar open state: defaults to open, remembers false, survives broken storage", () => {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
  };
  assert.equal(readSidebarOpen(storage), true);
  writeSidebarOpen(false, storage);
  assert.equal(values.get(SIDEBAR_OPEN_STORAGE_KEY), "false");
  assert.equal(readSidebarOpen(storage), false);
  writeSidebarOpen(true, storage);
  assert.equal(readSidebarOpen(storage), true);

  const throwing = {
    getItem: () => {
      throw new Error("blocked");
    },
    setItem: () => {
      throw new Error("blocked");
    },
  };
  assert.equal(readSidebarOpen(throwing), true);
  assert.doesNotThrow(() => writeSidebarOpen(false, throwing));
  assert.equal(readSidebarOpen(null), true);
});

test("sidebar width is sized to the footer row, so the row has no empty slot", () => {
  const row = footerActions(renderSidebar(true, { webAuthEnabled: true }));
  assert.equal(ariaLabels(row).length, SIDEBAR_FOOTER_BUTTONS);
  const style = CONVERSATION_SIDEBAR_STYLE as Record<string, string>;
  // 32px 按钮 × n + 4px 间距 × n（含两组之间多留的一个）+ 内边距 16px + 边框 1px
  assert.equal(
    CONVERSATION_SIDEBAR_WIDTH,
    `calc(${SIDEBAR_FOOTER_BUTTONS} * 2rem + ${SIDEBAR_FOOTER_BUTTONS} * 0.25rem + 1rem + 1px)`,
  );
  // 桌面与手机抽屉同宽
  assert.equal(style["--sidebar-width"], CONVERSATION_SIDEBAR_WIDTH);
  assert.equal(SIDEBAR_FOOTER_BUTTONS * 32 + SIDEBAR_FOOTER_BUTTONS * 4 + 16 + 1, 233);
  assert.equal(style["--sidebar-width-icon"], "calc(3rem + 1px)");
});

test("collapsed rail lists conversations by their first character, pinned first", async () => {
  const { conversationInitial, CONVERSATION_RAIL_TOOLTIP_DELAY } = await import("../app/components/conversation-rail");
  assert.equal(conversationInitial("雨夜的第一章"), "雨");
  assert.equal(conversationInitial("  outline"), "O");
  assert.equal(conversationInitial("🎉 发布计划"), "🎉");
  assert.equal(conversationInitial("👨‍👩‍👧 family"), "👨‍👩‍👧", "a ZWJ emoji is one grapheme");
  assert.equal(conversationInitial(""), "·");
  assert.equal(conversationInitial(null), "·");
  assert.equal(CONVERSATION_RAIL_TOOLTIP_DELAY, 1000);

  const item = (id: string, title: string, isPinned = false) => ({
    id, assistantId: "a", title, isPinned, folderId: null, createAt: 0, updateAt: 0, isGenerating: false,
  });
  const conversations = [item("1", "beta"), item("2", "置顶", true), item("3", "")];
  const html = renderSidebar(false, { conversations, activeId: "1" });
  const start = html.indexOf('data-testid="conversation-rail"');
  assert.ok(start >= 0, "rail is rendered");
  const rail = html.slice(start, html.indexOf("</ul>", start));
  assert.deepEqual(ariaLabels(rail).slice(1), ["置顶", "beta", en.conversation_sidebar.unnamed_conversation]);
  assert.match(rail, /aria-current="true"[^>]*>B</, "active conversation is marked");
  // The rail group is only visible in icon mode; the full list is hidden there.
  assert.match(html, /hidden min-h-0 flex-1 flex-col px-0 group-data-\[collapsible=icon\]:flex/);
});

test("the chat header lives in the chat pane, one 45px row", async () => {
  const { readFileSync } = await import("node:fs");
  const page = readFileSync(new URL("../app/routes/conversations.tsx", import.meta.url), "utf8");
  const pane = page.slice(page.indexOf("const chatPane = ("), page.indexOf("return (\n    <SidebarProvider"));
  assert.match(pane, /data-testid="chat-header"/);
  assert.match(pane, /h-\[45px\]/, "same height as the workspace panel header, so the borders line up");
  assert.match(pane, /<SidebarTrigger/);
  assert.match(page, /<ResizablePanel\s+id="chat"[\s\S]*?\{chatPane\}/);
  // Nothing spans the full width above the panel group any more.
  const inset = page.slice(page.indexOf("<SidebarInset"), page.indexOf("{!isMobile ? ("));
  assert.ok(!inset.includes("border-b"), "no full-width header above the panels");
});

test("panel layout is fitted to the panels that exist right now", async () => {
  const { fitPanelLayout } = await import("../app/lib/panel-layout");
  // The workbench closed while in focus mode: its share is dropped and the rest rescaled to 100.
  assert.deepEqual(fitPanelLayout({ chat: 40, workbench: 20, workspace: 40 }, ["chat", "workspace"]), { chat: 50, workspace: 50 });
  // The workbench opened meanwhile: the missing panel takes its fallback.
  const opened = fitPanelLayout({ chat: 50, workspace: 50, "focus-list": 0 }, ["chat", "workbench", "workspace", "focus-list"], { workbench: 36 })!;
  assert.ok(Math.abs(opened.workbench - 26.47) < 0.01);
  assert.ok(Math.abs(Object.values(opened).reduce((a, b) => a + b, 0) - 100) < 1e-9);
  assert.equal(fitPanelLayout({ chat: 0 }, ["chat"]), null);
});

test("side-by-side widening gives the workspace its share and scales the rest", async () => {
  const { widenPanelLayout } = await import("../app/lib/panel-layout");
  assert.deepEqual(widenPanelLayout({ chat: 50, workspace: 50 }, "workspace", 65), { chat: 35, workspace: 65 });
  const withWorkbench = widenPanelLayout({ chat: 40, workbench: 20, workspace: 40 }, "workspace", 70);
  assert.ok(Math.abs(withWorkbench.chat - 20) < 1e-9 && Math.abs(withWorkbench.workbench - 10) < 1e-9);
  assert.deepEqual(widenPanelLayout({ chat: 100 }, "workspace", 65), { chat: 100 });
});

test("an empty workbench is not mounted, so chat and workspace share one separator", async () => {
  const { readFileSync } = await import("node:fs");
  const page = readFileSync(new URL("../app/routes/conversations.tsx", import.meta.url), "utf8");
  assert.match(page, /\{showWorkbenchPanel && panel \? \(\s*<>\s*<ResizableHandle withHandle \/>\s*<ResizablePanel\s+id="workbench"/);
  assert.ok(!/id="workbench"[\s\S]{0,200}collapsible/.test(page), "no zero-width collapsible workbench left behind");
  assert.match(page, /onLayoutChanged=\{handlePanelLayoutChanged\}/);
});
