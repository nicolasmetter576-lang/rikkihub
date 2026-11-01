import * as React from "react";

import { useTranslation } from "react-i18next";

import { textStats } from "@overlay/lib/writing-stats";

/**
 * The editor's bottom status bar, like the App's: characters and words of the file, and of the selection
 * when there is one (none in the preview tab). Totals follow the text through a deferred value so typing in
 * a large file stays smooth.
 */
export function WorkspaceStatusBar({ text, selection }: { text: string; selection: { from: number; to: number } | null }) {
  const { t, i18n } = useTranslation("workspace");
  const deferred = React.useDeferredValue(text);
  const total = React.useMemo(() => textStats(deferred), [deferred]);
  const selected = React.useMemo(
    () => selection && selection.to > selection.from ? textStats(text, selection.from, selection.to) : null,
    [text, selection],
  );
  const format = React.useMemo(() => new Intl.NumberFormat(i18n.language), [i18n.language]);
  const label = selected
    ? t("writing.stats_selection", {
      selChars: format.format(selected.chars), chars: format.format(total.chars),
      selWords: format.format(selected.words), words: format.format(total.words),
    })
    : t("writing.stats", { chars: format.format(total.chars), words: format.format(total.words) });
  return (
    <div data-testid="workspace-stats" className="shrink-0 truncate border-t bg-muted/30 px-3 py-0.5 text-[11px] tabular-nums text-muted-foreground">
      {label}
    </div>
  );
}
