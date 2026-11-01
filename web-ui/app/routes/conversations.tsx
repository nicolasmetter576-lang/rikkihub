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
import { CompressDialog } from "~/components/compress-dialog";
import { ConversationNotices } from "~/components/conversation-notices";
import { conversationSizeInfo, lastContextTokens } from "~/lib/conversation-size";
import { collectLocalAttachmentUrls } from "@overlay/lib/draft-attachments";
import type { CacheWarmState } from "@overlay/lib/cache-keep-alive";
import { useAttachmentDraftLeases } from "@overlay/hooks/use-attachment-draft-leases";
import { ChatMessage } from "~/components/message/chat-message";
import { WorkspaceImageProvider } from "~/components/markdown/workspace-image-context";
import { workspaceImageContext } from "~/lib/workspace-image";
import { Button } from "~/components/ui/button";
import { Drawer, DrawerContent, DrawerTitle } from "~/components/ui/drawer";
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
import { convertConversationToMarkdown, downloadMarkdown } from "~/lib/export-markdown";
import { readSidebarOpen, writeSidebarOpen } from "~/lib/sidebar-state";
import { fitPanelLayout, widenPanelLayout } from "~/lib/panel-layout";
import { cn } from "~/lib/utils";
import api, { ApiError, sse } from "~/services/api";
import { useChatInputStore, useAppStore } from "~/stores";
import { WorkbenchHost } from "~/components/workbench/workbench-host";
import {
  useWorkbench,
  useWorkbenchController,
  WorkbenchProvider,
} from "~/components/workbench/workbench-context";
import {
  type CompressStateDto,
  type ConversationDto,
  type MessageNodeDto,
  type MessageDto,
  type ConversationNodeUpdateEventDto,
  type ConversationErrorEventDto,
  type ConversationSnapshotEventDto,
  type ConversationQueueEventDto,
  type MessageQueueDto,
  type ProviderModel,
  type Settings,
  type UIMessagePart,
} from "~/types";
import { Folder, MessageSquare } from "lucide-react";
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

type ConversationStreamEvent =
  | ConversationSnapshotEventDto
  | ConversationNodeUpdateEventDto
  | ConversationErrorEventDto
  | ConversationQueueEventDto;

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

function applyNodeUpdate(
  conversation: ConversationDto,
  event: ConversationNodeUpdateEventDto,
): ConversationDto {
  if (conversation.id !== event.conversationId) {
    return conversation;
  }

  const nextNodes = [...conversation.messages];
  const indexById = nextNodes.findIndex((node) => node.id === event.nodeId);
  const targetIndex = indexById >= 0 ? indexById : event.nodeIndex;

  if (targetIndex < 0) {
    return conversation;
  }

  if (targetIndex < nextNodes.length) {
    nextNodes[targetIndex] = event.node;
  } else if (targetIndex === nextNodes.length) {
    nextNodes.push(event.node);
  } else {
    return conversation;
  }

  return {
    ...conversation,
    messages: nextNodes,
    updateAt: event.updateAt,
    isGenerating: event.isGenerating,
  };
}

const NODE_UPDATE_FLUSH_MS = 30;
const STREAM_RETRY_DELAY_MIN_MS = 1_000;
const STREAM_RETRY_DELAY_MAX_MS = 30_000;

function useConversationDetail(
  activeId: string | null,
  updateSummary: ConversationSummaryUpdater,
  onStreamError?: () => void,
) {
  const { t } = useTranslation("page");
  const [detail, setDetail] = React.useState<ConversationDto | null>(null);
  const [detailLoading, setDetailLoading] = React.useState(false);
  const [detailError, setDetailError] = React.useState<string | null>(null);
  const [queue, setQueue] = React.useState<MessageQueueDto | null>(null);
  const [cacheWarm, setCacheWarm] = React.useState<CacheWarmState | null>(null);
  const [compressState, setCompressState] = React.useState<CompressStateDto | null>(null);
  const onStreamErrorRef = React.useRef(onStreamError);
  onStreamErrorRef.current = onStreamError;

  const resetDetail = React.useCallback(() => {
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
    setQueue(null);
    setCacheWarm(null);
    setCompressState(null);
  }, []);

  React.useEffect(() => {
    if (!activeId) {
      resetDetail();
      return;
    }

    let mounted = true;
    let retryAttempts = 0;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let stopRetrying = false;
    const pendingNodeUpdates = new Map<string, ConversationNodeUpdateEventDto>();
    let lastNodeUpdate: ConversationNodeUpdateEventDto | null = null;
    let flushTimer: ReturnType<typeof setTimeout> | null = null;
    setDetailLoading(true);
    setDetailError(null);

    const abortController = new AbortController();
    // Drop the previous conversation's queue immediately; the fresh fetch
    // below repopulates it.
    setQueue(null);
    // 保活状态按会话重置，等下一条 cache_warm SSE 重新下发
    setCacheWarm(null);
    // 压缩状态同理：连上后若有状态，服务端先推一次 compress_state
    setCompressState(null);

    const fetchSnapshot = () => {
      api
        .get<ConversationDto>(`conversations/${activeId}`)
        .then((data) => {
          if (!mounted) return;
          setDetail(data);
          updateSummary(toConversationSummaryUpdate(data));
        })
        .catch((err: Error) => {
          if (!mounted) return;
          setDetailError(err.message || t("conversations.errors.load_detail_failed"));
          setDetail(null);
        })
        .finally(() => {
          if (!mounted) return;
          setDetailLoading(false);
        });
    };

    const fetchQueue = () => {
      api
        .get<MessageQueueDto>(`conversations/${activeId}/queue`)
        .then((data) => {
          if (!mounted) return;
          setQueue(data);
        })
        .catch(() => {
          // Queue snapshot is best-effort; SSE queue_update keeps it fresh.
        });
    };

    fetchQueue();
    fetchSnapshot();

    const flushNodeUpdates = () => {
      flushTimer = null;
      if (!mounted) {
        pendingNodeUpdates.clear();
        return;
      }
      const lastEvent = lastNodeUpdate;
      lastNodeUpdate = null;
      // Copy + clear BEFORE setDetail: React may double-invoke updaters in dev.
      const events = Array.from(pendingNodeUpdates.values());
      pendingNodeUpdates.clear();
      if (lastEvent) {
        useAppStore.getState().setClockOffset(lastEvent.serverTime);
      }
      setDetail((prev) => {
        if (!prev) return prev;
        let next = prev;
        for (const event of events) {
          next = applyNodeUpdate(next, event);
        }
        // updateAt/isGenerating are conversation-level fields; the last event wins.
        if (lastEvent && next === prev && next.id === lastEvent.conversationId) {
          next = {
            ...next,
            updateAt: lastEvent.updateAt,
            isGenerating: lastEvent.isGenerating,
          };
        }
        if (next === prev) return prev;
        if (prev.isGenerating !== next.isGenerating) {
          updateSummary(toConversationSummaryUpdate(next));
        }
        return next;
      });
      setDetailError(null);
      setDetailLoading(false);
    };

    const scheduleNodeUpdate = (data: ConversationNodeUpdateEventDto) => {
      pendingNodeUpdates.set(data.nodeId, data);
      lastNodeUpdate = data;
      if (flushTimer !== null) return;
      flushTimer = setTimeout(flushNodeUpdates, NODE_UPDATE_FLUSH_MS);
    };

    const startStream = () => {
      void sse<ConversationStreamEvent>(
        `conversations/${activeId}/stream`,
        {
          onMessage: ({ event, data }) => {
            if (!mounted) return;

            if ((event as string) === "cache_warm") {
              const payload = data as {
                type?: string;
                state?: string;
                nextWarmAt?: number | null;
                serverTime?: number;
              };
              if (
                payload.type === "cache_warm" &&
                (payload.state === "inactive" ||
                  payload.state === "scheduled" ||
                  payload.state === "refreshing")
              ) {
                const offset =
                  typeof payload.serverTime === "number" ? payload.serverTime - Date.now() : 0;
                setCacheWarm({
                  state: payload.state,
                  nextWarmAt:
                    typeof payload.nextWarmAt === "number" ? payload.nextWarmAt - offset : null,
                  serverTime: payload.serverTime ?? Date.now(),
                });
              }
              return;
            }

            if ((event as string) === "compress_state") {
              const payload = data as unknown as CompressStateDto;
              if (payload?.conversationId === activeId) setCompressState(payload);
              return;
            }

            if (event === "queue_update") {
              const payload = data as ConversationQueueEventDto;
              if (payload.type === "queue_update") {
                setQueue(payload.queue);
              }
              return;
            }

            if (event === "error") {
              const payload = data as ConversationErrorEventDto;
              if (payload.type === "error") {
                toast.error(payload.message);
                onStreamErrorRef.current?.();
              }
              return;
            }

            if (event === "snapshot") {
              const payload = data as ConversationSnapshotEventDto;
              if (payload.type !== "snapshot") return;
              useAppStore.getState().setClockOffset(payload.serverTime);
              setDetail(payload.conversation);
              updateSummary(toConversationSummaryUpdate(payload.conversation));
              setDetailError(null);
              setDetailLoading(false);
              return;
            }

            if (event !== "node_update") return;
            const payload = data as ConversationNodeUpdateEventDto;
            if (payload.type !== "node_update") return;

            // Coalesce per-token updates into one render per ~30ms window.
            scheduleNodeUpdate(payload);
          },
          onOpen: () => {
            if (!mounted) return;
            const wasReconnect = retryAttempts > 0;
            retryAttempts = 0;
            if (wasReconnect) {
              // The stream re-sends a snapshot, but the REST fetch covers any gap
              // where events were missed while disconnected.
              fetchSnapshot();
              fetchQueue();
            }
          },
          onError: (streamError) => {
            if (!mounted) return;
            console.error("Conversation detail SSE error:", streamError);
            // 401 is terminal for this session; the auth gate handles re-auth.
            if (streamError instanceof ApiError && streamError.code === 401) {
              stopRetrying = true;
            }
          },
          onClose: () => {
            if (!mounted || abortController.signal.aborted || stopRetrying) return;
            const delay = Math.min(
              STREAM_RETRY_DELAY_MAX_MS,
              STREAM_RETRY_DELAY_MIN_MS * 2 ** retryAttempts,
            );
            retryAttempts += 1;
            retryTimer = setTimeout(() => {
              retryTimer = null;
              if (mounted) startStream();
            }, delay);
          },
        },
        { signal: abortController.signal },
      );
    };

    startStream();

    return () => {
      mounted = false;
      abortController.abort();
      if (retryTimer !== null) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      if (flushTimer !== null) {
        clearTimeout(flushTimer);
        flushTimer = null;
      }
      pendingNodeUpdates.clear();
    };
  }, [activeId, resetDetail, t, updateSummary]);

  const selectedNodeMessages = React.useMemo<SelectedNodeMessage[]>(() => {
    if (!detail) return [];
    return detail.messages.map((node) => ({
      node,
      message: node.messages[node.selectIndex] ?? node.messages[0],
    }));
  }, [detail]);

  return {
    detail,
    detailLoading,
    detailError,
    selectedNodeMessages,
    resetDetail,
    queue,
    setQueue,
    setDetail,
    cacheWarm,
    compressState,
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

const ConversationTimeline = React.memo(
  ({
    activeId,
    isHomeRoute,
    detailLoading,
    detailError,
    selectedNodeMessages,
    isGenerating,
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
  }: {
    activeId: string | null;
    isHomeRoute: boolean;
    detailLoading: boolean;
    detailError: string | null;
    selectedNodeMessages: SelectedNodeMessage[];
    isGenerating: boolean;
    settings: Settings | null;
    conversationAssistantId: string | null;
    translatingIds: ReadonlySet<string>;
    contentClassName?: string;
    onEdit: (message: MessageDto) => void | Promise<void>;
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
                    assistant={assistant}
                    model={model}
                    translating={translatingIds.has(message.id)}
                    onEdit={onEdit}
                    onDelete={onDelete}
                    onFork={onFork}
                    onRegenerate={onRegenerate}
                    onSelectBranch={onSelectBranch}
                    onToggleFavorite={onToggleFavorite}
                    onTranslate={onTranslate}
                    onClearTranslation={onClearTranslation}
                    onToolApproval={onToolApproval}
                  />
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
    resetDetail,
    queue,
    setQueue,
    setDetail,
    cacheWarm,
    compressState,
  } = useConversationDetail(activeId, updateConversationSummary, clearTranslating);

  // Background compression: banners while running / after a failure seen on this page.
  const compressRunning = compressState?.status === "running";
  const [compressDialogOpen, setCompressDialogOpen] = React.useState(false);
  const [compressFailure, setCompressFailure] = React.useState<CompressStateDto | null>(null);
  const [cancellingCompress, setCancellingCompress] = React.useState(false);
  const [sizeWarningDismissedFor, setSizeWarningDismissedFor] = React.useState<string | null>(null);
  const lastCompressRef = React.useRef<CompressStateDto | null>(null);

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
    // Only a running → finished transition of the same run is news; a replay on reconnect is not.
    const sameRun =
      previous?.status === "running" &&
      previous.conversationId === compressState.conversationId &&
      previous.startedAt === compressState.startedAt;
    if (!sameRun) return;
    if (compressState.status === "succeeded") toast.success(t("conversations.compress.succeeded"));
    else if (compressState.status === "cancelled")
      toast.info(t("conversations.compress.cancelled"));
    else setCompressFailure(compressState);
  }, [compressState, t]);

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
      resetDetail();
      if (routeId) {
        navigate("/", { replace: true });
      }
      refreshList();
    },
    [navigate, refreshList, resetDetail, routeId, setActiveId],
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
      await api.delete<{ status: string }>(`conversations/${activeId}/messages/${messageId}`);
    },
    [activeId, detail?.isGenerating, t],
  );

  const handleToggleFavorite = React.useCallback(
    async (nodeId: string) => {
      if (!activeId) return;
      try {
        const response = await api.post<{ isFavorite: boolean }>(
          `conversations/${activeId}/nodes/${nodeId}/favorite`,
        );
        setDetail((prev) => {
          if (!prev) return prev;
          return {
            ...prev,
            messages: prev.messages.map((node) =>
              node.id === nodeId ? { ...node, isFavorite: response.isFavorite } : node,
            ),
          };
        });
      } catch (favoriteError) {
        toast.error(
          favoriteError instanceof Error
            ? favoriteError.message
            : t("conversations.favorite_failed"),
        );
      }
    },
    [activeId, setDetail, t],
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
      setDetail((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          messages: prev.messages.map((node) => ({
            ...node,
            messages: node.messages.map((message) =>
              message.id === messageId ? { ...message, translation: null } : message,
            ),
          })),
        };
      });
    },
    [activeId, clearTranslating, setDetail, t],
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

  const handleSend = React.useCallback(async () => {
    if (!editingSession) {
      await handleSubmit();
      return;
    }

    if (!activeId) return;

    const draftParts = getCurrentSubmitParts();
    if (draftParts.length === 0) return;

    const nextParts = buildEditedParts(editingSession, draftParts);

    await api.post<{ status: string }>(
      `conversations/${activeId}/messages/${editingSession.messageId}/edit`,
      { parts: stripEditDraftMetadata(nextParts) },
    );

    setEditingSession(null);
    clearCurrentDraft();
  }, [activeId, clearCurrentDraft, editingSession, getCurrentSubmitParts, handleSubmit]);

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
        resetDetail();
        setHomeDraftId(createHomeDraftId());
        if (routeId === conversationId) {
          navigate("/", { replace: true });
        }
      }
      refreshList();
    },
    [activeId, navigate, refreshList, resetDetail, routeId, setActiveId],
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
        resetDetail();
        setHomeDraftId(createHomeDraftId());
        if (routeId === conversationId) {
          navigate("/", { replace: true });
        }
      }
      refreshList();
    },
    [activeId, navigate, refreshList, resetDetail, routeId, setActiveId],
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

  const handleCreateConversation = React.useCallback(() => {
    closePanel();
    setActiveId(null);
    resetDetail();
    setHomeDraftId(createHomeDraftId());

    if (routeId) {
      navigate("/");
    }
  }, [closePanel, navigate, resetDetail, routeId, setActiveId]);

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

  const chatContent = (
    <div
      className={cn("flex flex-1 flex-col min-h-0 overflow-hidden", isNewChat && "justify-center")}
    >
      {!isNewChat && (
        <div className="relative flex min-h-0 flex-1">
          <WorkspaceImageProvider value={workspaceImages}>
            <ConversationTimeline
              activeId={activeId}
              isHomeRoute={isHomeRoute}
              detailLoading={detailLoading}
              detailError={detailError}
              selectedNodeMessages={selectedNodeMessages}
              isGenerating={detail?.isGenerating ?? false}
              settings={settings}
              conversationAssistantId={detail?.assistantId ?? null}
              translatingIds={translatingIds}
              onEdit={handleStartEdit}
              onDelete={handleDeleteMessage}
              onFork={handleForkMessage}
              onRegenerate={handleRegenerate}
              onSelectBranch={handleSelectBranch}
              onToggleFavorite={handleToggleFavorite}
              onTranslate={handleTranslateMessage}
              onClearTranslation={handleClearTranslation}
              onToolApproval={handleToolApproval}
            />
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
        {activeId && !isNewChat ? (
          <ConversationNotices
            compressState={compressState}
            compressFailure={compressFailure}
            sizeInfo={sizeInfo}
            sizeWarningDismissed={sizeWarningDismissedFor === activeId}
            cancelling={cancellingCompress}
            onCancelCompression={() => void handleCancelCompression()}
            onOpenCompress={() => setCompressDialogOpen(true)}
            onDismissFailure={() => setCompressFailure(null)}
            onDismissSizeWarning={() => setSizeWarningDismissedFor(activeId)}
            onNewConversation={handleCreateConversation}
          />
        ) : null}
        {activeId ? (
          <ConversationQueuePanel
            conversationId={activeId}
            queue={queue}
            queueSize={detail?.queueSize ?? 0}
            queuePaused={detail?.queuePaused ?? false}
            onQueueChange={setQueue}
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
          disabled={detailLoading || Boolean(detailError)}
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
            detail && detail.messages.length >= 2 && !compressRunning
              ? () => setCompressDialogOpen(true)
              : undefined
          }
          adaptiveToolbar={!isMobile}
          onConversationUpdated={setDetail}
        />
        {activeId ? (
          <CompressDialog
            conversationId={activeId}
            open={compressDialogOpen}
            onOpenChange={setCompressDialogOpen}
            contextTokens={lastContextTokens(detail?.messages ?? [])}
            contextLength={currentModel?.contextLength ?? null}
          />
        ) : null}
      </div>
    </div>
  );

  // 聊天栏顶部的提示位（会话标题 · 助手 / 模型）：只占聊天栏宽度、一行高
  const modelLabel =
    currentModel && currentProvider
      ? `${getAssistantDisplayName(currentAssistant?.name)} / ${getModelDisplayName(currentModel.displayName, currentModel.modelId)} (${currentProvider.name})`
      : null;
  const chatTitle = activeConversation
    ? activeConversation.title
    : t("conversations.header.select_conversation");
  const chatPane = (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-[45px] shrink-0 items-center gap-1 border-b px-1.5" data-testid="chat-header">
        <SidebarTrigger className="size-7" />
        <div
          className="flex min-w-0 flex-1 items-baseline gap-2 text-xs"
          title={modelLabel ? `${chatTitle}\n${modelLabel}` : chatTitle}
        >
          <span
            className={cn(
              "max-w-[65%] shrink-0 truncate",
              activeConversation ? "text-foreground/90" : "text-muted-foreground",
            )}
          >
            {chatTitle}
          </span>
          {modelLabel ? (
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
            conversation={detail}
            assistant={
              settings?.assistants.find((item) => item.id === detail.assistantId) ?? null
            }
            workspaceId={
              settings?.assistants.find((item) => item.id === detail.assistantId)?.workspaceId ??
              null
            }
            onUpdated={setDetail}
          />
        ) : null}
      </div>
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
