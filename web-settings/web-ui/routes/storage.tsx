import * as React from "react";

import { Link } from "react-router";
import {
  ArrowLeft,
  BrushCleaning,
  Download,
  Eye,
  File as FileIcon,
  Image as ImageIcon,
  Loader2,
  Lock,
  RefreshCw,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

/** Radix Checkbox 的 checked/onCheckedChange 值类型。 */
type CheckedState = boolean | "indeterminate";

import { Badge } from "~/components/ui/badge";
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
import { Spinner } from "~/components/ui/spinner";
import { ApiError } from "~/services/api";
import { SettingsPageHeader, reportSettingsError } from "@overlay/components/settings/settings-ui";
import {
  isPreviewable,
  storageApi,
  storageDownloadUrl,
  storagePreviewUrl,
  type StorageDeleteResult,
  type StorageFileDto,
} from "@overlay/lib/storage";
import {
  formatBytes,
  formatEpochMillis,
  isProtectedFile,
  summarizeCleanupItems,
} from "@overlay/lib/storage-utils";

const PAGE_SIZE = 50;
/** 与后端 STORAGE_CLEANUP_ALLOWED_DAYS 一致 */
const CLEANUP_DAYS = [7, 14, 30] as const;

export default function StoragePage() {
  const { t } = useTranslation("storage");

  // ---- 列表状态 ----
  const [files, setFiles] = React.useState<StorageFileDto[]>([]);
  const [total, setTotal] = React.useState(0);
  const [totalSizeBytes, setTotalSizeBytes] = React.useState(0);
  const [overallTotal, setOverallTotal] = React.useState(0);
  const [overallSizeBytes, setOverallSizeBytes] = React.useState(0);
  const [loading, setLoading] = React.useState(true);
  const [loadingMore, setLoadingMore] = React.useState(false);
  const [listError, setListError] = React.useState<string | null>(null);
  const [query, setQuery] = React.useState("");
  const [queryInput, setQueryInput] = React.useState("");
  const [ageFilter, setAgeFilter] = React.useState<string>("all");
  const listAbortRef = React.useRef<AbortController | null>(null);

  // ---- 选择/操作状态 ----
  const [selected, setSelected] = React.useState<Set<number>>(new Set());
  const [confirmDelete, setConfirmDelete] = React.useState<StorageFileDto[] | null>(null);
  const [deleting, setDeleting] = React.useState(false);
  const [confirmCleanup, setConfirmCleanup] = React.useState<number | null>(null);
  const [cleaning, setCleaning] = React.useState(false);
  const [cleanupResults, setCleanupResults] = React.useState<StorageDeleteResult[] | null>(null);
  const [previewTarget, setPreviewTarget] = React.useState<StorageFileDto | null>(null);

  const load = React.useCallback(
    async (offset: number, append: boolean) => {
      listAbortRef.current?.abort();
      const abort = new AbortController();
      listAbortRef.current = abort;
      if (append) {
        setLoadingMore(true);
      } else {
        setLoading(true);
      }
      setListError(null);
      try {
        const res = await storageApi.list(
          {
            offset,
            limit: PAGE_SIZE,
            query: query || undefined,
            olderThanDays: ageFilter === "all" ? undefined : Number(ageFilter),
          },
          { signal: abort.signal },
        );
        setFiles((prev) => (append ? [...prev, ...res.files] : res.files));
        setTotal(res.total);
        setTotalSizeBytes(res.totalSizeBytes);
        setOverallTotal(res.overallTotal);
        setOverallSizeBytes(res.overallSizeBytes);
      } catch (error) {
        if (abort.signal.aborted) return;
        // 401 已由全局 web-auth-gate 弹登录框；这里展示一般错误
        setListError(error instanceof Error ? error.message : t("list.load_failed"));
        if (!append) setFiles([]);
      } finally {
        if (!abort.signal.aborted) {
          setLoading(false);
          setLoadingMore(false);
        }
      }
    },
    [query, ageFilter, t],
  );

  React.useEffect(() => {
    setSelected(new Set());
    void load(0, false);
    return () => listAbortRef.current?.abort();
  }, [load]);

  const hasMore = files.length < total;

  // ---- 选择（只允许未保护项）----
  const selectable = files.filter((f) => !isProtectedFile(f));
  const allSelectableChecked =
    selectable.length > 0 && selectable.every((f) => selected.has(f.id));
  const someChecked = files.some((f) => selected.has(f.id));

  const toggleFile = (file: StorageFileDto, checked: CheckedState) => {
    if (isProtectedFile(file)) return;
    setSelected((prev) => {
      const next = new Set(prev);
      if (checked === true) {
        next.add(file.id);
      } else {
        next.delete(file.id);
      }
      return next;
    });
  };

  const toggleAll = (checked: CheckedState) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (checked === true) {
        selectable.forEach((f) => next.add(f.id));
      } else {
        files.forEach((f) => next.delete(f.id));
      }
      return next;
    });
  };

  // ---- 删除 ----
  const runDelete = async (targets: StorageFileDto[]) => {
    setDeleting(true);
    const results: StorageDeleteResult[] = [];
    for (const target of targets) {
      try {
        results.push(await storageApi.delete(target.id));
      } catch (error) {
        // 409 受保护（竞态变化）/ 404 已消失 / 500 盘删失败
        if (error instanceof ApiError && error.code === 409) {
          results.push({ status: "skipped", id: target.id, reasons: ["protected"] });
        } else if (error instanceof ApiError && error.code === 404) {
          results.push({ status: "skipped", id: target.id, reasons: ["gone"], error: "not_found" });
        } else {
          results.push({
            status: "failed",
            id: target.id,
            error: error instanceof Error ? error.message : "unknown",
          });
        }
      }
    }
    setDeleting(false);
    setConfirmDelete(null);
    setCleanupResults(results);
    setSelected(new Set());
    void load(0, false);
  };

  const runCleanup = async (days: number) => {
    setCleaning(true);
    try {
      const res = await storageApi.cleanup({ olderThanDays: days });
      setCleanupResults(res.items);
      setSelected(new Set());
      void load(0, false);
      if (res.truncated) {
        toast.warning(t("cleanup.truncated", { count: res.items.length }));
      }
    } catch (error) {
      reportSettingsError(error, t("cleanup.failed"));
    } finally {
      setCleaning(false);
      setConfirmCleanup(null);
    }
  };

  const reasonLabel = (reason: string) => t(`reason.${reason}`, { defaultValue: reason });

  // ---- 渲染 ----
  return (
    <div className="flex min-h-svh flex-col bg-background">
      <header className="sticky top-0 z-20 border-b bg-background/90 backdrop-blur">
        <div className="mx-auto flex w-full max-w-6xl items-center gap-3 px-4 py-3">
          <Button variant="ghost" size="sm" asChild>
            <Link to="/">
              <ArrowLeft className="size-4" />
              {t("nav.back_to_chat")}
            </Link>
          </Button>
          <h1 className="text-sm font-semibold">{t("nav.title")}</h1>
        </div>
      </header>

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <div className="space-y-4">
          <SettingsPageHeader
            title={t("page.title")}
            description={t("page.description")}
            actions={
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={loading}
                  onClick={() => void load(0, false)}
                >
                  {loading ? (
                    <Loader2 className="size-4 animate-spin" />
                  ) : (
                    <RefreshCw className="size-4" />
                  )}
                  {t("list.refresh")}
                </Button>
              </div>
            }
          />

          {/* 保护语义说明 + 24h 离线草稿限制（如实告知，不承诺永存） */}
          <div className="rounded-xl border border-dashed bg-muted/30 px-4 py-3 text-xs leading-5 text-muted-foreground">
            <p>{t("page.notice_protected")}</p>
            <p>{t("page.notice_draft_window")}</p>
          </div>

          {/* 总量条 */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
            <span>
              {t("list.total", {
                count: overallTotal,
                size: formatBytes(overallSizeBytes),
              })}
            </span>
            {(query || ageFilter !== "all") && (
              <span>
                {t("list.filtered", {
                  count: total,
                  size: formatBytes(totalSizeBytes),
                })}
              </span>
            )}
          </div>

          {/* 过滤条 */}
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <div className="relative flex-1">
              <Search className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={queryInput}
                onChange={(e) => setQueryInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") setQuery(queryInput);
                }}
                placeholder={t("list.search_placeholder")}
                className="pl-9"
              />
            </div>
            <div className="flex items-center gap-2">
              <Select value={ageFilter} onValueChange={setAgeFilter}>
                <SelectTrigger className="w-[150px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">{t("filter.age_all")}</SelectItem>
                  {CLEANUP_DAYS.map((d) => (
                    <SelectItem key={d} value={String(d)}>
                      {t("filter.age_days", { days: d })}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button
                type="button"
                variant="secondary"
                disabled={loading}
                onClick={() => setQuery(queryInput)}
              >
                {t("list.apply")}
              </Button>
              {(loading || loadingMore) && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => listAbortRef.current?.abort()}
                >
                  <X className="size-4" />
                  {t("list.cancel")}
                </Button>
              )}
            </div>
          </div>

          {/* 批量条 */}
          {selected.size > 0 && (
            <div className="flex items-center justify-between rounded-lg border bg-accent/40 px-3 py-2">
              <span className="text-sm">{t("list.selected", { count: selected.size })}</span>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setSelected(new Set())}
                >
                  {t("list.clear_selection")}
                </Button>
                <Button
                  type="button"
                  variant="destructive"
                  size="sm"
                  disabled={deleting}
                  onClick={() => setConfirmDelete(files.filter((f) => selected.has(f.id)))}
                >
                  <Trash2 className="size-4" />
                  {t("list.delete_selected")}
                </Button>
              </div>
            </div>
          )}

          {/* 列表 */}
          {loading ? (
            <div className="flex items-center justify-center py-16">
              <Spinner className="size-6" />
            </div>
          ) : listError ? (
            <div className="rounded-xl border border-destructive/40 bg-destructive/5 px-4 py-10 text-center">
              <p className="text-sm text-destructive">{listError}</p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-3"
                onClick={() => void load(0, false)}
              >
                {t("list.retry")}
              </Button>
            </div>
          ) : files.length === 0 ? (
            <div className="rounded-xl border border-dashed px-4 py-16 text-center">
              <FileIcon className="mx-auto mb-3 size-8 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">{t("list.empty")}</p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-xl border">
              {/* 表头（sm+） */}
              <div className="hidden items-center gap-3 border-b bg-muted/40 px-3 py-2 text-xs text-muted-foreground sm:flex">
                <Checkbox
                  checked={
                    allSelectableChecked ? true : someChecked ? "indeterminate" : false
                  }
                  onCheckedChange={toggleAll}
                  disabled={selectable.length === 0}
                  aria-label={t("list.select_all")}
                />
                <span className="w-10" />
                <span className="flex-1">{t("col.name")}</span>
                <span className="w-28">{t("col.type")}</span>
                <span className="w-20 text-right">{t("col.size")}</span>
                <span className="w-28">{t("col.time")}</span>
                <span className="w-44">{t("col.protection")}</span>
                <span className="w-20" />
              </div>

              <div className="divide-y">
                {files.map((file) => (
                  <FileRow
                    key={file.id}
                    file={file}
                    checked={selected.has(file.id)}
                    disabled={isProtectedFile(file)}
                    reasonLabel={reasonLabel}
                    onToggle={(checked) => toggleFile(file, checked)}
                    onPreview={isPreviewable(file.mimeType) ? () => setPreviewTarget(file) : null}
                    downloadHref={storageDownloadUrl(file.id)}
                    previewSrc={storagePreviewUrl(file.id)}
                    downloadLabel={t("action.download")}
                    previewLabel={t("action.preview")}
                  />
                ))}
              </div>
            </div>
          )}

          {/* 分页 */}
          {hasMore && !loading && !listError && (
            <div className="flex items-center justify-center gap-3">
              <Button
                type="button"
                variant="outline"
                disabled={loadingMore}
                onClick={() => void load(files.length, true)}
              >
                {loadingMore && <Loader2 className="size-4 animate-spin" />}
                {t("list.load_more", { loaded: files.length, total })}
              </Button>
            </div>
          )}

          {/* 按时间清理 */}
          <div className="rounded-xl border p-4">
            <h2 className="flex items-center gap-2 text-sm font-semibold">
              <BrushCleaning className="size-4" />
              {t("cleanup.title")}
            </h2>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">
              {t("cleanup.description")}
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              {CLEANUP_DAYS.map((d) => (
                <Button
                  key={d}
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={cleaning || loading}
                  onClick={() => setConfirmCleanup(d)}
                >
                  {t("cleanup.older_than", { days: d })}
                </Button>
              ))}
            </div>
          </div>
        </div>
      </main>

      {/* 删除确认 */}
      <Dialog open={confirmDelete != null} onOpenChange={(o) => !o && setConfirmDelete(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("delete.title")}</DialogTitle>
            <DialogDescription>
              {t("delete.description", { count: confirmDelete?.length ?? 0 })}
            </DialogDescription>
          </DialogHeader>
          <p className="text-xs text-muted-foreground">{t("delete.warning")}</p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmDelete(null)} disabled={deleting}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={deleting}
              onClick={() => confirmDelete && void runDelete(confirmDelete)}
            >
              {deleting ? <Loader2 className="size-4 animate-spin" /> : null}
              {t("common.confirm_delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 清理确认 */}
      <Dialog open={confirmCleanup != null} onOpenChange={(o) => !o && setConfirmCleanup(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("cleanup.confirm_title", { days: confirmCleanup ?? 0 })}</DialogTitle>
            <DialogDescription>{t("cleanup.confirm_description")}</DialogDescription>
          </DialogHeader>
          <p className="text-xs text-muted-foreground">{t("cleanup.confirm_notice")}</p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmCleanup(null)} disabled={cleaning}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={cleaning}
              onClick={() => confirmCleanup != null && void runCleanup(confirmCleanup)}
            >
              {cleaning ? <Loader2 className="size-4 animate-spin" /> : null}
              {t("cleanup.confirm_action")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 逐项结果反馈 */}
      <Dialog open={cleanupResults != null} onOpenChange={(o) => !o && setCleanupResults(null)}>
        <DialogContent className="max-h-[80vh] overflow-hidden">
          <DialogHeader>
            <DialogTitle>{t("result.title")}</DialogTitle>
            <DialogDescription>
              {cleanupResults &&
                (() => {
                  const s = summarizeCleanupItems(cleanupResults);
                  return t("result.summary", {
                    deleted: s.deleted,
                    skipped: s.skipped,
                    failed: s.failed,
                    size: formatBytes(s.deletedBytes),
                  });
                })()}
            </DialogDescription>
          </DialogHeader>
          <div className="max-h-72 overflow-y-auto rounded-lg border">
            {(cleanupResults ?? [])
              .filter((r) => r.status !== "deleted")
              .map((r, i) => (
                <div
                  key={r.id ?? i}
                  className="flex items-start gap-2 border-b px-3 py-2 text-xs last:border-b-0"
                >
                  <Badge variant={r.status === "skipped" ? "secondary" : "destructive"}>
                    {t(`result.status.${r.status}`, { defaultValue: r.status })}
                  </Badge>
                  <div className="min-w-0 flex-1">
                    <p className="font-mono">#{r.id ?? "?"}</p>
                    {(r.reasons ?? []).length > 0 && (
                      <p className="text-muted-foreground">
                        {(r.reasons ?? []).map(reasonLabel).join(" · ")}
                      </p>
                    )}
                    {r.error && <p className="text-muted-foreground">{r.error}</p>}
                  </div>
                </div>
              ))}
            {(cleanupResults ?? []).every((r) => r.status === "deleted") && (
              <p className="px-3 py-6 text-center text-sm text-muted-foreground">
                {t("result.all_deleted")}
              </p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCleanupResults(null)}>
              {t("common.close")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 图片预览 */}
      <Dialog open={previewTarget != null} onOpenChange={(o) => !o && setPreviewTarget(null)}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle className="truncate">{previewTarget?.displayName}</DialogTitle>
            <DialogDescription>
              {previewTarget &&
                `${previewTarget.mimeType} · ${formatBytes(previewTarget.sizeBytes)}`}
            </DialogDescription>
          </DialogHeader>
          {previewTarget && (
            <img
              src={storagePreviewUrl(previewTarget.id)}
              alt={previewTarget.displayName}
              className="max-h-[60vh] w-full rounded-lg object-contain"
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function FileRow({
  file,
  checked,
  disabled,
  reasonLabel,
  onToggle,
  onPreview,
  downloadHref,
  previewSrc,
  downloadLabel,
  previewLabel,
}: {
  file: StorageFileDto;
  checked: boolean;
  disabled: boolean;
  reasonLabel: (r: string) => string;
  onToggle: (checked: CheckedState) => void;
  onPreview: (() => void) | null;
  downloadHref: string;
  previewSrc: string;
  downloadLabel: string;
  previewLabel: string;
}) {
  return (
    <div className="flex items-center gap-3 px-3 py-2.5">
      <Checkbox
        checked={checked}
        onCheckedChange={onToggle}
        disabled={disabled}
        aria-label={file.displayName}
      />

      {/* 缩略/图标 */}
      <div className="flex size-10 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-muted">
        {isPreviewable(file.mimeType) && file.existsOnDisk ? (
          <img src={previewSrc} alt="" className="size-full object-cover" loading="lazy" />
        ) : file.mimeType.startsWith("image/") ? (
          <ImageIcon className="size-4 text-muted-foreground" />
        ) : (
          <FileIcon className="size-4 text-muted-foreground" />
        )}
      </div>

      {/* 名称 + 移动端信息（含保护原因，小屏不丢） */}
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{file.displayName}</p>
        <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground sm:hidden">
          <span>{file.mimeType}</span>
          <span>{formatBytes(file.sizeBytes)}</span>
          <span>{formatEpochMillis(file.createdAt)}</span>
        </div>
        {file.protectionReasons.length > 0 && (
          <div className="mt-0.5 flex flex-wrap gap-1 sm:hidden">
            {file.protectionReasons.map((r) => (
              <Badge key={r} variant={r === "check_failed" ? "destructive" : "secondary"} className="gap-0.5">
                <Lock className="size-3" />
                {reasonLabel(r)}
              </Badge>
            ))}
          </div>
        )}
      </div>

      {/* 桌面列 */}
      <span className="hidden w-28 truncate text-xs text-muted-foreground sm:block">
        {file.mimeType}
      </span>
      <span className="hidden w-20 text-right text-xs sm:block">
        {formatBytes(file.sizeBytes)}
      </span>
      <span className="hidden w-28 text-xs text-muted-foreground sm:block">
        {formatEpochMillis(file.createdAt)}
      </span>

      {/* 保护原因 */}
      <div className="hidden w-44 flex-wrap gap-1 sm:flex">
        {file.protectionReasons.length > 0 ? (
          file.protectionReasons.map((r) => (
            <Badge
              key={r}
              variant={r === "check_failed" ? "destructive" : "secondary"}
              className="gap-0.5"
            >
              <Lock className="size-3" />
              {reasonLabel(r)}
            </Badge>
          ))
        ) : !file.existsOnDisk ? (
          <Badge variant="outline">{reasonLabel("orphaned_row")}</Badge>
        ) : (
          <span className="text-xs text-muted-foreground">{reasonLabel("none")}</span>
        )}
      </div>

      {/* 操作 */}
      <div className="flex w-20 shrink-0 items-center justify-end gap-0.5">
        {onPreview && (
          <Button type="button" variant="ghost" size="icon" className="size-7" onClick={onPreview}>
            <Eye className="size-4" />
            <span className="sr-only">{previewLabel}</span>
          </Button>
        )}
        <Button type="button" variant="ghost" size="icon" className="size-7" asChild>
          <a href={downloadHref} download={file.displayName} aria-label={downloadLabel}>
            <Download className="size-4" />
          </a>
        </Button>
      </div>
    </div>
  );
}
