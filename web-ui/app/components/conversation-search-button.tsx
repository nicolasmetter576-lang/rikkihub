import * as React from "react";

import dayjs from "dayjs";
import { ArrowDownUp, Check, Search } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogTitle, DialogTrigger } from "~/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { ScrollArea } from "~/components/ui/scroll-area";
import { ToggleGroup, ToggleGroupItem } from "~/components/ui/toggle-group";
import {
  SIDEBAR_ICON_MODE_BUTTON_CLASS,
  SIDEBAR_ICON_MODE_HIDDEN_CLASS,
  SidebarIconTooltip,
} from "~/components/sidebar-icon-mode";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { MessageSearchResultDto } from "~/types";

export type ConversationSearchScope = "current" | "all";
export type ConversationSearchSort = "RELEVANCE" | "NEWEST_FIRST" | "OLDEST_FIRST";

const SORT_OPTIONS: Array<{ value: ConversationSearchSort; labelKey: string }> = [
  { value: "RELEVANCE", labelKey: "conversation_search.sort_relevance" },
  { value: "NEWEST_FIRST", labelKey: "conversation_search.sort_newest" },
  { value: "OLDEST_FIRST", labelKey: "conversation_search.sort_oldest" },
];

export interface ConversationSearchButtonProps {
  onSelect: (id: string, nodeId?: string) => void;
  /**
   * Assistant whose conversations the "current assistant" scope searches.
   * When omitted, that scope is unavailable.
   */
  assistantId?: string | null;
}

function SnippetText({ snippet }: { snippet: string }) {
  const parts: React.ReactNode[] = [];
  let index = 0;
  let keyIdx = 0;
  while (index < snippet.length) {
    const start = snippet.indexOf("[", index);
    if (start === -1) {
      parts.push(snippet.substring(index));
      break;
    }
    if (start > index) {
      parts.push(snippet.substring(index, start));
    }
    const end = snippet.indexOf("]", start + 1);
    if (end === -1) {
      parts.push(snippet.substring(start));
      break;
    }
    const matched = snippet.substring(start + 1, end);
    parts.push(
      <mark key={keyIdx++} className="bg-transparent font-semibold text-foreground not-italic">
        {matched}
      </mark>,
    );
    index = end + 1;
  }
  return <>{parts}</>;
}

export function formatRelativeTime(updateAt: number, t: (key: string) => string): string {
  const date = dayjs(updateAt);
  const today = dayjs().startOf("day");
  const yesterday = today.subtract(1, "day");
  if (date.isSame(today, "day")) return t("conversation_sidebar.today");
  if (date.isSame(yesterday, "day")) return t("conversation_sidebar.yesterday");
  const sameYear = date.year() === today.year();
  const native = date.toDate();
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    ...(sameYear ? {} : { year: "numeric" }),
  }).format(native);
}

export function ConversationSearchButton({
  onSelect,
  assistantId = null,
}: ConversationSearchButtonProps) {
  const { t } = useTranslation();
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const [scope, setScope] = React.useState<ConversationSearchScope>(
    assistantId ? "current" : "all",
  );
  const [sort, setSort] = React.useState<ConversationSearchSort>("RELEVANCE");
  const [searching, setSearching] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [results, setResults] = React.useState<MessageSearchResultDto[]>([]);
  const requestIdRef = React.useRef(0);

  const canScopeCurrent = Boolean(assistantId);

  React.useEffect(() => {
    if (!open) {
      setQuery("");
      setResults([]);
      setError(null);
      setSearching(false);
      return;
    }

    const keyword = query.trim();
    const wantsCurrent = scope === "current" && canScopeCurrent;
    if (!keyword || (scope === "current" && !canScopeCurrent)) {
      setResults([]);
      setError(null);
      setSearching(false);
      return;
    }

    const requestId = ++requestIdRef.current;
    const timer = window.setTimeout(() => {
      setSearching(true);
      setError(null);

      api
        .get<MessageSearchResultDto[]>("conversations/search", {
          searchParams: {
            query: keyword,
            sort,
            ...(wantsCurrent && assistantId ? { assistantId } : {}),
          },
        })
        .then((data) => {
          if (requestId !== requestIdRef.current) return;
          setResults(data);
        })
        .catch((searchError) => {
          if (requestId !== requestIdRef.current) return;
          if (searchError instanceof Error) {
            setError(searchError.message);
          } else {
            setError(t("conversation_search.search_failed"));
          }
        })
        .finally(() => {
          if (requestId !== requestIdRef.current) return;
          setSearching(false);
        });
    }, 250);

    return () => {
      window.clearTimeout(timer);
    };
  }, [open, query, scope, sort, assistantId, canScopeCurrent, t]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <SidebarIconTooltip label={t("conversation_search.search_conversations")} collapsedOnly>
        <DialogTrigger asChild>
          <Button
            variant="ghost"
            size="sm"
            className={cn("w-full justify-start", SIDEBAR_ICON_MODE_BUTTON_CLASS)}
            type="button"
            aria-label={t("conversation_search.search_conversations")}
          >
            <Search className="size-4" />
            <span className={SIDEBAR_ICON_MODE_HIDDEN_CLASS}>
              {t("conversation_search.search_conversations")}
            </span>
          </Button>
        </DialogTrigger>
      </SidebarIconTooltip>
      <DialogContent className="max-h-[80svh] max-w-xl overflow-hidden p-0">
        <DialogTitle className="sr-only">
          {t("conversation_search.search_conversations")}
        </DialogTitle>

        <div className="flex items-center gap-2 border-b px-4 py-3">
          <Search className="size-4 shrink-0 text-muted-foreground" />
          <input
            className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
            }}
            placeholder={t("conversation_search.input_placeholder")}
            autoFocus
          />
        </div>

        <div className="flex items-center justify-between gap-2 border-b px-4 py-2">
          <ToggleGroup
            type="single"
            variant="outline"
            size="sm"
            value={scope}
            onValueChange={(value) => {
              if (value === "current" || value === "all") {
                setScope(value);
              }
            }}
            className="shrink-0"
          >
            <ToggleGroupItem
              value="current"
              disabled={!canScopeCurrent}
              className="h-7 px-2.5 text-xs"
            >
              {t("conversation_search.scope_current_assistant")}
            </ToggleGroupItem>
            <ToggleGroupItem value="all" className="h-7 px-2.5 text-xs">
              {t("conversation_search.scope_all_assistants")}
            </ToggleGroupItem>
          </ToggleGroup>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-7 gap-1 px-2 text-xs text-muted-foreground"
              >
                <ArrowDownUp className="size-3.5" />
                {t(SORT_OPTIONS.find((option) => option.value === sort)?.labelKey ?? "")}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-44">
              {SORT_OPTIONS.map((option) => (
                <DropdownMenuItem key={option.value} onClick={() => setSort(option.value)}>
                  <span className="flex-1">{t(option.labelKey)}</span>
                  <Check className={option.value === sort ? "size-4" : "size-4 opacity-0"} />
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>

        <ScrollArea className="h-[420px]">
          <div className="p-2">
            {searching ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_search.searching")}
              </div>
            ) : null}

            {!searching && error ? (
              <div className="px-2 py-6 text-center text-sm text-destructive">{error}</div>
            ) : null}

            {!searching && !error && query.trim().length === 0 ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_search.type_to_start")}
              </div>
            ) : null}

            {!searching && !error && query.trim().length > 0 && results.length === 0 ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_search.no_results")}
              </div>
            ) : null}

            {!searching &&
              !error &&
              results.map((item) => (
                <button
                  key={`${item.conversationId}-${item.messageId}`}
                  type="button"
                  className="flex w-full items-start gap-3 rounded-md px-2 py-2 text-left transition hover:bg-muted"
                  onClick={() => {
                    onSelect(item.conversationId, item.nodeId);
                    setOpen(false);
                  }}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-medium">
                        {item.title || t("conversation_search.unnamed_conversation")}
                      </span>
                      <span className="shrink-0 text-xs text-muted-foreground">
                        {formatRelativeTime(item.updateAt, t)}
                      </span>
                    </div>
                    <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
                      <SnippetText snippet={item.snippet} />
                    </p>
                  </div>
                </button>
              ))}
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  );
}
