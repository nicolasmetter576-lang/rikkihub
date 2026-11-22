import { useTranslation } from "react-i18next";

import { Input } from "~/components/ui/input";
import { parseOptionalNumber } from "../../lib/settings";
import type { AssistantProfile } from "../../types/settings";
import { SettingsField } from "./settings-ui";

type SamplingKey = "topK" | "frequencyPenalty" | "presencePenalty" | "repetitionPenalty" | "minP" | "topA" | "seed";

interface SamplingField {
  key: SamplingKey;
  locale: string;
  min: number;
  max?: number;
  step: number;
  integer?: boolean;
}

/** The extra sampling parameters SillyTavern presets bring; empty means "not sent". Ranges match the app and the server. */
export const SAMPLING_FIELDS: readonly SamplingField[] = [
  { key: "topK", locale: "top_k", min: 1, max: 100000, step: 1, integer: true },
  { key: "frequencyPenalty", locale: "frequency_penalty", min: -2, max: 2, step: 0.1 },
  { key: "presencePenalty", locale: "presence_penalty", min: -2, max: 2, step: 0.1 },
  { key: "repetitionPenalty", locale: "repetition_penalty", min: 0.01, max: 3, step: 0.05 },
  { key: "minP", locale: "min_p", min: 0, max: 1, step: 0.01 },
  { key: "topA", locale: "top_a", min: 0, max: 1, step: 0.01 },
  { key: "seed", locale: "seed", min: 0, step: 1, integer: true },
];

/** What an input's text means for a field: empty or not a number is "not sent", whole-number fields drop the fraction. */
export function parseSamplingValue(field: SamplingField, text: string): number | null {
  const parsed = parseOptionalNumber(text);
  if (parsed === null) return null;
  return field.integer ? Math.trunc(parsed) : parsed;
}

/** The values to send when saving: every sampling key explicitly, so clearing a field clears it on the server too. */
export function samplingPayload(draft: AssistantProfile): Record<SamplingKey, number | null> {
  const out = {} as Record<SamplingKey, number | null>;
  for (const field of SAMPLING_FIELDS) out[field.key] = draft[field.key] ?? null;
  return out;
}

export function SamplingFields({
  draft,
  onChange,
}: {
  draft: AssistantProfile;
  onChange: (patch: Partial<AssistantProfile>) => void;
}) {
  const { t } = useTranslation("settings");
  return (
    <div className="col-span-full space-y-3 rounded-xl border p-3">
      <div className="space-y-1">
        <h3 className="text-sm font-semibold">{t("assistants.fields.sampling_title")}</h3>
        <p className="text-xs text-muted-foreground">{t("assistants.hints.sampling_desc")}</p>
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        {SAMPLING_FIELDS.map((field) => (
          <SettingsField
            key={field.key}
            label={t(`assistants.fields.${field.locale}`)}
            hint={t(`assistants.hints.${field.locale}`)}
          >
            <Input
              type="number"
              step={field.step}
              min={field.min}
              max={field.max}
              value={draft[field.key] ?? ""}
              onChange={(event) => onChange({ [field.key]: parseSamplingValue(field, event.target.value) })}
            />
          </SettingsField>
        ))}
      </div>
    </div>
  );
}
