import { useTranslation } from "react-i18next";

import { useSettingsStore } from "~/stores";
import { CommitInput, CommitTextarea } from "@overlay/components/settings/commit-fields";
import { SettingsPageHeader, reportSettingsError } from "@overlay/components/settings/settings-ui";
import { FieldLabel, Hint } from "@overlay/components/tavern/tavern-ui";
import { personaApi } from "@overlay/lib/characters";

/** Who the user is in the story: the name `{{user}}` turns into, and the persona description sent with every request. */
export default function SettingsPersonaPage() {
  const { t } = useTranslation("tavern");
  const nickname = useSettingsStore((state) => state.settings?.displaySetting?.userNickname) ?? "";
  const description = useSettingsStore((state) => state.settings?.tavern?.personaDescription) ?? "";

  const save = async (patch: { nickname?: string; description?: string }) => {
    try {
      await personaApi.save(patch);
    } catch (error) {
      reportSettingsError(error, t("common.saveFailed"));
    }
  };

  return (
    <div className="space-y-6">
      <SettingsPageHeader title={t("persona.title")} />
      <div className="flex max-w-xl flex-col gap-5">
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("persona.name")}</FieldLabel>
          <CommitInput value={nickname} onCommit={(value) => void save({ nickname: value })} />
          <Hint>{t("persona.nameHint", { user: "{{user}}" })}</Hint>
        </div>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("persona.description")}</FieldLabel>
          <CommitTextarea rows={8} value={description} onCommit={(value) => void save({ description: value })} />
          <Hint>{t("persona.descriptionHint", { persona: "{{persona}}" })}</Hint>
        </div>
      </div>
    </div>
  );
}
