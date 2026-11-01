/**
 * 工作区编辑器字体与排版：Web 那一套设置（px）、字体列表接口、FontFace 加载。
 * App 那一套（sp）只在 App 里改；两套都存在手机的 DisplaySetting 里。
 * @see app/src/main/java/me/rerere/rikkihub/data/datastore/EditorTypography.kt
 * @see web-settings/src/main/java/me/rerere/rikkihub/web/routes/FontRoutes.kt
 */

import api from "~/services/api";

/** 与 EditorFontIds.ALL 一致（Kotlin 测试 EditorTypographyTest 核对顺序与内容）。 */
export const EDITOR_FONT_IDS = [
  "system",
  "serif",
  "jetbrains_mono",
  "harmonyos_sans_sc",
  "lxgw_wenkai_screen",
  "custom",
] as const;
export type EditorFontId = (typeof EDITOR_FONT_IDS)[number];

export interface EditorTypography {
  font: string;
  /** CSS px */
  fontSize: number;
  /** em */
  letterSpacing: number;
  /** 行高倍数 */
  lineHeight: number;
}

export const EDITOR_TYPOGRAPHY_LIMITS = {
  fontSize: { min: 10, max: 32, step: 1 },
  letterSpacing: { min: -0.05, max: 0.3, step: 0.01 },
  lineHeight: { min: 1, max: 3, step: 0.05 },
} as const;

/** 加入排版设置之前 Web 编辑器的样子：页面字体、0.875rem、1.625 倍行高。 */
export const WEB_EDITOR_TYPOGRAPHY_DEFAULT: EditorTypography = {
  font: "system",
  fontSize: 14,
  letterSpacing: 0,
  lineHeight: 1.625,
};

function clampNumber(value: unknown, min: number, max: number, fallback: number): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, value));
}

/** 与 EditorTypography.normalized 同规则：越界夹住、未知字体回默认、缺字段用默认。 */
export function normalizeEditorTypography(raw: unknown): EditorTypography {
  const value = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const d = WEB_EDITOR_TYPOGRAPHY_DEFAULT;
  const l = EDITOR_TYPOGRAPHY_LIMITS;
  return {
    font:
      typeof value.font === "string" && (EDITOR_FONT_IDS as readonly string[]).includes(value.font)
        ? value.font
        : d.font,
    fontSize: clampNumber(value.fontSize, l.fontSize.min, l.fontSize.max, d.fontSize),
    letterSpacing: clampNumber(value.letterSpacing, l.letterSpacing.min, l.letterSpacing.max, d.letterSpacing),
    lineHeight: clampNumber(value.lineHeight, l.lineHeight.min, l.lineHeight.max, d.lineHeight),
  };
}

export function sameEditorTypography(a: EditorTypography, b: EditorTypography): boolean {
  return (
    a.font === b.font &&
    Math.abs(a.fontSize - b.fontSize) < 1e-4 &&
    Math.abs(a.letterSpacing - b.letterSpacing) < 1e-4 &&
    Math.abs(a.lineHeight - b.lineHeight) < 1e-4
  );
}

/** 步进到网格上（滑块与加减按钮共用），并夹在范围内。 */
export function stepEditorTypographyValue(
  key: keyof typeof EDITOR_TYPOGRAPHY_LIMITS,
  value: number,
): number {
  const { min, max, step } = EDITOR_TYPOGRAPHY_LIMITS[key];
  const snapped = Math.round((value - min) / step) * step + min;
  return Math.min(max, Math.max(min, Number(snapped.toFixed(4))));
}

/** 字体本身的族名：电脑上装了就直接用本机的，不从手机传。 */
export const EDITOR_FONT_LOCAL_FAMILIES: Partial<Record<string, string>> = {
  jetbrains_mono: "JetBrains Mono",
  harmonyos_sans_sc: "HarmonyOS Sans SC",
  lxgw_wenkai_screen: "LXGW WenKai Screen",
};

export function editorFontFaceName(id: string): string {
  return `rikkihub-editor-${id}`;
}

/** CSS font-family：system 继承页面字体，其余先用加载的字体，缺字再回退到页面字体。 */
export function editorFontFamilyCss(id: string): string {
  if (id === "system") return "inherit";
  if (id === "serif") return "var(--font-serif)";
  const fallback = id === "jetbrains_mono" ? "var(--font-mono)" : "var(--font-sans)";
  return `"${editorFontFaceName(id)}", ${fallback}`;
}

export function editorFontFileUrl(id: string, version: string): string {
  return `/api/fonts/${encodeURIComponent(id)}/file?v=${encodeURIComponent(version)}`;
}

/** 编辑器（CodeMirror 与预览）用的样式值。 */
export function editorTypographyStyle(t: EditorTypography): {
  fontFamily: string;
  fontSize: string;
  letterSpacing: string;
  lineHeight: string;
} {
  return {
    fontFamily: editorFontFamilyCss(t.font),
    fontSize: `${t.fontSize}px`,
    letterSpacing: t.letterSpacing === 0 ? "normal" : `${t.letterSpacing}em`,
    lineHeight: String(t.lineHeight),
  };
}

/** 专注模式正文最大宽度：约 40 个汉字（含字间距）加左右内边距。 */
export function editorFocusMaxWidth(t: EditorTypography, chars = 40): number {
  return Math.round(chars * t.fontSize * (1 + t.letterSpacing) + 24);
}

// ---- FontFace 加载 ----

interface FontFaceLike {
  load(): Promise<unknown>;
}
interface FontLoaderEnv {
  createFace(family: string, source: string): FontFaceLike;
  add(face: FontFaceLike): void;
}

function browserFontEnv(): FontLoaderEnv | null {
  if (typeof document === "undefined" || typeof FontFace === "undefined" || !document.fonts) return null;
  return {
    createFace: (family, source) => new FontFace(family, source, { display: "swap" }),
    add: (face) => document.fonts.add(face as FontFace),
  };
}

const loadedFaces = new Map<string, Promise<boolean>>();

/** `local(...)` 在前、手机上的文件在后；两者都没有时返回 null。 */
export function editorFontSource(id: string, version: string | undefined): string | null {
  const parts: string[] = [];
  const local = EDITOR_FONT_LOCAL_FAMILIES[id];
  if (local) parts.push(`local("${local}")`);
  if (version) parts.push(`url("${editorFontFileUrl(id, version)}") format("truetype")`);
  return parts.length > 0 ? parts.join(", ") : null;
}

/**
 * 注册并加载字体（同一 id+版本只做一次）。失败（本机没有、手机也没下载）返回 false，
 * 文字照常用回退字体显示。
 */
export function ensureEditorFont(
  id: string,
  version: string | undefined,
  env: FontLoaderEnv | null = browserFontEnv(),
): Promise<boolean> {
  if (id === "system" || id === "serif" || !env) return Promise.resolve(false);
  const source = editorFontSource(id, version);
  if (!source) return Promise.resolve(false);
  const key = `${id}\u0000${version ?? ""}`;
  const existing = loadedFaces.get(key);
  if (existing) return existing;
  const promise = (async () => {
    try {
      const face = env.createFace(editorFontFaceName(id), source);
      env.add(face);
      await face.load();
      return true;
    } catch {
      loadedFaces.delete(key);
      return false;
    }
  })();
  loadedFaces.set(key, promise);
  return promise;
}

export function resetEditorFontCacheForTests(): void {
  loadedFaces.clear();
}

// ---- 接口 ----

export interface FontDownloadState {
  id: string;
  status: "downloading" | "failed";
  bytesRead: number;
  totalBytes: number;
  error?: string | null;
}

export interface DownloadableFontInfo {
  id: string;
  name: string;
  family: string;
  sizeBytes: number;
  installed: boolean;
  license: string;
  licenseUrl: string;
  download?: FontDownloadState | null;
}

export interface EditorFontsResponse {
  fonts: DownloadableFontInfo[];
  /** 字体 id → 文件版本：此刻浏览器能从手机取到文件的字体 */
  files: Record<string, string>;
  customName?: string | null;
}

export const editorFontsQueryKey = ["editor-fonts"] as const;

export const editorFontsApi = {
  list: () => api.get<EditorFontsResponse>("fonts"),
  download: (id: string) => api.post<FontDownloadState>(`fonts/${encodeURIComponent(id)}/download`),
  cancel: (id: string) => api.delete<void>(`fonts/${encodeURIComponent(id)}/download`),
  remove: (id: string) => api.delete<void>(`fonts/${encodeURIComponent(id)}`),
  putTypography: (patch: Partial<EditorTypography>) =>
    api.put<EditorTypography>("display/workspace-editor-typography", patch),
  /** The Web editor's behaviour switches (auto-closing pairs); the App editor keeps its own. */
  putEditor: (patch: { autoPair?: boolean }) => api.put<{ autoPair: boolean }>("display/workspace-editor", patch),
};

export function formatFontSize(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}
