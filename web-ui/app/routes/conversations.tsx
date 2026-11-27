import { requestWorkspaceEditorLeave } from "@overlay/components/workspace/use-workspace-editor-session";
import * as React from "react";

import { useNavigate, useParams } from "react-router";

import {
  ConversationQuickJump,
  getConversationMessageAnchorId,
  scrollToConversationMessage,
} from "~/components/conversation-quick-jump";
import { ConversationQueuePanel } from "~/components/conversation-queue-panel";
import { ConversationSettingsMenu } from "~/components/conversation-settings-menu";
import { CONVERSATION_SIDEBAR_STYLE, ConversationSidebar } from "~/components/conversation-sidebar";
import {
  Conversation,
  ConversationContent,
  ConversationEmptyState,
  ConversationScrollButton,
} from "~/components/extended/conversation";
import { ChatInput } from "~/components/input/chat-input";
import { CompressDialog, CompressReviewDialog, compressErrorKey, formatCompressResultMeta } from "~/components/compress-dialog";
import { ContextRecordsDialog } from "~/components/context-records-dialog";
import { ContextUsageDialog } from "~/components/context-usage-dialog";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import {
  affectedCheckpointsForMessage,
  clearConversationContext,
  laterCheckpointsForCheckpointMessage,
  revokeContextRecord,
} from "@overlay/lib/compression";
import { CreatorProposalsButton } from "~/components/creator-proposals";
import { ConversationNotices } from "~/components/conversation-notices";
import { conversationSizeInfo, lastContextTokens } from "~/lib/conversation-size";
import { isCreatorConversation } from "~/lib/creator-proposals";
import { collectLocalAttachmentUrls } from "@overlay/lib/draft-attachments";
import { useAttachmentDraftLeases } from "@overlay/hooks/use-attachment-draft-leases";
import { ChatMessage } from "~/components/message/chat-message";
import { ConversationLockCard } from "@overlay/components/workspace/conversation-lock-card";
import { WorkspaceImageProvider } from "~/components/markdown/workspace-image-context";
import { workspaceImageContext } from "~/lib/workspace-image";
import { badgeVariants } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Drawer, DrawerContent, DrawerTitle } from "~/components/ui/drawer";
import { Popover, PopoverContent, PopoverTrigger } from "~/components/ui/popover";
import { Sheet, SheetContent, SheetTitle } from "~/components/ui/sheet";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "~/components/ui/resizable";
import { TypingIndicator } from "~/components/ui/typing-indicator";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "~/components/ui/sidebar";
import { useIsMobile } from "~/hooks/use-mobile";
import { toConversationSummaryUpdate, useConversationList } from "~/hooks/use-conversation-list";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { useFolders } from "~/hooks/use-folders";
import { useCurrentModel } from "~/hooks/use-current-model";
import { getAssistantDisplayName, getModelDisplayName } from "~/lib/display";
import {
  EVENT_CLI_RUNTIME,
  formatCliSessionSummary,
  isCliControlForbiddenError,
  normalizeCliPermissionMode,
  resolveCliAgentName,
  resolveCliSessionDisplay,
  restartConversationCli,
  updateConversationCli,
} from "~/lib/cli";
import { convertConversationToMarkdown, downloadMarkdown } from "~/lib/export-markdown";
import { readSidebarOpen, writeSidebarOpen } from "~/lib/sidebar-state";
import { fitPanelLayout, widenPanelLayout } from "~/lib/panel-layout";
import { cn } from "~/lib/utils";
import api, { ApiError, sse } from "~/services/api";
import { subscribeToEvent } from "~/services/events";
import { createConversationStream } from "~/lib/conversation-stream";
import { useChatInputStore, useAppStore, useSettingsStore } from "~/stores";
import { displayScriptsSignature } from "@overlay/lib/tavern";
import { ActiveCharacterContext, useActiveCharacter } from "@overlay/hooks/use-active-character";
import { WorkbenchHost } from "~/components/workbench/workbench-host";
import {
  useWorkbench,
  useWorkbenchController,
  WorkbenchProvider,
} from "~/components/workbench/workbench-context";
import {
  type CliRuntimeEvent,
  type CompressStateDto,
  type ConversationCliSession,
  type CustomCliAgentConfig,
  type MessageNodeDto,
  type MessageDto,
  type ProviderModel,
  type Settings,
  type UIMessagePart,
} from "~/types";
import { Folder, Loader2, LogOut, MessageSquare, RotateCw, Terminal, X } from "lucide-react";
import Logo from "~/components/logo";
import type { GroupImperativeHandle } from "react-resizable-panels";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";
import i18n from "~/i18n";

/** 专注模式：聊天 | 编辑器（居中）| 文件列表，百分比 */
const FOCUS_LAYOUT = { chat: 28, workspace: 50, "focus-list": 22 };
/** 专注模式下工作区分成两栏时：编辑区从聊天栏多分一些，文件列表宽度不变。 */
const FOCUS_SPLIT_LAYOUT = { chat: 20, workspace: 58, "focus-list": 22 };
/** 工作区两栏时面板窄于这个宽度（每栏不到 360px）就自动加宽到 [SPLIT_WORKSPACE_SIZE]%，退出两栏时恢复。 */
const SPLIT_MIN_WORKSPACE_PX = 720;
const SPLIT_WORKSPACE_SIZE = 65;

const WorkspaceChatPanel = React.lazy(() =>
  import("@overlay/components/workspace/workspace-chat-panel").then((module) => ({
    default: module.WorkspaceChatPanel,
  })),
);

interface SelectedNodeMessage {
  node: MessageNodeDto;
  message: MessageNodeDto["messages"][number];
}
type ConversationSummaryUpdater = (update: ReturnType<typeof toConversationSummaryUpdate>) => void;

const EDIT_DRAFT_ATTACHMENT_MARK = "__from_message_attachment";
const EDIT_DRAFT_SOURCE_INDEX = "__from_message_source_index";
const EMPTY_INPUT_ATTACHMENTS: UIMessagePart[] = [];
const EMPTY_SUGGESTIONS: string[] = [];

interface EditDraft {
  text: string;
  attachments: UIMessagePart[];
  sourceParts: UIMessagePart[];
  textPartIndex: number | null;
}

interface EditingSession {
  messageId: string;
  sourceParts: UIMessagePart[];
  textPartIndex: number | null;
}

function createHomeDraftId() {
  return `home-${uuidv4()}`;
}

function truncatePreviewText(value: string, maxLength = 48): string {
  if (value.length <= maxLength) {
    return value;
  }

  return `${value.slice(0, maxLength)}...`;
}

// Collapse whitespace cheaply: slice a bounded window first so streaming-sized
// messages never run the regex over the full body.
function collapseWhitespaceForPreview(value: string, maxLength = 48): string {
  return value
    .slice(0, maxLength + 8)
    .replace(/\s+/g, " ")
    .trimEnd();
}

function getQuickJumpPreview(
  message: MessageDto,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const textPreview = message.parts
    .filter((part): part is Extract<UIMessagePart, { type: "text" }> => part.type === "text")
    .map((part) => part.text.trim())
    .find((text) => text.length > 0);

  if (textPreview) {
    return truncatePreviewText(collapseWhitespaceForPreview(textPreview));
  }

  const fallbackPart = message.parts.find(Boolean);
  if (!fallbackPart) return t("conversations.preview.empty_message");

  switch (fallbackPart.type) {
    case "image":
      return t("conversations.preview.image");
    case "video":
      return t("conversations.preview.video");
    case "audio":
      return t("conversations.preview.audio");
    case "document":
      return fallbackPart.fileName.trim().length > 0
        ? t("conversations.preview.document_with_name", {
            name: truncatePreviewText(fallbackPart.fileName.trim(), 32),
          })
        : t("conversations.preview.document");
    case "reasoning":
      return fallbackPart.reasoning.trim().length > 0
        ? truncatePreviewText(collapseWhitespaceForPreview(fallbackPart.reasoning.trim()))
        : t("conversations.preview.thinking");
    case "tool":
    case "server_tool":
      return fallbackPart.toolName.trim().length > 0
        ? t("conversations.preview.tool_with_name", {
            name: truncatePreviewText(fallbackPart.toolName.trim(), 32),
          })
        : t("conversations.preview.tool_call");
    case "text":
      return t("conversations.preview.empty_message");
  }
}

function isAttachmentPart(
  part: UIMessagePart,
): part is Extract<UIMessagePart, { type: "image" | "video" | "audio" | "document" }> {
  return (
    part.type === "image" ||
    part.type === "video" ||
    part.type === "audio" ||
    part.type === "document"
  );
}

function getLastTextPartIndex(parts: UIMessagePart[]): number | null {
  for (let index = parts.length - 1; index >= 0; index -= 1) {
    if (parts[index]?.type === "text") {
      return index;
    }
  }

  return null;
}

function getDraftSourceIndex(part: UIMessagePart): number | null {
  const value = part.metadata?.[EDIT_DRAFT_SOURCE_INDEX];
  return typeof value === "number" ? value : null;
}

function toEditDraft(message: MessageDto): EditDraft | null {
  const textPartIndex = getLastTextPartIndex(message.parts);
  const text =
    textPartIndex !== null && message.parts[textPartIndex]?.type === "text"
      ? message.parts[textPartIndex].text
      : "";

  const attachments = message.parts.flatMap((part, index) => {
    if (!isAttachmentPart(part)) return [];

    return [
      {
        ...part,
        metadata: {
          ...part.metadata,
          [EDIT_DRAFT_ATTACHMENT_MARK]: true,
          [EDIT_DRAFT_SOURCE_INDEX]: index,
        },
      },
    ];
  });

  if (text.trim().length === 0 && attachments.length === 0) {
    return null;
  }

  return {
    text,
    attachments,
    sourceParts: message.parts,
    textPartIndex,
  };
}

function shouldDeleteAttachmentFileOnRemove(part: UIMessagePart): boolean {
  if (!part.metadata) return true;

  return part.metadata[EDIT_DRAFT_ATTACHMENT_MARK] !== true;
}

function stripEditDraftMetadata(parts: UIMessagePart[]): UIMessagePart[] {
  return parts.map((part) => {
    if (!part.metadata) {
      return part;
    }

    const hasEditMark =
      EDIT_DRAFT_ATTACHMENT_MARK in part.metadata || EDIT_DRAFT_SOURCE_INDEX in part.metadata;
    if (!hasEditMark) {
      return part;
    }

    const nextMetadata = { ...part.metadata };
    delete nextMetadata[EDIT_DRAFT_ATTACHMENT_MARK];
    delete nextMetadata[EDIT_DRAFT_SOURCE_INDEX];

    return {
      ...part,
      metadata: Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined,
    };
  });
}

function buildEditedParts(session: EditingSession, draftParts: UIMessagePart[]): UIMessagePart[] {
  const textPart = draftParts.find(
    (part): part is Extract<UIMessagePart, { type: "text" }> => part.type === "text",
  );
  const editedText = textPart?.text ?? "";

  const retainedAttachmentIndexes = new Set<number>();
  const appendedAttachments: UIMessagePart[] = [];

  draftParts.forEach((part) => {
    if (!isAttachmentPart(part)) return;

    if (part.metadata?.[EDIT_DRAFT_ATTACHMENT_MARK] === true) {
      const sourceIndex = getDraftSourceIndex(part);
      if (sourceIndex !== null) {
        retainedAttachmentIndexes.add(sourceIndex);
      }
      return;
    }

    appendedAttachments.push(part);
  });

  const preservedParts: UIMessagePart[] = [];

  session.sourceParts.forEach((part, index) => {
    if (session.textPartIndex !== null && index === session.textPartIndex && part.type === "text") {
      preservedParts.push({ ...part, text: editedText });
      return;
    }

    if (isAttachmentPart(part)) {
      if (retainedAttachmentIndexes.has(index)) {
        preservedParts.push(part);
      }
      return;
    }

    preservedParts.push(part);
  });

  if (session.textPartIndex === null && textPart && textPart.text.trim().length > 0) {
    return [textPart, ...preservedParts, ...appendedAttachments];
  }

  return [...preservedParts, ...appendedAttachments];
}

function useConversationDetail(
  activeId: string | null,
  updateSummary: ConversationSummaryUpdater,
  onStreamError?: () => void,
) {
  const { t } = useTranslation("page");
  const callbacks = React.useRef({ updateSummary, onStreamError });
  callbacks.current = { updateSummary, onStreamError };

  // A fresh store for every visit masks the previous conversation during render,
  // not one effect later. Even A -> B -> A cannot reuse A's old callbacks/state.
  const stream = React.useMemo(() => createConversationStream(activeId, {
    connect: (handlers, signal) => sse(`conversations/${activeId}/stream`, handlers, { signal }),
    onSummary: (conversation) => callbacks.current.updateSummary(toConversationSummaryUpdate(conversation)),
    onClock: (serverTime) => useAppStore.getState().setClockOffset(serverTime),
    onGenerationError: () => {
      callbacks.current.onStreamError?.();
    },
  }), [activeId]);
  const state = React.useSyncExternalStore(stream.subscribe, stream.getSnapshot, stream.getSnapshot);
  React.useEffect(() => {
    stream.start();
    return stream.stop;
  }, [stream]);

  const { detail } = state;
  const detailAssistantId = detail?.assistantId ?? null;
  const displaySignature = useSettingsStore((store) => displayScriptsSignature(store.settings, detailAssistantId));
  React.useEffect(() => {
    // Display-only text is projected by the server. Reconnect for a new SSE
    // baseline, never fetch the database's possibly older message tree.
    stream.setDisplaySignature(detailAssistantId, displaySignature);
  }, [stream, detailAssistantId, displaySignature]);

  const selectedNodeMessages = React.useMemo<SelectedNodeMessage[]>(() => {
    if (!detail) return [];
    return detail.messages.map((node) => ({
      node,
      message: node.messages[node.selectIndex] ?? node.messages[0],
    }));
  }, [detail]);
  const syncing = state.status !== "idle" && state.status !== "live";
  const syncError = state.status === "unauthorized"
    ? t("common:web_auth_gate.title")
    : state.error || t("conversations.errors.load_detail_failed");

  return {
    detail,
    detailLoading: !detail && state.status === "connecting",
    detailError: !detail && syncing && state.status !== "connecting" ? syncError : null,
    selectedNodeMessages,
    queue: state.queue,
    cacheWarm: state.cacheWarm,
    compressState: state.compressState,
    compressSuggestion: state.compressSuggestion,
    generationErrors: state.generationErrors,
    syncing,
    syncStatus: state.status,
    syncNotice: syncing && detail
      ? state.status === "connecting"
        ? t("conversations.empty_state.loading_description")
        : `${syncError} · ${t("conversations.compress.retry")}`
      : null,
  };
}

function useDraftInputController({
  activeId,
  isHomeRoute,
  homeDraftId,
  setHomeDraftId,
  useConversationPromptInjection,
  folderId,
  navigate,
  refreshList,
}: {
  activeId: string | null;
  isHomeRoute: boolean;
  homeDraftId: string;
  setHomeDraftId: React.Dispatch<React.SetStateAction<string>>;
  useConversationPromptInjection: boolean;
  /** 侧边栏当前选中的文件夹；新会话的首条消息归入它 */
  folderId: string | null;
  navigate: ReturnType<typeof useNavigate>;
  refreshList: () => void;
}) {
  const draftKey = activeId ?? (isHomeRoute ? homeDraftId : null);
  const draft = useChatInputStore(
    React.useCallback((state) => (draftKey ? state.drafts[draftKey] : undefined), [draftKey]),
  );

  const setDraftText = useChatInputStore((state) => state.setText);
  const addDraftParts = useChatInputStore((state) => state.addParts);
  const removeDraftPart = useChatInputStore((state) => state.removePartAt);
  const getSubmitParts = useChatInputStore((state) => state.getSubmitParts);
  const getPromptInjectionIds = useChatInputStore((state) => state.getPromptInjectionIds);
  const clearDraft = useChatInputStore((state) => state.clearDraft);

  const inputText = draft?.text ?? "";
  const inputAttachments = draft?.parts ?? EMPTY_INPUT_ATTACHMENTS;

  const handleInputTextChange = React.useCallback(
    (text: string) => {
      if (!draftKey) return;
      setDraftText(draftKey, text);
    },
    [draftKey, setDraftText],
  );

  const handleAddInputParts = React.useCallback(
    (parts: UIMessagePart[]) => {
      if (!draftKey || parts.length === 0) return;
      addDraftParts(draftKey, parts);
    },
    [addDraftParts, draftKey],
  );

  const handleRemoveInputPart = React.useCallback(
    (index: number) => {
      if (!draftKey) return;
      removeDraftPart(draftKey, index);
    },
    [draftKey, removeDraftPart],
  );

  const handleSubmit = React.useCallback(
    async (answer = true) => {
      if (!draftKey) return;

      const parts = getSubmitParts(draftKey);
      if (parts.length === 0) return;

      if (activeId) {
        await api.post<{ status: string }>(`conversations/${activeId}/messages`, {
          parts,
          answer,
        });
        clearDraft(draftKey);
        return;
      }

      const conversationId = uuidv4();
      setHomeDraftId(createHomeDraftId());
      const promptInjectionIds = getPromptInjectionIds(draftKey);

      await api.post<{ status: string }>(`conversations/${conversationId}/messages`, {
        parts,
        answer,
        ...(folderId ? { folderId } : {}),
        ...(useConversationPromptInjection
          ? {
              modeInjectionIds: promptInjectionIds.modeInjectionIds,
              lorebookIds: promptInjectionIds.lorebookIds,
            }
          : {}),
      });
      clearDraft(draftKey);

      navigate(`/c/${conversationId}`);
      refreshList();
    },
    [
      activeId,
      clearDraft,
      draftKey,
      folderId,
      getPromptInjectionIds,
      getSubmitParts,
      navigate,
      refreshList,
      setHomeDraftId,
      useConversationPromptInjection,
    ],
  );

  const replaceDraft = React.useCallback(
    (text: string, parts: UIMessagePart[]) => {
      if (!draftKey) return;
      clearDraft(draftKey);
      setDraftText(draftKey, text);
      addDraftParts(draftKey, parts);
    },
    [addDraftParts, clearDraft, draftKey, setDraftText],
  );

  const clearCurrentDraft = React.useCallback(() => {
    if (!draftKey) return;
    clearDraft(draftKey);
  }, [clearDraft, draftKey]);

  const getCurrentSubmitParts = React.useCallback(() => {
    if (!draftKey) return [];
    return getSubmitParts(draftKey);
  }, [draftKey, getSubmitParts]);

  return {
    draftKey,
    inputText,
    inputAttachments,
    handleInputTextChange,
    handleAddInputParts,
    handleRemoveInputPart,
    handleSubmit,
    replaceDraft,
    clearCurrentDraft,
    getCurrentSubmitParts,
  };
}

export function CliSessionBadge({
  cliSession,
  customCliAgents,
  conversationId,
  busy = false,
  onControlError,
}: {
  cliSession: ConversationCliSession;
  customCliAgents?: ReadonlyArray<CustomCliAgentConfig> | null;
  conversationId?: string | null;
  busy?: boolean;
  onControlError?: (message: string | null) => void;
}) {
  const { t } = useTranslation("page");
  const [open, setOpen] = React.useState(false);
  const [pendingAction, setPendingAction] = React.useState<"permission" | "restart" | "exit" | null>(
    null,
  );
  const [localError, setLocalError] = React.useState<string | null>(null);

  const agentName = resolveCliAgentName(cliSession.agentId, customCliAgents);
  const modelId = cliSession.modelId?.trim() || null;
  const modeId = cliSession.modeId?.trim() || null;
  const permissionMode = normalizeCliPermissionMode(cliSession.permissionMode);
  const summary = formatCliSessionSummary(cliSession, customCliAgents);
  const interactive = Boolean(conversationId);
  const readonlyHint = t("conversations.cli.readonly_hint");
  const titleText = interactive
    ? busy
      ? `${summary} · ${t("conversations.cli.busy")}`
      : summary
    : `${summary} · ${readonlyHint}`;

  const runControl = React.useCallback(
    async (action: "permission" | "restart" | "exit", fn: () => Promise<void>) => {
      setPendingAction(action);
      setLocalError(null);
      onControlError?.(null);
      try {
        await fn();
      } catch (err) {
        const message = isCliControlForbiddenError(err)
          ? t("common:cli_control_forbidden")
          : err instanceof Error
            ? err.message
            : t("conversations.cli.control_failed");
        setLocalError(message);
        onControlError?.(message);
        toast.error(message);
      } finally {
        setPendingAction(null);
      }
    },
    [onControlError, t],
  );

  const handlePermissionChange = React.useCallback(
    (nextMode: "ask" | "auto") => {
      if (!conversationId) return;
      if ((nextMode === "auto" ? "AUTO" : "ASK") === permissionMode) return;
      void runControl("permission", () =>
        updateConversationCli(conversationId, { permissionMode: nextMode }),
      );
    },
    [conversationId, permissionMode, runControl],
  );

  const handleRestart = React.useCallback(() => {
    if (!conversationId) return;
    void runControl("restart", async () => {
      await restartConversationCli(conversationId);
      toast.success(t("conversations.cli.restarted"));
    });
  }, [conversationId, runControl, t]);

  const handleExitCli = React.useCallback(() => {
    if (!conversationId) return;
    void runControl("exit", async () => {
      await updateConversationCli(conversationId, { agentId: null });
      setOpen(false);
      toast.success(t("conversations.cli.exited"));
    });
  }, [conversationId, runControl, t]);

  return (
    <div className="flex min-w-0 items-center gap-1.5">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            data-testid="cli-session-badge"
            title={titleText}
            aria-label={titleText}
            className={cn(
              badgeVariants({ variant: "secondary" }),
              "max-w-[min(100%,18rem)] cursor-pointer gap-1 font-normal",
            )}
          >
            {busy ? (
              <Loader2 className="size-3 shrink-0 animate-spin" data-testid="cli-session-busy" />
            ) : (
              <Terminal className="size-3 shrink-0" />
            )}
            <span className="truncate">{summary}</span>
          </button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-80 space-y-2.5 p-3 text-xs">
          <div className="flex items-center justify-between gap-2">
            <div className="font-medium text-foreground">
              {interactive
                ? t("conversations.cli.badge_title_interactive")
                : t("conversations.cli.badge_title")}
            </div>
            {busy ? (
              <span className="inline-flex items-center gap-1 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                <Loader2 className="size-2.5 animate-spin" />
                {t("conversations.cli.busy")}
              </span>
            ) : null}
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            <dt className="text-muted-foreground">{t("conversations.cli.agent")}</dt>
            <dd className="truncate font-medium text-foreground">{agentName}</dd>
            {modelId ? (
              <>
                <dt className="text-muted-foreground">{t("conversations.cli.model")}</dt>
                <dd className="truncate font-mono text-foreground">{modelId}</dd>
              </>
            ) : null}
            {modeId ? (
              <>
                <dt className="text-muted-foreground">{t("conversations.cli.mode")}</dt>
                <dd className="truncate text-foreground">{modeId}</dd>
              </>
            ) : null}
            <dt className="text-muted-foreground">{t("conversations.cli.permission")}</dt>
            <dd className="text-foreground">
              {permissionMode === "AUTO"
                ? t("conversations.cli.permission_auto")
                : t("conversations.cli.permission_ask")}
            </dd>
          </dl>
          {interactive ? (
            <div className="space-y-2 border-t pt-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-muted-foreground">{t("conversations.cli.permission")}</span>
                <div className="inline-flex rounded-md border bg-muted/40 p-0.5">
                  <Button
                    type="button"
                    size="xs"
                    variant={permissionMode === "ASK" ? "secondary" : "ghost"}
                    disabled={pendingAction !== null}
                    data-testid="cli-permission-ask"
                    onClick={() => handlePermissionChange("ask")}
                    className="h-6 px-2 text-[11px]"
                  >
                    {t("conversations.cli.permission_switch_ask")}
                  </Button>
                  <Button
                    type="button"
                    size="xs"
                    variant={permissionMode === "AUTO" ? "secondary" : "ghost"}
                    disabled={pendingAction !== null}
                    data-testid="cli-permission-auto"
                    onClick={() => handlePermissionChange("auto")}
                    className="h-6 px-2 text-[11px]"
                  >
                    {t("conversations.cli.permission_switch_auto")}
                  </Button>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  size="xs"
                  variant="outline"
                  disabled={pendingAction !== null}
                  data-testid="cli-restart-button"
                  onClick={handleRestart}
                  className="h-7 flex-1 gap-1 text-[11px]"
                >
                  <RotateCw
                    className={cn("size-3", pendingAction === "restart" && "animate-spin")}
                  />
                  {t("conversations.cli.restart")}
                </Button>
                <Button
                  type="button"
                  size="xs"
                  variant="outline"
                  disabled={pendingAction !== null}
                  data-testid="cli-exit-button"
                  onClick={handleExitCli}
                  className="h-7 flex-1 gap-1 text-[11px] text-destructive hover:text-destructive"
                >
                  <LogOut className="size-3" />
                  {t("conversations.cli.exit")}
                </Button>
              </div>
              {localError ? (
                <div
                  role="alert"
                  data-testid="cli-control-popover-error"
                  className="rounded border border-destructive/30 bg-destructive/10 px-2 py-1.5 text-[11px] text-destructive"
                >
                  {localError}
                </div>
              ) : null}
            </div>
          ) : (
            <p className="border-t pt-2 text-[11px] text-muted-foreground">{readonlyHint}</p>
          )}
        </PopoverContent>
      </Popover>
      {!interactive ? (
        <span
          data-testid="cli-session-readonly-hint"
          className="hidden min-w-0 truncate text-[11px] text-muted-foreground md:inline"
        >
          {readonlyHint}
        </span>
      ) : null}
    </div>
  );
}

const ConversationTimeline = React.memo(
  ({
    activeId,
    isHomeRoute,
    detailLoading,
    detailError,
    selectedNodeMessages,
    isGenerating,
    isCliSession = false,
    settings,
    conversationAssistantId,
    translatingIds,
    contentClassName,
    onEdit,
    onDelete,
    onFork,
    onRegenerate,
    onSelectBranch,
    onToggleFavorite,
    onTranslate,
    onClearTranslation,
    onToolApproval,
    onOpenContextUsage,
  }: {
    activeId: string | null;
    isHomeRoute: boolean;
    detailLoading: boolean;
    detailError: string | null;
    selectedNodeMessages: SelectedNodeMessage[];
    isGenerating: boolean;
    isCliSession?: boolean;
    settings: Settings | null;
    conversationAssistantId: string | null;
    translatingIds: ReadonlySet<string>;
    contentClassName?: string;
    onEdit?: (message: MessageDto) => void | Promise<void>;
    onDelete: (messageId: string) => Promise<void>;
    onFork: (messageId: string) => Promise<void>;
    onRegenerate: (messageId: string) => Promise<void>;
    onSelectBranch: (nodeId: string, selectIndex: number) => Promise<void>;
    onToggleFavorite: (nodeId: string) => Promise<void>;
    onTranslate: (messageId: string, language: string) => Promise<void>;
    onClearTranslation: (messageId: string) => Promise<void>;
    onToolApproval: (
      toolCallId: string,
      approved: boolean,
      reason: string,
      answer?: string,
    ) => Promise<void>;
    onOpenContextUsage?: () => void;
  }) => {
    const { t } = useTranslation("page");
    const canQuickJump =
      Boolean(activeId) && !detailLoading && !detailError && selectedNodeMessages.length > 1;
    const assistant = React.useMemo(() => {
      if (!settings || !conversationAssistantId) return null;
      return settings.assistants.find((item) => item.id === conversationAssistantId) ?? null;
    }, [conversationAssistantId, settings]);
    const modelById = React.useMemo(() => {
      const map = new Map<string, ProviderModel>();
      if (!settings) return map;

      for (const provider of settings.providers) {
        for (const model of provider.models) {
          if (!map.has(model.id)) {
            map.set(model.id, model);
          }
        }
      }

      return map;
    }, [settings]);
    const timelineNodes = React.useMemo(() => selectedNodeMessages.map((entry) => entry.node), [selectedNodeMessages]);

    return (
      <Conversation className="flex-1 min-h-0">
        <ConversationContent
          className={cn("mx-auto w-full max-w-3xl gap-4 px-4 py-6", contentClassName)}
        >
          {!activeId && !isHomeRoute && (
            <ConversationEmptyState
              icon={<MessageSquare className="size-10" />}
              title={t("conversations.empty_state.select_title")}
              description={t("conversations.empty_state.select_description")}
            />
          )}
          {activeId && detailLoading && (
            <ConversationEmptyState
              title={t("conversations.empty_state.loading_title")}
              description={t("conversations.empty_state.loading_description")}
            />
          )}
          {activeId && detailError && (
            <ConversationEmptyState
              title={t("conversations.empty_state.error_title")}
              description={detailError}
            />
          )}
          {!detailLoading && !detailError && activeId && selectedNodeMessages.length === 0 && (
            <ConversationEmptyState
              icon={<MessageSquare className="size-10" />}
              title={t("conversations.empty_state.no_message_title")}
              description={t("conversations.empty_state.no_message_description")}
            />
          )}
          {!detailLoading &&
            !detailError &&
            activeId &&
            selectedNodeMessages.map(({ node, message }, index) => {
              const model = message.modelId ? (modelById.get(message.modelId) ?? null) : null;

              return (
                <div
                  key={message.id}
                  id={getConversationMessageAnchorId(message.id)}
                  className="scroll-mt-24"
                >
                  <ChatMessage
                    node={node}
                    message={message}
                    loading={isGenerating && index === selectedNodeMessages.length - 1}
                    isLastMessage={index === selectedNodeMessages.length - 1}
                    isCliSession={isCliSession}
                    assistant={assistant}
                    model={model}
                    translating={translatingIds.has(message.id)}
                    onEdit={onEdit}
                    onDelete={onDelete}
                    skipDeleteConfirm={
                      Boolean(message.isContextCheckpoint) ||
                      affectedCheckpointsForMessage(
                        selectedNodeMessages.map((entry) => entry.node),
                        message.id,
                      ).length > 0
                    }
                    onFork={onFork}
                    onOpenContextUsage={isCliSession ? undefined : onOpenContextUsage}
                    onRegenerate={onRegenerate}
                    onSelectBranch={onSelectBranch}
                    onToggleFavorite={onToggleFavorite}
                    onTranslate={onTranslate}
                    onClearTranslation={onClearTranslation}
                    onToolApproval={onToolApproval}
                  />
                  {message.role === "ASSISTANT" ? (
                    <ConversationLockCard
                      workspaceId={assistant?.workspaceId}
                      conversationId={activeId}
                      messageId={message.id}
                      nodes={timelineNodes}
                      generating={isGenerating}
                    />
                  ) : null}
                </div>
              );
            })}
          {!detailLoading && !detailError && activeId && isGenerating && (
            <div className="flex items-start py-2">
              <TypingIndicator className="px-1 py-2" />
            </div>
          )}
        </ConversationContent>

        {canQuickJump ? (
          <ConversationQuickJump
            items={selectedNodeMessages.map(({ message }) => ({
              id: message.id,
              role: message.role,
              preview: getQuickJumpPreview(message, t),
            }))}
          />
        ) : null}

        <ConversationScrollButton />
      </Conversation>
    );
  },
);

export function meta() {
  return [
    { title: i18n.t("page:conversations.meta.title") },
    {
      name: "description",
      content: i18n.t("page:conversations.meta.description"),
    },
  ];
}

export default function ConversationsPage() {
  const workbench = useWorkbenchController();

  return (
    <WorkbenchProvider value={workbench}>
      <ConversationsPageInner />
    </WorkbenchProvider>
  );
}

function ConversationsPageInner() {
  const { t } = useTranslation("page");
  const navigate = useNavigate();
  const { id: routeId } = useParams();
  const isHomeRoute = !routeId;
  const isMobile = useIsMobile();
  const { panel, closePanel } = useWorkbench();

  const { settings, assistants, currentAssistantId, currentAssistant } = useCurrentAssistant();
  const { currentModel, currentProvider } = useCurrentModel();
  const {
    folders,
    selectedFolderId,
    setSelectedFolderId,
    createFolder,
    renameFolder,
    deleteFolder,
    moveConversationToFolder,
  } = useFolders(currentAssistantId);
  const {
    conversations,
    activeId,
    setActiveId,
    loading,
    error,
    hasMore,
    loadMore,
    refreshList,
    updateConversationSummary,
  } = useConversationList({
    currentAssistantId,
    routeId,
    autoSelectFirst: !isHomeRoute,
    folderId: selectedFolderId,
  });

  const [homeDraftId, setHomeDraftId] = React.useState(() => createHomeDraftId());
  const [editingSession, setEditingSession] = React.useState<EditingSession | null>(null);
  const [translatingIds, setTranslatingIds] = React.useState<ReadonlySet<string>>(
    () => new Set<string>(),
  );
  const translatingTimersRef = React.useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const pendingScrollNodeIdRef = React.useRef<string | null>(null);
  const [workspaceDrawerOpen, setWorkspaceDrawerOpen] = React.useState(false);
  const [sidebarOpen, setSidebarOpen] = React.useState(() => readSidebarOpen());
  const handleSidebarOpenChange = React.useCallback((open: boolean) => {
    setSidebarOpen(open);
    writeSidebarOpen(open);
  }, []);

  const clearTranslating = React.useCallback((messageId?: string) => {
    if (messageId) {
      const timer = translatingTimersRef.current.get(messageId);
      if (timer) {
        clearTimeout(timer);
        translatingTimersRef.current.delete(messageId);
      }
      setTranslatingIds((prev) => {
        if (!prev.has(messageId)) return prev;
        const next = new Set(prev);
        next.delete(messageId);
        return next;
      });
      return;
    }
    for (const timer of translatingTimersRef.current.values()) {
      clearTimeout(timer);
    }
    translatingTimersRef.current.clear();
    setTranslatingIds((prev) => (prev.size === 0 ? prev : new Set<string>()));
  }, []);

  const markTranslating = React.useCallback(
    (messageId: string) => {
      clearTranslating(messageId);
      setTranslatingIds((prev) => new Set(prev).add(messageId));
      // Safety net: drop the pending flag if no SSE resolution arrives.
      translatingTimersRef.current.set(
        messageId,
        setTimeout(() => clearTranslating(messageId), 120_000),
      );
    },
    [clearTranslating],
  );

  const {
    detail,
    detailLoading,
    detailError,
    selectedNodeMessages,
    queue,
    cacheWarm,
    compressState,
    compressSuggestion,
    generationErrors,
    syncing,
    syncStatus,
    syncNotice,
  } = useConversationDetail(activeId, updateConversationSummary, clearTranslating);
  React.useEffect(() => {
    if (generationErrors.length > 0) clearTranslating();
  }, [generationErrors, clearTranslating]);

  const handleDismissGenerationError = React.useCallback(
    (errorId: string) => {
      if (!activeId) return;
      void api.delete(`conversations/${activeId}/errors/${errorId}`).catch(() => undefined);
    },
    [activeId],
  );

  const handleCompressAndRetry = React.useCallback(() => {
    if (!activeId) return;
    void api.post(`conversations/${activeId}/compress/retry`).catch((error) => {
      const code = error instanceof ApiError ? error.errorCode : undefined;
      toast.error(t(compressErrorKey(code)));
    });
  }, [activeId, t]);
  const conversationAssistant = React.useMemo(
    () => assistants?.find((item) => item.id === detail?.assistantId) ?? null,
    [assistants, detail?.assistantId],
  );
  const isCliSession = Boolean(detail?.cliSession);
  const [cliRuntime, setCliRuntime] = React.useState<CliRuntimeEvent | null>(null);
  const [cliControlError, setCliControlError] = React.useState<string | null>(null);

  React.useEffect(() => {
    setCliControlError(null);
    if (!activeId) {
      setCliRuntime(null);
      return;
    }
    setCliRuntime(null);
    return subscribeToEvent<CliRuntimeEvent>(EVENT_CLI_RUNTIME, (event) => {
      if (!event.active || event.conversationId === activeId) {
        setCliRuntime(event);
      }
    });
  }, [activeId]);

  // Background compression: banners while running / after a failure seen on this page.
  const compressRunning = compressState?.status === "running";
  const [compressDialogOpen, setCompressDialogOpen] = React.useState(false);
  const [contextRecordsOpen, setContextRecordsOpen] = React.useState(false);
  const [contextUsageOpen, setContextUsageOpen] = React.useState(false);
  const [clearConfirmOpen, setClearConfirmOpen] = React.useState(false);
  const [clearingContext, setClearingContext] = React.useState(false);
  const [pendingStaleEdit, setPendingStaleEdit] = React.useState<{
    messageId: string;
    parts: ReturnType<typeof stripEditDraftMetadata>;
    firstAffectedNodeId: string;
    affectedCount: number;
  } | null>(null);
  const [pendingStaleDelete, setPendingStaleDelete] = React.useState<{
    messageId: string;
    firstAffectedNodeId: string;
    affectedCount: number;
  } | null>(null);
  const [pendingCascadeCheckpoint, setPendingCascadeCheckpoint] = React.useState<{
    nodeId: string;
    laterCount: number;
  } | null>(null);
  const [staleActionBusy, setStaleActionBusy] = React.useState(false);
  const [compressFailure, setCompressFailure] = React.useState<CompressStateDto | null>(null);
  const [cancellingCompress, setCancellingCompress] = React.useState(false);
  const [sizeWarningDismissedFor, setSizeWarningDismissedFor] = React.useState<string | null>(null);
  const lastCompressRef = React.useRef<CompressStateDto | null>(null);
  const [reviewOpen, setReviewOpen] = React.useState(false);

  React.useEffect(() => {
    const previous = lastCompressRef.current;
    lastCompressRef.current = compressState;
    if (!compressState) {
      setCompressFailure(null);
      return;
    }
    if (compressState.status === "running") {
      setCompressFailure(null);
      return;
    }
    if (compressState.status === "awaiting_review") {
      // Opens once for a run started here; otherwise the banner offers it.
      if (previous?.status === "running" && compressState.origin === "web") setReviewOpen(true);
      return;
    }
    setReviewOpen(false);
    // Only a running → finished transition of the same run is news; a replay on reconnect is not.
    const sameRun =
      (previous?.status === "running" || previous?.status === "awaiting_review") &&
      previous.conversationId === compressState.conversationId &&
      previous.startedAt === compressState.startedAt;
    if (!sameRun) return;
    if (compressState.status === "succeeded") {
      const targetId = compressState.targetConversationId;
      if (targetId && targetId !== activeId) {
        toast.success(t("conversations.compress.new_conversation_created"));
        setActiveId(targetId);
        navigate(`/c/${targetId}`);
        refreshList();
      } else {
        toast.success(formatCompressResultMeta(compressState, t) ?? t("conversations.compress.succeeded"));
      }
    } else if (compressState.status === "cancelled") {
      toast.info(t("conversations.compress.cancelled"));
    } else setCompressFailure(compressState);
  }, [activeId, compressState, navigate, refreshList, setActiveId, t]);

  const handleCancelCompression = React.useCallback(async () => {
    if (!activeId) return;
    setCancellingCompress(true);
    try {
      await api.delete(`conversations/${activeId}/compress`);
    } catch {
      // 404: it already finished; the SSE state is authoritative.
    } finally {
      setCancellingCompress(false);
    }
  }, [activeId]);

  const sizeInfo = React.useMemo(
    () => conversationSizeInfo(detail?.messages ?? []),
    [detail?.messages],
  );

  const {
    draftKey,
    inputText,
    inputAttachments,
    handleInputTextChange,
    handleAddInputParts,
    handleRemoveInputPart,
    handleSubmit,
    replaceDraft,
    clearCurrentDraft,
    getCurrentSubmitParts,
  } = useDraftInputController({
    activeId,
    isHomeRoute,
    homeDraftId,
    setHomeDraftId,
    useConversationPromptInjection: currentAssistant?.allowConversationPromptInjection === true,
    folderId: selectedFolderId,
    navigate,
    refreshList,
  });

  // 未发送草稿附件续租：只对 draft 里的本地附件 URL 生效（文本不扫描），
  // 编辑已发送消息走 replaceDraft 后同样被覆盖；附件增删时 urls 变化即换批。
  const draftAttachmentUrls = React.useMemo(
    () => collectLocalAttachmentUrls(inputAttachments),
    [inputAttachments],
  );
  useAttachmentDraftLeases(draftAttachmentUrls);

  const activeConversation = conversations.find((item) => item.id === activeId);
  const chatSuggestions = detail?.chatSuggestions ?? EMPTY_SUGGESTIONS;

  React.useEffect(() => {
    const base = t("conversations.meta.title");
    document.title = activeConversation?.title ? `${activeConversation.title} - ${base}` : base;
    return () => {
      document.title = base;
    };
  }, [activeConversation?.title, t]);
  const isNewChat = isHomeRoute && !activeId;
  const showSuggestions =
    Boolean(activeId) && !detailLoading && !detailError && chatSuggestions.length > 0;
  const displaySuggestions = showSuggestions ? chatSuggestions : EMPTY_SUGGESTIONS;

  const handleSelect = React.useCallback(
    (id: string, nodeId?: string) => {
      if (nodeId && activeId === id) {
        // Conversation already loaded: jump straight to the hit node.
        const node = detail?.messages.find((item) => item.id === nodeId);
        const message = node?.messages[node.selectIndex] ?? node?.messages[0];
        if (message) {
          scrollToConversationMessage(message.id);
        }
      } else if (nodeId) {
        pendingScrollNodeIdRef.current = nodeId;
      } else {
        pendingScrollNodeIdRef.current = null;
      }
      setActiveId(id);
      if (routeId !== id) {
        navigate(`/c/${id}`);
      }
    },
    [activeId, detail?.messages, navigate, routeId, setActiveId],
  );

  React.useEffect(() => {
    setEditingSession(null);
    requestWorkspaceEditorLeave(() => setWorkspaceDrawerOpen(false));
    clearTranslating();
  }, [activeId, clearTranslating]);

  // Scroll to a search-hit message once the target conversation has loaded.
  React.useEffect(() => {
    const nodeId = pendingScrollNodeIdRef.current;
    if (!nodeId || !detail || detailLoading || detailError || detail.id !== activeId) return;
    pendingScrollNodeIdRef.current = null;
    const node = detail.messages.find((item) => item.id === nodeId);
    const message = node ? (node.messages[node.selectIndex] ?? node.messages[0]) : undefined;
    if (!message) {
      // Compressed or deleted since the search/favorite was listed.
      toast.info(t("conversations.jump_target_missing"));
      return;
    }
    scrollToConversationMessage(message.id);
  }, [activeId, detail, detailLoading, detailError, t]);

  // A finished translation lands via node updates; release the pending flag.
  React.useEffect(() => {
    if (!detail || translatingIds.size === 0) return;
    for (const node of detail.messages) {
      for (const message of node.messages) {
        if (translatingIds.has(message.id) && message.translation?.trim()) {
          clearTranslating(message.id);
        }
      }
    }
  }, [detail, translatingIds, clearTranslating]);

  const handleAssistantChange = React.useCallback(
    async (assistantId: string) => {
      await api.post<{ status: string }>("settings/assistant", { assistantId });
      setActiveId(null);
      if (routeId) {
        navigate("/", { replace: true });
      }
      refreshList();
    },
    [navigate, refreshList, routeId, setActiveId],
  );

  const handleToolApproval = React.useCallback(
    async (toolCallId: string, approved: boolean, reason: string, answer?: string) => {
      if (!activeId) return;
      if (compressRunning) {
        toast.info(t("conversations.compress.wait"));
        return;
      }
      await api.post<{ status: string }>(`conversations/${activeId}/tool-approval`, {
        toolCallId,
        approved,
        reason,
        ...(answer != null ? { answer } : {}),
      });
    },
    [activeId, compressRunning, t],
  );

  const handleRegenerate = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      if (compressRunning) {
        toast.info(t("conversations.compress.wait"));
        return;
      }
      await api.post<{ status: string }>(`conversations/${activeId}/regenerate`, {
        messageId,
      });
    },
    [activeId, compressRunning, t],
  );

  const handleSelectBranch = React.useCallback(
    async (nodeId: string, selectIndex: number) => {
      if (!activeId) return;
      await api.post<{ status: string }>(`conversations/${activeId}/nodes/${nodeId}/select`, {
        selectIndex,
      });
    },
    [activeId],
  );

  const handleDeleteMessage = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      // Deleting while a generation is in flight corrupts the turn (same guard
      // as the app, ChatPage onDelete).
      if (detail?.isGenerating) {
        toast.error(t("conversations.delete_blocked_generating"));
        return;
      }
      const nodes = detail?.messages ?? [];
      const targetNode = nodes.find((node) => node.messages.some((msg) => msg.id === messageId));
      const targetMsg = targetNode?.messages.find((msg) => msg.id === messageId);
      if (targetMsg?.isContextCheckpoint) {
        const info = laterCheckpointsForCheckpointMessage(nodes, messageId);
        if (info && info.laterCount > 0) {
          setPendingCascadeCheckpoint({ nodeId: info.nodeId, laterCount: info.laterCount });
          return;
        }
      } else {
        const affected = affectedCheckpointsForMessage(nodes, messageId);
        if (affected.length > 0) {
          setPendingStaleDelete({
            messageId,
            firstAffectedNodeId: affected[0].id,
            affectedCount: affected.length,
          });
          return;
        }
      }
      await api.delete<{ status: string }>(`conversations/${activeId}/messages/${messageId}`);
    },
    [activeId, detail?.isGenerating, detail?.messages, t],
  );

  const handleToggleFavorite = React.useCallback(
    async (nodeId: string) => {
      if (!activeId) return;
      try {
        // The SSE node is authoritative, even if this acknowledgement arrives later.
        await api.post(`conversations/${activeId}/nodes/${nodeId}/favorite`);
      } catch (favoriteError) {
        toast.error(
          favoriteError instanceof Error
            ? favoriteError.message
            : t("conversations.favorite_failed"),
        );
      }
    },
    [activeId, t],
  );

  const handleTranslateMessage = React.useCallback(
    async (messageId: string, language: string) => {
      if (!activeId) return;
      markTranslating(messageId);
      try {
        await api.post(`conversations/${activeId}/messages/${messageId}/translate`, {
          language,
        });
      } catch (translateError) {
        clearTranslating(messageId);
        toast.error(
          translateError instanceof Error
            ? translateError.message
            : t("conversations.translate_failed"),
        );
      }
    },
    [activeId, clearTranslating, markTranslating, t],
  );

  const handleClearTranslation = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      clearTranslating(messageId);
      try {
        await api.delete(`conversations/${activeId}/messages/${messageId}/translation`);
      } catch (clearError) {
        toast.error(
          clearError instanceof Error ? clearError.message : t("conversations.translate_failed"),
        );
        return;
      }
    },
    [activeId, clearTranslating, t],
  );

  const handleForkMessage = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      const response = await api.post<{ conversationId: string }>(
        `conversations/${activeId}/fork`,
        {
          messageId,
        },
      );
      setActiveId(response.conversationId);
      navigate(`/c/${response.conversationId}`);
      refreshList();
    },
    [activeId, navigate, refreshList, setActiveId],
  );

  const handleStartEdit = React.useCallback(
    (message: MessageDto) => {
      if (!activeId || (message.role !== "USER" && message.role !== "ASSISTANT")) return;

      const draft = toEditDraft(message);
      if (!draft) return;

      setEditingSession({
        messageId: message.id,
        sourceParts: draft.sourceParts,
        textPartIndex: draft.textPartIndex,
      });
      replaceDraft(draft.text, draft.attachments);
    },
    [activeId, replaceDraft],
  );

  const handleCancelEdit = React.useCallback(() => {
    setEditingSession(null);
    clearCurrentDraft();
  }, [clearCurrentDraft]);

  const handleClickSuggestion = React.useCallback(
    (suggestion: string) => {
      if (editingSession) {
        setEditingSession(null);
      }
      handleInputTextChange(suggestion);
    },
    [editingSession, handleInputTextChange],
  );

  const commitEditedMessage = React.useCallback(
    async (
      messageId: string,
      strippedParts: ReturnType<typeof stripEditDraftMetadata>,
      firstCheckpointNodeIdToRevoke?: string,
    ) => {
      if (!activeId) return;
      await api.post<{ status: string }>(
        `conversations/${activeId}/messages/${messageId}/edit`,
        { parts: strippedParts },
      );
      if (firstCheckpointNodeIdToRevoke) {
        await revokeContextRecord(activeId, firstCheckpointNodeIdToRevoke, true);
      }
      setEditingSession(null);
      clearCurrentDraft();
    },
    [activeId, clearCurrentDraft],
  );

  const handleSend = React.useCallback(async () => {
    if (!editingSession) {
      await handleSubmit();
      return;
    }

    if (!activeId) return;

    const draftParts = getCurrentSubmitParts();
    if (draftParts.length === 0) return;

    const nextParts = buildEditedParts(editingSession, draftParts);
    const stripped = stripEditDraftMetadata(nextParts);
    const affected = affectedCheckpointsForMessage(detail?.messages ?? [], editingSession.messageId);
    if (affected.length > 0) {
      setPendingStaleEdit({
        messageId: editingSession.messageId,
        parts: stripped,
        firstAffectedNodeId: affected[0].id,
        affectedCount: affected.length,
      });
      return;
    }

    await commitEditedMessage(editingSession.messageId, stripped);
  }, [activeId, commitEditedMessage, detail?.messages, editingSession, getCurrentSubmitParts, handleSubmit]);

  const handleSendNoAnswer = React.useCallback(async () => {
    if (editingSession) return;
    await handleSubmit(false);
  }, [editingSession, handleSubmit]);

  const handleTogglePinConversation = React.useCallback(
    async (conversationId: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/pin`);
      refreshList();
    },
    [refreshList],
  );

  const handleRegenerateConversationTitle = React.useCallback(
    async (conversationId: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/regenerate-title`);
      refreshList();
    },
    [refreshList],
  );

  const handleMoveConversation = React.useCallback(
    async (conversationId: string, assistantId: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/move`, { assistantId });
      if (conversationId === activeId) {
        setActiveId(null);
        setHomeDraftId(createHomeDraftId());
        if (routeId === conversationId) {
          navigate("/", { replace: true });
        }
      }
      refreshList();
    },
    [activeId, navigate, refreshList, routeId, setActiveId],
  );

  const handleUpdateConversationTitle = React.useCallback(
    async (conversationId: string, title: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/title`, { title });
      refreshList();
    },
    [refreshList],
  );

  const handleDeleteConversation = React.useCallback(
    async (conversationId: string) => {
      await api.delete<Record<string, never>>(`conversations/${conversationId}`, {
        parseJson: (raw) => (raw ? JSON.parse(raw) : {}),
      });
      if (conversationId === activeId) {
        setActiveId(null);
        setHomeDraftId(createHomeDraftId());
        if (routeId === conversationId) {
          navigate("/", { replace: true });
        }
      }
      refreshList();
    },
    [activeId, navigate, refreshList, routeId, setActiveId],
  );

  const handleSelectFolder = React.useCallback(
    (folderId: string | null) => {
      setSelectedFolderId(folderId);
    },
    [setSelectedFolderId],
  );

  const handleCreateFolder = React.useCallback(
    async (name: string) => {
      await createFolder(name);
    },
    [createFolder],
  );

  const handleRenameFolder = React.useCallback(
    async (id: string, name: string) => {
      await renameFolder(id, name);
    },
    [renameFolder],
  );

  const handleDeleteFolder = React.useCallback(
    async (id: string) => {
      await deleteFolder(id);
      refreshList();
    },
    [deleteFolder, refreshList],
  );

  const handleMoveConversationToFolder = React.useCallback(
    async (conversationId: string, folderId: string | null) => {
      await moveConversationToFolder(conversationId, folderId);
      refreshList();
    },
    [moveConversationToFolder, refreshList],
  );

  // The server made the conversation (with the current assistant): list it and open it.
  const handleStartCharacterChat = React.useCallback(
    (conversationId: string) => {
      handleSelect(conversationId);
      refreshList();
    },
    [handleSelect, refreshList],
  );

  const handleCreateConversation = React.useCallback(() => {
    closePanel();
    setActiveId(null);
    setHomeDraftId(createHomeDraftId());

    if (routeId) {
      navigate("/");
    }
  }, [closePanel, navigate, routeId, setActiveId]);

  const handleStop = React.useCallback(async () => {
    if (!activeId) return;
    await api.post<{ status: string }>(`conversations/${activeId}/stop`);
  }, [activeId]);

  const handleExportConversation = React.useCallback(
    (includeReasoning: boolean) => {
      if (!detail) return;
      const content = convertConversationToMarkdown(detail, includeReasoning);
      const filename = `${detail.title || "conversation"}.md`;
      downloadMarkdown(content, filename);
    },
    [detail],
  );

  const hasWorkbenchPanel = Boolean(panel);
  const configuredWorkspaceId = currentAssistant?.workspaceId ?? null;
  const [workspaceId, setEditorWorkspaceId] = React.useState(configuredWorkspaceId);
  React.useEffect(() => {
    if (configuredWorkspaceId !== workspaceId) requestWorkspaceEditorLeave(() => setEditorWorkspaceId(configuredWorkspaceId));
  }, [configuredWorkspaceId, workspaceId]);

  // 专注模式：聊天 | 编辑器（居中）| 文件列表。只改面板尺寸、不重挂编辑器；
  // 进入时收起侧边栏与工作台，退出时恢复原来的布局与侧边栏（不改「记住的开合」）。
  const panelGroupRef = React.useRef<GroupImperativeHandle | null>(null);
  const [focusMode, setFocusMode] = React.useState(false);
  const [workspaceSplit, setWorkspaceSplit] = React.useState(false);
  const workspaceSplitRef = React.useRef(workspaceSplit);
  workspaceSplitRef.current = workspaceSplit;
  const workspacePanelElRef = React.useRef<HTMLDivElement | null>(null);
  const [focusListEl, setFocusListEl] = React.useState<HTMLDivElement | null>(null);
  const focusActive = focusMode && !isMobile && Boolean(workspaceId);
  const focusRestoreRef = React.useRef<{ layout: Record<string, number>; sidebarOpen: boolean } | null>(null);
  const sidebarOpenRef = React.useRef(sidebarOpen);
  sidebarOpenRef.current = sidebarOpen;
  React.useEffect(() => {
    if (isMobile || !workspaceId) setFocusMode(false);
  }, [isMobile, workspaceId]);
  // 工作台只在打开时挂载（连同它左边的分隔条）：没打开时聊天与工作区之间只有一条分隔条，
  // 不会再有一个宽度为 0 的面板被拖开成空白。专注期间工作台改用右侧滑层。
  const showWorkbenchPanel = hasWorkbenchPanel && !focusActive;

  // 面板挂载/卸载后分组要到下一帧才按新面板重排，过早 setLayout 会被覆盖，所以统一推迟一帧，
  // 并按那时实际存在的面板换算。
  const applyPanelLayout = React.useCallback(
    (wanted: Record<string, number>, fallback?: Record<string, number>) => {
      const frame = requestAnimationFrame(() => {
        const group = panelGroupRef.current;
        const layout = group && fitPanelLayout(wanted, Object.keys(group.getLayout()), fallback);
        if (layout) group.setLayout(layout);
      });
      return () => cancelAnimationFrame(frame);
    },
    [],
  );

  // onLayoutChanged 记录：最近一次非专注模式的布局（退出专注时恢复），
  // 以及最近一次没有工作台的布局（打开工作台按它缩放、关闭时回到它）。
  const focusActiveRef = React.useRef(focusActive);
  focusActiveRef.current = focusActive;
  const lastNormalLayoutRef = React.useRef<Record<string, number> | null>(null);
  const layoutBeforeWorkbenchRef = React.useRef<Record<string, number> | null>(null);
  const handlePanelLayoutChanged = React.useCallback((layout: Record<string, number>) => {
    if (focusActiveRef.current) return;
    lastNormalLayoutRef.current = layout;
    if (!("workbench" in layout)) layoutBeforeWorkbenchRef.current = layout;
  }, []);

  React.useEffect(() => {
    if (focusActive) {
      if (!focusRestoreRef.current) {
        focusRestoreRef.current = {
          layout: lastNormalLayoutRef.current ?? panelGroupRef.current?.getLayout() ?? {},
          sidebarOpen: sidebarOpenRef.current,
        };
      }
      setSidebarOpen(false);
      return applyPanelLayout(workspaceSplitRef.current ? FOCUS_SPLIT_LAYOUT : FOCUS_LAYOUT);
    }
    if (focusRestoreRef.current) {
      const restore = focusRestoreRef.current;
      focusRestoreRef.current = null;
      setSidebarOpen(restore.sidebarOpen);
      // 工作台可能在专注期间打开/关闭过：缺的面板按默认 36% 补上
      const cancelLayout = applyPanelLayout({ ...restore.layout, "focus-list": 0 }, { workbench: 36 });
      // 两栏还开着：等布局与侧边栏都恢复后量一下，太窄照样加宽（侧边栏展开会挤窄面板，事先算不准）
      const timer = window.setTimeout(widenSplitIfNarrow, 400);
      return () => { cancelLayout(); window.clearTimeout(timer); };
    }
  }, [focusActive, applyPanelLayout]);

  // 工作区分成两栏：专注模式换成两栏的比例；普通模式下面板太窄就加宽，退出两栏恢复加宽前的布局。
  const splitRestoreRef = React.useRef<Record<string, number> | null>(null);
  /** 两栏开着、不在专注模式、面板窄于 SPLIT_MIN_WORKSPACE_PX：记下现在的布局并加宽。 */
  function widenSplitIfNarrow() {
    if (!workspaceSplitRef.current || focusActiveRef.current || splitRestoreRef.current) return;
    const layout = panelGroupRef.current?.getLayout();
    const px = workspacePanelElRef.current?.getBoundingClientRect().width ?? 0;
    if (!layout || !("workspace" in layout) || px >= SPLIT_MIN_WORKSPACE_PX) return;
    splitRestoreRef.current = layout;
    applyPanelLayout(widenPanelLayout(layout, "workspace", SPLIT_WORKSPACE_SIZE));
  }
  const prevWorkspaceSplitRef = React.useRef(workspaceSplit);
  React.useEffect(() => {
    if (prevWorkspaceSplitRef.current === workspaceSplit) return;
    prevWorkspaceSplitRef.current = workspaceSplit;
    if (focusActiveRef.current) {
      // 专注期间退出两栏：退出专注时回到加宽之前的布局
      if (!workspaceSplit && splitRestoreRef.current && focusRestoreRef.current) {
        focusRestoreRef.current = { ...focusRestoreRef.current, layout: splitRestoreRef.current };
        splitRestoreRef.current = null;
      }
      return applyPanelLayout(workspaceSplit ? FOCUS_SPLIT_LAYOUT : FOCUS_LAYOUT);
    }
    if (workspaceSplit) {
      widenSplitIfNarrow();
      return;
    }
    const restore = splitRestoreRef.current;
    splitRestoreRef.current = null;
    if (restore) return applyPanelLayout(restore, { workbench: 36 });
  }, [workspaceSplit, applyPanelLayout]);

  const prevFocusActiveRef = React.useRef(focusActive);
  React.useEffect(() => {
    // 专注模式进出时工作台跟着卸载/挂载，布局由上面的专注模式副作用处理
    const focusChanged = prevFocusActiveRef.current !== focusActive;
    prevFocusActiveRef.current = focusActive;
    const before = layoutBeforeWorkbenchRef.current;
    if (focusChanged || focusActive || !before) return;
    // 打开：其余面板按原比例让出 36%；关闭：回到打开前的布局
    return applyPanelLayout(
      showWorkbenchPanel
        ? { ...Object.fromEntries(Object.entries(before).map(([id, size]) => [id, size * 0.64])), workbench: 36 }
        : before,
    );
  }, [showWorkbenchPanel, focusActive, applyPanelLayout]);

  // 消息里写成工作区路径的图片（/workspace/out.png 等），到这个会话的工作区里取
  const workspaceImages = React.useMemo(() => {
    const conversationAssistant = settings?.assistants.find(
      (item) => item.id === detail?.assistantId,
    );
    return workspaceImageContext(
      conversationAssistant?.workspaceId,
      detail?.workspaceCwd,
      detail?.workspaceCwdScoped !== false,
    );
  }, [settings, detail?.assistantId, detail?.workspaceCwd, detail?.workspaceCwdScoped]);

  const activeCharacter = useActiveCharacter(detail?.characterId);

  const chatContent = (
    <div
      className={cn("flex flex-1 flex-col min-h-0 overflow-hidden", isNewChat && "justify-center")}
    >
      {syncNotice ? (
        <p role="status" data-sync-status={syncStatus} className="px-4 py-2 text-center text-xs text-muted-foreground">
          {syncNotice}
        </p>
      ) : null}
      {!isNewChat && (
        <div className="relative flex min-h-0 flex-1">
          <WorkspaceImageProvider value={workspaceImages}>
            <ActiveCharacterContext.Provider value={activeCharacter}>
              <ConversationTimeline
                activeId={activeId}
                isHomeRoute={isHomeRoute}
                detailLoading={detailLoading}
                detailError={detailError}
                selectedNodeMessages={selectedNodeMessages}
                isGenerating={!syncing && (detail?.isGenerating ?? false)}
                isCliSession={isCliSession}
                settings={settings}
                conversationAssistantId={detail?.assistantId ?? null}
                translatingIds={translatingIds}
                onEdit={isCliSession ? undefined : handleStartEdit}
                onDelete={handleDeleteMessage}
                onFork={handleForkMessage}
                onRegenerate={handleRegenerate}
                onSelectBranch={handleSelectBranch}
                onToggleFavorite={handleToggleFavorite}
                onTranslate={handleTranslateMessage}
                onClearTranslation={handleClearTranslation}
                onToolApproval={handleToolApproval}
                onOpenContextUsage={detail && !isCliSession ? () => setContextUsageOpen(true) : undefined}
              />
            </ActiveCharacterContext.Provider>
          </WorkspaceImageProvider>
        </div>
      )}

      <div>
        {isNewChat && (
          <div className="mb-4 text-center">
            <div className="mb-3 flex justify-center">
              <div className="[&>svg]:size-16">
                <Logo className="size-16 text-primary" />
              </div>
            </div>
            <p className="text-lg text-muted-foreground">{t("conversations.welcome_prompt")}</p>
          </div>
        )}
        {activeId && !isNewChat && !isCliSession && detail?.characterId && conversationAssistant && conversationAssistant.tavernMode !== true ? (
          // a character chat on an assistant with SillyTavern mode off: the card is not sent
          <div className="mx-auto mb-2 w-full max-w-3xl px-4" data-testid="tavern-off-notice">
            <div role="status" className="flex w-full flex-wrap items-center gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm">
              <div className="min-w-0 flex-1">{t("conversations.tavern_off")}</div>
              <Button size="sm" variant="outline" onClick={() => navigate(`/settings/assistants/${conversationAssistant.id}`)}>
                {t("conversations.tavern_open_settings")}
              </Button>
            </div>
          </div>
        ) : null}
        {activeId && !isNewChat ? (
          <ConversationNotices
            compressState={compressState}
            compressFailure={compressFailure}
            generationErrors={generationErrors}
            sizeInfo={sizeInfo}
            sizeWarningDismissed={sizeWarningDismissedFor === activeId}
            isCliSession={isCliSession}
            cancelling={cancellingCompress}
            onCancelCompression={() => void handleCancelCompression()}
            onOpenCompress={() => setCompressDialogOpen(true)}
            compressSuggestion={compressSuggestion}
            onDismissSuggestion={() => void api.delete(`conversations/${activeId}/compress/suggestion`).catch(() => undefined)}
            onDismissGenerationError={handleDismissGenerationError}
            onCompressAndRetry={handleCompressAndRetry}
            onOpenReview={() => setReviewOpen(true)}
            onDismissFailure={() => setCompressFailure(null)}
            onDismissSizeWarning={() => setSizeWarningDismissedFor(activeId)}
            onNewConversation={handleCreateConversation}
          />
        ) : null}
        {activeId && !isNewChat && isCreatorConversation(detail?.assistantId) ? (
          <div className="flex justify-end pb-1">
            <CreatorProposalsButton conversationId={activeId} />
          </div>
        ) : null}
        {activeId ? (
          <ConversationQueuePanel
            key={activeId}
            conversationId={activeId}
            queue={queue}
            queueSize={detail?.queueSize ?? 0}
            queuePaused={detail?.queuePaused ?? false}
            disabled={syncing}
            className="pb-2"
          />
        ) : null}
        <ChatInput
          value={inputText}
          attachments={inputAttachments}
          conversation={detail}
          draftKey={draftKey}
          ready={draftKey !== null}
          isGenerating={detail?.isGenerating ?? false}
          cacheWarm={cacheWarm}
          disabled={syncing}
          onValueChange={handleInputTextChange}
          onAddParts={handleAddInputParts}
          suggestions={displaySuggestions}
          onSuggestionClick={handleClickSuggestion}
          isEditing={Boolean(editingSession)}
          onCancelEdit={editingSession ? handleCancelEdit : undefined}
          shouldDeleteFileOnRemove={shouldDeleteAttachmentFileOnRemove}
          onRemovePart={handleRemoveInputPart}
          onSend={handleSend}
          onSendNoAnswer={activeId ? handleSendNoAnswer : undefined}
          onStop={activeId ? handleStop : undefined}
          onExportConversation={
            detail && detail.messages.length > 0 ? handleExportConversation : undefined
          }
          onCompressConversation={
            detail && !isCliSession && detail.messages.length >= 2 && !compressRunning
              ? () => setCompressDialogOpen(true)
              : undefined
          }
          onOpenContextBreakdown={
            detail && !isCliSession ? () => setContextUsageOpen(true) : undefined
          }
          adaptiveToolbar={!isMobile}
        />
        {activeId && !isCliSession ? (
          <CompressDialog
            conversationId={activeId}
            open={compressDialogOpen}
            onOpenChange={setCompressDialogOpen}
            contextTokens={lastContextTokens(detail?.messages ?? [])}
            contextLength={currentModel?.contextLength ?? null}
            nodes={detail?.messages ?? []}
            override={detail?.compression ?? null}
          />
        ) : null}
        {activeId && compressState?.status === "awaiting_review" && compressState.reviewSummary ? (
          <CompressReviewDialog
            key={compressState.startedAt}
            conversationId={activeId}
            summary={compressState.reviewSummary}
            compressState={compressState}
            open={reviewOpen}
            onOpenChange={setReviewOpen}
          />
        ) : null}
        {activeId && !isCliSession ? (
          <ContextRecordsDialog
            conversationId={activeId}
            open={contextRecordsOpen}
            onOpenChange={setContextRecordsOpen}
            refreshKey={detail?.messages.length ?? 0}
            isBusy={Boolean(detail?.isGenerating) || compressRunning}
            onJumpToMessage={(_nodeId, messageId) => {
              scrollToConversationMessage(messageId);
            }}
          />
        ) : null}
        {activeId && !isCliSession ? (
          <ContextUsageDialog
            conversationId={activeId}
            open={contextUsageOpen}
            onOpenChange={setContextUsageOpen}
            draftText={inputText}
            draftImageCount={inputAttachments.filter((part) => part.type === "image").length}
            onOpenCompress={() => setCompressDialogOpen(true)}
          />
        ) : null}
        <Dialog open={clearConfirmOpen} onOpenChange={setClearConfirmOpen}>
          <DialogContent data-testid="context-clear-confirm-dialog">
            <DialogHeader>
              <DialogTitle>{t("conversations.context_records.clear_confirm_title")}</DialogTitle>
              <DialogDescription>{t("conversations.context_records.clear_confirm_message")}</DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setClearConfirmOpen(false)}>
                {t("conversations.context_records.cancel")}
              </Button>
              <Button
                type="button"
                disabled={clearingContext}
                onClick={() => {
                  if (!activeId) return;
                  setClearingContext(true);
                  clearConversationContext(activeId)
                    .then(() => {
                      setClearConfirmOpen(false);
                      toast.success(t("conversations.context_records.cleared"));
                    })
                    .catch((error) => {
                      toast.error(error instanceof Error ? error.message : t("conversations.context_records.action_failed"));
                    })
                    .finally(() => {
                      setClearingContext(false);
                    });
                }}
                data-testid="context-clear-confirm-button"
              >
                {t("conversations.context_records.clear_context")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        <Dialog open={pendingStaleEdit !== null} onOpenChange={(next) => !next && setPendingStaleEdit(null)}>
          <DialogContent data-testid="stale-edit-dialog">
            <DialogHeader>
              <DialogTitle>{t("conversations.stale.edit_title")}</DialogTitle>
              <DialogDescription>
                {t("conversations.stale.edit_message", { count: pendingStaleEdit?.affectedCount ?? 0 })}
              </DialogDescription>
            </DialogHeader>
            <DialogFooter className="flex-wrap gap-2">
              <Button type="button" variant="outline" onClick={() => setPendingStaleEdit(null)}>
                {t("conversations.stale.cancel")}
              </Button>
              <Button
                type="button"
                variant="secondary"
                disabled={staleActionBusy}
                onClick={() => {
                  if (!pendingStaleEdit) return;
                  const current = pendingStaleEdit;
                  setStaleActionBusy(true);
                  void commitEditedMessage(current.messageId, current.parts)
                    .then(() => setPendingStaleEdit(null))
                    .catch((error) => toast.error(error instanceof Error ? error.message : String(error)))
                    .finally(() => setStaleActionBusy(false));
                }}
                data-testid="stale-edit-only"
              >
                {t("conversations.stale.edit_only")}
              </Button>
              <Button
                type="button"
                disabled={staleActionBusy}
                onClick={() => {
                  if (!pendingStaleEdit) return;
                  const current = pendingStaleEdit;
                  setStaleActionBusy(true);
                  void commitEditedMessage(current.messageId, current.parts, current.firstAffectedNodeId)
                    .then(() => setPendingStaleEdit(null))
                    .catch((error) => toast.error(error instanceof Error ? error.message : String(error)))
                    .finally(() => setStaleActionBusy(false));
                }}
                data-testid="stale-edit-and-revoke"
              >
                {t("conversations.stale.edit_and_revoke")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        <Dialog open={pendingStaleDelete !== null} onOpenChange={(next) => !next && setPendingStaleDelete(null)}>
          <DialogContent data-testid="stale-delete-dialog">
            <DialogHeader>
              <DialogTitle>{t("conversations.stale.delete_title")}</DialogTitle>
              <DialogDescription>
                {t("conversations.stale.delete_message", { count: pendingStaleDelete?.affectedCount ?? 0 })}
              </DialogDescription>
            </DialogHeader>
            <DialogFooter className="flex-wrap gap-2">
              <Button type="button" variant="outline" onClick={() => setPendingStaleDelete(null)}>
                {t("conversations.stale.cancel")}
              </Button>
              <Button
                type="button"
                variant="secondary"
                disabled={staleActionBusy}
                onClick={() => {
                  if (!pendingStaleDelete || !activeId) return;
                  const current = pendingStaleDelete;
                  setStaleActionBusy(true);
                  void api.delete(`conversations/${activeId}/messages/${current.messageId}`)
                    .then(() => setPendingStaleDelete(null))
                    .catch((error) => toast.error(error instanceof Error ? error.message : String(error)))
                    .finally(() => setStaleActionBusy(false));
                }}
                data-testid="stale-delete-only"
              >
                {t("conversations.stale.delete_only")}
              </Button>
              <Button
                type="button"
                variant="destructive"
                disabled={staleActionBusy}
                onClick={() => {
                  if (!pendingStaleDelete || !activeId) return;
                  const current = pendingStaleDelete;
                  setStaleActionBusy(true);
                  void api.delete(`conversations/${activeId}/messages/${current.messageId}`)
                    .then(() => revokeContextRecord(activeId, current.firstAffectedNodeId, true))
                    .then(() => setPendingStaleDelete(null))
                    .catch((error) => toast.error(error instanceof Error ? error.message : String(error)))
                    .finally(() => setStaleActionBusy(false));
                }}
                data-testid="stale-delete-and-revoke"
              >
                {t("conversations.stale.delete_and_revoke")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        <Dialog open={pendingCascadeCheckpoint !== null} onOpenChange={(next) => !next && setPendingCascadeCheckpoint(null)}>
          <DialogContent data-testid="context-record-cascade-dialog">
            <DialogHeader>
              <DialogTitle>{t("conversations.context_records.revoke_title")}</DialogTitle>
              <DialogDescription>
                {t("conversations.context_records.revoke_later_message", {
                  count: pendingCascadeCheckpoint?.laterCount ?? 0,
                })}
              </DialogDescription>
            </DialogHeader>
            <DialogFooter className="flex-wrap gap-2">
              <Button type="button" variant="outline" onClick={() => setPendingCascadeCheckpoint(null)}>
                {t("conversations.context_records.cancel")}
              </Button>
              <Button
                type="button"
                variant="secondary"
                disabled={staleActionBusy}
                onClick={() => {
                  if (!pendingCascadeCheckpoint || !activeId) return;
                  const current = pendingCascadeCheckpoint;
                  setStaleActionBusy(true);
                  void revokeContextRecord(activeId, current.nodeId, false)
                    .then(() => setPendingCascadeCheckpoint(null))
                    .catch((error) => toast.error(error instanceof Error ? error.message : String(error)))
                    .finally(() => setStaleActionBusy(false));
                }}
                data-testid="context-record-revoke-single"
              >
                {t("conversations.context_records.revoke_single")}
              </Button>
              <Button
                type="button"
                variant="destructive"
                disabled={staleActionBusy}
                onClick={() => {
                  if (!pendingCascadeCheckpoint || !activeId) return;
                  const current = pendingCascadeCheckpoint;
                  setStaleActionBusy(true);
                  void revokeContextRecord(activeId, current.nodeId, true)
                    .then(() => setPendingCascadeCheckpoint(null))
                    .catch((error) => toast.error(error instanceof Error ? error.message : String(error)))
                    .finally(() => setStaleActionBusy(false));
                }}
                data-testid="context-record-revoke-cascade"
              >
                {t("conversations.context_records.revoke_cascade")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </div>
  );

  // 聊天栏顶部的提示位（会话标题 · 助手 / 模型，或 CLI 徽标）：只占聊天栏宽度、一行高
  const cliDisplay = React.useMemo(
    () =>
      detail?.cliSession
        ? resolveCliSessionDisplay(detail.cliSession, cliRuntime, activeId)
        : null,
    [activeId, cliRuntime, detail?.cliSession],
  );
  const cliSession = cliDisplay?.session ?? null;
  const cliBusy = cliDisplay?.busy ?? false;
  const cliSummary = cliSession
    ? formatCliSessionSummary(cliSession, settings?.customCliAgents)
    : null;
  const modelLabel =
    !isCliSession && currentModel && currentProvider
      ? `${getAssistantDisplayName(currentAssistant?.name)} / ${getModelDisplayName(currentModel.displayName, currentModel.modelId)} (${currentProvider.name})`
      : null;
  const headerSubtitle = cliSummary ?? modelLabel;
  const chatTitle = activeConversation
    ? activeConversation.title
    : t("conversations.header.select_conversation");
  const chatPane = (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-[45px] shrink-0 items-center gap-1 border-b px-1.5" data-testid="chat-header">
        <SidebarTrigger className="size-7" />
        <div
          className="flex min-w-0 flex-1 items-center gap-2 text-xs"
          title={headerSubtitle ? `${chatTitle}\n${headerSubtitle}` : chatTitle}
        >
          <span
            className={cn(
              isCliSession ? "max-w-[45%] shrink-0 truncate" : "max-w-[65%] shrink-0 truncate",
              activeConversation ? "text-foreground/90" : "text-muted-foreground",
            )}
          >
            {chatTitle}
          </span>
          {cliSession ? (
            <CliSessionBadge
              cliSession={cliSession}
              customCliAgents={settings?.customCliAgents}
              conversationId={activeId}
              busy={cliBusy}
              onControlError={setCliControlError}
            />
          ) : modelLabel ? (
            <span className="min-w-0 truncate text-muted-foreground/70">{modelLabel}</span>
          ) : null}
        </div>
        {isMobile && workspaceId ? (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("conversations.header.open_workspace")}
            title={t("conversations.header.open_workspace")}
            onClick={() => setWorkspaceDrawerOpen(true)}
          >
            <Folder className="size-4" />
          </Button>
        ) : null}
        {detail ? (
          <ConversationSettingsMenu
            key={detail.id}
            conversation={detail}
            assistant={
              settings?.assistants.find((item) => item.id === detail.assistantId) ?? null
            }
            workspaceId={
              settings?.assistants.find((item) => item.id === detail.assistantId)?.workspaceId ??
              null
            }
            canClearContext={(() => {
              const nodes = detail.messages ?? [];
              if (nodes.length === 0) return false;
              const lastNode = nodes[nodes.length - 1];
              const lastMsg = lastNode.messages[lastNode.selectIndex] ?? lastNode.messages[0];
              if (!lastMsg?.isContextCheckpoint) return true;
              const isClear =
                lastMsg.checkpointMeta?.kind === "clear" ||
                !lastMsg.parts.some((p) => p.type === "text" && p.text.trim());
              return !isClear;
            })()}
            onOpenCompress={() => setCompressDialogOpen(true)}
            onOpenContextRecords={() => setContextRecordsOpen(true)}
            onOpenContextUsage={() => setContextUsageOpen(true)}
            onClearContext={() => setClearConfirmOpen(true)}
          />
        ) : null}
      </div>
      {cliControlError ? (
        <div
          role="alert"
          data-testid="cli-control-error-banner"
          className="flex items-center justify-between gap-2 border-b border-destructive/20 bg-destructive/10 px-3 py-1.5 text-xs text-destructive"
        >
          <span className="min-w-0 flex-1 truncate">{cliControlError}</span>
          <button
            type="button"
            aria-label={t("common:actions.close")}
            onClick={() => setCliControlError(null)}
            className="shrink-0 rounded p-0.5 hover:bg-destructive/15"
          >
            <X className="size-3.5" />
          </button>
        </div>
      ) : null}
      {chatContent}
    </div>
  );

  return (
    <SidebarProvider
      open={sidebarOpen}
      onOpenChange={handleSidebarOpenChange}
      className="h-svh overflow-hidden"
      style={CONVERSATION_SIDEBAR_STYLE}
    >
      <ConversationSidebar
        conversations={conversations}
        activeId={activeId}
        loading={loading}
        error={error}
        hasMore={hasMore}
        loadMore={loadMore}
        userName={
          settings?.displaySetting.userNickname?.trim() || t("conversations.user.default_name")
        }
        userAvatar={settings?.displaySetting.userAvatar}
        assistants={assistants}
        assistantTags={settings?.assistantTags ?? []}
        currentAssistantId={currentAssistantId}
        onSelect={handleSelect}
        onAssistantChange={handleAssistantChange}
        onPin={handleTogglePinConversation}
        onRegenerateTitle={handleRegenerateConversationTitle}
        onMoveToAssistant={handleMoveConversation}
        onUpdateTitle={handleUpdateConversationTitle}
        onDelete={handleDeleteConversation}
        onCreateConversation={handleCreateConversation}
        webAuthEnabled={settings?.webServerJwtEnabled === true}
        folders={folders}
        selectedFolderId={selectedFolderId}
        onSelectFolder={handleSelectFolder}
        onCreateFolder={handleCreateFolder}
        onRenameFolder={handleRenameFolder}
        onDeleteFolder={handleDeleteFolder}
        onMoveToFolder={handleMoveConversationToFolder}
        onStartCharacterChat={handleStartCharacterChat}
      />
      <SidebarInset className="flex min-h-svh flex-col overflow-hidden">
        {!isMobile ? (
          <ResizablePanelGroup
            orientation="horizontal"
            className="min-h-0 flex-1"
            groupRef={panelGroupRef}
            onLayoutChanged={handlePanelLayoutChanged}
          >
            <ResizablePanel
              id="chat"
              defaultSize={workspaceId ? "50%" : "100%"}
              minSize="20%"
              className="flex min-h-0 flex-col"
            >
              {chatPane}
            </ResizablePanel>
            {showWorkbenchPanel && panel ? (
              <>
                <ResizableHandle withHandle />
                <ResizablePanel
                  id="workbench"
                  defaultSize="36%"
                  minSize="20%"
                  className="flex min-h-0 flex-col"
                >
                  <WorkbenchHost panel={panel} onClose={closePanel} className="border-l-0" />
                </ResizablePanel>
              </>
            ) : null}
            {workspaceId ? (
              <>
                <ResizableHandle withHandle />
                <ResizablePanel
                  id="workspace"
                  defaultSize="50%"
                  minSize="20%"
                  className="flex min-h-0 flex-col"
                >
                  <div ref={workspacePanelElRef} className="h-full min-h-0 overflow-hidden border-l">
                    <React.Suspense fallback={null}>
                      <WorkspaceChatPanel
                        key={workspaceId}
                        workspaceId={workspaceId}
                        focus={focusActive}
                        listContainer={focusListEl}
                        onFocusChange={setFocusMode}
                        onSplitChange={setWorkspaceSplit}
                      />
                    </React.Suspense>
                  </div>
                </ResizablePanel>
                <ResizableHandle
                  withHandle
                  className={cn(!focusActive && "pointer-events-none opacity-0")}
                />
                <ResizablePanel
                  id="focus-list"
                  defaultSize="0%"
                  minSize="14%"
                  collapsible
                  collapsedSize={0}
                  className="flex min-h-0 flex-col"
                >
                  <div
                    ref={setFocusListEl}
                    data-testid="focus-list-panel"
                    className={cn("h-full min-h-0 overflow-hidden", focusActive && "border-l")}
                  />
                </ResizablePanel>
              </>
            ) : null}
          </ResizablePanelGroup>
        ) : (
          chatPane
        )}

        {focusActive && panel ? (
          <Sheet
            open
            onOpenChange={(open) => {
              if (!open) closePanel();
            }}
          >
            <SheetContent side="right" showCloseButton={false} className="w-[min(760px,90vw)] p-0 sm:max-w-none">
              <SheetTitle className="sr-only">{t("conversations.header.open_workspace")}</SheetTitle>
              <WorkbenchHost panel={panel} onClose={closePanel} className="border-l-0" />
            </SheetContent>
          </Sheet>
        ) : null}

        {isMobile && panel ? (
          <Drawer
            open={hasWorkbenchPanel}
            onOpenChange={(open) => {
              if (!open) {
                closePanel();
              }
            }}
            direction="bottom"
          >
            <DrawerContent className="h-[85vh] max-h-[85vh]">
              <WorkbenchHost panel={panel} onClose={closePanel} className="border-l-0" />
            </DrawerContent>
          </Drawer>
        ) : null}

        {isMobile && workspaceId ? (
          <Drawer
            open={workspaceDrawerOpen}
            onOpenChange={open => { if (open) setWorkspaceDrawerOpen(true); else requestWorkspaceEditorLeave(() => setWorkspaceDrawerOpen(false)); }}
            direction="bottom"
          >
            <DrawerContent className="h-[92svh] max-h-[92svh]">
              <DrawerTitle className="sr-only">
                {t("conversations.header.open_workspace")}
              </DrawerTitle>
              <div className="min-h-0 flex-1 overflow-hidden">
                <React.Suspense fallback={null}>
                  <WorkspaceChatPanel key={workspaceId} workspaceId={workspaceId} />
                </React.Suspense>
              </div>
            </DrawerContent>
          </Drawer>
        ) : null}
      </SidebarInset>
    </SidebarProvider>
  );
}
