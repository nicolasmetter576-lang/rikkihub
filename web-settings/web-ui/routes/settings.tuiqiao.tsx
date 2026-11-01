import * as React from "react";

import { ArrowDown, ArrowUp, Pencil, Trash2, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { getModelDisplayName } from "~/lib/display";
import { cn } from "~/lib/utils";
import { useSettingsStore } from "~/stores";
import { SettingsField, SettingsPageHeader, SettingsSelectField } from "@overlay/components/settings/settings-ui";
import { listChatModels } from "@overlay/lib/settings";
import {
  fetchTuiqiaoConfig,
  TUIQIAO_EXPLANATIONS,
  TUIQIAO_MODES,
  tuiqiaoRolesPatch,
  updateTuiqiaoConfig,
  type TuiqiaoConfig,
  type TuiqiaoConfigPatch,
  type TuiqiaoMode,
  type TuiqiaoRole,
} from "@overlay/lib/tuiqiao";

const ASSISTANT_MODEL = "__assistant__";

type RoleDraft = Omit<TuiqiaoRole, "id" | "modelMissing"> & { id?: string };

const emptyRole = (): RoleDraft => ({ name: "", prompt: "", modes: ["SENTENCE", "PARAGRAPH"], modelId: null, enabled: true });

/** 推敲设置（与 App「设置 → 推敲」同一份配置）：候选、推敲角色、文件类型、默认改写提示词。 */
export default function SettingsTuiqiaoPage() {
  const { t } = useTranslation("settings");
  const settings = useSettingsStore(s => s.settings);
  const [config, setConfig] = React.useState<TuiqiaoConfig | null>(null);
  const [editing, setEditing] = React.useState<{ index: number | null; draft: RoleDraft } | null>(null);
  const [extInput, setExtInput] = React.useState("");
  const [extInvalid, setExtInvalid] = React.useState<string | null>(null);
  const [promptDrafts, setPromptDrafts] = React.useState<Partial<Record<TuiqiaoMode, string>>>({});

  React.useEffect(() => {
    let mounted = true;
    fetchTuiqiaoConfig()
      .then(value => { if (mounted) setConfig(value); })
      .catch(error => toast.error(extractErrorMessage(error, t("common.save_failed"))));
    return () => { mounted = false; };
  }, [t]);

  const apply = React.useCallback(
    (patch: TuiqiaoConfigPatch) =>
      updateTuiqiaoConfig(patch)
        .then(next => { setConfig(next); return true; })
        .catch(error => { toast.error(extractErrorMessage(error, t("common.save_failed"))); return false; }),
    [t],
  );

  if (!config) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("tuiqiao.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  const chatModels = settings ? listChatModels(settings) : [];
  const modelLabel = (role: TuiqiaoRole) => {
    if (!role.modelId) return t("tuiqiao.model_assistant");
    const found = chatModels.find(({ model }) => model.id === role.modelId);
    return found ? getModelDisplayName(found.model.displayName, found.model.modelId) : t("tuiqiao.model_assistant");
  };
  const saveRoles = (roles: TuiqiaoRole[]) => apply({ roles: tuiqiaoRolesPatch(roles) });
  const move = (index: number, delta: number) => {
    const roles = [...config.roles];
    const target = index + delta;
    if (target < 0 || target >= roles.length) return;
    [roles[index], roles[target]] = [roles[target]!, roles[index]!];
    void saveRoles(roles);
  };
  const saveDraft = () => {
    if (!editing) return;
    const draft = { ...editing.draft, name: editing.draft.name.trim(), prompt: editing.draft.prompt.trim() };
    if (!draft.name || !draft.prompt || draft.modes.length === 0) {
      toast.error(t("tuiqiao.role_invalid"));
      return;
    }
    const roles: TuiqiaoConfigPatch["roles"] = tuiqiaoRolesPatch(config.roles);
    if (editing.index === null) roles.push(draft);
    else roles[editing.index] = { ...draft, id: config.roles[editing.index]!.id };
    void apply({ roles }).then(ok => { if (ok) setEditing(null); });
  };
  const addExtension = () => {
    const raw = extInput.trim().replace(/^\.+/, "").toLowerCase();
    if (!raw) return;
    if (!config.availableExtensions.includes(raw)) {
      setExtInvalid(raw);
      return;
    }
    setExtInvalid(null);
    setExtInput("");
    if (!config.extensions.includes(raw)) void apply({ extensions: [...config.extensions, raw] });
  };
  const count = config.candidateCount;

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("tuiqiao.title")} description={t("tuiqiao.description")} />

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("tuiqiao.candidates")}</h2>
        <div className="flex flex-wrap items-center gap-2">
          <span className="w-24 text-sm text-muted-foreground">{t("tuiqiao.count")}</span>
          {Array.from({ length: config.maxCandidates - config.minCandidates + 1 }, (_, i) => config.minCandidates + i).map(value => (
            <Button key={value} size="sm" variant={count === value ? "default" : "outline"} onClick={() => void apply({ candidateCount: value })}>
              {value}
            </Button>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="w-24 text-sm text-muted-foreground">{t("tuiqiao.explanation")}</span>
          {TUIQIAO_EXPLANATIONS.map(explanation => (
            <Button key={explanation} size="sm" variant={config.explanation === explanation ? "default" : "outline"}
              onClick={() => void apply({ explanation })}>
              {t(`tuiqiao.explanations.${explanation}`)}
            </Button>
          ))}
        </div>
      </section>

      <section className="space-y-3 rounded-xl border p-4" data-testid="tuiqiao-roles">
        <h2 className="text-base font-semibold">{t("tuiqiao.roles")}</h2>
        <p className="text-xs text-muted-foreground">{t("tuiqiao.roles_hint")}</p>
        {config.roles.length === 0 ? <p className="text-sm text-muted-foreground">{t("tuiqiao.roles_empty")}</p> : null}
        {config.roles.map((role, index) => (
          <div key={role.id} className="flex items-center gap-2 rounded-md border px-3 py-2" data-testid={`tuiqiao-role-${index}`}>
            <div className="flex flex-col">
              <Button variant="ghost" size="icon" className="size-6" disabled={index === 0} aria-label={t("tuiqiao.move_up")}
                onClick={() => move(index, -1)}><ArrowUp className="size-3.5" /></Button>
              <Button variant="ghost" size="icon" className="size-6" disabled={index === config.roles.length - 1}
                aria-label={t("tuiqiao.move_down")} onClick={() => move(index, 1)}><ArrowDown className="size-3.5" /></Button>
            </div>
            <div className="min-w-0 flex-1 space-y-0.5">
              <p className="truncate text-sm font-medium">{role.name}</p>
              <p className="truncate text-xs text-muted-foreground">
                {[...TUIQIAO_MODES.filter(mode => role.modes.includes(mode)).map(mode => t(`tuiqiao.modes.${mode}`)), modelLabel(role)].join(" · ")}
              </p>
              {role.modelMissing ? <p className="text-xs text-destructive">{t("tuiqiao.model_missing")}</p> : null}
            </div>
            <Switch checked={role.enabled} aria-label={t("tuiqiao.enabled")}
              onCheckedChange={enabled => void saveRoles(config.roles.map((r, i) => (i === index ? { ...r, enabled } : r)))} />
            <Button variant="ghost" size="icon" aria-label={t("tuiqiao.edit_role")}
              onClick={() => setEditing({ index, draft: { ...role } })}><Pencil className="size-4" /></Button>
            <Button variant="ghost" size="icon" aria-label={t("tuiqiao.delete_role")}
              onClick={() => void saveRoles(config.roles.filter((_, i) => i !== index))}><Trash2 className="size-4" /></Button>
          </div>
        ))}
        {TUIQIAO_MODES.map(mode => {
          const matching = config.roles.filter(role => role.enabled && role.modes.includes(mode)).length;
          if (matching < count) return null;
          return (
            <p key={mode} className="text-xs text-amber-700 dark:text-amber-300">
              {t(matching > count ? "tuiqiao.overflow" : "tuiqiao.saturated", { mode: t(`tuiqiao.modes.${mode}`), count })}
            </p>
          );
        })}
        {editing ? (
          <div className="space-y-3 rounded-md border bg-muted/30 p-3" data-testid="tuiqiao-role-editor">
            <SettingsField label={t("tuiqiao.role_name")}>
              <Input value={editing.draft.name} maxLength={config.maxRoleNameChars} data-testid="tuiqiao-role-name"
                onChange={event => setEditing({ ...editing, draft: { ...editing.draft, name: event.target.value } })} />
            </SettingsField>
            <SettingsField label={t("tuiqiao.role_prompt")}>
              <Textarea value={editing.draft.prompt} maxLength={config.maxRolePromptChars} rows={4} data-testid="tuiqiao-role-prompt"
                placeholder={t("tuiqiao.role_prompt_hint")}
                onChange={event => setEditing({ ...editing, draft: { ...editing.draft, prompt: event.target.value } })} />
            </SettingsField>
            <div className="space-y-1.5">
              <p className="text-sm font-medium">{t("tuiqiao.role_modes")}</p>
              <div className="flex gap-2">
                {TUIQIAO_MODES.map(mode => {
                  const on = editing.draft.modes.includes(mode);
                  return (
                    <Button key={mode} size="sm" variant={on ? "default" : "outline"} data-testid={`tuiqiao-role-mode-${mode}`}
                      onClick={() => setEditing({
                        ...editing,
                        draft: { ...editing.draft, modes: on ? editing.draft.modes.filter(m => m !== mode) : [...editing.draft.modes, mode] },
                      })}>
                      {t(`tuiqiao.modes.${mode}`)}
                    </Button>
                  );
                })}
              </div>
            </div>
            <SettingsSelectField
              label={t("tuiqiao.role_model")}
              value={editing.draft.modelId ?? ASSISTANT_MODEL}
              onValueChange={value => setEditing({ ...editing, draft: { ...editing.draft, modelId: value === ASSISTANT_MODEL ? null : value } })}
              options={[
                { value: ASSISTANT_MODEL, label: t("tuiqiao.model_assistant") },
                ...(editing.draft.modelId && !chatModels.some(({ model }) => model.id === editing.draft.modelId)
                  ? [{ value: editing.draft.modelId, label: t("tuiqiao.model_missing") }] : []),
                ...chatModels.map(({ provider, model }) => ({
                  value: model.id,
                  label: `${provider.name} / ${getModelDisplayName(model.displayName, model.modelId)}`,
                })),
              ]}
            />
            <p className="text-xs text-muted-foreground">{t("tuiqiao.role_model_hint")}</p>
            <div className="flex items-center justify-between">
              <span className="text-sm">{t("tuiqiao.enabled")}</span>
              <Switch checked={editing.draft.enabled}
                onCheckedChange={enabled => setEditing({ ...editing, draft: { ...editing.draft, enabled } })} />
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={() => setEditing(null)}>{t("common.cancel")}</Button>
              <Button size="sm" onClick={saveDraft} data-testid="tuiqiao-role-save">{t("common.save")}</Button>
            </div>
          </div>
        ) : (
          <Button variant="outline" size="sm" disabled={config.roles.length >= config.maxRoles} data-testid="tuiqiao-role-add"
            onClick={() => setEditing({ index: null, draft: emptyRole() })}>
            {t("tuiqiao.add_role")}
          </Button>
        )}
        {config.roles.length >= config.maxRoles ? (
          <p className="text-xs text-muted-foreground">{t("tuiqiao.role_limit", { count: config.maxRoles })}</p>
        ) : null}
      </section>

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("tuiqiao.files")}</h2>
        <p className="text-xs text-muted-foreground">{t("tuiqiao.files_hint")}</p>
        <div className="flex flex-wrap gap-1.5">
          {config.extensions.map(ext => (
            <span key={ext} className="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs" data-testid={`tuiqiao-ext-${ext}`}>
              .{ext}
              <button type="button" aria-label={t("tuiqiao.remove_extension", { ext })} className="text-muted-foreground hover:text-foreground"
                onClick={() => void apply({ extensions: config.extensions.filter(e => e !== ext) })}>
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <Input value={extInput} placeholder={t("tuiqiao.extension_input")} className={cn("max-w-48", extInvalid && "border-destructive")}
            data-testid="tuiqiao-ext-input"
            onChange={event => { setExtInput(event.target.value); setExtInvalid(null); }}
            onKeyDown={event => { if (event.key === "Enter") addExtension(); }} />
          <Button variant="outline" size="sm" onClick={addExtension} data-testid="tuiqiao-ext-add">{t("tuiqiao.add_extension")}</Button>
          {config.extensionsCustomized ? (
            <Button variant="ghost" size="sm" onClick={() => void apply({ extensions: null })}>{t("tuiqiao.reset_extensions")}</Button>
          ) : null}
        </div>
        {extInvalid ? <p className="text-xs text-destructive">{t("tuiqiao.extension_invalid", { ext: extInvalid })}</p> : null}
      </section>

      <section className="space-y-3 rounded-xl border p-4">
        <h2 className="text-base font-semibold">{t("tuiqiao.prompts")}</h2>
        <p className="text-xs text-muted-foreground">{t("tuiqiao.prompts_hint")}</p>
        {TUIQIAO_MODES.map(mode => {
          const saved = config.prompts[mode] ?? config.defaultPrompts[mode] ?? "";
          const value = promptDrafts[mode] ?? saved;
          const dirty = promptDrafts[mode] !== undefined && promptDrafts[mode] !== saved;
          return (
            <SettingsField key={mode} label={t(`tuiqiao.modes.${mode}`)}>
              <Textarea value={value} rows={3} data-testid={`tuiqiao-prompt-${mode}`}
                onChange={event => setPromptDrafts(drafts => ({ ...drafts, [mode]: event.target.value }))} />
              <div className="flex justify-end gap-2">
                {config.prompts[mode] ? (
                  <Button variant="ghost" size="sm" onClick={() => void apply({ prompts: { [mode]: null } }).then(ok => {
                    if (ok) setPromptDrafts(drafts => ({ ...drafts, [mode]: undefined }));
                  })}>{t("tuiqiao.reset_prompt")}</Button>
                ) : null}
                <Button size="sm" disabled={!dirty} onClick={() => void apply({ prompts: { [mode]: value } }).then(ok => {
                  if (ok) setPromptDrafts(drafts => ({ ...drafts, [mode]: undefined }));
                })}>{t("common.save")}</Button>
              </div>
            </SettingsField>
          );
        })}
      </section>
    </div>
  );
}
