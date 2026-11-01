/**
 * Chart projection for the web, ported from the app so both draw the same picture:
 * ChartGeometry.kt (scales, ticks, clipping, bars), ChartFormatting.kt and ChartTableProjection.kt.
 * Input is the server-validated spec (MessageDto.charts); this module never validates provenance.
 */
import type { ChartSpecDto } from "~/types";

export const MAX_CHART_SERIES = 12;
export const MAX_CHART_ROWS = 2000;
export const MAX_AXIS_TICKS = 6;
export const MAX_CATEGORY_TICKS = 8;
export const CHART_TABLE_PAGE_SIZE = 50;
const MAX_NUMBER = 1e100;

export const CHART_PALETTE = [
  "#4A7FD6",
  "#E2774A",
  "#4FAE7B",
  "#9B6FD1",
  "#D9A93A",
  "#D65C8A",
  "#3FA7B5",
  "#B0694E",
  "#7D9A3E",
  "#5F7896",
  "#E0605F",
  "#8C8C8C",
];

const COLOR_PATTERN = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;
const FORMAT_PATTERN = /^(,)?(?:\.(\d{1,2}))?([fd%])$/;

export function safeChartNumber(value: number): boolean {
  return (
    Number.isFinite(value) &&
    Math.abs(value) <= MAX_NUMBER &&
    (value === 0 || Math.abs(value) >= 1e-100)
  );
}

/** Java `%.Ne`: at least two exponent digits with an explicit sign. */
function scientific(value: number, digits: number): string {
  return value.toExponential(digits).replace(/e([+-])(\d)$/, "e$10$2");
}

/** Bounded labels, with scientific fallback rather than hundreds of digits at extreme magnitudes. */
export function formatChartNumber(value: number, format?: string | null, locale?: string): string {
  if (!Number.isFinite(value)) return "—";
  const spec = format && format.length <= 16 ? FORMAT_PATTERN.exec(format) : null;
  const percent = spec?.[3] === "%";
  let number = percent ? value * 100 : value;
  if (number === 0) number = 0;
  if (!Number.isFinite(number)) return "—";
  const parsedDigits = spec?.[2] != null ? Math.min(8, Math.max(0, Number(spec[2]))) : undefined;
  const digits = spec?.[3] === "d" ? 0 : parsedDigits;
  let label: string;
  if (Math.abs(number) >= 1e9 || (number !== 0 && Math.abs(number) < 1e-5)) {
    label = scientific(number, digits ?? 3);
  } else {
    label = new Intl.NumberFormat(locale, {
      useGrouping: spec?.[1] === ",",
      minimumFractionDigits: digits ?? 0,
      maximumFractionDigits: digits ?? 5,
    }).format(number);
  }
  return (label + (percent ? "%" : "")).slice(0, 64);
}

export function chartSeriesColor(text: string | null | undefined, index: number): string {
  if (text && COLOR_PATTERN.test(text)) {
    const raw = text.slice(1);
    return `#${
      raw.length === 3
        ? raw
            .split("")
            .map((c) => c + c)
            .join("")
        : raw
    }`;
  }
  return CHART_PALETTE[
    ((index % CHART_PALETTE.length) + CHART_PALETTE.length) % CHART_PALETTE.length
  ];
}

export interface ChartTick {
  fraction: number;
  label: string;
}
export interface ChartCoordinate {
  x: number;
  y: number;
}
export interface ChartBar {
  left: number;
  right: number;
  top: number;
  bottom: number;
}
export interface ChartLineSegment {
  start: ChartCoordinate;
  end: ChartCoordinate;
}
export interface ProjectedSeries {
  color: string;
  /** Only visible markers; out-of-range points are never moved onto borders. */
  points: Array<ChartCoordinate | null>;
  bars: ChartBar[];
  lineSegments: ChartLineSegment[];
}
export interface ChartGeometry {
  style: ChartSpecDto["style"];
  xTicks: ChartTick[];
  yTicks: ChartTick[];
  series: ProjectedSeries[];
}

export class ChartAxisScale {
  private readonly lo: number;
  private readonly hi: number;
  private readonly divisor: number;

  constructor(
    readonly min: number,
    readonly max: number,
    readonly log: boolean,
  ) {
    this.lo = log ? Math.log10(min) : min;
    this.hi = log ? Math.log10(max) : max;
    this.divisor = Math.max(Math.abs(this.lo), Math.abs(this.hi), 1e-100);
  }

  fraction(value: number): number {
    if (!safeChartNumber(value) || (this.log && value <= 0)) return 0.5;
    const v = this.log ? Math.log10(value) : value;
    const span = (this.hi - this.lo) / this.divisor;
    if (span <= 0 || !Number.isFinite(span)) return 0.5;
    const result = (v - this.lo) / this.divisor / span;
    return Number.isFinite(result) ? result : 0.5;
  }

  valueAt(fraction: number): number {
    const f = Number.isFinite(fraction) ? Math.min(1, Math.max(0, fraction)) : 0.5;
    const v = this.lo * (1 - f) + this.hi * f;
    return this.log ? Math.min(MAX_NUMBER, Math.max(1e-100, 10 ** v)) : v;
  }

  ticks(format: string | null | undefined, locale?: string): ChartTick[] {
    return Array.from({ length: MAX_AXIS_TICKS }, (_, index) => {
      const fraction = index / (MAX_AXIS_TICKS - 1);
      return { fraction, label: formatChartNumber(this.valueAt(fraction), format, locale) };
    });
  }
}

export function buildAxisScale(
  values: number[],
  userMin: number | null | undefined,
  userMax: number | null | undefined,
  log = false,
  includeZero = false,
): ChartAxisScale {
  const valid = values
    .slice(0, MAX_CHART_SERIES * MAX_CHART_ROWS)
    .filter((it) => safeChartNumber(it) && (!log || it > 0));
  const bound = (v: number | null | undefined) =>
    v != null && safeChartNumber(v) && (!log || v > 0) ? v : null;
  const minOf = () => (valid.length ? Math.min(...valid) : null);
  const maxOf = () => (valid.length ? Math.max(...valid) : null);
  let low = bound(userMin) ?? minOf() ?? (log ? 1 : 0);
  let high = bound(userMax) ?? maxOf() ?? (log ? 10 : 1);
  if (includeZero && !log) {
    low = Math.min(low, 0);
    high = Math.max(high, 0);
  }
  if (low > high) {
    low = minOf() ?? (log ? 1 : 0);
    high = maxOf() ?? (log ? 10 : 1);
    if (includeZero && !log) {
      low = Math.min(low, 0);
      high = Math.max(high, 0);
    }
  }
  if (low === high || (log && Math.log10(low) === Math.log10(high))) {
    if (log) {
      const exponent = Math.log10(low);
      low = 10 ** Math.max(exponent - 0.5, -100);
      high = 10 ** Math.min(exponent + 0.5, 100);
    } else {
      const pad = low === 0 ? 1 : Math.abs(low) * 0.1;
      high = Math.min(high + pad, MAX_NUMBER);
      low = Math.max(low - pad, -MAX_NUMBER);
    }
  }
  return new ChartAxisScale(low, high, log);
}

export function categoryCount(spec: ChartSpecDto): number {
  return Math.max(spec.xAxis.data.length, ...spec.series.map((s) => s.values.length), 0);
}

export function categoryLabel(spec: ChartSpecDto, index: number): string {
  return spec.xAxis.data[index] ?? String(index + 1);
}

export function isInChartViewport(point: ChartCoordinate): boolean {
  return (
    Number.isFinite(point.x) &&
    Number.isFinite(point.y) &&
    point.x >= 0 &&
    point.x <= 1 &&
    point.y >= 0 &&
    point.y <= 1
  );
}

/** Liang–Barsky clipping with exactly four bounds. */
export function clipChartLineSegment(
  start: ChartCoordinate,
  end: ChartCoordinate,
): ChartLineSegment | null {
  if (![start.x, start.y, end.x, end.y].every(Number.isFinite)) return null;
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  if (!Number.isFinite(dx) || !Number.isFinite(dy)) return null;
  let enter = 0;
  let exit = 1;
  let enterEdge = -1;
  let exitEdge = -1;
  const p = [-dx, dx, -dy, dy];
  const q = [start.x, 1 - start.x, start.y, 1 - start.y];
  for (let index = 0; index < 4; index += 1) {
    if (p[index] === 0) {
      if (q[index] < 0) return null;
    } else {
      const t = q[index] / p[index];
      if (p[index] < 0 && t > enter) {
        enter = t;
        enterEdge = index;
      }
      if (p[index] > 0 && t < exit) {
        exit = t;
        exitEdge = index;
      }
      if (enter > exit) return null;
    }
  }
  const clamp = (v: number) => Math.min(1, Math.max(0, v));
  const at = (t: number, edge: number): ChartCoordinate => ({
    x: clamp(edge === 0 ? 0 : edge === 1 ? 1 : start.x + t * dx),
    y: clamp(edge === 2 ? 0 : edge === 3 ? 1 : start.y + t * dy),
  });
  return { start: at(enter, enterEdge), end: at(exit, exitEdge) };
}

export function clipChartBar(bar: ChartBar): ChartBar | null {
  if (![bar.left, bar.right, bar.top, bar.bottom].every(Number.isFinite)) return null;
  const left = Math.max(0, bar.left);
  const right = Math.min(1, bar.right);
  const top = Math.max(0, bar.top);
  const bottom = Math.min(1, bar.bottom);
  if (left > right || top > bottom) return null;
  return { left, right, top, bottom };
}

export function buildChartGeometry(spec: ChartSpecDto, locale?: string): ChartGeometry {
  const series = spec.series.slice(0, MAX_CHART_SERIES);
  const scatter = spec.style === "scatter";
  const yLog = spec.yAxis.scale === "log" && spec.style !== "bar";
  const yValues = series.flatMap((s) =>
    scatter ? s.points.slice(0, MAX_CHART_ROWS).map((p) => p.y) : s.values.slice(0, MAX_CHART_ROWS),
  );
  const yScale = buildAxisScale(
    yValues,
    spec.yAxis.min,
    spec.yAxis.max,
    yLog,
    spec.style === "bar",
  );
  const xLog = spec.xAxis.scale === "log";
  const xScale = scatter
    ? buildAxisScale(
        series.flatMap((s) => s.points.slice(0, MAX_CHART_ROWS).map((p) => p.x)),
        spec.xAxis.min,
        spec.xAxis.max,
        xLog,
      )
    : null;
  const categories = Math.min(Math.max(categoryCount(spec), 0), MAX_CHART_ROWS);
  const categoryX = (index: number) => {
    if (spec.style === "bar") return (index + 0.5) / Math.max(categories, 1);
    if (categories <= 1) return 0.5;
    return index / (categories - 1);
  };
  let xTicks: ChartTick[];
  if (xScale) {
    xTicks = xScale.ticks(spec.xAxis.format, locale);
  } else {
    const count = Math.min(categories, MAX_CATEGORY_TICKS);
    xTicks = Array.from({ length: count }, (_, tick) => {
      const index = count <= 1 ? 0 : Math.trunc((tick * (categories - 1)) / (count - 1));
      return { fraction: categoryX(index), label: categoryLabel(spec, index).slice(0, 80) };
    });
  }
  const projected = series.map((s, seriesIndex): ProjectedSeries => {
    const color = chartSeriesColor(s.color, seriesIndex);
    const rawPoints: Array<ChartCoordinate | null> = scatter
      ? s.points
          .slice(0, MAX_CHART_ROWS)
          .map((p) =>
            !safeChartNumber(p.x) ||
            !safeChartNumber(p.y) ||
            (xLog && p.x <= 0) ||
            (yLog && p.y <= 0)
              ? null
              : { x: xScale!.fraction(p.x), y: 1 - yScale.fraction(p.y) },
          )
      : s.values
          .slice(0, MAX_CHART_ROWS)
          .map((value, index) =>
            !safeChartNumber(value) || (yLog && value <= 0)
              ? null
              : { x: categoryX(index), y: 1 - yScale.fraction(value) },
          );
    const points = rawPoints.map((point) => (point && isInChartViewport(point) ? point : null));
    const lineSegments: ChartLineSegment[] = [];
    if (spec.style === "line") {
      for (let i = 0; i + 1 < rawPoints.length; i += 1) {
        const a = rawPoints[i];
        const b = rawPoints[i + 1];
        const segment = a && b ? clipChartLineSegment(a, b) : null;
        if (segment) lineSegments.push(segment);
      }
    }
    const bars: ChartBar[] = [];
    if (spec.style === "bar") {
      const width = 0.8 / Math.max(categories, 1) / Math.max(series.length, 1);
      const baseline = 1 - yScale.fraction(0);
      rawPoints.forEach((point, index) => {
        if (!point) return;
        const left = (index + 0.1) / Math.max(categories, 1) + seriesIndex * width;
        const bar = clipChartBar({
          left,
          right: left + width,
          top: Math.min(point.y, baseline),
          bottom: Math.max(point.y, baseline),
        });
        if (bar) bars.push(bar);
      });
    }
    return { color, points, bars, lineSegments };
  });
  return {
    style: spec.style,
    xTicks,
    yTicks: yScale.ticks(spec.yAxis.format, locale),
    series: projected,
  };
}

/** Fixed-margin plot area shared by the SVG and the PNG export (chartPlotBounds in the app). */
export interface ChartPlotBounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export function chartPlotBounds(width: number, height: number): ChartPlotBounds {
  const w = Number.isFinite(width) ? Math.min(4096, Math.max(1, width)) : 1;
  const h = Number.isFinite(height) ? Math.min(4096, Math.max(1, height)) : 1;
  return { left: w * 0.2, top: h * 0.05, right: w * 0.96, bottom: h * 0.82 };
}

export function plotX(bounds: ChartPlotBounds, fraction: number): number {
  const f = Number.isFinite(fraction) ? Math.min(1, Math.max(0, fraction)) : 0.5;
  return bounds.left + f * (bounds.right - bounds.left);
}

export function plotY(bounds: ChartPlotBounds, fraction: number): number {
  const f = Number.isFinite(fraction) ? Math.min(1, Math.max(0, fraction)) : 0.5;
  return bounds.top + f * (bounds.bottom - bounds.top);
}

export interface ChartTablePage {
  pageIndex: number;
  pageCount: number;
  totalRows: number;
  rows: string[][];
}

/** Project only one page; never build a 24,000-row table. */
export function chartTablePage(
  spec: ChartSpecDto,
  requestedPage: number,
  seriesNames: string[],
  locale?: string,
): ChartTablePage {
  const series = spec.series.slice(0, MAX_CHART_SERIES);
  const scatter = spec.style === "scatter";
  const total = scatter
    ? series.reduce((sum, s) => sum + Math.min(s.points.length, MAX_CHART_ROWS), 0)
    : Math.min(Math.max(categoryCount(spec), 0), MAX_CHART_ROWS);
  const pageCount = Math.max(1, Math.ceil(total / CHART_TABLE_PAGE_SIZE));
  const pageIndex = Math.min(Math.max(requestedPage, 0), pageCount - 1);
  const start = pageIndex * CHART_TABLE_PAGE_SIZE;
  const end = Math.min(total, start + CHART_TABLE_PAGE_SIZE);
  const rows: string[][] = [];
  if (scatter) {
    let offset = 0;
    series.forEach((s, seriesIndex) => {
      const count = Math.min(s.points.length, MAX_CHART_ROWS);
      const from = Math.max(0, start - offset);
      const to = Math.min(count, end - offset);
      for (let index = from; index < Math.max(from, to); index += 1) {
        const p = s.points[index];
        rows.push([
          (seriesNames[seriesIndex] ?? s.name ?? "").slice(0, 160),
          formatChartNumber(p.x, spec.xAxis.format, locale),
          formatChartNumber(p.y, spec.yAxis.format, locale),
        ]);
      }
      offset += count;
    });
  } else {
    for (let index = start; index < end; index += 1) {
      rows.push([
        categoryLabel(spec, index).slice(0, 160),
        ...series.map((s) => {
          const value = s.values[index];
          return value == null ? "—" : formatChartNumber(value, spec.yAxis.format, locale);
        }),
      ]);
    }
  }
  return { pageIndex, pageCount, totalRows: total, rows };
}
