// Node resolves the overlay symlink to web-settings (outside web-ui/node_modules).
// For focused SSR tests only, resolve its bare dependencies from the web-ui harness.
export async function resolve(specifier, context, nextResolve) {
  if (context.parentURL?.includes("/web-settings/web-ui/") &&
      !specifier.startsWith(".") && !specifier.startsWith("/") &&
      !specifier.startsWith("~") && !specifier.startsWith("@overlay/") && !specifier.includes(":")) {
    return nextResolve(specifier, { ...context, parentURL: new URL("../../package.json", import.meta.url).href });
  }
  return nextResolve(specifier, context);
}
