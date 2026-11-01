import * as React from "react";

import { useQuery } from "@tanstack/react-query";
import { Link, useLocation, useNavigate, useParams } from "react-router";
import { Copy, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import {
  ConfirmDeleteDialog,
  CreateNameDialog,
  SettingsCheckboxGroup,
  SettingsField,
  SettingsFormActions,
  SettingsPageHeader,
  SettingsSelectField,
  SettingsSwitchRow,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { AssistantMemoryPanel } from "@overlay/components/settings/assistant-memory-panel";
import { AssistantSystemPromptEditor } from "@overlay/components/settings/assistant-system-prompt-editor";
import { skillsApi, skillsQueryKey, type SkillSummary } from "@overlay/lib/skills";
import { getAssistantDisplayName, getModelDisplayName } from "~/lib/display";
import {
  hasLocalTool,
  isCreatorAssistant,
  isDefaultAssistant,
  listChatModels,
  LOCAL_TOOL_TYPES,
  normalizeLocalTools,
  parseOptionalNumber,
  REASONING_LEVELS,
  toggleLocalToolOption,
} from "@overlay/lib/settings";
import { safeStringArray } from "~/lib/type-guards";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import type { AssistantProfile, AssistantTag, ReasoningLevel } from "@overlay/types/settings";
import type { Workspace } from "@overlay/types/workspace";

function asIdList(value: unknown): string[] {
  if (Array.isArray(value)) {
    return safeStringArray(value);
  }
  if (value && typeof value === "object") {
    return Object.keys(value as Record<string, unknown>);
  }
  return [];
}

export default function SettingsAssistantDetailPage() {
  const { t } = useTranslation("settings");
  const { id } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const settings = useSettingsStore((state) => state.settings);
  const storeAssistant = settings?.assistants.find((item) => item.id === id);
  const locationAssistant = (location.state as { assistant?: AssistantProfile } | null)?.assistant;
  const assistant = storeAssistant ?? (locationAssistant?.id === id ? locationAssistant : undefined);

  const [draft, setDraft] = React.useState<AssistantProfile | null>(null);
  const [saving, setSaving] = React.useState(false);
  const [cloning, setCloning] = React.useState(false);
  const [deleting, setDeleting] = React.useState(false);
  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [workspaces, setWorkspaces] = React.useState<Workspace[]>([]);
  const [tagCreateOpen, setTagCreateOpen] = React.useState(false);
  const [tagCreating, setTagCreating] = React.useState(false);
  const [tagDeleteTarget, setTagDeleteTarget] = React.useState<AssistantTag | null>(null);
  const [tagDeleting, setTagDeleting] = React.useState(false);
  const loadedIdRef = React.useRef<string | null>(null);
  const skills = useQuery({ queryKey: skillsQueryKey, queryFn: skillsApi.list });

  React.useEffect(() => {
    let cancelled = false;
    api
      .get<Workspace[]>("workspaces")
      .then((list) => {
        if (!cancelled) setWorkspaces(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        // 工作区服务不可用时保持空列表，绑定下拉只显示「无绑定」
      });
    return () => {
      cancelled = true;
    };
  }, []);

  React.useEffect(() => {
    if (!assistant) {
      return;
    }
    if (loadedIdRef.current === assistant.id) {
      return;
    }
    loadedIdRef.current = assistant.id;
    setDraft({
      ...assistant,
      tags: asIdList(assistant.tags),
      mcpServers: asIdList(assistant.mcpServers),
      modeInjectionIds: asIdList(assistant.modeInjectionIds),
      lorebookIds: asIdList(assistant.lorebookIds),
      quickMessageIds: asIdList(assistant.quickMessageIds),
      localTools: normalizeLocalTools(assistant.localTools),
      enabledSkills: safeStringArray(assistant.enabledSkills),
    });
  }, [assistant]);

  if (!id || !assistant || !draft) {
    return <p className="text-sm text-muted-foreground">{t("common.not_found")}</p>;
  }

  const chatModels = listChatModels(settings);
  const creator = isCreatorAssistant(assistant.id);
  const defaultLocked = isDefaultAssistant(assistant.id) || creator;

  const updateDraft = (patch: Partial<AssistantProfile>) => {
    setDraft((current) => (current ? { ...current, ...patch } : current));
  };

  const save = async () => {
    setSaving(true);
    try {
      const saved = await api.put<AssistantProfile>(`assistants/${assistant.id}`, {
        ...draft,
        // 归一化后写回；后端按字段 presence 处理，旧客户端缺键不会清空
        localTools: normalizeLocalTools(draft.localTools) ?? [],
        chatModelId: draft.chatModelId || null,
        workspaceId: draft.workspaceId || null,
        temperature: draft.temperature ?? null,
        topP: draft.topP ?? null,
        maxTokens: draft.maxTokens ?? null,
      });
      setDraft({
        ...saved,
        tags: asIdList(saved.tags),
        mcpServers: asIdList(saved.mcpServers),
        modeInjectionIds: asIdList(saved.modeInjectionIds),
        lorebookIds: asIdList(saved.lorebookIds),
        quickMessageIds: asIdList(saved.quickMessageIds),
        localTools: normalizeLocalTools(saved.localTools),
        enabledSkills: safeStringArray(saved.enabledSkills),
      });
      toast.success(t("common.saved"));
    } catch (error) {
      reportSettingsError(error, t("common.save_failed"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <SettingsPageHeader
        title={getAssistantDisplayName(draft.name)}
        description={assistant.id}
        actions={
          <>
            {creator ? null : <Button
              type="button"
              variant="outline"
              disabled={cloning}
              onClick={async () => {
                setCloning(true);
                try {
                  const cloned = await api.post<AssistantProfile>(`assistants/${assistant.id}/clone`);
                  toast.success(t("common.created"));
                  void navigate(`/settings/assistants/${cloned.id}`, { state: { assistant: cloned } });
                } catch (error) {
                  reportSettingsError(error, t("common.clone_failed"));
                } finally {
                  setCloning(false);
                }
              }}
            >
              <Copy className="size-4" />
              {cloning ? t("common.cloning") : t("common.clone")}
            </Button>}
            <Button
              type="button"
              variant="destructive"
              disabled={defaultLocked}
              onClick={() => setDeleteOpen(true)}
            >
              <Trash2 className="size-4" />
              {t("common.delete")}
            </Button>
          </>
        }
      />

      {defaultLocked ? <Badge variant="secondary">{t("assistants.default_badge")}</Badge> : null}

      <div className="grid gap-4 md:grid-cols-2">
        <SettingsField label={t("assistants.fields.name")}>
          <Input value={draft.name} onChange={(event) => updateDraft({ name: event.target.value })} />
        </SettingsField>
        <SettingsSelectField
          label={t("assistants.fields.chat_model")}
          value={draft.chatModelId || "__global__"}
          onValueChange={(value) => updateDraft({ chatModelId: value === "__global__" ? null : value })}
          options={[
            { value: "__global__", label: t("assistants.fields.use_global_model") },
            ...chatModels.map(({ provider, model }) => ({
              value: model.id,
              label: `${provider.name} / ${getModelDisplayName(model.displayName, model.modelId)}`,
            })),
          ]}
        />
        <SettingsField label={t("assistants.fields.temperature")} hint={t("assistants.hints.temperature")}>
          <Input
            type="number"
            step="0.1"
            min={0}
            max={2}
            value={draft.temperature ?? ""}
            onChange={(event) => updateDraft({ temperature: parseOptionalNumber(event.target.value) })}
          />
        </SettingsField>
        <SettingsField label={t("assistants.fields.top_p")} hint={t("assistants.hints.top_p")}>
          <Input
            type="number"
            step="0.05"
            min={0}
            max={1}
            value={draft.topP ?? ""}
            onChange={(event) => updateDraft({ topP: parseOptionalNumber(event.target.value) })}
          />
        </SettingsField>
        <SettingsField label={t("assistants.fields.context_limit")} hint={t("assistants.hints.context_limit")}>
          <Input
            type="number"
            min={0}
            value={draft.contextMessageLimit ?? 0}
            onChange={(event) =>
              updateDraft({ contextMessageLimit: Math.max(0, Number(event.target.value) || 0) })
            }
          />
        </SettingsField>
        <SettingsSelectField
          label={t("assistants.fields.reasoning_level")}
          value={(draft.reasoningLevel as ReasoningLevel | undefined) ?? "auto"}
          onValueChange={(value) => updateDraft({ reasoningLevel: value as ReasoningLevel })}
          options={REASONING_LEVELS.map((level) => ({
            value: level,
            label: t(`reasoning.${level}`),
          }))}
        />
        <SettingsField label={t("assistants.fields.max_tokens")} hint={t("assistants.hints.max_tokens")}>
          <Input
            type="number"
            min={1}
            value={draft.maxTokens ?? ""}
            onChange={(event) => updateDraft({ maxTokens: parseOptionalNumber(event.target.value) })}
          />
        </SettingsField>
        {creator ? (
          <SettingsField label={t("assistants.fields.workspace")} hint={t("assistants.hints.creator_no_workspace")}>
            <span className="text-sm text-muted-foreground">{t("assistants.fields.no_workspace")}</span>
          </SettingsField>
        ) : <SettingsSelectField
          label={t("assistants.fields.workspace")}
          value={draft.workspaceId || "__none__"}
          onValueChange={(value) => updateDraft({ workspaceId: value === "__none__" ? null : value })}
          options={[
            { value: "__none__", label: t("assistants.fields.no_workspace") },
            ...workspaces.map((workspace) => ({
              value: workspace.id,
              label: workspace.name,
            })),
          ]}
        />}
        {draft.enableTimeReminder ? (
          <SettingsField
            label={t("assistants.fields.time_reminder_interval")}
            hint={t("assistants.hints.time_reminder_interval")}
          >
            <Input
              type="number"
              min={1}
              value={draft.timeReminderIntervalMinutes ?? 60}
              onChange={(event) =>
                updateDraft({
                  timeReminderIntervalMinutes: Math.max(1, Math.floor(Number(event.target.value) || 60)),
                })
              }
            />
          </SettingsField>
        ) : null}
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <SettingsSwitchRow
          label={t("assistants.fields.stream_output")}
          checked={draft.streamOutput !== false}
          onCheckedChange={(checked) => updateDraft({ streamOutput: checked })}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.enable_web_search")}
          checked={draft.enableWebSearch === true}
          onCheckedChange={(checked) => updateDraft({ enableWebSearch: checked })}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.allow_conversation_system_prompt")}
          checked={draft.allowConversationSystemPrompt === true}
          onCheckedChange={(checked) => updateDraft({ allowConversationSystemPrompt: checked })}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.allow_conversation_injection")}
          checked={draft.allowConversationPromptInjection === true}
          onCheckedChange={(checked) => updateDraft({ allowConversationPromptInjection: checked })}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.use_assistant_avatar")}
          checked={draft.useAssistantAvatar === true}
          onCheckedChange={(checked) => updateDraft({ useAssistantAvatar: checked })}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.enable_memory")}
          checked={draft.enableMemory === true}
          onCheckedChange={(checked) => updateDraft({ enableMemory: checked })}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.use_global_memory")}
          checked={draft.useGlobalMemory === true}
          onCheckedChange={(checked) => updateDraft({ useGlobalMemory: checked })}
          disabled={draft.enableMemory !== true}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.auto_consolidate_memory")}
          checked={draft.autoConsolidateMemory !== false}
          onCheckedChange={(checked) => updateDraft({ autoConsolidateMemory: checked })}
          disabled={draft.enableMemory !== true}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.enable_recent_chats")}
          checked={draft.enableRecentChatsReference === true}
          onCheckedChange={(checked) => updateDraft({ enableRecentChatsReference: checked })}
        />
        <SettingsSwitchRow
          label={t("assistants.fields.enable_time_reminder")}
          checked={draft.enableTimeReminder === true}
          onCheckedChange={(checked) => updateDraft({ enableTimeReminder: checked })}
        />
      </div>

      <AssistantMemoryPanel
        assistantId={draft.id}
        savedUseGlobal={assistant?.useGlobalMemory === true}
        draftUseGlobal={draft.useGlobalMemory === true}
      />

      <AssistantSystemPromptEditor assistant={draft} onChange={updateDraft} />
      <SettingsField label={t("assistants.fields.message_template")} hint={t("assistants.hints.message_template")}>
        <Input
          value={draft.messageTemplate ?? "{{ message }}"}
          onChange={(event) => updateDraft({ messageTemplate: event.target.value })}
        />
      </SettingsField>

      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-medium">{t("assistants.fields.tags")}</span>
          <Button type="button" size="sm" variant="outline" onClick={() => setTagCreateOpen(true)}>
            <Plus className="size-4" />
            {t("assistants.tags.new")}
          </Button>
        </div>
        <div className="flex flex-col gap-1 rounded-md border p-2">
          {(settings?.assistantTags ?? []).length === 0 ? (
            <span className="px-1 py-2 text-xs text-muted-foreground">—</span>
          ) : (
            (settings?.assistantTags ?? []).map((tag) => {
              const checked = (draft.tags ?? []).includes(tag.id);
              return (
                <div key={tag.id} className="flex items-center gap-1 rounded-sm px-1 py-1.5">
                  <label className="flex min-w-0 flex-1 items-center gap-2 text-sm">
                    <Checkbox
                      checked={checked}
                      onCheckedChange={(value) => {
                        const current = draft.tags ?? [];
                        updateDraft({
                          tags:
                            value === true
                              ? [...current, tag.id]
                              : current.filter((item) => item !== tag.id),
                        });
                      }}
                    />
                    <span className="truncate">{tag.name}</span>
                  </label>
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    onClick={() => setTagDeleteTarget(tag)}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              );
            })
          )}
        </div>
      </div>
      <SettingsCheckboxGroup
        label={t("assistants.fields.mcp")}
        selected={draft.mcpServers ?? []}
        onChange={(mcpServers) => updateDraft({ mcpServers })}
        items={(settings?.mcpServers ?? []).map((server) => ({
          id: server.id,
          label: server.commonOptions.name || t("common.unnamed"),
        }))}
      />
      <SettingsCheckboxGroup
        label={t("assistants.fields.mode_injections")}
        selected={draft.modeInjectionIds ?? []}
        onChange={(modeInjectionIds) => updateDraft({ modeInjectionIds })}
        items={(settings?.modeInjections ?? []).map((item) => ({
          id: item.id,
          label: item.name || t("common.unnamed"),
        }))}
      />
      <SettingsCheckboxGroup
        label={t("assistants.fields.lorebooks")}
        selected={draft.lorebookIds ?? []}
        onChange={(lorebookIds) => updateDraft({ lorebookIds })}
        items={(settings?.lorebooks ?? []).map((item) => ({
          id: item.id,
          label: item.name || t("common.unnamed"),
        }))}
      />
      <SettingsCheckboxGroup
        label={t("assistants.fields.quick_messages")}
        selected={draft.quickMessageIds ?? []}
        onChange={(quickMessageIds) => updateDraft({ quickMessageIds })}
        items={(settings?.quickMessages ?? []).map((item) => ({
          id: item.id,
          label: item.title || t("common.unnamed"),
        }))}
      />

      <AssistantSkillsField
        skills={skills.data}
        selected={draft.enabledSkills ?? []}
        onChange={(enabledSkills) => updateDraft({ enabledSkills })}
      />

      <div className="flex flex-col gap-2">
        <span className="text-sm font-medium">{t("assistants.local_tools.title")}</span>
        <p className="text-xs text-muted-foreground">{t("assistants.local_tools.hint")}</p>
        <div className="flex flex-col gap-1 rounded-md border p-2">
          {LOCAL_TOOL_TYPES.map((toolType) => {
            const enabled = hasLocalTool(draft.localTools, toolType);
            return (
              <div key={toolType} className="flex items-center justify-between gap-3 rounded-sm px-1 py-1.5">
                <div className="min-w-0 flex-1">
                  <div className="text-sm">{t(`assistants.local_tools.${toolType}.label`)}</div>
                  <div className="text-xs text-muted-foreground">
                    {t(`assistants.local_tools.${toolType}.desc`)}
                  </div>
                </div>
                <Switch
                  checked={enabled}
                  onCheckedChange={(checked) =>
                    updateDraft({
                      localTools: toggleLocalToolOption(draft.localTools, toolType, checked === true),
                    })
                  }
                />
              </div>
            );
          })}
        </div>
      </div>

      <SettingsFormActions onSave={() => void save()} saving={saving} />

      <ConfirmDeleteDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title={t("common.delete")}
        description={t("assistants.delete_confirm", { name: getAssistantDisplayName(draft.name) })}
        loading={deleting}
        onConfirm={async () => {
          if (defaultLocked) {
            toast.error(t(creator ? "assistants.cannot_delete_creator" : "assistants.cannot_delete_default"));
            return;
          }
          setDeleting(true);
          try {
            await api.delete(`assistants/${assistant.id}`);
            toast.success(t("common.deleted"));
            setDeleteOpen(false);
            void navigate("/settings/assistants");
          } catch (error) {
            reportSettingsError(error, t("common.delete_failed"));
          } finally {
            setDeleting(false);
          }
        }}
      />

      <CreateNameDialog
        open={tagCreateOpen}
        onOpenChange={setTagCreateOpen}
        title={t("assistants.tags.new")}
        placeholder={t("assistants.tags.name_placeholder")}
        loading={tagCreating}
        onSubmit={async (name) => {
          setTagCreating(true);
          try {
            const tag = await api.post<AssistantTag>("assistant-tags", { name: name.trim() });
            toast.success(t("common.created"));
            setTagCreateOpen(false);
            updateDraft({ tags: [...(draft.tags ?? []), tag.id] });
          } catch (error) {
            reportSettingsError(error, t("common.create_failed"));
          } finally {
            setTagCreating(false);
          }
        }}
      />

      <ConfirmDeleteDialog
        open={tagDeleteTarget !== null}
        onOpenChange={(open) => {
          if (!open) setTagDeleteTarget(null);
        }}
        title={t("common.delete")}
        description={t("assistants.tags.delete_confirm", { name: tagDeleteTarget?.name ?? "" })}
        loading={tagDeleting}
        onConfirm={async () => {
          if (!tagDeleteTarget) return;
          setTagDeleting(true);
          try {
            await api.delete(`assistant-tags/${tagDeleteTarget.id}`);
            toast.success(t("common.deleted"));
            setTagDeleteTarget(null);
            updateDraft({
              tags: (draft.tags ?? []).filter((item) => item !== tagDeleteTarget.id),
            });
          } catch (error) {
            reportSettingsError(error, t("common.delete_failed"));
          } finally {
            setTagDeleting(false);
          }
        }}
      />
    </div>
  );
}

/** Skills enabled for this assistant; saved with the rest of the page (PUT carries `enabledSkills`). */
export function AssistantSkillsField({
  skills,
  selected,
  onChange,
}: {
  skills: SkillSummary[] | undefined;
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const { t } = useTranslation("settings");
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">{t("assistants.skills.title")}</span>
        <Button type="button" size="sm" variant="ghost" asChild>
          <Link to="/settings/skills">{t("assistants.skills.manage")}</Link>
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">{t("assistants.skills.hint")}</p>
      <div className="flex flex-col gap-1 rounded-md border p-2">
        {skills === undefined ? (
          <span className="px-1 py-2 text-xs text-muted-foreground">{t("common.loading")}</span>
        ) : skills.length === 0 ? (
          <span className="px-1 py-2 text-xs text-muted-foreground">{t("assistants.skills.empty")}</span>
        ) : (
          skills.map((skill) => (
            <label key={skill.name} className="flex items-start gap-2 rounded-sm px-1 py-1.5 text-sm">
              <Checkbox
                className="mt-0.5"
                checked={selected.includes(skill.name)}
                onCheckedChange={(value) =>
                  onChange(
                    value === true
                      ? [...selected.filter((name) => name !== skill.name), skill.name]
                      : selected.filter((name) => name !== skill.name),
                  )
                }
              />
              <span className="min-w-0">
                <span className="block truncate">{skill.name}</span>
                {skill.description ? (
                  <span className="block line-clamp-1 text-xs text-muted-foreground">{skill.description}</span>
                ) : null}
              </span>
            </label>
          ))
        )}
      </div>
    </div>
  );
}
