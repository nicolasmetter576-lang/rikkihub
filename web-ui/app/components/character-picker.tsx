import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Drama, Search, Star } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { toast } from "sonner";

import {
  SIDEBAR_ICON_MODE_BUTTON_CLASS,
  SIDEBAR_ICON_MODE_HIDDEN_CLASS,
  SidebarIconTooltip,
} from "~/components/sidebar-icon-mode";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogTitle, DialogTrigger } from "~/components/ui/dialog";
import { ScrollArea } from "~/components/ui/scroll-area";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import { CharacterAvatar } from "@overlay/components/tavern/character-avatar";
import {
  type RowOrder,
  characterQueryKey,
  charactersApi,
  charactersQueryKey,
  inOrder,
  keepOrder,
  pageOf,
  quickCharacters,
  sortCharacters,
} from "@overlay/lib/characters";
import type { CharacterSummary } from "@overlay/types/character";

/** The full list is the same query the chat uses to show a character's avatar, so it is fetched once. */
function useCharacterList(query: string, enabled = true) {
  return useQuery({
    queryKey: [...charactersQueryKey, "list", query],
    queryFn: () => charactersApi.list(query),
    enabled,
    staleTime: 30_000,
  });
}

/**
 * Starts a chat with a character: the server makes the conversation (greeting already in it) with the
 * current assistant, [onStarted] then opens it. One start at a time.
 */
function useStartCharacterChat(onStarted: (conversationId: string) => void) {
  const { t } = useTranslation("tavern");
  const [startingId, setStartingId] = React.useState<string | null>(null);
  const start = React.useCallback(
    async (character: Pick<CharacterSummary, "id">) => {
      if (startingId) return;
      setStartingId(character.id);
      try {
        const chat = await charactersApi.startChat(character.id);
        onStarted(chat.conversationId);
      } catch (error) {
        toast.error(extractErrorMessage(error, t("characters.startFailed")));
      } finally {
        setStartingId(null);
      }
    },
    [onStarted, startingId, t],
  );
  return { start, startingId };
}

export interface CharacterPickerProps {
  /** Opens the conversation the server just made. */
  onStarted: (conversationId: string) => void;
  /** The assistant a new chat will use; shown under the search box when not empty. */
  assistantName: string;
  /** Optional override for the current assistant's default CLI agent id. */
  defaultCliAgentId?: string | null;
}

export function CharacterPickerCliNotice({ defaultCliAgentId }: { defaultCliAgentId?: string | null }) {
  const { t } = useTranslation("common");
  if (!defaultCliAgentId) return null;
  return (
    <p
      role="status"
      data-testid="character-picker-cli-notice"
      className="mx-4 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-1.5 text-xs text-foreground"
    >
      {t("common:character_picker.cli_http_notice")}
    </p>
  );
}

/** The sidebar button that opens the character picker. */
export function CharacterPickerButton({
  onStarted,
  assistantName,
  defaultCliAgentId,
}: CharacterPickerProps) {
  const { t } = useTranslation("tavern");
  const { currentAssistant } = useCurrentAssistant();
  const effectiveDefaultCliAgentId =
    defaultCliAgentId !== undefined ? defaultCliAgentId : (currentAssistant?.defaultCliAgentId ?? null);
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [pages, setPages] = React.useState(1);

  React.useEffect(() => {
    const timer = setTimeout(() => setDebounced(query.trim()), 250);
    return () => clearTimeout(timer);
  }, [query]);
  React.useEffect(() => {
    setPages(1);
  }, [debounced]);
  React.useEffect(() => {
    if (!open) {
      setQuery("");
      setDebounced("");
      setPages(1);
    }
  }, [open]);

  const list = useCharacterList(debounced, open);
  const sorted = React.useMemo(() => sortCharacters(list.data ?? []), [list.data]);
  // Starring changes the star, not the row order; the rows are re-ordered when the dialog opens, the search changes or
  // the set of characters changes.
  const rowOrder = React.useRef<RowOrder | null>(null);
  const order = keepOrder(sorted, `${open}\n${debounced}`, rowOrder.current);
  rowOrder.current = order;
  const ordered = React.useMemo(() => inOrder(order.ids, sorted), [order, sorted]);
  const shown = React.useMemo(() => pageOf(ordered, pages), [ordered, pages]);
  const remaining = ordered.length - shown.length;

  const handleStarted = React.useCallback(
    (conversationId: string) => {
      setOpen(false);
      onStarted(conversationId);
    },
    [onStarted],
  );
  const { start, startingId } = useStartCharacterChat(handleStarted);

  const favorite = useMutation({
    mutationFn: (character: CharacterSummary) => charactersApi.patch(character.id, { fav: !character.fav }),
    onSuccess: (detail) => {
      queryClient.setQueryData(characterQueryKey(detail.id), detail);
      void queryClient.invalidateQueries({ queryKey: charactersQueryKey });
    },
    onError: (error) => toast.error(extractErrorMessage(error, t("common.saveFailed"))),
  });

  // The next page is drawn when the end of the list scrolls into view. The dialog's content mounts a render after it
  // opens, so the end marker is kept in state rather than read from a ref.
  const [sentinel, setSentinel] = React.useState<HTMLDivElement | null>(null);
  React.useEffect(() => {
    if (!sentinel || remaining <= 0 || typeof IntersectionObserver === "undefined") return;
    // A new observer reports the current position at once, so a page that leaves the end in view loads the next one.
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) setPages((current) => current + 1);
    });
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [sentinel, remaining, shown.length]);

  const empty = !list.isLoading && ordered.length === 0;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <SidebarIconTooltip label={t("characters.title")} collapsedOnly>
        <DialogTrigger asChild>
          <Button
            variant="ghost"
            size="sm"
            className={cn("w-full justify-start", SIDEBAR_ICON_MODE_BUTTON_CLASS)}
            type="button"
            aria-label={t("characters.title")}
          >
            <Drama className="size-4" />
            <span className={SIDEBAR_ICON_MODE_HIDDEN_CLASS}>{t("characters.title")}</span>
          </Button>
        </DialogTrigger>
      </SidebarIconTooltip>
      <DialogContent className="max-h-[80svh] max-w-xl overflow-hidden p-0">
        <DialogTitle className="sr-only">{t("characters.title")}</DialogTitle>

        <div className="flex items-center gap-2 border-b px-4 py-3 pr-12">
          <Search className="size-4 shrink-0 text-muted-foreground" />
          <input
            className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t("characters.search")}
            aria-label={t("characters.search")}
            autoFocus
          />
          <Button asChild variant="ghost" size="sm">
            <Link to="/settings/characters" onClick={() => setOpen(false)}>
              {t("characters.pickerManage")}
            </Link>
          </Button>
        </div>
        {assistantName ? (
          <p className="px-4 text-xs text-muted-foreground">
            {t("characters.pickerAssistant", { name: assistantName })}
          </p>
        ) : null}
        <CharacterPickerCliNotice defaultCliAgentId={effectiveDefaultCliAgentId} />

        <ScrollArea className="h-[420px]">
          <div className="p-2" data-testid="character-picker-list">
            {empty ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {debounced ? t("characters.noMatch") : t("characters.empty")}
              </div>
            ) : null}
            {shown.map((character) => (
              <div
                key={character.id}
                className="flex items-center gap-1 rounded-md transition hover:bg-muted"
              >
                <button
                  type="button"
                  disabled={startingId !== null}
                  className="flex min-w-0 flex-1 items-center gap-3 px-2 py-2 text-left disabled:cursor-wait disabled:opacity-60"
                  aria-label={t("characters.pickerStart", { name: character.name })}
                  onClick={() => void start(character)}
                >
                  <CharacterAvatar name={character.name} avatarUrl={character.avatarUrl} />
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium">
                      {character.name || t("characters.fieldName")}
                    </span>
                    {character.tags.length > 0 ? (
                      <span className="block truncate text-xs text-muted-foreground">
                        {character.tags.slice(0, 4).join(" · ")}
                      </span>
                    ) : null}
                  </span>
                </button>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  className="mr-1 shrink-0"
                  aria-label={character.fav ? t("characters.unfavorite") : t("characters.favorite")}
                  aria-pressed={character.fav}
                  disabled={favorite.isPending}
                  onClick={() => favorite.mutate(character)}
                >
                  <Star
                    className={cn("size-4", character.fav ? "fill-primary text-primary" : "text-muted-foreground")}
                  />
                </Button>
              </div>
            ))}
            {remaining > 0 ? (
              <div ref={setSentinel} className="px-2 py-2 text-center">
                <Button type="button" variant="ghost" size="sm" onClick={() => setPages((current) => current + 1)}>
                  {t("characters.pickerMore", { count: remaining })}
                </Button>
              </div>
            ) : null}
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  );
}

/** A row of the favorite characters' avatars under the picker button: one click starts a chat. Hidden in the icon rail. */
export function FavoriteCharacterStrip({ onStarted }: Pick<CharacterPickerProps, "onStarted">) {
  const { t } = useTranslation("tavern");
  const list = useCharacterList("");
  const quick = React.useMemo(() => quickCharacters(list.data ?? []), [list.data]);
  const { start, startingId } = useStartCharacterChat(onStarted);

  if (quick.length === 0) return null;
  return (
    <div
      className={cn("flex flex-wrap gap-1 px-2 pt-1", SIDEBAR_ICON_MODE_HIDDEN_CLASS)}
      role="group"
      aria-label={t("characters.pickerQuick")}
      data-testid="favorite-characters"
    >
      {quick.map((character) => (
        <button
          key={character.id}
          type="button"
          disabled={startingId !== null}
          title={character.name}
          aria-label={t("characters.pickerStart", { name: character.name })}
          className="rounded-full transition hover:ring-2 hover:ring-ring disabled:cursor-wait disabled:opacity-60"
          onClick={() => void start(character)}
        >
          <CharacterAvatar name={character.name} avatarUrl={character.avatarUrl} />
        </button>
      ))}
    </div>
  );
}
