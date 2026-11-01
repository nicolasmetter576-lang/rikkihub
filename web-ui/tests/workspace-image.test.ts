/**
 * 聊天里模型写的工作区图片引用（/workspace/out.png、/tmp/plot.png、out.png）：
 * 地址解析（与 App 端 WorkspaceImageRefs 同一批用例）和实渲染共享 Markdown 的改写结果。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/workspace-image.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import Markdown from "../app/components/markdown/markdown";
import { WorkspaceImageProvider } from "../app/components/markdown/workspace-image-context";
import {
  percentDecode,
  workspaceImageContext,
  workspaceImageGuestPath,
  workspaceImageSrc,
  workspaceImageUrl,
  type WorkspaceImageContext,
} from "../app/lib/workspace-image";

const CWD = "/workspace";
const guest = (reference: string, cwd = CWD) => workspaceImageGuestPath(reference, cwd);

test("绝对路径原样使用，路径里的 . 和重复斜杠被整理", () => {
  assert.equal(guest("/workspace/out.png"), "/workspace/out.png");
  assert.equal(guest("/tmp/plot.png"), "/tmp/plot.png");
  assert.equal(guest("/upload/photo.jpg"), "/upload/photo.jpg");
  assert.equal(guest("/workspace//a/./b.png"), "/workspace/a/b.png");
  // 绝对路径不要求图片扩展名（后端会拒绝非图片）
  assert.equal(guest("/workspace/figure"), "/workspace/figure");
});

test("百分号转义按 UTF-8 解码，坏转义和 + 保持原样", () => {
  assert.equal(guest("/workspace/my%20pic.png"), "/workspace/my pic.png");
  assert.equal(guest("/workspace/%E5%9B%BE%20%E8%A1%A8.png"), "/workspace/图 表.png");
  assert.equal(guest("/workspace/a+b.png"), "/workspace/a+b.png");
  assert.equal(guest("/workspace/100%.png"), "/workspace/100%.png");
  assert.equal(guest("/workspace/%zz.png"), "/workspace/%zz.png");
  assert.equal(guest("/workspace/😀.png"), "/workspace/😀.png");
  assert.equal(percentDecode("a%2Fb"), "a/b");
});

test("查询串和片段被丢掉，被编码的 ? 留在文件名里", () => {
  assert.equal(guest("/workspace/out.png?v=2"), "/workspace/out.png");
  assert.equal(guest("/workspace/out.png#top"), "/workspace/out.png");
  assert.equal(guest("/workspace/a%20b.png?x#y"), "/workspace/a b.png");
  assert.equal(guest("/workspace/what%3F.png"), "/workspace/what?.png");
});

test("尖括号目标被去掉", () => {
  assert.equal(guest("</workspace/my pic.png>"), "/workspace/my pic.png");
});

test("相对引用按工作目录解析，且必须像图片", () => {
  assert.equal(guest("out.png"), "/workspace/out.png");
  assert.equal(guest("./img/out.png"), "/workspace/img/out.png");
  assert.equal(guest("out.png", "/workspace/proj"), "/workspace/proj/out.png");
  assert.equal(guest("img/a%20b.PNG", "/workspace/proj"), "/workspace/proj/img/a b.PNG");
  for (const bad of ["notes", "readme.md", "archive.zip", "png"]) {
    assert.equal(guest(bad), null, bad);
  }
});

test("别的地址不是工作区引用", () => {
  for (const other of [
    "https://example.com/a.png",
    "http://example.com/a.png",
    "file:///workspace/a.png",
    "content://media/1",
    "data:image/png;base64,AAAA",
    "//example.com/a.png",
    "#anchor",
    "",
    "   ",
    "<>",
  ]) {
    assert.equal(guest(other), null, JSON.stringify(other));
  }
});

test("越界与控制字符一律拒绝", () => {
  for (const bad of [
    "/workspace/../etc/passwd",
    "../out.png",
    "img/../../out.png",
    "/workspace/%2e%2e/out.png",
    "/workspace/%2E%2E/%2e%2e/out.png",
    "/workspace/a%00.png",
    "/workspace\\..\\a.png",
    "/",
    "/.",
  ]) {
    assert.equal(guest(bad), null, bad);
  }
});

test("解析环境跟随会话的工作目录与「仅此目录」", () => {
  assert.equal(workspaceImageContext(null, "/workspace/a", true), null);
  assert.equal(workspaceImageContext(undefined, null, true), null);

  assert.deepEqual(workspaceImageContext("ws", null, true), {
    workspaceId: "ws",
    scope: null,
    cwd: "/workspace",
  });
  assert.deepEqual(workspaceImageContext("ws", "/workspace/a/b", true), {
    workspaceId: "ws",
    scope: "a/b",
    cwd: "/workspace/a/b",
  });
  assert.deepEqual(workspaceImageContext("ws", "/workspace/a/b", false), {
    workspaceId: "ws",
    scope: null,
    cwd: "/workspace/a/b",
  });
  assert.equal(workspaceImageContext("ws", "a/b", true)?.scope, "a/b");
});

test("工作目录不可用时没有环境，而不是放宽", () => {
  for (const cwd of ["/etc", "/workspace/../x", "a/../b", "a\\b"]) {
    assert.equal(workspaceImageContext("ws", cwd, true), null, cwd);
    assert.equal(workspaceImageContext("ws", cwd, false), null, cwd);
  }
});

const ctx: WorkspaceImageContext = { workspaceId: "W 1", scope: null, cwd: "/workspace" };
const scoped: WorkspaceImageContext = { workspaceId: "W1", scope: "a/b", cwd: "/workspace/a/b" };

test("取图地址带工作区、路径和（有的话）仅此目录", () => {
  assert.equal(
    workspaceImageUrl(ctx, "/workspace/图 表.png"),
    "/api/workspaces/W%201/rootfs-file?path=%2Fworkspace%2F%E5%9B%BE+%E8%A1%A8.png",
  );
  assert.equal(
    workspaceImageUrl(scoped, "/workspace/a/b/x.png"),
    "/api/workspaces/W1/rootfs-file?path=%2Fworkspace%2Fa%2Fb%2Fx.png&scope=a%2Fb",
  );
});

test("图片地址改写只动工作区引用", () => {
  assert.match(workspaceImageSrc(ctx, "/workspace/out.png"), /rootfs-file\?path=%2Fworkspace%2Fout\.png$/);
  assert.match(workspaceImageSrc(ctx, "out.png"), /path=%2Fworkspace%2Fout\.png$/);
  assert.equal(workspaceImageSrc(ctx, "https://example.com/a.png"), "https://example.com/a.png");
  assert.equal(workspaceImageSrc(ctx, "data:image/png;base64,AAAA"), "data:image/png;base64,AAAA");
  // 站内接口本身就是完整地址（附件等）
  assert.equal(workspaceImageSrc(ctx, "/api/files/path/upload/a.png"), "/api/files/path/upload/a.png");
  assert.equal(workspaceImageSrc(ctx, "/workspace/../x.png"), "/workspace/../x.png");
});

function render(content: string, context: WorkspaceImageContext | null): string {
  return renderToStaticMarkup(
    createElement(
      WorkspaceImageProvider,
      { value: context },
      createElement(Markdown, { content }),
    ),
  );
}

function imgSrcs(html: string): string[] {
  return [...html.matchAll(/<img[^>]*\ssrc="([^"]*)"/g)].map((match) => match[1].replaceAll("&amp;", "&"));
}

test("聊天里的 Markdown 图片按工作区取图", () => {
  const html = render("![chart](/workspace/out/chart.png)\n\n![shot](/tmp/shot.png)", scoped);
  assert.deepEqual(imgSrcs(html), [
    "/api/workspaces/W1/rootfs-file?path=%2Fworkspace%2Fout%2Fchart.png&scope=a%2Fb",
    "/api/workspaces/W1/rootfs-file?path=%2Ftmp%2Fshot.png&scope=a%2Fb",
  ]);
});

test("尖括号写法、中文和空格的路径、相对路径", () => {
  const html = render("![a](</workspace/我的 图.png>)\n\n![b](plot.png)", ctx);
  const srcs = imgSrcs(html);
  assert.equal(srcs.length, 2);
  assert.match(srcs[0], /path=%2Fworkspace%2F%E6%88%91%E7%9A%84\+%E5%9B%BE\.png$/);
  assert.match(srcs[1], /path=%2Fworkspace%2Fplot\.png$/);
});

test("聊天里允许的原始 HTML <img> 同样改写，其它标签的 src 不动", () => {
  const html = render('<img src="/workspace/a.png" alt="x">\n\n<video src="/workspace/v.mp4"></video>', ctx);
  assert.deepEqual(imgSrcs(html).map((src) => src.includes("rootfs-file?path=%2Fworkspace%2Fa.png")), [true]);
  assert.ok(html.includes('src="/workspace/v.mp4"'), html);
});

test("远程图、data: 图、链接不受影响", () => {
  const html = render(
    "![r](https://example.com/a.png)\n\n![d](data:image/png;base64,AAAA)\n\n[l](/workspace/out.png)",
    ctx,
  );
  assert.deepEqual(imgSrcs(html), ["https://example.com/a.png", "data:image/png;base64,AAAA"]);
  assert.ok(html.includes('href="/workspace/out.png"'), "链接地址保持原样");
});

test("没有工作区环境时渲染与原来一致", () => {
  const html = render("![chart](/workspace/out.png)", null);
  assert.deepEqual(imgSrcs(html), ["/workspace/out.png"]);
  assert.equal(renderToStaticMarkup(createElement(Markdown, { content: "![chart](/workspace/out.png)" })), html);
});

test("越界路径不会被改写成取图地址", () => {
  const html = render("![x](/workspace/../etc/passwd.png)", ctx);
  assert.ok(!html.includes("rootfs-file"), html);
});
