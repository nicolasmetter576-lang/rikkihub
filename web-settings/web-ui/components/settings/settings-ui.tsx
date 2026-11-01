import * as React from "react";

import { Link } from "react-router";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "~/components/ui/select";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";

export function SettingsField({
  label,
  hint,
  children,
  className,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <label className={cn("flex flex-col gap-1.5", className)}>
      <span className="text-sm font-medium">{label}</span>
      {children}
      {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </label>
  );
}

export function SettingsSwitchRow({
  label,
  checked,
  onCheckedChange,
  disabled,
}: {
  label: string;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-md border px-3 py-2">
      <span className="text-sm">{label}</span>
      <Switch checked={checked} onCheckedChange={onCheckedChange} disabled={disabled} />
    </div>
  );
}

export function SettingsSelectField({
  label,
  value,
  onValueChange,
  options,
  placeholder,
  className,
}: {
  label: string;
  value: string;
  onValueChange: (value: string) => void;
  options: Array<{ value: string; label: string }>;
  placeholder?: string;
  className?: string;
}) {
  return (
    <SettingsField label={label} className={className}>
      <Select value={value} onValueChange={onValueChange}>
        <SelectTrigger className="w-full">
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </SettingsField>
  );
}

export function SettingsCheckboxGroup({
  label,
  items,
  selected,
  onChange,
}: {
  label: string;
  items: Array<{ id: string; label: string; disabled?: boolean }>;
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-medium">{label}</span>
      <div className="flex flex-col gap-1 rounded-md border p-2">
        {items.length === 0 ? (
          <span className="px-1 py-2 text-xs text-muted-foreground">—</span>
        ) : (
          items.map((item) => {
            const checked = selected.includes(item.id);
            return (
              <label key={item.id} className="flex items-center gap-2 rounded-sm px-1 py-1.5 text-sm">
                <Checkbox
                  checked={checked}
                  disabled={item.disabled}
                  onCheckedChange={(value) => {
                    const next = value === true
                      ? [...selected, item.id]
                      : selected.filter((id) => id !== item.id);
                    onChange(next);
                  }}
                />
                <span className="truncate">{item.label}</span>
              </label>
            );
          })
        )}
      </div>
    </div>
  );
}

export function SettingsSegmented<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (value: T) => void;
  options: Array<{ value: T; label: string }>;
}) {
  return (
    <div className="bg-muted inline-flex rounded-full p-1">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          className={cn(
            "rounded-full px-3 py-1 text-xs transition",
            value === option.value
              ? "bg-background text-foreground shadow-sm"
              : "text-muted-foreground",
          )}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function SettingsListCard({
  title,
  description,
  href,
  badges,
  actions,
}: {
  title: string;
  description?: string;
  href?: string;
  badges?: React.ReactNode;
  actions?: React.ReactNode;
}) {
  const content = (
    <div className="flex items-start justify-between gap-3 rounded-xl border p-4">
      <div className="min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="truncate font-medium">{title}</p>
          {badges}
        </div>
        {description ? (
          <p className="line-clamp-2 text-sm text-muted-foreground">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-1">{actions}</div> : null}
    </div>
  );

  if (!href) {
    return content;
  }

  return (
    <Link to={href} className="block transition hover:bg-accent/30">
      {content}
    </Link>
  );
}

export function ConfirmDeleteDialog({
  open,
  onOpenChange,
  title,
  description,
  onConfirm,
  loading,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  onConfirm: () => Promise<void> | void;
  loading?: boolean;
}) {
  const { t } = useTranslation("settings");
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t("common.cancel")}
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={loading}
            onClick={() => {
              void onConfirm();
            }}
          >
            {loading ? t("common.deleting") : t("common.delete")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function CreateNameDialog({
  open,
  onOpenChange,
  title,
  placeholder,
  extra,
  initialName,
  submitLabel,
  submittingLabel,
  footerStart,
  submitInHeader,
  onSubmit,
  loading,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  placeholder?: string;
  extra?: React.ReactNode;
  initialName?: string;
  submitLabel?: string;
  submittingLabel?: string;
  footerStart?: React.ReactNode;
  submitInHeader?: boolean;
  onSubmit: (name: string) => Promise<void> | void;
  loading?: boolean;
}) {
  const { t } = useTranslation("settings");
  const [name, setName] = React.useState("");
  const formId = React.useId();

  React.useEffect(() => {
    if (open) {
      setName(initialName ?? "");
    }
  }, [open, initialName]);

  const submitButton = (
    <Button type="submit" form={formId} disabled={loading}>
      {loading
        ? (submittingLabel ?? t("common.creating"))
        : (submitLabel ?? t("common.create"))}
    </Button>
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader className={submitInHeader ? "flex-row items-center justify-between space-y-0" : undefined}>
          <DialogTitle>{title}</DialogTitle>
          {submitInHeader ? submitButton : null}
        </DialogHeader>
        <form
          id={formId}
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            void onSubmit(name);
          }}
        >
          {extra}
          <Input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder={placeholder}
            autoFocus
          />
          <DialogFooter>
            {footerStart ? <div className="mr-auto">{footerStart}</div> : null}
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            {submitInHeader ? null : submitButton}
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function reportSettingsError(error: unknown, fallback: string) {
  toast.error(extractErrorMessage(error, fallback));
}

export function SettingsPageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
      <div className="space-y-1">
        <h1 className="text-xl font-semibold">{title}</h1>
        {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function SettingsFormActions({
  onSave,
  saving,
  extra,
}: {
  onSave: () => void;
  saving?: boolean;
  extra?: React.ReactNode;
}) {
  const { t } = useTranslation("settings");
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button type="button" onClick={onSave} disabled={saving}>
        {saving ? t("common.saving") : t("common.save")}
      </Button>
      {extra}
    </div>
  );
}

export { Input, Textarea };
