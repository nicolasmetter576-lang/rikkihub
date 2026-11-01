import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import {
  MAX_CHART_SERIES,
  buildChartGeometry,
  chartPlotBounds,
  chartTablePage,
  plotX,
  plotY,
  type ChartGeometry,
} from "~/lib/chart-geometry";
import type { ChartSpecDto } from "~/types";

const PLOT_HEIGHT = 240;
const IMAGE_WIDTH = 1080;
const IMAGE_HEIGHT = 900;

/** Display names the app uses: the series name, or "Series N". */
export function chartSeriesNames(spec: ChartSpecDto, fallback: (n: number) => string): string[] {
  return spec.series
    .slice(0, MAX_CHART_SERIES)
    .map((s, index) => (s.name && s.name.trim() ? s.name : fallback(index + 1)));
}

function truncate(label: string, maxWidth: number, charWidth: number): string {
  const max = Math.max(1, Math.floor(maxWidth / charWidth));
  return label.length > max ? `${label.slice(0, Math.max(0, max - 1))}…` : label;
}

function useElementWidth(fallback: number) {
  const ref = React.useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = React.useState(fallback);
  React.useEffect(() => {
    const element = ref.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width;
      if (next && Number.isFinite(next)) setWidth(Math.max(120, Math.round(next)));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return { ref, width };
}

/** SVG plot. All strings are rendered as text nodes; nothing from the data becomes markup. */
export function ChartPlot({ geometry, width }: { geometry: ChartGeometry; width: number }) {
  const clipId = React.useId().replace(/:/g, "");
  const bounds = chartPlotBounds(width, PLOT_HEIGHT);
  const xLabelWidth = width / Math.max(geometry.xTicks.length, 1);
  return (
    <svg
      width={width}
      height={PLOT_HEIGHT}
      viewBox={`0 0 ${width} ${PLOT_HEIGHT}`}
      role="img"
      className="block text-muted-foreground"
    >
      <defs>
        <clipPath id={clipId}>
          <rect
            x={bounds.left}
            y={bounds.top}
            width={bounds.right - bounds.left}
            height={bounds.bottom - bounds.top}
          />
        </clipPath>
      </defs>
      {geometry.yTicks.map((tick, index) => {
        const y = plotY(bounds, 1 - tick.fraction);
        return (
          <g key={`y-${index}`}>
            <line
              x1={bounds.left}
              x2={bounds.right}
              y1={y}
              y2={y}
              className="stroke-border"
              strokeWidth={1}
            />
            <text
              x={bounds.left - 6}
              y={y}
              fontSize={11}
              textAnchor="end"
              dominantBaseline="middle"
              fill="currentColor"
            >
              {truncate(tick.label, bounds.left - 8, 6.5)}
            </text>
          </g>
        );
      })}
      {geometry.xTicks.map((tick, index) => (
        <text
          key={`x-${index}`}
          x={plotX(bounds, tick.fraction)}
          y={bounds.bottom + 16}
          fontSize={11}
          textAnchor="middle"
          fill="currentColor"
        >
          {truncate(tick.label, xLabelWidth - 4, 6.5)}
        </text>
      ))}
      <line
        x1={bounds.left}
        x2={bounds.left}
        y1={bounds.top}
        y2={bounds.bottom}
        stroke="currentColor"
        strokeWidth={1}
      />
      <line
        x1={bounds.left}
        x2={bounds.right}
        y1={bounds.bottom}
        y2={bounds.bottom}
        stroke="currentColor"
        strokeWidth={1}
      />
      <g clipPath={`url(#${clipId})`}>
        {geometry.series.map((series, seriesIndex) => (
          <g key={seriesIndex} fill={series.color} stroke={series.color}>
            {geometry.style === "bar"
              ? series.bars.map((bar, index) => (
                  <rect
                    key={index}
                    x={plotX(bounds, bar.left)}
                    y={plotY(bounds, bar.top)}
                    width={Math.max(0, plotX(bounds, bar.right) - plotX(bounds, bar.left))}
                    height={Math.max(0, plotY(bounds, bar.bottom) - plotY(bounds, bar.top))}
                    stroke="none"
                  />
                ))
              : null}
            {geometry.style === "line"
              ? series.lineSegments.map((segment, index) => (
                  <line
                    key={index}
                    x1={plotX(bounds, segment.start.x)}
                    y1={plotY(bounds, segment.start.y)}
                    x2={plotX(bounds, segment.end.x)}
                    y2={plotY(bounds, segment.end.y)}
                    strokeWidth={2}
                  />
                ))
              : null}
            {geometry.style === "scatter" ||
            (geometry.style === "line" && series.points.length <= 100)
              ? series.points.map((point, index) =>
                  point ? (
                    <circle
                      key={index}
                      cx={plotX(bounds, point.x)}
                      cy={plotY(bounds, point.y)}
                      r={geometry.style === "scatter" ? 3 : 2.5}
                      stroke="none"
                    />
                  ) : null,
                )
              : null}
          </g>
        ))}
      </g>
    </svg>
  );
}

/** Standalone 1080×900 PNG with the app's layout (ChartImageExport.kt). */
export function drawChartImage(
  canvas: HTMLCanvasElement,
  spec: ChartSpecDto,
  geometry: ChartGeometry,
  names: string[],
  fallbackTitle: string,
) {
  canvas.width = IMAGE_WIDTH;
  canvas.height = IMAGE_HEIGHT;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas is not available");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
  const text = (
    label: string,
    x: number,
    y: number,
    width: number,
    size = 22,
    centered = false,
  ) => {
    ctx.fillStyle = "#333333";
    ctx.font = `${size}px sans-serif`;
    let display = label.slice(0, 240);
    while (display.length > 1 && ctx.measureText(display).width > Math.max(1, width)) {
      display = `${display.slice(0, -2)}…`;
    }
    const measured = ctx.measureText(display).width;
    const left = centered ? x - measured / 2 : x;
    ctx.fillText(display, Math.min(Math.max(0, left), Math.max(0, IMAGE_WIDTH - measured)), y);
  };
  text(spec.title?.trim() ? spec.title : fallbackTitle, 32, 48, IMAGE_WIDTH - 64, 32);
  if (spec.yAxis.title) text(spec.yAxis.title, 32, 82, IMAGE_WIDTH - 64);
  const plotTop = 96;
  const plot = chartPlotBounds(IMAGE_WIDTH, 620);
  const px = (x: number) => plotX(plot, x);
  const py = (y: number) => plotTop + plotY(plot, y);
  geometry.yTicks.forEach((tick) => {
    const y = py(1 - tick.fraction);
    ctx.strokeStyle = "#dddddd";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(plot.left, y);
    ctx.lineTo(plot.right, y);
    ctx.stroke();
    text(tick.label, 16, y + 7, plot.left - 24);
  });
  geometry.xTicks.forEach((tick) => {
    text(
      tick.label,
      px(tick.fraction),
      plotTop + plot.bottom + 30,
      IMAGE_WIDTH / Math.max(geometry.xTicks.length, 1),
      22,
      true,
    );
  });
  ctx.strokeStyle = "#777777";
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(plot.left, py(0));
  ctx.lineTo(plot.left, py(1));
  ctx.lineTo(plot.right, py(1));
  ctx.stroke();
  ctx.save();
  ctx.beginPath();
  ctx.rect(plot.left, py(0), plot.right - plot.left, py(1) - py(0));
  ctx.clip();
  geometry.series.forEach((series) => {
    ctx.fillStyle = series.color;
    ctx.strokeStyle = series.color;
    ctx.lineWidth = 3;
    const dot = (x: number, y: number, r: number) => {
      ctx.beginPath();
      ctx.arc(px(x), py(y), r, 0, Math.PI * 2);
      ctx.fill();
    };
    if (geometry.style === "bar") {
      series.bars.forEach((bar) =>
        ctx.fillRect(
          px(bar.left),
          py(bar.top),
          px(bar.right) - px(bar.left),
          py(bar.bottom) - py(bar.top),
        ),
      );
    } else if (geometry.style === "scatter") {
      series.points.forEach((p) => p && dot(p.x, p.y, 5));
    } else {
      series.lineSegments.forEach((segment) => {
        ctx.beginPath();
        ctx.moveTo(px(segment.start.x), py(segment.start.y));
        ctx.lineTo(px(segment.end.x), py(segment.end.y));
        ctx.stroke();
      });
      if (series.points.length <= 100) series.points.forEach((p) => p && dot(p.x, p.y, 4));
    }
  });
  ctx.restore();
  if (spec.xAxis.title) text(spec.xAxis.title, IMAGE_WIDTH / 2, 690, IMAGE_WIDTH - 64, 22, true);
  // Fixed 3-column, 4-row legend holds all 12 series.
  geometry.series.forEach((series, index) => {
    const left = 32 + (index % 3) * 350;
    const top = 744 + Math.trunc(index / 3) * 34;
    ctx.fillStyle = series.color;
    ctx.fillRect(left, top - 16, 16, 16);
    text(names[index] ?? "", left + 24, top, 310, 20);
  });
}

function ChartTable({ spec, names }: { spec: ChartSpecDto; names: string[] }) {
  const { t, i18n } = useTranslation("message");
  const [pageIndex, setPageIndex] = React.useState(0);
  const page = chartTablePage(spec, pageIndex, names, i18n.language);
  const headers =
    spec.style === "scatter"
      ? [
          t("chart.category"),
          spec.xAxis.title?.trim() || t("chart.x"),
          spec.yAxis.title?.trim() || t("chart.y"),
        ]
      : [spec.xAxis.title?.trim() || t("chart.category"), ...names];
  return (
    <div className="space-y-2">
      <div className="max-h-72 overflow-auto rounded-md border">
        <table className="w-full text-left text-xs">
          <thead className="sticky top-0 bg-muted">
            <tr>
              {headers.map((header, index) => (
                <th key={index} className="max-w-36 truncate px-2 py-1.5 font-medium">
                  {header.slice(0, 160)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {page.rows.map((row, rowIndex) => (
              <tr key={rowIndex} className="border-t">
                {row.map((cell, cellIndex) => (
                  <td key={cellIndex} className="max-w-36 truncate px-2 py-1 tabular-nums">
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={page.pageIndex <= 0}
          onClick={() => setPageIndex(page.pageIndex - 1)}
        >
          {t("chart.previous_page")}
        </Button>
        <span>{t("chart.page", { page: page.pageIndex + 1, count: page.pageCount })}</span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={page.pageIndex + 1 >= page.pageCount}
          onClick={() => setPageIndex(page.pageIndex + 1)}
        >
          {t("chart.next_page")}
        </Button>
      </div>
    </div>
  );
}

export function ChartPart({ spec }: { spec: ChartSpecDto }) {
  const { t, i18n } = useTranslation("message");
  const [mode, setMode] = React.useState<"chart" | "table">("chart");
  const { ref, width } = useElementWidth(600);
  const geometry = React.useMemo(
    () => buildChartGeometry(spec, i18n.language),
    [spec, i18n.language],
  );
  const names = React.useMemo(
    () => chartSeriesNames(spec, (n) => t("chart.series_name", { index: n })),
    [spec, t],
  );
  const title = spec.title?.trim() ? spec.title.slice(0, 240) : t("chart.title");

  const exportImage = () => {
    try {
      const canvas = document.createElement("canvas");
      drawChartImage(canvas, spec, geometry, names, t("chart.title"));
      canvas.toBlob((blob) => {
        if (!blob) {
          toast.error(t("chart.export_failed", { reason: "PNG" }));
          return;
        }
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = `chart-${crypto.randomUUID()}.png`;
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        toast.success(t("chart.export_success"));
      }, "image/png");
    } catch (error) {
      toast.error(
        t("chart.export_failed", {
          reason: error instanceof Error ? error.message.slice(0, 160) : "",
        }),
      );
    }
  };

  return (
    <div className="my-2 space-y-2 rounded-xl border p-3" data-testid="chart-card">
      <div className="line-clamp-2 font-medium">{title}</div>
      <div className="flex items-center gap-1">
        {(["chart", "table"] as const).map((value) => (
          <Button
            key={value}
            type="button"
            size="sm"
            variant={mode === value ? "secondary" : "ghost"}
            onClick={() => setMode(value)}
          >
            {value === "chart" ? t("chart.mode_chart") : t("chart.mode_table")}
          </Button>
        ))}
      </div>
      {mode === "chart" ? (
        <>
          {spec.yAxis.title ? (
            <div className="text-xs text-muted-foreground">{spec.yAxis.title.slice(0, 160)}</div>
          ) : null}
          <div ref={ref} className="w-full overflow-hidden">
            <ChartPlot geometry={geometry} width={width} />
          </div>
          {spec.xAxis.title ? (
            <div className="text-center text-xs text-muted-foreground">
              {spec.xAxis.title.slice(0, 160)}
            </div>
          ) : null}
          <ul className="flex flex-wrap gap-x-4 gap-y-1">
            {names.map((name, index) => (
              <li key={index} className="flex min-w-0 items-center gap-1.5 text-xs">
                <span
                  className="size-2.5 shrink-0 rounded-sm"
                  style={{ backgroundColor: geometry.series[index]?.color }}
                />
                <span className="max-w-48 truncate">{name.slice(0, 160)}</span>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <ChartTable spec={spec} names={names} />
      )}
      <Button type="button" variant="ghost" size="sm" className="px-2" onClick={exportImage}>
        {t("chart.export_image")}
      </Button>
    </div>
  );
}
