import type {
  AssistantProfile as OverlayAssistantProfile,
  Settings as OverlaySettings,
} from "@overlay/types/settings";
import type { CliPermissionMode } from "./conversation";

export type {
  AntigravityProviderProfile,
  AssistantAvatar,
  AssistantQuickMessage,
  AssistantTag,
  BuiltInTool,
  BuiltinToolExecution,
  CacheKeepAliveConfig,
  ClaudePromptCacheTtl,
  ClaudeProviderProfile,
  CodexProviderProfile,
  DevinProviderProfile,
  DisplaySetting,
  GoogleProviderProfile,
  LocalToolOption,
  LocalToolType,
  McpCommonOptions,
  McpHeaderPair,
  McpOAuthState,
  McpServerConfig,
  McpSseServerConfig,
  McpStreamableHttpServerConfig,
  McpToolExecution,
  McpToolOption,
  ModelAbility,
  ModelCapabilities,
  ModelModality,
  ModelType,
  OpenAIProviderProfile,
  ProviderModel,
  ProviderProfile,
  ProviderType,
  QuickMessage,
  ReasoningLevel,
  RemoteModelsResponse,
  SearchServiceOption,
  ToolExecutionConfig,
  ToolExecutionPatch,
  ToolExecutionState,
  ToolOverride,
} from "@overlay/types/settings";

/**
 * User-defined ACP agent config (projected for Web: `envVars[].value` is masked).
 * @see app/src/main/java/me/rerere/rikkihub/data/cli/CliAgents.kt - CustomCliAgentConfig
 */
export interface CustomCliAgentConfig {
  id: string;
  name: string;
  executable: string;
  args?: string[];
  envVars?: Array<{ name: string; value: string | null; valueConfigured?: boolean }>;
  loginCommand?: string;
  installCommand?: string;
}

/**
 * Extra environment variables for a built-in CLI agent.
 * @see app/src/main/java/me/rerere/rikkihub/data/cli/CliAgents.kt - CliEnvOverride
 */
export interface CliEnvOverride {
  id: string;
  envVars?: Array<{ name: string; value: string | null; valueConfigured?: boolean }>;
}

export interface AssistantProfile extends OverlayAssistantProfile {
  defaultCliAgentId?: string | null;
  defaultCliPermissionMode?: CliPermissionMode | null;
}

export interface Settings extends OverlaySettings {
  assistants: AssistantProfile[];
  customCliAgents?: CustomCliAgentConfig[];
  builtInCliEnvOverrides?: CliEnvOverride[];
}
