/**
 * Web UI Phase B: interactive CLI control, SSE `cli_runtime`, `!cmd` / `/restart` Web support,
 * and 403 `WEB_CLI_CONTROL_FORBIDDEN` error handling.
 *
 * Run:
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs --test tests/cli-phase-b.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { register } from "node:module";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";
import ts from "typescript";

import { ApiError } from "../app/services/api";
import {
  EVENT_CLI_RUNTIME,
  WEB_CLI_CONTROL_FORBIDDEN_CODE,
  isCliControlForbiddenError,
  isUnsupportedWebCliInput,
  parseCliInput,
  resolveCliSessionDisplay,
} from "../app/lib/cli";
import type { CliRuntimeEvent, ConversationCliSession } from "../app/types";
import enCommon from "../app/locales/en-US/common.json";
import zhCommon from "../app/locales/zh-CN/common.json";
import enInput from "../app/locales/en-US/input.json";
import zhInput from "../app/locales/zh-CN/input.json";
import enPage from "../app/locales/en-US/page.json";
import zhPage from "../app/locales/zh-CN/page.json";

register("./helpers/svg-stub-loader.mjs", import.meta.url);

const { CliSessionBadge } = await import("../app/routes/conversations");

await i18next.use(initReactI18next).init({
  lng: "en-US",
  defaultNS: "common",
  resources: {
    "en-US": { common: enCommon, input: enInput, page: enPage },
    "zh-CN": { common: zhCommon, input: zhInput, page: zhPage },
  },
  interpolation: { escapeValue: false },
});

function extractExecutable(relativePath: string, name: string, bindings: Record<string, unknown>) {
  const fullPath = new URL(`../app/${relativePath}`, import.meta.url);
  const text = readFileSync(fullPath, "utf8");
  const source = ts.createSourceFile(relativePath, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const matches: Array<ts.FunctionDeclaration | ts.VariableDeclaration> = [];
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

test("isUnsupportedWebCliInput allows !cmd and /restart while blocking /login, /model, /mode, /effort, /thinking, /status", () => {
  for (const allowed of ["!pwd", "!ls -la", "  !git status  ", "/restart", "  /restart  ", "/compact", "hello"]) {
    assert.equal(isUnsupportedWebCliInput(allowed), false, `expected allowed: ${allowed}`);
  }
  for (const blocked of ["/login", "/model", "/mode", "/effort", "/thinking", "/status"]) {
    assert.equal(isUnsupportedWebCliInput(blocked), true, `expected blocked: ${blocked}`);
  }
});

test("isCliControlForbiddenError matches only ApiError with WEB_CLI_CONTROL_FORBIDDEN", () => {
  assert.equal(
    isCliControlForbiddenError(new ApiError("Forbidden", 403, WEB_CLI_CONTROL_FORBIDDEN_CODE)),
    true,
  );
  assert.equal(isCliControlForbiddenError(new ApiError("Forbidden", 403, "OTHER_CODE")), false);
  assert.equal(isCliControlForbiddenError(new Error(WEB_CLI_CONTROL_FORBIDDEN_CODE)), false);
  assert.equal(isCliControlForbiddenError(null), false);
});

test("resolveCliSessionDisplay merges active matching cli_runtime and ignores inactive or mismatched conversations", () => {
  const baseSession: ConversationCliSession = {
    agentId: "builtin:agy",
    permissionMode: "ask",
    modelId: "persisted-model",
    modeId: "persisted-mode",
  };
  const activeRuntime: CliRuntimeEvent = {
    active: true,
    conversationId: "conv-1",
    agentId: "builtin:agy",
    busy: true,
    currentModelId: "live-model",
    currentModeId: "live-mode",
    usedTokens: 1200,
    contextTokens: 8000,
    title: "Working",
  };

  const matched = resolveCliSessionDisplay(baseSession, activeRuntime, "conv-1");
  assert.equal(matched.busy, true);
  assert.equal(matched.session.modelId, "live-model");
  assert.equal(matched.session.modeId, "live-mode");
  assert.equal(matched.session.permissionMode, "ask");
  assert.equal(matched.usedTokens, 1200);
  assert.equal(matched.contextTokens, 8000);

  const otherConv = resolveCliSessionDisplay(baseSession, activeRuntime, "conv-2");
  assert.equal(otherConv.busy, false);
  assert.equal(otherConv.session.modelId, "persisted-model");
  assert.equal(otherConv.session.modeId, "persisted-mode");

  const inactive = resolveCliSessionDisplay(baseSession, { active: false }, "conv-1");
  assert.equal(inactive.busy, false);
  assert.equal(inactive.session.modelId, "persisted-model");
});

test("ChatInput handleWebCliInput handles !cmd, busy guard, /restart, unsupported slash commands, and 403 WEB_CLI_CONTROL_FORBIDDEN", async () => {
  const runCase = async ({
    value,
    isGenerating = false,
    shellImpl = async () => {},
    restartImpl = async () => {},
  }: {
    value: string;
    isGenerating?: boolean;
    shellImpl?: (id: string, cmd: string) => Promise<void>;
    restartImpl?: (id: string) => Promise<void>;
  }) => {
    const shellCalls: Array<{ id: string; cmd: string }> = [];
    const restartCalls: string[] = [];
    const valueChanges: string[] = [];
    const errors: Array<string | null> = [];
    const toasts: string[] = [];
    let submitting = false;

    const handleWebCliInput = extractExecutable("components/input/chat-input.tsx", "handleWebCliInput", {
      React: { useCallback: (cb: unknown) => cb },
      isCliSession: true,
      isEditing: false,
      value,
      isGenerating,
      conversation: { id: "conv-1" },
      isUnsupportedWebCliInput,
      parseCliInput,
      isCliControlForbiddenError,
      runConversationCliShell: async (id: string, cmd: string) => {
        shellCalls.push({ id, cmd });
        await shellImpl(id, cmd);
      },
      restartConversationCli: async (id: string) => {
        restartCalls.push(id);
        await restartImpl(id);
      },
      onValueChange: (next: string) => valueChanges.push(next),
      setSubmitting: (next: boolean) => {
        submitting = next;
      },
      setError: (err: string | null) => errors.push(err),
      toast: { success: (msg: string) => toasts.push(msg) },
      t: (key: string) => key,
    });

    const handled = await handleWebCliInput();
    assert.equal(submitting, false);
    return { handled, shellCalls, restartCalls, valueChanges, errors, toasts };
  };

  // !cmd when idle -> calls shell endpoint and clears input
  const shellOk = await runCase({ value: "!ls -la" });
  assert.equal(shellOk.handled, true);
  assert.deepEqual(shellOk.shellCalls, [{ id: "conv-1", cmd: "ls -la" }]);
  assert.deepEqual(shellOk.valueChanges, [""]);
  assert.deepEqual(shellOk.errors, [null]);

  // !cmd when generating -> busy warning, no API call, input preserved
  const shellBusy = await runCase({ value: "!pwd", isGenerating: true });
  assert.equal(shellBusy.handled, true);
  assert.deepEqual(shellBusy.shellCalls, []);
  assert.deepEqual(shellBusy.valueChanges, []);
  assert.deepEqual(shellBusy.errors, ["chat.cli_shell_busy"]);

  // /restart -> calls restart endpoint, clears input, shows toast
  const restartOk = await runCase({ value: "/restart" });
  assert.equal(restartOk.handled, true);
  assert.deepEqual(restartOk.restartCalls, ["conv-1"]);
  assert.deepEqual(restartOk.valueChanges, [""]);
  assert.deepEqual(restartOk.toasts, ["chat.cli_restarted"]);

  // 403 WEB_CLI_CONTROL_FORBIDDEN -> localized forbidden error, input preserved
  const shellForbidden = await runCase({
    value: "!whoami",
    shellImpl: async () => {
      throw new ApiError("Forbidden", 403, WEB_CLI_CONTROL_FORBIDDEN_CODE);
    },
  });
  assert.equal(shellForbidden.handled, true);
  assert.deepEqual(shellForbidden.valueChanges, []);
  assert.equal(shellForbidden.errors.at(-1), "common:cli_control_forbidden");

  const restartForbidden = await runCase({
    value: "/restart",
    restartImpl: async () => {
      throw new ApiError("Forbidden", 403, WEB_CLI_CONTROL_FORBIDDEN_CODE);
    },
  });
  assert.equal(restartForbidden.handled, true);
  assert.deepEqual(restartForbidden.valueChanges, []);
  assert.equal(restartForbidden.errors.at(-1), "common:cli_control_forbidden");

  // Unsupported /login -> blocked locally
  const loginBlocked = await runCase({ value: "/login" });
  assert.equal(loginBlocked.handled, true);
  assert.deepEqual(loginBlocked.shellCalls, []);
  assert.deepEqual(loginBlocked.restartCalls, []);
  assert.deepEqual(loginBlocked.errors, ["chat.cli_command_unsupported"]);

  // Normal prompt -> not intercepted
  const prompt = await runCase({ value: "/compact" });
  assert.equal(prompt.handled, false);
});

test("CliSessionBadge renders interactive busy indicator with conversationId and readonly hint without conversationId", async () => {
  await i18next.changeLanguage("en-US");
  const interactiveBusy = renderToStaticMarkup(
    createElement(CliSessionBadge, {
      cliSession: { agentId: "builtin:agy", modelId: "claude-sonnet-4", permissionMode: "auto" },
      conversationId: "conv-1",
      busy: true,
    }),
  );
  assert.ok(interactiveBusy.includes('data-testid="cli-session-busy"'));
  assert.ok(!interactiveBusy.includes('data-testid="cli-session-readonly-hint"'));

  const readonly = renderToStaticMarkup(
    createElement(CliSessionBadge, {
      cliSession: { agentId: "builtin:droid", permissionMode: "ask" },
    }),
  );
  assert.ok(readonly.includes('data-testid="cli-session-readonly-hint"'));
});

test("conversations.tsx subscribes to cli_runtime and en-US/zh-CN Phase B locale keys are aligned", () => {
  const source = readFileSync(new URL("../app/routes/conversations.tsx", import.meta.url), "utf8");
  assert.match(source, /subscribeToEvent<CliRuntimeEvent>\(EVENT_CLI_RUNTIME/);
  assert.match(source, /cli-control-error-banner/);
  assert.equal(EVENT_CLI_RUNTIME, "cli_runtime");

  assert.equal(typeof enCommon.cli_control_forbidden, "string");
  assert.equal(typeof zhCommon.cli_control_forbidden, "string");
  assert.equal(typeof enInput.chat.cli_shell_busy, "string");
  assert.equal(typeof zhInput.chat.cli_shell_busy, "string");
  assert.equal(typeof enInput.chat.cli_restarted, "string");
  assert.equal(typeof zhInput.chat.cli_restarted, "string");
  assert.deepEqual(Object.keys(enPage.conversations.cli).sort(), Object.keys(zhPage.conversations.cli).sort());
});
