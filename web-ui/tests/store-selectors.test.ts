import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";

/**
 * A store selector that falls back to a fresh `[]` or `{}` (`state.settings?.x ?? []`) returns a new object on every
 * call while the settings are still loading; the store hook then re-renders forever and the page dies with the error
 * boundary — visible when a settings page is opened by its address or reloaded. Fall back outside the selector
 * (`useSettingsStore((s) => s.settings?.x) ?? NONE`) with a constant.
 */
const ROOTS = [path.resolve("app"), path.resolve("../web-settings/web-ui")];
const PATTERN = /use\w*Store\(\s*\(?\s*\w+\s*\)?\s*=>[^;]*?\?\?\s*(\[\]|\{\})\s*\)/;

function sources(dir: string): string[] {
  const out: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name === "overlay" || entry.name.startsWith(".")) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...sources(full));
    else if (/\.(ts|tsx)$/.test(entry.name)) out.push(full);
  }
  return out;
}

test("no store selector falls back to a fresh array or object", () => {
  const offenders: string[] = [];
  for (const root of ROOTS) {
    for (const file of sources(root)) {
      const lines = fs.readFileSync(file, "utf8").split("\n");
      lines.forEach((line, index) => {
        if (PATTERN.test(line)) offenders.push(`${path.relative(process.cwd(), file)}:${index + 1}: ${line.trim()}`);
      });
    }
  }
  assert.deepEqual(offenders, []);
});

test("the pattern catches the shapes it is meant to", () => {
  assert.ok(PATTERN.test("const a = useSettingsStore((state) => state.settings?.assistants ?? []);"));
  assert.ok(PATTERN.test("const a = useSettingsStore((s) => s.settings?.tavern ?? {});"));
  assert.ok(!PATTERN.test("const a = useSettingsStore((state) => state.settings?.assistants) ?? NO_ASSISTANTS;"));
  assert.ok(!PATTERN.test("const a = useSettingsStore((state) => state.settings?.name ?? \"\");"));
});
