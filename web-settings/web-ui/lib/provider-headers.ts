import type { ProviderProfile } from "../types/settings";

/**
 * Provider custom-header wire entry.
 * `value: null` = preserve the stored value server-side (the projection masks
 * configured values to ""+`valueConfigured:true`; echoing that mask back means
 * "keep the secret", not "clear it"). `""` = explicit clear.
 */
export type ProviderHeader = { name: string; value: string | null; valueConfigured?: boolean };
export type ProviderHeadersError = "json" | "shape" | "name" | "value";
export type ProviderHeadersResult =
  | { ok: true; customHeaders: ProviderHeader[] }
  | { ok: false; error: ProviderHeadersError };

// Names only: never supply sample credentials or header values.
export const COMMON_PROVIDER_HEADER_NAMES = [
  "Authorization", "api-key", "x-api-key", "x-goog-api-key", "OpenAI-Organization", "OpenAI-Project",
  "anthropic-version", "anthropic-beta", "X-Session-ID", "x-opencode-session", "User-Agent",
  "Content-Type", "Accept", "HTTP-Referer", "X-Title",
] as const;

export function providerHeadersDraft(provider: Pick<ProviderProfile, "customHeaders">): string {
  return JSON.stringify(provider.customHeaders ?? [], null, 2);
}

export function parseProviderHeaders(text: string): ProviderHeadersResult {
  let parsed: unknown;
  try {
    parsed = text.trim() === "" ? [] : JSON.parse(text);
  } catch {
    return { ok: false, error: "json" };
  }
  if (!Array.isArray(parsed)) return { ok: false, error: "shape" };
  const customHeaders: ProviderHeader[] = [];
  for (const item of parsed) {
    if (!item || typeof item !== "object" || Array.isArray(item) ||
        typeof item.name !== "string" ||
        (item.value !== null && item.value !== undefined && typeof item.value !== "string")) {
      return { ok: false, error: "shape" };
    }
    // Blank names are ignored by transport. Nonblank names are literal RFC7230 tokens.
    if (!/^[ \t]*$/.test(item.name) && !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(item.name)) {
      return { ok: false, error: "name" };
    }
    const valueConfigured = item.valueConfigured === true;
    // Masked echo (value ""+configured) → preserve (null wire value).
    const wireValue =
      item.value === null || item.value === undefined
        ? null
        : valueConfigured && item.value === ""
          ? null
          : item.value;
    if (wireValue !== null && !/^[\t\x20-\x7e]*$/.test(wireValue)) {
      return { ok: false, error: "value" };
    }
    customHeaders.push(
      wireValue === null
        ? { name: item.name, value: null, valueConfigured: true }
        : { name: item.name, value: wireValue },
    );
  }
  return { ok: true, customHeaders };
}

// The caller combines only this field with its existing provider connection allowlist.
// An explicit [] clears; no models, settings or OAuth credentials enter this payload.
export function providerHeadersPayload(text: string): { customHeaders: ProviderHeader[] } {
  const result = parseProviderHeaders(text);
  if (!result.ok) throw new Error(`provider_headers_${result.error}`);
  return { customHeaders: result.customHeaders };
}
