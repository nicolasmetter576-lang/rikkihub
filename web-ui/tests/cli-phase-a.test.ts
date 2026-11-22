/**
 * Web UI Phase A: CLI session safe degradation, local command guard, ACP tool & diff rendering,
 * header badge, character picker fallback notice, and bilingual key parity.
 *
 * Run:
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs --test tests/cli-phase-a.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { register } from "node:module";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter, Route, Routes } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import ts from "typescript";

import {
  extractAcpDiffs,
  formatCliSessionSummary,
  generateUnifiedDiff,
  getAcpToolTitle,
  isAcpToolPart,
  isUnsupportedWebCliInput,
  normalizeCliPermissionMode,
  parseAcpMetadata,
  parseCliInput,
  resolveCliAgentName,
} from "../app/lib/cli";
import type {
  AssistantProfile,
  ConversationCliSession,
  ConversationDto,
  MessageDto,
  MessageNodeDto,
  Settings,
  ToolPart as UIToolPart,
} from "../app/types";
import enCommon from "../app/locales/en-US/common.json";
import zhCommon from "../app/locales/zh-CN/common.json";
import enInput from "../app/locales/en-US/input.json";
import zhInput from "../app/locales/zh-CN/input.json";
import enMessage from "../app/locales/en-US/message.json";
import zhMessage from "../app/locales/zh-CN/message.json";
import enPage from "../app/locales/en-US/page.json";
import zhPage from "../app/locales/zh-CN/page.json";
import enTavern from "../app/overlay/locales/en-US/tavern.json";
import enSettings from "../app/overlay/locales/en-US/settings.json";

register("./helpers/svg-stub-loader.mjs", import.meta.url);

const { compressErrorKey } = await import("../app/components/compress-dialog");
const { ConversationNotices } = await import("../app/components/conversation-notices");
const { CharacterPickerCliNotice } = await import("../app/components/character-picker");
const { ChatInput } = await import("../app/components/input/chat-input");
const { visibleExtensionTabs, EXTENSION_TABS } = await import(
  "../app/components/input/extension-picker"
);
const { MessageParts } = await import("../app/components/message/message-part");
const { ChatMessage } = await import("../app/components/message/chat-message");
const { ToolPart } = await import("../app/components/message/parts/tool-part");
const { CliSessionBadge } = await import("../app/routes/conversations");
const { workspaceQueryKeys } = await import("../app/overlay/lib/workspace");
const { useSettingsStore } = await import("../app/stores");

await i18next.use(initReactI18next).init({
  lng: "en-US",
  defaultNS: "common",
  resources: {
    "en-US": {
      common: enCommon,
      input: enInput,
      message: enMessage,
      page: enPage,
      tavern: enTavern,
      settings: enSettings,
    },
    "zh-CN": {
      common: zhCommon,
      input: zhInput,
      message: zhMessage,
      page: zhPage,
      tavern: enTavern,
      settings: enSettings,
    },
  },
  interpolation: { escapeValue: false },
});

const assistantId = "11111111-1111-4111-8111-111111111111";

function installTestSettings(overrides: Partial<Settings> = {}) {
  const assistant: AssistantProfile = {
    id: assistantId,
    name: "Workspace Assistant",
    workspaceId: "w1",
    chatModelId: "m1",
    tags: [],
    defaultCliAgentId: "builtin:agy",
  };
  const nextSettings = {
    assistantId,
    chatModelId: "m1",
    dynamicColor: false,
    themeId: "default",
    developerMode: false,
    favoriteModels: [],
    assistantTags: [],
    displaySetting: {
      userNickname: "User",
      showUserAvatar: false,
      showModelIcon: false,
      showModelName: false,
      showTokenUsage: false,
      showThinkingContent: true,
      autoCloseThinking: true,
      codeBlockAutoWrap: false,
      codeBlockAutoCollapse: false,
      showLineNumbers: false,
      sendOnEnter: true,
      enableAutoScroll: true,
      fontSizeRatio: 1,
      pasteLongTextAsFile: false,
      pasteLongTextThreshold: 1000,
    },
    assistants: [assistant],
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
            displayName: "Claude Opus 5.5",
            type: "CHAT",
            abilities: ["TOOL", "REASONING"],
          },
        ],
      },
    ],
    quickMessages: [],
    promptLibrary: [],
    lorebooks: [],
    mcpServers: [],
    searchServices: [],
    searchServiceSelected: 0,
    customCliAgents: [
      { id: "custom-1", name: "Codex CLI", executable: "codex" },
      { id: "custom-unnamed", name: "  ", executable: "opencode" },
    ],
    ...overrides,
  } as unknown as Settings;

  useSettingsStore.setState({ settings: nextSettings });
  Object.assign(useSettingsStore.getInitialState(), { settings: nextSettings });
}

installTestSettings();

function makeCliConversation(cliSession?: ConversationCliSession | null): ConversationDto {
  return {
    id: "conv-1",
    assistantId,
    title: "CLI Session",
    messages: [],
    chatSuggestions: [],
    isPinned: false,
    cliSession: cliSession ?? null,
    createAt: 0,
    updateAt: 0,
    isGenerating: false,
  };
}

// --------------------------------------------------------------------------- 1. Types & Agent Name

test("resolveCliAgentName handles built-ins, custom agents, executable fallback, and unknown ids", () => {
  const customAgents = [
    { id: "c1", name: "Codex", executable: "codex" },
    { id: "c2", name: "", executable: "opencode" },
  ];
  assert.equal(resolveCliAgentName("builtin:agy", customAgents), "Antigravity");
  assert.equal(resolveCliAgentName("builtin:droid", customAgents), "Droid");
  assert.equal(resolveCliAgentName("c1", customAgents), "Codex");
  assert.equal(resolveCliAgentName("c2", customAgents), "opencode");
  assert.equal(resolveCliAgentName("unknown-agent", customAgents), "unknown-agent");
});

test("normalizeCliPermissionMode and formatCliSessionSummary accept both Kotlin wire case and uppercase", () => {
  assert.equal(normalizeCliPermissionMode("ask"), "ASK");
  assert.equal(normalizeCliPermissionMode("ASK"), "ASK");
  assert.equal(normalizeCliPermissionMode("auto"), "AUTO");
  assert.equal(normalizeCliPermissionMode("AUTO"), "AUTO");

  assert.equal(
    formatCliSessionSummary({
      agentId: "builtin:agy",
      permissionMode: "ASK",
      modelId: "gemini-2.5-pro",
    }),
    "Antigravity · gemini-2.5-pro · ASK",
  );
  assert.equal(
    formatCliSessionSummary({
      agentId: "builtin:droid",
      permissionMode: "auto",
      modelId: null,
    }),
    "Droid · AUTO",
  );
});

// --------------------------------------------------------------------------- 2. CLI Input Parsing

test("parseCliInput mirrors App CliInput.parse and never intercepts /compact", () => {
  assert.deepEqual(parseCliInput("!ls -la"), { kind: "shell", command: "ls -la" });
  assert.deepEqual(parseCliInput("  !git status  "), { kind: "shell", command: "git status" });
  assert.equal(isUnsupportedWebCliInput("!pwd"), false);
  assert.equal(isUnsupportedWebCliInput("/restart"), false);
  assert.deepEqual(parseCliInput("/restart"), { kind: "local", command: "/restart" });

  for (const cmd of ["/model", "/mode", "/effort", "/thinking", "/status", "/login"]) {
    assert.deepEqual(parseCliInput(cmd), { kind: "local", command: cmd });
    assert.deepEqual(parseCliInput(`  ${cmd}  `), { kind: "local", command: cmd });
    assert.equal(isUnsupportedWebCliInput(cmd), true);
  }

  // Must NEVER block /compact or regular prompts
  assert.deepEqual(parseCliInput("/compact"), { kind: "prompt" });
  assert.deepEqual(parseCliInput("  /compact  "), { kind: "prompt" });
  assert.deepEqual(parseCliInput("/compact focus on tests"), { kind: "prompt" });
  assert.equal(isUnsupportedWebCliInput("/compact"), false);

  // Bare `!`, whitespace, non-exact local commands, and normal text pass through
  for (const text of ["", "   ", "!", "  !  ", "/model gemini", "/MODEL", "hello !world", "/help"]) {
    assert.deepEqual(parseCliInput(text), { kind: "prompt" }, `unexpected intercept for ${JSON.stringify(text)}`);
    assert.equal(isUnsupportedWebCliInput(text), false);
  }
});

// --------------------------------------------------------------------------- 3. ChatInput Degradation & Guard

function renderChatInput(conversation: ConversationDto | null, extraProps: Record<string, unknown> = {}): string {
  installTestSettings();
  const client = new QueryClient();
  client.setQueryData(workspaceQueryKeys.list, [
    { id: "w1", name: "Project", root: "r", shellStatus: "READY", createdAt: 0, updatedAt: 0 },
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
          conversation,
          onValueChange: () => {},
          onAddParts: () => {},
          onRemovePart: () => {},
          onSend: () => {},
          onSendNoAnswer: () => {},
          ...extraProps,
        }),
      ),
    ),
  );
}

test("ChatInput hides HTTP/tavern/tool controls in CLI sessions while keeping extensions, workspace CWD, and terminal", () => {
  const cliConv = makeCliConversation({
    agentId: "builtin:agy",
    permissionMode: "ASK",
    modelId: "gemini-2.5-pro",
  });
  const cliHtml = renderChatInput(cliConv);
  assert.doesNotMatch(cliHtml, /aria-label="Web Search"/);
  assert.doesNotMatch(cliHtml, /aria-label="MCP"/);
  assert.doesNotMatch(cliHtml, /data-tavern-picker/);
  assert.doesNotMatch(cliHtml, /data-testid="chat-polish-button"/);
  assert.doesNotMatch(cliHtml, /Claude Opus 5\.5/);
  assert.doesNotMatch(cliHtml, /Cache keep-alive inactive/);
  assert.match(cliHtml, /aria-label="Extensions"/);
  assert.match(cliHtml, /data-testid="workspace-picker"/);
  assert.match(cliHtml, /data-testid="chat-workspace-terminal"/);

  const httpHtml = renderChatInput(makeCliConversation(null));
  assert.match(httpHtml, /aria-label="Web Search"/);
  assert.match(httpHtml, /aria-label="MCP"/);
  assert.match(httpHtml, /data-tavern-picker/);
  assert.match(httpHtml, /data-testid="chat-polish-button"/);
  assert.match(httpHtml, /Claude Opus 5\.5/);
});

test("ExtensionPicker hides lorebook and skills tabs in CLI sessions", () => {
  assert.deepEqual([...visibleExtensionTabs(false)], [...EXTENSION_TABS]);
  assert.deepEqual([...visibleExtensionTabs(true)], ["quickmessages", "mode"]);
});

function extractExecutable(relativePath: string, name: string, bindings: Record<string, unknown>) {
  const source = ts.createSourceFile(
    relativePath,
    readFileSync(new URL(`../app/${relativePath}`, import.meta.url), "utf8"),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX,
  );
  const matches: ts.Node[] = [];
  const visit = (node: ts.Node) => {
    if (ts.isFunctionDeclaration(node) && node.name?.text === name) matches.push(node);
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === name) {
      matches.push(node);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  assert.equal(matches.length, 1, `${relativePath}: unique ${name}`);
  const node = matches[0];
  const code = ts.isVariableDeclaration(node)
    ? `const ${name} = ${node.initializer!.getText(source)};`
    : node.getText(source);
  const compiled = ts.transpileModule(`${code}\nreturn ${name};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText;
  return new Function(...Object.keys(bindings), compiled)(...Object.values(bindings));
}

test("ChatInput send handlers block !cmd and local slash commands in CLI mode without clearing input, while allowing /compact", async () => {
  for (const handlerName of ["handlePrimaryAction", "handleSecondaryAction"] as const) {
    let sent = 0;
    let lastError: string | null = null;
    let submitting = false;

    const run = async (value: string, isCliSession: boolean, isEditing = false) => {
      sent = 0;
      lastError = null;
      const fn = extractExecutable("components/input/chat-input.tsx", handlerName, {
        React: { useCallback: (cb: unknown) => cb },
        actionDisabled: false,
        canStop: false,
        canSend: true,
        isCliSession,
        isEditing,
        value,
        polishing: false,
        setSubmitting: (v: boolean) => {
          submitting = v;
        },
        setError: (e: string | null) => {
          lastError = e;
        },
        onStop: undefined,
        onSend: async () => {
          sent += 1;
        },
        onSendNoAnswer: async () => {
          sent += 1;
        },
        isUnsupportedWebCliInput,
        handleWebCliInput: async () => {
          if (!isCliSession || isEditing) return false;
          if (value.trim().startsWith("!")) return true;
          if (isUnsupportedWebCliInput(value)) {
            lastError = "chat.cli_command_unsupported";
            return true;
          }
          return false;
        },
        t: (key: string) => key,
      });
      await fn();
      assert.equal(submitting, false);
    };

    await run("!ls", true);
    assert.equal(sent, 0);
    assert.equal(lastError, null);

    await run("/model", true);
    assert.equal(sent, 0);
    assert.equal(lastError, "chat.cli_command_unsupported");

    await run("/thinking", true);
    assert.equal(sent, 0);
    assert.equal(lastError, "chat.cli_command_unsupported");

    // /compact MUST go through to the ACP agent
    await run("/compact", true);
    assert.equal(sent, 1);
    assert.equal(lastError, null);

    // Editing an existing message or normal HTTP conversation is not blocked
    await run("/model", true, true);
    assert.equal(sent, 1);
    assert.equal(lastError, null);

    await run("!ls", false);
    assert.equal(sent, 1);
    assert.equal(lastError, null);
  }
});

// --------------------------------------------------------------------------- 4. Header Badge, Notices, Compress Error

test("CliSessionBadge renders agent name, optional modelId, permissionMode, and read-only hint", async () => {
  await i18next.changeLanguage("zh-CN");
  const withModel = renderToStaticMarkup(
    createElement(CliSessionBadge, {
      cliSession: { agentId: "builtin:agy", modelId: "claude-sonnet-4", permissionMode: "ASK" },
      customCliAgents: [],
    }),
  );
  assert.ok(withModel.includes("Antigravity · claude-sonnet-4 · ASK"));
  assert.ok(withModel.includes(zhPage.conversations.cli.readonly_hint));

  await i18next.changeLanguage("en-US");
  const withoutModel = renderToStaticMarkup(
    createElement(CliSessionBadge, {
      cliSession: { agentId: "custom-1", modelId: null, permissionMode: "auto" },
      customCliAgents: [{ id: "custom-1", name: "Codex CLI", executable: "codex" }],
    }),
  );
  assert.ok(withoutModel.includes("Codex CLI · AUTO"));
  assert.ok(withoutModel.includes(enPage.conversations.cli.readonly_hint));
});

test("compressErrorKey maps CLI_CONVERSATION and ConversationNotices hides oversize warning in CLI sessions", () => {
  assert.equal(compressErrorKey("CLI_CONVERSATION"), "conversations.compress.error_cli");

  const big = { nodeCount: 900, lastAssistantInputTokens: 400_000, showWarning: true };
  const baseProps = {
    compressState: null,
    compressFailure: null,
    sizeInfo: big,
    sizeWarningDismissed: false,
    cancelling: false,
    onCancelCompression: () => {},
    onOpenCompress: () => {},
    onDismissFailure: () => {},
    onDismissSizeWarning: () => {},
    onNewConversation: () => {},
  };
  assert.match(renderToStaticMarkup(createElement(ConversationNotices, { ...baseProps, isCliSession: false })), /very large/);
  assert.equal(renderToStaticMarkup(createElement(ConversationNotices, { ...baseProps, isCliSession: true })), "");
});

test("conversations.tsx gates tavern-off-notice, compress dialog, and compress action on isCliSession", () => {
  const source = readFileSync(new URL("../app/routes/conversations.tsx", import.meta.url), "utf8");
  assert.match(source, /const isCliSession = Boolean\(detail\?\.cliSession\);/);
  assert.match(source, /!isCliSession && detail\?\.characterId/);
  assert.match(source, /detail && !isCliSession && detail\.messages\.length >= 2/);
  assert.match(source, /activeId && !isCliSession \?/);
});

// --------------------------------------------------------------------------- 5. MessageParts & ChatMessage

test("MessageParts skips applyDisplay when isCliSession is true", () => {
  const parts = [{ type: "text" as const, text: "raw model text" }];
  const display = { "0": "regex replaced text" };

  const httpHtml = renderToStaticMarkup(
    createElement(MessageParts, { parts, display, isCliSession: false }),
  );
  assert.ok(httpHtml.includes("regex replaced text"));
  assert.ok(!httpHtml.includes("raw model text"));

  const cliHtml = renderToStaticMarkup(
    createElement(MessageParts, { parts, display, isCliSession: true }),
  );
  assert.ok(cliHtml.includes("raw model text"));
  assert.ok(!cliHtml.includes("regex replaced text"));
});

test("ChatMessage hides edit, qubagu, and worldinfo activations buttons in CLI sessions", () => {
  installTestSettings();
  const message: MessageDto = {
    id: "m1",
    role: "ASSISTANT",
    createdAt: "2026-11-18T00:00:00Z",
    display: { "0": "projected text" },
    parts: [
      {
        type: "text",
        text: "original text",
        metadata: {
          tavernActivations: {
            v: 1,
            more: 0,
            entries: [{ book: "b1", bookName: "Lore", uid: 1, title: "Entry", position: 0, order: 1 }],
          },
          qubagu: { v: 1, h: 0, edits: [] },
        },
      },
    ],
  };
  const node: MessageNodeDto = { id: "n1", selectIndex: 0, messages: [message] };

  const renderMessage = (isCliSession: boolean) =>
    renderToStaticMarkup(
      createElement(
        MemoryRouter,
        { initialEntries: ["/c/conv-1"] },
        createElement(
          Routes,
          {},
          createElement(Route, {
            path: "/c/:id",
            element: createElement(ChatMessage, {
              node,
              message,
              isCliSession,
              onEdit: () => {},
              onDelete: async () => {},
            }),
          }),
        ),
      ),
    );

  const httpHtml = renderMessage(false);
  assert.match(httpHtml, /aria-label="Edit message"/);
  assert.match(httpHtml, /data-qubagu-button/);
  assert.match(httpHtml, /data-activations-button/);
  assert.ok(httpHtml.includes("projected text"));

  const cliHtml = renderMessage(true);
  assert.doesNotMatch(cliHtml, /aria-label="Edit message"/);
  assert.doesNotMatch(cliHtml, /data-qubagu-button/);
  assert.doesNotMatch(cliHtml, /data-activations-button/);
  assert.ok(cliHtml.includes("original text"));
  assert.ok(!cliHtml.includes("projected text"));
});

// --------------------------------------------------------------------------- 6. ACP Tools & Diffs

test("generateUnifiedDiff and extractAcpDiffs handle creations, deletions, edits, and large inputs", () => {
  assert.equal(generateUnifiedDiff("same", "same", "a.ts"), null);
  assert.equal(generateUnifiedDiff("a\r\nb", "a\nb", "a.ts"), null);

  const created = generateUnifiedDiff("", "line1\nline2", "new.txt");
  assert.ok(created);
  assert.match(created, /^--- a\/new\.txt\n\+\+\+ b\/new\.txt\n@@ -0,0 \+1,2 @@\n\+line1\n\+line2$/);

  const deleted = generateUnifiedDiff("gone", "", "old.txt");
  assert.ok(deleted);
  assert.match(deleted, /@@ -1,1 \+0,0 @@\n-gone/);

  const edited = generateUnifiedDiff("a\nb\nc\nd\ne\nf", "a\nb\nC\nd\ne\nf", "src/app.ts");
  assert.ok(edited);
  assert.match(edited, /--- a\/src\/app\.ts/);
  assert.match(edited, /\+\+\+ b\/src\/app\.ts/);
  assert.match(edited, /-c\n\+C/);

  const extracted = extractAcpDiffs({
    kind: "edit",
    content: [
      { type: "text", text: "ignored" },
      { type: "diff", path: "src/foo.ts", oldText: "old", newText: "new" },
    ],
  });
  assert.equal(extracted.length, 1);
  assert.equal(extracted[0].path, "src/foo.ts");
  assert.match(extracted[0].diff, /-old\n\+new/);
});

test("ToolPart renders acp_* cards, collapsed titles, !shell local badge, diffs, approvals, and cancelled acp_ask_question", () => {
  const renderTool = (tool: UIToolPart, loading = false) =>
    renderToStaticMarkup(
      createElement(ToolPart, {
        tool,
        loading,
        onToolApproval: async () => {},
      }),
    );

  // Local !shell command
  const localShell: UIToolPart = {
    type: "tool",
    toolCallId: "shell-1",
    toolName: "acp_shell",
    input: JSON.stringify({ command: "git status" }),
    output: [{ type: "text", text: "On branch main\nnothing to commit" }],
    approvalState: { type: "auto" },
    metadata: {
      acp: { kind: "execute", title: "git\nstatus", status: "completed", local: true },
    },
  };
  assert.equal(isAcpToolPart(localShell), true);
  assert.equal(getAcpToolTitle(localShell, parseAcpMetadata(localShell)), "git status");
  const shellHtml = renderTool(localShell);
  assert.match(shellHtml, /data-acp-local/);
  assert.ok(shellHtml.includes("!shell"));
  assert.ok(shellHtml.includes("git status"));
  assert.ok(shellHtml.includes("On branch main"));

  // Diff rendering + line truncation (> 8 lines)
  const longOld = Array.from({ length: 12 }, (_, i) => `old ${i + 1}`).join("\n");
  const longNew = Array.from({ length: 12 }, (_, i) => `new ${i + 1}`).join("\n");
  const editTool: UIToolPart = {
    type: "tool",
    toolCallId: "edit-1",
    toolName: "acp_edit",
    input: "{}",
    output: [{ type: "text", text: "[completed]" }],
    approvalState: { type: "auto" },
    metadata: {
      acp: {
        kind: "edit",
        title: "Edit src/index.ts",
        content: [{ type: "diff", path: "src/index.ts", oldText: longOld, newText: longNew }],
      },
    },
  };
  const editHtml = renderTool(editTool);
  assert.match(editHtml, /data-acp-diff/);
  assert.ok(editHtml.includes("--- a/src/index.ts"));
  assert.ok(editHtml.includes("+++ b/src/index.ts"));
  assert.match(editHtml, /more line\(s\)/);

  // Tool without acp_ prefix but with metadata.acp
  const customAcp: UIToolPart = {
    type: "tool",
    toolCallId: "c-1",
    toolName: "custom_runner",
    input: "{}",
    output: [{ type: "text", text: "ok" }],
    approvalState: { type: "auto" },
    metadata: { acp: { kind: "read", title: "Read package.json" } },
  };
  assert.equal(isAcpToolPart(customAcp), true);
  assert.ok(renderTool(customAcp).includes("Read package.json"));

  // acp_ask_question cancelled / completed with empty text output renders safely without <pre>
  const askQuestion: UIToolPart = {
    type: "tool",
    toolCallId: "q-1",
    toolName: "acp_ask_question",
    input: "{}",
    output: [{ type: "text", text: "" }],
    approvalState: { type: "auto" },
    metadata: { acp: { kind: "other", title: "Pick a color", status: "completed" } },
  };
  const askHtml = renderTool(askQuestion);
  assert.ok(askHtml.includes("Pick a color"));
  assert.doesNotMatch(askHtml, /data-acp-output/);

  // Pending approval on acp_* tool preserves approve/deny buttons
  const pendingAcp: UIToolPart = {
    type: "tool",
    toolCallId: "p-1",
    toolName: "acp_run_command",
    input: JSON.stringify({ CommandLine: "rm -rf build" }),
    output: [],
    approvalState: { type: "pending" },
    metadata: { acp: { kind: "execute", title: "rm -rf build", status: "pending" } },
  };
  const pendingHtml = renderTool(pendingAcp, true);
  assert.equal((pendingHtml.match(/<button/g) ?? []).length >= 2, true);
});

// --------------------------------------------------------------------------- 7. CharacterPicker Notice

test("CharacterPickerCliNotice displays only when assistant has defaultCliAgentId", () => {
  assert.equal(renderToStaticMarkup(createElement(CharacterPickerCliNotice, { defaultCliAgentId: null })), "");
  assert.equal(renderToStaticMarkup(createElement(CharacterPickerCliNotice, { defaultCliAgentId: undefined })), "");
  const html = renderToStaticMarkup(
    createElement(CharacterPickerCliNotice, { defaultCliAgentId: "builtin:agy" }),
  );
  assert.match(html, /data-testid="character-picker-cli-notice"/);
  assert.ok(html.includes(enCommon.character_picker.cli_http_notice));
});

// --------------------------------------------------------------------------- 8. Bilingual Locale Key Alignment

function flattenKeys(value: unknown, prefix = ""): string[] {
  if (!value || typeof value !== "object" || Array.isArray(value)) return [prefix];
  return Object.entries(value).flatMap(([key, child]) =>
    flattenKeys(child, prefix ? `${prefix}.${key}` : key),
  );
}

test("en-US and zh-CN locale keys are strictly aligned across common, input, message, and page", () => {
  for (const [name, enObj, zhObj] of [
    ["common", enCommon, zhCommon],
    ["input", enInput, zhInput],
    ["message", enMessage, zhMessage],
    ["page", enPage, zhPage],
  ] as const) {
    assert.deepEqual(
      flattenKeys(zhObj).sort(),
      flattenKeys(enObj).sort(),
      `${name}.json keys mismatch between en-US and zh-CN`,
    );
  }
});
