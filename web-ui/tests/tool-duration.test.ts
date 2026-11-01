/**
 * 工具调用耗时：解析 Kotlin 的高精度时间、没计时的调用不显示、执行中随当前时间增长、格式与 App 一致。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/tool-duration.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  TOOL_DURATION_MIN_MS,
  formatToolDuration,
  parseInstantMs,
  toolDurationMs,
} from "../app/lib/tool-duration";

const T0 = "2026-10-26T12:00:00.250Z";

test("instants with microsecond or nanosecond digits parse like their millisecond prefix", () => {
  const base = Date.parse("2026-10-26T12:00:00.123Z");
  assert.equal(parseInstantMs("2026-10-26T12:00:00.123456Z"), base);
  assert.equal(parseInstantMs("2026-10-26T12:00:00.123456789Z"), base);
  assert.equal(parseInstantMs("2026-10-26T12:00:00Z"), Date.parse("2026-10-26T12:00:00Z"));
  assert.equal(parseInstantMs(null), null);
  assert.equal(parseInstantMs(""), null);
  assert.equal(parseInstantMs("not a time"), null);
});

test("a finished call lasts from start to finish and ignores the clock", () => {
  const tool = { startedAt: T0, finishedAt: "2026-10-26T12:00:01.750Z" };
  assert.equal(toolDurationMs(tool, 0, false), 1500);
  assert.equal(toolDurationMs(tool, Date.now(), true), 1500);
});

test("a running call counts up to now, and never goes negative with a slow clock", () => {
  const start = Date.parse(T0);
  assert.equal(toolDurationMs({ startedAt: T0 }, start + 2300, true), 2300);
  assert.equal(toolDurationMs({ startedAt: T0 }, start - 500, true), 0);
});

test("calls that were never timed, or were left unfinished and are not running, show nothing", () => {
  assert.equal(toolDurationMs({}, 1, true), null);
  assert.equal(toolDurationMs({ startedAt: null, finishedAt: null }, 1, true), null);
  // Started, never finished (interrupted) and no longer running.
  assert.equal(toolDurationMs({ startedAt: T0 }, Date.parse(T0) + 5000, false), null);
});

test("duration text is seconds with one decimal and the minimum is 100 ms", () => {
  assert.equal(formatToolDuration(1234), "1.2s");
  assert.equal(formatToolDuration(100), "0.1s");
  assert.equal(formatToolDuration(61_000), "61.0s");
  assert.equal(formatToolDuration(-5), "0.0s");
  assert.equal(TOOL_DURATION_MIN_MS, 100);
});
