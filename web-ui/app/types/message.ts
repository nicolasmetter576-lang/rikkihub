import type { MessageRole, TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { UIMessagePart } from "./parts";

/**
 * UI Message
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessage
 */
export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  annotations: UIMessageAnnotation[];
  createdAt: string;
  finishedAt?: string | null;
  modelId?: string | null;
  modelSnapshot?: ModelSnapshot | null;
  contextLength?: number | null;
  usage?: TokenUsage | null;
  /** true = complete successful usage; false = partial/in progress; null/absent = legacy unknown */
  usageComplete?: boolean | null;
  generationTimeMs?: number | null;
  lastUsage?: TokenUsage | null;
  stepCount?: number | null;
  translation?: string | null;
  title?: string | null;
  isContextCheckpoint?: boolean;
  checkpointMeta?: ContextCheckpointMetadata | null;
}

export type ContextCheckpointKind = "manual" | "auto" | "overflow_retry" | "clear";

export interface ContextCheckpointMetadata {
  kind?: ContextCheckpointKind | null;
  templateId?: string | null;
  fromOriginal?: boolean;
  chunked?: boolean;
  requestCount?: number | null;
  carriedCount?: number | null;
  rangeStartNodeId?: string | null;
  rangeEndNodeId?: string | null;
  summarizedCount?: number | null;
  supersededCheckpointNodeId?: string | null;
  usage?: TokenUsage | null;
}

/**
 * 消息上保存的模型名称快照，模型被删除后用它显示来源
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - ModelSnapshot
 */
export interface ModelSnapshot {
  modelId: string;
  displayName: string;
}
