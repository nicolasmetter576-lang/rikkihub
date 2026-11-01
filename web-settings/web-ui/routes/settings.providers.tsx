import * as React from "react";

import { useNavigate } from "react-router";
import { Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  CreateNameDialog,
  SettingsListCard,
  SettingsPageHeader,
  SettingsSelectField,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { getDisplayName } from "~/lib/display";
import { isBuiltInProvider, PROVIDER_TYPES, type ProviderType } from "@overlay/lib/settings";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import type { ProviderProfile } from "@overlay/types/settings";

export default function SettingsProvidersPage() {
  const { t } = useTranslation("settings");
  const navigate = useNavigate();
  const providers = useSettingsStore((state) => state.settings?.providers ?? []);
  const [createOpen, setCreateOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const [createType, setCreateType] = React.useState<ProviderType>("openai");

  return (
    <div className="space-y-6">
      <SettingsPageHeader
        title={t("providers.title")}
        description={t("providers.description")}
        actions={
          <Button type="button" onClick={() => setCreateOpen(true)}>
            <Plus className="size-4" />
            {t("providers.new")}
          </Button>
        }
      />

      {providers.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          {t("providers.empty")}
        </p>
      ) : (
        <div className="space-y-3">
          {providers.map((provider) => (
            <SettingsListCard
              key={provider.id}
              href={`/settings/providers/${provider.id}`}
              title={getDisplayName(provider.name, t("common.unnamed"))}
              description={`${t(`provider_types.${provider.type ?? "openai"}`)} · ${provider.models.length}`}
              badges={
                <>
                  {isBuiltInProvider(provider.id) ? (
                    <Badge variant="secondary">{t("providers.builtin_badge")}</Badge>
                  ) : null}
                  {!provider.enabled ? <Badge variant="outline">{t("common.disabled")}</Badge> : null}
                </>
              }
            />
          ))}
        </div>
      )}

      <CreateNameDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        title={t("providers.new")}
        placeholder={t("providers.create_name_placeholder")}
        loading={creating}
        extra={
          <SettingsSelectField
            label={t("providers.create_type")}
            value={createType}
            onValueChange={(value) => setCreateType(value as ProviderType)}
            options={PROVIDER_TYPES.map((type) => ({
              value: type,
              label: t(`provider_types.${type}`),
            }))}
          />
        }
        onSubmit={async (name) => {
          setCreating(true);
          try {
            const provider = await api.post<ProviderProfile>("providers", {
              type: createType,
              name: name.trim() || undefined,
            });
            toast.success(t("common.created"));
            setCreateOpen(false);
            void navigate(`/settings/providers/${provider.id}`, { state: { provider } });
          } catch (error) {
            reportSettingsError(error, t("common.create_failed"));
          } finally {
            setCreating(false);
          }
        }}
      />
    </div>
  );
}
