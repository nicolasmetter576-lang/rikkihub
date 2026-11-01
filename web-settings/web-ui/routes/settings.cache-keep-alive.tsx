import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import { extractErrorMessage } from "~/lib/error";
import {
  SettingsField,
  SettingsPageHeader,
} from "@overlay/components/settings/settings-ui";
import {
  DEFAULT_CACHE_KEEP_ALIVE,
  fetchCacheKeepAlive,
  parsePositiveInt,
  updateCacheKeepAlive,
  validateCacheKeepAlive,
} from "@overlay/lib/cache-keep-alive";
import type { CacheKeepAliveConfig } from "@overlay/types/settings";

export default function SettingsCacheKeepAlivePage() {
  const { t } = useTranslation("settings");
  const [loading, setLoading] = React.useState(true);
  const [enabled, setEnabled] = React.useState(DEFAULT_CACHE_KEEP_ALIVE.enabled);
  const [intervalText, setIntervalText] = React.useState(
    String(DEFAULT_CACHE_KEEP_ALIVE.intervalSeconds),
  );
  const [maxDurationText, setMaxDurationText] = React.useState(
    String(DEFAULT_CACHE_KEEP_ALIVE.maxDurationMinutes),
  );
  const [saving, setSaving] = React.useState(false);

  React.useEffect(() => {
    let mounted = true;
    fetchCacheKeepAlive()
      .then((config: CacheKeepAliveConfig) => {
        if (!mounted) return;
        setEnabled(config.enabled);
        setIntervalText(String(config.intervalSeconds));
        setMaxDurationText(String(config.maxDurationMinutes));
      })
      .catch((error) => {
        if (!mounted) return;
        toast.error(extractErrorMessage(error, t("common.save_failed")));
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [t]);

  const handleEnabledChange = React.useCallback(
    (checked: boolean) => {
      setEnabled(checked);
      void updateCacheKeepAlive({ enabled: checked }).catch((error) => {
        setEnabled(!checked);
        toast.error(extractErrorMessage(error, String(error)));
      });
    },
    [],
  );

  const handleSave = React.useCallback(async () => {
    const intervalSeconds = parsePositiveInt(intervalText);
    const maxDurationMinutes = parsePositiveInt(maxDurationText);

    if (intervalSeconds === null) {
      toast.error(t("cache_keep_alive.error_interval"));
      return;
    }
    if (maxDurationMinutes === null) {
      toast.error(t("cache_keep_alive.error_max_duration"));
      return;
    }

    const invalid = validateCacheKeepAlive(intervalSeconds, maxDurationMinutes);
    if (invalid === "interval") {
      toast.error(t("cache_keep_alive.error_interval"));
      return;
    }
    if (invalid === "max") {
      toast.error(t("cache_keep_alive.error_max_duration"));
      return;
    }
    if (invalid === "max_le_interval") {
      toast.error(t("cache_keep_alive.error_max_le_interval"));
      return;
    }

    setSaving(true);
    try {
      await updateCacheKeepAlive({ intervalSeconds, maxDurationMinutes });
      toast.success(t("cache_keep_alive.saved"));
    } catch (error) {
      toast.error(extractErrorMessage(error, String(error)));
    } finally {
      setSaving(false);
    }
  }, [intervalText, maxDurationText, t]);

  if (loading) {
    return (
      <div className="space-y-6">
        <SettingsPageHeader title={t("cache_keep_alive.title")} />
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("cache_keep_alive.title")} />

      <div className="space-y-3 rounded-xl border p-4">
        <div className="flex items-center justify-between gap-3 rounded-md border px-3 py-2">
          <div className="space-y-0.5">
            <p className="text-sm">{t("cache_keep_alive.enable")}</p>
            <p className="text-xs text-muted-foreground">{t("cache_keep_alive.enable_desc")}</p>
          </div>
          <Switch checked={enabled} onCheckedChange={handleEnabledChange} />
        </div>

        <div className="grid gap-3 md:grid-cols-2">
          <SettingsField
            label={t("cache_keep_alive.interval")}
            hint={t("cache_keep_alive.interval_desc")}
          >
            <Input
              type="number"
              min={1}
              value={intervalText}
              onChange={(event) => setIntervalText(event.target.value)}
            />
          </SettingsField>
          <SettingsField
            label={t("cache_keep_alive.max_duration")}
            hint={t("cache_keep_alive.max_duration_desc")}
          >
            <Input
              type="number"
              min={1}
              value={maxDurationText}
              onChange={(event) => setMaxDurationText(event.target.value)}
            />
          </SettingsField>
        </div>

        <div className="flex gap-2">
          <Button
            type="button"
            size="sm"
            disabled={saving}
            onClick={() => {
              void handleSave();
            }}
          >
            {saving ? t("common.saving") : t("cache_keep_alive.save")}
          </Button>
        </div>
      </div>

      <p className="text-xs text-muted-foreground">{t("cache_keep_alive.note")}</p>
    </div>
  );
}
