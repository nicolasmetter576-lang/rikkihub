import test from "node:test";
import assert from "node:assert/strict";

import { KeyedInflightRequests } from "../../web-settings/web-ui/lib/workspace-completion";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

test("same key reuses the in-flight promise", async () => {
  const pool = new KeyedInflightRequests<string>();
  const d = deferred<string>();
  const a = pool.start("ws1 ", () => d.promise);
  const b = pool.start("ws1 ", () => Promise.resolve("should-not-run"));
  assert.equal(b, a, "同 key 应复用同一 promise");
  assert.equal(pool.get("ws1 "), a);
  d.resolve("A");
  assert.equal(await a, "A");
  await tick();
  assert.equal(pool.peek(), null, "完成后槽位清空");
});

test("switching key aborts the old request and isolates records", async () => {
  const pool = new KeyedInflightRequests<string>();
  const dA = deferred<string>();
  const dB = deferred<string>();
  const signals: AbortSignal[] = [];

  const pA = pool.start("wsA ", (signal) => {
    signals.push(signal);
    return dA.promise;
  });
  const pB = pool.start("wsB ", (signal) => {
    signals.push(signal);
    return dB.promise;
  });

  assert.ok(signals[0].aborted, "异 key 时旧请求必须 abort");
  assert.ok(!signals[1].aborted);
  assert.notEqual(pA, pB);
  assert.equal(pool.get("wsA "), null, "旧 key 不再复用");
  assert.equal(pool.get("wsB "), pB);

  // B 先完成 → 槽位清的是 B 的 record；之后 A 晚到 resolve 不得污染
  dB.resolve("B-fast");
  assert.equal(await pB, "B-fast");
  await tick();
  assert.equal(pool.peek(), null);

  dA.resolve("A-late");
  assert.equal(await pA, "A-late", "调用方持有的旧 promise 仍 resolve 自己的值");
  await tick();
  assert.equal(pool.peek(), null, "A 晚到的 finally 不得清掉任何东西（槽已空）");

  // A 晚到后再起同 key 请求：不受影响、正常启动
  const pA2 = pool.start("wsA ", () => Promise.resolve("A-new"));
  assert.equal(await pA2, "A-new");
});

test("aborting old request mid-flight does not clear the new record", async () => {
  const pool = new KeyedInflightRequests<string>();
  const dA = deferred<string>();
  const dB = deferred<string>();
  pool.start("wsA ", () => dA.promise);
  const pB = pool.start("wsB ", () => dB.promise);

  // 模拟「A 已 abort 但 task 迟迟不 settle」——此时 B 的 record 必须在位
  assert.equal(pool.peek()?.key, "wsB ");
  dA.resolve("late");
  await tick();
  assert.equal(pool.peek()?.key, "wsB ", "A 的 finally 不能清 B");

  dB.resolve("B");
  assert.equal(await pB, "B");
  await tick();
  assert.equal(pool.peek(), null);
});

test("abort() aborts current and is idempotent", async () => {
  const pool = new KeyedInflightRequests<string>();
  let captured: AbortSignal | null = null;
  const d = deferred<string>();
  pool.start("k", (signal) => {
    captured = signal;
    return d.promise;
  });
  pool.abort();
  assert.ok(captured!.aborted);
  assert.equal(pool.peek(), null);
  pool.abort(); // 幂等
  const p2 = pool.start("k", () => Promise.resolve("fresh"));
  assert.equal(await p2, "fresh");
});

test("cwd-scope switch (same workspace, different cwd) is a different key", async () => {
  const pool = new KeyedInflightRequests<string>();
  const d1 = deferred<string>();
  const d2 = deferred<string>();
  const sig1: AbortSignal[] = [];
  pool.start("ws1 src/app", (s) => {
    sig1.push(s);
    return d1.promise;
  });
  const p2 = pool.start("ws1 ", (s) => {
    void s;
    return d2.promise;
  });
  assert.ok(sig1[0].aborted, "cwd 变化也必须 abort 旧请求");
  d2.resolve("root-index");
  assert.equal(await p2, "root-index");
});

test("task rejection propagates and clears only its own slot", async () => {
  const pool = new KeyedInflightRequests<string>();
  const p = pool.start("ws ", () => Promise.reject(new Error("io")));
  await assert.rejects(p, /io/);
  await tick();
  assert.equal(pool.peek(), null, "失败后槽位清空，可重试");
  const p2 = pool.start("ws ", () => Promise.resolve("ok"));
  assert.equal(await p2, "ok");
});

test("abort propagates as AbortError-style rejection to task consumers", async () => {
  const pool = new KeyedInflightRequests<string>();
  const observed: unknown[] = [];
  const p = pool.start("ws ", async (signal) => {
    await new Promise((r) => setTimeout(r, 50));
    if (signal.aborted) {
      const err = new Error("aborted");
      err.name = "AbortError";
      throw err;
    }
    return "done";
  });
  p.catch((e) => observed.push(e));
  pool.abort();
  await tick();
  await new Promise((r) => setTimeout(r, 80));
  assert.equal(observed.length, 1);
  assert.equal((observed[0] as Error).name, "AbortError");
});
