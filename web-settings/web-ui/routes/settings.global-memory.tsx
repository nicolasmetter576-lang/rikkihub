import { useTranslation } from "react-i18next";

import { GlobalMemoryPanel } from "@overlay/components/settings/assistant-memory-panel";
import { SettingsPageHeader } from "@overlay/components/settings/settings-ui";

export default function SettingsGlobalMemoryPage() {
  const { t } = useTranslation("settings");
  return (
    <div className="space-y-4">
      <SettingsPageHeader
        title={t("assistants.memory.global_title")}
        description={t("assistants.memory.global_page_description")}
      />
      <GlobalMemoryPanel />
    </div>
  );
}
