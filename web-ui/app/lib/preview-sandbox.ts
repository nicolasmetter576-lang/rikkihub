/**
 * Workbench preview of model-written HTML / SVG.
 *
 * The page runs in a sandboxed iframe WITHOUT `allow-same-origin`: it gets an opaque
 * origin, so its requests are cross-site to this app (the SameSite=Strict session
 * cookie is never attached and the server rejects the `null` Origin). Everything a
 * demo page needs still works: CDN scripts and styles, web fonts, remote images,
 * fetch, forms, alert/confirm, links opening a new tab, downloads.
 *
 * An opaque origin has no Web Storage, and pages that touch `localStorage` would
 * throw on first access, so an in-memory stand-in is installed before user content.
 */
export const PREVIEW_IFRAME_SANDBOX =
  "allow-scripts allow-forms allow-modals allow-popups allow-popups-to-escape-sandbox allow-downloads";

const DOCTYPE_PATTERN = /^\s*<!doctype\b[^>]*>/i;

const MEMORY_STORAGE_SCRIPT = `<script>
try { window.localStorage; } catch (_) {
  const memoryStorage = () => {
    const items = new Map();
    return {
      get length() { return items.size; },
      key: (index) => [...items.keys()][index] ?? null,
      getItem: (key) => (items.has(String(key)) ? items.get(String(key)) : null),
      setItem: (key, value) => { items.set(String(key), String(value)); },
      removeItem: (key) => { items.delete(String(key)); },
      clear: () => { items.clear(); },
    };
  };
  for (const name of ["localStorage", "sessionStorage"]) {
    Object.defineProperty(window, name, { value: memoryStorage(), configurable: true });
  }
}
</script>`;

const SVG_PREVIEW_STYLE = `<style>
  html, body { margin: 0; padding: 0; background: #ffffff; }
  body { display: flex; align-items: flex-start; justify-content: center; padding: 16px; box-sizing: border-box; min-height: 100vh; }
  svg { max-width: 100%; height: auto; }
</style>`;

/** Preview document: our prelude first, then the user's markup exactly as written (minus its doctype). */
export function buildPreviewDoc(content: string, headExtras = ""): string {
  return `<!doctype html><head>${MEMORY_STORAGE_SCRIPT}${headExtras}</head>${content.replace(DOCTYPE_PATTERN, "")}`;
}

/**
 * iframe `srcDoc` for a code block, or null when the language has no preview.
 * Mermaid is rendered by `mermaid-renderer` (it bundles its own runtime).
 */
export function buildCodePreviewSrcDoc(normalizedLanguage: string, code: string): string | null {
  switch (normalizedLanguage) {
    case "html":
      return buildPreviewDoc(code);
    case "svg":
      return buildPreviewDoc(code, SVG_PREVIEW_STYLE);
    default:
      return null;
  }
}
