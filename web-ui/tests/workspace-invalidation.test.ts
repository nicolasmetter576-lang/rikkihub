import test from "node:test";
import assert from "node:assert/strict";

import { QueryClient } from "@tanstack/react-query";

import {
  invalidateWorkspaceQueriesEvent,
  workspaceQueryKeys,
  type WorkspaceQueryInvalidator,
} from "../../web-settings/web-ui/lib/workspace";

/** 记录所有 invalidate 调用的最小 fake，断言精确 key。 */
function fakeClient() {
  const calls: (readonly string[])[] = [];
  const client: WorkspaceQueryInvalidator = {
    invalidateQueries(options) {
      calls.push(options.queryKey);
    },
  };
  return { client, calls };
}

test("workspaceQueryKeys: list 独占第二段，detail 在前缀之外", () => {
  assert.deepEqual(workspaceQueryKeys.list, ["workspaces", "list"]);
  assert.deepEqual(workspaceQueryKeys.detail("w1"), ["workspaces", "w1"]);
});

test("malformed payloads only invalidate list, never throw", () => {
  const malformed = [
    undefined,
    null,
    {},
    { workspaceIds: null },
    { workspaceIds: "w1" },
    { workspaceIds: 7 },
  ];
  for (const data of malformed) {
    const { client, calls } = fakeClient();
    invalidateWorkspaceQueriesEvent(client, data as never);
    assert.deepEqual(calls, [workspaceQueryKeys.list]);
  }
});

test("empty ids array still invalidates list only", () => {
  const { client, calls } = fakeClient();
  invalidateWorkspaceQueriesEvent(client, { workspaceIds: [], timestamp: 1 });
  assert.deepEqual(calls, [workspaceQueryKeys.list]);
});

test("event ids invalidate exact detail keys plus list", () => {
  const { client, calls } = fakeClient();
  invalidateWorkspaceQueriesEvent(client, {
    workspaceIds: ["w1", "w2"],
    timestamp: 1,
  });
  assert.deepEqual(calls, [
    workspaceQueryKeys.detail("w1"),
    workspaceQueryKeys.detail("w2"),
    workspaceQueryKeys.list,
  ]);
});

test("non-string / empty-string ids are skipped, list still invalidated", () => {
  const { client, calls } = fakeClient();
  invalidateWorkspaceQueriesEvent(client, {
    workspaceIds: ["w1", "", 42, null, { id: "w3" }] as never,
    timestamp: 1,
  });
  assert.deepEqual(calls, [
    workspaceQueryKeys.detail("w1"),
    workspaceQueryKeys.list,
  ]);
});

test("real QueryClient: event invalidates listed detail + list, leaves other workspaces' caches valid", async () => {
  const client = new QueryClient();
  client.setQueryData(workspaceQueryKeys.list, ["list-data"]);
  client.setQueryData(workspaceQueryKeys.detail("w1"), { id: "w1" });
  client.setQueryData(workspaceQueryKeys.detail("w2"), { id: "w2" });

  invalidateWorkspaceQueriesEvent(client, { workspaceIds: ["w1"], timestamp: 1 });

  assert.equal(client.getQueryState(workspaceQueryKeys.list)?.isInvalidated, true);
  assert.equal(client.getQueryState(workspaceQueryKeys.detail("w1"))?.isInvalidated, true);
  // 其他 workspace 的 detail 缓存不受波及 —— list 独占第二段，不再是 ["workspaces"] 前缀
  assert.equal(client.getQueryState(workspaceQueryKeys.detail("w2"))?.isInvalidated, false);

  await client.invalidateQueries({ queryKey: workspaceQueryKeys.list });
});

test("real QueryClient: 旧前缀 [\"workspaces\"] 会连带全部 detail —— 回归对照", () => {
  const client = new QueryClient();
  client.setQueryData(workspaceQueryKeys.detail("w1"), { id: "w1" });
  client.setQueryData(workspaceQueryKeys.detail("w2"), { id: "w2" });

  // 旧实现 invalidateQueries({ queryKey: workspaceQueryKeys.all }) 是前缀匹配，
  // ["workspaces","w1"]/["workspaces","w2"] 都被命中；新 list key 隔离了这个副作用。
  void client.invalidateQueries({ queryKey: workspaceQueryKeys.all });
  assert.equal(client.getQueryState(workspaceQueryKeys.detail("w2"))?.isInvalidated, true);

  const client2 = new QueryClient();
  client2.setQueryData(workspaceQueryKeys.detail("w2"), { id: "w2" });
  void client2.invalidateQueries({ queryKey: workspaceQueryKeys.list });
  assert.equal(client2.getQueryState(workspaceQueryKeys.detail("w2"))?.isInvalidated, false);
});
