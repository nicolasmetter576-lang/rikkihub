import * as React from "react";
import { ChevronDown, ChevronUp, Eraser, History, Trash2 } from "lucide-react";
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
import type { ContextCheckpointRecordDto } from "~/types";
import {
  clearConversationContext,
  getContextRecords,
  revokeContextRecord,
} from "@overlay/lib/compression";

export interface ContextRecordsDialogProps {
  conversationId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  refreshKey?: number | string;
  isBusy?: boolean;
  onJumpToMessage?: (nodeId: string, messageId: string) => void;
}

export function ContextRecordsDialog({
  conversationId,
  open,
  onOpenChange,
  refreshKey,
  isBusy = false,
  onJumpToMessage,
}: ContextRecordsDialogProps) {
  const { t } = useTranslation("page");
  const [records, setRecords] = React.useState<ContextCheckpointRecordDto[]>([]);
  const [canClear, setCanClear] = React.useState(false);
  const [loading, setLoading] = React.useState(false);
  const [acting, setActing] = React.useState(false);
  const [expandedPreviews, setExpandedPreviews] = React.useState<Record<string, boolean>>({});
  const [pendingRevoke, setPendingRevoke] = React.useState<ContextCheckpointRecordDto | null>(null);
  const [clearConfirmOpen, setClearConfirmOpen] = React.useState(false);

  const load = React.useCallback(async () => {
    setLoading(true);
    try {
      const response = await getContextRecords(conversationId);
      setRecords(response.records);
      setCanClear(response.canClear);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("conversations.context_records.load_failed"));
    } finally {
      setLoading(false);
    }
  }, [conversationId, t]);

  React.useEffect(() => {
    if (!open) return;
    setPendingRevoke(null);
    setClearConfirmOpen(false);
    void load();
  }, [open, refreshKey, load]);

  const handleRevoke = async (nodeId: string, cascade: boolean) => {
    setActing(true);
    try {
      await revokeContextRecord(conversationId, nodeId, cascade);
      setPendingRevoke(null);
      toast.success(t("conversations.context_records.revoked"));
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("conversations.context_records.action_failed"));
    } finally {
      setActing(false);
    }
  };

  const handleClear = async () => {
    setActing(true);
    try {
      await clearConversationContext(conversationId);
      setClearConfirmOpen(false);
      toast.success(t("conversations.context_records.cleared"));
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("conversations.context_records.action_failed"));
    } finally {
      setActing(false);
    }
  };

  const ordered = React.useMemo(() => [...records].reverse(), [records]);

  const kindLabel = (record: ContextCheckpointRecordDto): string => {
    const kind = record.meta?.kind;
    if (kind === "clear" || record.isClear) return t("conversations.context_records.kind_clear");
    if (kind === "auto") return t("conversations.context_records.kind_auto");
    if (kind === "overflow_retry") return t("conversations.context_records.kind_overflow");
    if (kind === "manual") return t("conversations.context_records.kind_manual");
    return t("conversations.context_records.kind_legacy");
  };

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="flex max-h-[82vh] max-w-xl flex-col gap-3" data-testid="context-records-dialog">
          <DialogHeader>
            <div className="flex items-center justify-between gap-2 pr-6">
              <DialogTitle className="flex items-center gap-2">
                <History className="size-4" />
                {t("conversations.context_records.title")}
              </DialogTitle>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={!canClear || isBusy || acting}
                onClick={() => setClearConfirmOpen(true)}
                data-testid="context-records-clear-button"
              >
                <Eraser className="mr-1.5 size-3.5" />
                {t("conversations.context_records.clear_context")}
              </Button>
            </div>
            <DialogDescription>
              {t("conversations.context_records.description")}
            </DialogDescription>
          </DialogHeader>

          <div className="min-h-0 flex-1 space-y-2.5 overflow-y-auto pr-1">
            {loading && records.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">
                {t("conversations.context_records.loading")}
              </p>
            ) : ordered.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground" data-testid="context-records-empty">
                {t("conversations.context_records.empty")}
              </p>
            ) : (
              ordered.map((record) => {
                const isExpanded = Boolean(expandedPreviews[record.nodeId]);
                const metaParts: string[] = [];
                if (record.meta?.templateId) metaParts.push(record.meta.templateId);
                if (record.meta?.requestCount) metaParts.push(`${record.meta.requestCount}x`);
                if (record.meta?.chunked) metaParts.push(t("conversations.compress.result_chunked"));
                if (record.meta?.carriedCount) {
                  metaParts.push(t("conversations.compress.result_carried", { count: record.meta.carriedCount }));
                }
                if (record.meta?.usage?.totalTokens) {
                  metaParts.push(`${record.meta.usage.totalTokens.toLocaleString()} tok`);
                }

                return (
                  <div
                    key={record.nodeId}
                    className="space-y-2 rounded-lg border bg-muted/30 p-3 text-sm"
                    data-testid={`context-record-card-${record.nodeId}`}
                  >
                    <div className="flex flex-wrap items-center gap-1.5 text-xs">
                      <span
                        className={
                          record.isActive
                            ? "rounded bg-primary/15 px-1.5 py-0.5 font-medium text-primary"
                            : "rounded bg-muted px-1.5 py-0.5 text-muted-foreground"
                        }
                      >
                        {t(
                          record.isActive
                            ? "conversations.context_records.active"
                            : "conversations.context_records.superseded",
                        )}
                      </span>
                      <span className="rounded bg-secondary px-1.5 py-0.5 font-medium text-secondary-foreground">
                        {kindLabel(record)}
                      </span>
                      {record.meta?.fromOriginal ? (
                        <span className="rounded bg-accent px-1.5 py-0.5 text-accent-foreground">
                          {t("conversations.context_records.from_original")}
                        </span>
                      ) : null}
                      <span className="ml-auto text-muted-foreground">
                        {new Date(record.createdAt).toLocaleString()}
                      </span>
                    </div>

                    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                      {record.coveredMessageCount > 0 ? (
                        <span>
                          {t("conversations.context_records.covered_count", {
                            count: record.coveredMessageCount,
                          })}
                        </span>
                      ) : null}
                      {metaParts.length > 0 ? <span>{metaParts.join(" · ")}</span> : null}
                    </div>

                    <p className="line-clamp-3 text-xs text-foreground/90 whitespace-pre-wrap">
                      {record.isClear
                        ? t("conversations.context_records.cleared_hint")
                        : record.summaryText}
                    </p>

                    {record.coveredPreviews.length > 0 ? (
                      <div className="space-y-1">
                        <button
                          type="button"
                          className="flex items-center gap-1 text-xs font-medium text-primary hover:underline"
                          onClick={() =>
                            setExpandedPreviews((prev) => ({
                              ...prev,
                              [record.nodeId]: !isExpanded,
                            }))
                          }
                        >
                          {isExpanded ? <ChevronUp className="size-3.5" /> : <ChevronDown className="size-3.5" />}
                          {isExpanded
                            ? t("conversations.context_records.hide_previews")
                            : t("conversations.context_records.show_previews", {
                                count: record.coveredPreviews.length,
                              })}
                        </button>
                        {isExpanded ? (
                          <div className="space-y-1 border-t pt-1.5 text-xs text-muted-foreground">
                            {record.coveredPreviews.map((preview) => (
                              <div key={preview.nodeId} className="flex gap-1.5 truncate">
                                <span className="shrink-0 font-semibold uppercase text-primary">
                                  {preview.role.slice(0, 1)}
                                </span>
                                <span className="truncate">{preview.previewText}</span>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    ) : null}

                    <div className="flex justify-end gap-1.5 pt-1">
                      {onJumpToMessage ? (
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            onOpenChange(false);
                            onJumpToMessage(record.nodeId, record.messageId);
                          }}
                          data-testid={`context-record-jump-${record.nodeId}`}
                        >
                          {t("conversations.context_records.jump")}
                        </Button>
                      ) : null}
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        className="text-destructive hover:text-destructive"
                        disabled={isBusy || acting}
                        onClick={() => {
                          if (record.laterCheckpointNodeIds.length > 0) {
                            setPendingRevoke(record);
                          } else {
                            void handleRevoke(record.nodeId, false);
                          }
                        }}
                        data-testid={`context-record-revoke-${record.nodeId}`}
                      >
                        <Trash2 className="mr-1 size-3.5" />
                        {t("conversations.context_records.undo")}
                      </Button>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={pendingRevoke !== null} onOpenChange={(next) => !next && setPendingRevoke(null)}>
        <DialogContent data-testid="context-record-cascade-dialog">
          <DialogHeader>
            <DialogTitle>{t("conversations.context_records.revoke_title")}</DialogTitle>
            <DialogDescription>
              {t("conversations.context_records.revoke_later_message", {
                count: pendingRevoke?.laterCheckpointNodeIds.length ?? 0,
              })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="flex-wrap gap-2">
            <Button type="button" variant="outline" onClick={() => setPendingRevoke(null)}>
              {t("conversations.context_records.cancel")}
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={acting}
              onClick={() => pendingRevoke && void handleRevoke(pendingRevoke.nodeId, false)}
              data-testid="context-record-revoke-single"
            >
              {t("conversations.context_records.revoke_single")}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={acting}
              onClick={() => pendingRevoke && void handleRevoke(pendingRevoke.nodeId, true)}
              data-testid="context-record-revoke-cascade"
            >
              {t("conversations.context_records.revoke_cascade")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

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
              disabled={acting}
              onClick={() => void handleClear()}
              data-testid="context-clear-confirm-button"
            >
              {t("conversations.context_records.clear_context")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
