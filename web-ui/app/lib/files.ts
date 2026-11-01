/**
 * Convert a file reference to a URL the browser loads directly; same-origin
 * requests carry the web session cookie, so no credential goes into the URL.
 * - data: URLs are returned as-is (base64 encoded files)
 * - http/https URLs are returned as-is (external files)
 * - file:// URLs are extracted to relative paths and converted to /api/files/path/{path}
 * - Relative paths are converted to /api/files/path/{path}
 */
export function resolveFileUrl(url: string): string {
  if (url.startsWith("data:") || url.startsWith("http://") || url.startsWith("https://")) {
    return url;
  }

  // Handle file:// protocol URLs from Android
  if (url.startsWith("file://")) {
    // Format: file:///data/user/0/package.name/files/upload/xxx
    const match = url.match(/file:\/\/.*?\/files\/(.+)/);
    // If we can't extract the path, return as-is (will fail to load with error)
    return match?.[1] ? `/api/files/path/${match[1]}` : url;
  }

  // Relative path - convert to API endpoint
  const path = url.startsWith("/") ? url.slice(1) : url;
  return `/api/files/path/${path}`;
}
