import * as React from "react";

import { ChevronDown, ChevronRight, RefreshCw, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Spinner } from "~/components/ui/spinner";
import { cn } from "~/lib/utils";
import { useSettingsStore } from "~/stores";
import { ConfirmDeleteDialog, SettingsPageHeader, reportSettingsError } from "@overlay/components/settings/settings-ui";
import {
  USAGE_RANGES,
  cacheHitRate,
  buildHeatmapWeeks,
  formatCost,
  formatCount,
  formatPercent,
  formatTokens,
  isFullyPriced,
  quotaLeft,
  statsApi,
  type QuotaEstimate,
  type StatsResponse,
  type UsageAmount,
  type UsageModelStat,
  type UsageProviderStat,
  type UsageRange,
  type UsageStatsReport,
} from "@overlay/lib/stats";

/** 与 UsageStatsService.MIN_DROP_FOR_ESTIMATE 一致 */
const MIN_DROP_FOR_ESTIMATE = 0.05;

export default function StatsPage() {
  const { t } = useTranslation("settings");
  const [range, setRange] = React.useState<UsageRange>("D30");
  const [data, setData] = React.useState<StatsResponse | null>(null);
  const [loading, setLoading] = React.useState(true);
  const pending = React.useRef<AbortController | null>(null);
  const providers = useSettingsStore((state) => state.settings?.providers);

  const load = React.useCallback(
    async (next: UsageRange, refreshQuota = true) => {
      pending.current?.abort();
      const controller = new AbortController();
      pending.current = controller;
      setLoading(true);
      try {
        const result = await statsApi.get(next, controller.signal, refreshQuota);
        if (!controller.signal.aborted) setData(result);
      } catch (error) {
        if (!controller.signal.aborted) reportSettingsError(error, t("stats.load_failed"));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    },
    [t],
  );

  React.useEffect(() => {
    void load(range);
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void load(range, false);
    }, 15_000);
    return () => {
      window.clearInterval(timer);
      pending.current?.abort();
    };
  }, [load, range, providers]);

  const [deletion, setDeletion] = React.useState<StatsDeletion | null>(null);
  const [deleting, setDeleting] = React.useState(false);
  const confirmDeletion = async () => {
    if (!deletion) return;
    setDeleting(true);
    try {
      if (deletion.kind === "model") await statsApi.deleteModel(deletion.key);
      else await statsApi.deleteProvider(deletion.providerId);
      setDeletion(null);
      await load(range);
    } catch (error) {
      reportSettingsError(error, t("stats.delete_failed"));
    } finally {
      setDeleting(false);
    }
  };

  const report = data?.report.range === range ? data.report : null;

  return (
    <div className="space-y-4">
      <SettingsPageHeader
        title={t("stats.title")}
        description={t("stats.description")}
        actions={
          <Button variant="outline" size="sm" onClick={() => void load(range)} disabled={loading}>
            {loading ? <Spinner className="size-4" /> : <RefreshCw className="size-4" />}
            {t("stats.refresh")}
          </Button>
        }
      />

      <div className="grid grid-cols-4 gap-1 rounded-lg border p-1" role="tablist">
        {USAGE_RANGES.map((option) => (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={range === option.value}
            onClick={() => setRange(option.value)}
            className={cn(
              "rounded-md px-2 py-1.5 text-sm transition",
              range === option.value
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:bg-accent hover:text-foreground",
            )}
          >
            {t(`stats.range.${option.value}`)}
          </button>
        ))}
      </div>

      {!report ? (
        <div className="flex justify-center py-12">
          <Spinner />
        </div>
      ) : (
        <>
          <SummaryCard report={report} />
          <TrendCard report={report} />
          {report.providers.length > 0 ? <ProvidersCard report={report} onDelete={setDeletion} /> : null}
          {report.models.length > 0 ? <ModelsCard report={report} onDelete={setDeletion} /> : null}
          {report.sources.length > 0 ? <SourcesCard report={report} /> : null}
          {data ? <HeatmapCard requestsPerDay={data.overview.requestsPerDay} /> : null}
          {data ? (
            <div className="grid grid-cols-3 gap-2">
              <OverviewCell label={t("stats.conversations")} value={formatCount(data.overview.conversations)} />
              <OverviewCell label={t("stats.messages")} value={formatCount(data.overview.messages)} />
              <OverviewCell label={t("stats.launches")} value={formatCount(data.overview.launchCount)} />
            </div>
          ) : null}
        </>
      )}
      <ConfirmDeleteDialog
        open={deletion !== null}
        onOpenChange={(open) => {
          if (!open) setDeletion(null);
        }}
        title={t("stats.delete_usage")}
        description={t(deletion?.kind === "provider" ? "stats.delete_provider_confirm" : "stats.delete_model_confirm", {
          name: deletion?.name ?? "",
        })}
        loading={deleting}
        onConfirm={confirmDeletion}
      />
    </div>
  );
}

/** A deletion waiting for the user's confirmation: every usage record of one model row or one provider. */
type StatsDeletion =
  | { kind: "model"; key: string; name: string }
  | { kind: "provider"; providerId: string | null; name: string };

function DeleteUsageButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation("settings");
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      className="size-7 shrink-0 text-muted-foreground"
      aria-label={t("stats.delete_usage")}
      onClick={onClick}
    >
      <Trash2 className="size-4" />
    </Button>
  );
}

function Section({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <section className="space-y-3 rounded-xl border bg-card p-3">
      {title ? <h2 className="text-base font-semibold">{title}</h2> : null}
      {children}
    </section>
  );
}

function SummaryCard({ report }: { report: UsageStatsReport }) {
  const { t } = useTranslation("settings");
  const total = report.total;
  let costNote = t("stats.cost");
  if (total.totalTokens > 0 && total.pricedTokens === 0) costNote = t("stats.cost_none");
  else if (total.totalTokens > 0 && !isFullyPriced(total)) {
    costNote = t("stats.cost_partial", { percent: formatPercent(total.pricedTokens / (total.promptTokens + total.completionTokens)) });
  }
  return (
    <Section>
      <div className="text-sm text-muted-foreground">{t("stats.total_tokens")}</div>
      <div className="text-4xl font-semibold tabular-nums" data-testid="stats-total-tokens">
        {formatTokens(total.totalTokens)}
      </div>
      <div>
        <div className="text-xl font-semibold text-primary" data-testid="stats-total-cost">
          {total.pricedTokens > 0 ? formatCost(total.cost) : "—"}
        </div>
        <div className="text-xs text-muted-foreground">{costNote}</div>
      </div>
      <div className="grid grid-cols-3 gap-3 border-t pt-3">
        <Cell label={t("stats.requests")} value={formatCount(total.requests)} />
        <Cell label={t("stats.input")} value={formatTokens(total.promptTokens)} />
        <Cell label={t("stats.output")} value={formatTokens(total.completionTokens)} />
        <Cell label={t("stats.cached")} value={formatTokens(total.cachedTokens)} />
        <Cell label={t("stats.cache_hit_rate")} value={formatPercent(cacheHitRate(total))} />
      </div>
    </Section>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="truncate text-base font-medium tabular-nums">{value}</div>
      <div className="truncate text-xs text-muted-foreground">{label}</div>
    </div>
  );
}

function OverviewCell({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border bg-card p-3">
      <div className="text-lg font-semibold tabular-nums">{value}</div>
      <div className="text-xs text-muted-foreground">{label}</div>
    </div>
  );
}

function TrendCard({ report }: { report: UsageStatsReport }) {
  const { t } = useTranslation("settings");
  const buckets = report.trend;
  const [selected, setSelected] = React.useState(buckets.length - 1);
  React.useEffect(() => setSelected(buckets.length - 1), [buckets]);
  const max = Math.max(0, ...buckets.map((b) => b.amount.totalTokens));
  const current = buckets[selected];
  return (
    <Section title={t("stats.trend")}>
      {max === 0 ? (
        <p className="text-sm text-muted-foreground">{t("stats.trend_empty")}</p>
      ) : (
        <>
          {current ? (
            <div className="flex items-baseline justify-between gap-2 text-sm">
              <span className="text-muted-foreground">{current.bucket}</span>
              <span className="font-medium tabular-nums">
                {formatTokens(current.amount.totalTokens)}
                {current.amount.pricedTokens > 0 ? ` · ${formatCost(current.amount.cost)}` : ""}
                {` · ${t("stats.requests_count", { count: formatCount(current.amount.requests) })}`}
              </span>
            </div>
          ) : null}
          <div className="flex h-32 items-end gap-px" data-testid="stats-trend">
            {buckets.map((bucket, index) => {
              const value = bucket.amount.totalTokens;
              const height = value > 0 ? Math.max(2, (value / max) * 100) : 0;
              return (
                <button
                  key={bucket.bucket}
                  type="button"
                  title={`${bucket.bucket}: ${formatTokens(value)}`}
                  onClick={() => setSelected(index)}
                  onMouseEnter={() => setSelected(index)}
                  className="flex h-full min-w-0 flex-1 items-end rounded-sm bg-muted"
                >
                  <span
                    className={cn("block w-full rounded-sm", index === selected ? "bg-primary" : "bg-primary/40")}
                    style={{ height: `${height}%` }}
                  />
                </button>
              );
            })}
          </div>
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>{buckets[0]?.bucket}</span>
            <span>{buckets[buckets.length - 1]?.bucket}</span>
          </div>
        </>
      )}
    </Section>
  );
}

function Bar({ value, max }: { value: number; max: number }) {
  return (
    <div className="h-1 w-full overflow-hidden rounded-full bg-muted">
      <div className="h-full rounded-full bg-primary" style={{ width: `${Math.min(100, (value / Math.max(1, max)) * 100)}%` }} />
    </div>
  );
}

function AmountText({ amount }: { amount: UsageAmount }) {
  return (
    <div className="shrink-0 text-right">
      <div className="text-sm tabular-nums">{formatTokens(amount.totalTokens)}</div>
      {amount.pricedTokens > 0 ? (
        <div className="text-xs text-primary tabular-nums">{formatCost(amount.cost)}</div>
      ) : null}
    </div>
  );
}

function ProvidersCard({ report, onDelete }: { report: UsageStatsReport; onDelete: (deletion: StatsDeletion) => void }) {
  const { t } = useTranslation("settings");
  const max = Math.max(1, ...report.providers.map((p) => p.amount.totalTokens));
  return (
    <Section title={t("stats.providers")}>
      <div className="divide-y">
        {report.providers.map((provider) => (
          <ProviderRow
            key={provider.providerId ?? `${provider.kind}`}
            provider={provider}
            max={max}
            onDelete={() => onDelete({ kind: "provider", providerId: provider.providerId, name: providerLabel(provider, t) })}
          />
        ))}
      </div>
    </Section>
  );
}

function providerLabel(provider: UsageProviderStat, t: (key: string) => string): string {
  if (provider.kind === "UNKNOWN") return t("stats.provider_unknown");
  if (provider.kind === "DELETED") return t("stats.provider_deleted");
  return provider.name;
}

function ProviderRow({ provider, max, onDelete }: { provider: UsageProviderStat; max: number; onDelete: () => void }) {
  const { t } = useTranslation("settings");
  // 订阅类提供商默认展开，额度一眼能看到
  const [open, setOpen] = React.useState(provider.quotas.length > 0);
  return (
    <div className="space-y-2 py-2" data-testid="stats-provider">
      <div className="flex w-full items-center gap-1">
        <button type="button" className="flex min-w-0 flex-1 items-center gap-2 text-left" onClick={() => setOpen(!open)}>
          {open ? <ChevronDown className="size-4 shrink-0" /> : <ChevronRight className="size-4 shrink-0" />}
          <span className="min-w-0 flex-1 truncate font-medium">{providerLabel(provider, t)}</span>
          <AmountText amount={provider.amount} />
        </button>
        <DeleteUsageButton onClick={onDelete} />
      </div>
      <Bar value={provider.amount.totalTokens} max={max} />
      {open ? (
        <div className="space-y-2 pl-6">
          {provider.quotas.map((quota) => (
            <QuotaBlock key={`${quota.window}-${quota.models.join(",")}`} quota={quota} />
          ))}
          {provider.quotas.length > 0 ? (
            <p className="text-xs text-muted-foreground">{t("stats.quota_note")}</p>
          ) : null}
          {provider.models.map((model) => (
            <ModelLine key={model.key || model.name} model={model} showProvider={false} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function quotaTitle(quota: QuotaEstimate, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (quota.models.length > 0) return t("stats.quota_models", { models: quota.models.join("、") });
  if (quota.window === "daily") return t("stats.quota_daily");
  if (quota.window === "weekly" || quota.window === "secondary") return t("stats.quota_weekly");
  if (quota.window === "primary") return t("stats.quota_primary");
  return quota.window;
}

function QuotaBlock({ quota }: { quota: QuotaEstimate }) {
  const { t } = useTranslation("settings");
  const meta = [t("stats.quota_remaining", { percent: formatPercent(quota.remaining) })];
  if (quota.stale) meta.push(t("stats.quota_stale"));
  if (quota.resetAt) meta.push(t("stats.quota_reset", { time: new Date(quota.resetAt).toLocaleString() }));
  if (quota.accounts > 1) meta.push(t("stats.quota_accounts", { count: quota.accounts }));

  const full: string[] = [];
  if (quota.tokens) full.push(`${formatTokens(quota.tokens.perFull)} Token`);
  if (quota.cost) full.push(formatCost(quota.cost.perFull));
  if (quota.requests) full.push(t("stats.requests_count", { count: formatCount(quota.requests.perFull) }));
  const left: string[] = [];
  const leftTokens = quotaLeft(quota, quota.tokens);
  const leftCost = quotaLeft(quota, quota.cost);
  if (leftTokens != null) left.push(`${formatTokens(leftTokens)} Token`);
  if (leftCost != null) left.push(formatCost(leftCost));

  const likely = quota.likelyUnit ? quota[quota.likelyUnit] : null;

  return (
    <div className="space-y-1.5 rounded-lg border bg-muted/30 p-3" data-testid="stats-quota">
      <div className="text-sm font-medium">{quotaTitle(quota, t)}</div>
      {quota.boundaryUncertain ? <p className="text-xs text-muted-foreground">{t("stats.quota_boundary")}</p> : null}
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div className="h-full rounded-full bg-primary" style={{ width: `${Math.min(100, quota.remaining * 100)}%` }} />
      </div>
      <div className="text-xs text-muted-foreground">{meta.join(" · ")}</div>
      {!quota.enoughData ? (
        <p className="text-sm">
          {t("stats.quota_insufficient", {
            drop: formatPercent(quota.observedDrop),
            needed: formatPercent(MIN_DROP_FOR_ESTIMATE),
          })}
        </p>
      ) : (
        <>
          <p className="text-sm" data-testid="stats-quota-full">
            {t("stats.quota_full", { value: full.join(" ≈ ") })}
          </p>
          {left.length > 0 ? <p className="text-sm">{t("stats.quota_left", { value: left.join(" ≈ ") })}</p> : null}
          {likely && likely.variation != null && quota.likelyUnit ? (
            <p className="text-xs text-muted-foreground">
              {t("stats.quota_likely", {
                unit: t(`stats.quota_unit_${quota.likelyUnit}`),
                variation: formatPercent(likely.variation),
              })}
            </p>
          ) : null}
          <p className="text-xs text-muted-foreground">
            {t("stats.quota_based", { segments: quota.segments, drop: formatPercent(quota.observedDrop) })}
          </p>
        </>
      )}
    </div>
  );
}

function ModelLine({ model, showProvider, onDelete }: { model: UsageModelStat; showProvider: boolean; onDelete?: () => void }) {
  const { t } = useTranslation("settings");
  const sub = [
    showProvider ? model.providerName : null,
    t("stats.requests_count", { count: formatCount(model.amount.requests) }),
    model.priced ? null : t("stats.no_price"),
  ].filter(Boolean);
  return (
    <div className="flex items-center gap-2">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm">{model.name || t("stats.model_unknown")}</div>
        <div className="truncate text-xs text-muted-foreground">{sub.join(" · ")}</div>
      </div>
      <AmountText amount={model.amount} />
      {onDelete ? <DeleteUsageButton onClick={onDelete} /> : null}
    </div>
  );
}

function ModelsCard({ report, onDelete }: { report: UsageStatsReport; onDelete: (deletion: StatsDeletion) => void }) {
  const { t } = useTranslation("settings");
  const [showAll, setShowAll] = React.useState(false);
  const max = Math.max(1, ...report.models.map((m) => m.amount.totalTokens));
  const models = showAll ? report.models : report.models.slice(0, 8);
  return (
    <Section title={t("stats.models")}>
      {models.map((model) => (
        <div key={model.key || model.name} className="space-y-1">
          <ModelLine
            model={model}
            showProvider
            onDelete={() => onDelete({ kind: "model", key: model.key, name: model.name || t("stats.model_unknown") })}
          />
          <Bar value={model.amount.totalTokens} max={max} />
        </div>
      ))}
      {report.models.length > 8 ? (
        <Button variant="ghost" size="sm" onClick={() => setShowAll(!showAll)}>
          {showAll ? t("stats.show_less") : t("stats.show_all", { count: report.models.length })}
        </Button>
      ) : null}
    </Section>
  );
}

function SourcesCard({ report }: { report: UsageStatsReport }) {
  const { t } = useTranslation("settings");
  const max = Math.max(1, ...report.sources.map((s) => s.amount.totalTokens));
  return (
    <Section title={t("stats.sources")}>
      {report.sources.map((source) => (
        <div key={source.source} className="space-y-1">
          <div className="flex items-center gap-2">
            <div className="min-w-0 flex-1">
              <div className="text-sm">{t(`stats.source.${source.source}`, { defaultValue: source.source })}</div>
              <div className="text-xs text-muted-foreground">
                {t("stats.requests_count", { count: formatCount(source.amount.requests) })}
              </div>
            </div>
            <AmountText amount={source.amount} />
          </div>
          <Bar value={source.amount.totalTokens} max={max} />
        </div>
      ))}
    </Section>
  );
}

function HeatmapCard({ requestsPerDay }: { requestsPerDay: Record<string, number> }) {
  const { t } = useTranslation("settings");
  const weeks = React.useMemo(() => buildHeatmapWeeks(new Date()), []);
  const counts = Object.values(requestsPerDay).filter((c) => c > 0).sort((a, b) => a - b);
  const q = (p: number, fallback: number) => counts[Math.floor(counts.length * p)] ?? fallback;
  const [q1, q2, q3] = [q(0.25, 1), q(0.5, 2), q(0.75, 3)];
  const level = (count: number) => (count === 0 ? 0 : count <= q1 ? 1 : count <= q2 ? 2 : count <= q3 ? 3 : 4);
  const scrollRef = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    scrollRef.current?.scrollTo({ left: scrollRef.current.scrollWidth });
  }, []);
  return (
    <Section title={t("stats.heatmap")}>
      <div ref={scrollRef} className="overflow-x-auto">
        <div className="flex gap-[2px]">
          {weeks.map((week, wi) => (
            <div key={wi} className="flex flex-col gap-[2px]">
              {week.map((day) => {
                const count = day.future ? 0 : (requestsPerDay[day.key] ?? 0);
                const lv = day.future ? -1 : level(count);
                return (
                  <div
                    key={day.key}
                    title={day.future ? undefined : `${day.key}: ${count}`}
                    className={cn(
                      "size-[11px] rounded-[2px]",
                      lv < 0 && "bg-muted/30",
                      lv === 0 && "bg-muted",
                      lv === 1 && "bg-primary/25",
                      lv === 2 && "bg-primary/50",
                      lv === 3 && "bg-primary/75",
                      lv === 4 && "bg-primary",
                    )}
                  />
                );
              })}
            </div>
          ))}
        </div>
      </div>
    </Section>
  );
}