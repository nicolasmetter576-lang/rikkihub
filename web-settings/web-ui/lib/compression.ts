/**
 * 上下文压缩：全局设置、会话设置与预估的读写（与 App「设置 → 上下文压缩」、压缩对话框同一份配置）。
 * 触发判断、预估计算、原话挑选与模板指令都在服务端（与 App 共用 Kotlin 代码）。
 * @see web-settings/src/main/java/me/rerere/rikkihub/web/routes/CompressRoutes.kt
 */

import api from "~/services/api";
import type {
  AutoCompressMode,
  CarriedOriginalEditDto,
  CarryOriginalsMode,
  ClearContextResponse,
  ContextRecordsResponse,
  ContextUsageBreakdownDto,
  CompressEstimateDto,
  ConversationCompressionDto,
  MessageNodeDto,
  RevokeCheckpointResponse,
} from "~/types";

export const BUILT_IN_TEMPLATE_IDS = ["general", "code", "writing", "research", "roleplay"] as const;

export interface CompressionTemplate {
  id: string;
  name: string;
  prompt: string;
}

export interface CompressionConfig {
  mode: AutoCompressMode;
  triggerPercent: number;
  targetPercent: number;
  keepRecent: number;
  templateId: string;
  summaryTokens: number;
  /** null = same as the chat model (shares its prompt cache). */
  modelId: string | null;
  /** The chosen compression model no longer exists; the chat model is used. */
  modelMissing: boolean;
  reviewBeforeApply: boolean;
  carryOriginalsMode: CarryOriginalsMode;
  carryOriginalsCount: number;
  carryIncludeReplies: boolean;
  maxChunkRequests: number;
  chunkTimeoutSeconds: number | null;
  customTemplates: CompressionTemplate[];
  builtInTemplates: Array<{ id: string; prompt: string }>;
  minTriggerPercent: number;
  maxTriggerPercent: number;
  minTargetPercent: number;
  maxKeepRecent: number;
  minSummaryTokens: number;
  maxSummaryTokens: number;
  maxCustomTemplates: number;
  maxTemplateName: number;
  maxTemplatePrompt: number;
  minCarryCount: number;
  maxCarryCount: number;
  minMaxChunkRequests: number;
  maxMaxChunkRequests: number;
  minChunkTimeoutSeconds: number;
  maxChunkTimeoutSeconds: number;
}

export type CompressionConfigPatch = Partial<
  Pick<
    CompressionConfig,
    | "mode"
    | "triggerPercent"
    | "targetPercent"
    | "keepRecent"
    | "templateId"
    | "summaryTokens"
    | "modelId"
    | "reviewBeforeApply"
    | "carryOriginalsMode"
    | "carryOriginalsCount"
    | "carryIncludeReplies"
    | "maxChunkRequests"
    | "chunkTimeoutSeconds"
    | "customTemplates"
  >
>;

export interface CompressEstimateOptions {
  additionalPrompt?: string;
  keepRecent: number;
  templateId?: string | null;
  fromOriginal?: boolean;
  carryMode?: CarryOriginalsMode;
  carryCount?: number;
  carryIncludeReplies?: boolean;
  carryExcludedNodeIds?: string[];
  carryEdits?: Record<string, CarriedOriginalEditDto>;
}

export function fetchCompressionConfig(): Promise<CompressionConfig> {
  return api.get<CompressionConfig>("compression/config");
}

export function updateCompressionConfig(patch: CompressionConfigPatch): Promise<CompressionConfig> {
  return api.put<CompressionConfig>("compression/config", patch);
}

export function updateConversationCompression(
  conversationId: string,
  value: ConversationCompressionDto,
): Promise<ConversationCompressionDto> {
  return api.put<ConversationCompressionDto>(`conversations/${conversationId}/compression`, {
    mode: value.mode ?? null,
    templateId: value.templateId ?? null,
  });
}

export async function fetchCompressEstimate(
  conversationId: string,
  options: CompressEstimateOptions,
): Promise<CompressEstimateDto | null> {
  const response = await api.post<{ estimate: CompressEstimateDto | null }>(
    `conversations/${conversationId}/compress/estimate`,
    options,
  );
  return response.estimate;
}

/** Every template id in display order: built-ins first, then the user's own. */
export function templateIds(config: CompressionConfig): string[] {
  return [...BUILT_IN_TEMPLATE_IDS, ...config.customTemplates.map((template) => template.id)];
}

export function getContextRecords(conversationId: string): Promise<ContextRecordsResponse> {
  return api.get<ContextRecordsResponse>(`conversations/${conversationId}/context-records`);
}

export function clearConversationContext(conversationId: string): Promise<ClearContextResponse> {
  return api.post<ClearContextResponse>(`conversations/${conversationId}/context-records/clear`, {});
}

export function revokeContextRecord(
  conversationId: string,
  nodeId: string,
  cascade = false,
): Promise<RevokeCheckpointResponse> {
  const qs = cascade ? "?cascade=true" : "?cascade=false";
  return api.delete<RevokeCheckpointResponse>(
    `conversations/${conversationId}/context-records/${nodeId}${qs}`,
  );
}

export function getContextUsageBreakdown(
  conversationId: string,
  draft?: { draftText?: string; draftImageCount?: number },
): Promise<ContextUsageBreakdownDto> {
  const draftText = draft?.draftText ?? "";
  const draftImageCount = draft?.draftImageCount ?? 0;
  if (!draftText && draftImageCount <= 0) {
    return api.get<ContextUsageBreakdownDto>(`conversations/${conversationId}/context-usage`);
  }
  return api.post<ContextUsageBreakdownDto>(`conversations/${conversationId}/context-usage`, {
    draftText,
    draftImageCount,
  });
}

export function affectedCheckpointsForMessage(
  nodes: MessageNodeDto[],
  messageId: string,
): MessageNodeDto[] {
  const idx = nodes.findIndex((node) => node.messages.some((msg) => msg.id === messageId));
  if (idx < 0) return [];
  const target = nodes[idx].messages[nodes[idx].selectIndex] ?? nodes[idx].messages[0];
  if (target?.isContextCheckpoint) return [];
  return nodes.slice(idx + 1).filter((node) => {
    const current = node.messages[node.selectIndex] ?? node.messages[0];
    return Boolean(current?.isContextCheckpoint);
  });
}

export function laterCheckpointsForCheckpointMessage(
  nodes: MessageNodeDto[],
  messageId: string,
): { nodeId: string; laterCount: number } | null {
  const idx = nodes.findIndex((node) => node.messages.some((msg) => msg.id === messageId));
  if (idx < 0) return null;
  const laterCount = nodes.slice(idx + 1).filter((node) => {
    const current = node.messages[node.selectIndex] ?? node.messages[0];
    return Boolean(current?.isContextCheckpoint);
  }).length;
  return { nodeId: nodes[idx].id, laterCount };
}

/**
 * Display name of a template: a custom one by its own name, a built-in by its translation key
 * (`settings:compression.templates.<id>`). Unknown ids show as the general template, like the server.
 */
export function templateName(
  config: CompressionConfig,
  id: string,
  t: (key: string) => string,
): string {
  const custom = config.customTemplates.find((template) => template.id === id);
  if (custom) return custom.name;
  const builtIn = (BUILT_IN_TEMPLATE_IDS as readonly string[]).includes(id) ? id : "general";
  return t(`settings:compression.templates.${builtIn}`);
}
