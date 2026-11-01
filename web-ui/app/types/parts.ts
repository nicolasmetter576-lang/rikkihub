/**
 * Tool approval state
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - ToolApprovalState
 */
export type ToolApprovalState =
  | { type: "auto" }
  | { type: "pending" }
  | { type: "approved" }
  | { type: "denied"; reason: string }
  | { type: "answered"; answer: string };

/** Optional worker-role identity, published in tool metadata (not the delegated task title). */
export interface SubagentPresetMetadata {
  presetId?: string | null;
  presetKey?: string | null;
  presetName?: string | null;
  presetRevision?: number | null;
}

interface BaseMessagePart {
  metadata?: Record<string, unknown> | null;
}

export interface TextPart extends BaseMessagePart {
  type: "text";
  text: string;
}

export interface ImagePart extends BaseMessagePart {
  type: "image";
  url: string;
}

export interface VideoPart extends BaseMessagePart {
  type: "video";
  url: string;
}

export interface AudioPart extends BaseMessagePart {
  type: "audio";
  url: string;
}

export interface DocumentPart extends BaseMessagePart {
  type: "document";
  url: string;
  fileName: string;
  mime: string;
}

export interface ReasoningPart extends BaseMessagePart {
  type: "reasoning";
  reasoning: string;
  createdAt?: string;
  finishedAt?: string | null;
}

/**
 * Workspace tool identity assigned at assembly time.
 * @see ai/src/main/java/me/rerere/ai/ui/WorkspaceToolBinding.kt - WorkspaceToolIdentity
 */
export interface WorkspaceToolIdentity {
  workspaceId: string;
  /** stable builtin id, e.g. "workspace_read_file" / "bash" / "subagent" (not a wire name) */
  stableId: string;
  implementationId?: string;
  /** "normal" | "harness_minimal" */
  mode: string;
  viewFingerprint?: string | null;
  /** per-stableId revocation epoch captured at issue time */
  activationEpoch?: number;
  /**
   * Process-local random nonce distinguishing activation epochs across process
   * restarts — an old unfinished binding never collides with a reused epoch.
   * Never sent on the provider wire; issued locally at binding creation.
   */
  activationSession?: string | null;
}

/**
 * Local binding issued by the producer (App) for workspace tool calls.
 * Web only ever *receives* this for display/serialization fidelity — the HMAC
 * signature is never shown to the model and web input boundaries strip bindings
 * server-side; do not construct or mutate it here.
 * @see ai/src/main/java/me/rerere/ai/ui/WorkspaceToolBinding.kt - LocalWorkspaceToolBinding
 */
export interface LocalWorkspaceToolBinding {
  identity: WorkspaceToolIdentity;
  originalWireName: string;
  conversationId: string;
  messageId: string;
  toolCallId: string;
  issuedAt: number;
  signature: string;
}

export interface ToolPart extends BaseMessagePart {
  metadata?: (Record<string, unknown> & SubagentPresetMetadata) | null;
  type: "tool";
  toolCallId: string;
  toolName: string;
  input: string;
  output: UIMessagePart[];
  approvalState: ToolApprovalState;
  /** Producer-issued local workspace binding; absent for non-workspace tools. */
  localBinding?: LocalWorkspaceToolBinding | null;
  /** When the app actually started running the tool (ISO instant); absent for denied, answered and old calls. */
  startedAt?: string | null;
  /** When it stopped running, or was cancelled by the user. */
  finishedAt?: string | null;
}

/** Provider-executed tool: opaque JSON, display only, never client approval/execution. */
export interface ServerToolPart extends BaseMessagePart {
  type: "server_tool";
  toolCallId: string;
  toolName: string;
  input?: unknown;
  output?: unknown;
  status: "in_progress" | "completed" | "failed";
}

/**
 * Union type for all message parts
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessagePart
 */
export type UIMessagePart =
  | TextPart
  | ImagePart
  | VideoPart
  | AudioPart
  | DocumentPart
  | ReasoningPart
  | ToolPart
  | ServerToolPart;
