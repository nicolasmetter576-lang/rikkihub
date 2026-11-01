import * as React from "react";

import { useTranslation } from "react-i18next";

import { Tooltip, TooltipContent, TooltipTrigger } from "~/components/ui/tooltip";
import { cn } from "~/lib/utils";
import type { ConversationListDto } from "~/types";

/** 鼠标停留多久才显示完整标题（毫秒） */
export const CONVERSATION_RAIL_TOOLTIP_DELAY = 1000;

/**
 * 标题的第一个字（按字素切分，emoji/组合字符不会被拆开）；拉丁字母转大写；空标题返回「·」。
 */
export function conversationInitial(title: string | null | undefined): string {
  const text = (title ?? "").trim();
  if (!text) return "·";
  let first: string | undefined;
  if (typeof Intl !== "undefined" && "Segmenter" in Intl) {
    const segments = new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(text);
    first = segments[Symbol.iterator]().next().value?.segment;
  }
  first ??= Array.from(text)[0];
  return (first ?? "·").toLocaleUpperCase();
}

interface ConversationRailProps {
  conversations: ConversationListDto[];
  activeId: string | null;
  hasMore: boolean;
  loadMore: () => void;
  onSelect: (id: string) => void;
  className?: string;
}

/**
 * 侧边栏收成图标栏时，中间那段空白放会话列表：每个会话一个方块，只显示标题首字，
 * 停留一秒后浮出完整标题。顺序与展开时的列表相同（置顶在前），滚到底继续加载。
 */
export function ConversationRail({
  conversations,
  activeId,
  hasMore,
  loadMore,
  onSelect,
  className,
}: ConversationRailProps) {
  const { t } = useTranslation();
  const ordered = React.useMemo(
    () => [...conversations.filter((item) => item.isPinned), ...conversations.filter((item) => !item.isPinned)],
    [conversations],
  );
  const onScroll = (event: React.UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    if (hasMore && el.scrollTop + el.clientHeight >= el.scrollHeight - 48) loadMore();
  };

  return (
    <div
      data-testid="conversation-rail"
      aria-label={t("conversation_sidebar.conversations")}
      className={cn("min-h-0 flex-1 overflow-y-auto overflow-x-hidden py-1 [scrollbar-width:none]", className)}
      onScroll={onScroll}
    >
      <ul className="flex flex-col items-center gap-1">
        {ordered.map((item, index) => {
          const title = item.title.trim() || t("conversation_sidebar.unnamed_conversation");
          const active = item.id === activeId;
          const pinnedEnd = item.isPinned && !ordered[index + 1]?.isPinned;
          return (
            <li key={item.id} className={cn(pinnedEnd && "mb-1 border-b border-sidebar-border pb-1")}>
              <Tooltip delayDuration={CONVERSATION_RAIL_TOOLTIP_DELAY}>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    aria-label={title}
                    aria-current={active ? "true" : undefined}
                    onClick={() => onSelect(item.id)}
                    className={cn(
                      "relative flex size-8 items-center justify-center rounded-md text-sm text-sidebar-foreground/80 transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground",
                      active && "bg-sidebar-accent font-semibold text-sidebar-accent-foreground ring-1 ring-sidebar-ring/60",
                    )}
                  >
                    {conversationInitial(item.title)}
                    {item.isGenerating ? (
                      <span className="absolute top-0.5 right-0.5 size-1.5 animate-pulse rounded-full bg-primary" />
                    ) : null}
                  </button>
                </TooltipTrigger>
                <TooltipContent side="right" className="max-w-64">
                  {title}
                </TooltipContent>
              </Tooltip>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
