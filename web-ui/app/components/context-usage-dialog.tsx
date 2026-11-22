import * as React from "react";
import { BarChart3 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import type { ContextUsageBreakdownDto } from "~/types";
import { getContextUsageBreakdown } from "@overlay/lib/compression";

export interface ContextUsageDialogProps {
  conversationId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  draftText?: string;
  draftImageCount?: number;
  onOpenCompress?: () => void;
}

interface Segment {
  key: string;
  color: string;
  label: string;
  tokens: number;
}

export function ContextUsageDialog({
  conversationId,
  open,
  onOpenChange,
  draftText = "",
  draftImageCount = 0,
  onOpenCompress,
}: ContextUsageDialogProps) {
  const { t } = useTranslation("page");
  const [breakdown, setBreakdown] = React.useState<ContextUsageBreakdownDto | null>(null);
  const [loading, setLoading] = React.useState(false);

  React.useEffect(() => {
    if (!open) return;
    let alive = true;
    setLoading(true);
    getContextUsageBreakdown(conversationId, { draftText, draftImageCount })
      .then((res) => {
        if (alive) setBreakdown(res);
      })
      .catch((error) => {
        if (alive) {
          toast.error(error instanceof Error ? error.message : t("conversations.context_usage.load_failed"));
        }
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [open, conversationId, draftText, draftImageCount, t]);

  const segments: Segment[] = React.useMemo(() => {
    if (!breakdown) return [];
    return [
      {
        key: "system",
        color: "#6366f1",
        label: t("conversations.context_usage.system"),
        tokens: breakdown.systemPromptTokens,
      },
      {
        key: "memory",
        color: "#8b5cf6",
        label: t("conversations.context_usage.memory", { count: breakdown.memoryFileCount }),
        tokens: breakdown.memoryTokens,
      },
      {
        key: "lorebook",
        color: "#ec4899",
        label: t("conversations.context_usage.lorebook", { count: breakdown.activeLorebookCount }),
        tokens: breakdown.lorebookAndTavernTokens,
      },
      {
        key: "tools_local",
        color: "#00897b",
        label: t("conversations.context_usage.tools_local", { count: breakdown.toolsLocalCount }),
        tokens: breakdown.toolsLocalTokens,
      },
      {
        key: "tools_mcp",
        color: "#0288d1",
        label: t("conversations.context_usage.tools_mcp", { count: breakdown.toolsMcpCount }),
        tokens: breakdown.toolsMcpTokens,
      },
      {
        key: "tools_skills",
        color: "#7b1fa2",
        label: t("conversations.context_usage.tools_skills", { count: breakdown.toolsSkillsCount }),
        tokens: breakdown.toolsSkillsTokens,
      },
      {
        key: "tools_workspace",
        color: "#ef6c00",
        label: t("conversations.context_usage.tools_workspace", { count: breakdown.toolsWorkspaceCount }),
        tokens: breakdown.toolsWorkspaceTokens,
      },
      {
        key: "checkpoint",
        color: "#5c6bc0",
        label: t("conversations.context_usage.checkpoint"),
        tokens: breakdown.historyCheckpointTokens,
      },
      {
        key: "history",
        color: "#2e7d32",
        label: t("conversations.context_usage.history", { count: breakdown.historyMessageCount }),
        tokens: breakdown.historyMessagesTokens,
      },
      {
        key: "attachments",
        color: "#d81b60",
        label: t("conversations.context_usage.attachments", { count: breakdown.attachmentCount }),
        tokens: breakdown.attachmentsTokens,
      },
      {
        key: "draft",
        color: "#f9a825",
        label: t("conversations.context_usage.draft"),
        tokens: breakdown.draftTokens,
      },
    ];
  }, [breakdown, t]);

  const badgeText = React.useMemo(() => {
    if (!breakdown) return "";
    if (breakdown.totalExact) return t("conversations.context_usage.exact_badge");
    if (breakdown.reportedContextTokens != null && breakdown.draftTokens > 0) {
      return t("conversations.context_usage.exact_plus_draft", {
        exact: breakdown.reportedContextTokens.toLocaleString(),
        draft: breakdown.draftTokens.toLocaleString(),
      });
    }
    return t("conversations.context_usage.estimated_badge");
  }, [breakdown, t]);

  const totalEst = Math.max(breakdown?.estimatedTotalTokens ?? 0, 1);
  const windowLimit = breakdown?.contextLength && breakdown.contextLength > 0 ? breakdown.contextLength : null;
  const usageRatio = breakdown && windowLimit ? Math.min(breakdown.totalTokens / windowLimit, 1) : null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[82vh] max-w-lg flex-col gap-3" data-testid="context-usage-dialog">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <BarChart3 className="size-4" />
            {t("conversations.context_usage.title")}
          </DialogTitle>
          <DialogDescription>
            {breakdown
              ? t("conversations.context_usage.note", {
                  estimated: breakdown.estimatedTotalTokens.toLocaleString(),
                })
              : t("conversations.context_usage.loading")}
          </DialogDescription>
        </DialogHeader>

        {loading && !breakdown ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            {t("conversations.context_usage.loading")}
          </p>
        ) : breakdown ? (
          <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
            <div className="space-y-2 rounded-lg border bg-muted/30 p-3" data-testid="context-usage-summary-card">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-base font-semibold">
                  {breakdown.totalTokens.toLocaleString()}
                  {windowLimit ? ` / ${windowLimit.toLocaleString()}` : ""} tokens
                </span>
                <span
                  className={
                    breakdown.totalExact
                      ? "rounded bg-primary/15 px-2 py-0.5 text-xs font-medium text-primary"
                      : "rounded bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground"
                  }
                >
                  {badgeText}
                </span>
              </div>

              {usageRatio !== null ? (
                <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full rounded-full bg-primary transition-all"
                    style={{ width: `${Math.round(usageRatio * 100)}%` }}
                  />
                </div>
              ) : null}

              {segments.some((s) => s.tokens > 0) ? (
                <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-muted">
                  {segments
                    .filter((s) => s.tokens > 0)
                    .map((s) => (
                      <div
                        key={s.key}
                        style={{ width: `${(s.tokens / totalEst) * 100}%`, backgroundColor: s.color }}
                        title={`${s.label}: ${s.tokens.toLocaleString()}`}
                      />
                    ))}
                </div>
              ) : null}
            </div>

            <div className="space-y-2 text-sm">
              {segments.map((s, idx) => {
                if (s.key === "draft" && s.tokens === 0) return null;
                const pct = ((s.tokens / totalEst) * 100).toFixed(1);
                return (
                  <React.Fragment key={s.key}>
                    <div className="flex items-center justify-between gap-2">
                      <span className="flex items-center gap-2">
                        <span
                          className="inline-block size-2.5 shrink-0 rounded-full"
                          style={{ backgroundColor: s.color }}
                        />
                        <span>{s.label}</span>
                      </span>
                      <span className={s.tokens > 0 ? "font-mono text-xs" : "font-mono text-xs text-muted-foreground"}>
                        {s.tokens > 0 ? `${s.tokens.toLocaleString()} (${pct}%)` : "0"}
                      </span>
                    </div>
                    {idx === 2 || idx === 6 ? <div className="border-t" /> : null}
                  </React.Fragment>
                );
              })}
            </div>
          </div>
        ) : null}

        {onOpenCompress ? (
          <DialogFooter>
            <Button
              type="button"
              variant="secondary"
              onClick={() => {
                onOpenChange(false);
                onOpenCompress();
              }}
              data-testid="context-breakdown-open-compress"
            >
              {t("conversations.context_usage.open_compress")}
            </Button>
          </DialogFooter>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}
