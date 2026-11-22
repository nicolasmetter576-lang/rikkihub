import * as React from "react";

import { useTranslation } from "react-i18next";

import { CharacterAvatar } from "@overlay/components/tavern/character-avatar";
import { ActiveCharacterContext } from "@overlay/hooks/use-active-character";
import { AIIcon } from "~/components/ui/ai-icon";
import { UIAvatar } from "~/components/ui/ui-avatar";
import { useSettingsStore } from "~/stores";
import type { AssistantProfile, MessageDto, ProviderModel } from "~/types";

export interface ChatMessageAvatarRowProps {
  message: MessageDto;
  hasMessageContent: boolean;
  loading: boolean;
  assistant?: AssistantProfile | null;
  model?: ProviderModel | null;
}

// Intl.DateTimeFormat construction is expensive; keep one formatter per locale.
const TIMESTAMP_FORMATTERS = new Map<string, Intl.DateTimeFormat>();

function formatMessageTimestamp(createdAt: string, locale?: string): string | null {
  const timestamp = Date.parse(createdAt);
  if (Number.isNaN(timestamp)) return null;

  const key = locale || "";
  let formatter = TIMESTAMP_FORMATTERS.get(key);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale || undefined, {
      dateStyle: "medium",
      timeStyle: "medium",
    });
    TIMESTAMP_FORMATTERS.set(key, formatter);
  }
  return formatter.format(timestamp);
}

export function ChatMessageAvatarRow({
  message,
  hasMessageContent,
  loading,
  assistant,
  model,
}: ChatMessageAvatarRowProps) {
  const { t, i18n } = useTranslation(["common", "page"]);
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);
  const character = React.useContext(ActiveCharacterContext);

  if (!hasMessageContent) {
    return null;
  }

  const createdAtLabel = formatMessageTimestamp(message.createdAt, i18n.language);

  if (message.role === "USER") {
    if (!displaySetting?.showUserAvatar) {
      return null;
    }

    const userName =
      displaySetting.userNickname.trim() ||
      t("page:conversations.user.default_name", { defaultValue: "User" });

    return (
      <div className="flex w-full justify-end">
        <div className="flex items-center gap-2">
          <div className="min-w-0 text-right">
            <div className="truncate text-sm font-medium text-foreground/90">{userName}</div>
            {createdAtLabel ? (
              <div className="truncate text-xs text-muted-foreground/80">{createdAtLabel}</div>
            ) : null}
          </div>
          <UIAvatar name={userName} avatar={displaySetting.userAvatar} className="size-9" />
        </div>
      </div>
    );
  }

  if (message.role !== "ASSISTANT") {
    return null;
  }

  const showModelIcon = displaySetting?.showModelIcon !== false;
  const showModelName = displaySetting?.showModelName === true;

  // 模型被删除后用消息上的快照；两者都没有时退回助手名称，时间照常显示
  const modelInfo = model ?? message.modelSnapshot ?? null;
  const useAssistantAvatar = assistant?.useAssistantAvatar === true;
  const defaultAssistantName = t("common:quick_jump.role_assistant", { defaultValue: "Assistant" });
  const assistantName = assistant?.name?.trim() || defaultAssistantName;
  const modelName = modelInfo
    ? modelInfo.displayName.trim() || modelInfo.modelId.trim() || defaultAssistantName
    : assistantName;
  // 和角色聊天：头像与名字是角色的，不是模型的
  const title = character ? character.name : useAssistantAvatar ? assistantName : modelName;

  const icon = !showModelIcon ? null : character ? (
    <CharacterAvatar name={character.name} avatarUrl={character.avatarUrl} className="size-9" />
  ) : useAssistantAvatar ? (
    <UIAvatar name={assistantName} avatar={assistant?.avatar} className="size-9" />
  ) : modelInfo ? (
    <AIIcon
      name={modelInfo.modelId}
      size={36}
      loading={loading}
      className="bg-secondary"
      imageClassName="h-[72%] w-[72%]"
    />
  ) : null;
  // 每轮标题：模型名右侧的浅色小字
  const turnTitle = message.title?.trim() || null;
  const turnTitleLabel = turnTitle ? (
    <div className="truncate text-xs text-muted-foreground/70">{turnTitle}</div>
  ) : null;
  if (!icon && !showModelName && !turnTitle) {
    return null;
  }

  return (
    <div className="flex w-full justify-start">
      <div className="flex min-w-0 items-center gap-2">
        {icon}
        {showModelName ? (
          <div className="min-w-0">
            <div className="flex min-w-0 items-baseline gap-2">
              <div className="truncate text-sm font-medium text-foreground/90">{title}</div>
              {turnTitleLabel}
            </div>
            {createdAtLabel ? (
              <div className="truncate text-xs text-muted-foreground/80">{createdAtLabel}</div>
            ) : null}
          </div>
        ) : (
          turnTitleLabel
        )}
      </div>
    </div>
  );
}
