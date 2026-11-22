import * as React from "react";

import { useTranslation } from "react-i18next";

import api from "~/services/api";
import { diffObjects } from "../../lib/tavern";
import type { RegexScript } from "../../types/tavern";
import { reportSettingsError } from "../settings/settings-ui";
import { useRegexTools } from "./regex-tools";
import { RegexScriptList } from "./regex-script-list";
import { Hint } from "./tavern-ui";

/**
 * Regex scripts that apply to every assistant (they run before an assistant's own). Each change is saved by itself:
 * a patch of the changed fields, merged into the latest script on the server.
 */
export function GlobalRegexPanel({ scripts }: { scripts: RegexScript[] }) {
  const { t } = useTranslation("tavern");
  const tools = useRegexTools();
  // What is on screen while a save is on its way; the server's list (settings snapshot) takes over once idle.
  const [local, setLocal] = React.useState(scripts);
  const pending = React.useRef(0);
  const chain = React.useRef<Promise<unknown>>(Promise.resolve());
  React.useEffect(() => {
    if (pending.current === 0) setLocal(scripts);
  }, [scripts]);

  // The server keeps the id the page gives a new script, so edits made before the reply still find it.
  const run = (work: () => Promise<unknown>) => {
    pending.current += 1;
    chain.current = chain.current
      .then(work)
      .catch((error: unknown) => {
        reportSettingsError(error, t("common.saveFailed"));
        setLocal(scripts);
      })
      .finally(() => {
        pending.current -= 1;
      });
  };

  return (
    <div className="space-y-4">
      <Hint>{t("regex.globalDesc")}</Hint>
      <RegexScriptList
        scripts={local}
        tools={tools}
        onChange={setLocal}
        onAdd={(script) => run(() => api.post("tavern/regex", script))}
        onScriptChange={(next, previous) => {
          const patch = diffObjects(previous, next);
          if (Object.keys(patch).length > 0) run(() => api.put(`tavern/regex/${previous.id}`, patch));
        }}
        onScriptDelete={(script) => run(() => api.delete(`tavern/regex/${script.id}`))}
        onReorder={(ids) => run(() => api.put("tavern/regex/order", { ids }))}
      />
    </div>
  );
}
