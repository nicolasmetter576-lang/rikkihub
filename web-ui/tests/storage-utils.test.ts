import test from "node:test";
import assert from "node:assert/strict";

import {
  STORAGE_PROTECTION_REASONS,
  collectRenewableLeaseRefs,
  formatBytes,
  formatEpochMillis,
  hasMoreStoragePage,
  isProtectedFile,
  isStorageProtectionReason,
  summarizeCleanupItems,
} from "../../web-settings/web-ui/lib/storage-utils";

test("protection reasons cover the seven wire values", () => {
  assert.deepEqual([...STORAGE_PROTECTION_REASONS].sort(), [
    "check_failed",
    "leased",
    "new_file",
    "referenced_db",
    "referenced_session",
    "referenced_settings",
    "retained",
  ]);
  for (const r of STORAGE_PROTECTION_REASONS) {
    assert.equal(isStorageProtectionReason(r), true);
  }
  assert.equal(isStorageProtectionReason("forced"), false);
});

test("isProtectedFile honors flag or any reason", () => {
  assert.equal(isProtectedFile({ id: 1, protected: true }), true);
  assert.equal(isProtectedFile({ id: 1, protectionReasons: ["leased"] }), true);
  assert.equal(isProtectedFile({ id: 1, protectionReasons: [] }), false);
  assert.equal(isProtectedFile({ id: 1 }), false);
});

test("collectRenewableLeaseRefs keeps local file refs only", () => {
  const urls = [
    "file:///data/user/0/pkg/files/upload/a.png",
    "upload/b.txt",
    "https://example.com/x.png",
    "http://example.com/x.png",
    "data:image/png;base64,AAA",
    "blob:https://app/x",
    "mailto:a@b.c",
    "  ",
    "",
    "file:///data/user/0/pkg/files/upload/a.png", // dup
    "not-a-part",
  ];
  const refs = collectRenewableLeaseRefs(urls);
  assert.deepEqual(refs, [
    "file:///data/user/0/pkg/files/upload/a.png",
    "upload/b.txt",
    "not-a-part", // 裸相对路径按本地文件处理（服务端再严格校验）
  ]);
});

test("collectRenewableLeaseRefs enforces the 200-entry cap", () => {
  const many = Array.from({ length: 300 }, (_, i) => `upload/f${i}.png`);
  assert.equal(collectRenewableLeaseRefs(many).length, 200);
  assert.equal(collectRenewableLeaseRefs(many, 10).length, 10);
});

test("summarizeCleanupItems counts statuses and bytes", () => {
  const s = summarizeCleanupItems([
    { status: "deleted", id: 1, bytesFreed: 10 },
    { status: "deleted", id: 2, bytesFreed: 5 },
    { status: "skipped", id: 3, reasons: ["leased"] },
    { status: "failed", id: 4, error: "disk_delete_failed" },
    { status: "weird", id: 5 },
  ]);
  assert.deepEqual(s, { deleted: 2, skipped: 1, failed: 2, deletedBytes: 15 });
});

test("formatBytes matches 1024-based display", () => {
  assert.equal(formatBytes(0), "0 B");
  assert.equal(formatBytes(512), "512 B");
  assert.equal(formatBytes(1536), "1.5 KB");
  assert.equal(formatBytes(5 * 1024 * 1024), "5.0 MB");
  assert.equal(formatBytes(-1), "0 B");
  assert.equal(formatBytes(Number.NaN), "0 B");
});

test("formatEpochMillis renders local time or dash", () => {
  assert.equal(formatEpochMillis(null), "-");
  assert.equal(formatEpochMillis(0), "-");
  assert.equal(formatEpochMillis(Number.NaN), "-");
  const rendered = formatEpochMillis(new Date(2024, 0, 2, 3, 4).getTime());
  assert.match(rendered, /^2024-01-02 03:04$/);
});

test("hasMoreStoragePage mirrors server pagination", () => {
  assert.equal(hasMoreStoragePage(0, 50, 120), true);
  assert.equal(hasMoreStoragePage(50, 70, 120), false);
  assert.equal(hasMoreStoragePage(0, 0, 0), false);
});
