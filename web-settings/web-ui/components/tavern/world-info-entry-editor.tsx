import * as React from "react";

import { useTranslation } from "react-i18next";

import { CommitInput, CommitInt, CommitOptionalInt, CommitTextarea } from "../settings/commit-fields";
import {
  entryTriggerSet,
  formatKeyList,
  toggleEntryTrigger,
  triStateOf,
  triStateValue,
  VISIBLE_TRIGGERS,
  withPrimaryKeys,
  withProbability,
  withSecondaryKeys,
} from "../../lib/tavern";
import type { WorldInfoEntry } from "../../types/tavern";
import { Chip, ChipRow, FieldLabel, Hint, Section, SwitchField, TriStateField } from "./tavern-ui";

export type WorldInfoChange = (transform: (entry: WorldInfoEntry) => WorldInfoEntry) => void;

/** `WorldInfoPosition`. */
const POS = { BEFORE: 0, AFTER: 1, AN_TOP: 2, AN_BOTTOM: 3, AT_DEPTH: 4, EM_TOP: 5, EM_BOTTOM: 6, OUTLET: 7 } as const;
const LOGICS = [
  [0, "worldinfo.logicAndAny"],
  [3, "worldinfo.logicAndAll"],
  [2, "worldinfo.logicNotAny"],
  [1, "worldinfo.logicNotAll"],
] as const;
const MAX_SCAN_DEPTH = 1000;
const TRIGGER_KEYS = {
  normal: "layout.triggerNormal",
  regenerate: "layout.triggerRegenerate",
  quiet: "layout.triggerQuiet",
} as const;

/**
 * The fields of a world book entry in seven groups. Fields that only mean something with a character card
 * (matching the card's description, character filters, vectorisation) are not shown; their values stay in the entry.
 */
export function WorldInfoEntryEditor({ entry, onChange }: { entry: WorldInfoEntry; onChange: WorldInfoChange }) {
  const { t } = useTranslation("tavern");
  const tri = { inherit: t("worldinfo.triInherit"), on: t("worldinfo.triOn"), off: t("worldinfo.triOff") };
  const selectedTriggers = entryTriggerSet(entry);

  // "Before/after example dialogues" only show up for entries that already have them (imported books).
  const positions: Array<[number, string]> = [
    [POS.BEFORE, "worldinfo.posBefore"],
    [POS.AFTER, "worldinfo.posAfter"],
    [POS.AN_TOP, "worldinfo.posAnTop"],
    [POS.AN_BOTTOM, "worldinfo.posAnBottom"],
    [POS.AT_DEPTH, "worldinfo.posDepth"],
    [POS.OUTLET, "worldinfo.posOutlet"],
  ];
  if (entry.position === POS.EM_TOP) positions.push([POS.EM_TOP, "worldinfo.posEmTop"]);
  if (entry.position === POS.EM_BOTTOM) positions.push([POS.EM_BOTTOM, "worldinfo.posEmBottom"]);

  return (
    <div className="flex flex-col">
      <Section title={t("worldinfo.sectionBasic")} defaultOpen>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.entryTitle")}</FieldLabel>
          <CommitInput value={entry.comment} onCommit={(comment) => onChange((e) => ({ ...e, comment }))} />
        </div>
        <SwitchField
          title={t("common.enabled")}
          checked={!entry.disable}
          onCheckedChange={(on) => onChange((e) => ({ ...e, disable: !on }))}
        />
        <SwitchField
          title={t("worldinfo.constantActive")}
          description={t("worldinfo.constantActiveDesc")}
          checked={entry.constant}
          onCheckedChange={(on) => onChange((e) => ({ ...e, constant: on }))}
        />
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.keywords")}</FieldLabel>
          <CommitTextarea rows={2} value={formatKeyList(entry.key)} onCommit={(text) => onChange((e) => withPrimaryKeys(e, text))} />
          <Hint>{t("worldinfo.keysHint")}</Hint>
        </div>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.keysSecondary")}</FieldLabel>
          <CommitTextarea rows={2} value={formatKeyList(entry.keysecondary)} onCommit={(text) => onChange((e) => withSecondaryKeys(e, text))} />
          <Hint>{t("worldinfo.keysSecondaryHint")}</Hint>
        </div>
        {entry.keysecondary.length > 0 ? (
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("worldinfo.logic")}</FieldLabel>
            <ChipRow>
              {LOGICS.map(([logic, key]) => (
                <Chip key={logic} selected={entry.selectiveLogic === logic} onClick={() => onChange((e) => ({ ...e, selectiveLogic: logic }))}>
                  {t(key)}
                </Chip>
              ))}
            </ChipRow>
          </div>
        ) : null}
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("layout.entryContent")}</FieldLabel>
          <CommitTextarea rows={6} value={entry.content} onCommit={(content) => onChange((e) => ({ ...e, content }))} />
        </div>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.order")}</FieldLabel>
          <CommitInt value={entry.order} onCommit={(order) => onChange((e) => ({ ...e, order }))} />
          <Hint>{t("worldinfo.orderHint")}</Hint>
        </div>
      </Section>

      <Section title={t("worldinfo.sectionTrigger")}>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.probability")}</FieldLabel>
          <CommitInt value={entry.probability} min={0} onCommit={(value) => onChange((e) => withProbability(e, value))} />
          <Hint>{t("worldinfo.probabilityHint")}</Hint>
        </div>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.scanDepth")}</FieldLabel>
          <CommitOptionalInt
            value={entry.scanDepth}
            min={0}
            max={MAX_SCAN_DEPTH}
            onCommit={(scanDepth) => onChange((e) => ({ ...e, scanDepth }))}
          />
          <Hint>{t("worldinfo.scanDepthHint")}</Hint>
        </div>
        <TriStateField
          title={t("worldinfo.caseSensitive")}
          value={triStateOf(entry.caseSensitive)}
          labels={tri}
          onSelect={(state) => onChange((e) => ({ ...e, caseSensitive: triStateValue(state) }))}
        />
        <TriStateField
          title={t("worldinfo.wholeWords")}
          value={triStateOf(entry.matchWholeWords)}
          labels={tri}
          onSelect={(state) => onChange((e) => ({ ...e, matchWholeWords: triStateValue(state) }))}
        />
        <SwitchField
          title={t("worldinfo.ignoreBudget")}
          description={t("worldinfo.ignoreBudgetDesc")}
          checked={entry.ignoreBudget}
          onCheckedChange={(on) => onChange((e) => ({ ...e, ignoreBudget: on }))}
        />
      </Section>

      <Section title={t("worldinfo.sectionPosition")}>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.position")}</FieldLabel>
          <ChipRow>
            {positions.map(([position, key]) => (
              <Chip key={position} selected={entry.position === position} onClick={() => onChange((e) => ({ ...e, position }))}>
                {t(key)}
              </Chip>
            ))}
          </ChipRow>
        </div>
        {entry.position === POS.AT_DEPTH ? (
          <>
            <div className="flex flex-col gap-1.5">
              <FieldLabel>{t("layout.depth")}</FieldLabel>
              <CommitInt value={entry.depth} min={0} onCommit={(depth) => onChange((e) => ({ ...e, depth }))} />
              <Hint>{t("layout.depthHint")}</Hint>
            </div>
            <div className="flex flex-col gap-1.5">
              <FieldLabel>{t("worldinfo.role")}</FieldLabel>
              <ChipRow>
                {([0, 1, 2] as const).map((role) => (
                  <Chip key={role} selected={(entry.role ?? 0) === role} onClick={() => onChange((e) => ({ ...e, role }))}>
                    {t(["layout.roleSystem", "layout.roleUser", "layout.roleAssistant"][role])}
                  </Chip>
                ))}
              </ChipRow>
            </div>
          </>
        ) : null}
        {entry.position === POS.OUTLET ? (
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("worldinfo.outletName")}</FieldLabel>
            <CommitInput value={entry.outletName} onCommit={(text) => onChange((e) => ({ ...e, outletName: text.trim() }))} />
            <Hint>{t("worldinfo.outletHint")}</Hint>
          </div>
        ) : null}
      </Section>

      <Section title={t("worldinfo.sectionRecursion")}>
        <SwitchField
          title={t("worldinfo.excludeRecursion")}
          description={t("worldinfo.excludeRecursionDesc")}
          checked={entry.excludeRecursion}
          onCheckedChange={(on) => onChange((e) => ({ ...e, excludeRecursion: on }))}
        />
        <SwitchField
          title={t("worldinfo.preventRecursion")}
          description={t("worldinfo.preventRecursionDesc")}
          checked={entry.preventRecursion}
          onCheckedChange={(on) => onChange((e) => ({ ...e, preventRecursion: on }))}
        />
        <SwitchField
          title={t("worldinfo.delayRecursion")}
          description={t("worldinfo.delayRecursionDesc")}
          checked={entry.delayUntilRecursion > 0}
          onCheckedChange={(on) => onChange((e) => ({ ...e, delayUntilRecursion: on ? 1 : 0 }))}
        />
        {entry.delayUntilRecursion > 0 ? (
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("worldinfo.delayRecursionLevel")}</FieldLabel>
            <CommitInt value={entry.delayUntilRecursion} min={1} onCommit={(level) => onChange((e) => ({ ...e, delayUntilRecursion: level }))} />
          </div>
        ) : null}
      </Section>

      <Section title={t("worldinfo.sectionGroup")}>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.group")}</FieldLabel>
          <CommitInput value={entry.group} onCommit={(text) => onChange((e) => ({ ...e, group: text.trim() }))} />
          <Hint>{t("worldinfo.groupHint")}</Hint>
        </div>
        <SwitchField
          title={t("worldinfo.groupOverride")}
          description={t("worldinfo.groupOverrideDesc")}
          checked={entry.groupOverride}
          onCheckedChange={(on) => onChange((e) => ({ ...e, groupOverride: on }))}
        />
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("worldinfo.groupWeight")}</FieldLabel>
          <CommitInt value={entry.groupWeight} min={0} onCommit={(groupWeight) => onChange((e) => ({ ...e, groupWeight }))} />
        </div>
        <TriStateField
          title={t("worldinfo.groupScoring")}
          value={triStateOf(entry.useGroupScoring)}
          labels={tri}
          onSelect={(state) => onChange((e) => ({ ...e, useGroupScoring: triStateValue(state) }))}
        />
      </Section>

      <Section title={t("worldinfo.sectionTiming")}>
        {(["sticky", "cooldown", "delay"] as const).map((field) => (
          <div key={field} className="flex flex-col gap-1.5">
            <FieldLabel>{t(`worldinfo.${field}`)}</FieldLabel>
            <CommitOptionalInt
              value={entry[field]}
              min={0}
              onCommit={(value) => onChange((e) => ({ ...e, [field]: value }))}
            />
            <Hint>{t(`worldinfo.${field}Hint`)}</Hint>
          </div>
        ))}
      </Section>

      <Section title={t("worldinfo.sectionFilter")}>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("layout.triggers")}</FieldLabel>
          <ChipRow>
            {VISIBLE_TRIGGERS.map((type) => (
              <Chip key={type} selected={selectedTriggers.includes(type)} onClick={() => onChange((e) => toggleEntryTrigger(e, type))}>
                {t(TRIGGER_KEYS[type as keyof typeof TRIGGER_KEYS])}
              </Chip>
            ))}
          </ChipRow>
          <Hint>{t("layout.triggersHint")}</Hint>
        </div>
      </Section>
    </div>
  );
}
