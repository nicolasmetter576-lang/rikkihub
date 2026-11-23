import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { extractErrorMessage } from "~/lib/error";
import { ApiError } from "~/services/api";
import { SettingsField, SettingsSelectField } from "@overlay/components/settings/settings-ui";
import {
  IMPORT_CHARSETS,
  IMPORT_RULES,
  bookApi,
  bookExportHref,
  type Book,
  type BookExportFormat,
  type BookImportOptions,
  type BookImportPreview,
  type BookImportRule,
} from "@overlay/lib/book";
import { startDownload } from "@overlay/lib/characters";

const AUTO = "auto";
const DEFAULT_OPTIONS: BookImportOptions = { rule: "auto", count: 0, prefix: "", titles: {}, merged: [] };
const RULE_COUNT: Partial<Record<BookImportRule, number>> = { headings: 2, blank: 2, size: 3000 };

/** 导入 TXT / EPUB：先看拆出来的章节，改好再写。文件留在浏览器里，每次预览随请求带上。 */
export function WorkspaceBookImport({
  workspaceId,
  onClose,
  onImported,
}: {
  workspaceId: string;
  onClose: () => void;
  onImported: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [file, setFile] = React.useState<File | null>(null);
  const [options, setOptions] = React.useState<BookImportOptions>(DEFAULT_OPTIONS);
  const [preview, setPreview] = React.useState<BookImportPreview | null>(null);
  const [busy, setBusy] = React.useState(false);
  const latest = React.useRef(0);

  const report = (error: unknown) => {
    const code = error instanceof ApiError ? error.errorCode : undefined;
    const key = code?.startsWith("IMPORT_") ? `book.import_error_${code.slice("IMPORT_".length).toLowerCase()}` : null;
    toast.error(key ? t(key) : extractErrorMessage(error, t("book.import_failed")));
  };

  const load = async (source: File, next: BookImportOptions) => {
    const request = ++latest.current;
    setOptions(next);
    setBusy(true);
    try {
      const result = await bookApi.importPreview(workspaceId, source, next);
      if (request === latest.current) setPreview(result);
    } catch (error) {
      if (request !== latest.current) return;
      setPreview(null);
      report(error);
    } finally {
      if (request === latest.current) setBusy(false);
    }
  };

  // 换了编码或拆法，按下标记的改动就对不上了
  const resplit = (change: Partial<BookImportOptions>) => {
    if (file) void load(file, { ...options, ...change, titles: {}, merged: [] });
  };

  const apply = async () => {
    if (!file) return;
    setBusy(true);
    try {
      await bookApi.importFile(workspaceId, file, options);
      toast.success(t("book.import_done", { count: preview?.rows.length ?? 0 }));
      onImported();
      onClose();
    } catch (error) {
      report(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[85vh] flex-col sm:max-w-2xl" data-testid="book-import">
        <DialogHeader>
          <DialogTitle>{t("book.import_title")}</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-3 overflow-auto">
          <Input
            type="file"
            accept=".txt,.epub,.md,text/plain,application/epub+zip"
            onChange={(event) => {
              const chosen = event.target.files?.[0];
              if (!chosen) return;
              setFile(chosen);
              setPreview(null);
              void load(chosen, DEFAULT_OPTIONS);
            }}
          />
          {!file ? <p className="text-sm text-muted-foreground">{t("book.import_hint")}</p> : null}
          {file && preview?.isText !== false ? (
            <div className="grid gap-2 sm:grid-cols-3">
              <SettingsSelectField
                label={t("book.import_encoding")}
                value={options.charset ?? AUTO}
                onValueChange={(value) => resplit({ charset: value === AUTO ? undefined : value })}
                options={[
                  {
                    value: AUTO,
                    label: preview?.charset && !options.charset ? `${t("book.import_auto")} (${preview.charset})` : t("book.import_auto"),
                  },
                  ...IMPORT_CHARSETS.map((name) => ({ value: name, label: name })),
                ]}
              />
              <SettingsSelectField
                label={t("book.import_rule")}
                value={options.rule}
                onValueChange={(value) => {
                  const rule = value as BookImportRule;
                  const next = { rule, count: RULE_COUNT[rule] ?? 0, prefix: "" };
                  // 开头文字还没填，先不拆
                  if (rule === "prefix") setOptions({ ...options, ...next });
                  else resplit(next);
                }}
                options={IMPORT_RULES.map((rule) => ({ value: rule, label: t(`book.import_rule_${rule}`) }))}
              />
              {options.rule === "prefix" ? (
                <SettingsField label={t("book.import_rule_prefix")}>
                  <Input
                    key="prefix"
                    defaultValue={options.prefix}
                    onBlur={(event) => event.target.value.trim() && resplit({ prefix: event.target.value.trim() })}
                  />
                </SettingsField>
              ) : options.rule !== "auto" ? (
                <SettingsField label={t(`book.import_rule_${options.rule}`)}>
                  <Input
                    key={options.rule}
                    type="number"
                    min={1}
                    defaultValue={options.count}
                    onBlur={(event) => {
                      const count = Number.parseInt(event.target.value, 10);
                      if (count > 0 && count !== options.count) resplit({ count });
                    }}
                  />
                </SettingsField>
              ) : null}
            </div>
          ) : null}
          {preview ? (
            <div className="space-y-1 text-sm">
              <p className="font-medium">
                {t("book.import_summary", {
                  count: preview.rows.length,
                  words: preview.rows.reduce((sum, row) => sum + row.words, 0).toLocaleString(),
                })}
              </p>
              {preview.rows.map((row, index) => (
                <div key={row.source}>
                  {row.volume && row.volume !== preview.rows[index - 1]?.volume ? (
                    <p className="pt-2 font-medium text-primary">{row.volume}</p>
                  ) : null}
                  <div className="flex items-center gap-2">
                    <span className="w-8 shrink-0 text-xs text-muted-foreground">{index + 1}</span>
                    <Input
                      key={row.title}
                      className="h-8 min-w-0 flex-1"
                      defaultValue={row.title}
                      placeholder={t("book.import_opening")}
                      onBlur={(event) => {
                        const title = event.target.value.trim();
                        if (file && title !== row.title) {
                          void load(file, { ...options, titles: { ...options.titles, [row.source]: title } });
                        }
                      }}
                    />
                    {row.issues.length > 0 ? (
                      <span className="text-xs text-destructive">
                        {row.issues.map((issue) => t(`book.import_issue_${issue.toLowerCase()}`)).join(" · ")}
                      </span>
                    ) : null}
                    <span className="w-14 shrink-0 text-right text-xs text-muted-foreground">
                      {row.words.toLocaleString()}
                    </span>
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy || index === 0}
                      onClick={() => file && void load(file, { ...options, merged: [...options.merged, row.source] })}
                    >
                      {t("book.import_merge")}
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("book.cancel")}
          </Button>
          <Button disabled={busy || !preview || preview.rows.length === 0} onClick={() => void apply()}>
            {t("book.import_apply", { count: preview?.rows.length ?? 0 })}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const WHOLE_BOOK = "";
const CHOSEN = "chosen";

/** 导出 TXT、Markdown 或 EPUB，由浏览器下载。 */
export function WorkspaceBookExport({
  workspaceId,
  book,
  onClose,
}: {
  workspaceId: string;
  book: Book;
  onClose: () => void;
}) {
  const { t } = useTranslation("workspace");
  const [format, setFormat] = React.useState<BookExportFormat>("epub");
  const [scope, setScope] = React.useState(WHOLE_BOOK);
  const [chosen, setChosen] = React.useState<ReadonlySet<string>>(new Set());
  const [finalOnly, setFinalOnly] = React.useState(false);
  const [volumeTitles, setVolumeTitles] = React.useState(true);
  const [indent, setIndent] = React.useState(true);
  const [blankLines, setBlankLines] = React.useState(false);

  const volumes = book.items.filter((item) => item.type === "volume");
  let volume: string | undefined;
  const inScope = book.items.filter((item) => {
    if (item.type === "volume") {
      volume = item.id;
      return false;
    }
    if (finalOnly && item.status !== "final") return false;
    return scope === WHOLE_BOOK || (scope === CHOSEN ? chosen.has(item.id) : volume === scope);
  });
  const missing = inScope.filter((item) => item.missing);
  const count = inScope.length - missing.length;

  const toggle = (label: string, checked: boolean, onChange: (value: boolean) => void) => (
    <label className="flex items-center gap-2 text-sm">
      <Checkbox checked={checked} onCheckedChange={(value) => onChange(value === true)} />
      {label}
    </label>
  );

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[85vh] flex-col" data-testid="book-export">
        <DialogHeader>
          <DialogTitle>{t("book.export_title")}</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-3 overflow-auto">
          <SettingsSelectField
            label={t("book.export_format")}
            value={format}
            onValueChange={(value) => setFormat(value as BookExportFormat)}
            options={[
              { value: "epub", label: "EPUB" },
              { value: "txt", label: "TXT" },
              { value: "markdown", label: "Markdown" },
            ]}
          />
          <SettingsSelectField
            label={t("book.export_scope")}
            value={scope || "all"}
            onValueChange={(value) => setScope(value === "all" ? WHOLE_BOOK : value)}
            options={[
              { value: "all", label: t("book.export_all") },
              ...volumes.map((item) => ({ value: item.id, label: item.title })),
              { value: CHOSEN, label: t("book.export_chosen") },
            ]}
          />
          {scope === CHOSEN ? (
            <div className="max-h-60 space-y-1 overflow-auto rounded-xl border p-2">
              {book.items
                .filter((item) => item.type === "chapter" && !item.missing)
                .map((item) => (
                  <React.Fragment key={item.id}>
                    {toggle(`${item.number}  ${item.title}`, chosen.has(item.id), (value) => {
                      const next = new Set(chosen);
                      if (value) next.add(item.id);
                      else next.delete(item.id);
                      setChosen(next);
                    })}
                  </React.Fragment>
                ))}
            </div>
          ) : null}
          {volumes.length > 0 ? toggle(t("book.export_volume_titles"), volumeTitles, setVolumeTitles) : null}
          {toggle(t("book.export_final_only"), finalOnly, setFinalOnly)}
          {format !== "markdown" ? toggle(t("book.export_indent"), indent, setIndent) : null}
          {format === "txt" ? toggle(t("book.export_blank_lines"), blankLines, setBlankLines) : null}
          <p className="text-sm text-muted-foreground">{t("book.export_count", { count })}</p>
          {missing.length > 0 ? (
            <p className="text-sm text-destructive">
              {t("book.export_missing", { files: missing.map((item) => item.title).join("、") })}
            </p>
          ) : null}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("book.cancel")}
          </Button>
          <Button
            disabled={count === 0}
            onClick={() => {
              startDownload(
                bookExportHref(workspaceId, {
                  format,
                  finalOnly,
                  volumeTitles,
                  indent,
                  blankLines,
                  ...(scope === CHOSEN ? { chapters: [...chosen] } : scope ? { volumeId: scope } : {}),
                }),
              );
              onClose();
            }}
          >
            {t("book.export_save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
