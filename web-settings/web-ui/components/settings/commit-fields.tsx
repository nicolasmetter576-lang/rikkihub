import * as React from "react";

import { Input } from "~/components/ui/input";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";

/**
 * Fields that hand their value over when the user is done with them (leaving the field), like the App's
 * "commit on done" fields: typing never sends a half-written value, and an update that arrives from the other end
 * while the field is not being edited replaces what it shows.
 * With `live` they hand every keystroke over instead (used where a Save button collects the changes).
 */
interface CommitBase {
  value: string;
  onCommit: (value: string) => void;
  live?: boolean;
}

function useCommitText({ value, onCommit, live }: CommitBase) {
  const [text, setText] = React.useState(value);
  const focused = React.useRef(false);
  React.useEffect(() => {
    if (!focused.current) setText(value);
  }, [value]);
  return {
    text,
    onFocus: () => {
      focused.current = true;
    },
    onChange: (next: string) => {
      setText(next);
      if (live) onCommit(next);
    },
    onBlur: () => {
      focused.current = false;
      if (!live && text !== value) onCommit(text);
    },
  };
}

type InputProps = Omit<React.ComponentProps<typeof Input>, "value" | "onChange" | "onBlur" | "onFocus">;

export function CommitInput({ value, onCommit, live, onKeyDown, ...props }: CommitBase & InputProps) {
  const field = useCommitText({ value, onCommit, live });
  return (
    <Input
      {...props}
      value={field.text}
      onFocus={field.onFocus}
      onChange={(event) => field.onChange(event.target.value)}
      onBlur={field.onBlur}
      onKeyDown={(event) => {
        onKeyDown?.(event);
        if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
      }}
    />
  );
}

type TextareaProps = Omit<React.ComponentProps<typeof Textarea>, "value" | "onChange" | "onBlur" | "onFocus">;

export function CommitTextarea({ value, onCommit, live, ...props }: CommitBase & TextareaProps) {
  const field = useCommitText({ value, onCommit, live });
  return (
    <Textarea
      {...props}
      value={field.text}
      onFocus={field.onFocus}
      onChange={(event) => field.onChange(event.target.value)}
      onBlur={field.onBlur}
    />
  );
}

/**
 * An integer field. A value that is not an integer (or outside min/max) is shown in red and not handed over;
 * leaving it puts the last good value back.
 */
export function CommitInt({
  value,
  onCommit,
  min,
  max,
  className,
  ...props
}: {
  value: number;
  onCommit: (value: number) => void;
  min?: number;
  max?: number;
} & Omit<InputProps, "value" | "type">) {
  const [text, setText] = React.useState(String(value));
  const focused = React.useRef(false);
  React.useEffect(() => {
    if (!focused.current) setText(String(value));
  }, [value]);
  const parsed = parseInteger(text);
  const invalid = text.trim() !== "" && (parsed === null || (min !== undefined && parsed < min) || (max !== undefined && parsed > max));
  return (
    <Input
      {...props}
      inputMode="numeric"
      className={cn(invalid && "border-destructive", className)}
      value={text}
      aria-invalid={invalid || undefined}
      onFocus={() => {
        focused.current = true;
      }}
      onChange={(event) => setText(event.target.value)}
      onBlur={() => {
        focused.current = false;
        if (parsed !== null && !invalid && parsed !== value) onCommit(parsed);
        else setText(String(value));
      }}
      onKeyDown={(event) => {
        if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
      }}
    />
  );
}

/** An integer that may be left empty: empty means "not set" (null). */
export function CommitOptionalInt({
  value,
  onCommit,
  min,
  max,
  className,
  ...props
}: {
  value: number | null | undefined;
  onCommit: (value: number | null) => void;
  min?: number;
  max?: number;
} & Omit<InputProps, "value" | "type">) {
  const shown = value === null || value === undefined ? "" : String(value);
  const [text, setText] = React.useState(shown);
  const focused = React.useRef(false);
  React.useEffect(() => {
    if (!focused.current) setText(shown);
  }, [shown]);
  const parsed = parseInteger(text);
  const empty = text.trim() === "";
  const invalid = !empty && (parsed === null || (min !== undefined && parsed < min) || (max !== undefined && parsed > max));
  return (
    <Input
      {...props}
      inputMode="numeric"
      className={cn(invalid && "border-destructive", className)}
      value={text}
      aria-invalid={invalid || undefined}
      onFocus={() => {
        focused.current = true;
      }}
      onChange={(event) => setText(event.target.value)}
      onBlur={() => {
        focused.current = false;
        if (empty) {
          if (shown !== "") onCommit(null);
        } else if (parsed !== null && !invalid) {
          if (parsed !== value) onCommit(parsed);
        } else {
          setText(shown);
        }
      }}
      onKeyDown={(event) => {
        if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
      }}
    />
  );
}

/** `-12` / `7` but not `1.5`, `1e3`, `0x10` or blank. */
export function parseInteger(text: string): number | null {
  const trimmed = text.trim();
  return /^-?\d+$/.test(trimmed) ? Number(trimmed) : null;
}
