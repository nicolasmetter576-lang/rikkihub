import test from "node:test";
import assert from "node:assert/strict";

import {
  buildDeviceCompletionItems,
  DeviceCompletionIndexCache,
  deviceMountsFingerprint,
  isDeviceMentionQuery,
  loadDeviceCompletionEntries,
  parseDeviceMentionQuery,
  usableDeviceMounts,
  DEVICE_COMPLETION_MAX_DIRS,
  DEVICE_COMPLETION_MAX_ENTRIES,
  DEVICE_COMPLETION_MAX_MOUNTS,
  type DeviceCompletionMount,
  type DeviceIndexSource,
  type WorkspaceCompletionEntry,
} from "../../web-settings/web-ui/lib/workspace-completion";

function mount(
  alias: string,
  overrides: Partial<DeviceCompletionMount> = {},
): DeviceCompletionMount {
  return {
    id: `id-${alias}`,
    alias,
    sourceRelativePath: `src/${alias}`,
    enabled: true,
    active: true,
    ...overrides,
  };
}

function fakeDeviceSource(options: {
  available?: boolean;
  mounts?: DeviceCompletionMount[];
  tree?: Record<string, Array<{ path: string; name: string; isDirectory: boolean }>>;
  /** 每次 listMounts 返回不同快照（模拟中途变更） */
  mountsSequence?: DeviceCompletionMount[][];
}) {
  const calls = { availability: 0, listMounts: 0, listFiles: [] as Array<[string, string]> };
  let seq = 0;
  const source: DeviceIndexSource = {
    async isAvailable() {
      calls.availability += 1;
      return options.available ?? true;
    },
    async listMounts() {
      calls.listMounts += 1;
      const seqs = options.mountsSequence;
      if (seqs) return seqs[Math.min(seq++, seqs.length - 1)];
      return options.mounts ?? [];
    },
    async listFiles(mountId, path) {
      calls.listFiles.push([mountId, path]);
      return options.tree?.[`${mountId}:${path}`] ?? options.tree?.[path] ?? [];
    },
  };
  return { calls, source };
}

const e = (path: string, isDirectory = false) => ({
  path,
  name: path.split("/").pop() ?? path,
  isDirectory,
});

// ---------------------------------------------------------------------------
// query 归属与解析：/device 域判定不影响 /workspace 语义
// ---------------------------------------------------------------------------

test("isDeviceMentionQuery only accepts leading-slash /device", () => {
  assert.ok(isDeviceMentionQuery("/device"));
  assert.ok(isDeviceMentionQuery("/device/"));
  assert.ok(isDeviceMentionQuery("/device/pix/a"));
  // 无前导斜杠 → workspace 域（workspace 里名为 device 的目录不受影响）
  assert.ok(!isDeviceMentionQuery("device"));
  assert.ok(!isDeviceMentionQuery("device/pix"));
  assert.ok(!isDeviceMentionQuery("/workspace/x"));
  assert.ok(!isDeviceMentionQuery("src/a"));
  assert.ok(isDeviceMentionQuery("\\device\\pix")); // 反斜杠归一化
});

test("parseDeviceMentionQuery splits mounts vs files forms", () => {
  assert.deepEqual(parseDeviceMentionQuery("/device"), {
    kind: "mounts",
    rest: "",
    aliasPrefix: "",
  });
  assert.deepEqual(parseDeviceMentionQuery("/device/pi"), {
    kind: "mounts",
    rest: "pi",
    aliasPrefix: "pi",
  });
  assert.deepEqual(parseDeviceMentionQuery("/device/pix/DCIM/ca"), {
    kind: "files",
    rest: "pix/DCIM/ca",
    alias: "pix",
    dir: "DCIM",
    namePrefix: "ca",
  });
  // 根目录文件名前缀
  assert.deepEqual(parseDeviceMentionQuery("/device/pix/re"), {
    kind: "files",
    rest: "pix/re",
    alias: "pix",
    dir: "",
    namePrefix: "re",
  });
  // alias 后紧跟斜杠 → 该挂载根目录
  assert.deepEqual(parseDeviceMentionQuery("/device/pix/"), {
    kind: "files",
    rest: "pix/",
    alias: "pix",
    dir: "",
    namePrefix: "",
  });
  // 越界组件拒绝
  assert.equal(parseDeviceMentionQuery("/device/../x"), null);
  assert.equal(parseDeviceMentionQuery("/device/pix/../x"), null);
  assert.equal(parseDeviceMentionQuery("/device/pix/a/../../b"), null);
  // 非设备域
  assert.equal(parseDeviceMentionQuery("workspace/src"), null);
  assert.equal(parseDeviceMentionQuery("/workspace/device/x"), null);
});

// ---------------------------------------------------------------------------
// 挂载过滤 / 指纹：connected(enabled+active) ONLY
// ---------------------------------------------------------------------------

test("usableDeviceMounts keeps only enabled+active, sorted, capped at 8", () => {
  const mounts = [
    mount("z"),
    mount("a"),
    mount("off", { enabled: false }),
    mount("idle", { active: false }),
    ...Array.from({ length: 10 }, (_, i) => mount(`m${i}`)),
  ];
  const usable = usableDeviceMounts(mounts);
  assert.equal(usable.length, DEVICE_COMPLETION_MAX_MOUNTS);
  assert.ok(usable.every((m) => m.enabled && m.active));
  assert.deepEqual(
    usable.map((m) => m.alias),
    [...usable.map((m) => m.alias)].sort(),
  );
});

test("deviceMountsFingerprint changes on revoke/disable/remap/source", () => {
  const base = [mount("pix"), mount("docs")];
  const fp = deviceMountsFingerprint(base);
  // 撤销一个挂载
  assert.notEqual(deviceMountsFingerprint([mount("pix")]), fp);
  // 停用
  assert.notEqual(
    deviceMountsFingerprint([mount("pix"), mount("docs", { enabled: false })]),
    fp,
  );
  // 激活关闭
  assert.notEqual(
    deviceMountsFingerprint([mount("pix"), mount("docs", { active: false })]),
    fp,
  );
  // 同 alias 重映射到新 id / source
  assert.notEqual(
    deviceMountsFingerprint([mount("pix"), mount("docs", { id: "id-NEW" })]),
    fp,
  );
  assert.notEqual(
    deviceMountsFingerprint([mount("pix"), mount("docs", { sourceRelativePath: "other" })]),
    fp,
  );
  // 顺序无关稳定
  assert.equal(deviceMountsFingerprint([mount("docs"), mount("pix")]), fp);
});

// ---------------------------------------------------------------------------
// loadDeviceCompletionEntries
// ---------------------------------------------------------------------------

test("mounts form lists alias candidates filtered by prefix", async () => {
  const { source } = fakeDeviceSource({
    mounts: [mount("pix"), mount("docs"), mount("off", { enabled: false })],
  });
  const all = await loadDeviceCompletionEntries({
    query: { kind: "mounts", rest: "", aliasPrefix: "" },
    source,
  });
  assert.deepEqual(
    all.entries.map((en) => en.path),
    ["docs", "pix"],
  );
  const filtered = await loadDeviceCompletionEntries({
    query: { kind: "mounts", rest: "pi", aliasPrefix: "pi" },
    source,
  });
  assert.deepEqual(filtered.entries.map((en) => en.path), ["pix"]);
});

test("files form resolves alias and lists bounded subtree with mountId", async () => {
  const { calls, source } = fakeDeviceSource({
    mounts: [mount("pix", { id: "mount-7" })],
    tree: {
      DCIM: [e("DCIM/c1.jpg"), e("DCIM/Sub", true), e("DCIM/.hidden"), e("DCIM/..")],
      "DCIM/Sub": [e("DCIM/Sub/x.png")],
    },
  });
  const loaded = await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "pix/DCIM/", alias: "pix", dir: "DCIM", namePrefix: "" },
    source,
  });
  // listFiles 用 mountId（不是 alias）
  assert.ok(calls.listFiles.every(([id]) => id === "mount-7"));
  const paths = loaded.entries.map((en) => en.path);
  // 路径以 alias 开头（guest /device/pix/... 的最后一段由 build 层拼）
  assert.ok(paths.includes("pix/DCIM/c1.jpg"));
  assert.ok(paths.includes("pix/DCIM/Sub/x.png"));
  // 隐藏 / .. 被过滤
  assert.ok(!paths.some((p) => p.includes(".hidden") || p.endsWith("..")));
});

test("unknown alias yields empty result with no fallback scan", async () => {
  const { calls, source } = fakeDeviceSource({
    mounts: [mount("pix")],
    tree: { "": [e("a.txt")] },
  });
  const loaded = await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "nope/x", alias: "nope", dir: "", namePrefix: "x" },
    source,
  });
  assert.equal(loaded.entries.length, 0);
  assert.equal(calls.listFiles.length, 0, "unknown alias 不得列任何目录");
});

test("unavailable device yields empty and still validates every call", async () => {
  const { calls, source } = fakeDeviceSource({ available: false, mounts: [mount("pix")] });
  const loaded = await loadDeviceCompletionEntries({
    query: { kind: "mounts", rest: "", aliasPrefix: "" },
    source,
  });
  assert.equal(loaded.entries.length, 0);
  await loadDeviceCompletionEntries({
    query: { kind: "mounts", rest: "", aliasPrefix: "" },
    source,
  });
  // 每次 query 都重新拉 availability —— 无跨请求假设；不可用时不再列挂载
  assert.equal(calls.availability, 2);
  assert.equal(calls.listMounts, 0);
});

test("bounded traversal: stops at dir cap instead of scanning the phone", async () => {
  // 无限深树：每个目录返回一个子目录
  const source: DeviceIndexSource = {
    async isAvailable() {
      return true;
    },
    async listMounts() {
      return [mount("big", { id: "m1" })];
    },
    async listFiles(_mountId, path) {
      const depth = path === "" ? 0 : path.split("/").length;
      return [e(`${path === "" ? "d0" : `${path}/d${depth}`}`, true)];
    },
  };
  const loaded = await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "big/", alias: "big", dir: "", namePrefix: "" },
    source,
  });
  assert.ok(loaded.entries.length <= DEVICE_COMPLETION_MAX_DIRS);
  assert.ok(loaded.entries.length <= DEVICE_COMPLETION_MAX_ENTRIES);
});

test("entry cap bound is enforced on wide listings", async () => {
  const source: DeviceIndexSource = {
    async isAvailable() {
      return true;
    },
    async listMounts() {
      return [mount("pix", { id: "m1" })];
    },
    async listFiles() {
      return Array.from({ length: 2000 }, (_, i) => e(`f${i}.jpg`));
    },
  };
  const loaded = await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "pix/", alias: "pix", dir: "", namePrefix: "" },
    source,
  });
  assert.ok(loaded.entries.length <= DEVICE_COMPLETION_MAX_ENTRIES);
});

test("a failing directory listing is skipped, not fatal", async () => {
  const source: DeviceIndexSource = {
    async isAvailable() {
      return true;
    },
    async listMounts() {
      return [mount("pix", { id: "m1" })];
    },
    async listFiles(_mountId, path) {
      if (path === "boom") throw new Error("io");
      if (path === "") return [e("boom", true), e("ok.txt")];
      return [];
    },
  };
  const loaded = await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "pix/", alias: "pix", dir: "", namePrefix: "" },
    source,
  });
  assert.deepEqual(
    loaded.entries.map((en) => en.path).sort(),
    ["pix/boom", "pix/ok.txt"],
  );
});

test("abort propagates out of the loader", async () => {
  const controller = new AbortController();
  const source: DeviceIndexSource = {
    async isAvailable() {
      return true;
    },
    async listMounts() {
      return [mount("pix", { id: "m1" })];
    },
    async listFiles(_m, path) {
      if (path === "") {
        controller.abort();
        return [e("a", true)];
      }
      return [];
    },
  };
  await assert.rejects(
    loadDeviceCompletionEntries({
      query: { kind: "files", rest: "pix/", alias: "pix", dir: "", namePrefix: "" },
      source,
      signal: controller.signal,
    }),
    (error: unknown) => error instanceof Error && error.name === "AbortError",
  );
});

// ---------------------------------------------------------------------------
// buildDeviceCompletionItems：label/insertText 绝对 guest 路径
// ---------------------------------------------------------------------------

test("buildDeviceCompletionItems emits /device/alias/path labels", () => {
  const items = buildDeviceCompletionItems(
    [e("pix/DCIM", true), e("pix/readme.md")],
    { query: "/device/pi", start: 0, end: 10 },
  );
  const dir = items.find((i) => i.isDirectory);
  const file = items.find((i) => !i.isDirectory);
  assert.equal(dir?.label, "/device/pix/DCIM");
  assert.equal(dir?.insertText, "@/device/pix/DCIM/");
  assert.equal(file?.label, "/device/pix/readme.md");
  assert.equal(file?.insertText, "@/device/pix/readme.md ");
});

test("device items are sorted by score and capped like workspace", () => {
  const entries = Array.from({ length: 30 }, (_, i) =>
    e(`pix/f${String(i).padStart(2, "0")}.jpg`),
  );
  const items = buildDeviceCompletionItems(entries, {
    query: "/device/pix/f",
    start: 0,
    end: 13,
  });
  assert.equal(items.length, 8);
  for (let i = 1; i < items.length; i += 1) {
    assert.ok(items[i - 1].sortScore >= items[i].sortScore);
  }
});

test("device query never produces /workspace/ labels", () => {
  const items = buildDeviceCompletionItems([e("pix/a.jpg")], {
    query: "/device/pix/a",
    start: 0,
    end: 13,
  });
  assert.ok(items.every((item) => item.label.startsWith("/device/")));
});

// ---------------------------------------------------------------------------
// DeviceCompletionIndexCache：workspace+指纹隔离，撤销后旧条目拒绝复用
// ---------------------------------------------------------------------------

test("device cache is keyed by workspace + mount fingerprint", () => {
  const cache = new DeviceCompletionIndexCache();
  const ws1 = [mount("pix")];
  const fp1 = deviceMountsFingerprint(ws1);

  cache.set("ws1", fp1, "pix\u0000DCIM", [e("pix/DCIM/a.jpg")]);
  assert.equal(cache.get("ws1", fp1, "pix\u0000DCIM")?.length, 1);
  // 不同目录桶隔离
  assert.equal(cache.get("ws1", fp1, "pix\u0000Other"), null);
  // 换 workspace 不命中
  assert.equal(cache.get("ws2", fp1, "pix\u0000DCIM"), null);

  // 场景：授权撤回 → 指纹变 → 旧缓存拒绝
  const revokedFp = deviceMountsFingerprint([]);
  assert.equal(cache.get("ws1", revokedFp, "pix\u0000DCIM"), null);

  // 场景：同 alias 重映射 → 指纹变 → 旧 scope 不可命中，新 set 后旧数据被清
  const remappedFp = deviceMountsFingerprint([mount("pix", { id: "id-NEW", sourceRelativePath: "x" })]);
  cache.set("ws1", remappedFp, "pix\u0000DCIM", [e("pix/DCIM/new.jpg")]);
  assert.equal(cache.get("ws1", remappedFp, "pix\u0000DCIM")?.[0].path, "pix/DCIM/new.jpg");
  assert.equal(cache.get("ws1", fp1, "pix\u0000DCIM"), null, "scope 切换后旧指纹桶清空");

  cache.invalidate();
  assert.equal(cache.get("ws1", remappedFp, "pix\u0000DCIM"), null);
});

test("device cache revalidation flow: fresh mounts gate every reuse", async () => {
  // 模拟两次连续 query：第二次 mounts 快照已撤回 → 指纹不同 → loader 返回空，
  // 且缓存层天然 miss（fingerprint 是 scopeKey 一部分）。
  const { source, calls } = fakeDeviceSource({
    mountsSequence: [[mount("pix")], []], // 第二次调用时挂载被撤
    tree: { DCIM: [e("DCIM/a.jpg")] },
  });
  const first = await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "pix/DCIM/", alias: "pix", dir: "DCIM", namePrefix: "" },
    source,
  });
  assert.equal(first.entries.length, 1);
  const second = await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "pix/DCIM/", alias: "pix", dir: "DCIM", namePrefix: "" },
    source,
  });
  assert.equal(second.entries.length, 0, "撤回后立刻无候选");
  assert.notEqual(second.fingerprint, first.fingerprint);
  assert.ok(calls.listMounts >= 2, "每次 query 都重新拉 mounts 做校验");
});

test("mount identity is used for file listing after same-alias remap", async () => {
  // 同 alias 重映射到新 mountId：listing 必须携带新 id（旧 id 会被服务端拒）
  const { calls, source } = fakeDeviceSource({
    mounts: [mount("pix", { id: "mount-NEW" })],
    tree: { "mount-NEW:": [e("a.jpg")] },
  });
  await loadDeviceCompletionEntries({
    query: { kind: "files", rest: "pix/", alias: "pix", dir: "", namePrefix: "" },
    source,
  });
  assert.ok(calls.listFiles.every(([id]) => id === "mount-NEW"));
});
