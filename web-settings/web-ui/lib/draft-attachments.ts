/**
 * 草稿附件 URL 提取：给 `useAttachmentDraftLeases`（D 组 hook）准备入参。
 *
 * 判定规则与 `~/lib/files.ts#resolveFileUrl` 的“本地文件”分支保持一致：
 * - data:/blob:/http(s): → 远程或内存数据，不需要服务端 lease
 * - file://（Android 形态）与裸相对路径 → 本机 /api/files/path/ 资源，需要 lease
 */

/** 只依赖结构子集：UIMessagePart 各成员均可直接传入，无需公共 url 类型 */
export interface AttachmentUrlPart {
  type: string;
  url?: string;
}

const REMOTE_URL_PATTERN = /^(?:data:|blob:|https?:|mailto:|tel:)/i;

export function isLocalAttachmentUrl(url: string): boolean {
  const trimmed = url.trim();
  if (trimmed.length === 0) return false;
  if (REMOTE_URL_PATTERN.test(trimmed)) return false;
  return true;
}

/**
 * 收集一组 part 里所有“本机附件”URL：去重、保序、跳过空/远程。
 * 只在草稿（未发送）part 数组上调用；文本正文里的 URL 不扫描。
 */
export function collectLocalAttachmentUrls(
  parts: readonly AttachmentUrlPart[],
): string[] {
  const seen = new Set<string>();
  const urls: string[] = [];
  for (const part of parts) {
    if (part.type === "text") continue;
    const url = part.url;
    if (typeof url !== "string") continue;
    const trimmed = url.trim();
    if (!isLocalAttachmentUrl(trimmed)) continue;
    if (seen.has(trimmed)) continue;
    seen.add(trimmed);
    urls.push(trimmed);
  }
  return urls;
}
