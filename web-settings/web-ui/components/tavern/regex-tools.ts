import * as React from "react";

import api from "~/services/api";
import type { RegexCheckResult, RegexScript } from "../../types/tavern";
import type { RegexTools } from "./regex-script-card";

/**
 * Find-string check and try-out over the API (the same engine and macro environment the App uses).
 * [assistantId] is the assistant the macros (`{{char}}` ...) are resolved for; global scripts pass none.
 * The result is memoised: the cards re-check only when the tools change.
 */
export function useRegexTools(assistantId?: string): RegexTools {
  return React.useMemo<RegexTools>(
    () => ({
      check: (findRegex, substituteRegex) =>
        api.post<RegexCheckResult>("tavern/regex/check", { findRegex, substituteRegex }),
      tryOut: async (script: RegexScript, sample: string) => {
        const result = await api.post<{ text: string }>("tavern/regex/preview", { script, sample, assistantId });
        return result.text;
      },
    }),
    [assistantId],
  );
}
