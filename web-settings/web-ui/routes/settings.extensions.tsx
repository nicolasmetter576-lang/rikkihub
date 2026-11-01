import * as React from "react";

import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useLocation } from "react-router";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import {
  ConfirmDeleteDialog,
  SettingsField,
  SettingsPageHeader,
  SettingsSegmented,
  SettingsSelectField,
  SettingsSwitchRow,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { getDisplayName } from "~/lib/display";
import {
  INJECTION_POSITIONS,
  INJECTION_ROLES,
  newLorebook,
  newLorebookEntry,
  newMcpServer,
  newModeInjection,
  newQuickMessage,
  parseMcpHeaders,
  serializeMcpHeaders,
} from "@overlay/lib/settings";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import type {
  InjectionPosition,
  InjectionRole,
  LorebookProfile,
  McpHeaderPair,
  McpServerConfig,
  ModeInjectionProfile,
  QuickMessage,
} from "@overlay/types/settings";

type ExtensionTab = "quick" | "mode" | "lorebook" | "mcp";
const EXTENSION_TABS: ReadonlyArray<ExtensionTab> = ["quick", "mode", "lorebook", "mcp"];

/** `?tab=` from the chat-bar extension picker's "go manage" links. */
export function extensionTabFromSearch(search: string): ExtensionTab {
  const tab = new URLSearchParams(search).get("tab");
  return EXTENSION_TABS.find((item) => item === tab) ?? "quick";
}

export default function SettingsExtensionsPage() {
  const { t } = useTranslation("settings");
  const settings = useSettingsStore((state) => state.settings);
  const location = useLocation();
  const [tab, setTab] = React.useState<ExtensionTab>(() => extensionTabFromSearch(location.search));
  React.useEffect(() => {
    setTab(extensionTabFromSearch(location.search));
  }, [location.search]);
  const [deleteTarget, setDeleteTarget] = React.useState<{ kind: ExtensionTab; id: string; name: string } | null>(
    null,
  );
  const [deleting, setDeleting] = React.useState(false);

  const quickMessages = settings?.quickMessages ?? [];
  const modeInjections = settings?.modeInjections ?? [];
  const lorebooks = settings?.lorebooks ?? [];
  const mcpServers = settings?.mcpServers ?? [];

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("extensions.title")} description={t("extensions.description")} />
      <SettingsSegmented
        value={tab}
        onChange={setTab}
        options={[
          { value: "quick", label: t("extensions.tab_quick") },
          { value: "mode", label: t("extensions.tab_mode") },
          { value: "lorebook", label: t("extensions.tab_lorebook") },
          { value: "mcp", label: t("extensions.tab_mcp") },
        ]}
      />

      {tab === "quick" ? (
        <QuickMessagesPanel
          items={quickMessages}
          onDelete={(item) =>
            setDeleteTarget({ kind: "quick", id: item.id, name: item.title || t("common.unnamed") })
          }
        />
      ) : null}
      {tab === "mode" ? (
        <ModeInjectionsPanel
          items={modeInjections}
          onDelete={(item) =>
            setDeleteTarget({ kind: "mode", id: item.id, name: item.name || t("common.unnamed") })
          }
        />
      ) : null}
      {tab === "lorebook" ? (
        <LorebooksPanel
          items={lorebooks}
          onDelete={(item) =>
            setDeleteTarget({ kind: "lorebook", id: item.id, name: item.name || t("common.unnamed") })
          }
        />
      ) : null}
      {tab === "mcp" ? (
        <McpPanel
          items={mcpServers}
          onDelete={(item) =>
            setDeleteTarget({
              kind: "mcp",
              id: item.id,
              name: item.commonOptions.name || t("common.unnamed"),
            })
          }
        />
      ) : null}

      <ConfirmDeleteDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
        title={t("common.delete")}
        description={t("extensions.delete_confirm", { name: deleteTarget?.name ?? "" })}
        loading={deleting}
        onConfirm={async () => {
          if (!deleteTarget) return;
          setDeleting(true);
          try {
            const path =
              deleteTarget.kind === "quick"
                ? `quick-messages/${deleteTarget.id}`
                : deleteTarget.kind === "mode"
                  ? `mode-injections/${deleteTarget.id}`
                  : deleteTarget.kind === "lorebook"
                    ? `lorebooks/${deleteTarget.id}`
                    : `mcp-servers/${deleteTarget.id}`;
            await api.delete(path);
            toast.success(t("common.deleted"));
            setDeleteTarget(null);
          } catch (error) {
            reportSettingsError(error, t("common.delete_failed"));
          } finally {
            setDeleting(false);
          }
        }}
      />
    </div>
  );
}

function QuickMessagesPanel({
  items,
  onDelete,
}: {
  items: QuickMessage[];
  onDelete: (item: QuickMessage) => void;
}) {
  const { t } = useTranslation("settings");
  const [creating, setCreating] = React.useState(false);

  return (
    <div className="space-y-4">
      <Button
        type="button"
        onClick={async () => {
          setCreating(true);
          try {
            await api.post<QuickMessage>("quick-messages", newQuickMessage());
            toast.success(t("common.created"));
          } catch (error) {
            reportSettingsError(error, t("common.create_failed"));
          } finally {
            setCreating(false);
          }
        }}
        disabled={creating}
      >
        <Plus className="size-4" />
        {t("extensions.new_quick")}
      </Button>
      {items.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {t("extensions.empty_quick")}
        </p>
      ) : (
        items.map((item) => <QuickMessageEditor key={item.id} item={item} onDelete={() => onDelete(item)} />)
      )}
    </div>
  );
}

function QuickMessageEditor({ item, onDelete }: { item: QuickMessage; onDelete: () => void }) {
  const { t } = useTranslation("settings");
  const [draft, setDraft] = React.useState(item);
  const [saving, setSaving] = React.useState(false);
  const loadedIdRef = React.useRef(item.id);

  React.useEffect(() => {
    // SSE 推送更新 item 时不要重置正在编辑的草稿，只有换了条目才重置。
    if (item.id !== loadedIdRef.current) {
      loadedIdRef.current = item.id;
      setDraft(item);
    }
  }, [item]);

  return (
    <div className="space-y-3 rounded-xl border p-4">
      <div className="grid gap-3 md:grid-cols-2">
        <SettingsField label={t("extensions.fields.title")}>
          <Input value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} />
        </SettingsField>
      </div>
      <SettingsField label={t("extensions.fields.content")}>
        <Textarea
          rows={4}
          value={draft.content}
          onChange={(event) => setDraft({ ...draft, content: event.target.value })}
        />
      </SettingsField>
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          disabled={saving}
          onClick={async () => {
            setSaving(true);
            try {
              await api.put(`quick-messages/${item.id}`, draft);
              toast.success(t("common.saved"));
            } catch (error) {
              reportSettingsError(error, t("common.save_failed"));
            } finally {
              setSaving(false);
            }
          }}
        >
          {saving ? t("common.saving") : t("common.save")}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
          <Trash2 className="size-4" />
        </Button>
      </div>
    </div>
  );
}

function ModeInjectionsPanel({
  items,
  onDelete,
}: {
  items: ModeInjectionProfile[];
  onDelete: (item: ModeInjectionProfile) => void;
}) {
  const { t } = useTranslation("settings");
  const [creating, setCreating] = React.useState(false);

  return (
    <div className="space-y-4">
      <Button
        type="button"
        disabled={creating}
        onClick={async () => {
          setCreating(true);
          try {
            await api.post("mode-injections", newModeInjection());
            toast.success(t("common.created"));
          } catch (error) {
            reportSettingsError(error, t("common.create_failed"));
          } finally {
            setCreating(false);
          }
        }}
      >
        <Plus className="size-4" />
        {t("extensions.new_mode")}
      </Button>
      {items.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {t("extensions.empty_mode")}
        </p>
      ) : (
        items.map((item) => <ModeInjectionEditor key={item.id} item={item} onDelete={() => onDelete(item)} />)
      )}
    </div>
  );
}

function ModeInjectionEditor({
  item,
  onDelete,
}: {
  item: ModeInjectionProfile;
  onDelete: () => void;
}) {
  const { t } = useTranslation("settings");
  const [draft, setDraft] = React.useState(item);
  const [saving, setSaving] = React.useState(false);
  const loadedIdRef = React.useRef(item.id);

  React.useEffect(() => {
    if (item.id !== loadedIdRef.current) {
      loadedIdRef.current = item.id;
      setDraft(item);
    }
  }, [item]);

  return (
    <div className="space-y-3 rounded-xl border p-4">
      <div className="grid gap-3 md:grid-cols-2">
        <SettingsField label={t("common.name")}>
          <Input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
        </SettingsField>
        <SettingsField label={t("extensions.fields.priority")}>
          <Input
            type="number"
            value={draft.priority ?? 0}
            onChange={(event) => setDraft({ ...draft, priority: Number(event.target.value) || 0 })}
          />
        </SettingsField>
        <SettingsSelectField
          label={t("extensions.fields.position")}
          value={draft.position ?? "after_system_prompt"}
          onValueChange={(value) => setDraft({ ...draft, position: value as InjectionPosition })}
          options={INJECTION_POSITIONS.map((position) => ({
            value: position,
            label: t(`extensions.positions.${position}`),
          }))}
        />
        <SettingsSelectField
          label={t("extensions.fields.role")}
          value={draft.role ?? "user"}
          onValueChange={(value) => setDraft({ ...draft, role: value as InjectionRole })}
          options={INJECTION_ROLES.map((role) => ({
            value: role,
            label: t(`extensions.roles.${role}`),
          }))}
        />
        {draft.position === "at_depth" ? (
          <SettingsField label={t("extensions.fields.inject_depth")}>
            <Input
              type="number"
              value={draft.injectDepth ?? 4}
              onChange={(event) => setDraft({ ...draft, injectDepth: Number(event.target.value) || 0 })}
            />
          </SettingsField>
        ) : null}
      </div>
      <SettingsSwitchRow
        label={t("common.enabled")}
        checked={draft.enabled !== false}
        onCheckedChange={(checked) => setDraft({ ...draft, enabled: checked })}
      />
      <SettingsField label={t("extensions.fields.content")}>
        <Textarea
          rows={6}
          value={draft.content ?? ""}
          onChange={(event) => setDraft({ ...draft, content: event.target.value })}
        />
      </SettingsField>
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          disabled={saving}
          onClick={async () => {
            setSaving(true);
            try {
              await api.put(`mode-injections/${item.id}`, { ...draft, type: "mode" });
              toast.success(t("common.saved"));
            } catch (error) {
              reportSettingsError(error, t("common.save_failed"));
            } finally {
              setSaving(false);
            }
          }}
        >
          {saving ? t("common.saving") : t("common.save")}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
          <Trash2 className="size-4" />
        </Button>
      </div>
    </div>
  );
}

function LorebooksPanel({
  items,
  onDelete,
}: {
  items: LorebookProfile[];
  onDelete: (item: LorebookProfile) => void;
}) {
  const { t } = useTranslation("settings");
  const [creating, setCreating] = React.useState(false);

  return (
    <div className="space-y-4">
      <Button
        type="button"
        disabled={creating}
        onClick={async () => {
          setCreating(true);
          try {
            await api.post("lorebooks", newLorebook());
            toast.success(t("common.created"));
          } catch (error) {
            reportSettingsError(error, t("common.create_failed"));
          } finally {
            setCreating(false);
          }
        }}
      >
        <Plus className="size-4" />
        {t("extensions.new_lorebook")}
      </Button>
      {items.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {t("extensions.empty_lorebook")}
        </p>
      ) : (
        items.map((item) => <LorebookEditor key={item.id} item={item} onDelete={() => onDelete(item)} />)
      )}
    </div>
  );
}

function LorebookEditor({ item, onDelete }: { item: LorebookProfile; onDelete: () => void }) {
  const { t } = useTranslation("settings");
  const [draft, setDraft] = React.useState(item);
  const [saving, setSaving] = React.useState(false);
  const loadedIdRef = React.useRef(item.id);

  React.useEffect(() => {
    if (item.id !== loadedIdRef.current) {
      loadedIdRef.current = item.id;
      setDraft(item);
    }
  }, [item]);

  return (
    <div className="space-y-3 rounded-xl border p-4">
      <div className="grid gap-3 md:grid-cols-2">
        <SettingsField label={t("common.name")}>
          <Input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
        </SettingsField>
        <SettingsField label={t("common.description")}>
          <Input
            value={draft.description ?? ""}
            onChange={(event) => setDraft({ ...draft, description: event.target.value })}
          />
        </SettingsField>
      </div>
      <SettingsSwitchRow
        label={t("common.enabled")}
        checked={draft.enabled !== false}
        onCheckedChange={(checked) => setDraft({ ...draft, enabled: checked })}
      />
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium">{t("extensions.fields.entries")}</p>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => setDraft({ ...draft, entries: [...(draft.entries ?? []), newLorebookEntry()] })}
          >
            <Plus className="size-4" />
            {t("extensions.fields.add_entry")}
          </Button>
        </div>
        {(draft.entries ?? []).map((entry, index) => (
          <div key={entry.id} className="space-y-3 rounded-lg border p-3">
            <div className="grid gap-3 md:grid-cols-2">
              <SettingsField label={t("common.name")}>
                <Input
                  value={entry.name}
                  onChange={(event) => {
                    const entries = [...(draft.entries ?? [])];
                    entries[index] = { ...entry, name: event.target.value };
                    setDraft({ ...draft, entries });
                  }}
                />
              </SettingsField>
              <SettingsField label={t("extensions.fields.priority")}>
                <Input
                  type="number"
                  value={entry.priority ?? 0}
                  onChange={(event) => {
                    const entries = [...(draft.entries ?? [])];
                    entries[index] = { ...entry, priority: Number(event.target.value) || 0 };
                    setDraft({ ...draft, entries });
                  }}
                />
              </SettingsField>
              <SettingsSelectField
                label={t("extensions.fields.position")}
                value={entry.position ?? "after_system_prompt"}
                onValueChange={(value) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = { ...entry, position: value as InjectionPosition };
                  setDraft({ ...draft, entries });
                }}
                options={INJECTION_POSITIONS.map((position) => ({
                  value: position,
                  label: t(`extensions.positions.${position}`),
                }))}
              />
              <SettingsSelectField
                label={t("extensions.fields.role")}
                value={entry.role ?? "user"}
                onValueChange={(value) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = { ...entry, role: value as InjectionRole };
                  setDraft({ ...draft, entries });
                }}
                options={INJECTION_ROLES.map((role) => ({
                  value: role,
                  label: t(`extensions.roles.${role}`),
                }))}
              />
              {entry.position === "at_depth" ? (
                <SettingsField label={t("extensions.fields.inject_depth")}>
                  <Input
                    type="number"
                    value={entry.injectDepth ?? 4}
                    onChange={(event) => {
                      const entries = [...(draft.entries ?? [])];
                      entries[index] = { ...entry, injectDepth: Number(event.target.value) || 0 };
                      setDraft({ ...draft, entries });
                    }}
                  />
                </SettingsField>
              ) : null}
              <SettingsField label={t("extensions.fields.scan_depth")}>
                <Input
                  type="number"
                  value={entry.scanDepth ?? 4}
                  onChange={(event) => {
                    const entries = [...(draft.entries ?? [])];
                    entries[index] = { ...entry, scanDepth: Number(event.target.value) || 0 };
                    setDraft({ ...draft, entries });
                  }}
                />
              </SettingsField>
            </div>
            <SettingsField label={t("extensions.fields.keywords")}>
              <Textarea
                rows={3}
                value={(entry.keywords ?? []).join("\n")}
                onChange={(event) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = {
                    ...entry,
                    keywords: event.target.value.split("\n").map((line) => line.trim()).filter(Boolean),
                  };
                  setDraft({ ...draft, entries });
                }}
              />
            </SettingsField>
            <SettingsField label={t("extensions.fields.content")}>
              <Textarea
                rows={4}
                value={entry.content ?? ""}
                onChange={(event) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = { ...entry, content: event.target.value };
                  setDraft({ ...draft, entries });
                }}
              />
            </SettingsField>
            <div className="grid gap-3 md:grid-cols-2">
              <SettingsSwitchRow
                label={t("common.enabled")}
                checked={entry.enabled !== false}
                onCheckedChange={(checked) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = { ...entry, enabled: checked };
                  setDraft({ ...draft, entries });
                }}
              />
              <SettingsSwitchRow
                label={t("extensions.fields.use_regex")}
                checked={entry.useRegex === true}
                onCheckedChange={(checked) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = { ...entry, useRegex: checked };
                  setDraft({ ...draft, entries });
                }}
              />
              <SettingsSwitchRow
                label={t("extensions.fields.case_sensitive")}
                checked={entry.caseSensitive === true}
                onCheckedChange={(checked) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = { ...entry, caseSensitive: checked };
                  setDraft({ ...draft, entries });
                }}
              />
              <SettingsSwitchRow
                label={t("extensions.fields.constant_active")}
                checked={entry.constantActive === true}
                onCheckedChange={(checked) => {
                  const entries = [...(draft.entries ?? [])];
                  entries[index] = { ...entry, constantActive: checked };
                  setDraft({ ...draft, entries });
                }}
              />
            </div>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() =>
                setDraft({ ...draft, entries: (draft.entries ?? []).filter((current) => current.id !== entry.id) })
              }
            >
              <Trash2 className="size-4" />
            </Button>
          </div>
        ))}
      </div>
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          disabled={saving}
          onClick={async () => {
            setSaving(true);
            try {
              await api.put(`lorebooks/${item.id}`, draft);
              toast.success(t("common.saved"));
            } catch (error) {
              reportSettingsError(error, t("common.save_failed"));
            } finally {
              setSaving(false);
            }
          }}
        >
          {saving ? t("common.saving") : t("common.save")}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
          <Trash2 className="size-4" />
        </Button>
      </div>
    </div>
  );
}

function McpPanel({
  items,
  onDelete,
}: {
  items: McpServerConfig[];
  onDelete: (item: McpServerConfig) => void;
}) {
  const { t } = useTranslation("settings");
  const [creating, setCreating] = React.useState(false);

  return (
    <div className="space-y-4">
      <Button
        type="button"
        disabled={creating}
        onClick={async () => {
          setCreating(true);
          try {
            await api.post("mcp-servers", newMcpServer("streamable_http"));
            toast.success(t("common.created"));
          } catch (error) {
            reportSettingsError(error, t("common.create_failed"));
          } finally {
            setCreating(false);
          }
        }}
      >
        <Plus className="size-4" />
        {t("extensions.new_mcp")}
      </Button>
      {items.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {t("extensions.empty_mcp")}
        </p>
      ) : (
        items.map((item) => <McpEditor key={item.id} item={item} onDelete={() => onDelete(item)} />)
      )}
    </div>
  );
}

function McpEditor({ item, onDelete }: { item: McpServerConfig; onDelete: () => void }) {
  const { t } = useTranslation("settings");
  const [name, setName] = React.useState(item.commonOptions.name);
  const [enable, setEnable] = React.useState(item.commonOptions.enable);
  const [url, setUrl] = React.useState(item.url ?? "");
  const [type, setType] = React.useState<"sse" | "streamable_http">(
    item.type === "sse" ? "sse" : "streamable_http",
  );
  const [headers, setHeaders] = React.useState<McpHeaderPair[]>(parseMcpHeaders(item.commonOptions.headers));
  const [saving, setSaving] = React.useState(false);
  const loadedIdRef = React.useRef(item.id);

  React.useEffect(() => {
    if (item.id !== loadedIdRef.current) {
      loadedIdRef.current = item.id;
      setName(item.commonOptions.name);
      setEnable(item.commonOptions.enable);
      setUrl(item.url ?? "");
      setType(item.type === "sse" ? "sse" : "streamable_http");
      setHeaders(parseMcpHeaders(item.commonOptions.headers));
    }
  }, [item]);

  return (
    <div className="space-y-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="font-medium">{getDisplayName(name, t("common.unnamed"))}</p>
        {!enable ? <Badge variant="outline">{t("common.disabled")}</Badge> : null}
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <SettingsField label={t("common.name")}>
          <Input value={name} onChange={(event) => setName(event.target.value)} />
        </SettingsField>
        <SettingsSelectField
          label={t("extensions.fields.transport")}
          value={type}
          onValueChange={(value) => setType(value as "sse" | "streamable_http")}
          options={[
            { value: "sse", label: t("extensions.transports.sse") },
            { value: "streamable_http", label: t("extensions.transports.streamable_http") },
          ]}
        />
        <SettingsField label={t("extensions.fields.url")} className="md:col-span-2">
          <Input value={url} onChange={(event) => setUrl(event.target.value)} />
        </SettingsField>
      </div>
      <SettingsSwitchRow label={t("common.enabled")} checked={enable} onCheckedChange={setEnable} />
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium">{t("extensions.fields.headers")}</p>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => setHeaders((current) => [...current, { first: "", second: "" }])}
          >
            {t("extensions.fields.add_header")}
          </Button>
        </div>
        {headers.map((header, index) => (
          <div key={index} className="grid gap-2 md:grid-cols-[1fr_1fr_auto]">
            <Input
              placeholder={t("extensions.fields.header_name")}
              value={header.first}
              onChange={(event) => {
                const next = [...headers];
                next[index] = { ...header, first: event.target.value };
                setHeaders(next);
              }}
            />
            <Input
              placeholder={header.second === null && header.valueConfigured ? "••••••••" : t("extensions.fields.header_value")}
              value={header.second ?? ""}
              onChange={(event) => {
                const next = [...headers];
                // Editing a masked row replaces the secret (""+no flag = clear).
                next[index] = { first: header.first, second: event.target.value };
                setHeaders(next);
              }}
            />
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              onClick={() => setHeaders(headers.filter((_, currentIndex) => currentIndex !== index))}
            >
              <Trash2 className="size-4" />
            </Button>
          </div>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">{t("extensions.fields.tools_readonly")}</p>
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          disabled={saving}
          onClick={async () => {
            setSaving(true);
            try {
              await api.put(`mcp-servers/${item.id}`, {
                id: item.id,
                type,
                url,
                commonOptions: {
                  enable,
                  name,
                  headers: serializeMcpHeaders(headers),
                },
              });
              toast.success(t("common.saved"));
            } catch (error) {
              reportSettingsError(error, t("common.save_failed"));
            } finally {
              setSaving(false);
            }
          }}
        >
          {saving ? t("common.saving") : t("common.save")}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
          <Trash2 className="size-4" />
        </Button>
      </div>
    </div>
  );
}
