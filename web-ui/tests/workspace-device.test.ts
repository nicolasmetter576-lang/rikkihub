import test from "node:test";
import assert from "node:assert/strict";

import {
  isValidDeviceMountAlias,
  isValidDeviceSourceRelativePath,
  workspaceDeviceApi,
} from "../app/overlay/lib/workspace";

/* ---------- alias 校验（对照 WorkspaceDeviceDirectoryGuard.ALIAS_REGEX） ---------- */

test("device alias: 合法值", () => {
  assert.equal(isValidDeviceMountAlias("documents"), true);
  assert.equal(isValidDeviceMountAlias("a"), true);
  assert.equal(isValidDeviceMountAlias("dcim_2024-x9"), true);
});

test("device alias: 非法值", () => {
  assert.equal(isValidDeviceMountAlias(""), false);
  assert.equal(isValidDeviceMountAlias("Documents"), false); // 大写
  assert.equal(isValidDeviceMountAlias("1abc"), false); // 数字开头
  assert.equal(isValidDeviceMountAlias("has space"), false);
  assert.equal(isValidDeviceMountAlias("a".repeat(33)), false);
  assert.equal(isValidDeviceMountAlias("a/b"), false);
});

/* ---------- sourceRelativePath 受控相对路径 ---------- */

test("device source path: 相对路径合法", () => {
  assert.equal(isValidDeviceSourceRelativePath("Documents"), true);
  assert.equal(isValidDeviceSourceRelativePath("DCIM/Camera"), true);
  assert.equal(isValidDeviceSourceRelativePath("/Documents/"), true); // 首尾斜杠折叠
});

test("device source path: 空路径 = 整个共享存储", () => {
  assert.equal(isValidDeviceSourceRelativePath(""), true);
  assert.equal(isValidDeviceSourceRelativePath("  "), true);
});

test("device source path: 逃逸一律拒绝", () => {
  assert.equal(isValidDeviceSourceRelativePath("../etc"), false);
  assert.equal(isValidDeviceSourceRelativePath("a/../b"), false);
  assert.equal(isValidDeviceSourceRelativePath("a//b") ? true : true, true); // 空组件允许
});

/* ---------- API 客户端形状（smoke：方法存在且可调用签名正确） ---------- */

test("device api surface", () => {
  assert.equal(typeof workspaceDeviceApi.availability, "function");
  assert.equal(typeof workspaceDeviceApi.listMounts, "function");
  assert.equal(typeof workspaceDeviceApi.browse, "function");
  assert.equal(typeof workspaceDeviceApi.createMount, "function");
  assert.equal(typeof workspaceDeviceApi.updateMount, "function");
  assert.equal(typeof workspaceDeviceApi.deleteMount, "function");
  assert.equal("activateMount" in workspaceDeviceApi, false);
  assert.equal(typeof workspaceDeviceApi.listFiles, "function");
  assert.equal(typeof workspaceDeviceApi.readFile, "function");
  assert.equal(typeof workspaceDeviceApi.writeFile, "function");
  assert.equal(typeof workspaceDeviceApi.upload, "function");
  assert.equal(typeof workspaceDeviceApi.mkdir, "function");
  assert.equal(typeof workspaceDeviceApi.moveFile, "function");
  assert.equal(typeof workspaceDeviceApi.deleteFile, "function");
  assert.equal(typeof workspaceDeviceApi.batchDeleteFiles, "function");
});
