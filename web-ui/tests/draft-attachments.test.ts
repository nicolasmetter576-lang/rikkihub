import test from "node:test";
import assert from "node:assert/strict";

import {
  collectLocalAttachmentUrls,
  isLocalAttachmentUrl,
} from "../../web-settings/web-ui/lib/draft-attachments";

test("isLocalAttachmentUrl accepts server-local forms", () => {
  assert.ok(isLocalAttachmentUrl("upload/abc.png"));
  assert.ok(isLocalAttachmentUrl("/api/files/path/upload/abc.png"));
  assert.ok(isLocalAttachmentUrl("file:///data/user/0/pkg/files/upload/x.png"));
});

test("isLocalAttachmentUrl rejects remote / inline forms", () => {
  assert.ok(!isLocalAttachmentUrl("https://example.com/x.png"));
  assert.ok(!isLocalAttachmentUrl("http://example.com/x.png"));
  assert.ok(!isLocalAttachmentUrl("data:image/png;base64,AAAA"));
  assert.ok(!isLocalAttachmentUrl("blob:https://x/uuid"));
  assert.ok(!isLocalAttachmentUrl("mailto:a@b.c"));
  assert.ok(!isLocalAttachmentUrl(""));
  assert.ok(!isLocalAttachmentUrl("   "));
});

test("collectLocalAttachmentUrls dedupes, preserves order, skips text parts", () => {
  const parts = [
    { type: "text", text: "see https://x/y in body — not scanned" },
    { type: "image", url: "upload/a.png" },
    { type: "image", url: "https://cdn.example.com/a.png" },
    { type: "document", url: "upload/b.pdf" },
    { type: "image", url: "upload/a.png" },
    { type: "image" },
    { type: "audio", url: "file:///data/user/0/pkg/files/upload/c.m4a" },
  ];
  const urls = collectLocalAttachmentUrls(parts);
  assert.deepEqual(urls, [
    "upload/a.png",
    "upload/b.pdf",
    "file:///data/user/0/pkg/files/upload/c.m4a",
  ]);
});

test("collectLocalAttachmentUrls covers every attachment kind", () => {
  // 图片/视频/音频/文档四种 part 的本地 url 都必须被覆盖（续租不能漏类型）
  const urls = collectLocalAttachmentUrls([
    { type: "image", url: "upload/i.png" },
    { type: "video", url: "upload/v.mp4" },
    { type: "audio", url: "upload/a.m4a" },
    { type: "document", url: "upload/d.pdf" },
    { type: "tool", url: "upload/should-not-matter.png" },
  ]);
  assert.deepEqual(urls, [
    "upload/i.png",
    "upload/v.mp4",
    "upload/a.m4a",
    "upload/d.pdf",
    // 非 text 且带 url 的 part 也收集：宁可多续租也不漏
    "upload/should-not-matter.png",
  ]);
});

test("collectLocalAttachmentUrls boundary inputs", () => {
  assert.deepEqual(collectLocalAttachmentUrls([]), []);
  // 缺 url / url 非字符串 / 空白 url 全部跳过且不抛
  assert.deepEqual(
    collectLocalAttachmentUrls([
      { type: "image" },
      { type: "image", url: undefined },
      { type: "image", url: 123 as unknown as string },
      { type: "document", url: "   " },
    ]),
    [],
  );
  // url 首尾空白归一化为 trim 后的本地路径
  assert.deepEqual(collectLocalAttachmentUrls([{ type: "image", url: " upload/x.png " }]), [
    "upload/x.png",
  ]);
  // 大小写 scheme 也被视为远程
  assert.deepEqual(collectLocalAttachmentUrls([{ type: "image", url: "HTTPS://x/y.png" }]), []);
});
