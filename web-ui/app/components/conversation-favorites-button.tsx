import * as React from "react";

import { RefreshCw, Star, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { formatRelativeTime } from "~/components/conversation-search-button";
import {
  SIDEBAR_ICON_MODE_BUTTON_CLASS,
  SIDEBAR_ICON_MODE_HIDDEN_CLASS,
  SidebarIconTooltip,
} from "~/components/sidebar-icon-mode";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogTitle, DialogTrigger } from "~/components/ui/dialog";
import { ScrollArea } from "~/components/ui/scroll-area";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { FavoriteItemDto, FavoriteListResponse, FavoriteRecordDto } from "~/types";

export interface ConversationFavoritesButtonProps {
  onSelect: (id: string, nodeId?: string) => void;
}

/** Case-insensitive match on title and preview; blank query keeps everything. */
export function filterFavorites(items: FavoriteItemDto[], query: string): FavoriteItemDto[] {
  const keyword = query.trim().toLowerCase();
  if (!keyword) return items;
  return items.filter(
    (item) =>
      item.title.toLowerCase().includes(keyword) || item.preview.toLowerCase().includes(keyword),
  );
}

export function ConversationFavoritesButton({ onSelect }: ConversationFavoritesButtonProps) {
  const { t } = useTranslation();
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [data, setData] = React.useState<FavoriteListResponse | null>(null);
  const [removingId, setRemovingId] = React.useState<string | null>(null);
  const requestIdRef = React.useRef(0);

  const load = React.useCallback(() => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    api
      .get<FavoriteListResponse>("favorites")
      .then((result) => {
        if (requestId === requestIdRef.current) setData(result);
      })
      .catch((loadError) => {
        if (requestId !== requestIdRef.current) return;
        setError(
          loadError instanceof Error ? loadError.message : t("conversation_favorites.load_failed"),
        );
      })
      .finally(() => {
        if (requestId === requestIdRef.current) setLoading(false);
      });
  }, [t]);

  React.useEffect(() => {
    if (open) {
      load();
    } else {
      requestIdRef.current += 1;
      setQuery("");
      setError(null);
    }
  }, [open, load]);

  const restore = React.useCallback(
    async (record: FavoriteRecordDto) => {
      try {
        await api.post("favorites/restore", record);
        load();
      } catch {
        toast.error(t("conversation_favorites.restore_failed"));
      }
    },
    [load, t],
  );

  const remove = React.useCallback(
    async (item: FavoriteItemDto) => {
      setRemovingId(item.id);
      try {
        const record = await api.delete<FavoriteRecordDto>(
          `favorites/${encodeURIComponent(item.id)}`,
        );
        setData((current) =>
          current
            ? { ...current, items: current.items.filter((it) => it.id !== item.id) }
            : current,
        );
        toast.success(t("conversation_favorites.removed"), {
          action: {
            label: t("conversation_favorites.undo"),
            onClick: () => void restore(record),
          },
        });
      } catch {
        toast.error(t("conversation_favorites.remove_failed"));
      } finally {
        setRemovingId(null);
      }
    },
    [restore, t],
  );

  const items = data?.items ?? [];
  const visible = filterFavorites(items, query);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <SidebarIconTooltip label={t("conversation_favorites.title")} collapsedOnly>
        <DialogTrigger asChild>
          <Button
            variant="ghost"
            size="sm"
            className={cn("w-full justify-start", SIDEBAR_ICON_MODE_BUTTON_CLASS)}
            type="button"
            aria-label={t("conversation_favorites.title")}
          >
            <Star className="size-4" />
            <span className={SIDEBAR_ICON_MODE_HIDDEN_CLASS}>
              {t("conversation_favorites.title")}
            </span>
          </Button>
        </DialogTrigger>
      </SidebarIconTooltip>
      <DialogContent className="max-h-[80svh] max-w-xl overflow-hidden p-0">
        <DialogTitle className="sr-only">{t("conversation_favorites.title")}</DialogTitle>

        <div className="flex items-center gap-2 border-b px-4 py-3 pr-12">
          <Star className="size-4 shrink-0 text-muted-foreground" />
          <input
            className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t("conversation_favorites.filter_placeholder")}
            autoFocus
          />
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("conversation_favorites.refresh")}
            title={t("conversation_favorites.refresh")}
            disabled={loading}
            onClick={load}
          >
            <RefreshCw className={cn("size-4", loading && "animate-spin")} />
          </Button>
        </div>

        <ScrollArea className="h-[420px]">
          <div className="p-2">
            {loading && !data ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_favorites.loading")}
              </div>
            ) : null}
            {error ? (
              <div className="px-2 py-6 text-center text-sm text-destructive">{error}</div>
            ) : null}
            {!error && data && items.length === 0 ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_favorites.empty")}
              </div>
            ) : null}
            {!error && items.length > 0 && visible.length === 0 ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_favorites.no_match")}
              </div>
            ) : null}
            {!error &&
              visible.map((item) => {
                const deleted = item.status === "conversation_deleted";
                return (
                  <div
                    key={item.id}
                    className="group/fav flex items-start gap-1 rounded-md transition hover:bg-muted"
                  >
                    <button
                      type="button"
                      disabled={deleted}
                      className="flex min-w-0 flex-1 items-start gap-3 px-2 py-2 text-left disabled:cursor-not-allowed disabled:opacity-60"
                      onClick={() => {
                        if (item.status === "node_missing") {
                          toast.info(t("conversation_favorites.status_node_missing"));
                          onSelect(item.conversationId);
                        } else {
                          onSelect(item.conversationId, item.nodeId);
                        }
                        setOpen(false);
                      }}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate text-sm font-medium">
                            {item.title || t("conversation_favorites.unnamed_conversation")}
                          </span>
                          <span className="shrink-0 text-xs text-muted-foreground">
                            {formatRelativeTime(item.createdAt, t)}
                          </span>
                        </div>
                        {item.preview ? (
                          <p className="mt-0.5 line-clamp-2 text-xs whitespace-pre-line text-muted-foreground">
                            {item.preview}
                          </p>
                        ) : null}
                        {item.status !== "ok" ? (
                          <span className="mt-1 inline-block rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
                            {t(`conversation_favorites.status_${item.status}`)}
                          </span>
                        ) : null}
                      </div>
                    </button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      className="mt-1.5 mr-1 shrink-0 opacity-60 group-hover/fav:opacity-100"
                      aria-label={t("conversation_favorites.remove")}
                      title={t("conversation_favorites.remove")}
                      disabled={removingId === item.id}
                      onClick={() => void remove(item)}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </div>
                );
              })}
            {data?.truncated ? (
              <div className="px-2 py-3 text-center text-xs text-muted-foreground">
                {t("conversation_favorites.truncated", { count: items.length })}
              </div>
            ) : null}
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  );
}
