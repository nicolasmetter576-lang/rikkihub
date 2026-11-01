import api, { ApiError } from "~/services/api";

/** Same shapes as SkillRoutes.kt. */
export interface SkillSummary {
  name: string;
  description: string;
  compatibility?: string | null;
  builtin: boolean;
  /** A user skill shadowing an APK-shipped one of the same name. */
  overridesBuiltin: boolean;
  enabledByAssistants: number;
}

/** A folder under the user skills directory that cannot be read as a skill (SkillRoutes.kt). */
export interface InvalidSkillDir {
  dirName: string;
  reason: "missing_skill_md" | "missing_name" | "missing_description" | "read_failed";
}

export interface SkillFile {
  path: string;
  size: number;
  text: boolean;
  revision?: string | null;
}

export interface SkillFilesResponse {
  name: string;
  builtin: boolean;
  readOnly: boolean;
  files: SkillFile[];
}

export interface SkillFileContent {
  path: string;
  content: string;
  revision: string;
}

export const SKILL_MD = "SKILL.md";
/** Same bound as the server (SKILL_UPLOAD_MAX_BYTES); checked first so a huge file is not sent. */
export const SKILL_UPLOAD_MAX_BYTES = 16 * 1024 * 1024;

export const skillsQueryKey = ["skills"] as const;
export const invalidSkillDirsQueryKey = ["skill-invalid-dirs"] as const;
export const skillFilesQueryKey = (name: string) => ["skills", name, "files"] as const;

const skillPath = (name: string) => `skills/${encodeURIComponent(name)}`;

export const skillsApi = {
  list: () => api.get<SkillSummary[]>("skills"),
  create: (name: string, description: string) =>
    api.post<{ imported: string[] }>("skills", { name, description }),
  delete: (name: string) => api.delete<{ revealedBuiltin?: boolean }>(skillPath(name)),
  listInvalidDirs: () => api.get<InvalidSkillDir[]>("skill-invalid-dirs"),
  deleteInvalidDir: (dirName: string) =>
    api.delete<void>(`skill-invalid-dirs/${encodeURIComponent(dirName)}`),
  override: (name: string) => api.post<{ imported: string[] }>(`${skillPath(name)}/override`),
  files: (name: string) => api.get<SkillFilesResponse>(`${skillPath(name)}/files`),
  readFile: (name: string, path: string) =>
    api.get<SkillFileContent>(`${skillPath(name)}/files/content`, { searchParams: { path } }),
  /** Same body contract as workspace writes: `overwrite: false` creates only; `expectedRevision` guards edits. */
  writeFile: (
    name: string,
    path: string,
    body: { content: string; overwrite?: boolean; expectedRevision?: string },
  ) =>
    api.put<SkillFileContent>(`${skillPath(name)}/files/content`, body, { searchParams: { path } }),
  deleteFile: (name: string, path: string) =>
    api.delete<void>(`${skillPath(name)}/files`, { searchParams: { path } }),
  importFile: (file: File, overwrite: boolean) => {
    const form = new FormData();
    form.append("file", file, file.name);
    return api.postMultipart<{ imported: string[] }>(`skills/import?overwrite=${overwrite}`, form);
  },
  importGitHub: (url: string, overwrite: boolean) =>
    api.post<{ imported: string[] }>(
      "skills/import-github",
      { url, overwrite },
      { timeout: 120_000 },
    ),
  /** Chat-page picker: replaces only this assistant's enabled skills on the server's latest copy. */
  setAssistantSkills: (assistantId: string, enabledSkills: string[]) =>
    api.put<unknown>(`assistants/${encodeURIComponent(assistantId)}/skills`, { enabledSkills }),
};

export function skillDownloadUrl(name: string, path: string): string {
  return `/api/${skillPath(name)}/files/download?path=${encodeURIComponent(path)}`;
}

export function skillDetailHref(name: string): string {
  return `/settings/skills/${encodeURIComponent(name)}`;
}

export type SkillBadge = "builtin" | "overrides_builtin" | "enabled_by";

export function skillBadges(skill: SkillSummary): SkillBadge[] {
  const badges: SkillBadge[] = [];
  if (skill.builtin) badges.push("builtin");
  if (skill.overridesBuiltin) badges.push("overrides_builtin");
  if (skill.enabledByAssistants > 0) badges.push("enabled_by");
  return badges;
}

export function filterSkills(skills: SkillSummary[], query: string): SkillSummary[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return skills;
  return skills.filter(
    (skill) =>
      skill.name.toLowerCase().includes(needle) || skill.description.toLowerCase().includes(needle),
  );
}

/** Names of existing skills an import would replace, when the server refused it for that reason. */
export function skillImportConflictNames(error: unknown): string[] | null {
  if (!(error instanceof ApiError) || error.code !== 409 || error.errorCode !== "SKILL_EXISTS")
    return null;
  const names = error.details?.names;
  return Array.isArray(names)
    ? names.filter((name): name is string => typeof name === "string")
    : [];
}

/**
 * Runs an import; when same-named skills exist, asks [confirmOverwrite] and retries with overwrite.
 * Returns the imported names, or null when the user declined.
 */
export async function importWithOverwriteConfirm(
  run: (overwrite: boolean) => Promise<{ imported: string[] }>,
  confirmOverwrite: (names: string[]) => boolean | Promise<boolean>,
): Promise<string[] | null> {
  try {
    return (await run(false)).imported;
  } catch (error) {
    const names = skillImportConflictNames(error);
    if (names === null) throw error;
    if (!(await confirmOverwrite(names))) return null;
    return (await run(true)).imported;
  }
}

/** Error code of a failed skill call (stable machine code from the server), if any. */
export function skillErrorCode(error: unknown): string | undefined {
  return error instanceof ApiError ? error.errorCode : undefined;
}

/** SKILL.md first; then, level by level, folders before files and names alphabetically. */
export function sortSkillFiles(files: SkillFile[]): SkillFile[] {
  return [...files].sort((a, b) => {
    if (a.path === SKILL_MD) return -1;
    if (b.path === SKILL_MD) return 1;
    const left = a.path.split("/");
    const right = b.path.split("/");
    for (let index = 0; index < Math.min(left.length, right.length); index++) {
      const leftIsDir = index < left.length - 1;
      const rightIsDir = index < right.length - 1;
      if (left[index] === right[index] && leftIsDir && rightIsDir) continue;
      if (leftIsDir !== rightIsDir) return leftIsDir ? -1 : 1;
      return left[index].localeCompare(right[index]);
    }
    return left.length - right.length;
  });
}

export type SkillFileRow =
  | { kind: "dir"; path: string; name: string; depth: number }
  | { kind: "file"; file: SkillFile; name: string; depth: number };

/** Flat rows for an indented tree: a folder row before the first file inside it. */
export function skillFileRows(files: SkillFile[]): SkillFileRow[] {
  const rows: SkillFileRow[] = [];
  let previous: string[] = [];
  for (const file of sortSkillFiles(files)) {
    const parts = file.path.split("/");
    const dirs = parts.slice(0, -1);
    let common = 0;
    while (common < dirs.length && common < previous.length && dirs[common] === previous[common])
      common++;
    for (let depth = common; depth < dirs.length; depth++) {
      rows.push({
        kind: "dir",
        path: dirs.slice(0, depth + 1).join("/"),
        name: dirs[depth],
        depth,
      });
    }
    rows.push({ kind: "file", file, name: parts[parts.length - 1], depth: dirs.length });
    previous = dirs;
  }
  return rows;
}

/** Client mirror of the server path rule; returns the normalized path or null. */
export function normalizeNewSkillFilePath(raw: string): string | null {
  const trimmed = raw.trim().replace(/\\/g, "/");
  if (!trimmed || trimmed.startsWith("/") || trimmed.length > 1024) return null;
  const parts = trimmed.split("/").filter((part) => part.length > 0);
  if (parts.length === 0 || parts.some((part) => part === "." || part === "..")) return null;
  // eslint-disable-next-line no-control-regex
  if (parts.some((part) => /[\u0000-\u001f\u007f]/.test(part))) return null;
  return parts.join("/");
}

/** Next enabled list for a toggle, keeping the order and dropping names that no longer exist. */
export function toggleSkillName(
  enabled: readonly string[],
  available: ReadonlySet<string>,
  name: string,
  on: boolean,
): string[] {
  const next = enabled.filter((item) => available.has(item) && item !== name);
  if (on) next.push(name);
  return next;
}

/** Unrecognized folders matching the search box (by folder name). */
export function filterInvalidSkillDirs(dirs: InvalidSkillDir[], query: string): InvalidSkillDir[] {
  const needle = query.trim().toLowerCase();
  return needle ? dirs.filter((dir) => dir.dirName.toLowerCase().includes(needle)) : dirs;
}
