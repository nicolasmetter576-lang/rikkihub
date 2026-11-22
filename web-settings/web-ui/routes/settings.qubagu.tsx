import * as React from "react";

import { ChevronDown, ChevronUp, Download, MoreVertical, Plus, Trash2, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "~/components/ui/dropdown-menu";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { getModelDisplayName } from "~/lib/display";
import { useSettingsStore } from "~/stores";
import {
  SettingsField,
  SettingsPageHeader,
  SettingsSegmented,
  SettingsSelectField,
} from "@overlay/components/settings/settings-ui";
import { listChatModels } from "@overlay/lib/settings";
import {
  QUBAGU_IMPORT_CHOICES,
  QUBAGU_LIMITS,
  QUBAGU_MODES,
  fetchQubaguConfig,
  importQubaguRules,
  mapPack,
  movePack,
  newQuotaRule,
  newReplaceRule,
  newRulePack,
  parseQuotaWords,
  parseReplacements,
  qubaguExportUrl,
  qubaguOnOneLine,
  qubaguRuleLabelKey,
  qubaguRuleName,
  testQubagu,
  updateQubaguConfig,
  type QubaguConfig,
  type QubaguConfigPatch,
  type QubaguImportChoice,
  type QubaguImportResult,
  type QubaguRule,
  type QubaguRulePack,
  type QubaguTestResult,
} from "@overlay/lib/qubagu";

const REPLY_MODEL = "__reply__";

function SwitchLine({
  label,
  hint,
  checked,
  onChange,
  testId,
  disabled,
}: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  testId?: string;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <p className="text-sm">{label}</p>
        {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      </div>
      <Switch checked={checked} onCheckedChange={onChange} data-testid={testId} disabled={disabled} />
    </div>
  );
}

/** 失焦时保存的整数；超出范围不保存、恢复原值。 */
function NumberLine({
  label,
  value,
  min,
  max,
  onCommit,
  testId,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  onCommit: (value: number) => void;
  testId: string;
}) {
  const [draft, setDraft] = React.useState(String(value));
  React.useEffect(() => setDraft(String(value)), [value]);
  return (
    <SettingsField label={`${label} (${min}–${max})`}>
      <Input
        type="number"
        min={min}
        max={max}
        value={draft}
        data-testid={testId}
        className="w-28"
        onChange={event => setDraft(event.target.value)}
        onBlur={() => {
          const parsed = Number(draft);
          if (draft.trim() !== "" && Number.isInteger(parsed) && parsed >= min && parsed <= max) {
            if (parsed !== value) onCommit(parsed);
          } else {
            setDraft(String(value));
          }
        }}
      />
    </SettingsField>
  );
}

function ruleTitle(rule: QubaguRule): string {
  return rule.name || (rule.kind === "replace" ? rule.find : rule.id);
}

function RuleSummary({ rule }: { rule: QubaguRule }) {
  const { t } = useTranslation("settings");
  if (rule.kind === "quota") return <>{t("qubagu.rule_quota_summary", { max: rule.max, words: rule.words.join("、") })}</>;
  const replacements = rule.replacements.length > 0 ? rule.replacements.join(" / ") : t("qubagu.deletes");
  return <>{rule.sample ? `${rule.sample} → ${replacements}` : `→ ${replacements}`}</>;
}

/** 一个规则包：开关、菜单（编辑、上下移、导出、删除）；点开看每条规则，逐条开关、点一条编辑、添加规则。 */
function PackCard({
  pack,
  index,
  count,
  canAddRule,
  setPacks,
  onEditRule,
  onEdit,
  onDelete,
}: {
  pack: QubaguRulePack;
  index: number;
  count: number;
  canAddRule: boolean;
  setPacks: (transform: (packs: QubaguRulePack[]) => QubaguRulePack[]) => void;
  onEditRule: (rule: QubaguRule, isNew: boolean) => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation("settings");
  const [open, setOpen] = React.useState(false);
  const active = pack.rules.filter(rule => rule.enabled).length;
  return (
    <div className="rounded-lg border" data-testid={`qubagu-pack-${pack.id}`}>
      <div className="flex items-start justify-between gap-3 p-3">
        <button type="button" className="flex min-w-0 flex-1 items-start gap-2 text-left" onClick={() => setOpen(value => !value)}>
          {open ? <ChevronUp className="mt-0.5 size-4 shrink-0" /> : <ChevronDown className="mt-0.5 size-4 shrink-0" />}
          <span className="min-w-0">
            <span className="block text-sm font-medium">{pack.name || pack.id}</span>
            {pack.description ? (
              <span className={open ? "block text-xs text-muted-foreground" : "block truncate text-xs text-muted-foreground"}>{pack.description}</span>
            ) : null}
            <span className="block text-xs text-muted-foreground">{t("qubagu.pack_rule_count", { total: pack.rules.length, active })}</span>
          </span>
        </button>
        <div className="flex items-center gap-1">
          <Switch
            checked={pack.enabled}
            data-testid={`qubagu-pack-switch-${pack.id}`}
            onCheckedChange={enabled => setPacks(packs => mapPack(packs, pack.id, item => ({ ...item, enabled })))}
          />
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon-sm" aria-label={t("qubagu.pack_menu")} data-testid={`qubagu-pack-menu-${pack.id}`}>
                <MoreVertical className="size-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={onEdit}>{t("qubagu.pack_edit")}</DropdownMenuItem>
              {index > 0 ? <DropdownMenuItem onSelect={() => setPacks(packs => movePack(packs, pack.id, -1))}>{t("qubagu.move_up")}</DropdownMenuItem> : null}
              {index < count - 1 ? (
                <DropdownMenuItem onSelect={() => setPacks(packs => movePack(packs, pack.id, 1))}>{t("qubagu.move_down")}</DropdownMenuItem>
              ) : null}
              <DropdownMenuItem asChild>
                <a href={qubaguExportUrl(pack.id)} download>{t("qubagu.pack_export")}</a>
              </DropdownMenuItem>
              <DropdownMenuItem className="text-destructive" onSelect={onDelete} data-testid={`qubagu-pack-delete-${pack.id}`}>
                {t("common.delete")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>
      {open ? (
        <div className="border-t">
          <ul className="divide-y">
            {pack.rules.map(rule => (
              <li key={rule.id} className="flex items-center justify-between gap-3 px-3 py-2" data-testid="qubagu-rule">
                <button type="button" className="min-w-0 flex-1 text-left" onClick={() => onEditRule(rule, false)}>
                  <span className="block truncate text-sm">{ruleTitle(rule)}</span>
                  <span className="block truncate text-xs text-muted-foreground"><RuleSummary rule={rule} /></span>
                </button>
                <Switch
                  checked={rule.enabled}
                  disabled={!pack.enabled}
                  onCheckedChange={enabled =>
                    setPacks(packs => mapPack(packs, pack.id, item => ({ ...item, rules: item.rules.map(r => (r.id === rule.id ? { ...r, enabled } : r)) })))
                  }
                />
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap gap-2 p-3">
            <Button variant="outline" size="sm" disabled={!canAddRule} data-testid="qubagu-rule-add" onClick={() => onEditRule(newReplaceRule(), true)}>
              <Plus className="size-4" />
              {t("qubagu.rule_add_replace")}
            </Button>
            <Button variant="outline" size="sm" disabled={!canAddRule} data-testid="qubagu-rule-add-quota" onClick={() => onEditRule(newQuotaRule(), true)}>
              <Plus className="size-4" />
              {t("qubagu.rule_add_quota")}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** 编辑的是草稿，点保存才发请求；正则写错由服务端报回来。 */
function RuleDialog({
  rule,
  isNew,
  onSave,
  onDelete,
  onClose,
}: {
  rule: QubaguRule;
  isNew: boolean;
  onSave: (rule: QubaguRule) => Promise<boolean>;
  onDelete: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("settings");
  const [draft, setDraft] = React.useState(rule);
  const [list, setList] = React.useState(rule.kind === "replace" ? rule.replacements.join(" / ") : rule.words.join("、"));
  const [saving, setSaving] = React.useState(false);
  const cleaned: QubaguRule =
    draft.kind === "replace"
      ? { ...draft, name: draft.name.trim(), refinePrompt: draft.refinePrompt.trim(), find: draft.regex ? draft.find.trim() : draft.find, replacements: parseReplacements(list) }
      : { ...draft, name: draft.name.trim(), refinePrompt: draft.refinePrompt.trim(), words: parseQuotaWords(list) };
  const incomplete = cleaned.kind === "replace" ? cleaned.find.trim() === "" : cleaned.words.length === 0;
  return (
    <Dialog open onOpenChange={open => { if (!open) onClose(); }}>
      <DialogContent className="max-h-[90svh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t(isNew ? "qubagu.custom_add" : "qubagu.custom_edit")}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <SettingsField label={t("qubagu.rule_name")}>
            <Input value={draft.name} maxLength={QUBAGU_LIMITS.nameChars} data-testid="qubagu-rule-name"
              onChange={event => setDraft({ ...draft, name: event.target.value })} />
          </SettingsField>
          {draft.kind === "replace" ? (
            <>
              <SettingsField label={t("qubagu.rule_find")}>
                <Textarea value={draft.find} rows={2} maxLength={QUBAGU_LIMITS.findChars} data-testid="qubagu-rule-find"
                  onChange={event => setDraft({ ...draft, find: event.target.value })} />
              </SettingsField>
              <SettingsField label={t("qubagu.rule_replacements")} hint={t("qubagu.rule_replacements_hint")}>
                <Textarea value={list} rows={2} data-testid="qubagu-rule-replacements" onChange={event => setList(event.target.value)} />
              </SettingsField>
              <SettingsField label={t("qubagu.rule_sample")}>
                <Input value={draft.sample} maxLength={QUBAGU_LIMITS.sampleChars} onChange={event => setDraft({ ...draft, sample: event.target.value })} />
              </SettingsField>
              <SwitchLine label={t("qubagu.rule_regex")} hint={t("qubagu.rule_regex_hint")} checked={draft.regex}
                onChange={regex => setDraft({ ...draft, regex })} testId="qubagu-rule-regex" />
              <SwitchLine label={t("qubagu.rule_dialogue")} checked={draft.dialogue} onChange={dialogue => setDraft({ ...draft, dialogue })} />
              <SwitchLine label={t("qubagu.rule_keep_first")} hint={t("qubagu.rule_keep_first_hint")} checked={draft.keepFirst}
                onChange={keepFirst => setDraft({ ...draft, keepFirst })} />
              <SwitchLine label={t("qubagu.rule_at_end")} hint={t("qubagu.rule_at_end_hint")} checked={draft.atEnd}
                onChange={atEnd => setDraft({ ...draft, atEnd })} testId="qubagu-rule-at-end" />
            </>
          ) : (
            <>
              <SettingsField label={t("qubagu.quota_words")} hint={t("qubagu.quota_words_hint")}>
                <Textarea value={list} rows={2} data-testid="qubagu-rule-words" onChange={event => setList(event.target.value)} />
              </SettingsField>
              <NumberLine label={t("qubagu.quota_max")} value={draft.max} {...QUBAGU_LIMITS.quotaMax}
                onCommit={max => setDraft({ ...draft, max })} testId="qubagu-rule-quota-max" />
            </>
          )}
          <SettingsField label={t("qubagu.rule_refine_prompt")} hint={t("qubagu.rule_refine_prompt_hint")}>
            <Textarea value={draft.refinePrompt} rows={3} maxLength={QUBAGU_LIMITS.ruleRefinePromptChars} data-testid="qubagu-rule-refine-prompt"
              onChange={event => setDraft({ ...draft, refinePrompt: event.target.value })} />
          </SettingsField>
        </div>
        <DialogFooter>
          {isNew ? null : (
            <Button variant="ghost" className="mr-auto text-destructive" onClick={onDelete} data-testid="qubagu-rule-delete">
              <Trash2 className="size-4" />
              {t("common.delete")}
            </Button>
          )}
          <Button variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
          <Button
            disabled={saving || incomplete}
            data-testid="qubagu-rule-save"
            onClick={() => {
              setSaving(true);
              void onSave(cleaned).then(ok => {
                setSaving(false);
                if (ok) onClose();
              });
            }}
          >
            {t("common.save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PackDialog({
  pack,
  isNew,
  onSave,
  onClose,
}: {
  pack: QubaguRulePack;
  isNew: boolean;
  onSave: (name: string, description: string) => Promise<boolean>;
  onClose: () => void;
}) {
  const { t } = useTranslation("settings");
  const [name, setName] = React.useState(pack.name);
  const [description, setDescription] = React.useState(pack.description);
  return (
    <Dialog open onOpenChange={open => { if (!open) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t(isNew ? "qubagu.pack_new" : "qubagu.pack_edit")}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <SettingsField label={t("qubagu.pack_name")}>
            <Input value={name} maxLength={QUBAGU_LIMITS.nameChars} data-testid="qubagu-pack-name" onChange={event => setName(event.target.value)} />
          </SettingsField>
          <SettingsField label={t("qubagu.pack_description")}>
            <Textarea value={description} rows={3} maxLength={QUBAGU_LIMITS.descriptionChars} onChange={event => setDescription(event.target.value)} />
          </SettingsField>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
          <Button
            disabled={name.trim() === ""}
            data-testid="qubagu-pack-save"
            onClick={() => void onSave(name.trim(), description.trim()).then(ok => { if (ok) onClose(); })}
          >
            {t("common.save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** 导入的文件里有和已有规则包同 id 的包：逐个选覆盖、另存副本或跳过（默认覆盖）。 */
function ImportConflictDialog({
  conflicts,
  onConfirm,
  onClose,
}: {
  conflicts: QubaguImportResult["packs"];
  onConfirm: (choices: Record<string, QubaguImportChoice>) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("settings");
  const [choices, setChoices] = React.useState<Record<string, QubaguImportChoice>>({});
  return (
    <Dialog open onOpenChange={open => { if (!open) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("qubagu.import_conflict_title")}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          {conflicts.map(pack => (
            <div key={pack.id} className="space-y-1.5">
              <p className="text-sm font-medium">{pack.name || pack.id}</p>
              <SettingsSegmented
                value={choices[pack.id] ?? "OVERWRITE"}
                onChange={choice => setChoices(current => ({ ...current, [pack.id]: choice }))}
                options={QUBAGU_IMPORT_CHOICES.map(choice => ({ value: choice, label: t(`qubagu.import_choice.${choice}`) }))}
              />
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
          <Button data-testid="qubagu-import-confirm" onClick={() => onConfirm(choices)}>{t("common.confirm")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function TestBox({ config }: { config: QubaguConfig }) {
  const { t } = useTranslation("settings");
  const [input, setInput] = React.useState("");
  const [result, setResult] = React.useState<QubaguTestResult | null>(null);
  const [running, setRunning] = React.useState(false);
  return (
    <section className="space-y-3 rounded-xl border p-3">
      <h2 className="text-base font-semibold">{t("qubagu.test_section")}</h2>
      <Textarea value={input} rows={5} placeholder={t("qubagu.test_input")} data-testid="qubagu-test-input"
        onChange={event => { setInput(event.target.value); setResult(null); }} />
      <Button
        size="sm"
        disabled={running || input.trim() === ""}
        data-testid="qubagu-test-run"
        onClick={() => {
          setRunning(true);
          testQubagu(input)
            .then(setResult)
            .catch(error => toast.error(extractErrorMessage(error, t("common.save_failed"))))
            .finally(() => setRunning(false));
        }}
      >
        {t("qubagu.test_run")}
      </Button>
      {result ? (
        <div className="space-y-2" data-testid="qubagu-test-result">
          <p className="text-sm font-medium">
            {result.edits.length === 0 ? t("qubagu.none_found") : t("qubagu.test_found", { count: result.edits.length })}
          </p>
          {result.edits.length > 0 ? (
            <>
              <p className="whitespace-pre-wrap rounded-md bg-muted p-3 text-sm" data-testid="qubagu-test-output">{result.text}</p>
              <ul className="space-y-1.5">
                {result.edits.map((edit, index) => (
                  <li key={index} className="text-sm">
                    <span className="block text-xs text-muted-foreground">{qubaguRuleName(edit.rule, config) ?? t(qubaguRuleLabelKey(edit.rule))}</span>
                    <span className="text-muted-foreground line-through">{qubaguOnOneLine(edit.before)}</span>
                    {" → "}
                    <span className="text-primary">{qubaguOnOneLine(edit.after) || t("qubagu.deletes")}</span>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

/** 去八股设置（与 App「设置 → 写作 → 去八股」同一份配置）。聊天里的总开关在输入框的「+」浮层。 */
export default function SettingsQubaguPage() {
  const { t } = useTranslation("settings");
  const settings = useSettingsStore(s => s.settings);
  const [source, setSource] = React.useState<{ config: QubaguConfig; defaults: { refinePrompt: string } } | null>(null);
  const [editingRule, setEditingRule] = React.useState<{ packId: string; rule: QubaguRule; isNew: boolean } | null>(null);
  const [editingPack, setEditingPack] = React.useState<{ pack: QubaguRulePack; isNew: boolean } | null>(null);
  const [deletingPack, setDeletingPack] = React.useState<QubaguRulePack | null>(null);
  const [pendingImport, setPendingImport] = React.useState<{ text: string; preview: QubaguImportResult } | null>(null);
  const [invalidRules, setInvalidRules] = React.useState<string[]>([]);
  const [promptDraft, setPromptDraft] = React.useState<string | undefined>(undefined);
  const fileInput = React.useRef<HTMLInputElement>(null);

  React.useEffect(() => {
    let mounted = true;
    fetchQubaguConfig()
      .then(value => { if (mounted) setSource(value); })
      .catch(error => toast.error(extractErrorMessage(error, t("common.save_failed"))));
    return () => { mounted = false; };
  }, [t]);

  const apply = React.useCallback(
    (patch: QubaguConfigPatch) =>
      updateQubaguConfig(patch)
        .then(next => { setSource(next); return true; })
        .catch(error => { toast.error(extractErrorMessage(error, t("common.save_failed"))); return false; }),
    [t],
  );

  const finishImport = (text: string, choices: Record<string, QubaguImportChoice>) =>
    importQubaguRules(text, { choices })
      .then(result => {
        if (result.config) setSource(result.config);
        const imported = result.packs.filter(pack => choices[pack.id] !== "SKIP");
        toast.success(t("qubagu.import_done", { packs: imported.length, rules: imported.reduce((sum, pack) => sum + pack.rules, 0) }));
        if (imported.length > 0) setInvalidRules(result.invalid);
      })
      .catch(error => toast.error(extractErrorMessage(error, t("qubagu.import_failed"))));

  const startImport = (file: File) =>
    file
      .text()
      .then(text => importQubaguRules(text, { preview: true }).then(preview => ({ text, preview })))
      .then(({ text, preview }) => {
        if (preview.packs.some(pack => pack.conflict)) setPendingImport({ text, preview });
        else return finishImport(text, {});
      })
      .catch(error => toast.error(extractErrorMessage(error, t("qubagu.import_failed"))));

  if (!source) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("qubagu.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  const { config, defaults } = source;
  const fire = (patch: QubaguConfigPatch) => void apply(patch);
  const setPacks = (transform: (packs: QubaguRulePack[]) => QubaguRulePack[]) => apply({ packs: transform(config.packs) });
  const chatModels = settings ? listChatModels(settings) : [];
  const modelMissing = config.refineModelId !== null && !chatModels.some(({ model }) => model.id === config.refineModelId);
  const savedPrompt = config.refinePrompt ?? defaults.refinePrompt;
  const promptValue = promptDraft ?? savedPrompt;
  const ruleCount = config.packs.reduce((sum, pack) => sum + pack.rules.length, 0);

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("qubagu.title")} description={t("qubagu.description")} />

      <section className="space-y-3 rounded-xl border p-3">
        <SwitchLine label={t("qubagu.enabled")} hint={t("qubagu.enabled_hint")} checked={config.enabled}
          onChange={enabled => fire({ enabled })} testId="qubagu-enabled" />
      </section>

      <section className="space-y-3 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("qubagu.mode_section")}</h2>
        <SettingsSegmented
          value={config.mode}
          onChange={mode => fire({ mode })}
          options={QUBAGU_MODES.map(mode => ({ value: mode, label: t(`qubagu.mode.${mode}`) }))}
        />
        <p className="text-xs text-muted-foreground">{t(`qubagu.mode_hint.${config.mode}`)}</p>
      </section>

      <section className="space-y-3 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("qubagu.protect_section")}</h2>
        <SwitchLine label={t("qubagu.protect_dialogue")} hint={t("qubagu.protect_dialogue_hint")} checked={config.protectDialogue}
          onChange={protectDialogue => fire({ protectDialogue })} testId="qubagu-protect-dialogue" />
        <SwitchLine label={t("qubagu.protect_corner")} checked={config.protectCornerQuotes}
          onChange={protectCornerQuotes => fire({ protectCornerQuotes })} />
        <SwitchLine label={t("qubagu.protect_details")} hint={t("qubagu.protect_details_hint")} checked={config.protectDetails}
          onChange={protectDetails => fire({ protectDetails })} />
      </section>

      <section className="space-y-3 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("qubagu.syntax_section")}</h2>
        <SwitchLine label={t("qubagu.merge")} hint={t("qubagu.merge_hint")} checked={config.merge}
          onChange={merge => fire({ merge })} testId="qubagu-merge" />
        {config.merge ? (
          <div className="border-l-2 pl-3">
            <NumberLine label={t("qubagu.merge_max_chars")} value={config.mergeMaxChars} {...QUBAGU_LIMITS.mergeMaxChars}
              onCommit={mergeMaxChars => fire({ mergeMaxChars })} testId="qubagu-merge-max" />
          </div>
        ) : null}
      </section>

      <section className="space-y-3 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("qubagu.packs_section")}</h2>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" data-testid="qubagu-import" onClick={() => fileInput.current?.click()}>
            <Upload className="size-4" />
            {t("qubagu.import")}
          </Button>
          {config.packs.length > 0 ? (
            <Button variant="outline" size="sm" asChild data-testid="qubagu-export">
              <a href={qubaguExportUrl()} download>
                <Download className="size-4" />
                {t("qubagu.export_all")}
              </a>
            </Button>
          ) : null}
          <Button variant="outline" size="sm" disabled={config.packs.length >= QUBAGU_LIMITS.packs} data-testid="qubagu-pack-new"
            onClick={() => setEditingPack({ pack: newRulePack("", ""), isNew: true })}>
            <Plus className="size-4" />
            {t("qubagu.pack_new")}
          </Button>
          <input
            ref={fileInput}
            type="file"
            accept=".json,application/json,text/plain"
            className="hidden"
            data-testid="qubagu-import-file"
            onChange={event => {
              const file = event.target.files?.[0];
              event.target.value = "";
              if (file) void startImport(file);
            }}
          />
        </div>
        {config.packs.length === 0 ? <p className="text-xs text-muted-foreground">{t("qubagu.rules_empty")}</p> : null}
        {config.packs.map((pack, index) => (
          <PackCard
            key={pack.id}
            pack={pack}
            index={index}
            count={config.packs.length}
            canAddRule={ruleCount < QUBAGU_LIMITS.rules}
            setPacks={transform => void setPacks(transform)}
            onEditRule={(rule, isNew) => setEditingRule({ packId: pack.id, rule, isNew })}
            onEdit={() => setEditingPack({ pack, isNew: false })}
            onDelete={() => setDeletingPack(pack)}
          />
        ))}
      </section>

      <section className="space-y-3 rounded-xl border p-3">
        <h2 className="text-base font-semibold">{t("qubagu.refine_section")}</h2>
        <SwitchLine label={t("qubagu.refine_auto")} hint={t("qubagu.refine_auto_hint")} checked={config.refine}
          onChange={refine => fire({ refine })} testId="qubagu-refine-auto" />
        <SettingsSelectField
          label={t("qubagu.refine_model")}
          value={config.refineModelId ?? REPLY_MODEL}
          onValueChange={value => fire({ refineModelId: value === REPLY_MODEL ? null : value })}
          options={[
            { value: REPLY_MODEL, label: t("qubagu.refine_model_reply") },
            ...(modelMissing && config.refineModelId ? [{ value: config.refineModelId, label: t("qubagu.refine_model_missing") }] : []),
            ...chatModels.map(({ provider, model }) => ({
              value: model.id,
              label: `${provider.name} / ${getModelDisplayName(model.displayName, model.modelId)}`,
            })),
          ]}
        />
        {modelMissing ? <p className="text-xs text-destructive">{t("qubagu.refine_model_missing")}</p> : null}
        <SettingsField label={t("qubagu.refine_prompt")} hint={t("qubagu.refine_prompt_hint")}>
          <Textarea value={promptValue} rows={8} maxLength={QUBAGU_LIMITS.refinePromptChars} data-testid="qubagu-refine-prompt"
            onChange={event => setPromptDraft(event.target.value)} />
          <div className="flex justify-end gap-2">
            {config.refinePrompt ? (
              <Button variant="ghost" size="sm" onClick={() => void apply({ refinePrompt: null }).then(ok => { if (ok) setPromptDraft(undefined); })}>
                {t("qubagu.reset")}
              </Button>
            ) : null}
            <Button size="sm" disabled={promptDraft === undefined || promptDraft === savedPrompt}
              onClick={() => void apply({ refinePrompt: promptValue }).then(ok => { if (ok) setPromptDraft(undefined); })}>
              {t("common.save")}
            </Button>
          </div>
        </SettingsField>
      </section>

      <TestBox config={config} />

      {editingRule ? (
        <RuleDialog
          key={editingRule.rule.id}
          rule={editingRule.rule}
          isNew={editingRule.isNew}
          onSave={rule =>
            setPacks(packs =>
              mapPack(packs, editingRule.packId, pack => ({
                ...pack,
                rules: pack.rules.some(item => item.id === rule.id) ? pack.rules.map(item => (item.id === rule.id ? rule : item)) : [...pack.rules, rule],
              })),
            )
          }
          onDelete={() => {
            void setPacks(packs => mapPack(packs, editingRule.packId, pack => ({ ...pack, rules: pack.rules.filter(item => item.id !== editingRule.rule.id) })));
            setEditingRule(null);
          }}
          onClose={() => setEditingRule(null)}
        />
      ) : null}
      {editingPack ? (
        <PackDialog
          key={editingPack.pack.id}
          pack={editingPack.pack}
          isNew={editingPack.isNew}
          onSave={(name, description) =>
            setPacks(packs =>
              editingPack.isNew
                ? [...packs, { ...editingPack.pack, name, description }]
                : mapPack(packs, editingPack.pack.id, pack => ({ ...pack, name, description })),
            )
          }
          onClose={() => setEditingPack(null)}
        />
      ) : null}
      {deletingPack ? (
        <Dialog open onOpenChange={open => { if (!open) setDeletingPack(null); }}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{t("common.delete")}</DialogTitle>
              <DialogDescription>{t("qubagu.pack_delete_confirm", { name: deletingPack.name || deletingPack.id, count: deletingPack.rules.length })}</DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline" onClick={() => setDeletingPack(null)}>{t("common.cancel")}</Button>
              <Button
                variant="destructive"
                data-testid="qubagu-pack-delete-confirm"
                onClick={() => {
                  void setPacks(packs => packs.filter(pack => pack.id !== deletingPack.id));
                  setDeletingPack(null);
                }}
              >
                {t("common.delete")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}
      {pendingImport ? (
        <ImportConflictDialog
          conflicts={pendingImport.preview.packs.filter(pack => pack.conflict)}
          onConfirm={choices => {
            const { text } = pendingImport;
            setPendingImport(null);
            void finishImport(text, choices);
          }}
          onClose={() => setPendingImport(null)}
        />
      ) : null}
      {invalidRules.length > 0 ? (
        <Dialog open onOpenChange={open => { if (!open) setInvalidRules([]); }}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{t("qubagu.import_invalid_title")}</DialogTitle>
            </DialogHeader>
            <ul className="space-y-1 text-sm">
              {invalidRules.map(name => <li key={name}>{name}</li>)}
            </ul>
            <DialogFooter>
              <Button onClick={() => setInvalidRules([])}>{t("common.confirm")}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}
    </div>
  );
}
