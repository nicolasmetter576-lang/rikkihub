import * as React from "react";

import { AlertTriangle, Loader2, X } from "lucide-react";
import { useTranslation } from "react-i18next";

import { compressErrorKey } from "~/components/compress-dialog";
import { Button } from "~/components/ui/button";
import type { ConversationSizeInfo } from "~/lib/conversation-size";
import { cn } from "~/lib/utils";
import type { CompressStateDto } from "~/types";

interface ConversationNoticesProps {
  /** Latest compression state from the conversation SSE. */
  compressState: CompressStateDto | null;
  /** A failure that happened while this page was open; cleared by the user. */
  compressFailure: CompressStateDto | null;
  sizeInfo: ConversationSizeInfo;
  sizeWarningDismissed: boolean;
  cancelling: boolean;
  onCancelCompression: () => void;
  onOpenCompress: () => void;
  onDismissFailure: () => void;
  onDismissSizeWarning: () => void;
  onNewConversation: () => void;
}

function Notice({
  tone,
  icon,
  children,
  actions,
}: {
  tone: "info" | "warning";
  icon: React.ReactNode;
  children: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <div className="mx-auto mb-2 w-full max-w-3xl px-4">
      <div
        role="status"
        className={cn(
          "flex w-full flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-sm",
          tone === "warning"
            ? "border-amber-500/40 bg-amber-500/10 text-foreground"
            : "bg-muted text-foreground",
        )}
      >
        <span className="shrink-0 text-muted-foreground">{icon}</span>
        <div className="min-w-0 flex-1">{children}</div>
        {actions ? <div className="flex shrink-0 items-center gap-1">{actions}</div> : null}
      </div>
    </div>
  );
}

export function ConversationNotices({
  compressState,
  compressFailure,
  sizeInfo,
  sizeWarningDismissed,
  cancelling,
  onCancelCompression,
  onOpenCompress,
  onDismissFailure,
  onDismissSizeWarning,
  onNewConversation,
}: ConversationNoticesProps) {
  const { t } = useTranslation("page");
  const running = compressState?.status === "running";

  return (
    <>
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
          {compressState?.origin === "app"
            ? t("conversations.compress.running_from_app")
            : t("conversations.compress.running")}
          <span className="block text-xs text-muted-foreground">
            {t("conversations.compress.running_hint")}
          </span>
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

      {!running && sizeInfo.showWarning && !sizeWarningDismissed ? (
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
