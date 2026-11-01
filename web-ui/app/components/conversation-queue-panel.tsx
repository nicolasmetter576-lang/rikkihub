import * as React from "react";

import { ChevronDown, ChevronUp, ListTodo, Pencil, Play, X } from "lucide-react";
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
import { cn } from "~/lib/utils";
import { collectLocalAttachmentUrls } from "@overlay/lib/draft-attachments";
import { useAttachmentDraftLeases } from "@overlay/hooks/use-attachment-draft-leases";
import api from "~/services/api";
import type { MessageQueueDto, QueuedMessageDto, UIMessagePart } from "~/types";

function getQueuedMessagePreview(
  message: QueuedMessageDto,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const parts = message.parts
    .map((part): string | null => {
      switch (part.type) {
        case "text":
          return part.text.trim().length > 0 ? part.text : null;
        case "image":
          return t("queue_panel.attachment_image");
        case "video":
          return t("queue_panel.attachment_video");
        case "audio":
          return t("queue_panel.attachment_audio");
        case "document":
          return part.fileName.trim().length > 0
            ? part.fileName
            : t("queue_panel.attachment_document");
        default:
          return null;
      }
    })
    .filter((value): value is string => Boolean(value));

  return parts.join(" ");
}

function buildEditedQueueParts(
  originalParts: UIMessagePart[],
  editedText: string,
): UIMessagePart[] {
  const trimmed = editedText.trim();
  const hasTextPart = originalParts.some((part) => part.type === "text");
  const nonTextParts = originalParts.filter((part) => part.type !== "text");

  if (!hasTextPart) {
    return trimmed.length > 0 ? [{ type: "text", text: trimmed }, ...nonTextParts] : nonTextParts;
  }

  // Keep the text slot in place: replace text parts with a single edited text
  // part at the position of the last text part (mirrors ChatInputState.getContents).
  const merged: UIMessagePart[] = [];
  let textInserted = false;
  for (let index = 0; index < originalParts.length; index += 1) {
    const part = originalParts[index];
    if (part.type !== "text") {
      merged.push(part);
      continue;
    }

    const isLastTextPart = !originalParts.slice(index + 1).some((later) => later.type === "text");
    if (isLastTextPart) {
      textInserted = true;
      if (trimmed.length > 0) {
        merged.push({ type: "text", text: trimmed });
      }
    }
  }
  if (!textInserted && trimmed.length > 0) {
    merged.unshift({ type: "text", text: trimmed });
  }
  return merged;
}

interface QueueEditDialogProps {
  conversationId: string;
  message: QueuedMessageDto;
  onFinished: (next: MessageQueueDto | null) => void;
  onClose: () => void;
}

function QueueEditDialog({ conversationId, message, onFinished, onClose }: QueueEditDialogProps) {
  const { t } = useTranslation("page");
  const initialText = React.useMemo(
    () =>
      message.parts
        .filter((part): part is Extract<UIMessagePart, { type: "text" }> => part.type === "text")
        .map((part) => part.text)
        .join("\n"),
    [message],
  );
  const [text, setText] = React.useState(initialText);
  const [saving, setSaving] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const unlockedRef = React.useRef(false);

  // 编辑排队消息草稿：消息本体尚在队列（未发送），其附件同样需要续租保护。
  // 覆盖 message.parts 全量（既有图片/音视频/文档）；将来若加附件增删 UI，
  // 收集入参随 parts 更新即可。
  const queuedAttachmentUrls = React.useMemo(
    () => collectLocalAttachmentUrls(message.parts),
    [message.parts],
  );
  useAttachmentDraftLeases(queuedAttachmentUrls);

  const unlock = React.useCallback(
    async (parts: UIMessagePart[] | null) => {
      if (unlockedRef.current) return;
      unlockedRef.current = true;
      try {
        const next = await api.post<MessageQueueDto>(
          `conversations/${conversationId}/queue/${message.id}/finish-edit`,
          { parts },
        );
        onFinished(next);
        return true;
      } catch (unlockError) {
        // Allow a later save attempt or the unmount fallback to retry the unlock.
        unlockedRef.current = false;
        onFinished(null);
        toast.error(
          unlockError instanceof Error ? unlockError.message : t("queue_panel.update_failed"),
        );
        return false;
      }
    },
    [conversationId, message.id, onFinished, t],
  );
  const unlockRef = React.useRef(unlock);
  unlockRef.current = unlock;

  // Dismissing the dialog or leaving the page must release the queue item.
  React.useEffect(() => {
    return () => {
      void unlockRef.current(null);
    };
  }, []);

  const handleSave = React.useCallback(() => {
    if (saving) return;
    const parts = buildEditedQueueParts(message.parts, text);
    if (parts.length === 0) {
      setError(t("queue_panel.edit_empty"));
      return;
    }
    setSaving(true);
    setError(null);
    void unlock(parts).then((ok) => {
      setSaving(false);
      if (ok) {
        onClose();
      } else {
        // The item may have stayed locked server-side; keep the dialog open so
        // the user can retry or explicitly cancel.
        setError(t("queue_panel.update_failed"));
      }
    });
  }, [message.parts, onClose, saving, t, text, unlock]);

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("queue_panel.edit_title")}</DialogTitle>
          <DialogDescription className="sr-only">{t("queue_panel.edit_title")}</DialogDescription>
        </DialogHeader>
        <Textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          className="max-h-[50vh] min-h-32 resize-none"
          autoFocus
        />
        {error ? <p className="text-xs text-destructive">{error}</p> : null}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={saving}>
            {t("queue_panel.edit_cancel")}
          </Button>
          <Button
            type="button"
            onClick={handleSave}
            disabled={saving || buildEditedQueueParts(message.parts, text).length === 0}
          >
            {saving ? t("queue_panel.edit_saving") : t("queue_panel.edit_save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export interface ConversationQueuePanelProps {
  conversationId: string;
  queue: MessageQueueDto | null;
  queueSize: number;
  queuePaused: boolean;
  onQueueChange: (next: MessageQueueDto | null) => void;
  className?: string;
}

export function ConversationQueuePanel({
  conversationId,
  queue,
  queueSize,
  queuePaused,
  onQueueChange,
  className,
}: ConversationQueuePanelProps) {
  const { t } = useTranslation("page");
  const [expanded, setExpanded] = React.useState(false);
  const [editingMessage, setEditingMessage] = React.useState<QueuedMessageDto | null>(null);
  const [pendingAction, setPendingAction] = React.useState<string | null>(null);

  const messages = queue?.messages ?? [];
  const displayCount = queue ? queue.messages.length : queueSize;
  const paused = queue?.paused ?? queuePaused;

  const refreshQueue = React.useCallback(async () => {
    try {
      const next = await api.get<MessageQueueDto>(`conversations/${conversationId}/queue`);
      onQueueChange(next);
    } catch {
      // Queue fetch is best-effort; SSE updates keep the panel in sync.
    }
  }, [conversationId, onQueueChange]);

  React.useEffect(() => {
    if (expanded) {
      void refreshQueue();
    }
  }, [expanded, refreshQueue]);

  const handleRemove = React.useCallback(
    async (queuedId: string) => {
      if (pendingAction) return;
      setPendingAction(`remove:${queuedId}`);
      try {
        await api.delete(`conversations/${conversationId}/queue/${queuedId}`);
        onQueueChange(
          queue ? { ...queue, messages: queue.messages.filter((m) => m.id !== queuedId) } : null,
        );
      } catch (removeError) {
        toast.error(
          removeError instanceof Error ? removeError.message : t("queue_panel.update_failed"),
        );
        void refreshQueue();
      } finally {
        setPendingAction(null);
      }
    },
    [conversationId, onQueueChange, pendingAction, queue, refreshQueue, t],
  );

  const handleBeginEdit = React.useCallback(
    async (queuedId: string) => {
      if (pendingAction) return;
      setPendingAction(`edit:${queuedId}`);
      try {
        const next = await api.post<MessageQueueDto>(
          `conversations/${conversationId}/queue/${queuedId}/begin-edit`,
        );
        onQueueChange(next);
        const target = next.messages.find((item) => item.id === queuedId) ?? null;
        if (target) {
          setEditingMessage(target);
        }
      } catch (editError) {
        toast.error(
          editError instanceof Error ? editError.message : t("queue_panel.update_failed"),
        );
      } finally {
        setPendingAction(null);
      }
    },
    [conversationId, onQueueChange, pendingAction, t],
  );

  const handleResume = React.useCallback(async () => {
    if (pendingAction) return;
    setPendingAction("resume");
    try {
      const next = await api.post<MessageQueueDto>(`conversations/${conversationId}/queue/resume`);
      onQueueChange(next);
    } catch (resumeError) {
      toast.error(
        resumeError instanceof Error ? resumeError.message : t("queue_panel.update_failed"),
      );
    } finally {
      setPendingAction(null);
    }
  }, [conversationId, onQueueChange, pendingAction, t]);

  if (displayCount <= 0) {
    return null;
  }

  return (
    <div className={cn("mx-auto w-full max-w-3xl px-4", className)}>
      <div className="rounded-2xl border border-border/60 bg-muted/50 px-3 py-1.5">
        <div className="flex items-center gap-1">
          <ListTodo className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate px-1 text-xs text-muted-foreground">
            {paused
              ? t("queue_panel.paused_count", { count: displayCount })
              : t("queue_panel.pending_count", { count: displayCount })}
          </span>
          {paused ? (
            <Button
              type="button"
              size="xs"
              variant="ghost"
              className="h-6 gap-1 px-2 text-xs"
              disabled={pendingAction !== null}
              onClick={() => void handleResume()}
            >
              <Play className="size-3" />
              {t("queue_panel.resume")}
            </Button>
          ) : null}
          <Button
            type="button"
            size="icon-xs"
            variant="ghost"
            className="size-6"
            aria-label={expanded ? t("queue_panel.collapse") : t("queue_panel.expand")}
            title={expanded ? t("queue_panel.collapse") : t("queue_panel.expand")}
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded ? <ChevronDown className="size-3.5" /> : <ChevronUp className="size-3.5" />}
          </Button>
        </div>

        {expanded ? (
          <ul className="max-h-44 divide-y divide-border/50 overflow-y-auto">
            {messages.map((queuedMessage, index) => (
              <li key={queuedMessage.id} className="flex items-center gap-1 py-1.5">
                <span className="min-w-0 flex-1 truncate text-xs">
                  {index + 1}.{" "}
                  {getQueuedMessagePreview(queuedMessage, t) || t("queue_panel.empty_message")}
                  {queuedMessage.isEditing ? (
                    <span className="ml-1 text-muted-foreground">{t("queue_panel.editing")}</span>
                  ) : null}
                </span>
                <Button
                  type="button"
                  size="icon-xs"
                  variant="ghost"
                  className="size-6 shrink-0"
                  aria-label={t("queue_panel.edit")}
                  title={queuedMessage.isEditing ? t("queue_panel.editing") : t("queue_panel.edit")}
                  disabled={pendingAction !== null || queuedMessage.isEditing}
                  onClick={() => void handleBeginEdit(queuedMessage.id)}
                >
                  <Pencil className="size-3" />
                </Button>
                <Button
                  type="button"
                  size="icon-xs"
                  variant="ghost"
                  className="size-6 shrink-0 text-destructive hover:text-destructive"
                  aria-label={t("queue_panel.remove")}
                  title={t("queue_panel.remove")}
                  disabled={pendingAction !== null || queuedMessage.isEditing}
                  onClick={() => void handleRemove(queuedMessage.id)}
                >
                  <X className="size-3.5" />
                </Button>
              </li>
            ))}
            {messages.length === 0 ? (
              <li className="py-2 text-xs text-muted-foreground">{t("queue_panel.empty")}</li>
            ) : null}
          </ul>
        ) : null}
      </div>

      {editingMessage ? (
        <QueueEditDialog
          key={editingMessage.id}
          conversationId={conversationId}
          message={editingMessage}
          onFinished={(next) => {
            if (next) {
              onQueueChange(next);
            } else {
              void refreshQueue();
            }
          }}
          onClose={() => setEditingMessage(null)}
        />
      ) : null}
    </div>
  );
}
