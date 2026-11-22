import * as React from "react";

import { useTranslation } from "react-i18next";

import { CommitInput, CommitInt, CommitTextarea } from "../settings/commit-fields";
import {
  entryDepth,
  entryOrder,
  entryPlacement,
  entryTriggers,
  libraryAnchor,
  toggleTrigger,
  VISIBLE_TRIGGERS,
  withLibraryMeta,
  withPlacement,
} from "../../lib/tavern";
import type { LibraryAnchor, PromptEntry, PromptRole } from "../../types/tavern";
import { Chip, ChipRow, FieldLabel, Hint, SwitchField } from "./tavern-ui";

export type EntryScope = "layout" | "library";
export type EntryChange = (transform: (entry: PromptEntry) => PromptEntry) => void;

const ROLES: PromptRole[] = ["system", "user", "assistant"];
const ANCHORS: LibraryAnchor[] = ["before_system", "after_system", "before_history"];
const ANCHOR_KEYS: Record<LibraryAnchor, string> = {
  before_system: "layout.anchorBeforeSystem",
  after_system: "layout.anchorAfterSystem",
  before_history: "layout.anchorBeforeHistory",
};
const TRIGGER_KEYS = {
  normal: "layout.triggerNormal",
  regenerate: "layout.triggerRegenerate",
  quiet: "layout.triggerQuiet",
} as const;

/**
 * Editing fields of a prompt entry: name, role, position (in order / in chat with depth and order), trigger types
 * and content. The assistant's layout and the prompt library share them; a relative library entry also has an anchor
 * and calls its order "priority".
 */
export function PromptEntryFields({
  entry,
  scope,
  onChange,
  live,
  showName = true,
  showContent = true,
  showForbidOverrides = true,
}: {
  entry: PromptEntry;
  scope: EntryScope;
  onChange: EntryChange;
  live?: boolean;
  showName?: boolean;
  /** The layout's advanced dialog leaves the content out (it is edited in the entry card). */
  showContent?: boolean;
  /** "Keep original text" only matters in SillyTavern mode (only a character card replaces the main prompt). */
  showForbidOverrides?: boolean;
}) {
  const { t } = useTranslation("tavern");
  const placement = entryPlacement(entry);
  const selectedTriggers = entryTriggers(entry);
  return (
    <div className="flex flex-col gap-3">
      {showName ? (
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("layout.entryName")}</FieldLabel>
          <CommitInput
            live={live}
            value={entry.name ?? ""}
            onCommit={(name) => onChange((current) => ({ ...current, name }))}
          />
        </div>
      ) : null}

      <ChipRow>
        {ROLES.map((role) => (
          <Chip
            key={role}
            selected={(entry.role ?? "system") === role}
            onClick={() => onChange((current) => ({ ...current, role }))}
          >
            {t(`layout.role${role[0].toUpperCase()}${role.slice(1)}`)}
          </Chip>
        ))}
      </ChipRow>

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("layout.position")}</FieldLabel>
        <ChipRow>
          <Chip selected={placement === "relative"} onClick={() => onChange((current) => withPlacement(current, "relative"))}>
            {t("layout.positionRelative")}
          </Chip>
          <Chip selected={placement === "in_chat"} onClick={() => onChange((current) => withPlacement(current, "in_chat"))}>
            {t("layout.positionInChat")}
          </Chip>
        </ChipRow>
      </div>

      {placement === "in_chat" ? (
        <>
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <FieldLabel>{t("layout.depth")}</FieldLabel>
              <CommitInt
                value={entryDepth(entry)}
                min={0}
                onCommit={(depth) => onChange((current) => withPlacement(current, "in_chat", { depth }))}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <FieldLabel>{t("layout.order")}</FieldLabel>
              <CommitInt
                value={entryOrder(entry)}
                onCommit={(order) => onChange((current) => withPlacement(current, "in_chat", { order }))}
              />
            </div>
          </div>
          <Hint>{t("layout.depthHint")}</Hint>
          <Hint>{t("layout.orderHint")}</Hint>
        </>
      ) : scope === "library" ? (
        <>
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("layout.anchor")}</FieldLabel>
            <ChipRow>
              {ANCHORS.map((anchor) => (
                <Chip
                  key={anchor}
                  selected={(libraryAnchor(entry) ?? "after_system") === anchor}
                  onClick={() => onChange((current) => withLibraryMeta(current, { anchor }))}
                >
                  {t(ANCHOR_KEYS[anchor])}
                </Chip>
              ))}
            </ChipRow>
          </div>
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("layout.priority")}</FieldLabel>
            <CommitInt
              value={entryOrder(entry)}
              onCommit={(order) => onChange((current) => ({ ...current, injection_order: order }))}
            />
            <Hint>{t("layout.priorityHint")}</Hint>
          </div>
        </>
      ) : null}

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("layout.triggers")}</FieldLabel>
        <ChipRow>
          {VISIBLE_TRIGGERS.map((type) => (
            <Chip
              key={type}
              selected={selectedTriggers.includes(type)}
              onClick={() => onChange((current) => toggleTrigger(current, type))}
            >
              {t(TRIGGER_KEYS[type as keyof typeof TRIGGER_KEYS])}
            </Chip>
          ))}
        </ChipRow>
        <Hint>{t("layout.triggersHint")}</Hint>
      </div>

      {scope === "layout" && showForbidOverrides && entry.system_prompt === true ? (
        <SwitchField
          title={t("layout.forbidOverrides")}
          description={t("layout.forbidOverridesDesc")}
          checked={entry.forbid_overrides === true}
          onCheckedChange={(on) => onChange((current) => ({ ...current, forbid_overrides: on }))}
        />
      ) : null}

      {showContent ? (
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("layout.entryContent")}</FieldLabel>
          <CommitTextarea
            live={live}
            rows={6}
            value={entry.content ?? ""}
            onCommit={(content) => onChange((current) => ({ ...current, content }))}
          />
        </div>
      ) : null}
    </div>
  );
}
