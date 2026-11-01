import * as React from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";

import {
  ArrowDown,
  ChevronLeft,
  ChevronRight,
  Copy,
  Ellipsis,
  FileDown,
  GitFork,
  Layers,
  Pencil,
  RefreshCw,
  Repeat,
  Star,
  Trash2,
  Zap,
} from "lucide-react";

import { useSettingsStore } from "~/stores";
import type {
  AssistantProfile,
  MessageDto,
  MessageNodeDto,
  ProviderModel,
  UIMessagePart,
} from "~/types";

import { copyTextToClipboard } from "~/lib/clipboard";
import { convertMessageToMarkdown, downloadMarkdown } from "~/lib/export-markdown";
import { cn } from "~/lib/utils";
import { Button } from "~/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { ChatMessageAnnotationsRow } from "./chat-message-annotations";
import { ChatMessageAvatarRow } from "./chat-message-avatar-row";
import { ChatMessageCheckpoint } from "./chat-message-checkpoint";
import { ChatMessageTranslation, TranslateActionsButton } from "./chat-message-translation";
import { MessageParts } from "./message-part";

interface ChatMessageProps {
  node: MessageNodeDto;
  message: MessageDto;
  loading?: boolean;
  isLastMessage?: boolean;
  assistant?: AssistantProfile | null;
  model?: ProviderModel | null;
  translating?: boolean;
  onEdit?: (message: MessageDto) => void | Promise<void>;
  onRegenerate?: (messageId: string) => void | Promise<void>;
  onSelectBranch?: (nodeId: string, selectIndex: number) => void | Promise<void>;
  onDelete?: (messageId: string) => void | Promise<void>;
  onFork?: (messageId: string) => void | Promise<void>;
  onToggleFavorite?: (nodeId: string) => void | Promise<void>;
  onTranslate?: (messageId: string, language: string) => void | Promise<void>;
  onClearTranslation?: (messageId: string) => void | Promise<void>;
  onToolApproval?: (
    toolCallId: string,
    approved: boolean,
    reason: string,
    answer?: string,
  ) => void | Promise<void>;
}

function hasRenderablePart(part: UIMessagePart): boolean {
  switch (part.type) {
    case "text":
      return part.text.trim().length > 0;
    case "image":
    case "video":
    case "audio":
      return part.url.trim().length > 0;
    case "document":
      return part.url.trim().length > 0 || part.fileName.trim().length > 0;
    case "reasoning":
      return part.reasoning.trim().length > 0;
    case "tool":
    case "server_tool":
      return true;
  }
}

function formatPartForCopy(part: UIMessagePart, t: TFunction): string | null {
  switch (part.type) {
    case "text":
      return part.text;
    case "image":
      return `[${t("chat_message.copy_image")}] ${part.url}`;
    case "video":
      return `[${t("chat_message.copy_video")}] ${part.url}`;
    case "audio":
      return `[${t("chat_message.copy_audio")}] ${part.url}`;
    case "document":
      return `[${t("chat_message.copy_document")}] ${part.fileName}`;
    case "reasoning":
      return part.reasoning;
    case "tool":
      return `[${t("chat_message.copy_tool")}] ${part.toolName}`;
    case "server_tool":
      return `${t("server_tool.label", { name: part.toolName })}\n${JSON.stringify({ input: part.input, output: part.output }, null, 2)}`;
  }
}

function buildCopyText(parts: UIMessagePart[], t: TFunction): string {
  return parts
    .map((part) => formatPartForCopy(part, t))
    .filter((value): value is string => Boolean(value && value.trim().length > 0))
    .join("\n\n")
    .trim();
}

function hasEditableContent(parts: UIMessagePart[]): boolean {
  return parts.some(
    (part) =>
      part.type === "text" ||
      part.type === "image" ||
      part.type === "video" ||
      part.type === "audio" ||
      part.type === "document",
  );
}

const NUMBER_FORMATTER = new Intl.NumberFormat();

function formatNumber(value: number): string {
  return NUMBER_FORMATTER.format(value);
}

function getDurationMs(createdAt: string, finishedAt?: string | null): number | null {
  const start = Date.parse(createdAt);
  if (Number.isNaN(start)) return null;

  const end = finishedAt ? Date.parse(finishedAt) : Date.now();
  if (Number.isNaN(end) || end <= start) return null;

  return end - start;
}

interface NerdStatsItem {
  key: string;
  icon: React.ReactNode;
  label: string;
}

/**
 * 统计行。多轮工具调用时 usage 是各步累计值：行内显示最后一次请求的上下文占用，
 * 累计值放在悬停提示里。
 */
export function getNerdStats(
  message: Pick<
    MessageDto,
    "usage" | "lastUsage" | "stepCount" | "generationTimeMs" | "createdAt" | "finishedAt"
  >,
  contextLength: number | null | undefined,
  t: TFunction,
): { items: NerdStatsItem[]; details: string } {
  const usage = message.usage;
  if (!usage) return { items: [], details: "" };
  const last = message.lastUsage ?? usage;
  const steps = message.stepCount ?? 1;
  const contextTokens = last.promptTokens + last.completionTokens;
  const items: NerdStatsItem[] = [];

  if (steps > 1) {
    items.push({
      key: "steps",
      icon: <Repeat className="size-3" />,
      label: t("chat_message.usage_steps", { count: steps }),
    });
  }

  items.push({
    key: "context",
    icon: <Layers className="size-3" />,
    label:
      last.cachedTokens > 0
        ? t("chat_message.usage_context_cached", {
            tokens: formatNumber(contextTokens),
            cached: formatNumber(last.cachedTokens),
          })
        : t("chat_message.usage_context", { tokens: formatNumber(contextTokens) }),
  });

  items.push({
    key: "completion",
    icon: <ArrowDown className="size-3" />,
    label: t("chat_message.usage_output", { tokens: formatNumber(usage.completionTokens) }),
  });

  // 旧消息没有记录生成耗时，退回用创建到完成的总时长估算
  const durationMs =
    message.generationTimeMs ?? getDurationMs(message.createdAt, message.finishedAt);
  const tps = durationMs && usage.completionTokens > 0 ? usage.completionTokens / (durationMs / 1000) : null;
  if (tps !== null) {
    items.push({
      key: "speed",
      icon: <Zap className="size-3" />,
      label: t("chat_message.tokens_per_second", { value: tps.toFixed(1) }),
    });
  }

  const exact = (value: number) => value.toLocaleString();
  const lines: string[] = [];
  const window = contextLength && contextLength > 0 ? contextLength : null;
  lines.push(
    window
      ? t("chat_message.usage_detail_context_window", {
          tokens: exact(contextTokens),
          window: exact(window),
          percent: Math.round((contextTokens / window) * 100),
        })
      : t("chat_message.usage_detail_context", { tokens: exact(contextTokens) }),
  );
  lines.push(
    t("chat_message.usage_detail_last", {
      input: exact(last.promptTokens),
      cached: exact(last.cachedTokens),
      output: exact(last.completionTokens),
    }),
  );
  if (steps > 1) {
    lines.push(t("chat_message.usage_detail_steps", { count: steps }));
    lines.push(
      t("chat_message.usage_detail_total", {
        input: exact(usage.promptTokens),
        cached: exact(usage.cachedTokens),
        output: exact(usage.completionTokens),
      }),
    );
  }
  if (durationMs) {
    lines.push(t("chat_message.usage_detail_duration", { value: (durationMs / 1000).toFixed(1) }));
  }
  return { items, details: lines.join("\n") };
}

function parseToolOutputJson(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) return null;

  try {
    return JSON.parse(trimmed);
  } catch {
    // Some models wrap JSON in fenced blocks.
    const fenced = trimmed.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
    if (!fenced) return null;
    try {
      return JSON.parse(fenced[1]);
    } catch {
      return null;
    }
  }
}

function buildCitationUrlMap(parts: UIMessagePart[]): Map<string, string> {
  const map = new Map<string, string>();

  parts.forEach((part) => {
    if (part.type !== "tool" || part.toolName !== "search_web") return;
    const outputText = part.output
      .filter(
        (outputPart): outputPart is { type: "text"; text: string } => outputPart.type === "text",
      )
      .map((outputPart) => outputPart.text)
      .join("\n");
    const parsed = parseToolOutputJson(outputText);
    if (!parsed || typeof parsed !== "object") return;
    const items = (parsed as { items?: unknown }).items;
    if (!Array.isArray(items)) return;

    items.forEach((item) => {
      if (!item || typeof item !== "object") return;
      const id = String((item as { id?: unknown }).id ?? "").trim();
      const url = String((item as { url?: unknown }).url ?? "").trim();
      if (!id || !url) return;
      if (!map.has(id)) {
        map.set(id, url);
      }
    });
  });

  return map;
}

const ChatMessageActionsRow = React.memo(
  ({
    node,
    message,
    loading,
    alignRight,
    onEdit,
    onRegenerate,
    onSelectBranch,
    onDelete,
    onFork,
    onToggleFavorite,
    onTranslate,
    onClearTranslation,
  }: {
    node: MessageNodeDto;
    message: MessageDto;
    loading: boolean;
    alignRight: boolean;
    onEdit?: (message: MessageDto) => void | Promise<void>;
    onRegenerate?: (messageId: string) => void | Promise<void>;
    onSelectBranch?: (nodeId: string, selectIndex: number) => void | Promise<void>;
    onDelete?: (messageId: string) => void | Promise<void>;
    onFork?: (messageId: string) => void | Promise<void>;
    onToggleFavorite?: (nodeId: string) => void | Promise<void>;
    onTranslate?: (language: string) => void | Promise<void>;
    onClearTranslation?: () => void | Promise<void>;
  }) => {
    const { t } = useTranslation("message");
    const [regenerating, setRegenerating] = React.useState(false);
    const [switchingBranch, setSwitchingBranch] = React.useState(false);
    const [deleting, setDeleting] = React.useState(false);
    const [forking, setForking] = React.useState(false);
    const [favoriting, setFavoriting] = React.useState(false);

    const handleCopy = React.useCallback(async () => {
      const text = buildCopyText(message.parts, t);
      if (!text) return;

      try {
        await copyTextToClipboard(text);
      } catch {
        // Ignore copy failures to keep action row interaction uninterrupted.
      }
    }, [message.parts, t]);

    const handleRegenerate = React.useCallback(async () => {
      if (!onRegenerate) return;

      if (message.role === "USER") {
        const confirmed = window.confirm(t("chat_message.regenerate_from_user_confirm"));
        if (!confirmed) return;
      }

      setRegenerating(true);
      try {
        await onRegenerate(message.id);
      } finally {
        setRegenerating(false);
      }
    }, [message.id, message.role, onRegenerate, t]);

    const handleSwitchBranch = React.useCallback(
      async (selectIndex: number) => {
        if (!onSelectBranch) return;
        if (selectIndex < 0 || selectIndex > node.messages.length - 1) return;
        if (selectIndex === node.selectIndex) return;

        setSwitchingBranch(true);
        try {
          await onSelectBranch(node.id, selectIndex);
        } finally {
          setSwitchingBranch(false);
        }
      },
      [node.id, node.messages.length, node.selectIndex, onSelectBranch],
    );

    const handleDelete = React.useCallback(async () => {
      if (!onDelete) return;

      const confirmed = window.confirm(t("chat_message.delete_confirm"));
      if (!confirmed) return;

      setDeleting(true);
      try {
        await onDelete(message.id);
      } finally {
        setDeleting(false);
      }
    }, [message.id, onDelete, t]);

    const handleFork = React.useCallback(async () => {
      if (!onFork) return;

      setForking(true);
      try {
        await onFork(message.id);
      } finally {
        setForking(false);
      }
    }, [message.id, onFork]);

    const handleToggleFavorite = React.useCallback(async () => {
      if (!onToggleFavorite || favoriting) return;

      setFavoriting(true);
      try {
        await onToggleFavorite(node.id);
      } finally {
        setFavoriting(false);
      }
    }, [favoriting, node.id, onToggleFavorite]);

    const canSwitchBranch = Boolean(onSelectBranch) && node.messages.length > 1;
    const canEdit =
      Boolean(onEdit) &&
      (message.role === "USER" || message.role === "ASSISTANT") &&
      hasEditableContent(message.parts);
    const canTranslate = Boolean(onTranslate) && message.role === "ASSISTANT";
    const actionDisabled =
      loading || switchingBranch || regenerating || deleting || forking || favoriting;

    return (
      <div
        className={cn(
          "flex w-full items-center gap-1",
          alignRight ? "justify-end" : "justify-start",
        )}
      >
        <Button
          aria-label={t("chat_message.copy_message")}
          disabled={actionDisabled}
          onClick={() => {
            void handleCopy();
          }}
          size="icon-xs"
          title={t("chat_message.copy")}
          type="button"
          variant="ghost"
        >
          <Copy className="size-3.5" />
        </Button>

        {canEdit && (
          <Button
            aria-label={t("chat_message.edit_message")}
            disabled={actionDisabled}
            onClick={() => {
              void onEdit?.(message);
            }}
            size="icon-xs"
            title={t("chat_message.edit")}
            type="button"
            variant="ghost"
          >
            <Pencil className="size-3.5" />
          </Button>
        )}

        {canTranslate && onTranslate ? (
          <TranslateActionsButton
            hasTranslation={Boolean(message.translation?.trim())}
            disabled={actionDisabled}
            alignRight={alignRight}
            onTranslate={onTranslate}
            onClearTranslation={onClearTranslation ?? (() => {})}
          />
        ) : null}

        {onToggleFavorite ? (
          <Button
            aria-label={
              node.isFavorite ? t("chat_message.remove_favorite") : t("chat_message.add_favorite")
            }
            aria-pressed={node.isFavorite}
            disabled={actionDisabled}
            onClick={() => {
              void handleToggleFavorite();
            }}
            size="icon-xs"
            title={
              node.isFavorite ? t("chat_message.remove_favorite") : t("chat_message.add_favorite")
            }
            type="button"
            variant="ghost"
          >
            <Star className={cn("size-3.5", node.isFavorite && "fill-amber-400 text-amber-400")} />
          </Button>
        ) : null}

        {onRegenerate && (
          <Button
            aria-label={t("chat_message.regenerate")}
            disabled={actionDisabled}
            onClick={() => {
              void handleRegenerate();
            }}
            size="icon-xs"
            title={t("chat_message.regenerate")}
            type="button"
            variant="ghost"
          >
            <RefreshCw className={cn("size-3.5", regenerating && "animate-spin")} />
          </Button>
        )}

        {canSwitchBranch && (
          <>
            <Button
              aria-label={t("chat_message.previous_branch")}
              disabled={actionDisabled || node.selectIndex <= 0}
              onClick={() => {
                void handleSwitchBranch(node.selectIndex - 1);
              }}
              size="icon-xs"
              title={t("chat_message.previous_branch")}
              type="button"
              variant="ghost"
            >
              <ChevronLeft className="size-3.5" />
            </Button>
            <span className="text-[11px] text-muted-foreground">
              {node.selectIndex + 1}/{node.messages.length}
            </span>
            <Button
              aria-label={t("chat_message.next_branch")}
              disabled={actionDisabled || node.selectIndex >= node.messages.length - 1}
              onClick={() => {
                void handleSwitchBranch(node.selectIndex + 1);
              }}
              size="icon-xs"
              title={t("chat_message.next_branch")}
              type="button"
              variant="ghost"
            >
              <ChevronRight className="size-3.5" />
            </Button>
          </>
        )}

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              aria-label={t("chat_message.more_actions")}
              disabled={actionDisabled}
              size="icon-xs"
              title={t("chat_message.more_actions")}
              type="button"
              variant="ghost"
            >
              <Ellipsis className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align={alignRight ? "end" : "start"}>
            <DropdownMenuItem
              onSelect={() => {
                const content = convertMessageToMarkdown(message, false);
                downloadMarkdown(content, `message-${message.id}.md`);
              }}
            >
              <FileDown className="size-3.5" />
              {t("chat_message.export_markdown")}
            </DropdownMenuItem>
            <DropdownMenuItem
              onSelect={() => {
                const content = convertMessageToMarkdown(message, true);
                downloadMarkdown(content, `message-${message.id}.md`);
              }}
            >
              <FileDown className="size-3.5" />
              {t("chat_message.export_markdown_with_reasoning")}
            </DropdownMenuItem>
            {onFork && (
              <DropdownMenuItem
                disabled={actionDisabled}
                onSelect={() => {
                  void handleFork();
                }}
              >
                <GitFork className="size-3.5" />
                {t("chat_message.create_fork")}
              </DropdownMenuItem>
            )}
            {onDelete && (
              <DropdownMenuItem
                variant="destructive"
                disabled={actionDisabled}
                onSelect={() => {
                  void handleDelete();
                }}
              >
                <Trash2 className="size-3.5" />
                {t("chat_message.delete")}
              </DropdownMenuItem>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    );
  },
);

const ChatMessageNerdLineRow = React.memo(
  ({
    message,
    alignRight,
    contextLength,
  }: {
    message: MessageDto;
    alignRight: boolean;
    contextLength?: number | null;
  }) => {
    const { t } = useTranslation("message");
    const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);

    if (!displaySetting?.showTokenUsage || !message.usage) {
      return null;
    }

    const { items, details } = getNerdStats(message, contextLength, t);
    if (items.length === 0) return null;

    return (
      <div
        title={details}
        className={cn(
          "flex w-full flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground/50",
          alignRight ? "justify-end" : "justify-start",
        )}
      >
        {items.map((item) => (
          <div key={item.key} className="inline-flex items-center gap-1">
            {item.icon}
            <span>{item.label}</span>
          </div>
        ))}
      </div>
    );
  },
);

export const ChatMessage = React.memo(
  ({
    node,
    message,
    loading = false,
    isLastMessage = false,
    assistant,
    model,
    translating = false,
    onEdit,
    onRegenerate,
    onSelectBranch,
    onDelete,
    onFork,
    onToggleFavorite,
    onTranslate,
    onClearTranslation,
    onToolApproval,
  }: ChatMessageProps) => {
    const isUser = message.role === "USER";
    const hasMessageContent = message.parts.some(hasRenderablePart);
    const showActions = isLastMessage ? !loading : hasMessageContent;
    const citationUrlMap = React.useMemo(() => buildCitationUrlMap(message.parts), [message.parts]);
    const handleClickCitation = React.useCallback(
      (citationId: string) => {
        const url = citationUrlMap.get(citationId.trim());
        if (!url || typeof window === "undefined") return;
        window.open(url, "_blank", "noopener,noreferrer");
      },
      [citationUrlMap],
    );

    if (message.isContextCheckpoint) {
      return <ChatMessageCheckpoint message={message} onEdit={onEdit} onDelete={onDelete} />;
    }

    return (
      <div
        className={cn("flex flex-col gap-4", isUser ? "items-end" : "items-start")}
        data-message-role={message.role.toLowerCase()}
        data-message-loading={loading || undefined}
      >
        <div className="flex w-full flex-col gap-2">
          <ChatMessageAvatarRow
            message={message}
            hasMessageContent={hasMessageContent}
            loading={loading}
            assistant={assistant}
            model={model}
          />

          <div className={cn("flex w-full", isUser ? "justify-end" : "justify-start")}>
            <div
              data-message-bubble
              className={cn(
                "flex flex-col gap-2 text-sm",
                isUser ? "max-w-[85%] rounded-lg bg-muted px-4 py-3" : "w-full",
              )}
            >
              <MessageParts
                parts={message.parts}
                charts={message.charts}
                loading={loading}
                onToolApproval={onToolApproval}
                onClickCitation={handleClickCitation}
              />

              <ChatMessageTranslation
                translation={message.translation}
                translating={translating}
                onClickCitation={handleClickCitation}
              />
            </div>
          </div>
        </div>

        {showActions && (
          // Offset half the difference between the 24px button and its 14px icon.
          <div data-message-actions className={isUser ? "-mr-1.25" : "-ml-1.25"}>
            <ChatMessageActionsRow
              node={node}
              message={message}
              loading={loading}
              alignRight={isUser}
              onEdit={onEdit}
              onRegenerate={onRegenerate}
              onSelectBranch={onSelectBranch}
              onDelete={onDelete}
              onFork={onFork}
              onToggleFavorite={onToggleFavorite}
              onTranslate={
                onTranslate ? (language) => onTranslate(message.id, language) : undefined
              }
              onClearTranslation={
                onClearTranslation ? () => onClearTranslation(message.id) : undefined
              }
            />
          </div>
        )}

        <ChatMessageAnnotationsRow annotations={message.annotations} alignRight={isUser} />

        <ChatMessageNerdLineRow
          message={message}
          alignRight={isUser}
          contextLength={model?.contextLength}
        />
      </div>
    );
  },
);
