import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_CACHE_KEEP_ALIVE,
  formatCacheWarmCountdown,
  parsePositiveInt,
  validateCacheKeepAlive,
} from "../../web-settings/web-ui/lib/cache-keep-alive";

test("formatCacheWarmCountdown renders m:ss with ceil semantics", () => {
  assert.equal(formatCacheWarmCountdown(270000), "🔥 4:30");
  assert.equal(formatCacheWarmCountdown(269001), "🔥 4:30");
  assert.equal(formatCacheWarmCountdown(269000), "🔥 4:29");
  assert.equal(formatCacheWarmCountdown(-5), "🔥 0:00");
  assert.equal(formatCacheWarmCountdown(0), "🔥 0:00");
  assert.equal(formatCacheWarmCountdown(59000), "🔥 0:59");
  assert.equal(formatCacheWarmCountdown(60000), "🔥 1:00");
});

test("parsePositiveInt accepts trimmed digit-only positive integers", () => {
  assert.equal(parsePositiveInt("270"), 270);
  assert.equal(parsePositiveInt(" 30 "), 30);
  assert.equal(parsePositiveInt("1"), 1);
  assert.equal(parsePositiveInt(String(DEFAULT_CACHE_KEEP_ALIVE.intervalSeconds)), 270);
});

test("parsePositiveInt rejects non-positive / non-integer / overflow input", () => {
  assert.equal(parsePositiveInt("0"), null);
  assert.equal(parsePositiveInt("-1"), null);
  assert.equal(parsePositiveInt("1.5"), null);
  assert.equal(parsePositiveInt("abc"), null);
  assert.equal(parsePositiveInt(""), null);
  assert.equal(parsePositiveInt("   "), null);
  assert.equal(parsePositiveInt("99999999999"), null);
  assert.equal(parsePositiveInt("2147483647"), 2147483647);
  assert.equal(parsePositiveInt("2147483648"), null);
});

test("validateCacheKeepAlive enforces positive ints and max > interval", () => {
  assert.equal(validateCacheKeepAlive(270, 30), null);
  assert.equal(validateCacheKeepAlive(0, 30), "interval");
  assert.equal(validateCacheKeepAlive(-5, 30), "interval");
  assert.equal(validateCacheKeepAlive(1.5, 30), "interval");
  assert.equal(validateCacheKeepAlive(270, 0), "max");
  assert.equal(validateCacheKeepAlive(270, -1), "max");
  assert.equal(validateCacheKeepAlive(1800, 30), "max_le_interval");
  assert.equal(validateCacheKeepAlive(1799, 30), null);
  // interval 校验优先于 max
  assert.equal(validateCacheKeepAlive(0, 0), "interval");
});
