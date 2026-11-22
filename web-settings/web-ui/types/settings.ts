import type { LorebookSummary, PromptEntry, PromptPreset, TavernSettings } from "./tavern";
import type { ModelPricing } from "../lib/model-pricing";

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
  | "chart_display"
  | "pseudo_io";

export interface LocalToolOption {
  type: string;
  [key: string]: unknown;
}

export interface AssistantProfile {
  id: string;
  chatModelId?: string | null;
  reasoningLevel?: ReasoningLevel | string | null;
  enableWebSearch?: boolean;
  mcpServers?: string[];
  /** Prompt library entries (identifiers) the assistant uses. */
  libraryEntryIds?: string[];
  lorebookIds?: string[];
  allowConversationPromptInjection?: boolean;
  /** SillyTavern mode: the full prompt layout applies; off sends only the main prompt and the user's own entries. */
  tavernMode?: boolean;
  allowConversationSystemPrompt?: boolean;
  name: string;
  /** false hides the assistant wherever one is picked (the current one stays usable); missing means enabled. */
  enabled?: boolean;
  avatar?: AssistantAvatar;
  useAssistantAvatar?: boolean;
  tags: string[];
  quickMessageIds?: string[];
  temperature?: number | null;
  topP?: number | null;
  /** Extra sampling parameters (SillyTavern presets bring them); null or missing means "not sent". */
  topK?: number | null;
  frequencyPenalty?: number | null;
  presencePenalty?: number | null;
  repetitionPenalty?: number | null;
  minP?: number | null;
  topA?: number | null;
  seed?: number | null;
  contextMessageLimit?: number;
  streamOutput?: boolean;
  enableMessageTitle?: boolean;
  maxTokens?: number | null;
  /** The assistant's prompt: entries, order, assembly options and assistant-scoped regex scripts (SillyTavern preset JSON). */
  promptPreset?: PromptPreset;
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
  pricing?: ModelPricing | null;
  [key: string]: unknown;
}

export type ProviderType = "openai" | "google" | "claude" | "codex" | "devin" | "antigravity";
export type ClaudePromptCacheTtl = "5m" | "1h";
export type CredentialRotationStrategy = "round_robin" | "failover";

export interface ApiKeyEntry {
  id: string;
  label?: string;
  enabled?: boolean;
  key?: string;
  keyConfigured?: boolean;
  keyPreview?: string;
}

export interface VertexServiceAccount {
  id: string;
  label?: string;
  projectId?: string;
  serviceAccountEmail?: string;
  location?: string;
  enabled?: boolean;
  privateKey?: string;
  privateKeyConfigured?: boolean;
}

export interface CredentialRuntimeStatus {
  credentialId: string;
  status: "healthy" | "cooling_down" | "auth_error";
  cooldownUntil: number;
  remainingMillis: number;
  consecutiveRateLimits: number;
  lastStatusCode?: number | null;
  lastErrorSummary?: string | null;
  modelCooldowns?: Record<string, number>;
}

export interface CredentialStatusResponse {
  statuses: Record<string, CredentialRuntimeStatus>;
}

export interface TestCredentialResponse {
  ok: boolean;
  modelCount?: number | null;
  error?: string | null;
}

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
  apiKeys?: ApiKeyEntry[];
  rotationStrategy?: CredentialRotationStrategy;
  chatCompletionsPath?: string;
  responsesPath?: string;
  useResponseApi?: boolean;
  includeHistoryReasoning?: boolean;
}

export interface GoogleProviderProfile extends ProviderProfileBase {
  type: "google";
  apiKeyConfigured?: boolean;
  apiKeys?: ApiKeyEntry[];
  privateKeyConfigured?: boolean;
  serviceAccounts?: VertexServiceAccount[];
  rotationStrategy?: CredentialRotationStrategy;
  /** Defaults off for old settings; retained but ineffective with Vertex AI. */
  useInteractionsApi?: boolean;
  /** Vertex only; missing (older settings) means on. */
  streamFunctionCallArguments?: boolean;
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
  apiKeys?: ApiKeyEntry[];
  rotationStrategy?: CredentialRotationStrategy;
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
  referencePrice?: ModelPricing | null;
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
  /** The prompt library (SillyTavern prompt entries). */
  promptLibrary?: PromptEntry[];
  /** Summaries only; a book's entries come from `GET /api/lorebooks/{id}`. */
  lorebooks?: LorebookSummary[];
  tavern?: TavernSettings;
  mcpServers: McpServerConfig[];
  searchServices: SearchServiceOption[];
  quickMessages?: QuickMessage[];
  searchServiceSelected: number;
  cacheKeepAliveConfig?: CacheKeepAliveConfig;
  /** 去八股 settings without the rule packs (those come from /api/qubagu/config). */
  qubagu?: { enabled?: boolean; [key: string]: unknown };
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
