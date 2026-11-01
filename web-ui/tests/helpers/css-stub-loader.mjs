// node:test 用：把 .css 导入降级为空模块（tsx 不处理 CSS）。
// The overlay is a real-path symlink outside web-ui. Resolve its bare packages
// against the same web-ui dependency root Vite uses (without a second install).
export async function resolve(specifier, context, nextResolve) {
  try { return await nextResolve(specifier, context); }
  catch (error) {
    if (error.code === "ERR_MODULE_NOT_FOUND" && context.parentURL?.includes("/web-settings/web-ui/") && !specifier.startsWith(".") && !specifier.startsWith("/")) {
      return nextResolve(specifier, { ...context, parentURL: new URL("../../package.json", import.meta.url).href });
    }
    throw error;
  }
}
export function load(url, context, nextLoad) {
  if (url.endsWith(".css")) {
    return { format: "module", source: "export default {};", shortCircuit: true };
  }
  return nextLoad(url, context);
}
