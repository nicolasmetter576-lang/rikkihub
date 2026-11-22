import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { Slider } from "~/components/ui/slider";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { getModelDisplayName } from "~/lib/display";
import { useSettingsStore } from "~/stores";
import type { AutoCompressMode, CarryOriginalsMode } from "~/types";
import {
  SettingsPageHeader,
  SettingsSegmented,
  SettingsSelectField,
  SettingsSwitchRow,
} from "@overlay/components/settings/settings-ui";
import { listChatModels } from "@overlay/lib/settings";
import {
  BUILT_IN_TEMPLATE_IDS,
  fetchCompressionConfig,
  templateName,
  updateCompressionConfig,
  type CompressionConfig,
  type CompressionConfigPatch,
  type CompressionTemplate,
} from "@overlay/lib/compression";

const CHAT_MODEL = "__chat__";
const FOLLOW_NETWORK = "__follow__";
const SUMMARY_TOKEN_PRESETS = ["1024", "2048", "4096", "8192", "16384"] as const;
const CHUNK_TIMEOUT_PRESETS = ["60", "120", "180", "300"] as const;

/** 上下文压缩设置（与 App「设置 → 上下文压缩」同一份配置）。 */
export default function SettingsCompressionPage() {
  const { t } = useTranslation("settings");
  const settings = useSettingsStore((s) => s.settings);
  const [config, setConfig] = React.useState<CompressionConfig | null>(null);
  const [drafts, setDrafts] = React.useState<{
    trigger?: number;
    target?: number;
    keep?: number;
    carryCount?: number;
    maxRequests?: number;
  }>({});
  const [viewing, setViewing] = React.useState<string | null>(null);
  const [editing, setEditing] = React.useState<CompressionTemplate | null>(null);

  React.useEffect(() => {
    let mounted = true;
    fetchCompressionConfig()
      .then((value) => { if (mounted) setConfig(value); })
      .catch((error) => toast.error(extractErrorMessage(error, t("common.save_failed"))));
    return () => { mounted = false; };
  }, [t]);

  const apply = React.useCallback(
    (patch: CompressionConfigPatch) =>
      updateCompressionConfig(patch)
        .then((next) => { setConfig(next); return true; })
        .catch((error) => { toast.error(extractErrorMessage(error, t("common.save_failed"))); return false; }),
    [t],
  );

  if (!config) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("compression.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  const chatModels = settings ? listChatModels(settings) : [];
  const trigger = drafts.trigger ?? config.triggerPercent;
  const target = Math.min(drafts.target ?? config.targetPercent, trigger - 5);
  const keep = drafts.keep ?? config.keepRecent;
  const carryCount = drafts.carryCount ?? Math.min(30, config.carryOriginalsCount);
  const maxRequests = drafts.maxRequests ?? config.maxChunkRequests;
  const saveTemplates = (templates: CompressionTemplate[], makeDefault?: string) =>
    apply({ customTemplates: templates, ...(makeDefault ? { templateId: makeDefault } : {}) });

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("compression.title")} description={t("compression.description")} />

      <section className="space-y-3 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("compression.mode")}</h2>
        <SettingsSegmented<AutoCompressMode>
          value={config.mode}
          onChange={(mode) => void apply({ mode })}
          options={(["off", "notify", "auto"] as const).map((mode) => ({ value: mode, label: t(`compression.modes.${mode}`) }))}
        />
        <p className="text-xs text-muted-foreground">{t("compression.mode_desc")}</p>
        <p className="text-xs text-muted-foreground">{t("compression.unknown_window")}</p>
      </section>

      <section className="space-y-4 rounded-xl border p-3">
        <PercentSlider
          label={t("compression.trigger", { percent: trigger })}
          min={config.minTriggerPercent}
          max={config.maxTriggerPercent}
          value={trigger}
          onDraft={(value) => setDrafts((d) => ({ ...d, trigger: value }))}
          onCommit={(value) => {
            setDrafts((d) => ({ ...d, trigger: undefined }));
            const patch: CompressionConfigPatch = { triggerPercent: value };
            if (config.targetPercent > value - 5) patch.targetPercent = value - 5;
            void apply(patch);
          }}
          testId="compression-trigger"
        />
        <PercentSlider
          label={t("compression.target", { percent: target })}
          hint={t("compression.target_desc")}
          min={config.minTargetPercent}
          max={config.triggerPercent - 5}
          value={target}
          onDraft={(value) => setDrafts((d) => ({ ...d, target: value }))}
          onCommit={(value) => {
            setDrafts((d) => ({ ...d, target: undefined }));
            void apply({ targetPercent: value });
          }}
          testId="compression-target"
        />
        <PercentSlider
          label={t("compression.keep_recent", { count: keep })}
          hint={t("compression.keep_recent_desc")}
          min={0}
          max={30}
          value={keep}
          onDraft={(value) => setDrafts((d) => ({ ...d, keep: value }))}
          onCommit={(value) => {
            setDrafts((d) => ({ ...d, keep: undefined }));
            void apply({ keepRecent: value });
          }}
          testId="compression-keep-recent"
        />
        <div className="space-y-2">
          <p className="text-sm">{t("compression.summary_tokens")}</p>
          <SettingsSegmented<string>
            value={String(config.summaryTokens)}
            onChange={(value) => void apply({ summaryTokens: Number(value) })}
            options={SUMMARY_TOKEN_PRESETS.map((value) => ({ value, label: `${Number(value) / 1024}K` }))}
          />
        </div>
      </section>

      <section className="space-y-3 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("compression.model")}</h2>
        <SettingsSelectField
          label={t("compression.model")}
          value={config.modelId ?? CHAT_MODEL}
          onValueChange={(value) => void apply({ modelId: value === CHAT_MODEL ? null : value })}
          options={[
            { value: CHAT_MODEL, label: t("compression.model_follow") },
            ...(config.modelId && !chatModels.some(({ model }) => model.id === config.modelId)
              ? [{ value: config.modelId, label: t("compression.model_missing") }] : []),
            ...chatModels.map(({ provider, model }) => ({
              value: model.id,
              label: `${provider.name} / ${getModelDisplayName(model.displayName, model.modelId)}`,
            })),
          ]}
        />
        {config.modelId && <p className="text-xs text-destructive">{t("compression.model_cache_warning")}</p>}
      </section>

      <section className="space-y-4 rounded-xl border p-3">
        <div className="space-y-1">
          <h2 className="text-base font-semibold">{t("compression.carry_title")}</h2>
          <p className="text-xs text-muted-foreground">{t("compression.carry_mode_desc")}</p>
        </div>
        <SettingsSegmented<CarryOriginalsMode>
          value={config.carryOriginalsMode}
          onChange={(carryOriginalsMode) => void apply({ carryOriginalsMode })}
          options={(["off", "recent", "auto"] as const).map((mode) => ({
            value: mode,
            label: t(`compression.carry_modes.${mode}`),
          }))}
        />
        <PercentSlider
          label={t("compression.carry_count", { count: carryCount })}
          min={config.minCarryCount}
          max={Math.min(30, config.maxCarryCount)}
          value={carryCount}
          disabled={config.carryOriginalsMode !== "recent"}
          onDraft={(value) => setDrafts((d) => ({ ...d, carryCount: value }))}
          onCommit={(value) => {
            setDrafts((d) => ({ ...d, carryCount: undefined }));
            void apply({ carryOriginalsCount: value });
          }}
          testId="compression-carry-count"
        />
        <div className="space-y-1">
          <SettingsSwitchRow
            label={t("compression.carry_replies")}
            checked={config.carryIncludeReplies}
            disabled={config.carryOriginalsMode === "off"}
            onCheckedChange={(carryIncludeReplies) => void apply({ carryIncludeReplies })}
          />
          <p className="text-xs text-muted-foreground">{t("compression.carry_replies_desc")}</p>
        </div>
      </section>

      <section className="space-y-4 rounded-xl border p-3">
        <div className="space-y-1">
          <h2 className="text-base font-semibold">{t("compression.chunk_title")}</h2>
          <p className="text-xs text-muted-foreground">{t("compression.chunk_desc")}</p>
        </div>
        <PercentSlider
          label={t("compression.max_requests", { count: maxRequests })}
          hint={t("compression.max_requests_desc")}
          min={config.minMaxChunkRequests}
          max={config.maxMaxChunkRequests}
          value={maxRequests}
          onDraft={(value) => setDrafts((d) => ({ ...d, maxRequests: value }))}
          onCommit={(value) => {
            setDrafts((d) => ({ ...d, maxRequests: undefined }));
            void apply({ maxChunkRequests: value });
          }}
          testId="compression-max-requests"
        />
        <SettingsSelectField
          label={t("compression.chunk_timeout")}
          value={config.chunkTimeoutSeconds ? String(config.chunkTimeoutSeconds) : FOLLOW_NETWORK}
          onValueChange={(value) =>
            void apply({ chunkTimeoutSeconds: value === FOLLOW_NETWORK ? null : Number(value) })
          }
          options={[
            { value: FOLLOW_NETWORK, label: t("compression.chunk_timeout_follow") },
            ...(config.chunkTimeoutSeconds && !CHUNK_TIMEOUT_PRESETS.includes(String(config.chunkTimeoutSeconds) as typeof CHUNK_TIMEOUT_PRESETS[number])
              ? [{
                  value: String(config.chunkTimeoutSeconds),
                  label: t("compression.chunk_timeout_seconds", { seconds: config.chunkTimeoutSeconds }),
                }]
              : []),
            ...CHUNK_TIMEOUT_PRESETS.map((seconds) => ({
              value: seconds,
              label: t("compression.chunk_timeout_seconds", { seconds: Number(seconds) }),
            })),
          ]}
        />
      </section>

      <section className="space-y-2 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("compression.templates_title")}</h2>
        {[...BUILT_IN_TEMPLATE_IDS, ...config.customTemplates.map((item) => item.id)].map((id) => {
          const custom = config.customTemplates.find((item) => item.id === id);
          return (
            <button
              key={id}
              type="button"
              className="flex w-full items-center justify-between gap-3 rounded-md border px-3 py-2 text-left text-sm hover:bg-muted"
              onClick={() => (custom ? setEditing(custom) : setViewing(id))}
              data-testid={`compression-template-${id}`}
            >
              <span>
                {templateName(config, id, t)}
                {custom ? null : <span className="ml-2 text-xs text-muted-foreground">{t("compression.builtin")}</span>}
              </span>
              {config.templateId === id ? (
                <span className="rounded-full bg-secondary px-2 py-0.5 text-xs">{t("compression.default")}</span>
              ) : null}
            </button>
          );
        })}
        {config.customTemplates.length < config.maxCustomTemplates ? (
          <Button variant="outline" size="sm" data-testid="compression-template-add"
            onClick={() => setEditing({ id: crypto.randomUUID(), name: "", prompt: "" })}>
            {t("compression.template_add")}
          </Button>
        ) : null}
      </section>

      {viewing ? (
        <Dialog open onOpenChange={(open) => { if (!open) setViewing(null); }}>
          <DialogContent className="max-w-2xl">
            <DialogHeader>
              <DialogTitle>{templateName(config, viewing, t)}</DialogTitle>
            </DialogHeader>
            <pre className="max-h-96 overflow-auto whitespace-pre-wrap text-xs">
              {config.builtInTemplates.find((item) => item.id === viewing)?.prompt}
            </pre>
            <DialogFooter>
              {config.templateId !== viewing ? (
                <Button variant="outline" onClick={() => void apply({ templateId: viewing }).then(() => setViewing(null))}>
                  {t("compression.set_default")}
                </Button>
              ) : null}
              <Button onClick={() => {
                const prompt = config.builtInTemplates.find((item) => item.id === viewing)?.prompt ?? "";
                setViewing(null);
                setEditing({ id: crypto.randomUUID(), name: "", prompt });
              }}>{t("compression.template_copy")}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}

      {editing ? (
        <TemplateEditor
          template={editing}
          isNew={!config.customTemplates.some((item) => item.id === editing.id)}
          isDefault={config.templateId === editing.id}
          maxName={config.maxTemplateName}
          maxPrompt={config.maxTemplatePrompt}
          onSave={(saved, makeDefault) => {
            const exists = config.customTemplates.some((item) => item.id === saved.id);
            const templates = exists
              ? config.customTemplates.map((item) => (item.id === saved.id ? saved : item))
              : [...config.customTemplates, saved];
            void saveTemplates(templates, makeDefault ? saved.id : undefined).then((ok) => { if (ok) setEditing(null); });
          }}
          onDelete={() => {
            void saveTemplates(config.customTemplates.filter((item) => item.id !== editing.id))
              .then((ok) => { if (ok) setEditing(null); });
          }}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </div>
  );
}

function PercentSlider({
  label,
  hint,
  min,
  max,
  value,
  disabled,
  onDraft,
  onCommit,
  testId,
}: {
  label: string;
  hint?: string;
  min: number;
  max: number;
  value: number;
  disabled?: boolean;
  onDraft: (value: number) => void;
  onCommit: (value: number) => void;
  testId: string;
}) {
  return (
    <div className="space-y-2" data-testid={testId}>
      <p className={disabled ? "text-sm text-muted-foreground" : "text-sm"}>{label}</p>
      <Slider
        min={min}
        max={max}
        step={1}
        disabled={disabled}
        value={[value]}
        onValueChange={(values) => onDraft(values[0] ?? value)}
        onValueCommit={(values) => onCommit(values[0] ?? value)}
      />
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

function TemplateEditor({
  template,
  isNew,
  isDefault,
  maxName,
  maxPrompt,
  onSave,
  onDelete,
  onClose,
}: {
  template: CompressionTemplate;
  isNew: boolean;
  isDefault: boolean;
  maxName: number;
  maxPrompt: number;
  onSave: (template: CompressionTemplate, makeDefault: boolean) => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("settings");
  const [name, setName] = React.useState(template.name);
  const [prompt, setPrompt] = React.useState(template.prompt);
  const valid = name.trim().length > 0 && prompt.trim().length > 0;
  const saved = () => ({ ...template, name: name.trim(), prompt: prompt.trim() });
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{isNew ? t("compression.template_add") : template.name}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <Input value={name} maxLength={maxName} placeholder={t("compression.template_name")}
            onChange={(event) => setName(event.target.value)} data-testid="compression-template-name" />
          <Textarea value={prompt} maxLength={maxPrompt} rows={12} className="font-mono text-xs"
            placeholder={t("compression.template_prompt")}
            onChange={(event) => setPrompt(event.target.value)} data-testid="compression-template-prompt" />
          <p className="text-xs text-muted-foreground">{t("compression.template_prompt_hint")}</p>
        </div>
        <DialogFooter className="gap-2">
          {!isNew ? (
            <Button variant="ghost" className="text-destructive" onClick={onDelete}>{t("common.delete")}</Button>
          ) : null}
          {!isDefault ? (
            <Button variant="outline" disabled={!valid} onClick={() => onSave(saved(), true)}>
              {t("compression.set_default")}
            </Button>
          ) : null}
          <Button disabled={!valid} onClick={() => onSave(saved(), false)} data-testid="compression-template-save">
            {t("common.save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
