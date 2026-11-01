/**
 * Display settings
 * @see app/src/main/java/me/rerere/rikkihub/data/datastore/PreferencesStore.kt - DisplaySetting
 */
export interface DisplaySetting {
  /** Android display preference; absent on older servers means OFF. Web only reads it. */
  workspaceMarkdownHeadingSizes?: boolean;
  /** Android-only workspace editor toolbar layout (single/double row) and button order by id. */
  workspaceEditorToolbarDoubleRow?: boolean;
  workspaceEditorToolbarOrder?: string[];
  /** Auto-closing pairs with a fill box, one switch per editor; absent means ON (the default is not serialized). */
  workspaceEditorAutoPair?: boolean;
  /** The Web editor's own switch: written only via PUT /api/display/workspace-editor. */
  webWorkspaceEditorAutoPair?: boolean;
  /** Workspace editor typography. The Web edits only its own (px) one; read via normalizeEditorTypography. */
  workspaceEditorTypography?: unknown;
  webWorkspaceEditorTypography?: unknown;
  chatFontFamily?: string;
  userNickname: string;
  userAvatar?: AssistantAvatar;
  showUserAvatar: boolean;
  showModelIcon?: boolean;
  showModelName: boolean;
  showTokenUsage: boolean;
  showThinkingContent: boolean;
  autoCloseThinking: boolean;
  codeBlockAutoWrap: boolean;
  codeBlockAutoCollapse: boolean;
  showLineNumbers: boolean;
  sendOnEnter: boolean;
  enableAutoScroll: boolean;
  fontSizeRatio: number;
  pasteLongTextAsFile: boolean;
  pasteLongTextThreshold: number;
  [key: string]: unknown;
}

export interface AssistantTag {
  id: string;
  name: string;
}

/**
 * Built-in tool override (rename/description/systemPrompt).
 * @see app/src/main/java/me/rerere/rikkihub/data/ai/tools/ToolOverride.kt
 */
export interface ToolOverride {
  name?: string;
  description?: string;
  systemPrompt?: string;
}

export interface AssistantAvatar {
  type?: string;
  content?: string;
  url?: string;
  [key: string]: unknown;
}

export interface AssistantQuickMessage {
  title: string;
  content: string;
}

export interface QuickMessage {
  id: string;
  title: string;
  content: string;
}

export type ReasoningLevel = "off" | "auto" | "low" | "medium" | "high" | "xhigh" | "max";

/**
 * On-device local tool options bound to an assistant.
 * @see app/src/main/java/me/rerere/rikkihub/data/ai/tools/local/LocalToolOption.kt
 */
export type LocalToolType =
  | "javascript_engine"
  | "time_info"
  | "clipboard"
  | "tts"
  | "ask_user"
  | "screen_time"
  | "calendar"
  | "chart_display";

export interface LocalToolOption {
  type: string;
  [key: string]: unknown;
}

export type InjectionPosition =
  | "before_system_prompt"
  | "after_system_prompt"
  | "top_of_chat"
  | "bottom_of_chat"
  | "at_depth";

export type InjectionRole = "system" | "user" | "assistant" | "tool";

export interface ModeInjectionProfile {
  type?: "mode";
  id: string;
  name: string;
  enabled?: boolean;
  priority?: number;
  position?: InjectionPosition;
  content?: string;
  injectDepth?: number;
  role?: InjectionRole;
}

export interface LorebookEntry {
  type?: "regex";
  id: string;
  name: string;
  enabled?: boolean;
  priority?: number;
  position?: InjectionPosition;
  content?: string;
  injectDepth?: number;
  role?: InjectionRole;
  keywords?: string[];
  useRegex?: boolean;
  caseSensitive?: boolean;
  scanDepth?: number;
  constantActive?: boolean;
}

export interface LorebookProfile {
  id: string;
  name: string;
  description?: string;
  enabled?: boolean;
  entries?: LorebookEntry[];
}

/** Which editor view the assistant's system prompt opens in; does not change what is sent. */
export type SystemPromptMode = "whole" | "sections";

/** One system prompt section; `title` is only a label and is never sent to the model. */
export interface PromptSection {
  id: string;
  title: string;
  content: string;
  enabled: boolean;
}

export interface AssistantProfile {
  id: string;
  chatModelId?: string | null;
  reasoningLevel?: ReasoningLevel | string | null;
  enableWebSearch?: boolean;
  mcpServers?: string[];
  modeInjectionIds?: string[];
  lorebookIds?: string[];
  allowConversationPromptInjection?: boolean;
  allowConversationSystemPrompt?: boolean;
  name: string;
  avatar?: AssistantAvatar;
  useAssistantAvatar?: boolean;
  tags: string[];
  quickMessageIds?: string[];
  temperature?: number | null;
  topP?: number | null;
  contextMessageLimit?: number;
  streamOutput?: boolean;
  maxTokens?: number | null;
  systemPrompt?: string;
  systemPromptMode?: SystemPromptMode;
  promptSections?: PromptSection[];
  messageTemplate?: string;
  workspaceId?: string | null;
  enableMemory?: boolean;
  useGlobalMemory?: boolean;
  /** 对话停下来后自动做梦（整理记忆）；关掉后只在手动「立即做梦」时整理 */
  autoConsolidateMemory?: boolean;
  enableRecentChatsReference?: boolean;
  enableTimeReminder?: boolean;
  timeReminderIntervalMinutes?: number;
  localTools?: LocalToolOption[];
  /** Names of enabled skills; the server drops names that no longer exist. */
  enabledSkills?: string[];
  [key: string]: unknown;
}

export interface McpToolOption {
  enable: boolean;
  name: string;
  description?: string | null;
  needsApproval?: boolean;
  /** 服务器声明的只读标记（每次同步刷新）；null = 未声明。 */
  readOnlyHint?: boolean | null;
  /** 并行执行的用户覆盖；null = 跟随 readOnlyHint。 */
  parallel?: boolean | null;
  [key: string]: unknown;
}

export interface McpHeaderPair {
  first: string;
  /** null = preserve stored value (masked echo); "" = explicit clear. */
  second: string | null;
  valueConfigured?: boolean;
}

export interface McpOAuthState {
  enabled?: boolean;
  [key: string]: unknown;
}

export interface McpCommonOptions {
  enable: boolean;
  name: string;
  headers?: McpHeaderPair[] | Array<[string, string]> | Array<{ first?: string; second?: string }>;
  tools: McpToolOption[];
  oauth?: McpOAuthState | null;
  [key: string]: unknown;
}

interface McpServerConfigBase {
  id: string;
  commonOptions: McpCommonOptions;
  url?: string;
}

export interface McpSseServerConfig extends McpServerConfigBase {
  type: "sse";
}

export interface McpStreamableHttpServerConfig extends McpServerConfigBase {
  type: "streamable_http";
}

export type McpServerConfig =
  | McpSseServerConfig
  | McpStreamableHttpServerConfig
  | (McpServerConfigBase & { type?: string });

export type ModelType = "CHAT" | "IMAGE" | "EMBEDDING";
export type ModelModality = "TEXT" | "IMAGE";
export type ModelAbility = "TOOL" | "REASONING";

export interface BuiltInTool {
  type?: string;
  [key: string]: unknown;
}

export interface ProviderModel {
  id: string;
  modelId: string;
  displayName: string;
  type: ModelType;
  inputModalities?: ModelModality[];
  outputModalities?: ModelModality[];
  abilities?: ModelAbility[];
  tools?: BuiltInTool[];
  customHeaders?: Array<{ name: string; value: string | null; valueConfigured?: boolean }>;
  customBodies?: Array<{ key: string; value: unknown; valueConfigured?: boolean }>;
  ccDisguise?: boolean;
  /** 上下文窗口大小（token），缺省 1,000,000 */
  contextLength?: number;
  [key: string]: unknown;
}

export type ProviderType = "openai" | "google" | "claude" | "codex" | "devin" | "antigravity";
export type ClaudePromptCacheTtl = "5m" | "1h";

interface ProviderProfileBase {
  customHeaders?: Array<{ name: string; value: string | null; valueConfigured?: boolean }>;
  id: string;
  enabled: boolean;
  name: string;
  models: ProviderModel[];
  apiKey?: string;
  baseUrl?: string;
}

export interface OpenAIProviderProfile extends ProviderProfileBase {
  type: "openai";
  apiKeyConfigured?: boolean;
  chatCompletionsPath?: string;
  responsesPath?: string;
  useResponseApi?: boolean;
  includeHistoryReasoning?: boolean;
}

export interface GoogleProviderProfile extends ProviderProfileBase {
  type: "google";
  apiKeyConfigured?: boolean;
  privateKeyConfigured?: boolean;
  /** Defaults off for old settings; retained but ineffective with Vertex AI. */
  useInteractionsApi?: boolean;
  vertexAI?: boolean;
  useServiceAccount?: boolean;
  privateKey?: string;
  serviceAccountEmail?: string;
  location?: string;
  projectId?: string;
}

export interface ClaudeProviderProfile extends ProviderProfileBase {
  type: "claude";
  apiKeyConfigured?: boolean;
  promptCaching?: boolean;
  promptCacheTtl?: ClaudePromptCacheTtl;
}

export interface CodexProviderProfile extends ProviderProfileBase {
  type: "codex";
}

export interface DevinProviderProfile extends ProviderProfileBase {
  type: "devin";
  clientVersion?: string;
  deviceSeed?: string;
}

export interface AntigravityProviderProfile extends ProviderProfileBase {
  type: "antigravity";
  /** Reported Antigravity client version; empty = automatic. */
  clientVersion?: string;
}

export type ProviderProfile =
  | OpenAIProviderProfile
  | GoogleProviderProfile
  | ClaudeProviderProfile
  | CodexProviderProfile
  | DevinProviderProfile
  | AntigravityProviderProfile;

export interface SearchServiceOption {
  id: string;
  type?: string;
  [key: string]: unknown;
}

/** `GET /providers/model-capabilities`: what the app would fill in for a model id; no window = null. */
export interface ModelCapabilities {
  inputModalities: ModelModality[];
  outputModalities: ModelModality[];
  abilities: ModelAbility[];
  contextLength: number | null;
}

export interface RemoteModelsResponse {
  models: ProviderModel[];
  error?: string | null;
}

/**
 * Global cache keep-alive config (claudelife)
 * @see claudelife/src/main/java/me/rerere/ai/claudelife/CacheKeepAliveConfig.kt
 */
/**
 * 工具调用并行执行（`Settings.toolExecution`）。
 * @see app/src/main/java/me/rerere/rikkihub/data/ai/tools/ToolConcurrencyPolicy.kt
 */
export interface ToolExecutionConfig {
  parallel: boolean;
  maxParallel: number;
  parallelOverrides?: Record<string, boolean>;
}

/** `GET /api/tool-execution` 的内置工具条目。 */
export interface BuiltinToolExecution {
  id: string;
  name: string;
  category: string;
  defaultParallel: boolean;
  parallel: boolean;
  overridden: boolean;
  /** 只有部分参数能并行（如 str_replace_editor 仅 view）。 */
  argumentLimited: boolean;
}

/** `GET /api/tool-execution` 的 MCP 工具条目。 */
export interface McpToolExecution {
  serverId: string;
  serverName: string;
  tool: string;
  enabled: boolean;
  readOnlyHint?: boolean | null;
  override?: boolean | null;
  parallel: boolean;
}

/** `GET/PUT /api/tool-execution` 响应。 */
export interface ToolExecutionState {
  parallel: boolean;
  maxParallel: number;
  maxParallelLimit: number;
  builtinTools: BuiltinToolExecution[];
  mcpTools: McpToolExecution[];
}

/** `PUT /api/tool-execution` 请求体（merge-patch，覆盖值 null = 恢复默认）。 */
export interface ToolExecutionPatch {
  parallel?: boolean;
  maxParallel?: number;
  builtinOverrides?: Record<string, boolean | null>;
  mcpTools?: { serverId: string; tool: string; parallel: boolean | null }[];
}

export interface CacheKeepAliveConfig {
  enabled: boolean;
  intervalSeconds: number;
  maxDurationMinutes: number;
}

/**
 * App settings (streamed via SSE)
 * @see app/src/main/java/me/rerere/rikkihub/data/datastore/PreferencesStore.kt - Settings
 */
export interface Settings {
  dynamicColor: boolean;
  themeId: string;
  developerMode: boolean;
  displaySetting: DisplaySetting;
  favoriteModels: string[];
  chatModelId: string;
  assistantId: string;
  providers: ProviderProfile[];
  assistants: AssistantProfile[];
  assistantTags: AssistantTag[];
  modeInjections?: ModeInjectionProfile[];
  lorebooks?: LorebookProfile[];
  mcpServers: McpServerConfig[];
  searchServices: SearchServiceOption[];
  quickMessages?: QuickMessage[];
  searchServiceSelected: number;
  cacheKeepAliveConfig?: CacheKeepAliveConfig;
  toolExecution?: ToolExecutionConfig;
  /** 推敲 settings as stored (null extensions = defaults); edited through /api/tuiqiao/config. */
  tuiqiao?: { extensions?: string[] | null; [key: string]: unknown };
  /** 再造 settings as stored; edited through /api/zaizao/config (recent requirements also by each sent round). */
  zaizao?: { includeAfter?: boolean; defaultType?: string; recentRequirements?: string[]; [key: string]: unknown };
  /** 聊天框润色 settings as stored; edited through /api/prompt-polish/config. */
  promptPolish?: { modelId?: string | null; includeContext?: boolean; [key: string]: unknown };
  /** builtin tool id -> 用户覆盖 (名称/描述/prompt)；工作区工具启用区用它显示当前 wire 名 */
  toolOverrides?: Record<string, ToolOverride>;
  [key: string]: unknown;
}
