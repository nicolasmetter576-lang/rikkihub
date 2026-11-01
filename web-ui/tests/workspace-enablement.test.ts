import test from "node:test";
import assert from "node:assert/strict";

import {
  WORKSPACE_TOOL_ENABLEMENT_MINIMAL_IDS,
  WORKSPACE_TOOL_ENABLEMENT_NORMAL_IDS,
  WORKSPACE_TOOL_ENABLEMENT_UI_ENABLED,
  resolveWorkspaceToolWireName,
  workspaceQueryKeys,
  workspaceToolEnablementIds,
} from "../app/overlay/lib/workspace";

/* ---------- 稳定 ID 表（对照 WorkspaceToolEnablement.normalIds/minimalIds） ---------- */

test("enablement ids: 普通模式 5 项且顺序固定", () => {
  assert.deepEqual([...workspaceToolEnablementIds(false)], [
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
    "subagent",
  ]);
  assert.deepEqual([...WORKSPACE_TOOL_ENABLEMENT_NORMAL_IDS], workspaceToolEnablementIds(false));
});

test("enablement ids: 极简模式 3 项", () => {
  assert.deepEqual([...workspaceToolEnablementIds(true)], [
    "bash",
    "str_replace_editor",
    "subagent",
  ]);
  assert.deepEqual([...WORKSPACE_TOOL_ENABLEMENT_MINIMAL_IDS], workspaceToolEnablementIds(true));
});

test("enablement ids: 两模式并集共 7 个稳定 ID（与后端 configurableIds 一致）", () => {
  const union = new Set([
    ...WORKSPACE_TOOL_ENABLEMENT_NORMAL_IDS,
    ...WORKSPACE_TOOL_ENABLEMENT_MINIMAL_IDS,
  ]);
  assert.equal(union.size, 7);
});

/* ---------- Override 名显示 ---------- */

test("wire name: 无 override 时回退稳定 ID", () => {
  assert.equal(resolveWorkspaceToolWireName("bash", undefined), "bash");
  assert.equal(resolveWorkspaceToolWireName("bash", {}), "bash");
  assert.equal(resolveWorkspaceToolWireName("bash", { other: { name: "x" } }), "bash");
});

test("wire name: override.name 生效，空白名回退", () => {
  assert.equal(
    resolveWorkspaceToolWireName("bash", { bash: { name: "my_shell" } }),
    "my_shell",
  );
  assert.equal(
    resolveWorkspaceToolWireName("bash", { bash: { name: "   " } }),
    "bash",
  );
  assert.equal(
    resolveWorkspaceToolWireName("bash", { bash: { description: "only desc" } }),
    "bash",
  );
});

/* ---------- React Query keys / 功能开关 ---------- */

test("query keys: detail 是 all 的子前缀（invalidate all 同时覆盖 detail）", () => {
  assert.deepEqual([...workspaceQueryKeys.all], ["workspaces"]);
  assert.deepEqual([...workspaceQueryKeys.detail("abc")], ["workspaces", "abc"]);
  assert.equal(
    workspaceQueryKeys.detail("abc").slice(0, workspaceQueryKeys.all.length).join("/"),
    workspaceQueryKeys.all.join("/"),
  );
});

test("feature flag: T0–T2 闭环后工具启用 UI 入口已开放", () => {
  assert.equal(WORKSPACE_TOOL_ENABLEMENT_UI_ENABLED, true);
});
