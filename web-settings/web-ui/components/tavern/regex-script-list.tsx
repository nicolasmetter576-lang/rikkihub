import * as React from "react";

import { Plus } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { cn } from "~/lib/utils";
import { newRegexScript } from "../../lib/tavern";
import type { RegexScript } from "../../types/tavern";
import { DragGrip, RegexScriptCard, type RegexTools } from "./regex-script-card";

/** Move [id] to where [targetId] is (the others shift) — the same rule as dragging in the App. */
export function reorderScripts(scripts: RegexScript[], id: string, targetId: string): RegexScript[] {
  const from = scripts.findIndex((script) => script.id === id);
  const to = scripts.findIndex((script) => script.id === targetId);
  if (from < 0 || to < 0 || from === to) return scripts;
  const next = [...scripts];
  next.splice(to, 0, next.splice(from, 1)[0]);
  return next;
}

/**
 * A reorderable list of regex script cards with an add button. Every change is handed over as the new
 * list ([onChange]) or, for a single script, through [onScriptChange]/[onScriptDelete]/[onAdd] when the owner saves each
 * change by itself (the global scripts page).
 */
export function RegexScriptList({
  scripts,
  tools,
  live,
  onChange,
  onAdd,
  onScriptChange,
  onScriptDelete,
  onReorder,
}: {
  scripts: RegexScript[];
  tools: RegexTools;
  live?: boolean;
  /** Draft mode: the whole new list. */
  onChange?: (scripts: RegexScript[]) => void;
  /** Autosave mode. */
  onAdd?: (script: RegexScript) => void;
  onScriptChange?: (script: RegexScript, previous: RegexScript) => void;
  onScriptDelete?: (script: RegexScript) => void;
  onReorder?: (ids: string[]) => void;
}) {
  const { t } = useTranslation("tavern");
  const [dragging, setDragging] = React.useState<string | null>(null);
  const [over, setOver] = React.useState<string | null>(null);
  return (
    <div className="flex flex-col gap-2">
      {scripts.map((script) => (
        <div
          key={script.id}
          data-regex-script={script.id}
          className={cn("rounded-lg", over === script.id && dragging !== script.id && "ring-1 ring-primary")}
          onDragOver={(event) => {
            if (dragging === null) return;
            event.preventDefault();
            setOver(script.id);
          }}
          onDrop={(event) => {
            event.preventDefault();
            if (dragging !== null) {
              const next = reorderScripts(scripts, dragging, script.id);
              if (next !== scripts) {
                if (onChange) onChange(next);
                onReorder?.(next.map((item) => item.id));
              }
            }
            setDragging(null);
            setOver(null);
          }}
        >
          <RegexScriptCard
            script={script}
            tools={tools}
            live={live}
            dragHandle={
              <DragGrip
                label={t("layout.drag")}
                onDragStart={(event) => {
                  event.dataTransfer.effectAllowed = "move";
                  event.dataTransfer.setData("text/plain", script.id);
                  setDragging(script.id);
                }}
                onDragEnd={() => {
                  setDragging(null);
                  setOver(null);
                }}
              />
            }
            onChange={(next) => {
              if (onChange) onChange(scripts.map((item) => (item.id === script.id ? next : item)));
              onScriptChange?.(next, script);
            }}
            onDelete={() => {
              if (onChange) onChange(scripts.filter((item) => item.id !== script.id));
              onScriptDelete?.(script);
            }}
          />
        </div>
      ))}
      <Button
        type="button"
        variant="outline"
        onClick={() => {
          const script = newRegexScript(t("regex.new"));
          if (onChange) onChange([...scripts, script]);
          onAdd?.(script);
        }}
      >
        <Plus className="size-4" />
        {t("regex.add")}
      </Button>
    </div>
  );
}
