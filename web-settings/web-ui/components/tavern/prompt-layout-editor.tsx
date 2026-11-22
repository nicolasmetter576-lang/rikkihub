import * as React from "react";

import { ChevronDown, ChevronUp, GripVertical, Plus, SlidersHorizontal, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { Switch } from "~/components/ui/switch";
import { cn } from "~/lib/utils";
import { ConfirmDeleteDialog } from "../settings/settings-ui";
import { CommitInput, CommitInt, CommitTextarea } from "../settings/commit-fields";
import { createLongPress } from "../../lib/long-press";
import {
  contextUnlimited,
  entryDepth,
  entryOrder,
  entryPlacement,
  isPlainPromptEntry,
  newPromptTextEntry,
  optionsOf,
  presetLayout,
  UNLIMITED_CONTEXT,
  withContextSize,
  withEntryAdded,
  withEntryEnabled,
  withEntryMovedTo,
  withEntryRemoved,
  withEntryUpdated,
  withNewChatPrompt,
  withResponseTokens,
  withSquashSystemMessages,
  withWiFormat,
  MARKER_JAILBREAK,
  MARKER_MAIN,
  MARKER_RIKKIHUB_CONTEXT,
} from "../../lib/tavern";
import type { PromptEntry, PromptPreset } from "../../types/tavern";
import { PromptEntryFields } from "./prompt-entry-fields";
import { FieldLabel, Hint, SwitchField } from "./tavern-ui";

/** Display name: built-in entries get a translated name, the rest their own name (or identifier). */
export function useEntryTitle() {
  const { t } = useTranslation("tavern");
  return React.useCallback(
    (entry: PromptEntry): string => {
      if (entry.marker === true) {
        const key: Record<string, string> = {
          worldInfoBefore: "layout.markerWorldInfoBefore",
          worldInfoAfter: "layout.markerWorldInfoAfter",
          dialogueExamples: "layout.markerDialogueExamples",
          chatHistory: "layout.markerChatHistory",
          [MARKER_RIKKIHUB_CONTEXT]: "layout.markerContext",
          personaDescription: "layout.markerPersona",
          charDescription: "layout.markerCharDescription",
          charPersonality: "layout.markerCharPersonality",
          scenario: "layout.markerScenario",
        };
        if (key[entry.identifier]) return t(key[entry.identifier]);
      }
      if (entry.identifier === MARKER_MAIN && entry.name === "Main Prompt") return t("layout.entryMain");
      if (entry.identifier === MARKER_JAILBREAK && entry.name === "Post-History Instructions") return t("layout.entryPostHistory");
      return entry.name?.trim() ? entry.name : entry.identifier;
    },
    [t],
  );
}

/**
 * An assistant's prompt layout (the Prompt Manager): entries in the order they are sent, drag to reorder.
 * Clicking an entry (or its arrow) opens just a large content box; a long press (or the sliders button) opens the
 * advanced dialog — name, role, position, when to send, keep original text, move up/down and delete.
 *
 * In SillyTavern mode every entry is listed (markers are filled in by the app and can only be switched and moved) and
 * the assembly options follow. With it off only the main prompt and the user's own entries are listed; the rest stays in
 * the preset untouched and is not sent either (the app sends `plainLayout`).
 *
 * Changes go to [onChange] as a whole new preset (the page keeps them in its draft until Save).
 */
export function PromptLayoutEditor({
  preset,
  onChange,
  modelContextLength,
  tavernMode = true,
}: {
  preset: PromptPreset;
  onChange: (preset: PromptPreset) => void;
  modelContextLength?: number | null;
  tavernMode?: boolean;
}) {
  const { t } = useTranslation("tavern");
  const entryTitle = useEntryTitle();
  const layout = presetLayout(preset).filter(({ entry }) => tavernMode || isPlainPromptEntry(entry));
  const [dragging, setDragging] = React.useState<string | null>(null);
  const [over, setOver] = React.useState<string | null>(null);
  const [deleting, setDeleting] = React.useState<PromptEntry | null>(null);

  return (
    <div className="flex flex-col gap-2">
      <Hint>{t(tavernMode ? "layout.hint" : "layout.hintPlain")}</Hint>
      <Hint>{t("layout.gestureHint")}</Hint>
      <ul className="flex flex-col gap-2">
        {layout.map(({ entry, enabled }, index) => (
          <li
            key={entry.identifier}
            data-layout-entry={entry.identifier}
            onDragOver={(event) => {
              if (dragging === null) return;
              event.preventDefault();
              setOver(entry.identifier);
            }}
            onDrop={(event) => {
              event.preventDefault();
              if (dragging !== null) onChange(withEntryMovedTo(preset, dragging, entry.identifier));
              setDragging(null);
              setOver(null);
            }}
            className={cn("rounded-lg border", over === entry.identifier && dragging !== entry.identifier && "border-primary")}
          >
            <LayoutEntryCard
              entry={entry}
              enabled={enabled}
              tavernMode={tavernMode}
              title={entryTitle(entry)}
              isFirst={index === 0}
              isLast={index === layout.length - 1}
              onDragStart={(event) => {
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/plain", entry.identifier);
                const card = event.currentTarget.closest("li");
                if (card) event.dataTransfer.setDragImage(card, 16, 16);
                setDragging(entry.identifier);
              }}
              onDragEnd={() => {
                setDragging(null);
                setOver(null);
              }}
              onEnabled={(on) => onChange(withEntryEnabled(preset, entry.identifier, on))}
              // move past the visible neighbour: with SillyTavern mode off, hidden markers sit in between
              onMove={(delta) => {
                const target = layout[index + delta];
                if (target) onChange(withEntryMovedTo(preset, entry.identifier, target.entry.identifier));
              }}
              onChange={(transform) => onChange(withEntryUpdated(preset, entry.identifier, transform))}
              onDelete={() => setDeleting(entry)}
            />
          </li>
        ))}
      </ul>
      <Button
        type="button"
        variant="outline"
        onClick={() => onChange(withEntryAdded(preset, newPromptTextEntry(t("layout.newEntry"))))}
      >
        <Plus className="size-4" />
        {t("layout.add")}
      </Button>
      {tavernMode ? <AssemblyOptionsCard preset={preset} onChange={onChange} modelContextLength={modelContextLength} /> : null}
      <ConfirmDeleteDialog
        open={deleting !== null}
        onOpenChange={(open) => {
          if (!open) setDeleting(null);
        }}
        title={t("layout.deleteTitle")}
        description={t("layout.deleteMessage", { p1: deleting ? entryTitle(deleting) : "" })}
        loading={false}
        onConfirm={() => {
          if (deleting) onChange(withEntryRemoved(preset, deleting.identifier));
          setDeleting(null);
        }}
      />
    </div>
  );
}

function entrySummary(entry: PromptEntry, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (entry.marker === true) return t("layout.marker");
  const role = t(`layout.role${(entry.role ?? "system")[0].toUpperCase()}${(entry.role ?? "system").slice(1)}`);
  const where = entryPlacement(entry) === "in_chat"
    ? t("layout.summaryInChat", { p1: role, p2: entryDepth(entry), p3: entryOrder(entry) })
    : t("layout.summaryRelative", { p1: role });
  const preview = (entry.content ?? "").trim().replace(/\s+/g, " ").slice(0, 60);
  return preview === "" ? where : `${where}\n${preview}`;
}

function LayoutEntryCard({
  entry,
  enabled,
  tavernMode,
  title,
  isFirst,
  isLast,
  onDragStart,
  onDragEnd,
  onEnabled,
  onMove,
  onChange,
  onDelete,
}: {
  entry: PromptEntry;
  enabled: boolean;
  tavernMode: boolean;
  title: string;
  isFirst: boolean;
  isLast: boolean;
  onDragStart: (event: React.DragEvent<HTMLButtonElement>) => void;
  onDragEnd: () => void;
  onEnabled: (enabled: boolean) => void;
  onMove: (delta: number) => void;
  onChange: (transform: (entry: PromptEntry) => PromptEntry) => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation("tavern");
  const [expanded, setExpanded] = React.useState(false);
  const [advanced, setAdvanced] = React.useState(false);
  const [pressing, setPressing] = React.useState(false);
  const isMarker = entry.marker === true;
  // click = content box (markers have none: they open the dialog), long press = advanced dialog
  const actions = {
    onClick: () => (isMarker ? setAdvanced(true) : setExpanded((value) => !value)),
    onLongPress: () => setAdvanced(true),
    onPressingChange: setPressing,
  };
  const latest = React.useRef(actions);
  latest.current = actions;
  const press = React.useMemo(() => createLongPress(() => latest.current), []);
  React.useEffect(() => press.cancel, [press]);
  return (
    <div className={cn(isMarker && "bg-muted/40")}>
      <div className="flex items-center gap-1 pr-2">
        <button
          type="button"
          draggable
          onDragStart={onDragStart}
          onDragEnd={onDragEnd}
          aria-label={t("layout.drag")}
          className="flex size-10 shrink-0 cursor-grab items-center justify-center text-muted-foreground"
        >
          <GripVertical className="size-4" />
        </button>
        <button
          type="button"
          data-entry-title={entry.identifier}
          className={cn(
            "min-w-0 flex-1 select-none rounded-md py-2 text-left transition-colors [-webkit-touch-callout:none]",
            pressing && "bg-primary/5 duration-500",
          )}
          onPointerDown={(event) => press.down(event)}
          onPointerMove={(event) => press.move(event)}
          onPointerUp={() => press.up()}
          onPointerCancel={press.cancel}
          onPointerLeave={press.cancel}
          onContextMenu={(event) => event.preventDefault()}
          onKeyDown={(event) => {
            if (event.key !== "Enter" && event.key !== " ") return;
            event.preventDefault();
            if (event.key === "Enter" && event.shiftKey) setAdvanced(true);
            else latest.current.onClick();
          }}
        >
          <p className="truncate text-sm font-medium">{title}</p>
          <p className="line-clamp-2 whitespace-pre-line text-xs text-muted-foreground">{entrySummary(entry, t)}</p>
        </button>
        <Switch checked={enabled} onCheckedChange={onEnabled} aria-label={title} />
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          aria-label={t("layout.advanced")}
          title={t("layout.advanced")}
          onClick={() => setAdvanced(true)}
        >
          <SlidersHorizontal className="size-4" />
        </Button>
        {!isMarker ? (
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            aria-label={expanded ? t("layout.collapse") : t("layout.expand")}
            aria-expanded={expanded}
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
          </Button>
        ) : null}
      </div>
      {expanded && !isMarker ? (
        <div className="px-3 pb-3">
          <CommitTextarea
            live
            rows={12}
            aria-label={t("layout.entryContent")}
            placeholder={t("layout.entryContent")}
            className="min-h-60"
            value={entry.content ?? ""}
            onCommit={(content) => onChange((current) => ({ ...current, content }))}
          />
        </div>
      ) : null}
      <Dialog open={advanced} onOpenChange={setAdvanced}>
        <DialogContent className="max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle className="truncate">{title}</DialogTitle>
            <DialogDescription>{isMarker ? t("layout.markerDesc") : t("layout.advanced")}</DialogDescription>
          </DialogHeader>
          {!isMarker ? (
            <PromptEntryFields
              entry={entry}
              scope="layout"
              onChange={onChange}
              live
              showContent={false}
              showForbidOverrides={tavernMode}
            />
          ) : null}
          <div className="flex items-center gap-1">
            <Button type="button" size="sm" variant="ghost" disabled={isFirst} onClick={() => onMove(-1)}>
              <ChevronUp className="size-4" />
              {t("layout.moveUp")}
            </Button>
            <Button type="button" size="sm" variant="ghost" disabled={isLast} onClick={() => onMove(1)}>
              <ChevronDown className="size-4" />
              {t("layout.moveDown")}
            </Button>
            <span className="flex-1" />
            {!isMarker ? (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                onClick={() => {
                  setAdvanced(false);
                  onDelete();
                }}
              >
                <Trash2 className="size-4" />
                {t("layout.delete")}
              </Button>
            ) : null}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

const DEFAULT_LIMITED_CONTEXT = 32_000;

/** Where "no limit" starts when switched off: the model's own context length, or a common value. */
function limitedStart(modelContextLength: number | null | undefined): number {
  return modelContextLength !== null && modelContextLength !== undefined && modelContextLength >= 1 && modelContextLength < UNLIMITED_CONTEXT
    ? modelContextLength
    : DEFAULT_LIMITED_CONTEXT;
}

/** The assembly options that matter in rikkihub; the rest stay in the preset untouched. */
function AssemblyOptionsCard({
  preset,
  onChange,
  modelContextLength,
}: {
  preset: PromptPreset;
  onChange: (preset: PromptPreset) => void;
  modelContextLength?: number | null;
}) {
  const { t } = useTranslation("tavern");
  const [expanded, setExpanded] = React.useState(false);
  const options = optionsOf(preset);
  const unlimited = contextUnlimited(preset);
  return (
    <div className="rounded-lg border">
      <button
        type="button"
        className="flex w-full items-center justify-between px-4 py-3 text-left text-sm font-medium"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        {t("layout.optionsTitle")}
        {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
      </button>
      {expanded ? (
        <div className="flex flex-col gap-4 px-4 pb-4">
          <SwitchField
            title={t("layout.contextUnlimited")}
            description={t("layout.contextUnlimitedDesc")}
            checked={unlimited}
            onCheckedChange={(on) => onChange(withContextSize(preset, on ? null : limitedStart(modelContextLength)))}
          />
          {!unlimited ? (
            <>
              <div className="flex flex-col gap-1.5">
                <FieldLabel>{t("layout.contextSize")}</FieldLabel>
                <CommitInt value={options.contextSize} min={1} onCommit={(value) => onChange(withContextSize(preset, value))} />
                {modelContextLength ? (
                  <Hint>{t("layout.contextModel", { p1: modelContextLength.toLocaleString() })}</Hint>
                ) : null}
              </div>
              <div className="flex flex-col gap-1.5">
                <FieldLabel>{t("layout.responseTokens")}</FieldLabel>
                <CommitInt value={options.responseTokens} min={0} onCommit={(value) => onChange(withResponseTokens(preset, value))} />
                <Hint>{t("layout.responseTokensDesc")}</Hint>
              </div>
            </>
          ) : null}
          <SwitchField
            title={t("layout.squash")}
            description={t("layout.squashDesc")}
            checked={options.squashSystemMessages}
            onCheckedChange={(on) => onChange(withSquashSystemMessages(preset, on))}
          />
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("layout.newChatPrompt")}</FieldLabel>
            <CommitInput live value={options.newChatPrompt} onCommit={(text) => onChange(withNewChatPrompt(preset, text))} />
            <Hint>{t("layout.newChatPromptDesc")}</Hint>
          </div>
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("layout.wiFormat")}</FieldLabel>
            <CommitInput live value={options.wiFormat} onCommit={(text) => onChange(withWiFormat(preset, text))} />
            <Hint>{t("layout.wiFormatDesc")}</Hint>
          </div>
        </div>
      ) : null}
    </div>
  );
}
