import * as React from "react";

import { AlertCircle, AlertTriangle, Gauge, Loader2, ScrollText, X } from "lucide-react";
import { useTranslation } from "react-i18next";

import { compressErrorKey } from "~/components/compress-dialog";
import { Button } from "~/components/ui/button";
import type { ConversationSizeInfo } from "~/lib/conversation-size";
import { cn } from "~/lib/utils";
import type { CompressStateDto, ConversationErrorDto } from "~/types";

interface ConversationNoticesProps {
  /** Latest compression state from the conversation SSE. */
  compressState: CompressStateDto | null;
  /** A failure that happened while this page was open; cleared by the user. */
  compressFailure: CompressStateDto | null;
  /** Stateful generation errors replayed over SSE until dismissed or superseded. */
  generationErrors?: ConversationErrorDto[];
  sizeInfo: ConversationSizeInfo;
  sizeWarningDismissed: boolean;
  isCliSession?: boolean;
  cancelling: boolean;
  onCancelCompression: () => void;
  onOpenCompress: () => void;
  onDismissFailure: () => void;
  onDismissGenerationError?: (errorId: string) => void;
  onCompressAndRetry?: () => void;
  onDismissSizeWarning: () => void;
  onNewConversation: () => void;
  /** "Notify only" hint from the server; null when there is nothing to suggest. */
  compressSuggestion?: { percent: number; exact: boolean } | null;
  onDismissSuggestion?: () => void;
  onOpenReview?: () => void;
}

function Notice({
  tone,
  icon,
  children,
  actions,
}: {
  tone: "info" | "warning" | "error";
  icon: React.ReactNode;
  children: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <div className="mx-auto mb-2 w-full max-w-3xl px-4">
      <div
        role={tone === "error" ? "alert" : "status"}
        className={cn(
          "flex w-full flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-sm",
          tone === "error"
            ? "border-destructive/40 bg-destructive/10 text-foreground"
            : tone === "warning"
              ? "border-amber-500/40 bg-amber-500/10 text-foreground"
              : "bg-muted text-foreground",
        )}
      >
        <span className={cn("shrink-0", tone === "error" ? "text-destructive" : "text-muted-foreground")}>
          {icon}
        </span>
        <div className="min-w-0 flex-1">{children}</div>
        {actions ? <div className="flex shrink-0 items-center gap-1">{actions}</div> : null}
      </div>
    </div>
  );
}

export function ConversationNotices({
  compressState,
  compressFailure,
  generationErrors = [],
  sizeInfo,
  sizeWarningDismissed,
  isCliSession = false,
  cancelling,
  onCancelCompression,
  onOpenCompress,
  onDismissFailure,
  onDismissGenerationError,
  onCompressAndRetry,
  onDismissSizeWarning,
  onNewConversation,
  compressSuggestion = null,
  onDismissSuggestion,
  onOpenReview,
}: ConversationNoticesProps) {
  const { t } = useTranslation("page");
  const running = compressState?.status === "running";
  const awaitingReview = compressState?.status === "awaiting_review";

  return (
    <>
      {generationErrors.map((err) => {
        const isWarning = err.affectsReply === false;
        return (
          <Notice
            key={err.id}
            tone={isWarning ? "warning" : "error"}
            icon={isWarning ? <AlertTriangle className="size-4" /> : <AlertCircle className="size-4" />}
            actions={
              <>
                {err.solution === "compress_and_retry" && !running && onCompressAndRetry ? (
                  <Button type="button" size="sm" variant="ghost" onClick={onCompressAndRetry}>
                    {t("conversations.compress.compress_and_retry")}
                  </Button>
                ) : null}
                {onDismissGenerationError ? (
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    aria-label={t("conversations.compress.dismiss")}
                    onClick={() => onDismissGenerationError(err.id)}
                  >
                    <X className="size-4" />
                  </Button>
                ) : null}
              </>
            }
          >
            {err.title ? <div className="font-medium">{err.title}</div> : null}
            <div className="max-h-24 overflow-y-auto whitespace-pre-wrap break-words text-xs text-muted-foreground">
              {err.message}
            </div>
          </Notice>
        );
      })}
      {running ? (
        <Notice
          tone="info"
          icon={<Loader2 className="size-4 animate-spin" />}
          actions={
            <Button
              type="button"
              size="sm"
              variant="ghost"
              disabled={cancelling}
              onClick={onCancelCompression}
            >
              {t("conversations.compress.cancel_running")}
            </Button>
          }
        >
          {compressState?.chunked && (compressState.totalChunks ?? 0) > 0
            ? t("conversations.compress.running_chunked", {
                current: Math.min((compressState.completedChunks ?? 0) + 1, compressState.totalChunks!),
                total: compressState.totalChunks!,
              })
            : compressState?.origin === "app"
              ? t("conversations.compress.running_from_app")
              : compressState?.origin === "auto"
                ? t("conversations.compress.running_auto")
                : t("conversations.compress.running")}
          <span className="block text-xs text-muted-foreground">
            {t("conversations.compress.running_hint")}
          </span>
        </Notice>
      ) : null}

      {awaitingReview ? (
        <Notice
          tone="info"
          icon={<ScrollText className="size-4" />}
          actions={
            <>
              <Button type="button" size="sm" variant="ghost" onClick={onOpenReview} data-testid="compress-review-open">
                {t("conversations.compress.review_open")}
              </Button>
              <Button type="button" size="sm" variant="ghost" disabled={cancelling} onClick={onCancelCompression}>
                {t("conversations.compress.review_discard")}
              </Button>
            </>
          }
        >
          {t("conversations.compress.review_ready")}
        </Notice>
      ) : null}

      {!running && !awaitingReview && compressSuggestion && !isCliSession ? (
        <Notice
          tone="warning"
          icon={<Gauge className="size-4" />}
          actions={
            <>
              <Button type="button" size="sm" variant="ghost" onClick={onOpenCompress} data-testid="compress-suggestion-open">
                {t("conversations.compress.suggestion_action")}
              </Button>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={t("conversations.compress.dismiss")}
                onClick={onDismissSuggestion}
              >
                <X className="size-4" />
              </Button>
            </>
          }
        >
          {(compressSuggestion.exact ? "" : "≈ ") +
            t("conversations.compress.suggestion", { percent: compressSuggestion.percent })}
        </Notice>
      ) : null}

      {!running && compressFailure ? (
        <Notice
          tone="warning"
          icon={<AlertTriangle className="size-4" />}
          actions={
            <>
              <Button type="button" size="sm" variant="ghost" onClick={onOpenCompress}>
                {t("conversations.compress.retry")}
              </Button>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={t("conversations.compress.dismiss")}
                onClick={onDismissFailure}
              >
                <X className="size-4" />
              </Button>
            </>
          }
        >
          {t("conversations.compress.failed_title")}
          <span className="block text-xs text-muted-foreground">
            {t(compressErrorKey(compressFailure.errorCode))}
            {compressFailure.errorCode === "COMPRESS_FAILED" && compressFailure.errorMessage
              ? ` — ${compressFailure.errorMessage}`
              : ""}
          </span>
        </Notice>
      ) : null}

      {!running && !isCliSession && sizeInfo.showWarning && !sizeWarningDismissed ? (
        <Notice
          tone="warning"
          icon={<AlertTriangle className="size-4" />}
          actions={
            <>
              <Button type="button" size="sm" variant="ghost" onClick={onOpenCompress}>
                {t("conversations.compress.menu")}
              </Button>
              <Button type="button" size="sm" variant="ghost" onClick={onNewConversation}>
                {t("conversations.size_warning.new_conversation")}
              </Button>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={t("conversations.compress.dismiss")}
                onClick={onDismissSizeWarning}
              >
                <X className="size-4" />
              </Button>
            </>
          }
        >
          {t("conversations.size_warning.title")}
          <span className="block text-xs text-muted-foreground">
            {t("conversations.size_warning.detail", {
              nodes: sizeInfo.nodeCount,
              tokens: sizeInfo.lastAssistantInputTokens.toLocaleString(),
            })}
          </span>
        </Notice>
      ) : null}
    </>
  );
}
