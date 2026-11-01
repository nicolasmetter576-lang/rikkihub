import * as React from "react";

import {
  ArrowUp,
  ChevronDown,
  File,
  FileDown,
  Flame,
  Image,
  LoaderCircle,
  MessageSquarePlus,
  Shrink,
  Mic,
  Plus,
  Square,
  Undo2,
  Video,
  WandSparkles,
  X,
  Zap,
} from "lucide-react";
import { flushSync } from "react-dom";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router";
import { toast } from "sonner";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { ModelList } from "~/components/input/model-list";
import { ReasoningPickerButton } from "~/components/input/reasoning-picker";
import { SearchPickerButton } from "~/components/input/search-picker";
import { McpPickerButton } from "~/components/input/mcp-picker";
import { ExtensionPickerButton } from "~/components/input/extension-picker";
import { WorkspacePickerButton } from "~/components/input/workspace-picker";
import { useChatInputStore, useSettingsStore } from "~/stores";
import { Button } from "~/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { resolveFileUrl } from "~/lib/files";
import { cn } from "~/lib/utils";
import { useWorkspaceFileCompletion } from "@overlay/hooks/use-workspace-file-completion";
import { WorkspaceCompletionPopup } from "@overlay/components/input/workspace-completion-popup";
import {
  formatCacheWarmCountdown,
  updateCacheKeepAlive,
  type CacheWarmState,
} from "@overlay/lib/cache-keep-alive";
import {
  createPromptPolishController,
  replaceTextareaWithUndo,
  resolvePromptPolishModel,
  streamPromptPolish,
  type PromptPolishState,
} from "@overlay/lib/prompt-polish";
import { getModelDisplayName } from "~/lib/display";
import api from "~/services/api";
import type { ConversationDto, UIMessagePart, UploadFilesResponseDto } from "~/types";

export interface ChatInputProps {
  value: string;
  attachments: UIMessagePart[];
  suggestions?: string[];
  conversation?: ConversationDto | null;
  draftKey?: string | null;
  ready?: boolean;
  disabled?: boolean;
  isGenerating?: boolean;
  cacheWarm?: CacheWarmState | null;
  isEditing?: boolean;
  onValueChange: (value: string) => void;
  onAddParts: (parts: UIMessagePart[]) => void;
  shouldDeleteFileOnRemove?: (part: UIMessagePart) => boolean;
  onRemovePart: (index: number, part: UIMessagePart) => Promise<void> | void;
  onSend: () => Promise<void> | void;
  /** Secondary send: enqueue the message without triggering a generation. */
  onSendNoAnswer?: () => Promise<void> | void;
  onStop?: () => Promise<void> | void;
  onCancelEdit?: () => void;
  onSuggestionClick?: (suggestion: string) => void;
  onExportConversation?: (includeReasoning: boolean) => void;
  /** Opens the compress-history dialog; absent when not applicable. */
  onCompressConversation?: () => void;
  /** Fresh conversation after the workspace picker changed its cwd / scope. */
  onConversationUpdated?: (conversation: ConversationDto) => void;
  /**
   * Desktop chat pane: size the toolbar by the pane's own width (container queries) instead of
   * the viewport, so a narrow resizable pane wraps the send cluster to a second row rather than
   * squeezing the tool icons into a column. Mobile keeps the viewport-based single row.
   */
  adaptiveToolbar?: boolean;
  className?: string;
}

const IMAGE_UPLOAD_ACCEPT = "image/*";

const ALLOWED_DOCUMENT_MIMES = new Set([
  "application/pdf",
  "application/msword",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.ms-excel",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.ms-powerpoint",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation",
]);

async function detectUploadFile(
  file: globalThis.File,
): Promise<{ allowed: boolean; mimeType: string }> {
  const buffer = await file.slice(0, 4100).arrayBuffer();
  // Dynamic import: file-type pulls a large signature table, only needed on upload.
  const { fileTypeFromBuffer } = await import("file-type");
  const detected = await fileTypeFromBuffer(buffer);

  // 无法识别 magic bytes → 文本文件 → 允许，强制 text/plain 防止 OS MIME 映射污染（如 .ts → video/mp2t）
  if (!detected) return { allowed: true, mimeType: "text/plain" };

  // 识别为图片 / 视频 / 音频 → 允许，使用 magic bytes 检测到的 MIME
  if (
    detected.mime.startsWith("image/") ||
    detected.mime.startsWith("video/") ||
    detected.mime.startsWith("audio/")
  ) {
    return { allowed: true, mimeType: detected.mime };
  }

  // 允许常见文档格式
  if (ALLOWED_DOCUMENT_MIMES.has(detected.mime)) {
    return { allowed: true, mimeType: detected.mime };
  }

  // 其他可识别的二进制格式（exe、zip 等）→ 拒绝
  return { allowed: false, mimeType: detected.mime };
}

function toMessagePart(file: UploadFilesResponseDto["files"][number]): UIMessagePart {
  if (file.mime.startsWith("image/")) {
    return {
      type: "image",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  if (file.mime.startsWith("video/")) {
    return {
      type: "video",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  if (file.mime.startsWith("audio/")) {
    return {
      type: "audio",
      url: file.url,
      metadata: { fileId: file.id },
    };
  }

  return {
    type: "document",
    url: file.url,
    fileName: file.fileName,
    mime: file.mime,
    metadata: { fileId: file.id },
  };
}

function partLabel(part: UIMessagePart, t: (key: string) => string): string {
  switch (part.type) {
    case "document":
      return part.fileName;
    case "image":
      return t("chat.attachment_image");
    case "video":
      return t("chat.attachment_video");
    case "audio":
      return t("chat.attachment_audio");
    default:
      return t("chat.attachment_file");
  }
}

function partIcon(part: UIMessagePart) {
  switch (part.type) {
    case "image":
      return <Image className="size-3.5" />;
    case "video":
      return <Video className="size-3.5" />;
    case "audio":
      return <Mic className="size-3.5" />;
    case "document":
      return <File className="size-3.5" />;
    default:
      return <File className="size-3.5" />;
  }
}

function getPartFileId(part: UIMessagePart): number | null {
  const value = part.metadata?.fileId;
  return typeof value === "number" ? value : null;
}

function hasFilesInDataTransfer(dataTransfer: DataTransfer | null): boolean {
  if (!dataTransfer) return false;
  if (dataTransfer.files.length > 0) return true;
  return Array.from(dataTransfer.items).some((item) => item.kind === "file");
}

function ChatInputInner({
  value,
  attachments,
  suggestions = [],
  conversation = null,
  draftKey = null,
  ready = true,
  disabled = false,
  isGenerating = false,
  cacheWarm = null,
  isEditing = false,
  onValueChange,
  onAddParts,
  shouldDeleteFileOnRemove,
  onRemovePart,
  onSend,
  onSendNoAnswer,
  onStop,
  onCancelEdit,
  onSuggestionClick,
  onExportConversation,
  onCompressConversation,
  onConversationUpdated,
  adaptiveToolbar = false,
  className,
}: ChatInputProps) {
  const { t } = useTranslation("input");
  const navigate = useNavigate();
  const sendOnEnter = useSettingsStore(
    (state) => state.settings?.displaySetting.sendOnEnter ?? true,
  );
  const pasteLongTextAsFile = useSettingsStore(
    (state) => state.settings?.displaySetting.pasteLongTextAsFile ?? false,
  );
  const pasteLongTextThreshold = useSettingsStore(
    (state) => state.settings?.displaySetting.pasteLongTextThreshold ?? 1000,
  );
  const { currentAssistant, settings } = useCurrentAssistant();
  const cacheKeepAliveEnabled = useSettingsStore(
    (state) => state.settings?.cacheKeepAliveConfig?.enabled ?? false,
  );

  const quickMessages = React.useMemo(() => {
    const ids = currentAssistant?.quickMessageIds;
    const allQuickMessages = settings?.quickMessages ?? [];
    if (!Array.isArray(ids) || ids.length === 0 || allQuickMessages.length === 0) {
      return [] as QuickMessageOption[];
    }
    const idSet = new Set(ids);
    return allQuickMessages
      .filter((qm) => idSet.has(qm.id))
      .map((qm) => ({
        title: qm.title.trim() || t("chat.quick_message_default_title"),
        content: qm.content.trim(),
      }))
      .filter((item): item is QuickMessageOption => item.content.length > 0);
  }, [currentAssistant?.quickMessageIds, settings?.quickMessages, t]);

  const imageInputRef = React.useRef<HTMLInputElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const [submitting, setSubmitting] = React.useState(false);
  const [uploading, setUploading] = React.useState(false);
  const [uploadMenuOpen, setUploadMenuOpen] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [dragActive, setDragActive] = React.useState(false);
  const dragDepthRef = React.useRef(0);
  const [composing, setComposing] = React.useState(false);
  // 保活倒计时驱动时钟：仅在 scheduled 状态下 500ms 走一次表
  const [clockNow, setClockNow] = React.useState(() => Date.now());
  // 补全插入后要恢复的光标位置（在父组件 value 更新后的 layoutEffect 里应用）
  const pendingCaretRef = React.useRef<number | null>(null);
  // 润色：一按即用设置里的润色模型改写输入框（流式覆盖），可撤销（按钮 ↶ 或 Ctrl+Z）
  const [polishState, setPolishState] = React.useState<PromptPolishState>({ phase: "idle" });
  const polishing = polishState.phase === "running";
  const polishLatest = React.useRef({ value, onValueChange, conversationId: conversation?.id ?? null, t });
  polishLatest.current = { value, onValueChange, conversationId: conversation?.id ?? null, t };
  const polisher = React.useMemo(
    () =>
      createPromptPolishController({
        read: () => polishLatest.current.value,
        write: (text) => polishLatest.current.onValueChange(text),
        commit: (original, polished) =>
          replaceTextareaWithUndo(textareaRef.current, original, polished, (text) => {
            flushSync(() => polishLatest.current.onValueChange(text));
          }),
        stream: (text, onEvent, signal) =>
          streamPromptPolish({ text, conversationId: polishLatest.current.conversationId }, onEvent, { signal }),
        onError: (code, message) => {
          const tr = polishLatest.current.t;
          if (code === "NO_MODEL") toast.error(tr("chat.polish_error_no_model"));
          else if (code === "EMPTY_RESULT") toast.error(tr("chat.polish_error_empty"));
          else toast.error(`${tr("chat.polish_failed")}: ${message || code}`);
        },
        onState: setPolishState,
      }),
    [],
  );
  React.useEffect(() => {
    polisher.textChanged(value);
  }, [polisher, value]);
  // 换会话（草稿换了）或卸载：断开；写了一半的话把原文写回原来那份草稿，不碰现在显示的草稿
  React.useEffect(() => {
    const key = draftKey;
    return () =>
      polisher.detach((original) => {
        if (key) useChatInputStore.getState().setText(key, original);
      });
  }, [polisher, draftKey]);
  const polishModel = resolvePromptPolishModel(settings, currentAssistant?.chatModelId ?? null);
  const toolsRowRef = React.useRef<HTMLDivElement | null>(null);
  const sendClusterRef = React.useRef<HTMLDivElement | null>(null);
  // Adaptive toolbar: whether the send cluster has wrapped under the tool icons. Only alignment
  // follows it (nothing that changes widths), so the wrap point never depends on resize history.
  const [toolbarWrapped, setToolbarWrapped] = React.useState(false);

  // 仅当前助手绑定 workspaceId 时启用（对齐 App WorkspaceCompletionProvider）
  const workspaceId = currentAssistant?.workspaceId ?? null;
  const completion = useWorkspaceFileCompletion({
    workspaceId,
    cwd: conversation?.workspaceCwd ?? null,
    enabled: ready && !disabled && workspaceId != null && !composing,
    textareaRef,
    onApplyValue: React.useCallback(
      (nextText: string, nextCaret: number) => {
        pendingCaretRef.current = nextCaret;
        onValueChange(nextText);
      },
      [onValueChange],
    ),
  });

  React.useLayoutEffect(() => {
    const tools = toolsRowRef.current;
    const send = sendClusterRef.current;
    if (!adaptiveToolbar || !tools || !send) {
      setToolbarWrapped(false);
      return;
    }
    const measure = () => setToolbarWrapped(send.offsetTop >= tools.offsetTop + tools.offsetHeight);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(tools);
    observer.observe(send);
    return () => observer.disconnect();
  }, [adaptiveToolbar]);

  React.useEffect(() => {
    if (cacheWarm?.state !== "scheduled") return;
    setClockNow(Date.now());
    const timer = setInterval(() => setClockNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, [cacheWarm?.state]);

  React.useLayoutEffect(() => {
    const caret = pendingCaretRef.current;
    if (caret == null) return;
    pendingCaretRef.current = null;
    const textarea = textareaRef.current;
    if (textarea) {
      textarea.focus();
      textarea.setSelectionRange(caret, caret);
      completion.refresh();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 只在 value 更新后应用一次
  }, [value]);

  const isEmpty = value.trim().length === 0 && attachments.length === 0;

  // 与 App 一致：生成中有输入 → 发送（消息进入队列）；生成中无输入 → 停止
  const canStop = ready && Boolean(onStop) && isGenerating && isEmpty && !disabled;
  const canSend = ready && !disabled && !isEmpty;
  const canUpload = ready && !disabled && !isGenerating && !uploading && !submitting;
  const canSwitchModel = ready && !disabled && !isGenerating && !uploading && !submitting;
  const canUseQuickMessage = ready && !disabled && !uploading && !submitting && !polishing;
  const actionDisabled = submitting || uploading || polishing || (!canStop && !canSend);
  const canPolish = ready && !disabled && value.trim().length > 0;

  React.useEffect(() => {
    if (!canUpload) {
      setUploadMenuOpen(false);
      setDragActive(false);
      dragDepthRef.current = 0;
    }
  }, [canUpload]);

  const uploadFiles = React.useCallback(
    async (fileList: FileList | globalThis.File[] | null) => {
      if (!ready || !fileList || fileList.length === 0) {
        return;
      }

      const allFiles = Array.from(fileList);
      const results = await Promise.all(
        allFiles.map(async (f) => ({ file: f, ...(await detectUploadFile(f)) })),
      );
      const uploadableFiles = results.filter((r) => r.allowed);
      const skippedFiles = results.filter((r) => !r.allowed);

      if (skippedFiles.length > 0) {
        toast.warning(t("chat.unsupported_file_skipped", { count: skippedFiles.length }));
      }

      if (uploadableFiles.length === 0) {
        return;
      }

      const formData = new FormData();
      uploadableFiles.forEach(({ file, mimeType }) => {
        // 用 magic bytes 检测结果覆盖浏览器的 file.type，修正跨平台 MIME 歧义
        const safeFile =
          file.type !== mimeType
            ? new globalThis.File([file], file.name, { type: mimeType })
            : file;
        formData.append("files", safeFile, safeFile.name);
      });

      setUploading(true);
      setError(null);
      try {
        const response = await api.postMultipart<UploadFilesResponseDto>("files/upload", formData);
        const parts = response.files.map(toMessagePart);
        onAddParts(parts);
      } catch (uploadError) {
        const message =
          uploadError instanceof Error ? uploadError.message : t("chat.upload_failed");
        setError(message);
      } finally {
        setUploading(false);
      }
    },
    [onAddParts, ready, t],
  );

  const handlePrimaryAction = React.useCallback(async () => {
    if (actionDisabled) {
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      if (canStop) {
        await onStop?.();
        return;
      }

      if (canSend) {
        await onSend();
      }
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : t("chat.send_failed");
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }, [actionDisabled, canSend, canStop, onSend, onStop, t]);

  const handleSecondaryAction = React.useCallback(async () => {
    if (!canSend || !onSendNoAnswer || polishing) {
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      await onSendNoAnswer();
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : t("chat.send_failed");
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }, [canSend, onSendNoAnswer, polishing, t]);

  const handleTextChange = React.useCallback(
    (event: React.ChangeEvent<HTMLTextAreaElement>) => {
      onValueChange(event.target.value);
      if (error) {
        setError(null);
      }
      completion.refresh();
    },
    [completion, error, onValueChange],
  );

  const handleQuickMessageSelect = React.useCallback(
    (content: string) => {
      if (!canUseQuickMessage || !content) {
        return;
      }

      const needLineBreak = value.length > 0 && !value.endsWith("\n");
      onValueChange(`${value}${needLineBreak ? "\n" : ""}${content}`);
      if (error) {
        setError(null);
      }
      textareaRef.current?.focus();
    },
    [canUseQuickMessage, error, onValueChange, value],
  );

  const handleSuggestionSelect = React.useCallback(
    (suggestion: string) => {
      if (!canUseQuickMessage || !suggestion) {
        return;
      }

      onSuggestionClick?.(suggestion);
      if (error) {
        setError(null);
      }
      textareaRef.current?.focus();
    },
    [canUseQuickMessage, error, onSuggestionClick],
  );

  const handleKeyDown = React.useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      // IME composition（中文输入等）期间不触发补全、不发送
      if (event.nativeEvent.isComposing) return;

      // 补全弹层优先消费 ↑↓ / Tab / Enter / Escape；候选就绪时 Enter 只插入选项不发送；
      // stopPropagation 防止 Escape 冒泡到外层 Drawer/Dialog 将其一起关掉
      if (completion.handleKeyDown(event)) {
        event.preventDefault();
        event.stopPropagation();
        return;
      }

      if (event.key !== "Enter") return;

      // 镜像逻辑：
      // sendOnEnter = true: Enter 发送，Shift+Enter 换行
      // sendOnEnter = false: Shift+Enter 发送，Enter 换行
      const shouldSend = sendOnEnter ? !event.shiftKey : event.shiftKey;
      if (!shouldSend) return;

      event.preventDefault();
      void handlePrimaryAction();
    },
    [completion, handlePrimaryAction, isGenerating, sendOnEnter],
  );

  const handleUploadInputChange = React.useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      // currentTarget is nulled after the await; capture target (the <input>) first.
      const input = event.target;
      await uploadFiles(input.files);
      input.value = "";
    },
    [uploadFiles],
  );

  const handlePaste = React.useCallback(
    async (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
      if (!canUpload) return;

      // 粘贴长文本自动转换为文件
      if (pasteLongTextAsFile) {
        const text = event.clipboardData.getData("text/plain");
        if (text.length > pasteLongTextThreshold) {
          event.preventDefault();
          const file = new globalThis.File([text], "pasted_text.txt", {
            type: "text/plain",
          });
          toast.info(t("chat.long_text_as_file"));
          void uploadFiles([file]);
          return;
        }
      }

      const files = Array.from(event.clipboardData.items)
        .filter((item) => item.kind === "file")
        .map((item) => item.getAsFile())
        .filter((file): file is globalThis.File => file !== null);

      if (files.length === 0) {
        return;
      }

      event.preventDefault();
      void uploadFiles(files);
    },
    [canUpload, pasteLongTextAsFile, pasteLongTextThreshold, t, uploadFiles],
  );

  const handleDragEnter = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      if (!canUpload || !hasFilesInDataTransfer(event.dataTransfer)) return;
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current += 1;
      setDragActive(true);
    },
    [canUpload],
  );

  const handleDragOver = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      if (!canUpload || !hasFilesInDataTransfer(event.dataTransfer)) return;
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = "copy";
      if (!dragActive) {
        setDragActive(true);
      }
    },
    [canUpload, dragActive],
  );

  const handleDragLeave = React.useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) {
      setDragActive(false);
    }
  }, []);

  const handleDrop = React.useCallback(
    async (event: React.DragEvent<HTMLDivElement>) => {
      if (!hasFilesInDataTransfer(event.dataTransfer)) return;
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current = 0;
      setDragActive(false);
      if (!canUpload) return;
      await uploadFiles(event.dataTransfer.files);
    },
    [canUpload, uploadFiles],
  );

  const sendHint = sendOnEnter ? t("chat.send_hint_enter") : t("chat.send_hint_newline");
  let placeholder: string;
  if (!ready) {
    placeholder = t("chat.placeholder_not_ready");
  } else if (cacheWarm?.state === "scheduled" && cacheWarm.nextWarmAt != null) {
    placeholder = formatCacheWarmCountdown(cacheWarm.nextWarmAt - clockNow);
  } else if (cacheWarm?.state === "refreshing") {
    placeholder = t("chat.cache_warm_refreshing");
  } else {
    placeholder = t("chat.cache_warm_inactive");
  }

  return (
    <div
      className={cn(
        "bg-background/95 backdrop-blur supports-backdrop-filter:bg-background/60",
        adaptiveToolbar && "@container/composer",
        className,
      )}
    >
      <div
        className={cn(
          "mx-auto w-full max-w-3xl",
          adaptiveToolbar ? "px-2 py-3 @md/composer:px-4 @md/composer:py-4" : "px-4 py-4",
        )}
      >
        <div
          className={cn(
            "relative flex flex-col gap-2 rounded-3xl border border-border/60 bg-muted/50 p-2.5 transition-colors focus-within:border-border",
            dragActive && "border-primary/40 bg-primary/5 ring-2 ring-primary/30",
          )}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={(event) => {
            void handleDrop(event);
          }}
        >
          {dragActive ? (
            <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center rounded-3xl border-2 border-dashed border-primary/50 bg-background/80 px-4 text-center text-sm font-medium text-primary">
              {t("chat.drop_to_upload")}
            </div>
          ) : null}
          {isEditing ? (
            <div className="flex items-center justify-between rounded-xl border border-primary/30 bg-primary/5 px-3 py-2 text-xs">
              <span className="text-primary">{t("chat.editing_tip")}</span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={onCancelEdit}
                disabled={submitting || uploading}
              >
                {t("chat.cancel_edit")}
              </Button>
            </div>
          ) : null}

          {suggestions.length > 0 ? (
            <div className="flex gap-2 overflow-x-auto rounded-lg px-1 py-1">
              {suggestions.map((suggestion, index) => (
                <button
                  key={`${suggestion}-${index}`}
                  type="button"
                  disabled={!canUseQuickMessage}
                  className={cn(
                    "shrink-0 rounded-lg border bg-background px-3 py-1 text-xs text-foreground transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50",
                  )}
                  onClick={() => {
                    handleSuggestionSelect(suggestion);
                  }}
                >
                  {suggestion}
                </button>
              ))}
            </div>
          ) : null}

          {attachments.length > 0 ? (
            <div className="flex flex-wrap gap-2 px-2 pt-1">
              {attachments.map((part, index) => {
                const key = `${part.type}-${index}`;
                return (
                  <div
                    key={key}
                    className="group inline-flex max-w-[220px] items-center gap-1 rounded-full border bg-background/80 px-2 py-1 text-xs"
                  >
                    {part.type === "image" ? (
                      <img
                        alt="upload"
                        className="size-5 rounded object-cover"
                        src={resolveFileUrl(part.url)}
                      />
                    ) : (
                      partIcon(part)
                    )}
                    <span className="truncate">{partLabel(part, t)}</span>
                    <button
                      className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                      onClick={async () => {
                        if (!ready || disabled || isGenerating || submitting) return;

                        const fileId = getPartFileId(part);
                        if (fileId != null && (shouldDeleteFileOnRemove?.(part) ?? true)) {
                          try {
                            await api.delete<{ status: string }>(`files/${fileId}`);
                          } catch (deleteError) {
                            const message =
                              deleteError instanceof Error
                                ? deleteError.message
                                : t("chat.delete_attachment_failed");
                            setError(message);
                            return;
                          }
                        }

                        await onRemovePart(index, part);
                      }}
                      type="button"
                    >
                      <X className="size-3" />
                    </button>
                  </div>
                );
              })}
            </div>
          ) : null}

          <div className="flex items-start gap-0.5">
          {quickMessages.length > 0 ? (
            <div className="shrink-0 pt-1">
              <QuickMessageButton
                quickMessages={quickMessages}
                disabled={!canUseQuickMessage}
                onSelect={handleQuickMessageSelect}
              />
            </div>
          ) : null}
          <Textarea
            ref={textareaRef}
            value={value}
            readOnly={polishing}
            onChange={handleTextChange}
            onKeyDown={handleKeyDown}
            onSelect={() => completion.refresh()}
            onClick={() => completion.refresh()}
            onKeyUp={(event) => {
              // 方向键已被弹层消费时不要重算（避免 selectedIndex 被回弹）
              if (
                completion.candidatesActive &&
                (event.key === "ArrowUp" ||
                  event.key === "ArrowDown" ||
                  event.key === "Enter" ||
                  event.key === "Tab")
              ) {
                return;
              }
              completion.refresh();
            }}
            onCompositionStart={() => setComposing(true)}
            onCompositionEnd={() => {
              setComposing(false);
              completion.refresh();
            }}
            onBlur={() => completion.cancel()}
            onPaste={(event) => {
              void handlePaste(event);
            }}
            placeholder={placeholder}
            disabled={!ready || disabled}
            className="min-h-[64px] max-h-[200px] min-w-0 flex-1 resize-none border-0 bg-transparent dark:bg-transparent p-2 text-sm shadow-none focus-visible:ring-0"
            rows={2}
          />
          </div>
          {completion.popupVisible ? (
            <WorkspaceCompletionPopup
              state={completion.state}
              onPick={completion.applyItem}
              onHighlight={completion.highlight}
            />
          ) : null}
          <div
            className={cn(
              "flex items-center",
              // Adaptive: the tool icons never shrink; once the space left beside them drops
              // under the send cluster's basis, that cluster wraps to its own row.
              adaptiveToolbar ? "flex-wrap gap-x-2 gap-y-1" : "justify-between gap-2",
            )}
          >
            <div
              ref={toolsRowRef}
              className={cn(
                "flex flex-wrap items-center gap-0.5",
                adaptiveToolbar ? "max-w-full shrink-0" : "min-w-0",
              )}
            >
              <DropdownMenu open={uploadMenuOpen} onOpenChange={setUploadMenuOpen}>
                <input
                  ref={fileInputRef}
                  className="hidden"
                  multiple
                  onChange={handleUploadInputChange}
                  type="file"
                />
                <input
                  ref={imageInputRef}
                  accept={IMAGE_UPLOAD_ACCEPT}
                  className="hidden"
                  multiple
                  onChange={handleUploadInputChange}
                  type="file"
                />
                <DropdownMenuTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={!canUpload}
                    className="size-8 rounded-full text-muted-foreground hover:text-foreground"
                  >
                    <Plus
                      className={cn("size-4 transition-transform", uploadMenuOpen && "rotate-45")}
                    />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent className="min-w-36" side="top" align="start">
                  <DropdownMenuItem
                    onClick={() => {
                      imageInputRef.current?.click();
                    }}
                  >
                    <Image className="size-4" />
                    {t("chat.upload_image")}
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    onClick={() => {
                      fileInputRef.current?.click();
                    }}
                  >
                    <File className="size-4" />
                    {t("chat.upload_document")}
                  </DropdownMenuItem>
                  {onExportConversation && (
                    <DropdownMenuItem
                      onClick={() => {
                        onExportConversation(false);
                      }}
                    >
                      <FileDown className="size-4" />
                      {t("chat.export_conversation")}
                    </DropdownMenuItem>
                  )}
                  {onExportConversation && (
                    <DropdownMenuItem
                      onClick={() => {
                        onExportConversation(true);
                      }}
                    >
                      <FileDown className="size-4" />
                      {t("chat.export_conversation_with_reasoning")}
                    </DropdownMenuItem>
                  )}
                  {onCompressConversation && (
                    <DropdownMenuItem onSelect={onCompressConversation}>
                      <Shrink className="size-4" />
                      {t("chat.compress_conversation")}
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuItem
                    onSelect={() => {
                      navigate("/settings/cache-keep-alive");
                    }}
                  >
                    <Flame className="size-4" />
                    {t("chat.cache_warm_menu")}
                    <Switch
                      size="sm"
                      className="ml-auto"
                      checked={cacheKeepAliveEnabled}
                      // 开关只切换启停，不得触发菜单项 onSelect（跳转配置页）。
                      // Radix MenuItem 未见 pointerdown 时会在 pointerup 合成 click，
                      // 因此 pointerdown / pointerup / click 三者都截断冒泡。
                      onClick={(event) => {
                        event.stopPropagation();
                      }}
                      onPointerDown={(event) => {
                        event.stopPropagation();
                      }}
                      onPointerUp={(event) => {
                        event.stopPropagation();
                      }}
                      onCheckedChange={(checked) => {
                        void updateCacheKeepAlive({ enabled: checked }).catch((error) =>
                          toast.error(error instanceof Error ? error.message : String(error)),
                        );
                      }}
                    />
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
              <SearchPickerButton disabled={!canSwitchModel} />
              <McpPickerButton disabled={!canSwitchModel} />
              <ExtensionPickerButton
                conversationId={conversation?.id ?? null}
                conversationModeInjectionIds={conversation?.modeInjectionIds}
                conversationLorebookIds={conversation?.lorebookIds}
                draftKey={draftKey}
                disabled={!canSwitchModel}
              />
              <WorkspacePickerButton
                conversation={conversation}
                onConversationUpdated={onConversationUpdated}
                disabled={!canSwitchModel}
                labelClassName={adaptiveToolbar ? "@2xl/composer:inline" : undefined}
              />
            </div>
            <div
              ref={sendClusterRef}
              className={cn(
                "flex min-w-0 items-center",
                // Adaptive: grows into the rest of the row (or the whole second row); its own
                // width then decides how much model name and reasoning label fit. Beside the
                // tools it hugs the right edge; on its own row the model starts at the left.
                adaptiveToolbar
                  ? cn("@container/send grow basis-76 gap-1", !toolbarWrapped && "justify-end")
                  : "shrink-0 gap-0.5 sm:gap-1",
              )}
            >
              <ModelList
                disabled={!canSwitchModel}
                className={adaptiveToolbar ? "max-w-56 shrink" : "max-w-[120px] sm:max-w-56"}
                popoverAlign={toolbarWrapped ? "start" : "end"}
              />
              <ReasoningPickerButton
                disabled={!canSwitchModel}
                className={adaptiveToolbar ? "shrink-0" : undefined}
                labelClassName={adaptiveToolbar ? "@[25rem]/send:block" : undefined}
                popoverAlign={toolbarWrapped ? "start" : "end"}
              />
              <Button
                type="button"
                data-testid="chat-polish-button"
                aria-label={
                  polishState.phase === "running"
                    ? t("chat.polish_cancel")
                    : polishState.phase === "done"
                      ? t("chat.polish_undo")
                      : t("chat.polish")
                }
                title={
                  polishState.phase === "running"
                    ? t("chat.polish_cancel")
                    : polishState.phase === "done"
                      ? t("chat.polish_undo")
                      : polishModel
                        ? t("chat.polish_with", { model: getModelDisplayName(polishModel.displayName, polishModel.modelId) })
                        : t("chat.polish")
                }
                onClick={() => {
                  if (polishState.phase === "running") polisher.cancel();
                  else if (polishState.phase === "done") polisher.undo();
                  else if (canPolish) void polisher.start();
                }}
                disabled={polishState.phase === "idle" && !canPolish}
                size="icon"
                variant="ghost"
                className={cn(
                  "size-9 shrink-0 rounded-full",
                  polishState.phase === "done" ? "text-primary" : "text-muted-foreground hover:text-foreground",
                  toolbarWrapped && "ml-auto",
                )}
              >
                {polishState.phase === "running" ? (
                  <LoaderCircle className="size-4 animate-spin" />
                ) : polishState.phase === "done" ? (
                  <Undo2 className="size-4" />
                ) : (
                  <WandSparkles className="size-4" />
                )}
              </Button>
              <Button
                aria-label={canStop ? t("chat.stop_generating") : t("chat.send")}
                title={sendHint}
                onClick={() => {
                  void handlePrimaryAction();
                }}
                disabled={actionDisabled}
                size="icon"
                className={cn(
                  "size-9 shrink-0 rounded-full",
                  canStop && !submitting
                    ? "bg-destructive text-destructive-foreground hover:bg-destructive/90"
                    : "bg-primary text-primary-foreground hover:bg-primary/90",
                )}
              >
                {submitting || uploading ? (
                  <LoaderCircle className="size-4 animate-spin" />
                ) : canStop ? (
                  <Square className="size-4" />
                ) : (
                  <ArrowUp className="size-4" />
                )}
              </Button>
              {onSendNoAnswer && !isEditing ? (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button
                      aria-label={t("chat.send_more_options")}
                      title={t("chat.send_more_options")}
                      disabled={!canSend || submitting || polishing}
                      size="icon"
                      variant="ghost"
                      className="size-9 shrink-0 rounded-full text-muted-foreground hover:text-foreground"
                    >
                      <ChevronDown className="size-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent side="top" align="end" className="min-w-48">
                    <DropdownMenuItem
                      onClick={() => {
                        void handleSecondaryAction();
                      }}
                    >
                      <MessageSquarePlus className="size-4" />
                      {t("chat.send_without_answer")}
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              ) : null}
            </div>
          </div>
        </div>
        <p className="sr-only">{sendHint}</p>
        {error ? <p className="mt-1 text-center text-xs text-destructive">{error}</p> : null}
      </div>
    </div>
  );
}

export const ChatInput = React.memo(ChatInputInner);
ChatInput.displayName = "ChatInput";

type QuickMessageOption = {
  title: string;
  content: string;
};

interface QuickMessageButtonProps {
  quickMessages: QuickMessageOption[];
  disabled?: boolean;
  onSelect: (content: string) => void;
}

function QuickMessageButton({
  quickMessages,
  disabled = false,
  onSelect,
}: QuickMessageButtonProps) {
  if (quickMessages.length === 0) {
    return null;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          disabled={disabled}
          className="size-8 rounded-full text-muted-foreground hover:text-foreground"
        >
          <Zap className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        className="w-[min(92vw,20rem)] max-h-64 overflow-y-auto"
        side="top"
        align="start"
      >
        {quickMessages.map((quickMessage, index) => {
          const key = `${quickMessage.title}-${index}`;
          return (
            <DropdownMenuItem
              key={key}
              className="items-start"
              onClick={() => {
                onSelect(quickMessage.content);
              }}
            >
              <div className="min-w-0">
                <div className="truncate text-sm font-medium">{quickMessage.title}</div>
                <div className="text-muted-foreground mt-0.5 line-clamp-2 text-xs">
                  {quickMessage.content}
                </div>
              </div>
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
