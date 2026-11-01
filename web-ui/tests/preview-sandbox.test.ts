/**
 * Workbench preview documents (web-ui/app/lib/preview-sandbox.ts): opaque-origin
 * sandbox, user markup preserved, Web Storage stand-in installed before user content.
 */
import test from "node:test";
import assert from "node:assert/strict";

import { buildCodePreviewSrcDoc, buildPreviewDoc, PREVIEW_IFRAME_SANDBOX } from "../app/lib/preview-sandbox";

test("sandbox keeps an opaque origin but lets demo pages behave normally", () => {
  const tokens = PREVIEW_IFRAME_SANDBOX.split(/\s+/);
  assert.ok(!tokens.includes("allow-same-origin"), PREVIEW_IFRAME_SANDBOX);
  assert.ok(!tokens.includes("allow-top-navigation"), PREVIEW_IFRAME_SANDBOX);
  for (const needed of ["allow-scripts", "allow-forms", "allow-modals", "allow-popups"]) {
    assert.ok(tokens.includes(needed), `missing ${needed}`);
  }
});

test("previews do not restrict network access", () => {
  const doc = buildPreviewDoc('<script src="https://cdn.example/lib.js"></script>');
  assert.ok(!doc.includes("Content-Security-Policy"), doc.slice(0, 200));
  assert.ok(doc.includes('<script src="https://cdn.example/lib.js"></script>'));
});

test("storage stand-in runs before user content and the user's doctype is dropped", () => {
  const doc = buildPreviewDoc("<!DOCTYPE html><html><body><script>localStorage.setItem('a', '1')</script></body></html>");
  assert.ok(doc.startsWith("<!doctype html><head><script>"));
  assert.equal(doc.toLowerCase().split("<!doctype").length - 1, 1);
  assert.ok(doc.indexOf("window.localStorage") < doc.indexOf("<html>"));
});

test("storage stand-in behaves like Web Storage when the real one is unavailable", () => {
  const stand = (doc: string) => doc.slice(doc.indexOf("<script>") + 8, doc.indexOf("</script>"));
  const window: Record<string, unknown> = {};
  Object.defineProperty(window, "localStorage", {
    configurable: true,
    get() { throw new Error("SecurityError"); },
  });
  new Function("window", stand(buildPreviewDoc("")))(window);
  const storage = window.localStorage as Storage;
  storage.setItem("k", 1 as unknown as string);
  assert.equal(storage.getItem("k"), "1");
  assert.equal(storage.length, 1);
  assert.equal(storage.key(0), "k");
  storage.removeItem("k");
  assert.equal(storage.getItem("k"), null);
  assert.ok(window.sessionStorage);
});

test("code previews exist for html and svg only", () => {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>';
  assert.ok(buildCodePreviewSrcDoc("html", "<p>x</p>")?.includes("<p>x</p>"));
  assert.ok(buildCodePreviewSrcDoc("svg", svg)?.includes(svg));
  assert.equal(buildCodePreviewSrcDoc("mermaid", "graph TD\nA-->B"), null);
  assert.equal(buildCodePreviewSrcDoc("python", "print(1)"), null);
});
