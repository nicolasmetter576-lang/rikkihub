import * as React from "react";

import { ChevronDown, ChevronUp, GripVertical, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";
import { CommitInput, CommitOptionalInt, CommitTextarea } from "../settings/commit-fields";
import {
  normalizeFindRegex,
  REGEX_AI_OUTPUT,
  REGEX_REASONING,
  REGEX_USER_INPUT,
  REGEX_WORLD_INFO,
  scriptAffects,
  SUBSTITUTE_ESCAPED,
  SUBSTITUTE_NONE,
  SUBSTITUTE_RAW,
  withScriptPlacement,
} from "../../lib/tavern";
import type { RegexCheckResult, RegexScript } from "../../types/tavern";
import { Chip, ChipRow, FieldLabel, Hint, SwitchField } from "./tavern-ui";

const PLACEMENTS = [
  [REGEX_USER_INPUT, "regex.placementUser"],
  [REGEX_AI_OUTPUT, "regex.placementAssistant"],
  [REGEX_WORLD_INFO, "regex.placementWorldInfo"],
  [REGEX_REASONING, "regex.placementReasoning"],
] as const;

const SUBSTITUTES = [
  [SUBSTITUTE_NONE, "regex.substituteNone"],
  [SUBSTITUTE_RAW, "regex.substituteRaw"],
  [SUBSTITUTE_ESCAPED, "regex.substituteEscaped"],
] as const;

export interface RegexTools {
  /** Can this find string compile? Checked after the value is committed, not on every keystroke. */
  check: (findRegex: string, substituteRegex: number) => Promise<RegexCheckResult>;
  /** Run the script alone over a sample text, with the real engine. */
  tryOut: (script: RegexScript, sample: string) => Promise<string>;
}

/**
 * One regex script: folded it is a name and a switch; open it has every field (find, replace, placements,
 * display-only / prompt-only / run-on-edit, depth range, macros in the find string, trim, try-out).
 * Assistant scripts and global scripts share it.
 */
export function RegexScriptCard({
  script,
  tools,
  onChange,
  onDelete,
  live,
  dragHandle,
}: {
  script: RegexScript;
  tools: RegexTools;
  onChange: (script: RegexScript) => void;
  onDelete: () => void;
  live?: boolean;
  dragHandle?: React.ReactNode;
}) {
  const { t } = useTranslation("tavern");
  const [expanded, setExpanded] = React.useState(false);
  return (
    <div className="rounded-lg border">
      <div className="flex items-center gap-2 pr-2">
        {dragHandle ?? <span className="w-2" />}
        <button type="button" className="min-w-0 flex-1 py-3 text-left" onClick={() => setExpanded((value) => !value)}>
          <p className="truncate text-sm font-medium">{script.scriptName.trim() ? script.scriptName : t("regex.unnamed")}</p>
        </button>
        <Switch
          checked={!script.disabled}
          aria-label={script.scriptName}
          onCheckedChange={(enabled) => onChange({ ...script, disabled: !enabled })}
        />
        <Button type="button" size="icon-sm" variant="ghost" aria-label={t(expanded ? "layout.collapse" : "layout.expand")} onClick={() => setExpanded((value) => !value)}>
          {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
        </Button>
      </div>
      {expanded ? <RegexScriptFields script={script} tools={tools} onChange={onChange} onDelete={onDelete} live={live} /> : null}
    </div>
  );
}

function RegexScriptFields({
  script,
  tools,
  onChange,
  onDelete,
  live,
}: {
  script: RegexScript;
  tools: RegexTools;
  onChange: (script: RegexScript) => void;
  onDelete: () => void;
  live?: boolean;
}) {
  const { t } = useTranslation("tavern");

  // The committed find string is what gets checked; a pattern with macros can only be checked when it runs.
  const [check, setCheck] = React.useState<RegexCheckResult | null>(null);
  React.useEffect(() => {
    let cancelled = false;
    tools.check(script.findRegex, script.substituteRegex).then(
      (result) => !cancelled && setCheck(result),
      () => !cancelled && setCheck(null),
    );
    return () => {
      cancelled = true;
    };
  }, [tools, script.findRegex, script.substituteRegex]);

  return (
    <div className="flex flex-col gap-3 px-4 pb-4">
      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("regex.assistantName")}</FieldLabel>
        <CommitInput live={live} value={script.scriptName} onCommit={(scriptName) => onChange({ ...script, scriptName })} />
      </div>

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("regex.assistantFindRegex")}</FieldLabel>
        <CommitInput
          className={cn("font-mono", check?.status === "invalid" && "border-destructive")}
          placeholder="/\b\w+@\w+\.\w+\b/g"
          value={script.findRegex}
          onCommit={(findRegex) => onChange({ ...script, findRegex: normalizeFindRegex(findRegex) })}
        />
        {check?.status === "invalid" ? (
          <p className="text-xs text-destructive">
            {check.message ? t("regex.findInvalidDetail", { p1: check.message }) : t("regex.findInvalid")}
          </p>
        ) : (
          <Hint>{check?.status === "skipped" ? t("regex.findSkipped") : t("regex.findHint")}</Hint>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("regex.assistantReplaceString")}</FieldLabel>
        <CommitTextarea
          live={live}
          rows={2}
          placeholder="[EMAIL]"
          value={script.replaceString}
          onCommit={(replaceString) => onChange({ ...script, replaceString })}
        />
        <Hint>{t("regex.replaceHint")}</Hint>
      </div>

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("regex.trim")}</FieldLabel>
        <CommitTextarea
          live={live}
          rows={2}
          value={script.trimStrings.join("\n")}
          onCommit={(text) => onChange({ ...script, trimStrings: text.split("\n").filter((line) => line !== "") })}
        />
        <Hint>{t("regex.trimHint")}</Hint>
      </div>

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("regex.assistantAffectingScopes")}</FieldLabel>
        <ChipRow>
          {PLACEMENTS.map(([point, key]) => (
            <Chip
              key={point}
              selected={scriptAffects(script, point)}
              onClick={() => onChange(withScriptPlacement(script, point, !scriptAffects(script, point)))}
            >
              {t(key)}
            </Chip>
          ))}
        </ChipRow>
      </div>

      <SwitchField
        title={t("regex.markdownOnly")}
        description={t("regex.markdownOnlyDesc")}
        checked={script.markdownOnly}
        onCheckedChange={(markdownOnly) => onChange({ ...script, markdownOnly })}
      />
      <SwitchField
        title={t("regex.promptOnly")}
        description={t("regex.promptOnlyDesc")}
        checked={script.promptOnly}
        onCheckedChange={(promptOnly) => onChange({ ...script, promptOnly })}
      />
      <SwitchField
        title={t("regex.runOnEdit")}
        description={t("regex.runOnEditDesc")}
        checked={script.runOnEdit}
        onCheckedChange={(runOnEdit) => onChange({ ...script, runOnEdit })}
      />

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("regex.depth")}</FieldLabel>
        <div className="grid grid-cols-2 gap-3">
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("regex.depthMin")}</FieldLabel>
            <CommitOptionalInt value={script.minDepth} onCommit={(minDepth) => onChange({ ...script, minDepth })} />
          </div>
          <div className="flex flex-col gap-1.5">
            <FieldLabel>{t("regex.depthMax")}</FieldLabel>
            <CommitOptionalInt value={script.maxDepth} onCommit={(maxDepth) => onChange({ ...script, maxDepth })} />
          </div>
        </div>
        <Hint>{t("regex.depthHint")}</Hint>
      </div>

      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("regex.substitute")}</FieldLabel>
        <ChipRow>
          {SUBSTITUTES.map(([mode, key]) => (
            <Chip key={mode} selected={script.substituteRegex === mode} onClick={() => onChange({ ...script, substituteRegex: mode })}>
              {t(key)}
            </Chip>
          ))}
        </ChipRow>
      </div>

      <TryRun script={script} tools={tools} />

      <div>
        <Button type="button" size="sm" variant="ghost" onClick={onDelete}>
          <Trash2 className="size-4" />
          {t("layout.delete")}
        </Button>
      </div>
    </div>
  );
}

/** Try-out: type a sample and see what this script alone does to it (placements, depth and the on/off switch do not apply). */
function TryRun({ script, tools }: { script: RegexScript; tools: RegexTools }) {
  const { t } = useTranslation("tavern");
  const [sample, setSample] = React.useState("");
  const [result, setResult] = React.useState<string | null>(null);
  React.useEffect(() => {
    if (sample === "") {
      setResult(null);
      return;
    }
    let cancelled = false;
    setResult(null);
    const timer = setTimeout(() => {
      tools.tryOut(script, sample).then(
        (text) => !cancelled && setResult(text),
        (error: unknown) => !cancelled && setResult(error instanceof Error ? error.message : String(error)),
      );
    }, 250); // do not run it for every typed character
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [tools, script, sample]);
  return (
    <div className="flex flex-col gap-1.5">
      <FieldLabel>{t("regex.try")}</FieldLabel>
      <Textarea rows={2} value={sample} onChange={(event) => setSample(event.target.value)} />
      <Hint>{t("regex.tryHint")}</Hint>
      {result !== null ? (
        <pre
          data-regex-result
          className={cn(
            "whitespace-pre-wrap break-words rounded-md bg-muted/50 p-2 font-mono text-sm",
            result === sample ? "text-muted-foreground" : "text-primary",
          )}
        >
          {result === "" ? t("regex.tryEmpty") : result}
        </pre>
      ) : null}
    </div>
  );
}

/** A grip a script card or layout entry can be dragged by (native drag and drop). */
export function DragGrip({
  label,
  onDragStart,
  onDragEnd,
}: {
  label: string;
  onDragStart: (event: React.DragEvent<HTMLButtonElement>) => void;
  onDragEnd: () => void;
}) {
  return (
    <button
      type="button"
      draggable
      aria-label={label}
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      className="flex size-10 shrink-0 cursor-grab items-center justify-center text-muted-foreground"
    >
      <GripVertical className="size-4" />
    </button>
  );
}
