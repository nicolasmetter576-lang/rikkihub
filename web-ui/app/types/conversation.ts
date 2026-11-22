import type { UIMessage } from "./message";

/**
 * Permission mode for a workspace CLI session.
 * Kotlin serializes `@SerialName("ask" | "auto")`; uppercase literals are also accepted.
 * @see app/src/main/java/me/rerere/rikkihub/data/cli/CliAgents.kt - CliPermissionMode
 */
export type CliPermissionMode = "ask" | "auto" | "ASK" | "AUTO";

/**
 * Workspace CLI session state on a conversation.
 * @see app/src/main/java/me/rerere/rikkihub/data/cli/CliAgents.kt - ConversationCliSession
 */
export interface ConversationCliSession {
  agentId: string;
  acpSessionId?: string | null;
  permissionMode: CliPermissionMode;
  modelId?: string | null;
  modeId?: string | null;
  configValues?: Record<string, string>;
  systemPromptHash?: string | null;
  historyFingerprint?: string | null;
  needsHistorySeed?: boolean;
  lastActiveAtMillis?: number | null;
}

/**
 * Live ACP runtime snapshot broadcast over `/api/events` (`event: cli_runtime`).
 * @see app/src/main/java/me/rerere/rikkihub/web/routes/EventsRoutes.kt - CliRuntimeSseEvent
 */
export interface CliRuntimeEvent {
  active: boolean;
  conversationId?: string | null;
  agentId?: string | null;
  busy?: boolean;
  currentModelId?: string | null;
  currentModeId?: string | null;
  usedTokens?: number | null;
  contextTokens?: number | null;
  title?: string | null;
}

/**
 * Message node - container for message branching
 * @see app/src/main/java/me/rerere/rikkihub/data/model/Conversation.kt - MessageNode
 */
export interface MessageNode {
  id: string;
  messages: UIMessage[];
  selectIndex: number;
}

/**
 * Conversation
 * @see app/src/main/java/me/rerere/rikkihub/data/model/Conversation.kt - Conversation
 */
export interface Conversation {
  id: string;
  assistantId: string;
  title: string;
  messageNodes: MessageNode[];
  chatSuggestions: string[];
  isPinned: boolean;
  customSystemPrompt?: string | null;
  modeInjectionIds?: string[];
  lorebookIds?: string[];
  /** Absolute path inside the workspace rootfs */
  workspaceCwd?: string | null;
  /** 所属文件夹（助手内分组），null 表示未归入任何文件夹 */
  folderId?: string | null;
  cliSession?: ConversationCliSession | null;
  createAt: number;
  updateAt: number;
}
