import * as React from "react";

import { Minus, Plus } from "lucide-react";
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
import { Textarea } from "~/components/ui/textarea";
import api, { ApiError } from "~/services/api";
import type { CompressStateResponse } from "~/types";

// Same bounds as the app dialog and the server (CompressRequest).
export const COMPRESS_MAX_ADDITIONAL_PROMPT = 2_000;
export const COMPRESS_MAX_KEEP_RECENT = 100_000;

/** Stepper arithmetic for "keep recent messages": never below 0, never above the server's bound. */
export function stepKeepRecent(value: number, delta: number): number {
  return Math.min(COMPRESS_MAX_KEEP_RECENT, Math.max(0, value + delta));
}

/** Translation key for a compression error code (start refusal or final failure). */
export function compressErrorKey(code: string | null | undefined): string {
  switch (code) {
    case "CONVERSATION_BUSY":
      return "conversations.compress.error_busy";
    case "ALREADY_COMPRESSING":
      return "conversations.compress.error_already";
    case "NOTHING_TO_COMPRESS":
      return "conversations.compress.error_nothing";
    case "NOT_ENOUGH_MESSAGES":
      return "conversations.compress.error_not_enough";
    case "PENDING_TOOLS":
      return "conversations.compress.error_pending_tools";
    case "TOOL_CALL_RESPONSE":
      return "conversations.compress.error_tool_call";
    case "CONVERSATION_CHANGED":
      return "conversations.compress.error_changed";
    default:
      return "conversations.compress.error_failed";
  }
}

interface CompressDialogProps {
  conversationId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Context used by the latest reply; null when unknown or already compressed after it. */
  contextTokens?: number | null;
  /** Context window of the conversation's chat model. */
  contextLength?: number | null;
}

/**
 * Starts a background compression; progress and the result arrive over the conversation SSE.
 * The server inserts a summary checkpoint at the end; nothing is deleted.
 */
export function CompressDialog({
  conversationId,
  open,
  onOpenChange,
  contextTokens,
  contextLength,
}: CompressDialogProps) {
  const { t } = useTranslation("page");
  const [additionalPrompt, setAdditionalPrompt] = React.useState("");
  // 0 compresses everything, which keeps the request identical to the chat's and reuses its cache.
  const [keepRecent, setKeepRecent] = React.useState(0);
  const [starting, setStarting] = React.useState(false);

  const start = async () => {
    setStarting(true);
    try {
      await api.post<CompressStateResponse>(`conversations/${conversationId}/compress`, {
        additionalPrompt,
        keepRecent,
      });
      onOpenChange(false);
    } catch (error) {
      const code = error instanceof ApiError ? error.errorCode : undefined;
      toast.error(t(compressErrorKey(code)), {
        description: error instanceof Error && !code ? error.message : undefined,
      });
    } finally {
      setStarting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("conversations.compress.title")}</DialogTitle>
          <DialogDescription>{t("conversations.compress.description")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {contextTokens != null && contextLength ? (
            <ContextUsage tokens={contextTokens} length={contextLength} />
          ) : null}
          <label className="block space-y-1.5">
            <span className="text-sm font-medium">
              {t("conversations.compress.additional_prompt")}
            </span>
            <Textarea
              value={additionalPrompt}
              maxLength={COMPRESS_MAX_ADDITIONAL_PROMPT}
              onChange={(event) => setAdditionalPrompt(event.target.value)}
              placeholder={t("conversations.compress.additional_prompt_hint")}
              rows={3}
            />
          </label>

          <div className="space-y-1.5">
            <div className="flex items-center gap-2">
              <span className="flex-1 text-sm font-medium">
                {t("conversations.compress.keep_recent")}
              </span>
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label={t("conversations.compress.keep_recent_decrease")}
                disabled={keepRecent <= 0}
                onClick={() => setKeepRecent((value) => stepKeepRecent(value, -1))}
              >
                <Minus className="size-4" />
              </Button>
              <span className="min-w-10 text-center text-sm font-medium tabular-nums" data-keep-recent>
                {keepRecent}
              </span>
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label={t("conversations.compress.keep_recent_increase")}
                disabled={keepRecent >= COMPRESS_MAX_KEEP_RECENT}
                onClick={() => setKeepRecent((value) => stepKeepRecent(value, 1))}
              >
                <Plus className="size-4" />
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              {t("conversations.compress.keep_recent_desc")}
            </p>
          </div>

          <p className="text-xs text-muted-foreground">{t("conversations.compress.checkpoint_info")}</p>
          <p className="text-xs text-muted-foreground">
            {t("conversations.compress.background_hint")}
          </p>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t("conversations.compress.cancel")}
          </Button>
          <Button type="button" disabled={starting} onClick={() => void start()}>
            {starting ? t("conversations.compress.starting") : t("conversations.compress.start")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Context usage bar, turning warning colours near the window like the app's ring. */
function ContextUsage({ tokens, length }: { tokens: number; length: number }) {
  const { t } = useTranslation("page");
  const fraction = Math.min(1, Math.max(0, tokens / length));
  const tone = fraction >= 0.9 ? "bg-destructive" : fraction >= 0.7 ? "bg-amber-500" : "bg-primary";
  return (
    <div className="space-y-1.5" data-context-usage>
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>{t("conversations.compress.context_usage")}</span>
        <span>
          {tokens.toLocaleString()} / {length.toLocaleString()} · {Math.round(fraction * 100)}%
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div className={`h-full ${tone}`} style={{ width: `${fraction * 100}%` }} />
      </div>
    </div>
  );
}
