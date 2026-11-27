import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Lock, MoreHorizontal } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "~/components/ui/dropdown-menu";
import { WorkspaceBookHistory } from "@overlay/components/workspace/workspace-book-history";
import { WorkspaceBookReview } from "@overlay/components/workspace/workspace-book-review";
import {
  bookApi,
  bookLocksQueryKey,
  bookTurnsQueryKey,
  dismissedNotices,
  turnCards,
  type BookTurnSummary,
  type LockNotice,
} from "@overlay/lib/book";

/** 比较页能直接“放回”的提醒（只提醒、没自动放回的）。 */
const COMPARE_KINDS = new Set(["changed", "not_restored"]);

/**
 * 对话内卡片：这条回复改了锁定的章节（放回了或没放回），以及这一轮改了哪些文件（逐处审阅）。两者合成一张卡：
 * 锁提醒在上，审阅行在下。按提醒和审阅记录渲染，不改消息内容，也不进分享和导出。“保留这次修改”只在 App 里。
 */
export function ConversationLockCard({
  workspaceId,
  conversationId,
  messageId,
  nodes,
  generating = false,
}: {
  workspaceId?: string | null;
  conversationId: string;
  messageId: string;
  /** 当前对话的节点（判断最新一条待审回复、重新生成的兄弟回复） */
  nodes: ReadonlyArray<{ messages: Array<{ id: string }>; selectIndex: number }>;
  generating?: boolean;
}) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const [history, setHistory] = React.useState<{ path: string; noticeId?: string } | null>(null);
  const [reviewing, setReviewing] = React.useState<BookTurnSummary | null>(null);
  // 同一个对话的所有回复共用一份（同一个 queryKey）
  const locks = useQuery({
    queryKey: bookLocksQueryKey(workspaceId ?? ""),
    queryFn: () => bookApi.locks(workspaceId ?? ""),
    enabled: Boolean(workspaceId),
    retry: false,
  });
  const turns = useQuery({
    queryKey: bookTurnsQueryKey(workspaceId ?? "", conversationId),
    queryFn: () => bookApi.turns(workspaceId ?? "", conversationId),
    enabled: Boolean(workspaceId),
    retry: false,
  });
  // 生成结束后收尾是异步的：马上取一次，稍后再取一次
  const wasGenerating = React.useRef(generating);
  React.useEffect(() => {
    if (wasGenerating.current && !generating && workspaceId) {
      const refresh = () => {
        void queryClient.invalidateQueries({ queryKey: bookTurnsQueryKey(workspaceId, conversationId) });
        void queryClient.invalidateQueries({ queryKey: bookLocksQueryKey(workspaceId) });
      };
      refresh();
      const timer = window.setTimeout(refresh, 2000);
      wasGenerating.current = generating;
      return () => window.clearTimeout(timer);
    }
    wasGenerating.current = generating;
  }, [generating, workspaceId, conversationId, queryClient]);
  const cards = React.useMemo(
    () => turnCards(turns.data?.turns ?? [], turns.data?.muted ?? false, nodes),
    [turns.data, nodes],
  );
  const dismissed = dismissedNotices.read();
  const notices = (locks.data?.notices ?? []).filter(
    (notice) => notice.conversationId === conversationId && notice.messageId === messageId && !dismissed.has(notice.id),
  );
  const card = cards.get(messageId);
  if (!workspaceId || (notices.length === 0 && !card)) return null;
  const refreshTurns = () => void queryClient.invalidateQueries({ queryKey: bookTurnsQueryKey(workspaceId, conversationId) });
  const view = (notice: LockNotice) => setHistory({ path: notice.path, noticeId: COMPARE_KINDS.has(notice.kind) ? notice.id : undefined });
  const counts = (summary: BookTurnSummary) =>
    summary.deleted > 0 ? ` · ${t("book.turn.deleted_count", { count: summary.deleted })}` : "";
  return (
    <>
      {notices.length > 0 || card?.full ? (
        <div className="mt-1 space-y-1 rounded-xl border bg-muted/40 px-3 py-2 text-xs" data-testid="conversation-lock-card">
          {notices.map((notice) => (
            <div key={notice.id} className="flex flex-wrap items-center gap-2">
              <Lock className="size-3.5 text-primary" />
              <span className="min-w-0 flex-1">
                {t(`book.lock.notice_${notice.kind}`, { name: notice.path.split("/").pop() ?? notice.path })}
              </span>
              {notice.kind !== "deleted" ? (
                <Button size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={() => view(notice)}>
                  {t("book.lock.view")}
                </Button>
              ) : null}
            </div>
          ))}
          {notices.length > 0 ? <p className="text-muted-foreground">{t("book.lock.card_hint")}</p> : null}
          {card?.full ? (
            <div data-testid="book-turn-row">
              <div className="flex items-center gap-2">
                <span className="min-w-0 flex-1 text-sm">
                  {t("book.turn.card", { count: card.summary.files })}
                  {counts(card.summary)}
                </span>
                <Button size="sm" variant="ghost" className="h-7 px-2" onClick={() => setReviewing(card.summary)}>
                  {t("book.turn.review")}
                </Button>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button size="icon" variant="ghost" className="size-7" aria-label={t("book.turn.more")}>
                      <MoreHorizontal className="size-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem onSelect={() => void bookApi.muteTurns(workspaceId, conversationId, true).then(refreshTurns)}>
                      {t("book.turn.mute")}
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
              {card.summary.unattributed ? <p className="text-muted-foreground">{t(`book.turn.unattributed_${card.summary.unattributed}`)}</p> : null}
            </div>
          ) : null}
        </div>
      ) : null}
      {card && !card.full ? (
        <button
          type="button"
          className="mt-1 block px-3 py-1 text-left text-xs text-muted-foreground hover:underline"
          onClick={() => setReviewing(card.summary)}
          data-testid="book-turn-line"
        >
          {card.summary.status === "REVIEWED"
            ? t("book.turn.reviewed_summary", { count: card.summary.reverted })
            : `${t("book.turn.review_small")}${counts(card.summary)}`}
        </button>
      ) : null}
      {card?.sibling ? (
        <button
          type="button"
          className="mt-1 block px-3 py-1 text-left text-xs text-primary hover:underline"
          onClick={() => setReviewing(card.sibling)}
        >
          {t("book.turn.sibling")}
        </button>
      ) : null}
      {history ? (
        <WorkspaceBookHistory
          workspaceId={workspaceId}
          path={history.path}
          noticeId={history.noticeId}
          onClose={() => setHistory(null)}
          onChanged={() => void queryClient.invalidateQueries({ queryKey: bookLocksQueryKey(workspaceId) })}
        />
      ) : null}
      {reviewing ? (
        <WorkspaceBookReview
          workspaceId={workspaceId}
          conversationId={reviewing.conversationId}
          messageId={reviewing.messageId}
          onClose={() => {
            setReviewing(null);
            refreshTurns();
          }}
          onChanged={refreshTurns}
        />
      ) : null}
    </>
  );
}
