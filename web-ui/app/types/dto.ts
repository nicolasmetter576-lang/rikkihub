import type { TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { ConversationCliSession } from "./conversation";
import type { ContextCheckpointMetadata, MessageStopReason, ModelSnapshot } from "./message";
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
  contextLength?: number | null;
  /** 同一消息各逻辑模型步骤已报告用量的累计 */
  usage?: TokenUsage | null;
  /** true = 完整成功累计；false = 生成中或已知不完整；null/缺省 = 旧数据未知 */
  usageComplete?: boolean | null;
  /** 异常结束原因（达到最大输出/内容拦截/工具步数上限/提前结束）；正常结束缺省为 null */
  stopReason?: MessageStopReason | null;
  /** 应用侧观测耗时（毫秒）：流式从首 chunk 起，非流式含请求等待；不含工具/审批 */
  generationTimeMs?: number | null;
  /** 最近逻辑模型步骤的 usage 参考，不是下一次请求的精确上下文容量 */
  lastUsage?: TokenUsage | null;
  stepCount?: number | null;
  /** Validated charts by part index; absent when the message has none. */
  charts?: ChartDto[] | null;
  /**
   * Text after the "display only" regex scripts, by part index (text and reasoning parts a script changed).
   * The stored text in `parts` is untouched; absent when no script applies.
   */
  display?: Record<string, string> | null;
  translation?: string | null;
  /** Per-turn title parsed from the reply's last-line `<rk-title>`; the tag itself is hidden when shown. */
  title?: string | null;
  /** 压缩检查点：此消息是它之前全部历史的摘要，之前的消息不再发送给模型 */
  isContextCheckpoint?: boolean;
  /** 检查点元数据（生成方式、模板、覆盖范围、用量等）；旧检查点缺省为 null */
  checkpointMeta?: ContextCheckpointMetadata | null;
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
  /** The character this conversation is with (a SillyTavern character card), if any. */
  characterId?: string | null;
  /** Workspace CLI session (read-only on Web). */
  cliSession?: ConversationCliSession | null;
  compression?: ConversationCompressionDto | null;
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
  /** "compress_and_retry": the reply overflowed the context window. */
  solution?: string | null;
}

export interface ConversationErrorDto {
  id: string;
  message: string;
  title?: string | null;
  /** "compress_and_retry": the reply overflowed the context window. */
  solution?: string | null;
  timestamp: number;
  affectsReply?: boolean;
}

export interface ConversationErrorsEventDto {
  type: "errors";
  conversationId: string;
  errors: ConversationErrorDto[];
}

/** Background compression state: SSE `compress_state` and GET/POST .../compress. */
export type CompressStatus = "running" | "awaiting_review" | "succeeded" | "failed" | "cancelled";

export interface CompressStateDto {
  conversationId: string;
  status: CompressStatus;
  /** "auto": started by automatic compression or an overflow retry. */
  origin: "app" | "web" | "auto";
  startedAt: number;
  finishedAt?: number | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  /** The generated summary while status is "awaiting_review". */
  reviewSummary?: string | null;
  requestCount?: number;
  completedChunks?: number;
  totalChunks?: number;
  chunked?: boolean;
  fromOriginal?: boolean;
  cacheBypassed?: boolean;
  carriedCount?: number;
  createNewConversation?: boolean;
  targetConversationId?: string | null;
  totalUsage?: TokenUsage | null;
}

export interface ContextRecordMessagePreviewDto {
  nodeId: string;
  messageId: string;
  nodeIndex: number;
  role: "user" | "assistant" | "system" | "tool";
  previewText: string;
}

export interface ContextCheckpointRecordDto {
  nodeId: string;
  messageId: string;
  nodeIndex: number;
  createdAt: string;
  isClear: boolean;
  summaryText: string;
  meta?: ContextCheckpointMetadata | null;
  rangeStartIndex?: number | null;
  rangeEndIndex?: number | null;
  rangeStartNodeId?: string | null;
  rangeEndNodeId?: string | null;
  coveredMessageCount: number;
  coveredPreviews: ContextRecordMessagePreviewDto[];
  isActive: boolean;
  laterCheckpointNodeIds: string[];
  supersededByNodeId?: string | null;
}

export interface ContextRecordsResponse {
  records: ContextCheckpointRecordDto[];
  canClear: boolean;
}

export interface ClearContextResponse {
  record: ContextCheckpointRecordDto;
  canClear: boolean;
}

export interface RevokeCheckpointResponse {
  removedNodeIds: string[];
}

export interface ContextUsageBreakdownDto {
  totalTokens: number;
  totalExact: boolean;
  reportedContextTokens?: number | null;
  estimatedTotalTokens: number;
  contextLength?: number | null;
  inputBudgetTokens?: number | null;
  systemPromptTokens: number;
  memoryTokens: number;
  lorebookAndTavernTokens: number;
  toolsLocalTokens: number;
  toolsMcpTokens: number;
  toolsSkillsTokens: number;
  toolsWorkspaceTokens: number;
  historyCheckpointTokens: number;
  historyMessagesTokens: number;
  attachmentsTokens: number;
  draftTokens: number;
  toolsLocalCount: number;
  toolsMcpCount: number;
  toolsSkillsCount: number;
  toolsWorkspaceCount: number;
  historyMessageCount: number;
  hasActiveCheckpoint: boolean;
  attachmentCount: number;
  activeLorebookCount: number;
  memoryFileCount: number;
}

export type AutoCompressMode = "off" | "notify" | "auto";

export type CarryOriginalsMode = "off" | "recent" | "auto";

export interface CarriedOriginalEditDto {
  userText?: string | null;
  replyText?: string | null;
}

export interface CarriedOriginalPreviewDto {
  nodeId: string;
  userText: string;
  replyText?: string | null;
  tokens: number;
  edited?: boolean;
}

/** A conversation's own compression settings; a missing field follows the global setting. */
export interface ConversationCompressionDto {
  mode?: AutoCompressMode | null;
  templateId?: string | null;
}

/** GET/POST .../compress/estimate: local estimate before compressing (no model call). */
export interface CompressEstimateDto {
  summarizedMessages: number;
  summarizedChars: number;
  summarizedTokens: number;
  keptMessages: number;
  keptTokens: number;
  beforeTokens: number;
  beforeExact: boolean;
  afterMinTokens: number;
  afterMaxTokens: number;
  budgetTokens?: number | null;
  carriedCount?: number;
  carriedTokens?: number;
  carriedOverBudget?: boolean;
  carriedSkippedForBudget?: number;
  carriedExcludedCount?: number;
  carriedItems?: CarriedOriginalPreviewDto[];
  hasPreviousCheckpoint?: boolean;
  fromOriginal?: boolean;
  willChunk?: boolean;
  estimatedChunks?: number;
  maxChunkRequests?: number;
  willBreakCache?: boolean;
}

/** SSE `compress_suggestion` ("notify only" mode); percent null clears it. */
export interface CompressSuggestionEventDto {
  type: "compress_suggestion";
  conversationId: string;
  percent?: number | null;
  exact: boolean;
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
