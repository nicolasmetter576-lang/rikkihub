// Node-side stub for Vite `?raw` imports (e.g. mermaid standalone bundle used
// by mermaid-renderer). Tests exercise the doc assembly/escaping, not the
// 3.3MB runtime contents — a placeholder keeps module resolution honest.
export function resolve(specifier, context, nextResolve) {
  const isRaw = specifier.includes("?raw");
  if (isRaw) {
    return { url: new URL("./raw-stub.js", import.meta.url).href + "?raw-stub", shortCircuit: true };
  }
  return nextResolve(specifier, context);
}
export function load(url, context, nextLoad) {
  if (url.includes("?raw-stub")) {
    return {
      format: "module",
      source: 'export default "/* RAW STUB: real mermaid bundle injected by Vite */";',
      shortCircuit: true,
    };
  }
  return nextLoad(url, context);
}
