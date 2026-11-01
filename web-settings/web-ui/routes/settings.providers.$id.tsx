import * as React from "react";

import { useLocation, useNavigate, useParams } from "react-router";
import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import {
  ConfirmDeleteDialog,
  SettingsField,
  SettingsFormActions,
  SettingsPageHeader,
  SettingsSelectField,
  SettingsSwitchRow,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { ProviderHeadersEditor } from "@overlay/components/settings/provider-headers-editor";
import { parseProviderHeaders, providerHeadersDraft, providerHeadersPayload } from "@overlay/lib/provider-headers";
import { getDisplayName, getModelDisplayName } from "~/lib/display";
import {
  applyModelCapabilities,
  BUILTIN_TOOL_TYPES,
  getProviderType,
  hasBuiltInTool,
  isBuiltInProvider,
  MODEL_ABILITIES,
  MODEL_MODALITIES,
  MODEL_TYPES,
  newProviderModel,
  shouldDetectCapabilities,
  toggleBuiltInTool,
  toggleListValue,
} from "@overlay/lib/settings";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";

// Same default and bound as the app (Model.kt: DEFAULT_MODEL_CONTEXT_LENGTH / MAX_MODEL_CONTEXT_LENGTH).
const DEFAULT_MODEL_CONTEXT_LENGTH = 1_000_000;
const MAX_MODEL_CONTEXT_LENGTH = 100_000_000;
import type {
  ClaudePromptCacheTtl,
  ModelAbility,
  ModelCapabilities,
  ModelType,
  ProviderModel,
  ProviderProfile,
  RemoteModelsResponse,
} from "@overlay/types/settings";

type ProviderDraft = {
  providerHeadersJson: string;
  id: string;
  enabled: boolean;
  name: string;
  models: ProviderModel[];
  /** Secret slots: `*Touched` decides whether the field is sent at all — a
   * projected (masked) client never rewrites a credential it did not edit. */
  apiKeyTouched: boolean;
  privateKeyTouched: boolean;
  apiKey: string;
  baseUrl: string;
  type: "openai" | "google" | "claude" | "codex" | "devin" | "antigravity";
  chatCompletionsPath: string;
  responsesPath: string;
  useResponseApi: boolean;
  includeHistoryReasoning: boolean;
  promptCaching: boolean;
  promptCacheTtl: ClaudePromptCacheTtl;
  vertexAI: boolean;
  useInteractionsApi: boolean;
  useServiceAccount: boolean;
  privateKey: string;
  serviceAccountEmail: string;
  location: string;
  projectId: string;
  clientVersion: string;
};

function readField(provider: ProviderProfile, key: string): unknown {
  return (provider as unknown as Record<string, unknown>)[key];
}

export function connectionDraft(provider: ProviderProfile): ProviderDraft {
  return {
    providerHeadersJson: providerHeadersDraft(provider),
    id: provider.id,
    enabled: provider.enabled,
    name: provider.name,
    models: provider.models,
    apiKeyTouched: false,
    privateKeyTouched: false,
    apiKey: String(readField(provider, "apiKey") ?? ""),
    baseUrl: String(readField(provider, "baseUrl") ?? ""),
    type: getProviderType(provider),
    chatCompletionsPath: String(readField(provider, "chatCompletionsPath") ?? "/chat/completions"),
    responsesPath: String(readField(provider, "responsesPath") ?? "/responses"),
    useResponseApi: readField(provider, "useResponseApi") === true,
    includeHistoryReasoning: readField(provider, "includeHistoryReasoning") !== false,
    promptCaching: readField(provider, "promptCaching") === true,
    promptCacheTtl: (readField(provider, "promptCacheTtl") as ClaudePromptCacheTtl | undefined) ?? "5m",
    vertexAI: readField(provider, "vertexAI") === true,
    useInteractionsApi: readField(provider, "useInteractionsApi") === true,
    useServiceAccount: readField(provider, "useServiceAccount") === true,
    privateKey: String(readField(provider, "privateKey") ?? ""),
    serviceAccountEmail: String(readField(provider, "serviceAccountEmail") ?? ""),
    location: String(readField(provider, "location") ?? "us-central1"),
    projectId: String(readField(provider, "projectId") ?? ""),
    clientVersion: String(readField(provider, "clientVersion") ?? ""),
  };
}

export default function SettingsProviderDetailPage() {
  const { t } = useTranslation("settings");
  const { id } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const storeProvider = useSettingsStore((state) =>
    state.settings?.providers.find((item) => item.id === id),
  );
  const locationProvider = (location.state as { provider?: ProviderProfile } | null)?.provider;
  const provider = storeProvider ?? (locationProvider?.id === id ? locationProvider : undefined);

  const [draft, setDraft] = React.useState<ProviderDraft | null>(null);
  const [saving, setSaving] = React.useState(false);
  const [deleting, setDeleting] = React.useState(false);
  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [modelDraft, setModelDraft] = React.useState<ProviderModel | null>(null);
  // 新建模型时按型号自动识别能力；用户手动改过模态、能力或上下文长度后就不再覆盖
  const [capabilitiesEdited, setCapabilitiesEdited] = React.useState(false);
  const [detectedVersion, setDetectedVersion] = React.useState(0);
  const draftModelId = modelDraft?.modelId ?? "";
  const draftIsNew = modelDraft !== null && !provider?.models.some((item) => item.id === modelDraft.id);
  React.useEffect(() => {
    if (!shouldDetectCapabilities(draftIsNew, draftModelId, capabilitiesEdited)) return;
    const modelId = draftModelId.trim();
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      api
        .get<ModelCapabilities>(`providers/model-capabilities?modelId=${encodeURIComponent(modelId)}`, {
          signal: controller.signal,
        })
        .then((capabilities) => {
          setModelDraft((current) =>
            current && current.modelId.trim() === modelId ? applyModelCapabilities(current, capabilities) : current,
          );
          setDetectedVersion((version) => version + 1);
        })
        .catch(() => {
          // 识别只是帮忙预填；失败就保持手动填写
        });
    }, 400);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [draftIsNew, draftModelId, capabilitiesEdited]);
  const [modelHeadersJson, setModelHeadersJson] = React.useState("[]");
  const [modelBodiesJson, setModelBodiesJson] = React.useState("[]");
  const [modelSaving, setModelSaving] = React.useState(false);
  const [modelDeleteId, setModelDeleteId] = React.useState<string | null>(null);
  const [modelDeleting, setModelDeleting] = React.useState(false);
  const [remoteModels, setRemoteModels] = React.useState<ProviderModel[]>([]);
  const [remoteError, setRemoteError] = React.useState<string | null>(null);
  const [selectedRemote, setSelectedRemote] = React.useState<string[]>([]);
  const [fetchingRemote, setFetchingRemote] = React.useState(false);
  const [importingRemote, setImportingRemote] = React.useState(false);
  const loadedIdRef = React.useRef<string | null>(null);

  React.useEffect(() => {
    if (!provider) {
      return;
    }
    if (loadedIdRef.current !== provider.id) {
      loadedIdRef.current = provider.id;
      setDraft(connectionDraft(provider));
      return;
    }
    setDraft((current) => (current ? { ...current, models: provider.models } : current));
  }, [provider]);

  if (!id || !provider || !draft) {
    return <p className="text-sm text-muted-foreground">{t("common.not_found")}</p>;
  }

  const type = draft.type;
  const builtinLocked = isBuiltInProvider(provider.id);

  const updateDraft = (patch: Partial<ProviderDraft>) => {
    setDraft((current) => (current ? { ...current, ...patch } : current));
  };

  const saveConfig = async () => {
    const headers = parseProviderHeaders(draft.providerHeadersJson);
    if (!headers.ok) {
      toast.error(t(`providers.headers.errors.${headers.error}`));
      return;
    }
    setSaving(true);
    try {
      const payload =
        type === "codex"
          ? {
              type: "codex",
              id: draft.id,
              enabled: draft.enabled,
              name: draft.name,
            }
          : type === "devin"
            ? {
                type: "devin",
                id: draft.id,
                enabled: draft.enabled,
                name: draft.name,
                clientVersion: draft.clientVersion ?? "",
              }
          : type === "antigravity"
            ? {
                type: "antigravity",
                id: draft.id,
                enabled: draft.enabled,
                name: draft.name,
                clientVersion: draft.clientVersion ?? "",
              }
            : type === "google"
              ? {
                  type: "google",
                  id: draft.id,
                  enabled: draft.enabled,
                  name: draft.name,
                  ...(draft.apiKeyTouched ? { apiKey: draft.apiKey } : {}),
                  baseUrl: draft.baseUrl ?? "",
                  vertexAI: draft.vertexAI === true,
                  useInteractionsApi: draft.useInteractionsApi === true,
                  useServiceAccount: draft.useServiceAccount === true,
                  ...(draft.privateKeyTouched ? { privateKey: draft.privateKey } : {}),
                  serviceAccountEmail: draft.serviceAccountEmail ?? "",
                  location: draft.location ?? "",
                  projectId: draft.projectId ?? "",
                }
              : type === "claude"
                ? {
                    type: "claude",
                    id: draft.id,
                    enabled: draft.enabled,
                    name: draft.name,
                    ...(draft.apiKeyTouched ? { apiKey: draft.apiKey } : {}),
                    baseUrl: draft.baseUrl ?? "",
                    promptCaching: draft.promptCaching === true,
                    promptCacheTtl: draft.promptCacheTtl ?? "5m",
                  }
                : {
                    type: "openai",
                    id: draft.id,
                    enabled: draft.enabled,
                    name: draft.name,
                    ...(draft.apiKeyTouched ? { apiKey: draft.apiKey } : {}),
                    baseUrl: draft.baseUrl ?? "",
                    chatCompletionsPath: draft.chatCompletionsPath ?? "/chat/completions",
                    responsesPath: draft.responsesPath ?? "/responses",
                    useResponseApi: draft.useResponseApi === true,
                    includeHistoryReasoning: draft.includeHistoryReasoning !== false,
                  };
      const saved = await api.put<ProviderProfile>(`providers/${provider.id}`, {
        ...payload,
        ...providerHeadersPayload(draft.providerHeadersJson),
      });
      setDraft(connectionDraft({ ...saved, models: provider.models }));
      toast.success(t("common.saved"));
    } catch (error) {
      reportSettingsError(error, t("common.save_failed"));
    } finally {
      setSaving(false);
    }
  };

  const openModelEditor = (model: ProviderModel) => {
    setModelDraft(model);
    setCapabilitiesEdited(false);
    setModelHeadersJson(JSON.stringify(model.customHeaders ?? [], null, 2));
    setModelBodiesJson(JSON.stringify(model.customBodies ?? [], null, 2));
  };

  const parseJsonList = (text: string): unknown[] | null => {
    if (text.trim().length === 0) {
      return [];
    }
    try {
      const parsed: unknown = JSON.parse(text);
      return Array.isArray(parsed) ? parsed : null;
    } catch {
      return null;
    }
  };

  const saveModel = async () => {
    if (!modelDraft) {
      return;
    }
    const customHeaders = parseJsonList(modelHeadersJson);
    const customBodies = parseJsonList(modelBodiesJson);
    if (customHeaders === null || customBodies === null) {
      reportSettingsError(new Error("invalid JSON"), t("providers.model_json_invalid"));
      return;
    }
    setModelSaving(true);
    try {
      const isNew = !provider.models.some((item) => item.id === modelDraft.id);
      const payload = { ...modelDraft, customHeaders, customBodies };
      const saved = isNew
        ? await api.post<ProviderModel>(`providers/${provider.id}/models`, payload)
        : await api.put<ProviderModel>(`providers/${provider.id}/models/${modelDraft.id}`, payload);
      setModelDraft(null);
      toast.success(isNew ? t("common.created") : t("common.saved"));
      setDraft((current) =>
        current
          ? {
              ...current,
              models: isNew
                ? [...current.models, saved]
                : current.models.map((item) => (item.id === saved.id ? saved : item)),
            }
          : current,
      );
    } catch (error) {
      reportSettingsError(error, t("common.save_failed"));
    } finally {
      setModelSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <SettingsPageHeader
        title={getDisplayName(draft.name, t("common.unnamed"))}
        description={t(`provider_types.${type}`)}
        actions={
          <Button
            type="button"
            variant="destructive"
            disabled={builtinLocked}
            onClick={() => setDeleteOpen(true)}
          >
            <Trash2 className="size-4" />
            {t("common.delete")}
          </Button>
        }
      />

      <div className="flex flex-wrap gap-2">
        {builtinLocked ? <Badge variant="secondary">{t("providers.builtin_badge")}</Badge> : null}
        {!draft.enabled ? <Badge variant="outline">{t("common.disabled")}</Badge> : null}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <SettingsField label={t("providers.fields.name")}>
          <Input value={draft.name} onChange={(event) => updateDraft({ name: event.target.value })} />
        </SettingsField>
        {type !== "codex" && type !== "devin" && type !== "antigravity" ? (
          <>
            <SettingsField label={t("providers.fields.api_key")}>
              <Input
                type="password"
                autoComplete="off"
                value={draft.apiKey ?? ""}
                placeholder={readField(provider, "apiKeyConfigured") === true ? "••••••••" : undefined}
                onChange={(event) => updateDraft({ apiKey: event.target.value, apiKeyTouched: true })}
              />
              {readField(provider, "apiKeyConfigured") === true && !draft.apiKeyTouched ? (
                <p className="text-xs text-muted-foreground">{t("providers.fields.api_key_hint")}</p>
              ) : null}
            </SettingsField>
            <SettingsField label={t("providers.fields.base_url")} className="md:col-span-2">
              <Input value={draft.baseUrl ?? ""} onChange={(event) => updateDraft({ baseUrl: event.target.value })} />
            </SettingsField>
          </>
        ) : null}
        {type === "devin" || type === "antigravity" ? (
          <SettingsField label={t("providers.fields.client_version")}>
            <Input
              value={draft.clientVersion ?? ""}
              placeholder={type === "antigravity" ? t("providers.fields.client_version_auto") : undefined}
              onChange={(event) => updateDraft({ clientVersion: event.target.value })}
            />
          </SettingsField>
        ) : null}
        {type === "openai" ? (
          <>
            <SettingsField label={t("providers.fields.chat_completions_path")}>
              <Input
                value={draft.chatCompletionsPath ?? "/chat/completions"}
                disabled={builtinLocked}
                onChange={(event) => updateDraft({ chatCompletionsPath: event.target.value })}
              />
            </SettingsField>
            {draft.useResponseApi === true ? (
              <SettingsField label={t("providers.fields.responses_path")}>
                <Input
                  value={draft.responsesPath ?? "/responses"}
                  onChange={(event) => updateDraft({ responsesPath: event.target.value })}
                />
              </SettingsField>
            ) : null}
          </>
        ) : null}
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <SettingsSwitchRow
          label={t("providers.fields.enabled")}
          checked={draft.enabled}
          onCheckedChange={(checked) => updateDraft({ enabled: checked })}
        />
        {type === "openai" ? (
          <>
            <SettingsSwitchRow
              label={t("providers.fields.use_response_api")}
              checked={draft.useResponseApi === true}
              onCheckedChange={(checked) => updateDraft({ useResponseApi: checked })}
            />
            <SettingsSwitchRow
              label={t("providers.fields.include_history_reasoning")}
              checked={draft.includeHistoryReasoning !== false}
              onCheckedChange={(checked) => updateDraft({ includeHistoryReasoning: checked })}
            />
          </>
        ) : null}
        {type === "claude" ? (
          <>
            <SettingsSwitchRow
              label={t("providers.fields.prompt_caching")}
              checked={draft.promptCaching === true}
              onCheckedChange={(checked) => updateDraft({ promptCaching: checked })}
            />
            <SettingsSelectField
              label={t("providers.fields.prompt_cache_ttl")}
              value={draft.promptCacheTtl ?? "5m"}
              onValueChange={(value) => updateDraft({ promptCacheTtl: value as ClaudePromptCacheTtl })}
              options={[
                { value: "5m", label: t("cache_ttl.5m") },
                { value: "1h", label: t("cache_ttl.1h") },
              ]}
            />
          </>
        ) : null}
        {type === "google" ? (
          <>
            <SettingsSwitchRow
              label={t("providers.fields.vertex_ai")}
              checked={draft.vertexAI === true}
              onCheckedChange={(checked) => updateDraft({ vertexAI: checked })}
            />
            <SettingsSwitchRow
              label={t("providers.fields.use_interactions_api")}
              checked={draft.useInteractionsApi}
              disabled={draft.vertexAI || saving}
              onCheckedChange={(checked) => updateDraft({ useInteractionsApi: checked })}
            />
            <p className="text-xs text-muted-foreground">
              {t(draft.vertexAI ? "providers.interactions_vertex_hint" : "providers.interactions_hint")}
            </p>
            <SettingsSwitchRow
              label={t("providers.fields.use_service_account")}
              checked={draft.useServiceAccount === true}
              onCheckedChange={(checked) => updateDraft({ useServiceAccount: checked })}
            />
          </>
        ) : null}
      </div>

      {type === "google" && draft.vertexAI ? (
        <div className="grid gap-4 md:grid-cols-2">
          <SettingsField label={t("providers.fields.service_account_email")}>
            <Input
              value={draft.serviceAccountEmail ?? ""}
              onChange={(event) => updateDraft({ serviceAccountEmail: event.target.value })}
            />
          </SettingsField>
          <SettingsField label={t("providers.fields.project_id")}>
            <Input
              value={draft.projectId ?? ""}
              onChange={(event) => updateDraft({ projectId: event.target.value })}
            />
          </SettingsField>
          <SettingsField label={t("providers.fields.location")}>
            <Input
              value={draft.location ?? ""}
              onChange={(event) => updateDraft({ location: event.target.value })}
            />
          </SettingsField>
          <SettingsField label={t("providers.fields.private_key")} className="md:col-span-2">
            <Textarea
              rows={5}
              value={draft.privateKey ?? ""}
              placeholder={readField(provider, "privateKeyConfigured") === true ? "••••••••" : undefined}
              onChange={(event) => updateDraft({ privateKey: event.target.value, privateKeyTouched: true })}
            />
            {readField(provider, "privateKeyConfigured") === true && !draft.privateKeyTouched ? (
              <p className="text-xs text-muted-foreground">{t("providers.fields.private_key_hint")}</p>
            ) : null}
          </SettingsField>
        </div>
      ) : null}

      <ProviderHeadersEditor
        value={draft.providerHeadersJson}
        onChange={(providerHeadersJson) => updateDraft({ providerHeadersJson })}
        disabled={saving}
      />

      <SettingsFormActions
        onSave={() => void saveConfig()}
        saving={saving}
        extra={<span className="text-xs text-muted-foreground">{t("providers.save_config")}</span>}
      />

      <div className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold">{t("providers.models_title")}</h2>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={fetchingRemote}
              onClick={async () => {
                setFetchingRemote(true);
                try {
                  const response = await api.get<RemoteModelsResponse>(
                    `providers/${provider.id}/remote-models`,
                  );
                  setRemoteError(response.error ?? null);
                  setRemoteModels(response.models ?? []);
                  setSelectedRemote([]);
                } catch (error) {
                  reportSettingsError(error, t("common.save_failed"));
                } finally {
                  setFetchingRemote(false);
                }
              }}
            >
              {fetchingRemote ? t("providers.fetching_remote") : t("providers.fetch_remote")}
            </Button>
            <Button type="button" variant="outline" onClick={() => openModelEditor(newProviderModel())}>
              <Plus className="size-4" />
              {t("providers.add_model")}
            </Button>
          </div>
        </div>

        {remoteError ? (
          <p className="text-sm text-destructive">{t("providers.remote_error", { error: remoteError })}</p>
        ) : null}

        {remoteModels.length > 0 ? (
          <div className="space-y-2 rounded-xl border p-3">
            {remoteModels.map((model) => {
              const alreadyImported = provider.models.some((item) => item.modelId === model.modelId);
              const selected = selectedRemote.includes(model.modelId);
              return (
                <label key={model.modelId} className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={selected}
                    disabled={alreadyImported}
                    onCheckedChange={(value) => {
                      setSelectedRemote((current) =>
                        value === true
                          ? [...current, model.modelId]
                          : current.filter((item) => item !== model.modelId),
                      );
                    }}
                  />
                  <span className="truncate">
                    {getModelDisplayName(model.displayName, model.modelId)}
                  </span>
                  {alreadyImported ? <Badge variant="outline">{t("common.enabled")}</Badge> : null}
                </label>
              );
            })}
            <Button
              type="button"
              size="sm"
              disabled={selectedRemote.length === 0 || importingRemote}
              onClick={async () => {
                setImportingRemote(true);
                const failed: string[] = [];
                try {
                  for (const modelId of selectedRemote) {
                    const remote = remoteModels.find((item) => item.modelId === modelId);
                    if (!remote) continue;
                    try {
                      await api.post<ProviderModel>(`providers/${provider.id}/models`, {
                        ...newProviderModel(),
                        modelId: remote.modelId,
                        displayName: remote.displayName || remote.modelId,
                        type: remote.type ?? "CHAT",
                        inputModalities: remote.inputModalities ?? ["TEXT"],
                        outputModalities: remote.outputModalities ?? ["TEXT"],
                        abilities: remote.abilities ?? [],
                        tools: remote.tools ?? [],
                        ...(remote.contextLength ? { contextLength: remote.contextLength } : {}),
                      });
                    } catch {
                      failed.push(modelId);
                    }
                  }
                  if (failed.length === 0) {
                    toast.success(t("common.created"));
                  } else {
                    reportSettingsError(
                      new Error(failed.join(", ")),
                      t("providers.import_failed_count", { count: failed.length }),
                    );
                  }
                  // 失败的保留勾选，可重试续导
                  setSelectedRemote(failed);
                } finally {
                  setImportingRemote(false);
                }
              }}
            >
              {importingRemote ? t("common.creating") : t("providers.import_selected")}
            </Button>
          </div>
        ) : null}

        <div className="space-y-2">
          {draft.models.map((model) => (
            <div key={model.id} className="flex items-center justify-between gap-3 rounded-xl border p-3">
              <button
                type="button"
                className="min-w-0 flex-1 text-left"
                onClick={() => openModelEditor(model)}
              >
                <p className="truncate font-medium">
                  {getModelDisplayName(model.displayName, model.modelId)}
                </p>
                <p className="truncate text-xs text-muted-foreground">
                  {model.modelId} · {model.type}
                </p>
              </button>
              <Button type="button" size="icon-sm" variant="ghost" onClick={() => setModelDeleteId(model.id)}>
                <Trash2 className="size-4" />
              </Button>
            </div>
          ))}
        </div>
      </div>

      {modelDraft ? (
        <div className="space-y-4 rounded-xl border p-4">
          <div className="grid gap-4 md:grid-cols-2">
            <SettingsField label={t("providers.fields.model_id")}>
              <Input
                value={modelDraft.modelId}
                disabled={provider.models.some((item) => item.id === modelDraft.id)}
                onChange={(event) => setModelDraft({ ...modelDraft, modelId: event.target.value })}
              />
            </SettingsField>
            <SettingsField label={t("providers.fields.display_name")}>
              <Input
                value={modelDraft.displayName}
                onChange={(event) => setModelDraft({ ...modelDraft, displayName: event.target.value })}
              />
            </SettingsField>
            <SettingsSelectField
              label={t("providers.fields.type")}
              value={modelDraft.type}
              onValueChange={(value) => setModelDraft({ ...modelDraft, type: value as ModelType })}
              options={MODEL_TYPES.map((item) => ({ value: item, label: item }))}
            />
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <p className="text-sm font-medium">{t("providers.fields.input_modalities")}</p>
              {MODEL_MODALITIES.map((item) => (
                <label key={item} className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={(modelDraft.inputModalities ?? []).includes(item)}
                    onCheckedChange={() => {
                      setCapabilitiesEdited(true);
                      setModelDraft({
                        ...modelDraft,
                        inputModalities: toggleListValue(modelDraft.inputModalities ?? [], item),
                      });
                    }}
                  />
                  {item}
                </label>
              ))}
            </div>
            <div className="space-y-2">
              <p className="text-sm font-medium">{t("providers.fields.output_modalities")}</p>
              {MODEL_MODALITIES.map((item) => (
                <label key={item} className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={(modelDraft.outputModalities ?? []).includes(item)}
                    onCheckedChange={() => {
                      setCapabilitiesEdited(true);
                      setModelDraft({
                        ...modelDraft,
                        outputModalities: toggleListValue(modelDraft.outputModalities ?? [], item),
                      });
                    }}
                  />
                  {item}
                </label>
              ))}
            </div>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium">{t("providers.fields.abilities")}</p>
            {MODEL_ABILITIES.map((item) => (
              <label key={item} className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={(modelDraft.abilities ?? []).includes(item)}
                  onCheckedChange={() => {
                    setCapabilitiesEdited(true);
                    setModelDraft({
                      ...modelDraft,
                      abilities: toggleListValue(modelDraft.abilities ?? [], item) as ModelAbility[],
                    });
                  }}
                />
                {item}
              </label>
            ))}
          </div>
          {modelDraft.type === "CHAT" ? (
            <SettingsField
              label={t("providers.fields.context_length")}
              hint={t("providers.hints.context_length")}
            >
              {/* Uncontrolled so the field can be cleared while typing; only valid values reach the draft. */}
              <Input
                key={`${modelDraft.id}:${detectedVersion}`}
                type="number"
                min={1}
                max={MAX_MODEL_CONTEXT_LENGTH}
                defaultValue={modelDraft.contextLength ?? DEFAULT_MODEL_CONTEXT_LENGTH}
                onChange={(event) => {
                  const value = Math.trunc(Number(event.target.value));
                  if (Number.isFinite(value) && value >= 1 && value <= MAX_MODEL_CONTEXT_LENGTH) {
                    setCapabilitiesEdited(true);
                    setModelDraft({ ...modelDraft, contextLength: value });
                  }
                }}
              />
            </SettingsField>
          ) : null}
          <div className="space-y-2">
            <p className="text-sm font-medium">{t("providers.fields.builtin_tools")}</p>
            {BUILTIN_TOOL_TYPES.map((item) => (
              <label key={item} className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={hasBuiltInTool(modelDraft.tools, item)}
                  onCheckedChange={() =>
                    setModelDraft({
                      ...modelDraft,
                      tools: toggleBuiltInTool(modelDraft.tools, item),
                    })
                  }
                />
                {t(`tools.${item}`)}
              </label>
            ))}
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <SettingsField
              label={t("providers.fields.custom_headers")}
              hint={t("providers.hints.custom_headers")}
            >
              <Textarea
                rows={4}
                className="font-mono text-xs"
                value={modelHeadersJson}
                onChange={(event) => setModelHeadersJson(event.target.value)}
              />
            </SettingsField>
            <SettingsField
              label={t("providers.fields.custom_bodies")}
              hint={t("providers.hints.custom_bodies")}
            >
              <Textarea
                rows={4}
                className="font-mono text-xs"
                value={modelBodiesJson}
                onChange={(event) => setModelBodiesJson(event.target.value)}
              />
            </SettingsField>
          </div>
          <div className="flex gap-2">
            <Button type="button" onClick={() => void saveModel()} disabled={modelSaving}>
              {modelSaving ? t("common.saving") : t("common.save")}
            </Button>
            <Button type="button" variant="outline" onClick={() => setModelDraft(null)}>
              {t("common.cancel")}
            </Button>
          </div>
        </div>
      ) : null}

      <ConfirmDeleteDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title={t("common.delete")}
        description={t("providers.delete_confirm", { name: getDisplayName(draft.name, t("common.unnamed")) })}
        loading={deleting}
        onConfirm={async () => {
          if (builtinLocked) {
            toast.error(t("providers.cannot_delete_builtin"));
            return;
          }
          setDeleting(true);
          try {
            await api.delete(`providers/${provider.id}`);
            toast.success(t("common.deleted"));
            setDeleteOpen(false);
            void navigate("/settings/providers");
          } catch (error) {
            reportSettingsError(error, t("common.delete_failed"));
          } finally {
            setDeleting(false);
          }
        }}
      />

      <ConfirmDeleteDialog
        open={modelDeleteId !== null}
        onOpenChange={(open) => {
          if (!open) setModelDeleteId(null);
        }}
        title={t("common.delete")}
        description={t("providers.delete_model_confirm", {
          name: getModelDisplayName(
            provider.models.find((item) => item.id === modelDeleteId)?.displayName,
            provider.models.find((item) => item.id === modelDeleteId)?.modelId,
          ),
        })}
        loading={modelDeleting}
        onConfirm={async () => {
          if (!modelDeleteId) return;
          setModelDeleting(true);
          try {
            await api.delete(`providers/${provider.id}/models/${modelDeleteId}`);
            toast.success(t("common.deleted"));
            setModelDeleteId(null);
            setDraft((current) =>
              current
                ? { ...current, models: current.models.filter((item) => item.id !== modelDeleteId) }
                : current,
            );
          } catch (error) {
            reportSettingsError(error, t("common.delete_failed"));
          } finally {
            setModelDeleting(false);
          }
        }}
      />
    </div>
  );
}
