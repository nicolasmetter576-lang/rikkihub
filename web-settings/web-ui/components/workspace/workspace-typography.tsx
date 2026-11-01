import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Minus, Plus, Type } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "~/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Slider } from "~/components/ui/slider";
import { Switch } from "~/components/ui/switch";
import { useSettingsStore } from "~/stores/settings";
import {
  EDITOR_FONT_IDS,
  EDITOR_TYPOGRAPHY_LIMITS,
  WEB_EDITOR_TYPOGRAPHY_DEFAULT,
  editorFontFamilyCss,
  editorFontsApi,
  editorFontsQueryKey,
  editorTypographyStyle,
  ensureEditorFont,
  formatFontSize,
  normalizeEditorTypography,
  sameEditorTypography,
  stepEditorTypographyValue,
  type EditorFontsResponse,
  type EditorTypography,
} from "@overlay/lib/editor-typography";

// 本地先生效的改动（所有编辑器共用一份），防抖写入；设置事件回来与之相同后清掉。
let pending: EditorTypography | null = null;
const listeners = new Set<() => void>();
let writeTimer: ReturnType<typeof setTimeout> | null = null;
const emit = () => listeners.forEach((listener) => listener());
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

function writeTypography(next: EditorTypography, onError: () => void) {
  pending = next;
  emit();
  if (writeTimer) clearTimeout(writeTimer);
  writeTimer = setTimeout(() => {
    writeTimer = null;
    editorFontsApi.putTypography(next).catch(() => {
      if (pending === next) {
        pending = null;
        emit();
      }
      onError();
    });
  }, 300);
}

/** Web 编辑器的排版（px）：设置里的值，加上尚未写回的本地改动。 */
export function useEditorTypography(): {
  typography: EditorTypography;
  update: (next: EditorTypography) => void;
} {
  const { t } = useTranslation("workspace");
  const stored = useSettingsStore((state) => state.settings?.displaySetting?.webWorkspaceEditorTypography);
  const base = React.useMemo(() => normalizeEditorTypography(stored), [stored]);
  const local = React.useSyncExternalStore(subscribe, () => pending, () => null);
  React.useEffect(() => {
    if (pending && !writeTimer && sameEditorTypography(pending, base)) {
      pending = null;
      emit();
    }
  }, [base, local]);
  const update = React.useCallback(
    (next: EditorTypography) => writeTypography(next, () => toast.error(t("typography.save_failed"))),
    [t],
  );
  return { typography: local ?? base, update };
}

/** 字体列表（下载状态、可加载的文件版本）；有下载进行中时每秒刷新。 */
export function useEditorFonts() {
  return useQuery({
    queryKey: editorFontsQueryKey,
    queryFn: editorFontsApi.list,
    staleTime: 60_000,
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.fonts.some((font) => font.download?.status === "downloading") ? 1000 : false,
  });
}

/** 排版 + 把所选字体注册进 document.fonts（本机字体优先，其次手机上的文件）。 */
export function useEditorTypographyWithFont() {
  const result = useEditorTypography();
  const fonts = useEditorFonts();
  const font = result.typography.font;
  const version = fonts.data?.files[font];
  React.useEffect(() => {
    void ensureEditorFont(font, version);
  }, [font, version]);
  return result;
}

function fontLabel(id: string, fonts: EditorFontsResponse | undefined, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (id === "system") return t("typography.font_system");
  if (id === "serif") return t("typography.font_serif");
  if (id === "jetbrains_mono") return "JetBrains Mono";
  if (id === "custom") return t("typography.font_custom", { name: fonts?.customName ?? "…" });
  return fonts?.fonts.find((font) => font.id === id)?.name ?? id;
}

function FontDownloadRow({ id, fonts }: { id: string; fonts: EditorFontsResponse | undefined }) {
  const { t } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const info = fonts?.fonts.find((font) => font.id === id);
  if (!info) return null;
  const refresh = () => void queryClient.invalidateQueries({ queryKey: editorFontsQueryKey });
  const run = (action: () => Promise<unknown>) => {
    action().then(refresh, (error) => {
      refresh();
      toast.error(error instanceof Error ? error.message : String(error));
    });
  };
  const state = info.download;
  const percent = state ? Math.floor((state.bytesRead * 100) / Math.max(1, state.totalBytes)) : 0;
  let text: string;
  let action: React.ReactNode;
  if (state?.status === "downloading") {
    text = t("typography.downloading", { percent });
    action = <Button type="button" size="sm" variant="ghost" onClick={() => run(() => editorFontsApi.cancel(id))}>{t("typography.cancel")}</Button>;
  } else if (state?.status === "failed") {
    text = t("typography.failed", { error: state.error ?? "" });
    action = <Button type="button" size="sm" variant="ghost" onClick={() => run(() => editorFontsApi.download(id))}>{t("typography.retry")}</Button>;
  } else if (info.installed) {
    text = t("typography.downloaded", { size: formatFontSize(info.sizeBytes) });
    action = <Button type="button" size="sm" variant="ghost" onClick={() => run(() => editorFontsApi.remove(id))}>{t("typography.delete")}</Button>;
  } else {
    text = t("typography.not_downloaded", { size: formatFontSize(info.sizeBytes) });
    action = <Button type="button" size="sm" variant="ghost" onClick={() => run(() => editorFontsApi.download(id))}>{t("typography.download")}</Button>;
  }
  return (
    <div className="space-y-1" data-testid="font-download-row">
      <div className="flex items-center gap-2">
        <p className={state?.status === "failed" ? "flex-1 text-xs text-destructive" : "flex-1 text-xs text-muted-foreground"}>{text}</p>
        {action}
      </div>
      {state?.status === "downloading" ? (
        <div className="h-1 overflow-hidden rounded bg-muted">
          <div className="h-full bg-primary transition-[width]" style={{ width: `${percent}%` }} />
        </div>
      ) : null}
      <p className="text-[11px] text-muted-foreground">
        {t("typography.license", { license: info.license })}
        {" · "}
        {t("typography.local_hint", { family: info.family })}
      </p>
    </div>
  );
}

function Stepper({
  label,
  field,
  value,
  format,
  onChange,
}: {
  label: string;
  field: keyof typeof EDITOR_TYPOGRAPHY_LIMITS;
  value: number;
  format: (value: number) => string;
  onChange: (value: number) => void;
}) {
  const { min, max, step } = EDITOR_TYPOGRAPHY_LIMITS[field];
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between text-xs">
        <span>{label}</span>
        <span className="text-muted-foreground tabular-nums">{format(value)}</span>
      </div>
      <div className="flex items-center gap-2">
        <Button type="button" size="icon-sm" variant="ghost" aria-label={`${label} −`} disabled={value <= min}
          onClick={() => onChange(stepEditorTypographyValue(field, value - step))}>
          <Minus className="size-3.5" />
        </Button>
        <Slider className="flex-1" min={min} max={max} step={step} value={[value]} aria-label={label}
          onValueChange={([next]) => onChange(stepEditorTypographyValue(field, next ?? value))} />
        <Button type="button" size="icon-sm" variant="ghost" aria-label={`${label} +`} disabled={value >= max}
          onClick={() => onChange(stepEditorTypographyValue(field, value + step))}>
          <Plus className="size-3.5" />
        </Button>
      </div>
    </div>
  );
}

/** 编辑器标签栏上的「Aa」：字体（含下载）、字号、字间距、行间距、实时预览、恢复默认。 */
export function WorkspaceTypographyButton({ className }: { className?: string }) {
  const { t } = useTranslation("workspace");
  const [open, setOpen] = React.useState(false);
  const { typography, update } = useEditorTypography();
  const fonts = useEditorFonts();
  const choices = EDITOR_FONT_IDS.filter((id) => id !== "custom" || Boolean(fonts.data?.customName) || typography.font === id);
  const set = (patch: Partial<EditorTypography>) => update({ ...typography, ...patch });
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" data-testid="typography-button" title={t("typography.title")} aria-label={t("typography.title")}
          className={className ?? "rounded px-1.5 py-1 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"}>
          <Type className="size-3.5" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 space-y-4">
        <p className="text-sm font-medium">{t("typography.title")}</p>
        <div className="space-y-2">
          <p className="text-xs">{t("typography.font")}</p>
          <Select value={typography.font} onValueChange={(font) => set({ font })}>
            <SelectTrigger className="w-full" aria-label={t("typography.font")}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {choices.map((id) => (
                <SelectItem key={id} value={id}>
                  <span style={{ fontFamily: editorFontFamilyCss(id) }}>{fontLabel(id, fonts.data, t)}</span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FontDownloadRow id={typography.font} fonts={fonts.data} />
        </div>
        <Stepper label={t("typography.font_size")} field="fontSize" value={typography.fontSize}
          format={(v) => `${Math.round(v)} px`} onChange={(fontSize) => set({ fontSize })} />
        <Stepper label={t("typography.letter_spacing")} field="letterSpacing" value={typography.letterSpacing}
          format={(v) => `${v.toFixed(2)} em`} onChange={(letterSpacing) => set({ letterSpacing })} />
        <Stepper label={t("typography.line_height")} field="lineHeight" value={typography.lineHeight}
          format={(v) => `${v.toFixed(2)}×`} onChange={(lineHeight) => set({ lineHeight })} />
        <p className="whitespace-pre-line rounded-md bg-muted/60 p-3" style={editorTypographyStyle(typography)} data-testid="typography-preview">
          {t("typography.preview")}
        </p>
        <AutoPairSwitch />
        <div className="flex justify-between gap-2">
          <p className="text-[11px] text-muted-foreground">{t("typography.web_only")}</p>
          <Button type="button" size="sm" variant="outline" disabled={sameEditorTypography(typography, WEB_EDITOR_TYPOGRAPHY_DEFAULT)}
            onClick={() => update(WEB_EDITOR_TYPOGRAPHY_DEFAULT)}>
            {t("typography.reset")}
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** 自动补全成对符号 for the Web editor (the App has its own); shown at once, reverted if the write fails. */
function AutoPairSwitch() {
  const { t } = useTranslation("workspace");
  const stored = useSettingsStore((state) => state.settings?.displaySetting?.webWorkspaceEditorAutoPair !== false);
  const [pending, setPending] = React.useState<boolean | null>(null);
  React.useEffect(() => {
    if (pending !== null && pending === stored) setPending(null);
  }, [pending, stored]);
  const checked = pending ?? stored;
  return (
    <div className="flex items-start justify-between gap-3 border-t pt-3">
      <div className="space-y-0.5">
        <p className="text-xs">{t("writing.auto_pair")}</p>
        <p className="text-[11px] text-muted-foreground">{t("writing.auto_pair_hint")}</p>
      </div>
      <Switch data-testid="auto-pair-switch" checked={checked} aria-label={t("writing.auto_pair")} onCheckedChange={(next) => {
        setPending(next);
        editorFontsApi.putEditor({ autoPair: next }).catch(() => {
          setPending(null);
          toast.error(t("writing.auto_pair_failed"));
        });
      }} />
    </div>
  );
}
