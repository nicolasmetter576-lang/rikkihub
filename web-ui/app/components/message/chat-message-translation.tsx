import * as React from "react";

import { ChevronDown, ChevronUp, Languages, LoaderCircle, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import Markdown from "~/components/markdown/markdown";
import { Button } from "~/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "~/components/ui/popover";
import { Separator } from "~/components/ui/separator";

/** BCP-47 codes must stay in sync with the translate endpoint contract. */
export const TRANSLATE_LANGUAGES: Array<{ code: string; labelKey: string }> = [
  { code: "zh-CN", labelKey: "translation.lang_zh_cn" },
  { code: "zh-TW", labelKey: "translation.lang_zh_tw" },
  { code: "en", labelKey: "translation.lang_en" },
  { code: "ja", labelKey: "translation.lang_ja" },
  { code: "ko", labelKey: "translation.lang_ko" },
  { code: "fr", labelKey: "translation.lang_fr" },
  { code: "de", labelKey: "translation.lang_de" },
  { code: "es", labelKey: "translation.lang_es" },
  { code: "it", labelKey: "translation.lang_it" },
];

export interface TranslateActionsButtonProps {
  hasTranslation: boolean;
  disabled?: boolean;
  alignRight?: boolean;
  onTranslate: (language: string) => void | Promise<void>;
  onClearTranslation: () => void | Promise<void>;
}

export function TranslateActionsButton({
  hasTranslation,
  disabled = false,
  alignRight = false,
  onTranslate,
  onClearTranslation,
}: TranslateActionsButtonProps) {
  const { t } = useTranslation("message");
  const [open, setOpen] = React.useState(false);
  const [pending, setPending] = React.useState(false);

  const run = React.useCallback(
    async (action: () => void | Promise<void>) => {
      if (pending) return;
      setPending(true);
      try {
        await action();
        setOpen(false);
      } finally {
        setPending(false);
      }
    },
    [pending],
  );

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          aria-label={t("translation.translate")}
          disabled={disabled}
          size="icon-xs"
          title={t("translation.translate")}
          type="button"
          variant="ghost"
        >
          <Languages className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align={alignRight ? "end" : "start"} className="w-44 p-1">
        <div className="px-2 py-1.5 text-xs font-medium text-muted-foreground">
          {t("translation.select_language")}
        </div>
        {TRANSLATE_LANGUAGES.map((language) => (
          <button
            key={language.code}
            type="button"
            disabled={disabled || pending}
            className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground disabled:opacity-50"
            onClick={() => {
              void run(() => onTranslate(language.code));
            }}
          >
            {t(language.labelKey)}
          </button>
        ))}
        <Separator className="my-1" />
        <button
          type="button"
          disabled={disabled || pending || !hasTranslation}
          className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-destructive hover:bg-accent disabled:opacity-50"
          onClick={() => {
            void run(() => onClearTranslation());
          }}
        >
          <Trash2 className="size-3.5" />
          {t("translation.clear")}
        </button>
      </PopoverContent>
    </Popover>
  );
}

export interface ChatMessageTranslationProps {
  translation?: string | null;
  translating?: boolean;
  onClickCitation?: (citationId: string) => void;
}

export function ChatMessageTranslation({
  translation,
  translating = false,
  onClickCitation,
}: ChatMessageTranslationProps) {
  const { t } = useTranslation("message");
  const [collapsed, setCollapsed] = React.useState(false);
  const hasContent = Boolean(translation && translation.trim().length > 0);

  if (!translating && !hasContent) {
    return null;
  }

  return (
    <div className="mt-1 w-full">
      <Separator className="opacity-50" />
      <div className="flex items-center gap-2 pt-2">
        <Languages className="size-4 shrink-0 text-primary" />
        <span className="min-w-0 flex-1 text-sm font-semibold text-primary">
          {t("translation.title")}
        </span>
        <Button
          type="button"
          size="icon-xs"
          variant="ghost"
          aria-label={collapsed ? t("translation.expand") : t("translation.collapse")}
          title={collapsed ? t("translation.expand") : t("translation.collapse")}
          onClick={() => setCollapsed((value) => !value)}
        >
          {collapsed ? <ChevronDown className="size-3.5" /> : <ChevronUp className="size-3.5" />}
        </Button>
      </div>
      {!collapsed ? (
        <div className="mt-2 rounded-lg bg-primary/5 px-3 py-2">
          {translating && !hasContent ? (
            <div className="flex items-center gap-2 py-1 text-sm text-muted-foreground">
              <LoaderCircle className="size-3.5 animate-spin" />
              <span className="animate-pulse">{t("translation.translating")}</span>
            </div>
          ) : (
            <Markdown
              content={translation ?? ""}
              onClickCitation={onClickCitation}
              className="text-sm"
            />
          )}
        </div>
      ) : null}
    </div>
  );
}
