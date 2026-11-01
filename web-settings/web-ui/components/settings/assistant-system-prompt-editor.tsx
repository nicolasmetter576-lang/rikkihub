import * as React from "react";

import { ChevronDown, ChevronUp, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "~/components/ui/toggle-group";
import {
  composeSystemPrompt,
  movePromptSection,
  newPromptSection,
  promptSectionsOf,
  promptSegmentsOf,
  remapPromptSegments,
  switchSystemPromptMode,
  systemPromptModeOf,
  withPromptSections,
  withPromptSegments,
  type PromptSegment,
} from "@overlay/lib/settings";
import { createId } from "@overlay/lib/id";
import type { AssistantProfile, PromptSection } from "@overlay/types/settings";

/**
 * Assistant system prompt in two views of the same data: the whole view shows the joined
 * sections and writes edits back into the matching sections; the sections view edits them
 * one by one (ordered, individually switchable).
 */
export function AssistantSystemPromptEditor({
  assistant,
  onChange,
}: {
  assistant: Pick<AssistantProfile, "systemPrompt" | "systemPromptMode" | "promptSections">;
  onChange: (patch: Partial<AssistantProfile>) => void;
}) {
  const { t } = useTranslation("settings");
  const mode = systemPromptModeOf(assistant);
  const sections = promptSectionsOf(assistant);
  // Whole view over sections: keep its own segment map while it is open, so a piece emptied by an
  // edit keeps its place (and its blank line) until the view is reopened.
  const [whole, setWhole] = React.useState<{ segments: PromptSegment[]; cleared: string[] } | null>(null);
  const wholeView = mode === "whole" && sections.length > 0
    ? (whole ?? { segments: promptSegmentsOf(sections, createId()), cleared: [] })
    : null;

  const updateSections = (next: PromptSection[]) => onChange(withPromptSections(next));
  const updateSection = (id: string, patch: Partial<PromptSection>) => {
    updateSections(sections.map((section) => (section.id === id ? { ...section, ...patch } : section)));
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium">{t("assistants.fields.system_prompt")}</span>
        <ToggleGroup
          type="single"
          variant="outline"
          size="sm"
          value={mode}
          onValueChange={(value) => {
            if (value === "whole" || value === "sections") {
              setWhole(null);
              onChange(switchSystemPromptMode(assistant, value, t("assistants.prompt_sections.seed_title")));
            }
          }}
        >
          <ToggleGroupItem value="whole">{t("assistants.prompt_sections.mode_whole")}</ToggleGroupItem>
          <ToggleGroupItem value="sections">{t("assistants.prompt_sections.mode_sections")}</ToggleGroupItem>
        </ToggleGroup>
      </div>

      {mode === "whole" ? (
        <>
          <Textarea
            rows={8}
            aria-label={t("assistants.fields.system_prompt")}
            value={wholeView ? wholeView.segments.map((segment) => segment.text).join("\n\n") : composeSystemPrompt(assistant)}
            onChange={(event) => {
              const text = event.target.value;
              if (!wholeView) {
                onChange({ systemPrompt: text });
                return;
              }
              const remap = remapPromptSegments(wholeView.segments, text);
              const cleared = [...wholeView.cleared, ...remap.cleared];
              setWhole({ segments: remap.segments, cleared });
              onChange(withPromptSegments(sections, remap.segments, cleared));
            }}
          />
          {wholeView ? (
            <p className="text-xs text-muted-foreground">{t("assistants.prompt_sections.whole_merged_hint")}</p>
          ) : null}
        </>
      ) : (
        <>
          <p className="text-xs text-muted-foreground">{t("assistants.prompt_sections.desc")}</p>
          {sections.map((section, index) => (
            <div key={section.id} data-prompt-section={section.id} className="flex flex-col gap-2 rounded-md border p-3">
              <div className="flex items-center gap-2">
                <Input
                  className="flex-1"
                  value={section.title}
                  placeholder={t("assistants.prompt_sections.untitled")}
                  aria-label={t("assistants.prompt_sections.title")}
                  onChange={(event) => updateSection(section.id, { title: event.target.value })}
                />
                <Switch
                  checked={section.enabled}
                  aria-label={t("assistants.prompt_sections.enabled")}
                  onCheckedChange={(checked) => updateSection(section.id, { enabled: checked })}
                />
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  disabled={index === 0}
                  aria-label={t("assistants.prompt_sections.move_up")}
                  onClick={() => updateSections(movePromptSection(sections, index, -1))}
                >
                  <ChevronUp className="size-4" />
                </Button>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  disabled={index === sections.length - 1}
                  aria-label={t("assistants.prompt_sections.move_down")}
                  onClick={() => updateSections(movePromptSection(sections, index, 1))}
                >
                  <ChevronDown className="size-4" />
                </Button>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t("common.delete")}
                  onClick={() => updateSections(sections.filter((item) => item.id !== section.id))}
                >
                  <Trash2 className="size-4" />
                </Button>
              </div>
              <Textarea
                rows={4}
                aria-label={t("assistants.prompt_sections.content")}
                value={section.content}
                onChange={(event) => updateSection(section.id, { content: event.target.value })}
              />
            </div>
          ))}
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => updateSections([...sections, newPromptSection()])}
            >
              <Plus className="size-4" />
              {t("assistants.prompt_sections.add")}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
