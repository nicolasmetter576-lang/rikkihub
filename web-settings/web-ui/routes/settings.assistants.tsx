import * as React from "react";

import { useNavigate } from "react-router";
import { ChevronDown, ChevronUp, Plus, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  CreateNameDialog,
  SettingsList,
  SettingsListCard,
  SettingsPageHeader,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { getAssistantDisplayName } from "~/lib/display";
import { isDefaultAssistant } from "@overlay/lib/settings";
import { promptPreviewText } from "@overlay/lib/tavern";
import { presetApi } from "@overlay/lib/characters";
import { extractErrorMessage } from "~/lib/error";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import type { AssistantProfile } from "@overlay/types/settings";

/** A fixed fallback: a fresh `[]` in the selector makes the store hook re-render forever while settings are still loading. */
const NO_ASSISTANTS: AssistantProfile[] = [];

export default function SettingsAssistantsPage() {
  const { t } = useTranslation("settings");
  const navigate = useNavigate();
  const assistants = useSettingsStore((state) => state.settings?.assistants) ?? NO_ASSISTANTS;
  const [createOpen, setCreateOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const [moving, setMoving] = React.useState<string | null>(null);
  const [importing, setImporting] = React.useState(false);
  const presetInput = React.useRef<HTMLInputElement>(null);

  /** A SillyTavern preset file becomes a new assistant; the toast says what was left out or switched off. */
  const importPreset = async (file: File) => {
    setImporting(true);
    try {
      const { assistant, report } = await presetApi.import(file);
      toast.success(
        t("assistants.preset_imported", {
          prompts: report.promptCount,
          regex: report.regexScriptsDisabled,
          skipped: report.connectionKeysSkipped.length,
        }),
      );
      void navigate(`/settings/assistants/${assistant.id}`, { state: { assistant } });
    } catch (error) {
      toast.error(extractErrorMessage(error, t("assistants.import_preset_failed")));
    } finally {
      setImporting(false);
    }
  };

  const move = async (assistant: AssistantProfile, direction: -1 | 1) => {
    const index = assistants.findIndex((item) => item.id === assistant.id);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= assistants.length) {
      return;
    }
    const ids = assistants.map((item) => item.id);
    [ids[index], ids[target]] = [ids[target], ids[index]];
    setMoving(assistant.id);
    try {
      await api.put("assistants/order", { ids });
    } catch (error) {
      reportSettingsError(error, t("common.save_failed"));
    } finally {
      setMoving(null);
    }
  };

  return (
    <div className="space-y-6">
      <SettingsPageHeader
        title={t("assistants.title")}
        description={t("assistants.description")}
        actions={
          <>
            <input
              ref={presetInput}
              type="file"
              accept=".json,application/json"
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = "";
                if (file) void importPreset(file);
              }}
            />
            <Button type="button" variant="outline" disabled={importing} onClick={() => presetInput.current?.click()}>
              <Upload className="size-4" />
              {importing ? t("assistants.importing_preset") : t("assistants.import_preset")}
            </Button>
            <Button type="button" onClick={() => setCreateOpen(true)}>
              <Plus className="size-4" />
              {t("assistants.new")}
            </Button>
          </>
        }
      />

      {assistants.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          {t("assistants.empty")}
        </p>
      ) : (
        <SettingsList>
          {assistants.map((assistant, index) => (
            <SettingsListCard
              key={assistant.id}
              href={`/settings/assistants/${assistant.id}`}
              title={getAssistantDisplayName(assistant.name)}
              description={promptPreviewText(assistant.promptPreset).trim() || undefined}
              badges={
                <>
                  {isDefaultAssistant(assistant.id) ? (
                    <Badge variant="secondary">{t("assistants.default_badge")}</Badge>
                  ) : null}
                  {assistant.enabled === false ? (
                    <Badge variant="outline">{t("assistants.disabled_badge")}</Badge>
                  ) : null}
                </>
              }
              actions={
                <>
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    disabled={index === 0 || moving !== null}
                    aria-label={t("assistants.move_up")}
                    onClick={(event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      void move(assistant, -1);
                    }}
                  >
                    <ChevronUp className="size-4" />
                  </Button>
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    disabled={index === assistants.length - 1 || moving !== null}
                    aria-label={t("assistants.move_down")}
                    onClick={(event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      void move(assistant, 1);
                    }}
                  >
                    <ChevronDown className="size-4" />
                  </Button>
                </>
              }
            />
          ))}
        </SettingsList>
      )}

      <CreateNameDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        title={t("assistants.new")}
        placeholder={t("assistants.create_name_placeholder")}
        submitInHeader
        submitLabel={t("common.save")}
        loading={creating}
        onSubmit={async (name) => {
          setCreating(true);
          try {
            const assistant = await api.post<AssistantProfile>("assistants", {
              name: name.trim() || undefined,
            });
            toast.success(t("common.created"));
            setCreateOpen(false);
            void navigate(`/settings/assistants/${assistant.id}`, { state: { assistant } });
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
