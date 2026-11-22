import * as React from "react";

import { useMutation } from "@tanstack/react-query";
import { Settings } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { useEntryTitle } from "@overlay/components/tavern/prompt-layout-editor";
import { isPlainPromptEntry, presetLayout } from "@overlay/lib/tavern";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { extractErrorMessage } from "~/lib/error";
import api from "~/services/api";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { Switch } from "~/components/ui/switch";

/**
 * The + menu prompt tile's quick switches for the current assistant's prompt entries. Each switch is saved at once through a
 * narrow endpoint that changes only that entry on the latest stored assistant; the gear opens the assistant's settings page.
 */
export function AssistantQuickConfigDialog({
  open,
  onClose,
  onOpenSettings,
}: {
  open: boolean;
  onClose: () => void;
  onOpenSettings: (assistantId: string) => void;
}) {
  const { t } = useTranslation("input");
  const { currentAssistant } = useCurrentAssistant();

  return (
    <Dialog open={open && currentAssistant !== null} onOpenChange={(next) => { if (!next) onClose(); }}>
      <DialogContent className="max-h-[80vh] overflow-y-auto sm:max-w-md">
        <DialogHeader className="flex-row items-center justify-between gap-2 pr-8">
          <DialogTitle>{t("chat.prompt_entries_title")}</DialogTitle>
          {currentAssistant ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={t("chat.open_assistant_settings")}
              title={t("chat.open_assistant_settings")}
              onClick={() => onOpenSettings(currentAssistant.id)}
            >
              <Settings className="size-4" />
            </Button>
          ) : null}
        </DialogHeader>
        {currentAssistant ? <PromptEntrySwitches /> : null}
      </DialogContent>
    </Dialog>
  );
}

function SwitchRow({
  label,
  description,
  checked,
  disabled,
  onCheckedChange,
}: {
  label: string;
  description?: string;
  checked: boolean;
  disabled: boolean;
  onCheckedChange: (checked: boolean) => void;
}) {
  return (
    <label className="flex items-center justify-between gap-3 rounded-md px-1 py-1.5">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm">{label}</div>
        {description ? <div className="line-clamp-2 text-xs text-muted-foreground">{description}</div> : null}
      </div>
      <Switch checked={checked} disabled={disabled} onCheckedChange={(next) => onCheckedChange(next === true)} />
    </label>
  );
}

function useAssistantMutation<T>(request: (assistantId: string, value: T) => Promise<unknown>) {
  const { t } = useTranslation("input");
  const { currentAssistant } = useCurrentAssistant();
  const mutation = useMutation({
    mutationFn: (value: T) => request(currentAssistant!.id, value),
    onError: (error) => toast.error(extractErrorMessage(error, t("chat.quick_config_failed"))),
  });
  return { currentAssistant: currentAssistant!, mutation };
}

function PromptEntrySwitches() {
  const entryTitle = useEntryTitle();
  const { currentAssistant, mutation } = useAssistantMutation(
    (assistantId, { identifier, enabled }: { identifier: string; enabled: boolean }) =>
      api.put(`assistants/${assistantId}/prompt-entries/${encodeURIComponent(identifier)}`, { enabled }),
  );
  const preset = currentAssistant.promptPreset;
  // Same entries as the assistant's layout editor: all with SillyTavern mode on, otherwise the main prompt and the user's own
  const entries = React.useMemo(
    () => (preset ? presetLayout(preset) : []).filter(({ entry }) => currentAssistant.tavernMode === true || isPlainPromptEntry(entry)),
    [preset, currentAssistant.tavernMode],
  );
  return (
    <div className="flex flex-col gap-1">
      {entries.map(({ entry, enabled }) => (
        <SwitchRow
          key={entry.identifier}
          label={entryTitle(entry)}
          description={entry.marker === true ? undefined : (entry.content ?? "").trim().replace(/\s+/g, " ").slice(0, 80) || undefined}
          checked={enabled}
          disabled={mutation.isPending}
          onCheckedChange={(checked) => mutation.mutate({ identifier: entry.identifier, enabled: checked })}
        />
      ))}
    </div>
  );
}
