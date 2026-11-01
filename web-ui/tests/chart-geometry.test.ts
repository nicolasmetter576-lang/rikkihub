/**
 * 图表显示：移植自 App 的 ChartFormattingTest / ChartGeometryTest / ChartTableProjectionTest
 * 关键用例，保证网页与 App 画出同一张图；另测分组与渲染不把数据当标记。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs --test tests/chart-geometry.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { register } from "node:module";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import {
  MAX_AXIS_TICKS,
  MAX_CATEGORY_TICKS,
  buildAxisScale,
  buildChartGeometry,
  chartSeriesColor,
  chartTablePage,
  formatChartNumber,
  isInChartViewport,
  type ChartGeometry,
} from "../app/lib/chart-geometry";
import type { ChartSpecDto, UIMessagePart } from "../app/types";
import en from "../app/locales/en-US/message.json";
import zh from "../app/locales/zh-CN/message.json";

register("./helpers/svg-stub-loader.mjs", import.meta.url);
const { groupMessageParts } = await import("../app/components/message/message-part");
const { ChartPart } = await import("../app/components/message/parts/chart-part");

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { message: en } },
  interpolation: { escapeValue: false },
});

const axis = (extra: Partial<ChartSpecDto["xAxis"]> = {}): ChartSpecDto["xAxis"] => ({
  data: [],
  scale: "linear",
  ...extra,
});
const line = (values: number[], yAxis = axis()): ChartSpecDto => ({
  style: "line",
  xAxis: axis(),
  yAxis,
  series: [{ values, points: [] }],
});

function assertBounded(g: ChartGeometry) {
  assert.ok(g.yTicks.length <= MAX_AXIS_TICKS);
  assert.ok(g.xTicks.length <= MAX_CATEGORY_TICKS);
  for (const s of g.series) {
    for (const p of s.points) if (p) assert.ok(isInChartViewport(p));
    for (const seg of s.lineSegments)
      assert.ok(isInChartViewport(seg.start) && isInChartViewport(seg.end));
    for (const b of s.bars) {
      for (const v of [b.left, b.right, b.top, b.bottom])
        assert.ok(Number.isFinite(v) && v >= 0 && v <= 1);
      assert.ok(b.left <= b.right && b.top <= b.bottom);
    }
  }
}

test("formatting matches the app", () => {
  assert.equal(formatChartNumber(12.345, ".2f", "en-US"), "12.35");
  assert.equal(formatChartNumber(1234, ",d", "en-US"), "1,234");
  assert.equal(formatChartNumber(0.125, ".1%", "en-US"), "12.5%");
  assert.equal(formatChartNumber(1234.5, ",.2f", "en-US"), "1,234.50");
  assert.equal(formatChartNumber(-0, undefined, "en-US"), "0");
  assert.equal(formatChartNumber(-0, ".2f", "en-US"), "0.00");
  assert.equal(formatChartNumber(1e100, undefined, "en-US"), "1.000e+100");
  assert.equal(formatChartNumber(1e-100, undefined, "en-US"), "1.000e-100");
  assert.equal(formatChartNumber(9e-101, undefined, "en-US"), "9.000e-101");
  assert.equal(formatChartNumber(1234567890, undefined, "en-US"), "1.235e+09");
  for (const v of [Number.NaN, Infinity, -Infinity]) assert.equal(formatChartNumber(v), "—");
  assert.equal(formatChartNumber(Number.MAX_VALUE, ".2%", "en-US"), "—");
  assert.equal(formatChartNumber(12.5, "%Y", "en-US"), "12.5");
  assert.equal(formatChartNumber(12.5, ".999999f", "en-US"), "12.5");
  assert.ok(formatChartNumber(12.5, ".99f", "en-US").length <= 64);
  assert.equal(formatChartNumber(12.5, ".2f", "de-DE"), "12,50");
});

test("line points keep order and endpoints; categories are 1-based", () => {
  const g = buildChartGeometry(line([-10, 0, 10]));
  assert.deepEqual(g.series[0].points, [
    { x: 0, y: 1 },
    { x: 0.5, y: 0.5 },
    { x: 1, y: 0 },
  ]);
  assert.deepEqual(
    g.xTicks.map((t) => t.label),
    ["1", "2", "3"],
  );
});

test("explicit clipping keeps everything in the viewport", () => {
  const g = buildChartGeometry(line([-1e100, 0, 1e100], axis({ min: -1, max: 1 })));
  assertBounded(g);
  assert.equal(g.series[0].points[0], null);
  assert.equal(g.series[0].points[2], null);
  assert.equal(g.series[0].lineSegments.length, 2);
});

test("scales stay finite at the extremes", () => {
  assert.ok(Math.abs(buildAxisScale([-1e100, 1e100], null, null).fraction(0) - 0.5) < 1e-10);
  const tiny = buildAxisScale([-1e-100, 1e-100], null, null);
  assert.equal(tiny.fraction(-1e-100), 0);
  assert.equal(tiny.fraction(1e-100), 1);
  for (const value of [0, 7, -7, 1e100, -1e100, 1e-100, -1e-100]) {
    const scale = buildAxisScale([value, value], null, null);
    assert.ok(scale.min < scale.max);
    assertBounded(buildChartGeometry(line([value, value])));
  }
  const log = buildAxisScale([1, 10, 100], null, null, true);
  assert.ok(Math.abs(log.fraction(10) - 0.5) < 1e-10);
  assert.ok(Math.abs(log.valueAt(0.5) - 10) < 1e-10);
  assert.equal(
    buildAxisScale([1e-100, 1e100], null, null, true).ticks(null).length,
    MAX_AXIS_TICKS,
  );
});

test("single category is centered; scatter axes are independent; bars sit on zero", () => {
  assert.equal(buildChartGeometry(line([2])).series[0].points[0]!.x, 0.5);
  const scatter = buildChartGeometry({
    style: "scatter",
    xAxis: axis({ scale: "log" }),
    yAxis: axis(),
    series: [
      {
        values: [],
        points: [
          { x: 1, y: -5 },
          { x: 10, y: 0 },
          { x: 100, y: 5 },
        ],
      },
    ],
  });
  assert.deepEqual(scatter.series[0].points[1], { x: 0.5, y: 0.5 });
  const bars = buildChartGeometry({
    style: "bar",
    xAxis: axis(),
    yAxis: axis(),
    series: [{ values: [-5, 5], points: [] }],
  });
  assert.ok(Math.abs(bars.series[0].bars[0].top - 0.5) < 1e-10);
  assert.ok(Math.abs(bars.series[0].bars[1].bottom - 0.5) < 1e-10);
  assertBounded(bars);
});

test("series colors use the hex override or the shared palette", () => {
  assert.equal(chartSeriesColor("#abc", 0), "#aabbcc");
  assert.equal(chartSeriesColor("red", 0), "#4A7FD6");
  assert.equal(chartSeriesColor(null, 13), "#E2774A");
});

test("table pages hold at most 50 rows and scatter rows carry the series name", () => {
  const values = Array.from({ length: 120 }, (_, i) => i);
  const spec: ChartSpecDto = {
    style: "line",
    xAxis: axis(),
    yAxis: axis(),
    series: [{ values, points: [] }],
  };
  const page = chartTablePage(spec, 2, ["A"], "en-US");
  assert.equal(page.pageCount, 3);
  assert.equal(page.rows.length, 20);
  assert.deepEqual(page.rows[0], ["101", "100"]);
  assert.equal(chartTablePage(spec, 99, ["A"]).pageIndex, 2);
  const scatter: ChartSpecDto = {
    style: "scatter",
    xAxis: axis(),
    yAxis: axis(),
    series: [
      { values: [], points: [{ x: 1, y: 2 }] },
      { values: [], points: [{ x: 3, y: 4 }] },
    ],
  };
  assert.deepEqual(chartTablePage(scatter, 0, ["S1", "S2"], "en-US").rows, [
    ["S1", "1", "2"],
    ["S2", "3", "4"],
  ]);
});

test("validated tool calls leave the thinking block; others stay", () => {
  const chartTool = {
    type: "tool",
    toolCallId: "c",
    toolName: "chart_display",
    input: "{}",
    output: [],
  } as unknown as UIMessagePart;
  const otherTool = {
    type: "tool",
    toolCallId: "o",
    toolName: "bash",
    input: "{}",
    output: [],
  } as unknown as UIMessagePart;
  const spec = line([1, 2]);
  const blocks = groupMessageParts(
    [otherTool, chartTool, { type: "text", text: "after" } as UIMessagePart],
    [{ partIndex: 1, spec }],
  );
  assert.deepEqual(
    blocks.map((b) => b.type),
    ["thinking", "chart", "content"],
  );
  // Without server validation the same call is an ordinary tool step.
  assert.deepEqual(
    groupMessageParts([chartTool]).map((b) => b.type),
    ["thinking"],
  );
});

test("chart strings render as text, never as markup", () => {
  const spec: ChartSpecDto = {
    style: "bar",
    title: '<img src=x onerror="alert(1)">',
    xAxis: axis({ data: ["<b>Q1</b>"], title: "<script>x</script>" }),
    yAxis: axis(),
    series: [{ name: '<a href="javascript:alert(1)">s</a>', values: [1], points: [] }],
  };
  const html = renderToStaticMarkup(createElement(ChartPart, { spec }));
  assert.match(html, /&lt;img/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<img|<script|<a\b|<b>/);
  assert.match(html, /<svg/);
  const source = readFileSync(
    new URL("../app/components/message/parts/chart-part.tsx", import.meta.url),
    "utf8",
  );
  assert.doesNotMatch(source, /dangerouslySetInnerHTML/);
});

test("chart strings exist in both languages", () => {
  assert.deepEqual(Object.keys(zh.chart).sort(), Object.keys(en.chart).sort());
});
