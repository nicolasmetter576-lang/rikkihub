import { BookOpen } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "~/components/ui/popover";
import type { ActivationSummary } from "@overlay/types/tavern";

/** `WorldInfoPosition` → the label key of the entry editor. */
const POSITION_KEYS = [
  "worldinfo.posBefore",
  "worldinfo.posAfter",
  "worldinfo.posAnTop",
  "worldinfo.posAnBottom",
  "worldinfo.posDepth",
  "worldinfo.posEmTop",
  "worldinfo.posEmBottom",
  "worldinfo.posOutlet",
] as const;

/**
 * "World info used (N)": the entries that were added to the prompt for this reply, with their book, where they went
 * and their order. The server records them on the reply (see `ActivationRecord`); old replies have none.
 */
export function ActivationsButton({
  summary,
  disabled,
  alignRight,
}: {
  summary: ActivationSummary;
  disabled?: boolean;
  alignRight?: boolean;
}) {
  const { t } = useTranslation("tavern");
  const label = t("worldinfo.activationsAction", { p1: summary.total });
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          aria-label={label}
          title={label}
          disabled={disabled}
          size="icon-xs"
          type="button"
          variant="ghost"
          data-activations-button
        >
          <BookOpen className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent side="top" align={alignRight ? "end" : "start"} className="w-[min(92vw,22rem)] space-y-2 p-3">
        <PopoverTitle className="text-sm">{t("worldinfo.activationsTitle")}</PopoverTitle>
        <p className="text-xs text-muted-foreground">{t("worldinfo.activationsHint")}</p>
        <ul className="max-h-72 space-y-2 overflow-y-auto">
          {summary.entries.map((entry, index) => (
            <li key={`${entry.bookId}-${entry.uid}-${index}`} className="text-sm">
              <p className="truncate font-medium">{entry.title.trim() ? entry.title : t("worldinfo.activationsUnnamed", { p1: entry.uid })}</p>
              <p className="truncate text-xs text-muted-foreground">
                {t("worldinfo.activationsDetail", {
                  p1: entry.bookName,
                  p2: POSITION_KEYS[entry.position] ? t(POSITION_KEYS[entry.position]) : String(entry.position),
                  p3: entry.order,
                })}
              </p>
            </li>
          ))}
        </ul>
        {summary.more > 0 ? <p className="text-xs text-muted-foreground">{t("worldinfo.activationsMore", { p1: summary.more })}</p> : null}
      </PopoverContent>
    </Popover>
  );
}
