import type { MessageDto, MessageNodeDto } from "~/types";

// Same thresholds as the app (ChatSizeChecker.kt); both must be exceeded to warn.
export const MESSAGE_NODE_WARNING_THRESHOLD = 768;
export const LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD = 300_000;

export interface ConversationSizeInfo {
  nodeCount: number;
  lastAssistantInputTokens: number;
  showWarning: boolean;
}

function currentMessage(node: MessageNodeDto): MessageDto | undefined {
  return node.messages[node.selectIndex] ?? node.messages[0];
}

/**
 * The last assistant reply, unless a compression checkpoint makes its recorded usage out of date
 * (same as the app). That is the case when the checkpoint comes after the reply, or when the reply
 * was one of the recent messages kept after the checkpoint: those were generated before it.
 */
function freshLastReply(nodes: MessageNodeDto[]): MessageDto | null {
  let checkpointIndex = -1;
  let replyIndex = -1;
  for (let index = nodes.length - 1; index >= 0; index -= 1) {
    const message = currentMessage(nodes[index]);
    if (checkpointIndex < 0 && message?.isContextCheckpoint) checkpointIndex = index;
    if (replyIndex < 0 && message?.role === "ASSISTANT") replyIndex = index;
    if (checkpointIndex >= 0 && replyIndex >= 0) break;
  }
  if (replyIndex < 0) return null;
  const reply = currentMessage(nodes[replyIndex]) ?? null;
  if (checkpointIndex < 0 || !reply) return reply;
  if (checkpointIndex > replyIndex) return null;
  const checkpoint = currentMessage(nodes[checkpointIndex]);
  // ISO local date-times of one device order the same as text.
  if (reply.createdAt && checkpoint?.createdAt && reply.createdAt < checkpoint.createdAt) return null;
  return reply;
}

export function conversationSizeInfo(nodes: MessageNodeDto[]): ConversationSizeInfo {
  const reply = freshLastReply(nodes);
  // Multi-step replies sum usage across steps; the context size is the last request's input.
  const lastAssistantInputTokens = reply ? ((reply.lastUsage ?? reply.usage)?.promptTokens ?? 0) : 0;
  return {
    nodeCount: nodes.length,
    lastAssistantInputTokens,
    showWarning:
      nodes.length > MESSAGE_NODE_WARNING_THRESHOLD &&
      lastAssistantInputTokens > LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD,
  };
}

/**
 * Context used by the latest reply (last request's prompt + completion), or null when there is no
 * reply or a compression checkpoint was inserted after it (same as the app's lastContextTokens).
 */
export function lastContextTokens(nodes: MessageNodeDto[]): number | null {
  const reply = freshLastReply(nodes);
  const usage = reply ? (reply.lastUsage ?? reply.usage) : null;
  return usage ? usage.promptTokens + usage.completionTokens : null;
}
