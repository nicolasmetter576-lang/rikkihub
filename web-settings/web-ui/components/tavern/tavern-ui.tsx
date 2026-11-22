import * as React from "react";

import { ChevronDown, ChevronRight } from "lucide-react";

import { Switch } from "~/components/ui/switch";
import { cn } from "~/lib/utils";
import type { TriState } from "../../lib/tavern";

/** A toggle chip (the App's filter chip): one of a set of choices, or an independent on/off option. */
export function Chip({
  selected,
  onClick,
  children,
  disabled,
}: {
  selected: boolean;
  onClick: () => void;
  children: React.ReactNode;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        "rounded-full border px-3 py-1 text-xs transition disabled:opacity-50",
        selected ? "border-primary bg-primary text-primary-foreground" : "text-muted-foreground hover:bg-accent/40",
      )}
    >
      {children}
    </button>
  );
}

export function ChipRow({ children }: { children: React.ReactNode }) {
  return <div className="flex flex-wrap gap-2">{children}</div>;
}

export function FieldLabel({ children }: { children: React.ReactNode }) {
  return <span className="text-xs font-medium text-muted-foreground">{children}</span>;
}

export function Hint({ children }: { children: React.ReactNode }) {
  return <p className="text-xs text-muted-foreground">{children}</p>;
}

/** A switch with a title and an optional explanation underneath. */
export function SwitchField({
  title,
  description,
  checked,
  onCheckedChange,
  disabled,
}: {
  title: string;
  description?: string;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center gap-3">
      <div className="min-w-0 flex-1">
        <p className="text-sm">{title}</p>
        {description ? <Hint>{description}</Hint> : null}
      </div>
      <Switch checked={checked} onCheckedChange={onCheckedChange} disabled={disabled} aria-label={title} />
    </div>
  );
}

/** Follow the global setting / yes / no. */
export function TriStateField({
  title,
  value,
  onSelect,
  labels,
}: {
  title: string;
  value: TriState;
  onSelect: (state: TriState) => void;
  labels: { inherit: string; on: string; off: string };
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-sm">{title}</span>
      <ChipRow>
        {(["inherit", "on", "off"] as const).map((state) => (
          <Chip key={state} selected={value === state} onClick={() => onSelect(state)}>
            {labels[state]}
          </Chip>
        ))}
      </ChipRow>
    </div>
  );
}

/** A collapsible group of fields. */
export function Section({
  title,
  defaultOpen,
  children,
}: {
  title: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = React.useState(defaultOpen ?? false);
  return (
    <div className="border-t">
      <button
        type="button"
        className="flex w-full items-center gap-2 py-3 text-left text-sm font-medium"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
        {title}
      </button>
      {open ? <div className="flex flex-col gap-3 pb-3">{children}</div> : null}
    </div>
  );
}
