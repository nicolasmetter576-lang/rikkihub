import test from "node:test";
import assert from "node:assert/strict";

import {
  builtinParallelPatch,
  groupBuiltinTools,
  groupMcpTools,
  mcpParallelPatch,
  mcpParallelSourceKey,
} from "../../web-settings/web-ui/lib/tool-execution";
import type { BuiltinToolExecution, McpToolExecution } from "../../web-settings/web-ui/types/settings";

const builtin = (id: string, category: string, defaultParallel: boolean): BuiltinToolExecution => ({
  id,
  name: id,
  category,
  defaultParallel,
  parallel: defaultParallel,
  overridden: false,
  argumentLimited: false,
});

const mcp = (serverId: string, tool: string, readOnlyHint: boolean | null, override: boolean | null): McpToolExecution => ({
  serverId,
  serverName: `server-${serverId}`,
  tool,
  enabled: true,
  readOnlyHint,
  override,
  parallel: override ?? readOnlyHint === true,
});

test("builtin patch clears the override when it matches the default", () => {
  const read = builtin("workspace_read_file", "WORKSPACE", true);
  assert.deepEqual(builtinParallelPatch(read, true), { builtinOverrides: { workspace_read_file: null } });
  assert.deepEqual(builtinParallelPatch(read, false), { builtinOverrides: { workspace_read_file: false } });
  const write = builtin("workspace_write_file", "WORKSPACE", false);
  assert.deepEqual(builtinParallelPatch(write, true), { builtinOverrides: { workspace_write_file: true } });
});

test("mcp patch follows the server read-only hint unless it differs", () => {
  const readOnly = mcp("s1", "search", true, null);
  assert.deepEqual(mcpParallelPatch(readOnly, true).mcpTools, [{ serverId: "s1", tool: "search", parallel: null }]);
  assert.deepEqual(mcpParallelPatch(readOnly, false).mcpTools, [{ serverId: "s1", tool: "search", parallel: false }]);
  const undeclared = mcp("s1", "write", null, null);
  assert.deepEqual(mcpParallelPatch(undeclared, true).mcpTools, [{ serverId: "s1", tool: "write", parallel: true }]);
  assert.deepEqual(mcpParallelPatch(undeclared, false).mcpTools, [{ serverId: "s1", tool: "write", parallel: null }]);
  assert.deepEqual(mcpParallelPatch(mcp("s1", "x", null, true), null).mcpTools, [{ serverId: "s1", tool: "x", parallel: null }]);
});

test("grouping keeps server order", () => {
  const groups = groupBuiltinTools([
    builtin("a", "LOCAL", true),
    builtin("b", "WORKSPACE", true),
    builtin("c", "LOCAL", false),
  ]);
  assert.deepEqual(groups.map((g) => [g.category, g.tools.map((t) => t.id)]), [
    ["LOCAL", ["a", "c"]],
    ["WORKSPACE", ["b"]],
  ]);
  const servers = groupMcpTools([mcp("s2", "a", null, null), mcp("s1", "b", null, null), mcp("s2", "c", null, null)]);
  assert.deepEqual(servers.map((g) => [g.serverId, g.tools.map((t) => t.tool)]), [
    ["s2", ["a", "c"]],
    ["s1", ["b"]],
  ]);
});

test("mcp source key reflects manual vs server hint", () => {
  assert.equal(mcpParallelSourceKey(mcp("s", "t", true, null)), "tool_execution.mcp_follow_readonly");
  assert.equal(mcpParallelSourceKey(mcp("s", "t", null, null)), "tool_execution.mcp_follow_not_readonly");
  assert.equal(mcpParallelSourceKey(mcp("s", "t", true, false)), "tool_execution.mcp_manual");
});
