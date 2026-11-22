import * as React from "react";

import { ArrowLeft } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import { CommitInt } from "@overlay/components/settings/commit-fields";
import { FieldLabel, Hint, SwitchField } from "@overlay/components/tavern/tavern-ui";
import { useAutosaved } from "@overlay/hooks/use-autosaved";
import type { WorldInfoSettings } from "@overlay/types/tavern";

/** What the App shows when the server has not sent the block yet (the engine defaults). */
const DEFAULTS: WorldInfoSettings = {
  world_info_depth: 2,
  world_info_min_activations: 0,
  world_info_min_activations_depth_max: 0,
  world_info_budget: 25,
  world_info_include_names: true,
  world_info_recursive: false,
  world_info_overflow_alert: false,
  world_info_case_sensitive: false,
  world_info_match_whole_words: false,
  world_info_use_group_scoring: false,
  world_info_character_strategy: 1,
  world_info_budget_cap: 0,
  world_info_max_recursion_steps: 0,
};

/** Global world info settings: how far back to scan, the budget, recursion and the matching defaults entries fall back to. */
export default function SettingsWorldInfoPage() {
  const { t } = useTranslation("tavern");
  const remote = useSettingsStore((state) => state.settings?.tavern?.worldInfo) ?? DEFAULTS;
  const { value, change } = useAutosaved<WorldInfoSettings>(
    remote,
    (patch) => api.put<WorldInfoSettings>("tavern/world-info", patch),
    (error) => reportSettingsError(error, t("common.saveFailed")),
  );
  const set = (patch: Partial<WorldInfoSettings>) => change((current) => ({ ...current, ...patch }));

  const intField = (key: keyof WorldInfoSettings, label: string, hint: string, min = 0, max?: number) => (
    <div className="flex flex-col gap-1.5">
      <FieldLabel>{label}</FieldLabel>
      <CommitInt value={value[key] as number} min={min} max={max} onCommit={(next) => set({ [key]: next })} />
      <Hint>{hint}</Hint>
    </div>
  );

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <Button variant="ghost" size="sm" asChild>
          <Link to="/settings/extensions?tab=lorebook">
            <ArrowLeft className="size-4" />
            {t("worldinfo.backToBooks")}
          </Link>
        </Button>
        <h2 className="text-lg font-semibold">{t("worldinfo.globalSettings")}</h2>
        <p className="text-sm text-muted-foreground">{t("worldinfo.globalSettingsDesc")}</p>
      </div>

      <div className="flex max-w-xl flex-col gap-5">
        {intField("world_info_depth", t("worldinfo.setScanDepth"), t("worldinfo.setScanDepthHint"), 0, 1000)}
        {intField("world_info_budget", t("worldinfo.setBudget"), t("worldinfo.setBudgetHint"), 1, 100)}
        {intField("world_info_budget_cap", t("worldinfo.setBudgetCap"), t("worldinfo.setBudgetCapHint"))}
        {intField("world_info_min_activations", t("worldinfo.setMinActivations"), t("worldinfo.setMinActivationsHint"))}
        {intField("world_info_min_activations_depth_max", t("worldinfo.setMinActivationsDepth"), t("worldinfo.setMinActivationsDepthHint"))}
        {intField("world_info_max_recursion_steps", t("worldinfo.setMaxRecursion"), t("worldinfo.setMaxRecursionHint"))}
        <SwitchField
          title={t("worldinfo.setRecursive")}
          description={t("worldinfo.setRecursiveDesc")}
          checked={value.world_info_recursive}
          onCheckedChange={(on) => set({ world_info_recursive: on })}
        />
        <SwitchField
          title={t("worldinfo.setIncludeNames")}
          description={t("worldinfo.setIncludeNamesDesc")}
          checked={value.world_info_include_names}
          onCheckedChange={(on) => set({ world_info_include_names: on })}
        />
        <SwitchField
          title={t("worldinfo.caseSensitive")}
          description={t("worldinfo.setDefaultDesc")}
          checked={value.world_info_case_sensitive}
          onCheckedChange={(on) => set({ world_info_case_sensitive: on })}
        />
        <SwitchField
          title={t("worldinfo.setWholeWords")}
          description={t("worldinfo.setDefaultDesc")}
          checked={value.world_info_match_whole_words}
          onCheckedChange={(on) => set({ world_info_match_whole_words: on })}
        />
        <SwitchField
          title={t("worldinfo.setGroupScoring")}
          description={t("worldinfo.setGroupScoringDesc")}
          checked={value.world_info_use_group_scoring}
          onCheckedChange={(on) => set({ world_info_use_group_scoring: on })}
        />
        <div>
          <Button
            type="button"
            variant="outline"
            onClick={async () => {
              try {
                await api.delete("tavern/world-info");
                toast.success(t("worldinfo.setResetDone"));
              } catch (error) {
                reportSettingsError(error, t("common.saveFailed"));
              }
            }}
          >
            {t("worldinfo.setReset")}
          </Button>
        </div>
      </div>
    </div>
  );
}
