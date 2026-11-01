/**
 * 聊天里模型写的 Markdown/HTML 图片引用工作区文件：`![图](/workspace/out.png)`、`/tmp/plot.png`、
 * `out.png`（相对工作目录）。App 端同一套规则（WorkspaceImageRefs.kt）；这里把它们换成
 * `GET /api/workspaces/{id}/rootfs-file`，由后端按模型看到的路径与「仅此目录」取图。
 * 会话 cookie 随同源 <img> 请求携带，URL 里不放凭据。
 */

export type WorkspaceImageContext = {
  workspaceId: string;
  /** 会话被限定在 FILES 下的这个目录（相对路径）；null = 没限定。 */
  scope: string | null;
  /** 相对引用的基准目录（guest 路径）。 */
  cwd: string;
};

const WORKSPACE_ROOT = "/workspace";

/** 相对引用必须像图片：`![](notes)` 更可能是笔误而不是文件。 */
export const RELATIVE_IMAGE_EXTENSIONS: ReadonlySet<string> = new Set([
  "png",
  "jpg",
  "jpeg",
  "gif",
  "webp",
  "bmp",
  "avif",
  "heic",
  "heif",
  "svg",
]);

const SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*:/;

/** 与 WorkspaceFilesScope.normalize 同规则：返回相对 FILES 的目录，根目录为 null，非法返回 undefined。 */
function normalizeScope(raw: string | null | undefined): string | null | undefined {
  if (raw == null) return null;
  if (raw.includes("\u0000") || raw.includes("\\")) return undefined;
  let value = raw.trim();
  if (value.startsWith("/")) {
    if (value !== WORKSPACE_ROOT && !value.startsWith(`${WORKSPACE_ROOT}/`)) return undefined;
    value = value.slice(WORKSPACE_ROOT.length);
  }
  const segments = value.split("/").filter((segment) => segment !== "" && segment !== ".");
  if (segments.includes("..")) return undefined;
  return segments.length > 0 ? segments.join("/") : null;
}

/**
 * 当前会话里图片引用的解析环境；助手没绑工作区或工作目录不可用时为 null
 * （不可用时不放宽，和模型自己的文件工具一样）。
 */
export function workspaceImageContext(
  workspaceId: string | null | undefined,
  workspaceCwd: string | null | undefined,
  scoped: boolean,
): WorkspaceImageContext | null {
  if (!workspaceId) return null;
  const directory = normalizeScope(workspaceCwd);
  if (directory === undefined) return null;
  return {
    workspaceId,
    scope: scoped ? directory : null,
    cwd: directory ? `${WORKSPACE_ROOT}/${directory}` : WORKSPACE_ROOT,
  };
}

/** UTF-8 百分号解码：连续的合法转义一起解，格式坏的保持原样；`+` 不是空格。 */
export function percentDecode(value: string): string {
  if (!value.includes("%")) return value;
  return value.replace(/(?:%[0-9A-Fa-f]{2})+/g, (run) => {
    try {
      return decodeURIComponent(run);
    } catch {
      return run;
    }
  });
}

/** 把消息里的图片地址换成模型文件工具认得的绝对 guest 路径；不是工作区文件引用则返回 null。 */
export function workspaceImageGuestPath(reference: string, cwd: string): string | null {
  let value = reference.trim();
  if (value.startsWith("<") && value.endsWith(">")) value = value.slice(1, -1).trim();
  if (value === "" || value.startsWith("//") || value.startsWith("#") || SCHEME.test(value)) {
    return null;
  }

  const cut = value.search(/[?#]/);
  if (cut >= 0) value = value.slice(0, cut);
  value = percentDecode(value);
  if (value === "" || value.includes("\u0000") || value.includes("\\")) return null;

  const absolute = value.startsWith("/");
  if (!absolute) {
    const dot = value.lastIndexOf(".");
    const extension = dot >= 0 ? value.slice(dot + 1).toLowerCase() : "";
    if (!RELATIVE_IMAGE_EXTENSIONS.has(extension)) return null;
  }
  const segments = (absolute ? value : `${cwd}/${value}`)
    .split("/")
    .filter((segment) => segment !== "" && segment !== ".");
  if (segments.length === 0 || segments.includes("..")) return null;
  return `/${segments.join("/")}`;
}

export function workspaceImageUrl(context: WorkspaceImageContext, guestPath: string): string {
  const params = new URLSearchParams({ path: guestPath });
  if (context.scope) params.set("scope", context.scope);
  return `/api/workspaces/${encodeURIComponent(context.workspaceId)}/rootfs-file?${params.toString()}`;
}

/** 图片 src 的改写：只动 <img>，解析不出工作区引用的原样放过（远程图、data: 图、站内路径）。 */
export function workspaceImageSrc(context: WorkspaceImageContext, src: string): string {
  // 站内接口（附件、其它工作区预览）自己就是完整地址
  if (src.startsWith("/api/")) return src;
  const guestPath = workspaceImageGuestPath(src, context.cwd);
  return guestPath ? workspaceImageUrl(context, guestPath) : src;
}
