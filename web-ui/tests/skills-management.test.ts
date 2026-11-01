/**
 * 技能管理：列表标签与过滤、同名导入先确认再覆盖、内置技能只读态、
 * 文件树排序、扩展选择器勾选发出的请求体。
 *
 * 运行：
 *   node --import tsx --import ./tests/helpers/register-css-stub.mjs \
 *     --test tests/skills-management.test.ts
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter, Route, Routes } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { initReactI18next } from "react-i18next";

import api, { ApiError } from "../app/services/api";
import {
  filterInvalidSkillDirs,
  filterSkills,
  importWithOverwriteConfirm,
  normalizeNewSkillFilePath,
  skillBadges,
  skillFileRows,
  skillFilesQueryKey,
  skillImportConflictNames,
  skillsApi,
  skillsQueryKey,
  toggleSkillName,
  type InvalidSkillDir,
  type SkillFilesResponse,
  type SkillSummary,
} from "../app/overlay/lib/skills";
import { InvalidSkillDirsView, SkillListView, skillErrorMessage } from "../app/overlay/routes/settings.skills";
import SkillDetailPage from "../app/overlay/routes/settings.skills.$name";
import { AssistantSkillsField } from "../app/overlay/routes/settings.assistants.$id";
import en from "../app/overlay/locales/en-US/settings.json";
import zh from "../app/overlay/locales/zh-CN/settings.json";
import enInput from "../app/locales/en-US/input.json";
import zhInput from "../app/locales/zh-CN/input.json";

await i18next.use(initReactI18next).init({
  lng: "en-US",
  resources: { "en-US": { settings: en } },
  interpolation: { escapeValue: false },
});

function skill(name: string, patch: Partial<SkillSummary> = {}): SkillSummary {
  return { name, description: `does ${name}`, builtin: false, overridesBuiltin: false, enabledByAssistants: 0, ...patch };
}

test("badges: built-in, overrides built-in, and the enabled-by count only when non-zero", () => {
  assert.deepEqual(skillBadges(skill("a")), []);
  assert.deepEqual(skillBadges(skill("b", { builtin: true })), ["builtin"]);
  assert.deepEqual(skillBadges(skill("c", { overridesBuiltin: true, enabledByAssistants: 2 })), [
    "overrides_builtin",
    "enabled_by",
  ]);
});

test("filter matches name or description, case-insensitively", () => {
  const list = [skill("pdf-tools", { description: "Read PDFs" }), skill("git", { description: "Version control" })];
  assert.equal(filterSkills(list, "  ").length, 2);
  assert.deepEqual(filterSkills(list, "PDF").map((s) => s.name), ["pdf-tools"]);
  assert.deepEqual(filterSkills(list, "control").map((s) => s.name), ["git"]);
});

test("list renders the three labels, an empty state and a no-match state", () => {
  const render = (skills: SkillSummary[], query = "") =>
    renderToStaticMarkup(createElement(MemoryRouter, {}, createElement(SkillListView, { skills, query })));
  const html = render([
    skill("shipped", { builtin: true }),
    skill("mine", { overridesBuiltin: true, enabledByAssistants: 2 }),
  ]);
  assert.match(html, />Built-in</);
  assert.match(html, />Overrides built-in</);
  assert.match(html, />Enabled by 2 assistant\(s\)</);
  assert.match(html, /href="\/settings\/skills\/mine"/);
  assert.match(render([]), /No skills yet/);
  assert.match(render([skill("a")], "zzz"), /No skills match/);
  // Names with URL-significant characters stay one path segment.
  assert.match(render([skill("a b/c")]), /href="\/settings\/skills\/a%20b%2Fc"/);
});

test("unrecognized folders: one row per folder with its reason, filtered by name, nothing when none match", () => {
  const dirs: InvalidSkillDir[] = [
    { dirName: "empty-dir", reason: "missing_skill_md" },
    { dirName: "half-written", reason: "missing_description" },
  ];
  const render = (list: InvalidSkillDir[], query = "") =>
    renderToStaticMarkup(
      createElement(MemoryRouter, {}, createElement(InvalidSkillDirsView, { dirs: list, query, onDelete: () => {} })),
    );
  const html = render(dirs);
  assert.match(html, />Unrecognized folders</);
  assert.match(html, />empty-dir</);
  assert.match(html, />No SKILL\.md file in this folder</);
  assert.match(html, />half-written</);
  assert.match(html, />SKILL\.md has no description field</);
  assert.equal(render([]), "");
  assert.equal(render(dirs, "zzz"), "");
  assert.doesNotMatch(render(dirs, "HALF"), /empty-dir/);
  assert.deepEqual(filterInvalidSkillDirs(dirs, " EMPTY ").map((d) => d.dirName), ["empty-dir"]);
  assert.equal(filterInvalidSkillDirs(dirs, "").length, 2);
});

test("unrecognized-folder texts exist for every reason in both languages", () => {
  const reasons = ["missing_skill_md", "missing_name", "missing_description", "read_failed"];
  for (const locale of [en, zh]) {
    for (const reason of reasons) {
      assert.ok(locale.skills.invalid_dirs[`reason_${reason}` as keyof typeof locale.skills.invalid_dirs]);
    }
    assert.ok(locale.skills.invalid_dirs.title && locale.skills.invalid_dirs.delete_confirm);
    assert.ok(locale.skills.errors.SKILL_DIR_CHANGED);
  }
});

function conflict(names: string[]) {
  return new ApiError("exists", 409, "SKILL_EXISTS", { error: "exists", code: 409, errorCode: "SKILL_EXISTS", names });
}

test("same-name import asks with the conflicting names, then retries with overwrite", async () => {
  const calls: boolean[] = [];
  const asked: string[][] = [];
  const result = await importWithOverwriteConfirm(
    async (overwrite) => {
      calls.push(overwrite);
      if (!overwrite) throw conflict(["a", "b"]);
      return { imported: ["a", "b"] };
    },
    (names) => {
      asked.push(names);
      return true;
    },
  );
  assert.deepEqual(result, ["a", "b"]);
  assert.deepEqual(calls, [false, true]);
  assert.deepEqual(asked, [["a", "b"]]);
});

test("declining the overwrite stops without a second request; other errors propagate", async () => {
  const calls: boolean[] = [];
  const declined = await importWithOverwriteConfirm(
    async (overwrite) => {
      calls.push(overwrite);
      throw conflict(["a"]);
    },
    () => false,
  );
  assert.equal(declined, null);
  assert.deepEqual(calls, [false]);

  await assert.rejects(
    importWithOverwriteConfirm(async () => {
      throw new ApiError("too big", 413, "SKILL_TOO_LARGE");
    }, () => true),
    (error: unknown) => error instanceof ApiError && error.errorCode === "SKILL_TOO_LARGE",
  );
  assert.equal(skillImportConflictNames(new ApiError("x", 409, "FILE_EXISTS")), null);
  assert.deepEqual(skillImportConflictNames(conflict(["z"])), ["z"]);
});

test("server error codes map to localized text, unknown codes keep the server message", () => {
  const t = i18next.getFixedT("en-US", "settings");
  assert.equal(skillErrorMessage(t, new ApiError("raw", 403, "SKILL_READ_ONLY"), "x"), en.skills.errors.SKILL_READ_ONLY);
  assert.equal(skillErrorMessage(t, new ApiError("raw text", 400, "SOMETHING_NEW"), "x"), "raw text");
});

function renderDetail(name: string, files: SkillFilesResponse, summary: SkillSummary) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(skillFilesQueryKey(name), files);
  client.setQueryData(skillsQueryKey, [summary]);
  return renderToStaticMarkup(
    createElement(
      MemoryRouter,
      { initialEntries: [`/settings/skills/${encodeURIComponent(name)}`] },
      createElement(
        QueryClientProvider,
        { client },
        createElement(Routes, {}, createElement(Route, { path: "/settings/skills/:name", element: createElement(SkillDetailPage) })),
      ),
    ),
  );
}

const shippedFiles: SkillFilesResponse = {
  name: "shipped",
  builtin: true,
  readOnly: true,
  files: [
    { path: "scripts/run.py", size: 10, text: true, revision: "r2" },
    { path: "SKILL.md", size: 50, text: true, revision: "r1" },
  ],
};

test("built-in skill is read-only and offers copy-to-edit instead of delete or new file", () => {
  const html = renderDetail("shipped", shippedFiles, skill("shipped", { builtin: true }));
  assert.match(html, /Copy as user skill to edit/);
  assert.match(html, /Built-in skill: read-only/);
  assert.doesNotMatch(html, /Delete skill/);
  assert.doesNotMatch(html, /aria-label="New file"/);
  // SKILL.md is listed first.
  assert.ok(html.indexOf("SKILL.md") < html.indexOf("run.py"));
});

test("user skill can be deleted and gets files; an override says so", () => {
  const html = renderDetail(
    "mine",
    { ...shippedFiles, name: "mine", builtin: false, readOnly: false },
    skill("mine", { overridesBuiltin: true }),
  );
  assert.match(html, /Delete skill/);
  assert.match(html, /aria-label="New file"/);
  assert.match(html, /overrides a built-in skill/);
  assert.doesNotMatch(html, /Copy as user skill to edit/);
});

test("file tree: SKILL.md first, folders before files, folder rows before their contents", () => {
  const rows = skillFileRows([
    { path: "z.txt", size: 1, text: true },
    { path: "lib/b/x.py", size: 1, text: true },
    { path: "lib/a.py", size: 1, text: true },
    { path: "SKILL.md", size: 1, text: true },
  ]);
  assert.deepEqual(
    rows.map((row) => `${row.depth}:${row.kind}:${row.name}`),
    ["0:file:SKILL.md", "0:dir:lib", "1:dir:b", "2:file:x.py", "1:file:a.py", "0:file:z.txt"],
  );
});

test("new file paths mirror the server rule", () => {
  assert.equal(normalizeNewSkillFilePath(" scripts//run.py "), "scripts/run.py");
  assert.equal(normalizeNewSkillFilePath("a\\b.md"), "a/b.md");
  for (const bad of ["", "/abs", "../x", "a/./b", "a/../b", "bad\u0001name"]) {
    assert.equal(normalizeNewSkillFilePath(bad), null, bad);
  }
});

test("assistant page lists skills as checkboxes with the saved selection", () => {
  const html = renderToStaticMarkup(
    createElement(
      MemoryRouter,
      {},
      createElement(AssistantSkillsField, {
        skills: [skill("a"), skill("b")],
        selected: ["b"],
        onChange: () => {},
      }),
    ),
  );
  assert.match(html, /Manage skills/);
  const boxes = html.match(/<button[^>]*role="checkbox"[^>]*>/g) ?? [];
  assert.equal(boxes.length, 2);
  assert.match(boxes[0], /aria-checked="false"/);
  assert.match(boxes[1], /aria-checked="true"/);
});

test("extension picker toggle sends only enabledSkills to the assistant skills endpoint", async () => {
  const available = new Set(["a", "b", "c"]);
  // Names that no longer exist are dropped; order is kept; the toggled name goes last.
  assert.deepEqual(toggleSkillName(["gone", "b"], available, "a", true), ["b", "a"]);
  assert.deepEqual(toggleSkillName(["a", "b"], available, "a", false), ["b"]);
  assert.deepEqual(toggleSkillName(["a"], available, "a", true), ["a"]);

  const calls: Array<{ url: string; body: unknown }> = [];
  const realPut = api.put;
  api.put = (async (url: string, body?: unknown) => {
    calls.push({ url, body });
    return {};
  }) as typeof api.put;
  try {
    await skillsApi.setAssistantSkills("a1/x", ["b", "a"]);
  } finally {
    api.put = realPut;
  }
  assert.deepEqual(calls, [{ url: "assistants/a1%2Fx/skills", body: { enabledSkills: ["b", "a"] } }]);
});

test("skill strings exist in both languages", () => {
  const keys = (value: unknown, prefix = ""): string[] =>
    value && typeof value === "object"
      ? Object.entries(value).flatMap(([key, child]) => keys(child, `${prefix}${key}.`))
      : [prefix];
  assert.deepEqual(keys(zh.skills).sort(), keys(en.skills).sort());
  assert.deepEqual(keys(zh.assistants.skills).sort(), keys(en.assistants.skills).sort());
  assert.equal(zh.nav.skills, "技能");
  for (const locale of [enInput, zhInput]) {
    assert.ok(locale.injection.tab_skills && locale.injection.empty_skills && locale.injection.skills_update_failed);
  }
});
