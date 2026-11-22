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
import { newMcpServer, newQuickMessage, parseMcpHeaders, serializeMcpHeaders } from "@overlay/lib/settings";
import { GlobalRegexPanel } from "@overlay/components/tavern/global-regex-panel";
import { LorebooksPanel } from "@overlay/components/tavern/lorebooks-panel";
import { PromptLibraryPanel } from "@overlay/components/tavern/prompt-library-panel";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import type { McpHeaderPair, McpServerConfig, QuickMessage } from "@overlay/types/settings";

type ExtensionTab = "quick" | "library" | "lorebook" | "regex" | "mcp";
const EXTENSION_TABS: ReadonlyArray<ExtensionTab> = ["quick", "library", "lorebook", "regex", "mcp"];

/** `?tab=` from the chat-bar extension picker's "go manage" links (`mode` is the old name of `library`). */
export function extensionTabFromSearch(search: string): ExtensionTab {
  const tab = new URLSearchParams(search).get("tab");
  if (tab === "mode") return "library";
  return EXTENSION_TABS.find((item) => item === tab) ?? "quick";
}

export default function SettingsExtensionsPage() {
  const { t } = useTranslation("settings");
  const { t: tt } = useTranslation("tavern");
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
  const promptLibrary = settings?.promptLibrary ?? [];
  const lorebooks = settings?.lorebooks ?? [];
  const globalRegexScripts = settings?.tavern?.regexScripts ?? [];
  const mcpServers = settings?.mcpServers ?? [];

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("extensions.title")} description={t("extensions.description")} />
      <SettingsSegmented
        value={tab}
        onChange={setTab}
        options={[
          { value: "quick", label: t("extensions.tab_quick") },
          { value: "library", label: tt("library.tab") },
          { value: "lorebook", label: tt("worldinfo.tab") },
          { value: "regex", label: tt("regex.tab") },
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
      {tab === "library" ? <PromptLibraryPanel entries={promptLibrary} /> : null}
      {tab === "lorebook" ? <LorebooksPanel books={lorebooks} /> : null}
      {tab === "regex" ? <GlobalRegexPanel scripts={globalRegexScripts} /> : null}
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
              deleteTarget.kind === "quick" ? `quick-messages/${deleteTarget.id}` : `mcp-servers/${deleteTarget.id}`;
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
    <div className="space-y-3 rounded-xl border p-3">
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
    <div className="space-y-3 rounded-xl border p-3">
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
