import * as React from "react";
import { Copy, FlaskConical, FolderUp, Plus, RotateCcw, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import {
  SettingsField,
  SettingsSelectField,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import {
  type ApiKeyDraftItem,
  type VertexServiceAccountDraftItem,
  makeCredentialId,
  maskApiKeyPreview,
  mergeImportedServiceAccounts,
  parseBatchApiKeys,
  parseVertexServiceAccountJsons,
} from "@overlay/lib/provider-credentials";
import api from "~/services/api";
import type {
  CredentialRotationStrategy,
  CredentialRuntimeStatus,
  CredentialStatusResponse,
  TestCredentialResponse,
} from "@overlay/types/settings";

function useCredentialStatuses(providerId: string, refreshSignal: number) {
  const { t } = useTranslation("settings");
  const [statuses, setStatuses] = React.useState<Record<string, CredentialRuntimeStatus>>({});
  const [nowMs, setNowMs] = React.useState(() => Date.now());
  const [resetting, setResetting] = React.useState(false);

  const fetchStatuses = React.useCallback(async () => {
    try {
      const response = await api.get<CredentialStatusResponse>(`providers/${providerId}/credential-status`);
      setStatuses(response.statuses ?? {});
      setNowMs(Date.now());
    } catch {
      // Best-effort status fetch; do not block settings form.
    }
  }, [providerId]);

  React.useEffect(() => {
    void fetchStatuses();
  }, [fetchStatuses, refreshSignal]);

  const anyActiveCooldown = React.useMemo(
    () =>
      Object.values(statuses).some(
        (item) => item.status !== "healthy" && item.cooldownUntil > nowMs,
      ),
    [statuses, nowMs],
  );

  React.useEffect(() => {
    if (!anyActiveCooldown) return;
    const timer = window.setInterval(() => {
      const nextNow = Date.now();
      setNowMs(nextNow);
      if (
        Object.values(statuses).some(
          (item) => item.status !== "healthy" && item.cooldownUntil <= nextNow,
        )
      ) {
        void fetchStatuses();
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [anyActiveCooldown, statuses, fetchStatuses]);

  const resetAll = async () => {
    setResetting(true);
    try {
      const response = await api.post<CredentialStatusResponse>(`providers/${providerId}/credentials/reset`, {});
      setStatuses(response.statuses ?? {});
      setNowMs(Date.now());
      toast.success(t("providers.credentials.reset_done"));
    } catch (error) {
      reportSettingsError(error, t("common.save_failed"));
    } finally {
      setResetting(false);
    }
  };

  return { statuses, nowMs, anyActiveCooldown, resetting, resetAll, refresh: fetchStatuses };
}

function StatusBadge({
  enabled,
  status,
  nowMs,
}: {
  enabled: boolean;
  status?: CredentialRuntimeStatus;
  nowMs: number;
}) {
  const { t } = useTranslation("settings");
  if (!enabled) {
    return <Badge variant="outline">{t("common.disabled")}</Badge>;
  }
  if (status && status.status !== "healthy" && status.cooldownUntil > nowMs) {
    const seconds = Math.max(1, Math.ceil((status.cooldownUntil - nowMs) / 1000));
    return (
      <Badge variant={status.status === "auth_error" ? "destructive" : "secondary"}>
        {status.status === "auth_error"
          ? t("providers.credentials.status_auth_error", { seconds })
          : t("providers.credentials.status_cooling", { seconds })}
      </Badge>
    );
  }
  return <Badge variant="secondary">{t("providers.credentials.status_healthy")}</Badge>;
}

export function RotationStrategyField({
  value,
  disabled,
  onChange,
}: {
  value: CredentialRotationStrategy;
  disabled?: boolean;
  onChange: (value: CredentialRotationStrategy) => void;
}) {
  const { t } = useTranslation("settings");
  return (
    <div className={disabled ? "pointer-events-none opacity-60" : ""}>
      <SettingsSelectField
        label={t("providers.credentials.rotation_strategy")}
        value={value}
        onValueChange={(next) => onChange(next === "failover" ? "failover" : "round_robin")}
        options={[
          { value: "round_robin", label: t("providers.credentials.rotation_round_robin") },
          { value: "failover", label: t("providers.credentials.rotation_failover") },
        ]}
      />
      <p className="mt-1 text-xs text-muted-foreground">{t("providers.credentials.rotation_hint")}</p>
    </div>
  );
}

export function ApiKeysPoolEditor({
  providerId,
  items,
  rotationStrategy,
  refreshSignal,
  disabled,
  onChangeItems,
  onChangeStrategy,
}: {
  providerId: string;
  items: ApiKeyDraftItem[];
  rotationStrategy: CredentialRotationStrategy;
  refreshSignal: number;
  disabled?: boolean;
  onChangeItems: (next: ApiKeyDraftItem[]) => void;
  onChangeStrategy: (next: CredentialRotationStrategy) => void;
}) {
  const { t } = useTranslation("settings");
  const { statuses, nowMs, anyActiveCooldown, resetting, resetAll, refresh } =
    useCredentialStatuses(providerId, refreshSignal);
  const [batchOpen, setBatchOpen] = React.useState(false);
  const [batchText, setBatchText] = React.useState("");
  const [testingId, setTestingId] = React.useState<string | null>(null);

  const enabledCount = items.filter((item) => item.enabled && (item.keyConfigured || item.key.trim().length > 0)).length;

  const updateItem = (id: string, patch: Partial<ApiKeyDraftItem>) => {
    onChangeItems(items.map((item) => (item.id === id ? { ...item, ...patch } : item)));
  };

  const handleBatchImport = () => {
    const incoming = parseBatchApiKeys(batchText);
    const existingKeys = new Set(items.map((item) => item.key.trim()).filter(Boolean));
    const added: ApiKeyDraftItem[] = incoming
      .filter((k) => !existingKeys.has(k))
      .map((k) => ({
        id: makeCredentialId(),
        label: "",
        enabled: true,
        key: k,
        keyConfigured: true,
        keyPreview: maskApiKeyPreview(k),
        keyTouched: true,
      }));
    if (added.length > 0) {
      onChangeItems([...items, ...added]);
    }
    toast.success(t("providers.credentials.imported_keys", { count: added.length }));
    setBatchText("");
    setBatchOpen(false);
  };

  const testKey = async (item: ApiKeyDraftItem) => {
    setTestingId(item.id);
    try {
      const res = await api.post<TestCredentialResponse>(`providers/${providerId}/credentials/test`, {
        type: "api_key",
        credentialId: item.id,
        ...(item.keyTouched ? { key: item.key } : {}),
      });
      if (res.ok) {
        toast.success(t("providers.credentials.test_ok"));
        await refresh();
      } else {
        toast.error(t("providers.credentials.test_failed", { error: res.error ?? "Unknown error" }));
      }
    } catch (error) {
      reportSettingsError(error, t("common.save_failed"));
    } finally {
      setTestingId(null);
    }
  };

  return (
    <div className="space-y-3 rounded-xl border p-3 md:col-span-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium">
            {t("providers.credentials.api_key_pool_title", { enabled: enabledCount, total: items.length })}
          </p>
          <p className="text-xs text-muted-foreground">{t("providers.credentials.save_required_hint")}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {anyActiveCooldown ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={resetting || disabled}
              onClick={() => void resetAll()}
            >
              <RotateCcw className="size-3.5" />
              {t("providers.credentials.reset_cooldown")}
            </Button>
          ) : null}
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() => setBatchOpen(true)}
          >
            <Copy className="size-3.5" />
            {t("providers.credentials.batch_paste")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() =>
              onChangeItems([
                ...items,
                {
                  id: makeCredentialId(),
                  label: "",
                  enabled: true,
                  key: "",
                  keyConfigured: false,
                  keyPreview: "",
                  keyTouched: true,
                },
              ])
            }
          >
            <Plus className="size-3.5" />
            {t("providers.credentials.add_key")}
          </Button>
        </div>
      </div>

      {items.length > 1 ? (
        <RotationStrategyField
          value={rotationStrategy}
          disabled={disabled}
          onChange={onChangeStrategy}
        />
      ) : null}

      {items.length === 0 ? (
        <p className="text-xs text-muted-foreground">{t("providers.credentials.empty_keys")}</p>
      ) : (
        <div className="space-y-2">
          {items.map((item, index) => (
            <div key={item.id} className="space-y-2 rounded-lg border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">
                    {item.label.trim() || item.keyPreview || `Key #${index + 1}`}
                  </span>
                  {item.label.trim() && item.keyPreview ? (
                    <span className="font-mono text-xs text-muted-foreground">{item.keyPreview}</span>
                  ) : null}
                  <StatusBadge enabled={item.enabled} status={statuses[item.id]} nowMs={nowMs} />
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    disabled={disabled || testingId !== null || (!item.keyConfigured && !item.key.trim())}
                    onClick={() => void testKey(item)}
                  >
                    <FlaskConical className="size-3.5" />
                    {testingId === item.id ? "..." : t("providers.credentials.test")}
                  </Button>
                  <Switch
                    checked={item.enabled}
                    disabled={disabled}
                    onCheckedChange={(enabled) => updateItem(item.id, { enabled })}
                  />
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    disabled={disabled}
                    onClick={() => onChangeItems(items.filter((entry) => entry.id !== item.id))}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              </div>

              <div className="grid gap-2 md:grid-cols-2">
                <Input
                  placeholder={t("providers.credentials.label_placeholder")}
                  value={item.label}
                  disabled={disabled}
                  onChange={(event) => updateItem(item.id, { label: event.target.value })}
                />
                <Input
                  type="password"
                  autoComplete="off"
                  placeholder={
                    item.keyConfigured && !item.keyTouched
                      ? item.keyPreview || "••••••••"
                      : t("providers.fields.api_key")
                  }
                  value={item.key}
                  disabled={disabled}
                  onChange={(event) =>
                    updateItem(item.id, {
                      key: event.target.value,
                      keyTouched: true,
                      keyPreview: maskApiKeyPreview(event.target.value),
                    })
                  }
                />
              </div>
            </div>
          ))}
        </div>
      )}

      <Dialog open={batchOpen} onOpenChange={setBatchOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("providers.credentials.batch_paste")}</DialogTitle>
            <DialogDescription>{t("providers.credentials.batch_paste_hint")}</DialogDescription>
          </DialogHeader>
          <Textarea
            rows={6}
            className="font-mono text-xs"
            value={batchText}
            onChange={(event) => setBatchText(event.target.value)}
            placeholder="sk-..."
          />
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setBatchOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="button" disabled={!batchText.trim()} onClick={handleBatchImport}>
              {t("providers.credentials.import")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export function VertexServiceAccountsEditor({
  providerId,
  defaultLocation,
  items,
  rotationStrategy,
  refreshSignal,
  disabled,
  onChangeItems,
  onChangeStrategy,
}: {
  providerId: string;
  defaultLocation: string;
  items: VertexServiceAccountDraftItem[];
  rotationStrategy: CredentialRotationStrategy;
  refreshSignal: number;
  disabled?: boolean;
  onChangeItems: (next: VertexServiceAccountDraftItem[]) => void;
  onChangeStrategy: (next: CredentialRotationStrategy) => void;
}) {
  const { t } = useTranslation("settings");
  const { statuses, nowMs, anyActiveCooldown, resetting, resetAll, refresh } =
    useCredentialStatuses(providerId, refreshSignal);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const [pasteOpen, setPasteOpen] = React.useState(false);
  const [pasteText, setPasteText] = React.useState("");
  const [editing, setEditing] = React.useState<{
    item: VertexServiceAccountDraftItem;
    isNew: boolean;
  } | null>(null);
  const [testingId, setTestingId] = React.useState<string | null>(null);

  const enabledCount = items.filter(
    (item) =>
      item.enabled &&
      item.projectId.trim().length > 0 &&
      item.serviceAccountEmail.trim().length > 0 &&
      (item.privateKeyConfigured || item.privateKey.trim().length > 0),
  ).length;

  const applyImported = (imported: VertexServiceAccountDraftItem[], skippedCount: number) => {
    const { merged, addedCount } = mergeImportedServiceAccounts(items, imported);
    onChangeItems(merged);
    toast.success(
      skippedCount > 0
        ? t("providers.credentials.imported_sa_skipped", { count: addedCount, skipped: skippedCount })
        : t("providers.credentials.imported_sa", { count: addedCount }),
    );
  };

  const handleFilesSelected = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const allAccounts: VertexServiceAccountDraftItem[] = [];
    let skipped = 0;
    for (const file of Array.from(files)) {
      try {
        const text = await file.text();
        const res = parseVertexServiceAccountJsons(text);
        allAccounts.push(...res.accounts);
        skipped += res.skippedCount;
      } catch {
        skipped++;
      }
    }
    applyImported(allAccounts, skipped);
  };

  const testAccount = async (item: VertexServiceAccountDraftItem) => {
    setTestingId(item.id);
    try {
      const res = await api.post<TestCredentialResponse>(`providers/${providerId}/credentials/test`, {
        type: "service_account",
        credentialId: item.id,
        ...(item.privateKeyTouched
          ? {
              projectId: item.projectId,
              serviceAccountEmail: item.serviceAccountEmail,
              privateKey: item.privateKey,
              location: item.location,
            }
          : {}),
      });
      if (res.ok) {
        toast.success(t("providers.credentials.test_ok"));
        await refresh();
      } else {
        toast.error(t("providers.credentials.test_failed", { error: res.error ?? "Unknown error" }));
      }
    } catch (error) {
      reportSettingsError(error, t("common.save_failed"));
    } finally {
      setTestingId(null);
    }
  };

  return (
    <div className="space-y-3 rounded-xl border p-3 md:col-span-2">
      <input
        ref={fileInputRef}
        type="file"
        multiple
        accept="application/json,.json"
        className="hidden"
        onChange={(event) => {
          void handleFilesSelected(event.target.files);
          event.target.value = "";
        }}
      />

      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium">
            {t("providers.credentials.sa_pool_title", { enabled: enabledCount, total: items.length })}
          </p>
          <p className="text-xs text-muted-foreground">{t("providers.credentials.save_required_hint")}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {anyActiveCooldown ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={resetting || disabled}
              onClick={() => void resetAll()}
            >
              <RotateCcw className="size-3.5" />
              {t("providers.credentials.reset_cooldown")}
            </Button>
          ) : null}
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() => fileInputRef.current?.click()}
          >
            <FolderUp className="size-3.5" />
            {t("providers.credentials.import_sa_files")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() => setPasteOpen(true)}
          >
            <Copy className="size-3.5" />
            {t("providers.credentials.paste_sa_json")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() =>
              setEditing({
                isNew: true,
                item: {
                  id: makeCredentialId(),
                  label: "",
                  projectId: "",
                  serviceAccountEmail: "",
                  location: "",
                  enabled: true,
                  privateKey: "",
                  privateKeyConfigured: false,
                  privateKeyTouched: true,
                },
              })
            }
          >
            <Plus className="size-3.5" />
            {t("providers.credentials.add_sa")}
          </Button>
        </div>
      </div>

      {items.length > 1 ? (
        <RotationStrategyField
          value={rotationStrategy}
          disabled={disabled}
          onChange={onChangeStrategy}
        />
      ) : null}

      {items.length === 0 ? (
        <p className="text-xs text-muted-foreground">{t("providers.credentials.empty_sas")}</p>
      ) : (
        <div className="space-y-2">
          {items.map((item, index) => {
            const effectiveLocation = item.location.trim() || defaultLocation.trim() || "us-central1";
            return (
              <div key={item.id} className="space-y-2 rounded-lg border p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="min-w-0 flex-1 space-y-0.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="truncate text-sm font-medium">
                        {item.label.trim() || item.projectId.trim() || `SA #${index + 1}`}
                      </span>
                      <StatusBadge enabled={item.enabled} status={statuses[item.id]} nowMs={nowMs} />
                    </div>
                    <p className="truncate font-mono text-xs text-muted-foreground">
                      {item.projectId || "-"} · {effectiveLocation}
                      {!item.location.trim() ? ` (${t("providers.credentials.inherited_location")})` : ""}
                    </p>
                    {item.serviceAccountEmail ? (
                      <p className="truncate text-xs text-muted-foreground">{item.serviceAccountEmail}</p>
                    ) : null}
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      disabled={
                        disabled ||
                        testingId !== null ||
                        !item.serviceAccountEmail.trim() ||
                        (!item.privateKeyConfigured && !item.privateKey.trim())
                      }
                      onClick={() => void testAccount(item)}
                    >
                      <FlaskConical className="size-3.5" />
                      {testingId === item.id ? "..." : t("providers.credentials.test")}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      disabled={disabled}
                      onClick={() =>
                        setEditing({
                          isNew: true,
                          item: {
                            ...item,
                            id: makeCredentialId(),
                            location: "",
                          },
                        })
                      }
                    >
                      {t("providers.credentials.duplicate")}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      disabled={disabled}
                      onClick={() => setEditing({ isNew: false, item })}
                    >
                      {t("common.edit")}
                    </Button>
                    <Switch
                      checked={item.enabled}
                      disabled={disabled}
                      onCheckedChange={(enabled) =>
                        onChangeItems(
                          items.map((entry) => (entry.id === item.id ? { ...entry, enabled } : entry)),
                        )
                      }
                    />
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      disabled={disabled}
                      onClick={() => onChangeItems(items.filter((entry) => entry.id !== item.id))}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <Dialog open={pasteOpen} onOpenChange={setPasteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("providers.credentials.paste_sa_json")}</DialogTitle>
            <DialogDescription>{t("providers.credentials.paste_sa_json_hint")}</DialogDescription>
          </DialogHeader>
          <Textarea
            rows={7}
            className="font-mono text-xs"
            value={pasteText}
            onChange={(event) => setPasteText(event.target.value)}
            placeholder='{"type":"service_account","project_id":"...","client_email":"...","private_key":"..."}'
          />
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setPasteOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              disabled={!pasteText.trim()}
              onClick={() => {
                const res = parseVertexServiceAccountJsons(pasteText);
                applyImported(res.accounts, res.skippedCount);
                setPasteText("");
                setPasteOpen(false);
              }}
            >
              {t("providers.credentials.import")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("providers.fields.use_service_account")}</DialogTitle>
          </DialogHeader>
          {editing ? (
            <div className="space-y-3">
              <SettingsField label={t("providers.credentials.label_placeholder")}>
                <Input
                  value={editing.item.label}
                  onChange={(event) =>
                    setEditing({ ...editing, item: { ...editing.item, label: event.target.value } })
                  }
                />
              </SettingsField>
              <SettingsField label={t("providers.fields.project_id")}>
                <Input
                  value={editing.item.projectId}
                  onChange={(event) =>
                    setEditing({ ...editing, item: { ...editing.item, projectId: event.target.value } })
                  }
                />
              </SettingsField>
              <SettingsField label={t("providers.fields.service_account_email")}>
                <Input
                  value={editing.item.serviceAccountEmail}
                  onChange={(event) =>
                    setEditing({
                      ...editing,
                      item: { ...editing.item, serviceAccountEmail: event.target.value },
                    })
                  }
                />
              </SettingsField>
              <SettingsField label={t("providers.credentials.sa_location_optional")}>
                <Input
                  value={editing.item.location}
                  placeholder={defaultLocation || "us-central1"}
                  onChange={(event) =>
                    setEditing({ ...editing, item: { ...editing.item, location: event.target.value } })
                  }
                />
              </SettingsField>
              <SettingsField label={t("providers.fields.private_key")}>
                <Textarea
                  rows={4}
                  className="font-mono text-xs"
                  value={editing.item.privateKey}
                  placeholder={
                    editing.item.privateKeyConfigured && !editing.item.privateKeyTouched
                      ? "••••••••"
                      : "-----BEGIN PRIVATE KEY-----"
                  }
                  onChange={(event) =>
                    setEditing({
                      ...editing,
                      item: {
                        ...editing.item,
                        privateKey: event.target.value,
                        privateKeyTouched: true,
                      },
                    })
                  }
                />
                {editing.item.privateKeyConfigured && !editing.item.privateKeyTouched ? (
                  <p className="text-xs text-muted-foreground">{t("providers.fields.private_key_hint")}</p>
                ) : null}
              </SettingsField>
            </div>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setEditing(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              disabled={
                !editing ||
                !editing.item.projectId.trim() ||
                !editing.item.serviceAccountEmail.trim() ||
                (!editing.item.privateKeyConfigured && !editing.item.privateKey.trim())
              }
              onClick={() => {
                if (!editing) return;
                if (editing.isNew) {
                  onChangeItems([...items, editing.item]);
                } else {
                  onChangeItems(
                    items.map((entry) => (entry.id === editing.item.id ? editing.item : entry)),
                  );
                }
                setEditing(null);
              }}
            >
              {t("common.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
