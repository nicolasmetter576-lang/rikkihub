const COMPLETE_JSON_LITERAL = /^(true|false|null|-?\d+(\.\d+)?([eE][+-]?\d+)?)$/;

/**
 * Display-only parse of tool arguments that are still streaming (a JSON prefix), the same as the
 * App's `Tool.inputAsPartialJson`. Never use it to decide anything about executing a tool.
 */
export function parsePartialJsonObject(input: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(closePartialJson(input.trim() ? input : "{}"));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

/**
 * Turns a JSON prefix into a document that parses: closes the open string and containers and drops a
 * dangling key, colon, comma, escape, half `\u` escape or literal (port of the App's `closePartialJson`).
 */
export function closePartialJson(prefix: string): string {
  const closers: string[] = [];
  let inString = false;
  let escaped = false;
  let stringStart = 0;
  let expectKey = false;
  // Start of an object key whose value has not begun yet; such a key is dropped.
  let keyStart = -1;
  let sawColon = false;
  for (let i = 0; i < prefix.length; i++) {
    const c = prefix[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (keyStart >= 0 && sawColon && !/\s/.test(c) && c !== ":") keyStart = -1;
    switch (c) {
      case '"':
        inString = true;
        stringStart = i;
        if (expectKey) {
          keyStart = i;
          sawColon = false;
        }
        expectKey = false;
        break;
      case "{":
        closers.push("}");
        expectKey = true;
        break;
      case "[":
        closers.push("]");
        expectKey = false;
        break;
      case "}":
      case "]":
        closers.pop();
        expectKey = false;
        keyStart = -1;
        break;
      case ",":
        expectKey = closers[closers.length - 1] === "}";
        keyStart = -1;
        break;
      case ":":
        if (keyStart >= 0) sawColon = true;
        break;
    }
  }
  let out = prefix;
  if (inString && keyStart !== stringStart) {
    if (escaped) out = out.slice(0, -1);
    const unicode = out.lastIndexOf("\\u");
    if (unicode >= 0 && out.length - unicode < 6 && /^[A-Za-z0-9]*$/.test(out.slice(unicode + 2))) {
      out = out.slice(0, unicode);
    }
    out += '"';
  } else if (keyStart >= 0) {
    out = out.slice(0, keyStart);
  } else if (!inString) {
    const literal = /[A-Za-z0-9.+-]+$/.exec(out)?.[0];
    if (literal && !COMPLETE_JSON_LITERAL.test(literal)) out = out.slice(0, out.length - literal.length);
  }
  out = out.replace(/\s+$/, "");
  if (out.endsWith(",")) out = out.slice(0, -1);
  if (out.endsWith(":")) out += "null";
  return out + closers.reverse().join("");
}
