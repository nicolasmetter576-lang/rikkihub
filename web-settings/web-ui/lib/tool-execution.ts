/**
 * 工具调用并行执行：纯逻辑 + API helper。
 * 纯函数部分零外部依赖，node:test 可单测；API 部分走 `~/services/api`。
 * @see web-settings/src/main/java/me/rerere/rikkihub/web/routes/ToolExecutionRoutes.kt
 */

import api from "~/services/api";
import type {
  BuiltinToolExecution,
  McpToolExecution,
  ToolExecutionPatch,
  ToolExecutionState,
} from "@overlay/types/settings";

export function fetchToolExecution(): Promise<ToolExecutionState> {
  return api.get<ToolExecutionState>("tool-execution");
}

export function updateToolExecution(patch: ToolExecutionPatch): Promise<ToolExecutionState> {
  return api.put<ToolExecutionState>("tool-execution", patch);
}

/** 内置工具开关的 patch：与默认值相同时发 null（移除覆盖，跟随以后的默认分类）。 */
export function builtinParallelPatch(tool: BuiltinToolExecution, parallel: boolean): ToolExecutionPatch {
  return { builtinOverrides: { [tool.id]: parallel === tool.defaultParallel ? null : parallel } };
}

/** MCP 工具开关的 patch：与服务器只读声明一致时发 null（继续跟随声明）。 */
export function mcpParallelPatch(tool: McpToolExecution, parallel: boolean | null): ToolExecutionPatch {
  const follows = tool.readOnlyHint === true;
  return {
    mcpTools: [
      {
        serverId: tool.serverId,
        tool: tool.tool,
        parallel: parallel === null || parallel === follows ? null : parallel,
      },
    ],
  };
}

/** 按分类分组内置工具，保持服务端顺序（分类首次出现的顺序）。 */
export function groupBuiltinTools(
  tools: BuiltinToolExecution[],
): { category: string; tools: BuiltinToolExecution[] }[] {
  const groups = new Map<string, BuiltinToolExecution[]>();
  for (const tool of tools) {
    const list = groups.get(tool.category);
    if (list) list.push(tool);
    else groups.set(tool.category, [tool]);
  }
  return [...groups.entries()].map(([category, list]) => ({ category, tools: list }));
}

/** 按服务器分组 MCP 工具，保持服务端顺序。 */
export function groupMcpTools(
  tools: McpToolExecution[],
): { serverId: string; serverName: string; tools: McpToolExecution[] }[] {
  const groups = new Map<string, { serverId: string; serverName: string; tools: McpToolExecution[] }>();
  for (const tool of tools) {
    const group = groups.get(tool.serverId);
    if (group) group.tools.push(tool);
    else groups.set(tool.serverId, { serverId: tool.serverId, serverName: tool.serverName, tools: [tool] });
  }
  return [...groups.values()];
}

/** MCP 工具当前来源说明的 i18n key。 */
export function mcpParallelSourceKey(tool: McpToolExecution): string {
  if (tool.override !== null && tool.override !== undefined) return "tool_execution.mcp_manual";
  return tool.readOnlyHint === true
    ? "tool_execution.mcp_follow_readonly"
    : "tool_execution.mcp_follow_not_readonly";
}
