import { useTranslation } from "react-i18next";

import type { ServerToolPart as ServerToolPartData } from "~/types";

// Keep the stored JSON untouched. React escapes this plain text; do not pass it
// to Markdown/HTML renderers or turn provider strings into executable links.
function displayJson(value: unknown): string {
  return typeof value === "string" ? value : (JSON.stringify(value, null, 2) ?? "");
}

export function ServerToolPart({ part }: { part: ServerToolPartData }) {
  const { t } = useTranslation("message");
  return (
    <details className="my-2 rounded-md border p-3 text-sm">
      <summary className="cursor-pointer break-all">
        {t("server_tool.label", { name: part.toolName })} · {t(`server_tool.status.${part.status}`)}
      </summary>
      <p className="mt-2 text-xs text-muted-foreground">{t("server_tool.display_only")}</p>
      {(["input", "output"] as const).map((field) =>
        part[field] == null ? null : (
          <div key={field} className="mt-2">
            <p className="text-xs font-medium">{t(`server_tool.${field}`)}</p>
            <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs">
              {displayJson(part[field])}
            </pre>
          </div>
        ),
      )}
    </details>
  );
}
