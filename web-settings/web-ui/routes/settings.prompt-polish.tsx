import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { getModelDisplayName } from "~/lib/display";
import { useSettingsStore } from "~/stores";
import { SettingsField, SettingsPageHeader, SettingsSelectField } from "@overlay/components/settings/settings-ui";
import { listChatModels } from "@overlay/lib/settings";
import {
  fetchPromptPolishConfig,
  updatePromptPolishConfig,
  type PromptPolishConfig,
  type PromptPolishConfigPatch,
} from "@overlay/lib/prompt-polish";

const ASSISTANT_MODEL = "__assistant__";

/** 润色设置（与 App「设置 → 润色」同一份配置）：模型、对话上下文、润色提示词。 */
export default function SettingsPromptPolishPage() {
  const { t } = useTranslation("settings");
  const settings = useSettingsStore(s => s.settings);
  const [config, setConfig] = React.useState<PromptPolishConfig | null>(null);
  const [promptDraft, setPromptDraft] = React.useState<string | undefined>(undefined);

  React.useEffect(() => {
    let mounted = true;
    fetchPromptPolishConfig()
      .then(value => { if (mounted) setConfig(value); })
      .catch(error => toast.error(extractErrorMessage(error, t("common.save_failed"))));
    return () => { mounted = false; };
  }, [t]);

  const apply = React.useCallback(
    (patch: PromptPolishConfigPatch) =>
      updatePromptPolishConfig(patch)
        .then(next => { setConfig(next); return true; })
        .catch(error => { toast.error(extractErrorMessage(error, t("common.save_failed"))); return false; }),
    [t],
  );

  if (!config) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("prompt_polish.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  const chatModels = settings ? listChatModels(settings) : [];
  const savedPrompt = config.prompt ?? config.defaultPrompt;
  const promptValue = promptDraft ?? savedPrompt;
  const promptDirty = promptDraft !== undefined && promptDraft !== savedPrompt;
  const counts = Array.from(
    { length: config.maxContextMessages - config.minContextMessages + 1 },
    (_, i) => config.minContextMessages + i,
  );

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("prompt_polish.title")} description={t("prompt_polish.description")} />

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("prompt_polish.model_section")}</h2>
        <SettingsSelectField
          label={t("prompt_polish.model")}
          value={config.modelId ?? ASSISTANT_MODEL}
          onValueChange={value => void apply({ modelId: value === ASSISTANT_MODEL ? null : value })}
          options={[
            { value: ASSISTANT_MODEL, label: t("prompt_polish.model_assistant") },
            ...(config.modelId && !chatModels.some(({ model }) => model.id === config.modelId)
              ? [{ value: config.modelId, label: t("prompt_polish.model_missing") }] : []),
            ...chatModels.map(({ provider, model }) => ({
              value: model.id,
              label: `${provider.name} / ${getModelDisplayName(model.displayName, model.modelId)}`,
            })),
          ]}
        />
        <p className={config.modelMissing ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
          {config.modelMissing ? t("prompt_polish.model_missing") : t("prompt_polish.model_hint")}
        </p>
      </section>

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("prompt_polish.context_section")}</h2>
        <div className="flex items-center justify-between gap-4">
          <div>
            <p className="text-sm">{t("prompt_polish.context")}</p>
            <p className="text-xs text-muted-foreground">{t("prompt_polish.context_hint")}</p>
          </div>
          <Switch checked={config.includeContext} data-testid="prompt-polish-context"
            onCheckedChange={includeContext => void apply({ includeContext })} />
        </div>
        {config.includeContext ? (
          <SettingsSelectField
            label={t("prompt_polish.context_count")}
            value={String(config.contextMessages)}
            onValueChange={value => void apply({ contextMessages: Number(value) })}
            options={counts.map(count => ({ value: String(count), label: t("prompt_polish.context_count_value", { count }) }))}
          />
        ) : null}
      </section>

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("prompt_polish.prompt")}</h2>
        <p className="text-xs text-muted-foreground">{t("prompt_polish.prompt_hint")}</p>
        <SettingsField label={t("prompt_polish.prompt")}>
          <Textarea value={promptValue} rows={12} maxLength={config.maxPromptChars} data-testid="prompt-polish-prompt"
            onChange={event => setPromptDraft(event.target.value)} />
          <div className="flex justify-end gap-2">
            {config.prompt ? (
              <Button variant="ghost" size="sm" onClick={() => void apply({ prompt: null }).then(ok => {
                if (ok) setPromptDraft(undefined);
              })}>{t("prompt_polish.reset_prompt")}</Button>
            ) : null}
            <Button size="sm" disabled={!promptDirty} data-testid="prompt-polish-save"
              onClick={() => void apply({ prompt: promptValue }).then(ok => { if (ok) setPromptDraft(undefined); })}>
              {t("common.save")}
            </Button>
          </div>
        </SettingsField>
      </section>
    </div>
  );
}
