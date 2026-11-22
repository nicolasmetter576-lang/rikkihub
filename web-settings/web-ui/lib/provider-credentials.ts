import type { ApiKeyEntry, VertexServiceAccount } from "../types/settings";

export interface ApiKeyDraftItem {
  id: string;
  label: string;
  enabled: boolean;
  key: string;
  keyConfigured: boolean;
  keyPreview: string;
  keyTouched: boolean;
}

export interface VertexServiceAccountDraftItem {
  id: string;
  label: string;
  projectId: string;
  serviceAccountEmail: string;
  location: string;
  enabled: boolean;
  privateKey: string;
  privateKeyConfigured: boolean;
  privateKeyTouched: boolean;
}

const MAX_IMPORTED_SA_COUNT = 200;

export function makeCredentialId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `cred-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

export function maskApiKeyPreview(key: string): string {
  const trimmed = key.trim();
  if (!trimmed) return "";
  return trimmed.length >= 12 ? `••••${trimmed.slice(-4)}` : "••••";
}

export function parseBatchApiKeys(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of raw.split(/[\s,]+/)) {
    const trimmed = part.trim();
    if (!trimmed || seen.has(trimmed)) continue;
    seen.add(trimmed);
    out.push(trimmed);
  }
  return out;
}

export function toApiKeyDraftItems(entries?: ApiKeyEntry[]): ApiKeyDraftItem[] {
  return (entries ?? []).map((item) => ({
    id: item.id || makeCredentialId(),
    label: item.label ?? "",
    enabled: item.enabled !== false,
    key: item.key ?? "",
    keyConfigured: item.keyConfigured === true,
    keyPreview: item.keyPreview ?? maskApiKeyPreview(item.key ?? ""),
    keyTouched: false,
  }));
}

export function toServiceAccountDraftItems(accounts?: VertexServiceAccount[]): VertexServiceAccountDraftItem[] {
  return (accounts ?? []).map((item) => ({
    id: item.id || makeCredentialId(),
    label: item.label ?? "",
    projectId: item.projectId ?? "",
    serviceAccountEmail: item.serviceAccountEmail ?? "",
    location: item.location ?? "",
    enabled: item.enabled !== false,
    privateKey: item.privateKey ?? "",
    privateKeyConfigured: item.privateKeyConfigured === true,
    privateKeyTouched: false,
  }));
}

export function buildApiKeysPayload(items: ApiKeyDraftItem[]): Array<Record<string, unknown>> {
  return items.map((item) => {
    const base = {
      id: item.id,
      label: item.label.trim(),
      enabled: item.enabled,
    };
    if (item.keyTouched) {
      return { ...base, key: item.key.trim() };
    }
    if (item.keyConfigured) {
      return { ...base, key: "", keyConfigured: true };
    }
    return { ...base, key: item.key.trim() };
  });
}

export function buildServiceAccountsPayload(items: VertexServiceAccountDraftItem[]): Array<Record<string, unknown>> {
  return items.map((item) => {
    const base = {
      id: item.id,
      label: item.label.trim(),
      projectId: item.projectId.trim(),
      serviceAccountEmail: item.serviceAccountEmail.trim(),
      location: item.location.trim(),
      enabled: item.enabled,
    };
    if (item.privateKeyTouched) {
      return { ...base, privateKey: item.privateKey.trim() };
    }
    if (item.privateKeyConfigured) {
      return { ...base, privateKey: "", privateKeyConfigured: true };
    }
    return { ...base, privateKey: item.privateKey.trim() };
  });
}

function extractSaObject(obj: unknown): VertexServiceAccountDraftItem | null {
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) return null;
  const record = obj as Record<string, unknown>;
  const projectId =
    typeof record.project_id === "string"
      ? record.project_id.trim()
      : typeof record.projectId === "string"
        ? record.projectId.trim()
        : "";
  const email =
    typeof record.client_email === "string"
      ? record.client_email.trim()
      : typeof record.serviceAccountEmail === "string"
        ? record.serviceAccountEmail.trim()
        : "";
  const privateKey =
    typeof record.private_key === "string"
      ? record.private_key.trim()
      : typeof record.privateKey === "string"
        ? record.privateKey.trim()
        : "";
  if (!projectId || !email || !privateKey) return null;
  const location = typeof record.location === "string" ? record.location.trim() : "";
  const label = typeof record.label === "string" ? record.label.trim() : "";
  return {
    id: makeCredentialId(),
    label,
    projectId,
    serviceAccountEmail: email,
    location,
    enabled: true,
    privateKey,
    privateKeyConfigured: true,
    privateKeyTouched: true,
  };
}

function findJsonObjectEnd(input: string, start: number): number | null {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < input.length; i++) {
    const ch = input[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === "\\") {
        escaped = true;
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
    } else if (ch === "{" || ch === "[") {
      depth++;
    } else if (ch === "}" || ch === "]") {
      depth--;
      if (depth === 0) return i;
      if (depth < 0) return null;
    }
  }
  return null;
}

function scanTopLevelJsonObjects(input: string): Array<[number, number]> | null {
  const ranges: Array<[number, number]> = [];
  let i = 0;
  let commaSinceLast = false;
  while (i < input.length) {
    const ch = input[i];
    if (/\s/.test(ch)) {
      i++;
    } else if (ch === ",") {
      if (ranges.length === 0 || commaSinceLast) return null;
      commaSinceLast = true;
      i++;
    } else if (ch === "{") {
      if (ranges.length >= MAX_IMPORTED_SA_COUNT) break;
      const end = findJsonObjectEnd(input, i);
      if (end === null) return null;
      ranges.push([i, end]);
      commaSinceLast = false;
      i = end + 1;
    } else {
      return null;
    }
  }
  if (commaSinceLast) return null;
  return ranges;
}

/**
 * 解析单条 JSON 对象、JSON 数组 `[{...}]`，或连续/换行分隔的多段 GCP Service Account JSON。
 */
export function parseVertexServiceAccountJsons(raw: string): {
  accounts: VertexServiceAccountDraftItem[];
  skippedCount: number;
} {
  const trimmed = raw.trim();
  if (!trimmed) return { accounts: [], skippedCount: 0 };

  try {
    const direct: unknown = JSON.parse(trimmed);
    if (Array.isArray(direct)) {
      const accounts: VertexServiceAccountDraftItem[] = [];
      let skippedCount = 0;
      for (const el of direct.slice(0, MAX_IMPORTED_SA_COUNT)) {
        const extracted = extractSaObject(el);
        if (extracted) accounts.push(extracted);
        else skippedCount++;
      }
      return { accounts, skippedCount };
    }
    if (direct && typeof direct === "object") {
      const extracted = extractSaObject(direct);
      return extracted
        ? { accounts: [extracted], skippedCount: 0 }
        : { accounts: [], skippedCount: 1 };
    }
    return { accounts: [], skippedCount: 1 };
  } catch {
    // Fall through to multi-object scanner.
  }

  const ranges = scanTopLevelJsonObjects(trimmed);
  if (!ranges || ranges.length === 0) {
    return { accounts: [], skippedCount: 1 };
  }
  const accounts: VertexServiceAccountDraftItem[] = [];
  let skippedCount = 0;
  for (const [start, end] of ranges) {
    try {
      const parsed: unknown = JSON.parse(trimmed.slice(start, end + 1));
      const extracted = extractSaObject(parsed);
      if (extracted) accounts.push(extracted);
      else skippedCount++;
    } catch {
      skippedCount++;
    }
  }
  return { accounts, skippedCount };
}

export function mergeImportedServiceAccounts(
  existing: VertexServiceAccountDraftItem[],
  imported: VertexServiceAccountDraftItem[],
): { merged: VertexServiceAccountDraftItem[]; addedCount: number } {
  const seen = new Set(
    existing.map(
      (item) =>
        `${item.projectId.trim()}|${item.serviceAccountEmail.trim()}|${item.location.trim()}`,
    ),
  );
  const appended: VertexServiceAccountDraftItem[] = [];
  for (const acc of imported) {
    const sig = `${acc.projectId.trim()}|${acc.serviceAccountEmail.trim()}|${acc.location.trim()}`;
    if (seen.has(sig)) continue;
    seen.add(sig);
    appended.push(acc);
  }
  return {
    merged: [...existing, ...appended],
    addedCount: appended.length,
  };
}
