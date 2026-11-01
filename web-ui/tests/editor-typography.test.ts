/**
 * 工作区编辑器排版（Web 那一套）、字体加载、专注模式接线。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/editor-typography.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  EDITOR_FONT_IDS,
  WEB_EDITOR_TYPOGRAPHY_DEFAULT,
  editorFocusMaxWidth,
  editorFontFamilyCss,
  editorFontSource,
  editorTypographyStyle,
  ensureEditorFont,
  normalizeEditorTypography,
  resetEditorFontCacheForTests,
  sameEditorTypography,
  stepEditorTypographyValue,
} from "../app/overlay/lib/editor-typography";
import { createEditorTheme } from "../app/overlay/components/workspace/workspace-source-editor";

test("defaults keep the Web editor look from before typography settings", () => {
  assert.deepEqual(WEB_EDITOR_TYPOGRAPHY_DEFAULT, { font: "system", fontSize: 14, letterSpacing: 0, lineHeight: 1.625 });
  assert.deepEqual(editorTypographyStyle(WEB_EDITOR_TYPOGRAPHY_DEFAULT), {
    fontFamily: "inherit",
    fontSize: "14px",
    letterSpacing: "normal",
    lineHeight: "1.625",
  });
  assert.ok(createEditorTheme(false), "theme builds with defaults");
});

test("normalize clamps, falls back on unknown fonts and missing/corrupt values", () => {
  assert.deepEqual(normalizeEditorTypography(undefined), WEB_EDITOR_TYPOGRAPHY_DEFAULT);
  assert.deepEqual(normalizeEditorTypography({ font: "comic", fontSize: 99, letterSpacing: -1, lineHeight: "2" }), {
    font: "system",
    fontSize: 32,
    letterSpacing: -0.05,
    lineHeight: 1.625,
  });
  assert.deepEqual(
    normalizeEditorTypography({ font: "lxgw_wenkai_screen", fontSize: 18, letterSpacing: 0.05, lineHeight: 2 }),
    { font: "lxgw_wenkai_screen", fontSize: 18, letterSpacing: 0.05, lineHeight: 2 },
  );
  assert.equal(normalizeEditorTypography({ fontSize: Number.NaN }).fontSize, 14);
  assert.ok(sameEditorTypography({ ...WEB_EDITOR_TYPOGRAPHY_DEFAULT, lineHeight: 1.62500001 }, WEB_EDITOR_TYPOGRAPHY_DEFAULT));
});

test("steps snap to the grid and stay in range", () => {
  assert.equal(stepEditorTypographyValue("fontSize", 14.4), 14);
  assert.equal(stepEditorTypographyValue("fontSize", 40), 32);
  assert.equal(stepEditorTypographyValue("letterSpacing", 0.0349), 0.03);
  assert.equal(stepEditorTypographyValue("lineHeight", 1.625 + 0.05), 1.7);
  assert.equal(stepEditorTypographyValue("lineHeight", 0.2), 1);
});

test("font families: system inherits, downloaded fonts fall back to the page font", () => {
  assert.deepEqual([...EDITOR_FONT_IDS], ["system", "serif", "jetbrains_mono", "harmonyos_sans_sc", "lxgw_wenkai_screen", "custom"]);
  assert.equal(editorFontFamilyCss("system"), "inherit");
  assert.equal(editorFontFamilyCss("serif"), "var(--font-serif)");
  assert.equal(editorFontFamilyCss("lxgw_wenkai_screen"), '"rikkihub-editor-lxgw_wenkai_screen", var(--font-sans)');
  assert.equal(editorFontFamilyCss("jetbrains_mono"), '"rikkihub-editor-jetbrains_mono", var(--font-mono)');
  assert.equal(
    editorFontSource("harmonyos_sans_sc", "abc"),
    'local("HarmonyOS Sans SC"), url("/api/fonts/harmonyos_sans_sc/file?v=abc") format("truetype")',
  );
  assert.equal(editorFontSource("harmonyos_sans_sc", undefined), 'local("HarmonyOS Sans SC")', "this computer's copy still works");
  assert.equal(editorFontSource("custom", undefined), null);
  assert.equal(editorFocusMaxWidth({ ...WEB_EDITOR_TYPOGRAPHY_DEFAULT, fontSize: 16 }), 40 * 16 + 24);
});

test("ensureEditorFont registers each id+version once and retries after a failure", async () => {
  resetEditorFontCacheForTests();
  const created: Array<{ family: string; source: string }> = [];
  let fail = true;
  const env = {
    createFace: (family: string, source: string) => {
      created.push({ family, source });
      return { load: async () => { if (fail) throw new Error("no font"); } };
    },
    add: () => {},
  };
  assert.equal(await ensureEditorFont("system", "v", env), false);
  assert.equal(await ensureEditorFont("custom", undefined, env), false);
  assert.equal(created.length, 0);
  assert.equal(await ensureEditorFont("lxgw_wenkai_screen", "v1", env), false);
  fail = false;
  assert.equal(await ensureEditorFont("lxgw_wenkai_screen", "v1", env), true);
  assert.equal(await ensureEditorFont("lxgw_wenkai_screen", "v1", env), true);
  assert.equal(created.length, 2, "failed load is not cached, success is");
  assert.equal(created[1].family, "rikkihub-editor-lxgw_wenkai_screen");
  assert.equal(await ensureEditorFont("lxgw_wenkai_screen", "v2", env), true);
  assert.equal(created.length, 3, "a new file version loads again");
});

const read = (rel: string) => readFileSync(new URL(rel, import.meta.url), "utf8");

test("focus mode keeps the editor mounted and portals only the file list", () => {
  const page = read("../app/routes/conversations.tsx");
  // Same element, same key in both modes: switching only resizes panels.
  assert.match(page, /<WorkspaceChatPanel\s+key=\{workspaceId\}\s+workspaceId=\{workspaceId\}\s+focus=\{focusActive\}/);
  assert.match(page, /id="focus-list"/);
  assert.match(page, /const FOCUS_LAYOUT = \{ chat: 28, workspace: 50, "focus-list": 22 \}/);
  assert.match(page, /applyPanelLayout\(workspaceSplitRef\.current \? FOCUS_SPLIT_LAYOUT : FOCUS_LAYOUT\)/);
  assert.ok(!/key=\{[^}]*focus/.test(page), "focus state never keys a remount");
  const panel = read("../../web-settings/web-ui/components/workspace/workspace-chat-panel.tsx");
  assert.match(panel, /createPortal\(/);
  assert.match(panel, /centered=\{focus\}/);
  assert.match(panel, /const listBesideEditor = focus \|\| split;/);
  assert.match(panel, /const goToPath = \(next: string\) => listBesideEditor \? setPath\(next\)/, "browsing folders keeps the open file");
  // Both panes are always rendered at fixed keys; neither focus nor split keys a remount.
  assert.match(panel, /key="pane-0"/);
  assert.match(panel, /key="pane-1"/);
  assert.ok(!/key=\{[^}]*(focus|split)/.test(panel), "focus/split state never keys a remount");
  // The editor element lives only in the pane body, shared by every mode.
  assert.equal((panel.match(/<WorkspaceTextEditor/g) ?? []).length, 0);
  const pane = read("../../web-settings/web-ui/components/workspace/workspace-file-pane.tsx");
  assert.equal((pane.match(/<WorkspaceTextEditor/g) ?? []).length, 1);
});

test("side by side: one router guard for both panes, auto-widen in the chat page", () => {
  const panel = read("../../web-settings/web-ui/components/workspace/workspace-chat-panel.tsx");
  // A router supports one blocker: the panel renders one guard over both sessions, panes opt out of their own.
  assert.match(panel, /<WorkspaceEditorRouterGuard entries=\{panes\.map/);
  const pane = read("../../web-settings/web-ui/components/workspace/workspace-file-pane.tsx");
  assert.match(pane, /useWorkspaceEditorSession\([\s\S]*?true,\s*false,\s*\)/);
  const page = read("../app/routes/conversations.tsx");
  assert.match(page, /onSplitChange=\{setWorkspaceSplit\}/);
  assert.match(page, /widenPanelLayout\(layout, "workspace", SPLIT_WORKSPACE_SIZE\)/);
  assert.match(page, /setTimeout\(widenSplitIfNarrow, 400\)/, "re-checked after leaving focus mode");
});

test("typography reaches CodeMirror and the preview; code stays monospace", () => {
  const editor = read("../../web-settings/web-ui/components/workspace/workspace-source-editor.tsx");
  assert.match(editor, /createEditorTheme\(dark, typography, centered\)/);
  assert.match(editor, /requestMeasure\(\)/, "re-measure when a web font finishes loading");
  const text = read("../../web-settings/web-ui/components/workspace/workspace-text-editor.tsx");
  assert.match(text, /useEditorTypographyWithFont\(\)/);
  assert.match(text, /editorTypographyStyle\(typography\)/);
  const styles = read("../../web-settings/web-ui/lib/workspace-markdown-styles.ts");
  assert.match(styles, /"\.md-code, \.md-fence": \{ fontFamily: "ui-monospace/);
});
