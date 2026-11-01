import type { TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { ModelSnapshot } from "./message";
import type { UIMessagePart } from "./parts";

export interface ConversationListDto {
  id: string;
  assistantId: string;
  title: string;
  isPinned: boolean;
  folderId?: string | null;
  createAt: number;
  updateAt: number;
  isGenerating: boolean;
}

/**
 * Folder (conversation grouping within an assistant)
 * @see app/src/main/java/me/rerere/rikkihub/web/dto/WebDto.kt - FolderDto
 */
export interface FolderDto {
  id: string;
  assistantId: string;
  name: string;
  sortIndex: number;
  createAt: number;
}

/**
 * Folder list change event (pushed on the shared `/api/events` stream)
 * @see app/src/main/java/me/rerere/rikkihub/web/dto/WebDto.kt - FolderListEvent
 */
export interface FolderListEventDto {
  assistantId: string;
  folders: FolderDto[];
}

export interface PagedResult<T> {
  items: T[];
  nextOffset?: number | null;
  hasMore: boolean;
}

export interface UploadedFileDto {
  id: number;
  url: string;
  fileName: string;
  mime: string;
  size: number;
}

export interface UploadFilesResponseDto {
  files: UploadedFileDto[];
}

export interface ConversationListInvalidateEventDto {
  type: "invalidate";
  assistantId: string;
  timestamp: number;
}

/**
 * Message DTO (for API response)
 * @see app/src/main/java/me/rerere/rikkihub/web/dto/WebDto.kt - MessageDto
 */
/** Server-validated chart_display result; the browser only draws it. */
export interface ChartAxisDto {
  title?: string | null;
  data: string[];
  min?: number | null;
  max?: number | null;
  scale: "linear" | "log";
  format?: string | null;
}

export interface ChartSpecDto {
  style: "line" | "bar" | "scatter";
  title?: string | null;
  xAxis: ChartAxisDto;
  yAxis: ChartAxisDto;
  series: Array<{
    name?: string | null;
    color?: string | null;
    values: number[];
    points: Array<{ x: number; y: number }>;
  }>;
}

export interface ChartDto {
  partIndex: number;
  spec: ChartSpecDto;
}

export interface MessageDto {
  id: string;
  role: string;
  parts: UIMessagePart[];
  annotations?: UIMessageAnnotation[];
  createdAt: string;
  finishedAt?: string | null;
  modelId?: string | null;
  /** 模型名称快照，模型被删除后用它显示来源 */
  modelSnapshot?: ModelSnapshot | null;
  /** 多步工具调用时是各步累计值 */
  usage?: TokenUsage | null;
  /** 模型实际生成耗时（毫秒），不含首 token 延迟、工具执行和审批等待 */
  generationTimeMs?: number | null;
  /** 最后一次请求自己的 usage（上下文占用、缓存命中看这个） */
  lastUsage?: TokenUsage | null;
  stepCount?: number | null;
  /** Validated charts by part index; absent when the message has none. */
  charts?: ChartDto[] | null;
  translation?: string | null;
  /** 压缩检查点：此消息是它之前全部历史的摘要，之前的消息不再发送给模型 */
  isContextCheckpoint?: boolean;
}

export interface MessageNodeDto {
  id: string;
  messages: MessageDto[];
  selectIndex: number;
  isFavorite?: boolean;
}

export interface ConversationDto {
  id: string;
  assistantId: string;
  title: string;
  messages: MessageNodeDto[];
  chatSuggestions: string[];
  isPinned: boolean;
  customSystemPrompt?: string | null;
  modeInjectionIds?: string[];
  lorebookIds?: string[];
  workspaceCwd?: string | null;
  /** 「仅此目录」：选了工作目录时模型只看得到该目录（服务端默认 true） */
  workspaceCwdScoped?: boolean;
  folderId?: string | null;
  createAt: number;
  updateAt: number;
  isGenerating: boolean;
  queueSize?: number;
  queuePaused?: boolean;
}

/**
 * Send message request body (POST /api/conversations/:id/messages)
 * @see app/src/main/java/me/rerere/rikkihub/web/dto/WebDto.kt - SendMessageRequest
 */
export interface SendMessageRequest {
  parts: UIMessagePart[];
  modeInjectionIds?: string[];
  lorebookIds?: string[];
  answer?: boolean;
}

/**
 * Queued (pending, not yet dispatched) message
 * @see app/src/main/java/me/rerere/rikkihub/web/dto/WebDto.kt - QueuedMessageDto
 */
export interface QueuedMessageDto {
  id: string;
  parts: UIMessagePart[];
  answer: boolean;
  isEditing: boolean;
}

/**
 * Message queue state (GET /api/conversations/:id/queue)
 * @see app/src/main/java/me/rerere/rikkihub/web/dto/WebDto.kt - MessageQueueDto
 */
export interface MessageQueueDto {
  messages: QueuedMessageDto[];
  paused: boolean;
}

/**
 * Queue change event (pushed on the per-conversation `/stream` SSE)
 * @see app/src/main/java/me/rerere/rikkihub/web/dto/WebDto.kt - ConversationQueueEvent
 */
export interface ConversationQueueEventDto {
  type: "queue_update";
  conversationId: string;
  queue: MessageQueueDto;
  serverTime: number;
}

export interface ConversationSnapshotEventDto {
  type: "snapshot";
  seq: number;
  conversation: ConversationDto;
  serverTime: number;
}

export interface ConversationNodeUpdateEventDto {
  type: "node_update";
  seq: number;
  conversationId: string;
  nodeId: string;
  nodeIndex: number;
  node: MessageNodeDto;
  updateAt: number;
  isGenerating: boolean;
  serverTime: number;
}

export interface ConversationErrorEventDto {
  type: "error";
  message: string;
}

/** Background compression state: SSE `compress_state` and GET/POST .../compress. */
export type CompressStatus = "running" | "succeeded" | "failed" | "cancelled";

export interface CompressStateDto {
  conversationId: string;
  status: CompressStatus;
  origin: "app" | "web";
  startedAt: number;
  finishedAt?: number | null;
  errorCode?: string | null;
  errorMessage?: string | null;
}

export interface CompressStateResponse {
  state: CompressStateDto | null;
}

/** GET /api/favorites item. */
export type FavoriteStatus = "ok" | "conversation_deleted" | "node_missing";

export interface FavoriteItemDto {
  id: string;
  conversationId: string;
  nodeId: string;
  assistantId?: string | null;
  title: string;
  preview: string;
  createdAt: number;
  status: FavoriteStatus;
}

export interface FavoriteListResponse {
  items: FavoriteItemDto[];
  truncated: boolean;
}

/** Original row returned by DELETE /api/favorites/{id}; handed back verbatim to restore. */
export interface FavoriteRecordDto {
  id: string;
  type: string;
  refKey: string;
  refJson: string;
  snapshotJson: string;
  metaJson?: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface MessageSearchResultDto {
  nodeId: string;
  messageId: string;
  conversationId: string;
  title: string;
  updateAt: number;
  snippet: string;
}
