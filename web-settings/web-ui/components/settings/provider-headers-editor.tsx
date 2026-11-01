import * as React from "react";
import { useTranslation } from "react-i18next";
import { COMMON_PROVIDER_HEADER_NAMES, parseProviderHeaders } from "../../lib/provider-headers";

export function ProviderHeadersEditor({ value, onChange, disabled }: {
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
}) {
  const { t } = useTranslation("settings");
  const result = parseProviderHeaders(value);
  return (
    <fieldset disabled={disabled} className="space-y-2 rounded-md border p-3">
      <legend className="px-1 text-sm font-medium">{t("providers.headers.title")}</legend>
      <label className="flex flex-col gap-1.5">
        <span className="text-sm">{t("providers.headers.json_label")}</span>
        <textarea
          rows={5}
          className="w-full rounded-md border bg-transparent p-2 font-mono text-sm"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          autoComplete="off"
          spellCheck={false}
          aria-invalid={!result.ok}
        />
      </label>
      <label className="flex flex-col gap-1.5 text-sm">
        <span>{t("providers.headers.common_name")}</span>
        <select
          className="rounded-md border bg-background p-2"
          value=""
          disabled={!result.ok || disabled}
          onChange={(event) => {
            if (result.ok && event.target.value) {
              onChange(JSON.stringify([...result.customHeaders, { name: event.target.value, value: "" }], null, 2));
            }
          }}
        >
          <option value="">{t("providers.headers.choose_name")}</option>
          {COMMON_PROVIDER_HEADER_NAMES.map((name) => <option key={name} value={name}>{name}</option>)}
        </select>
      </label>
      {!result.ok ? <p role="alert" className="text-sm text-destructive">{t(`providers.headers.errors.${result.error}`)}</p> : null}
      <p className="text-xs text-muted-foreground">{t("providers.headers.format_hint")}</p>
      <p className="text-xs text-muted-foreground">{t("providers.headers.priority_hint")}</p>
      <p className="text-xs text-muted-foreground">{t("providers.headers.save_hint")}</p>
    </fieldset>
  );
}
