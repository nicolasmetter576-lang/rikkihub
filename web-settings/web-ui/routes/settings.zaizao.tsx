import * as React from "react";

import { X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { getModelDisplayName } from "~/lib/display";
import { useSettingsStore } from "~/stores";
import { SettingsField, SettingsPageHeader, SettingsSegmented, SettingsSelectField } from "@overlay/components/settings/settings-ui";
import { listChatModels } from "@overlay/lib/settings";
import {
  fetchZaizaoConfig,
  updateZaizaoConfig,
  ZAIZAO_REASONING_LEVELS,
  ZAIZAO_TYPES,
  type ZaizaoConfig,
  type ZaizaoConfigPatch,
  type ZaizaoType,
} from "@overlay/lib/zaizao";

const ASSISTANT_MODEL = "__assistant__";

/**
 * 再造设置（与 App「设置 → 再造」同一份配置）：模型与推理参数、是否发送后文、默认类型、三个类型的提示词、
 * 最近用过的要求；文件类型与推敲共用。
 */
export default function SettingsZaizaoPage() {
  const { t } = useTranslation("settings");
  const settings = useSettingsStore(s => s.settings);
  const [config, setConfig] = React.useState<ZaizaoConfig | null>(null);
  const [promptDrafts, setPromptDrafts] = React.useState<Partial<Record<ZaizaoType, string>>>({});

  React.useEffect(() => {
    let mounted = true;
    fetchZaizaoConfig()
      .then(value => { if (mounted) setConfig(value); })
      .catch(error => toast.error(extractErrorMessage(error, t("common.save_failed"))));
    return () => { mounted = false; };
  }, [t]);

  // Recent requirements also change from the editor (each sent round): follow the live settings.
  const liveRecent = settings?.zaizao?.recentRequirements;
  React.useEffect(() => {
    if (liveRecent) setConfig(current => current ? { ...current, recentRequirements: liveRecent } : current);
  }, [liveRecent]);

  const apply = React.useCallback(
    (patch: ZaizaoConfigPatch) =>
      updateZaizaoConfig(patch)
        .then(next => { setConfig(next); return true; })
        .catch(error => { toast.error(extractErrorMessage(error, t("common.save_failed"))); return false; }),
    [t],
  );

  if (!config) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("zaizao.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  const chatModels = settings ? listChatModels(settings) : [];

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("zaizao.title")} description={t("zaizao.description")} />

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("zaizao.model_section")}</h2>
        <SettingsSelectField
          label={t("zaizao.model")}
          value={config.modelId ?? ASSISTANT_MODEL}
          onValueChange={value => void apply({ modelId: value === ASSISTANT_MODEL ? null : value })}
          options={[
            { value: ASSISTANT_MODEL, label: t("zaizao.model_assistant") },
            ...(config.modelId && !chatModels.some(({ model }) => model.id === config.modelId)
              ? [{ value: config.modelId, label: t("zaizao.model_missing") }] : []),
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
          onValueChange={value => void apply({ reasoningLevel: value as ZaizaoConfig["reasoningLevel"] })}
          options={ZAIZAO_REASONING_LEVELS.map(level => ({ value: level, label: t(`reasoning.${level}`) }))}
        />
        <div className="grid gap-3 sm:grid-cols-3">
          <NumberField label={t("zaizao.temperature")} value={config.temperature} min={0} max={2} step={0.1} testId="zaizao-temperature"
            valid={value => value >= 0 && value <= 2} onCommit={temperature => apply({ temperature })} />
          <NumberField label={t("zaizao.top_p")} value={config.topP} min={0} max={1} step={0.05} testId="zaizao-top-p"
            valid={value => value > 0 && value <= 1} onCommit={topP => apply({ topP })} />
          <NumberField label={t("zaizao.max_tokens")} value={config.maxTokens} min={1} step={1} testId="zaizao-max-tokens"
            valid={value => Number.isInteger(value) && value > 0} onCommit={maxTokens => apply({ maxTokens })} />
        </div>
        <p className="text-xs text-muted-foreground">{t("zaizao.params_hint")}</p>
      </section>

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("zaizao.context_section")}</h2>
        <div className="flex items-center justify-between gap-4">
          <div>
            <p className="text-sm">{t("zaizao.include_after")}</p>
            <p className="text-xs text-muted-foreground">{t("zaizao.include_after_hint")}</p>
          </div>
          <Switch checked={config.includeAfter} data-testid="zaizao-include-after-default"
            onCheckedChange={includeAfter => void apply({ includeAfter })} />
        </div>
      </section>

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("zaizao.types_section")}</h2>
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-sm">{t("zaizao.default_type")}</span>
          <SettingsSegmented
            value={config.defaultType}
            onChange={defaultType => void apply({ defaultType })}
            options={ZAIZAO_TYPES.map(type => ({ value: type, label: t(`zaizao.types.${type}`) }))}
          />
        </div>
        <ul className="space-y-1 text-xs text-muted-foreground">
          {ZAIZAO_TYPES.map(type => <li key={type}>{t("zaizao.type_line", { type: t(`zaizao.types.${type}`), hint: t(`zaizao.type_hints.${type}`) })}</li>)}
        </ul>
        <p className="text-xs text-muted-foreground">{t("zaizao.prompts_hint")}</p>
        {ZAIZAO_TYPES.map(type => {
          const saved = config.prompts[type] ?? config.defaultPrompts[type] ?? "";
          const value = promptDrafts[type] ?? saved;
          const dirty = promptDrafts[type] !== undefined && promptDrafts[type] !== saved;
          return (
            <SettingsField key={type} label={t("zaizao.prompt", { type: t(`zaizao.types.${type}`) })}>
              <Textarea value={value} rows={5} maxLength={config.maxPromptChars} data-testid={`zaizao-prompt-${type}`}
                onChange={event => setPromptDrafts(drafts => ({ ...drafts, [type]: event.target.value }))} />
              <div className="flex justify-end gap-2">
                {config.prompts[type] ? (
                  <Button variant="ghost" size="sm" onClick={() => void apply({ prompts: { [type]: null } }).then(ok => {
                    if (ok) setPromptDrafts(drafts => ({ ...drafts, [type]: undefined }));
                  })}>{t("zaizao.reset_prompt")}</Button>
                ) : null}
                <Button size="sm" disabled={!dirty} data-testid={`zaizao-prompt-save-${type}`}
                  onClick={() => void apply({ prompts: { [type]: value } }).then(ok => {
                    if (ok) setPromptDrafts(drafts => ({ ...drafts, [type]: undefined }));
                  })}>{t("common.save")}</Button>
              </div>
            </SettingsField>
          );
        })}
      </section>

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("zaizao.recent_section")}</h2>
        {config.recentRequirements.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("zaizao.recent_empty")}</p>
        ) : (
          <ul className="space-y-1" data-testid="zaizao-recent-list">
            {config.recentRequirements.map(text => (
              <li key={text} className="flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm">
                <span className="min-w-0 flex-1 truncate" title={text}>{text}</span>
                <Button variant="ghost" size="icon" className="size-7" aria-label={t("zaizao.recent_remove")}
                  onClick={() => void apply({ recentRequirements: config.recentRequirements.filter(item => item !== text) })}>
                  <X className="size-3.5" />
                </Button>
              </li>
            ))}
          </ul>
        )}
        {config.recentRequirements.length > 0 ? (
          <Button variant="outline" size="sm" onClick={() => void apply({ recentRequirements: [] })}>{t("zaizao.recent_clear")}</Button>
        ) : null}
      </section>

      <section className="space-y-2 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("zaizao.files_section")}</h2>
        <p className="text-sm text-muted-foreground">
          {t("zaizao.files_hint", { list: config.extensions.map(ext => `.${ext}`).join(" ") })}
        </p>
        <Link to="/settings/tuiqiao" className="text-sm text-primary hover:underline">{t("zaizao.files_link")}</Link>
      </section>
    </div>
  );
}

/** A number saved on blur / Enter; empty clears it (model default), an invalid value is not saved. */
function NumberField({ label, value, min, max, step, valid, onCommit, testId }: {
  label: string;
  value: number | null;
  min?: number;
  max?: number;
  step?: number;
  valid: (value: number) => boolean;
  onCommit: (value: number | null) => Promise<boolean>;
  testId: string;
}) {
  const [text, setText] = React.useState(value === null ? "" : String(value));
  React.useEffect(() => { setText(value === null ? "" : String(value)); }, [value]);
  const parsed = text.trim() === "" ? null : Number(text);
  const invalid = parsed !== null && (!Number.isFinite(parsed) || !valid(parsed));
  const commit = () => {
    if (invalid || parsed === value) return;
    void onCommit(parsed);
  };
  return (
    <SettingsField label={label}>
      <Input type="number" inputMode="decimal" value={text} min={min} max={max} step={step} aria-invalid={invalid}
        className={invalid ? "border-destructive" : undefined} data-testid={testId}
        onChange={event => setText(event.target.value)} onBlur={commit}
        onKeyDown={event => { if (event.key === "Enter") commit(); }} />
    </SettingsField>
  );
}
