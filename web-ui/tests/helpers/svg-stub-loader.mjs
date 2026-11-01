// Focused message dispatch SSR tests do not exercise Vite's SVG React plugin.
export function load(url, context, nextLoad) {
  if (/\.svg(?:\?react)?$/.test(url)) {
    return { format: "module", source: "export default function SvgStub() { return null; }", shortCircuit: true };
  }
  return nextLoad(url, context);
}
