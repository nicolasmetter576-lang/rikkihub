import * as React from "react";

import { useTranslation } from "react-i18next";

import { presetRegexScripts, withPresetRegexScripts } from "../../lib/tavern";
import type { PromptPreset } from "../../types/tavern";
import { PromptLayoutEditor } from "./prompt-layout-editor";
import { useRegexTools } from "./regex-tools";
import { RegexScriptList } from "./regex-script-list";
import { Hint, SwitchField } from "./tavern-ui";

/**
 * The prompt part of the assistant page: the SillyTavern mode switch, the layout of entries and assembly options, then
 * the assistant's own regex scripts. Layout and regex live in the assistant's preset; the page keeps the edited preset
 * and the switch in its draft until Save.
 */
export function AssistantPromptSection({
  assistantId,
  preset,
  onChange,
  modelContextLength,
  tavernMode,
  onTavernModeChange,
}: {
  assistantId: string;
  preset: PromptPreset;
  onChange: (preset: PromptPreset) => void;
  modelContextLength?: number | null;
  tavernMode: boolean;
  onTavernModeChange: (tavernMode: boolean) => void;
}) {
  const { t } = useTranslation("tavern");
  const tools = useRegexTools(assistantId);
  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-col gap-3" data-section="tavern-mode">
        <SwitchField
          title={t("layout.tavernMode")}
          description={t("layout.tavernModeDesc")}
          checked={tavernMode}
          onCheckedChange={onTavernModeChange}
        />
      </section>
      <section className="flex flex-col gap-3" data-section="prompt-layout">
        <h3 className="text-sm font-medium">{t("layout.title")}</h3>
        <PromptLayoutEditor
          preset={preset}
          onChange={onChange}
          modelContextLength={modelContextLength}
          tavernMode={tavernMode}
        />
      </section>
      <section className="flex flex-col gap-3" data-section="regex-scripts">
        <div>
          <h3 className="text-sm font-medium">{t("regex.assistantTitle")}</h3>
          <Hint>{t("regex.assistantDesc")}</Hint>
        </div>
        <RegexScriptList
          live
          scripts={presetRegexScripts(preset)}
          tools={tools}
          onChange={(scripts) => onChange(withPresetRegexScripts(preset, scripts))}
        />
      </section>
    </div>
  );
}
