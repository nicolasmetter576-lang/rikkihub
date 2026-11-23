import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Input } from "~/components/ui/input";
import { extractErrorMessage } from "~/lib/error";
import { getModelDisplayName } from "~/lib/display";
import { useSettingsStore } from "~/stores";
import { SettingsField, SettingsPageHeader, SettingsSelectField } from "@overlay/components/settings/settings-ui";
import { fetchBookConfig, updateBookConfig, type BookConfig } from "@overlay/lib/book";
import { listChatModels } from "@overlay/lib/settings";
import { ZAIZAO_REASONING_LEVELS } from "@overlay/lib/zaizao";

const ASSISTANT_MODEL = "__assistant__";

/** 作品设置（与 App「设置 → 作品」同一份）：作品功能用的模型和参数。 */
export default function SettingsBookPage() {
  const { t } = useTranslation("settings");
  const settings = useSettingsStore((s) => s.settings);
  const [config, setConfig] = React.useState<BookConfig | null>(null);
  const [temperature, setTemperature] = React.useState("");
  const show = React.useCallback((next: BookConfig) => {
    setConfig(next);
    setTemperature(next.temperature == null ? "" : String(next.temperature));
  }, []);

  React.useEffect(() => {
    let mounted = true;
    fetchBookConfig()
      .then((value) => {
        if (mounted) show(value);
      })
      .catch((error) => toast.error(extractErrorMessage(error, t("common.save_failed"))));
    return () => {
      mounted = false;
    };
  }, [t, show]);

  const apply = (patch: Partial<Pick<BookConfig, "modelId" | "reasoningLevel" | "temperature">>) =>
    updateBookConfig(patch)
      .then(show)
      .catch((error) => toast.error(extractErrorMessage(error, t("common.save_failed"))));

  if (!config) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("book.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }
  const chatModels = settings ? listChatModels(settings) : [];
  const parsed = temperature.trim() === "" ? null : Number(temperature);
  const invalid = parsed !== null && !(parsed >= 0 && parsed <= 2);
  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("book.title")} description={t("book.description")} />
      <section className="space-y-3 rounded-xl border p-3">
        <SettingsSelectField
          label={t("zaizao.model")}
          value={config.modelId ?? ASSISTANT_MODEL}
          onValueChange={(value) => void apply({ modelId: value === ASSISTANT_MODEL ? null : value })}
          options={[
            { value: ASSISTANT_MODEL, label: t("zaizao.model_assistant") },
            ...(config.modelId && !chatModels.some(({ model }) => model.id === config.modelId)
              ? [{ value: config.modelId, label: t("zaizao.model_missing") }]
              : []),
            ...chatModels.map(({ provider, model }) => ({
              value: model.id,
              label: `${provider.name} / ${getModelDisplayName(model.displayName, model.modelId)}`,
            })),
          ]}
        />
        <p className={config.modelMissing ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
          {config.modelMissing ? t("zaizao.model_missing") : t("zaizao.model_hint")}
        </p>
        <SettingsSelectField
          label={t("zaizao.reasoning")}
          value={config.reasoningLevel}
          onValueChange={(value) => void apply({ reasoningLevel: value })}
          options={ZAIZAO_REASONING_LEVELS.map((level) => ({ value: level, label: t(`reasoning.${level}`) }))}
        />
        <SettingsField label={t("zaizao.temperature")}>
          <Input
            type="number"
            min={0}
            max={2}
            step={0.1}
            value={temperature}
            aria-invalid={invalid}
            data-testid="book-temperature"
            onChange={(event) => setTemperature(event.target.value)}
            onBlur={() => {
              if (!invalid && parsed !== (config.temperature ?? null)) void apply({ temperature: parsed });
            }}
          />
        </SettingsField>
      </section>
    </div>
  );
}
