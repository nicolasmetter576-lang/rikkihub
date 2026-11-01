import * as React from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronUp, Package } from "lucide-react";

import Markdown from "~/components/markdown/markdown";
import { Button } from "~/components/ui/button";
import type { MessageDto } from "~/types";

interface ChatMessageCheckpointProps {
  message: MessageDto;
  onEdit?: (message: MessageDto) => void | Promise<void>;
  onDelete?: (messageId: string) => void | Promise<void>;
}

function checkpointText(message: MessageDto): string {
  return message.parts
    .map((part) => (part.type === "text" ? part.text : ""))
    .join("\n")
    .trim();
}

/**
 * 压缩检查点：分隔线以上的消息仍可查看，但不再发送给模型，由这里的摘要代替。
 * 默认折叠，避免长摘要打断历史消息的阅读；删除摘要即撤销压缩。
 */
export function ChatMessageCheckpoint({ message, onEdit, onDelete }: ChatMessageCheckpointProps) {
  const { t } = useTranslation("message");
  const [expanded, setExpanded] = React.useState(false);

  return (
    <div className="flex w-full flex-col gap-2" data-context-checkpoint>
      <button
        type="button"
        className="flex w-full items-center gap-2 rounded-md py-2 text-xs text-muted-foreground hover:bg-muted/60"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        <span className="h-px flex-1 bg-border" />
        <Package className="size-3.5" />
        <span>{t("chat_message.checkpoint_title")}</span>
        {expanded ? <ChevronUp className="size-3.5" /> : <ChevronDown className="size-3.5" />}
        <span className="h-px flex-1 bg-border" />
      </button>
      {expanded ? (
        <div className="flex flex-col gap-2 rounded-lg bg-muted/60 px-4 pt-3 pb-1 text-sm">
          <p className="text-xs text-muted-foreground">{t("chat_message.checkpoint_hint")}</p>
          <Markdown content={checkpointText(message)} />
          <div className="flex justify-end gap-1">
            {onEdit ? (
              <Button type="button" size="sm" variant="ghost" onClick={() => void onEdit(message)}>
                {t("chat_message.edit")}
              </Button>
            ) : null}
            {onDelete ? (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                className="text-destructive hover:text-destructive"
                onClick={() => void onDelete(message.id)}
              >
                {t("chat_message.delete")}
              </Button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}
